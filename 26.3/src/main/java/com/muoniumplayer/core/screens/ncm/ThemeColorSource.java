package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.settings.HudConfig;

/**
 * 播放器主题颜色的来源。
 *
 * <p>默认仍然是用户原本选中的主题预设；选择「封面跟随」时只改变颜色计算，不会覆盖
 * 每个预设自己的自定义颜色。切回「当前主题」即可恢复原样。</p>
 */
enum ThemeColorSource {

    PRESET("当前主题"),
    COVER("封面跟随");

    private final String displayName;

    ThemeColorSource(String displayName) {
        this.displayName = displayName;
    }

    String getDisplayName() {
        return displayName;
    }

    static ThemeColorSource current() {
        ThemeColorSource[] values = values();
        int index = HudConfig.themeColorSource;
        if (index < 0 || index >= values.length) return PRESET;
        return values[index];
    }

    static ThemeColorSource next() {
        return shift(1);
    }

    static ThemeColorSource previous() {
        return shift(-1);
    }

    private static ThemeColorSource shift(int delta) {
        ThemeColorSource[] values = values();
        int index = ((Math.max(0, HudConfig.themeColorSource) + delta) % values.length
                + values.length) % values.length;
        NCMTheme.setColorSource(index);
        return values[index];
    }
}
