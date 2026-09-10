package dev.sbelx.x6driver.core;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class HistoryStore implements AutoCloseable {
    private final Connection db;
    public final Path directory;
    public record Entry(String id, String title, String device, String state, String path, String time, String message) {}
    public HistoryStore(Path directory) throws Exception {
        this.directory = directory; Files.createDirectories(directory.resolve("jobs")); Files.createDirectories(directory.resolve("templates"));
        db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("x6driver.db"));
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY,title TEXT NOT NULL,device TEXT NOT NULL,state TEXT NOT NULL,path TEXT NOT NULL,time TEXT NOT NULL,message TEXT NOT NULL DEFAULT '')");
            s.execute("UPDATE jobs SET state='INTERRUPTED',message='Application stopped; manual retry required' WHERE state IN ('QUEUED','SENDING','WAITING')");
        }
    }
    public synchronized String setting(String key, String fallback) {
        try (PreparedStatement s=db.prepareStatement("SELECT value FROM settings WHERE key=?")) {s.setString(1,key);try(ResultSet r=s.executeQuery()){return r.next()?r.getString(1):fallback;}} catch(SQLException e){throw new IllegalStateException(e);}
    }
    public synchronized void setting(String key, Object value) {
        try (PreparedStatement s=db.prepareStatement("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {s.setString(1,key);s.setString(2,value.toString());s.executeUpdate();}catch(SQLException e){throw new IllegalStateException(e);}
    }
    public synchronized Path add(String id, Document document, String device) throws Exception {
        Path path=directory.resolve("jobs").resolve(id+".x6driver"); DocumentIO.save(document,path);
        try(PreparedStatement s=db.prepareStatement("INSERT INTO jobs(id,title,device,state,path,time) VALUES(?,?,?,'QUEUED',?,?)")) {
            s.setString(1,id);s.setString(2,document.title);s.setString(3,device);s.setString(4,path.toString());s.setString(5,Instant.now().toString());s.executeUpdate();
        }return path;
    }
    public synchronized void update(String id,String state,String message) {
        try(PreparedStatement s=db.prepareStatement("UPDATE jobs SET state=?,message=? WHERE id=?")) {s.setString(1,state);s.setString(2,message);s.setString(3,id);s.executeUpdate();}catch(SQLException e){throw new IllegalStateException(e);}
    }
    public synchronized List<Entry> entries() {
        List<Entry> out=new ArrayList<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT * FROM jobs ORDER BY time DESC LIMIT 500")) {while(r.next())out.add(new Entry(r.getString("id"),r.getString("title"),r.getString("device"),r.getString("state"),r.getString("path"),r.getString("time"),r.getString("message")));}catch(SQLException e){throw new IllegalStateException(e);}return out;
    }
    @Override public synchronized void close() throws SQLException {db.close();}
}
