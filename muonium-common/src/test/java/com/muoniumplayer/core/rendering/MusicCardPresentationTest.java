package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class MusicCardPresentationTest {
    private static Music music(String key) {
        Music music = new Music(key, key, "", Math.abs((long) key.hashCode()), Collections.emptyList(), Collections.emptyList(),
                null, 180_000L, 0L, 0L, Collections.emptyList());
        music.setSource(MusicPlatform.QQ);
        music.setSourceId(key);
        return music;
    }

    private static CloudMusic.PlaybackSnapshot actual(long session, Music music) {
        return new CloudMusic.PlaybackSnapshot(session, music.getId(), 63_000L, 180_000L, true, null, music);
    }

    private static CloudMusic.PlaybackSnapshot pending(long session, long request, Music music) {
        return new CloudMusic.PlaybackSnapshot(session, music.getId(), 63_000L, 180_000L,
                true, null, music, true, request);
    }

    @Test
    void pendingSnapshotHasItsOwnDisplayIdentityAndCannotBorrowAPositionOrPlaybackState() {
        Music track = music("pending-fields");
        CloudMusic.PlaybackSnapshot preview = pending(-1L, 821L, track);
        assertTrue(preview.preparing);
        assertEquals(821L, preview.requestNanos);
        assertEquals(0L, preview.positionMs);
        assertFalse(preview.playing);
        assertNull(preview.player);
        assertSame(track, preview.music);
        assertFalse(MusicCapsuleCard.matchesFrame(preview));
    }

    @Test
    void acceptedSelectionOpensImmediatelyAndAudibleCommitUpgradesTheSameNotice() throws Exception {
        try (SavedState ignored = new SavedState()) {
            Music old = music("audible-old"), next = music("pending-next");
            MusicCapsuleCard.observePresentation(actual(901L, old), true);
            long before = MusicCapsuleCard.noticeRevision();
            CloudMusic.PlaybackSnapshot requested = pending(-1L, 4521L, next);
            MusicCapsuleCard.observePresentation(requested, false);
            assertSame(requested, MusicCapsuleCard.currentSnapshot());
            assertEquals(before + 1L, MusicCapsuleCard.noticeRevision());
            assertTrue(MusicCapsuleCard.trackNoticeVisible(requested));
            assertFalse(MusicCapsuleCard.presentationReady());
            assertFalse(MusicCapsuleCard.matchesFrame(actual(901L, old)));
            CloudMusic.PlaybackSnapshot committed = actual(902L, next);
            MusicCapsuleCard.observePresentation(committed, true);
            assertEquals(before + 1L, MusicCapsuleCard.noticeRevision(), "audible commit cannot start a second popup");
            assertSame(committed, MusicCapsuleCard.trackNoticeSnapshot());
            assertEquals(902L, MusicCapsuleCard.activeNoticeSession());
            assertTrue(MusicCapsuleCard.matchesFrame(committed));
        }
    }

    @Test
    void firstPreparingSessionIsOneNoticeAndCancelledSelectionRollsBackWithoutReopeningOldMusic() throws Exception {
        try (SavedState ignored = new SavedState()) {
            Music old = music("first"), next = music("cancelled");
            CloudMusic.PlaybackSnapshot loading = pending(802L, 0L, old);
            MusicCapsuleCard.observePresentation(loading, false);
            long revision = MusicCapsuleCard.noticeRevision();
            assertTrue(MusicCapsuleCard.trackNoticeVisible(loading));
            MusicCapsuleCard.observePresentation(actual(802L, old), true);
            assertEquals(revision, MusicCapsuleCard.noticeRevision());
            MusicCapsuleCard.observePresentation(pending(-1L, 5321L, next), false);
            assertEquals(revision + 1L, MusicCapsuleCard.noticeRevision());
            field(MusicCapsuleCard.class, "flipCoverLocation").set(MusicCapsuleCard.INSTANCE,
                    com.muoniumplayer.core.utils.Location.of("cancelled-pending-cover"));
            CloudMusic.PlaybackSnapshot restored = actual(802L, old);
            MusicCapsuleCard.observePresentation(restored, true);
            assertNull(field(MusicCapsuleCard.class, "flipCoverLocation").get(MusicCapsuleCard.INSTANCE));
            assertEquals(revision + 1L, MusicCapsuleCard.noticeRevision(), "rollback is not a new playback event");
            assertSame(restored, MusicCapsuleCard.trackNoticeSnapshot());
            assertFalse(MusicCapsuleCard.trackTransitionActive());
        }
    }

    @Test
    void sourceReplacementKeepsTheAcceptedRequestRevisionAndFailedPreparationReleasesReadiness() throws Exception {
        try (SavedState ignored = new SavedState()) {
            Music old = music("original"), requested = music("requested-source"), replacement = music("replacement-source");
            MusicCapsuleCard.observePresentation(actual(301L, old), true);
            MusicCapsuleCard.observePresentation(pending(-1L, 963L, requested), false);
            long revision = MusicCapsuleCard.noticeRevision();
            CloudMusic.PlaybackSnapshot committed = new CloudMusic.PlaybackSnapshot(302L, replacement.getId(),
                    0L, 180_000L, true, null, replacement, false, 963L);
            field(MusicCapsuleCard.class, "flipCoverLocation").set(MusicCapsuleCard.INSTANCE,
                    com.muoniumplayer.core.utils.Location.of("old-resolved-cover"));
            MusicCapsuleCard.observePresentation(committed, false);
            assertFalse(MusicCapsuleCard.pendingEndedWithoutPlayback(), "accepted silent fade-in is not a failed preparation");
            assertNull(field(MusicCapsuleCard.class, "flipCoverLocation").get(MusicCapsuleCard.INSTANCE));
            MusicCapsuleCard.observePresentation(committed, true);
            assertEquals(revision, MusicCapsuleCard.noticeRevision(), "source resolution updates one request rather than starting a second card");
            assertSame(committed, MusicCapsuleCard.trackNoticeSnapshot());
            assertFalse(MusicCapsuleCard.pendingEndedWithoutPlayback());
            MusicCapsuleCard.observePresentation(pending(303L, 964L, requested), false);
            MusicCapsuleCard.observePresentation(new CloudMusic.PlaybackSnapshot(303L, requested.getId(),
                    0L, 180_000L, false, null, requested, false, 964L), false);
            assertTrue(MusicCapsuleCard.pendingEndedWithoutPlayback(), "failed preparation needs a terminal lock release");
        }
    }

    @Test
    void automaticCommittedFadeRespondsBeforeItsFirstAudibleGainWithoutASecondNotice() throws Exception {
        try (SavedState ignored = new SavedState()) {
            Music old = music("automatic-old"), next = music("automatic-next");
            MusicCapsuleCard.observePresentation(actual(101L, old), true);
            long before = MusicCapsuleCard.noticeRevision();
            CloudMusic.PlaybackSnapshot committed = new CloudMusic.PlaybackSnapshot(102L, next.getId(),
                    0L, 180_000L, true, null, next, false, 638L);
            MusicCapsuleCard.observePresentation(committed, false);
            assertEquals(before + 1L, MusicCapsuleCard.noticeRevision());
            assertTrue(MusicCapsuleCard.trackNoticeVisible(committed));
            assertFalse(MusicCapsuleCard.pendingEndedWithoutPlayback());
            MusicCapsuleCard.observePresentation(committed, true);
            assertEquals(before + 1L, MusicCapsuleCard.noticeRevision());
        }
    }

    @Test
    void returningToTheAudibleTrackClearsThePreviousPendingCover() throws Exception {
        try (SavedState ignored = new SavedState()) {
            Music old = music("cover-original"), requested = music("cover-next");
            MusicCapsuleCard.observePresentation(actual(501L, old), true);
            MusicCapsuleCard.observePresentation(pending(-1L, 863L, requested), false);
            field(MusicCapsuleCard.class, "flipCoverLocation").set(MusicCapsuleCard.INSTANCE,
                    com.muoniumplayer.core.utils.Location.of("stale-pending-cover"));
            MusicCapsuleCard.observePresentation(pending(-1L, 864L, old), false);
            assertNull(field(MusicCapsuleCard.class, "flipCoverLocation").get(MusicCapsuleCard.INSTANCE));
            assertFalse(MusicCapsuleCard.trackTransitionActive());
            assertEquals(1.0, field(MusicCapsuleCard.class, "coverFlipProgress").get(MusicCapsuleCard.INSTANCE));
        }
    }

    @Test
    void rapidRequestsUseTheNewestRequestAndPausingDoesNotCreateAnotherNotice() throws Exception {
        try (SavedState ignored = new SavedState()) {
            Music track = music("rapid");
            MusicCapsuleCard.observePresentation(actual(701L, track), true);
            long before = MusicCapsuleCard.noticeRevision();
            MusicCapsuleCard.observePresentation(pending(-1L, 321L, track), false);
            MusicCapsuleCard.observePresentation(pending(-1L, 322L, track), false);
            assertEquals(before + 2L, MusicCapsuleCard.noticeRevision());
            MusicCapsuleCard.observePresentation(actual(702L, track), true);
            assertEquals(before + 2L, MusicCapsuleCard.noticeRevision());
            MusicCapsuleCard.observePresentation(new CloudMusic.PlaybackSnapshot(702L, track.getId(),
                    64_000L, 180_000L, false, null, track), false);
            assertEquals(before + 2L, MusicCapsuleCard.noticeRevision());
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class SavedState implements AutoCloseable {
        private final String[] statics = {"frameSnapshot", "frameAudible", "lastAudibleSnapshot", "noticeRevision",
                "pausedTrack", "pausedSinceMs", "heldSessionId", "heldTrackKey", "heldSnapshot", "heldSinceMillis",
                "previousTrackKey", "previousCoverLocation", "previousCoverTextureId", "coverFlipElapsedMillis",
                "coverFlipDirection", "animationDirection", "observedTrackKey", "observedSessionId"};
        private final String[] members = {"coverLocation", "flipCoverLocation", "coverTextureId", "trackFlipPending",
                "coverFlipProgress", "coverTrackSession"};
        private final Object[] oldStatic = new Object[statics.length], oldMember = new Object[members.length];
        private final MusicCardEffects effects;
        private final Object oldWait;

        SavedState() throws Exception {
            for (int i = 0; i < statics.length; i++) oldStatic[i] = field(MusicCapsuleCard.class, statics[i]).get(null);
            for (int i = 0; i < members.length; i++) oldMember[i] = field(MusicCapsuleCard.class, members[i]).get(MusicCapsuleCard.INSTANCE);
            effects = (MusicCardEffects) field(MusicCapsuleCard.class, "effects").get(MusicCapsuleCard.INSTANCE);
            oldWait = field(MusicCardEffects.CoverWait.class, "elapsed").get(effects.coverWait);
            field(MusicCapsuleCard.class, "frameSnapshot").set(null,
                    new CloudMusic.PlaybackSnapshot(-1L, -1L, 0L, 0L, false, null, null));
            field(MusicCapsuleCard.class, "lastAudibleSnapshot").set(null, null);
            field(MusicCapsuleCard.class, "heldTrackKey").set(null, null);
            field(MusicCapsuleCard.class, "heldSessionId").set(null, Long.MIN_VALUE);
        }

        public void close() throws Exception {
            for (int i = 0; i < statics.length; i++) field(MusicCapsuleCard.class, statics[i]).set(null, oldStatic[i]);
            for (int i = 0; i < members.length; i++) field(MusicCapsuleCard.class, members[i]).set(MusicCapsuleCard.INSTANCE, oldMember[i]);
            field(MusicCardEffects.CoverWait.class, "elapsed").set(effects.coverWait, oldWait);
        }
    }
}
