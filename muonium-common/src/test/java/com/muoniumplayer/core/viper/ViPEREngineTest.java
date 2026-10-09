package com.muoniumplayer.core.viper;

import com.muoniumplayer.core.viper.dsp.ViPER;
import com.muoniumplayer.core.viper.dsp.ViPERParams;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ViPER} 引擎冒烟：Process 链（输出段顺序：scale → pan → 逐样本限幅）、
 * 复位请求、核装载/卸载、GainReduction 读数。深层 DSP 的正确性由逐模块移植细节保证，
 * 这里钉的是「链的接线与语义边界」。
 */
class ViPEREngineTest {

    private static float[] constant(int frames, float value) {
        float[] buffer = new float[frames * 2];
        Arrays.fill(buffer, value);
        return buffer;
    }

    @Test
    @DisplayName("静音进、静音出（全默认参数）")
    void silenceInSilenceOut() {
        ViPER engine = new ViPER();
        float[] buffer = new float[1024];
        engine.process(buffer, 512);
        for (float v : buffer) {
            assertEquals(0.0f, v, 0.0f);
        }
    }

    @Test
    @DisplayName("主链输出段：音量缩放 → 限幅器逐样本 256 前瞻（与旧实现一致）")
    void masterChainScaleAndLimiter() {
        ViPER engine = new ViPER();
        ViPERParams params = new ViPERParams();
        params.masterLimiter.threshold = 1.0f;
        params.masterLimiter.outputVolume = 0.5f;
        params.masterLimiter.channelPan = 0.0f;
        engine.applyParams(params);

        float[] buffer = constant(512, 1.0f);
        engine.process(buffer, 512);
        assertEquals(0.0f, buffer[0], 0.0f, "前 256 帧在前瞻窗口内");
        assertEquals(0.0f, buffer[255 * 2], 0.0f);
        assertEquals(0.5f, buffer[256 * 2], 1e-6f, "第 256 帧起 = 缩放后的延迟样本");
        assertEquals(0.5f, buffer[511 * 2 + 1], 1e-6f);
    }

    @Test
    @DisplayName("复位请求在下一缓冲开头被消费（限幅器延迟线清空）")
    void resetRequestConsumedOnNextProcess() {
        ViPER engine = new ViPER();
        ViPERParams params = new ViPERParams();
        params.masterLimiter.outputVolume = 1.0f;
        engine.applyParams(params);

        float[] warm = constant(600, 0.5f);
        engine.process(warm, 600);
        assertEquals(0.5f, warm[512 * 2], 1e-6f);

        engine.requestEffectsReset();
        float[] again = constant(600, 0.5f);
        engine.process(again, 600);
        assertEquals(0.0f, again[0], 0.0f, "复位后第一个样本仍在窗口里");
        assertEquals(0.5f, again[256 * 2], 1e-6f);
        assertEquals(1200L, engine.getProcessedFrames(), "帧计数覆盖两次处理");
    }

    @Test
    @DisplayName("卷积核装载/卸载：成功返回 kernelId，卸载后归零")
    void kernelLoadAndUnload() {
        ViPER engine = new ViPER();
        float[] kernel = new float[64];
        kernel[0] = 1.0f;
        Integer id = engine.loadConvolverKernel(kernel, 64, 1, 77);
        assertEquals(Integer.valueOf(77), id);
        assertEquals(77, engine.getConvolverKernelID());

        engine.unloadConvolverKernel();
        assertEquals(0, engine.getConvolverKernelID());
    }

    @Test
    @DisplayName("非法核参数被拒绝（null / 帧数不足 / 声道非法）")
    void kernelRejectsInvalid() {
        ViPER engine = new ViPER();
        assertNull(engine.loadConvolverKernel(null, 64, 1, 1));
        assertNull(engine.loadConvolverKernel(new float[8], 8, 1, 1));
        assertNull(engine.loadConvolverKernel(new float[64], 64, 3, 1));
    }

    @Test
    @DisplayName("压缩器增益缩减读数：6 项（5 段 MBC + FET）")
    void gainReductionReadout() {
        ViPER engine = new ViPER();
        float[] values = engine.getCompressorGainReductionDb();
        assertEquals(6, values.length);
        for (float v : values) {
            assertTrue(Float.isFinite(v));
        }
    }

    @Test
    @DisplayName("参数变化检测：相同参数重复下发不重复触发重逻辑（applyParams 幂等）")
    void applyParamsIdempotent() {
        ViPER engine = new ViPER();
        ViPERParams params = new ViPERParams();
        params.masterLimiter.outputVolume = 0.8f;
        engine.applyParams(params);
        float[] first = constant(600, 1.0f);
        engine.process(first, 600);

        // 同一份参数再次 apply：masterLimiter 相等 → 跳过下发；处理结果只受状态影响。
        engine.applyParams(params);
        float[] second = constant(600, 1.0f);
        engine.process(second, 600);
        assertEquals(first[256 * 2], second[256 * 2], 0.0f);

        // 参数变化 → 下发（音量变化在下一缓冲生效，链序不变）。
        ViPERParams changed = new ViPERParams();
        changed.masterLimiter.outputVolume = 0.4f;
        engine.applyParams(changed);
        assertNotEquals(params.masterLimiter.outputVolume, changed.masterLimiter.outputVolume);
        float[] third = constant(600, 1.0f);
        engine.process(third, 600);
        assertEquals(0.4f, third[256 * 2], 1e-6f);
    }
}
