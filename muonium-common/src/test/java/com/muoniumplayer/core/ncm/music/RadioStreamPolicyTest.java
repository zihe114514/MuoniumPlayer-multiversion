package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.audio.ffmpeg.AudioInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RadioStreamPolicyTest {
    @TempDir Path cache;

    @Test void httpAndHlsUseLiveUriWithHeadersTimeoutsAndExplicitTrust() throws Exception {
        for (String value : new String[]{"http://radio.test/live", "https://radio.test/list.m3u8?signature=kept"}) {
            Path ca = cache.resolve("trusted.pem");
            AudioInput input = RadioStreamPolicy.input(URI.create(value), ca);
            assertTrue(input.network()); assertTrue(input.options().live());
            assertEquals(Duration.ofSeconds(15), input.options().openTimeout());
            assertEquals(Duration.ofSeconds(20), input.options().readTimeout());
            assertEquals(RadioStreamPolicy.USER_AGENT, input.options().headers().get("User-Agent"));
            assertEquals("0", input.options().headers().get("Icy-MetaData"));
            assertEquals("0", input.options().options().get("icy"));
            assertEquals("0", input.options().options().get("reconnect"));
            assertEquals(ca.toAbsolutePath().toString(), input.options().options().get("ca_file"));
            assertFalse(input.toString().contains("signature"));
        }
        try (var files = Files.list(cache)) { assertEquals(0, files.count(), "input preparation cannot spool audio or playlists"); }
    }

    @Test void radioFormatIsExplicitStereoFloatTargetAndPcmBlockIsBounded() {
        assertEquals(48000, RadioStreamPolicy.output().sampleRate());
        assertEquals("stereo", RadioStreamPolicy.output().channelLayout());
        assertEquals(4096, RadioStreamPolicy.BLOCK_FRAMES);
    }

    @Test void invalidAndUnrelatedSchemesAreRejectedWithoutEchoingSecrets() {
        for (String value : new String[]{"", "file:///tmp/live.m3u8", "ftp://radio.test/live", "rtsp://radio.test/live",
                "https://user:secret@radio.test/live", "https://radio.test/live#secret", "https://radio.test/ secret"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> RadioStreamPolicy.uri(value));
            assertFalse(failure.getMessage().contains("secret"));
        }
    }

    @Test void channelOfflineClassificationIsBoundedAndFailureMessagesAreRedacted() {
        assertTrue(RadioStreamPolicy.channelGone(new IOException("Server returned 404 Not Found")));
        assertTrue(RadioStreamPolicy.channelGone(new IOException("HTTP 410")));
        assertFalse(RadioStreamPolicy.channelGone(new IOException("native code -1234045")));
        assertFalse(RadioStreamPolicy.channelGone(new IOException("connection to https://radio.test/404 failed")));
        assertFalse(RadioStreamPolicy.failureReason(new IOException("https://radio.test/?token=secret")).contains("secret"));
        assertEquals(0f, RadioStreamPolicy.volume(Float.NaN));
        assertEquals(0f, RadioStreamPolicy.volume(Float.POSITIVE_INFINITY));
        assertEquals(1f, RadioStreamPolicy.volume(2f)); assertEquals(0f, RadioStreamPolicy.volume(-2f));
    }
}
