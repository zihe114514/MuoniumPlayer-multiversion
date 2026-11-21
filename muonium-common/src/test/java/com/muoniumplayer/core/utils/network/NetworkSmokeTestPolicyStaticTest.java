package com.muoniumplayer.core.utils.network;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-network smoke tests stay opt-in on every Fabric lane. */
class NetworkSmokeTestPolicyStaticTest {
    private static final String[] LANES = {
            "1.20.1 recode", "1.21.1", "1.21.10", "1.21.11 recode",
            "26.1.2", "26.2", "26.3"
    };

    @Test
    void networkSmokeIsExcludedByDefaultAndExplicitlyOptedIntoOnEveryFabricLane() throws IOException {
        for (String lane : LANES) {
            Path buildFile = ProjectPaths.repoRoot().resolve(lane).resolve("fabric/build.gradle");
            String build = Files.readString(buildFile, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String testBlock = methodBody(build, "test {\n    useJUnitPlatform {");
            assertTrue(testBlock.contains("providers.gradleProperty('muonium.includeNetworkSmoke')"), lane);
            assertTrue(testBlock.contains("getOrElse(false)"), lane + " must default to offline-safe tests");
            assertTrue(testBlock.contains("excludeTags 'network-smoke'"), lane);
        }

        Path smokeFile = ProjectPaths.repoRoot().resolve(
                "muonium-common/src/test/java/com/muoniumplayer/core/utils/network/HttpDownloadRaceSmokeTest.java");
        String smoke = Files.readString(smokeFile, StandardCharsets.UTF_8);
        assertTrue(smoke.contains("@Tag(\"network-smoke\")"));
        assertTrue(smoke.contains("elapsed < 120_000"), "keep the original network performance assertion");
        assertTrue(smoke.contains("ZSTD_BYTES, ZSTD_SHA256, target"), "keep byte-count and SHA-256 validation");
        assertTrue(smoke.contains("assertEquals(ZSTD_BYTES, target.length()"), "keep the downloaded file size assertion");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char value = source.charAt(at);
            if (value == '{') depth++;
            else if (value == '}' && --depth == 0) return source.substring(open, at + 1);
        }
        throw new AssertionError("unbalanced test configuration");
    }
}
