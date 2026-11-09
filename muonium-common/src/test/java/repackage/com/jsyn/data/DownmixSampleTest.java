package repackage.com.jsyn.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「多声道音频降混成立体声」装饰器的回归测试——播放失败归因的源头之一。
 *
 * <p>引擎只认 1/2 声道（mono/stereo 读头 + 端口声道数校验），而 FLAC/WAV 解码把文件头的
 * 声道数原样透传：5.1 就是 6 声道 FloatSample，入队即抛 ChannelMismatchException，用户视角
 * 是「下载正常、解码正常、播放失败」。这层装饰器在解码出口把 >2 声道折算成立体声，所以
 * 最要紧的性质是：<b>包装后对外必须是 2 声道</b>（端口校验的判据），其次是折算数值正确、
 * 帧数不变、1/2 声道零包装。</p>
 *
 * <p>全部真跑：拿一块下标即取值的内存采样当原始数据，读它、比对数值，不启动 Minecraft、
 * 不放声音、不联网。</p>
 */
class DownmixSampleTest {

    private static final int RATE = 44_100;

    /** 一块可预测的原始素材：每个采样的值就是它的下标，读到哪个原始声道一目了然。 */
    private static final class Tap extends FloatSample {
        private final float[] values;

        Tap(int frames, int channels) {
            this.values = new float[frames * channels];
            for (int i = 0; i < values.length; i++) values[i] = i;
            allocate(frames, channels);
            write(values);
            setFrameRate(RATE);
        }

        float value(int index) {
            return index >= 0 && index < values.length ? values[index] : 0.0f;
        }
    }

    @Test
    @DisplayName("1/2 声道原样归还，不包装饰器（零开销直通）")
    void monoAndStereoPassThroughUntouched() {
        Tap mono = new Tap(8, 1);
        assertSame(mono, DownmixSample.stereoOrSame(mono));
        Tap stereo = new Tap(8, 2);
        assertSame(stereo, DownmixSample.stereoOrSame(stereo));
        assertSame(null, DownmixSample.stereoOrSame(null));
    }

    @Test
    @DisplayName("6 声道（5.1）包装后对外必须是 2 声道——端口校验的判据")
    void sixChannelWrapsToStereoChannels() {
        Tap raw = new Tap(4, 6);
        FloatSample mixed = DownmixSample.stereoOrSame(raw);
        assertTrue(mixed instanceof DownmixSample);
        assertEquals(2, mixed.getChannelsPerFrame(), "引擎端口校验只认 2");
        assertEquals(raw.getNumFrames(), mixed.getNumFrames(), "帧数不变，进度条与 automix 分析看到的还是同一首歌");
        assertEquals(raw.getFrameRate(), mixed.getFrameRate(), 0.0);
    }

    @Test
    @DisplayName("5.1 折算系数：L=FL+0.707·C+0.707·SL，R=FR+0.707·C+0.707·SR")
    void fivePointOneFoldDownCoefficients() {
        Tap raw = new Tap(1, 6);
        // 布局（WAV/FLAC 标准序）：FL=0 FR=1 C=2 LFE=3 SL=4 SR=5
        double fl = 0, fr = 1, c = 2, lfe = 3, sl = 4, sr = 5;
        double atten = Math.sqrt(0.5);
        FloatSample mixed = DownmixSample.stereoOrSame(raw);
        // LFE（ch3）按惯例不进折算。
        assertEquals(fl + atten * c + atten * sl, mixed.readDouble(0), 1e-9, "左 = FL + 0.707·C + 0.707·SL");
        assertEquals(fr + atten * c + atten * sr, mixed.readDouble(1), 1e-9, "右 = FR + 0.707·C + 0.707·SR");
    }

