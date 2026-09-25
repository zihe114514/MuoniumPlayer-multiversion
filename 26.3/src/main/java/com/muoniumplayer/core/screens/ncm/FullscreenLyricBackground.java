package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.settings.HudConfig;

/**
 * 全屏歌词页的背景来源。
 *
 * <p>索引写入 {@link HudConfig#fullscreenLyricBackground}，与歌词样式一样只能向后追加，
 * 这样旧配置会始终落在原来的模糊封面背景上。</p>
 */
enum FullscreenLyricBackground {

    BLURRED_COVER("模糊封面"),
    LATENT("隐现");

    private final String displayName;

    FullscreenLyricBackground(String displayName) {
        this.displayName = displayName;
    }

    String getDisplayName() {
        return displayName;
    }

    static FullscreenLyricBackground current() {
        FullscreenLyricBackground[] values = values();
        int index = HudConfig.fullscreenLyricBackground;
        if (index < 0 || index >= values.length) return BLURRED_COVER;
        return values[index];
    }

    static FullscreenLyricBackground next() {
        return shift(1);
    }

    static FullscreenLyricBackground previous() {
        return shift(-1);
    }

    private static FullscreenLyricBackground shift(int delta) {
        FullscreenLyricBackground[] values = values();
        int index = ((Math.max(0, HudConfig.fullscreenLyricBackground) + delta) % values.length
                + values.length) % values.length;
        HudConfig.fullscreenLyricBackground = index;
        HudConfig.save();
        return values[index];
    }
}
