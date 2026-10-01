package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「一键排列」<b>落排</b>那一步的回归测试。
 *
 * <p>排布是在后台按几十秒前的那份队列算出来的，落的那一刻队列早可能已经不是那个队列了：用户
 * 又插了一首「下一首播放」、正在播的这首结束让 {@code curIdx} 前移一格、或者某首被换成了别的源。
 * 上一版只比长度，而「少一首又多一首」长度正好不变 —— 照一份过期的排布去搬，用户自己排的顺序
 * 会被搅乱，而且搅乱之后没有任何地方会报错，要等几首歌播过去才发现。</p>
 *
 * <p>这一组用例全部是<b>真跑</b> {@link CloudMusic#reorderQueuedNext(int[], List)}：待播段由账本
 * 登记出来，队列就是普通的 {@code List<Music>}，不启动 Minecraft、不碰灵动岛、不联网。</p>
 */
class QueueReorderIdentityTest {

    @BeforeEach
    @AfterEach
    void restorePristineQueue() {
        // 这两个是全局静态字段，别的用例可能假定「刚进游戏」的那个状态。
        CloudMusic.playList = new ArrayList<>();
        CloudMusic.curIdx = 0;
        CloudMusic.playNextLedger.clear();
    }

    @Test
    @DisplayName("长度没变、内容变了：一个都不改")
    void sameLengthDifferentSongsIsRefused() {
        List<Music> songs = queue(4);
        CloudMusic.playList = songs;
        List<Music> snapshot = markPending(1, 3);          // 第 1..3 格是用户排的待播

        // 扫描期间用户把第 2 格换成了另一首：待播段还是三格长。
        Music outsider = song(9L);
        CloudMusic.playNextLedger.forget(songs.get(2));
        songs.set(2, outsider);
        CloudMusic.playNextLedger.remember(outsider);

        assertFalse(CloudMusic.reorderQueuedNext(new int[]{2, 0, 1}, snapshot),
                "只比长度会接受这次改动，而它用的是一份过期的排布");
        assertSame(snapshot.get(0), songs.get(1), "对得上的那几格也不该被搬动——整件事要整个作废");
        assertSame(outsider, songs.get(2));
        assertSame(snapshot.get(2), songs.get(3));
    }

    @Test
    @DisplayName("比的是身份，不是 equals：同一首歌的另一个实例不算对得上")
    void identityNotEquality() {
        List<Music> songs = queue(3);
        CloudMusic.playList = songs;
        List<Music> snapshot = markPending(1, 2);

        Music sameSongDifferentInstance = song(songs.get(2).getId());
        assertTrue(sameSongDifferentInstance.equals(songs.get(2)),
                "前提：这两位在 equals 看来是同一首");
        songs.set(2, sameSongDifferentInstance);

        assertFalse(CloudMusic.reorderQueuedNext(new int[]{1, 0}, snapshot),
                "实例都换了说明这一格被人重写过（换源、重新解析），快照不能再信");
    }

    @Test
    @DisplayName("快照对得上就按它排，而且只改扫过的那几格")
    void reordersOnlyTheScannedPrefix() {
        List<Music> songs = queue(6);
        CloudMusic.playList = songs;
        Music a = songs.get(1), b = songs.get(2), c = songs.get(3);
        Music tail4 = songs.get(4), tail5 = songs.get(5);
        List<Music> snapshot = markPending(1, 3);          // 第 4、5 格不是待播：这次没量它们

        assertTrue(CloudMusic.reorderQueuedNext(new int[]{2, 0, 1}, snapshot));
        assertSame(c, songs.get(1));
        assertSame(a, songs.get(2));
        assertSame(b, songs.get(3));
        assertSame(tail4, songs.get(4), "没量的那些必须原位不动");
        assertSame(tail5, songs.get(5));
        assertSame(songs.get(0), CloudMusic.playList.get(0), "正在播的那格不属于待播段");
        assertEquals(0, CloudMusic.curIdx, "排列不许动播放指针");
    }

    @Test
    @DisplayName("正在播的换了：curIdx 前移会让快照整体错位，必须拒绝")
    void refusesWhenCurrentTrackAdvanced() {
        List<Music> songs = queue(5);
        CloudMusic.playList = songs;
        List<Music> snapshot = markPending(1, 3);

        CloudMusic.curIdx = 2;                             // 这一期间播过去了两首

        assertFalse(CloudMusic.reorderQueuedNext(new int[]{2, 1, 0}, snapshot),
                "同一个下标现在指的是别的歌");
        assertSame(songs.get(3), snapshot.get(2), "确认快照与队列确实错位了，不是巧合通过的用例");
    }

    @Test
    @DisplayName("半排列与越界一律拒绝")
    void refusesHalfPermutation() {
        List<Music> songs = queue(4);
        CloudMusic.playList = songs;
        List<Music> snapshot = markPending(1, 3);
        List<Music> copy = new ArrayList<>(songs);

        assertFalse(CloudMusic.reorderQueuedNext(new int[]{0, 0, 1}, snapshot), "重复的下标会让一首歌凭空消失");
        assertFalse(CloudMusic.reorderQueuedNext(new int[]{0, 1, 3}, snapshot), "越界的下标会让队列里多出 null");
        assertFalse(CloudMusic.reorderQueuedNext(new int[]{1, 0}, snapshot),
                "排布比快照短就是半排列：剩下的格子没有归宿");
        assertEquals(copy, songs, "三次拒绝都得一根手指都没碰过队列");
    }

    @Test
    @DisplayName("待播段比快照短（用户删了几首）也拒绝")
    void refusesWhenPendingRunShrank() {
        List<Music> songs = queue(5);
        CloudMusic.playList = songs;
        List<Music> snapshot = markPending(1, 3);

        CloudMusic.playNextLedger.forget(songs.get(3));    // 用户撤销了一首的排队
        CloudMusic.playNextLedger.forget(songs.get(4));

        assertFalse(CloudMusic.reorderQueuedNext(new int[]{2, 1, 0}, snapshot));
        assertEquals(5, songs.size(), "拒绝的时候不许顺手改长度");
    }

    private static List<Music> queue(int size) {
        List<Music> songs = new ArrayList<>(size);
        for (int index = 0; index < size; index++) songs.add(song(1000L + index));
        return songs;
    }

    /**
     * 把 {@code from..to}（含）这些格登记成「用户用下一首播放排进去的」，并返回同一份前缀快照——
     * 也就是 {@link CloudMusic#getQueuedNextSongs()} 在那一刻会给后台的东西。
     */
    private static List<Music> markPending(int from, int to) {
        for (int index = from; index <= to; index++) {
            CloudMusic.playNextLedger.remember(CloudMusic.playList.get(index));
        }
        return CloudMusic.getQueuedNextSongs();
    }

    private static Music song(long id) {
        return new Music("测试曲 " + id, "测试曲 " + id, "", id,
                new ArrayList<Artist>(), Collections.<String>emptyList(),
                new Album(id, "测试专辑 " + id, "", Collections.<String>emptyList()),
                200_000L, 0L, 0L, Collections.<String>emptyList());
    }
}
