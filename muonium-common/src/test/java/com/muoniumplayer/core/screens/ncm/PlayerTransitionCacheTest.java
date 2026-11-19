package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransitionCacheTest {
    @Test
    void captureHasPixelAndTimeBounds() {
        PlayerTransitionCachePolicy policy = new PlayerTransitionCachePolicy();
        assertTrue(policy.canCapture(1_000_000_000L, 1920, 1080));
        assertFalse(policy.canCapture(1_000_000_000L, 8192, 8192));
        assertFalse(policy.canCapture(1_000_000_000L, 0, 100));
        policy.captured(1_000_000_000L);
        assertFalse(policy.canCapture(1_100_000_000L, 1920, 1080));
        assertTrue(policy.canCapture(1_250_000_000L, 1920, 1080));
        assertTrue(policy.isFresh(1_750_000_000L));
        assertFalse(policy.isFresh(1_750_000_001L));
        assertFalse(policy.isFresh(999_999_999L));
        policy.reset();
        assertFalse(policy.isFresh(1_000_000_000L));
    }

    @Test
    void worldFrameRateAloneDoesNotStripSoftening() {
        PlayerTransitionCachePolicy policy = new PlayerTransitionCachePolicy();
        assertTrue(policy.canSoften(1_000_000L, 60.0));
        policy.observeComposite(3_000_000L);
        assertFalse(policy.canSoften(1_000_000L, 60.0));
        assertTrue(policy.canSoften(1_000_000L, 16.0));
        assertFalse(policy.canSoften(PlayerTransitionCachePolicy.MAX_PIXELS, 16.0));
    }

    @Test
    void coldOpenDoesNotBuildTheTreeBeforeStartingMotion() throws Exception {
        String screen = read("core/screens/ncm/NCMScreen.java");
        String init = region(screen, "public void initGui()", "public void markDirty()");
        assertFalse(init.contains("this.checkDirty()"));
        assertFalse(init.contains("this.layout()"));
        assertFalse(init.contains("new HomePanel()"));
        assertTrue(init.contains("PlayerIslandTransition.open(HudConfig.playerFadeSpeed)"));
        String prepare = region(screen, "private void advanceInitialLayout()", "private float initialContentReveal()");
        assertTrue(prepare.contains("++this.initialSurfaceFrames <= 1"));
        assertTrue(prepare.contains("switch (this.initialLayoutStage)"));
    }

    @Test
    void cacheOnlyCapturesSettledContentAndLeavesControlsLive() throws Exception {
        String screen = read("core/screens/ncm/NCMScreen.java");
        int draw = screen.indexOf("this.transitionContent.draw(");
        int capture = screen.indexOf("this.transitionContent.capture(");
        int controls = screen.indexOf("this.controlsBar.renderWidget(");
        assertTrue(draw >= 0 && capture > draw && controls > capture);
        assertTrue(screen.substring(draw, controls).contains("!motionRunning && this.isContentLayoutReady()"));
        assertTrue(screen.contains("((HomePanel) this.currentPanel).pumpPendingUi();"));
        assertTrue(screen.contains("this.transitionContent.clear();"));
        String cache = read("core/screens/ncm/PlayerTransitionContentCache.java");
        assertFalse(cache.contains("readPixels("));
        assertFalse(cache.contains("saveLayer("));
        assertFalse(cache.contains("renderWidget("));
        assertTrue(cache.contains("this.source.close()") && cache.contains("this.image.close()"));
        assertTrue(cache.contains("SkijaHost.snapshotContent("));
        assertTrue(cache.contains("this.effect != null"));
        assertTrue(cache.contains("this.generation == contentGeneration"));
        assertTrue(cache.contains("try (Image hostImage = SkijaHost.snapshotContent("));
        assertTrue(cache.contains("this.snapshotSurface.makeImageSnapshot()"));
        assertTrue(cache.contains("this.captureTranslateX - this.left"));
        assertTrue(screen.contains("this.currentPanel instanceof HomePanel")
                && screen.contains("isTransitionSnapshotReady()"));
        assertTrue(screen.contains("if (reopening) this.initialSurfaceFrames = 0;"));
        assertTrue(screen.contains("prewarmStage = Math.max(prewarmStage, 4)"));
        assertTrue(screen.contains("this.currentPanel != null && !this.contentHitStateStale"));
    }

    private static String read(String relative) throws Exception {
        Path path = ProjectPaths.sourceFile(Paths.get("com/muoniumplayer", relative));
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String region(String text, String start, String end) {
        int begin = text.indexOf(start);
        assertTrue(begin >= 0);
        int until = text.indexOf(end, begin + start.length());
        assertTrue(until > begin);
        return text.substring(begin, until);
    }
}
