package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.AudioSpectrumAnalyzer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.ExternalPlayback;
import com.muoniumplayer.core.ncm.music.LiveStreamSession;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.spotify.SpotifyConnectMirror;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.svg.AnimatedIcons;
import com.muoniumplayer.core.rendering.svg.SmilSvg;
import com.muoniumplayer.core.rendering.svg.SvgPlayback;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.screens.ncm.ConfirmationOverlay;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.PlayerIconAssets;
import com.muoniumplayer.core.screens.ncm.PlayerIslandTransition;
import com.muoniumplayer.core.screens.ncm.TransitionCurveCache;
import com.muoniumplayer.core.screens.ncm.panels.LikeButton;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaImages;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.awt.Color;

/** 收起音乐行与五款样式共用的聊天音乐卡；所有控件使用 MusicCardLayout 的设计空间。 */
public final class MusicCapsuleCard implements SharedRenderingConstants {
    public static final double CARD_WIDTH = MusicCardLayout.WIDTH;
    public static final double CARD_HEIGHT = MusicCardLayout.HEIGHT;
    public static final double CARD_RADIUS = MusicCardLayout.RADIUS;
    public static final double PROGRESS_X = MusicCardLayout.PROGRESS_X;
    public static final double PROGRESS_Y = MusicCardLayout.PROGRESS_Y;
    public static final double CONTROLS_Y = MusicCardLayout.CONTROLS_Y;
    public static final double COVER_X = MusicCardLayout.COVER_X;
    public static final double COVER_Y = MusicCardLayout.COVER_Y;
    public static final double COVER_SIZE = MusicCardLayout.COVER_SIZE;
    public static final double ROW_HEIGHT = 26.0;
    static final double ROW_COVER_SIZE = 18.0;
    static final double ROW_COVER_INSET = 7.0;
    static final double ROW_TEXT_LEFT = 33.0;
    static final double ROW_RIGHT_PAD = 9.0;
    static final double ROW_BARS_WIDTH = 22.0;
    static final long PAUSE_TIMEOUT_MILLIS = 10000L;
    private static final double MARQUEE_HOLD_MILLIS = 900.0;
    private static final double MARQUEE_SPEED = 25.0;

    private static final CloudMusic.PlaybackSnapshot EMPTY_SNAPSHOT =
            new CloudMusic.PlaybackSnapshot(-1L, -1L, 0L, 0L, false, null, null);
    private static CloudMusic.PlaybackSnapshot frameSnapshot = EMPTY_SNAPSHOT;
    private static boolean frameAudible;
    private static long noticeRevision;
    private static CloudMusic.PlaybackSnapshot lastAudibleSnapshot;
    public static final MusicCapsuleCard INSTANCE = new MusicCapsuleCard();
    private static long pausedSinceMs;
    private static String pausedTrack = "";
    private static String observedTrackKey = "";
    private static long observedSessionId = Long.MIN_VALUE;
    private static long heldSessionId = Long.MIN_VALUE;
    private static String heldTrackKey;
    private static long heldSinceMillis;
    private static CloudMusic.PlaybackSnapshot heldSnapshot;
    private static String previousTrackKey = "";
    private static Location previousCoverLocation;
    private static int previousCoverTextureId;
    private static double coverFlipElapsedMillis;
    private static double coverFlipDirection = -1.0;
    private static int animationDirection;
    private static String rowText = "";
    private static String rowPreviousText = "";
    private static float rowFade = 1f;
    private static double rowFrameDeltaMillis = 16.0;
    private static LyricLine rowLyric;
    private static long rowSession = Long.MIN_VALUE;
    private static String rowTrackKey = "";
    private static double rowPreviousOffset;
    private static double rowOffset;
    private static double rowElapsedMillis;
    private static final MusicCapsuleLyrics ROW_LYRICS = new MusicCapsuleLyrics();
    private static final float[] BAR_LEVELS = new float[6];
    private static final int[] BAR_BANDS = {2, 5, 9, 14, 21, 31};
    private static final MusicCardLayout.Target[] CONTROL_TARGETS = MusicCardLayout.Target.values();

    private final MusicCardEffects effects = new MusicCardEffects();
    private String headline = "";
    private String previousHeadline = "";
    private float headlineFade = 1f;
    private long headlineStartedAtNanos = System.nanoTime();
    private String trackKey = "";
    private String coverTrackKey = "";
    private Location coverLocation;
    private Location flipCoverLocation;
    private int coverTextureId;
    private long coverTrackSession = Long.MIN_VALUE;
    private boolean trackFlipPending;
    private double coverFlipProgress = 1.0;
    private long subtitleStartedAt = System.nanoTime();
    private int accent = 0xD6D9E5;
    private int secondaryAccent = 0x9FAAC4;
    private MusicCardLayout.Target hovered = MusicCardLayout.Target.NONE;
    private MusicCardLayout.Target pressed = MusicCardLayout.Target.NONE;
    private final float[] hoverAmounts = new float[MusicCardLayout.Target.values().length];
    private final float[] pressAmounts = new float[MusicCardLayout.Target.values().length];
    private SvgPlayback likePlayback;
    private String likeTrack = "";
    private long lastLikePressNanos;
    private ConfirmationOverlay confirmation;
    private long confirmationSession;
    private String confirmationTrack;
    private String hint = "";
    private long hintUntil;

    private MusicCapsuleCard() {
    }

    public static float cardScale() {
        float scale = HudConfig.islandMiniCardScale;
        return Float.isFinite(scale) ? Math.max(.5f, Math.min(2.0f, scale)) : 1.0f;
    }

    private static CloudMusic.PlaybackSnapshot snapshot() {
        try {
            return CloudMusic.getSnapshot();
        } catch (Throwable t) {
            return EMPTY_SNAPSHOT;
        }
    }

    public static void observePlayback() {
        CloudMusic.PlaybackSnapshot presentation = CloudMusic.getIslandSnapshot();
        boolean audible = !presentation.preparing && CloudMusic.isAudibleSnapshot(presentation);
        CloudMusic.PlaybackSnapshot actual = presentation.preparing && presentation.requestNanos > 0L
                ? snapshot() : presentation;
        observePresentation(presentation, audible);
        if (CloudMusic.isAudibleSnapshot(actual)) lastAudibleSnapshot = actual;
    }

    static void observePresentation(CloudMusic.PlaybackSnapshot presentation, boolean audible) {
        CloudMusic.PlaybackSnapshot previous = frameSnapshot;
        frameSnapshot = presentation;
        frameAudible = audible && !presentation.preparing;
        String key = frameSnapshot.music == null ? "" : frameSnapshot.music.getStableKey();
        if (!key.equals(pausedTrack)) {
            pausedTrack = key;
            pausedSinceMs = 0L;
        }
        if (frameSnapshot.music == null || frameSnapshot.playing || frameSnapshot.preparing) pausedSinceMs = 0L;
        else if (pausedSinceMs == 0L) pausedSinceMs = System.currentTimeMillis();

        boolean sameRequest = previous.requestNanos > 0L && previous.requestNanos == frameSnapshot.requestNanos;
        boolean sameCommittedTrack = previous.music != null && key.equals(previous.music.getStableKey())
                && previous.sessionId == frameSnapshot.sessionId;
        boolean pendingUpgrade = previous.preparing && !frameSnapshot.preparing
                && (sameRequest || sameCommittedTrack || previous.music != null && key.equals(previous.music.getStableKey())
                && frameSnapshot.requestNanos == 0L);
        boolean cancelledPending = previous.preparing && !frameSnapshot.preparing && !pendingUpgrade;
        boolean requestChanged = frameSnapshot.requestNanos > 0L
                && previous.requestNanos != frameSnapshot.requestNanos;
        boolean identityChanged = previous.music == null || previous.sessionId != frameSnapshot.sessionId
                || !key.equals(previous.music.getStableKey());
        boolean audibleTransition = frameAudible && (lastAudibleSnapshot == null || lastAudibleSnapshot.music == null
                || lastAudibleSnapshot.sessionId != frameSnapshot.sessionId
                || !key.equals(lastAudibleSnapshot.music.getStableKey()));
        boolean alreadyHeld = frameSnapshot.music != null && heldTrackKey != null
                && heldTrackKey.equals(key) && heldSessionId == frameSnapshot.sessionId;
        if (frameSnapshot.music != null && (frameSnapshot.preparing && (identityChanged || requestChanged) && !sameRequest
                || !frameSnapshot.preparing && frameSnapshot.playing && requestChanged && !pendingUpgrade && !cancelledPending
                || audibleTransition && !pendingUpgrade && !cancelledPending && !alreadyHeld && !sameRequest)) {
            String fromTrack = lastAudibleSnapshot == null || lastAudibleSnapshot.music == null ? ""
                    : lastAudibleSnapshot.music.getStableKey();
            int direction = CloudMusic.consumeIslandTrackDirection(fromTrack, key);
            beginTrackTransition(lastAudibleSnapshot, frameSnapshot, direction);
            noticeRevision++;
        } else if (frameSnapshot.music != null && (pendingUpgrade || alreadyHeld || sameRequest)) {
            heldSessionId = frameSnapshot.sessionId;
            heldTrackKey = key;
            heldSnapshot = frameSnapshot;
            INSTANCE.coverTrackSession = frameSnapshot.sessionId;
            if (previous.music != null && !key.equals(previous.music.getStableKey())) {
                INSTANCE.flipCoverLocation = null;
                INSTANCE.coverLocation = coverLocationFor(frameSnapshot.music);
                INSTANCE.coverTextureId = 0;
            }
        } else if (previous.preparing && !frameSnapshot.preparing) {
            heldSnapshot = frameSnapshot;
            heldSessionId = frameSnapshot.sessionId;
            heldTrackKey = frameSnapshot.music == null ? null : key;
            previousTrackKey = "";
            previousCoverLocation = null;
            previousCoverTextureId = 0;
            INSTANCE.flipCoverLocation = null;
            INSTANCE.coverLocation = frameSnapshot.music == null ? null : coverLocationFor(frameSnapshot.music);
            INSTANCE.coverTextureId = 0;
            INSTANCE.trackFlipPending = false;
            INSTANCE.coverFlipProgress = 1.0;
        }
        if (frameAudible) lastAudibleSnapshot = frameSnapshot;
        observedTrackKey = key;
        observedSessionId = frameSnapshot.sessionId;
    }

