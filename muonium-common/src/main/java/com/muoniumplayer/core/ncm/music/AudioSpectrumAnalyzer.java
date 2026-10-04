package com.muoniumplayer.core.ncm.music;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import repackage.processing.sound.Engine;

/** Passive final-mix capture. Only a visible consumer schedules FFT; audio thread only copies PCM. */
public final class AudioSpectrumAnalyzer {
    public static final int BANDS = SpectrumTransform.BANDS;
    private static final SpectrumCapture CAPTURE = new SpectrumCapture();
    private static final SpectrumTransform TRANSFORM = new SpectrumTransform();
    private static final float[] LEFT = new float[SpectrumCapture.SIZE], RIGHT = new float[SpectrumCapture.SIZE];
    private static final float[] WORK = new float[BANDS];
    private static final long[] METADATA = new long[2];
    private static final AtomicBoolean PENDING = new AtomicBoolean();
    private static volatile Frame latest = new Frame(new float[BANDS],0L);
    private static boolean attached;
    private static volatile boolean unavailable;
    private static long nextAnalysis;
    private static final Runnable ANALYZE = () -> {
        try {
            if (!CAPTURE.requested(System.nanoTime()) || !CAPTURE.copyTo(LEFT,RIGHT,METADATA)) return;
            TRANSFORM.analyze(LEFT,RIGHT,(int)METADATA[0],WORK);
            latest=new Frame(WORK.clone(),METADATA[1]);
        } catch (Throwable failure) {
            unavailable=true;
            CAPTURE.suspend();
        } finally { PENDING.set(false); }
    };
    private static final class Worker {
        static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
            Thread thread=new Thread(task,"Muonium-Spectrum");
            thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
        });
    }
    public static final class Frame {
        private final float[] bands;
        public final long capturedAt;
        private Frame(float[] bands,long capturedAt) { this.bands=bands; this.capturedAt=capturedAt; }
        public void copyTo(float[] destination) { System.arraycopy(bands,0,destination,0,BANDS); }
    }
    private AudioSpectrumAnalyzer() { }
    /** Render-thread entry point. At most one FFT task may be queued or executing. */
    public static Frame requestFrame(long now) {
        if (unavailable) return latest;
        try {
            if (!attached) {
                Engine.getEngine().setOutputObserver((pcm,channels,rate) -> CAPTURE.accept(pcm,channels,rate,System.nanoTime()));
                attached=true;
            }
            CAPTURE.request(now);
            if (now >= nextAnalysis && PENDING.compareAndSet(false,true)) {
                nextAnalysis=now+33_333_333L;
                try { Worker.EXECUTOR.execute(ANALYZE); }
                catch (RuntimeException failure) { PENDING.set(false); throw failure; }
            }
        } catch (Throwable failure) { unavailable=true; CAPTURE.suspend(); }
        return latest;
    }
    public static void suspend() { CAPTURE.suspend(); }
}
