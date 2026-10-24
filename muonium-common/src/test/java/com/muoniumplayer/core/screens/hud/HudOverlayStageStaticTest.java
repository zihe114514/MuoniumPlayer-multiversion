package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HUD 叠加层舞台（点选 / 拖动 / 边缘缩放 / 快捷小窗）的不变量。
 *
 * <p>这一层是纯交互逻辑，最容易退回老的坏习惯：按下即拖（没法点选）、把把手做成装饰
 * （命中不走同一份几何）、缩放写死数字（和设置页滑条的钳制悄悄分叉）、每一帧都落盘。
 * 下面把这些钉住；顺带钉「小窗不遮本体」的摆位次序。</p>
 */
class HudOverlayStageStaticTest {

    private static String stage() throws IOException {
        return read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", "HudOverlayStage.java")));
    }

    private static String editor() throws IOException {
        return read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", "GuiHudEditorPro.java")));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("点选与拖动靠位移阈值区分，纯点按不会进入拖动")
    void selectionUsesMoveThreshold() throws IOException {
        String body = stage();
        assertTrue(body.contains("SELECT_THRESHOLD = 4"),
                "点选阈值没了：按下即拖的老交互回来了");
        assertTrue(body.contains("Math.abs(mx - pressX) <= SELECT_THRESHOLD")
                        && body.contains("Math.abs(my - pressY) <= SELECT_THRESHOLD"),
                "阈值没参与判定");
        assertTrue(body.contains("selected = selected == engaged ? Kind.NONE : engaged"),
                "松开时的选中/取消选中语义变了：再点同一块应取消选中");
    }

    @Test
    @DisplayName("把手判定先角后边，命中带与绘制把手来自同一份几何")
    void handleHitPrefersCorners() throws IOException {
        String body = stage();
        int nw = body.indexOf("if (nearL && nearT) return Zone.NW;");
        int n = body.indexOf("if (nearT) return Zone.N;");
        assertTrue(nw >= 0 && n >= 0 && nw < n,
                "角必须在边之前判定，否则角落永远只能拖到边");
        assertTrue(body.contains("static Zone zoneAt(int[] r, int mx, int my)"),
                "把手判定要是静态纯函数：渲染与命中共用它");
        assertTrue(body.contains("List<int[]> handleRects(int[] r)"),
                "把手矩形由舞台提供，编辑器不得自己另算一份");
    }

    @Test
    @DisplayName("歌词缩放的钳制来自 HudSetting 滑条声明，不另写一套数字")
    void resizeClampsFollowSliderDeclarations() throws IOException {
        String body = stage();
        assertTrue(body.contains("HudSetting.OSD_WIDTH.getMin()")
                        && body.contains("HudSetting.OSD_WIDTH.getMax()")
                        && body.contains("HudSetting.OSD_HEIGHT.getMin()")
                        && body.contains("HudSetting.OSD_HEIGHT.getMax()"),
                "宽高钳制没有走 OSD_WIDTH/OSD_HEIGHT 的声明：会和设置页滑条分叉");
        assertTrue(body.contains("HudConfig.clampScale("),
                "整体缩放没走 HudConfig.clampScale");
    }

    @Test
    @DisplayName("落盘只发生在舞台报告「松手且有改动」时")
    void savesOnlyOnDirtyRelease() throws IOException {
        String body = editor();
        assertTrue(body.contains("boolean stageDirty = stage.update("),
                "编辑器要把舞台的松手脏标记接住");
        assertTrue(body.contains("if (stageDirty) HudConfig.save();"),
                "松手之外的路径不许顺手 save：拖动中每帧落盘会把磁盘写爆");
    }

    @Test
    @DisplayName("快捷小窗优先摆在选中块旁边，不遮住本体")
    void popoverPrefersBesideNotOver() throws IOException {
        String body = stage();
        assertTrue(body.contains("static int[] popoverBounds("),
                "小窗摆位要是纯静态函数：渲染与命中共用");
        int right = body.indexOf("int x = r[0] + r[2] + gap;");
        assertTrue(right >= 0, "首选应当是本体右侧");
        assertTrue(body.indexOf("if (x + popW > screenW - 4) x = r[0] - popW - gap;") > right,
                "右侧放不下要翻到左侧，而不是退化为盖住本体");
    }

    @Test
    @DisplayName("编辑器对舞台让路：面板 / 取色器 / 小窗忙时舞台不吃输入")
    void stageYieldsWhileUiBusy() throws IOException {
        String body = editor();
        assertTrue(body.contains("boolean overPopover = stage.hasSelection()"),
                "小窗区域要算进 uiBusy");
        assertTrue(body.contains("stage.update(lmb && !uiBusy, uiBusy ? 0 : wheel,"),
                "忙时左键与滚轮都不能进舞台");
    }
}
