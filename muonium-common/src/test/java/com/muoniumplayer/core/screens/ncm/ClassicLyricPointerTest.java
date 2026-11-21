package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassicLyricPointerTest {
    private final ClassicLyricPointer pointer = new ClassicLyricPointer();
    private final ClassicLyricPointer.Track track = new ClassicLyricPointer.Track(7, "player:42", 1);

    @Test
    void scrollBetweenPressAndReleaseCannotRetargetToAnotherLyric() {
        begin(track);
        row(0, 1200, 20);
        row(1, 2400, 50);
        assertTrue(pointer.press(50, 32, track));

        begin(track);
        row(0, 1200, -10);
        row(1, 2400, 20);
        assertEquals(1, pointer.hover(50, 33));
        assertEquals(1200, pointer.release(50, 33, 8, track));
        assertEquals(-1, pointer.release(50, 33, 8, track));
    }

    @Test
    void cancelsDragEvenIfThePointerReturnsAndRejectsChangedTrackOrLyrics() {
        begin(track);
        row(0, 1200, 20);
        assertTrue(pointer.press(50, 32, track));
        pointer.pointerMoved(50, 46, 8);
        assertEquals(-1, pointer.release(50, 32, 8, track));

        assertTrue(pointer.press(50, 32, track));
        assertEquals(-1, pointer.release(50, 45, 8, track)); // no intervening render sample

        assertTrue(pointer.press(50, 32, track));
        var replacement = new ClassicLyricPointer.Track(7, "player:42", 2);
        assertEquals(-1, pointer.release(50, 32, 8, replacement));

        assertTrue(pointer.press(50, 32, track));
        assertEquals(-1, pointer.release(50, 32, 8,
                new ClassicLyricPointer.Track(8, "player:42", 1)));
    }

    @Test
    void clipsToTheVisibleViewportAndUsesRenderedRowScaleAndTransition() {
        pointer.beginFrame(track, 0, 0, .9, 5, 20, 10, 100, 90);
        pointer.addRow(1, 100, 20, 20, 60, 20, 20, 1.2);
        pointer.addRow(2, 200, 65, 20, 55, 20, 20, 1.2);
        assertEquals(1, pointer.hover(45, 21));
        assertEquals(2, pointer.hover(70, 21)); // overlap uses foreground paint order
        assertEquals(2, pointer.hover(97, 21)); // later duet column
        assertFalse(pointer.press(17, 21, track));
        assertFalse(pointer.press(97, 10, track));

        assertTrue(pointer.press(20, 21, track));
        assertEquals(-1, pointer.release(17, 21, 8, track)); // outside clipped viewport
        assertTrue(pointer.press(45, 21, track));
        pointer.invalidate();
        assertEquals(-1, pointer.release(45, 21, 8, track));
    }

    private void begin(ClassicLyricPointer.Track track) {
        pointer.beginFrame(track, 0, 0, 1, 0, 0, 0, 100, 100);
    }

    private void row(int index, long millis, double top) {
        pointer.addRow(index, millis, 20, top, 80, 20, 20, 1);
    }
}
