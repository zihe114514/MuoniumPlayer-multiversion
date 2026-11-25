package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MuoniumConfigSelectionTest {
    @Test
    void legacyAndUnknownChoicesNormalizeToBuiltin() throws Exception {
        java.lang.reflect.Method normalize = HudConfig.class.getDeclaredMethod("normalizeHudEditorUi", String.class);
        normalize.setAccessible(true);
        assertEquals("builtin", HudConfig.HUD_EDITOR_UI_BUILTIN);
        assertEquals("mconfig", HudConfig.HUD_EDITOR_UI_MCONFIG);
        assertEquals("oneconfig", HudConfig.HUD_EDITOR_UI_ONECONFIG);
        assertEquals("modernui", HudConfig.HUD_EDITOR_UI_MODERNUI);
        for (String choice : new String[]{"builtin", "mconfig", "oneconfig", "BUILTIN",
                "MCONFIG", "ONECONFIG", "unknown", "", null}) {
            assertEquals(HudConfig.HUD_EDITOR_UI_BUILTIN, normalize.invoke(null, choice), choice);
        }
        assertEquals(HudConfig.HUD_EDITOR_UI_MODERNUI, normalize.invoke(null, "modernui"));
        assertEquals(HudConfig.HUD_EDITOR_UI_MODERNUI, normalize.invoke(null, "MODERNUI"));
    }

    @Test
    void everyChoiceAndLaneKeepsInstallerDisabledEvenWithOldOptInProperty() {
        String previous = HudConfig.hudEditorUi;
        boolean previouslyInstalled = HudInitialSelection.isOneConfigInstalled();
        String property = "muonium.muoniumconfig.autoDownload";
        String previousProperty = System.getProperty(property);
        try {
            for (String optIn : new String[]{"true", "false", ""}) {
                System.setProperty(property, optIn);
                for (boolean installed : new boolean[]{false, true}) {
                    HudInitialSelection.setOneConfigInstalled(installed);
                    for (String choice : new String[]{HudConfig.HUD_EDITOR_UI_BUILTIN,
                            HudConfig.HUD_EDITOR_UI_MCONFIG, HudConfig.HUD_EDITOR_UI_ONECONFIG,
                            HudConfig.HUD_EDITOR_UI_MODERNUI, "unknown", null}) {
                        HudConfig.hudEditorUi = choice;
                        for (String mc : new String[]{"1.20.1", "1.21.1", "1.21.10", "1.21.11",
                                "26.1.2", "26.2", "26.3"}) {
                            for (String loader : mc.equals("1.20.1")
                                    ? new String[]{"fabric", "forge"} : new String[]{"fabric", "neoforge"}) {
                                assertFalse(MuoniumConfigDownload.ensureDownloaded(mc, loader), mc + "/" + loader);
                            }
                        }
                        assertFalse(MuoniumConfigDownload.shouldRetry(), choice);
                        assertFalse(MuoniumConfigDownload.tick(), choice);
                        assertFalse(MuoniumConfigDownload.isRestartRequired(), choice);
                    }
                }
            }
            assertEquals(MuoniumConfigDownload.Phase.IDLE, MuoniumConfigDownload.state().phase());
            assertNull(MuoniumConfigDownload.fileName());
            assertEquals("", MuoniumConfigDownload.targetVersion());
            assertFalse(MuoniumConfigDownload.noticeVisible());
            assertEquals(java.util.List.of(), MuoniumConfigDownload.noticeLines());
        } finally {
            HudConfig.hudEditorUi = previous;
            HudInitialSelection.setOneConfigInstalled(previouslyInstalled);
            if (previousProperty == null) System.clearProperty(property);
            else System.setProperty(property, previousProperty);
        }
    }
}
