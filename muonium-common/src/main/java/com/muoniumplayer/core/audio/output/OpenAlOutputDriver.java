package com.muoniumplayer.core.audio.output;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTFloat32;
import org.lwjgl.openal.EXTMCFormats;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.openal.SOFTDirectChannels;
import org.lwjgl.openal.SOFTDirectChannelsRemix;
import org.lwjgl.openal.SOFTSourceSpatialize;
import org.lwjgl.system.MemoryUtil;

/** Private device/context, source and buffer pool, all owned by one thread. */
final class OpenAlOutputDriver implements PcmOutputDriver {
    private static final int BUFFER_COUNT = 4;
    private static final int[] CHANNELS = {1, 2, 6, 8};
    private final boolean loopback;
    private final int[] buffers = new int[BUFFER_COUNT];
    private final int[] free = new int[BUFFER_COUNT];
    private final int[] bufferFrames = new int[BUFFER_COUNT];
    private Thread owner;
    private long device;
    private long context;
    private long previousContext;
    private ALCCapabilities previousAlc;
    private ALCapabilities previousAl;
    private boolean contextBound;
    private boolean capabilitiesChanged;
    private int source;
    private int freeCount;
    private int pendingFrames;
    private int sampleRate;
    private int inputChannels;
    private int outputChannels;
    private int format;
    private boolean float32;
    private boolean paused;
    private ALCapabilities ownedAl;
    private ALCCapabilities ownedAlc;
    private PcmOutputCapabilities capabilities;
    private ByteBuffer upload;

    OpenAlOutputDriver() { this(false); }
    /** Package-private loopback route for deterministic native tests, never a fallback. */
    OpenAlOutputDriver(boolean loopback) { this.loopback = loopback; }

