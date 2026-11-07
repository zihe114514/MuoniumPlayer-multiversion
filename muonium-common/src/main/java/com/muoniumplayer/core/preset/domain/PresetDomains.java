package com.muoniumplayer.core.preset.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Ordered configuration adapters. Account/provider/cache settings must never be added here. */
public final class PresetDomains {
    private PresetDomains() {}
    public static List<PresetDomain> all() {
        return Collections.unmodifiableList(Arrays.<PresetDomain>asList(new HudPresetDomain(), new ThemePresetDomain(),
                new FoliaBackgroundPresetDomain(), new FontPresetDomain(), new TransparentPresetDomain(),
                new CinePresetDomain(), new AcousticsPresetDomain(), new ViperPresetDomain()));
    }
}
