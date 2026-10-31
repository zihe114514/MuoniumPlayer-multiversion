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
            "1.20.1 recode", "1.21.1", "1.21.11 recode", "26.2", "26.3",
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
}
