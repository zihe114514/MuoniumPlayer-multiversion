package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUTOMIX 的离线架构回归测试。
 *
 * <p>测试只读取源码，不启动 Minecraft、音频设备或网络。它保护移植的关键边界：功能默认
 * opt-in、配置独立落盘、关闭时保留旧单 Deck 路径、失败时能够回退，以及 UI 有明确开关。</p>
 */
class AutomixIntegrationStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();

    @Test
    @DisplayName("Automix 默认开启且缺少 engine 的旧配置保持原版引擎")
    void automixDefaultsAndConfigMigrationPreserveLegacyPlayback() throws IOException {
        String settings = read(music("AutomixSettings.java"));
        String paths = read(setting("ConfigPaths.java"));

        assertTrue(settings.contains("private static boolean enabled = true;"),
                "缺少 automix.json 时不能静默关闭全部过渡");
        assertTrue(settings.contains("private static Engine engine = Engine.LEGACY;"),
                "未选择引擎时必须保留原版智能淡化");
        assertTrue(settings.contains("boolean migrateEngine = !root.has(\"engine\")"),
                "旧配置缺少 engine 时必须进入迁移路径");
        assertTrue(settings.contains("engine = Engine.LEGACY;"),
                "旧配置必须迁移为原版引擎，不能静默切到 Folia");
        assertTrue(settings.contains("if (migrateEngine) saveLocked();"),
                "迁移结果必须立即写回，避免每次启动重复判定");
        assertTrue(settings.contains("root.addProperty(\"enabled\", enabled)"));
        assertTrue(paths.contains("AUTOMIX = new File(CONFIG_DIRECTORY, \"automix.json\")"),
                "Automix 配置必须与播放器主配置隔离");
    }

    @Test
    @DisplayName("运行时默认开启且使用原版引擎")
    void runtimeDefaultsKeepOriginalAutomixEnabled() {
        assertTrue(AutomixSettings.isEnabled(), "默认配置必须启用 AUTOMIX");
        assertEquals(AutomixSettings.Engine.LEGACY, AutomixSettings.getEngine(),
                "默认配置必须保留原版智能淡化");
        assertTrue(AutomixSettings.isModelInferenceStatusEnabled(),
                "模型推理状态默认应开启，并可由 HUD 关闭");
    }

    @Test
    @DisplayName("双 Deck 仅在开关开启后接入，任意预备失败均回退旧切歌")
    void playbackPathIsGatedAndHasLegacyFallback() throws IOException {
        String cloud = read(music("CloudMusic.java"));
        String player = read(music("AudioPlayer.java"));

        assertTrue(cloud.contains("if (!AutomixSettings.isEnabled() || awaitingPlaybackAction) return;"),
                "预解码必须受总开关控制");
        assertTrue(cloud.contains("&& AutomixSettings.isEnabled();"),
                "后台预备在运行时关闭开关后必须自动失效");
        assertTrue(cloud.contains("performOrdinaryNextAfterFailedHandover()"),
                "交接失败必须回到原始切歌路径");
        assertTrue(player.contains("if (!AutomixSettings.isEnabled()) return;"),
                "Automix 关闭时不得把 DSP 链接入普通播放器");
    }

    @Test
    @DisplayName("分析、规划、DSP 与用户开关均完整接线")
    void analysisPlannerDspAndSwitchAreWired() throws IOException {
        assertTrue(Files.isRegularFile(music("AutomixAnalyzer.java")));
        assertTrue(Files.isRegularFile(music("AutomixPlanner.java")));
        assertTrue(Files.isRegularFile(music("AutomixPlan.java")));
        assertTrue(Files.isRegularFile(JAVA.resolve(Paths.get(
                "repackage", "processing", "sound", "AutomixDeckChain.java"))));
        assertTrue(Files.isRegularFile(JAVA.resolve(Paths.get(
                "repackage", "processing", "sound", "WsolaTimeStretch.java"))));

        String settingsUi = read(JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", "HudSettingsModel.java")));
        String controls = read(JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "ncm", "panels", "ControlsBar.java")));
        assertTrue(settingsUi.contains("AutomixSettings.setEnabled(!AutomixSettings.isEnabled())"));
        assertTrue(settingsUi.contains("AutomixSettings.setEngine(AutomixSettings.Engine.fromStored(value))"),
                "HUD 选择引擎时必须显式写回配置");
        assertTrue(controls.contains("AutomixSettings.setEnabled(enabled)"));
    }

    @Test
    @DisplayName("模型推理状态可由用户控制，并报告开始与成功完成")
    void modelInferenceStatusNotificationsAreWired() throws IOException {
        String settings = read(music("AutomixSettings.java"));
        String hud = read(JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", "HudSettingsModel.java")));
        String island = read(JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java")));
        String profiler = read(music("FoliaTrackProfiler.java"));
        String audioPlayer = read(music("AudioPlayer.java"));
        String cloudMusic = read(music("CloudMusic.java"));

        assertTrue(settings.contains("private static boolean modelInferenceStatus = true;"));
        assertTrue(settings.contains("root.has(\"modelInferenceStatus\")"));
        assertTrue(settings.contains("root.addProperty(\"modelInferenceStatus\", modelInferenceStatus)"));
        assertTrue(hud.contains("模型推理状态"));
        assertTrue(hud.contains("AutomixSettings.setModelInferenceStatusEnabled"));
        assertTrue(island.contains("public static void beginModelInference"));
        assertTrue(island.contains("public static void finishModelInference"));
        assertTrue(island.contains("MODEL_INFERENCE_SUCCESS"));
        assertTrue(profiler.contains("DownloadDynamicIsland.beginModelInference"));
        assertTrue(profiler.contains("DownloadDynamicIsland.finishModelInference"));
        assertTrue(profiler.contains("AutomixSettings.isModelInferenceStatusEnabled()"));
        assertTrue(audioPlayer.contains("getTrackName()"));
        assertTrue(cloudMusic.contains("deck.setTrackName(trackDisplayName(song, file))"));
        assertTrue(profiler.contains("player.getTrackName()"), "推理通知必须优先使用歌曲元数据而非文件名");
    }

    private static Path music(String file) {
        return JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "ncm", "music", file));
    }

    private static Path setting(String file) {
        return JAVA.resolve(Paths.get("com", "muoniumplayer", "core", "settings", file));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
