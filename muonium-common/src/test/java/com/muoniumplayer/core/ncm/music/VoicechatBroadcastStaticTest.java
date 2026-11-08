package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「语音广播」({@link VoicechatBroadcast}) 的环缓冲行为与接线不变量。
 *
 * <p>环缓冲的采样换算（立体声下混、0.9 预乘、960 帧对齐、陈帧跳转）是真对象跑——
 * 数学错了听友那边就是变调/断续/回放旧货，不是难看是事故。接线（挂点、API 引用边界、
 * 三加载器注册）用源码形状钉，与 GnetumCompat / 公告同一路数。</p>
 *
 * <p>单位约定：{@code captureRange} 的参数与断言里的「帧」都是<b>声道帧</b>（一对 L/R），
 * 环缓冲与 SVC 上行按<b>交错采样</b>计数——一个声道帧 = 两个采样，换算处都写明。</p>
 */
class VoicechatBroadcastStaticTest {

    @BeforeEach
    void isolateTestJvm() {
        VoicechatBroadcast.resetForTest();
        VoicechatBroadcast.setEnabled(true);
        CloudMusic.playing.set(true);
    }

    @AfterEach
    void restorePlaybackFlag() {
        CloudMusic.playing.set(true);
    }

    // ==================== 环缓冲行为（真对象跑） ====================

