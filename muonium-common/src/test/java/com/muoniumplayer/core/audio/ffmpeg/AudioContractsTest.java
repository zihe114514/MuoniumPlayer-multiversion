package com.muoniumplayer.core.audio.ffmpeg;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AudioContractsTest {
    @Test void validatesOptionsAndProtectsTls() {
        assertThrows(IllegalArgumentException.class, () -> DecodeOptions.builder().header("X-Test", "a\r\nb"));
        assertThrows(IllegalArgumentException.class, () -> DecodeOptions.builder().header("bad:name", "b"));
        assertThrows(IllegalArgumentException.class, () -> DecodeOptions.builder().option("tls_verify", "0"));
        assertThrows(IllegalArgumentException.class, () -> DecodeOptions.builder().option("headers", "x: y"));
        assertThrows(IllegalArgumentException.class, () -> DecodeOptions.builder().readTimeout(Duration.ZERO));
        var options = DecodeOptions.builder().header("Authorization", "test-only").caFile(Path.of("local.pem"))
                .openTimeout(Duration.ofMillis(300)).live(true).build();
        assertTrue(options.live());
        assertEquals("test-only", options.headers().get("Authorization"));
        assertThrows(UnsupportedOperationException.class, () -> options.headers().clear());
    }
    @Test void validatesInputAndHidesCredentials() {
        assertThrows(IllegalArgumentException.class, () -> AudioInput.uri(URI.create("ftp://host/file")));
        assertThrows(IllegalArgumentException.class, () -> AudioInput.uri(URI.create("relative.m3u8")));
        assertThrows(IllegalArgumentException.class, () -> AudioInput.uri(URI.create("https://host/a#fragment")));
        var input = AudioInput.uri(URI.create("https://user:pass@host/a?token=secret"));
        assertFalse(input.toString().contains("secret"));
        assertFalse(input.toString().contains("pass"));
        assertTrue(input.network());
        assertFalse(AudioInput.file(Path.of("track.flac")).network());
    }
    @Test void pcmBoundsAndOutputContract() {
        assertThrows(IllegalArgumentException.class, () -> new PcmBlock(0, 2));
        assertThrows(IllegalArgumentException.class, () -> new PcmBlock(4096, 9));
        assertThrows(IllegalArgumentException.class, () -> new PcmBlock(PcmBlock.MAX_FRAMES + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> OutputSpec.mono(-1));
        var block = new PcmBlock(3, 6);
        assertEquals(18, block.samples().length);
        assertEquals(0, block.frames());
        assertEquals(3, block.capacityFrames());
    }
    @Test void androidDetectionDoesNotDependOnDalvik() throws Exception {
        assertEquals("android-arm64", FfmpegRuntime.detectPlatform("Linux", "aarch64", Map.of("ANDROID_ROOT", "/system"), false));
        assertEquals("android-x86_64", FfmpegRuntime.detectPlatform("Linux", "amd64", Map.of(), true));
        assertEquals("linux-arm64", FfmpegRuntime.detectPlatform("Linux", "aarch64", Map.of(), false));
        assertEquals("macosx-arm64", FfmpegRuntime.detectPlatform("Mac OS X", "aarch64", Map.of(), false));
        assertEquals("windows-x86_64", FfmpegRuntime.detectPlatform("Windows 11", "amd64", Map.of(), false));
    }
    @Test void cancellationAndThreadInterruptionAreOneWay() throws Exception {
        var token = new CancellationToken();
        token.cancel();
        assertTrue(token.isCancelled());
        assertThrows(java.io.InterruptedIOException.class, token::throwIfCancelled);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, new CancellationToken()::throwIfCancelled);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
