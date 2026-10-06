package com.muoniumplayer.render.skija;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards delayed-GUI glass sampling and island compositing order on every deferred port. */
class PostCompositeOverlayStaticTest {
    private static final List<String> DELAYED_PORTS = List.of(
            "1.21.11 recode", "26.2", "26.3");

    @Test
    void baseScreenIsCompositedThenSampledBeforeIslandOverlay() throws Exception {
        Path repository = ProjectPaths.repoRoot();
        for (String port : DELAYED_PORTS) {
            Path host = repository.resolve(port).resolve(Path.of("src", "main", "java",
                    "com", "muoniumplayer", "render", "skija", "SkijaHost.java"));
            String source = Files.readString(host, StandardCharsets.UTF_8);
            int compositeFrame = source.indexOf("public static void compositeFrame()");
            int deferMethod = source.indexOf("public static boolean deferPostCompositeOverlay", compositeFrame);
            assertTrue(compositeFrame >= 0 && deferMethod > compositeFrame,
                    port + ": expected frame compositor and deferred overlay hook");

            String compositor = source.substring(compositeFrame, deferMethod);
            int consumeQueuedOverlay = compositor.indexOf("Runnable topOverlay = postCompositeOverlay;");
            int clearQueuedOverlay = compositor.indexOf("postCompositeOverlay = null;", consumeQueuedOverlay);
            int captureBase = compositor.indexOf("GlassBackdrop.captureNow(0, window.getWidth(), window.getHeight(),");
            int captureTopmost = compositor.indexOf("GlassBackdrop.captureTopmostNow(0, window.getWidth(), window.getHeight());");
            int drawIsland = compositor.indexOf("renderPostCompositeOverlay(topOverlay)");
            assertTrue(consumeQueuedOverlay >= 0 && clearQueuedOverlay > consumeQueuedOverlay,
                    port + ": queued overlay must be consumed exactly at frame composition");
            assertTrue(captureBase >= 0 && captureTopmost > captureBase && drawIsland > captureTopmost,
                    port + ": sample the completed base GUI before drawing the island over it");

            int defer = source.indexOf("public static boolean deferPostCompositeOverlay");
            int deferEnd = source.indexOf("private static void renderPostCompositeOverlay", defer);
            String deferBody = source.substring(defer, deferEnd);
            assertTrue(deferBody.contains("postCompositeOverlay = overlay;")
                            && deferBody.contains("return true;"),
                    port + ": deferred render request must be retained until the composite hook");
        }
    }
}
