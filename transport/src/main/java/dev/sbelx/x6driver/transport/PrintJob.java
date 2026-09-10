package dev.sbelx.x6driver.transport;
import dev.sbelx.x6driver.core.Document;
import dev.sbelx.x6driver.protocol.*;
import dev.sbelx.x6driver.imaging.Rasterizer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
public final class PrintJob {
    public enum State { QUEUED, SENDING, WAITING, SENT, COMPLETED, CANCELLED, FAILED, INTERRUPTED }
    public final String id;
    public final Document document;
    public final PrinterProfile profile;
    public final ProtocolEncoder.Options options;
    public final Rasterizer.Mode mode;
    public final int threshold,copies;
    public final double gamma;
    public final AtomicBoolean cancelled=new AtomicBoolean();
    public volatile State state=State.QUEUED;
    public volatile double progress;
    public volatile String message="";
    public PrintJob(Document document,PrinterProfile profile,ProtocolEncoder.Options options,Rasterizer.Mode mode,int threshold,int copies){
        this(document,profile,options,mode,threshold,copies,1.0);
    }
    public PrintJob(Document document,PrinterProfile profile,ProtocolEncoder.Options options,Rasterizer.Mode mode,int threshold,int copies,double gamma){
        this(UUID.randomUUID().toString(),document,profile,options,mode,threshold,copies,gamma);
    }
    public PrintJob(String id,Document document,PrinterProfile profile,ProtocolEncoder.Options options,Rasterizer.Mode mode,int threshold,int copies,double gamma){
        this.id=UUID.fromString(id).toString();
        if(!Double.isFinite(gamma)||gamma<.5||gamma>2)throw new IllegalArgumentException("Invalid gamma");
        this.gamma=gamma;
        if(copies<1||copies>30||threshold<1||threshold>254)throw new IllegalArgumentException("Invalid print options");
        this.document=document.copy();this.profile=profile;this.options=options;this.mode=mode;this.threshold=threshold;this.copies=copies;
    }
}
