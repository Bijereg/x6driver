package dev.sbelx.x6driver.service;
import com.google.gson.*;
import dev.sbelx.x6driver.macos.MacBluetoothTransport;
import dev.sbelx.x6driver.transport.PrinterTransport;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.channels.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import jdk.net.ExtendedSocketOptions;

public final class ServiceMain {
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("server-deadlines").factory());
    public static void main(String[] args)throws Exception{
        Path socket=ServicePaths.socket(),directory=socket.getParent();
        if(Files.exists(directory)&&(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||((Number)Files.getAttribute(directory,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()!=ServicePaths.uid()))throw new IOException("Unsafe service socket directory");
        Files.createDirectories(directory);Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        Files.createDirectories(ServicePaths.data());Files.setPosixFilePermissions(ServicePaths.data(),PosixFilePermissions.fromString("rwx------"));
        try(var lockFile=FileChannel.open(directory.resolve("service.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=lockFile.tryLock()){
            if(lock==null)return;
            Files.deleteIfExists(socket);
            String bridge=System.getProperty("x6driver.bridge");if(bridge==null)throw new IllegalArgumentException("Missing x6driver.bridge");
            try(var engine=new ServiceEngine(ServicePaths.data(),()->{try{return new MacBluetoothTransport(Path.of(bridge));}catch(IOException e){throw new UncheckedIOException(e);}});
                var server=ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                var clients=Executors.newVirtualThreadPerTaskExecutor()){
                server.bind(UnixDomainSocketAddress.of(socket));Files.setPosixFilePermissions(socket,PosixFilePermissions.fromString("rw-------"));
                Runtime.getRuntime().addShutdownHook(new Thread(()->{try{server.close();engine.close();Files.deleteIfExists(socket);}catch(Exception ignored){}}));
                System.out.println("X6 service ready");
                while(server.isOpen()){SocketChannel client=server.accept();clients.submit(()->handle(client,engine));}
            }finally{Files.deleteIfExists(socket);}
        }
    }
    private static void handle(SocketChannel client,ServiceEngine engine){
        Path upload=null;
        var deadline=DEADLINES.schedule(()->{try{client.close();}catch(IOException ignored){}},45,TimeUnit.SECONDS);
        try(client){
            var peer=client.getOption(ExtendedSocketOptions.SO_PEERCRED);
            if(!peer.user().getName().equals(System.getProperty("user.name"))&&!peer.user().getName().equals("root"))throw new IOException("IPC peer denied");
            var in=new DataInputStream(Channels.newInputStream(client));var out=new DataOutputStream(Channels.newOutputStream(client));
            try{
                JsonObject q=Wire.header(in);if(q.get("v").getAsInt()!=1)throw new IOException("Unsupported IPC version");
                long bytes=in.readLong();if(bytes<0||bytes>Wire.MAX_PAYLOAD)throw new IOException("Payload exceeds 128 MB");
                if(bytes>0){
                    upload=Files.createTempFile(ServicePaths.data().resolve("inputs"),".upload-",".tmp");
                    try(var file=Files.newOutputStream(upload)){byte[] buffer=new byte[65536];while(bytes>0){int n=in.read(buffer,0,(int)Math.min(bytes,buffer.length));if(n<0)throw new EOFException();file.write(buffer,0,n);bytes-=n;}}
                }
                String op=q.get("op").getAsString();JsonObject response=new JsonObject();
                switch(op){
                    case "status" -> response=engine.snapshot();
                    case "scan" -> engine.scan();
                    case "connect" -> engine.connect(Wire.JSON.fromJson(q.get("device"),PrinterTransport.Device.class));
                    case "select" -> engine.select(Wire.JSON.fromJson(q.get("device"),PrinterTransport.Device.class));
                    case "disconnect" -> engine.disconnect();
                    case "inspect" -> engine.inspect();
                    case "cancel" -> engine.cancel(q.get("id").getAsString());
                    case "cancel-key" -> engine.cancelKey(q.get("key").getAsString());
                    case "job" -> response=Wire.JSON.toJsonTree(engine.get(q.get("id").getAsString())).getAsJsonObject();
                    case "submit" -> {
                        if(upload==null)throw new IOException("Missing document");
                        String source=q.get("source").getAsString();if(!source.equals("cups")&&!source.equals("client"))throw new IOException("Unknown source");
                        JobSettings settings=source.equals("cups")?JobSettings.cups(q.get("options").getAsString(),q.get("copies").getAsInt()):Wire.JSON.fromJson(q.get("settings"),JobSettings.class);
                        response=Wire.JSON.toJsonTree(engine.accept(q.get("key").getAsString(),q.get("title").getAsString(),source,settings,upload)).getAsJsonObject();
                    }
                    default -> throw new IOException("Unknown operation");
                }
                Wire.header(out,response);out.flush();
            }catch(Exception e){JsonObject error=new JsonObject();error.addProperty("error",e.getMessage()==null?e.toString():e.getMessage());Wire.header(out,error);out.flush();}
        }catch(Exception e){System.err.println("IPC: "+e.getMessage());}
        finally{deadline.cancel(false);if(upload!=null)try{Files.deleteIfExists(upload);}catch(IOException ignored){}}
    }
}
