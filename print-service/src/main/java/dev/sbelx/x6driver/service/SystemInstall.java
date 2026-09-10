package dev.sbelx.x6driver.service;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.io.IOException;

public final class SystemInstall {
    private static String run(String... command)throws Exception{
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        if(p.waitFor()!=0)throw new IOException(output.isBlank()?"Command failed: "+command[0]:output.strip());return output;
    }
    private static void stopAgent(int uid){try{run("/bin/launchctl","bootout","gui/"+uid+"/dev.sbelx.x6driver.service");}catch(Exception ignored){}}
    public static void configure(Path source,boolean enable)throws Exception{
        int uid=ServicePaths.uid();Path installed=ServicePaths.app();
        JsonCheck: {
            com.google.gson.JsonObject snapshot;
            try{snapshot=Wire.call(Wire.request("status"),null);}catch(Exception unavailable){break JsonCheck;}
            for(var item:snapshot.getAsJsonArray("jobs"))if(!ServiceEngine.terminal(item.getAsJsonObject().get("state").getAsString()))throw new IOException("Finish or cancel pending X6 jobs before changing installation");
        }
        String pending;
        try{pending=run("/usr/bin/lpstat","-o","X6_X6h");}catch(Exception e){pending="";}
        if(!pending.isBlank())throw new IOException("Finish or cancel pending X6 system jobs before changing installation");
        if(enable){
            if(!Files.isExecutable(source.resolve("Contents/MacOS/x6driver-service")))throw new IOException("Build the complete X6 Driver app before installing system printing");
            stopAgent(uid);
            if(!source.toAbsolutePath().normalize().equals(installed.toAbsolutePath().normalize())){
                Files.createDirectories(installed.getParent());Path staging=Files.createTempDirectory(installed.getParent(),".x6driver-install-").resolve("X6 Driver.app");
                run("/usr/bin/ditto",source.toString(),staging.toString());
                if(Files.exists(installed))Files.move(installed,installed.resolveSibling("X6 Driver.previous-"+System.currentTimeMillis()+".app"));
                Files.move(staging,installed);Files.deleteIfExists(staging.getParent());
            }
        }
        Path resources=(enable?installed:source).resolve("Contents/app/system-print");
        Path stage=Files.createTempDirectory(Path.of("/private/tmp"),"x6driver-install-");
        Files.setPosixFilePermissions(stage,PosixFilePermissions.fromString("rwx------"));
        for(String name:List.of("install-root.sh","x6driver","X6-X6h.ppd"))Files.copy(resources.resolve(name),stage.resolve(name));
        String appleScript="on run argv\n do shell script (\"/bin/sh \" & quoted form of (item 1 of argv) & \" \" & quoted form of (item 2 of argv) & \" \" & quoted form of (item 3 of argv) & \" \" & quoted form of (item 4 of argv)) with administrator privileges\nend run";
        run("/usr/bin/osascript","-e",appleScript,stage.resolve("install-root.sh").toString(),stage.toString(),Integer.toString(uid),enable?"enable":"disable");
        if(enable){
            Path logs=ServicePaths.data().resolve("logs");Files.createDirectories(logs);Files.createDirectories(ServicePaths.agent().getParent());
            String plist="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\"><dict><key>Label</key><string>dev.sbelx.x6driver.service</string><key>ProgramArguments</key><array><string>"+xml(installed.resolve("Contents/MacOS/x6driver-service").toString())+"</string></array><key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>ThrottleInterval</key><integer>10</integer><key>StandardOutPath</key><string>"+xml(logs.resolve("service.log").toString())+"</string><key>StandardErrorPath</key><string>"+xml(logs.resolve("service-error.log").toString())+"</string></dict></plist>\n";
            Files.writeString(ServicePaths.agent(),plist);run("/usr/bin/plutil","-lint",ServicePaths.agent().toString());
            run("/bin/launchctl","bootstrap","gui/"+uid,ServicePaths.agent().toString());
        }else{stopAgent(uid);Files.deleteIfExists(ServicePaths.agent());}
        for(String name:List.of("install-root.sh","x6driver","X6-X6h.ppd"))Files.deleteIfExists(stage.resolve(name));Files.deleteIfExists(stage);
    }
    private static String xml(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    public static void main(String[] args)throws Exception{if(args.length!=2||!Set.of("enable","disable").contains(args[0]))throw new IllegalArgumentException("enable|disable APP_PATH");configure(Path.of(args[1]),args[0].equals("enable"));if(args[0].equals("enable"))PrinterSetup.run();System.out.println("System printing "+args[0]);}
}
