package com.muoniumplayer.core.viper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import repackage.com.jsyn.engine.SynthesisEngine;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出处理器钩子在合成引擎里的接线：在写设备之前、观察者之前运行；可改写缓冲；
 * 失败即卸载且不影响出声。用无头 {@link SynthesisEngine}（不开音频设备）直接驱动
 * 缓冲生成，与 {@code SpectrumOutputObserverTest} 同一套构造法。
 */
class ViperOutputProcessorTest {

    private static SynthesisEngine headless() throws Exception {
        SynthesisEngine engine = new SynthesisEngine(null);
        Method setup = SynthesisEngine.class.getDeclaredMethod("setupAudioBuffers", int.class, int.class);
        setup.setAccessible(true);
        setup.invoke(engine, 0, 2);
        return engine;
    }

    @Test
    @DisplayName("处理器改写缓冲，观察者看到的是改写后的内容（先处理、后观察）")
    void processorMutatesBeforeObserver() throws Exception {
        SynthesisEngine engine = headless();
        AtomicInteger channels = new AtomicInteger();
        AtomicInteger rate = new AtomicInteger();
        engine.setOutputProcessor((pcm, ch, sampleRate) -> {
            channels.set(ch);
            rate.set(sampleRate);
            java.util.Arrays.fill(pcm, 0.5);
        });
        AtomicBoolean observerSawProcessed = new AtomicBoolean(false);
        engine.setOutputObserver((pcm, ch, sampleRate) -> {
            boolean allHalf = true;
            for (double value : pcm) {
                if (value != 0.5) {
                    allHalf = false;
                    break;
                }
            }
            observerSawProcessed.set(allHalf);
        });
        engine.generateNextBuffer();
        assertEquals(2, channels.get());
        assertEquals(44100, rate.get());
        assertTrue(observerSawProcessed.get(), "观察者应看到处理器改写后的缓冲");
    }

    @Test
    @DisplayName("处理器抛异常时被卸载，合成循环继续且不再调用")
    void failingProcessorIsDetached() throws Exception {
        SynthesisEngine engine = headless();
        AtomicInteger calls = new AtomicInteger();
        engine.setOutputProcessor((pcm, ch, sampleRate) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("effect-only failure");
        });
        long before = engine.getFrameCount();
        assertDoesNotThrow(engine::generateNextBuffer);
        assertEquals(1, calls.get());
        assertDoesNotThrow(engine::generateNextBuffer);
        assertEquals(1, calls.get(), "失败后应已卸载");
        assertTrue(engine.getFrameCount() > before);
    }

    @Test
    @DisplayName("未配置的真处理器不改变缓冲（旁路）")
    void idleProcessorKeepsBufferUntouched() throws Exception {
        SynthesisEngine engine = headless();
        engine.setOutputProcessor(new ViperMasterProcessor());
        AtomicReference<double[]> seen = new AtomicReference<>();
        engine.setOutputObserver((pcm, ch, sampleRate) -> seen.set(pcm.clone()));
        engine.generateNextBuffer();
        double[] pcm = seen.get();
        assertNotNull(pcm);
        for (double value : pcm) {
            assertEquals(0.0, value, 1e-12, "无单元发声时缓冲为静音，处理器不得注入内容");
        }
    }
}
