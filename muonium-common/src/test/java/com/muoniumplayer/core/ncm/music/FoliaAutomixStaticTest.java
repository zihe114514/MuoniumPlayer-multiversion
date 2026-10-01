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
        assertTrue(eventHandler.contains("AutomixTransitionAnimation.render()"));
        assertTrue(dispatcher.contains("return FoliaAutomixPlanner.plan(outgoing, incoming, lastVocalEndMillis);"));
        assertTrue(planner.contains("FoliaTransitionStyle.BEAT_CUT"));
        assertTrue(planner.contains("FoliaTransitionStyle.TAIL_RIDE"));
        assertTrue(planner.contains("FoliaTransitionStyle.PLAIN_BLEND"));
        assertTrue(planner.contains("stemDriven") && planner.contains("bassEndMillis"));
        assertTrue(planner.contains("quantiseToMusic") && planner.contains("BEATS_PER_PHRASE"));
        assertTrue(!planner.contains("Math.min(12_000L, overlap)"));
        assertTrue(planner.contains("double drumAt") && planner.contains("double bassAt"));
        assertTrue(plan.contains("drumHandoverAt") && plan.contains("incomingMidDb"));
        assertTrue(profile.contains("loadOrAnalyse"));
        assertTrue(profile.contains("sectionEdges"));
        assertTrue(profile.contains("FoliaStemSeparator.analyse"));
        assertTrue(profile.contains("AutomixSettings.isPerformanceModeEnabled()"));
        assertTrue(planner.contains("AutomixSettings.isPerformanceModeEnabled() && stems != null"));
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
