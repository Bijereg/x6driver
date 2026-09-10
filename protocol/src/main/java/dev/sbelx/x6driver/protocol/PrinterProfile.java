package dev.sbelx.x6driver.protocol;
import com.google.gson.*;
import dev.sbelx.x6driver.core.Document;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public record PrinterProfile(String name,int width,int dpi,int imageSpeed,int textSpeed,int packetSize,int interval,int energy,int textEnergy,int paperFeed,boolean compression,boolean labels,boolean spp,JsonObject original) {
    private static final List<PrinterProfile> PROFILES=load();
    private static List<PrinterProfile> load(){
        try(var in=PrinterProfile.class.getResourceAsStream("/printer-profiles.json")){
            if(in==null)throw new IOException("Missing printer catalog");
            var list=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("data");List<PrinterProfile> out=new ArrayList<>();
            for(var e:list){var o=e.getAsJsonObject();out.add(new PrinterProfile(o.get("modelNo").getAsString(),n(o,"printSize"),n(o,"devdpi"),n(o,"imgPrintSpeed"),n(o,"textPrintSpeed"),n(o,"imgMTU"),n(o,"interval"),n(o,"moderationEneragy"),n(o,"textEneragy"),n(o,"paperNum")*48,o.get("newCompress").getAsBoolean(),o.get("canPrintLabel").getAsBoolean(),o.get("useSPP").getAsBoolean(),o));}
            return List.copyOf(out);
        }catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    private static int n(JsonObject o,String key){return o.get(key).getAsInt();}
    public static List<PrinterProfile> all(){return PROFILES;}
    public static Optional<PrinterProfile> match(String advertisedName){
        return PROFILES.stream().filter(p->!p.name.isBlank()&&(advertisedName.equals(p.name)||advertisedName.startsWith(p.name+"-"))).max(Comparator.comparingInt(p->p.name.length()));
    }
    /** Only the recovered, tested 384-dot X6 family is enabled in this release. */
    public boolean supported(){return Set.of("X6h","X6","X6H","X6HP").contains(name)&&width==384&&!original.get("newFormat").getAsBoolean()&&original.get("d1key").getAsString().isEmpty();}
    @Override public String toString(){return name+" · "+width+" px · "+dpi+" dpi";}
}
