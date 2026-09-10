package dev.sbelx.x6driver.service;
import dev.sbelx.x6driver.transport.PrintQueue;
import dev.sbelx.x6driver.imaging.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.io.IOException;
import java.util.stream.IntStream;

public final class PdfPages implements PrintQueue.Pages {
    private final PDDocument pdf;
    private final int[] pages;
    private final int orientation;
    public PdfPages(Path path,JobSettings settings)throws IOException{
        pdf=Loader.loadPDF(path.toFile());
        try{
            int count=pdf.getNumberOfPages();if(count<1||count>200)throw new IOException("PDF must contain 1–200 pages");
            pages=settings.pageRanges().isEmpty()?IntStream.range(0,count).toArray():PdfSupport.parsePages(settings.pageRanges(),count);
            orientation=settings.orientation();
        }catch(Exception e){pdf.close();throw new IOException(e.getMessage(),e);}
    }
    public int count(){return pages.length;}
    public BufferedImage render(int index)throws IOException{
        int page=pages[index];var box=pdf.getPage(page).getCropBox();
        double width=box.getWidth(),height=box.getHeight();
        if(!Double.isFinite(width*height)||width<=0||height<=0)throw new IOException("Invalid PDF geometry");
        float dpi=(float)Math.min(200,72*Math.sqrt(12_000_000/(width*height)));
        BufferedImage image=new PDFRenderer(pdf).renderImageWithDPI(page,dpi);
        // PDFRenderer already applies the PDF page's /Rotate. Only apply an explicit job orientation.
        if(orientation!=3){
            boolean quarter=orientation==4||orientation==5;
            BufferedImage rotated=new BufferedImage(quarter?image.getHeight():image.getWidth(),quarter?image.getWidth():image.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=rotated.createGraphics();g.translate(rotated.getWidth()/2.0,rotated.getHeight()/2.0);
            g.rotate(orientation==4?Math.PI/2:orientation==5?-Math.PI/2:Math.PI);g.drawImage(image,-image.getWidth()/2,-image.getHeight()/2,null);g.dispose();image=rotated;
        }
        return RollImage.crop(image);
    }
    public void close()throws IOException{pdf.close();}
}
