package dev.sbelx.x6driver.imaging;
import dev.sbelx.x6driver.core.Document;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public final class PdfSupport {
    public static int pageCount(Path file)throws IOException{try(PDDocument pdf=Loader.loadPDF(file.toFile())){return pdf.getNumberOfPages();}}
    public static int[] parsePages(String input,int total){
        SortedSet<Integer> selected=new TreeSet<>();
        for(String group:input.replace(" ","").split(",")){
            String[] range=group.split("-",-1);if(range.length>2)throw new IllegalArgumentException("Invalid page range");
            int start=Integer.parseInt(range[0]),end=range.length==2?Integer.parseInt(range[1]):start;
            if(start<1||end>total||start>end||end-start>199)throw new IllegalArgumentException("Invalid page range");
            for(int i=start;i<=end;i++)selected.add(i-1);
        }
        if(selected.isEmpty()||selected.size()>200)throw new IllegalArgumentException("Select 1–200 pages");
        return selected.stream().mapToInt(Integer::intValue).toArray();
    }
    public static Document importPages(Path file,int[] pages,double widthMm)throws IOException{
        Document doc=new Document();doc.title=file.getFileName().toString();doc.widthMm=widthMm;doc.marginMm=1;doc.pages.clear();
        try(PDDocument pdf=Loader.loadPDF(file.toFile())){
            PDFRenderer renderer=new PDFRenderer(pdf);double maxHeight=10;
            for(int index:pages){
                if(index<0||index>=pdf.getNumberOfPages())throw new IOException("Page out of range");
                var box=pdf.getPage(index).getCropBox();if(box.getWidth()<=0||box.getHeight()<=0)throw new IOException("Invalid PDF page");
                float dpi=(float)Math.min(150,Math.sqrt(12_000_000.0/(box.getWidth()*box.getHeight()))*72);
                var image=renderer.renderImageWithDPI(index,dpi);Document.Element e=new Document.Element(Document.Kind.IMAGE);
                e.x=1;e.y=1;e.width=widthMm-2;e.height=e.width*image.getHeight()/image.getWidth();e.asset=doc.addAsset(DocumentRenderer.png(image));
                maxHeight=Math.max(maxHeight,e.height+2);Document.Page page=new Document.Page();page.elements.add(e);doc.pages.add(page);
            }
            doc.heightMm=Math.min(2000,maxHeight);doc.validate();return doc;
        }
    }
    public static void export(Document doc,Path target)throws IOException{
        Path path=target.toAbsolutePath();Files.createDirectories(path.getParent());Path tmp=Files.createTempFile(path.getParent(),".x6driver-pdf-",".pdf");
        try {
            try(PDDocument pdf=new PDDocument()){
                DocumentRenderer renderer=new DocumentRenderer();
                for(int i=0;i<doc.pages.size();i++){
                    PDPage page=new PDPage(new PDRectangle((float)(doc.widthMm*72/25.4),(float)(doc.heightMm*72/25.4)));pdf.addPage(page);
                    var image=LosslessFactory.createFromImage(pdf,renderer.render(doc,i,200));
                    try(PDPageContentStream content=new PDPageContentStream(pdf,page)){content.drawImage(image,0,0,page.getMediaBox().getWidth(),page.getMediaBox().getHeight());}
                }
                pdf.getDocumentInformation().setTitle(doc.title);pdf.save(tmp.toFile());
            }
            try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
}
