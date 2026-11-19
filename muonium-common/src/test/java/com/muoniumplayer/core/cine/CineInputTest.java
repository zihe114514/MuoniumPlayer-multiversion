package com.muoniumplayer.core.cine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CineInputTest {

    @Test
    void heldKeyAndReleaseDoNotBecomeIdleBetweenRepeatEvents() {
        CineInput input = new CineInput();
        input.key(87, 1);
        for (int i = 0; i < 300; i++) assertTrue(input.consume(true, false));
        input.key(87, 0);
        assertTrue(input.consume(true, false));
        assertFalse(input.consume(true, false));
    }

    @Test
    void mappedMovementAndHeldButtonBlockActivation() {
        CineInput input = new CineInput();
        assertTrue(input.consume(true, true));
        input.button(0, 1);
        for (int i = 0; i < 100; i++) assertTrue(input.consume(true, false));
        input.button(0, 0);
        assertTrue(input.consume(true, false));
        assertFalse(input.consume(true, false));
    }

    @Test
    void scrollAndRealMovementCountButStationaryCallbacksDoNot() {
        CineInput input = new CineInput();
        input.move(500d, 300d);
        assertFalse(input.consume(true, false));
        input.move(500d, 300d);
        assertFalse(input.consume(true, false));
        input.move(501d, 300d);
        assertTrue(input.consume(true, false));
        input.relativeMove(0d, 0d);
        assertFalse(input.consume(true, false));
        input.relativeMove(0.1d, 0d);
        assertTrue(input.consume(true, false));
        input.scroll(0d, 1d);
        assertTrue(input.consume(true, false));
        assertFalse(input.consume(true, false));
    }

    @Test
    void losingFocusClearsHeldStateAndPreventsIdleEntry() {
        CineInput input = new CineInput();
        input.key(87, 1);
        assertTrue(input.consume(false, false));
        assertTrue(input.consume(false, false));
        assertFalse(input.consume(true, false), "失焦错过释放事件不能把键永久锁住");
    }
}
