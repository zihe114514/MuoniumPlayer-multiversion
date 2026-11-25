package com.muoniumplayer.core.audio.output;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** Native format/channel tests; not game listening or hardware surround proof. */
@Timeout(20)
class OpenAlOutputNativeTest {
    @Test void loopbackStereoRetainsQuietFloatSignal() throws Exception {
        requireLoopback();
        try (OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
            driver.open(48000, 2);
            assertTrue(driver.capabilities().supportsChannels(2));
            assertTrue(driver.usesFloat32(), "Installed loopback must actually accept float PCM");
            float[] samples = new float[1024 * 2];
            for (int i = 0; i < 1024; i++) { samples[i * 2] = 0x1.0p-22f; samples[i * 2 + 1] = -0x1.0p-22f; }
            driver.submit(samples, 1024);
            FloatBuffer result = BufferUtils.createFloatBuffer(1024 * 2);
            driver.renderForTest(result, 1024);
            float left = 0, right = 0;
            for (int i = 0; i < 1024; i++) { left = Math.max(left, result.get(i * 2)); right = Math.min(right, result.get(i * 2 + 1)); }
            assertTrue(left > 0x1.0p-25f && left <= 0x1.0p-21f, "Sub-PCM16 signal must survive float output");
            assertTrue(right < -0x1.0p-25f && right >= -0x1.0p-21f);
        }
    }

    @Test void loopbackFloatHeadroomIsAppliedBeforeFinalGain() throws Exception {
        requireLoopback();
        try (OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
            driver.open(48000, 2);
            driver.setGain(0.5f);
            float[] samples = new float[1024 * 2];
            for (int i = 0; i < 1024; i++) { samples[i * 2] = 1.5f; samples[i * 2 + 1] = -1.5f; }
            driver.submit(samples, 1024);
            FloatBuffer result = BufferUtils.createFloatBuffer(2048);
            driver.renderForTest(result, 1024);
            assertEquals(0.75f, result.get(1900), 0.005f);
            assertEquals(-0.75f, result.get(1901), 0.005f);
        }
    }

    @Test void loopbackFivePointOneMapsAllSixChannels() throws Exception { channelMapping(6); }
    @Test void loopbackSevenPointOneMapsAllEightChannels() throws Exception { channelMapping(8); }

    private static void channelMapping(int channels) throws Exception {
        requireLoopback();
        try (OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
            driver.open(48000, channels);
            assertTrue(driver.capabilities().supportsChannels(channels));
            assertEquals(channels, driver.outputChannels());
            float[] samples = new float[1024 * channels];
            FloatBuffer rendered = BufferUtils.createFloatBuffer(1024 * channels);
            for (int input = 0; input < channels; input++) {
                driver.clear();
                java.util.Arrays.fill(samples, 0);
                for (int i = 0; i < 1024; i++) samples[i * channels + input] = 0.125f;
                driver.submit(samples, 1024);
                rendered.clear();
                driver.renderForTest(rendered, 1024);
                double[] energy = new double[channels];
                for (int frame = 128; frame < 1024; frame++) {
                    for (int channel = 0; channel < channels; channel++) {
                        float value = rendered.get(frame * channels + channel);
                        assertTrue(Float.isFinite(value));
                        energy[channel] += value * value;
                    }
                }
                assertTrue(energy[input] > 1, "Requested channel " + input + " must carry PCM");
                for (int output = 0; output < channels; output++) {
                    if (output != input) assertTrue(energy[output] < energy[input] * 0.001,
                            "Channel " + input + " leaked/remapped into " + output + ": " + java.util.Arrays.toString(energy));
                }
            }
        }
    }

    @Test void everyMethodRestoresForeignNativeTlsAndBothCapabilities() throws Exception {
        requireLoopback();
        try (AmbientContext ambient = new AmbientContext();
             OpenAlOutputDriver driver = new OpenAlOutputDriver(true)) {
            driver.open(48000, 2); ambient.assertCurrent();
            driver.setGain(0.5f); ambient.assertCurrent();
            driver.submit(new float[128], 64); ambient.assertCurrent();
            driver.pause(); ambient.assertCurrent();
            driver.resume(); ambient.assertCurrent();
            driver.renderForTest(BufferUtils.createFloatBuffer(256), 128); ambient.assertCurrent();
            driver.freeBuffers(); ambient.assertCurrent();
            driver.clear(); ambient.assertCurrent();
            driver.close(); ambient.assertCurrent();
        }
    }

    @Test void desktopDeviceSilentProbeUsesOwnerAndLeavesCallerContextUntouched() throws Exception {
        requireLoopback();
        try (AmbientContext ambient = new AmbientContext()) {
            // Probe only device availability before testing the production route.
            long device = ALC10.alcOpenDevice((ByteBuffer) null);
            Assumptions.assumeTrue(device != 0, "No physical/default OpenAL device on this host");
            try {
                assertTrue(ALC10.alcIsExtensionPresent(device, "ALC_EXT_thread_local_context"),
                        "Available default device must have safe TLS support");
            } finally { assertTrue(ALC10.alcCloseDevice(device)); }
            try (StreamingPcmSink sink = OpenAlPcmSink.open(48000, 2)) {
                ambient.assertCurrent();
                sink.setGain(0);
                sink.write(new float[4800 * 2], 0, 4800);
                sink.drain();
                ambient.assertCurrent();
            }
            ambient.assertCurrent();
        }
    }

