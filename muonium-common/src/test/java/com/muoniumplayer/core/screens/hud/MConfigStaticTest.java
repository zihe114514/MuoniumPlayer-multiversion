package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MuoniumConfig（HUD 设置界面的私有现代化呈现）的结构不变量。
 *
 * <p>它和 {@link GuiHudEditorPro} 是同一份内容源（{@code HudSettingsModel}）与同一个舞台
 * （{@code HudOverlayStage}）的第二套呈现。这里钉四件事：</p>
 * <ol>
 *   <li><b>共享层纯净</b>：mconfig 两个文件不许出现任何加载器或 Minecraft 直接 import ——
 *     共享代码与版本树之间只有 compat 垫片这一条合法通道。</li>
 *   <li><b>动效纪律与旧版同款</b>：时长走 SheetClock、全类只允许一处 closeScreen。</li>
 *   <li><b>空 alpha 陷阱</b>：MConfigTheme 的颜色常量必须带真实 alpha 高字节。</li>
 *   <li><b>内容同源</b>：读的是 HudSettingsModel，不许私下再抄一份设置声明。</li>
 * </ol>
 */
class MConfigStaticTest {

    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();

    private static Path hud(String fileName) {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", fileName));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("mconfig 文件零加载器符号、零 Minecraft 直引 —— 共享层只走 compat 垫片")
    void sharedPurity() throws IOException {
        List<String> banned = List.of(
                "import net.minecraft", "import net.fabricmc", "import net.neoforged",
                "import net.minecraftforge", "import cpw.mods", "import icyllis.modernui");
        for (String fileName : List.of("MConfigHudScreen.java", "MConfigTheme.java")) {
            String source = read(hud(fileName));
            List<String> hits = new ArrayList<String>();
            for (String line : source.split("\n")) {
                String trimmed = line.trim();
                for (String prefix : banned) {
                    if (trimmed.startsWith(prefix)) {
                        hits.add(trimmed);
                    }
                }
            }
            assertTrue(hits.isEmpty(), fileName + " 出现了越界 import：" + hits);
        }
    }

    @Test
    @DisplayName("宿主壳是 compat GuiScreen，舞台与内容源都复用旧版那份")
    void reusesStageAndModel() throws IOException {
        String source = read(hud("MConfigHudScreen.java"));
        assertTrue(source.contains("extends GuiScreen"), "宿主壳必须是 compat GuiScreen");
        assertTrue(source.contains("HudOverlayStage"), "编辑舞台必须复用 HudOverlayStage，不许自抄一套几何");
        assertTrue(source.contains("HudSettingsModel.categories()"),
                "内容必须来自 HudSettingsModel（唯一内容源），不许私下再抄一份设置声明");
        assertTrue(source.contains("GlassLook.Surface.EDITORS"),
                "面板底必须走 GlassLook 玻璃面，不许自成一套底色画法");
    }

    @Test
    @DisplayName("动效纪律：时长全部过 SheetClock.speed()，closeScreen 全类只出现一次")
    void motionDiscipline() throws IOException {
        String source = read(hud("MConfigHudScreen.java"));
        assertTrue(source.contains("SheetClock.speed()"),
                "动效时长必须除以 SheetClock.speed()，跟玩家的动画速度档位联动");
        long closeCalls = source.split("closeScreen\\(\\);", -1).length - 1;
        assertTrue(closeCalls == 1,
                "closeScreen() 全类只允许一处（退场播完再关），现在有 " + closeCalls + " 处");
        assertTrue(source.contains("TransitionCurveCache.EXPO"),
                "开场缩放要走 EXPO 曲线（OneConfig 的 easeOutExpo 手感）");
        assertTrue(source.contains("TransitionCurveCache.SPRING_LAND"),
                "开关回弹要走 SPRING_LAND（OneConfig 的 EaseOutBump 手感）");
    }

    @Test
    @DisplayName("MConfigTheme 的颜色常量全部带真实 alpha 高字节（空 alpha 陷阱从源头杜绝）")
    void themeColorsCarryAlpha() throws IOException {
        String theme = read(hud("MConfigTheme.java"));
        Pattern constant = Pattern.compile("0x([0-9A-Fa-f]{8})");
        Pattern declaration =
                Pattern.compile("public static final int ([A-Z0-9_]+) = (0x[0-9A-Fa-f]+);");
        Matcher declarer = declaration.matcher(theme);
        int checked = 0;
        while (declarer.find()) {
            String value = declarer.group(2);
            Matcher hex = constant.matcher(value);
            assertTrue(hex.matches(), declarer.group(1) + " = " + value + " 不是 8 位十六进制");
            boolean emptyAlpha = Character.toUpperCase(value.charAt(2)) == '0'
                    && Character.toUpperCase(value.charAt(3)) == '0';
            assertFalse(emptyAlpha,
                    declarer.group(1) + " = " + value + " 的高字节是 00，画出来是全透明（空 alpha 陷阱）");
            checked++;
        }
        assertTrue(checked >= 20, "MConfigTheme 的颜色常量数量不对，是不是改坏了？数到 " + checked);
    }

    @Test
    @DisplayName("EXPO 曲线登记在共享的 TransitionCurveCache 上")
    void expoCurveRegistered() throws IOException {
        String cache = Files.readString(SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "ncm", "TransitionCurveCache.java")),
                StandardCharsets.UTF_8);
        assertTrue(cache.contains("public static final CachedBezier EXPO = new CachedBezier(0.16, 1.0, 0.3, 1.0);"),
                "EXPO（easeOutExpo 近似）必须长在 TransitionCurveCache 上，五个树才共用同一条曲线");
    }

    @Test
    @DisplayName("旧版内置编辑器不被这次改动牵连： GuiHudEditorPro 仍然分派可达")
    void legacyEditorUntouched() throws IOException {
        String source = read(hud("GuiHudEditorPro.java"));
        assertFalse(source.contains("MConfigHudScreen"),
                "旧版编辑器不许反向认识新版：两套呈现互不依赖，分派层负责切换");
        String client = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "MuoniumPlayerClient.java")));
        assertTrue(client.contains("new GuiHudEditorPro()"),
                "内置编辑器仍是兜底档位，分派链不许拆它");
    }
}
