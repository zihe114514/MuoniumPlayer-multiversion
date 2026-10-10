package com.muoniumplayer.core.update;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动重启（{@link GameRestart}）的安全阀与接线。
 *
 * <p>这套逻辑会<b>真的关掉玩家的游戏进程</b>，错了不是 bug 是事故，所以每条安全阀都用
 * 源码形状钉死（与伴生包/字体下载器同一路数）：只在主菜单执行、ESC 拆雷、至多重启一次、
 * 看守不删防循环计数、五棵树的 tick 钩子都挂上且用同一条安全闸。</p>
 */
class GameRestartStaticTest {

    /** 五棵版本树（各树 MuoniumPlayerClient 的 tick 钩子是重启唯一的执行入口）。 */
    private static final String[] TREES = {
            "1.20.1 recode", "1.21.1", "1.21.11 recode", "26.2", "26.3"};

    @Test
    @DisplayName("安全阀：只在主菜单停人；ESC 拆雷；至多一次重启；标记两小时过期")
    void everySafetyValveIsPinned() throws IOException {
        String restart = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "update", "GameRestart.java")));

        // 主菜单闸：倒计时只在 idle（mc.level == null）下走；世界里绝不自动关人
        assertTrue(restart.contains("if (!idle) {"),
                "倒计时前必须过主菜单闸，游戏世界里停人是事故");

        // ESC 拆雷：取消时删 marker，否则看守仍会在关游戏后把游戏复活——违背取消意图
        int esc = restart.indexOf("Keyboard.KEY_ESCAPE");
        int deleteOnCancel = restart.indexOf("Files.deleteIfExists(markerFile().toPath())", esc);
        assertTrue(esc >= 0 && deleteOnCancel > esc,
                "ESC 取消必须同时删除重启标记（拆雷）");

        // 防循环计数：至多一次；看守脚本绝不删 marker
        assertEquals(1, countOf(restart, "private static final int MAX_ATTEMPTS = 1;"),
                "自动重启的上限就是「一个标记周期至多一次」");
        assertTrue(restart.contains("MARKER_STALE_MILLIS = 2L"),
                "陈旧标记要重新起算，系统不能被一次意外永久锁死");
        // 脚本行里的注释必须存在（rem 或 #），且没有任何脚本行删 marker
        assertTrue(restart.contains("rem 标记绝不") && restart.contains("# 标记绝不"),
                "看守脚本要留注释说明标记不在此删");
        assertFalse(restart.contains("script.append(\"del ") || restart.contains("script.append(\"rm -f "),
                "看守绝不能删 marker——防循环计数只属于游戏进程");

        // 拿不到启动命令（精简运行时不支持自省）：不布防，回原手动指引
        assertTrue(restart.contains("不自动重启"),
                "命令自省失败必须降级，不能瞎拼一个启动命令");
    }

    @Test
    @DisplayName("接线：五棵树 tick 都挂上且同一条主菜单闸；跃迁拆雷；救援文案播报")
    void wiringIsInPlaceOnEveryTree() throws IOException {
        for (String tree : TREES) {
            Path client = ProjectPaths.repoRoot().resolve(Paths.get(
                    tree, "src", "main", "java", "com", "muoniumplayer", "MuoniumPlayerClient.java"));
            assertTrue(Files.isRegularFile(client), "树缺 MuoniumPlayerClient: " + client);
            String text = new String(Files.readAllBytes(client), StandardCharsets.UTF_8);
            int hook = text.indexOf("GameRestart.tick(mc::stop, mc.level == null);");
            assertTrue(hook >= 0, tree + ": 未挂自动重启 tick 钩子");
            assertTrue(text.indexOf("GuiAnnouncements.tickAutoShow(mc);") < hook,
                    tree + ": 重启钩子要排在公告排队之后（三个提示先过一遍，重启最后问）");
        }

        // 跃迁拆雷：任何一次 TRUE（重启后正常启动 / 热启用成功）都删标记
        String natives = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "render", "skija", "SkijaNatives.java")));
        assertTrue(natives.contains("GameRestart.onNativesLoaded();"),
                "adopt 到 TRUE 要拆重启标记，否则计数带进下一次真实场景");

        // 救援文案：布防后的措辞走 GameRestart.statusLine（含热启用未成功分支）
        assertTrue(natives.contains("GameRestart.statusLine()"),
                "「必须重启」那一段要播报倒计时/取消后的手动指引");

        // 跨包门面的边界：GameRestart 只准依赖 public 门面，不许 import 包级私有的下载器
        // （javadoc 里提到类名讲历史是允许的，禁的是编译依赖）
        String restart = read(ProjectPaths.sourceFile(
                Paths.get("com", "muoniumplayer", "core", "update", "GameRestart.java")));
        assertFalse(restart.contains("import com.muoniumplayer.render.skija.SkijaCompanionDownload;"),
                "重启器要经 SkijaNatives.companionRestartPending 问状态，不 import 包级私有的下载器");
        assertTrue(restart.contains("companionRestartPending()"),
                "布防判据必须来自 public 门面");
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
