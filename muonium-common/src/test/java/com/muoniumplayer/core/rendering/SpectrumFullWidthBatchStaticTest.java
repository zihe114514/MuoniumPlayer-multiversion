package com.muoniumplayer.core.rendering;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures higher selectable densities stay a single GPU path submission. */
class SpectrumFullWidthBatchStaticTest {
    @Test
    void allBarsArePackedIntoOnePathAndDrawnOnce() throws Exception {
        Path sourcePath = ProjectPaths.sharedJava().resolve(Path.of("com", "muoniumplayer", "core",
                "rendering", "SpectrumFullWidth.java"));
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        int loopStart = source.indexOf("for(int index=0;index<count;index++)");
        int pathSnapshot = source.indexOf("try(Path path=builder.snapshot())", loopStart);
        assertTrue(loopStart >= 0 && pathSnapshot > loopStart, "bar builder loop or path submission changed");
        String barLoop = source.substring(loopStart, pathSnapshot);
        assertTrue(barLoop.contains("builder.addRRect("), "bars must be accumulated into a shared path");
        assertFalse(barLoop.contains("canvas.draw"), "never submit one GPU draw per bar");
        assertEquals(1, occurrences(source, "canvas.drawPath(path,paint)"),
                "the full-width spectrum should submit its completed batch once");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = 0; (at = source.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }
}
