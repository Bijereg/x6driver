package dev.sbelx.x6driver.imaging;
import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;
class RollImageTest {
    @Test void alphaIsCompositedAndInternalWhiteSpaceIsKept(){
        BufferedImage input=new BufferedImage(30,40,BufferedImage.TYPE_INT_ARGB);
        input.setRGB(2,3,0xff000000);input.setRGB(20,30,0xff000000);input.setRGB(29,39,0x00000000);
        BufferedImage result=RollImage.crop(input);
        assertEquals(19,result.getWidth());assertEquals(28,result.getHeight());assertEquals(0xffffff,result.getRGB(10,10)&0xffffff);
    }
    @Test void blankAndNearWhitePagesAreSkipped(){
        BufferedImage input=new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB);Graphics2D g=input.createGraphics();g.setColor(new Color(250,250,250));g.fillRect(0,0,8,8);g.dispose();assertNull(RollImage.crop(input));
        input.setRGB(4,4,0xfff9fafa);assertEquals(1,RollImage.crop(input).getWidth());
    }
    @Test void fittingPreservesAspectAndUses384Dots(){
        BufferedImage input=new BufferedImage(100,200,BufferedImage.TYPE_INT_RGB);var cropped=RollImage.crop(input);
        var raster=Rasterizer.monochrome(cropped,384,Rasterizer.Mode.PHOTO,160,1.15);
        assertEquals(384,raster.getWidth());assertEquals(768,raster.getHeight());
    }
    @Test void excessiveRollLengthIsRejected(){assertThrows(IllegalArgumentException.class,()->RollImage.crop(new BufferedImage(1,100,BufferedImage.TYPE_INT_RGB)));}
}
