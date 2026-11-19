package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MusicCardColorsTest {
    @Test
    void missingCoverUsesThisFramesFallbackInsteadOfAnyPreviousPalette() {
        int pink = MusicCardColors.accent(new int[]{0xD648A0}, 0x34AACC);
        int fallback = MusicCardColors.accent(null, 0x34AACC);
        assertNotEquals(pink, fallback);
        assertEquals(fallback, MusicCardColors.accent(new int[0], 0x34AACC));
        assertTrue((fallback & 255) > ((fallback >>> 16) & 255));
    }

    @Test
    void darkArtworkBecomesReadableWithoutLosingItsHue() {
        int red = MusicCardColors.accent(new int[]{0x210507}, 0x55AACC);
        assertTrue(((red >>> 16) & 255) >= 185);
        assertTrue(((red >>> 16) & 255) > (red & 255));
        assertEquals(0xE3E4EC, MusicCardColors.accent(new int[]{0x101010}, 0x55AACC));
    }

    @Test
    void picksColourfulArtworkOverBlackBordersAndKeepsBlendEndpoints() {
        int[] palette = {0x000000, 0xE6599C, 0x4B3C45};
        int color = MusicCardColors.accent(palette, 0x11AACC);
        assertTrue(((color >>> 16) & 255) > ((color >>> 8) & 255));
        assertEquals(0x123456, MusicCardColors.mix(0x123456, 0xABCDEF, 0));
        assertEquals(0xABCDEF, MusicCardColors.mix(0x123456, 0xABCDEF, 1));
        assertEquals(0x808080, MusicCardColors.mix(0, 0xFFFFFF, .5));
        assertEquals(0x123456, MusicCardColors.mix(0x123456, 0xFFFFFF, Double.NaN));
    }
}
