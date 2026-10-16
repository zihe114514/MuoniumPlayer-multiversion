package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹窗与页面里那些「自己画的一块底」各归哪一档玻璃。
 *
 * <p>2026-10-03 玩家四张截图指着这几处：一起听那三张卡、私人 FM 的推荐卡、下一首播放抽屉、
 * 音乐来源底下那两行开关。它们全都是<em>手搓的</em> RoundedRectWidget / RoundedButtonWidget，
 * 没有走 {@code NcmSheet.card()} 那套已经接过玻璃的公共件，所以一处都没吃上。</p>
 *
 * <p>分档只问一件事：<b>这一块背后是什么</b>。贴在面板页上的（FM hero 卡、那颗「播放」）背后就是
 * 这一帧采进来的游戏画面 → PLATE；浮在我们自己画的面板之上的那一张（待播抽屉）背后是面板加遮罩，
 * 两样都不在采集里 → SHEET（贴压暗过的背景，不折边）；已经落在别人那块玻璃<em>里面</em>的卡、行、
 * 按钮、输入框 → RIM，再各贴一遍真背景就是一排挖在玻璃上的洞。</p>
 *
 * <p>三条反向断言（白底二维码板、全屏遮罩、白色勾选徽标）钉的是「不许上玻璃」：二维码板要能被扫，
 * 遮罩必须实，徽标是盘面上唯一的高对比标记。</p>
 */
class SheetGlassCoverageStaticTest {

