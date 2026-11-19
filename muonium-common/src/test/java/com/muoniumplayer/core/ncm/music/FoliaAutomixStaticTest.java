package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaAutomixStaticTest {
    private static final Path JAVA = ProjectPaths.sharedJava();

    @Test
    void strategyAndProfilePipelineArePresentAndSelectable() throws Exception {
        String settings = read(music("AutomixSettings.java"));
        String planner = read(music("FoliaAutomixPlanner.java"));
        String plan = read(music("AutomixPlan.java"));
        String dispatcher = read(music("AutomixPlanner.java"));
        String cloud = read(music("CloudMusic.java"));
        String profile = read(music("FoliaTrackProfiler.java"));
        String profileModel = read(music("FoliaTrackProfile.java"));
        String autoCue = read(music("AutomixAutoCue.java"));
        String stems = read(music("FoliaStemSeparator.java"));
        String modelManager = read(music("FoliaModelManager.java"));
        String downloadIsland = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java")));
        String hudModel = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", "HudSettingsModel.java")));
        String beatThis = read(music("FoliaBeatThis.java"));
        String animation = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "rendering", "AutomixTransitionAnimation.java")));
        String eventHandler = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "MuoniumPlayerEventHandler.java")));
        String beatRunner = new String(Files.readAllBytes(ProjectPaths.sharedResources().resolve(
                Paths.get("muonium", "automix", "folia_beat_this.py"))), StandardCharsets.UTF_8);
        String htDemucs = new String(Files.readAllBytes(ProjectPaths.sharedResources().resolve(
                Paths.get("muonium", "automix", "folia_htdemucs.py"))), StandardCharsets.UTF_8);
        assertTrue(settings.contains("enum Engine") && settings.contains("isFoliaEngine()"));
        assertTrue(settings.contains("setModelsDirectory") && settings.contains("modelsPath"));
        assertTrue(cloud.contains("effectiveOverlap >= 5_000L"));
        assertTrue(cloud.contains("AutomixTransitionAnimation.start"));
        assertTrue(cloud.contains("plan.animationPeriodMillis"));
        assertTrue(animation.contains("MIN_DURATION_MILLIS = 5_000L"));
        assertTrue(animation.contains("drawArc") && animation.contains("preview()"));
        // 过渡动画的 HUD 渲染入口：2026-11 起从事件处理器挪进灵动岛的 HUD overlay
        //（renderHudOverlay 里与音乐卡做表面仲裁后调用 render()），断言跟到新家。
        assertTrue(downloadIsland.contains("AutomixTransitionAnimation.render()"));
        assertTrue(dispatcher.contains("return FoliaAutomixPlanner.plan(outgoing, incoming, lastVocalEndMillis);"));
        assertTrue(planner.contains("AutomixAutoCue.Result selected = pairCue("),
                "普通 Folia 默认调用整曲配对搜索");
        assertTrue(planner.contains("style != FoliaTransitionStyle.BEAT_CUT"),
                "AutoCue 不得把 Folia 的瞬时 BEAT_CUT 改成普通交叠");
        assertTrue(autoCue.contains("MAX_SEARCH_CANDIDATES = 256"),
                "密集 fallback 的粗排矩阵必须有界");
        assertTrue(autoCue.contains("STRUCTURE_BAR_EVERY = 8")
                && autoCue.contains("lowEndCompatibility(request"),
                "结构八小节候选与独立低频相容项需进入评分");
        assertTrue(planner.contains("AutomixSettings.isAdvancedModeEnabled() && outgoingEnvelope != null"),
                "默认 AutoCue 不能连带开启出场人声处理");
        assertTrue(planner.contains("FoliaTransitionStyle.TAIL_RIDE"));
        assertTrue(planner.contains("FoliaTransitionStyle.PLAIN_BLEND"));
        assertTrue(planner.contains("stemDriven") && planner.contains("bassEndMillis"));
        assertTrue(planner.contains("quantiseToMusic") && planner.contains("BEATS_PER_PHRASE"));
        assertTrue(!planner.contains("Math.min(12_000L, overlap)"));
        assertTrue(planner.contains("double drumAt") && planner.contains("double bassAt"));
        assertTrue(plan.contains("drumHandoverAt") && plan.contains("incomingMidDb"));
        assertTrue(plan.contains("learnedEqControls") && plan.contains("incomingLearnedEqDb"));
        assertTrue(profile.contains("loadOrAnalyse"));
        assertTrue(profile.contains("sectionEdges"));
        assertTrue(profile.contains("cacheKey(source, outgoing)"));
        assertTrue(profile.contains("tailStartMillis"));
        assertTrue(profileModel.contains("VERSION = 5"));
        assertTrue(planner.contains("minimumPlayedMillis(outgoingMillis)"));
        assertTrue(autoCue.contains("request.outgoingGridOriginMillis"));
        assertTrue(autoCue.contains("request.incomingGridOriginMillis"));
        assertTrue(profile.contains("FoliaStemSeparator.analyse"));
        assertTrue(profile.contains("AutomixSettings.isPerformanceModeEnabled()"));
        assertTrue(planner.contains("AutomixSettings.isPerformanceModeEnabled() && stems != null"));
        assertTrue(planner.contains("long availableOverlap = outgoingMillis - earliestFire"));
        assertTrue(planner.contains("if (availableOverlap < MIN_OVERLAP_MILLIS) return null"));
        assertTrue(planner.contains("minimumPlayedMillis(outgoingMillis)"));
        assertTrue(modelManager.contains("ACTIVE_DOWNLOADS") && modelManager.contains("DownloadDynamicIsland.updateProgress"));
        assertTrue(modelManager.contains("下载中 "));
        assertTrue(downloadIsland.contains("showModelOperationResult") && downloadIsland.contains("isDownloadActive()"));
        assertTrue(hudModel.contains("showModelOperationResult"));
        assertTrue(stems.contains("FoliaModelManager.pythonExecutable"));
        assertTrue(stems.contains("folia_htdemucs.py"));
        assertTrue(beatThis.contains("boolean outgoing"));
        assertTrue(beatRunner.contains("mode='reflect'"));
        assertTrue(beatRunner.contains("np.dot(fb, magnitude)"));
        assertTrue(beatRunner.contains("reversed(predictions)"));
        assertTrue(!beatRunner.contains("mag * mag"));
        assertTrue(htDemucs.contains("enable_cpu_mem_arena = False"));
        assertTrue(htDemucs.contains("enable_mem_reuse = False"));
        assertTrue(htDemucs.contains("overlap = segment // 4"));
        assertTrue(htDemucs.contains("\"vocals\"") && htDemucs.contains("\"drums\""));
    }

    private static Path music(String name) { return ProjectPaths.sourceFile(Paths.get("com","muoniumplayer","core","ncm","music",name)); }
    private static String read(Path p) throws Exception { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }
}
