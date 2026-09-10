package dev.sbelx.x6driver.transport;
import dev.sbelx.x6driver.core.*;
import dev.sbelx.x6driver.protocol.*;
import dev.sbelx.x6driver.imaging.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
class PrintQueueTest {
 @TempDir Path temp;
 static class Fake implements PrinterTransport {
  boolean connected=true,disconnectOnData=false,pauseOnce=false,replyStatus=true,stuckBuffer=false;
  int errorStatus,maxChunk=0,rows=0;Consumer<byte[]> data=x->{};Consumer<Event> event=x->{};FrameParser parser=new FrameParser();
  public void onEvent(Consumer<Event> e){event=e;}public void onData(Consumer<byte[]> e){data=e;}
  public CompletableFuture<Void> scan(){return CompletableFuture.completedFuture(null);}public CompletableFuture<Connection> connect(Device d){return CompletableFuture.completedFuture(connection());}
  public CompletableFuture<Void> write(byte[] bytes){maxChunk=Math.max(maxChunk,bytes.length);
   for(var f:parser.accept(bytes)){
    if(f.command()==0xa3&&replyStatus){byte[] reply=X6Encoder.frame(0xa3,(byte)errorStatus,(byte)20,(byte)40);data.accept(Arrays.copyOfRange(reply,0,3));data.accept(Arrays.copyOfRange(reply,3,reply.length));}
    if(f.command()==0xbf||f.command()==0xa2){rows++;if(disconnectOnData){connected=false;event.accept(new Event("disconnected","Lost",null));return CompletableFuture.failedFuture(new java.io.IOException("Connection lost"));}
     if(pauseOnce){pauseOnce=false;data.accept(X6Encoder.frame(0xae,(byte)16));if(!stuckBuffer)CompletableFuture.delayedExecutor(80,TimeUnit.MILLISECONDS).execute(()->data.accept(X6Encoder.frame(0xae,(byte)0)));}}
   }return CompletableFuture.completedFuture(null);
  }
  public CompletableFuture<Void> disconnect(){connected=false;return CompletableFuture.completedFuture(null);}public boolean connected(){return connected;}public Connection connection(){return new Connection("test","X6h-test",20,"AE01");}public void close(){}
 }
 PrintJob job(){Document d=new Document();d.heightMm=10;d.marginMm=1;return new PrintJob(d,PrinterProfile.match("X6h").orElseThrow(),new ProtocolEncoder.Options(false,2,false),Rasterizer.Mode.TEXT,160,1);}
 @Test void sendsFragmentedPacketsWithFlowControlAndHonestCompletion()throws Exception{Fake transport=new Fake();transport.pauseOnce=true;try(var store=new HistoryStore(temp);var queue=new PrintQueue(transport,store)){var result=queue.submit(job()).get(10,TimeUnit.SECONDS);assertEquals(PrintJob.State.SENT,result.state);assertTrue(transport.maxChunk<=20);assertTrue(transport.rows>0);assertEquals("SENT",store.entries().getFirst().state());}}
 @Test void noPaperStopsBeforeRaster()throws Exception{Fake t=new Fake();t.errorStatus=1;try(var s=new HistoryStore(temp);var q=new PrintQueue(t,s)){var result=q.submit(job()).get(10,TimeUnit.SECONDS);assertEquals(PrintJob.State.FAILED,result.state);assertEquals(0,t.rows);assertTrue(result.message.contains("paper"));}}
 @Test void disconnectNeverAutomaticallyRetries()throws Exception{Fake t=new Fake();t.disconnectOnData=true;try(var s=new HistoryStore(temp);var q=new PrintQueue(t,s)){var result=q.submit(job()).get(10,TimeUnit.SECONDS);assertEquals(PrintJob.State.FAILED,result.state);assertEquals(1,t.rows);}}
 @Test void missingFreshStatusFailsEvenWhenCachedStatusWasReady()throws Exception{Fake t=new Fake();t.replyStatus=false;try(var s=new HistoryStore(temp);var q=new PrintQueue(t,s,150,150)){t.data.accept(X6Encoder.frame(0xa3,(byte)0));var result=q.submit(job()).get(3,TimeUnit.SECONDS);assertEquals(PrintJob.State.FAILED,result.state);assertEquals(0,t.rows);}}
 @Test void stuckBufferTimesOutInsteadOfHanging()throws Exception{Fake t=new Fake();t.pauseOnce=true;t.stuckBuffer=true;try(var s=new HistoryStore(temp);var q=new PrintQueue(t,s,150,150)){var result=q.submit(job()).get(3,TimeUnit.SECONDS);assertEquals(PrintJob.State.FAILED,result.state);assertEquals(1,t.rows);}}
 @Test void closingResolvesPendingJobsBeforeDatabaseCloses()throws Exception{Fake t=new Fake();t.pauseOnce=true;t.stuckBuffer=true;try(var s=new HistoryStore(temp)){var q=new PrintQueue(t,s,150,150);var first=q.submit(job());var second=q.submit(job());q.close();assertEquals(PrintJob.State.CANCELLED,first.get(2,TimeUnit.SECONDS).state);assertEquals(PrintJob.State.CANCELLED,second.get(2,TimeUnit.SECONDS).state);assertTrue(s.entries().stream().allMatch(e->e.state().equals("CANCELLED")));}}
 @Test void cancellationStopsQueuedJob()throws Exception{Fake t=new Fake();try(var s=new HistoryStore(temp);var q=new PrintQueue(t,s)){PrintJob job=job();job.cancelled.set(true);assertEquals(PrintJob.State.CANCELLED,q.submit(job).get(10,TimeUnit.SECONDS).state);assertEquals(0,t.rows);}}
}