    static long noticeRevision() {
        return noticeRevision;
    }

    static boolean pendingEndedWithoutPlayback() {
        return frameSnapshot.music != null && !frameSnapshot.preparing && !frameAudible && !frameSnapshot.playing;
    }

    static boolean presentationReady() {
        return frameAudible && !frameSnapshot.preparing && trackCoverReady(frameSnapshot);
    }

    private static void beginTrackTransition(CloudMusic.PlaybackSnapshot previous,
                                             CloudMusic.PlaybackSnapshot current, int direction) {
        heldSessionId = current.sessionId;
        heldTrackKey = current.music.getStableKey();
        heldSnapshot = current;
        heldSinceMillis = System.currentTimeMillis();
        if (previous == null || previous.music == null || previous.music.getStableKey().equals(heldTrackKey)) {
            previousTrackKey = "";
            previousCoverLocation = null;
            previousCoverTextureId = 0;
            INSTANCE.flipCoverLocation = null;
            INSTANCE.coverLocation = coverLocationFor(current.music);
            INSTANCE.coverTextureId = 0;
            INSTANCE.trackFlipPending = false;
            INSTANCE.coverFlipProgress = 1.0;
            INSTANCE.coverTrackSession = current.sessionId;
            return;
        }
        MusicCapsuleCard card = INSTANCE;
        String oldKey = previous.music.getStableKey();
        previousTrackKey = oldKey;
        previousCoverLocation = card.coverLocationFor(previous.music);
        ITextureObject oldTexture = card.coverTextureObject(previous.music, previousCoverLocation);
        previousCoverTextureId = oldTexture == null ? 0 : oldTexture.getGlTextureId();
        if (previousCoverTextureId == 0) {
            Location fallback = previous.music.getSmallCoverLocation();
            ITextureObject small = card.coverTextureObject(previous.music, fallback);
            previousCoverTextureId = small == null ? 0 : small.getGlTextureId();
        }
        coverFlipElapsedMillis = 0.0;
        coverFlipDirection = direction < 0 ? 1.0 : -1.0;
        card.effects.coverWait.begin();
        animationDirection = direction;
        card.coverLocation = card.coverLocationFor(current.music);
        card.flipCoverLocation = null;
        card.coverTextureId = 0;
        card.trackFlipPending = true;
        card.coverFlipProgress = 0.0;
        card.coverTrackSession = current.sessionId;
    }

    public static void tickTrackTransition(float deltaMillis) {
        double dt = MusicCardEffects.frameDelta(deltaMillis);
        if (dt <= .001) return;
        MusicCapsuleCard card = INSTANCE;
        if (previousTrackKey.isEmpty()) return;
        CloudMusic.PlaybackSnapshot snapshot = heldSnapshot;
        if (snapshot == null || snapshot.music == null || heldTrackKey == null || snapshot.sessionId != heldSessionId
                || !heldTrackKey.equals(snapshot.music.getStableKey()) || !heldTrackKey.equals(card.trackKey)
                || frameSnapshot.music == null || frameSnapshot.sessionId != heldSessionId
                || !heldTrackKey.equals(frameSnapshot.music.getStableKey())) return;
        ITextureObject texture = card.coverTextureObject(snapshot.music,
                card.flipCoverLocation == null ? coverLocationFor(snapshot.music) : card.flipCoverLocation);
        if (texture == null) {
            if (card.effects.coverWait.expired()) {
                // Missing art is a bounded placeholder downgrade, never a reason to pin the old song's cover.
                card.coverLocation = coverLocationFor(snapshot.music);
                card.coverTextureId = 0;
                card.trackFlipPending = false;
                card.coverFlipProgress = 1.0;
                card.flipCoverLocation = null;
                previousTrackKey = "";
                previousCoverLocation = null;
                previousCoverTextureId = 0;
                card.effects.ripple.trigger();
            }
            return;
        }
        if (card.flipCoverLocation == null) card.flipCoverLocation = coverLocationFor(snapshot.music);
        card.coverLocation = card.flipCoverLocation;
        card.coverTextureId = texture.getGlTextureId();
        if (card.trackFlipPending) {
            card.trackFlipPending = false;
            coverFlipElapsedMillis = 0.0;
            card.coverFlipProgress = 0.0;
            card.effects.ripple.trigger();
            return;
        }
        coverFlipElapsedMillis = Math.min(420.0, coverFlipElapsedMillis + dt);
        card.coverFlipProgress = Math.min(1.0, coverFlipElapsedMillis / 420.0);
        if (card.coverFlipProgress >= 1.0) {
            previousTrackKey = "";
            previousCoverLocation = null;
            previousCoverTextureId = 0;
            card.flipCoverLocation = null;
        }
    }

    public static CloudMusic.PlaybackSnapshot trackNoticeSnapshot() {
        return heldSnapshot != null ? heldSnapshot : frameSnapshot;
    }

    public static void updateTrackNoticePlayback(CloudMusic.PlaybackSnapshot snapshot) {
        if (snapshot != null && snapshot.music != null && snapshot.sessionId == heldSessionId
                && heldTrackKey != null && heldTrackKey.equals(snapshot.music.getStableKey())) {
            heldSnapshot = snapshot;
        }
    }

    public static boolean trackNoticeVisible(CloudMusic.PlaybackSnapshot snapshot) {
        return snapshot != null && snapshot.music != null
                && (snapshot.preparing || frameAudible || snapshot.playing && snapshot.requestNanos > 0L)
                && snapshot.sessionId == heldSessionId && heldTrackKey != null
                && heldTrackKey.equals(snapshot.music.getStableKey());
    }

    public static long activeNoticeSession() {
        return heldSessionId;
    }

    public static long activeNoticeStartedAt() {
        return heldSinceMillis;
    }

    static boolean trackTransitionActive() {
        return !previousTrackKey.isEmpty();
    }

    static int animationDirection() {
        return animationDirection;
    }

    static double coverFlipProgress() {
        return INSTANCE.coverFlipProgress;
    }

    static boolean trackCoverReady(CloudMusic.PlaybackSnapshot snapshot) {
        if (snapshot == null || snapshot.music == null || previousTrackKey.isEmpty()
                || heldSessionId != snapshot.sessionId || heldTrackKey == null
                || !heldTrackKey.equals(snapshot.music.getStableKey())) return true;
        if (INSTANCE.effects.coverWait.expired()) return true;
        Location location = snapshot.music.getStableKey().equals(INSTANCE.coverTrackKey)
                ? INSTANCE.coverLocation : coverLocationFor(snapshot.music);
        ITextureObject current = INSTANCE.coverTextureObject(snapshot.music, location);
        if (current != null && current.getGlTextureId() != 0) return true;
        Location fallback = snapshot.music.getSmallCoverLocation();
        ITextureObject small = fallback == null ? null : INSTANCE.coverTextureObject(snapshot.music, fallback);
        return small != null && small.getGlTextureId() != 0;
    }

    public static CloudMusic.PlaybackSnapshot currentSnapshot() {
        return frameSnapshot;
    }

    static boolean matchesFrame(CloudMusic.PlaybackSnapshot snapshot) {
        return !snapshot.preparing && !frameSnapshot.preparing && snapshot.music != null && frameSnapshot.music != null
                && snapshot.sessionId == frameSnapshot.sessionId
                && snapshot.music.getStableKey().equals(frameSnapshot.music.getStableKey());
    }

    static boolean snapshotIdentityMatches(CloudMusic.PlaybackSnapshot expected, CloudMusic.PlaybackSnapshot current) {
        return expected != null && current != null && !expected.preparing && !current.preparing
                && expected.music != null && current.music != null && expected.sessionId == current.sessionId
                && expected.music.getStableKey().equals(current.music.getStableKey());
    }

    static boolean frameHasMusic() {
        return frameSnapshot.music != null;
    }

    public static boolean hasMusic() {
        return snapshot().music != null;
    }

    public static boolean rowContentActive() {
        return rowContentActive(frameSnapshot.music != null, pausedSinceMs, System.currentTimeMillis(),
                CloudMusic.hasRestoredPlaybackMemory());
    }

    static boolean rowContentActive(boolean hasMusic, long pausedSinceMillis, long nowMillis,
                                    boolean restoredMemoryActive) {
        return hasMusic && (restoredMemoryActive || pausedSinceMillis == 0L
                || nowMillis - pausedSinceMillis < PAUSE_TIMEOUT_MILLIS);
    }

    public static double progressFraction(CloudMusic.PlaybackSnapshot snapshot) {
        return snapshot.durationMs <= 0L ? 0.0
                : DynamicIslandMath.clamp01(snapshot.positionMs / (double) snapshot.durationMs);
    }

    /** 歌词偏移由 findCurrentLyric 统一应用；旧曲时间轴尚未被替换时显示当前歌名。 */
    private static LyricLine rowLyric(CloudMusic.PlaybackSnapshot snapshot) {
        Music music = snapshot.music;
        if (music == null || snapshot.preparing || !HudConfig.islandMusicShowsLyric
                || !CloudMusic.lyricsMatchCurrentTrack() || !music.getStableKey().equals(CloudMusic.lyricsTrackKey)) return null;
        LyricLine line = CloudMusic.findCurrentLyric(snapshot.positionMs);
        return line == null || line.lyric == null || line.lyric.trim().isEmpty() ? null : line;
    }

