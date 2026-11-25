package com.muoniumplayer.configskija.host.migration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Session-owned worker I/O + client delivery; no game API, old writes or application singleton. */
public final class MigrationService implements AutoCloseable {
    private final ExecutorService worker;
    private final Executor client;
    private final boolean ownsWorker;
    private final Set<CompletableFuture<LegacyMigration.Snapshot>> pending=new LinkedHashSet<>();
    private long generation;
    private boolean closed;
    public MigrationService(Executor client){this(Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"muonium-config-legacy-read");thread.setDaemon(true);return thread;}),client,true);}
    public MigrationService(ExecutorService worker,Executor client){this(worker,client,false);}
    private MigrationService(ExecutorService worker,Executor client,boolean owned){this.worker=Objects.requireNonNull(worker);this.client=Objects.requireNonNull(client);ownsWorker=owned;}
    public CompletableFuture<LegacyMigration.Snapshot> start(LegacyMigration.Request request,Consumer<LegacyMigration.Snapshot> apply,Consumer<Throwable> failed){
        Objects.requireNonNull(request);Objects.requireNonNull(apply);Objects.requireNonNull(failed);
        long ticket;List<CompletableFuture<LegacyMigration.Snapshot>> obsolete;CompletableFuture<LegacyMigration.Snapshot> result=new CompletableFuture<>();
        synchronized(this){if(closed)return CompletableFuture.failedFuture(new IllegalStateException("migration service closed"));ticket=++generation;obsolete=new ArrayList<>(pending);pending.add(result);}
        cancel(obsolete);
        result.whenComplete((value,error)->{synchronized(MigrationService.this){pending.remove(result);}});
        try{worker.execute(()->{
            synchronized(MigrationService.this){if(closed||ticket!=generation||result.isDone())return;}
            LegacyMigration.Snapshot snapshot=null;Throwable error=null;
            try{snapshot=LegacyMigration.read(request);}catch(Throwable readError){error=readError;}
            LegacyMigration.Snapshot ready=snapshot;Throwable failure=error;
            try{client.execute(()->{
                synchronized(MigrationService.this){if(closed||ticket!=generation||result.isDone()){result.completeExceptionally(new java.util.concurrent.CancellationException("obsolete migration snapshot"));return;}}
                if(failure!=null){try{failed.accept(failure);}finally{result.completeExceptionally(failure);}return;}
                try{apply.accept(ready);result.complete(ready);}catch(Throwable applyError){try{failed.accept(applyError);}finally{result.completeExceptionally(applyError);}}
            });}catch(Throwable dispatchError){result.completeExceptionally(dispatchError);}
        });}catch(Throwable dispatchError){result.completeExceptionally(dispatchError);}
        return result;
    }
    public void invalidate(){List<CompletableFuture<LegacyMigration.Snapshot>> obsolete;synchronized(this){generation++;obsolete=new ArrayList<>(pending);}cancel(obsolete);}
    private static void cancel(List<CompletableFuture<LegacyMigration.Snapshot>> results){for(var result:results)result.completeExceptionally(new java.util.concurrent.CancellationException("obsolete migration snapshot"));}
    @Override public void close(){List<CompletableFuture<LegacyMigration.Snapshot>> obsolete;synchronized(this){if(closed)return;closed=true;generation++;obsolete=new ArrayList<>(pending);}cancel(obsolete);if(ownsWorker)worker.shutdownNow();}
}
