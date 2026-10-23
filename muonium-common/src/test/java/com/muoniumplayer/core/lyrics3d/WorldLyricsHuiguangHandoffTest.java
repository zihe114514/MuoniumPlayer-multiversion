package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绘光换句交接的状态机回归：跑真实 {@link WorldLyricsState#update}，断言
 * prev/own/prevGeo 快照的落位、fading 回退源、引用链截断与 revive 续飞。
 */
class WorldLyricsHuiguangHandoffTest {

    private static final WorldLyricsState.CameraView CAMERA = new WorldLyricsState.CameraView() {
        @Override public double eyeX() { return 0; }
        @Override public double eyeY() { return 64; }
        @Override public double eyeZ() { return 0; }
        @Override public double lookX() { return 0; }
        @Override public double lookY() { return 0; }
        @Override public double lookZ() { return 1; }
        @Override public double yawDeg() { return 0; }
        @Override public double pitchDeg() { return 0; }
    };

    private static final WorldLyricsState.Params PARAMS = new WorldLyricsState.Params() {
        @Override public double distance() { return 6.5; }
        @Override public boolean splitLines() { return false; }
        @Override public double spreadDeg() { return 0; }
        @Override public double scale() { return 1; }
        @Override public boolean facePlayer() { return true; }
        @Override public boolean keepAway() { return false; }
        @Override public boolean wordPop() { return false; }
        @Override public boolean huiguang() { return true; }
    };

    private WorldLyricsState state;

    @BeforeEach
    void setUp() {
        state = WorldLyricsState.get();
        state.resetForTest();
        CloudMusic.lyrics.clear();
        CloudMusic.currentlyPlaying = music();
        CloudMusic.lyricsTrackKey = CloudMusic.currentlyPlaying.getStableKey();
    }

    @AfterEach
    void tearDown() {
        CloudMusic.lyrics.clear();
        CloudMusic.lyricsTrackKey = null;
        CloudMusic.currentlyPlaying = null;
        state.resetForTest();
    }

    private static Music music() {
        List<Artist> artists = List.of(new Artist(1L, "测试艺人",
                Collections.emptyList(), Collections.emptyList()));
        Album album = new Album(1L, "测试专辑", "", Collections.emptyList());
        return new Music("测试曲名", "测试曲名", "", 1L, artists,
                Collections.emptyList(), album, 300_000L, 0L, 0L,
                Collections.emptyList());
    }


    /** 帧组装时 fading 在前、active 在后 —— 取 active 行的句子。 */
    private static WorldLyricsState.Floating activeOf(WorldLyricsState.Frame frame) {
        for (WorldLyricsState.Row r : frame.rows) {
            if (r.active) return r.floating;
        }
        return frame.rows.isEmpty() ? null : frame.rows.get(frame.rows.size() - 1).floating;
    }

    private static LyricLine line(long timestamp, String text) {
        return new LyricLine(timestamp, text);
    }

    @Test
    void lineChangeWiresPrevAndCutsUpstreamChain() {
        // 三句歌词。逐句推进：每次换行 active 句都应拿到 prev（上一句）与几何快照；
        // 被换下的句子的上游引用必须被截断（防 active→N→N−1→… 引用链）。
        CloudMusic.lyrics.add(line(0L, "第一句"));
        CloudMusic.lyrics.add(line(4000L, "第二句"));
        CloudMusic.lyrics.add(line(8000L, "第三句"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        assertEquals(1, f1.rows.size());
        WorldLyricsState.Floating s1 = activeOf(f1);
        assertNull(s1.huiguangPrev, "首句无上一句");

        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 4100L);
        WorldLyricsState.Floating s2 = activeOf(f2);
        assertNotNull(s2.huiguangPrev, "换句后新句应有 prev");
        assertEquals(s1, s2.huiguangPrev, "prev 应是刚被换下的那句");
        assertNotNull(s2.huiguangOwnGeo, "应有本句几何快照");
        assertNotNull(s2.huiguangPrevGeo, "应有上一句几何快照");
        // 引用链截断：s1 自己的 prev 必须是 null（首句本来就没有），geo 断开
        assertNull(s1.huiguangPrevGeo, "被换下句子的上游快照应截断");
        assertNull(s1.huiguangOwnGeo, "被换下句子的自身快照应截断");

        WorldLyricsState.Frame f3 = state.update(CAMERA, PARAMS, 1_200_000_000L, true, 8100L);
        WorldLyricsState.Floating s3 = activeOf(f3);
        assertEquals(s2, s3.huiguangPrev, "第三句的 prev 应是第二句");
        assertNull(s2.huiguangPrev, "第二句的上游链应截断（不再钉住 s1）");
        assertNull(s2.huiguangOwnGeo);
    }

    @Test
    void gapBeforeNextLineFallsBackToFadingSource() {
        // 两句之间隔一个空行（break 行）：active 短暂为 null，下一句应从
        // 最近一句 fading 飞来（R1 观察项：间隙句不丢飞行源）。
        CloudMusic.lyrics.add(line(0L, "前一句"));
        LyricLine brk = line(4000L, "");
        brk.isBreakLine = true;
        CloudMusic.lyrics.add(brk);
        CloudMusic.lyrics.add(line(8000L, "后一句"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        assertEquals(1, f1.rows.size());
        WorldLyricsState.Floating s1 = activeOf(f1);

        // 落到 break 行：active 置 null、s1 转淡出
        state.update(CAMERA, PARAMS, 1_100_000_000L, true, 4100L);
        // 再落到后一句：replaced 为 null（active 上一帧已是 null），应回退 fading 源
        WorldLyricsState.Frame f3 = state.update(CAMERA, PARAMS, 1_200_000_000L, true, 8100L);
        WorldLyricsState.Floating s3 = activeOf(f3);
        assertNotNull(s3, "空行后的实句应正常 spawn");
        assertEquals(s1, s3.huiguangPrev, "间隙后应回退最近一句 fading 当飞行源");
        assertNotNull(s3.huiguangOwnGeo);
        assertNotNull(s3.huiguangPrevGeo);
    }

    @Test
    void reviveInsideFlightWindowContinuesNotReflies() {
        // 同句 retire→revive（暂停去抖恢复）不重建飞行定义：缓存的 huiguangEntry
        // 保持不变（字从空中续飞，不是重飞）。
        CloudMusic.lyrics.add(line(0L, "第一句"));
        CloudMusic.lyrics.add(line(4000L, "第二句"));

        state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 4100L);
        WorldLyricsState.Floating s2 = activeOf(f2);
        assertNotNull(s2.huiguangPrev);
        // update() 不调 buildGlyphs（那是渲染层的活）—— 手动构建一次把
        // 飞行定义缓存出来，revive 后再 build 比较数组实例。update 传 null
        // 宽度回调不行，用与句宽恒定的 lambda。
        WorldLyricsState.PixelWidthOf width = s -> 10f;
        WorldLyricsState.buildGlyphs(s2, 4300L, 1f, false, false, width, false, true);
        HuiguangMotion.Flight[] before = s2.huiguangEntry;
        assertNotNull(before, "首绘应已构建飞行定义（此前该断言因未构建而空洞）");

        // 同句 revive（走 takeFadingIfSameLine 路径要先 retire——用暂停窗口模拟：
        // 持续暂停让它转淡出，再恢复同 idx）
        state.update(CAMERA, PARAMS, 1_300_000_000L, false, 4300L);
        WorldLyricsState.Frame back = state.update(CAMERA, PARAMS, 1_400_000_000L, true, 4350L);
        assertEquals(s2, activeOf(back), "同句应被认回（不重建）");
        WorldLyricsState.buildGlyphs(s2, 4350L, 1f, false, false, width, false, true);
        assertTrue(s2.huiguangEntry == before,
                "revive 不得重建飞行定义（同一数组实例 = 续飞）");
    }

    @Test
    void seekJumpStillFormsHandoffFromSkippedLine() {
        // 时间轴大跳（seek）：被跳过的旧句就是飞行源 —— folia 式退化。
        CloudMusic.lyrics.add(line(0L, "第一句"));
        CloudMusic.lyrics.add(line(10000L, "远处的句子"));

        WorldLyricsState.Frame f1 = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 100L);
        WorldLyricsState.Floating s1 = activeOf(f1);

        WorldLyricsState.Frame f2 = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 10100L);
        WorldLyricsState.Floating s2 = activeOf(f2);
        assertEquals(s1, s2.huiguangPrev, "seek 大跳同样形成交接，源是被跳过的句子");
    }
}
