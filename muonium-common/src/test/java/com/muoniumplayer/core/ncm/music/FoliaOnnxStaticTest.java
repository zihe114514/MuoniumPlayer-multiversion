package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automix 的 ONNX Runtime 安卓适配不变量。
 *
 * <p>推理链从「Python 子进程」换成了「进程内 ONNX Runtime」，数学部分
 * （mel 前端、峰值后处理、包络统计）是真跑的；运行时加载与平台路由是
 * 源码形状的钉子——这三件事错了都不在编译期报错，只会变成「安卓上分析
 * 静默失败，退回内置 BPM」。
 */
class FoliaOnnxStaticTest {

    private static final double EPS = 1e-9;

    // ------------------------------------------------------------------
    // mel 前端：与 Python 参考实现的数学契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Folia 的 Hz↔mel 刻度：1kHz 以下线性、以上对数（与 librosa 不同，不能混用）")
    void melScaleMatchesReference() {
        assertEquals(0.45, FoliaMelFrontend.hzToMel(30.0), EPS);
        assertEquals(15.0, FoliaMelFrontend.hzToMel(1000.0), EPS);
        assertEquals(1000.0, FoliaMelFrontend.melToHz(15.0), EPS);
        assertEquals(30.0, FoliaMelFrontend.melToHz(FoliaMelFrontend.hzToMel(30.0)), EPS);
        // 往返一致性覆盖对数段
        for (double hz = 1100; hz <= 11000; hz += 700) {
            assertEquals(hz, FoliaMelFrontend.melToHz(FoliaMelFrontend.hzToMel(hz)), 1e-6);
        }
    }

    @Test
    @DisplayName("mel 帧 == numpy 金标值（1kHz 纯音，逐带对照）")
    void spectrogramMatchesNumpyGolden() {
        float[] mono = pureTone();
        float[][] mel = FoliaMelFrontend.spectrogram(mono);
        // 金标由 folia_beat_this.py 的 numpy 参考实现生成（2026-10-06 对拍 max abs diff = 0）
        double[][] golden = {
                {10, 52, 0.34991348},
                {10, 53, 0.27790987},
                {10, 54, 0.23582423},
                {30, 52, 0.34991348},
                {5, 20, 0.30154696},
        };
        for (double[] cell : golden) {
            assertEquals(cell[2], mel[(int) cell[0]][(int) cell[1]], 1e-5,
                    "mel[" + (int) cell[0] + "][" + (int) cell[1] + "] 偏离 numpy 参考");
        }
    }

