package com.muoniumplayer.core.screens.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OneConfigRouteCompatibilityTest {
    public static class TwoArguments {
        final String id;
        final String category;
        public TwoArguments(String id, String category) { this.id = id; this.category = category; }
    }

    public static class ThreeArguments {
        final String id;
        final String category;
        final String submenu;
        public ThreeArguments(String id, String category, String submenu) {
            this.id = id;
            this.category = category;
            this.submenu = submenu;
        }
    }

    @Test
    void supportsMuoniumConfigAndOneConfigRoutes() throws Exception {
        TwoArguments oldRoute = (TwoArguments) OneConfigHudBridge.createModConfigRoute(TwoArguments.class, "muonium.hud");
        assertEquals("muonium.hud", oldRoute.id);
        assertNull(oldRoute.category);
        ThreeArguments newRoute = (ThreeArguments) OneConfigHudBridge.createModConfigRoute(ThreeArguments.class, "muonium.hud");
        assertEquals("muonium.hud", newRoute.id);
        assertNull(newRoute.category);
        assertNull(newRoute.submenu);
        assertThrows(NoSuchMethodException.class,
                () -> OneConfigHudBridge.createModConfigRoute(Object.class, "muonium.hud"));
    }
}
