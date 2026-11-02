package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 失焦压低音乐：目标倍率、时间斜坡与档位归一化的纯逻辑，外加输出路径的回归锁。
 */
class FocusVolumeDuckerTest {

    private static final long MS = 1_000_000L;

    private static final long OUT = FocusVolumeDucker.FADE_OUT_NANOS;
    private static final long IN = FocusVolumeDucker.FADE_IN_NANOS;

    @Test
    void gainEndpoints() {
        assertEquals(1f, FocusVolumeDucker.gainFor(0f, 25));
        assertEquals(.25f, FocusVolumeDucker.gainFor(1f, 25), 1e-4);
        assertEquals(.5f, FocusVolumeDucker.gainFor(1f, 50), 1e-4);
        assertEquals(0f, FocusVolumeDucker.gainFor(1f, 0), "静音档到终点必须是真正的 0");
        assertTrue(FocusVolumeDucker.gainFor(.99f, 0) > 0f, "静音档途中按分贝下限走，不提前掐断");
    }

    @Test
    void fadeTakesTheSameTimeForEveryLevel() {
        FocusVolumeDucker.Ramp down = new FocusVolumeDucker.Ramp(0L, 0f, true);
        for (int level : FocusVolumeDucker.LEVELS) {
            assertTrue(FocusVolumeDucker.gainFor(down.progressAt(OUT / 2), level) > level / 100f,
                    "档位 " + level + "：半程不该已经到底");
            assertEquals(level / 100f, FocusVolumeDucker.gainFor(down.progressAt(OUT), level), 1e-4,
                    "档位 " + level + "：满时长正好到底");
        }
    }

    @Test
    void fadesAreLongEnoughToHear() {
        assertTrue(OUT >= 1_000 * MS, "压下至少 1 秒，不然听着就是「啪」一下");
        assertTrue(IN >= 600 * MS, "恢复也要有过渡");
    }

    @Test
    void curveIsPerceptuallyEvenInTheMiddle() {
        // 分贝插值 + smoothstep：中段每 100ms 掉的分贝数应当大致相等，不能前平后陡。
        FocusVolumeDucker.Ramp down = new FocusVolumeDucker.Ramp(0L, 0f, true);
        double a = db(FocusVolumeDucker.gainFor(down.progressAt(OUT * 4 / 10), 25));
        double b = db(FocusVolumeDucker.gainFor(down.progressAt(OUT * 5 / 10), 25));
        double c = db(FocusVolumeDucker.gainFor(down.progressAt(OUT * 6 / 10), 25));
        assertEquals(a - b, b - c, .3, "中段两段等长时间的分贝降幅应接近");
    }

    @Test
    void startsSoftlyWithoutAStep() {
        // 起步那 20ms 的变化必须远小于 0.5 dB：这正是旧实现「卡一下」的位置。
        FocusVolumeDucker.Ramp down = new FocusVolumeDucker.Ramp(0L, 0f, true);
        double first = db(FocusVolumeDucker.gainFor(down.progressAt(20 * MS), 0));
        assertTrue(first > -.5, "起步 20ms 就掉了 " + first + " dB");
        FocusVolumeDucker.Ramp up = new FocusVolumeDucker.Ramp(0L, 1f, false);
        float g0 = FocusVolumeDucker.gainFor(up.progressAt(0L), 25);
        float g1 = FocusVolumeDucker.gainFor(up.progressAt(20 * MS), 25);
        assertTrue(db(g1) - db(g0) < .5, "回升起步也要柔");
    }

    @Test
    void reversingMidFadeIsContinuous() {
        // 压到一半时切回来：新快照从当前进度出发，倍率不跳。
        FocusVolumeDucker.Ramp down = new FocusVolumeDucker.Ramp(0L, 0f, true);
        long t = OUT / 3;
        float p = down.progressAt(t);
        FocusVolumeDucker.Ramp up = new FocusVolumeDucker.Ramp(t, p, false);
        assertEquals(FocusVolumeDucker.gainFor(down.progressAt(t), 25),
                FocusVolumeDucker.gainFor(up.progressAt(t), 25), 1e-6);
        assertEquals(0f, up.progressAt(t + IN), "回升最多一个 FADE_IN 就回到原音量");
    }

    @Test
    void perBufferStepsAreInaudible() {
        // 音频任务每 64 帧（44.1k 下约 1.45ms）写一次；整条曲线上任意相邻两次的差都要远小于 0.1 dB。
        long buffer = 1_451_000L;
        for (int level : FocusVolumeDucker.LEVELS) {
            if (level == 0) continue; // 静音档终点那一下是从 -60 dB 落到 0，本来就听不见
            FocusVolumeDucker.Ramp down = new FocusVolumeDucker.Ramp(0L, 0f, true);
            double worst = 0;
            for (long t = 0; t < OUT; t += buffer) {
                double d = db(FocusVolumeDucker.gainFor(down.progressAt(t), level))
                        - db(FocusVolumeDucker.gainFor(down.progressAt(t + buffer), level));
                worst = Math.max(worst, d);
            }
            assertTrue(worst < .1, "档位 " + level + " 单缓冲最大台阶 " + worst + " dB");
        }
    }

