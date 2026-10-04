package com.muoniumplayer.core.rendering;

/** 只有真正连续画出的帧才消费预算；隐藏和长时间不渲染不会让消息在后台消失。 */
final class IslandVisibleClock {
    private static final long MAX_CREDIT_PER_FRAME_MILLIS = 250L;
    private long elapsed;
    private long lastPresented = -1L;
    void record(long now) {
        long delta = lastPresented < 0L ? 0L : now - lastPresented;
        lastPresented = now;
        // A slow but continuously visible renderer must still consume time. Hidden scenes call
        // pause(), so a long gap between unpaused frames is treated conservatively as one frame,
        // rather than making the budget immortal at low FPS.
        if (delta > 0L) {
            elapsed += Math.min(delta, MAX_CREDIT_PER_FRAME_MILLIS);
        }
    }
    void pause() { lastPresented = -1L; }
    void reset() { elapsed = 0L; pause(); }
    long elapsed() { return elapsed; }
}
