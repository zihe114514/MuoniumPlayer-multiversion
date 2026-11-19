package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FontPickerGeometryTest {
    @Test
    void wideLayoutSeparatesPreviewFromListAndFooter() {
        FontPickerGeometry g = new FontPickerGeometry(980, 740);
        assertTrue(g.columns);
        assertEquals(g.inset + g.listWidth + 20, g.previewX);
        assertTrue(g.listY + g.listHeight <= g.footerY - 40);
        assertTrue(g.previewY + g.previewHeight <= g.footerY - 40);
    }

    @Test
    void narrowLayoutKeepsARealListBelowCompactPreview() {
        FontPickerGeometry g = new FontPickerGeometry(560, 740);
        assertFalse(g.columns);
        assertEquals(g.contentWidth, g.previewWidth);
        assertTrue(g.listY >= g.previewY + g.previewHeight);
        assertTrue(g.listHeight >= 200);
    }

    @Test
    void mediumHeightWindowUsesCompactPreviewToPreserveListSpace() {
        FontPickerGeometry g = new FontPickerGeometry(900, 568);
        assertFalse(g.columns);
        assertEquals(0, g.previewHeight);
        assertTrue(g.listHeight >= 150);
        assertTrue(g.listY + g.listHeight <= g.footerY - 40);
    }

    @Test
    void shortWindowsGiveListPriorityRatherThanAnOverlappingPreview() {
        FontPickerGeometry g = new FontPickerGeometry(980, 428);
        assertFalse(g.columns);
        assertEquals(0, g.previewHeight);
        assertTrue(g.listHeight > 100);
    }

    @Test
    void actionsAreContiguousWithoutOverlapAcrossSizes() {
        for (double width : new double[]{300, 420, 560, 800, 980}) {
            FontPickerGeometry g = new FontPickerGeometry(width, 740);
            assertTrue(g.actionWidth(0) > 0);
            assertEquals(g.actionX(0) + g.actionWidth(0) + 8, g.actionX(1), 1e-8);
            assertEquals(g.actionX(1) + g.actionWidth(1) + 8, g.actionX(2), 1e-8);
            assertTrue(g.actionX(2) + g.actionWidth(2) <= width - g.inset + 1e-8);
        }
    }
}
