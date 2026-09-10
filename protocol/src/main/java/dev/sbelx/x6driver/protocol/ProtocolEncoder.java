package dev.sbelx.x6driver.protocol;
import java.awt.image.BufferedImage;
import java.util.List;
public interface ProtocolEncoder {
    record Options(boolean photo,int density,boolean labelPaper) { public Options {if(density<1||density>3)throw new IllegalArgumentException("Invalid density");} }
    List<byte[]> encode(BufferedImage raster,PrinterProfile profile,Options options);
}
