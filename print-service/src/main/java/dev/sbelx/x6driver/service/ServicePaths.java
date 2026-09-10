package dev.sbelx.x6driver.service;
import java.nio.file.*;
import java.io.IOException;
public final class ServicePaths {
    public static Path data(){return Path.of(System.getProperty("x6driver.service.data",System.getProperty("user.home")+"/Library/Application Support/X6 Service"));}
    public static int uid()throws IOException{return ((Number)Files.getAttribute(Path.of(System.getProperty("user.home")),"unix:uid")).intValue();}
    public static Path socket()throws IOException{return Path.of(System.getProperty("x6driver.service.socket","/private/tmp/dev.sbelx.x6driver."+uid()+"/service.sock"));}
    public static Path app(){return Path.of(System.getProperty("x6driver.installed.app",System.getProperty("user.home")+"/Applications/X6 Driver.app"));}
    public static Path agent(){return Path.of(System.getProperty("user.home"),"Library/LaunchAgents/dev.sbelx.x6driver.service.plist");}
}
