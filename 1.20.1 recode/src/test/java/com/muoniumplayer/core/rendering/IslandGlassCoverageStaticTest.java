package com.muoniumplayer.core.rendering;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灵动岛的八款样式里，真背景到底铺上了几款。
 *
 * <h2>为什么单独钉这个</h2>
 * 2026-09-30 玩家报「HUD 界面的不是实时渲染，例如歌词 HUD、灵动岛」。查下来跟采集、坐标、
 * 帧率闸都没关系：八款岛形样式里只有「液态玻璃」那一款调过 {@code paintFrost}，其余各款
 * 画的是自己那套按时间摆动的假渐变——它当然不会跟着身后的画面走。而他用的是默认的经典胶囊。
 *
 * <p>这类漏接<em>不会</em>让任何东西报错，也不会让构建变红：样式分派是 if-else 链，新增一款
 * 或者搬运某一款时忘记接玻璃，症状只是「那一款没有玻璃」，而截图上一块没有玻璃的岛和一块
 * 玻璃浓度调到最低的岛长得几乎一样。所以把结构钉在这里。</p>
 *
 * <h2>钉住的三件事</h2>
 * <ol>
 *   <li>铺背景那一步在<em>样式分派之前</em>——这样后来加进分派链的样式会天然走上玻璃，
 *       不需要作者记得再接一次。</li>
 *   <li>凡是画「整块岛形底」（矩形就是 {@code x, y, width, height}）的分支，那一层都必须按
 *       玻璃浓度压薄，否则真背景被自己的实色底挡死，铺了等于没铺。</li>
 *   <li>回弹胶囊排在铺背景之前就已经 return：那是作者另一份稿子的 1:1 复刻，纯色平面是它
 *       造型的一部分。这条写反了也会静默改变那一款的观感，所以同样钉住。</li>
 * </ol>
 */
class IslandGlassCoverageStaticTest {

    private static final Pattern FULL_PLATE = Pattern.compile(
            "roundedRect\\(\\s*x,\\s*y,\\s*width,\\s*height,\\s*radius,\\s*([^;]*?)\\)\\s*;");

    /** 岛形主体：从样式分派前那一步到交接光环之前。 */
    private static String islandBody() throws Exception {
        String src = read(island());
        int start = src.indexOf("private void drawIsland(");
        assertTrue(start >= 0, "找不到 drawIsland，改动方式变了要一起改这份测试");
        int frost = src.indexOf("GlassLook.paintFrost(GlassLook.Surface.ISLAND", start);
        assertTrue(frost >= 0, "灵动岛主体里已经没有铺真背景这一步了");
        int end = src.indexOf("if (absorbGlow > .01)", frost);
        assertTrue(end > frost, "找不到主体结尾的锚点");
        return src.substring(frost, end);
    }

    @Test
    @DisplayName("铺真背景排在样式分派之前，新加进分派链的样式自动走上玻璃")
    void frostPrecedesStyleDispatch() throws Exception {
        String src = read(island());
        int start = src.indexOf("private void drawIsland(");
        int frost = src.indexOf("GlassLook.paintFrost(GlassLook.Surface.ISLAND", start);
        int dispatch = src.indexOf("if (liquidGlass) {", frost);
        assertTrue(frost > 0 && dispatch > frost,
                "铺背景那一步必须排在样式分派之前，否则以后加一款样式就又会静默没有玻璃");
    }

    @Test
    @DisplayName("回弹胶囊仍然在铺背景之前就 return —— 那一款的纯色平面是造型的一部分")
    void bounceStyleStaysOutOfGlass() throws Exception {
        String src = read(island());
        int start = src.indexOf("private void drawIsland(");
        int bounce = src.indexOf("drawBounceIsland(", start);
        int frost = src.indexOf("GlassLook.paintFrost(GlassLook.Surface.ISLAND", start);
        assertTrue(bounce > 0 && bounce < frost, "回弹胶囊现在也走上玻璃了，那一款是 1:1 复刻稿");
        assertTrue(src.substring(bounce, frost).contains("return;"),
                "回弹胶囊那一段必须原样提前返回");
    }

    @Test
    @DisplayName("每一块整岛尺寸的深色底都按玻璃浓度压薄")
    void everyFullSizePlateIsThinned() throws Exception {
        List<String> plates = new ArrayList<>();
        Matcher m = FULL_PLATE.matcher(islandBody());
        while (m.find()) {
            plates.add(m.group(1));
        }
        // 通透玻璃 / 浮层卡片 / 音乐聚焦 / 系统通知 / 经典胶囊与紧凑状态（同一张 else 底）。
        assertEquals(5, plates.size(), "整岛尺寸的底板数量变了：" + plates);
        for (String plate : plates) {
            assertTrue(plate.contains("thin"), "这块岛形底没按玻璃浓度压薄，铺上背景也看不见: " + plate);
        }
    }

    @Test
    @DisplayName("液态玻璃那一款仍然在自己的表面里铺背景，而不是靠主体这一次")
    void liquidGlassStyleKeepsItsOwnPlate() throws Exception {
        String src = read(island());
        int surface = src.indexOf("private void renderLiquidGlassSurface(");
        assertTrue(surface > 0);
        assertTrue(src.substring(surface, surface + 3000)
                        .contains("GlassLook.paintFrost(GlassLook.Surface.ISLAND"),
                "液态玻璃自己的表面不再铺背景，而主体的那一步是按 !liquidGlass 跳过的——两条都断了");
        assertFalse(islandBody().isEmpty());
    }

    @Test
    @DisplayName("描边与高光不受浓度影响，只有镜面高光跟着压薄")
    void outlinesStayWhileSheenFollowsTint() throws Exception {
        String src = read(island());
        int sheen = src.indexOf("if (!systemCard && !musicFocus && !liquidGlass) {");
        assertTrue(sheen > 0);
        assertTrue(src.substring(sheen, sheen + 600).contains("* thin"),
                "顶部那条镜面高光必须跟着浓度走，否则它会在真背景上挡出一条不透明的膜");
    }

    private static Path island() {
        return ProjectPaths.sharedJava()
                .resolve(Paths.get("com", "muoniumplayer", "core", "rendering",
                        "DownloadDynamicIsland.java"));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
