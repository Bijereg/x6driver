package dev.sbelx.x6driver.imaging;

import dev.sbelx.x6driver.core.Document;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.awt.*;
import java.awt.geom.*;
import java.awt.font.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.text.AttributedString;
import java.util.Map;
import javax.imageio.ImageIO;

/** Single renderer for editing, export and printing. No platform/UI types in the document model. */
public final class DocumentRenderer {
    public BufferedImage render(Document doc,int page,int dpi) throws IOException {
        doc.validate(); if(dpi<72||dpi>600)throw new IllegalArgumentException("Invalid DPI");
        double scale=dpi/25.4; int w=(int)Math.round(doc.widthMm*scale), h=(int)Math.round(doc.heightMm*scale);
        if((long)w*h>40_000_000)throw new IOException("Page is too large to render");
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,w,h);g.scale(scale,scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.clip(new Rectangle2D.Double(doc.marginMm,doc.marginMm,doc.widthMm-2*doc.marginMm,doc.heightMm-2*doc.marginMm));
            for(Document.Element e:doc.pages.get(page).elements)if(!e.hidden)draw(g,doc,e,scale);
        } finally {g.dispose();}return image;
    }
    private void draw(Graphics2D base,Document doc,Document.Element e,double scale)throws IOException {
        Graphics2D g=(Graphics2D)base.create();
        try {
            g.translate(e.x+e.width/2,e.y+e.height/2);g.rotate(Math.toRadians(e.rotation));g.translate(-e.width/2,-e.height/2);
            g.setColor(Color.decode(e.color));g.setStroke(new BasicStroke((float)Math.max(.01,e.stroke),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            switch(e.kind){
                case TEXT -> {
                    g.clip(new Rectangle2D.Double(0,0,e.width,e.height));
                    Font font=new Font(e.font,e.bold?Font.BOLD:Font.PLAIN,12).deriveFont((float)(e.fontSize*25.4/72));g.setFont(font);
                    float y=0;
                    for(String line:e.text.split("\n",-1)) {
                        if(line.isEmpty()){y+=font.getSize2D()*1.25f;continue;}
                        AttributedString text=new AttributedString(line);text.addAttribute(TextAttribute.FONT,font);
                        LineBreakMeasurer lm=new LineBreakMeasurer(text.getIterator(),g.getFontRenderContext());
                        while(lm.getPosition()<line.length()){
                            TextLayout layout=lm.nextLayout((float)e.width);y+=layout.getAscent();layout.draw(g,0,y);y+=layout.getDescent()+layout.getLeading()+font.getSize2D()*.1f;
                        }
                    }
                }
                case IMAGE -> {
                    BufferedImage source=decode(doc.assets.get(e.asset));
                    int x=(int)Math.floor(e.cropX*source.getWidth()),y=(int)Math.floor(e.cropY*source.getHeight());
                    int w=Math.max(1,Math.min(source.getWidth()-x,(int)Math.round(e.cropWidth*source.getWidth()))),h=Math.max(1,Math.min(source.getHeight()-y,(int)Math.round(e.cropHeight*source.getHeight())));
                    BufferedImage crop=source.getSubimage(x,y,w,h);
                    g.drawImage(crop,AffineTransform.getScaleInstance(e.width/w,e.height/h),null);
                }
                case RECTANGLE,ELLIPSE -> {Shape shape=e.kind==Document.Kind.RECTANGLE?new Rectangle2D.Double(0,0,e.width,e.height):new Ellipse2D.Double(0,0,e.width,e.height);if(e.filled)g.fill(shape);else g.draw(shape);}
                case LINE -> g.draw(new Line2D.Double(0,0,e.width,e.height));
                case DRAWING -> {Path2D path=new Path2D.Double();boolean first=true;for(Document.Point p:e.points){if(first){path.moveTo(p.x()*e.width,p.y()*e.height);first=false;}else path.lineTo(p.x()*e.width,p.y()*e.height);}g.draw(path);}
                case QR,BARCODE -> {
                    try{
                        int w=Math.max(1,(int)Math.round(e.width*scale)),h=Math.max(1,(int)Math.round(e.height*scale));
                        BitMatrix matrix=new MultiFormatWriter().encode(e.text,e.kind==Document.Kind.QR?BarcodeFormat.QR_CODE:BarcodeFormat.CODE_128,w,h,Map.of(EncodeHintType.CHARACTER_SET,"UTF-8",EncodeHintType.MARGIN,e.kind==Document.Kind.QR?4:10));
                        BufferedImage code=new BufferedImage(matrix.getWidth(),matrix.getHeight(),BufferedImage.TYPE_INT_RGB);
                        for(int cy=0;cy<code.getHeight();cy++)for(int cx=0;cx<code.getWidth();cx++)code.setRGB(cx,cy,matrix.get(cx,cy)?Color.BLACK.getRGB():Color.WHITE.getRGB());
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                        g.drawImage(code,AffineTransform.getScaleInstance(e.width/code.getWidth(),e.height/code.getHeight()),null);
                    }catch(Exception ex){throw new IOException("Invalid "+e.kind+": "+ex.getMessage(),ex);}
                }
            }
        }finally{g.dispose();}
    }
    public static BufferedImage decode(byte[] bytes)throws IOException {
        if(bytes==null)throw new IOException("Missing image");
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IOException("Unsupported image");
            var reader=readers.next();try{reader.setInput(stream);if((long)reader.getWidth(0)*reader.getHeight(0)>40_000_000)throw new IOException("Image exceeds 40 megapixels");return reader.read(0);}finally{reader.dispose();}
        }
    }
    public static byte[] png(BufferedImage image)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
}
