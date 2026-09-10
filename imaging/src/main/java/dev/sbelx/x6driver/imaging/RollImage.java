package dev.sbelx.x6driver.imaging;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Crops only exterior near-white pixels, before contrast or dithering. */
public final class RollImage {
    private RollImage() {}
    public static BufferedImage crop(BufferedImage input) {
        BufferedImage rgb=new BufferedImage(input.getWidth(),input.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=rgb.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,rgb.getWidth(),rgb.getHeight());g.drawImage(input,0,0,null);g.dispose();
        int left=rgb.getWidth(),top=rgb.getHeight(),right=-1,bottom=-1;
        for(int y=0;y<rgb.getHeight();y++)for(int x=0;x<rgb.getWidth();x++){
            int p=rgb.getRGB(x,y);
            if(((p>>16)&255)<250||((p>>8)&255)<250||(p&255)<250){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
        }
        if(right<left)return null;
        double length=(bottom-top+1)*48.768/(right-left+1);
        if(length>2000)throw new IllegalArgumentException("Cropped page exceeds 2000 mm roll length");
        return rgb.getSubimage(left,top,right-left+1,bottom-top+1);
    }
}
