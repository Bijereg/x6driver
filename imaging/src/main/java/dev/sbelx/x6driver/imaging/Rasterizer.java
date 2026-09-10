package dev.sbelx.x6driver.imaging;
import java.awt.*;
import java.awt.image.BufferedImage;

public final class Rasterizer {
    public enum Mode { PHOTO, TEXT, BINARY }
    /** Returns the exact black/white raster sent to the protocol encoder. */
    public static BufferedImage monochrome(BufferedImage source,int width,Mode mode,int threshold){
        return monochrome(source,width,mode,threshold,1.0);
    }
    public static BufferedImage monochrome(BufferedImage source,int width,Mode mode,int threshold,double gamma){
        if(!Double.isFinite(gamma)||gamma<.5||gamma>2)throw new IllegalArgumentException("Invalid gamma");
        if(width<8||width%8!=0||width>2400)throw new IllegalArgumentException("Width must be a multiple of eight");
        int height=Math.max(1,(int)Math.round(source.getHeight()*(double)width/source.getWidth()));
        if((long)width*height>40_000_000)throw new IllegalArgumentException("Raster too large");
        BufferedImage scaled=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=scaled.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,width,height);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,width,height,null);g.dispose();
        // Original text path uses an image-wide adaptive threshold (mean - 13).
        // BINARY uses the explicit user threshold, with no error diffusion or intermediate shades.
        if(mode==Mode.TEXT){long sum=0;for(int y=0;y<height;y++)for(int x=0;x<width;x++){int rgb=scaled.getRGB(x,y);double gray=.299*((rgb>>16)&255)+.587*((rgb>>8)&255)+.114*(rgb&255);sum+=(int)(255*Math.pow(gray/255,gamma));}threshold=Math.max(1,Math.min(254,(int)(sum/((long)width*height))-13));}
        BufferedImage result=new BufferedImage(width,height,BufferedImage.TYPE_BYTE_BINARY);
        double[] current=new double[width+2],next=new double[width+2];
        for(int y=0;y<height;y++){
            for(int x=0;x<width;x++){
                int rgb=scaled.getRGB(x,y);double gray=.299*((rgb>>16)&255)+.587*((rgb>>8)&255)+.114*(rgb&255);
                gray=255*Math.pow(gray/255,gamma);
                double value=gray+(mode==Mode.PHOTO?current[x+1]:0);int out=value<threshold?0:255;
                result.setRGB(x,y,out==0?0xff000000:0xffffffff);
                if(mode==Mode.PHOTO){double error=value-out;current[x+2]+=error*7/16;next[x]+=error*3/16;next[x+1]+=error*5/16;next[x+2]+=error/16;}
            }
            current=next;next=new double[width+2];
        }return result;
    }
    public static byte[] row(BufferedImage raster,int y){
        byte[] data=new byte[raster.getWidth()/8];
        for(int x=0;x<raster.getWidth();x++)if((raster.getRGB(x,y)&0xffffff)<0x808080)data[x/8]|=(byte)(1<<(x%8));
        return data;
    }
}