    public static String rowText(CloudMusic.PlaybackSnapshot snapshot) {
        LyricLine line = rowLyric(snapshot);
        return line != null ? line.lyric.trim() : snapshot.music == null ? "" : songLabel(snapshot.music);
    }

    private static String songLabel(Music music) {
        String name = music.getName() == null ? "" : music.getName().trim();
        String artist = music.getArtistsName() == null ? "" : music.getArtistsName().trim();
        return artist.isEmpty() ? name : name + " — " + artist;
    }

    private static String cardSubtitle(Music music) {
        String subtitle = songLabel(music);
        if (music != null && music.isCrossSourceSwitched()) {
            String source = music.getUnblockedSourceLabel();
            if (source != null && !source.trim().isEmpty()) subtitle += " · 已换源至 " + source.trim();
        }
        return subtitle;
    }

    public static void tick(float deltaMillis) {
        tick(deltaMillis, deltaMillis);
    }

    static void tick(float deltaMillis, float coverWaitMillis) {
        rowFrameDeltaMillis = MusicCardEffects.frameDelta(deltaMillis);
        INSTANCE.effects.coverWait.tick(coverWaitMillis);
        LyricLine nextLine = rowLyric(frameSnapshot);
        String next = nextLine != null ? nextLine.lyric.trim()
                : frameSnapshot.music == null ? "" : songLabel(frameSnapshot.music);
        String key = frameSnapshot.music == null ? "" : frameSnapshot.music.getStableKey();
        if (!next.equals(rowText) || nextLine != rowLyric || rowSession != frameSnapshot.sessionId
                || !key.equals(rowTrackKey)) {
            rowPreviousText = rowText;
            rowPreviousOffset = rowOffset;
            rowOffset = 0.0;
            rowElapsedMillis = 0.0;
            rowText = next;
            rowLyric = nextLine;
            rowSession = frameSnapshot.sessionId;
            rowTrackKey = key;
            rowFade = 0f;
        }
        ROW_LYRICS.prepare(rowLyric, rowText, rowFont());
        if (frameSnapshot.playing) rowElapsedMillis += rowFrameDeltaMillis;
        rowFade = Math.min(1f, rowFade + (float) (rowFrameDeltaMillis / MusicCapsuleLyrics.SWITCH_MILLIS));
        INSTANCE.tickCard();
        tickBars();
    }

    private void tickCard() {
        // These effects keep their own state across track changes, but share the card's one frame delta.
        effects.tickPresentation(rowFrameDeltaMillis, frameSnapshot.playing);
        Music music = frameSnapshot.music;
        String key = music == null ? "" : music.getStableKey();
        if (music == null) {
            coverTrackKey = "";
            coverLocation = null;
            flipCoverLocation = null;
            coverTextureId = 0;
            coverFlipProgress = 1.0;
            trackFlipPending = false;
            previousTrackKey = "";
            previousCoverLocation = null;
            previousCoverTextureId = 0;
        } else if (!key.equals(coverTrackKey)) {
            if (!key.equals(heldTrackKey) || frameSnapshot.sessionId != heldSessionId) {
                previousTrackKey = "";
                previousCoverLocation = null;
                previousCoverTextureId = 0;
                flipCoverLocation = null;
            }
            coverTrackKey = key;
            coverLocation = coverLocationFor(music);
            ITextureObject cover = coverTextureObject(music, coverLocation);
            coverTextureId = cover == null ? 0 : cover.getGlTextureId();
            boolean transitioningToHeldTrack = !previousTrackKey.isEmpty()
                    && heldSessionId == frameSnapshot.sessionId && key.equals(heldTrackKey);
            if (transitioningToHeldTrack) {
                coverTrackSession = frameSnapshot.sessionId;
            } else {
                coverFlipProgress = 1.0;
                trackFlipPending = false;
            }
        } else if (music != null) {
            Location preferred = coverLocationFor(music);
            if (!sameLocation(preferred, coverLocation) && !trackTransitionActive()) {
                coverLocation = preferred;
                ITextureObject cover = coverTextureObject(music, preferred);
                coverTextureId = cover == null ? 0 : cover.getGlTextureId();
            }
        }
        String next = music == null ? "" : frameSnapshot.preparing ? music.getName()
                : HudConfig.islandMusicShowsLyric ? rowText : music.getName() == null ? "" : music.getName();
        if (!key.equals(trackKey)) {
            trackKey = key;
            previousHeadline = "";
            headline = next;
            headlineFade = 0f;
            subtitleStartedAt = System.nanoTime();
            headlineStartedAtNanos = subtitleStartedAt;
            likePlayback = null;
            likeTrack = key;
            hintUntil = 0L;
            cancelConfirmation();
            accent = MusicCardColors.accent(music == null ? null : music.getCoverPalette(), NCMTheme.getAccentColor());
            secondaryAccent = MusicCardColors.secondary(music == null ? null : music.getCoverPalette(), accent);
        } else if (!next.equals(headline)) {
            previousHeadline = headline;
            headline = next;
            headlineFade = 0f;
            headlineStartedAtNanos = System.nanoTime();
        }
        headlineFade = Math.min(1f, headlineFade + (float) (rowFrameDeltaMillis / 220.0));
        double blend = 1.0 - Math.exp(-rowFrameDeltaMillis / 180.0);
        int target = MusicCardColors.accent(music == null ? null : music.getCoverPalette(), NCMTheme.getAccentColor());
        accent = MusicCardColors.mix(accent, target, blend);
        secondaryAccent = MusicCardColors.mix(secondaryAccent,
                MusicCardColors.secondary(music == null ? null : music.getCoverPalette(), target), blend);
        double hoverRate = 1.0 - Math.exp(-rowFrameDeltaMillis / 65.0);
        double pressRate = 1.0 - Math.exp(-rowFrameDeltaMillis / 45.0);
        for (MusicCardLayout.Target t : CONTROL_TARGETS) {
            int i = t.ordinal();
            float hoverTarget = hovered == t ? 1f : 0f;
            float pressTarget = pressed == t ? 1f : 0f;
            hoverAmounts[i] += (hoverTarget - hoverAmounts[i]) * (float) hoverRate;
            pressAmounts[i] += (pressTarget - pressAmounts[i]) * (float) pressRate;
        }
        if (confirmation != null && (confirmationSession != frameSnapshot.sessionId
                || !key.equals(confirmationTrack))) cancelConfirmation();
    }

    private static void tickBars() {
        boolean audible = frameSnapshot.player != null && frameSnapshot.playing
                && frameSnapshot.player.isUsable() && frameSnapshot.player.isPlaying();
        AudioSpectrumAnalyzer.Frame frame = null;
        long now = System.nanoTime();
        if (audible) {
            try {
                frame = AudioSpectrumAnalyzer.requestFrame(now);
            } catch (Throwable ignored) {
            }
        }
        boolean fresh = frame != null && frame.capturedAt > 0L && now - frame.capturedAt < 250_000_000L;
        for (int i = 0; i < BAR_LEVELS.length; i++) {
            float target = fresh ? frame.band(BAR_BANDS[i]) : 0f;
            BAR_LEVELS[i] += (target - BAR_LEVELS[i]) * (float) (1.0 - Math.exp(-rowFrameDeltaMillis * .014));
        }
    }

    private static CFontRenderer rowFont() {
        return FontManager.pf20 != null ? FontManager.pf20 : FontManager.pf20bold;
    }

    public static double rowTextWidth() {
        ROW_LYRICS.prepare(rowLyric, rowText, rowFont());
        return ROW_LYRICS.width();
    }

    public static double rowTargetWidth() {
        return DynamicIslandLayoutCalculator.musicRowWidth(HudConfig.dynamicIslandMaxWidth,
                HudConfig.islandMusicCapsuleMaxWidth,
                ROW_TEXT_LEFT + rowTextWidth() + ROW_BARS_WIDTH + 6.0 + ROW_RIGHT_PAD,
                HudConfig.dynamicIslandScale, RenderSystem.getLayoutWidth());
    }

    static int motionTrailColor() {
        return MusicCardColors.mix(INSTANCE.accent, INSTANCE.secondaryAccent, .38);
    }

    public static double capsuleNoticeWidth(double targetWidth, double currentWidth, double configuredScale,
                                            double screenWidth) {
        double screenLimit = Math.max(126.0, Math.max(1.0, screenWidth - 24.0)
                / DynamicIslandMath.clamp(configuredScale, .60, 1.35));
        return Math.min(screenLimit, Math.max(Math.min(screenLimit, 176.0),
                Math.min(screenLimit, Math.max(targetWidth, currentWidth))));
    }

    static boolean showCapsuleRow(boolean musicCapsuleStyle, boolean preview, boolean notice,
                                  boolean downloading, boolean completing, boolean mixing,
                                  boolean musicActive, boolean cardTransitionActive) {
        return showCapsuleRow(musicCapsuleStyle, preview, notice, downloading, completing, mixing,
                musicActive, cardTransitionActive, false);
    }

    static boolean showCapsuleRow(boolean musicCapsuleStyle, boolean preview, boolean notice,
                                  boolean downloading, boolean completing, boolean mixing,
                                  boolean musicActive, boolean cardTransitionActive, boolean capsuleBorderActive) {
        return musicCapsuleStyle && !cardTransitionActive
                && MusicCardMotion.showCapsuleRow(preview, notice, capsuleBorderActive,
                downloading, completing, mixing, musicActive);
    }

