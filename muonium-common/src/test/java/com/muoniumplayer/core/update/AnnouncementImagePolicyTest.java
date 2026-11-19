package com.muoniumplayer.core.update;

import com.muoniumplayer.testsupport.ProjectPaths;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.muoniumplayer.core.update.AnnouncementImageCache.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real policy/transport/worker tests with no networking, native loading or persistent state. */
class AnnouncementImagePolicyTest {
    private static final String URL = "https://raw.githubusercontent.com/example/announcement.png";

    @Test
    void urlPolicyUsesCentralWhitelistButRejectsAmbiguousAuthoritiesAndSchemes() {
        assertNotNull(allowedUri(URL));
        assertNotNull(allowedUri("HTTPS://github.com:443/image.png?raw=1"));
        assertNotNull(allowedUri("https://cdn.jsdelivr.net/a.png"));
        for (String invalid : new String[]{"", "http://github.com/a.png", "file:///a.png", "data:image/png;base64,a",
                "https://localhost/a.png", "https://127.0.0.1/a.png", "https://[::1]/a.png",
                "https://github.com.evil.test/a.png", "https://evilgithub.com/a.png",
                "https://github.com@evil.test/a.png", "https://user@github.com/a.png",
                "https://github.com:444/a.png", "https://github.com:/a.png", "https://github.com:abc/a.png",
                "https://github.com\\@evil.test/a.png", "https://github.com/a.png#fragment",
                "https://github.com/%zz", "https://github.com/a\nb.png"}) {
            assertNull(allowedUri(invalid), invalid);
        }
        assertNull(allowedUri("https://github.com/" + "a".repeat(4096)));
    }

    @Test
    void onlyActualImageHeadersAndBoundedDimensionsProceed() throws Exception {
        assertEquals(new Header(Format.PNG, 4096, 1953), inspect(png(4096, 1953)));
        assertEquals(new Header(Format.PNG, 4000, 2000), inspect(png(4000, 2000)));
        assertEquals(new Header(Format.JPEG, 640, 480), inspect(jpeg(640, 480)));
        assertEquals(new Header(Format.WEBP, 23, 17), inspect(webp("VP8X", 23, 17)));
        assertEquals(new Header(Format.WEBP, 23, 17), inspect(webp("VP8L", 23, 17)));
        assertEquals(new Header(Format.WEBP, 23, 17), inspect(webp("VP8 ", 23, 17)));
        for (byte[] bad : new byte[][]{png(0, 10), png(10, 0), png(4097, 1), png(4000, 2001),
                png(-1, 1), jpeg(65535, 1), webp("VP8X", 4097, 1), webp("VP8L", 4000, 2001),
                "GIF89a".getBytes(StandardCharsets.US_ASCII), "<svg/>".getBytes(StandardCharsets.US_ASCII),
                "<!doctype html>".getBytes(StandardCharsets.US_ASCII), new byte[0], new byte[MAX_ENCODED_BYTES + 1]}) {
            assertThrows(IOException.class, () -> inspect(bad));
        }
    }

    @Test
    void truncatedHeadersCannotAllocateAndJpegSkipsMetadataSafely() throws Exception {
        byte[] png = png(10, 20);
        for (int length = 0; length < 33; length++) {
            byte[] truncated = java.util.Arrays.copyOf(png, length);
            assertThrows(IOException.class, () -> inspect(truncated));
        }
        byte[] jpeg = jpeg(7, 9);
        byte[] metadata = new byte[jpeg.length + 7];
        System.arraycopy(jpeg, 0, metadata, 0, 2);
        byte[] app = {(byte) 255, (byte) 225, 0, 5, 1, 2, 3};
        System.arraycopy(app, 0, metadata, 2, app.length);
        System.arraycopy(jpeg, 2, metadata, 9, jpeg.length - 2);
        assertEquals(new Header(Format.JPEG, 7, 9), inspect(metadata));
        metadata[4] = (byte) 255;
        assertThrows(IOException.class, () -> inspect(metadata));
        byte[] corruptPng = png.clone();
        corruptPng[11] = 12; // IHDR must be exactly 13 bytes.
        assertThrows(IOException.class, () -> inspect(corruptPng));
    }

