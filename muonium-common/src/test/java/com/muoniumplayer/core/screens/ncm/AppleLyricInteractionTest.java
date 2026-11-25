package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppleLyricInteractionTest {
    private static final AppleLyricInteraction.Bounds VIEW = box(10, 20, 100, 130);
    private static final long SESSION = 8;
    private static final long REVISION = 4;
    private final AppleLyricInteraction input = new AppleLyricInteraction();

    @Test
    void anchorsSeekToTheDisplayedRowEvenWhenAnotherRowSlidesUnderThePointer() {
        Object first = new Object();
        Object second = new Object();
        begin();
        input.add(first, 2_000, box(20, 40, 60, 58));
        input.add(second, 3_000, box(20, 60, 60, 78));
        input.press(35, 49, SESSION, REVISION);
        assertTrue(input.isPressed(first));

        begin();
        input.add(first, 2_000, box(20, 20, 60, 38));
        input.add(second, 3_000, box(20, 40, 60, 58));
        assertEquals(2_000, input.release(35, 49, SESSION, REVISION));
        assertEquals(-1, input.release(35, 49, SESSION, REVISION));
    }

    @Test
    void clipsTheRowAndDoesNotTurnWhitespaceIntoASeekTarget() {
        begin();
        input.add(new Object(), 8_000, box(0, 8, 65, 35));
        assertFalse(input.canHit(9, 30));
        assertFalse(input.canHit(45, 19));
        assertFalse(input.canHit(65, 30));
        assertTrue(input.canHit(45, 20));
        input.press(66, 30, SESSION, REVISION);
        assertEquals(-1, input.release(64, 30, SESSION, REVISION));
    }

    @Test
    void cancelsDragsAndRejectsStaleOrUnpaintedFrames() {
        begin();
        input.add(new Object(), 8_000, box(20, 30, 65, 50));
        input.press(35, 40, SESSION, REVISION);
        input.drag(35, 52);
        assertEquals(-1, input.release(35, 40, SESSION, REVISION));

        input.press(35, 40, SESSION, REVISION);
        assertEquals(-1, input.release(46, 40, SESSION, REVISION));
        input.press(35, 40, SESSION, REVISION);
        assertEquals(-1, input.release(35, 40, SESSION + 1, REVISION));
        input.press(35, 40, SESSION, REVISION);
        assertEquals(-1, input.release(35, 40, SESSION, REVISION + 1));

        input.press(35, 40, SESSION, REVISION);
        input.invalidate();
        assertFalse(input.canHit(35, 40));
        assertEquals(-1, input.release(35, 40, SESSION, REVISION));
    }

    private void begin() {
        input.beginFrame(SESSION, REVISION, VIEW);
    }

    private static AppleLyricInteraction.Bounds box(double x, double y, double right, double bottom) {
        return new AppleLyricInteraction.Bounds(x, y, right, bottom);
    }
}