    @Test
    @DisplayName("mel 帧 == 稠密内积复算（滤波器支撑区必须各自取：相邻三角在角点两侧重叠，"
            + "拿上一带末端当本带起点会漏掉半段上升沿）")
    void spectrogramMatchesDenseRecompute() {
        float[] mono = pureTone();
        float[][] mel = FoliaMelFrontend.spectrogram(mono);
        int frame = 10;
        int base = frame * FoliaMelFrontend.HOP;
        int pad = FoliaMelFrontend.N_FFT / 2;
        assertTrue(base - pad >= 0, "该帧在 padded 信号里不能触到左反射边，复算才无需重放反射");
        double[] re = new double[FoliaMelFrontend.N_FFT];
        double[] im = new double[FoliaMelFrontend.N_FFT];
        for (int n = 0; n < FoliaMelFrontend.N_FFT; n++) {
            double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / FoliaMelFrontend.N_FFT);
            // 帧 f 取 padded 信号的 [f·HOP, f·HOP+N_FFT)，即 mono 的 f·HOP-pad 起
            re[n] = mono[base - pad + n] * window;
        }
        FoliaMelFrontend.fft(re, im);
        int bins = FoliaMelFrontend.N_FFT / 2 + 1;
        double scale = 1.0 / Math.sqrt(FoliaMelFrontend.N_FFT);
        double[] magnitude = new double[bins];
        for (int bin = 0; bin < bins; bin++) {
            magnitude[bin] = Math.hypot(re[bin], im[bin]) * scale;
        }
        double melMin = FoliaMelFrontend.hzToMel(30.0);
        double melMax = FoliaMelFrontend.hzToMel(11000.0);
        double[] corners = new double[FoliaMelFrontend.N_MELS + 2];
        for (int i = 0; i < corners.length; i++) {
            corners[i] = FoliaMelFrontend.melToHz(
                    melMin + (melMax - melMin) * i / (FoliaMelFrontend.N_MELS + 1));
        }
        double worst = 0.0;
        for (int band = 0; band < FoliaMelFrontend.N_MELS; band++) {
            double energy = 0.0;
            for (int bin = 0; bin < bins; bin++) {
                double freq = bin * (double) FoliaMelFrontend.SR / FoliaMelFrontend.N_FFT;
                double up = (freq - corners[band]) / (corners[band + 1] - corners[band]);
                double down = (corners[band + 2] - freq) / (corners[band + 2] - corners[band + 1]);
                energy += Math.max(0.0, Math.min(up, down)) * magnitude[bin];
            }
            double expected = Math.log1p(1000.0 * Math.max(energy, 1e-10));
            worst = Math.max(worst, Math.abs(expected - mel[frame][band]));
        }
        assertTrue(worst < 1e-5, "稀疏内积与稠密复算的最大偏差 " + worst + " 超出容差");
    }

    @Test
    @DisplayName("频谱峰值落点正确：1kHz 纯音的 mel 能量峰在 1kHz 附近的带上")
    void spectrogramLocalizesPureTone() {
        float[][] mel = FoliaMelFrontend.spectrogram(pureTone());
        // numpy: (len(padded) - N_FFT) // HOP + 1 = 22050/441 + 1 = 51
        assertEquals(51, mel.length);
        // mel 能量最高（均值最大）的带，其中心频率应落在 1kHz 附近（±300Hz）
        double[] energy = new double[FoliaMelFrontend.N_MELS];
        for (float[] frame : mel) {
            for (int band = 0; band < FoliaMelFrontend.N_MELS; band++) energy[band] += frame[band];
        }
        int peak = 0;
        for (int band = 1; band < FoliaMelFrontend.N_MELS; band++) {
            if (energy[band] > energy[peak]) peak = band;
        }
        double centre = FoliaMelFrontend.melToHz(
                FoliaMelFrontend.hzToMel(30.0)
                        + (FoliaMelFrontend.hzToMel(11000.0) - FoliaMelFrontend.hzToMel(30.0))
                                * (peak + 1) / (double) (FoliaMelFrontend.N_MELS + 1));
        assertTrue(centre > 700.0 && centre < 1300.0,
                "1kHz 纯音的能量峰应落在 1kHz 附近的 mel 带，实际带中心 " + centre);
    }

    /** 1 秒 1kHz 纯音（SR=22050，恰 51 帧）。 */
    private static float[] pureTone() {
        float[] mono = new float[FoliaMelFrontend.SR];
        for (int i = 0; i < mono.length; i++) {
            mono[i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / FoliaMelFrontend.SR);
        }
        return mono;
    }

    // ------------------------------------------------------------------
    // Beat This! 后处理：分块/峰值/去重
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分块起点：起点从 -BORDER 起步，最后一块钉到尾部补满的位置")
    void chunkStartsMatchReference() {
        List<int[]> chunks = FoliaBeatOnnx.chunks(3000);
        // stride=1488: [-6, 1482, 2970] → 尾块钉到 3000-(1500-6)=1506
        assertEquals(3, chunks.size());
        assertEquals(-6, chunks.get(0)[0]);
        assertEquals(1482, chunks.get(1)[0]);
        assertEquals(1506, chunks.get(2)[0]);
        // 每块的帧数被夹在 [0, CHUNK]
        for (int[] chunk : chunks) {
            assertTrue(chunk[1] >= 0 && chunk[1] <= 1500);
        }
    }

    @Test
    @DisplayName("峰值挑选：正值的七帧严格局部极大，平台取首帧，随后一帧宽度去重")
    void peakPickingMatchesReference() {
        float[] logits = new float[20];
        logits[5] = 1.0f;
        logits[6] = 1.0f;           // 平台：两个相等都不被对方压过 → 取首帧 5
        logits[12] = 0.5f;
        logits[13] = 1.5f;          // 13 是真正的峰；12 不是（邻居更大）
        logits[13 + 1] = 0.0f;
        List<Integer> peaks = FoliaBeatOnnx.pickPeaks(logits);
        // pickPeaks 返回的是去重后的结果：5 与 6 相距 1（≤ 去重宽度）→ 合并成均值 6；13 独立
        assertEquals(2, peaks.size());
        assertEquals(6, peaks.get(0));
        assertEquals(13, peaks.get(1));
    }

    // ------------------------------------------------------------------
    // HTDemucs 统计：numpy percentile 的 linear 语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("percentile 用 numpy 的 linear 插值语义")
    void percentileMatchesNumpyLinear() {
        float[] oneToHundred = new float[100];
        for (int i = 0; i < 100; i++) oneToHundred[i] = i + 1;
        // rank = 0.95×99 = 94.05 → 95 + 0.05×1 = 95.05（numpy 同式）
        assertEquals(95.05, FoliaStemOnnx.percentile(oneToHundred, 95), 1e-9);
        float[] single = {7.5f};
        assertEquals(7.5, FoliaStemOnnx.percentile(single, 95), 1e-9);
        assertEquals(0.0, FoliaStemOnnx.percentile(new float[0], 95), 1e-9);
    }

    // ------------------------------------------------------------------
    // 运行时获取与平台路由
    // ------------------------------------------------------------------

    @Test
    @DisplayName("安卓运行时 zip 的校验哈希已钉入；桌面不提供 ONNX 运行时（走 Python）")
    void onnxRuntimeEntriesPinned() throws IOException {
        String manager = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "FoliaModelManager.java"));
        assertTrue(manager.contains("folia-onnx-android-arm64.zip")
                        && manager.contains("389a074e6783a0cc97f05cf2d7fe20711f624c0365160ce9328362f9e4fc6de0"),
                "安卓运行时 zip（AAR 抽包：classes.jar + 两个 .so，ORT 1.29）必须钉住哈希");
        assertFalse(manager.contains("folia-onnx-desktop.zip"),
                "桌面保持 Python 路线：不再有桌面 ONNX 运行时条目");
        assertFalse(manager.contains("onnxruntime.jar"),
                "桌面分支（官方 jar）必须已经撤掉");
    }

    @Test
    @DisplayName("只有安卓走进程内 ONNX；桌面 preferOnnxInference 恒 false（引擎是 Python 子进程）")
    void onlyAndroidPrefersOnnx() throws IOException {
        String manager = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "FoliaModelManager.java"));
        String prefer = body(manager, "public static boolean preferOnnxInference()");
        assertTrue(prefer.contains("return isAndroidPlatform();"),
                "preferOnnxInference 只认安卓：桌面必须恒 false，否则会绕开 Python 引擎");
        String onnx = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "FoliaOnnx.java"));
        // 守卫必须在真正取运行时文件之前：桌面不能去解包一个为安卓准备的运行时。
        String load = body(onnx, "private static boolean load()");
        int guard = load.indexOf("isAndroidPlatform()");
        int classesJar = load.indexOf("\"classes.jar\"");
        assertTrue(guard >= 0 && classesJar > guard, "非安卓守卫必须挡在 classes.jar 检查之前");
    }

    @Test
    @DisplayName("平台互斥：运行组件下载按平台分家，安卓不会拉 Python 包（aarch64 曾误匹配 linux-x64）")
    void runtimeDownloadIsPlatformExclusive() throws IOException {
        String manager = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "FoliaModelManager.java"));
        String download = body(manager, "public static void download(Kind kind, Progress progress)");
        assertTrue(download.contains("isAndroidPlatform() && kind == Kind.RUNTIME"),
                "安卓点 RUNTIME（Python 包）必须直接拒绝：os.name=Linux 会误下 linux-x64");
        assertTrue(download.contains("!isAndroidPlatform() && kind == Kind.ONNX_RUNTIME"),
                "桌面点 ONNX_RUNTIME 必须直接拒绝：那是安卓专用的包");
        String entry = body(manager, "private static RuntimeEntry runtimeEntry()");
        assertTrue(entry.contains("if (isAndroidPlatform())"),
                "runtimeEntry 要先拒绝安卓，再按平台挑 Python 包");
        assertFalse(entry.contains("arch.contains(\"64\")"),
                "aarch64 也 contains(\"64\")，x64 判定必须写 x86_64 / amd64");
    }

    @Test
    @DisplayName("运行包布局容错与推理可用性快照都已接入")
    void layoutToleranceAndReadinessAreWired() throws IOException {
        String manager = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "FoliaModelManager.java"));
        assertTrue(manager.contains("resolveOnnxRuntimeDir()"),
                "运行包实际目录要同时认 onnx-rt/ 与模型根目录（手动解压常放错一层）");
        String isInstalled = body(manager, "private static boolean isOnnxRuntimeInstalled()");
        assertTrue(isInstalled.contains("hasRuntimeFiles(resolveOnnxRuntimeDir())"),
                "安装判定与加载查找必须共用同一套目录解析，否则出现「已安装却加载不到」");
        assertTrue(manager.contains("inferenceReadiness()"),
                "要有「为什么没走模型推理」的可用性快照：三个前置静默退回是最难排查的现场");

        String profiler = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "FoliaTrackProfiler.java"));
        assertTrue(profiler.contains("推理未全开"),
                "profile 分析时未全开推理必须留一行日志说明缺什么");
        String onnx = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "FoliaOnnx.java"));
        assertTrue(onnx.contains("installStamp()"),
                "运行包加载失败后要按运行包指纹允许重试：手动解压发生在失败之后是常态");
    }

    /** 某个成员的方法体，按签名定位、按配对的花括号截断。 */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "源码里找不到: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "签名后面没有方法体: " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("方法体没有闭合: " + signature);
    }

    @Test
    @DisplayName("安卓优先 ONNX 路线，且不碰 javax.sound（MioJSound 桥会与游戏声音引擎抢上下文）")
    void androidRoutesToOnnxWithoutTouchingJavaSound() throws IOException {
        String player = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "AudioPlayer.java"));
        int androidGate = player.indexOf("mappedPlatform().startsWith(\"android\")");
        int javaSoundCtor = player.indexOf("new JavaSoundAudioDevice()");
        assertTrue(androidGate >= 0 && javaSoundCtor > androidGate,
                "安卓分支必须挡在 JavaSoundAudioDevice 构造之前");
        String beat = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "FoliaBeatThis.java"));
        assertTrue(beat.contains("preferOnnxInference()"),
                "FoliaBeatThis 要按 preferOnnxInference 路由：安卓走 ONNX，桌面 Python 优先");
        String stems = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "FoliaStemSeparator.java"));
        assertTrue(stems.contains("preferOnnxInference()"),
                "FoliaStemSeparator 同样按 preferOnnxInference 路由");
    }

    @Test
    @DisplayName("门面不许直接 import onnxruntime：运行时类是下载来的，不在编译类路径上")
    void facadeUsesReflectionOnly() throws IOException {
        String onnx = read(Paths.get("com", "muoniumplayer", "core", "ncm", "music",
                "FoliaOnnx.java"));
        assertFalse(onnx.contains("import ai.onnxruntime"),
                "ORT 类经 URLClassLoader 反射使用：编译期 import 会在没装运行时的机器上直接 NoClassDefFoundError");
        assertTrue(onnx.contains("onnxruntime.native.path"),
                "安卓侧要设 ORT 的 native path 属性（ORT 类初始化时读一次）");
        assertTrue(onnx.contains("libonnxruntime4j_jni.so"),
                "JNI 绑定库的实际文件名是 4j_jni，写错一个字 native 就加载不上");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String read(Path relative) throws IOException {
        Path path = ProjectPaths.sharedJava().resolve(relative);
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
