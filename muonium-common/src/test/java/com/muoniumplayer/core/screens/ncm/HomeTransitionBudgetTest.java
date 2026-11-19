package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class HomeTransitionBudgetTest {
    @Test
    void callbacksOnlyPublishAndHostOwnsLayoutAndCardBudget() throws Exception {
        String home = Files.readString(ProjectPaths.sourceFile(Paths.get(
                "com/muoniumplayer/core/screens/ncm/panels/HomePanel.java")));
        assertTrue(home.contains("private void publishCallback(UiTicket ticket, Runnable callback)"));
        String callbacks = home.substring(home.indexOf("private void publishCallback("), home.indexOf("private void requestLayout("));
        assertTrue(callbacks.contains("runOnMainThread") && callbacks.contains("getContentHostGeneration()"));
        assertFalse(callbacks.contains("layout(") || callbacks.contains("buildPendingCards("));
        String pump = home.substring(home.indexOf("private void pumpPendingUiInternal()"), home.indexOf("private static final class UiTicket"));
        assertTrue(pump.contains("isContentPanelActive(this)"));
        assertTrue(pump.contains("if (screen.isContentLayoutReady()) applyPendingLayout();"));
        assertEquals(1, pump.split("buildPendingCards\\(\\)", -1).length - 1);
        assertTrue(home.contains("|| !NCMScreen.getInstance().isContentLayoutReady() ? 1 : CARD_BUILD_BATCH"));
        String layout = home.substring(home.indexOf("private void layout(List<PlayList>"), home.indexOf("private void refreshGridLayout()"));
        assertFalse(layout.contains("buildPendingCards();"), "layout cannot consume a second card batch");
    }

    @Test
    void closeInvalidatesUiButDoesNotStopSharedRecommendationCache() throws Exception {
        String home = Files.readString(ProjectPaths.sourceFile(Paths.get(
                "com/muoniumplayer/core/screens/ncm/panels/HomePanel.java")));
        String closed = home.substring(home.indexOf("public void onHostClosed()"), home.indexOf("public void onHostOpened()"));
        assertTrue(closed.contains("this.pendingUi.close()"));
        assertTrue(closed.contains("this.pendingCards = null"));
        assertFalse(closed.contains("neteaseHomeComplete") || closed.contains("qqHomePlaylists ="));
        assertTrue(home.contains("if (this.hostClosed) this.onInit();"));
        assertTrue(home.contains("ticket.hostGeneration == hostGeneration"));
        assertTrue(home.contains("showInitialPlaceholder();"));
    }
}
