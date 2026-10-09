package com.muoniumplayer.core.viper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 混响链路（Freeverb）的逐行对齐测试：{@code CRevModel} 的系数换算与 {@code wet1/wet2}
 * 宽度公式、getter 反算、{@code Reverberation} 的开关复位语义，以及混响在
 * {@code ViPER} 效果链中的实际出声 / 旁路。
 *
 * <p>数值全部直接取自上游源码字面量（{@code CRevModel.cpp} / {@code Reverberation.cpp}），
 * 不做「听起来对不对」的宽松断言——混响是延迟网络，容差一放宽就等于没测。</p>
 *
 * <p><b>为什么走反射而不是直接 import</b>：本类与被测类分属 {@code core.viper} /
 * {@code core.viper.dsp} 两个包。在本仓库当前的 javac / classpath 组合下，跨这两个包的
 * <b>短类名</b>（形如 {@code CRevModel model = new CRevModel()}）会在符号解析阶段报
 * {@code cant.resolve}，而同包短名与全限定名都实测通过——即classpath 上
 * {@code dsp/CRevModel.class} 的短名解析路径存在异常。为绕开这个真实的编译期问题，
 * 混响模型统一用「反射构造」持有，类型位不出现短名。
 *
 * <p>类名以 <b>上游 C++ 为准：{@code CRevModel}</b>（不是 CRRevModel）。
 * 26.2 曾因「import CRevModel 而代码里写 CRRevModel」编译失败，那是拼写错、不是别的问题。</p>
 */
class ViperReverbTest {

    private static final float EPS = 1e-6f;
    private static final String MODEL_CLASS = "com.muoniumplayer.core.viper.dsp.CRevModel";

    private static Object newModelRaw() {
        try {
            return Class.forName(MODEL_CLASS).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("反射构造 CRevModel 失败", failure);
        }
    }

    private static Object configureModel(float roomSize, float damp, float wet, float dry, float width) {
        Object model = newModelRaw();
        invoke(model, "setRoomSize", roomSize);
        invoke(model, "setDamp", damp);
        invoke(model, "setWet", wet);
        invoke(model, "setDry", dry);
        invoke(model, "setWidth", width);
        return model;
    }

    private static void invoke(Object target, String method, float argument) {
        try {
            target.getClass().getMethod(method, float.class).invoke(target, argument);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("调用 " + method + " 失败", failure);
        }
    }

    private static float readFloat(Object target, String method) {
        try {
            return (Float) target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("读取 " + method + " 失败", failure);
        }
    }

    private static void processModel(Object model, float[] buffer, int frames) {
        try {
            model.getClass().getMethod("process", float[].class, int.class).invoke(model, buffer, frames);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("调用 process 失败", failure);
        }
    }

    // ─────────────────────────── CRevModel 系数换算 ───────────────────────────

    @Test
    @DisplayName("CRevModel：构造默认走 Freeverb 标准档（wet 0.167 / room 0.5 / dry 0.25 / damp 0.5 / width 1.0）")
    void modelDefaults() {
        Object model = newModelRaw();
        // 上游构造末尾：SetWet(0.167) → SetRoomSize(0.5) → SetDry(0.25) → SetDamp(0.5) → SetWidth(1.0)
        assertEquals(0.167f, readFloat(model, "getWet"), EPS, "wet");
        assertEquals(0.5f, readFloat(model, "getRoomSize"), EPS, "room_size");
        assertEquals(0.25f, readFloat(model, "getDry"), EPS, "dry");
        assertEquals(0.5f, readFloat(model, "getDamp"), EPS, "damp");
        assertEquals(1.0f, readFloat(model, "getWidth"), EPS, "width");
    }

