package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * 一首歌的人声素材缓存：HTDemucs 分出来、按分析窗口逐帧对齐摆放的那一份单声道人声。
 *
 * <p>交接期播放链会把它的 {@code level} 从 1 拉到 0 再放回 1，于是进来的那首先以器乐垫的身份进来、
 * 人声随后长回来（见 {@code repackage.processing.sound.VocalMaterialSample}）。这条链路要成立，
 * 素材必须和播放素材<b>逐帧同位</b> —— 所以这里的所有判据都是在回答「这一份能不能对得上」，
 * 对不上就整个不要，退回带限中央声道抵消器：那是永远可用的兜底，也只是没那么准。</p>
 */
final class FoliaVocalStem {
    private static final int FLOAT_BYTES = 4;
    /**
     * 单个素材的帧数上限。分析窗口只有 30 秒（{@link FoliaTrackProfiler#WINDOW_MILLIS}），
     * 留一倍余量；超过它说明缓存被写坏或者混进了别的东西，整份丢掉比读进内存便宜。
     */
    private static final long MAX_FRAMES = 90L * 44_100L;

    /** 素材本体，单声道、{@link FoliaStemProfile#MATERIAL_RATE} 采样率。 */
    final float[] samples;
    /** {@code samples[0]} 对应播放素材的第几帧。 */
    final long firstFrame;
    /** 素材对回原曲人声响度的增益（最小二乘投影系数）。 */
    final float ratio;

    /** 包可见而不是 {@code private}：两条判据（可信区间、覆盖范围）要能脱离磁盘与模型单测。 */
    FoliaVocalStem(float[] samples, long firstFrame, float ratio) {
        this.samples = samples;
        this.firstFrame = firstFrame;
        this.ratio = ratio;
    }

    /**
     * 读这首歌的人声素材；返回 {@code null} 表示这次交接不用素材。
     *
     * <p>调用点在预备（arm）线程上，那里本来就要从磁盘拉第一个流式窗口，所以这里可以同步读盘；
     * 界面线程与音频线程都不许调它。</p>
     *
     * @param deckRate 播放素材的采样率；和素材的采样率不一致时整条路放弃（见方法体注释）
     */
    static FoliaVocalStem load(File source, int deckRate) {
        try {
            if (source == null || deckRate <= 0) return null;
            FoliaTrackProfile profile = FoliaTrackProfiler.peek(source);
            FoliaStemProfile stems = profile == null ? null : profile.stems;
            if (stems == null || !stems.hasVocalMaterial()) return null;
            // 素材是模型在 44.1k 上算的（输入从原采样率线性插值上来）。把它再插值回 48k 去减 48k
            // 的原曲，两次插值误差叠加，残差里会留一条人声的幽灵 —— 听起来像伴奏被啃掉一块，
            // 比不启用素材更糟。所以采样率对不上就整个不接，让抵消器继续管这条 deck。
            if (deckRate != FoliaStemProfile.MATERIAL_RATE) {
                System.out.println("[Automix/VocalMaterial] 未启用：素材 44100 / 播放 " + deckRate
                        + " · " + source.getName());
                return null;
            }
            File file = FoliaTrackProfiler.stemFile(profile.cacheKey);
            long frames = stems.materialFrames;
            if (file == null || !file.isFile() || frames <= 0L || frames > MAX_FRAMES
                    || file.length() != frames * FLOAT_BYTES) return null;
            float[] samples = readLittleEndianFloats(file, frames);
            if (samples == null) return null;
            // 帧号换算与 PcmWindowReader#readMono 用同一个式子（毫秒整除，向下取整）。分析窗口
            // 现在恒从 0 起，两者一样；留个不一致以后加出场素材就会差上一帧 —— 一帧就是 22 µs，
            // 高倍频率上差一帧的相位移足以让人声减不干净。
            long firstFrame = Math.max(0L, stems.windowStartMillis * deckRate / 1000L);
            return new FoliaVocalStem(samples, firstFrame, stems.vocalProjection);
        } catch (Throwable failure) {
            System.err.println("[Automix/VocalMaterial] 读取失败: " + failure.getMessage());
            return null;
        }
    }

    /** 素材起点落在播放素材时间轴的第几毫秒。 */
    long firstMillis(int deckRate) {
        return deckRate <= 0 ? 0L : firstFrame * 1000L / deckRate;
    }

    /** 素材覆盖到的最后一刻（播放素材的时间轴）。 */
    long endMillis(int deckRate) {
        return deckRate <= 0 ? 0L : (firstFrame + samples.length) * 1000L / deckRate;
    }

    /**
     * 素材盖不盖得住这条接缝。
     *
     * <p>判据只要求接缝的<b>前半段</b>：人声让路在前半段压到底、后半段松回去，所以后半段越出素材
     * 窗口只是让人声早点回来（那一帧读不到素材就直通），而前半段越出去才是事故 —— 这层什么都减不
     * 到，可 {@code AudioPlayer} 已经因为「手上素材接上了」把抵消器的深度按在 0，症状是交接期两个
     * 人一起唱。所以这里判不过就必须把素材整个退掉，让抵消器重新接管这条 deck。</p>
     */
    boolean covers(long cueMillis, long overlapMillis, int deckRate) {
        if (deckRate <= 0) return false;
        return cueMillis >= firstMillis(deckRate)
                && cueMillis + Math.max(0L, overlapMillis) / 2L <= endMillis(deckRate);
    }

    /** 分块读，避免为了 5 MB 素材先在堆上放一份等大的字节数组。 */
    private static float[] readLittleEndianFloats(File file, long frames) throws Exception {
        float[] samples = new float[(int) frames];
        int chunkFrames = 1 << 17;                             // 128K 帧 ≈ 512 KB
        ByteBuffer buffer = ByteBuffer.allocate(chunkFrames * FLOAT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            int done = 0;
            while (done < samples.length) {
                int want = Math.min(chunkFrames, samples.length - done);
                buffer.clear();
                buffer.limit(want * FLOAT_BYTES);
                int read = 0;
                while (read < want * FLOAT_BYTES) {
                    int step = channel.read(buffer);
                    if (step < 0) break;
                    read += step;
                }
                if (read < want * FLOAT_BYTES) return null;   // 读不满就是文件短了
                buffer.flip();
                for (int i = 0; i < want; i++) samples[done + i] = buffer.getFloat();
                done += want;
            }
        }
        return samples;
    }
}
