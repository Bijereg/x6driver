package dev.sbelx.x6driver.transport;
import dev.sbelx.x6driver.core.*;
import dev.sbelx.x6driver.imaging.*;
import dev.sbelx.x6driver.protocol.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class PrintQueue implements PrintManager {
    public interface Pages extends AutoCloseable {
        int count();
        java.awt.image.BufferedImage render(int page) throws Exception;
        default void close() throws Exception {}
    }
    private final PrinterTransport transport;
    private final HistoryStore store;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("print-queue").factory());
    private final FrameParser parser=new FrameParser();
    private final CopyOnWriteArrayList<Consumer<PrintJob>> listeners=new CopyOnWriteArrayList<>();
    private final Object gate=new Object();
    private final long statusTimeoutMs,flowTimeoutMs;
    private volatile boolean paused, closed;
    private volatile int status=-1;
    private volatile long statusSequence;
    private volatile String firmware="";
    private volatile PrintJob active;
    private final ConcurrentHashMap<String,PrintJob> jobs=new ConcurrentHashMap<>();
    public PrintQueue(PrinterTransport transport,HistoryStore store){this(transport,store,5000,30000);}
    public PrintQueue(PrinterTransport transport,HistoryStore store,long statusTimeoutMs,long flowTimeoutMs){
        this.statusTimeoutMs=statusTimeoutMs;this.flowTimeoutMs=flowTimeoutMs;
        this.transport=transport;this.store=store;transport.onData(this::notification);
        transport.onEvent(e->{if((e.type().equals("disconnected")||e.type().equals("connected"))){status=-1;firmware="";paused=false;parser.reset();synchronized(gate){gate.notifyAll();}}});
    }
    public void onJob(Consumer<PrintJob> listener){listeners.add(listener);}
    public String firmware(){return firmware;}
    public int status(){return status;}
    public Collection<PrintJob> jobs(){return List.copyOf(jobs.values());}
    public void inspect(){executor.submit(()->{try{send(X6Encoder.info());send(X6Encoder.state());}catch(Exception e){System.err.println("Inspect: "+e.getMessage());}});}
    private void notification(byte[] bytes){
        for(var frame:parser.accept(bytes)){
            byte[] data=frame.payload();
            synchronized(gate){
                if(frame.command()==0xae&&data.length>0){paused=(data[0]&16)!=0;}
                if(frame.command()==0xa3&&data.length>0){status=data[0]&255;statusSequence++;}
                if(frame.command()==0xa8&&data.length>3)firmware=new String(data,3,data.length-3,StandardCharsets.US_ASCII).replace("\0","").trim();
                gate.notifyAll();
            }
        }
    }
    public CompletableFuture<PrintJob> submit(PrintJob job)throws Exception{
        return submit(job,new Pages(){
            public int count(){return job.document.pages.size();}
            public java.awt.image.BufferedImage render(int page)throws Exception{return new DocumentRenderer().render(job.document,page,job.profile.dpi());}
        });
    }
    public CompletableFuture<PrintJob> submit(PrintJob job,Pages source)throws Exception{
        if(closed||!transport.connected())throw new IllegalStateException("Printer is not connected");
        if(!job.profile.supported())throw new IllegalArgumentException("Unsupported printer profile");
        String device=transport.connection().deviceId();store.add(job.id,job.document,transport.connection().name());jobs.put(job.id,job);change(job,PrintJob.State.QUEUED,"");
        CompletableFuture<PrintJob> result=new CompletableFuture<>();
        executor.submit(()->{active=job;try{
            check(job,device);paused=false;long previous=statusSequence;send(X6Encoder.state());if(!waitStatus(previous,statusTimeoutMs)||status<0)throw new IllegalStateException("Printer did not return its status");checkStatus();
            change(job,PrintJob.State.SENDING,"");int pages=source.count()*job.copies,done=0;
            for(int copy=0;copy<job.copies;copy++)for(int page=0;page<source.count();page++){
                check(job,device);var image=source.render(page);if(image==null){done++;continue;}var raster=Rasterizer.monochrome(image,job.profile.width(),job.mode,job.threshold,job.gamma);
                List<byte[]> frames=new X6Encoder().encode(raster,job.profile,job.options);
                int packetSize=Math.min(transport.connection().maxWrite(),job.profile.packetSize()>0?job.profile.packetSize():20);
                // Never concatenate frames across boundaries; the peripheral may report flow control between rows.
                for(int n=0;n<frames.size();n++){
                    byte[] frame=frames.get(n);
                    for(int offset=0;offset<frame.length;offset+=packetSize){
                        check(job,device);awaitFlow(job,device);checkStatus();send(Arrays.copyOfRange(frame,offset,Math.min(offset+packetSize,frame.length)));
                        Thread.sleep(Math.max(2,job.profile.interval()));
                    }
                    job.progress=(done+(n+1.0)/frames.size())/pages;if(n%24==0)notifyJob(job);
                }
                done++;
            }
            change(job,PrintJob.State.WAITING,"");
            // A fresh idle response after the final write is useful, but does not prove paper output.
            previous=statusSequence;send(X6Encoder.state());boolean response=waitStatus(previous,statusTimeoutMs);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);boolean busy=response&&(status&128)!=0;
            while(response&&(status&128)!=0&&System.nanoTime()<deadline){check(job,device);Thread.sleep(500);previous=statusSequence;send(X6Encoder.state());response=waitStatus(previous,3000);checkStatus();}
            check(job,device);checkStatus();job.progress=1;
            if(response&&busy&&(status&128)==0)change(job,PrintJob.State.COMPLETED,"Printer reported busy → idle after final write");
            else change(job,PrintJob.State.SENT,"Data sent; physical output is not confirmed by the protocol");
        }catch(CancellationException e){try{if(!closed&&transport.connected())send(X6Encoder.cancel());}catch(Exception ignored){}change(job,PrintJob.State.CANCELLED,"Cancelled; printer may finish buffered rows");}
        catch(Exception e){change(job,job.cancelled.get()?PrintJob.State.CANCELLED:PrintJob.State.FAILED,rootMessage(e));}
        finally{try{source.close();}catch(Exception e){System.err.println(e.getMessage());}active=null;result.complete(job);}});return result;
    }
    private void check(PrintJob job,String device){if(job.cancelled.get()||closed)throw new CancellationException();if(!transport.connected()||!transport.connection().deviceId().equals(device))throw new IllegalStateException("Printer disconnected; manual retry required");}
    private void awaitFlow(PrintJob job,String device)throws Exception{
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(flowTimeoutMs);
        synchronized(gate){while(paused){check(job,device);if(System.nanoTime()>until)throw new TimeoutException("Printer buffer did not become ready");gate.wait(100);}}
    }
    private boolean waitStatus(long previous,long millis)throws InterruptedException{
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);synchronized(gate){while(statusSequence==previous&&transport.connected()&&System.nanoTime()<until)gate.wait(100);}return statusSequence!=previous;
    }
    private void checkStatus(){if(status>=0&&(status&15)!=0)throw new IllegalStateException((status&1)!=0?"No paper":(status&2)!=0?"Cover open":(status&4)!=0?"Printer overheated":"Battery too low");}
    private void send(byte[] frame)throws Exception{transport.write(frame).get(10,TimeUnit.SECONDS);}
    public void cancel(String id){var job=jobs.get(id);if(job!=null)job.cancelled.set(true);synchronized(gate){gate.notifyAll();}}
    private void change(PrintJob job,PrintJob.State state,String message){job.state=state;job.message=message;store.update(job.id,state.name(),message);notifyJob(job);}
    private void notifyJob(PrintJob job){for(var listener:listeners)try{listener.accept(job);}catch(RuntimeException ignored){}}
    private static String rootMessage(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getMessage()==null?e.toString():e.getMessage();}
    @Override public void close(){
        closed=true;jobs.values().forEach(j->j.cancelled.set(true));
        if(active!=null&&transport.connected())transport.write(X6Encoder.cancel());
        synchronized(gate){gate.notifyAll();}
        executor.shutdown();
        try{if(!executor.awaitTermination(3,TimeUnit.SECONDS)){executor.shutdownNow();executor.awaitTermination(1,TimeUnit.SECONDS);}}
        catch(InterruptedException e){executor.shutdownNow();Thread.currentThread().interrupt();}
    }
}
