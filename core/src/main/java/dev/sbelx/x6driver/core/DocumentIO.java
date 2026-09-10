package dev.sbelx.x6driver.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** A .x6driver file is a ZIP containing document.json and immutable image attachments. */
public final class DocumentIO {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    public static void save(Document doc, Path destination) throws IOException {
        doc.validate(); Path target = destination.toAbsolutePath(); Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".x6driver-", ".tmp");
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                entry(out, "document.json", Document.JSON.toJson(doc).getBytes(StandardCharsets.UTF_8));
                Set<String> used = new HashSet<>();
                doc.pages.forEach(p -> p.elements.forEach(e -> { if (e.kind == Document.Kind.IMAGE) used.add(e.asset); }));
                for (String id : used) {
                    if (!id.matches("[a-zA-Z0-9-]+")) throw new IOException("Invalid asset id");
                    entry(out, "assets/" + id, doc.assets.get(id));
                }
            }
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    private static void entry(ZipOutputStream out, String name, byte[] bytes) throws IOException { out.putNextEntry(new ZipEntry(name)); out.write(bytes); out.closeEntry(); }
    public static Document load(Path source) throws IOException {
        Map<String, byte[]> entries = new HashMap<>(); long total = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) {
                String name = e.getName();
                if (!name.equals("document.json") && !name.matches("assets/[a-zA-Z0-9-]+")) throw new IOException("Unexpected archive entry");
                if (entries.containsKey(name) || entries.size() > 20000) throw new IOException("Invalid archive");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1;) { total += n; if (total > MAX_BYTES) throw new IOException("Document exceeds 128 MB"); bytes.write(buffer,0,n); }
                entries.put(name, bytes.toByteArray());
            }
        }
        if (!entries.containsKey("document.json")) throw new IOException("Missing document.json");
        try {
            Document doc = Document.JSON.fromJson(new String(entries.get("document.json"), StandardCharsets.UTF_8), Document.class);
            doc.assets = new HashMap<>(); entries.forEach((k,v) -> { if(k.startsWith("assets/")) doc.assets.put(k.substring(7),v); });
            doc.validate(); return doc;
        } catch (RuntimeException e) { throw new IOException("Invalid X6 document: " + e.getMessage(),e); }
    }
}