    @Override
    public void open(int sampleRate, int channels) throws IOException {
        if (owner != null) throw new IllegalStateException("OpenAL driver cannot be reopened");
        owner = Thread.currentThread();
        this.sampleRate = sampleRate;
        inputChannels = channels;
        previousAlc = currentAlc();
        previousAl = currentAl();
        try {
            device = loopback ? SOFTLoopback.alcLoopbackOpenDeviceSOFT((ByteBuffer) null)
                    : ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0) throw new IOException("No OpenAL output device");
            ALCCapabilities alc = ALC.createCapabilities(device);
            ownedAlc = alc;
            capabilitiesChanged = true;
            if (!alc.ALC_EXT_thread_local_context) {
                throw new IOException("OpenAL requires ALC_EXT_thread_local_context; process context fallback is unsafe");
            }
            previousContext = EXTThreadLocalContext.alcGetThreadContext();
            if (previousContext != 0 && previousAl == null) {
                throw new IOException("Existing native TLS context has no matching LWJGL capabilities");
            }
            IntBuffer attributes = null;
            if (loopback) {
                int layout = channels == 6 ? SOFTLoopback.ALC_5POINT1_SOFT
                        : channels == 8 ? SOFTLoopback.ALC_7POINT1_SOFT : SOFTLoopback.ALC_STEREO_SOFT;
                attributes = BufferUtils.createIntBuffer(7);
                attributes.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(layout)
                        .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_FLOAT_SOFT)
                        .put(ALC10.ALC_FREQUENCY).put(sampleRate).put(0).flip();
            }
            context = ALC10.alcCreateContext(device, attributes);
            if (context == 0) throw new IOException("OpenAL context creation failed");
            if (!EXTThreadLocalContext.alcSetThreadContext(context)) {
                throw new IOException("Cannot bind OpenAL thread-local context");
            }
            contextBound = true;
            if (EXTThreadLocalContext.alcGetThreadContext() != context) {
                throw new IOException("OpenAL thread-local context verification failed");
            }
            // LWJGL sources verify that createCapabilities selects setCurrentThread
            // only when the extension exists AND a nonzero native TLS context is bound.
            ALCapabilities al = AL.createCapabilities(alc);
            AL.setCurrentThread(al);
            ownedAl = al;
            capabilities = probeFormats(al);
            // Mono normally spatializes in core AL. Without SOFT_source_spatialize,
            // duplicate mono into stereo instead of silently enabling panning/HRTF.
            outputChannels = channels == 1 && !al.AL_SOFT_source_spatialize ? 2 : channels;
            if (!capabilities.supportsChannels(outputChannels)) {
                throw new IOException("OpenAL cannot accept " + channels + " channels. Remap/downmix explicitly to stereo upstream; retain OpenAL output");
            }
            float32 = capabilities.supportsFloat32(outputChannels);
            format = format(outputChannels, float32);
            upload = MemoryUtil.memAlloc(OpenAlPcmSink.BLOCK_FRAMES * outputChannels * (float32 ? 4 : 2))
                    .order(ByteOrder.nativeOrder());
            source = AL10.alGenSources();
            check("create source");
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSource3f(source, AL10.AL_VELOCITY, 0f, 0f, 0f);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f);
            AL10.alSourcef(source, AL10.AL_GAIN, 1f);
            AL10.alSourcef(source, AL10.AL_PITCH, 1f);
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
            if (al.AL_SOFT_source_spatialize) {
                AL10.alSourcei(source, SOFTSourceSpatialize.AL_SOURCE_SPATIALIZE_SOFT, AL10.AL_FALSE);
            }
            if (al.AL_SOFT_direct_channels_remix) {
                // Preserve matching channels without virtual-speaker panning. Unlike
                // plain direct_channels=true, remix never drops unmatched C/surround
                // channels when the actual device happens to be stereo.
                AL10.alSourcei(source, SOFTDirectChannels.AL_DIRECT_CHANNELS_SOFT,
                        SOFTDirectChannelsRemix.AL_REMIX_UNMATCHED_SOFT);
            }
            check("configure non-spatial music source");
            for (int i = 0; i < BUFFER_COUNT; i++) {
                buffers[i] = AL10.alGenBuffers();
                check("create stream buffer");
                free[freeCount++] = buffers[i];
            }
        } finally { restoreBinding(); }
    }

    private PcmOutputCapabilities probeFormats(ALCapabilities al) throws IOException {
        int probe = AL10.alGenBuffers();
        check("create format probe");
        ByteBuffer frame = MemoryUtil.memCalloc(8 * 4).order(ByteOrder.nativeOrder());
        int integerMask = 0;
        int floatMask = 0;
        try {
            for (int channels : CHANNELS) {
                if (channels > 2 && (!al.AL_EXT_MCFORMATS || !al.AL_SOFT_direct_channels_remix)) continue;
                for (int bits : new int[] {16, 32}) {
                    if (bits == 32 && !al.AL_EXT_FLOAT32) continue;
                    frame.clear().limit(channels * (bits / 8));
                    AL10.alBufferData(probe, format(channels, bits == 32), frame, sampleRate);
                    if (AL10.alGetError() != AL10.AL_NO_ERROR) continue;
                    int actualChannels = AL10.alGetBufferi(probe, AL10.AL_CHANNELS);
                    int actualBits = AL10.alGetBufferi(probe, AL10.AL_BITS);
                    if (AL10.alGetError() != AL10.AL_NO_ERROR
                            || actualChannels != channels || actualBits != bits) continue;
                    if (bits == 32) floatMask |= 1 << channels;
                    else integerMask |= 1 << channels;
                }
            }
        } finally {
            MemoryUtil.memFree(frame);
            AL10.alDeleteBuffers(probe);
        }
        check("delete format probe");
        return new PcmOutputCapabilities(integerMask, floatMask, al.AL_SOFT_source_spatialize);
    }

    private static int format(int channels, boolean floating) {
        return switch (channels) {
            case 1 -> floating ? EXTFloat32.AL_FORMAT_MONO_FLOAT32 : AL10.AL_FORMAT_MONO16;
            case 2 -> floating ? EXTFloat32.AL_FORMAT_STEREO_FLOAT32 : AL10.AL_FORMAT_STEREO16;
            case 6 -> floating ? EXTMCFormats.AL_FORMAT_51CHN32 : EXTMCFormats.AL_FORMAT_51CHN16;
            case 8 -> floating ? EXTMCFormats.AL_FORMAT_71CHN32 : EXTMCFormats.AL_FORMAT_71CHN16;
            default -> throw new IllegalArgumentException("Unsupported channel count: " + channels);
        };
    }

    @Override public PcmOutputCapabilities capabilities() { return capabilities; }
    @Override public int outputChannels() { return outputChannels; }
    @Override public boolean usesFloat32() { return float32; }

    @Override public int freeBuffers() throws IOException {
        bind();
        try {
            recycleProcessed();
            return freeCount;
        } finally { restoreBinding(); }
    }

    private void recycleProcessed() throws IOException {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        check("query processed buffers");
        for (int i = 0; i < processed; i++) {
            int buffer = AL10.alSourceUnqueueBuffers(source);
            check("unqueue processed buffer");
            int slot = slot(buffer);
            pendingFrames -= bufferFrames[slot];
            bufferFrames[slot] = 0;
            if (freeCount >= BUFFER_COUNT) throw new IOException("OpenAL buffer pool overflow");
            free[freeCount++] = buffer;
        }
    }

    @Override public void submit(float[] samples, int frames) throws IOException {
        submit(samples, frames, null, 0);
    }

    /** Advance the real loopback device between enqueue and restart, without a speaker. */
    void submitWithRenderForTest(float[] samples, int frames, java.nio.FloatBuffer checkpoint,
                                int checkpointFrames) throws IOException {
        if (!loopback) throw new IllegalStateException("Not a loopback device");
        if (checkpoint == null || checkpointFrames < 1
                || (long) checkpointFrames * outputChannels > checkpoint.remaining()) {
            throw new IllegalArgumentException("Invalid loopback queue checkpoint");
        }
        submit(samples, frames, checkpoint, checkpointFrames);
    }

    private void submit(float[] samples, int frames, java.nio.FloatBuffer checkpoint,
                        int checkpointFrames) throws IOException {
        bind();
        try {
            if (frames < 1 || frames > OpenAlPcmSink.BLOCK_FRAMES || freeCount == 0) {
                throw new IOException("OpenAL submit exceeds bounded buffer capacity");
            }
            int buffer = free[--freeCount];
            PcmOutputEncoding.encode(samples, frames, inputChannels, inputChannels != outputChannels,
                    float32, upload);
            AL10.alBufferData(buffer, format, upload, sampleRate);
            check("upload PCM");
            // Quiesce consumption only after encoding/upload. A state check alone
            // cannot distinguish a late append to STOPPED from a new block that
            // finished before the next check. Pause preserves every unplayed
            // sample and makes queue editing stable; only explicit clear stops.
            AL10.alSourcePause(source);
            check("hold source while queueing PCM");
            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            check("query held music source");
            recycleProcessed();
            if (state == AL10.AL_STOPPED) {
                AL10.alSourceRewind(source);
                check("prepare drained music source");
            }
            AL10.alSourceQueueBuffers(source, buffer);
            check("queue PCM");
            bufferFrames[slot(buffer)] = frames;
            pendingFrames += frames;
            if (checkpoint != null) renderBound(checkpoint, checkpointFrames);
            playIfNeeded();
        } finally { restoreBinding(); }
    }

    private void playIfNeeded() throws IOException {
        if (paused || pendingFrames == 0) return;
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        check("query music source state");
        if (state == AL10.AL_PLAYING) return;
        if (state == AL10.AL_STOPPED) {
            // No new buffer can be appended to STOPPED: submit first holds and
            // rewinds it. Here a stopped queue therefore contains consumed data.
            recycleProcessed();
            AL10.alSourceRewind(source);
            check("rewind drained music source");
        }
        if (pendingFrames > 0) AL10.alSourcePlay(source);
        check("play queued PCM");
    }

    @Override public int queuedFrames() { checkOwner(); return pendingFrames; }
    @Override public void setGain(float gain) throws IOException {
        bind();
        try {
            AL10.alSourcef(source, AL10.AL_GAIN, gain);
            check("set music gain");
        } finally { restoreBinding(); }
    }
    @Override public void pause() throws IOException {
        bind();
        try {
            paused = true;
            AL10.alSourcePause(source);
            check("pause music source");
        } finally { restoreBinding(); }
    }
    @Override public void resume() throws IOException {
        bind();
        try {
            paused = false;
            playIfNeeded();
        } finally { restoreBinding(); }
    }

    @Override public void clear() throws IOException {
        bind();
        try { clearBound(); } finally { restoreBinding(); }
    }

    private void clearBound() throws IOException {
        if (source == 0) return;
        AL10.alSourceStop(source);
        check("stop music source");
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        check("query queued buffers");
        for (int i = 0; i < queued; i++) {
            AL10.alSourceUnqueueBuffers(source);
            check("clear queued buffer");
        }
        AL10.alSourcei(source, AL10.AL_BUFFER, 0);
        check("detach source buffers");
        pendingFrames = 0;
        freeCount = 0;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            bufferFrames[i] = 0;
            if (buffers[i] != 0) free[freeCount++] = buffers[i];
        }
    }

    /** Loopback tests render on the same native owner, without opening a speaker. */
    void renderForTest(java.nio.FloatBuffer output, int frames) throws IOException {
        if (!loopback) throw new IllegalStateException("Not a loopback device");
        bind();
        try { renderBound(output, frames); } finally { restoreBinding(); }
    }

    /** Source cursor evidence for loopback queue-transaction tests. */
    int sampleOffsetForTest() throws IOException {
        if (!loopback) throw new IllegalStateException("Not a loopback device");
        bind();
        try {
            int offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
            check("query loopback source position");
            return offset;
        } finally { restoreBinding(); }
    }

    private void renderBound(java.nio.FloatBuffer output, int frames) throws IOException {
        SOFTLoopback.alcRenderSamplesSOFT(device, output, frames);
        int error = ALC10.alcGetError(device);
        if (error != ALC10.ALC_NO_ERROR) throw new IOException("OpenAL loopback error: " + error);
    }

    private int slot(int buffer) throws IOException {
        for (int i = 0; i < BUFFER_COUNT; i++) if (buffers[i] == buffer) return i;
        throw new IOException("OpenAL returned a buffer outside this output's ownership");
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("OpenAL accessed outside its owner thread");
    }

    /** Fields are reused because this owner never nests scopes or invokes callbacks. */
    private void bind() throws IOException {
        checkOwner();
        if (contextBound || capabilitiesChanged) throw new IllegalStateException("Nested OpenAL scope");
        previousAlc = currentAlc();
        previousAl = currentAl();
        ALC.setCapabilities(ownedAlc);
        capabilitiesChanged = true;
        previousContext = EXTThreadLocalContext.alcGetThreadContext();
        if (previousContext != 0 && previousAl == null) {
            restoreBinding();
            throw new IOException("Native TLS context has no LWJGL capabilities");
        }
        if (!EXTThreadLocalContext.alcSetThreadContext(context)) {
            restoreBinding();
            throw new IOException("Cannot bind owned OpenAL TLS context");
        }
        contextBound = true;
        AL.setCurrentThread(ownedAl);
        if (EXTThreadLocalContext.alcGetThreadContext() != context || AL.getCapabilities() != ownedAl) {
            restoreBinding();
            throw new IOException("OpenAL TLS context/capabilities verification failed");
        }
    }

    private void restoreBinding() throws IOException {
        IOException failure = null;
        try {
            if (contextBound) {
                boolean restored = EXTThreadLocalContext.alcSetThreadContext(previousContext);
                boolean detached = restored;
                if (!restored) {
                    detached = EXTThreadLocalContext.alcSetThreadContext(0);
                    failure = new IOException("Could not restore native OpenAL TLS context");
                }
                AL.setCurrentThread(restored && previousContext != 0 ? previousAl : null);
                contextBound = !detached;
            }
        } finally {
            if (capabilitiesChanged) {
                ALC.setCapabilities(previousAlc);
                capabilitiesChanged = false;
            }
        }
        if (failure != null) throw failure;
    }

    private static void check(String operation) throws IOException {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) throw new IOException(operation + " failed: OpenAL error " + error);
    }

    @Override public void close() throws IOException {
        if (owner == null) return;
        checkOwner();
        IOException failure = null;
        try {
            if (ownedAl != null && context != 0) {
                bind();
                try {
                    try { clearBound(); } catch (IOException e) { failure = e; }
                    if (source != 0) { AL10.alDeleteSources(source); source = 0; }
                    for (int i = 0; i < BUFFER_COUNT; i++) {
                        if (buffers[i] != 0) {
                            AL10.alDeleteBuffers(buffers[i]);
                            buffers[i] = 0;
                        }
                    }
                    try { check("delete output objects"); }
                    catch (IOException e) { failure = combine(failure, e); }
                } finally { restoreBinding(); }
            }
        } catch (IOException e) { failure = combine(failure, e); }
        finally {
            if (upload != null) { MemoryUtil.memFree(upload); upload = null; }
            // Destruction needs ALC dispatch but no current AL context. Never
            // destroy a context whose native TLS detach failed.
            ALCCapabilities saved = currentAlc();
            try {
                if (ownedAlc != null) ALC.setCapabilities(ownedAlc);
                if (!contextBound && context != 0) {
                    ALC10.alcDestroyContext(context);
                    context = 0;
                }
                if (!contextBound && device != 0) {
                    if (!ALC10.alcCloseDevice(device)) {
                        failure = combine(failure, new IOException("OpenAL device close failed"));
                    }
                    device = 0;
                }
            } finally { ALC.setCapabilities(saved); }
        }
        if (failure != null) throw failure;
    }

    private static IOException combine(IOException existing, IOException next) {
        if (existing == null) return next;
        existing.addSuppressed(next);
        return existing;
    }

    private static ALCCapabilities currentAlc() {
        try { return ALC.getCapabilities(); } catch (IllegalStateException absent) { return null; }
    }
    private static ALCapabilities currentAl() {
        try { return AL.getCapabilities(); } catch (IllegalStateException absent) { return null; }
    }
}
