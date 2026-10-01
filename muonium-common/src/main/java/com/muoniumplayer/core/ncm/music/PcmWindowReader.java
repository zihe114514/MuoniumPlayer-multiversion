package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 用自己的文件句柄，直接从已解码的 WAV 缓存文件读取单声道分析窗口。
 *
 * <h3>为什么这样做，而不是读取 JSyn 采样</h3>
 * <p>一个 {@code SoundFile} <em>不</em>把解码后的曲目保存在 RAM 中：{@code SampleLoader}
 * .loadStreamedFloatSample} 构建一个 {@code BufferedFloatSample}，它从一个共享的
 * {@code RandomAccessFile} 经由一个滑动窗口（{@code lastReadIndexOffset} 加单个
 * 解码缓冲）。从该采样读取任意位置会<b>移动窗口</b>。对正在播放的 deck 这样做，
 * 会在 JSyn 引擎线程下重定位缓冲，该线程随后
 * 用陈旧偏移索引窗口并抛出
 * {@code ArrayIndexOutOfBoundsException: -12666884}，来自 {@code SynthesisEngine.generateNextBuffer}
 * ——只要分析在运行，音频就停止产出。这正是 automix 分析
 * 过去对退出曲目所做的事。</p>
 *
 * <p>因此分析绝不触碰实时采样。它重新打开同一缓存文件、seek、解码并
 * 关闭，这既无竞争，又比经由流式采样读取几十万次单值
 * 快得多。</p>
 *
 * <p>每条失败路径都返回 {@code null} / 空窗口：分析是可选的，本类无法解析的文件
 * 只意味着交接退化为普通的等功率交叉淡化。</p>
 */
final class PcmWindowReader implements AnalysisAudio {

    private static final int WAVE_FORMAT_PCM = 1;
    private static final int WAVE_FORMAT_IEEE_FLOAT = 3;
    private static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;
    /** 一个窗口的上限，使畸形请求无法分配任意大小的数组。 */
    private static final long MAX_WINDOW_MILLIS = 60_000L;
    private static final int COPY_CHUNK_BYTES = 64 * 1024;

    private final RandomAccessFile handle;
    private final long dataOffset;
    private final long dataBytes;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final int format;
    private final int bytesPerFrame;
    private final int frames;

