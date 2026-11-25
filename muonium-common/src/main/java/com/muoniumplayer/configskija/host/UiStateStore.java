package com.muoniumplayer.configskija.host;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All new UI file IO is off the client/render thread; each write uses an immutable snapshot. */
final class UiStateStore {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{ Thread t=new Thread(r,"MuoniumConfig-Skija-state");t.setDaemon(true);return t; });
    static { Runtime.getRuntime().addShutdownHook(new Thread(()-> { IO.shutdown();try { IO.awaitTermination(2,java.util.concurrent.TimeUnit.SECONDS); }catch(InterruptedException ex) { Thread.currentThread().interrupt(); } },"MuoniumConfig-Skija-flush")); }
    private final Path file;
    UiStateStore(Path file) { this.file=file; }
    CompletableFuture<Map<String,String>> load() { return CompletableFuture.supplyAsync(()-> { try {
        if(!Files.exists(file)) return Map.of();
        Properties values=new Properties();try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) { values.load(reader); }
        Map<String,String> out=new HashMap<>();for(String key:values.stringPropertyNames()) out.put(key,values.getProperty(key));return out;
    }catch(IOException ex) { throw new java.io.UncheckedIOException(ex); } },IO); }
    CompletableFuture<Void> save(Map<String,String> snapshot) { return CompletableFuture.runAsync(()-> { try {
        Files.createDirectories(file.getParent());Properties props=new Properties();props.putAll(snapshot);
        Path temporary=Files.createTempFile(file.getParent(),"ui-state-",".tmp");
        try {
            try(Writer writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)) { props.store(writer,"MuoniumConfig Skija UI state; music settings belong to MuoniumPlayer"); }
            try { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException ex) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
        }finally { Files.deleteIfExists(temporary); }
    }catch(IOException ex) { throw new java.io.UncheckedIOException(ex); } },IO); }
}
