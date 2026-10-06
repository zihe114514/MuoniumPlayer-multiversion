package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯 Java 解码分析源（mp3 / flac）的离线契约。
 *
 * <p>存在的理由：手机上没有也不可能有可执行的 ffmpeg（应用私有目录禁 exec、外置存储 noexec），
 * 而缓存里绝大多数歌曲正好是 mp3 / flac。这一层不通，automix 的全部结构判断（含模型推理）
 * 在手机上都是静默关闭的——这条链曾经就是「什么都没发生」，所以几个关键点用源码钉住。</p>
 */
class JavaDecoderAnalysisAudioStaticTest {

    @Test
    @DisplayName("分析源三层顺序：WAV 直读 → 纯 Java 解码 → ffmpeg 兜底")
    void analysisSourceOrderPrefersPureJavaOverFfmpeg() throws IOException {
        String source = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "AnalysisAudioSource.java"));
        int wav = source.indexOf("PcmWindowReader.open(source)");
        int java = source.indexOf("JavaDecoderAnalysisAudio.open(source)");
        int ffmpeg = source.indexOf("FfmpegAnalysisAudio.open(source)");
        assertTrue(wav >= 0 && java > wav && ffmpeg > java,
                "顺序必须是 WAV → 纯 Java → ffmpeg：手机没有 ffmpeg，纯 Java 必须在兜底之前");
    }

    @Test
    @DisplayName("纯 Java 解码只用打包的 JLayer / jFLAC，不起外部进程")
    void decoderUsesBundledLibrariesWithoutSpawningProcesses() throws IOException {
        String decoder = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "JavaDecoderAnalysisAudio.java"));
        assertTrue(decoder.contains("repackage.javazoom.jl.decoder.Bitstream"),
                "MP3 走打包的 JLayer（播放链同一套库），不新增依赖");
        assertTrue(decoder.contains("repackage.org.kc7bfi.jflac.FLACDecoder"),
                "FLAC 走打包的 jFLAC");
        assertFalse(decoder.contains("ProcessBuilder") || decoder.contains("Runtime.getRuntime"),
                "纯 Java 解码器不许起子进程：手机上的可执行路径整体不可用");
    }

    @Test
    @DisplayName("MP3 帧头码率表索引减一（表按 1..14 排布，漏减会算错帧长）")
    void mp3BitrateIndexIsShifted() throws IOException {
        String decoder = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "JavaDecoderAnalysisAudio.java"));
        assertTrue(decoder.contains("bitrateIndex - 1"),
                "码率表按索引 1..14 排布，查表必须减 1；不减会 ArrayIndexOutOfBounds 或算错帧长");
    }

    @Test
    @DisplayName("MP3 解码按每帧头判声道数（SampleBuffer 第二参数是声道数，不是固定值）")
    void mp3ChannelsComeFromEachFrameHeader() throws IOException {
        String decoder = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "JavaDecoderAnalysisAudio.java"));
        assertTrue(decoder.contains("header.mode() == Header.SINGLE_CHANNEL ? 1 : 2"),
                "立体声与单声道的交错长度不同（2304/1152），必须逐帧按帧头判定");
    }

    @Test
    @DisplayName("FLAC 以帧头 sampleNumber 为权威（jFLAC 的 seek 返回样本号实测会差一整帧）")
    void flacTrustsFrameHeaderNotSeekEstimate() throws IOException {
        String decoder = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "JavaDecoderAnalysisAudio.java"));
        assertTrue(decoder.contains("frame.header.sampleNumber"),
                "裁剪位置必须来自帧头：seek() 的返回样本号是字节估算，实测偏差可达一帧（4096 样本）");
        assertTrue(decoder.contains("2L * maxBlockSize"),
                "seek 时要多退两个块：估算偏晚一帧时起解位置仍需在目标之前");
        assertTrue(decoder.contains("class SamplesMissed"),
                "起解位置晚于目标时要能识别并退回顺序解码，而不是产出残缺窗口");
    }

    private static String read(Path relative) throws IOException {
        Path path = ProjectPaths.sharedJava().resolve(relative);
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
