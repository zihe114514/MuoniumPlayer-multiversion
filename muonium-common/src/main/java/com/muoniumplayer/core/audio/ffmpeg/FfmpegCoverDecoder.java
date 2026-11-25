package com.muoniumplayer.core.audio.ffmpeg;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avcodec.AVPacketSideData;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVFrameSideData;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * One local-video pass on its calling worker, with independent demux/codec cursors.
 * FFmpeg 8.1.2 / JavaCPP 1.5.14: receive-first decoding, libswscale RGBA output,
 * and display-matrix transforms described by libavutil/display.h. No audio handles
 * or native-backed pixels escape. Cancel only signals; the owner finally frees IO,
 * frames, codec and scaler before releasing the strongly held AVIO callback.
 */
public final class FfmpegCoverDecoder implements AutoCloseable {
    public static final int FRAME_SIZE = 256;
    public static final int FRAMES_PER_SECOND = 10;
    public static final int MAX_FRAMES = 50;
    static final long MAX_FRAME_BYTES = 14L * 1024 * 1024;
    static final int MAX_SOURCE_DIMENSION = 4096;
    static final long MAX_SOURCE_PIXELS = 4096L * 2160;
    static final int MAX_DECODED_FRAMES = 1200;
    static final int MAX_PACKETS = 8192;
    private static final int MAX_STREAMS = 32;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final Thread owner = Thread.currentThread();
    private final File input;
    private final LongSupplier nanoTime;
    private final long deadline;
    private volatile boolean cancelled;
    private boolean started;
    private boolean released;
    private boolean timedOut;
    private int callbackChecks;
    private int callbackAborts;
    private AVIOInterruptCB.Callback_Pointer interruptCallback;
    private AVFormatContext format;
    private AVCodecContext codec;
    private AVPacket packet;
    private AVFrame frame;
    private AVFrame held;
    private AVFrame rgba;
    private SwsContext scaler;
    private AVStream stream; // Borrowed from format, never freed separately.
    private int streamIndex;
    private boolean packetPending;
    private boolean demuxEof;
    private boolean flushSent;
    private int packetsRead;
    private int decodedFrames;
    private int sourceWidth;
    private int sourceHeight;
    private int sourceFormat = -1;
    private int scaledWidth;
    private int scaledHeight;
    private int[] streamMatrix;
    private final byte[] row = new byte[FRAME_SIZE * 4];

    private FfmpegCoverDecoder(File input) { this(input, TIMEOUT, System::nanoTime); }

    // A monotonic clock allows deterministic deadline tests without a 30-second sleep.
    FfmpegCoverDecoder(File input, Duration timeout, LongSupplier nanoTime) {
        this.input = Objects.requireNonNull(input, "input");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        long budget = Objects.requireNonNull(timeout, "timeout").toNanos();
        if (budget <= 0 || budget > TIMEOUT.toNanos()) throw new IllegalArgumentException("Timeout must be in (0, 30s]");
        deadline = nanoTime.getAsLong() + budget;
    }

    /** Blocking worker-only operation. Failure discards the sequence; Error/OOM propagate. */
    public static List<BufferedImage> decode(File input) throws IOException {
        return new FfmpegCoverDecoder(input).decodeFrames();
    }

    /** The only operation allowed from another thread; it never frees native memory. */
    public void cancel() { cancelled = true; }

    List<BufferedImage> decodeFrames() throws IOException {
        checkOwner();
        if (started || released) throw new IOException("Cover decoder is single use");
        started = true;
        try {
            String name = owner.getName();
            if (name.equals("Render thread") || name.equals("Client thread") || name.startsWith("JSyn")) {
                throw new IOException("Cover decoding requires a background worker");
            }
            checkInterrupted();
            if (!input.isFile() || input.length() == 0) throw new IOException("Empty or missing cover video");
            FfmpegRuntime.ensureVideoLoaded();
            checkInterrupted();
            initialize();
            return sampleFrames();
        } finally {
            release();
        }
    }

