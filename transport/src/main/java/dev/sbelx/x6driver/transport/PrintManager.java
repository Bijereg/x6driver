package dev.sbelx.x6driver.transport;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
public interface PrintManager extends AutoCloseable {
    void onJob(Consumer<PrintJob> listener);
    void inspect();
    Collection<PrintJob> jobs();
    CompletableFuture<PrintJob> submit(PrintJob job) throws Exception;
    void cancel(String id);
    String firmware();
    int status();
    void close();
}
