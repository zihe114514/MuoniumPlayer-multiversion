package com.muoniumplayer.core.update;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.muoniumplayer.core.update.AnnouncementImageCache.State;
import static org.junit.jupiter.api.Assertions.*;

class SponsorImageResourceTest {
    private static final String AFDIAN = "/assets/muonium/textures/sponsor/afdian-qr.png";
    private static final String WECHAT = "/assets/muonium/textures/sponsor/wechat-qr.jpg";

    @Test
    void onlyTwoFixedBundledPathsCanOpenAndCannotBypassNetworkUrlPolicy() {
        AtomicInteger opens = new AtomicInteger();
        try (AnnouncementImageCache cache = new AnnouncementImageCache(24,
                uri -> { throw new AssertionError("must not network"); },
                (bytes, header) -> { throw new AssertionError("must not decode"); },
                path -> { opens.incrementAndGet(); throw new AssertionError("must not read"); })) {
            for (String path : new String[]{"/config/muonium/token.json", "../sponsor/afdian-qr.png",
                    "/assets/muonium/textures/sponsor/../afdian-qr.png", "file:/tmp/secret", "", "https://afdian.com/test.png"}) {
                cache.requestResource(path);
                assertEquals(State.FAILED, cache.resourceState(path));
            }
            cache.requestResource(null);
            assertEquals(State.FAILED, cache.resourceState(null));
            assertEquals(0, opens.get());
            assertEquals(State.FAILED, cache.state("resource:" + AFDIAN));
            assertFalse(cache.withImage("resource:" + AFDIAN, image -> fail("resource key leaked")));
        }
    }

    @Test
    void resourceReadIsDeduplicatedOnWorkerAndCloseDoesNotWaitForIo() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        AtomicReference<Thread> reader = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AnnouncementImageCache cache = new AnnouncementImageCache(24,
                uri -> { throw new AssertionError("must not network"); },
                (bytes, header) -> { throw new AssertionError("missing resource must not decode"); }, path -> {
            opens.incrementAndGet();
            reader.set(Thread.currentThread());
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return null;
        });
        try {
            cache.requestResource(AFDIAN);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) cache.requestResource(AFDIAN);
            assertEquals(1, opens.get());
            assertNotSame(Thread.currentThread(), reader.get());
            assertTrue(reader.get().isDaemon());
            assertEquals(State.LOADING, cache.resourceState(AFDIAN));
            cache.close();
            assertEquals(State.EMPTY, cache.resourceState(AFDIAN));
            cache.requestResource(WECHAT);
            assertEquals(1, opens.get());
        } finally { release.countDown(); cache.close(); }
    }

    @Test
    void missingResourceFailsOnceWithoutAnyNetworkFallback() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        try (AnnouncementImageCache cache = new AnnouncementImageCache(24,
                uri -> { throw new AssertionError("must not network"); },
                (bytes, header) -> { throw new AssertionError("must not decode"); }, path -> {
            opens.incrementAndGet(); return null;
        })) {
            cache.requestResource(WECHAT);
            await(cache, WECHAT, State.FAILED);
            for (int i = 0; i < 100; i++) cache.requestResource(WECHAT);
            assertEquals(1, opens.get());
        }
    }

    @Test
    void corruptAndOversizedResourcesAreRejectedBeforeNativeDecoder() throws Exception {
        AtomicInteger decoded = new AtomicInteger();
        for (byte[] bytes : new byte[][]{new byte[]{1, 2, 3}, new byte[AnnouncementImageCache.MAX_ENCODED_BYTES + 1]}) {
            try (AnnouncementImageCache cache = new AnnouncementImageCache(24,
                    uri -> { throw new AssertionError("must not network"); }, (data, header) -> {
                decoded.incrementAndGet(); throw new IOException("unexpected native decode");
            }, path -> new ByteArrayInputStream(bytes))) {
                cache.requestResource(AFDIAN);
                await(cache, AFDIAN, State.FAILED);
            }
        }
        assertEquals(0, decoded.get());
    }

    @Test
    void bundledImagesHaveValidHeadersWithinSharedImageLimits() throws Exception {
        for (String path : new String[]{AFDIAN, WECHAT}) {
            try (var input = SponsorImageResourceTest.class.getResourceAsStream(path)) {
                assertNotNull(input, "missing bundled sponsor image: " + path);
                byte[] bytes = AnnouncementImageCache.readBounded(input, -1,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                AnnouncementImageCache.Header header = AnnouncementImageCache.inspect(bytes);
                assertTrue(header.width() > 0 && header.height() > 0);
                assertEquals(header.width(), header.height(), "QR artwork should remain square");
                assertTrue(header.width() <= AnnouncementImageCache.MAX_DIMENSION);
            }
        }
    }

    private static void await(AnnouncementImageCache cache, String path, State expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.resourceState(path) != expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(expected, cache.resourceState(path));
    }
}
