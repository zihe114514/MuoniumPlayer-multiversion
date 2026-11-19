package com.muoniumplayer.core.rendering;

/** 聊天音乐卡与短暂切歌提示共用的时间驱动状态。 */
final class MusicCardInteraction {
    private static final double OPEN_DURATION_MILLIS = 760.0;
    private static final double CLOSE_DURATION_MILLIS = 680.0;
    private static final double CLOSE_CONTENT_MILLIS = 180.0;
    static final double TRACK_NOTICE_HOLD_MILLIS = 3_000.0;

    private boolean chatSession;
    private boolean dismissed;
    private boolean clickedOpen;
    private boolean available;
    private boolean blocked;
    private boolean target;
    private boolean transitionTarget;
    private double transitionFrom;
    private double transitionTo;
    private double transitionElapsed = CLOSE_DURATION_MILLIS;
    private double openness;
    private double transitionVelocity;
    private double transitionInitialVelocity;
    private double closingContentFrom;
    private double closingContentLimit = 1.0;
    private boolean trackReady = true;
    private final IslandVisibleClock eventVisibleClock = new IslandVisibleClock();
    private long eventRevision;
    private double eventHoldMillis = TRACK_NOTICE_HOLD_MILLIS;
    private boolean eventLocked;
    private boolean chatCardDismissedByUser;
    private boolean animationActive;
    private boolean trackNoticeActive;
    private boolean suppressChatAutoOpen;
    private boolean capturedPress;
    private MusicCardLayout.Target pressedTarget = MusicCardLayout.Target.NONE;
    private long pressedSession = Long.MIN_VALUE;
    private String pressedTrack;
    private long seekSession = Long.MIN_VALUE;
    private String seekTrack;
    private double seekFraction;

    void update(boolean enabled, boolean chatOpen, boolean hasMusic, boolean surfaceBlocked,
                int trigger, double deltaMillis) {
        update(enabled, chatOpen, hasMusic, surfaceBlocked, trigger, deltaMillis, true);
    }

    void update(boolean enabled, boolean chatOpen, boolean hasMusic, boolean surfaceBlocked,
                int trigger, double deltaMillis, boolean trackCoverReady) {
        double dt = Double.isFinite(deltaMillis) ? Math.max(0.0, Math.min(40.0, deltaMillis)) : 0.0;
        if (chatOpen && !chatSession) {
            dismissed = false;
            clickedOpen = false;
            suppressChatAutoOpen = false;
            chatCardDismissedByUser = false;
        }
        if (!chatOpen || !enabled || !hasMusic) {
            if (!chatOpen && !eventLocked) {
                clickedOpen = false;
                suppressChatAutoOpen = false;
            }
            if (!enabled || !hasMusic) {
                clickedOpen = false;
                eventLocked = false;
                trackNoticeActive = false;
            }
            if (!chatOpen && !eventLocked || !hasMusic) cancelSeek();
        }

        chatSession = chatOpen;
        blocked = surfaceBlocked;
        trackReady = trackCoverReady;
        available = enabled && (hasMusic || animationActive || openness > .002)
                && (chatOpen || eventLocked || animationActive);

        if (eventLocked) trackNoticeActive = true;
        if (eventLocked && !chatOpen && chatCardDismissedByUser) dismissEvent();
        if (blocked) {
            eventVisibleClock.pause();
            cancelSeek();
            return;
        }
        if (eventLocked && trackReady && eventVisibleClock.elapsed() >= eventHoldMillis) {
            eventLocked = false;
            // 已经打开的聊天卡不应在每次切歌提示结束后被自动关掉。
            suppressChatAutoOpen = !chatOpen;
            if (chatOpen) trackNoticeActive = false;
        }

        target = eventLocked || enabled && chatOpen && hasMusic && !blocked && !dismissed
                && !suppressChatAutoOpen && (trigger == 0 || clickedOpen);
        if (!target) cancelSeek();
        if (target != transitionTarget) retarget(target);
        if (dt <= .001) return;

        double duration = transitionTarget ? OPEN_DURATION_MILLIS : CLOSE_DURATION_MILLIS;
        transitionElapsed = Math.min(duration, transitionElapsed + dt);
        double progress = transitionElapsed / duration;
        double value = transitionAt(progress, duration);
        openness = DynamicIslandMath.clamp01(value);
        transitionVelocity = value < 0.0 || value > 1.0 ? 0.0 : transitionVelocityAt(progress, duration);
        if (progress >= 1.0) {
            openness = transitionTo;
            transitionVelocity = 0.0;
        }
        animationActive = transitionElapsed < duration || openness > .002;
        if (!transitionTarget && !animationActive) trackNoticeActive = false;
    }

