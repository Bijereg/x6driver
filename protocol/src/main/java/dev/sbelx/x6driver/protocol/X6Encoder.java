package dev.sbelx.x6driver.protocol;
import dev.sbelx.x6driver.imaging.Rasterizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** Recovered 51 78 protocol. CRC-8 polynomial 0x07, zero seed; LSB-first raster. */
public final class X6Encoder implements ProtocolEncoder {
    public static int crc(byte[] data){int crc=0;for(byte b:data){crc^=b&255;for(int bit=0;bit<8;bit++)crc=((crc&128)!=0?crc<<1^7:crc<<1)&255;}return crc;}
    public static byte[] frame(int command,byte... payload){
        if(payload.length>65535)throw new IllegalArgumentException("Frame too large");
        byte[] out=new byte[payload.length+8];out[0]=0x51;out[1]=0x78;out[2]=(byte)command;out[4]=(byte)payload.length;out[5]=(byte)(payload.length>>8);System.arraycopy(payload,0,out,6,payload.length);out[out.length-2]=(byte)crc(payload);out[out.length-1]=(byte)255;return out;
    }
    public static byte[] info(){return frame(0xa8,(byte)0);}
    public static byte[] state(){return frame(0xa3,(byte)0);}
    public static byte[] cancel(){return frame(0xa6,(byte)5);}
    public static byte[] little(int value){return new byte[]{(byte)value,(byte)(value>>8)};}
    public static byte[] rle(byte[] packed,int width){
        ByteArrayOutputStream out=new ByteArrayOutputStream();int previous=-1,count=0;
        for(int x=0;x<width;x++){int bit=(packed[x/8]>>(x%8))&1;if(bit!=previous||count==127){if(count>0)out.write((previous<<7)|count);previous=bit;count=1;}else count++;}
        if(count>0)out.write((previous<<7)|count);return out.toByteArray();
    }
    @Override public List<byte[]> encode(BufferedImage raster,PrinterProfile p,Options options){
        if(!p.supported())throw new IllegalArgumentException("Printer profile not implemented: "+p.name());
        if(raster.getWidth()!=p.width()||raster.getWidth()%8!=0)throw new IllegalArgumentException("Raster width does not match printer");
        if(options.labelPaper())throw new IllegalArgumentException("Gap sensor calibration required; use continuous paper for this release");
        List<byte[]> out=new ArrayList<>();out.add(frame(0xa4,(byte)0x33));
        int energy=options.photo()?p.energy():(p.textEnergy()==0?p.energy():p.textEnergy());
        // Original PrinterModelUtils.getPrintEneragy: base + (concentration - 4) * 0.15 * base.
        // Three UI levels correspond to original concentrations 2, 4, 6.
        energy=(int)(energy*(1+(options.density()-2)*0.30));
        if(energy>0)out.add(frame(0xaf,little(energy)));
        out.add(frame(0xbe,(byte)(options.photo()?0:1)));
        byte[] speed=frame(0xbd,(byte)(options.photo()?p.imageSpeed():p.textSpeed()));out.add(speed);
        for(int y=0;y<raster.getHeight();y++){
            byte[] row=Rasterizer.row(raster,y),compressed=rle(row,raster.getWidth());
            if(p.compression()&&compressed.length<=row.length)out.add(frame(0xbf,compressed));else out.add(frame(0xa2,row));
            if((y+1)%200==0)out.add(speed);
        }
        out.add(frame(0xbd,(byte)25));out.add(frame(0xa1,little(p.paperFeed())));out.add(state());return List.copyOf(out);
    }
}
