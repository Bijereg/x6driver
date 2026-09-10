package dev.sbelx.x6driver.service;
import dev.sbelx.x6driver.imaging.Rasterizer;
import java.util.*;
import java.util.regex.*;

public record JobSettings(Rasterizer.Mode mode,int density,double gamma,int threshold,int copies,String pageRanges,int orientation) {
    public JobSettings {
        if(mode==null||density<1||density>3||!Double.isFinite(gamma)||gamma<.5||gamma>2||threshold<1||threshold>254||copies<1||copies>30)throw new IllegalArgumentException("Invalid job settings");
        if(pageRanges==null||!pageRanges.matches("[0-9,\\-]*")||orientation<3||orientation>6)throw new IllegalArgumentException("Invalid page selection or orientation");
    }
    public static Map<String,String> options(String input){
        Map<String,String> result=new HashMap<>();
        Matcher m=Pattern.compile("([^\\s=]+)=(?:\"([^\"]*)\"|'([^']*)'|([^\\s]*))").matcher(input);
        while(m.find())result.put(m.group(1),m.group(2)!=null?m.group(2):m.group(3)!=null?m.group(3):m.group(4));
        return result;
    }
    public static JobSettings cups(String raw,int copies){
        var opts=options(raw);
        Rasterizer.Mode mode=switch(opts.getOrDefault("X6Mode","Photo")){case "Photo"->Rasterizer.Mode.PHOTO;case "Text"->Rasterizer.Mode.TEXT;case "Binary"->Rasterizer.Mode.BINARY;default->throw new IllegalArgumentException("Unknown print mode");};
        int density=switch(opts.getOrDefault("X6Density","Dark")){case "Light"->1;case "Normal"->2;case "Dark"->3;default->throw new IllegalArgumentException("Unknown density");};
        double gamma=switch(opts.getOrDefault("X6Contrast","C115")){case "C080"->.8;case "C100"->1;case "C115"->1.15;case "C130"->1.3;case "C150"->1.5;default->throw new IllegalArgumentException("Unknown contrast");};
        // macOS cgpdftopdf has already expanded copies, selected pages and flattened
        // orientation before this backend. argv[4]/options still contain original values.
        // Diagnostic jobs 141/143 verify this on the supported macOS version.
        return new JobSettings(mode,density,gamma,160,1,"",3);
    }
}
