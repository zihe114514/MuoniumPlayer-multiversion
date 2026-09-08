package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.management.UiFont;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudSetting;

/** Navigation and shared mutable state exposed to individual onboarding pages. */
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
}
