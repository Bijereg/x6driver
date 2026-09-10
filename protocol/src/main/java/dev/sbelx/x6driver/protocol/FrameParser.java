package dev.sbelx.x6driver.protocol;
import java.util.*;
import java.io.*;
/** Reassembles arbitrary notification fragments, checks CRC, and resynchronises after noise. */
public final class FrameParser {
    public record Frame(int command,byte[] payload) {}
    private byte[] buffer=new byte[0];
    public synchronized List<Frame> accept(byte[] bytes){
        if(bytes.length>65536){buffer=new byte[0];return List.of();}
        byte[] combined=Arrays.copyOf(buffer,buffer.length+bytes.length);System.arraycopy(bytes,0,combined,buffer.length,bytes.length);List<Frame> frames=new ArrayList<>();int pos=0;
        while(pos+8<=combined.length){
            if(combined[pos]!=0x51||combined[pos+1]!=0x78){pos++;continue;}
            int len=(combined[pos+4]&255)|((combined[pos+5]&255)<<8);if(len>4096){pos++;continue;}
            if(pos+len+8>combined.length)break;
            byte[] data=Arrays.copyOfRange(combined,pos+6,pos+6+len);
            if(combined[pos+len+7]!=(byte)255||X6Encoder.crc(data)!=(combined[pos+len+6]&255)){pos++;continue;}
            frames.add(new Frame(combined[pos+2]&255,data));pos+=len+8;
        }
        buffer=Arrays.copyOfRange(combined,pos,combined.length);if(buffer.length>8192)buffer=new byte[0];return frames;
    }
    public synchronized void reset(){buffer=new byte[0];}
}
