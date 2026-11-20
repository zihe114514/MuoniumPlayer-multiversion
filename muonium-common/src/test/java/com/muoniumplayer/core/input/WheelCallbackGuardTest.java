package com.muoniumplayer.core.input;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelCallbackGuardTest {
    @Test
    void screenFallbackAccumulatesWhenNativeCallbackDoesNotReachScreen() {
        WheelCallbackGuard guard = new WheelCallbackGuard();
        assertTrue(guard.shouldAccumulateScreenFallback());
    }

    @Test
    void synchronousOriginalCallbackSuppressesOnlyItsNestedScreenFallback() {
        WheelCallbackGuard guard = new WheelCallbackGuard();
        AtomicInteger additions = new AtomicInteger();
        if (guard.shouldAccumulateScreenFallback()) additions.incrementAndGet(); // GLFW wrapper
        guard.forwardToScreen(() -> {
            if (guard.shouldAccumulateScreenFallback()) additions.incrementAndGet();
        });
        assertEquals(1, additions.get());
        assertTrue(guard.shouldAccumulateScreenFallback());
    }

    @Test
    void nestedForwardingRemainsSuppressedAndRestoresAfterExceptions() {
        WheelCallbackGuard guard = new WheelCallbackGuard();
        assertThrowsIllegalState(() -> guard.forwardToScreen(() -> {
            assertFalse(guard.shouldAccumulateScreenFallback());
            guard.forwardToScreen(() -> assertFalse(guard.shouldAccumulateScreenFallback()));
            throw new IllegalStateException();
        }));
        assertTrue(guard.shouldAccumulateScreenFallback());
    }

    @Test
    void markerDoesNotLeakAcrossThreads() throws Exception {
        WheelCallbackGuard guard = new WheelCallbackGuard();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean otherThreadMayFallback = new AtomicBoolean();
        Thread callback = new Thread(() -> guard.forwardToScreen(() -> {
            inside.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }));
        callback.start();
        try {
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            otherThreadMayFallback.set(guard.shouldAccumulateScreenFallback());
        } finally {
            release.countDown();
            callback.join(5000);
        }
        assertFalse(callback.isAlive());
        assertTrue(otherThreadMayFallback.get());
        assertTrue(guard.shouldAccumulateScreenFallback());
    }

    @Test
    void concurrentFallbackAndCallbackForwardDoNotCrossSuppress() throws Exception {
        WheelCallbackGuard guard = new WheelCallbackGuard();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread callback = new Thread(() -> guard.forwardToScreen(() -> {
            inside.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }));
        callback.start();
        try {
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            assertTrue(guard.shouldAccumulateScreenFallback(), "unrelated thread fallback is independent");
        } finally {
            release.countDown();
            callback.join(5000);
        }
        assertFalse(callback.isAlive());
    }

    private static void assertThrowsIllegalState(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected IllegalStateException");
    }
}