    /**
     * 流采样序号 {@code i}（交错计数）的期望值：居中声像（L=R），帧号 = i/2，
     * 值 = (帧号 % 97 - 48)/100，下混后 ×0.9 预乘再取整。
     */
    private static short expectedSample(int i) {
        double v = ((i / 2) % 97 - 48) / 100.0;
        return (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, (int) Math.round(v * 0.9 * 32767.0)));
    }

    /** 向环缓冲灌一段立体声 PCM：L=R=(帧号 % 97 - 48)/100，共 frames 个声道帧。 */
    private static void captureRange(int startFrame, int frames) {
        double[] pcm = new double[frames * 2];
        for (int f = 0; f < frames; f++) {
            double v = ((startFrame + f) % 97 - 48) / 100.0;
            pcm[f * 2] = v;
            pcm[f * 2 + 1] = v;
        }
        VoicechatBroadcast.capture(pcm, 2, 48000);
    }

    @Test
    @DisplayName("空环缓冲 pollFrame 返回 null，不合并进上行")
    void pollEmptyReturnsNull() {
        assertNull(VoicechatBroadcast.pollFrame());
    }

    @Test
    @DisplayName("立体声下混 ×0.9 预乘：一帧 960 采样的值逐个对得上")
    void downmixMatchesExpectedFormula() {
        captureRange(0, 5_000);
        short[] frame = VoicechatBroadcast.pollFrame();
        assertEquals(960, frame.length, "SVC 上行一帧必须恰好 960 采样");
        for (int i = 0; i < 960; i++) {
            assertEquals(expectedSample(i), frame[i], "采样 " + i + " 下混/预乘换算错了");
        }
    }

    @Test
    @DisplayName("跨多次 capture 采样流连续：四次 poll 拼回完整序列，读干后饿死")
    void streamIsContinuousAcrossCaptures() {
        captureRange(0, 700);    // 引擎缓冲不是 960 的倍数，故意错位
        captureRange(700, 1_300);
        // 2000 声道帧 = 4000 采样 = 4 帧 960（剩 160 采样不足一帧）。
        for (int frameIdx = 0; frameIdx < 4; frameIdx++) {
            short[] chunk = VoicechatBroadcast.pollFrame();
            assertEquals(960, chunk.length, "第 " + (frameIdx + 1) + " 帧");
            for (int i = 0; i < 960; i++) {
                assertEquals(expectedSample(frameIdx * 960 + i), chunk[i]);
            }
        }
        assertNull(VoicechatBroadcast.pollFrame(), "剩 160 采样不足一帧，必须饿死而不是凑帧");
    }

    @Test
    @DisplayName("写侧门控：没在放歌（CloudMusic.playing=false）时 capture 不进数据")
    void ignoresCaptureWhenNotPlaying() {
        CloudMusic.playing.set(false);
        captureRange(0, 2_000);
        assertNull(VoicechatBroadcast.pollFrame(), "没在放歌不许灌静音帧占消费余量");
    }

    @Test
    @DisplayName("陈帧跳转：积压超 4 秒后第一次 poll 拿到的是新鲜端，不是从头回放")
    void staleBacklogSkipsToFreshTail() {
        // 灌 5.5 秒 = 264_000 声道帧 = 528_000 采样再读：STALE_LIMIT（4 秒）触发跳转，
        // read 落到 write - 48_000 采样（留 1 秒）。
        int totalFrames = 264_000;
        for (int start = 0; start < totalFrames; start += 12_000) {
            captureRange(start, Math.min(12_000, totalFrames - start));
        }
        long totalSamples = totalFrames * 2L;
        long expectedFirstSample = totalSamples - 48_000L;
        short[] frame = VoicechatBroadcast.pollFrame();
        assertEquals(960, frame.length);
        for (int i = 0; i < 960; i++) {
            assertEquals(expectedSample((int) (expectedFirstSample + i)), frame[i],
                    "陈帧跳转后第一帧应从 write-1s 开始，而不是流头");
        }
    }

    // ==================== 接线形状（源码扫描） ====================

    private static String readShared(Path relative) throws IOException {
        return new String(Files.readAllBytes(ProjectPaths.sourceFile(relative)), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("SVC API import 只出现在 MuoniumVoicechatPlugin：其余共享层文件零 de.maxhenkel")
    void apiImportsConfinedToPluginClass() throws IOException {
        List<Path> sources = ProjectPaths.allJavaFiles();
        for (Path file : sources) {
            String name = file.getFileName().toString();
            if (name.equals("MuoniumVoicechatPlugin.java")) continue;
            String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertFalse(src.contains("de.maxhenkel.voicechat"),
                    "de.maxhenkel.voicechat 只许出现在 MuoniumVoicechatPlugin，"
                            + file + " 引到了（API 引用边界破了）");
        }
        String plugin = readShared(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "MuoniumVoicechatPlugin.java"));
        assertTrue(plugin.contains("@ForgeVoicechatPlugin"), "Forge/NeoForge 靠注解发现插件");
        assertTrue(plugin.contains("implements VoicechatPlugin"), "Fabric entrypoint 靠接口发现插件");
        assertTrue(plugin.contains("MergeClientSoundEvent"), "上行注入走麦克风帧合并事件");
        assertTrue(plugin.contains("public MuoniumVoicechatPlugin()"),
                "SVC 用 getDeclaredConstructor().newInstance() 反射实例化，无参构造必须 public");
    }

    @Test
    @DisplayName("共享层铁律：三个新文件零加载器符号")
    void noLoaderSymbols() throws IOException {
        for (String name : new String[]{"VoicechatBroadcast.java", "EngineOutputTap.java",
                "MuoniumVoicechatPlugin.java"}) {
            String src = readShared(Paths.get("com", "muoniumplayer", "core", "ncm", "music", name));
            assertFalse(src.contains("net.fabricmc") || src.contains("net.neoforged")
                            || src.contains("net.minecraftforge") || src.contains("cpw.mods"),
                    "muonium-common 不许出现加载器符号（铁律 1）：" + name);
        }
    }

    @Test
    @DisplayName("挂点在 loadUsableSoundFile（与 ViperRuntime/声学同款时机）；频谱已改走 EngineOutputTap")
    void wiringAnchors() throws IOException {
        String audioPlayer = readShared(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "AudioPlayer.java"));
        assertTrue(audioPlayer.contains("VoicechatBroadcast.ensureInstalled();"),
                "语音广播的采集回调必须挂在 loadUsableSoundFile（引擎已存在的时机）");

        String spectrum = readShared(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "AudioSpectrumAnalyzer.java"));
        assertTrue(spectrum.contains("EngineOutputTap.addListener"),
                "频谱必须经由 EngineOutputTap 分发器注册（输出观察者单槽，直挂会顶掉语音广播）");
        assertFalse(spectrum.contains("setOutputObserver"),
                "频谱不许再直接 setOutputObserver（单槽直挂 = 静默顶掉别的监听）");

        String tap = readShared(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "EngineOutputTap.java"));
        assertTrue(tap.contains("setOutputObserver(EngineOutputTap::dispatch)"),
                "分发器必须挂住引擎观察者槽并逐监听隔离");
    }

    @Test
    @DisplayName("上行格式硬约束：48kHz、20ms 帧（960 采样）")
    void uplinkFormatConstants() throws IOException {
        String src = readShared(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "VoicechatBroadcast.java"));
        assertTrue(src.contains("SAMPLE_RATE = 48_000"), "SVC 上行只收 48kHz");
        assertTrue(src.contains("SAMPLE_RATE / 50"), "20ms 一帧（麦克风线程的节拍）");
        assertEquals(960, 48_000 / 50, "SVC AudioUtils.FRAME_SIZE = 960，差一点整包被丢");
    }
}