    private double transitionAt(double progress, double duration) {
        double p = DynamicIslandMath.clamp01(progress);
        double base = DynamicIslandMath.lerp(transitionFrom, transitionTo, transitionEase(p, transitionTarget));
        double inverse = 1.0 - p;
        double brake = Math.exp(-p * duration / 48.0);
        return base + transitionInitialVelocity * duration * p * inverse * inverse * inverse * brake;
    }

    private double transitionVelocityAt(double progress, double duration) {
        double p = DynamicIslandMath.clamp01(progress);
        double inverse = 1.0 - p;
        double brake = Math.exp(-p * duration / 48.0);
        double base = (transitionTo - transitionFrom) * transitionSlope(p, transitionTarget) / duration;
        double inertia = transitionInitialVelocity * brake * inverse * inverse
                * (inverse - 3.0 * p - duration / 48.0 * p * inverse);
        return base + inertia;
    }

    private static double transitionEase(double progress, boolean opening) {
        double t = DynamicIslandMath.clamp01(progress);
        if (opening) return t * (.70 + t * t * (5.80 + t * (-9.40 + 3.90 * t)));
        return t * t * (6.0 + t * (-8.0 + 3.0 * t));
    }

    private static double transitionSlope(double progress, boolean opening) {
        double t = DynamicIslandMath.clamp01(progress);
        if (opening) return .70 + t * t * (17.40 + t * (-37.60 + 19.50 * t));
        return 12.0 * t * (1.0 - t) * (1.0 - t);
    }

    private static double smooth(double progress) {
        double t = DynamicIslandMath.clamp01(progress);
        return t * t * t * (t * (6.0 * t - 15.0) + 10.0);
    }

    private void retarget(boolean opening) {
        double content = contentReveal();
        transitionTarget = opening;
        transitionFrom = openness;
        transitionTo = opening ? 1.0 : 0.0;
        transitionElapsed = 0.0;
        closingContentFrom = opening ? 0.0 : content;
        closingContentLimit = opening ? content : 1.0;
        double direction = transitionTo - transitionFrom;
        // 反向继承当前速度；静止展开使用轻起步，不用前向采样重复求值。
        double curveVelocity = direction * transitionSlope(0.0, opening)
                / (opening ? OPEN_DURATION_MILLIS : CLOSE_DURATION_MILLIS);
        boolean fromRest = Math.abs(transitionVelocity) <= 1e-8
                && (opening && openness <= .002 || !opening && openness >= .998);
        transitionInitialVelocity = fromRest ? 0.0
                : DynamicIslandMath.clamp(transitionVelocity, -.006, .006) - curveVelocity;
        animationActive = true;
    }

    void startTrackNotice(double holdMillis) {
        eventRevision++;
        eventLocked = true;
        trackNoticeActive = true;
        eventVisibleClock.reset();
        eventHoldMillis = Double.isFinite(holdMillis)
                ? Math.max(0.0, Math.min(10_000.0, holdMillis)) : TRACK_NOTICE_HOLD_MILLIS;
        animationActive = true;
        available = true;
        blocked = false;
        dismissed = false;
        chatCardDismissedByUser = false;
        clickedOpen = true;
        target = true;
        if (!transitionTarget) retarget(true);
        cancelSeek();
    }

    void pauseTrackNotice() {
        blocked = true;
        eventVisibleClock.pause();
        cancelSeek();
    }

    void resumeTrackNotice() {
        blocked = false;
    }

    boolean claimsSurface(boolean enabled, boolean chatOpen, boolean hasMusic, int trigger) {
        if (!enabled || !hasMusic) return false;
        return eventLocked || trackNoticeActive || animationActive() || wantsOpen()
                || chatOpen && !dismissed && !suppressChatAutoOpen && (trigger == 0 || clickedOpen);
    }

    void onTrackChanged(boolean chatOpen) {
        cancelSeek();
        if (eventLocked) {
            eventRevision++;
            eventVisibleClock.reset();
        }
        if (!chatOpen && trackNoticeActive()) startTrackNotice(eventHoldMillis);
    }

    long eventRevision() { return eventRevision; }

    void recordPresentation(long revision, boolean presented, long now) {
        if (revision != eventRevision) return;
        if (!eventLocked || !presented || blocked || !trackReady || openness < .995) eventVisibleClock.pause();
        else eventVisibleClock.record(now);
    }

    void pausePresentation() { eventVisibleClock.pause(); }

    long visibleHoldMillis() { return eventVisibleClock.elapsed(); }

    boolean eventLocked() {
        return eventLocked;
    }

    boolean trackNoticeActive() {
        return trackNoticeActive || eventLocked;
    }

    boolean suppressChatAutoOpen() {
        return suppressChatAutoOpen;
    }