    private PcmWindowReader(RandomAccessFile handle, long dataOffset, long dataBytes, int sampleRate,
                            int channels, int bitsPerSample, int format, int bytesPerFrame) {
        this.handle = handle;
        this.dataOffset = dataOffset;
        this.dataBytes = dataBytes;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.format = format;
        this.bytesPerFrame = bytesPerFrame;
        long frameCount = dataBytes / bytesPerFrame;
        this.frames = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, frameCount));
    }

    /** 打开文件用于分析；若不是本类可解码的 WAV 则返回 {@code null}。 */
    static PcmWindowReader open(File source) {
        if (source == null || !source.isFile() || source.length() < 44L) return null;
        RandomAccessFile handle = null;
        try {
            handle = new RandomAccessFile(source, "r");
            if (readTag(handle) != 0x52494646) return null;          // "RIFF"
            handle.readInt();                                         // riff 大小，未用
            if (readTag(handle) != 0x57415645) return null;          // "WAVE"

            int format = 0;
            int channels = 0;
            int sampleRate = 0;
            int bitsPerSample = 0;
            int bytesPerFrame = 0;
            long dataOffset = -1L;
            long dataBytes = 0L;
            long length = handle.length();

            while (handle.getFilePointer() + 8L <= length) {
                int id = readTag(handle);
                long size = readIntLittle(handle) & 0xFFFFFFFFL;
                long body = handle.getFilePointer();
                if (id == 0x666D7420) {                               // "fmt "
                    format = readShortLittle(handle);
                    channels = readShortLittle(handle);
                    sampleRate = readIntLittle(handle);
                    readIntLittle(handle);                            // 字节率
                    bytesPerFrame = readShortLittle(handle);
                    bitsPerSample = readShortLittle(handle);
                    if (format == WAVE_FORMAT_EXTENSIBLE && size >= 40L) {
                        readShortLittle(handle);                      // 扩展大小
                        readShortLittle(handle);                      // 有效位数
                        readIntLittle(handle);                        // 通道掩码
                        format = readShortLittle(handle);             // 子格式 GUID 的第一个字段
                    }
                } else if (id == 0x64617461) {                        // "data"
                    dataOffset = body;
                    dataBytes = Math.min(size, length - body);
                    if (dataBytes > 0L) break;
                }
                long next = body + size + (size & 1L);                // 块按字对齐
                if (next <= body || next > length) break;
                handle.seek(next);
            }

            if (dataOffset < 0L || dataBytes <= 0L) return null;
            if (channels < 1 || channels > 8 || sampleRate <= 0) return null;
            if (bytesPerFrame <= 0) bytesPerFrame = channels * (bitsPerSample / 8);
            if (bytesPerFrame <= 0) return null;
            boolean supported = (format == WAVE_FORMAT_PCM
                    && (bitsPerSample == 16 || bitsPerSample == 24 || bitsPerSample == 32))
                    || (format == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample == 32);
            if (!supported) return null;

            PcmWindowReader reader = new PcmWindowReader(handle, dataOffset, dataBytes, sampleRate,
                    channels, bitsPerSample, format, bytesPerFrame);
            if (reader.frames <= 0) return null;
            handle = null;   // 所有权移交到读取器
            return reader;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (handle != null) {
                try {
                    handle.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    int channels() {
        return channels;
    }

    int frames() {
        return frames;
    }

    long durationMillis() {
        return frames * 1000L / sampleRate;
    }

    /**
     * 把文件的一个窗口拷贝下来并归一化为单声道。
     *
     * <p>请求落在数据块之外时返回空数组，因此调用方无需
     * 两次边界检查。声道被平均，这正是这里每次测量所需要的：节拍或
     * 包络是混音的属性，而非立体声图像某一侧的属性。</p>
     */
    @Override
    public float[] readMono(long startMillis, long lengthMillis) {
        if (lengthMillis <= 0L) return new float[0];
        try {
            long capped = Math.min(lengthMillis, MAX_WINDOW_MILLIS);
            long startFrame = Math.max(0L, startMillis) * sampleRate / 1000L;
            if (startFrame >= frames) return new float[0];
            int wantFrames = (int) Math.min(frames - startFrame, capped * sampleRate / 1000L);
            if (wantFrames <= 0) return new float[0];

            int sampleBytes = bitsPerSample / 8;
            float[] mono = new float[wantFrames];
            byte[] buffer = new byte[Math.max(bytesPerFrame, (COPY_CHUNK_BYTES / bytesPerFrame) * bytesPerFrame)];

            handle.seek(dataOffset + startFrame * bytesPerFrame);
            int produced = 0;
            while (produced < wantFrames) {
                int framesLeft = wantFrames - produced;
                int wantBytes = (int) Math.min(buffer.length, (long) framesLeft * bytesPerFrame);
                int read = readFully(buffer, wantBytes);
                if (read < bytesPerFrame) break;
                int framesRead = read / bytesPerFrame;
                for (int frame = 0; frame < framesRead; frame++) {
                    int base = frame * bytesPerFrame;
                    float sum = 0f;
                    for (int channel = 0; channel < channels; channel++) {
                        sum += decode(buffer, base + channel * sampleBytes);
                    }
                    mono[produced + frame] = sum / channels;
                }
                produced += framesRead;
            }
            if (produced == wantFrames) return mono;
            if (produced <= 0) return new float[0];
            float[] trimmed = new float[produced];
            System.arraycopy(mono, 0, trimmed, 0, produced);
            return trimmed;
        } catch (Throwable ignored) {
            // 缓存文件可能被并发切换删除或截断。
            return new float[0];
        }
    }

    private int readFully(byte[] buffer, int wantBytes) throws IOException {
        int total = 0;
        while (total < wantBytes) {
            int read = handle.read(buffer, total, wantBytes - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private float decode(byte[] buffer, int offset) {
        switch (bitsPerSample) {
            case 16: {
                int value = (buffer[offset] & 0xFF) | (buffer[offset + 1] << 8);
                return value / 32768f;
            }
            case 24: {
                int value = (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8)
                        | (buffer[offset + 2] << 16);
                return value / 8388608f;
            }
            case 32: {
                int bits = (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8)
                        | ((buffer[offset + 2] & 0xFF) << 16) | (buffer[offset + 3] << 24);
                if (format == WAVE_FORMAT_IEEE_FLOAT) {
                    float value = Float.intBitsToFloat(bits);
                    if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
                    return value;
                }
                return (float) (bits / 2147483648.0);
            }
            default:
                return 0f;
        }
    }

    @Override
    public void close() {
        try {
            handle.close();
        } catch (IOException ignored) {
        }
    }

    private static int readTag(RandomAccessFile handle) throws IOException {
        return handle.readInt();
    }

    private static int readIntLittle(RandomAccessFile handle) throws IOException {
        int value = handle.readInt();
        return Integer.reverseBytes(value);
    }

    private static int readShortLittle(RandomAccessFile handle) throws IOException {
        int low = handle.read();
        int high = handle.read();
        if (low < 0 || high < 0) throw new IOException("truncated WAV header");
        return (high << 8) | low;
    }
}
