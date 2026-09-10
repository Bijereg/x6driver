package dev.sbelx.x6driver.protocol;
import org.junit.jupiter.api.*;
import java.awt.image.BufferedImage;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ProtocolTest {
 @Test void exactOriginalCommandVectors(){
  assertEquals("5178a80001000000ff",HexFormat.of().formatHex(X6Encoder.info()));
  assertEquals("5178a30001000000ff",HexFormat.of().formatHex(X6Encoder.state()));
  assertEquals("5178a10002003000f9ff",HexFormat.of().formatHex(X6Encoder.frame(0xa1,X6Encoder.little(48))));
  assertEquals("5178a4000100329eff",HexFormat.of().formatHex(X6Encoder.frame(0xa4,(byte)0x32)));
  assertEquals("5178a6000100051bff",HexFormat.of().formatHex(X6Encoder.cancel()));
 }
 @Test void effectiveCatalogUsesSpecificCaseSensitiveProfile(){var p=PrinterProfile.match("X6h-1234").orElseThrow();assertEquals("X6h",p.name());assertEquals(384,p.width());assertEquals(5000,p.energy());assertEquals(8000,p.textEnergy());assertEquals(180,p.packetSize());assertEquals(187,com.google.gson.JsonParser.parseString(readAll()).getAsJsonArray().size());assertEquals("X6",PrinterProfile.match("X6-1234").orElseThrow().name());assertTrue(PrinterProfile.match("X6unknown").isEmpty());}
 private String readAll(){try(var input=ProtocolTest.class.getResourceAsStream("/all-original-profiles.json")){if(input==null)throw new java.io.IOException("Missing test catalog");return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new RuntimeException(e);}}
 @Test void runLengthEncodingSplitsAt127(){assertArrayEquals(new byte[]{127,127,127,3},X6Encoder.rle(new byte[48],384));byte[] allBlack=new byte[48];Arrays.fill(allBlack,(byte)255);assertArrayEquals(new byte[]{(byte)255,(byte)255,(byte)255,(byte)131},X6Encoder.rle(allBlack,384));}
 @Test void everyRandomRunLengthRoundTrips(){Random random=new Random(87);for(int i=0;i<50;i++){byte[] row=new byte[48];random.nextBytes(row);byte[] rle=X6Encoder.rle(row,384);int pos=0;for(byte run:rle){int bit=(run>>7)&1;for(int n=0;n<(run&127);n++,pos++)assertEquals((row[pos/8]>>(pos%8))&1,bit);}assertEquals(384,pos);}}
 @Test void parserAcceptsFragmentationAndRejectsBadCrc(){byte[] original=HexFormat.of().parseHex("5178a3010300001428dbff");FrameParser p=new FrameParser();assertTrue(p.accept(Arrays.copyOfRange(original,0,4)).isEmpty());var frames=p.accept(Arrays.copyOfRange(original,4,original.length));assertEquals(0xa3,frames.getFirst().command());assertArrayEquals(new byte[]{0,20,40},frames.getFirst().payload());byte[] corrupt=original.clone();corrupt[8]^=1;assertTrue(p.accept(corrupt).isEmpty());assertEquals(1,p.accept(original).size());}
 @Test void densityMatchesOriginalFormula(){
  var p=PrinterProfile.match("X6h").orElseThrow();var raster=new BufferedImage(384,1,BufferedImage.TYPE_INT_RGB);
  var dark=new X6Encoder().encode(raster,p,new ProtocolEncoder.Options(false,3,false));
  byte[] energy=dark.stream().filter(f->(f[2]&255)==0xaf).findFirst().orElseThrow();assertEquals(10400,(energy[6]&255)|((energy[7]&255)<<8));
  var light=new X6Encoder().encode(raster,p,new ProtocolEncoder.Options(true,1,false));energy=light.stream().filter(f->(f[2]&255)==0xaf).findFirst().orElseThrow();assertEquals(3500,(energy[6]&255)|((energy[7]&255)<<8));
 }
 @Test void encoderSelectsCompressedAndRawPerRow(){BufferedImage raster=new BufferedImage(384,2,BufferedImage.TYPE_INT_RGB);for(int x=0;x<384;x++){raster.setRGB(x,0,0xffffff);raster.setRGB(x,1,x%2==0?0:0xffffff);}var frames=new X6Encoder().encode(raster,PrinterProfile.match("X6h").orElseThrow(),new ProtocolEncoder.Options(false,2,false));assertTrue(frames.stream().anyMatch(f->(f[2]&255)==0xbf));assertTrue(frames.stream().anyMatch(f->(f[2]&255)==0xa2));}
}
