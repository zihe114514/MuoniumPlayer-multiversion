package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MuoniumConfigSelectionTest {
    @Test
    void selectedBuiltinSurvivesConfigurationReload() throws Exception {
        java.lang.reflect.Method normalize = HudConfig.class.getDeclaredMethod("normalizeHudEditorUi", String.class);
        normalize.setAccessible(true);
        assertEquals(HudConfig.HUD_EDITOR_UI_BUILTIN, normalize.invoke(null, "builtin"));
        assertEquals(HudConfig.HUD_EDITOR_UI_ONECONFIG, normalize.invoke(null, "oneconfig"));
        assertEquals(HudConfig.HUD_EDITOR_UI_MODERNUI, normalize.invoke(null, "modernui"));
        assertEquals(HudConfig.HUD_EDITOR_UI_MCONFIG, normalize.invoke(null, "mconfig"));
    }

    @Test
    void otherInterfacesDoNotStartDownloadOrRetry() {
        String previous = HudConfig.hudEditorUi;
        try {
            for (String choice : new String[]{HudConfig.HUD_EDITOR_UI_BUILTIN,
                    HudConfig.HUD_EDITOR_UI_ONECONFIG, HudConfig.HUD_EDITOR_UI_MODERNUI}) {
                HudConfig.hudEditorUi = choice;
                assertFalse(MuoniumConfigDownload.ensureDownloaded("1.20.1", "forge"), choice);
                assertFalse(MuoniumConfigDownload.shouldRetry(), choice);
                assertFalse(MuoniumConfigDownload.tick(), choice);
            }
        } finally {
            HudConfig.hudEditorUi = previous;
        }
    }
}
