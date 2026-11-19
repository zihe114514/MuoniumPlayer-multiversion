package com.muoniumplayer.core.rendering;

/** 决策纯函数：通知优先级、过滤与表面所有权。 */
final class IslandNoticeRules {
    private IslandNoticeRules() {
    }

    static boolean suppressAuxiliaryNotice(String type, String title, boolean enabled) {
        return suppressAuxiliaryNotice(type, title, enabled, false);
    }

    static boolean suppressAuxiliaryNotice(String type, String title, boolean enabled,
                                           boolean modelInferenceEnabled) {
        if (enabled || type == null) return false;
        if (modelInferenceEnabled && ("MODEL_INFERENCE".equals(type)
                || "MODEL_INFERENCE_SUCCESS".equals(type))) return false;
        String safeTitle = title == null ? "" : title;
        if ("LISTENING_DURATION_SUCCESS".equals(type) || "LISTENING_DURATION_ERROR".equals(type)
                || "NETEASE_UPLOADING".equals(type) || "RECENT_PLAY_SUCCESS".equals(type)
                || "RECENT_PLAY_ERROR".equals(type) || "ZSTD_NATIVE_SUCCESS".equals(type)
                || "MODEL_INFERENCE".equals(type) || "MODEL_INFERENCE_SUCCESS".equals(type)) return true;
        if ("QUALITY".equals(type)) return safeTitle.startsWith("获取音质：");
        return "AMBIENT_SCENE".equals(type)
                && !"环境选曲未开启".equals(safeTitle) && !"还没采到环境".equals(safeTitle);
    }

    static boolean preempts(int incoming, int current, boolean currentPersistent,
                            boolean incomingPersistent, boolean instant, boolean protectedResume) {
        if (incoming < current) return false;
        if (protectedResume && incoming < 3) return false;
        if (incoming > current) return true;
        if (current == 3) return false;
        return instant || currentPersistent && !incomingPersistent;
    }

    static boolean ownsSurface(boolean active, int priority, boolean downloading, boolean completing) {
        return ownsSurface(active, priority, downloading, completing, false);
    }

    static boolean ownsSurface(boolean active, int priority, boolean downloading, boolean completing,
                               boolean musicCardProtectsSurface) {
        if (!active || musicCardProtectsSurface) return false;
        return priority >= 2 || !downloading && !completing;
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
