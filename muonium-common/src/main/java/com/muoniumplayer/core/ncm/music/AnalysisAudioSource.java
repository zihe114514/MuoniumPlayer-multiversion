package com.muoniumplayer.core.ncm.music;

import java.io.File;

/**
 * 为 automix 分析挑一个能读的音频源。
 *
 * <p>顺序是有意的，三层各管一段：</p>
 * <ol>
 *   <li>{@link PcmWindowReader}：WAV 缓存（含 AAC/M4A 播放前解出的 {@code .decoded.wav} 边车），
 *       直接 seek，不起进程、不依赖外部工具；</li>
 *   <li>{@link JavaDecoderAnalysisAudio}：mp3 / flac，用 mod 里打包的 JLayer / jFLAC 纯 Java 解码
 *       （与播放链同一套库）。手机装不了也跑不了 ffmpeg，这一层就是移动端的分析主力；</li>
 *   <li>{@link FfmpegAnalysisAudio}：其余格式的兜底，桌面装了 ffmpeg 时仍可比纯 Java 路径更全。</li>
 * </ol>
 *
 * <p>三层都失败就返回 {@code null}，交接照旧退化成一次纯等功率淡化——分析从来不是播放的前置条件。</p>
 */
final class AnalysisAudioSource {

    private AnalysisAudioSource() {
    }

    static AnalysisAudio open(File source) {
        PcmWindowReader wav = PcmWindowReader.open(source);
        if (wav != null) return wav;
        JavaDecoderAnalysisAudio javaDecoder = JavaDecoderAnalysisAudio.open(source);
        if (javaDecoder != null) return javaDecoder;
        return FfmpegAnalysisAudio.open(source);
    }
}