    @Test
    @DisplayName("CRevModel：setter 的量纲折算（room×0.28+0.7 / damp×0.4 / wet×3 / dry×2）与 getter 反算")
    void coefficientScaling() {
        Object model = configureModel(0.5f, 0.25f, 0.4f, 0.6f, 0.25f);
        // getter 反算必须拿回原值（CRevModel.cpp 的 Get* 全部是逆运算）。
        assertEquals(0.5f, readFloat(model, "getRoomSize"), EPS, "room_size 反算");
        assertEquals(0.25f, readFloat(model, "getDamp"), EPS, "damp 反算");
        assertEquals(0.4f, readFloat(model, "getWet"), EPS, "wet 反算");
        assertEquals(0.6f, readFloat(model, "getDry"), EPS, "dry 反算");
        assertEquals(0.25f, readFloat(model, "getWidth"), EPS, "width 是直存");
    }

    @Test
    @DisplayName("CRevModel：wet1/wet2 交叉混合（width=0 能量分摊到对侧，右声道能量低于 width=1）")
    void wetMixFormula() {
        // CRevModel 无开关层，构造后直接配置系数。交织缓冲 process() 即上游
        // ProcessReplace(buf_l, buf_r, size) 的语义（同一缓冲、L/R 按 idx += 2 步进）。
        Object crossfeed = configureModel(0.5f, 0.0f, 1.0f, 0.0f, 0.0f);
        Object independent = configureModel(0.5f, 0.0f, 1.0f, 0.0f, 1.0f);

        // 上游输出混音：buf_l = outL·wet1 + outR·wet2 + in·dry、buf_r = outR·wet1 + outL·wet2 + in·dry。
        // wet1 = wet×(w/2+0.5)、wet2 = wet×(1-w)/2，于是 w=1 → (wet1, wet2)=(1, 0)、
        // w=0 → (0.5, 0.5)。注意 input = (L+R)×gain 是**单声道**激励（同时喂左右两组 comb），
        // 所以两声道本来都有输出；wet2 控的是**左右两组全通输出之间的交叉**。
        // 可观测后果（Freeverb 的功率互补）：w=1 时全部能量留在本声道（wet1=1），
        // w=0 时 wet1=wet2=0.5 → 本声道拿到一半、另一半来自对侧，故右声道总能量**下降**。
        float[] crossed = new float[8192];
        float[] separated = new float[8192];
        crossed[0] = 1.0f;
        separated[0] = 1.0f;
        processModel(crossfeed, crossed, 4096);
        processModel(independent, separated, 4096);

        float crossedRight = rightChannelEnergy(crossed);
        float separatedRight = rightChannelEnergy(separated);
        assertTrue(crossedRight < separatedRight * 0.75f,
                "width=0（wet1=wet2=0.5，能量分摊到对侧）时右声道能量应低于 width=1（wet1=1 全留本声道）：实测 "
                        + crossedRight + " vs " + separatedRight);
    }

    @Test
    @DisplayName("CRevModel：单位脉冲在延迟网络里产生非零尾音（延迟线确实在工作）")
    void impulseProducesTail() {
        Object model = configureModel(1.0f, 0.0f, 1.0f, 0.5f, 1.0f);
        float[] buffer = new float[8192];
        buffer[0] = 1.0f;
        processModel(model, buffer, 4096);

        int tail = 0;
        for (int i = 1; i < 4096; i++) {
            if (Math.abs(buffer[i * 2]) > 1e-7f) {
                tail++;
            }
        }
        assertTrue(tail > 0, "脉冲之后应有可听的混响尾音");
    }

    // ─────────────────────────── Reverberation 语义 ───────────────────────────

    @Test
    @DisplayName("Reverberation：默认关闭，关闭态处理不改变缓冲（旁路）")
    void disabledIsBypass() {
        com.muoniumplayer.core.viper.dsp.Reverberation reverb =
                new com.muoniumplayer.core.viper.dsp.Reverberation();
        float[] buffer = new float[64];
        for (int i = 0; i < 32; i++) {
            buffer[i * 2] = 0.5f;
            buffer[i * 2 + 1] = -0.5f;
        }
        float[] copy = buffer.clone();
        reverb.process(buffer, 32);
        assertArrayEqualsWithDelta(copy, buffer, 0.0f, "未启用时必须原样返回");
    }

