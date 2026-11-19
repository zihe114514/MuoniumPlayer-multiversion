package com.muoniumplayer.core.screens.ncm.effects;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;

/** 设计轴上的实际字号；FontManager 档名为字号的两倍，不能直接当作像素值。 */
public final class EffectsTypography {

    private EffectsTypography() { }

    public static CFontRenderer title() { return FontManager.pf40bold; }
    public static CFontRenderer heading() { return FontManager.pf40bold; }
    public static CFontRenderer section() { return FontManager.pf34bold; }
    public static CFontRenderer label() { return FontManager.pf28bold; }
    public static CFontRenderer body() { return FontManager.pf32; }
    public static CFontRenderer caption() { return FontManager.pf25; }
    public static CFontRenderer captionStrong() { return FontManager.pf25bold; }
}
