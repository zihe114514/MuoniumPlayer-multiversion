package com.muoniumplayer.core.audio.output;

import com.muoniumplayer.core.ncm.music.audio.OpenALAudioDevice;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.lwjgl.openal.ALC10;
import repackage.com.jsyn.devices.AudioDeviceInputStream;
import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.devices.AudioDeviceOutputStream;
import repackage.com.jsyn.engine.SynthesisEngine;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real EngineThread, including its startup/priming and cleanup paths. */
@Timeout(20)
class JsynOutputLifecycleTest {
    @Test void stopBetweenNativeStartAndPrimingWriteIsQuietAndReleasesOwner() throws Exception {
        requireDefaultDevice();
        try (Fixture fixture = new Fixture(true)) {
            fixture.output.holdStart = true;
            fixture.start(0);
            assertTrue(fixture.output.opened.await(5, TimeUnit.SECONDS));
            Thread stopper = new Thread(fixture.engine::stop, "test-jsyn-startup-stop");
            stopper.start();
            try {
                assertTrue(fixture.output.stopInterrupt.await(3, TimeUnit.SECONDS));
            } finally { fixture.output.releaseStart.countDown(); }
            stopper.join(5000);
            assertFalse(stopper.isAlive());
            fixture.awaitExit();
            assertNull(fixture.failure.get(), "Requested startup cancellation must not be an uncaught failure");
            assertEquals(List.of("output.start", "output.stop", "output.close"), fixture.trace);
            assertFalse(fixture.cleanupSawInterrupt);
            fixture.assertNativeReleased();
        }
    }

    @Test void exceptionAfterPartialNativeStartStillReleasesAllNativeResources() throws Exception {
        requireDefaultDevice();
        try (Fixture fixture = new Fixture(true)) {
            RuntimeException primary = new IllegalStateException("after native start");
            fixture.output.startFailure = primary;
            fixture.output.interruptStart = true;
            fixture.start(0);
            fixture.awaitExit();
            assertSame(primary, fixture.failure.get());
            assertEquals(List.of("output.start", "output.stop", "output.close"), fixture.trace);
            assertFalse(fixture.cleanupSawInterrupt);
            assertTrue(fixture.interruptedAtFailure);
            fixture.assertNativeReleased();
        }
    }

