package com.muoniumplayer.core.audio.ffmpeg;

import org.bytedeco.ffmpeg.avcodec.*;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.*;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.javacpp.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swresample.*;

/**
 * Blocking demux/decode/resample core for a background worker. The opening thread owns
 * read/seek; callers must not run them on audio/render/client threads. Only cancel and
 * close are cross-thread operations. close requests interruption, then waits on the
 * operation lock before freeing anything. A read's native output is bounded to 32 MiB;
 * no whole-track buffer, child process, JavaCV, or codec fallback is involved.
 */
public final class FfmpegAudioDecoder implements AutoCloseable {
    private static final int MAX_SOURCE_FRAMES = 262144;
    private static final int MAX_CONVERTED_FRAMES = 1048576;
    private final Object operationLock = new Object();
    private final Thread owner = Thread.currentThread();
    private final AudioInput input;
    private final OutputSpec output;
    private final CancellationToken token;
    private volatile boolean cancelled;
    private volatile boolean closing;
    private volatile long deadline;
    private volatile boolean timedOut;
    private boolean closed;
    // Allocated with av_* / swr_*: freed with the matching API, never Pointer.close().
    private AVFormatContext format;
    private AVCodecContext codec;
    private AVPacket packet;
    private AVFrame frame;
    private SwrContext resampler;
    // Allocated with JavaCPP: explicit close after layout_uninit where applicable.
    private AVChannelLayout sourceLayout;
    private AVChannelLayout outputLayout;
    private AVIOInterruptCB.Callback_Pointer interruptCallback;
    private PointerPointer<Pointer> outputPlanes;
    private PointerPointer<Pointer> trimmedInputPlanes;
    private FloatPointer converted;
    private int convertedCapacity;
    private int convertedFrames;
    private int convertedOffset;
    private int sourceFormat;
    private int sourceRate;
    private int outputRate;
    private int channels;
    private long outputChannelMask;
    private int streamIndex;
    private boolean frameReady;
    private boolean packetPending;
    private boolean demuxEof;
    private boolean flushSent;
    private boolean decoderEof;
    private boolean resamplerEof;
    private boolean needSeekAnchor;
    private long seekTargetFrame = -1;
    private long outputFrame;
    private AudioInfo info;
    private long expectedSourceFrames = -1;
    private long decodedSourceFrames;
    private boolean verifySourceLength = true;

    private FfmpegAudioDecoder(AudioInput input, OutputSpec output, CancellationToken token) {
        this.input = input;
        this.output = output;
        this.token = token;
    }