    private void initialize() throws IOException {
        interruptCallback = new AVIOInterruptCB.Callback_Pointer() {
            @Override public int call(Pointer ignored) {
                callbackChecks++;
                if (abortRequested()) { callbackAborts++; return 1; }
                return 0;
            }
        };
        format = avformat_alloc_context();
        requirePointer(format, "avformat_alloc_context");
        format.interrupt_callback().callback(interruptCallback).opaque(null);
        format.probesize(1024 * 1024).max_analyze_duration(AV_TIME_BASE).max_probe_packets(128);
        AVDictionary options = new AVDictionary((Pointer) null);
        try {
            NativeErrors.check("protocol_whitelist", av_dict_set(options, "protocol_whitelist", "file", 0));
            check("avformat_open_input", avformat_open_input(format, input.getAbsolutePath(), null, options));
        } finally { av_dict_free(options); }
        if (format.nb_streams() < 1 || format.nb_streams() > MAX_STREAMS) throw new IOException("Cover stream count exceeds limit");
        // Do not call find_stream_info: it may decode probe frames then replay
        // those packets. Containers must expose a video stream in their header;
        // unknown dimensions are checked by max_pixels and the first decoded frame.
        AVCodec selected = new AVCodec((Pointer) null);
        streamIndex = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, selected, 0);
        check("av_find_best_stream(video)", streamIndex);
        stream = format.streams(streamIndex);
        if (stream.codecpar().width() != 0 || stream.codecpar().height() != 0) {
            validateDimensions(stream.codecpar().width(), stream.codecpar().height());
        }
        AVRational timeBase = stream.time_base();
        if (timeBase.num() <= 0 || timeBase.den() <= 0) throw new IOException("Invalid video time base");
        AVPacketSideData matrix = av_packet_side_data_get(stream.codecpar().coded_side_data(),
                stream.codecpar().nb_coded_side_data(), AV_PKT_DATA_DISPLAYMATRIX);
        if (matrix != null && !matrix.isNull()) streamMatrix = readMatrix(matrix.data(), matrix.size());
        codec = avcodec_alloc_context3(selected);
        requirePointer(codec, "avcodec_alloc_context3");
        check("avcodec_parameters_to_context", avcodec_parameters_to_context(codec, stream.codecpar()));
        codec.pkt_timebase(timeBase).thread_count(1).max_pixels(MAX_SOURCE_PIXELS);
        codec.err_recognition(AV_EF_CRCCHECK | AV_EF_EXPLODE);
        check("avcodec_open2", avcodec_open2(codec, selected, (PointerPointer<?>) null));
        packet = av_packet_alloc();
        requirePointer(packet, "av_packet_alloc");
        frame = av_frame_alloc();
        requirePointer(frame, "av_frame_alloc");
        held = av_frame_alloc();
        requirePointer(held, "av_frame_alloc(held)");
    }

    private List<BufferedImage> sampleFrames() throws IOException {
        List<BufferedImage> frames = new ArrayList<>(MAX_FRAMES);
        long firstPts = AV_NOPTS_VALUE;
        double previous = -1;
        double heldTime = 0;
        double heldDuration = 1.0 / FRAMES_PER_SECOND;
        boolean haveHeld = false;
        BufferedImage heldImage = null;
        while (frames.size() < MAX_FRAMES && receiveFrame()) {
            validateDimensions(frame.width(), frame.height());
            double step = frameStep(frame);
            long pts = frame.best_effort_timestamp();
            double time;
            if (pts == AV_NOPTS_VALUE) {
                // Headerless videos may have no PTS or duration. A bounded synthetic
                // timeline uses the declared rate, never an unbounded duration loop.
                time = previous < 0 ? 0 : previous + step;
            } else {
                if (firstPts == AV_NOPTS_VALUE) firstPts = pts;
                long delta;
                try { delta = Math.subtractExact(pts, firstPts); }
                catch (ArithmeticException failure) { throw new IOException("Video PTS overflow", failure); }
                time = delta * ((double) stream.time_base().num() / stream.time_base().den());
                if (!Double.isFinite(time) || time < 0 || (previous >= 0 && time <= previous)) {
                    throw new IOException("Non-monotonic video PTS");
                }
            }
            if (haveHeld) {
                while (frames.size() < MAX_FRAMES && frames.size() / (double) FRAMES_PER_SECOND < time - 1e-8) {
                    checkInterrupted();
                    if (heldImage == null) heldImage = render(held);
                    append(frames, heldImage);
                }
                if (frames.size() == MAX_FRAMES) break;
                av_frame_unref(held);
            }
            av_frame_move_ref(held, frame);
            heldImage = null;
            haveHeld = true;
            previous = heldTime = time;
            long duration = held.duration();
            heldDuration = duration > 0 ? duration * ((double) stream.time_base().num() / stream.time_base().den()) : step;
            if (!Double.isFinite(heldDuration) || heldDuration <= 0) heldDuration = step;
            // A corrupt final duration must not manufacture seconds of duplicate tail.
            heldDuration = Math.min(heldDuration, 1.0);
        }
        if (haveHeld) {
            double end = heldTime + heldDuration;
            while (frames.size() < MAX_FRAMES && frames.size() / (double) FRAMES_PER_SECOND < end - 1e-8) {
                checkInterrupted();
                if (heldImage == null) heldImage = render(held);
                append(frames, heldImage);
            }
        }
        checkInterrupted();
        if (frames.isEmpty()) throw new IOException("Video contains no decoded cover frames");
        return List.copyOf(frames);
    }

    private static void append(List<BufferedImage> frames, BufferedImage image) throws IOException {
        if ((frames.size() + 1L) * FRAME_SIZE * FRAME_SIZE * 4 > MAX_FRAME_BYTES) throw new IOException("Cover pixel budget exceeded");
        // Repeated PTS samples share an immutable image rather than decoding again.
        frames.add(image);
    }

    private double frameStep(AVFrame source) {
        try (AVRational rate = av_guess_frame_rate(format, stream, source)) {
            double fps = rate.den() > 0 ? (double) rate.num() / rate.den() : 0;
            return Double.isFinite(fps) && fps >= 1 && fps <= 240 ? 1.0 / fps : 1.0 / FRAMES_PER_SECOND;
        }
    }

    /** Receive before send; retain a pending packet across send EAGAIN, flush once. */
    private boolean receiveFrame() throws IOException {
        boolean sendWasEagain = false;
        while (true) {
            checkInterrupted();
            int result = avcodec_receive_frame(codec, frame);
            if (result == 0) {
                if (++decodedFrames > MAX_DECODED_FRAMES) throw new IOException("Decoded video frame limit exceeded");
                return true;
            }
            if (result == AVERROR_EOF) return false;
            if (result != AVERROR_EAGAIN()) check("avcodec_receive_frame", result);
            if (sendWasEagain) throw new IOException("Video decoder returned EAGAIN from both send and receive");
            if (packetPending) {
                result = avcodec_send_packet(codec, packet);
                if (result == AVERROR_EAGAIN()) { sendWasEagain = true; continue; }
                check("avcodec_send_packet", result);
                av_packet_unref(packet);
                packetPending = false;
            } else if (demuxEof) {
                if (flushSent) throw new IOException("Video decoder requested input after EOF");
                result = avcodec_send_packet(codec, null);
                if (result == AVERROR_EAGAIN()) { sendWasEagain = true; continue; }
                if (result == AVERROR_EOF) return false;
                check("avcodec_send_packet(flush)", result);
                flushSent = true;
            } else {
                if (++packetsRead > MAX_PACKETS) throw new IOException("Video packet limit exceeded");
                result = av_read_frame(format, packet);
                if (result == AVERROR_EAGAIN()) { LockSupport.parkNanos(1_000_000L); continue; }
                if (result == AVERROR_EOF) { demuxEof = true; continue; }
                check("av_read_frame", result);
                if (format.nb_streams() > MAX_STREAMS) throw new IOException("Cover stream count exceeds limit");
                if (packet.stream_index() != streamIndex) { av_packet_unref(packet); continue; }
                if ((packet.flags() & AV_PKT_FLAG_CORRUPT) != 0) throw new IOException("Corrupt video packet");
                packetPending = true;
            }
        }
    }

    private BufferedImage render(AVFrame source) throws IOException {
        checkInterrupted();
        int[] matrix = streamMatrix;
        AVFrameSideData side = av_frame_get_side_data(source, AV_FRAME_DATA_DISPLAYMATRIX);
        if (side != null && !side.isNull()) matrix = readMatrix(side.data(), side.size());
        int a = matrix == null ? 1 : matrix[0], b = matrix == null ? 0 : matrix[1];
        int c = matrix == null ? 0 : matrix[2], d = matrix == null ? 1 : matrix[3];
        double aspect = 1;
        try (AVRational sar = av_guess_sample_aspect_ratio(format, stream, source)) {
            if (sar.num() > 0 && sar.den() > 0) aspect = (double) sar.num() / sar.den();
        }
        if (!Double.isFinite(aspect) || aspect < 1.0 / 100 || aspect > 100) throw new IOException("Invalid video pixel aspect ratio");
        double displayWidth = source.width() * aspect, displayHeight = source.height();
        double scale = FRAME_SIZE / Math.max(displayWidth, displayHeight);
        int width = Math.max(1, Math.min(FRAME_SIZE, (int) Math.round(displayWidth * scale)));
        int height = Math.max(1, Math.min(FRAME_SIZE, (int) Math.round(displayHeight * scale)));
        if (scaler == null || sourceWidth != source.width() || sourceHeight != source.height()
                || sourceFormat != source.format() || scaledWidth != width || scaledHeight != height) {
            if (scaler != null) { sws_freeContext(scaler); scaler = null; }
            if (rgba != null && !rgba.isNull()) { av_frame_free(rgba); rgba = null; }
            scaler = sws_getContext(source.width(), source.height(), source.format(), width, height,
                    AV_PIX_FMT_RGBA, SWS_BILINEAR, null, null, (DoublePointer) null);
            requirePointer(scaler, "sws_getContext");
            rgba = av_frame_alloc();
            requirePointer(rgba, "av_frame_alloc(rgba)");
            rgba.format(AV_PIX_FMT_RGBA).width(width).height(height);
            check("av_frame_get_buffer(rgba)", av_frame_get_buffer(rgba, 32));
            sourceWidth = source.width(); sourceHeight = source.height(); sourceFormat = source.format();
            scaledWidth = width; scaledHeight = height;
        }
        int rows = sws_scale(scaler, source.data(), source.linesize(), 0, source.height(), rgba.data(), rgba.linesize());
        check("sws_scale", rows);
        if (rows != height) throw new IOException("Incomplete scaled video frame");
        BufferedImage image = new BufferedImage(FRAME_SIZE, FRAME_SIZE, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int outputWidth = Math.abs(a) * width + Math.abs(c) * height;
        int outputHeight = Math.abs(b) * width + Math.abs(d) * height;
        int offsetX = (FRAME_SIZE - outputWidth) / 2 + (a < 0 ? width - 1 : 0) + (c < 0 ? height - 1 : 0);
        int offsetY = (FRAME_SIZE - outputHeight) / 2 + (b < 0 ? width - 1 : 0) + (d < 0 ? height - 1 : 0);
        BytePointer data = rgba.data(0); // Borrowed, reset position before returning.
        try {
            for (int y = 0; y < height; y++) {
                checkInterrupted();
                data.position((long) y * rgba.linesize(0)).get(row, 0, width * 4);
                for (int x = 0; x < width; x++) {
                    int at = x * 4;
                    int argb = ((row[at + 3] & 255) << 24) | ((row[at] & 255) << 16)
                            | ((row[at + 1] & 255) << 8) | (row[at + 2] & 255);
                    pixels[(offsetY + b * x + d * y) * FRAME_SIZE + offsetX + a * x + c * y] = argb;
                }
            }
        } finally { data.position(0); }
        return image;
    }

    private static int[] readMatrix(BytePointer data, long size) throws IOException {
        if (data == null || data.isNull() || size < 9L * Integer.BYTES) throw new IOException("Invalid video display matrix");
        int[] m = new int[9];
        // A borrowed cast has no allocation/deallocator of its own.
        new IntPointer(data).get(m);
        if (m[2] != 0 || m[5] != 0 || m[8] != (1 << 30)) throw new IOException("Unsupported perspective video transform");
        double normX = Math.hypot(m[0], m[1]), normY = Math.hypot(m[3], m[4]);
        if (normX == 0 || normY == 0) throw new IOException("Singular video display matrix");
        double[] values = {m[0] / normX, m[1] / normX, m[3] / normY, m[4] / normY};
        int[] result = new int[4];
        for (int i = 0; i < 4; i++) {
            result[i] = (int) Math.round(values[i]);
            if (Math.abs(values[i] - result[i]) > 0.001) throw new IOException("Unsupported non-right-angle video rotation");
        }
        if (Math.abs(result[0] * result[3] - result[1] * result[2]) != 1) throw new IOException("Invalid orthogonal video transform");
        return result;
    }

    private static void validateDimensions(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION
                || (long) width * height > MAX_SOURCE_PIXELS) throw new IOException("Cover video dimensions exceed limit");
    }

    private boolean abortRequested() {
        if (cancelled || owner.isInterrupted()) return true;
        boolean expired = nanoTime.getAsLong() - deadline >= 0;
        if (cancelled || owner.isInterrupted()) return true;
        if (expired) { timedOut = true; return true; }
        return false;
    }

    private void checkInterrupted() throws IOException {
        abortRequested();
        if (cancelled || owner.isInterrupted()) throw new InterruptedIOException("Cover decode cancelled");
        if (timedOut) throw new SocketTimeoutException("Cover decode timed out after 30 seconds");
    }

    private void check(String operation, int result) throws IOException {
        checkInterrupted();
        if (result < 0) throw NativeErrors.failure(operation, result);
    }

    private void checkOwner() throws IOException {
        if (Thread.currentThread() != owner) throw new IOException("Cover decoder must stay on its opening worker");
    }

    private static void requirePointer(Pointer value, String operation) throws IOException {
        if (value == null || value.isNull()) throw new IOException(operation + " returned a null native pointer");
    }

    @Override public void close() throws IOException { checkOwner(); release(); }

    private void release() {
        if (released) return;
        // All av_* / sws_* allocations use their matching free, not Pointer.close().
        try {
            if (packet != null && !packet.isNull()) av_packet_free(packet);
            if (frame != null && !frame.isNull()) av_frame_free(frame);
            if (held != null && !held.isNull()) av_frame_free(held);
            if (rgba != null && !rgba.isNull()) av_frame_free(rgba);
            if (scaler != null && !scaler.isNull()) sws_freeContext(scaler);
            if (codec != null && !codec.isNull()) avcodec_free_context(codec);
            if (format != null && !format.isNull()) avformat_close_input(format);
        } finally {
            if (interruptCallback != null) interruptCallback.close();
            packet = null; frame = held = rgba = null; scaler = null; codec = null; format = null; stream = null;
            interruptCallback = null;
            released = true;
        }
    }

    boolean resourcesReleased() { return released; }
    int nativeInterruptChecks() { return callbackChecks; }
    int nativeInterruptAborts() { return callbackAborts; }
}
