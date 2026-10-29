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
        // 岛锚点恒按布局轴上报，islandToCurrent* 先折算成本捕获时刻的上下文坐标，
        // toLayout* 再换回布局轴 —— 两段相乘在括号内/外、开关开/关下都是恒等闭环。
        assertTrue(transition.contains("startX = WindowAdaptiveScale.toLayoutX(WindowAdaptiveScale.islandToCurrentX(island[0]))"),
                "实时岛起点必须经 islandToCurrent → toLayout 闭环转换到播放器坐标");
        assertTrue(transition.contains("startWidth = WindowAdaptiveScale.toLayoutWidth(WindowAdaptiveScale.islandToCurrentWidth(island[2]))"),
                "实时岛宽度必须闭环转换，否则 GUI Scale 越高窗口越偏");
        assertTrue(transition.contains("capsuleX = WindowAdaptiveScale.toLayoutX(WindowAdaptiveScale.islandToCurrentX(island[0]))"),
                "收回胶囊终点必须闭环转换到播放器坐标");
        assertTrue(transition.contains("boolean ownedLayout = !WindowAdaptiveScale.isTransforming()"),
                "initGui 里的 open() 也必须在自适应坐标中捕获最终面板");
    }

    @Test
    @DisplayName("全局灵动岛锚点恒按布局轴上报")
    void islandAnchorUsesLayoutAxis() throws IOException {
        String island = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java")));
        String anchorStart = island.substring(island.indexOf("public static double[] getAnchorBounds()"));
        String anchor = anchorStart.substring(0, anchorStart.indexOf("public static double[] getLiveAnchorBounds()"));
        assertTrue(anchor.contains("RenderSystem.getLayoutWidth() * .5"),
                "岛锚点必须按布局轴上报（与岛渲染同轴、与 FIXED_SCALE 状态无关）；"
                        + "读 getWidthNotScaled() 会随上下文翻转，转场起终点跟着 GUI Scale 漂移");
    }

    @Test
    @DisplayName("HUD 覆盖层渲染入口自带布局括号与 correction 层")
    void hudOverlaysOwnTheirLayoutScope() throws IOException {
        // 灵动岛：render() 进强制布局括号（owned 守卫），renderIsolated 按路径压/不压 correction。
        String island = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "rendering", "DownloadDynamicIsland.java")));
        assertTrue(island.contains("boolean ownedLayout = !RenderSystem.FIXED_SCALE;\n        if (ownedLayout) WindowAdaptiveScale.beginForced();"),
                "灵动岛 render() 必须自带强制布局括号，叠加层大小不得跟随 GUI Scale");
        assertTrue(island.contains("renderIsolated(new Runnable() {"), "灵动岛真实渲染路径签名变了");
        assertTrue(island.contains("}, true);") && island.contains("}, false);"),
                "renderIsolated 的 correction 闸（真实路径 true / 编辑器预览 false）必须成对存在");
        // 频谱与 Automix 动画同理。
        String spectrum = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "rendering", "MusicSpectrumRenderer.java")));
        assertTrue(spectrum.contains("if(ownedLayout) WindowAdaptiveScale.beginForced();"),
                "频谱 renderHud 必须自带强制布局括号");
        String automix = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "rendering", "AutomixTransitionAnimation.java")));
        assertTrue(automix.contains("if (ownedLayout) WindowAdaptiveScale.beginForced();"),
                "Automix 切换动画 render() 必须自带强制布局括号");
    }

    @Test
    @DisplayName("correction 与 FIXED_SCALE 解耦，分母恒为布局轴")
    void correctionIsIndependentOfFixedScale() throws IOException {
        String scale = read(source("WindowAdaptiveScale.java"));
        String correction = scale.substring(scale.indexOf("public static double correctionX()"),
                scale.indexOf("public static int layoutWidth()"));
        assertTrue(correction.contains("RenderSystem.getLayoutWidth()"),
                "correctionX 分母必须是恒定的布局轴宽：getWidth() 随 FIXED_SCALE 翻转，"
                        + "用它会让 HUD 覆盖层的 correction 层退化为 1.0（GUI Scale 3x/4x 变大移位）");
        assertTrue(correction.contains("RenderSystem.getWidthNotScaled()"),
                "correctionX 分子必须是 GUI 逻辑宽（canvas 初始 scale 的倒数）");
        assertTrue(scale.contains("public static double islandToCurrentX(double value)"),
                "岛锚点消费方需要布局轴 → 上下文坐标的折算助手");
        assertTrue(scale.contains("public static double toLayoutXForced(double x)"),
                "HUD 编辑器需要跳过「窗口自适应缩放」开关的坐标换算变体");
        assertTrue(scale.contains("public static boolean beginForced()"),
                "强制布局括号入口必须存在");
    }

    @Test
    @DisplayName("HUD 编辑器强制布局轴，鼠标换算用 Forced 变体")
    void hudEditorUsesForcedLayout() throws IOException {
        String editor = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", "GuiHudEditorPro.java")));
        assertTrue(editor.contains("WindowAdaptiveScale.beginForced();"),
                "编辑器必须无视「窗口自适应缩放」开关强制布局轴：真实 HUD 已无条件布局轴，"
                        + "预览必须同轴，否则开关关闭时预览与实物差一个 correction 因子");
        assertTrue(!editor.contains("WindowAdaptiveScale.toLayoutX(")
                        && !editor.contains("WindowAdaptiveScale.toLayoutY("),
                "编辑器鼠标换算必须用 toLayoutXForced/toLayoutYForced（带开关闸的版本在开关关闭时不换算，预览点不中）");
        assertTrue(editor.contains("WindowAdaptiveScale.toLayoutXForced(mouseX)")
                && editor.contains("WindowAdaptiveScale.toLayoutYForced(mouseY)"),
                "drawScreen/mouseClicked/mouseReleased 的坐标换算点缺失");
    }

    @Test
    @DisplayName("widget 的 correction 闸门仍在（防编辑器预览双乘）")
    void widgetPreviewGateIntact() throws IOException {
        for (String widget : new String[]{"MusicLyricsWidget.java", "MusicInfoWidget.java"}) {
            String source = read(ProjectPaths.sourceFile(Paths.get(
                    "com", "muoniumplayer", "core", "widget", "impl", widget)));
            assertTrue(source.contains("boolean needCorrection = !editorPreviewActive;"),
                    widget + " 的 editorPreviewActive 闸门丢了：编辑器外层已有 correction，"
                            + "widget 再压就是双乘（预览与实物差一个因子）");
        }
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
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "ncm", fileName));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
