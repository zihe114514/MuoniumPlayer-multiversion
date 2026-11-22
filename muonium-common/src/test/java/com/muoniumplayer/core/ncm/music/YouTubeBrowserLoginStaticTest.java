package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class YouTubeBrowserLoginStaticTest {

    @Test
    @DisplayName("browser handoff keeps only youtube.com cookies")
    void keepsOnlyYouTubeCookieDomains() throws Exception {
        String packet = "{\"id\":1,\"result\":{\"cookies\":["
                + "{\"name\":\"SID\",\"value\":\"secret\",\"domain\":\".youtube.com\"},"
                + "{\"name\":\"SAPISID\",\"value\":\"music\",\"domain\":\"music.youtube.com\"},"
                + "{\"name\":\"SID\",\"value\":\"other\",\"domain\":\"youtube.com.attacker.test\"},"
                + "{\"name\":\"SID\",\"value\":\"google\",\"domain\":\".google.com\"}]}}";

        assertEquals("SID=secret; SAPISID=music", YouTubeBrowserLogin.cookieHeaderFromDevtools(
                JsonParser.parseString(packet).getAsJsonObject()));
    }

    @Test
    @DisplayName("browser protocol errors never become cookie headers")
    void rejectsProtocolError() {
        assertThrows(IOException.class, () -> YouTubeBrowserLogin.cookieHeaderFromDevtools(
                JsonParser.parseString("{\"id\":1,\"error\":{\"code\":-32000}}")
                        .getAsJsonObject()));
    }

    @Test
    @DisplayName("installed Chromium can return cookies over the isolated local DevTools endpoint")
    void isolatedBrowserDevtoolsRoundTrip() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("MUONIUM_TEST_BROWSER_LOGIN")),
                "Set MUONIUM_TEST_BROWSER_LOGIN=1 to run the local browser integration check");
        Path browser = YouTubeBrowserLogin.findBrowser();
        Assumptions.assumeTrue(browser != null, "No supported Chromium browser is installed");
        Path profile = Files.createTempDirectory("muonium-youtube-login-test-");
        try {
            assertEquals("", YouTubeBrowserLogin.readYouTubeCookies(browser, profile));
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(profile)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        }
    }
}
