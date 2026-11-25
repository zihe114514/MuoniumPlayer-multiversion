package com.muoniumplayer.core.audio.output;

import java.nio.FloatBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.ALC10;

import static org.junit.jupiter.api.Assertions.*;

/** Actual device consumption between worker queue operations; no speaker output. */
@Timeout(15)
class OpenAlUnderrunNativeTest {
    @Test void queueExpiresAfterFreeBufferQueryWithoutReplayingOldBlock() throws Exception {
        requireLoopback();
        try (OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
            driver.open(48000, 2);
            driver.submit(block(0.125f), 1024);
            assertEquals(3, driver.freeBuffers());
            render(driver, 2048); // The device expires after the worker's free-buffer query.
            driver.submit(block(-0.25f), 1024);
            assertEquals(1024, driver.queuedFrames());
            FloatBuffer result = render(driver, 2048);
            assertBlock(result, 0, -0.25f);
            assertEquals(0f, result.get(1500 * 2), 0.000001f, "No old prefix or duplicate new block");
            assertEquals(4, driver.freeBuffers());
            assertEquals(0, driver.queuedFrames());
        }
    }

    @Test void multipleExpiredBuffersAreRecycledWithoutDroppingTheFreshBlock() throws Exception {
        requireLoopback();
        try (OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
            driver.open(48000, 2);
            driver.submit(block(0.125f), 1024);
            driver.submit(block(0.25f), 1024);
            driver.submit(block(0.375f), 1024);
            assertEquals(1, driver.freeBuffers());
            render(driver, 4096);
            driver.submit(block(-0.5f), 1024);
            assertEquals(1024, driver.queuedFrames());
            FloatBuffer result = render(driver, 2048);
            assertBlock(result, 0, -0.5f);
            assertEquals(0f, result.get(1500 * 2), 0.000001f);
            assertEquals(4, driver.freeBuffers());
        }
    }

    @Test void pausedSubmissionAfterUnderrunSurvivesResumeInOrder() throws Exception {
        requireLoopback();
        try (OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
            driver.open(48000, 2);
            driver.submit(block(0.125f), 1024);
            render(driver, 2048);
            driver.pause();
            driver.submit(block(-0.25f), 1024);
            driver.submit(block(-0.5f), 1024);
            assertEquals(2048, driver.queuedFrames());
            FloatBuffer paused = render(driver, 1024);
            assertEquals(0f, paused.get(800), 0.000001f);
            driver.resume();
            FloatBuffer result = render(driver, 3072);
            assertBlock(result, 0, -0.25f);
            assertBlock(result, 1024, -0.5f);
            assertEquals(0f, result.get(2500 * 2), 0.000001f);
            assertEquals(4, driver.freeBuffers());
            assertEquals(0, driver.queuedFrames());
        }
    }

    @Test void resumeOfAnAlreadyDrainedSourceDoesNotReplayItsQueue() throws Exception {
        requireLoopback();
        try (OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
            driver.open(48000, 2);
            driver.submit(block(0.5f), 1024);
            render(driver, 2048);
            // The just-submitted block has really finished before the next
            // owner operation. STOPPED must not cause that block to be retained.
            driver.resume();
            assertEquals(0, driver.queuedFrames());
            assertEquals(0f, render(driver, 1024).get(800), 0.000001f);
            driver.submit(block(-0.125f), 1024);
            assertBlock(render(driver, 1024), 0, -0.125f);
        }
    }

    @Test void repeatedUnderrunsPreserveBufferPoolAndFrameAccounting() throws Exception {
        requireLoopback();
        try (OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
            driver.open(48000, 2);
            for (int cycle = 0; cycle < 32; cycle++) {
                float value = cycle % 2 == 0 ? 0.125f : -0.25f;
                driver.submit(block(value), 1024);
                assertEquals(1024, driver.queuedFrames());
                assertBlock(render(driver, 2048), 0, value);
                // Deliberately omit freeBuffers: submit must handle the expired queue itself.
            }
            assertEquals(4, driver.freeBuffers());
            assertEquals(0, driver.queuedFrames());
        }
    }

