package dev.sbelx.x6driver.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;

/** Coordinates are millimetres; font size is points; pages share the same paper size. */
public final class Document {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public int version = 1;
    public String title = "Новый документ";
    public double widthMm = 48.768, heightMm = 80, marginMm = 2;
    public List<Page> pages = new ArrayList<>(List.of(new Page()));
    public transient Map<String, byte[]> assets = new HashMap<>();
    public static final class Page { public List<Element> elements = new ArrayList<>(); }
    public enum Kind { TEXT, IMAGE, RECTANGLE, ELLIPSE, LINE, DRAWING, QR, BARCODE }
    public static final class Element {
        public String id = UUID.randomUUID().toString();
        public Kind kind = Kind.TEXT;
        public double x = 3, y = 3, width = 40, height = 12, rotation;
        public String text = "X6", asset = "", font = "SansSerif", color = "#17221e";
        public double fontSize = 12, stroke = 0.35;
        public boolean bold, filled, hidden;
        // Normalised crop rectangle in source-image space.
        public double cropX, cropY, cropWidth = 1, cropHeight = 1;
        public List<Point> points = new ArrayList<>();
        public Element() {}
        public Element(Kind kind) { this.kind = kind; }
        public Element copy() { Element e = JSON.fromJson(JSON.toJson(this), Element.class); e.id = UUID.randomUUID().toString(); return e; }
        @Override public String toString() { return kind + (text.isBlank() ? "" : " · " + text.lines().findFirst().orElse("")); }
    }
    public record Point(double x, double y) {}
    public Document copy() {
        Document d = JSON.fromJson(JSON.toJson(this), Document.class);
        d.assets = new HashMap<>(); assets.forEach((k,v)->d.assets.put(k,v.clone())); return d;
    }
    public String addAsset(byte[] data) { String id = UUID.randomUUID().toString(); assets.put(id, data.clone()); return id; }
    public void validate() {
        if (version != 1) throw new IllegalArgumentException("Unsupported document version: " + version);
        if (!finite(widthMm, heightMm, marginMm) || widthMm < 10 || widthMm > 300 || heightMm < 10 || heightMm > 2000 || marginMm < 0 || marginMm * 2 >= Math.min(widthMm, heightMm)) throw new IllegalArgumentException("Invalid paper size or margins");
        if (title == null || pages == null || pages.isEmpty() || pages.size() > 200) throw new IllegalArgumentException("Invalid document");
        int count = 0;
        for (Page p : pages) {
            if (p == null || p.elements == null) throw new IllegalArgumentException("Invalid page");
            for (Element e : p.elements) {
                if (++count > 20000 || e == null || e.kind == null || !finite(e.x,e.y,e.width,e.height,e.rotation,e.fontSize,e.stroke,e.cropX,e.cropY,e.cropWidth,e.cropHeight) || e.width <= 0 || e.height <= 0 || e.fontSize < 1 || e.fontSize > 300 || e.stroke < 0 || e.stroke > 20) throw new IllegalArgumentException("Invalid element");
                if (e.text == null || e.text.length() > 100000 || e.font == null || e.color == null || !e.color.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid element content");
                if (e.cropX < 0 || e.cropY < 0 || e.cropWidth <= 0 || e.cropHeight <= 0 || e.cropX + e.cropWidth > 1.000001 || e.cropY + e.cropHeight > 1.000001) throw new IllegalArgumentException("Invalid image crop");
                if (e.points == null || e.points.size() > 200000 || e.points.stream().anyMatch(pt -> pt == null || !finite(pt.x(),pt.y()))) throw new IllegalArgumentException("Invalid drawing");
                if (e.kind == Kind.IMAGE && !assets.containsKey(e.asset)) throw new IllegalArgumentException("Missing image attachment");
            }
        }
    }
    private static boolean finite(double... v) { return Arrays.stream(v).allMatch(Double::isFinite); }
}
