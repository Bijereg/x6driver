package dev.sbelx.x6driver.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
class UnixIPCTest {
    @Test void actualServiceAuthenticatesLocalPeerAndReplies()throws Exception{
        Path root=Files.createTempDirectory(Path.of("/private/tmp"),"tp-ipc-");
        String prior=System.getProperty("x6driver.service.socket");Path socket=root.resolve("sock/service.sock");
        Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Dx6driver.service.socket="+socket,"-Dx6driver.service.data="+root.resolve("data"),"-Dx6driver.bridge=/usr/bin/false","-cp",System.getProperty("java.class.path"),ServiceMain.class.getName()).redirectErrorStream(true).redirectOutput(root.resolve("log").toFile()).start();
        try{
            System.setProperty("x6driver.service.socket",socket.toString());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!Files.exists(socket)&&p.isAlive()&&System.nanoTime()<deadline)Thread.sleep(25);
            assertTrue(Files.exists(socket),()->{try{return Files.readString(root.resolve("log"));}catch(Exception e){return e.toString();}});
            var status=Wire.call(Wire.request("status"),null);assertEquals(1,status.get("v").getAsInt());assertEquals(0,status.getAsJsonArray("jobs").size());
            assertThrows(java.io.IOException.class,()->Wire.call(Wire.request("not-an-operation"),null));
            assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)));
            String id;
            try(var client=new ServiceClient()){
                var job=new dev.sbelx.x6driver.transport.PrintJob(new dev.sbelx.x6driver.core.Document(),dev.sbelx.x6driver.protocol.PrinterProfile.match("X6h").orElseThrow(),new dev.sbelx.x6driver.protocol.ProtocolEncoder.Options(true,3,false),dev.sbelx.x6driver.imaging.Rasterizer.Mode.PHOTO,160,1,1.15);
                id=job.id;assertEquals(id,client.submit(job).get(10,TimeUnit.SECONDS).id);
            }
            var request=Wire.request("job");request.addProperty("id",id);
            assertFalse(ServiceEngine.terminal(Wire.call(request,null).get("state").getAsString()),"Closing client IPC must not cancel an accepted job");
        }finally{
            p.destroy();if(!p.waitFor(10,TimeUnit.SECONDS))p.destroyForcibly();
            if(prior==null)System.clearProperty("x6driver.service.socket");else System.setProperty("x6driver.service.socket",prior);
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