    public static FfmpegAudioDecoder open(AudioInput input, OutputSpec output, CancellationToken token) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(token, "token").throwIfCancelled();
        assertBackgroundThread();
        FfmpegRuntime.ensureLoaded();
        FfmpegAudioDecoder decoder = new FfmpegAudioDecoder(input, output, token);
        synchronized (decoder.operationLock) {
            try {
                decoder.begin(input.options().openTimeout());
                decoder.initialize();
                return decoder;
            } catch (IOException | RuntimeException | Error e) {
                decoder.release();
                throw e;
            } finally {
                decoder.deadline = 0;
            }
        }
    }

    /** Metadata is immutable, native-free and safe to share with other threads. */
    public AudioInfo info() { return info; }

    /** Native speaker mask, or zero for unspecified/custom/ambisonic channel order. */
    public long outputChannelMask() { return outputChannelMask; }

    private void initialize() throws IOException {
        interruptCallback = new AVIOInterruptCB.Callback_Pointer() {
            @Override public int call(Pointer ignored) {
                if (cancelled || closing || token.isCancelled() || owner.isInterrupted()) return 1;
                long until = deadline;
                if (until != 0 && System.nanoTime() - until >= 0) {
                    timedOut = true;
                    return 1;
                }
                return 0;
            }
        };
        Map<String, String> unused = openInput(input.location(), null);
        if (input.filePath() != null && format.iformat().name().getString().equals("flac")) {
            LocalFileReadView view = LocalFileReadView.excludingTrailingTag(input.filePath(), token);
            checkInterrupted();
            if (view != null) {
                avformat_close_input(format);
                unused = openInput(view.location(), view);
            }
        }
        check("avformat_find_stream_info", avformat_find_stream_info(format, (PointerPointer<?>) null));
        AVCodec selected = new AVCodec((Pointer) null); // Borrowed static decoder definition.
        streamIndex = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, input.options().streamIndex(), -1, selected, 0);
        check("av_find_best_stream(audio)", streamIndex);
        if (input.options().streamIndex() >= 0 && streamIndex != input.options().streamIndex()) {
            throw new IOException("Requested audio stream was not selected");
        }
        codec = avcodec_alloc_context3(selected);
        requirePointer(codec, "avcodec_alloc_context3");
        AVStream stream = format.streams(streamIndex); // All stream/parameter views are borrowed.
        check("avcodec_parameters_to_context", avcodec_parameters_to_context(codec, stream.codecpar()));
        codec.pkt_timebase(stream.time_base());
        codec.err_recognition(AV_EF_CRCCHECK | AV_EF_EXPLODE);
        check("avcodec_open2", avcodec_open2(codec, selected, (PointerPointer<?>) null));
        packet = av_packet_alloc();
        requirePointer(packet, "av_packet_alloc");
        frame = av_frame_alloc();
        requirePointer(frame, "av_frame_alloc");
        if (!receiveFrame()) throw new IOException("Audio stream contains no decoded PCM frames");
        configureFromFirstFrame();
        // FFmpeg 8.1.2 flacdec.c stores STREAMINFO total_samples as duration with 1/rate timebase.
        // Other codec/container durations may be estimates and are deliberately not checked this way.
        if (format.iformat().name().getString().equals("flac")
                && stream.codecpar().codec_id() == AV_CODEC_ID_FLAC && stream.duration() > 0
                && stream.time_base().num() == 1 && stream.time_base().den() == sourceRate) {
            expectedSourceFrames = stream.duration();
        }
        long duration = format.duration() == AV_NOPTS_VALUE ? -1 : format.duration();
        if (stream.duration() != AV_NOPTS_VALUE && stream.duration() >= 0) {
            try (AVRational micros = new AVRational().num(1).den(AV_TIME_BASE)) {
                duration = av_rescale_q(stream.duration(), stream.time_base(), micros);
            }
        }
        AVIOContext io = format.pb();
        boolean canSeek = !input.options().live() && ((io != null && !io.isNull()
                && (io.seekable() & (AVIO_SEEKABLE_NORMAL | AVIO_SEEKABLE_TIME)) != 0)
                || (duration > 0 && (format.iformat().flags() & AVFMT_NOFILE) != 0));
        info = new AudioInfo(streamIndex, selected.name().getString(), format.iformat().name().getString(),
                sourceRate, sourceLayout.nb_channels(), describe(sourceLayout), outputRate, channels,
                describe(outputLayout), duration < 0 ? -1 : duration, canSeek, unused);
    }

    private Map<String, String> openInput(String location, LocalFileReadView view) throws IOException {
        format = avformat_alloc_context();
        requirePointer(format, "avformat_alloc_context");
        // This is an embedded borrowed struct; only the callback owns a JavaCPP allocation.
        format.interrupt_callback().callback(interruptCallback).opaque(null);
        AVDictionary options = new AVDictionary((Pointer) null);
        Map<String, String> unused = new LinkedHashMap<>();
        try {
            set(options, "protocol_whitelist", input.network() ? "http,https,tcp,tls,crypto" : "file,subfile,http,https,tcp,tls,crypto");
            set(options, "rw_timeout", Long.toString(Math.max(1, input.options().readTimeout().toNanos() / 1000)));
            set(options, "probesize", "8388608");
            set(options, "analyzeduration", "5000000");
            for (Map.Entry<String, String> entry : input.options().options().entrySet()) set(options, entry.getKey(), entry.getValue());
            if (view != null) {
                // URL parameters are also supplied as final options: caller-provided start/end
                // must not override the internal verified file boundary during protocol open.
                set(options, "start", "0");
                set(options, "end", Long.toString(view.endExclusive()));
            }
            // Enforce verification even when a playlist redirects or nests HTTPS resources.
            set(options, "tls_verify", "1");
            if (!input.options().headers().isEmpty()) {
                StringBuilder headers = new StringBuilder();
                input.options().headers().forEach((name, value) -> headers.append(name).append(": ").append(value).append("\r\n"));
                set(options, "headers", headers.toString());
            }
            check("avformat_open_input", avformat_open_input(format, location, null, options));
            AVDictionaryEntry entry = null;
            while ((entry = av_dict_get(options, "", entry, AV_DICT_IGNORE_SUFFIX)) != null && !entry.isNull()) {
                unused.put(entry.key().getString(), entry.value().getString());
            }
        } finally {
            av_dict_free(options);
        }
        return unused;
    }

    private void configureFromFirstFrame() throws IOException {
        sourceRate = frame.sample_rate();
        if (sourceRate < 1 || sourceRate > 768000 || frame.format() < 0) throw new IOException("Invalid decoded PCM format");
        validateLayout(frame.ch_layout());
        sourceLayout = new AVChannelLayout();
        outputLayout = new AVChannelLayout();
        check("av_channel_layout_copy(source)", av_channel_layout_copy(sourceLayout, frame.ch_layout()));
        if (output.channelLayout() == null) {
            check("av_channel_layout_copy(output)", av_channel_layout_copy(outputLayout, sourceLayout));
        } else {
            check("av_channel_layout_from_string", av_channel_layout_from_string(outputLayout, output.channelLayout()));
        }
        validateLayout(outputLayout);
        sourceFormat = frame.format();
        outputRate = output.sampleRate() == 0 ? sourceRate : output.sampleRate();
        channels = outputLayout.nb_channels();
        outputChannelMask = outputLayout.order() == AV_CHANNEL_ORDER_NATIVE ? outputLayout.u_mask() : 0;
        outputPlanes = new PointerPointer<>(1);
        trimmedInputPlanes = new PointerPointer<>(8);
        initializeResampler();
    }

    private void initializeResampler() throws IOException {
        if (resampler != null && !resampler.isNull()) swr_free(resampler);
        resampler = new SwrContext((Pointer) null);
        check("swr_alloc_set_opts2", swr_alloc_set_opts2(resampler, outputLayout, AV_SAMPLE_FMT_FLT,
                outputRate, sourceLayout, sourceFormat, sourceRate, 0, null));
        check("swr_init", swr_init(resampler));
    }

    /** Returns actual frames, or -1 only after codec and resampler have both drained. */
    public int read(PcmBlock block) throws IOException {
        Objects.requireNonNull(block, "block");
        synchronized (operationLock) {
            checkOwner();
            block.frames(0);
            if (block.channels() != channels) throw new IllegalArgumentException("PCM block channel count does not match output");
            begin(input.options().readTimeout());
            try {
                while (true) {
                    checkInterrupted();
                    if (convertedOffset < convertedFrames) {
                        long skip = seekTargetFrame < 0 ? 0 : Math.max(0, seekTargetFrame - outputFrame);
                        int dropped = (int) Math.min(skip, convertedFrames - convertedOffset);
                        convertedOffset += dropped;
                        outputFrame += dropped;
                        if (convertedOffset == convertedFrames) continue;
                        seekTargetFrame = -1;
                        int count = Math.min(block.capacityFrames(), convertedFrames - convertedOffset);
                        converted.position((long) convertedOffset * channels).get(block.samples(), 0, count * channels);
                        converted.position(0);
                        block.startFrame(outputFrame);
                        block.frames(count);
                        outputFrame += count;
                        convertedOffset += count;
                        return count;
                    }
                    if (resamplerEof) return -1;
                    if (frameReady) {
                        try { convertFrame(); } finally { av_frame_unref(frame); frameReady = false; }
                    } else if (decoderEof) {
                        if (verifySourceLength && expectedSourceFrames >= 0 && decodedSourceFrames != expectedSourceFrames) {
                            throw new IOException("FLAC decoded sample count differs from declared stream duration" );
                        }
                        convert(null, 0);
                        if (convertedFrames == 0) resamplerEof = true;
                    } else {
                        receiveFrame();
                    }
                }
            } finally { deadline = 0; }
        }
    }

    private void convertFrame() throws IOException {
        if (frame.sample_rate() != sourceRate || frame.format() != sourceFormat
                || av_channel_layout_compare(sourceLayout, frame.ch_layout()) != 0) {
            throw new IOException("Audio format changed mid-stream; reopen the decoder to renegotiate output");
        }
        int frames = frame.nb_samples();
        if (frames < 0 || frames > MAX_SOURCE_FRAMES) throw new IOException("Decoded PCM frame exceeds bounded input limit");
        decodedSourceFrames += frames;
        if (verifySourceLength && expectedSourceFrames >= 0 && decodedSourceFrames > expectedSourceFrames) {
            throw new IOException("FLAC decoded more samples than declared stream duration" );
        }
        int skip = 0;
        if (needSeekAnchor) {
            long timestamp = frame.best_effort_timestamp();
            if (timestamp == AV_NOPTS_VALUE) throw new IOException("Exact audio seek requires a decoded frame timestamp");
            AVStream stream = format.streams(streamIndex);
            long start = stream.start_time() == AV_NOPTS_VALUE ? 0 : stream.start_time();
            long sourcePosition;
            try (AVRational samples = new AVRational().num(1).den(sourceRate)) {
                sourcePosition = av_rescale_q(timestamp - start, stream.time_base(), samples);
            }
            // Reset swr on the same rational phase as a decode starting at media frame zero.
            // Rounding an arbitrary packet timestamp to output frames would shift the filter phase.
            int phasePeriod = sourceRate / gcd(sourceRate, outputRate);
            long remainder = Math.floorMod(sourcePosition, phasePeriod);
            skip = remainder == 0 ? 0 : (int) (phasePeriod - remainder);
            if (skip >= frames) { convertedFrames = convertedOffset = 0; return; }
            outputFrame = Math.multiplyExact((sourcePosition + skip) / phasePeriod,
                    (long) outputRate / gcd(sourceRate, outputRate));
            needSeekAnchor = false;
        }
        if (skip == 0) {
            convert(frame.extended_data(), frames);
        } else {
            int bytes = av_get_bytes_per_sample(sourceFormat);
            if (bytes <= 0) throw new IOException("Invalid native sample size");
            boolean planar = av_sample_fmt_is_planar(sourceFormat) != 0;
            int planes = planar ? sourceLayout.nb_channels() : 1;
            long offset = (long) skip * bytes * (planar ? 1 : sourceLayout.nb_channels());
            for (int i = 0; i < planes; i++) {
                trimmedInputPlanes.put(i, new BytePointer(frame.extended_data(i)).position(offset));
            }
            convert(trimmedInputPlanes, frames - skip);
        }
    }

    private void convert(PointerPointer<?> planes, int inputFrames) throws IOException {
        int capacity = swr_get_out_samples(resampler, inputFrames);
        check("swr_get_out_samples", capacity);
        if (capacity > MAX_CONVERTED_FRAMES) throw new IOException("Resampled PCM frame exceeds bounded output limit");
        capacity = Math.max(1, capacity);
        if (converted == null || convertedCapacity < capacity) {
            if (converted != null) converted.close();
            converted = new FloatPointer((long) capacity * channels);
            convertedCapacity = capacity;
            outputPlanes.put(0, converted);
        }
        converted.position(0);
        convertedFrames = swr_convert(resampler, outputPlanes, convertedCapacity, planes, inputFrames);
        check("swr_convert", convertedFrames);
        convertedOffset = 0;
    }

    /** FFmpeg receive-first state machine. A pending packet is retained on send EAGAIN. */
    private boolean receiveFrame() throws IOException {
        boolean sendWasEagain = false;
        while (true) {
            checkInterrupted();
            int result = avcodec_receive_frame(codec, frame);
            if (result == 0) { frameReady = true; return true; }
            if (result == AVERROR_EOF) { decoderEof = true; return false; }
            if (result != AVERROR_EAGAIN()) check("avcodec_receive_frame", result);
            if (sendWasEagain) throw new IOException("FFmpeg decoder returned EAGAIN from both send and receive");
            if (packetPending) {
                result = avcodec_send_packet(codec, packet);
                if (result == AVERROR_EAGAIN()) { sendWasEagain = true; continue; }
                check("avcodec_send_packet", result);
                av_packet_unref(packet);
                packetPending = false;
                continue;
            }
            if (demuxEof) {
                if (flushSent) throw new IOException("FFmpeg decoder requested input after accepting EOF");
                result = avcodec_send_packet(codec, null);
                if (result == AVERROR_EAGAIN()) { sendWasEagain = true; continue; }
                if (result == AVERROR_EOF) { decoderEof = true; return false; }
                check("avcodec_send_packet(flush)", result);
                flushSent = true;
                continue;
            }
            result = av_read_frame(format, packet);
            if (result == AVERROR_EAGAIN()) {
                LockSupport.parkNanos(1000000); // bounded retry; cancellation/timeout checked above
                continue;
            }
            if (result == AVERROR_EOF) { demuxEof = true; continue; }
            check("av_read_frame", result);
            if (packet.stream_index() != streamIndex) { av_packet_unref(packet); continue; }
            if ((packet.flags() & AV_PKT_FLAG_CORRUPT) != 0) {
                throw new IOException("av_read_frame returned a corrupt audio packet (FFmpeg AV_PKT_FLAG_CORRUPT)" );
            }
            packetPending = true;
        }
    }

    /**
     * Positions the next read at floor(position * outputRate), after decoder/swr preroll.
     * Live and non-seekable inputs fail explicitly. The native seek result remains authoritative.
     */
    public void seek(Duration position) throws IOException {
        Objects.requireNonNull(position, "position");
        if (position.isNegative()) throw new IllegalArgumentException("Negative seek");
        long micros;
        try { micros = Math.addExact(Math.multiplyExact(position.getSeconds(), 1000000L), position.getNano() / 1000); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Seek position too large", e); }
        synchronized (operationLock) {
            checkOwner();
            if (!info.seekable()) throw new IOException("Audio input is live or not seekable");
            begin(input.options().readTimeout());
            try {
                AVStream stream = format.streams(streamIndex);
                long target;
                try (AVRational time = new AVRational().num(1).den(AV_TIME_BASE)) {
                    target = av_rescale_q(Math.max(0, micros - 2000000L), time, stream.time_base());
                }
                boolean sampleAccurateTimebase = stream.time_base().num() == 1 && stream.time_base().den() % sourceRate == 0;
                if (!sampleAccurateTimebase) target = 0; // Coarse timestamps require counting from media start.
                if (stream.start_time() != AV_NOPTS_VALUE) target = Math.addExact(target, stream.start_time());
                check("avformat_seek_file", avformat_seek_file(format, streamIndex, Long.MIN_VALUE, target, target, 0));
                avcodec_flush_buffers(codec);
                av_packet_unref(packet);
                av_frame_unref(frame);
                initializeResampler();
                frameReady = packetPending = demuxEof = flushSent = decoderEof = resamplerEof = false;
                convertedFrames = convertedOffset = 0;
                seekTargetFrame = Math.addExact(Math.multiplyExact(position.getSeconds(), (long) outputRate),
                        (long) position.getNano() * outputRate / 1000000000L);
                needSeekAnchor = sampleAccurateTimebase;
                if (!sampleAccurateTimebase) outputFrame = 0;
                verifySourceLength = false;
            } catch (ArithmeticException e) { throw new IOException("Seek timestamp overflow", e); }
            finally { deadline = 0; }
        }
    }

    /** Safe from any thread. No native work and no locks. */
    public void cancel() { cancelled = true; }

    /** Cancellation first; resource freeing is serialized after the active native operation. */
    @Override public void close() {
        closing = true;
        synchronized (operationLock) { release(); }
    }

    private void release() {
        if (closed) return;
        closed = true;
        if (packet != null && !packet.isNull()) av_packet_free(packet);
        if (frame != null && !frame.isNull()) av_frame_free(frame);
        if (resampler != null && !resampler.isNull()) swr_free(resampler);
        if (codec != null && !codec.isNull()) avcodec_free_context(codec);
        // close_input releases IO and the format. open_input already frees/nulls on failure.
        if (format != null && !format.isNull()) avformat_close_input(format);
        if (sourceLayout != null) { av_channel_layout_uninit(sourceLayout); sourceLayout.close(); }
        if (outputLayout != null) { av_channel_layout_uninit(outputLayout); outputLayout.close(); }
        if (outputPlanes != null) outputPlanes.close();
        if (trimmedInputPlanes != null) trimmedInputPlanes.close();
        if (converted != null) converted.close();
        // Strong reference stays alive until format/IO are fully closed.
        if (interruptCallback != null) interruptCallback.close();
    }

    private void begin(Duration timeout) throws IOException {
        timedOut = false;
        deadline = System.nanoTime() + timeout.toNanos();
        checkInterrupted();
    }
    private void checkOwner() throws IOException {
        if (Thread.currentThread() != owner) throw new IOException("Audio decoder read/seek must stay on its opening worker thread");
        if (closed) { checkInterrupted(); throw new IOException("Audio decoder is closed"); }
    }
    private void checkInterrupted() throws IOException {
        if (cancelled || closing || token.isCancelled() || owner.isInterrupted()) {
            throw new InterruptedIOException("Audio decode cancelled");
        }
        if (timedOut || (deadline != 0 && System.nanoTime() - deadline >= 0)) {
            throw new SocketTimeoutException("FFmpeg audio operation timed out");
        }
    }
    private void check(String operation, int result) throws IOException {
        if (result >= 0) { checkInterrupted(); return; }
        IOException nativeError = NativeErrors.failure(operation, result);
        try { checkInterrupted(); } catch (IOException interrupted) { interrupted.initCause(nativeError); throw interrupted; }
        throw nativeError;
    }
    private static void set(AVDictionary options, String name, String value) throws IOException {
        NativeErrors.check("av_dict_set(" + name + ")", av_dict_set(options, name, value, 0));
    }
    private static void requirePointer(Pointer value, String operation) throws IOException {
        if (value == null || value.isNull()) throw new IOException(operation + " returned a null native pointer");
    }
    private static void validateLayout(AVChannelLayout layout) throws IOException {
        if (av_channel_layout_check(layout) == 0 || layout.nb_channels() < 1 || layout.nb_channels() > 8) {
            throw new IOException("Audio requires a valid channel layout with 1..8 channels");
        }
    }
    private static String describe(AVChannelLayout layout) throws IOException {
        try (BytePointer text = new BytePointer(1024)) {
            int needed = av_channel_layout_describe(layout, text, text.capacity());
            NativeErrors.check("av_channel_layout_describe", needed);
            if (needed > text.capacity()) throw new IOException("Channel layout description exceeds bounded buffer" );
            return text.limit(0).getString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private static int gcd(int a, int b) {
        while (b != 0) { int r = a % b; a = b; b = r; }
        return a;
    }
    private static void assertBackgroundThread() throws IOException {
        String name = Thread.currentThread().getName();
        if (name.equals("Render thread") || name.equals("Client thread") || name.startsWith("JSyn")) {
            throw new IOException("Blocking FFmpeg operations require a background worker");
        }
    }
}
