package dev.sbelx.x6driver.core;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class DocumentTest {
 @TempDir Path temp;
 @Test void roundTripAndIndependentUndo()throws Exception{
  Document doc=new Document();doc.title="Привет, мир";var e=new Document.Element(Document.Kind.TEXT);e.text="Кириллица\nSecond line";e.rotation=15;doc.pages.getFirst().elements.add(e);
  var photo=new Document.Element(Document.Kind.IMAGE);photo.asset=doc.addAsset(new byte[]{1,2,3});doc.pages.getFirst().elements.add(photo);
  Path path=temp.resolve("doc.x6driver");DocumentIO.save(doc,path);Document restored=DocumentIO.load(path);
  assertEquals(doc.title,restored.title);assertEquals(e.text,restored.pages.getFirst().elements.getFirst().text);assertArrayEquals(new byte[]{1,2,3},restored.assets.get(photo.asset));
  UndoHistory history=new UndoHistory();history.checkpoint(doc);doc.pages.getFirst().elements.getFirst().text="Changed";doc.assets.get(photo.asset)[0]=9;
  Document previous=history.undo(doc);assertEquals(e.rotation,previous.pages.getFirst().elements.getFirst().rotation);assertEquals(1,previous.assets.get(photo.asset)[0]);assertEquals("Changed",history.redo(previous).pages.getFirst().elements.getFirst().text);
 }
 @Test void failedSaveDoesNotOverwriteExisting()throws Exception{Document d=new Document();Path path=temp.resolve("saved.x6driver");DocumentIO.save(d,path);byte[] original=Files.readAllBytes(path);d.widthMm=Double.NaN;assertThrows(IllegalArgumentException.class,()->DocumentIO.save(d,path));assertArrayEquals(original,Files.readAllBytes(path));}
 @Test void rejectArchiveTraversal()throws Exception{Path path=temp.resolve("bad.x6driver");try(var z=new ZipOutputStream(Files.newOutputStream(path))){z.putNextEntry(new ZipEntry("../bad"));z.write(1);z.closeEntry();}assertThrows(IOException.class,()->DocumentIO.load(path));assertFalse(Files.exists(temp.getParent().resolve("bad")));}
 @Test void missingAssetsAndFutureVersionsRejected(){Document d=new Document();d.version=2;assertThrows(IllegalArgumentException.class,d::validate);d.version=1;d.pages.getFirst().elements.add(new Document.Element(Document.Kind.IMAGE));assertThrows(IllegalArgumentException.class,d::validate);}
 @Test void historyRecoveryDoesNotReprint()throws Exception{
  try(var store=new HistoryStore(temp)){store.setting("language",(Object)"en");store.add("a",new Document(),"X6h");store.update("a","SENDING","");}
  try(var store=new HistoryStore(temp)){assertEquals("en",store.setting("language","ru"));assertEquals("INTERRUPTED",store.entries().getFirst().state());assertTrue(Files.exists(Path.of(store.entries().getFirst().path())));}
 }
}
