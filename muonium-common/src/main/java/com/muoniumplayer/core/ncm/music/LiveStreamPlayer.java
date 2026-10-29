package com.muoniumplayer.core.ncm.music;

import lombok.Getter;
import repackage.javazoom.jl.decoder.Bitstream;
import repackage.javazoom.jl.decoder.BitstreamException;
import repackage.javazoom.jl.decoder.Decoder;
import repackage.javazoom.jl.decoder.Header;
import repackage.javazoom.jl.decoder.SampleBuffer;

import com.muoniumplayer.core.utils.network.HttpUtils;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 广播电台直播流播放器：HTTP 直推的 MPEG 音频流（ICECAST / SHOUTCAST 风格），
 * 用随包的 javazoom 解码器逐帧解码、JavaSound {@link SourceDataLine} 直推输出。
 *
 * <p>刻意<b>不走</b> {@link AudioPlayer}（JSyn）那条引擎：JSyn 的采样是一次性把整个文件
 * 解码进内存的 {@code FloatSample}，而直播流永不结束——塞进那条链路的结果要么是下载
 * 永远完不成，要么是堆内存按分钟线性膨胀。这条独立通道只有直播会用，与既有引擎互斥
 * （同一时刻只有一个声源），切歌/收尾都由 {@link LiveStreamSession} 统一调度。</p>
 *
 * <p>线程模型：解码线程是唯一的写方（HTTP 读 → 解码 → line.write 阻塞推流），
 * UI 线程只调 {@link #pause()}/{@link #resume()}/{@link #setVolume(float)}/{@link #close()}。
 * 输出电平在音频线程外用 {@link FloatControl}（MASTER_GAIN）近似控制，写值本身由
 * JavaSound 内部同步。</p>
 *
 * <p>静音容忍：ICESTREAM 服务偶发丢包/编码器瞬时静音，解码器对连续垃圾帧宽容处理
 * （跳过继续读），真正断流（EOF / 连续异常）由监听器上报给会话层决定重连或收尾。</p>
 */
public class LiveStreamPlayer {

    /** 触发「断流」回调的连续解码失败数。ICESTREAM 偶发的坏帧到不了这个量级。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 12;
    /** JavaSound 输出缓冲按毫秒折算的字节数：给网络抖动留 400ms 的垫，同时延迟可控。 */
    private static final int LINE_BUFFER_MILLIS = 400;
    /** 直播没有总时长，界面上以「已收听时长」代替；起始墙钟。 */
    private final long startedAtMillis = System.currentTimeMillis();

    private final String streamUrl;
    private final Listener listener;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicLong decodedFrameCount = new AtomicLong();

    private volatile float volume = 0.25f;
    /** 解码/输出线程；close 后自然退出。 */
    private volatile Thread decodeThread;
    /** 本播放器的会话归属：close 时回调。 */
    private volatile Runnable onClosed;

    @Getter
    private volatile String stationName = "";

    public interface Listener {
        /** 音频真正开始出声（line 起跑后第一帧写出）。 */
        void onStarted(LiveStreamPlayer player);

        /** 流断掉且内部重试用尽。UI 应提示用户重试；播放器已自行关闭。 */
        void onStreamEnded(LiveStreamPlayer player, String reason);
    }

    public LiveStreamPlayer(String streamUrl, String stationName, float initialVolume, Listener listener) {
        this.streamUrl = streamUrl;
        this.stationName = stationName == null ? "" : stationName;
        this.volume = initialVolume;
        this.listener = listener == null ? new Listener() {
            @Override public void onStarted(LiveStreamPlayer player) { }
            @Override public void onStreamEnded(LiveStreamPlayer player, String reason) { }
        } : listener;
    }

    /** 启动解码线程；立即返回，出错走 {@link Listener#onStreamEnded}。 */
    public synchronized void start() {
        if (closed.get() || decodeThread != null) return;
        decodeThread = new Thread(this::runLoop, "MuoniumPlayer-LiveStream");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    // ── UI 控制 ──────────────────────────────────────────────────────────────

    /** 暂停 = 数据线停止消费（网络读也会停下，缓冲自然止涨）。直播不支持 seek。 */
    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public boolean isPaused() {
        return paused.get();
    }

    /** UI 视角的「正在出声」：线程活着、没关、没暂停。 */
    public boolean isPlaying() {
        return !closed.get() && !paused.get() && playing.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 已收听毫秒数（墙钟，暂停也照走——广播不等人）。 */
    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAtMillis;
    }

    /** 已解码帧数（诊断用）。 */
    public long decodedFrameCount() {
        return decodedFrameCount.get();
    }

    public void setVolume(float newVolume) {
        this.volume = newVolume;
    }

    public float getVolume() {
        return volume;
    }

    /** close 时的回调（LiveStreamSession 用来对齐自身状态）。 */
    public void setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed;
    }

    /** 关闭播放器：断网络流、停输出线。幂等；解码线程随后自然退出。 */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        paused.set(false);
        Thread thread = this.decodeThread;
        if (thread != null) {
            // interrupt 能立刻打断 line.write 与各 sleep 点；对阻塞中的 socket read 无效
            //（HttpURLConnection 的流不是 InterruptibleChannel），那条路靠 20s 读超时兜底，
            // 期间旧 line 已因缓冲耗尽而无声，但对象要等线程退出才释放 —— 有界滞留，非泄漏。
            thread.interrupt();
        }
        Runnable callback = this.onClosed;
        this.onClosed = null;
        if (callback != null) callback.run();
    }

    // ── 解码 / 输出循环 ──────────────────────────────────────────────────────

    private void runLoop() {
        int consecutiveFailures = 0;
        // onStarted 只在第一次真正出声时报：断流重连成功是恢复，不是又一次起播，
        // 再弹一条"正在收听"会把恢复误报成新起播。
        boolean announcedStart = false;
        try {
            while (!closed.get()) {
                try (InputStream http = openStream(streamUrl)) {
                    Bitstream bitstream = new Bitstream(new PausableInputStream(http, paused, closed));
                    Decoder decoder = new Decoder();
                    // Decoder 的声道布局在首帧 initialize 后固定：mid-stream 声道数变化时
                    // 必须连解码器一起换，否则按旧声道合成会交错错位（同名不同形）。
                    int decoderChannels = 0;
                    SourceDataLine line = null;
                    AudioFormat lineFormat = null;
                    try {
                        while (!closed.get()) {
                            Header header = bitstream.readFrame();
                            if (header == null) {
                                // EOF：上游断流。抛出走外层重连。
                                throw new IOException("live stream ended");
                            }
                            SampleBuffer output;
                            try {
                                output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                            } catch (repackage.javazoom.jl.decoder.DecoderException frameFailure) {
                                // 帧体坏是 ICECAST 丢包最常见的形态：跳过这一帧继续读，
                                // 与网络故障共用连续失败计数，攒满 12 次才算真断流。
                                bitstream.closeFrame();
                                consecutiveFailures++;
                                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                    fail("连续解码失败（流数据损坏）: " + rootMessage(frameFailure));
                                    return;
                                }
                                continue;
                            }
                            bitstream.closeFrame();
                            decodedFrameCount.incrementAndGet();
                            consecutiveFailures = 0;

                            // SampleBuffer 的底层数组固定 2304 长（OBUFFERSIZE），本帧有效
                            // 采样数在 getBufferLength() 里。用数组全长转字节的话，单声道
                            // 流（有效 1152）会多写一倍残留数据 —— 播放半速且叠残影。
                            short[] pcm = output.getBuffer();
                            int validSamples = output.getBufferLength();
                            if (validSamples <= 0) continue;

                            int channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                            if (channels != decoderChannels) {
                                // 声道数变了（含首帧）：解码器与输出线都要按新布局重建。
                                decoder = new Decoder();
                                decoderChannels = channels;
                                if (line != null) {
                                    line.drain();
                                    line.close();
                                    line = null;
                                }
                            }
                            if (line == null || lineFormatChanged(lineFormat, header)) {
                                // 第一帧才知道流的真实参数（码率/声道/采样率由内容决定）。
                                if (line != null) {
                                    line.drain();
                                    line.close();
                                }
                                lineFormat = formatOf(header);
                                line = openLine(lineFormat);
                            }
                            if (line == null) throw new IOException("audio line unavailable");

                            if (!announcedStart) {
                                announcedStart = true;
                                playing.set(true);
                                listener.onStarted(this);
                            }

                            byte[] bytes = toByteArray(pcm, validSamples);
                            // 阻塞写：音频消费多快写多快，天然背压。暂停时 PausableInputStream
                            // 在读端拦住，这里的写很快耗尽缓冲然后停住——不占 CPU。
                            while (!closed.get() && !paused.get()) {
                                int written = line.write(bytes, 0, bytes.length);
                                if (written > 0) break;
                                Thread.sleep(20L);
                            }
                            // 音量变化在写帧间隙刷到线上（不持锁，FloatControl 内部同步）。
                            if (line != null) refreshVolumeOnLine(line);
                            // 暂停期间睡等恢复；closed 由循环条件兜住。
                            while (paused.get() && !closed.get()) {
                                Thread.sleep(50L);
                            }
                            consecutiveFailures = 0;
                        }
                    } finally {
                        if (line != null) {
                            try { line.drain(); } catch (Throwable ignored) { }
                            line.close();
                        }
                        try { bitstream.close(); } catch (Throwable ignored) { }
                    }
                } catch (IOException | BitstreamException | LineUnavailableException networkFailure) {
                    if (closed.get()) break;
                    // 404/410 是"频道下线"的确定性回答，重试也不会活：立即收尾并讲清原因，
                    // 不让用户对着一个死频道等满 12 次重试。
                    String message = rootMessage(networkFailure);
                    if (message.contains("404") || message.contains("410")) {
                        fail("这个频道已下线或暂时没有直播（HTTP " + message + "）");
                        return;
                    }
                    consecutiveFailures++;
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        fail("流连接持续失败: " + message);
                        return;
                    }
                    // 断流重连前小歇：广播服务端偶发重启是常态，退避 1s。
                    Thread.sleep(1000L);
                }
            }
        } catch (InterruptedException interrupted) {
            // close() 的 interrupt：正常收尾。
        } catch (Throwable fatal) {
            // HttpUtils 的 404/410 以 RuntimeException 逃到这里：同样是"频道下线"的确定性
            // 回答，按 fast-fail 归因，不与真正的意外异常混为一谈。
            String message = rootMessage(fatal);
            if (!closed.get() && (message.contains("404") || message.contains("410"))) {
                fail("这个频道已下线或暂时没有直播（HTTP 404/410）");
                return;
            }
            if (!closed.get()) fail("直播播放异常: " + message);
        } finally {
            playing.set(false);
            closed.set(true);
        }
    }

    /** 建一条按流参数开的输出线；缓冲给足 400ms 防网络抖动卡顿。 */
    private SourceDataLine openLine(AudioFormat format) throws LineUnavailableException {
        int bufferSize = Math.max(8192,
                (int) (format.getFrameRate() * format.getFrameSize() * LINE_BUFFER_MILLIS / 1000.0));
        javax.sound.sampled.DataLine.Info info =
                new javax.sound.sampled.DataLine.Info(SourceDataLine.class, format, bufferSize);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, bufferSize);
        applyVolume(line, this.volume);
        line.start();
        return line;
    }

    private static boolean lineFormatChanged(AudioFormat current, Header next) {
        if (current == null) return true;
        return current.getSampleRate() != next.frequency()
                || current.getChannels() != (next.mode() == Header.SINGLE_CHANNEL ? 1 : 2);
    }

    private static AudioFormat formatOf(Header header) {
        int channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
        return new AudioFormat((float) header.frequency(), 16, channels, true, false);
    }

    private static void applyVolume(SourceDataLine line, float volume) {
        try {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                float db = volume <= 0.0001f ? gain.getMinimum()
                        : (float) (20.0 * Math.log10(Math.max(0.0001, volume)));
                gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
            }
        } catch (Throwable ignored) {
            // 音量控制尽力而为：个别设备线不支持增益。
        }
    }

    /** short PCM → little-endian bytes（JavaSound 期望的 S16LE）。 */
    /** short PCM → little-endian bytes（JavaSound 期望的 S16LE）；只转换前 validCount 个有效采样。 */
    private static byte[] toByteArray(short[] samples, int validCount) {
        byte[] out = new byte[validCount * 2];
        for (int i = 0; i < validCount; i++) {
            out[i * 2] = (byte) (samples[i] & 0xFF);
            out[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return out;
    }

    /** 音量在解码线程的写路径上随写随刷（setVolume 只改字段，这里真正作用到线上）。 */
    void refreshVolumeOnLine(SourceDataLine line) {
        applyVolume(line, this.volume);
    }

    private void fail(String reason) {
        playing.set(false);
        closed.set(true);
        listener.onStreamEnded(this, reason);
        Runnable callback = this.onClosed;
        if (callback != null) callback.run();
    }

    private static String rootMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message.trim();
    }

    private static InputStream openStream(String url) throws IOException {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        headers.put("Icy-MetaData", "0");
        headers.put("Connection", "close");
        return HttpUtils.get(url, null, headers, 15_000, 20_000);
    }

    /**
     * 读端暂停器：包住 HTTP 流，暂停时在读 {@code read()} 前先睡——上游 TCP 窗口自然
     * 停止推进，服务端不会给我们推我们消费不了的数据。解码线程因此可以毫无竞态地
     * 用同一个 paused 标志同时拦住读端与写端。
     */
    private static final class PausableInputStream extends InputStream {
        private final InputStream delegate;
        private final AtomicBoolean paused;
        private final AtomicBoolean closed;

        PausableInputStream(InputStream delegate, AtomicBoolean paused, AtomicBoolean closed) {
            this.delegate = delegate;
            this.paused = paused;
            this.closed = closed;
        }

        private void gate() throws IOException {
            try {
                while (paused.get() && !closed.get()) Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("paused gate interrupted", interrupted);
            }
        }

        @Override public int read() throws IOException {
            gate();
            if (closed.get()) throw new IOException("closed");
            return delegate.read();
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            gate();
            if (closed.get()) throw new IOException("closed");
            return delegate.read(b, off, len);
        }

        @Override public int available() throws IOException {
            return delegate.available();
        }

        @Override public void close() throws IOException {
            delegate.close();
        }
    }
}
