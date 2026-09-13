package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverThemeGeneratorTest {

    @Test
    void generatedDarkCoverThemeStaysReadable() {
        int[] theme = CoverThemeGenerator.generate(samplePalette(), false);
        assertEquals(NCMScreen.ColorType.values().length, theme.length);
        int background = theme[NCMScreen.ColorType.GENERIC_BACKGROUND.ordinal()];
        int primaryText = theme[NCMScreen.ColorType.PRIMARY_TEXT.ordinal()];
        int accent = theme[NCMScreen.ColorType.ACCENT.ordinal()];

        assertTrue(luminance(background) < .22f, "深色主题背景应保持深色层次");
        assertTrue(saturation(accent) > .45f, "强调色应保留封面色彩");
        assertTrue(contrast(primaryText, background) >= 4.5f, "主要文字必须具备可读对比度");
    }

    @Test
    void generatedLightCoverThemeStaysReadable() {
        int[] theme = CoverThemeGenerator.generate(samplePalette(), true);
        int background = theme[NCMScreen.ColorType.GENERIC_BACKGROUND.ordinal()];
        int primaryText = theme[NCMScreen.ColorType.PRIMARY_TEXT.ordinal()];

        assertTrue(luminance(background) > .65f, "浅色预设应生成浅色封面主题");
        assertTrue(contrast(primaryText, background) >= 4.5f, "浅色主题文字必须具备可读对比度");
    }

    private static int[] samplePalette() {
        return new int[]{0x2B6CB0, 0xF6AD55, 0x1A202C, 0xE53E3E, 0x38A169, 0x805AD5};
    }

    private static float saturation(int rgb) {
        float[] hsb = java.awt.Color.RGBtoHSB((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF,
                rgb & 0xFF, null);
        return hsb[1];
    }

    private static float luminance(int rgb) {
        float r = channel((rgb >>> 16) & 0xFF);
        float g = channel((rgb >>> 8) & 0xFF);
        float b = channel(rgb & 0xFF);
        return .2126f * r + .7152f * g + .0722f * b;
    }

    private static float channel(int value) {
        float c = value / 255f;
        return c <= .04045f ? c / 12.92f : (float) Math.pow((c + .055f) / 1.055f, 2.4f);
    }

    private static float contrast(int first, int second) {
        float a = luminance(first);
        float b = luminance(second);
        return (Math.max(a, b) + .05f) / (Math.min(a, b) + .05f);
    }
}
