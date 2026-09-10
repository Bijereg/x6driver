package dev.sbelx.x6driver.transport;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public interface PrinterTransport extends AutoCloseable {
    record Device(String id,String name,int rssi,List<String> services,String kind) { @Override public String toString(){return name+"  ·  "+rssi+" dBm";} }
    record Connection(String deviceId,String name,int maxWrite,String characteristic) {}
    record Event(String type,String message,Device device) {}
    void onEvent(Consumer<Event> listener);
    void onData(Consumer<byte[]> listener);
    CompletableFuture<Void> scan();
    CompletableFuture<Connection> connect(Device device);
    CompletableFuture<Void> write(byte[] bytes);
    CompletableFuture<Void> disconnect();
    boolean connected();
    Connection connection();
    @Override void close();
}
