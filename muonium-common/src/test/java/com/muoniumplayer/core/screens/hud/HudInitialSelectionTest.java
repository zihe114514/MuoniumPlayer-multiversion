package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudInitialSelectionTest {


    @Test
    void defaultsToMuoniumConfigOrBuiltinWhenOneConfigIsInstalled() {
        assertEquals(HudConfig.HUD_EDITOR_UI_MCONFIG,
                HudInitialSelection.choose(false, false, HudConfig.HUD_EDITOR_UI_MCONFIG));
        assertEquals(HudConfig.HUD_EDITOR_UI_MCONFIG,
                HudInitialSelection.choose(true, false, HudConfig.HUD_EDITOR_UI_MCONFIG));
        assertEquals(HudConfig.HUD_EDITOR_UI_BUILTIN,
                HudInitialSelection.choose(true, true, HudConfig.HUD_EDITOR_UI_MCONFIG));
        assertEquals(HudConfig.HUD_EDITOR_UI_BUILTIN,
                HudInitialSelection.choose(true, true, HudConfig.HUD_EDITOR_UI_BUILTIN));
    }

    @Test
    void downloadGateWaitsForDetectionAndRespectsSelection() {
        assertFalse(HudInitialSelection.wantsMuoniumConfig(true, true));
        assertFalse(HudInitialSelection.wantsMuoniumConfig(false, false));
        assertTrue(HudInitialSelection.wantsMuoniumConfig(false, true));
    }

    @Test
    void installedOneConfigPreventsMuoniumConfigDownloadEvenWhenSelected() {
        String previous = HudConfig.hudEditorUi;
        try {
            HudInitialSelection.setOneConfigInstalled(true);
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_MCONFIG;
            assertFalse(MuoniumConfigDownload.ensureDownloaded("26.3", "fabric"));
            assertFalse(MuoniumConfigDownload.shouldRetry());
        } finally {
            HudConfig.hudEditorUi = previous;
            HudInitialSelection.setOneConfigInstalled(false);
        }
    }

    @Test
    void initializeDoesNotSwitchWhenOneConfigIsNotInstalled() {
        String previous = HudConfig.hudEditorUi;
        try {
            HudInitialSelection.setOneConfigInstalled(false);
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_MCONFIG;
            HudInitialSelection.initialize();
            assertEquals(HudConfig.HUD_EDITOR_UI_MCONFIG, HudConfig.hudEditorUi);
        } finally {
            HudConfig.hudEditorUi = previous;
        }
    }

    @Test
    void initializeDoesNotOverrideExplicitBuiltinChoice() {
        String previous = HudConfig.hudEditorUi;
        try {
            HudInitialSelection.setOneConfigInstalled(true);
            HudConfig.hudEditorUi = HudConfig.HUD_EDITOR_UI_BUILTIN;

            HudInitialSelection.initialize();

            assertEquals(HudConfig.HUD_EDITOR_UI_BUILTIN, HudConfig.hudEditorUi);
        } finally {
            HudConfig.hudEditorUi = previous;
            HudInitialSelection.setOneConfigInstalled(false);
        }
    }

    @Test
    void switchNoticeUsesExistingDownloadNoticeFacade() throws Exception {
        java.lang.reflect.Field pending =
                HudInitialSelection.class.getDeclaredField("noticePending");
        java.lang.reflect.Field shownAt =
                HudInitialSelection.class.getDeclaredField("noticeShownAt");
        pending.setAccessible(true);
        shownAt.setAccessible(true);
        boolean previousPending = pending.getBoolean(null);
        long previousShownAt = shownAt.getLong(null);
        try {
            pending.setBoolean(null, true);
            shownAt.setLong(null, 0L);
            assertTrue(MuoniumConfigDownload.noticeVisible());
            assertTrue(String.join(" ", MuoniumConfigDownload.noticeLines()).contains("OneConfig"));
            assertTrue(String.join(" ", MuoniumConfigDownload.noticeLines()).contains("内置"));
            shownAt.setLong(null, System.nanoTime() - 11_000_000_000L);
            assertFalse(HudInitialSelection.noticeVisible());
        } finally {
            pending.setBoolean(null, previousPending);
            shownAt.setLong(null, previousShownAt);
        }
    }
}
