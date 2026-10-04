package com.muoniumplayer.core.rendering;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class AutomixTransitionAnimationStaticTest {
    private static String read(String relative) throws Exception {
        return Files.readString(ProjectPaths.sourceFile(Paths.get(relative)));
    }

    @Test
    void curvesAreOnlyDrawnInsideTheExistingIsland() throws Exception {
        String animation = read("com/muoniumplayer/core/rendering/AutomixTransitionAnimation.java");
        String island = read("com/muoniumplayer/core/rendering/DownloadDynamicIsland.java");
        String renderer = read("com/muoniumplayer/core/rendering/AutomixIslandRenderer.java");
        assertTrue(animation.contains("TransitionAnimationStyle.CURVE) return"));
        assertFalse(animation.contains("drawCurveStage"), "不能保留屏幕中央的大曲线面板");
        assertTrue(island.contains("AutomixTransitionAnimation.islandFrame()"));
        assertTrue(island.contains("AutomixIslandRenderer.draw(SkijaGfx.canvas(), mixFrame"));
        assertTrue(island.contains("playerAbsorbImpact || mixMode"));
        assertTrue(island.contains("if (!surfaceActive) {"));
        assertTrue(island.contains("targetExpandedWidth = compactWidth;"),
                "退场不能重新扩大成下载兜底卡片");
        assertTrue(island.contains("!activeDownload && !holdingCompletion"));
        assertTrue(island.contains("allowsAutomixCurves(sourceNoticeType)"));
        int matrix = island.indexOf("scaleAtPos(centerX, topMargin, configuredScale)");
        int anchor = island.indexOf("LIVE_ANCHOR_BOUNDS[0] = centerX", matrix);
        int chart = island.indexOf("AutomixIslandRenderer.draw", anchor);
        assertTrue(matrix > 0 && anchor > matrix && chart > anchor,
                "曲线必须复用灵动岛的缩放和真实锚点，不是另开浮层");
        assertTrue(renderer.contains("canvas.clipRRect(bounds, true)"));
        assertFalse(renderer.contains("drawString"));
        assertFalse(renderer.contains("Math.sin"), "不能用伪造的正弦波冒充真实处理曲线");
    }

    @Test
    void planAndPauseClockAreSharedAndShortFadesAreSupported() throws Exception {
        String animation = read("com/muoniumplayer/core/rendering/AutomixTransitionAnimation.java");
        String cloud = read("com/muoniumplayer/core/ncm/music/CloudMusic.java");
        String renderer = read("com/muoniumplayer/core/rendering/AutomixIslandRenderer.java");
        assertTrue(cloud.contains("AutomixTransitionCurve.from(plan)"));
        assertTrue(cloud.contains("curveAnimation || AutomixSettings.isFoliaEngine()"));
        assertTrue(animation.contains("MIN_ISLAND_DURATION_MILLIS = 800L"));
        assertTrue(animation.contains("long elapsed = elapsedNanos(run, now)"));
        assertTrue(renderer.contains("frame.curve.value("));
        assertTrue(renderer.contains("sampleProgress(frame.progress, x)"));
        assertTrue(cloud.indexOf("AutomixTransitionAnimation.start(effectiveOverlap")
                < cloud.indexOf("final AudioPlayer callbackPlayer = incoming"),
                "动画起点不能等封面、歌词和播放上报");
    }
}
