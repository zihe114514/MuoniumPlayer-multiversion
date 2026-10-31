package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音质那一圈（触发按钮 + 弹出菜单 + 八格档位）各归哪一档玻璃。
 *
 * <p>2026-09-30 玩家截图指着这块说「添加一下液态玻璃」。三块的位置不一样，档位也就不一样：
 * 触发按钮贴在面板页脚上，背后是这一帧采进来的画面 → PLATE；弹出菜单自己是一块浮在面板上的
 * 玻璃 → PLATE；而菜单<em>里面</em>那八格不能再各自贴一遍真背景 —— 那样每一格透出的都比周围
 * 清楚，读起来是一排挖在玻璃上的洞，所以走 RIM（只上那一圈边缘光）。</p>
 *
 * <p>之所以钉在源码上而不是跑起来看：这三档的差别在截图上很微妙（都是「有点透」），而把选项
 * 改成 PLATE 这种"顺手统一一下"的改法，只会让那块菜单变成马赛克，谁都不会当这是个 bug。
 * 选中那一格同理：它靠实心强调色表达「现在用的是这一档」，被浓度压薄就等于把选择状态抹掉，
 * 而浓度滑块默认值下压完还剩不到两成，肉眼在暗主题上根本分不出来。</p>
 */
class QualityMenuGlassStaticTest {

    private static String source() throws Exception {
        Path path = ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core",
                "screens", "ncm", "panels", "ControlsBar.java"));
        // autocrlf=true 检出会把工作区转成 CRLF，逐字节源码断言按 LF 钳 —— 先归一
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** 取某个控件从自己那次 setColor 之前到回调结束那一段。 */
    private static String blockOf(String src, String anchor) {
        int at = src.indexOf(anchor);
        assertTrue(at >= 0, "找不到锚点：" + anchor);
        int end = src.indexOf("});", at);
        assertTrue(end > at, anchor + " 之后找不到回调结尾");
        return src.substring(at, end);
    }

    @Test
    @DisplayName("触发按钮与弹出菜单都贴真背景（PLATE），底色按浓度压薄")
    void selectorAndMenuUsePlate() throws Exception {
        String src = source();
        String selector = blockOf(src, "qualitySelector\n");
        assertTrue(selector.contains("setGlassPlate(GlassLook.Surface.PLAYER)"),
                "音质按钮没有上 PLATE，它就还是页脚那颗实心深色胶囊");
        assertTrue(selector.contains("setFillAlpha(GlassLook.washAlpha"),
                "音质按钮的底色没压薄：贴上去的真背景会被自己的实心色挡回去");
        String menu = blockOf(src, ".setGlassPlate(GlassLook.Surface.PLAYER)\n                .setBeforeRenderCallback");
        assertTrue(menu.contains("setFillAlpha(GlassLook.washAlpha"), "菜单底色没压薄");
    }

    @Test
    @DisplayName("菜单里那八格档位走 RIM，不重复贴真背景")
    void optionTilesStayOnRim() throws Exception {
        String src = source();
        int at = src.indexOf("qualityOption\n                                .setBounds");
        assertTrue(at > 0, "找不到档位选项的绘制回调");
        String tile = src.substring(at, at + 1600);
        assertTrue(tile.contains("setGlassRim(GlassLook.Surface.PLAYER)"),
                "档位选项没走 RIM：它应该只上那一圈边缘光");
        assertFalse(tile.contains("setGlassPlate"),
                "档位选项在玻璃里面又贴了一遍真背景，整块菜单会变成一排挖在玻璃上的洞");
    }

    @Test
    @DisplayName("选中与悬停那一格不被浓度压薄")
    void selectedTileKeepsItsFill() throws Exception {
        String src = source();
        int at = src.indexOf("setFillAlpha(qualityOption.isHovering()");
        assertTrue(at > 0, "档位选项的 fillAlpha 不再按「选中/悬停」分档，选中状态会被压没");
        String tile = src.substring(at, at + 400);
        assertTrue(tile.contains("1.0f"), "选中那一格也被 washAlpha 压薄了");
        assertTrue(tile.contains("washAlpha"), "未选中的那一格没有压薄，菜单里就还是八块实心板");
    }
}