    @Test void jsynAdapterStopCancelsWriterAndCanReopenWithoutNativeLeaks() throws Exception {
        requireLoopback();
        long device = ALC10.alcOpenDevice((ByteBuffer) null);
        Assumptions.assumeTrue(device != 0, "No default OpenAL device");
        assertTrue(ALC10.alcCloseDevice(device));
        repackage.com.jsyn.devices.AudioDeviceManager manager =
                com.muoniumplayer.core.ncm.music.audio.OpenALAudioDevice.createIfAvailable();
        assertEquals(2, manager.getMaxOutputChannels(0));
        assertThrows(IllegalArgumentException.class, () -> manager.createOutputStream(0, 48000, 6));
        assertThrows(UnsupportedOperationException.class, () -> manager.createInputStream(0, 48000, 1));
        repackage.com.jsyn.devices.AudioDeviceOutputStream stream = manager.createOutputStream(0, 48000, 2);
        java.util.concurrent.atomic.AtomicReference<Throwable> result = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            stream.start();
            Thread writer = new Thread(() -> {
                try { stream.write(new double[480000]); }
                catch (Throwable error) { result.set(error); }
            }, "test-jsyn-writer");
            writer.setDaemon(true);
            writer.start();
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (stream.getLatency() < 0.1 && System.nanoTime() < until) Thread.sleep(1);
            assertTrue(writer.isAlive());
            stream.stop();
            writer.join(3000);
            assertFalse(writer.isAlive());
            assertInstanceOf(IOException.class, result.get());
            stream.close();
            stream.start();
            stream.write(new double[128]);
            stream.stop();
            stream.close();
        } finally { stream.close(); }
    }

    @Test void legacyTerminalFailureParksCatchAndContinueLoopUntilStop() throws Exception {
        requireLoopback();
        long device = ALC10.alcOpenDevice((ByteBuffer) null);
        Assumptions.assumeTrue(device != 0, "No default OpenAL device");
        assertTrue(ALC10.alcCloseDevice(device));
        repackage.com.jsyn.devices.AudioDeviceManager manager =
                com.muoniumplayer.core.ncm.music.audio.OpenALAudioDevice.createIfAvailable();
        repackage.com.jsyn.devices.AudioDeviceOutputStream stream = manager.createOutputStream(0, 48000, 2);
        try {
            stream.start();
            java.lang.reflect.Field field = stream.getClass().getDeclaredField("sink");
            field.setAccessible(true);
            ((StreamingPcmSink) field.get(stream)).close(); // Deterministic terminal sink failure.
            assertThrows(IOException.class, () -> stream.write(new double[128]));
            java.util.concurrent.atomic.AtomicReference<Throwable> result = new java.util.concurrent.atomic.AtomicReference<>();
            Thread writer = new Thread(() -> {
                try { stream.write(new double[128]); } catch (Throwable e) { result.set(e); }
            }, "test-jsyn-failed-output");
            writer.setDaemon(true);
            writer.start();
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (writer.getState() != Thread.State.WAITING && System.nanoTime() < until) Thread.sleep(1);
            assertEquals(Thread.State.WAITING, writer.getState());
            assertNull(result.get());
            stream.stop();
            writer.join(3000);
            assertFalse(writer.isAlive());
            assertInstanceOf(IOException.class, result.get());
        } finally { stream.close(); }
    }

    private static void requireLoopback() {
        try {
            Assumptions.assumeTrue(ALC10.alcIsExtensionPresent(0, "ALC_SOFT_loopback"),
                    "Installed OpenAL does not expose ALC_SOFT_loopback");
        } catch (UnsatisfiedLinkError | NoClassDefFoundError unavailable) {
            Assumptions.assumeTrue(false, "OpenAL native library unavailable: " + unavailable);
        }
    }

    /** A foreign live TLS context representing another audio subsystem. */
    private static final class AmbientContext implements AutoCloseable {
        final ALCCapabilities oldAlc;
        final ALCapabilities oldAl;
        final long oldContext;
        final long device;
        final long context;
        final ALCCapabilities alc;
        final ALCapabilities al;
        AmbientContext() {
            oldAlc = ALC.getCapabilities();
            ALCapabilities saved;
            try { saved = AL.getCapabilities(); } catch (IllegalStateException absent) { saved = null; }
            oldAl = saved;
            device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
            assertNotEquals(0, device);
            alc = ALC.createCapabilities(device);
            assertTrue(alc.ALC_EXT_thread_local_context);
            oldContext = EXTThreadLocalContext.alcGetThreadContext();
            IntBuffer attributes = BufferUtils.createIntBuffer(7);
            attributes.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT)
                    .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_FLOAT_SOFT)
                    .put(ALC10.ALC_FREQUENCY).put(48000).put(0).flip();
            context = ALC10.alcCreateContext(device, attributes);
            assertNotEquals(0, context);
            assertTrue(EXTThreadLocalContext.alcSetThreadContext(context));
            al = AL.createCapabilities(alc);
        }
        void assertCurrent() {
            assertEquals(context, EXTThreadLocalContext.alcGetThreadContext());
            assertSame(al, AL.getCapabilities());
            assertSame(alc, ALC.getCapabilities());
        }
        @Override public void close() {
            ALC.setCapabilities(alc);
            assertTrue(EXTThreadLocalContext.alcSetThreadContext(oldContext));
            AL.setCurrentThread(oldContext != 0 ? oldAl : null);
            ALC10.alcDestroyContext(context);
            assertTrue(ALC10.alcCloseDevice(device));
            ALC.setCapabilities(oldAlc);
        }
    }
}
