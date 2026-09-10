package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.rendering.entities.impl.ScrollText;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression probes for the client crash in error report d2c713e3.
 *
 * A transitional ControlsBar layout produced a negative LabelWidget width, which reached
 * ScrollText and Skia's Rect::makeXYWH. These probes do not need a GL/Skija frame: the guard
 * must reject invalid input before any native rectangle is constructed.
 */
class ScrollTextSafetyTest {

    @Test
    void nonPositiveScrollWidthIsRejectedBeforeSkia() {
        ScrollText scrollText = new ScrollText();
        scrollText.render(null, "transient layout", 0.0, 0.0, -9.700003, 0xFFFFFFFF);
        assertFalse(scrollText.isScrolling);
    }

    @Test
    void labelWidthSentinelAndTransientNegativeValuesAreNormalized() {
        LabelWidget label = new LabelWidget("transient layout");
        assertEquals(-1.0, label.setMaxWidth(-1.0).getMaxWidth(), 0.0);
        assertEquals(0.0, label.setMaxWidth(-9.700003).getMaxWidth(), 0.0);
        assertEquals(0.0, label.setMaxWidth(Double.NaN).getMaxWidth(), 0.0);
    }
}
