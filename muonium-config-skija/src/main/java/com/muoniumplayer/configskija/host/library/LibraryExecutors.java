package com.muoniumplayer.configskija.host.library;

import java.util.concurrent.*;

public final class LibraryExecutors {
    private LibraryExecutors(){}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"MuoniumConfig-library-io");t.setDaemon(true);return t;});
    public static Executor worker(){return IO;}
}
