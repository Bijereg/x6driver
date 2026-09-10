package dev.sbelx.x6driver.service;

import com.google.gson.*;
import dev.sbelx.x6driver.core.*;
import dev.sbelx.x6driver.protocol.*;
import dev.sbelx.x6driver.transport.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** One durable FIFO and one Bluetooth owner for both client and CUPS jobs. */
public final class ServiceEngine implements AutoCloseable {
    public static final class Entry {
        public String id,key,title,source,path,time,state="QUEUED",message="";
        public JobSettings settings;
        public boolean started;
        public double progress;
    }
    private final Path directory;
    private final HistoryStore history;
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private final Set<String> cancelledKeys=new HashSet<>();
    private final Map<String,Long> leases=new HashMap<>();
    private final Supplier<PrinterTransport> factory;
    private final Object linkGate=new Object();
    private final CopyOnWriteArrayList<PrinterTransport.Device> devices=new CopyOnWriteArrayList<>();
    private final Thread worker;
    private volatile boolean closed;
    private volatile PrinterTransport transport;
    private volatile PrintQueue queue;
    private volatile PrinterTransport.Device selected;
    private volatile String bluetoothState="unknown",activeId;
    private long lastConnect;
    public ServiceEngine(Path directory,Supplier<PrinterTransport> factory)throws Exception{
        this.directory=directory;this.factory=factory;Files.createDirectories(directory.resolve("inputs"));Files.createDirectories(directory.resolve("ledger"));
        history=new HistoryStore(directory.resolve("history"));
        Path cancellations=directory.resolve("cancelled-keys.json");if(Files.exists(cancellations))for(var key:JsonParser.parseString(Files.readString(cancellations)).getAsJsonArray())cancelledKeys.add(key.getAsString());
        Path config=directory.resolve("printer.json");if(Files.exists(config))selected=Wire.JSON.fromJson(Files.readString(config),PrinterTransport.Device.class);
        try(var files=Files.list(directory.resolve("ledger"))){
            for(Path file:files.filter(p->p.toString().endsWith(".json")).sorted().toList()){
                Entry e=Wire.JSON.fromJson(Files.readString(file),Entry.class);
                if(Set.of("SENDING","WAITING").contains(e.state)){e.state="INTERRUPTED";e.message="Service stopped; manual retry required";save(e);}
                entries.put(e.id,e);
            }
        }
        worker=Thread.ofPlatform().daemon().name("service-print-fifo").start(this::work);
    }
    private synchronized PrinterTransport transport(){
        if(transport==null){
            transport=factory.get();queue=new PrintQueue(transport,history);
            transport.onEvent(event->{
                if(event.type().equals("device")&&PrinterProfile.match(event.device().name()).filter(PrinterProfile::supported).isPresent()){
                    devices.removeIf(d->d.id().equals(event.device().id()));devices.add(event.device());
                }
                if(event.type().equals("state")||event.type().equals("error"))bluetoothState=event.message();
            });
            queue.onJob(job->{synchronized(this){Entry e=entries.get(job.id);if(e==null)return;
                if(job.state==PrintJob.State.SENDING)e.started=true;
                e.state=job.state==PrintJob.State.FAILED&&e.started?"INTERRUPTED":job.state.name();
                e.progress=job.progress;e.message=job.message;saveUnchecked(e);
            }});
        }
        return transport;
    }
    public void scan()throws Exception{devices.clear();transport().scan().get(20,TimeUnit.SECONDS);}
    public synchronized void select(PrinterTransport.Device device)throws Exception{
        if(PrinterProfile.match(device.name()).filter(PrinterProfile::supported).isEmpty())throw new IllegalArgumentException("Unsupported printer");
        if(activeId!=null)throw new IllegalStateException("Wait for or cancel the active print job before changing printers");
        selected=device;atomic(directory.resolve("printer.json"),Wire.JSON.toJson(device));
    }
    public void connect(PrinterTransport.Device device)throws Exception{
        select(device);PrinterTransport t=transport();
        synchronized(linkGate){
            if(t.connected()&&!t.connection().deviceId().equals(device.id()))t.disconnect().get(10,TimeUnit.SECONDS);
            if(!t.connected())t.connect(device).get(30,TimeUnit.SECONDS);
        }
        queue.inspect();
    }
    public void disconnect()throws Exception{
        synchronized(this){if(activeId!=null)throw new IllegalStateException("Cancel the active print job before disconnecting");}
        synchronized(linkGate){if(transport!=null)transport.disconnect().get(10,TimeUnit.SECONDS);}
    }
    public synchronized Entry accept(String key,String title,String source,JobSettings settings,Path input)throws Exception{
        if(key==null||key.length()>512||title==null||title.length()>512)throw new IllegalArgumentException("Invalid job title or key");
        if(cancelledKeys.contains(key))throw new IllegalStateException("Job was cancelled");
        for(Entry e:entries.values())if(e.key.equals(key)){leases.put(e.id,System.currentTimeMillis());return e;}
        if(entries.values().stream().filter(e->!terminal(e.state)).count()>=100)throw new IllegalStateException("Print queue is full");
        Entry e=new Entry();e.id=source.equals("client")?UUID.fromString(key.substring("client:".length())).toString():UUID.randomUUID().toString();e.key=key;e.title=title;e.source=source;e.settings=settings;e.time=Instant.now().toString();
        Path target=directory.resolve("inputs").resolve(e.id+(source.equals("cups")?".pdf":".x6driver"));
        Files.move(input,target,StandardCopyOption.ATOMIC_MOVE);e.path=target.toString();save(e);entries.put(e.id,e);leases.put(e.id,System.currentTimeMillis());notifyAll();return e;
    }
    public synchronized JsonObject snapshot(){
        JsonObject o=new JsonObject();o.addProperty("v",1);o.addProperty("bluetoothState",bluetoothState);
        o.add("connection",Wire.JSON.toJsonTree(transport!=null&&transport.connected()?transport.connection():null));
        o.add("devices",Wire.JSON.toJsonTree(devices));o.add("selected",Wire.JSON.toJsonTree(selected));
        o.addProperty("firmware",queue==null?"":queue.firmware());o.addProperty("printerStatus",queue==null?-1:queue.status());
        o.add("jobs",Wire.JSON.toJsonTree(entries.values().stream().sorted(Comparator.comparing((Entry e)->e.time).reversed()).limit(500).toList()));return o;
    }
    public synchronized Entry get(String id){Entry e=entries.get(id);if(e==null)throw new IllegalArgumentException("Unknown print job");leases.put(e.id,System.currentTimeMillis());return e;}
    public synchronized void cancelKey(String key)throws Exception{
        cancelledKeys.add(key);atomic(directory.resolve("cancelled-keys.json"),Wire.JSON.toJson(cancelledKeys));
        for(Entry e:entries.values())if(e.key.equals(key))cancel(e.id);
    }
    public synchronized void cancel(String id){
        Entry e=get(id);if(terminal(e.state))return;
        if(id.equals(activeId)&&queue!=null)queue.cancel(id);
        e.state="CANCELLED";e.message="Cancelled; printer may finish buffered rows";saveUnchecked(e);notifyAll();
    }
    public void inspect(){if(queue!=null)queue.inspect();}
    public static boolean terminal(String state){return Set.of("SENT","COMPLETED","FAILED","INTERRUPTED","CANCELLED").contains(state);}
    private void work(){
        while(!closed){
            Entry e;
            synchronized(this){e=entries.values().stream().filter(x->!terminal(x.state))
                .filter(x->!x.source.equals("cups")||System.currentTimeMillis()-leases.getOrDefault(x.id,0L)<=10000)
                .min(Comparator.comparing(x->x.time)).orElse(null);}
            if(e==null){pause();continue;}
            try{
                PrinterTransport t=transport();
                if(!t.connected()){
                    synchronized(this){if(terminal(e.state))continue;e.state="WAITING_CONNECTION";e.message=selected==null?"Select a printer in X6":"Waiting for printer connection";saveUnchecked(e);}
                    if(selected!=null&&System.currentTimeMillis()-lastConnect>10000){
                        lastConnect=System.currentTimeMillis();try{synchronized(linkGate){if(!t.connected()&&selected!=null)t.connect(selected).get(30,TimeUnit.SECONDS);}}catch(Exception error){bluetoothState=root(error);}
                    }
                    pause();continue;
                }
                synchronized(this){if(terminal(e.state)||closed)continue;activeId=e.id;e.state="SENDING";e.message="Preparing print job";saveUnchecked(e);}
                Document doc;
                if(e.source.equals("cups")){doc=new Document();doc.title=e.title;doc.heightMm=10;doc.marginMm=0;}
                else doc=DocumentIO.load(Path.of(e.path));
                JobSettings s=e.settings;PrintJob job=new PrintJob(e.id,doc,PrinterProfile.match(t.connection().name()).orElseThrow(),new ProtocolEncoder.Options(s.mode()==dev.sbelx.x6driver.imaging.Rasterizer.Mode.PHOTO,s.density(),false),s.mode(),s.threshold(),s.copies(),s.gamma());
                CompletableFuture<PrintJob> future;
                synchronized(this){
                    if(terminal(e.state)||closed){activeId=null;continue;}
                    if(e.source.equals("cups")){
                        PdfPages pages=new PdfPages(Path.of(e.path),s);
                        try{future=queue.submit(job,pages);}catch(Exception ex){pages.close();throw ex;}
                    }else future=queue.submit(job);
                }
                future.get();
            }catch(Exception error){synchronized(this){if(!e.state.equals("CANCELLED")){e.state=e.started?"INTERRUPTED":"FAILED";e.message=root(error);saveUnchecked(e);}}}
            finally{activeId=null;}
        }
    }
    private void pause(){synchronized(this){try{wait(500);}catch(InterruptedException e){Thread.currentThread().interrupt();closed=true;}}}
    private void save(Entry e)throws java.io.IOException{atomic(directory.resolve("ledger").resolve(e.id+".json"),Wire.JSON.toJson(e));}
    private void saveUnchecked(Entry e){try{save(e);}catch(Exception ex){throw new IllegalStateException("Cannot persist print state",ex);}}
    static void atomic(Path target,String text)throws java.io.IOException{
        Path tmp=Files.createTempFile(target.getParent(),".state-",".tmp");
        try{Files.writeString(tmp,text);try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}
    }
    private static String root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getMessage()==null?e.toString():e.getMessage();}
    public void close(){closed=true;synchronized(this){notifyAll();}if(queue!=null)queue.close();if(transport!=null)transport.close();try{worker.join(5000);history.close();}catch(Exception e){System.err.println(e.getMessage());}}
}