    @Test void failedInputStartAndEveryCleanupFailureStillCloseOutput() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            RuntimeException primary = new IllegalArgumentException("partial input start");
            RuntimeException inputStop = new IllegalStateException("input stop");
            RuntimeException inputClose = new IllegalStateException("input close");
            RuntimeException outputStop = new IllegalStateException("output stop");
            IOException outputClose = new IOException("output close");
            fixture.input.startFailure = primary;
            fixture.input.stopFailure = inputStop;
            fixture.input.closeFailure = inputClose;
            fixture.output.stopFailure = outputStop;
            fixture.output.closeFailure = outputClose;
            fixture.start(2);
            fixture.awaitExit();
            assertSame(primary, fixture.failure.get());
            assertEquals(List.of("input.start", "input.stop", "input.close", "output.stop", "output.close"), fixture.trace);
            assertArrayEquals(new Throwable[] {inputStop}, primary.getSuppressed());
            assertArrayEquals(new Throwable[] {inputClose, outputStop, outputClose}, inputStop.getSuppressed());
        }
    }

    @Test void interruptedOutputStartPreservesPrimaryAndClosesAfterStopFails() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            RuntimeException primary = new IllegalArgumentException("partial output start");
            RuntimeException stop = new IllegalStateException("output stop");
            IOException close = new IOException("output close");
            fixture.output.startFailure = primary;
            fixture.output.stopFailure = stop;
            fixture.output.closeFailure = close;
            fixture.output.interruptStart = true;
            fixture.start(0);
            fixture.awaitExit();
            assertSame(primary, fixture.failure.get());
            assertEquals(List.of("output.start", "output.stop", "output.close"), fixture.trace);
            assertFalse(fixture.cleanupSawInterrupt);
            assertTrue(fixture.interruptedAtFailure);
            assertArrayEquals(new Throwable[] {stop}, primary.getSuppressed());
            assertArrayEquals(new Throwable[] {close}, stop.getSuppressed());
        }
    }

    @Test void outputIoFailureLeavesLoopClosesStreamsAndNeverWritesAnotherBlock() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            CountingIoFailure problem = new CountingIoFailure();
            fixture.output.writeFailure = problem;
            fixture.start(0);
            fixture.awaitExit();
            assertInstanceOf(RuntimeException.class, fixture.failure.get());
            assertSame(problem, fixture.failure.get().getCause());
            assertEquals(2, fixture.output.writes.get(), "Only priming and the first failed loop write");
            assertEquals(0, problem.prints.get(), "I/O failure must not go through the catch-and-continue logger");
            assertEquals(List.of("output.start", "output.stop", "output.close"), fixture.trace);
        }
    }

    @Test void terminalNativeSinkFailureIsReleasedWithoutAnotherJsynWrite() throws Exception {
        requireDefaultDevice();
        try (Fixture fixture = new Fixture(true)) {
            fixture.output.closeNativeAfterPriming = true;
            fixture.start(0);
            fixture.awaitExit();
            assertInstanceOf(RuntimeException.class, fixture.failure.get());
            assertInstanceOf(IOException.class, fixture.failure.get().getCause());
            assertEquals(2, fixture.output.writes.get(), "Do not enter the adapter's terminal-failure wait");
            assertEquals(List.of("output.start", "output.stop", "output.close"), fixture.trace);
            fixture.assertNativeReleased();
        }
    }

    @Test void uncheckedInputIoFailureStopsBeforeGeneratingOrWritingMoreAudio() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            RuntimeException problem = new IllegalStateException("capture device disconnected");
            fixture.input.readFailure = problem;
            fixture.start(2);
            fixture.awaitExit();
            assertSame(problem, fixture.failure.get());
            assertEquals(1, fixture.input.reads.get());
            assertEquals(1, fixture.output.writes.get(), "Only the initial priming write precedes input failure");
            assertEquals(List.of("input.start", "output.start", "input.stop", "input.close", "output.stop", "output.close"), fixture.trace);
        }
    }

    @Test void stopInterruptingAnOutputWriteIsQuietAndStillClosesBothStreams() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.output.holdLoopWrite = true;
            fixture.start(0);
            assertTrue(fixture.output.loopWriteEntered.await(5, TimeUnit.SECONDS));
            fixture.engine.stop();
            fixture.awaitExit();
            assertNull(fixture.failure.get());
            assertEquals(2, fixture.output.writes.get());
            assertEquals(0, fixture.output.cancelPrints.get());
            assertEquals(List.of("output.start", "output.stop", "output.close"), fixture.trace);
        }
    }

    @Test void synthesisTaskFailureKeepsItsExistingRetryPolicyButOutputFailureStillExits() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            AtomicInteger tasks = new AtomicInteger();
            AtomicInteger taskPrints = new AtomicInteger();
            RuntimeException taskFailure = new IllegalStateException("task failure") {
                @Override public void printStackTrace() { taskPrints.incrementAndGet(); }
            };
            fixture.engine.addAudioTask(() -> {
                if (tasks.incrementAndGet() == 1) throw taskFailure;
            });
            fixture.output.writeFailure = new CountingIoFailure();
            fixture.start(0);
            fixture.awaitExit();
            assertEquals(2, tasks.get(), "Non-I/O task failure retains the existing next-iteration policy");
            assertEquals(1, taskPrints.get());
            assertEquals(2, fixture.output.writes.get());
            assertEquals(List.of("output.start", "output.stop", "output.close"), fixture.trace);
        }
    }

    private static final class CountingIoFailure extends IOException {
        final AtomicInteger prints = new AtomicInteger();
        CountingIoFailure() { super("output device disconnected"); }
        @Override public void printStackTrace() { prints.incrementAndGet(); }
    }

    private static final class Fixture implements AutoCloseable {
        final List<String> trace = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final Input input = new Input();
        final Output output;
        final SynthesisEngine engine;
        volatile Thread worker;
        volatile boolean interruptedAtFailure;
        volatile boolean cleanupSawInterrupt;

        Fixture(boolean nativeOutput) {
            AudioDeviceManager delegate = OpenALAudioDevice.createIfAvailable();
            output = new Output(nativeOutput ? delegate.createOutputStream(0, 48000, 2) : null);
            AudioDeviceManager manager = (AudioDeviceManager) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {AudioDeviceManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("createOutputStream")) return output;
                        if (method.getName().equals("createInputStream")) return input;
                        try { return method.invoke(delegate, args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
            engine = new SynthesisEngine(manager);
        }

        void markOwner(String event) {
            worker = Thread.currentThread();
            worker.setUncaughtExceptionHandler((thread, problem) -> {
                interruptedAtFailure = thread.isInterrupted();
                failure.set(problem);
            });
            trace.add(event);
            entered.countDown();
        }
        void cleanup(String event) {
            cleanupSawInterrupt |= Thread.currentThread().isInterrupted();
            trace.add(event);
        }
        void start(int inputChannels) { engine.start(48000, -1, inputChannels, 0, 2); }
        void awaitExit() throws InterruptedException {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            worker.join(5000);
            assertFalse(worker.isAlive(), "Real EngineThread must exit");
            assertFalse(engine.isRunning());
        }
        void assertNativeReleased() throws Exception {
            assertNotNull(output.nativeOwner);
            assertFalse(output.nativeOwner.isAlive(), "Native owner must finish before engine exit");
            assertNull(field(output.delegate, "sink"));
        }
        @Override public void close() throws Exception {
            output.releaseStart.countDown();
            output.releaseLoopWrite.countDown();
            engine.stop();
            if (worker != null) worker.join(5000);
            if (output.delegate != null) output.delegate.close();
        }

        final class Input implements AudioDeviceInputStream {
            RuntimeException startFailure, stopFailure, closeFailure, readFailure;
            final AtomicInteger reads = new AtomicInteger();
            @Override public void start() { markOwner("input.start"); if (startFailure != null) throw startFailure; }
            @Override public void stop() { cleanup("input.stop"); if (stopFailure != null) throw stopFailure; }
            @Override public void close() { cleanup("input.close"); if (closeFailure != null) throw closeFailure; }
            @Override public double getLatency() { return 0; }
            @Override public double read() { return 0; }
            @Override public int read(double[] buffer) {
                reads.incrementAndGet();
                if (readFailure != null) throw readFailure;
                return buffer.length;
            }
            @Override public int read(double[] buffer, int start, int count) { return count; }
            @Override public int available() { return 0; }
        }
        final class Output implements AudioDeviceOutputStream {
            final AudioDeviceOutputStream delegate;
            final CountDownLatch opened = new CountDownLatch(1);
            final CountDownLatch releaseStart = new CountDownLatch(1);
            final CountDownLatch stopInterrupt = new CountDownLatch(1);
            boolean holdStart, interruptStart;
            RuntimeException startFailure, stopFailure;
            IOException closeFailure, writeFailure;
            final AtomicInteger writes = new AtomicInteger();
            final AtomicInteger cancelPrints = new AtomicInteger();
            final CountDownLatch loopWriteEntered = new CountDownLatch(1);
            final CountDownLatch releaseLoopWrite = new CountDownLatch(1);
            boolean holdLoopWrite, closeNativeAfterPriming;
            Thread nativeOwner;
            Output(AudioDeviceOutputStream delegate) { this.delegate = delegate; }
            @Override public void start() {
                markOwner("output.start");
                if (delegate != null) {
                    delegate.start();
                    try { nativeOwner = (Thread) field(field(delegate, "sink"), "worker"); }
                    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                }
                opened.countDown();
                boolean interrupted = interruptStart;
                if (holdStart) {
                    for (;;) {
                        try { releaseStart.await(); break; }
                        catch (InterruptedException e) { interrupted = true; stopInterrupt.countDown(); }
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                if (startFailure != null) throw startFailure;
            }
            @Override public void stop() {
                cleanup("output.stop");
                if (delegate != null) delegate.stop();
                if (stopFailure != null) throw stopFailure;
            }
            @Override public void close() throws IOException {
                cleanup("output.close");
                if (delegate != null) delegate.close();
                if (closeFailure != null) throw closeFailure;
            }
            @Override public double getLatency() { return delegate == null ? 0 : delegate.getLatency(); }
            @Override public void write(double value) throws IOException { if (delegate != null) delegate.write(value); }
            @Override public void write(double[] values) throws IOException {
                int call = writes.incrementAndGet();
                if (call > 1 && holdLoopWrite) {
                    loopWriteEntered.countDown();
                    try { releaseLoopWrite.await(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("requested output cancellation") {
                            @Override public void printStackTrace() { cancelPrints.incrementAndGet(); }
                        };
                    }
                }
                if (call > 1 && writeFailure != null) throw writeFailure;
                if (delegate != null) delegate.write(values);
                if (call == 1 && closeNativeAfterPriming) {
                    try { ((AutoCloseable) field(delegate, "sink")).close(); }
                    catch (Exception failure) { throw new IOException("Cannot close test sink", failure); }
                }
            }
            @Override public void write(double[] values, int offset, int count) throws IOException {
                if (delegate != null) delegate.write(values, offset, count);
            }
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void requireDefaultDevice() {
        long device = ALC10.alcOpenDevice((ByteBuffer) null);
        Assumptions.assumeTrue(device != 0, "No default OpenAL device");
        assertTrue(ALC10.alcCloseDevice(device));
    }
}
