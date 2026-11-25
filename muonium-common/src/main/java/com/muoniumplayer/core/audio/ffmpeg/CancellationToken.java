package com.muoniumplayer.core.audio.ffmpeg;

import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared, one-way cancellation. Calling cancel never touches a native pointer. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }

    public void throwIfCancelled() throws InterruptedIOException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Audio decode cancelled");
        }
    }
}
