package com.muoniumplayer.core.ncm.music.acoustics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 五树接线防漂移:五棵版本树的 {@code MuoniumPlayerClient} 必须都调场景声学的
 * tick 入口、都带 {@code ListenerProbe};共享引擎必须有尾槽。
 *
 * <p>测试从仓库布局反推五棵树的位置,任何一棵漏接线都会在这里点名。</p>
 */
class AcousticsWiringStaticTest {

    private static final String[] TREES = {
            "1.20.1 recode", "1.21.1", "1.21.10", "1.21.11 recode", "26.1.2", "26.2", "26.3",
    };

    private static Path repoRoot() {
        // 测试的工作目录可能是:树根(「1.20.1 recode」等)、树的加载器子目录
        // (fabric / forge / neoforge)、或 muonium-common。统一向上找到
        // 「同时含有 muonium-common 与至少一棵版本树」的目录为止。
        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd;
        for (int up = 0; up < 4 && candidate != null; up++, candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("muonium-common"))
                    && Files.isDirectory(candidate.resolve(TREES[0]))) {
                return candidate;
            }
        }
        return cwd;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    @Test
    @DisplayName("每棵树:tick 里调 AcousticsRuntime.onClientTick 且传 ListenerProbe")
    void everyTreeTicksAcoustics() throws IOException {
        Path root = repoRoot();
        for (String tree : TREES) {
            Path client = root.resolve(tree)
                    .resolve("src/main/java/com/muoniumplayer/MuoniumPlayerClient.java");
            assertTrue(Files.isRegularFile(client), "缺文件: " + client);
            String src = read(client);
            assertTrue(src.contains("AcousticsRuntime.onClientTick"),
                    tree + " 的 onClientTick 没接场景声学");
            assertTrue(src.contains("ListenerProbe.of(mc)"),
                    tree + " 没把 ListenerProbe 交给运行时");
        }
    }

    @Test
    @DisplayName("每棵树:ListenerProbe shim 都在,且实现的是 common 采样器接口")
    void everyTreeHasProbe() throws IOException {
        Path root = repoRoot();
        for (String tree : TREES) {
            Path probe = root.resolve(tree)
                    .resolve("src/main/java/com/muoniumplayer/compat/mc/ListenerProbe.java");
            assertTrue(Files.isRegularFile(probe), "缺文件: " + probe);
            String src = read(probe);
            assertTrue(src.contains("implements AcousticsRuntime.ListenerSampler"),
                    tree + " 的 ListenerProbe 没实现 ListenerSampler");
        }
    }

    @Test
    @DisplayName("共享引擎:尾槽入口与双段处理都在")
    void engineHasTrailingSlot() throws IOException {
        Path root = repoRoot();
        Path engine = root.resolve("muonium-common/src/main/java/repackage/com/jsyn/engine/SynthesisEngine.java");
        assertTrue(Files.isRegularFile(engine), "缺文件: " + engine);
        String src = read(engine);
        for (String expected : List.of(
                "setTrailingOutputProcessor",
                "trailing.process(outputBuffer.interleavedBuffer")) {
            assertTrue(src.contains(expected), "SynthesisEngine 缺少: " + expected);
        }
    }

    @Test
    @DisplayName("deck 装载:场景声学与 ViPER 在同一时机挂接")
    void deckLoadInstallsChain() throws IOException {
        Path root = repoRoot();
        Path player = root.resolve("muonium-common/src/main/java/com/muoniumplayer/core/ncm/music/AudioPlayer.java");
        assertTrue(Files.isRegularFile(player), "缺文件: " + player);
        String src = read(player);
        int viper = src.indexOf("ViperRuntime.ensureInstalled()");
        int acoustics = src.indexOf("AcousticsRuntime.ensureInstalled()");
        assertTrue(viper >= 0 && acoustics > viper, "挂接顺序:声学应在 ViPER 之后同一时机");
        assertTrue(src.contains("AcousticsRuntime.onTrackChanged"), "换曲/seek 必须清声场");
    }

    // ---- 世界声源(暂仅 26.3;铺树后把 26.3 专属断言并入全树循环) ----

    private static final String SPEAKER_TREE = "26.3";

    @Test
    @DisplayName("26.3:世界声源的按键、tick 判定、探针源点切换与渲染桥都在")
    void speakerWiringOn263() throws IOException {
        Path root = repoRoot();
        String client = read(root.resolve(SPEAKER_TREE)
                .resolve("src/main/java/com/muoniumplayer/MuoniumPlayerClient.java"));
        // 26.3 的 MuoniumPlayerClient 是 FORK:字段/构造/tick 消费都断言
        assertTrue(client.contains("keySpeakerPlace"), "26.3 缺声源按键定义");
        assertTrue(client.contains("SpeakerManager.toggle()"), "26.3 tick 没消费声源放置键");
        assertTrue(client.contains("SpeakerManager.tick()"), "26.3 tick 没跑声源降级判定");
        assertTrue(client.contains("SpeakerManager.init()"), "26.3 init 没恢复声源锚点");

        String probe = read(root.resolve(SPEAKER_TREE)
                .resolve("src/main/java/com/muoniumplayer/compat/mc/ListenerProbe.java"));
        assertTrue(probe.contains("SpeakerManager.samplingOrigin"), "26.3 探针没接声源采样原点");
        assertTrue(probe.contains("directOcclusion"), "26.3 探针没采直射遮挡");
        assertTrue(probe.contains("hasDirectSource"), "26.3 采样没交付直射声字段");

        Path manager = root.resolve(SPEAKER_TREE)
                .resolve("src/main/java/com/muoniumplayer/compat/mc/SpeakerManager.java");
        assertTrue(Files.isRegularFile(manager), "26.3 缺 SpeakerManager");
        String mgr = read(manager);
        assertTrue(mgr.contains("SpeakerState.RANGE_METERS"), "SpeakerManager 没用共享射程常量");
        assertTrue(mgr.contains("SpeakerStore.load") && mgr.contains("SpeakerStore.save"),
                "SpeakerManager 没接锚点持久化");

        String render = read(root.resolve(SPEAKER_TREE)
                .resolve("src/main/java/com/muoniumplayer/compat/mc/SpeakerRenderBridge.java"));
        assertTrue(render.contains("submitCustomGeometry"), "渲染桥没走 submit-collect");

        // 两个加载器都要注册渲染桥
        String fabric = read(root.resolve(SPEAKER_TREE)
                .resolve("fabric/src/main/java/com/muoniumplayer/MuoniumPlayerMod.java"));
        assertTrue(fabric.contains("SpeakerRenderBridge.collect"), "26.3 fabric 没注册声源渲染桥");
        String neo = read(root.resolve(SPEAKER_TREE)
                .resolve("neoforge/src/main/java/com/muoniumplayer/loader/neoforge/MuoniumPlayerNeoForge.java"));
        assertTrue(neo.contains("SpeakerRenderBridge.collect"), "26.3 neoforge 没注册声源渲染桥");
    }
}
