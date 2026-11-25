package com.muoniumplayer.core.ncm.music.audio;

import java.io.IOException;
import java.util.Objects;

import com.muoniumplayer.core.audio.output.OpenAlPcmSink;
import com.muoniumplayer.core.audio.output.StreamingPcmSink;
import repackage.com.jsyn.devices.AudioDeviceInputStream;
import repackage.com.jsyn.devices.AudioDeviceManager;
import repackage.com.jsyn.devices.AudioDeviceOutputStream;

/**
 * JSyn adapter for the sole music output backend. JSyn/DSP remains mono/stereo;
 * direct multichannel producers use StreamingPcmSink, without passing through
 * the legacy stereo engine. The manager owns no native resources. Each started
 * stream owns one sink, and stop/close releases it on the sink's native thread.
 */
public final class OpenALAudioDevice implements AudioDeviceManager {
    private OpenALAudioDevice() {}

    /**
     * Creates a logical default-device manager. Native device/extension probing
     * happens at stream start on the native owner, and fails explicitly there.
     * This never constructs JavaSound or falls back to another output backend.
     */
    public static AudioDeviceManager createIfAvailable() { return new OpenALAudioDevice(); }

    @Override public int getDeviceCount() { return 1; }
    @Override public String getDeviceName(int id) { return "OpenAL music output"; }
    @Override public String getName() { return "OpenAL"; }
    @Override public int getDefaultInputDeviceID() { return -1; }
    @Override public int getDefaultOutputDeviceID() { return 0; }
    @Override public int getMaxInputChannels(int id) { return 0; }
    /** Legacy JSyn path supports stereo; this does not advertise hardware channels. */
    @Override public int getMaxOutputChannels(int id) { return 2; }
    @Override public double getDefaultLowInputLatency(int id) { return 0; }
    @Override public double getDefaultHighInputLatency(int id) { return 0; }
    @Override public double getDefaultLowOutputLatency(int id) { return 0.04; }
    @Override public double getDefaultHighOutputLatency(int id) { return 0.25; }
    @Override public int setSuggestedInputLatency(double latency) { return 0; }
    @Override public int setSuggestedOutputLatency(double latency) { return 0; }

    @Override public AudioDeviceOutputStream createOutputStream(int id, int rate, int channels) {
        if (id != 0 && id != USE_DEFAULT_DEVICE) throw new IllegalArgumentException("Unknown output device");
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("JSyn DSP supports 1/2 channels; use StreamingPcmSink for 5.1/7.1");
        }
        if (rate <= 0) throw new IllegalArgumentException("Invalid output sample rate");
        return new Stream(rate, channels);
    }

    @Override public AudioDeviceInputStream createInputStream(int id, int rate, int channels) {
        throw new UnsupportedOperationException("OpenAL music output does not provide capture");
    }

    private static final class Stream implements AudioDeviceOutputStream {
        private final int rate;
        private final int channels;
        private final float[] staging;
        private final Object writer = new Object();
        private final Object lifecycle = new Object();
        private final Object failureState = new Object();
        private volatile StreamingPcmSink sink;
        private IOException terminalFailure;
        private int stagedSamples;

        Stream(int rate, int channels) {
            this.rate = rate;
            this.channels = channels;
            staging = new float[1024 * channels];
        }

        @Override public void start() {
            synchronized (lifecycle) {
                if (sink != null) return;
                try {
                    StreamingPcmSink opened = OpenAlPcmSink.open(rate, channels);
                    synchronized (failureState) { terminalFailure = null; sink = opened; }
                }
                catch (IOException e) { throw new IllegalStateException("Cannot start OpenAL music output", e); }
            }
        }

        @Override public void write(double value) throws IOException {
            synchronized (writer) {
                StreamingPcmSink current = requireSink();
                staging[stagedSamples++] = (float) value;
                flushFrames(current);
            }
        }

        @Override public void write(double[] values) throws IOException { write(values, 0, values.length); }

        @Override public void write(double[] values, int offset, int count) throws IOException {
            Objects.checkFromIndexSize(offset, count, values.length);
            synchronized (writer) {
                StreamingPcmSink current = requireSink();
                while (count > 0) {
                    int copied = Math.min(count, staging.length - stagedSamples);
                    for (int i = 0; i < copied; i++) staging[stagedSamples + i] = (float) values[offset + i];
                    stagedSamples += copied;
                    offset += copied;
                    count -= copied;
                    flushFrames(current);
                }
            }
        }

        private StreamingPcmSink requireSink() throws IOException {
            StreamingPcmSink current = sink;
            if (current == null) throw new IOException("OpenAL stream is not started");
            synchronized (failureState) {
                // JSyn catches output errors and continues its loop. After the
                // first reported failure, park instead of flooding logs/CPU.
                // stop/close signals this wait; Engine requestStop interrupts it.
                while (terminalFailure != null && sink == current) {
                    try { failureState.wait(); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        java.io.InterruptedIOException interrupted =
                                new java.io.InterruptedIOException("Failed OpenAL output interrupted");
                        interrupted.initCause(e);
                        throw interrupted;
                    }
                }
                if (sink != current) throw new IOException("OpenAL stream was stopped");
            }
            return current;
        }

        private void flushFrames(StreamingPcmSink current) throws IOException {
            int frames = stagedSamples / channels;
            if (frames == 0) return;
            int consumed = frames * channels;
            try { current.write(staging, 0, frames); }
            catch (IOException e) {
                synchronized (failureState) { terminalFailure = e; }
                throw e;
            }
            finally {
                // A cancelled write must not replay partially accepted audio.
                int remainder = stagedSamples - consumed;
                System.arraycopy(staging, consumed, staging, 0, remainder);
                stagedSamples = remainder;
            }
        }

        @Override public double getLatency() {
            StreamingPcmSink current = sink;
            return current == null ? 0 : current.latencySeconds();
        }

        @Override public void stop() {
            try { close(); }
            catch (IOException e) { throw new IllegalStateException("Cannot release OpenAL music output", e); }
        }

        @Override public void close() throws IOException {
            synchronized (lifecycle) {
                StreamingPcmSink current = sink;
                synchronized (failureState) { sink = null; failureState.notifyAll(); }
                // Do not take writer before closing: a writer may be waiting for
                // queue space and needs this close request to release it.
                try { if (current != null) current.close(); }
                finally { synchronized (writer) { stagedSamples = 0; } }
            }
        }
    }
}
