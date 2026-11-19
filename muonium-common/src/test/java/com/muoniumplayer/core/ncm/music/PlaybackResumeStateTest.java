package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 真跑恢复态快照与位置所有权，不启动音频、网络或游戏界面。 */
class PlaybackResumeStateTest {
    private List<Music> oldQueue;
    private int oldIndex;
    private Music oldTrack;
    private AudioPlayer oldPlayer;
    private AtomicReference<PlaybackResumePoint> pending;
    private PlaybackResumePoint oldResume;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        oldQueue = CloudMusic.playList;
        oldIndex = CloudMusic.curIdx;
        oldTrack = CloudMusic.currentlyPlaying;
        oldPlayer = CloudMusic.player;
        Field field = CloudMusic.class.getDeclaredField("pendingResume");
        field.setAccessible(true);
        pending = (AtomicReference<PlaybackResumePoint>) field.get(null);
        oldResume = pending.get();
        Music restored = song(42L, MusicPlatform.NETEASE, 200_000L);
        CloudMusic.playList = new ArrayList<>(List.of(restored));
        CloudMusic.curIdx = 0;
        CloudMusic.currentlyPlaying = restored;
        CloudMusic.player = null;
        pending.set(new PlaybackResumePoint(restored, 83_000L));
    }

    @AfterEach
    void tearDown() {
        pending.set(oldResume);
        CloudMusic.playList = oldQueue;
        CloudMusic.curIdx = oldIndex;
        CloudMusic.currentlyPlaying = oldTrack;
        CloudMusic.player = oldPlayer;
    }

    @Test
    void restoredSnapshotShowsSavedPositionWithoutStartingAClock() throws Exception {
        CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();
        assertEquals(83_000L, snapshot.positionMs);
        assertEquals(200_000L, snapshot.durationMs);
        assertSame(CloudMusic.currentlyPlaying, snapshot.music);
        assertFalse(snapshot.playing);
        assertNull(snapshot.player);
        assertFalse(CloudMusic.hasPlaybackClock(), "展示记忆不能让恢复态接受音频 seek");
        assertEquals(83_000L, CloudMusic.getSnapshot().positionMs, "恢复态不随墙钟前进");
        assertEquals(83_000L, savedPosition(), "未播放就退出也不能把记忆位置覆盖成零");
    }

    @Test
    void explicitResumeTransfersOnlyOnceToTheMatchingTrack() throws Exception {
        Music sameIdentity = song(42L, MusicPlatform.NETEASE, 200_000L);
        assertEquals(83_000L, take(sameIdentity, true));
        assertNull(pending.get());
        assertEquals(0L, take(sameIdentity, true));
    }

    @Test
    void selectingAnotherSongOrExplicitlyReplayingTheSameSongStartsAtZero() throws Exception {
        Music remembered = CloudMusic.currentlyPlaying;
        assertEquals(0L, take(song(99L, MusicPlatform.NETEASE, 200_000L), false));
        assertNull(pending.get());
        pending.set(new PlaybackResumePoint(remembered, 83_000L));
        assertEquals(0L, take(remembered, false), "列表点歌不是续播意图");
        assertNull(pending.get());
    }

    @Test
    void sameIndexOrNumericIdOnAnotherPlatformCannotInheritTheOffset() throws Exception {
        Music another = song(42L, MusicPlatform.KUGOU, 200_000L);
        CloudMusic.currentlyPlaying = another;
        CloudMusic.playList.set(0, another);
        assertEquals(0L, CloudMusic.getSnapshot().positionMs);
        assertEquals(0L, savedPosition());
        assertEquals(0L, take(another, true));
        assertNull(pending.get());
    }

    @Test
    void movedQueueSlotDoesNotExposeTheRememberedPosition() throws Exception {
        CloudMusic.playList.set(0, song(99L, MusicPlatform.NETEASE, 200_000L));
        assertEquals(0L, CloudMusic.getSnapshot().positionMs);
        assertEquals(0L, savedPosition());
    }

    @Test
    void displayedPositionClampsToKnownDurationButUnknownDurationKeepsTheMemory() {
        Music shortTrack = song(1L, MusicPlatform.NETEASE, 30_000L);
        assertEquals(30_000L, new PlaybackResumePoint(shortTrack, 83_000L).positionFor(shortTrack));
        Music unknownLength = song(2L, MusicPlatform.GD, 0L);
        assertEquals(83_000L, new PlaybackResumePoint(unknownLength, 83_000L).positionFor(unknownLength));
        assertEquals(0L, new PlaybackResumePoint(unknownLength, -1L).positionFor(unknownLength));
    }

    private long savedPosition() throws Exception {
        Method method = CloudMusic.class.getDeclaredMethod("currentPlaybackPositionMillis");
        method.setAccessible(true);
        return (Long) method.invoke(null);
    }

    private long take(Music track, boolean resume) throws Exception {
        Method method = CloudMusic.class.getDeclaredMethod("takeResumeOffset", Music.class, boolean.class);
        method.setAccessible(true);
        return (Long) method.invoke(null, track, resume);
    }

    private static Music song(long id, MusicPlatform source, long duration) {
        Music music = new Music("track", "track", "", id, List.of(), List.of(), null,
                duration, 0L, 0L, List.of());
        music.setSource(source);
        return music;
    }
}
