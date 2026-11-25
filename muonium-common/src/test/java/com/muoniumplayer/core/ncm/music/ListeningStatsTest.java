package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.json.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主页收听统计的读数口径：连续收听怎么数、反复聆听怎么排。
 *
 * <p>只测内存里的记账与读数（{@code recordForTest} 绕过播放器与写盘），不碰配置目录。</p>
 */
class ListeningStatsTest {

    @BeforeEach
    void reset() {
        ListeningStats.resetForTest();
    }

    @AfterEach
    void cleanup() {
        ListeningStats.resetForTest();
    }

    private static Music song(long id, String name) {
        Music music = JsonUtils.parse("{\"id\":" + id + ",\"name\":\"" + name + "\"}", Music.class);
        music.setSource(MusicPlatform.NETEASE);
        return music;
    }

    @Test
    @DisplayName("连续收听：今天没听从昨天数起，断一天就停")
    void streakCountsBackFromYesterdayWhenTodayIsEmpty() {
        Music music = song(1L, "a");
        LocalDate today = LocalDate.now();
        ListeningStats.recordForTest(music, 120_000L, today.minusDays(1).toString());
        ListeningStats.recordForTest(music, 120_000L, today.minusDays(2).toString());
        ListeningStats.recordForTest(music, 120_000L, today.minusDays(4).toString());
        assertEquals(2, ListeningStats.streakDays());
        ListeningStats.recordForTest(music, 120_000L, today.toString());
        assertEquals(3, ListeningStats.streakDays());
    }

    @Test
    @DisplayName("一天不满 60 秒不算这一天听过")
    void shortDaysDoNotCount() {
        ListeningStats.recordForTest(song(1L, "a"), 30_000L, LocalDate.now().toString());
        assertEquals(0, ListeningStats.streakDays());
    }

    @Test
    @DisplayName("反复聆听按播放次数降序")
    void topTracksAreOrderedByCount() {
        String today = LocalDate.now().toString();
        Music once = song(1L, "once");
        Music thrice = song(2L, "thrice");
        Music twice = song(3L, "twice");
        ListeningStats.recordForTest(once, 40_000L, today);
        for (int i = 0; i < 3; i++) ListeningStats.recordForTest(thrice, 40_000L, today);
        for (int i = 0; i < 2; i++) ListeningStats.recordForTest(twice, 40_000L, today);
        List<ListeningStats.TrackRecord> top = ListeningStats.topTracks(6);
        assertEquals(3, top.size());
        assertEquals("thrice", top.get(0).getMusic().getName());
        assertEquals(3, top.get(0).getCount());
        assertEquals("twice", top.get(1).getMusic().getName());
        assertEquals(1, ListeningStats.topTracks(1).size());
        assertTrue(ListeningStats.weekListenHours() > 0.0);
    }
}
