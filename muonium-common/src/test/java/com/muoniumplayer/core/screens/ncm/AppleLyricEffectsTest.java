package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppleLyricEffectsTest {
    @Test
    void onlyFutureUnfocusedLinesAreBlurred() {
        assertEquals(0, LyricPlaybackFocus.blur(0, 900, 0));
        assertEquals(0, LyricPlaybackFocus.blur(1, 0, 0));
        assertEquals(0, LyricPlaybackFocus.blur(1, -250, 0));
        assertEquals(0, LyricPlaybackFocus.blur(1, 900, 1));
        assertEquals(1, LyricPlaybackFocus.blur(5, 5000, 0));
        assertEquals(.30, LyricPlaybackFocus.blur(1, 5000, 0));
        assertTrue(LyricPlaybackFocus.blur(1, 120, 0) < LyricPlaybackFocus.blur(1, 900, 0));
        assertTrue(LyricPlaybackFocus.blur(2, 900, 0) < LyricPlaybackFocus.blur(3, 900, 0));
    }

    @Test
    void backdropUsesTheRequestedCoverAndKeepsItsSpatialColor() throws Exception {
        var source = new java.awt.image.BufferedImage(80, 80, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 80; y++) {
            for (int x = 0; x < 80; x++) source.setRGB(x, y, x < 40 ? 0xe07321 : 0x287dba);
        }
        CoverBackdropArtwork.publish("sample-cover", source);
        assertNull(CoverBackdropArtwork.find("another-cover"));
        var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(CoverBackdropArtwork.find("sample-cover")));
        assertEquals(32, decoded.getWidth());
        int warm = decoded.getRGB(4, 16), cool = decoded.getRGB(27, 16);
        assertTrue(((warm >> 16) & 255) > (warm & 255));
        assertTrue(((cool >> 16) & 255) < (cool & 255));
        assertTrue(((warm >> 16) & 255) < 120, "bright portions leave room for lyric text");
    }

    @Test
    void coverPaletteStaysDarkAndRetainsNeutralOrWarmTones() {
        int white = AppleLyricEffects.backdropColor(new int[]{0xffffff}, 2);
        assertEquals(0xff616161, white);
        int warm = AppleLyricEffects.backdropColor(new int[]{0xe07321}, 0);
        assertTrue(((warm >> 16) & 255) > (warm & 255));
        assertNotEquals(AppleLyricEffects.backdropColor(null, 0), AppleLyricEffects.backdropColor(null, 1));
        assertEquals(0xff405060, AppleLyricEffects.mix(0xff204060, 0xff606060, .5));
    }
}