    @Test
    void idleRampIsUnity() {
        assertEquals(0f, FocusVolumeDucker.Ramp.IDLE.progressAt(123_456_789L));
    }

    private static double db(float gain) {
        return 20.0 * Math.log10(Math.max(1e-6, gain));
    }

    @Test
    void levelsNormalizeAndLabel() {
        assertEquals(10, FocusVolumeDucker.normalizeLevel(10));
        assertEquals(FocusVolumeDucker.DEFAULT_LEVEL, FocusVolumeDucker.normalizeLevel(37));
        assertEquals(FocusVolumeDucker.DEFAULT_LEVEL, FocusVolumeDucker.normalizeLevel(-5));
        assertEquals("静音", FocusVolumeDucker.labelOf(0));
        assertEquals("25%", FocusVolumeDucker.labelOf(25));
        assertEquals(FocusVolumeDucker.LEVELS.length, FocusVolumeDucker.levelLabels().length);
    }

    @Test
    void outputStageInterpolatesPerSampleWithoutSteps() {
        // 两个相邻缓冲：从 1 → .8 → .6，每个采样都只比前一个小一点点，缓冲交界处也不跳。
        int channels = 2, frames = 64;
        double[] first = filled(channels * frames);
        double[] second = filled(channels * frames);
        FocusVolumeDucker.applyRamp(first, channels, 1f, .8f);
        FocusVolumeDucker.applyRamp(second, channels, .8f, .6f);
        double prev = 1.0, worst = 0;
        for (double[] buf : new double[][] {first, second}) {
            for (int f = 0; f < frames; f++) {
                assertEquals(buf[f * channels], buf[f * channels + 1], 1e-12, "左右声道同一倍率");
                worst = Math.max(worst, prev - buf[f * channels]);
                prev = buf[f * channels];
            }
        }
        assertEquals(.6, prev, 1e-6, "缓冲末尾正好落在目标上（float 精度）");
        assertTrue(worst < .01, "相邻采样最大跳变 " + worst);
    }

    @Test
    void outputStageLeavesUnityUntouched() {
        double[] buf = {.3, -.7, .1, .9};
        FocusVolumeDucker.applyRamp(buf, 2, 1f, 1f);
        assertArrayEquals(new double[] {.3, -.7, .1, .9}, buf, 0.0, "倍率为 1 时一个比特都不动");
        FocusVolumeDucker.applyRamp(buf, 2, .5f, .5f);
        assertArrayEquals(new double[] {.15, -.35, .05, .45}, buf, 1e-12);
    }

    /**
     * 压低必须挂在输出链最末一级，且只在那里乘一次：
     * 压在 deck 上会被蝰蛇音效的压缩器 / 回放增益控制反推（听着像卡顿），两处都乘则是双重压低。
     */
    @Test
    void duckingLivesOnlyAtTheEndOfTheOutputChain() throws Exception {
        String player = source("AudioPlayer.java");
        assertFalse(player.contains("FocusVolumeDucker"), "deck 振幅不许再乘失焦倍率");

        String synth = Files.readString(ProjectPaths.sourceFile(Paths.get(
                "repackage", "com", "jsyn", "engine", "SynthesisEngine.java")));
        String output = body(synth, "private void processOutput()");
        int trailing = output.indexOf("trailingProcessor;");
        int last = output.indexOf("finalProcessor;");
        assertTrue(trailing >= 0 && last > trailing, "最末一级必须排在蝰蛇主槽与声学尾槽之后");

        String engine = Files.readString(ProjectPaths.sourceFile(Paths.get(
                "repackage", "processing", "sound", "Engine.java")));
        assertTrue(body(engine, "private void createSynth(").contains("setFinalOutputProcessor(finalOutputProcessor)"),
                "切音频驱动重建合成器时要把最末一级重新挂上");

        String ducker = source("FocusVolumeDucker.java");
        assertTrue(body(ducker, "public static void onClientTick(").contains("Engine.installFinalOutputProcessor(OUTPUT_STAGE)"));

        String live = source("LiveStreamPlayer.java");
        assertTrue(body(live, "void refreshVolumeOnLine(").contains("FocusVolumeDucker.gain()"),
                "直播流不走 JSyn，倍率在它每次写线时乘");
        assertTrue(body(live, "private SourceDataLine openLine(").contains("FocusVolumeDucker.gain()"),
                "开线时的首次增益也要带倍率，否则失焦状态下开播第一段是满音量");
    }

    private static double[] filled(int n) {
        double[] buf = new double[n];
        java.util.Arrays.fill(buf, 1.0);
        return buf;
    }

    private static String source(String file) throws Exception {
        return Files.readString(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "ncm", "music", file)));
    }

    /** 某个成员的方法体，按签名定位、按配对的花括号截断。 */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "源码里找不到: " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        fail("花括号不配对: " + signature);
        return "";
    }
}
