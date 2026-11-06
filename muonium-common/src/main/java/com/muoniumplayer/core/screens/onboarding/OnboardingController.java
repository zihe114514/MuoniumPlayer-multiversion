package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudSetting;

/** 暴露给各新手引导页的导航和共享可变状态。 */
public interface OnboardingController {
    OnboardingStep step();

    int stepIndex();

    long openedAt();

    long pageStartedAt();

    boolean isSkipConfirm();

    void setSkipConfirm(boolean value);

    void nextPage();

    void previousPage();

    void skip();

    void finish();

    void openAccountManager();

    MusicPlatform selectedPlatform();

    void selectPlatform(MusicPlatform platform);

    NCMTheme.ThemePreset selectedTheme();

    void selectTheme(NCMTheme.ThemePreset theme);

    UiFont selectedFont();

    void selectFont(UiFont font);

    void cycleFont();

    float hudValue(HudSetting setting);

    void adjustHudSetting(HudSetting setting, float delta);

    // ------------------------------------------------ 沉浸感（声学 + 环境选曲）

    /** 场景声学总开关。默认关闭——这是引导里唯一「开箱不问就不会被打开」的一组功能。 */
    boolean acousticsEnabled();

    void setAcousticsEnabled(boolean value);

    /** 环境选曲总开关，默认关闭。 */
    boolean ambientEnabled();

    void setAmbientEnabled(boolean value);
}
