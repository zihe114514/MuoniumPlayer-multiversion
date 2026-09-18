package com.muoniumplayer.core.ncm.music;

/**
 * 单个已解码曲目的合理性上限。
 *
 * <p>JSyn 把整个采样保存在 RAM 中（{@code FloatSample.allocate} 做 {@code new float[frames *
 * channels]}），因此一个 16 位 PCM 流大约需要两倍字节大小的堆。这里的上限
 * 刻意设得宽松：它的存在是为了阻止病态载荷（一个指向
 * 数小时上传的第三方链接）填满音频缓存，而非管束长曲目。任何仍
 * 放不下的内容都被播放器当作普通曲目失败处理，而非让游戏崩溃。</p>
 */
final class PlaybackMemoryLimits {

    /** 16 位 PCM 在播放器的采样缓冲中变成 32 位浮点。 */
    private static final long HEAP_BYTES_PER_PCM_BYTE = 2L;
    /** 宽松的堆占比：普通长度的曲目，即使一小时长，也必须仍能播放。 */
    private static final long HEAP_FRACTION = 3L;
    /** 约 50 分钟 CD 音质音频；低于此值从不拒绝任何东西。 */
    private static final long MIN_PCM_BYTES = 512L * 1024L * 1024L;
    /** 约 3 小时 CD 音质音频；超过此值的载荷就不是音乐了。 */
    private static final long MAX_PCM_BYTES = 1900L * 1024L * 1024L;
    private static final long CD_BYTES_PER_SECOND = 44100L * 2L * 2L;

    private PlaybackMemoryLimits() {
    }

    /** 可在不冒内存不足失败风险的情况下加载的最大已解码 PCM 载荷。 */
    static long maxDecodedPcmBytes() {
        long heap = Runtime.getRuntime().maxMemory();
        long budget = heap == Long.MAX_VALUE ? MAX_PCM_BYTES * HEAP_BYTES_PER_PCM_BYTE : heap / HEAP_FRACTION;
        long pcmBytes = budget / HEAP_BYTES_PER_PCM_BYTE;
        return Math.max(MIN_PCM_BYTES, Math.min(MAX_PCM_BYTES, pcmBytes));
    }

    /** 播放失败卡片使用的面向用户原因。 */
    static String describeOverLimit(long pcmBytes) {
        long limit = maxDecodedPcmBytes();
        return "音频约 " + minutes(pcmBytes) + " 分钟（上限 " + minutes(limit) + " 分钟），已跳过";
    }

    static long minutes(long pcmBytes) {
        return Math.max(1L, pcmBytes / CD_BYTES_PER_SECOND / 60L);
    }

    static long megabytes(long bytes) {
        return Math.max(1L, bytes / (1024L * 1024L));
    }
}