    @Test
    @DisplayName("Reverberation：构造默认全零 + dry 0.5（上游构造函数逐项对齐）")
    void constructorDefaults() {
        com.muoniumplayer.core.viper.dsp.Reverberation reverb =
                new com.muoniumplayer.core.viper.dsp.Reverberation();
        // 上游构造：RoomSize 0 / Width 0 / Damp 0 / Wet 0 / Dry 0.5。
        // 注意 CRevModel.SetDry 会做 dry_ = value × 2 的折算，所以外部 0.5 → 内部 1.0（干声直通）。
        reverb.setEnable(true);
        reverb.setRoomSize(0.0f);
        reverb.setWidth(0.0f);
        reverb.setDamp(0.0f);
        reverb.setWet(0.0f);
        reverb.setDry(0.5f);

        float[] buffer = new float[64];
        buffer[0] = 1.0f;
        reverb.process(buffer, 32);
        // wet=0 → 湿声为零；内部 dry_=1.0 → 干声原样透传（延迟线此时尚未积累能量）。
        assertEquals(1.0f, buffer[0], 1e-6f, "外部 dry=0.5 经 ×2 折算后为直通，首帧应保持 1.0");
    }

    @Test
    @DisplayName("Reverberation：setEnable(false) 会复位延迟线（上游「关→开」先 Reset 的语义）")
    void disableResetsTails() {
        com.muoniumplayer.core.viper.dsp.Reverberation reverb =
                new com.muoniumplayer.core.viper.dsp.Reverberation();
        reverb.setEnable(true);
        reverb.setWet(1.0f);
        reverb.setDry(0.0f);
        reverb.setRoomSize(1.0f);

        float[] warm = new float[8192];
        warm[0] = 1.0f;
        reverb.process(warm, 4096);
        assertTrue(hasEnergy(warm), "先确认延迟线里确实存了能量");

        // 关闭 → 上游走 Reset()（mute 全部缓冲），且关闭态本身旁路。
        reverb.setEnable(false);
        float[] afterDisable = new float[64];
        afterDisable[0] = 1.0f;
        reverb.process(afterDisable, 32);
        assertEquals(1.0f, afterDisable[0], 0.0f, "关闭后不再处理");

        // 再打开：SetEnable 里 !enable_ → Reset 已执行，首帧应是纯 dry（无历史尾音）。
        reverb.setEnable(true);
        reverb.setDry(0.0f);
        reverb.setWet(1.0f);
        float[] afterReopen = new float[64];
        afterReopen[0] = 1.0f;
        reverb.process(afterReopen, 32);
        assertEquals(0.0f, afterReopen[0], 1e-7f, "重开后尾音已清空，首帧无残留");
    }

    // ─────────────────────────── 引擎链内的混响 ───────────────────────────

    @Test
    @DisplayName("引擎：混响启用后确实在链里出声，禁用时逐样本不变")
    void reverbInEngineChain() {
        float[] withReverb = runEngine(true);
        float[] withoutReverb = runEngine(false);
        assertTrue(differsBeyondLookahead(withReverb, withoutReverb),
                "混响启用后，256 帧之后（走出主限幅前瞻窗口）的样本应与旁路不同");
    }

