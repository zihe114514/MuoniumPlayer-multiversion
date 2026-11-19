package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MuoniumFontBridgeStaticTest {
    private static String source(String relative) throws IOException {
        return Files.readString(ProjectPaths.sourceFile(relative), StandardCharsets.UTF_8);
    }

    @Test
    void bridgeIsOptionalDynamicAndKeepsCjkFallback() throws IOException {
        String bridge = source("com/muoniumplayer/core/screens/hud/MuoniumFontBridge.java");
        assertTrue(bridge.contains("Class.forName(SOURCE_CLASS, false"), "Config must remain optional");
        assertTrue(bridge.contains("if (installedSource != null)"), "install one stable provider");
        assertTrue(bridge.contains("current.matches(next)"), "replace the provider snapshot only when bytes or identity change");
        assertTrue(bridge.contains("bumpRevision()"), "changed identity must invalidate Compose font families");
        assertTrue(bridge.contains("FontManager.builtinUiFallbackBytes()"), "provide the bundled CJK fallback face");
        assertTrue(bridge.contains("snapshot = null"), "clearing the override must release the host snapshot");
        assertTrue(bridge.contains("FontManager.uiFontBytesForBridge(false)"), "read the currently selected regular font");
        assertTrue(bridge.contains("FontManager.uiFontBytesForBridge(true)"), "read the currently selected bold font");
        assertTrue(bridge.contains("active.isBuiltin()"), "the composite builtin font uses a separate CJK fallback face");
        assertTrue(!bridge.contains("next game launch"), "font selection changes should not be deferred until restart");
        assertTrue(!bridge.contains("AtomicLong"), "avoid a second revision counter next to UiFontOverride.revision");
    }
}
