package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LyricBlurClipTest {
    @Test
    void skipsRowsInPreRenderMarginWithNoBlurOverlap() {
        assertNull(LyricBlurClip.layer(0, 0, 240, 200, 231, 261, 12.95f));
        assertNull(LyricBlurClip.layer(0, 0, 240, 200, -61, -31, 12.95f));
    }

    @Test
    void skipsRowsThatOnlyTouchPaddedBoundary() {
        assertNull(LyricBlurClip.layer(0, 0, 240, 200, 224, 254, 12));
        assertNull(LyricBlurClip.layer(0, 0, 240, 200, -54, -24, 12));
    }

    @Test
    void preservesVisiblePortionOfBlurNearBothEdges() {
        var bottom = LyricBlurClip.layer(0, 0, 240, 200, 221, 251, 12);
        assertNotNull(bottom);
        assertEquals(209f, bottom.getTop());
        assertEquals(212f, bottom.getBottom());

        var top = LyricBlurClip.layer(0, 0, 240, 200, -51, -21, 12);
        assertNotNull(top);
        assertEquals(-12f, top.getTop());
        assertEquals(-9f, top.getBottom());
    }
}