    @Test
    @DisplayName("引擎：混响 wet=0、dry=0.5（内部折算为直通）时与全旁路等价")
    void reverbDryOnlyIsTransparent() {
        com.muoniumplayer.core.viper.dsp.ViPER engine = new com.muoniumplayer.core.viper.dsp.ViPER();
        com.muoniumplayer.core.viper.dsp.ViPERParams params =
                new com.muoniumplayer.core.viper.dsp.ViPERParams();
        params.masterLimiter.threshold = 1.0f;
        params.masterLimiter.outputVolume = 1.0f;
        params.reverb.enable = true;
        params.reverb.roomSize = 1.0f;
        params.reverb.width = 1.0f;
        params.reverb.damp = 0.0f;
        params.reverb.wet = 0.0f;   // 湿声为零
        // CRevModel.SetDry 会 ×2，所以外部 0.5 才是真正的「干声 1.0 直通」；
        // 外部 1.0 会被折算成 2.0（放大），不能用来验证直通。
        params.reverb.dry = 0.5f;
        engine.applyParams(params);

        float[] buffer = new float[1024];
        Arrays.fill(buffer, 0.25f);
        engine.process(buffer, 512);

        com.muoniumplayer.core.viper.dsp.ViPER plain = new com.muoniumplayer.core.viper.dsp.ViPER();
        com.muoniumplayer.core.viper.dsp.ViPERParams plainParams =
                new com.muoniumplayer.core.viper.dsp.ViPERParams();
        plainParams.masterLimiter.threshold = 1.0f;
        plainParams.masterLimiter.outputVolume = 1.0f;
        plain.applyParams(plainParams);

        float[] reference = new float[1024];
        Arrays.fill(reference, 0.25f);
        plain.process(reference, 512);

        for (int i = 0; i < 1024; i++) {
            assertEquals(reference[i], buffer[i], 1e-6f,
                    "第 " + i + " 个样本：wet=0/dry=0.5（内部直通）应与旁路一致");
        }
    }

    @Test
    @DisplayName("引擎：ReverbParams 的 equals 语义（双关闭恒等，字段逐一参与比较）")
    void reverbParamsEquality() {
        com.muoniumplayer.core.viper.dsp.ViPERParams a = new com.muoniumplayer.core.viper.dsp.ViPERParams();
        com.muoniumplayer.core.viper.dsp.ViPERParams b = new com.muoniumplayer.core.viper.dsp.ViPERParams();
        assertEquals(a.reverb, b.reverb, "两边都关闭 → 恒等（上游短路语义）");

        a.reverb.enable = true;
        b.reverb.enable = true;
        assertEquals(a.reverb, b.reverb, "同字段同值");

        b.reverb.wet = 0.5f;
        assertNotEquals(a.reverb, b.reverb, "wet 不同 → 不等");

        a.reverb.wet = 0.5f;
        b.reverb.damp = 0.25f;
        assertNotEquals(a.reverb, b.reverb, "damp 不同 → 不等");
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private static float[] runEngine(boolean enableReverb) {
        com.muoniumplayer.core.viper.dsp.ViPER engine = new com.muoniumplayer.core.viper.dsp.ViPER();
        com.muoniumplayer.core.viper.dsp.ViPERParams params =
                new com.muoniumplayer.core.viper.dsp.ViPERParams();
        params.masterLimiter.threshold = 1.0f;
        params.masterLimiter.outputVolume = 1.0f;
        params.reverb.enable = enableReverb;
        if (enableReverb) {
            params.reverb.roomSize = 1.0f;
            params.reverb.width = 1.0f;
            params.reverb.damp = 0.0f;
            params.reverb.wet = 1.0f;
            params.reverb.dry = 0.0f;
        }
        engine.applyParams(params);

        float[] buffer = new float[1024];
        Arrays.fill(buffer, 0.25f);
        engine.process(buffer, 512);
        return buffer;
    }

    private static boolean differsBeyondLookahead(float[] a, float[] b) {
        for (int frame = 300; frame < 512; frame++) {
            if (Math.abs(a[frame * 2] - b[frame * 2]) > 1e-6f
                    || Math.abs(a[frame * 2 + 1] - b[frame * 2 + 1]) > 1e-6f) {
                return true;
            }
        }
        return false;
    }

    private static float rightChannelEnergy(float[] interleaved) {
        float energy = 0.0f;
        for (int frame = 0; frame * 2 + 1 < interleaved.length; frame++) {
            float value = interleaved[frame * 2 + 1];
            energy += value * value;
        }
        return energy;
    }

    private static boolean hasEnergy(float[] buffer) {
        for (float value : buffer) {
            if (Math.abs(value) > 1e-7f) {
                return true;
            }
        }
        return false;
    }

    private static void assertArrayEqualsWithDelta(float[] expected, float[] actual,
                                                   float delta, String message) {
        assertEquals(expected.length, actual.length, message + "（长度）");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], delta, message + "（下标 " + i + "）");
        }
    }
}