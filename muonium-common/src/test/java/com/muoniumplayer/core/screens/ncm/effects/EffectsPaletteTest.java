package com.muoniumplayer.core.screens.ncm.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EffectsPaletteTest {

    @Test
    void darkAndLightThemesKeepTheirIdentityAndReadableColorPairs() {
        int[][] inputs = {
                {0x211821, 0x30212C, 0x493342, 0x604252, 0xFFF7FB, 0xC2AAB7, 0xE48AAB, 0xF4A9C4, 0x2A1D27, 0x1A1219},
                {0xF4F0EA, 0xFFFCF7, 0xE7DDD1, 0xD4C6B8, 0x2A2723, 0x817970, 0xB85C38, 0xD37B54, 0xEEE7DF, 0xE7DED5},
                {0x182331, 0x26384B, 0x49637C, 0x91A8C2, 0xF7FBFF, 0xB7C6D8, 0x64A8FF, 0x8BC1FF, 0x203044, 0x111C2A},
                {0x101010, 0x303030, 0x555555, 0x333333, 0xCCCCCC, 0x222222, 0x333333, 0x555555, 0x151515, 0x202020},
                {0xEEEEEE, 0xDDDDDD, 0xCCCCCC, 0xAAAAAA, 0x555555, 0xCCCCCC, 0xEEEE99, 0xCCDDFF, 0xFFFFFF, 0xEEEEEE}
        };
        for (int[] colors : inputs) {
            EffectsPalette palette = EffectsPalette.fromColors(colors[0], colors[1], colors[2], colors[3], colors[4], colors[5],
                    colors[6], colors[7], colors[8], colors[9]);
            assertEquals(0xFF000000 | colors[0], palette.background());
            for (int background : new int[]{palette.background(), palette.surface(), palette.container(), palette.input(), palette.high(), palette.primaryContainer()}) {
                assertTrue(EffectsPalette.contrast(palette.text(), background) >= 4.5);
                assertTrue(EffectsPalette.contrast(palette.muted(), background) >= 4.5);
                assertTrue(EffectsPalette.contrast(palette.primary(), background) >= 4.5);
                assertTrue(EffectsPalette.contrast(palette.success(), background) >= 4.5);
                assertTrue(EffectsPalette.contrast(palette.warning(), background) >= 4.5);
                assertTrue(EffectsPalette.contrast(palette.error(), background) >= 4.5);
                assertTrue(EffectsPalette.contrast(palette.outline(), background) >= 3);
            }
            assertTrue(EffectsPalette.contrast(palette.onPrimary(), palette.primary()) >= 4.5);
            assertTrue(EffectsPalette.contrast(palette.onPrimary(), palette.primaryHover()) >= 4.5);
        }
    }

    @Test
    void accentHoverInputAndNavigationAreTakenFromTheSameThemeSnapshot() {
        EffectsPalette one = EffectsPalette.fromColors(0xFFFFFF, 0xF5F5F5, 0xEAEAEA, 0x666666, 0x202020,
                0x555555, 0x775522, 0x553311, 0xEEE0C0, 0xDDD0B0);
        EffectsPalette two = EffectsPalette.fromColors(0xFFFFFF, 0xF5F5F5, 0xEAEAEA, 0x666666, 0x202020,
                0x555555, 0x226677, 0x113355, 0xE0EEEE, 0xD0DDDD);
        assertNotEquals(one.primary(), two.primary());
        assertNotEquals(one.primaryHover(), two.primaryHover());
        assertNotEquals(one.input(), two.input());
        assertNotEquals(one.surface(), two.surface());
        assertEquals(21, EffectsPalette.contrast(0xFFFFFFFF, 0xFF000000), 1e-9);
    }

    @Test
    void paletteIsImmutableAndHasOpaqueArgbForDirectSkijaDrawing() {
        EffectsPalette palette = EffectsPalette.fallback();
        for (int color : new int[]{palette.background(), palette.surface(), palette.container(), palette.input(), palette.high(),
                palette.text(), palette.muted(), palette.primary(), palette.primaryContainer(), palette.onPrimary(), palette.outline()}) {
            assertEquals(255, color >>> 24);
        }
        assertEquals(EffectsPalette.fallback(), palette);
    }
}
