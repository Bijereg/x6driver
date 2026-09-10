package dev.sbelx.x6driver.imaging;
import dev.sbelx.x6driver.core.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ImagingTest {
 @TempDir Path temp;
 @Test void qrSurvivesExactPrintRaster()throws Exception{
  Document d=new Document();d.heightMm=50;var e=new Document.Element(Document.Kind.QR);e.text="https://example.com/x6driver";e.x=4;e.y=4;e.width=40;e.height=40;d.pages.getFirst().elements.add(e);
  BufferedImage image=Rasterizer.monochrome(new DocumentRenderer().render(d,0,200),384,Rasterizer.Mode.PHOTO,160);
  int[] rgb=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());var source=new RGBLuminanceSource(image.getWidth(),image.getHeight(),rgb);
  assertEquals(e.text,new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText());
 }
 @Test void barCodeSurvivesRendering()throws Exception{
  Document d=new Document();d.heightMm=30;var e=new Document.Element(Document.Kind.BARCODE);e.text="12345678";e.x=3;e.y=3;e.width=42;e.height=20;d.pages.getFirst().elements.add(e);
  BufferedImage image=new DocumentRenderer().render(d,0,200);int[] rgb=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());assertEquals(e.text,new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(),image.getHeight(),rgb)))).getText());
 }
 @Test void pagesExportAndImportOffline()throws Exception{
  Document d=new Document();var e=new Document.Element(Document.Kind.TEXT);e.text="Привет, мир!";d.pages.getFirst().elements.add(e);d.pages.add(new Document.Page());Path file=temp.resolve("test.pdf");PdfSupport.export(d,file);assertEquals(2,PdfSupport.pageCount(file));Document loaded=PdfSupport.importPages(file,new int[]{0,1},d.widthMm);assertEquals(2,loaded.pages.size());assertEquals(2,loaded.assets.size());assertNotNull(new DocumentRenderer().render(loaded,0,200));
 }
 @Test void pageRangesAreValidated(){assertArrayEquals(new int[]{0,1,2,4},PdfSupport.parsePages("1-3,5,2",8));assertThrows(IllegalArgumentException.class,()->PdfSupport.parsePages("0-2",8));assertThrows(IllegalArgumentException.class,()->PdfSupport.parsePages("3-1",8));assertThrows(IllegalArgumentException.class,()->PdfSupport.parsePages("1-9",8));}
 @Test void cropRotationAndMarginClip()throws Exception{
  Document d=new Document();d.widthMm=25.4;d.heightMm=25.4;d.marginMm=2;BufferedImage source=new BufferedImage(20,10,BufferedImage.TYPE_INT_RGB);for(int y=0;y<10;y++)for(int x=0;x<20;x++)source.setRGB(x,y,x<10?0xff000000:0xffffffff);
  var e=new Document.Element(Document.Kind.IMAGE);e.x=0;e.y=0;e.width=25.4;e.height=25.4;e.asset=d.addAsset(DocumentRenderer.png(source));e.cropWidth=.5;e.rotation=180;d.pages.getFirst().elements.add(e);
  BufferedImage image=new DocumentRenderer().render(d,0,100);assertEquals(0xffffff,image.getRGB(0,0)&0xffffff);assertEquals(0,image.getRGB(50,50)&0xffffff);
 }
 @Test void twoColorModeIsStrictThresholdWithoutDithering(){
  BufferedImage gradient=new BufferedImage(256,8,BufferedImage.TYPE_INT_RGB);for(int y=0;y<8;y++)for(int x=0;x<256;x++)gradient.setRGB(x,y,(x<<16)|(x<<8)|x);
  var binary=Rasterizer.monochrome(gradient,256,Rasterizer.Mode.BINARY,128);
  for(int y=0;y<8;y++)for(int x=0;x<256;x++)assertEquals(x<128?0:0xffffff,binary.getRGB(x,y)&0xffffff);
 }
 @Test void pixelPackingIsLsbFirst(){BufferedImage image=new BufferedImage(8,1,BufferedImage.TYPE_INT_RGB);for(int x=0;x<8;x++)image.setRGB(x,0,0xffffffff);image.setRGB(0,0,0xff000000);image.setRGB(7,0,0xff000000);assertArrayEquals(new byte[]{(byte)0x81},Rasterizer.row(image,0));}
}
