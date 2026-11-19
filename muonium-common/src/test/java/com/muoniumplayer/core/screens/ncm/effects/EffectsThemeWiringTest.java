package com.muoniumplayer.core.screens.ncm.effects;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** 主题入口与排版消费面的接线；不触发全局主题持久化或游戏窗口初始化。 */
class EffectsThemeWiringTest {

    @Test
    void workbenchReadsAllThemeRolesEachFrameBeforeDrawingAndNeverAddsAnotherColorClock() throws Exception {
        String source = Files.readString(ProjectPaths.sharedJava().resolve(
                "com/muoniumplayer/core/screens/ncm/effects/EffectsWorkbench.java"));
        for (String role : new String[]{"GENERIC_BACKGROUND", "ELEMENT_BACKGROUND", "ELEMENT_HOVER", "BORDER",
                "PRIMARY_TEXT", "SECONDARY_TEXT", "ACCENT", "ACCENT_HOVER", "INPUT_BACKGROUND", "NAVIGATION_BACKGROUND"}) {
            assertTrue(source.contains("NCMScreen.getColor(NCMScreen.ColorType." + role + ")"), role);
        }
        int render = source.indexOf("public void onRender(double absoluteX, double absoluteY)");
        int refresh = source.indexOf("refreshPalette();", render);
        int draw = source.indexOf("drawInHost(canvas", render);
        assertTrue(refresh > render && refresh < draw);
        assertFalse(source.contains("NCMTheme.getTargetColor"));
        assertFalse(source.contains("noticeColor"), "提示保存语义而不是冻结某一帧的主题颜色");
        assertTrue(source.contains("private enum NoticeTone"));
        assertTrue(source.contains("painter.setPalette(palette)"));
        assertTrue(source.contains("painter.paragraph("), "长说明必须通过折行布局而不是统一截断");
    }

    @Test
    void directPainterHasNoHardCodedPagePaletteOrOpaqueIconErasure() throws Exception {
        String source = Files.readString(ProjectPaths.sharedJava().resolve(
                "com/muoniumplayer/core/screens/ncm/effects/EffectsPaint.java"));
        assertFalse(source.contains("static final int BACKGROUND"));
        assertFalse(source.contains("static final int PRIMARY"));
        assertTrue(source.contains("palette.input()"));
        assertTrue(source.contains("palette.text()"));
        assertTrue(source.contains("palette.primary()"));
        assertFalse(source.contains("0, SURFACE"), "图标不能用特定底色挖洞，浅色/卡片上会露出色块");
        assertTrue(source.contains("text.split(\"\\\\R\", -1)"), "强制换行必须先按段拆开");
    }
}