    @Test void submissionPreservesThePartlyPlayedBufferOffsetAndItsRemainingFrames() throws Exception {
        requireLoopback();
        try (OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
            driver.open(48000, 2);
            driver.submit(block(0.125f), 1024);
            render(driver, 256);
            driver.submit(block(-0.25f), 1024);
            FloatBuffer result = render(driver, 2304);
            for (int frame = 100; frame < 700; frame++) {
                assertEquals(0.125f, result.get(frame * 2), 0.00001f, "Unplayed old tail " + frame);
            }
            assertBlock(result, 768, -0.25f);
            assertEquals(0f, result.get(2200 * 2), 0.000001f,
                    "The old buffer must resume at its paused offset, not its beginning");
            assertEquals(4, driver.freeBuffers());
            assertEquals(0, driver.queuedFrames());
        }
    }

    @Test void nativeRenderingBetweenEnqueueAndRestartKeepsCursorAndFreshBlockOrder() throws Exception {
        requireLoopback();
        for (boolean expiredBeforeSubmit : new boolean[] {false, true}) {
            try (OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
                driver.open(48000, 2);
                driver.submit(block(0.125f), 1024);
                render(driver, expiredBeforeSubmit ? 2048 : 256);
                int positionBeforeQueue = driver.sampleOffsetForTest();
                FloatBuffer duringQueue = BufferUtils.createFloatBuffer(4096 * 2);
                // Far longer than the whole queue. A running source would consume
                // both blocks here before the owner could query its state again.
                driver.submitWithRenderForTest(block(-0.25f), 1024, duringQueue, 4096);
                assertEquals(positionBeforeQueue, driver.sampleOffsetForTest(),
                        "Native rendering during the hold must not advance the source cursor");
                assertEquals(expiredBeforeSubmit ? 1024 : 2048, driver.queuedFrames(),
                        "The checkpoint must not retire the new block or unplayed old tail");
                for (int sample = 0; sample < duringQueue.capacity(); sample++) {
                    // Pausing can render a native declick/read-ahead envelope,
                    // including on 3.3.1. Cursor and queue evidence above prove
                    // that this does not advance or retire the queued samples.
                    float value = duringQueue.get(sample);
                    assertTrue(Float.isFinite(value) && Math.abs(value) <= 0.25001f);
                    if (expiredBeforeSubmit) assertEquals(0f, value, 0.000001f);
                }
                FloatBuffer resumed = render(driver, 2304);
                int newOffset = expiredBeforeSubmit ? 0 : 768;
                if (!expiredBeforeSubmit) {
                    // Native resume may ramp its gain, so test the exact channel
                    // boundary/position rather than assuming a flat envelope.
                    for (int frame = 100; frame < 768; frame++) {
                        assertTrue(resumed.get(frame * 2) > 0f && resumed.get(frame * 2) <= 0.12501f,
                                "Unplayed old frame must retain its original position: " + frame);
                    }
                    assertTrue(resumed.get(768 * 2) < 0f, "Fresh block must begin at frame 768");
                    for (int frame = 768; frame < 1792; frame++) {
                        assertTrue(resumed.get(frame * 2) < 0f && resumed.get(frame * 2) >= -0.25001f,
                                "Fresh frames must remain in order: " + frame);
                    }
                    assertEquals(-0.25f, resumed.get(1600 * 2), 0.00001f);
                } else {
                    assertBlock(resumed, newOffset, -0.25f);
                }
                assertEquals(0f, resumed.get(2200 * 2), 0.000001f,
                        "New data must appear exactly once, followed by silence");
                assertEquals(4, driver.freeBuffers());
                assertEquals(0, driver.queuedFrames());
            }
        }
    }

    private static float[] block(float value) {
        float[] block = new float[2048];
        Arrays.fill(block, value);
        return block;
    }

    private static FloatBuffer render(OpenAlOutputDriver driver, int frames) throws Exception {
        FloatBuffer result = BufferUtils.createFloatBuffer(frames * 2);
        driver.renderForTest(result, frames);
        return result;
    }

    private static void assertBlock(FloatBuffer samples, int offsetFrames, float expected) {
        for (int frame = offsetFrames + 200; frame < offsetFrames + 800; frame++) {
            assertEquals(expected, samples.get(frame * 2), 0.00001f, "Left frame " + frame);
            assertEquals(expected, samples.get(frame * 2 + 1), 0.00001f, "Right frame " + frame);
        }
    }

    private static void requireLoopback() {
        Assumptions.assumeTrue(ALC10.alcIsExtensionPresent(0, "ALC_SOFT_loopback"),
                "Installed OpenAL does not expose ALC_SOFT_loopback");
    }
}
