package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HudInitialSelectionTest {
    @Test
    void legacyChoicesUseBuiltinRegardlessOfOneConfigDetection() {
        for (boolean ready : new boolean[]{false, true}) {
            for (boolean installed : new boolean[]{false, true}) {
                for (String choice : new String[]{"builtin", "mconfig", "oneconfig",
                        "MCONFIG", "ONECONFIG", "unknown", "", null}) {
                    assertEquals(HudConfig.HUD_EDITOR_UI_BUILTIN,
                            HudInitialSelection.choose(ready, installed, choice), choice);
                }
                assertEquals(HudConfig.HUD_EDITOR_UI_MODERNUI,
                        HudInitialSelection.choose(ready, installed, "modernui"));
                assertEquals(HudConfig.HUD_EDITOR_UI_MODERNUI,
                        HudInitialSelection.choose(ready, installed, "MODERNUI"));
            }
        }
    }

    @Test
    void independentConfigDownloadIsNeverRequested() {
        for (boolean installed : new boolean[]{false, true}) {
            for (boolean selected : new boolean[]{false, true}) {
                assertFalse(HudInitialSelection.wantsMuoniumConfig(installed, selected));
            }
        }
    }

    @Test
    void initializePreservesBuiltinAndModernUiWithOrWithoutOneConfig() {
        String previous = HudConfig.hudEditorUi;
        boolean previouslyInstalled = HudInitialSelection.isOneConfigInstalled();
        try {
            for (boolean installed : new boolean[]{false, true}) {
                HudInitialSelection.setOneConfigInstalled(installed);
                for (String choice : new String[]{HudConfig.HUD_EDITOR_UI_BUILTIN,
                        HudConfig.HUD_EDITOR_UI_MODERNUI}) {
                    HudConfig.hudEditorUi = choice;
                    HudInitialSelection.initialize();
                    assertEquals(choice, HudConfig.hudEditorUi);
                    assertFalse(HudInitialSelection.noticeVisible());
                }
            }
        } finally {
            HudConfig.hudEditorUi = previous;
            HudInitialSelection.setOneConfigInstalled(previouslyInstalled);
        }
    }
}
