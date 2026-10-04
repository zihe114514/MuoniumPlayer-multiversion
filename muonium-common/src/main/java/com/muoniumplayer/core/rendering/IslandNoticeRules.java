package com.muoniumplayer.core.rendering;

/** 决策纯函数：四级优先级、等优先级 FIFO、高优先级不能被音量/主题打断。 */
final class IslandNoticeRules {
    private IslandNoticeRules() { }
    static boolean preempts(int incoming, int current, boolean currentPersistent,
                            boolean incomingPersistent, boolean instant, boolean protectedResume) {
        if (incoming < current) return false;
        if (protectedResume && incoming < 3) return false;
        if (incoming > current) return true;
        if (current == 3) return false;
        return instant || currentPersistent && !incomingPersistent;
    }
    static boolean ownsSurface(boolean active, int priority, boolean downloading, boolean completing) {
        return active && (priority >= 2 || !downloading && !completing);
    }
    static boolean presentationReady(boolean ownsSurface, boolean backendReady, float visibility,
                                     double expansion, float contentAlpha, float contentTransition,
                                     float copyTransition, boolean instant) {
        return ownsSurface && backendReady && visibility > .75f && expansion > .70
                && contentAlpha > .70f && contentTransition > .70f
                && (instant || copyTransition > .70f);
    }
    static long minimumVisibleMillis(int priority, boolean instant) {
        return priority == 3 ? 1_800L : instant ? 500L : 1_000L;
    }
}
