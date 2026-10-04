package com.muoniumplayer.core.ncm.music;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import repackage.com.jsyn.engine.SynthesisEngine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpectrumOutputObserverTest {
    private static SynthesisEngine headless() throws Exception {
        SynthesisEngine engine=new SynthesisEngine(null);
        Method setup=SynthesisEngine.class.getDeclaredMethod("setupAudioBuffers",int.class,int.class);
        setup.setAccessible(true);setup.invoke(engine,0,2);return engine;
    }
    @Test void failingObserverIsDetachedWithoutStoppingSynthesis() throws Exception {
        SynthesisEngine engine=headless();AtomicInteger calls=new AtomicInteger();
        engine.setOutputObserver((pcm,channels,rate)->{calls.incrementAndGet();throw new IllegalStateException("visualization-only failure");});
        assertDoesNotThrow(engine::generateNextBuffer);long first=engine.getFrameCount();
        assertDoesNotThrow(engine::generateNextBuffer);
        assertEquals(1,calls.get());assertTrue(engine.getFrameCount()>first);
    }
    @Test void observerSeesTheCompletedOutputBufferAndCanBeDisabled() throws Exception {
        SynthesisEngine engine=headless();AtomicInteger calls=new AtomicInteger();
        SpectrumCapture capture=new SpectrumCapture();capture.request(1_000_000_000L);
        engine.setOutputObserver((pcm,channels,rate)->{
            assertEquals(2,channels);assertEquals(44100,rate);assertTrue(pcm.length>0);
            double[] before=pcm.clone();capture.accept(pcm,channels,rate,1_010_000_000L);
            assertArrayEquals(before,pcm);calls.incrementAndGet();
        });
        engine.generateNextBuffer();assertEquals(1,calls.get());
        engine.setOutputObserver(null);engine.generateNextBuffer();assertEquals(1,calls.get());
    }
}
