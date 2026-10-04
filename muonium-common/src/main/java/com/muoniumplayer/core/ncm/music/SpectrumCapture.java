package com.muoniumplayer.core.ncm.music;

import java.util.concurrent.atomic.AtomicInteger;

/** Single audio producer / single worker mailbox. Never locks, allocates or waits in accept(). */
final class SpectrumCapture {
    static final int SIZE = 2048;
    private static final int EMPTY = 0, READY = 1, READING = 2;
    private final float[] ringLeft = new float[SIZE];
    private final float[] ringRight = new float[SIZE];
    private final float[] frameLeft = new float[SIZE];
    private final float[] frameRight = new float[SIZE];
    private final AtomicInteger slot = new AtomicInteger(EMPTY);
    private volatile long leaseUntil;
    private int write, count, sincePublication;
    private int frameRate, ringRate;
    private long frameNanos, lastAccepted;

    void request(long now) { leaseUntil = now + 200_000_000L; }
    void suspend() { leaseUntil = 0L; }
    boolean requested(long now) { return leaseUntil != 0L && now < leaseUntil; }

    void accept(double[] pcm, int channels, int rate, long now) {
        if (!requested(now) || pcm == null || channels < 1 || rate < 8000) return;
        if (lastAccepted == 0L || now - lastAccepted > 250_000_000L || ringRate != rate) {
            write = count = sincePublication = 0;
            ringRate = rate;
        }
        lastAccepted = now;
        for (int i = 0; i + channels <= pcm.length; i += channels) {
            ringLeft[write] = finite(pcm[i]);
            ringRight[write] = finite(channels > 1 ? pcm[i + 1] : pcm[i]);
            write = (write + 1) & (SIZE - 1);
            count = Math.min(SIZE, count + 1);
            sincePublication++;
        }
        // The producer owns both frame arrays until it release-publishes READY. While READY or
        // READING it only updates the ring, dropping intermediate analysis frames by design.
        if (count < SIZE || sincePublication < rate / 30 || slot.get() != EMPTY) return;
        int tail = SIZE - write;
        System.arraycopy(ringLeft, write, frameLeft, 0, tail);
        System.arraycopy(ringLeft, 0, frameLeft, tail, write);
        System.arraycopy(ringRight, write, frameRight, 0, tail);
        System.arraycopy(ringRight, 0, frameRight, tail, write);
        frameRate = rate;
        frameNanos = now;
        sincePublication = 0;
        slot.set(READY);
    }

    boolean copyTo(float[] left, float[] right, long[] metadata) {
        if (!slot.compareAndSet(READY, READING)) return false;
        try {
            System.arraycopy(frameLeft, 0, left, 0, SIZE);
            System.arraycopy(frameRight, 0, right, 0, SIZE);
            metadata[0] = frameRate;
            metadata[1] = frameNanos;
            return true;
        } finally { slot.set(EMPTY); }
    }
    private static float finite(double sample) {
        return Double.isFinite(sample) ? (float)Math.max(-1.0, Math.min(1.0, sample)) : 0f;
    }
}