    static double marqueeOffset(double textWidth, double viewportWidth, double elapsedMillis) {
        if (!Double.isFinite(textWidth) || !Double.isFinite(viewportWidth)
                || !Double.isFinite(elapsedMillis) || textWidth <= viewportWidth || viewportWidth <= 0.0) return 0.0;
        double travel = textWidth + Math.max(24.0, viewportWidth * .18);
        double movingMillis = Math.max(0.0, elapsedMillis - MARQUEE_HOLD_MILLIS);
        return (movingMillis * MARQUEE_SPEED / 1000.0) % travel;
    }

    public void drawRow(double x, double y, double width, double height, float alpha, int ignoredAccent) {
        drawRow(x, y, width, height, alpha, null);
    }

    /** Test supply replaces only the already-decoded cover; production geometry is unchanged. */
    void drawRow(double x, double y, double width, double height, float alpha,
                 io.github.humbleui.skija.Image rasterCover) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || alpha <= .01f || !Double.isFinite(width) || !Double.isFinite(height)
                || width <= 6.0 || height <= 0.0) return;
        double centerY = y + height * .5;
        double coverSize = Math.max(0.0, Math.min(ROW_COVER_SIZE, height - 8.0));
        int saveRow = canvas.save();
        try {
            canvas.clipRect(Rect.makeXYWH((float) (x + 3.0), (float) y,
                    (float) (width - 6.0), (float) height));
            if (coverSize > 2.0) {
                double coverX = x + ROW_COVER_INSET;
                double coverY = centerY - coverSize * .5;
                try (io.github.humbleui.skija.Path shape = MusicCardShape.outline(
                        coverX, coverY, coverSize, coverSize, Math.min(4.0, coverSize * .28))) {
                    int saveCover = canvas.save();
                    try {
                        canvas.clipPath(shape, true);
                        drawCoverFallback(canvas, shape, coverX, coverY, coverSize, alpha);
                        if (rasterCover != null) {
                            drawSquareImage(canvas, rasterCover, coverX, coverY, coverSize, alpha);
                        } else {
                            // Resolve from this snapshot, never the card's retained/previous track location.
                            Music music = frameSnapshot.music;
                            ITextureObject texture = coverTextureObject(music, coverLocationFor(music));
                            if (texture != null) drawSquareTexture(texture.getGlTextureId(),
                                    SkijaImages.sizeOf(texture.getGlTextureId()), coverX, coverY, coverSize, alpha);
                        }
                    } finally {
                        canvas.restoreToCount(saveCover);
                    }
                }
            }
            double textLeft = x + ROW_TEXT_LEFT;
            double textMax = Math.max(1.0, width - ROW_TEXT_LEFT - ROW_BARS_WIDTH - 6.0 - ROW_RIGHT_PAD);
            boolean atFullWidth = width + .5 >= rowTargetWidth();
            rowOffset = !atFullWidth ? 0.0 : rowLyric != null
                    ? ROW_LYRICS.offset(textMax, frameSnapshot.positionMs + (double) CloudMusic.getLyricOffsetMillis())
                    : Math.min(Math.max(0.0, ROW_LYRICS.width() - textMax),
                    Math.max(0.0, rowElapsedMillis - MARQUEE_HOLD_MILLIS) * MARQUEE_SPEED / 1000.0);
            double switchMix = MusicCapsuleLyrics.ease(rowFade);
            if (rowFade < .999f) drawRowText(rowPreviousText, textLeft, centerY, textMax,
                    alpha * (float) (1.0 - switchMix), -7.0 * switchMix, rowPreviousOffset);
            drawRowText(rowText, textLeft, centerY, textMax, alpha * (float) switchMix,
                    8.0 * (1.0 - switchMix), rowOffset);
            double barsX = textLeft + textMax + 6.0;
            for (int i = 0; i < BAR_LEVELS.length; i++) {
                double barHeight = 2.0 + 8.0 * DynamicIslandMath.clamp01(BAR_LEVELS[i]);
                roundedRect(barsX + i * 4.0, centerY - barHeight * .5, 2.0, barHeight, 1.0,
                        RenderSystem.reAlpha(accent, alpha * .82f));
            }
        } finally {
            canvas.restoreToCount(saveRow);
        }
    }

    private void drawRowText(String text, double x, double centerY, double maxWidth, float alpha,
                             double slide, double offset) {
        CFontRenderer font = rowFont();
        if (font == null || text.isEmpty() || alpha <= .01f) return;
        Canvas canvas = SkijaGfx.canvas();
        int save = canvas.save();
        try {
            canvas.clipRect(Rect.makeXYWH((float) x, (float) (centerY - 11.0), (float) maxWidth, 22f));
            Rect ink = font.getGlyphBounds(text);
            double drawY = centerY - (ink == null ? font.getFontHeight() * .5
                    : (ink.getTop() + ink.getBottom()) * .5) + slide;
            int color = RenderSystem.reAlpha(0xF4F5FA, alpha);
            double textWidth = font.getStringWidthD(text);
            boolean clippedLeft = offset > .01;
            boolean clippedRight = textWidth - offset > maxWidth + .5;
            if (!clippedLeft && !clippedRight) {
                font.drawString(text, x - offset, drawY, color);
            } else {
                float feather = (float) Math.min(.12, 5.0 / maxWidth);
                try (Shader edge = Shader.makeLinearGradient((float) x, 0f, (float) (x + maxWidth), 0f,
                        new int[]{clippedLeft ? 0x00F4F5FA : color, color, color,
                                clippedRight ? 0x00F4F5FA : color}, new float[]{0f, feather, 1f - feather, 1f})) {
                    font.drawStringShaded(text, x - offset, drawY, edge);
                }
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** 表面自带真实背景高斯模糊，暗色上沿压住文字，封面色柔光停留在卡面内部。 */
    public void drawSurface(double x, double y, double width, double height, double radius, float alpha) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || width <= 0.0 || height <= 0.0 || alpha <= .001f) return;
        try (io.github.humbleui.skija.Path shape = MusicCardShape.outline(x, y, width, height, radius)) {
            int save = canvas.save();
            try {
                canvas.clipPath(shape, true);
                boolean frosted = GlassLook.paintMusicCardFrost(x, y, width, height, radius, alpha);
                MusicCardSurface.paint(canvas, shape, x, y, width, height, radius, accent, secondaryAccent, alpha, frosted);
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }

    public void drawNoticeTransitionSurface(double x, double y, double width, double height,
                                            double radius, float alpha, double blurSigma) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || width <= 0.0 || height <= 0.0 || alpha <= .001f) return;
        try (io.github.humbleui.skija.Path shape = MusicCardShape.outline(x, y, width, height, radius)) {
            int save = canvas.save();
            try {
                canvas.clipPath(shape, true);
                if (blurSigma > .05) {
                    com.muoniumplayer.render.skija.SkijaEffects.backdropBlurRounded(x, y, width, height,
                            radius, (float) Math.min(12.0, blurSigma), alpha * .72f);
                }
                boolean frosted = GlassLook.paintMusicCardFrost(x, y, width, height, radius, alpha);
                MusicCardSurface.paint(canvas, shape, x, y, width, height, radius, accent, secondaryAccent, alpha, frosted);
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }

    /** 交接时沿用同一连续曲率表面，材质随原播放器时钟淡到目标底色。 */
    public void drawTransitionSurface(double x, double y, double width, double height, double radius,
                                      float cardMix, Color playerColor, boolean frosted) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return;
        float mix = Math.max(0f, Math.min(1f, cardMix));
        MusicCardSurface.paint(canvas, x, y, width, height, radius, accent, secondaryAccent, 1f, frosted);
        if (mix < .999f) {
            try (io.github.humbleui.skija.Path shape = MusicCardShape.outline(x, y, width, height, radius)) {
                canvas.drawPath(shape, SkijaGfx.fill(MusicCardSurface.color(playerColor.getRGB(), 1f - mix)));
            }
        }
    }

    public void drawCard(double x, double y, float scale, float alpha, boolean preview) {
        drawCard(x, y, CARD_WIDTH * scale, CARD_HEIGHT * scale, alpha, preview);
    }

    public void drawCard(double x, double y, double width, double height, float alpha, boolean preview) {
        drawCard(x, y, width, height, alpha, preview, CARD_RADIUS * Math.min(width / CARD_WIDTH, height / CARD_HEIGHT));
    }

    public void drawCard(double x, double y, double width, double height, float alpha,
                         boolean preview, double radius) {
        drawCard(x, y, width, height, alpha, null, radius);
    }

    /** 离屏光栅入口只替换封面供给；排版、材质和控件继续使用生产绘制路径。 */
    void drawCard(double x, double y, double width, double height, float alpha,
                  io.github.humbleui.skija.Image rasterCover) {
        drawCard(x, y, width, height, alpha, rasterCover,
                CARD_RADIUS * Math.min(width / CARD_WIDTH, height / CARD_HEIGHT));
    }

    private void drawCard(double x, double y, double width, double height, float alpha,
                          io.github.humbleui.skija.Image rasterCover, double radius) {
        drawCard(x, y, width, height, alpha, rasterCover, radius, frameSnapshot);
    }

    public void drawCard(double x, double y, double width, double height, float alpha,
                         boolean preview, double radius, CloudMusic.PlaybackSnapshot snapshot) {
        drawCard(x, y, width, height, alpha, null, radius, snapshot);
    }

    void drawNoticeCard(double x, double y, double width, double height, float alpha, double radius,
                        CloudMusic.PlaybackSnapshot snapshot) {
        drawCard(x, y, width, height, alpha, null, radius, snapshot);
    }

    private void drawCard(double x, double y, double width, double height, float alpha,
                          io.github.humbleui.skija.Image rasterCover, double radius,
                          CloudMusic.PlaybackSnapshot snapshot) {
        if (alpha <= .01f || snapshot == null || snapshot.music == null || width <= 0.0 || height <= 0.0) return;
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return;
        int save = canvas.save();
        SkijaGfx.pushMatrix();
        try (io.github.humbleui.skija.Path shape = MusicCardShape.outline(x, y, width, height, radius)) {
            canvas.clipPath(shape, true);
            SkijaGfx.translate(x, y);
            SkijaGfx.scale(width / CARD_WIDTH, height / CARD_HEIGHT);
            drawCardContent(alpha, rasterCover, snapshot);
        } finally {
            SkijaGfx.popMatrix();
            canvas.restoreToCount(save);
        }
    }

    void drawCardContent(MusicCardMotion.Frame frame, float alpha, CloudMusic.PlaybackSnapshot snapshot) {
        if (alpha <= .01f || snapshot == null || snapshot.music == null) return;
        SkijaGfx.pushMatrix();
        try {
            SkijaGfx.translate(frame.contentX(), frame.contentY());
            SkijaGfx.scale(frame.contentScale(), frame.contentScale());
            drawCardContent(alpha, null, snapshot);
        } finally {
            SkijaGfx.popMatrix();
        }
    }

    private void drawCardContent(float alpha, io.github.humbleui.skija.Image rasterCover,
                                 CloudMusic.PlaybackSnapshot snapshot) {
        Music music = snapshot.music;
        drawCoverRipple(SkijaGfx.canvas(), alpha);
        drawCover(music, alpha, rasterCover);
        drawHeadline(alpha, snapshot);
        drawSubtitle(music, alpha);
        drawPlatform(music.getSource(), alpha);
        drawProgress(alpha, snapshot);
        boolean like = LikeButton.canFavourite(music);
        drawControl(MusicCardLayout.Target.PREVIOUS, !LiveStreamSession.isActive()
                && !CloudMusic.isPersonalFmActive(), alpha, snapshot);
        drawControl(MusicCardLayout.Target.PLAY_PAUSE, true, alpha, snapshot);
        drawControl(MusicCardLayout.Target.NEXT, !LiveStreamSession.isActive(), alpha, snapshot);
        drawControl(MusicCardLayout.Target.MODE, modeAvailable(snapshot), alpha, snapshot);
        if (like) drawControl(MusicCardLayout.Target.LIKE, true, alpha, snapshot);
        if (System.nanoTime() < hintUntil) drawHint(hint, alpha);
        else if (hovered != MusicCardLayout.Target.NONE && hovered != MusicCardLayout.Target.PLAYER) {
            drawHint(label(hovered), alpha * .9f);
        }
    }

    private void drawSubtitle(Music music, float alpha) {
        CFontRenderer font = FontManager.pf20;
        if (font == null) return;
        String text = cardSubtitle(music);
        double width = CARD_WIDTH - MusicCardLayout.TEXT_X - 17.0;
        double measured = font.getStringWidthD(text);
        double gap = 28.0;
        double elapsed = Math.max(0.0, (System.nanoTime() - subtitleStartedAt) / 1_000_000_000.0 - 2.0);
        double offset = measured > width ? (elapsed * 23.0) % (measured + gap) : 0.0;
        Canvas canvas = SkijaGfx.canvas();
        int save = canvas.save();
        try {
            canvas.clipRect(Rect.makeXYWH((float) MusicCardLayout.TEXT_X, 43f, (float) width, 22f));
            font.drawString(text, MusicCardLayout.TEXT_X - offset, 46.0,
                    RenderSystem.reAlpha(0xA6A7B2, alpha * .92f));
            if (measured > width && offset > measured + gap - width) {
                font.drawString(text, MusicCardLayout.TEXT_X - offset + measured + gap, 46.0,
                        RenderSystem.reAlpha(0xA6A7B2, alpha * .92f));
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void drawHeadline(float alpha, CloudMusic.PlaybackSnapshot snapshot) {
        CFontRenderer font = FontManager.pf28bold;
        if (font == null) return;
        String text = snapshot.preparing ? (snapshot.music == null ? "" : snapshot.music.getName())
                : HudConfig.islandMusicShowsLyric ? rowText(snapshot)
                : snapshot.music == null || snapshot.music.getName() == null ? "" : snapshot.music.getName();
        double max = MusicCardLayout.BADGE_X - MusicCardLayout.TEXT_X - 10.0;
        int save = SkijaGfx.canvas().save();
        try {
            float t = headlineFade;
            if (t < .999f && !previousHeadline.isEmpty()) drawMarquee(font, previousHeadline,
                    MusicCardLayout.TEXT_X, 22.0 - 5.0 * t, max, 17.0, 25.0,
                    alpha * (1f - t), headlineStartedAtNanos);
            drawMarquee(font, text, MusicCardLayout.TEXT_X, 22.0 + 5.0 * (1f - t), max,
                    17.0, 25.0, alpha * t, headlineStartedAtNanos);
        } finally {
            SkijaGfx.canvas().restoreToCount(save);
        }
    }

    private static void drawMarquee(CFontRenderer font, String text, double x, double y, double width,
                                    double clipTop, double clipHeight, float alpha, long startedAtNanos) {
        if (font == null || text == null || text.isEmpty() || alpha <= .01f || width <= 1.0) return;
        double textWidth = font.getStringWidthD(text);
        double elapsed = Math.max(0.0, (System.nanoTime() - startedAtNanos) / 1_000_000.0);
        double offset = marqueeOffset(textWidth, width, elapsed);
        Canvas canvas = SkijaGfx.canvas();
        int save = canvas.save();
        try {
            canvas.clipRect(Rect.makeXYWH((float) x, (float) clipTop, (float) width, (float) clipHeight));
            int color = RenderSystem.reAlpha(0xFFFFFF, alpha);
            if (offset <= .001) {
                font.drawString(text, x, y, color);
            } else {
                double gap = Math.max(24.0, width * .18);
                font.drawString(text, x - offset, y, color);
                if (offset > textWidth + gap - width) {
                    font.drawString(text, x - offset + textWidth + gap, y, color);
                }
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** Drawn under cover/content and inside drawCard's actual outer shape clip. */
    private void drawCoverRipple(Canvas canvas, float alpha) {
        MusicCardEffects.Ripple ripple = effects.ripple;
        if (!ripple.active()) return;
        double pulse = ripple.pulse();
        double expansion = ripple.expansion();
        double radius = COVER_SIZE * (.16 + .92 * expansion);
        double centerX = COVER_X + COVER_SIZE * .5;
        double centerY = COVER_Y + COVER_SIZE * .5;
        int tint = MusicCardColors.mix(accent, 0xFFFFFF, .32);
        for (int ring = 0; ring < 2; ring++) {
            Paint paint = SkijaGfx.fillBlurred(MusicCardSurface.color(tint,
                    alpha * pulse * (ring == 0 ? .21 : .10)), ring == 0 ? 1.15f : 1.65f);
            paint.setMode(io.github.humbleui.skija.PaintMode.STROKE);
            paint.setStrokeWidth(ring == 0 ? .85f : .60f);
            try {
                canvas.drawCircle((float) centerX, (float) centerY,
                        (float) (radius * (1.0 + ring * .31)), paint);
            } finally {
                paint.setShader(null);
                paint.setMaskFilter(null);
            }
        }
        Paint glow = SkijaGfx.fillBlurred(MusicCardSurface.color(tint, alpha * pulse * .18), 3.2f);
        try {
            canvas.drawCircle((float) centerX, (float) centerY,
                    (float) (COVER_SIZE * (.22 + .18 * expansion)), glow);
        } finally {
            glow.setShader(null);
            glow.setMaskFilter(null);
        }
    }

    private void drawCover(Music music, float alpha, io.github.humbleui.skija.Image rasterCover) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return;
        try (io.github.humbleui.skija.Path coverShape = MusicCardShape.outline(
                COVER_X, COVER_Y, COVER_SIZE, COVER_SIZE, 10.5)) {
            int save = canvas.save();
            try {
                canvas.clipPath(coverShape, true);
                drawCoverFallback(canvas, coverShape, COVER_X, COVER_Y, COVER_SIZE, alpha);
                if (rasterCover != null) {
                    drawCoverImage(canvas, rasterCover, alpha, 1.0, 0.0);
                } else {
                    boolean heldTrack = heldTrackKey != null && heldTrackKey.equals(music.getStableKey());
                    Location currentLocation = heldTrack && flipCoverLocation != null
                            ? flipCoverLocation : coverLocationFor(music);
                    ITextureObject currentTexture = coverTextureObject(music, currentLocation);
                    int currentId = currentTexture == null ? 0 : currentTexture.getGlTextureId();
                    boolean oldReady = validTexture(previousCoverTextureId, SkijaImages.sizeOf(previousCoverTextureId));
                    boolean canFlip = !trackFlipPending && coverFlipProgress < 1.0 && oldReady
                            && validTexture(currentId, SkijaImages.sizeOf(currentId)) && !previousTrackKey.isEmpty()
                            && heldSessionId == coverTrackSession && heldTrack;
                    if (trackFlipPending && !previousTrackKey.isEmpty() && oldReady && heldTrack) {
                        drawCoverTexture(canvas, previousCoverTextureId, SkijaImages.sizeOf(previousCoverTextureId),
                                alpha, 1.0, 0.0);
                    } else if (canFlip) {
                        drawFlippingCover(canvas, previousCoverTextureId, currentId, alpha);
                    } else if (currentTexture != null) {
                        drawCoverTexture(canvas, currentId, SkijaImages.sizeOf(currentId), alpha, 1.0, 0.0);
                    }
                }
                canvas.drawPath(coverShape, SkijaGfx.fill(RenderSystem.reAlpha(0x242630, alpha * .14f)));
                MusicCardSurface.fill(canvas, coverShape, COVER_X, COVER_Y, COVER_X, COVER_Y + COVER_SIZE,
                        new int[]{MusicCardSurface.color(0xFFFFFF, alpha * .07), 0x00FFFFFF, 0x18000000},
                        new float[]{0f, .44f, 1f});
                canvas.drawPath(coverShape, SkijaGfx.stroke(MusicCardSurface.color(0xFFFFFF, alpha * .17), .65));
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }

    private void drawCoverFallback(Canvas canvas, io.github.humbleui.skija.Path shape,
                                   double x, double y, double size, float alpha) {
        // Opaque at full card alpha: the background ripple cannot tint missing/transparent cover pixels.
        canvas.drawPath(shape, SkijaGfx.fill(MusicCardSurface.color(MusicCardColors.mix(0x242630, accent, .18), alpha)));
        FoliaLucideIcons.draw(FoliaLucideIcons.NOTE, x + size * .5, y + size * .5,
                size * .52, RenderSystem.reAlpha(0xFFFFFF, alpha * .75f));
    }

    private void drawFlippingCover(Canvas canvas, int oldTexture, int newTexture, float alpha) {
        double p = Math.max(0.0, Math.min(1.0, coverFlipProgress));
        double eased = TransitionCurveCache.MUSIC_CARD.at(p);
        double angle = eased * 180.0 * coverFlipDirection;
        boolean incoming = eased >= .5;
        int texture = incoming ? newTexture : oldTexture;
        int[] textureSize = SkijaImages.sizeOf(texture);
        double facing = Math.abs(Math.cos(Math.toRadians(angle)));
        int save = canvas.save();
        try {
            canvas.translate((float) (COVER_X + COVER_SIZE * .5), (float) (COVER_Y + COVER_SIZE * .5));
            // rotateY 已经投影出余弦宽度，不能再 scale(facing) 变成余弦的平方。
            double localAngle = incoming ? angle - Math.copySign(180.0, angle) : angle;
            SkijaGfx.rotateY((float) localAngle);
            canvas.translate((float) (-COVER_X - COVER_SIZE * .5), (float) (-COVER_Y - COVER_SIZE * .5));
            drawCoverTexture(canvas, texture, textureSize, alpha, 1.0,
                    incoming ? .16 * (1.0 - facing) : 0.0);
            double rim = Math.sin(Math.PI * p) * .62;
            if (rim > .01) {
                canvas.drawRect(Rect.makeXYWH((float) (COVER_X + COVER_SIZE * .5 - .55), (float) COVER_Y + 2f,
                        1.1f, (float) (COVER_SIZE - 4.0)),
                        SkijaGfx.fill(MusicCardSurface.color(0xFFFFFF, alpha * rim)));
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void drawCoverImage(Canvas canvas, io.github.humbleui.skija.Image image,
                                       float alpha, double scaleX, double veil) {
        int save = canvas.save();
        try {
            canvas.translate((float) (COVER_X + COVER_SIZE * .5), (float) (COVER_Y + COVER_SIZE * .5));
            canvas.scale((float) scaleX, 1f);
            canvas.translate((float) (-COVER_SIZE * .5), (float) (-COVER_SIZE * .5));
            drawSquareImage(canvas, image, 0.0, 0.0, COVER_SIZE, alpha);
            if (veil > 0.0) canvas.drawRect(Rect.makeXYWH(0f, 0f, (float) COVER_SIZE, (float) COVER_SIZE),
                    SkijaGfx.fill(MusicCardSurface.color(0x000000, alpha * veil)));
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void drawSquareImage(Canvas canvas, io.github.humbleui.skija.Image image,
                                        double x, double y, double size, float alpha) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0 || size <= 0.0) return;
        double edge = Math.min(image.getWidth(), image.getHeight());
        Rect source = Rect.makeXYWH((float) ((image.getWidth() - edge) * .5),
                (float) ((image.getHeight() - edge) * .5), (float) edge, (float) edge);
        Paint paint = SkijaGfx.fill(SkijaGfx.argb(1f, 1f, 1f, alpha));
        try {
            canvas.drawImageRect(image, source, Rect.makeXYWH((float) x, (float) y, (float) size,
                    (float) size), io.github.humbleui.skija.SamplingMode.LINEAR, paint, true);
        } finally {
            paint.setShader(null);
            paint.setMaskFilter(null);
        }
    }

    private static void drawCoverTexture(Canvas canvas, int textureId, int[] textureSize,
                                         float alpha, double scaleX, double veil) {
        int save = canvas.save();
        try {
            canvas.translate((float) (COVER_X + COVER_SIZE * .5), (float) (COVER_Y + COVER_SIZE * .5));
            canvas.scale((float) scaleX, 1f);
            canvas.translate((float) (-COVER_SIZE * .5), (float) (-COVER_SIZE * .5));
            drawSquareTexture(textureId, textureSize, 0.0, 0.0, COVER_SIZE, alpha);
            if (veil > 0.0) canvas.drawRect(Rect.makeXYWH(0f, 0f, (float) COVER_SIZE, (float) COVER_SIZE),
                    SkijaGfx.fill(MusicCardSurface.color(0x000000, alpha * veil)));
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void drawSquareTexture(int textureId, int[] textureSize,
                                          double x, double y, double size, float alpha) {
        if (!validTexture(textureId, textureSize) || size <= 0.0) return;
        double edge = Math.min(textureSize[0], textureSize[1]);
        double u = (textureSize[0] - edge) * .5 / textureSize[0];
        double v = (textureSize[1] - edge) * .5 / textureSize[1];
        try {
            SkijaGfx.setBoundTexture(textureId);
            RenderSystem.linearFilter();
            SkijaImages.drawTextured(x, y, size, size, u, v, 1.0 - u, 1.0 - v,
                    textureId, textureSize[0], textureSize[1], SkijaGfx.argb(1f, 1f, 1f, alpha), true);
        } finally {
            SkijaGfx.setBoundTexture(0);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
        }
    }

    private static boolean validTexture(int textureId, int[] size) {
        return textureId > 0 && size != null && size.length >= 2 && size[0] > 0 && size[1] > 0;
    }

    private static Location coverLocationFor(Music music) {
        return music == null ? null : CloudMusic.preferredCoverLocation(music, music.getSmallCoverLocation());
    }

    private static ITextureObject coverTextureObject(Music music, Location location) {
        if (music == null) return null;
        TextureManager manager = TextureManager.getInstance();
        ITextureObject texture = readyCoverTexture(manager, location);
        if (texture == null) texture = readyCoverTexture(manager, music.getSmallCoverLocation());
        if (texture == null) texture = readyCoverTexture(manager, music.getCoverLocation());
        return texture;
    }

    private static ITextureObject readyCoverTexture(TextureManager manager, Location location) {
        ITextureObject texture = location == null ? null : manager.getTexture(location);
        return texture != null && validTexture(texture.getGlTextureId(), SkijaImages.sizeOf(texture.getGlTextureId()))
                ? texture : null;
    }

    private static boolean sameLocation(Location left, Location right) {
        return left == null ? right == null : left.equals(right);
    }

    private void drawPlatform(MusicPlatform source, float alpha) {
        double x = MusicCardLayout.BADGE_X;
        double size = MusicCardLayout.BADGE_SIZE;
        int brand = source.getBrandColor();
        boolean dark = source == MusicPlatform.QISHUI || source == MusicPlatform.SPOTIFY;
        roundedRect(x, COVER_Y, size, size, 7.0, RenderSystem.reAlpha(dark ? 0x050607 : brand, alpha * .97f));
        int ink = RenderSystem.reAlpha(dark ? brand : 0xFFFFFF, alpha);
        if (BrandVectorIcons.drawIn(source, x + 4.0, COVER_Y + 4.0, size - 8.0, size - 8.0, ink)) return;
        CFontRenderer font = FontManager.pf18bold;
        String glyph;
        switch (source) {
            case NETEASE: font = FontManager.musicBrand18; glyph = MusicBrandIcons.NETEASE_CLOUD_MUSIC; break;
            case QQ: font = FontManager.qqMusicIcon20; glyph = MusicBrandIcons.QQ_MUSIC; break;
            case YOUTUBE: font = FontManager.youtubeIcon24; glyph = MusicBrandIcons.YOUTUBE; break;
            case KUGOU: glyph = MusicBrandIcons.KUGOU_MONOGRAM; break;
            case BILIBILI: glyph = MusicBrandIcons.BILIBILI_MONOGRAM; break;
            case QISHUI: glyph = MusicBrandIcons.QISHUI_MONOGRAM; break;
            case SPOTIFY: glyph = MusicBrandIcons.SPOTIFY_MONOGRAM; break;
            case APPLE: glyph = MusicBrandIcons.APPLE_MONOGRAM; break;
            case LOCAL: glyph = "♪"; break;
            case SMTC: glyph = "♫"; break;
            case KUWO: glyph = "酷"; break;
            case MIGU: glyph = "咪"; break;
            default: glyph = "GD";
        }
        drawGlyph(font, glyph, x + size * .5, COVER_Y + size * .5, 16.0, ink);
    }

    private void drawProgress(float alpha, CloudMusic.PlaybackSnapshot snapshot) {
        if (snapshot.preparing) {
            if (FontManager.pf12 != null) FontManager.pf12.drawString("正在准备播放", PROGRESS_X,
                    PROGRESS_Y - FontManager.pf12.getFontHeight() * .5, RenderSystem.reAlpha(0xAFB0BB, alpha));
            return;
        }
        double fraction = DownloadDynamicIsland.musicCardProgress(progressFraction(snapshot));
        MusicCardProgress.draw(SkijaGfx.canvas(), PROGRESS_X, PROGRESS_Y,
                MusicCardLayout.PROGRESS_WIDTH, fraction, accent, alpha);
        CFontRenderer font = FontManager.pf16bold;
        if (font == null) return;
        long shownPosition = Math.round(fraction * snapshot.durationMs);
        if (snapshot.durationMs <= 0L) shownPosition = snapshot.positionMs;
        String current = formatTime(shownPosition);
        String total = snapshot.durationMs <= 0L ? "LIVE" : formatTime(snapshot.durationMs);
        double timeScale = Math.min(1.0, 36.0 / Math.max(1.0, Math.max(font.getStringWidthD(current), font.getStringWidthD(total))));
        double timeY = PROGRESS_Y - font.getFontHeight() * timeScale * .5;
        font.drawString(current, COVER_X, timeY, timeScale, RenderSystem.reAlpha(0xAFB0BB, alpha));
        font.drawString(total, CARD_WIDTH - COVER_X - font.getStringWidthD(total) * timeScale, timeY,
                timeScale, RenderSystem.reAlpha(0xAFB0BB, alpha));
    }

    private void drawControl(MusicCardLayout.Target target, boolean enabled, float alpha,
                             CloudMusic.PlaybackSnapshot snapshot) {
        enabled &= !snapshot.preparing;
        int index = target.ordinal();
        double centerX = MusicCardLayout.controlX(target);
        double hover = hoverAmounts[index];
        double press = pressAmounts[index];
        MusicCardEffects.Tap tap = target == MusicCardLayout.Target.PREVIOUS ? effects.previous
                : target == MusicCardLayout.Target.NEXT ? effects.next
                : target == MusicCardLayout.Target.PLAY_PAUSE ? effects.playPause : null;
        double feedback = tap == null ? 0.0 : Math.max(0.0, tap.amount());
        double radius = target == MusicCardLayout.Target.PLAY_PAUSE ? MusicCardLayout.PLAY_DISC_RADIUS : 18.0;
        float opacity = alpha * (enabled ? 1f : .30f);
        if (target == MusicCardLayout.Target.PLAY_PAUSE) {
            drawTransportDisc(centerX, CONTROLS_Y, radius, opacity, hover, Math.max(press, feedback));
        } else if (hover > .01 || press > .01 || feedback > .01) {
            roundedRectGradientVertical(centerX - radius, CONTROLS_Y - radius, radius * 2.0, radius * 2.0, radius,
                    new Color(MusicCardSurface.color(0xFFFFFF, opacity * (.10 * hover + .15 * Math.max(press, feedback))), true),
                    new Color(MusicCardSurface.color(accent, opacity * (.045 * hover + .065 * Math.max(press, feedback))), true));
        }
        SkijaGfx.pushMatrix();
        try {
            double scale = (1.0 + .045 * hover - .12 * press) * (tap == null ? 1.0 : tap.scale());
            SkijaGfx.translate(centerX + (tap == null ? 0.0 : tap.offsetX()), CONTROLS_Y);
            SkijaGfx.scale(scale, scale);
            SkijaGfx.translate(-centerX, -CONTROLS_Y);
            if (target == MusicCardLayout.Target.LIKE) {
                drawLike(centerX, CONTROLS_Y, opacity, snapshot.music);
            } else if (target == MusicCardLayout.Target.MODE) {
                drawMode(centerX, CONTROLS_Y, opacity, displayedMode(snapshot));
            } else {
                MusicCardEffects.Icon icon = target == MusicCardLayout.Target.PREVIOUS ? MusicCardEffects.Icon.PREVIOUS
                        : target == MusicCardLayout.Target.NEXT ? MusicCardEffects.Icon.NEXT : MusicCardEffects.Icon.PLAY_PAUSE;
                drawTransportIcon(SkijaGfx.canvas(), icon, centerX, CONTROLS_Y,
                        target == MusicCardLayout.Target.PLAY_PAUSE ? MusicCardLayout.PLAY_GLYPH_SIZE
                                : MusicCardLayout.SKIP_GLYPH_SIZE,
                        snapshot.preparing ? 0.0 : effects.playback.pauseAmount(snapshot.playing),
                        RenderSystem.reAlpha(0xF7F7FA, opacity));
            }
        } finally {
            SkijaGfx.popMatrix();
        }
    }

    /** The same bounded vector path is used by production controls and the focused raster probe. */
    static void drawTransportIcon(Canvas canvas, MusicCardEffects.Icon icon,
                                  double centerX, double centerY, double size, double pauseAmount, int color) {
        if (canvas == null || size <= 0.0) return;
        double[] vertices = MusicCardEffects.vertices(icon, pauseAmount);
        centerX += MusicCardEffects.opticalOffsetX(icon, pauseAmount) * size;
        try (PathBuilder builder = new PathBuilder()) {
            for (int contour = 0; contour < 2; contour++) {
                int first = contour * 8;
                builder.moveTo((float) (centerX + vertices[first] * size),
                        (float) (centerY + vertices[first + 1] * size));
                for (int point = first + 2; point < first + 8; point += 2) {
                    builder.lineTo((float) (centerX + vertices[point] * size),
                            (float) (centerY + vertices[point + 1] * size));
                }
                builder.closePath();
            }
            try (io.github.humbleui.skija.Path path = builder.snapshot()) {
                Paint paint = SkijaGfx.fill(color);
                try {
                    canvas.drawPath(path, paint);
                } finally {
                    paint.setShader(null);
                    paint.setMaskFilter(null);
                }
            }
        }
    }

    private void drawTransportDisc(double centerX, double centerY, double radius, float alpha,
                                   double hover, double press) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return;
        canvas.drawCircle((float) centerX, (float) (centerY + 1.3), (float) radius,
                SkijaGfx.fillBlurred(MusicCardSurface.color(0x000000, alpha * .15), 1.6f));
        int save = canvas.save();
        try {
            canvas.clipRRect(RRect.makeXYWH((float) (centerX - radius), (float) (centerY - radius),
                    (float) (radius * 2), (float) (radius * 2), (float) radius), true);
            roundedRectGradientVertical(centerX - radius, centerY - radius, radius * 2, radius * 2, radius,
                    new Color(MusicCardSurface.color(0xDEE6F2, alpha * (.20 + hover * .05 + press * .08)), true),
                    new Color(MusicCardSurface.color(0xAFBDCB, alpha * (.105 + hover * .04 + press * .06)), true));
            MusicCardSurface.lobe(canvas, centerX - radius * .15, centerY - radius * .93,
                    radius * 1.2, radius * 1.2, 0xFFFFFF, alpha * .115f);
            MusicCardSurface.lobe(canvas, centerX + radius * .33, centerY + radius * .83,
                    radius * 1.4, radius * .72, accent, alpha * .075f);
            try (Shader bevel = Shader.makeLinearGradient((float) centerX, (float) (centerY - radius),
                    (float) centerX, (float) (centerY + radius),
                    new int[]{MusicCardSurface.color(0xF7FBFF, alpha * .30), MusicCardSurface.color(0xE1E8F1, alpha * .055),
                            MusicCardSurface.color(0xFFFFFF, alpha * .10)}, new float[]{0f, .45f, 1f})) {
                Paint paint = SkijaGfx.strokeShader(bevel, .72);
                canvas.drawCircle((float) centerX, (float) centerY, (float) (radius - .35), paint);
                paint.setShader(null);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void drawLike(double centerX, double centerY, float alpha, Music music) {
        if (music == null) return;
        boolean liked = LikeButton.isLiked(music);
        if (!music.getStableKey().equals(likeTrack)) {
            likeTrack = music.getStableKey();
            likePlayback = null;
        }
        if (liked) {
            if (likePlayback == null) likePlayback = AnimatedIcons.likeSolid();
            SmilSvg art = AnimatedIcons.like();
            double size = MusicCardLayout.HEART_TEXTURE_SIZE * 1.15;
            if (art != null && likePlayback != null && art.drawIn(centerX - size * .5, centerY - size * .5,
                    size, size, likePlayback.time(), alpha, 0)) return;
        } else likePlayback = null;
        ITextureObject outline = TextureManager.getInstance().getTexture(PlayerIconAssets.FAVORITE);
        if (!liked && outline != null) {
            double size = MusicCardLayout.HEART_TEXTURE_SIZE;
            drawTexture(outline, centerX - size * .5, centerY - size * .5, size, 0.0, alpha);
        } else {
            if (outline == null) PlayerIconAssets.requestLoad(PlayerIconAssets.FAVORITE);
            drawHeart(centerX, centerY, liked, alpha);
        }
    }

    private static void drawHeart(double x, double y, boolean liked, float alpha) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return;
        int save = canvas.save();
        try (io.github.humbleui.skija.PathBuilder builder = new io.github.humbleui.skija.PathBuilder()) {
            double scale = MusicCardLayout.HEART_GLYPH_SIZE / 20.0;
            canvas.translate((float) x, (float) y);
            canvas.scale((float) scale, (float) scale);
            canvas.translate(-12f, -11.5f);
            builder.moveTo(12f, 21f).cubicTo(9f, 18.2f, 2f, 13f, 2f, 7.7f)
                    .cubicTo(2f, 1.9f, 9.4f, .4f, 12f, 5.6f)
                    .cubicTo(14.6f, .4f, 22f, 1.9f, 22f, 7.7f)
                    .cubicTo(22f, 13f, 15f, 18.2f, 12f, 21f).closePath();
            try (io.github.humbleui.skija.Path path = builder.snapshot()) {
                canvas.drawPath(path, liked ? SkijaGfx.fill(RenderSystem.reAlpha(0xFF526F, alpha))
                        : SkijaGfx.stroke(RenderSystem.reAlpha(0xFFFFFF, alpha), 1.5));
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    static CloudMusic.PlayMode displayedMode() {
        return displayedMode(frameSnapshot);
    }

    private static CloudMusic.PlayMode displayedMode(CloudMusic.PlaybackSnapshot snapshot) {
        if (MpPartyClient.room() != null && MpPartyClient.currentTrack() != null) {
            String repeat = MpPartyClient.room().repeat;
            if ("one".equals(repeat)) return CloudMusic.PlayMode.LoopSingle;
            if ("all".equals(repeat)) return CloudMusic.PlayMode.LoopInList;
            return CloudMusic.PlayMode.Sequential;
        }
        return CloudMusic.playMode == null ? CloudMusic.PlayMode.Sequential : CloudMusic.playMode;
    }

    private void drawMode(double x, double y, float alpha, CloudMusic.PlayMode mode) {
        if (mode == null) return;
        int color = RenderSystem.reAlpha(mode == CloudMusic.PlayMode.Sequential ? 0xEEEEF3 : accent, alpha);
        drawGlyph(FontManager.icon30, mode.getIcon(), x, y, MusicCardLayout.MODE_GLYPH_SIZE, color);
    }

    private static boolean modeAvailable(CloudMusic.PlaybackSnapshot snapshot) {
        boolean room = MpPartyClient.room() != null && MpPartyClient.currentTrack() != null;
        return room || (!CloudMusic.isPersonalFmActive() && !LiveStreamSession.isActive()
                && snapshot.music != null && !ExternalPlayback.isMirroring() && !SpotifyConnectMirror.isMirroring());
    }

    private static boolean modeAvailable() {
        return modeAvailable(frameSnapshot);
    }

    private void drawHint(String text, float alpha) {
        if (text == null || text.isEmpty() || FontManager.pf12 == null) return;
        CFontRenderer font = FontManager.pf12;
        String shown = font.trim(text, 210.0);
        double width = font.getStringWidthD(shown);
        font.drawString(shown, (CARD_WIDTH - width) * .5, CARD_HEIGHT - font.getFontHeight() - 2.0,
                RenderSystem.reAlpha(0xC4C6D1, alpha * .85f));
    }

    private String label(MusicCardLayout.Target target) {
        switch (target) {
            case LIKE: return LikeButton.isLiked(frameSnapshot.music) ? "取消喜欢" : "喜欢这首歌";
            case PREVIOUS: return "上一曲";
            case NEXT: return "下一曲";
            case PLAY_PAUSE: return frameSnapshot.playing ? "暂停" : "继续播放";
            case MODE: return modeAvailable() ? displayedMode().getDisplayName() : "当前播放不支持更改顺序";
            case SEEK: return "拖动后松开，跳转播放位置";
            default: return "打开播放器";
        }
    }

    private static void drawGlyph(CFontRenderer font, String glyph, double x, double y, double size, int color) {
        if (font == null || glyph == null || glyph.isEmpty()) return;
        GlyphInkMetrics ink = glyph.length() == 1 ? GlyphInkMetrics.of(font, glyph.charAt(0)) : null;
        if (ink != null && ink.width > 0.0 && ink.height > 0.0) {
            double scale = size / Math.max(ink.width, ink.height);
            SkijaGfx.pushMatrix();
            try {
                SkijaGfx.translate(x, y);
                SkijaGfx.scale(scale, scale);
                font.drawString(glyph, ink.alignCenterX(0), ink.alignCenterY(0), color);
            } finally {
                SkijaGfx.popMatrix();
            }
            return;
        }
        double width = font.getStringWidthD(glyph);
        double height = Math.max(1.0, font.getFontHeight());
        double scale = size / Math.max(1.0, Math.max(width, height));
        font.drawString(glyph, x - width * scale * .5, y - height * scale * .5, scale, color);
    }

    private void drawTexture(ITextureObject texture, double x, double y, double size, double radius, float alpha) {
        SkijaGfx.setColor(1f, 1f, 1f, 1f);
        try {
            SkijaGfx.setBoundTexture(texture.getGlTextureId());
            texture.linearFilter();
            roundedRectTextured(x, y, size, size, radius, alpha);
        } finally {
            SkijaGfx.setBoundTexture(0);
            SkijaGfx.setColor(1f, 1f, 1f, 1f);
        }
    }

    public MusicCardLayout.Target targetAt(double x, double y, CloudMusic.PlaybackSnapshot snapshot) {
        return MusicCardLayout.hit(x, y, LikeButton.canFavourite(snapshot.music), snapshot.durationMs > 0L);
    }

    public void setPointer(double x, double y) {
        hovered = confirmation == null ? targetAt(x, y, frameSnapshot) : MusicCardLayout.Target.NONE;
    }

    public void clearPointer() {
        hovered = MusicCardLayout.Target.NONE;
        pressed = MusicCardLayout.Target.NONE;
    }

    public void pressPointer(MusicCardLayout.Target target) {
        pressed = target;
    }

    public void releasePointer() {
        pressed = MusicCardLayout.Target.NONE;
    }

    /** 控件优先；封面、文字、来源徽标和卡内其余区域都打开播放器。 */
    public boolean click(double x, double y) {
        return click(x, y, snapshot());
    }

    boolean click(double x, double y, CloudMusic.PlaybackSnapshot snapshot) {
        if (snapshot.music == null || frameSnapshot.preparing || !matchesFrame(snapshot)
                || !snapshotIdentityMatches(snapshot, snapshot())) return false;
        MusicCardLayout.Target target = targetAt(x, y, snapshot);
        switch (target) {
            case NONE: return false;
            case PREVIOUS:
            case NEXT:
                if (LiveStreamSession.isActive()) flashHint("直播没有上一曲 / 下一曲");
                else if (target == MusicCardLayout.Target.PREVIOUS && CloudMusic.isPersonalFmActive()
                        && MpPartyClient.room() == null && !ExternalPlayback.isMirroring()
                        && !SpotifyConnectMirror.isMirroring()) flashHint("私人 FM 没有上一曲");
                else if (target == MusicCardLayout.Target.PREVIOUS) {
                    effects.previous.trigger();
                    CloudMusic.prev();
                } else {
                    effects.next.trigger();
                    CloudMusic.next();
                }
                return true;
            case PLAY_PAUSE:
                CloudMusic.PlayPauseResult result = CloudMusic.togglePlayPause(snapshot);
                if (result != CloudMusic.PlayPauseResult.UNAVAILABLE) {
                    effects.playPause.trigger();
                    effects.playback.accept(true, result == CloudMusic.PlayPauseResult.RESUMED);
                } else flashHint("当前播放不可暂停或继续");
                return true;
            case MODE:
                if (modeAvailable()) {
                    CloudMusic.cyclePlayMode();
                    flashHint(displayedMode().getDisplayName());
                } else flashHint("当前播放不支持更改顺序");
                return true;
            case LIKE:
                long now = System.nanoTime();
                if (now - lastLikePressNanos < 600_000_000L) return true;
                lastLikePressNanos = now;
                return LikeButton.toggleLiked(snapshot.music, () -> {
                    likeTrack = snapshot.music.getStableKey();
                    likePlayback = AnimatedIcons.likeBurst();
                }, (title, message, confirmText, onConfirm) -> {
                    confirmationSession = snapshot.sessionId;
                    confirmationTrack = snapshot.music.getStableKey();
                    confirmation = new ConfirmationOverlay(title, message, confirmText, () -> {
                        CloudMusic.PlaybackSnapshot current = snapshot();
                        if (current.sessionId == confirmationSession && current.music != null
                                && current.music.getStableKey().equals(confirmationTrack)) onConfirm.run();
                    });
                    confirmation.onInit();
                });
            case SEEK:
                seekToFraction(MusicCardLayout.seekFraction(x), snapshot);
                return true;
            default:
                openPlayer();
                return true;
        }
    }

    private void flashHint(String text) {
        hint = text;
        hintUntil = System.nanoTime() + 1_800_000_000L;
    }

    /** 拖动只预览，松手提交一次，沿 CloudMusic 的房间/镜像拦截路径。 */
    public void seekToFraction(double fraction) {
        seekToFraction(fraction, snapshot());
    }

    void seekToFraction(double fraction, CloudMusic.PlaybackSnapshot snapshot) {
        if (frameSnapshot.preparing || snapshot.durationMs <= 0L || !Double.isFinite(fraction)
                || !matchesFrame(snapshot) || !snapshotIdentityMatches(snapshot, snapshot())) return;
        CloudMusic.seekToMillis((float) (DynamicIslandMath.clamp01(fraction) * snapshot.durationMs), snapshot);
    }

    public static void openPlayer() {
        PlayerIslandTransition.openFromIslandCard();
    }

    public void drawConfirmation(double mouseX, double mouseY) {
        if (confirmation == null) return;
        confirmation.setBounds(0.0, 0.0, RenderSystem.getLayoutWidth(), RenderSystem.getLayoutHeight());
        confirmation.setAlpha(1f);
        confirmation.renderWidget(mouseX, mouseY, 0);
        if (confirmation.shouldClose()) confirmation = null;
    }

    public boolean clickConfirmation(double x, double y) {
        if (confirmation == null) return false;
        confirmation.onMouseClickReceived(x, y, 0);
        return true;
    }

    public void cancelConfirmation() {
        if (confirmation != null) confirmation.cancel();
        confirmation = null;
    }

    private static int clamp255(float value) {
        return (int) Math.max(0f, Math.min(255f, value));
    }

    public static String formatTime(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        return seconds / 60L + ":" + (seconds % 60L < 10 ? "0" : "") + seconds % 60L;
    }
}
