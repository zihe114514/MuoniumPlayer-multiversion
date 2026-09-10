package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窗口自适应缩放的坐标系统一性不变量。
 *
 * <p>播放器布局、展开动画起点、胶囊终点和最终面板必须使用同一套坐标。此前 drawScreen
 * 先按 Minecraft GUI 坐标捕获动画目标、再进入自适应坐标，导致 GUI Scale 改变后目标偏移、
 * 播放器不再居中。</p>
 */
class WindowAdaptiveScaleLayoutStaticTest {

    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();

    @Test
    @DisplayName("drawScreen 在捕获任何几何前进入自适应坐标")
    void adaptiveLayoutBeginsBeforeGeometryCapture() throws IOException {
        String screen = read(source("NCMScreen.java"));
        int drawStart = screen.indexOf("public void drawScreen(int mX, int mY)");
        int inputStart = screen.indexOf("public void mouseClicked(int mX, int mY, int mouseButton)");
        assertTrue(drawStart >= 0, "找不到 NCMScreen.drawScreen");
        assertTrue(inputStart > drawStart, "测试锚点失效：mouseClicked 必须位于 drawScreen 之后");

        int refresh = screen.indexOf("RenderSystem.refreshResolution();", drawStart);
        int begin = screen.indexOf("WindowAdaptiveScale.begin();", drawStart);
        int update = screen.indexOf("this.updatePanelScaleAnimation();", drawStart);
        int transitionTick = screen.indexOf("PlayerIslandTransition.tick();", drawStart);
        assertTrue(refresh >= 0 && refresh < inputStart, "drawScreen 必须先刷新分辨率");
        assertTrue(begin > refresh, "自适应布局必须在读取布局宽高前开启");
        assertTrue(update > begin, "面板尺寸必须在自适应坐标中计算");
        assertTrue(transitionTick > begin, "展开动画目标必须在自适应坐标中捕获");
    }

    @Test
    @DisplayName("展开动画的岛锚点会转换到播放器坐标")
    void islandAnchorsAreConvertedToPlayerLayout() throws IOException {
        String transition = read(source("PlayerIslandTransition.java"));
        assertTrue(transition.contains("startX = WindowAdaptiveScale.toLayoutX(island[0])"),
                "实时岛起点必须从全局 HUD 坐标转换到播放器坐标");
        assertTrue(transition.contains("startWidth = WindowAdaptiveScale.toLayoutWidth(island[2])"),
                "实时岛宽度必须转换，否则 GUI Scale 越高窗口越偏");
        assertTrue(transition.contains("capsuleX = WindowAdaptiveScale.toLayoutX(island[0])"),
                "收回胶囊终点必须转换到播放器坐标");
        assertTrue(transition.contains("boolean ownedLayout = !WindowAdaptiveScale.isTransforming()"),
                "initGui 里的 open() 也必须在自适应坐标中捕获最终面板");
    }

    @Test
    @DisplayName("全局灵动岛锚点始终使用未变换的 GUI 宽度")
    void globalIslandAnchorStaysInGuiCoordinates() throws IOException {
        String island = read(SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java")));
        String anchorStart = island.substring(island.indexOf("public static double[] getAnchorBounds()"));
        String anchor = anchorStart.substring(0, anchorStart.indexOf("public static double[] getLiveAnchorBounds()"));
        assertTrue(anchor.contains("RenderSystem.getWidthNotScaled() * .5"),
                "全局岛锚点不能读取播放器的自适应宽度，否则会被二次转换");
    }

    @Test
    @DisplayName("自适应层提供双精度坐标与尺寸转换")
    void adaptiveLayerConvertsCoordinatesAndSizes() throws IOException {
        String scale = read(source("WindowAdaptiveScale.java"));
        assertTrue(scale.contains("public static double toLayoutX(double x)"));
        assertTrue(scale.contains("public static double toLayoutY(double y)"));
        assertTrue(scale.contains("public static double toLayoutWidth(double width)"));
        assertTrue(scale.contains("public static double toLayoutHeight(double height)"));
        assertTrue(scale.contains("public static double toLayoutRadius(double radius)"));
        assertTrue(scale.contains("beginLayoutIfNeeded()"),
                "转换器必须能在 drawScreen 之外临时进入自适应坐标");
    }

    private static Path source(String fileName) {
        return SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "ncm", fileName));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
