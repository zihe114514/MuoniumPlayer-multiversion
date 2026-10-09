package com.muoniumplayer.core.viper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ViperMasterProcessor} 的行为回归：输出段三段（音量 → pan → 限幅器）的顺序与量值
 * 必须钉死，因为这是「预设听起来对不对」里唯一被本仓库实际执行的部分。
 *
 * <p>钉住的量值：256 样本前瞻（延迟 256）、默认门限 1.0 下把 2.0 峰值压到 1.0、
 * 门限 0.8 时压到 0.8、小信号逐位直通（仅延迟）、声道 pan 只作用于对应声道。</p>
 */
class ViperMasterProcessorTest {

    private static ViperPreset preset(String json) {
        return ViperPresetParser.parse(json, "处理器测试");
    }

    private static double[] constant(int frames, int channels, double value) {
        double[] buffer = new double[frames * channels];
        java.util.Arrays.fill(buffer, value);
        return buffer;
    }

    @Test
    @DisplayName("停用时不触碰任何样本")
    void bypassWhenInactive() {
        ViperMasterProcessor processor = new ViperMasterProcessor();
        processor.configure(false, preset("{\"schemaVersion\":2.1,\"masterLimiter\":{\"outputVolume\":0.5}}"));
        assertFalse(processor.isActive());
        double[] buffer = constant(512, 2, 1.0);
        double[] original = buffer.clone();
        processor.process(buffer, 2, 44100);
        assertArrayEquals(original, buffer, "停用时必须逐位不变");

        processor.configure(true, null);
        processor.process(buffer, 2, 44100);
        assertArrayEquals(original, buffer, "没有预设时不处理");
    }

    @Test
    @DisplayName("输出音量在采样前生效，限幅器带来 256 样本的前瞻延迟")
    void volumeAndLookahead() {
        ViperMasterProcessor processor = new ViperMasterProcessor();
        processor.configure(true, preset(
                "{\"schemaVersion\":2.1,\"masterLimiter\":{\"threshold\":1.0,\"outputVolume\":0.5,\"channelPan\":0.0}}"));
        double[] buffer = constant(512, 2, 1.0);
        processor.process(buffer, 2, 44100);

        // 前瞻 256：前 256 帧仍在环形缓冲里。
        assertEquals(0.0, buffer[0], 1e-9);
        assertEquals(0.0, buffer[255 * 2], 1e-9);
        // 第 256 帧起输出 = 缩放后的延迟样本（0.5），且限幅不介入（峰值 0.5 < 门限 1.0）。
        assertEquals(0.5, buffer[256 * 2], 1e-6);
        assertEquals(0.5, buffer[256 * 2 + 1], 1e-6);
        assertEquals(0.5, buffer[511 * 2], 1e-6);
    }

    @Test
    @DisplayName("声道 pan 只降低对应声道（-1 完全右偏时右声道归零）")
    void channelPan() {
        ViperMasterProcessor processor = new ViperMasterProcessor();
        processor.configure(true, preset(
                "{\"schemaVersion\":2.1,\"masterLimiter\":{\"threshold\":1.0,\"outputVolume\":1.0,\"channelPan\":-1.0}}"));
        double[] buffer = constant(512, 2, 0.5);
        processor.process(buffer, 2, 44100);
        assertEquals(0.5, buffer[256 * 2], 1e-6, "左声道保持 1.0 增益");
        assertEquals(0.0, buffer[256 * 2 + 1], 1e-9, "右声道被 pan 归零");
    }

    @Test
    @DisplayName("默认门限 1.0 把 2.0 的峰值约束到 1.0，且不削减门限以下信号")
    void limiterCapsAtGate() {
        ViperMasterProcessor processor = new ViperMasterProcessor();
        processor.configure(true, preset(
                "{\"schemaVersion\":2.1,\"masterLimiter\":{\"threshold\":1.0,\"outputVolume\":1.0,\"channelPan\":0.0}}"));
        double[] buffer = constant(700, 2, 2.0);
        processor.process(buffer, 2, 44100);
        assertEquals(1.0, buffer[256 * 2], 1e-6, "窗口峰值 2.0 → 增益 0.5 → 输出 1.0");
        assertEquals(1.0, buffer[600 * 2], 1e-6);
        double max = 0.0;
        for (double sample : buffer) {
            max = Math.max(max, Math.abs(sample));
        }
        assertTrue(max <= 1.0 + 1e-6, "整段不得超过门限");

        ViperMasterProcessor gate08 = new ViperMasterProcessor();
        gate08.configure(true, preset(
                "{\"schemaVersion\":2.1,\"masterLimiter\":{\"threshold\":0.8,\"outputVolume\":1.0,\"channelPan\":0.0}}"));
        double[] second = constant(700, 2, 2.0);
        gate08.process(second, 2, 44100);
        assertEquals(0.8, second[256 * 2], 1e-6, "门限 0.8 → 输出压到 0.8");
    }

    @Test
    @DisplayName("门限以下的信号只经历延迟，不改变幅度")
    void belowGateIsIdentityWithDelay() {
        ViperMasterProcessor processor = new ViperMasterProcessor();
        processor.configure(true, preset(
                "{\"schemaVersion\":2.1,\"masterLimiter\":{\"threshold\":1.0,\"outputVolume\":1.0,\"channelPan\":0.0}}"));
        double[] buffer = constant(600, 2, 0.25);
        processor.process(buffer, 2, 44100);
        assertEquals(0.25, buffer[256 * 2], 1e-9);
        assertEquals(0.25, buffer[500 * 2 + 1], 1e-9);
        assertEquals(0.0, buffer[255 * 2], 1e-9);
    }

    @Test
    @DisplayName("重新 configure 会清空限幅器状态（前瞻窗口复位）")
    void reconfigureResetsLimiter() {
        ViperMasterProcessor processor = new ViperMasterProcessor();
        ViperPreset first = preset(
                "{\"schemaVersion\":2.1,\"masterLimiter\":{\"threshold\":1.0,\"outputVolume\":1.0,\"channelPan\":0.0}}");
        processor.configure(true, first);
        double[] warmup = constant(600, 2, 0.5);
        processor.process(warmup, 2, 44100);
        assertEquals(0.5, warmup[512 * 2], 1e-9);

        processor.configure(true, first);
        double[] again = constant(600, 2, 0.5);
        processor.process(again, 2, 44100);
        assertEquals(0.0, again[0], 1e-9, "复位后第一个样本仍在窗口里");
        assertEquals(0.0, again[255 * 2], 1e-9);
        assertEquals(0.5, again[256 * 2], 1e-9);
    }

    @Test
    @DisplayName("单声道只走主声道路径，不因 pan 出错")
    void monoChannels() {
        ViperMasterProcessor processor = new ViperMasterProcessor();
        processor.configure(true, preset(
                "{\"schemaVersion\":2.1,\"masterLimiter\":{\"threshold\":1.0,\"outputVolume\":1.0,\"channelPan\":-1.0}}"));
        double[] buffer = constant(300, 1, 0.5);
        processor.process(buffer, 1, 44100);
        assertEquals(0.0, buffer[0], 1e-9);
        assertEquals(0.5, buffer[256], 1e-9, "单声道不应用 pan");
    }
}
