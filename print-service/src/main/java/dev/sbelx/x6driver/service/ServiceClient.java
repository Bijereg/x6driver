package dev.sbelx.x6driver.service;
import com.google.gson.*;
import dev.sbelx.x6driver.core.*;
import dev.sbelx.x6driver.protocol.*;
import dev.sbelx.x6driver.transport.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Client facade. Closing it never closes the service or cancels a print job. */
public final class ServiceClient implements PrinterTransport,PrintManager {
    private final ScheduledExecutorService poller=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("service-status").factory());
    private final CopyOnWriteArrayList<Consumer<Event>> events=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<PrintJob>> listeners=new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String,PrintJob> jobs=new ConcurrentHashMap<>();
    private final Map<String,String> lastJobs=new ConcurrentHashMap<>();
    private volatile List<HistoryStore.Entry> history=List.of();
    private volatile Connection connection;
    private volatile String firmware="",state="Service not running";
    private volatile int printerStatus=-1;
    private volatile boolean available;
    public ServiceClient(){poller.scheduleWithFixedDelay(this::poll,0,1,TimeUnit.SECONDS);}
    private void event(Event e){for(var listener:events)try{listener.accept(e);}catch(RuntimeException ignored){}}
    private void poll(){
        try{
            JsonObject s=Wire.call(Wire.request("status"),null);boolean wasAvailable=available;available=true;if(!wasAvailable)event(new Event("service","running",null));
            Connection next=Wire.JSON.fromJson(s.get("connection"),Connection.class);
            if(!Objects.equals(connection,next)){connection=next;event(new Event(next==null?"disconnected":"connected",next==null?"":next.name(),null));}
            String nextState=s.get("bluetoothState").getAsString();if(!state.equals(nextState)){state=nextState;event(new Event("state",state,null));}
            firmware=s.get("firmware").getAsString();printerStatus=s.get("printerStatus").getAsInt();
            for(JsonElement d:s.getAsJsonArray("devices"))event(new Event("device","",Wire.JSON.fromJson(d,Device.class)));
            List<HistoryStore.Entry> rows=new ArrayList<>();
            for(JsonElement value:s.getAsJsonArray("jobs")){
                ServiceEngine.Entry e=Wire.JSON.fromJson(value,ServiceEngine.Entry.class);
                rows.add(new HistoryStore.Entry(e.id,e.title,"X6h · "+e.source,e.state,e.path,e.time,e.message));
                PrintJob job=jobs.computeIfAbsent(e.id,id->{Document doc=new Document();doc.title=e.title;return new PrintJob(id,doc,PrinterProfile.match("X6h").orElseThrow(),new ProtocolEncoder.Options(e.settings.mode()==dev.sbelx.x6driver.imaging.Rasterizer.Mode.PHOTO,e.settings.density(),false),e.settings.mode(),e.settings.threshold(),e.settings.copies(),e.settings.gamma());});
                String signature=e.state+":"+e.progress+":"+e.message;
                job.state=PrintJob.State.valueOf(e.state.equals("WAITING_CONNECTION")?"QUEUED":e.state);job.progress=e.progress;job.message=e.message;
                if(!signature.equals(lastJobs.put(e.id,signature)))for(var listener:listeners)listener.accept(job);
            }
            history=List.copyOf(rows);
        }catch(Exception e){boolean wasAvailable=available;available=false;if(wasAvailable)event(new Event("service","unavailable",null));if(connection!=null){connection=null;event(new Event("disconnected","Service unavailable",null));}}
    }
    private CompletableFuture<JsonObject> command(String op,Consumer<JsonObject> fill){return CompletableFuture.supplyAsync(()->{try{JsonObject q=Wire.request(op);fill.accept(q);return Wire.call(q,null);}catch(Exception e){throw new CompletionException(e);}});}
    public boolean available(){return available;}
    public List<HistoryStore.Entry> history(){return history;}
    public void onEvent(Consumer<Event> listener){events.add(listener);}
    public void onData(Consumer<byte[]> listener){}
    public CompletableFuture<Void> scan(){return command("scan",q->{}).thenApply(q->null);}
    public CompletableFuture<Connection> connect(Device device){return command("connect",q->q.add("device",Wire.JSON.toJsonTree(device))).thenApply(q->{poll();return connection;});}
    public CompletableFuture<Void> select(Device device){return command("select",q->q.add("device",Wire.JSON.toJsonTree(device))).thenApply(q->null);}
    public CompletableFuture<Void> write(byte[] bytes){return CompletableFuture.failedFuture(new UnsupportedOperationException("Raw writes belong to the print service"));}
    public CompletableFuture<Void> disconnect(){return command("disconnect",q->{}).thenApply(q->{connection=null;return null;});}
    public boolean connected(){return connection!=null;}
    public Connection connection(){return connection;}
    public void onJob(Consumer<PrintJob> listener){listeners.add(listener);}
    public Collection<PrintJob> jobs(){return List.copyOf(jobs.values());}
    public String firmware(){return firmware;}
    public int status(){return printerStatus;}
    public void inspect(){command("inspect",q->{});}
    public void cancel(String id){command("cancel",q->q.addProperty("id",id)).exceptionally(e->{event(new Event("error",e.getMessage(),null));return null;});}
    public CompletableFuture<PrintJob> submit(PrintJob job){
        jobs.put(job.id,job);
        return CompletableFuture.supplyAsync(()->{
            Path tmp=null;
            try{
                tmp=Files.createTempFile("x6driver-submit-",".x6driver");DocumentIO.save(job.document,tmp);
                JsonObject q=Wire.request("submit");q.addProperty("source","client");q.addProperty("key","client:"+job.id);q.addProperty("title",job.document.title);
                q.add("settings",Wire.JSON.toJsonTree(new JobSettings(job.mode,job.options.density(),job.gamma,job.threshold,job.copies,"",3)));
                Wire.call(q,tmp);return job;
            }catch(Exception e){jobs.remove(job.id);throw new CompletionException(e);}
            finally{if(tmp!=null)try{Files.deleteIfExists(tmp);}catch(Exception ignored){}}
        });
    }
    public void close(){poller.shutdownNow();}
}
