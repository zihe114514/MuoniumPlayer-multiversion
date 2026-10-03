package com.muoniumplayer.core.screens.ncm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationRequestGateTest {
    @Test void latestSearchWinsEvenWhenOldResultsArriveLast() {
        NavigationRequestGate gate = new NavigationRequestGate();
        long oldSearch = gate.begin();
        long latestSearch = gate.begin();
        assertFalse(gate.isCurrent(oldSearch));
        assertTrue(gate.isCurrent(latestSearch));
    }
    @Test void switchingSourceOrLeavingPageRejectsTheOldRequest() {
        NavigationRequestGate gate = new NavigationRequestGate();
        long pending = gate.begin();
        gate.invalidate();
        assertFalse(gate.isCurrent(pending));
        long newSource = gate.begin();
        assertTrue(gate.isCurrent(newSource));
        assertFalse(gate.isCurrent(pending));
    }
    @Test void switchingAwayAndBackCannotReviveAnEarlierResponse() {
        NavigationRequestGate gate = new NavigationRequestGate();
        long firstSource = gate.begin();
        gate.invalidate();
        long secondSource = gate.begin();
        gate.invalidate();
        long returnToFirst = gate.begin();
        assertFalse(gate.isCurrent(firstSource));
        assertFalse(gate.isCurrent(secondSource));
        assertTrue(gate.isCurrent(returnToFirst));
    }
}
