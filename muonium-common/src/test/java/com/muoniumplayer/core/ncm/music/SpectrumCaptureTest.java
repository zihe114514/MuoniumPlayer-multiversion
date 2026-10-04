package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpectrumCaptureTest {
    @Test
    void capturePublishesOnlyAfterFullFrameAndHonorsRenderLease() {
        SpectrumCapture capture=new SpectrumCapture();
        double[] pcm=new double[512*2];
        capture.request(1_000_000_000L);
        capture.accept(pcm,2,44100,1_000_000_000L);
        assertFalse(capture.copyTo(new float[SpectrumCapture.SIZE],new float[SpectrumCapture.SIZE],new long[2]));
        capture.accept(new double[1536*2],2,44100,1_010_000_000L);
        float[] left=new float[SpectrumCapture.SIZE],right=new float[SpectrumCapture.SIZE];long[] meta=new long[2];
        assertTrue(capture.copyTo(left,right,meta));
        assertEquals(44100,meta[0]);
        capture.suspend();
        capture.accept(new double[2048*2],2,44100,1_020_000_000L);
        assertFalse(capture.copyTo(left,right,meta));
    }
    @Test
    void hiddenAndExpiredLeasesDoNotCopyPcm() {
        SpectrumCapture capture=new SpectrumCapture();
        double[] pcm=new double[SpectrumCapture.SIZE*2];
        float[] left=new float[SpectrumCapture.SIZE],right=new float[SpectrumCapture.SIZE];
        long[] metadata=new long[2];
        capture.accept(pcm,2,44100,1_000_000_000L);
        assertFalse(capture.copyTo(left,right,metadata));
        capture.request(1_000_000_000L);
        capture.accept(pcm,2,44100,1_201_000_000L);
        assertFalse(capture.copyTo(left,right,metadata));
    }
    @Test
    void readyMailboxIsNeverOverwrittenAndNoSourceSamplesAreMutated() {
        SpectrumCapture capture=new SpectrumCapture();capture.request(1_000_000_000L);
        double[] pcm=new double[SpectrumCapture.SIZE*2];
        for(int i=0;i<SpectrumCapture.SIZE;i++){pcm[2*i]=.25;pcm[2*i+1]=-.5;}
        double[] original=pcm.clone();
        capture.accept(pcm,2,44100,1_010_000_000L);
        java.util.Arrays.fill(pcm,.75);
        capture.accept(pcm,2,44100,1_030_000_000L);
        float[] left=new float[SpectrumCapture.SIZE],right=new float[SpectrumCapture.SIZE];long[] meta=new long[2];
        assertTrue(capture.copyTo(left,right,meta));
        for(int i=0;i<left.length;i++){assertEquals(.25f,left[i]);assertEquals(-.5f,right[i]);}
        assertEquals(1_010_000_000L,meta[1]);
        assertFalse(capture.copyTo(left,right,meta));
        assertEquals(.25,original[0]);
    }
    @Test
    void monoAndNonFiniteSamplesAreHandledWithoutChangingTheInput() {
        SpectrumCapture capture=new SpectrumCapture();capture.request(1_000_000_000L);
        double[] pcm=new double[SpectrumCapture.SIZE];java.util.Arrays.fill(pcm,.3);
        pcm[0]=Double.NaN;pcm[1]=Double.POSITIVE_INFINITY;pcm[2]=2.0;
        capture.accept(pcm,1,44100,1_010_000_000L);
        float[] left=new float[SpectrumCapture.SIZE],right=new float[SpectrumCapture.SIZE];long[] meta=new long[2];
        assertTrue(capture.copyTo(left,right,meta));assertArrayEquals(left,right);
        assertEquals(0f,left[0]);assertEquals(0f,left[1]);assertEquals(1f,left[2]);
        assertTrue(Double.isNaN(pcm[0]));assertEquals(2.0,pcm[2]);
    }
    @Test
    void longHiddenGapRestartsTheWindowInsteadOfMixingTwoSongs() {
        SpectrumCapture capture=new SpectrumCapture();capture.request(1_000_000_000L);
        capture.accept(new double[1024*2],2,44100,1_010_000_000L);
        capture.request(2_000_000_000L);
        capture.accept(new double[1024*2],2,44100,2_010_000_000L);
        assertFalse(capture.copyTo(new float[SpectrumCapture.SIZE],new float[SpectrumCapture.SIZE],new long[2]));
    }

    @Test
    void producerAndConsumerNeverSeeTornStereoFrames() throws Exception {
        SpectrumCapture capture=new SpectrumCapture();capture.request(1_000_000_000L);
        java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        double[] pcm=new double[128];for(int i=0;i<64;i++){pcm[i*2]=.25;pcm[i*2+1]=-.75;}
        try {
            java.util.concurrent.Future<Integer> consumer=executor.submit(()->{
                float[] left=new float[SpectrumCapture.SIZE],right=new float[SpectrumCapture.SIZE];long[] metadata=new long[2];int seen=0;
                while(true) {
                    if(capture.copyTo(left,right,metadata)) {
                        for(int i=0;i<left.length;i++){assertEquals(.25f,left[i]);assertEquals(-.75f,right[i]);}
                        seen++;
                    } else if(done.get())break;else Thread.onSpinWait();
                }
                return seen;
            });
            java.util.concurrent.Future<?> producer=executor.submit(()->{
                try{for(int i=0;i<4000;i++)capture.accept(pcm,2,44100,1_010_000_000L);}finally{done.set(true);}
            });
            producer.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(consumer.get(10,java.util.concurrent.TimeUnit.SECONDS)>0);
        } finally {executor.shutdownNow();}
    }

}