    boolean animationActive() {
        return animationActive || openness > .002;
    }

    boolean available() {
        return available && !blocked;
    }

    boolean canInteract() {
        return available() && !eventLocked && target && openness >= .995;
    }

    boolean inputEnabled() {
        return available() && !eventLocked;
    }

    boolean wantsOpen() {
        return target;
    }

    boolean userExpanded() {
        return clickedOpen && target && !eventLocked;
    }

    boolean hasTrack() {
        return available || eventLocked;
    }

    boolean dismissEvent() {
        if (!eventLocked) return false;
        eventLocked = false;
        trackNoticeActive = false;
        eventVisibleClock.reset();
        suppressChatAutoOpen = !chatSession;
        chatCardDismissedByUser = chatSession;
        if (!chatSession) {
            target = false;
            clickedOpen = false;
        }
        return true;
    }

    double openness() {
        return openness;
    }

    double transitionVelocity() {
        return transitionVelocity;
    }

    double contentReveal() {
        double geometric = MusicCardMotion.contentReveal(openness);
        if (!transitionTarget) {
            return Math.min(geometric, closingContentFrom
                    * (1.0 - smooth(transitionElapsed / CLOSE_CONTENT_MILLIS)));
        }
        return Math.min(geometric, DynamicIslandMath.lerp(closingContentLimit, 1.0,
                smooth(transitionElapsed / CLOSE_CONTENT_MILLIS)));
    }

    void expand() {
        if (!available() || eventLocked) return;
        dismissed = false;
        clickedOpen = true;
        target = true;
    }

    void collapse() {
        if (eventLocked) return;
        dismissed = true;
        clickedOpen = false;
        target = false;
        if (chatSession && openness <= .002) chatCardDismissedByUser = true;
        cancelSeek();
    }

    void reset() {
        eventRevision++;
        chatSession = false;
        chatCardDismissedByUser = false;
        dismissed = false;
        clickedOpen = false;
        available = false;
        blocked = false;
        target = false;
        transitionTarget = false;
        transitionFrom = 0.0;
        transitionTo = 0.0;
        transitionElapsed = CLOSE_DURATION_MILLIS;
        openness = 0.0;
        transitionVelocity = 0.0;
        transitionInitialVelocity = 0.0;
        closingContentFrom = 0.0;
        closingContentLimit = 1.0;
        trackReady = true;
        eventVisibleClock.reset();
        eventHoldMillis = TRACK_NOTICE_HOLD_MILLIS;
        eventLocked = false;
        animationActive = false;
        trackNoticeActive = false;
        suppressChatAutoOpen = false;
        capturedPress = false;
        pressedTarget = MusicCardLayout.Target.NONE;
        pressedSession = Long.MIN_VALUE;
        pressedTrack = null;
        cancelSeek();
    }

    void suspend() {
        blocked = true;
        eventVisibleClock.pause();
        capturedPress = false;
        pressedTarget = MusicCardLayout.Target.NONE;
        pressedSession = Long.MIN_VALUE;
        pressedTrack = null;
        cancelSeek();
    }

    void capturePress() {
        capturePress(MusicCardLayout.Target.NONE, Long.MIN_VALUE, null);
    }

    void capturePress(MusicCardLayout.Target target, long session, String track) {
        capturedPress = true;
        pressedTarget = target == null ? MusicCardLayout.Target.NONE : target;
        pressedSession = session;
        pressedTrack = track;
    }

    boolean pressMatches(MusicCardLayout.Target target, long session, String track) {
        return capturedPress && pressedTarget == target && pressedSession == session
                && (pressedTrack == null ? track == null : pressedTrack.equals(track));
    }

    boolean release() {
        boolean consumed = capturedPress;
        capturedPress = false;
        pressedTarget = MusicCardLayout.Target.NONE;
        pressedSession = Long.MIN_VALUE;
        pressedTrack = null;
        cancelSeek();
        return consumed;
    }

    void beginSeek(long session, String track, double fraction) {
        seekSession = session;
        seekTrack = track;
        seekFraction = clamp(fraction);
    }

    boolean dragging(long session, String track) {
        if (seekSession != session || seekTrack == null || !seekTrack.equals(track) || !canInteract()) {
            cancelSeek();
            return false;
        }
        return seekSession != Long.MIN_VALUE;
    }

    void previewSeek(double fraction) {
        seekFraction = clamp(fraction);
    }

    double seekFraction() {
        return seekFraction;
    }

    boolean seeking() {
        return seekSession != Long.MIN_VALUE;
    }

    void cancelSeek() {
        seekSession = Long.MIN_VALUE;
        seekTrack = null;
    }

    private static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }
}
