package com.muoniumplayer.core.update;

import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.muoniumplayer.core.update.AnnouncementImageCache.State;
import static com.muoniumplayer.core.update.AnnouncementImagePolicyTest.awaitState;
import static org.junit.jupiter.api.Assertions.*;

/** True native decoding, raster Canvas pixels and close/borrow races; not a GPU/in-game acceptance. */
@RequiresSkijaNative
class AnnouncementImageResourceTest {
    private static final String URL = "https://raw.githubusercontent.com/example/announcement.png";

    @BeforeAll
    static void loadNative() {
        assertTrue(SkijaNatives.ensureLoaded());
    }

    @Test
    void pngJpegAndWebpDecodeAsEagerRasterImagesAndDrawOnCpuCanvas() throws Exception {
        for (EncodedImageFormat format : new EncodedImageFormat[]{EncodedImageFormat.PNG, EncodedImageFormat.JPEG, EncodedImageFormat.WEBP}) {
            byte[] encoded = encode(format, 0xFFFF4000);
            try (AnnouncementImageCache cache = new AnnouncementImageCache();
                 Surface output = Surface.makeRasterN32Premul(40, 40)) {
                cache.prime(URL, encoded);
                awaitState(cache, URL, State.READY);
                assertEquals(8, cache.peek(URL).width());
                assertEquals(8, cache.peek(URL).height());
                output.getCanvas().clear(0);
                int saves = output.getCanvas().getSaveCount();
                cache.draw(URL, output.getCanvas(), Rect.makeXYWH(0, 0, 40, 40), 0, true, 1);
                assertEquals(saves, output.getCanvas().getSaveCount());
                try (Bitmap pixels = new Bitmap()) {
                    assertTrue(pixels.allocPixels(ImageInfo.makeN32Premul(40, 40)));
                    assertTrue(output.readPixels(pixels, 0, 0));
                    int color = pixels.getColor(20, 20);
                    assertEquals(255, (color >>> 24) & 255);
                    assertEquals(255, (color >>> 16) & 255, 10);
                    assertEquals(64, (color >>> 8) & 255, 10);
                }
                // Reading pixels is possible without DirectContext or a graphics context.
                cache.withImage(URL, image -> {
                    try (Bitmap pixels = Bitmap.makeFromImage(image)) {
                        assertNotNull(pixels);
                        assertTrue(pixels.isReadyToDraw());
                    }
                });
            }
        }
    }

    @Test
    void containPreservesWholeImageAndOpacityClippingDoNotLeakCanvasState() throws Exception {
        try (AnnouncementImageCache cache = new AnnouncementImageCache();
             Surface output = Surface.makeRasterN32Premul(40, 40)) {
            cache.prime(URL, encode(EncodedImageFormat.PNG, 0xFFFF4000));
            awaitState(cache, URL, State.READY);
            output.getCanvas().clear(0);
            cache.draw(URL, output.getCanvas(), Rect.makeXYWH(0, 0, 40, 20), 0, true, .5f);
            try (Bitmap pixels = new Bitmap()) {
                assertTrue(pixels.allocPixels(ImageInfo.makeN32Premul(40, 40)));
                assertTrue(output.readPixels(pixels, 0, 0));
                assertEquals(0, pixels.getColor(2, 10)); // Letterbox, not cropped/stretched QR.
                assertEquals(128, (pixels.getColor(20, 10) >>> 24) & 255, 1);
                assertEquals(0, pixels.getColor(20, 30));
            }
            output.getCanvas().clear(0);
            int saves = output.getCanvas().getSaveCount();
            cache.draw(URL, output.getCanvas(), Rect.makeXYWH(0, 0, 40, 40), 10, false, 1);
            assertEquals(saves, output.getCanvas().getSaveCount());
            try (Bitmap pixels = new Bitmap()) {
                assertTrue(pixels.allocPixels(ImageInfo.makeN32Premul(40, 40)));
                assertTrue(output.readPixels(pixels, 0, 0));
                assertEquals(0, pixels.getColor(0, 0));
                assertEquals(255, (pixels.getColor(20, 20) >>> 24) & 255);
            }
        }
    }

    @Test
    void reentrantCloseDefersReleaseUntilBorrowEndsEvenWhenPainterThrows() throws Exception {
        AnnouncementImageCache cache = new AnnouncementImageCache();
        try {
            cache.prime(URL, encode(EncodedImageFormat.PNG, 0xFF123456));
            awaitState(cache, URL, State.READY);
            AtomicReference<Image> observed = new AtomicReference<>(); // Test observes close only, never paints after borrow.
            assertThrows(IllegalStateException.class, () -> cache.withImage(URL, image -> {
                observed.set(image);
                cache.close();
                assertFalse(image.isClosed(), "close inside a live borrow must defer native release");
                assertEquals(8, image.getWidth());
                throw new IllegalStateException("painter failed");
            }));
            assertTrue(observed.get().isClosed());
            assertEquals(State.EMPTY, cache.state(URL));
            cache.close();
        } finally { cache.close(); }
    }