    @Test
    void webpContainerBoundsAnimationAndConflictingSizesAreRejected() {
        byte[] animated = webp("VP8X", 8, 8);
        animated[20] = 2;
        assertThrows(IOException.class, () -> inspect(animated));
        byte[] invalidSize = webp("VP8L", 8, 8);
        invalidSize[16] = (byte) 255;
        assertThrows(IOException.class, () -> inspect(invalidSize));
        byte[] first = webp("VP8X", 8, 8);
        byte[] second = webp("VP8L", 9, 8);
        byte[] combined = new byte[first.length + second.length - 12];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 12, combined, first.length, second.length - 12);
        le32(combined, 4, combined.length - 8);
        assertThrows(IOException.class, () -> inspect(combined));
    }

    @Test
    void byteCapAppliesEvenToMissingOrLyingContentLength() throws Exception {
        byte[] maximum = new byte[MAX_ENCODED_BYTES];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        assertEquals(MAX_ENCODED_BYTES, readBounded(new ByteArrayInputStream(maximum), -1, deadline).length);
        assertEquals(MAX_ENCODED_BYTES, readBounded(new ByteArrayInputStream(maximum), MAX_ENCODED_BYTES, deadline).length);
        AtomicInteger reads = new AtomicInteger();
        InputStream untouched = new ByteArrayInputStream(maximum) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                reads.incrementAndGet();
                return super.read(bytes, offset, length);
            }
        };
        assertThrows(IOException.class, () -> readBounded(untouched, MAX_ENCODED_BYTES + 1L, deadline));
        assertEquals(0, reads.get());
        assertThrows(IOException.class, () -> readBounded(new ByteArrayInputStream(new byte[MAX_ENCODED_BYTES + 1]), -1, deadline));
        assertThrows(IOException.class, () -> readBounded(new ByteArrayInputStream(new byte[MAX_ENCODED_BYTES + 1]), 1, deadline));
        assertThrows(IOException.class, () -> readBounded(new ByteArrayInputStream(new byte[]{1}), 2, deadline));
        assertThrows(IOException.class, () -> readBounded(new ByteArrayInputStream(new byte[0]), -1, deadline));
        assertThrows(IOException.class, () -> readBounded(new ByteArrayInputStream(new byte[]{1}), -1, System.nanoTime() - 1));
    }

    @Test
    void everyRedirectIsValidatedBeforeOpeningAndBodiesAreNotDrained() throws Exception {
        for (String location : new String[]{"http://github.com/a.png", "file:///a.png", "https://localhost/a.png",
                "//127.0.0.1/a.png", "https://evil.test/a.png", "https://user@github.com/a.png", "https://github.com:444/a.png"}) {
            FakeConnection first = response(302, location, "text/html", new byte[MAX_ENCODED_BYTES + 1]);
            AtomicInteger opens = new AtomicInteger();
            assertThrows(IOException.class, () -> fetch(URI.create(URL), uri -> {
                opens.incrementAndGet();
                return first;
            }), location);
            assertEquals(1, opens.get(), location);
            assertFalse(first.bodyRead);
            assertTrue(first.disconnected);
            assertFalse(first.getInstanceFollowRedirects());
        }
    }

    @Test
    void httpsRelativeRedirectUsesGetAndChecksMimeMagicAndClosesConnection() throws Exception {
        byte[] pixels = png(5, 7);
        FakeConnection redirect = response(307, "../a.png?raw=1", null, new byte[0]);
        FakeConnection success = response(200, null, "image/png; charset=binary", pixels);
        List<URI> opened = new ArrayList<>();
        assertArrayEquals(pixels, fetch(URI.create(URL), uri -> {
            opened.add(uri);
            return opened.size() == 1 ? redirect : success;
        }));
        assertEquals(URI.create("https://raw.githubusercontent.com/a.png?raw=1"), opened.get(1));
        assertEquals("GET", success.getRequestMethod());
        assertEquals("identity", success.getRequestProperty("Accept-Encoding"));
        assertTrue(success.getConnectTimeout() > 0 && success.getReadTimeout() > 0);
        assertTrue(redirect.disconnected && success.disconnected && success.streamClosed);
        for (String type : new String[]{"text/html", "image/gif", "image/jpeg"}) {
            FakeConnection mismatch = response(200, null, type, pixels);
            assertThrows(IOException.class, () -> fetch(URI.create(URL), uri -> mismatch));
            assertTrue(mismatch.disconnected);
        }
        FakeConnection compressed = response(200, null, "image/png", pixels);
        compressed.encoding = "gzip";
        assertThrows(IOException.class, () -> fetch(URI.create(URL), uri -> compressed));
        assertFalse(compressed.bodyRead);
        FakeConnection error = response(404, null, "text/html", pixels);
        assertThrows(IOException.class, () -> fetch(URI.create(URL), uri -> error));
        assertFalse(error.bodyRead);
    }

    @Test
    void redirectsAreBoundedEvenWithinWhitelist() {
        AtomicInteger opens = new AtomicInteger();
        assertThrows(IOException.class, () -> fetch(URI.create(URL), uri -> {
            opens.incrementAndGet();
            return response(301, URL, null, new byte[0]);
        }));
        assertEquals(6, opens.get());
    }

    @Test
    void failedRequestIsDeduplicatedAndCannotBeRetriedByEveryFrame() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (AnnouncementImageCache cache = new AnnouncementImageCache(24, uri -> {
            attempts.incrementAndGet();
            throw new IOException("offline");
        }, (bytes, header) -> { throw new AssertionError("not reached"); })) {
            assertEquals(State.EMPTY, cache.state(URL));
            assertFalse(cache.withImage(URL, image -> fail("EMPTY must not borrow")));
            assertTimeout(Duration.ofSeconds(1), () -> cache.request(URL));
            awaitState(cache, URL, State.FAILED);
            for (int i = 0; i < 1000; i++) cache.request(URL);
            assertEquals(1, attempts.get());
            cache.request("file:///bad.png");
            cache.request("https://localhost/a.png");
            assertEquals(State.FAILED, cache.state("file:///bad.png"));
            assertEquals(State.EMPTY, cache.state(""));
            cache.close();
            cache.request(URL);
            assertEquals(State.EMPTY, cache.state(URL));
            assertEquals(1, attempts.get());
        }
    }

    @Test
    void oversizeHeaderIsRejectedBeforeAnyDecoderAndProbeHasNoNetworkFallback() throws Exception {
        AtomicInteger decodes = new AtomicInteger();
        try (AnnouncementImageCache cache = new AnnouncementImageCache(24,
                uri -> { throw new AssertionError("prime must not network"); },
                (bytes, header) -> { decodes.incrementAndGet(); throw new IOException("decoder unavailable"); })) {
            cache.prime(URL, png(4097, 1));
            awaitState(cache, URL, State.FAILED);
            assertEquals(0, decodes.get());
            String good = URL + "?good";
            cache.prime(good, png(1, 1));
            awaitState(cache, good, State.FAILED);
            assertEquals(1, decodes.get());
            cache.prime(good, png(1, 1));
            assertEquals(1, decodes.get());
            assertThrows(IllegalArgumentException.class, () -> cache.prime(URL, new byte[MAX_ENCODED_BYTES + 1]));
        }
    }

    @Test
    void twoDaemonWorkersAndBoundedQueueDoNotRunDecoderOnCallingThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> executing = java.util.Collections.synchronizedList(new ArrayList<>());
        try (AnnouncementImageCache cache = new AnnouncementImageCache(24,
                uri -> { throw new AssertionError("prime must not network"); }, (bytes, header) -> {
            executing.add(Thread.currentThread());
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("test decode");
        })) {
            for (int i = 0; i < 24; i++) cache.prime(URL + "?" + i, png(1, 1));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, executing.size());
            for (Thread thread : executing) {
                assertTrue(thread.isDaemon());
                assertNotSame(Thread.currentThread(), thread);
                assertTrue(thread.getName().startsWith("muonium-announcement-image-"));
            }
            assertTimeout(Duration.ofSeconds(1), cache::close);
            for (int i = 0; i < 24; i++) assertEquals(State.EMPTY, cache.state(URL + "?" + i));
        } finally {
            release.countDown();
        }
    }

    @Test
    void snapshotPollingUpdatesLruAndEvictionLeavesUrlRequestable() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        try (AnnouncementImageCache cache = new AnnouncementImageCache(2,
                uri -> { throw new AssertionError("prime must not network"); }, (bytes, header) -> {
            starts.incrementAndGet();
            entered.countDown();
            boolean waiting = true;
            while (waiting) {
                try { release.await(); waiting = false; }
                catch (InterruptedException ignored) { /* Deliberately late decoder. */ }
            }
            throw new IOException("test end");
        })) {
            cache.prime(URL + "?a", png(1, 1));
            cache.prime(URL + "?b", png(1, 1));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(State.LOADING, cache.peek(URL + "?a").state()); // a is MRU.
            cache.prime(URL + "?c", png(1, 1));
            assertEquals(State.EMPTY, cache.state(URL + "?b"));
            assertEquals(State.LOADING, cache.state(URL + "?a"));
            assertEquals(State.LOADING, cache.state(URL + "?c"));
            cache.prime(URL + "?b", png(1, 1));
            assertEquals(State.LOADING, cache.state(URL + "?b"));
            assertEquals(State.EMPTY, cache.state(URL + "?a"));
            assertEquals(2, starts.get());
        } finally { release.countDown(); }
    }

    @Test
    void containNeverCropsAndCoverIsCentered() {
        Rect destination = Rect.makeXYWH(10, 20, 100, 50);
        Rect contained = fit(destination, 100, 100, true);
        assertEquals(35, contained.getLeft());
        assertEquals(20, contained.getTop());
        assertEquals(50, contained.getWidth());
        assertEquals(50, contained.getHeight());
        Rect covered = fit(destination, 100, 100, false);
        assertEquals(10, covered.getLeft());
        assertEquals(-5, covered.getTop());
        assertEquals(100, covered.getWidth());
        assertEquals(100, covered.getHeight());
    }

    @Test
    void sharedOwnershipAndNoDeferredOrGpuDecodeAreStructuralInvariants() throws Exception {
        String source = Files.readString(ProjectPaths.sharedJava().resolve(
                "com/muoniumplayer/core/update/AnnouncementImageCache.java"));
        assertTrue(source.contains("UpdateChecker.isAllowedPage("));
        assertFalse(source.contains("ALLOWED_PAGE_HOSTS"));
        assertFalse(source.contains("Image.makeFromEncoded("));
        assertFalse(source.contains("Image.makeDeferred"));
        assertFalse(source.contains("DirectContext.make"));
        assertFalse(source.contains("borrowTextureFrom("));
        assertFalse(source.contains("net.fabricmc."));
        assertFalse(source.contains("net.neoforged."));
        assertFalse(source.contains("net.minecraftforge."));
        assertTrue(source.contains("Codec.makeFromData(data)"));
        assertTrue(source.contains("bitmap.setImmutable()"));
        assertTrue(source.contains("Image.makeRasterFromBitmap(bitmap)"));
        assertTrue(source.contains("setInstanceFollowRedirects(false)"));
    }

    static void awaitState(AnnouncementImageCache cache, String url, State wanted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.state(url) != wanted && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(wanted, cache.state(url), cache.peek(url).failure());
    }

    static byte[] png(int width, int height) {
        byte[] bytes = new byte[33];
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        System.arraycopy(signature, 0, bytes, 0, 8);
        be32(bytes, 8, 13);
        ascii(bytes, 12, "IHDR");
        be32(bytes, 16, width);
        be32(bytes, 20, height);
        bytes[24] = 8;
        bytes[25] = 6;
        // Header-only fixtures intentionally cannot decode; policy tests must not load native Skia.
        return bytes;
    }

    private static byte[] jpeg(int width, int height) {
        byte[] bytes = {(byte) 255, (byte) 216, (byte) 255, (byte) 192, 0, 11, 8, 0, 0, 0, 0, 1, 1, 17, 0};
        bytes[7] = (byte) (height >>> 8);
        bytes[8] = (byte) height;
        bytes[9] = (byte) (width >>> 8);
        bytes[10] = (byte) width;
        return bytes;
    }

    private static byte[] webp(String chunk, int width, int height) {
        int size = chunk.equals("VP8L") ? 5 : 10;
        byte[] bytes = new byte[20 + size + (size & 1)];
        ascii(bytes, 0, "RIFF");
        le32(bytes, 4, bytes.length - 8);
        ascii(bytes, 8, "WEBP");
        ascii(bytes, 12, chunk);
        le32(bytes, 16, size);
        if (chunk.equals("VP8X")) {
            le32(bytes, 24, width - 1);
            bytes[27] = (byte) (height - 1);
            bytes[28] = (byte) ((height - 1) >>> 8);
            bytes[29] = (byte) ((height - 1) >>> 16);
        } else if (chunk.equals("VP8L")) {
            bytes[20] = 47;
            le32(bytes, 21, (width - 1) | ((height - 1) << 14));
        } else {
            bytes[23] = (byte) 157;
            bytes[24] = 1;
            bytes[25] = 42;
            bytes[26] = (byte) width;
            bytes[27] = (byte) (width >>> 8);
            bytes[28] = (byte) height;
            bytes[29] = (byte) (height >>> 8);
        }
        return bytes;
    }

    private static void ascii(byte[] bytes, int offset, String text) {
        byte[] content = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(content, 0, bytes, offset, content.length);
    }

    private static void be32(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (24 - i * 8));
    }

    private static void le32(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (i * 8));
    }

    private static FakeConnection response(int code, String location, String mime, byte[] bytes) throws IOException {
        return new FakeConnection(new URL(URL), code, location, mime, bytes);
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int code;
        private final String location;
        private final String mime;
        private final byte[] body;
        private String encoding;
        private boolean disconnected;
        private boolean bodyRead;
        private boolean streamClosed;

        FakeConnection(URL url, int code, String location, String mime, byte[] body) {
            super(url);
            this.code = code;
            this.location = location;
            this.mime = mime;
            this.body = body;
        }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return code; }
        @Override public String getHeaderField(String name) {
            if (name.equalsIgnoreCase("Location")) return location;
            if (name.equalsIgnoreCase("Content-Encoding")) return encoding;
            return null;
        }
        @Override public String getContentType() { return mime; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public InputStream getInputStream() {
            bodyRead = true;
            return new ByteArrayInputStream(body) {
                @Override public void close() throws IOException { streamClosed = true; super.close(); }
            };
        }
    }
}
