package com.muoniumplayer.render.skija;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the live-world default framebuffer read-buffer fix. */
class GlassBackdropCaptureStaticTest {
    @Test
    void captureSelectsTheCorrectReadAndDrawBuffersAndRestoresThem() throws Exception {
        Path path = ProjectPaths.sharedJava().resolve(Paths.get("com", "muoniumplayer", "render", "skija", "GlassBackdrop.java"));
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(source.contains("GL11.GL_READ_BUFFER"), "capture must snapshot the read buffer");
        assertTrue(source.contains("sourceFramebuffer == 0 ? GL11.GL_BACK"),
                "the default framebuffer must explicitly read GL_BACK during live gameplay");
        assertTrue(source.contains("GL30.GL_COLOR_ATTACHMENT0"),
                "offscreen capture target must explicitly select color attachment 0");
        assertTrue(source.contains("GL11.glReadBuffer(previousReadBuffer)"),
                "Minecraft's read-buffer state must be restored after the blit");
        assertTrue(source.contains("GL11.glDrawBuffer(previousDrawBuffer)"),
                "Minecraft's draw-buffer state must be restored after the blit");
    }
}