    private static String source(String... parts) throws Exception {
        StringBuilder relative = new StringBuilder();
        for (String part : parts) {
            if (relative.length() > 0) {
                relative.append('/');
            }
            relative.append(part);
        }
        Path path = ProjectPaths.sourceFile(relative.toString());
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static final String PARTY = "com/muoniumplayer/core/screens/ncm";
    private static final String PANELS = "com/muoniumplayer/core/screens/ncm/panels";

    /** 取锚点起、到下一个 {@code });} 那一段（一次控件登记 + 它的绘制回调）。 */
    private static String blockOf(String src, String anchor) {
        int at = src.indexOf(anchor);
        assertTrue(at >= 0, "找不到锚点：" + anchor);
        int end = src.indexOf("});", at);
        assertTrue(end > at, anchor + " 之后找不到回调结尾");
        return src.substring(at, end);
    }

    @Test
    @DisplayName("一起听：三张动作卡与服务器房间胶囊走 RIM，主操作那张不被压薄")
    void listenTogetherCardsStayOnRim() throws Exception {
        String src = source(PARTY, "ListenTogetherOverlay.java");
        String card = blockOf(src, "RoundedButtonWidget card = new RoundedButtonWidget(\"\", FontManager.pf14bold);");
        assertTrue(card.contains("setGlassRim(GlassLook.Surface.PLAYER)"),
                "动作卡没有上边缘光：它在弹窗那块玻璃里面，读起来还是一块实心深色板");
        assertTrue(src.contains("card.setFillAlpha(primary ? 1.0f :"),
                "主操作那张（强调色）必须保持实心，否则「哪一张是主入口」被浓度抹掉");
        assertFalse(card.contains("setGlassPlate"), "弹窗里面的卡不许再贴一遍真背景");
        assertTrue(src.contains("pill.setGlassRim(GlassLook.Surface.PLAYER)"),
                "标题右侧那颗「服务器房间」胶囊没上玻璃");
        // 在房判定已扩展为「服务器房间或直连房间任一活跃」：绿色强调跟着走，不许压薄。
        assertTrue(src.contains("pill.setFillAlpha(inRoom || directOn ? 1.0f :"),
                "在房状态那颗是绿色强调，压薄就看不出「已经在房里」了");
    }

    @Test
    @DisplayName("一起听：白底二维码板与全屏遮罩不许上玻璃")
    void qrPlateAndMaskStayOpaque() throws Exception {
        String src = source(PARTY, "ListenTogetherOverlay.java");
        String plate = blockOf(src, "RoundedRectWidget plate = new RoundedRectWidget();");
        assertFalse(plate.contains("setGlass"), "二维码底板是白底，压薄或贴背景都会让手机扫不出来");
        String mask = blockOf(source(PARTY, "NcmSheet.java"), "final RectWidget mask = new RectWidget();");
        assertFalse(mask.contains("setGlass"), "遮罩必须实：它负责把底下那一页压暗");
    }

    @Test
    @DisplayName("音乐来源：解灰与换源那两行走 RIM 并压薄，白色勾选徽标保持实心")
    void sourceSheetRowsGetRim() throws Exception {
        String src = source(PARTY, "MusicSourceOverlay.java");
        String needle = "final RoundedRectWidget surface = new RoundedRectWidget();";
        int rows = 0;
        for (int at = src.indexOf(needle); at >= 0; at = src.indexOf(needle, at + 1)) {
            rows++;
        }
        assertTrue(rows >= 3, "解灰/换源/状态卡这几行的底板结构变了，测试要先跟着改（现在数到 " + rows + "）");
        assertTrue(src.contains("surface.setGlassRim(GlassLook.Surface.PLAYER);"),
                "页脚那两行开关的底板没上边缘光，它还是两块实心深色板");
        assertTrue(src.contains("surface.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));"),
                "底板底色没压薄：贴上去的玻璃会被自己的实心色挡回去");
        String badge = blockOf(src, "final RoundedRectWidget badge = new RoundedRectWidget();");
        assertFalse(badge.contains("setGlass"), "白色勾选徽标上玻璃会把它融进盘面，选中记号就没了");
        assertTrue(src.contains("import com.muoniumplayer.core.rendering.GlassLook;"), "缺 import");
    }

    @Test
    @DisplayName("下一首播放：抽屉是浮在面板上的那一张（SHEET），里面的行只上边光")
    void queueDrawerIsASheet() throws Exception {
        String src = source(PANELS, "PlayQueuePanel.java");
        assertTrue(src.contains("this.drawer.setGlassSheet(GlassLook.Surface.PLAYER);"),
                "抽屉没接玻璃：它还是盖在列表上的一块实心板");
        assertFalse(src.contains("this.drawer.setGlassPlate"),
                "抽屉背后是我们自己画的面板，不在采集里 —— 贴真背景会变成透着晴天的窗");
        String row = blockOf(src, "private QueueRow(Music music) {");
        assertTrue(row.contains("this.setGlassRim(GlassLook.Surface.PLAYER)"),
                "行在抽屉那块玻璃里面，只该上自己那道边缘光");
        assertFalse(row.contains("setGlassPlate"), "行不许在抽屉里再贴一遍真背景");
        assertTrue(src.contains("this.setFillAlpha(dragging ? 1.0f :"),
                "拖起来那一行是强调色，压薄就看不出正在拖的是哪一行");
    }

    @Test
    @DisplayName("私人 FM：hero 卡与那颗「播放」贴在面板上 = 一整块小玻璃")
    void fmHeroIsAPlate() throws Exception {
        String src = source(PANELS, "PersonalFmPanel.java");
        String hero = blockOf(src, "RoundedRectWidget card = new RoundedRectWidget();");
        assertTrue(hero.contains("card.setGlassPlate(GlassLook.Surface.PLAYER)"),
                "hero 卡没贴真背景：它是红框里那块唯一的实心深色板，而底下的曲目行已经是玻璃");
        assertTrue(hero.contains("setFillAlpha(GlassLook.washAlpha"), "hero 卡底色没压薄");
        assertTrue(src.contains("play.setGlassPlate(GlassLook.Surface.PLAYER);"),
                "「播放」和页脚那颗是同一种东西，不该一个是一个不是");
        String skip = blockOf(src, "RoundedButtonWidget skip = new RoundedButtonWidget(");
        assertTrue(skip.contains("skip.setGlassRim(GlassLook.Surface.PLAYER)"),
                "「换一首」是强调色，只上边缘光");
        assertFalse(skip.contains("washAlpha"), "「换一首」被压薄就没有主操作的分量了");
    }

}
