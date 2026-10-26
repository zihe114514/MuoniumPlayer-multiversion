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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌尾闪烁修复的回归探针：播放结束边界（{@code playing} 在解码收尾几帧里 true/false
 * 抖动）不得把正在显示/正在淡出的句子反复 retire→重 spawn —— 那正是「歌词结束时闪烁
 * 一下才消失」的根因。跑真实的状态机 {@link WorldLyricsState#update}，喂抖动的
 * playing 序列，断言行身份与在场集合的变化规律。
 */
class WorldLyricsEndFlickerTest {

    /** 恒定视线相机（原点、朝 +Z）。 */
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
        @Override public boolean wordPop() { return true; }
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

    private static LyricLine line(long timestamp, String text) {
        return new LyricLine(timestamp, text);
    }

    @Test
    void indexJumpToZeroRetiresWithoutRespawn() {
        // 单句歌词 timestamp=1000。正常播放 pos=1200 → 行显示（active）。
        CloudMusic.lyrics.add(line(1000L, "一句"));

        WorldLyricsState.Frame frame = state.update(CAMERA, PARAMS, 1_000_000_000L, true, 1200L);
        assertEquals(1, frame.rows.size(), "行应显示");
        assertTrue(frame.rows.get(0).active);
        WorldLyricsState.SpawnedLine first = frame.rows.get(0).floating;

        // 「歌放完」边界：pos 跳 0（player close），idx 从 0 变 -1 → 原行转淡出。
        WorldLyricsState.Frame gone = state.update(CAMERA, PARAMS, 1_100_000_000L, true, 0L);
        assertEquals(1, gone.rows.size(), "淡出中的行应继续在场");
        assertFalse(gone.rows.get(0).active, "原行应转淡出态（不再 active）");
        assertEquals(first, gone.rows.get(0).floating, "淡出的是同一句（身份不变）");

        // 再下一帧 pos 仍 0：淡出行继续衰减，绝不重新 spawn 成 active。
        WorldLyricsState.Frame fading = state.update(CAMERA, PARAMS, 1_200_000_000L, true, 0L);
        assertEquals(1, fading.rows.size());
        assertFalse(fading.rows.get(0).active, "不得闪回 active");
    }

    @Test
    void playingJitterInsideDebounceDoesNotRetire() {
        // 双句歌词。第二句显示中 → playing=false 一帧（抖动，去抖窗 150ms 内）
        // → paused 不成立 → 行继续以 active 显示，绝不闪。
        CloudMusic.lyrics.add(line(0L, "first"));
        CloudMusic.lyrics.add(line(5000L, "second"));

        WorldLyricsState.Frame a = state.update(CAMERA, PARAMS, 2_000_000_000L, true, 5100L);
        assertEquals(1, a.rows.size());
        assertTrue(a.rows.get(0).active);

        // 抖动帧：距上次「在播」仅 50ms。
        WorldLyricsState.Frame jitter = state.update(CAMERA, PARAMS, 2_050_000_000L, false, 5200L);
        assertEquals(1, jitter.rows.size(), "去抖窗口内的 playing 抖动不得打断显示");
        assertTrue(jitter.rows.get(0).active, "行仍应是 active 态（未被 retire）");
    }

    @Test
    void sustainedPauseStillRetires() {
        // 去抖不能吞掉真暂停：持续 playing=false 超过 150ms 必须正常转淡出。
        CloudMusic.lyrics.add(line(0L, "first"));
        CloudMusic.lyrics.add(line(5000L, "second"));

        state.update(CAMERA, PARAMS, 3_000_000_000L, true, 5100L);
        WorldLyricsState.Frame paused = state.update(CAMERA, PARAMS, 3_300_000_000L, false, 5200L);
        assertFalse(paused.rows.get(0).active, "持续暂停应转淡出态");
        // 淡出继续推进：400ms 后离场。
        WorldLyricsState.Frame after = state.update(CAMERA, PARAMS, 3_800_000_000L, false, 5200L);
        assertTrue(after.rows.isEmpty() || after.rows.get(0).alpha <= 0.01f,
                "淡出完成后行应离场");
    }

    @Test
    void resumeAfterSustainedPauseReusesSameSpawnedLine() {
        // 持续暂停转淡出后恢复播放：同句（idx 相同）应把淡出中的句子原样认回
        // （从 alpha 0 渐入），而不是销毁重建 —— 重建会闪。
        CloudMusic.lyrics.add(line(0L, "first"));
        CloudMusic.lyrics.add(line(5000L, "second"));

        state.update(CAMERA, PARAMS, 3_000_000_000L, true, 5100L);
        state.update(CAMERA, PARAMS, 3_300_000_000L, false, 5200L); // 转 fade（100ms 内）
        WorldLyricsState.Frame resumed = state.update(CAMERA, PARAMS, 3_400_000_000L, true, 5100L);
        assertEquals(1, resumed.rows.size(), "同句恢复后应只有一行（认回，不叠新行）");
        assertTrue(resumed.rows.get(0).active, "认回的行回到 active 态");
        assertTrue(resumed.rows.get(0).alpha < 1f, "认回走恢复淡入（alpha<1）");
    }
}
