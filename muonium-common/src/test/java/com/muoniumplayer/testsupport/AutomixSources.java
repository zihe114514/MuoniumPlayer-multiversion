package com.muoniumplayer.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** AUTOMIX 静态守卫的严格源码定位：移动包后仍须唯一存在，缺失或重复都失败。 */
public final class AutomixSources {
    private static final Set<String> MODULE_FILES = Set.of(
            "AutomixAnalyzer.java", "AutomixAutoCue.java", "AutomixEnvelope.java",
            "AutomixLearnedEq.java", "AutomixLearnedEqProfile.java", "FoliaTrackProfile.java",
            "FoliaTrackProfiler.java", "AutomixPlan.java", "AutomixPlanner.java", "AutomixPlanCache.java",
            "AutomixFlowPlanner.java", "FoliaAutomixPlanner.java", "FoliaTransitionStyle.java",
            "AutomixSettings.java", "FoliaModelManager.java", "FoliaModelNotice.java",
            "FoliaVocalStem.java", "RoomAutomix.java", "AutomixCueMarker.java", "AutomixTransitionCurve.java",
            "AutomixCuePainter.java", "AutomixIslandRenderer.java", "AutomixTransitionAnimation.java",
            "FoliaOnnx.java", "FoliaBeatThis.java", "FoliaBeatOnnx.java", "AutomixModelStep.java", "FoliaStemSeparator.java",
            "FoliaStemOnnx.java", "FoliaStemProfile.java", "FoliaMelFrontend.java", "FoliaInferenceBudget.java");

    private AutomixSources() {
    }

    /** 按类名定位模块源码，不能退回旧包或返回不存在的占位路径。 */
    public static Path source(String fileName) {
        if (!MODULE_FILES.contains(fileName)) {
            throw new IllegalArgumentException("不是已登记的 AUTOMIX 主体源码: " + fileName);
        }
        Path root = ProjectPaths.sharedJava().resolve(Paths.get("com", "muoniumplayer", "automix"));
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> matches = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .collect(Collectors.toList());
            if (matches.size() != 1) {
                throw new IllegalStateException("AUTOMIX 源码必须唯一存在: " + fileName + "，实际: " + matches);
            }
            Path legacyMusic = ProjectPaths.sharedJava().resolve(Paths.get(
                    "com", "muoniumplayer", "core", "ncm", "music", fileName));
            Path legacyRendering = ProjectPaths.sharedJava().resolve(Paths.get(
                    "com", "muoniumplayer", "core", "rendering", fileName));
            if (Files.exists(legacyMusic) || Files.exists(legacyRendering)) {
                throw new IllegalStateException("AUTOMIX 主体仍残留旧包源码: " + fileName);
            }
            return matches.get(0);
        } catch (IOException failure) {
            throw new IllegalStateException("无法定位 AUTOMIX 源码: " + fileName, failure);
        }
    }

    /** 动态的播放器文件名：模块主体查新目录，宿主播放器仍按原有来源规则定位。 */
    public static Path music(String fileName) {
        return MODULE_FILES.contains(fileName) ? source(fileName) : ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "ncm", "music", fileName));
    }

    /** 兼容静态守卫已有的相对路径参数，只将已登记模块主体交给严格定位。 */
    public static Path sourceFile(Path relative) {
        String name = relative.getFileName().toString();
        return MODULE_FILES.contains(name) ? source(name) : ProjectPaths.sourceFile(relative);
    }
}
