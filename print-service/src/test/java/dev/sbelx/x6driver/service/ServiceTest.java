package dev.sbelx.x6driver.service;
import dev.sbelx.x6driver.core.*;
import dev.sbelx.x6driver.imaging.*;
import dev.sbelx.x6driver.transport.*;
import dev.sbelx.x6driver.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class ServiceTest {
    @TempDir Path temp;
    static final PrinterTransport.Device DEVICE=new PrinterTransport.Device("test","X6h-test",0,List.of("AE30"),"ble");
    static class Fake implements PrinterTransport {
        volatile boolean connected,disconnectOnData,pauseOnData;
        volatile int rows;
        final List<Consumer<Event>> events=new CopyOnWriteArrayList<>();
        Consumer<byte[]> data=x->{};FrameParser parser=new FrameParser();
        public void onEvent(Consumer<Event> e){events.add(e);}public void onData(Consumer<byte[]> d){data=d;}
        public CompletableFuture<Void> scan(){events.forEach(e->e.accept(new Event("device","",DEVICE)));return CompletableFuture.completedFuture(null);}
        public CompletableFuture<Connection> connect(Device d){connected=true;events.forEach(e->e.accept(new Event("connected",d.name(),null)));return CompletableFuture.completedFuture(connection());}
        public CompletableFuture<Void> write(byte[] bytes){
            for(var f:parser.accept(bytes)){
                if(f.command()==0xa3)data.accept(X6Encoder.frame(0xa3,(byte)0));
                if(f.command()==0xbf||f.command()==0xa2){rows++;if(pauseOnData)data.accept(X6Encoder.frame(0xae,(byte)16));if(disconnectOnData){connected=false;events.forEach(e->e.accept(new Event("disconnected","Lost",null)));return CompletableFuture.failedFuture(new java.io.IOException("Lost connection"));}}
            }return CompletableFuture.completedFuture(null);
        }
        public CompletableFuture<Void> disconnect(){connected=false;return CompletableFuture.completedFuture(null);}
        public boolean connected(){return connected;}
        public Connection connection(){return new Connection("test","X6h-test",180,"AE01");}
        public void close(){connected=false;}
    }
    JobSettings settings(){return new JobSettings(Rasterizer.Mode.TEXT,2,1,160,1,"",3);}
    Path document()throws Exception{Document d=new Document();d.heightMm=10;d.marginMm=0;Path p=Files.createTempFile(temp,"doc-",".x6driver");DocumentIO.save(d,p);return p;}
    String key(){return "client:"+UUID.randomUUID();}
    void until(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(25);assertTrue(condition.getAsBoolean());}
    @Test void waitsForConnectionThenPrintsAndDeduplicates()throws Exception{
        Fake fake=new Fake();try(var engine=new ServiceEngine(temp.resolve("service"),()->fake)){
            String key=key();var e=engine.accept(key,"Test","client",settings(),document());until(()->engine.get(e.id).state.equals("WAITING_CONNECTION"));assertEquals(0,fake.rows);
            engine.connect(DEVICE);until(()->ServiceEngine.terminal(engine.get(e.id).state));assertEquals("SENT",e.state);int rows=fake.rows;
            assertEquals(e.id,engine.accept(key,"Test","client",settings(),document()).id);Thread.sleep(100);assertEquals(rows,fake.rows);
        }
    }
    @Test void cancelBeforeConnectionNeverPrintsAndKeyCannotBeResubmitted()throws Exception{
        Fake fake=new Fake();try(var engine=new ServiceEngine(temp.resolve("service"),()->fake)){
            String key=key();var e=engine.accept(key,"Test","client",settings(),document());engine.cancelKey(key);engine.connect(DEVICE);Thread.sleep(200);assertEquals("CANCELLED",e.state);assertEquals(0,fake.rows);
            assertThrows(IllegalStateException.class,()->engine.accept(key,"Test","client",settings(),document()));
        }
    }
    @Test void partialTransferIsInterruptedWithoutRetry()throws Exception{
        Fake fake=new Fake();fake.connected=true;fake.disconnectOnData=true;
        try(var engine=new ServiceEngine(temp.resolve("service"),()->fake)){
            var e=engine.accept(key(),"Test","client",settings(),document());until(()->ServiceEngine.terminal(engine.get(e.id).state));assertEquals("INTERRUPTED",e.state);assertEquals(1,fake.rows);Thread.sleep(200);assertEquals(1,fake.rows);
        }
    }
    @Test void cancellationDuringTransferStopsFurtherRows()throws Exception{
        Fake fake=new Fake();fake.connected=true;fake.pauseOnData=true;
        try(var engine=new ServiceEngine(temp.resolve("service"),()->fake)){
            var e=engine.accept(key(),"Test","client",settings(),document());until(()->fake.rows==1);engine.cancel(e.id);
            until(()->engine.get(e.id).state.equals("CANCELLED"));Thread.sleep(200);assertEquals(1,fake.rows);
        }
    }
    @Test void startupInterruptsUncertainJobsAndKeepsUnsentJobs()throws Exception{
        Path directory=temp.resolve("service"),ledger=directory.resolve("ledger");Files.createDirectories(ledger);
        for(String state:List.of("SENDING","WAITING_CONNECTION")){
            ServiceEngine.Entry e=new ServiceEngine.Entry();e.id=UUID.randomUUID().toString();e.key=key();e.title=state;e.source="client";e.path=document().toString();e.time=java.time.Instant.now().toString();e.settings=settings();e.state=state;
            Files.writeString(ledger.resolve(e.id+".json"),Wire.JSON.toJson(e));
        }
        Fake fake=new Fake();try(var engine=new ServiceEngine(directory,()->fake)){
            var json=engine.snapshot().getAsJsonArray("jobs");assertTrue(json.asList().stream().anyMatch(x->x.getAsJsonObject().get("state").getAsString().equals("INTERRUPTED")));
            assertTrue(json.asList().stream().anyMatch(x->x.getAsJsonObject().get("state").getAsString().equals("WAITING_CONNECTION")));assertEquals(0,fake.rows);
        }
    }
    @Test void cupsCopiesRangesAndOrientationAreNotAppliedTwice(){
        var settings=JobSettings.cups("X6Mode=Binary X6Contrast=C130 X6Density=Light page-ranges=2 orientation-requested=4",2);
        assertEquals(Rasterizer.Mode.BINARY,settings.mode());assertEquals(1,settings.density());assertEquals(1.3,settings.gamma());assertEquals(1,settings.copies());assertEquals("",settings.pageRanges());assertEquals(3,settings.orientation());
    }
    Path pdf()throws Exception{
        Path file=temp.resolve("test.pdf");try(PDDocument pdf=new PDDocument()){
            for(int i=0;i<3;i++){PDPage page=new PDPage(new PDRectangle(100,200));pdf.addPage(page);if(i==1)page.setRotation(90);if(i<2)try(var c=new PDPageContentStream(pdf,page)){c.setNonStrokingColor(0f);c.addRect(10,20,20,60);c.fill();}}
            pdf.save(file.toFile());
        }return file;
    }
    @Test void pdfPagesPreserveIndividualLengthsAndRotationAndSkipBlank()throws Exception{
        try(var pages=new PdfPages(pdf(),settings())){
            assertEquals(3,pages.count());var a=pages.render(0);var b=pages.render(1);assertTrue(a.getHeight()>a.getWidth());assertTrue(b.getWidth()>b.getHeight());assertNull(pages.render(2));
        }
    }
    @Test void clientAndCupsUseSameFifo()throws Exception{
        Fake fake=new Fake();try(var engine=new ServiceEngine(temp.resolve("service"),()->fake)){
            var a=engine.accept(key(),"Client","client",settings(),document());var b=engine.accept("cups:test","PDF","cups",settings(),pdf());
            engine.connect(DEVICE);until(()->ServiceEngine.terminal(engine.get(b.id).state));assertEquals("SENT",a.state);assertEquals("SENT",b.state);assertTrue(fake.rows>0);
        }
    }
    @Test void orphanedCupsJobAfterRestartDoesNotPrintOrBlockClient()throws Exception{
        Path directory=temp.resolve("service"),ledger=directory.resolve("ledger");Files.createDirectories(ledger);
        ServiceEngine.Entry orphan=new ServiceEngine.Entry();orphan.id=UUID.randomUUID().toString();orphan.key="cups:orphan";orphan.title="Orphan";orphan.source="cups";orphan.path=pdf().toString();orphan.time="2020-01-01T00:00:00Z";orphan.settings=settings();orphan.state="QUEUED";
        Files.writeString(ledger.resolve(orphan.id+".json"),Wire.JSON.toJson(orphan));
        Fake fake=new Fake();fake.connected=true;try(var engine=new ServiceEngine(directory,()->fake)){
            var e=engine.accept(key(),"Client","client",settings(),document());until(()->ServiceEngine.terminal(engine.get(e.id).state));assertEquals("SENT",e.state);
            var jobs=engine.snapshot().getAsJsonArray("jobs");assertTrue(jobs.asList().stream().anyMatch(x->x.getAsJsonObject().get("id").getAsString().equals(orphan.id)&&x.getAsJsonObject().get("state").getAsString().equals("QUEUED")));
        }
    }
}
