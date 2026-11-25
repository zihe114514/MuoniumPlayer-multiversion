package com.muoniumplayer.core.audio.ffmpeg;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegNetworkInputTest {
    @TempDir Path temp;
    @BeforeAll static void nativeRequired() throws Exception { FfmpegAudioDecoderTest.requireNative(); }

    private static void requireExplicitCaFileBackend() throws Exception {
        // Schannel uses Windows system trust and has no client ca_file path. Ordinary
        // trusted HTTPS/HLS is checked by the native runtime smoke; do not edit OS trust.
        org.junit.jupiter.api.Assumptions.assumeFalse(NativeCapabilities.query().buildConfigurations()
                .get("avformat").contains("--enable-schannel"), "Local CA-file fixture requires the OpenSSL backend");
    }

    private static void send(HttpExchange request, String type, byte[] data) throws IOException {
        request.getResponseHeaders().set("Content-Type", type);
        request.sendResponseHeaders(200, data.length);
        try (var out = request.getResponseBody()) { out.write(data); }
    }
    private static URI uri(HttpServer server, String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }
    private static int readAll(FfmpegAudioDecoder decoder) throws IOException {
        var block = new PcmBlock(79, decoder.info().outputChannels());
        int count, frames = 0;
        while ((count = decoder.read(block)) != -1) frames += count;
        return frames;
    }
    @Test void httpHeadersAndNativeProbeWithoutExtension() throws Exception {
        var requests = new AtomicInteger();
        var header = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio", request -> {
            requests.incrementAndGet(); header.set(request.getRequestHeaders().getFirst("X-Muonium-Test"));
            send(request, "application/octet-stream", AudioTestFixtures.pcm16(48000, 2, 1000));
        });
        server.start();
        try {
            var options = DecodeOptions.builder().header("X-Muonium-Test", "preserved").build();
            try (var decoder = FfmpegAudioDecoder.open(AudioInput.uri(uri(server, "/audio")).withOptions(options), OutputSpec.preserveSource(), new CancellationToken())) {
                assertEquals(1000, readAll(decoder));
                assertEquals("wav", decoder.info().container());
            }
            assertTrue(requests.get() > 0);
            assertEquals("preserved", header.get());
        } finally { server.stop(0); }
    }
    @Test void hlsPlaylistFetchesNestedSegmentsAndRetainsHeaders() throws Exception {
        var segments = new AtomicInteger();
        var missingHeader = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] playlist = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXTINF:1.002667,\npart0.ts\n#EXTINF:1.002667,\npart1.ts\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.US_ASCII);
        server.createContext("/radio.m3u8", request -> send(request, "application/vnd.apple.mpegurl", playlist));
        server.createContext("/part", request -> {
            segments.incrementAndGet();
            if (!"segment-auth".equals(request.getRequestHeaders().getFirst("X-Muonium-Test"))) missingHeader.incrementAndGet();
            send(request, "video/mp2t", request.getRequestURI().getPath().equals("/part0.ts") ? AudioTestFixtures.segment0() : AudioTestFixtures.segment1());
        });
        server.start();
        try {
            var options = DecodeOptions.builder().live(true).header("X-Muonium-Test", "segment-auth").build();
            try (var decoder = FfmpegAudioDecoder.open(AudioInput.uri(uri(server, "/radio.m3u8")).withOptions(options), OutputSpec.preserveSource(), new CancellationToken())) {
                assertTrue(decoder.info().container().contains("hls"));
                assertFalse(decoder.info().seekable());
                assertThrows(IOException.class, () -> decoder.seek(Duration.ZERO));
                assertTrue(readAll(decoder) >= 96000);
            }
            assertTrue(segments.get() >= 2);
            assertEquals(0, missingHeader.get());
        } finally { server.stop(0); }
    }
    @Test void blockedHttpOpenHonoursDeadlineAndCancellation() throws Exception {
        var executor = Executors.newCachedThreadPool();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new LinkedBlockingQueue<Boolean>();
        var release = new CountDownLatch(1);
        server.setExecutor(executor);
        server.createContext("/stall", request -> {
            entered.offer(true);
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            request.close();
        });
        server.start();
        try {
            var timed = DecodeOptions.builder().openTimeout(Duration.ofMillis(250)).readTimeout(Duration.ofSeconds(3)).build();
            long start = System.nanoTime();
            assertThrows(SocketTimeoutException.class, () -> FfmpegAudioDecoder.open(AudioInput.uri(uri(server, "/stall")).withOptions(timed), OutputSpec.preserveSource(), new CancellationToken()));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
            entered.clear();
            var token = new CancellationToken();
            var future = executor.submit(() -> assertThrows(InterruptedIOException.class,
                    () -> FfmpegAudioDecoder.open(AudioInput.uri(uri(server, "/stall")), OutputSpec.preserveSource(), token)));
            assertNotNull(entered.poll(3, TimeUnit.SECONDS));
            token.cancel();
            future.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }
    @Test void httpsRejectsUntrustedCertificateAndAcceptsExplicitCa() throws Exception {
        requireExplicitCaFileBackend();
        var store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(AudioTestFixtures.localhostKeyStore()), "test-only".toCharArray());
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-only".toCharArray());
        var ssl = SSLContext.getInstance("TLS"); ssl.init(keys.getKeyManagers(), null, null);
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/audio", request -> send(request, "audio/wav", AudioTestFixtures.pcm16(48000, 1, 200)));
        server.start();
        try {
            URI address = URI.create("https://localhost:" + server.getAddress().getPort() + "/audio");
            assertThrows(IOException.class, () -> FfmpegAudioDecoder.open(AudioInput.uri(address), OutputSpec.preserveSource(), new CancellationToken()));
            var cert = store.getCertificate("local-fixture");
            Path ca = temp.resolve("test-only-ca.pem");
            Files.writeString(ca, "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(cert.getEncoded()) + "\n-----END CERTIFICATE-----\n", StandardCharsets.US_ASCII);
            var options = DecodeOptions.builder().caFile(ca).build();
            try (var decoder = FfmpegAudioDecoder.open(AudioInput.uri(address).withOptions(options), OutputSpec.preserveSource(), new CancellationToken())) {
                assertEquals(200, readAll(decoder));
            }
        } finally { server.stop(0); }
    }
    @Test void crossThreadCloseInterruptsBlockedReadBeforeFreeingNativeObjects() throws Exception {
        var executor = Executors.newCachedThreadPool();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var releaseServer = new CountDownLatch(1);
        var sourceConsumed = new CountDownLatch(1);
        var decoderRef = new AtomicReference<FfmpegAudioDecoder>();
        server.setExecutor(executor);
        server.createContext("/endless.wav", request -> {
            byte[] initial = AudioTestFixtures.pcm16(48000, 1, 48000);
            java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(initial).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            header.putInt(4, 960000036).putInt(40, 960000000);
            request.getResponseHeaders().set("Content-Type", "audio/wav");
            request.sendResponseHeaders(200, 0);
            try (var out = request.getResponseBody()) {
                out.write(initial); out.flush();
                try { releaseServer.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try {
            var options = DecodeOptions.builder().live(true).readTimeout(Duration.ofSeconds(10)).option("analyzeduration", "100000").option("probesize", "4096").option("max_probe_packets", "1").option("seekable", "0").build();
            var future = executor.submit(() -> {
                try (var decoder = FfmpegAudioDecoder.open(AudioInput.uri(uri(server, "/endless.wav")).withOptions(options), OutputSpec.preserveSource(), new CancellationToken())) {
                    decoderRef.set(decoder);
                    var block = new PcmBlock(1000, 1);
                    int frames = 0, count;
                    while ((count = decoder.read(block)) != -1) {
                        frames += count;
                        if (frames >= 32768) sourceConsumed.countDown();
                    }
                    return null;
                } catch (IOException e) { return e; }
            });
            assertTrue(sourceConsumed.await(4, TimeUnit.SECONDS), "worker should decode at least one complete native packet before close");
            long start = System.nanoTime();
            decoderRef.get().close();
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3));
            assertInstanceOf(InterruptedIOException.class, future.get(3, TimeUnit.SECONDS));
        } finally {
            releaseServer.countDown();
            var decoder = decoderRef.get(); if (decoder != null) decoder.close();
            server.stop(0); executor.shutdownNow();
        }
    }
    @Test void httpsHlsPropagatesCaAndHeadersToNestedPlaylistAesKeyAndSegments() throws Exception {
        requireExplicitCaFileBackend();
        var store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(AudioTestFixtures.localhostKeyStore()), "test-only".toCharArray());
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-only".toCharArray());
        var ssl = SSLContext.getInstance("TLS"); ssl.init(keys.getKeyManagers(), null, null);
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        var hits = new AtomicInteger();
        var unauthenticated = new AtomicInteger();
        byte[] master = ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nvariant.m3u8\n").getBytes(StandardCharsets.US_ASCII);
        byte[] media = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:1.002667,\npart0.ts\n"
                + "#EXTINF:1.002667,\npart1.ts\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.US_ASCII);
        var encrypted = new byte[2][];
        for (int i = 0; i < 2; i++) {
            byte[] iv = new byte[16]; iv[15] = (byte) i;
            var cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
            encrypted[i] = cipher.doFinal(i == 0 ? AudioTestFixtures.segment0() : AudioTestFixtures.segment1());
        }
        server.createContext("/", request -> {
            hits.incrementAndGet();
            if (!"all-nested".equals(request.getRequestHeaders().getFirst("X-Muonium-Test"))) unauthenticated.incrementAndGet();
            switch (request.getRequestURI().getPath()) {
                case "/master.m3u8" -> send(request, "application/vnd.apple.mpegurl", master);
                case "/variant.m3u8" -> send(request, "application/vnd.apple.mpegurl", media);
                case "/key.bin" -> send(request, "application/octet-stream", key);
                case "/part0.ts" -> send(request, "video/mp2t", encrypted[0]);
                case "/part1.ts" -> send(request, "video/mp2t", encrypted[1]);
                default -> { request.sendResponseHeaders(404, -1); request.close(); }
            }
        });
        server.start();
        try {
            Path ca = temp.resolve("hls-test-ca.pem");
            Files.writeString(ca, "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(store.getCertificate("local-fixture").getEncoded()) + "\n-----END CERTIFICATE-----\n", StandardCharsets.US_ASCII);
            var options = DecodeOptions.builder().caFile(ca).header("X-Muonium-Test", "all-nested").live(true).build();
            var address = URI.create("https://localhost:" + server.getAddress().getPort() + "/master.m3u8");
            try (var decoder = FfmpegAudioDecoder.open(AudioInput.uri(address).withOptions(options), OutputSpec.preserveSource(), new CancellationToken())) {
                assertTrue(readAll(decoder) >= 96000);
            }
            assertTrue(hits.get() >= 5, "master, variant, key and both segments must all be fetched");
            assertEquals(0, unauthenticated.get());
        } finally { server.stop(0); }
    }
}