    @Test
    void closeOnAnotherThreadCannotReleaseWhileRenderCallbackIsPainting() throws Exception {
        AnnouncementImageCache cache = new AnnouncementImageCache();
        CountDownLatch borrowed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicReference<Image> observed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread painter = null;
        Thread closer = null;
        try {
            cache.prime(URL, encode(EncodedImageFormat.PNG, 0xFF123456));
            awaitState(cache, URL, State.READY);
            painter = new Thread(() -> {
                try {
                    cache.withImage(URL, image -> {
                        observed.set(image);
                        borrowed.countDown();
                        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                        assertFalse(image.isClosed());
                    });
                } catch (Throwable error) { failure.set(error); }
            }, "test-image-render");
            painter.setDaemon(true);
            painter.start();
            assertTrue(borrowed.await(5, TimeUnit.SECONDS));
            closer = new Thread(() -> {
                closeStarted.countDown();
                cache.close();
                closeFinished.countDown();
            }, "test-image-close");
            closer.setDaemon(true);
            closer.start();
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            assertFalse(closeFinished.await(50, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(closeFinished.await(5, TimeUnit.SECONDS));
            painter.join(5000);
            assertNull(failure.get());
            assertTrue(observed.get().isClosed());
        } finally {
            release.countDown();
            if (painter != null) painter.join(5000);
            if (closer != null) closer.join(5000);
            cache.close();
        }
    }

    @Test
    void lateDecoderResultAfterCloseIsClosedInsteadOfPublished() throws Exception {
        CountDownLatch decoding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Image> produced = new AtomicReference<>();
        AnnouncementImageCache cache = new AnnouncementImageCache(24,
                uri -> { throw new AssertionError("probe must not network"); }, (bytes, header) -> {
            decoding.countDown();
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException ignored) { /* Simulate a native decoder ignoring cancellation. */ }
            }
            Image image = AnnouncementImageCache.decodeRaster(bytes, header);
            produced.set(image);
            return image;
        });
        try {
            cache.prime(URL, encode(EncodedImageFormat.PNG, 0xFF123456));
            assertTrue(decoding.await(5, TimeUnit.SECONDS));
            cache.close();
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((produced.get() == null || !produced.get().isClosed()) && System.nanoTime() < deadline) Thread.sleep(5);
            assertNotNull(produced.get());
            assertTrue(produced.get().isClosed());
            assertEquals(State.EMPTY, cache.state(URL));
        } finally { release.countDown(); cache.close(); }
    }

    @Test
    void lruEvictionClosesImageButNeverClosesItInsideReentrantBorrow() throws Exception {
        byte[] encoded = encode(EncodedImageFormat.PNG, 0xFF123456);
        try (AnnouncementImageCache cache = new AnnouncementImageCache(1,
                uri -> { throw new AssertionError("probe must not network"); }, AnnouncementImageCache::decodeRaster)) {
            cache.prime(URL, encoded);
            awaitState(cache, URL, State.READY);
            AtomicReference<Image> evicted = new AtomicReference<>();
            cache.withImage(URL, image -> {
                evicted.set(image);
                cache.prime(URL + "?next", encoded);
                assertFalse(image.isClosed());
                assertEquals(State.EMPTY, cache.state(URL));
            });
            assertTrue(evicted.get().isClosed());
            awaitState(cache, URL + "?next", State.READY);
            cache.prime(URL, encoded);
            awaitState(cache, URL, State.READY);
            assertEquals(State.EMPTY, cache.state(URL + "?next"));
        }
    }

    @Test
    void corruptOrIncompleteEncodedBytesNeverBecomeReady() throws Exception {
        try (AnnouncementImageCache cache = new AnnouncementImageCache()) {
            cache.prime(URL, AnnouncementImagePolicyTest.png(8, 8));
            awaitState(cache, URL, State.FAILED);
            assertFalse(cache.withImage(URL, image -> fail("failed decode must not publish")));
        }
    }

    private static byte[] encode(EncodedImageFormat format, int color) throws IOException {
        try (Surface source = Surface.makeRasterN32Premul(8, 8);
             Paint paint = new Paint().setColor(color)) {
            source.getCanvas().drawRect(Rect.makeXYWH(0, 0, 8, 8), paint);
            try (Image image = source.makeImageSnapshot(); Data data = image.encodeToData(format, 100)) {
                assertNotNull(data, "native encoder for " + format);
                return data.getBytes();
            }
        }
    }
}
