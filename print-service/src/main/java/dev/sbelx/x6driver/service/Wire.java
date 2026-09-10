package dev.sbelx.x6driver.service;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.*;
import java.net.*;
import java.nio.file.*;

/** Length-prefixed JSON header followed by a bounded binary payload; local Unix sockets only. */
public final class Wire {
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ipc-deadlines").factory());
    public static final Gson JSON=new Gson();
    public static final long MAX_PAYLOAD=128L*1024*1024;
    static void header(DataOutputStream out,JsonObject value)throws IOException{
        byte[] bytes=JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>1024*1024)throw new IOException("IPC header too large");
        out.writeInt(bytes.length);out.write(bytes);
    }
    static JsonObject header(DataInputStream in)throws IOException{
        int n=in.readInt();if(n<2||n>1024*1024)throw new IOException("Invalid IPC header length");
        byte[] bytes=in.readNBytes(n);if(bytes.length!=n)throw new EOFException();
        return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
    }
    public static JsonObject call(JsonObject request,Path payload)throws IOException{
        request.addProperty("v",1);
        try(SocketChannel socket=SocketChannel.open(StandardProtocolFamily.UNIX)){
            var timeout=DEADLINES.schedule(()->{try{socket.close();}catch(IOException ignored){}},45,java.util.concurrent.TimeUnit.SECONDS);
            try {
            socket.connect(UnixDomainSocketAddress.of(ServicePaths.socket()));
            var out=new DataOutputStream(Channels.newOutputStream(socket));
            header(out,request);long size=payload==null?0:Files.size(payload);
            if(size>MAX_PAYLOAD)throw new IOException("Job exceeds 128 MB");
            out.writeLong(size);if(payload!=null)Files.copy(payload,out);out.flush();
            JsonObject response=header(new DataInputStream(Channels.newInputStream(socket)));
            if(response.has("error"))throw new IOException(response.get("error").getAsString());
            return response;
            }finally{timeout.cancel(false);}
        }
    }
    public static JsonObject request(String op){JsonObject q=new JsonObject();q.addProperty("op",op);q.addProperty("v",1);return q;}
}
