package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放器「二级界面」的布局轴括号不变量（87a20447 的回归防线）。
 *
 * <p>背景：播放器内的浮层（音源/账号/一起听…）本就在 NCMScreen 的自适应括号里；
 * 真正跟随 GUI Scale 缩放的是一批独立宿主 Screen 与引导页 —— 它们曾显式
 * {@code RenderSystem.FIXED_SCALE = false} 或干脆不进括号，排版常量（布局轴书写）
 * 在高档位下整体变大移位。本测试钉两件事：</p>
 * <ol>
 *   <li><b>不许再显式退回 GUI 轴</b>：这批文件里不允许出现 {@code FIXED_SCALE = false}
 *       的手工赋值（WindowAdaptiveScale.end() 内部那一次是唯一的合法写点）；</li>
 *   <li><b>括号成对</b>：beginForced() 的每个出现文件必须同时有 end() 的 finally 配对，
 *       且命中换算（除 sx / correctionX）与绘制同层 —— 只换绘制不换命中就是点不着。</li>
 * </ol>
 */
class SecondaryScreensAdaptiveStaticTest {

    /** 已经进布局轴括号的独立宿主 Screen（各树一份，路径相对树内 src/main/java）。 */
    private static final String[] HOST_SCREENS = {
            "com/muoniumplayer/core/screens/GuiAnnouncements.java",
            "com/muoniumplayer/core/screens/GuiUpdateNotice.java",
            "com/muoniumplayer/core/screens/GuiFfmpegNotice.java",
    };

    /** 共享层的两个括号消费方。 */
    private static final String[] SHARED_SCREENS = {
            "com/muoniumplayer/core/screens/onboarding/OnboardingScreen.java",
            "com/muoniumplayer/core/screens/ncm/IslandSearchOverlay.java",
    };

    @Test
    @DisplayName("提示类宿主 Screen：进括号 + 命中换算，且不许手工退回 GUI 轴")
    void hostScreensOwnTheirLayoutScope() throws IOException {
        for (String rel : HOST_SCREENS) {
            for (Path p : ProjectPaths.sourceDirs("com/muoniumplayer/core/screens")) {
                Path file = p.resolve(Paths.get(rel.substring(rel.lastIndexOf('/') + 1)));
                if (!Files.exists(file)) {
                    continue;
                }
                String src = read(file);
                assertTrue(src.contains("WindowAdaptiveScale.beginForced()"),
                        rel + " 必须进强制布局括号：排版常量按布局轴书写，GUI 轴下跟随 GUI Scale 缩放");
                assertTrue(src.contains("WindowAdaptiveScale.end()"),
                        rel + " 的括号必须有 end() 配对（finally），否则泄漏到 HUD 帧");
                assertTrue(src.contains("mouseX / sx"),
                        rel + " 的鼠标必须除 correction 换算到布局轴——只换绘制不换命中就是点不着");
                assertFalse(src.contains("RenderSystem.FIXED_SCALE = false"),
                        rel + " 不许手工退回 GUI 轴（这是「界面跟随 GUI Scale」的根因）；"
                                + "退轴只允许经 WindowAdaptiveScale.end()");
            }
        }
    }

    @Test
    @DisplayName("引导页与灵动岛搜索：共享层同样进括号")
    void sharedScreensOwnTheirLayoutScope() throws IOException {
        for (String rel : SHARED_SCREENS) {
            String src = read(ProjectPaths.sourceFile(rel));
            assertTrue(src.contains("beginForced()"),
                    rel + " 必须进强制布局括号（登录浮层/搜索胶囊的几何都按布局轴书写）");
            assertTrue(src.contains("WindowAdaptiveScale.end()"),
                    rel + " 的括号必须有 end() 配对");
            assertFalse(src.contains("RenderSystem.FIXED_SCALE = false"),
                    rel + " 不许手工退回 GUI 轴");
        }
    }

    @Test
    @DisplayName("OnboardingScreen：drawScreen 与 mouseClicked 同括号（命中与绘制同轴）")
    void onboardingClickPathIsBracketed() throws IOException {
        String src = read(ProjectPaths.sourceFile("com/muoniumplayer/core/screens/onboarding/OnboardingScreen.java"));
        long begins = count(src, "WindowAdaptiveScale.beginForced()");
        long ends = count(src, "WindowAdaptiveScale.end()");
        assertTrue(begins >= 2 && begins == ends,
                "drawScreen 与 mouseClicked 各自一对括号（各自都有提前 return，不能共享一层）："
                        + "begin=" + begins + " end=" + ends);
        // 登录浮层必须仍然在括号内渲染（setBounds 喂布局轴宽高）。
        assertTrue(src.contains("accountOverlay.setBounds(0, 0, width, height)"),
                "登录浮层的 bounds 必须在括号内喂布局轴宽高——它在 GUI 轴下就是用户报的「登录菜单不受控」");
    }

    @Test
    @DisplayName("IslandSearchOverlay：toggle 的初始目标也按布局轴捕获")
    void islandSearchToggleCapturesInLayoutAxis() throws IOException {
        String src = read(ProjectPaths.sourceFile("com/muoniumplayer/core/screens/ncm/IslandSearchOverlay.java"));
        String toggle = body(src, "public static void toggle()");
        assertTrue(toggle.contains("beginForced()") && toggle.contains("WindowAdaptiveScale.end()"),
                "toggle 的 capsuleTarget() 必须在布局括号内执行：转场几何恒按布局轴存储，"
                        + "热键触发点不在播放器括号内，不补括号则初始目标按 GUI 轴算、高档位错位");
        // 输入框命中必须喂换算后的布局坐标（曾直接传 Screen 的原始 GUI 坐标，3x/4x 点不中输入框）。
        assertTrue(src.contains("this.searchField.mouseClicked((int) Math.round(mouseX), (int) Math.round(mouseY)"),
                "输入框命中必须喂布局坐标，不是 Screen 的原始 GUI 坐标");
    }

    @Test
    @DisplayName("五树的提示类宿主全部同步（其余四棵不许掉队）")
    void allTreesCarryTheBrackets() throws IOException {
        Path repo = ProjectPaths.repoRoot();
        List<String> missing = new ArrayList<>();
        for (String tree : Arrays.asList("1.20.1 recode", "1.21.1", "1.21.11 recode", "26.2", "26.3")) {
            for (String rel : HOST_SCREENS) {
                Path file = repo.resolve(tree + File.separatorChar + "src" + File.separatorChar
                        + "main" + File.separatorChar + "java" + File.separatorChar
                        + rel.replace('/', File.separatorChar));
                if (!Files.exists(file)) {
                    missing.add(tree + "/" + rel + "（文件不存在）");
                    continue;
                }
                String src = read(file);
                if (!src.contains("WindowAdaptiveScale.beginForced()")) {
                    missing.add(tree + "/" + rel);
                }
            }
        }
        assertTrue(missing.isEmpty(), "以下树/文件缺布局括号（87a20447 的四树同步遗漏检测）：" + missing);
    }

    // ------------------------------------------------------------------ 工具

    /** 提取方法体（自方法签名起到下一个「    }」缩进层为止）。 */
    private static String body(String src, String signature) {
        int start = src.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int open = src.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(start, i + 1);
                }
            }
        }
        return src.substring(start);
    }

    private static long count(String src, String needle) {
        long n = 0;
        int i = 0;
        while ((i = src.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
