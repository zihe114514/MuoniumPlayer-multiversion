package com.muoniumplayer.core.audio.ffmpeg;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegCoverDecoderTest {
    @TempDir Path temp;

    @BeforeAll static void requireVideoNative() throws IOException {
        // Supported-platform native failures are failures, never an assumption skip.
        FfmpegRuntime.ensureVideoLoaded();
    }

    private File file(byte[] bytes) throws IOException {
        Path path = Files.createTempFile(temp, "cover-", ".mp4");
        Files.write(path, bytes);
        return path.toFile();
    }

    @Test void h264UsesPtsAndFlushesDelayedFramesIntoTwelveRgbaSamples() throws Exception {
        List<BufferedImage> frames = FfmpegCoverDecoder.decode(file(CoverVideoTestFixtures.h264()));
        assertEquals(12, frames.size()); // 1.2 seconds at 10fps, including decoder flush tail.
        for (BufferedImage image : frames) {
            assertEquals(256, image.getWidth()); assertEquals(256, image.getHeight());
            assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
            assertEquals(0, image.getRGB(128, 0));
            assertEquals(0, image.getRGB(128, 63));
            assertEquals(0, image.getRGB(128, 192));
            assertEquals(0, image.getRGB(128, 255));
            assertEquals(255, image.getRGB(128, 64) >>> 24);
            assertEquals(255, image.getRGB(128, 191) >>> 24);
        }
        color(frames.get(0), 32, 96, 255, 0, 0);
        color(frames.get(0), 224, 96, 0, 255, 0);
        color(frames.get(0), 32, 160, 0, 0, 255);
        color(frames.get(0), 224, 160, 255, 255, 0);
        color(frames.get(5), 32, 96, 255, 0, 0);
        color(frames.get(6), 32, 96, 255, 255, 255);
        color(frames.get(11), 32, 96, 255, 255, 255);
    }

    @Test void hevcRepeatsLowRatePtsWithoutReopeningAndStopsAtFiftyWithinBudget() throws Exception {
        FfmpegCoverDecoder decoder = new FfmpegCoverDecoder(file(CoverVideoTestFixtures.hevc()), Duration.ofSeconds(30), System::nanoTime);
        List<BufferedImage> frames = decoder.decodeFrames();
        assertEquals(50, frames.size());
        assertTrue((long) frames.size() * 256 * 256 * 4 <= 14L * 1024 * 1024);
        assertTrue(decoder.resourcesReleased());
        assertThrows(IOException.class, decoder::decodeFrames); // Never reopens the same session.
        decoder.close(); decoder.close();
        for (int i = 1; i < 5; i++) assertSame(frames.get(0), frames.get(i));
        BufferedImage first = frames.get(0);
        assertEquals(0, first.getRGB(63, 128)); assertEquals(0, first.getRGB(192, 128));
        assertEquals(255, first.getRGB(64, 128) >>> 24);
        assertEquals(255, first.getRGB(191, 128) >>> 24);
        color(first, 96, 32, 255, 0, 0);
        color(first, 160, 32, 0, 255, 0);
        color(first, 96, 224, 0, 0, 255);
        color(first, 160, 224, 255, 255, 0);
        color(frames.get(29), 96, 32, 255, 0, 0);
        color(frames.get(30), 96, 32, 255, 255, 255);
    }

    @Test void displayMatrixRotationKeepsOrientationAndTransparentPortraitPadding() throws Exception {
        List<BufferedImage> frames = FfmpegCoverDecoder.decode(file(CoverVideoTestFixtures.rotated()));
        assertEquals(12, frames.size());
        BufferedImage first = frames.get(0);
        assertEquals(0, first.getRGB(63, 128)); assertEquals(0, first.getRGB(192, 128));
        // MP4 rotate=90: source top-right becomes displayed top-left (counterclockwise).
        color(first, 96, 32, 0, 255, 0);
        color(first, 160, 32, 255, 255, 0);
        color(first, 96, 224, 255, 0, 0);
        color(first, 160, 224, 0, 0, 255);
    }

    @Test void fragmentedMp4WithNoHeaderDurationStillEndsAndFlushes() throws Exception {
        byte[] bytes = CoverVideoTestFixtures.fragmented();
        assertEquals(0, durationInMovieHeader(bytes));
        List<BufferedImage> frames = FfmpegCoverDecoder.decode(file(bytes));
        assertEquals(12, frames.size());
        color(frames.get(11), 32, 96, 255, 255, 255);
    }

    @Test void corruptMissingOversizedAndDenseInputsFailAndReleaseInsteadOfReturningPartialSuccess() throws Exception {
        for (byte[] bytes : new byte[][] {
                new byte[] {1, 4, 0, 12, 9},
                unknownCodec(),
                java.util.Arrays.copyOf(CoverVideoTestFixtures.h264(), 100),
                CoverVideoTestFixtures.oversized(),
                CoverVideoTestFixtures.dense()}) {
            File video = file(bytes);
            FfmpegCoverDecoder decoder = new FfmpegCoverDecoder(video, Duration.ofSeconds(30), System::nanoTime);
            assertThrows(IOException.class, decoder::decodeFrames);
            assertTrue(decoder.resourcesReleased());
            decoder.close();
            assertTrue(video.delete(), "Native IO handle must be closed even on failure");
        }
        assertThrows(IOException.class, () -> FfmpegCoverDecoder.decode(temp.resolve("missing.mp4").toFile()));
        assertThrows(IOException.class, () -> FfmpegCoverDecoder.decode(file(new byte[0])));
    }

    @Test void backwardsOrOverflowingPtsCannotProduceAnUnboundedRead() throws Exception {
        byte[] bytes = CoverVideoTestFixtures.h264();
        // Corrupt the MP4 composition offsets while retaining the encoded packets.
        int ctts = atom(bytes, "ctts");
        assertTrue(ctts > 0, "B-frame fixture must have composition timestamps");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        bytes[ctts + 4] = 1; // signed composition offsets
        int entries = b.getInt(ctts + 8);
        for (int i = 0; i < entries; i++) b.putInt(ctts + 16 + i * 8, (i & 1) == 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE + 1);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(IOException.class, () -> FfmpegCoverDecoder.decode(file(bytes))));
    }

    @Test void deadlineActuallyAbortsFromTheNativeAvioCallbackAndReleasesEverything() throws Exception {
        AtomicReference<FfmpegCoverDecoder> reference = new AtomicReference<>();
        FfmpegCoverDecoder decoder = new FfmpegCoverDecoder(file(CoverVideoTestFixtures.h264()), Duration.ofSeconds(30), () -> {
            FfmpegCoverDecoder current = reference.get();
            return current != null && current.nativeInterruptChecks() > 0 ? TimeUnit.SECONDS.toNanos(31) : 0;
        });
        reference.set(decoder);
        assertThrows(SocketTimeoutException.class, decoder::decodeFrames);
        assertTrue(decoder.nativeInterruptAborts() > 0, "AVIO callback must return 1 from a real native operation");
        assertTrue(decoder.resourcesReleased());
    }

    @Test void cancellationAndOwnerInterruptionAbortNativeIoWithoutCrossThreadFree() throws Exception {
        cancelInNativeCallback(false);
        cancelInNativeCallback(true);
    }

    private void cancelInNativeCallback(boolean interruptOwner) throws Exception {
        File video = file(CoverVideoTestFixtures.h264());
        CountDownLatch inCallback = new CountDownLatch(1), resumeCallback = new CountDownLatch(1);
        AtomicReference<FfmpegCoverDecoder> decoderRef = new AtomicReference<>();
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        AtomicBoolean gated = new AtomicBoolean();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> {
                workerRef.set(Thread.currentThread());
                FfmpegCoverDecoder decoder = new FfmpegCoverDecoder(video, Duration.ofSeconds(30), () -> {
                    FfmpegCoverDecoder current = decoderRef.get();
                    if (current != null && current.nativeInterruptChecks() > 0 && gated.compareAndSet(false, true)) {
                        inCallback.countDown();
                        try {
                            if (!resumeCallback.await(5, TimeUnit.SECONDS)) throw new AssertionError("Callback test gate timed out");
                        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                    return System.nanoTime();
                });
                decoderRef.set(decoder);
                try {
                    IOException failure = assertThrows(InterruptedIOException.class, decoder::decodeFrames);
                    assertTrue(decoder.resourcesReleased());
                    assertTrue(decoder.nativeInterruptAborts() > 0);
                    return failure;
                } finally {
                    assertEquals(interruptOwner, Thread.currentThread().isInterrupted());
                    decoder.close();
                }
            });
            assertTrue(inCallback.await(5, TimeUnit.SECONDS), "Must enter callback from native libavformat IO");
            FfmpegCoverDecoder decoder = decoderRef.get();
            assertThrows(IOException.class, decoder::decodeFrames);
            assertThrows(IOException.class, decoder::close);
            assertFalse(decoder.resourcesReleased());
            if (interruptOwner) workerRef.get().interrupt(); else decoder.cancel();
            resumeCallback.countDown();
            assertInstanceOf(InterruptedIOException.class, result.get(5, TimeUnit.SECONDS));
            assertTrue(video.delete(), "Cancelled native input must be closed");
        } finally {
            resumeCallback.countDown(); pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void videoWorkersDoNotShareCursorsAndDoNotMoveAudioCursor() throws Exception {
        File audioFile = file(AudioTestFixtures.pcm16(48000, 2, 1000));
        try (var audio = FfmpegAudioDecoder.open(AudioInput.file(audioFile.toPath()), OutputSpec.preserveSource(), new CancellationToken())) {
            var block = new PcmBlock(17, 2);
            assertEquals(17, audio.read(block));
            assertEquals(0, block.startFrame());
            var pool = Executors.newFixedThreadPool(2);
            try {
                File h264 = file(CoverVideoTestFixtures.h264()), hevc = file(CoverVideoTestFixtures.hevc());
                var first = pool.submit(() -> FfmpegCoverDecoder.decode(h264));
                var second = pool.submit(() -> FfmpegCoverDecoder.decode(hevc));
                assertEquals(12, first.get(10, TimeUnit.SECONDS).size());
                assertEquals(50, second.get(10, TimeUnit.SECONDS).size());
                assertTrue(h264.delete()); assertTrue(hevc.delete());
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
            assertEquals(17, audio.read(block));
            assertEquals(17, block.startFrame());
        }
    }

    @Test void errorDuringInitializationPropagatesAndFreesTheAllocatedFormat() throws Exception {
        File video = file(CoverVideoTestFixtures.h264());
        File failedPath = new File(video.getPath()) {
            @Override public String getAbsolutePath() { throw new OutOfMemoryError("sentinel"); }
        };
        FfmpegCoverDecoder decoder = new FfmpegCoverDecoder(failedPath, Duration.ofSeconds(30), System::nanoTime);
        assertEquals("sentinel", assertThrows(OutOfMemoryError.class, decoder::decodeFrames).getMessage());
        assertTrue(decoder.resourcesReleased());
        decoder.close();
        assertTrue(video.delete());
    }

    @Test void preInterruptedOwnerKeepsInterruptStatusAndAllocatesNothing() throws Exception {
        File video = file(CoverVideoTestFixtures.h264());
        FfmpegCoverDecoder decoder = new FfmpegCoverDecoder(video, Duration.ofSeconds(30), System::nanoTime);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, decoder::decodeFrames);
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(decoder.resourcesReleased());
            assertEquals(0, decoder.nativeInterruptChecks());
        } finally { Thread.interrupted(); }
    }

    private static void color(BufferedImage image, int x, int y, int r, int g, int b) {
        int argb = image.getRGB(x, y);
        assertEquals(255, argb >>> 24);
        assertEquals(r, (argb >>> 16) & 255, 28);
        assertEquals(g, (argb >>> 8) & 255, 28);
        assertEquals(b, argb & 255, 28);
    }

    private static byte[] unknownCodec() {
        byte[] bytes = CoverVideoTestFixtures.h264();
        // Keep the valid MP4 structure but declare an unsupported sample-entry tag.
        for (int i = 4; i + 4 <= bytes.length; i++) {
            if (bytes[i] == 'a' && bytes[i + 1] == 'v' && bytes[i + 2] == 'c' && bytes[i + 3] == '1') {
                for (int j = 0; j < 4; j++) bytes[i + j] = 'z';
            }
        }
        return bytes;
    }

    private static int atom(byte[] bytes, String name) {
        byte[] tag = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 4; i + 4 < bytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < 4; j++) if (bytes[i + j] != tag[j]) match = false;
            if (match) return i;
        }
        return -1;
    }

    private static long durationInMovieHeader(byte[] bytes) {
        int mvhd = atom(bytes, "mvhd");
        assertTrue(mvhd > 0);
        assertEquals(0, bytes[mvhd + 4]);
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt(mvhd + 20));
    }
}
