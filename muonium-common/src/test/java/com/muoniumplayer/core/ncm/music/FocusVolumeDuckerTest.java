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

    @Test
    void targetIsFullWhenFocusedOrDisabled() {
        assertEquals(1f, FocusVolumeDucker.targetGain(true, 25, true));
        assertEquals(1f, FocusVolumeDucker.targetGain(false, 25, false));
        assertEquals(.25f, FocusVolumeDucker.targetGain(true, 25, false), 1e-6);
        assertEquals(0f, FocusVolumeDucker.targetGain(true, 0, false));
    }

    @Test
    void rampsDownOverFadeOutAndNeverOvershoots() {
        // 满幅下降用 FADE_OUT_NANOS；一半时间走一半幅度。
        float half = FocusVolumeDucker.step(1f, 0f, FocusVolumeDucker.FADE_OUT_NANOS / 2);
        assertEquals(.5f, half, 1e-4);
        // 时间给足了也只停在目标上。
        assertEquals(.25f, FocusVolumeDucker.step(1f, .25f, 10_000 * MS));
        // 零时间不动。
        assertEquals(1f, FocusVolumeDucker.step(1f, .25f, 0L));
    }

    @Test
    void rampsUpFasterThanDown() {
        assertTrue(FocusVolumeDucker.FADE_IN_NANOS < FocusVolumeDucker.FADE_OUT_NANOS,
                "回到游戏时音乐应当比切出去时更快回来");
        float up = FocusVolumeDucker.step(.25f, 1f, 50 * MS);
        float down = 1f - FocusVolumeDucker.step(1f, .25f, 50 * MS);
        assertTrue(up - .25f > down, "同样 50ms，上升幅度应大于下降幅度");
        assertEquals(1f, FocusVolumeDucker.step(.25f, 1f, 10_000 * MS));
    }

    @Test
    void oneTickStepIsSmall() {
        // 20 TPS 下一拍 50ms：单级台阶不该大到听得出「一格一格」。
        float drop = 1f - FocusVolumeDucker.step(1f, 0f, 50 * MS);
        assertTrue(drop <= .1f, "单拍下降 " + drop);
    }

    @Test
    void brokenGainRecoversToTarget() {
        assertEquals(.5f, FocusVolumeDucker.step(Float.NaN, .5f, 50 * MS));
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

    /** 两条振幅写路径与直播流线路增益都必须乘上失焦倍率，漏一条就会在那条路上「切出去音量不变」。 */
    @Test
    void everyOutputPathAppliesTheDuckGain() throws Exception {
        String player = source("AudioPlayer.java");
        assertTrue(body(player, "private float effectiveAmp()").contains("FocusVolumeDucker.gain()"),
                "effectiveAmp() 是 play/seek/setVolume 的共同出口");
        assertTrue(body(player, "void applyAutomixFrame(").contains("FocusVolumeDucker.gain()"),
                "automix 音频线程帧绕开 effectiveAmp() 直写端口，必须单独乘");

        String live = source("LiveStreamPlayer.java");
        assertTrue(body(live, "void refreshVolumeOnLine(").contains("FocusVolumeDucker.gain()"),
                "直播流每次写线重算增益，倍率要在这里乘");
        assertTrue(body(live, "private SourceDataLine openLine(").contains("FocusVolumeDucker.gain()"),
                "开线时的首次增益也要带倍率，否则失焦状态下开播第一段是满音量");
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
