package dev.sbelx.x6driver.macos;
import dev.sbelx.x6driver.transport.PrinterTransport;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class MacBluetoothTransport implements PrinterTransport {
    private final Process process;
    private final BufferedWriter input;
    private final ConcurrentHashMap<String,CompletableFuture<JsonObject>> pending=new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Event>> events=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<byte[]>> dataListeners=new CopyOnWriteArrayList<>();
    private volatile Connection connection;
    private volatile boolean closed;
    public MacBluetoothTransport(Path executable)throws IOException{
        process=new ProcessBuilder(executable.toAbsolutePath().toString()).start();input=process.outputWriter(StandardCharsets.UTF_8);
        Thread.ofVirtual().name("bluetooth-events").start(()->{
            try(var reader=process.inputReader(StandardCharsets.UTF_8)){String line;while((line=reader.readLine())!=null){try{handle(JsonParser.parseString(line).getAsJsonObject());}catch(RuntimeException e){event("error","Bluetooth protocol: "+e.getMessage(),null);}}}
            catch(IOException e){if(!closed)event("error",e.getMessage(),null);}
            finally{connection=null;var ex=new IOException("Bluetooth helper stopped");pending.values().forEach(f->f.completeExceptionally(ex));pending.clear();if(!closed)event("disconnected",ex.getMessage(),null);}
        });
        Thread.ofVirtual().name("bluetooth-stderr").start(()->{try(var reader=process.errorReader()){String line;while((line=reader.readLine())!=null)System.err.println("Bluetooth: "+line);}catch(IOException ignored){}});
    }
    private static String str(JsonObject o,String key){return o.has(key)?o.get(key).getAsString():"";}
    private void handle(JsonObject o){
        if(!o.has("v")||o.get("v").getAsInt()!=1)throw new IllegalArgumentException("Unsupported helper protocol");
        String type=str(o,"event"),id=str(o,"id");
        if(type.equals("connected")){connection=new Connection(str(o,"deviceId"),str(o,"name"),o.get("maxWrite").getAsInt(),str(o,"writer"));}
        if(type.equals("disconnected"))connection=null;
        CompletableFuture<JsonObject> future=pending.get(id);
        if(future!=null&&(type.equals("ack")||type.equals("connected")||type.equals("error"))){pending.remove(id);if(type.equals("error"))future.completeExceptionally(new IOException(str(o,"message")));else future.complete(o);}
        switch(type){
            case "device" -> {List<String> services=new ArrayList<>();if(o.has("services"))o.getAsJsonArray("services").forEach(s->services.add(s.getAsString()));event(type,"",new Device(str(o,"deviceId"),str(o,"name"),o.has("rssi")?o.get("rssi").getAsInt():0,services,str(o,"transport")));}
            case "notification" -> {byte[] bytes=Base64.getDecoder().decode(str(o,"data"));for(var listener:dataListeners)listener.accept(bytes);}
            case "state" -> event(type,str(o,"state"),null);
            case "scanning" -> event(type,o.get("active").getAsString(),null);
            case "disconnected","error" -> event(type,str(o,"message"),null);
            case "connected" -> event(type,str(o,"name"),null);
            default -> {}
        }
    }
    private void event(String type,String message,Device device){for(var listener:events){try{listener.accept(new Event(type,message,device));}catch(RuntimeException e){System.err.println(e.getMessage());}}}
    private CompletableFuture<JsonObject> command(String op,Map<String,Object> args){
        if(closed)return CompletableFuture.failedFuture(new IOException("Transport is closed"));
        String id=UUID.randomUUID().toString();JsonObject o=new Gson().toJsonTree(args).getAsJsonObject();o.addProperty("op",op);o.addProperty("id",id);o.addProperty("v",1);
        CompletableFuture<JsonObject> future=new CompletableFuture<>();pending.put(id,future);
        try{synchronized(input){input.write(o.toString());input.newLine();input.flush();}}catch(IOException e){pending.remove(id);future.completeExceptionally(e);}
        return future.orTimeout(op.equals("connect")?25:10,TimeUnit.SECONDS).whenComplete((v,e)->pending.remove(id));
    }
    @Override public void onEvent(Consumer<Event> listener){events.add(listener);}
    @Override public void onData(Consumer<byte[]> listener){dataListeners.add(listener);}
    @Override public CompletableFuture<Void> scan(){return command("scan",Map.of("seconds",15)).thenApply(x->null);}
    @Override public CompletableFuture<Connection> connect(Device d){return command(d.kind().equals("spp")?"connectSPP":"connect",Map.of("deviceId",d.id())).thenApply(x->connection);}
    @Override public CompletableFuture<Void> write(byte[] bytes){return command("write",Map.of("data",Base64.getEncoder().encodeToString(bytes))).thenApply(x->null);}
    @Override public CompletableFuture<Void> disconnect(){return command("disconnect",Map.of()).thenApply(x->{connection=null;return null;});}
    @Override public boolean connected(){return connection!=null&&process.isAlive();}
    @Override public Connection connection(){return connection;}
    @Override public void close(){if(closed)return;try{command("quit",Map.of());}finally{closed=true;connection=null;process.destroy();}}
}