    @Test
    @DisplayName("布局不足的声道按 0 补：4 声道（4.0）没有后环绕项，3 声道没有环绕项")
    void smallerLayoutsSkipMissingChannels() {
        Tap quad = new Tap(1, 4);
        // 布局：FL=0 FR=1 C=2（或 BL/BR，系数同为 0.707 直加）——ch3 在 5.1 折算里不进左右。
        double atten = Math.sqrt(0.5);
        FloatSample mixed = DownmixSample.stereoOrSame(quad);
        assertEquals(0 + atten * 2, mixed.readDouble(0), 1e-9);
        assertEquals(1 + atten * 2, mixed.readDouble(1), 1e-9);

        Tap three = new Tap(1, 3);
        FloatSample mixed3 = DownmixSample.stereoOrSame(three);
        assertEquals(0 + atten * 2, mixed3.readDouble(0), 1e-9);
        assertEquals(1 + atten * 2, mixed3.readDouble(1), 1e-9);
    }

    @Test
    @DisplayName("帧对齐：立体声下标 (frame*2+ch) 映射到原始帧的对应声道，跨帧不串")
    void frameAlignmentAcrossFrames() {
        Tap raw = new Tap(3, 6);
        FloatSample mixed = DownmixSample.stereoOrSame(raw);
        double atten = Math.sqrt(0.5);
        // 帧 1 的左声道：原始下标 6..11。
        assertEquals(6 + atten * 8 + atten * 10, mixed.readDouble(2), 1e-9);
        // 帧 1 的右声道。
        assertEquals(7 + atten * 8 + atten * 11, mixed.readDouble(3), 1e-9);
        // 帧 2 的左右。
        assertEquals(12 + atten * 14 + atten * 16, mixed.readDouble(4), 1e-9);
        assertEquals(13 + atten * 14 + atten * 17, mixed.readDouble(5), 1e-9);
    }

    @Test
    @DisplayName("批量 read 与逐采样 readDouble 数值一致（读头与素材提取两条路都要对）")
    void bulkReadMatchesPerSampleReadDouble() {
        Tap raw = new Tap(8, 6);
        FloatSample mixed = DownmixSample.stereoOrSame(raw);
        float[] bulk = new float[4 * 2];
        mixed.read(2, bulk, 0, 4);
        for (int i = 0; i < bulk.length; i++) {
            // read() 走 float 存储：47 这个量级的 float 精度约 3e-6，容差按 float 语义放宽。
            assertEquals((float) mixed.readDouble(2 * 2 + i), bulk[i], 1e-5);
        }
        // 起点合法但越过末尾的批量读：越界帧补 0，不许炸内层下标。
        float[] over = new float[4 * 2];
        mixed.read(6, over, 0, 4);
        assertEquals((float) mixed.readDouble(12), over[0], 1e-5, "帧 6 还在界内");
        assertEquals(0.0f, over[4], 0.0f, "帧 8 起越界，补 0");
        assertEquals(0.0f, over[7], 0.0f);
    }

    @Test
    @DisplayName("越界判定按立体声帧宽算：读头判尾用")
    void overflowIsMeasuredInStereoSamples() {
        Tap raw = new Tap(4, 6);
        FloatSample mixed = DownmixSample.stereoOrSame(raw);
        assertTrue(mixed.isIndexOverflow(8), "4 帧 × 2 声道 = 8，第 8 个采样起越界");
        assertEquals(raw.isIndexOverflow(7), mixed.isIndexOverflow(7));
    }

    @Test
    @DisplayName("只读装饰器：writeDouble 不落任何东西")
    void writeIsIgnored() {
        Tap raw = new Tap(2, 6);
        FloatSample mixed = DownmixSample.stereoOrSame(raw);
        // 先记下降混后的期望值（L = FL + 0.707·C + 0.707·SL，原始下标 0/2/4），再写再读。
        double expected = mixed.readDouble(0);
        mixed.writeDouble(0, 999.0);
        assertEquals(expected, mixed.readDouble(0), 0.0, "写不进去，读回降混原值");
    }
}
