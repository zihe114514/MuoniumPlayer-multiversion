package com.muoniumplayer.core.screens.ncm;

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
 * 「未安装分析模型」提示的离线契约测试。
 *
 * <p>只读源码，不启动 Minecraft、不碰网络、不碰模型目录。这个提示的行为约束几乎全是「位置」与
 * 「不许」，很难用运行时测试表达：闸门必须是纯内存的、两个出口不许改任何设置、浮层必须独占输入、
 * 并且它必须在「本次启动第一次打开播放器」这个时刻出现——而不是每次打开都出现。</p>
 */
class ModelInstallNoticeStaticTest {

    private static final Path JAVA = ProjectPaths.sharedJava();

    @Test
    @DisplayName("闸门是纯内存的「本次启动一次」，且按当前安装情况实时判定")
    void theGateIsSessionScopedAndEvaluatedLive() throws IOException {
        String notice = read(music("FoliaModelNotice.java"));

        assertTrue(notice.contains("private static volatile boolean shownThisSession;"),
                "「本次启动已弹过」必须是纯内存标记：重启后要重新提示");
        assertFalse(notice.contains("ConfigPaths"),
                "闸门不行落盘，否则重启之后就不再提示了");
        assertTrue(notice.contains("public static void markShownThisSession()"),
                "界面挂上去的那一刻必须标记，否则同一帧里会重复挂");

        String should = body(notice, "public static boolean shouldShow()");
        int gate = should.indexOf("if (shownThisSession) return false;");
        int probe = should.indexOf("return !allInstalled();");
        assertTrue(gate >= 0 && probe > gate,
                "必须先查闸门再判定：用户可能在启动后才把模型装好，那种情况下不该再误报");

        // 判定本身出错（目录不可读等）按「没装」处理：最多多弹一次，用户可以关掉。
        assertTrue(body(notice, "public static List<FoliaModelManager.Kind> missing()")
                        .contains("catch (Throwable ignored)"),
                "安装判定抛异常时不能让提示整个炸掉，应降级成「没装」");
    }

    @Test
    @DisplayName("提示覆盖的三个组件与顺序，必须与模型管理器的必需清单一致")
    void theListedComponentsMatchTheRequiredSet() throws IOException {
        String notice = read(music("FoliaModelNotice.java"));
        String overlay = read(screens("ModelInstallNoticeOverlay.java"));

        // 第三个组件是平台感知的 runtimeKind()（安卓展示 ONNX 运行时，桌面展示 Python）。
        String[] ordered = {"Kind.BEAT_THIS", "Kind.HTDEMUCS", "runtimeKind()"};
        int previous = -1;
        for (String kind : ordered) {
            int at = notice.indexOf(kind);
            assertTrue(at > previous, "判定清单必须按 " + kind + " 的顺序覆盖全部三个必需组件");
            previous = at;
        }

        previous = -1;
        for (String kind : ordered) {
            int at = overlay.indexOf(kind);
            assertTrue(at > previous, "展示行必须与判定清单同序，否则「缺哪个」和「装哪个」对不上");
            previous = at;
        }
    }

    @Test
    @DisplayName("提示是播放器内的模态浮层，两个出口都不改动任何设置")
    void theOverlayIsModalAndItsExitsAreHarmless() throws IOException {
        String overlay = read(screens("ModelInstallNoticeOverlay.java"));

        assertTrue(overlay.contains("class ModelInstallNoticeOverlay extends NCMPanel"),
                "必须是播放器自己的浮层，而不是再压一个原版 GuiScreen");
        assertTrue(overlay.contains("FoliaModelManager.statusLabel(kind)"),
                "每个组件这一行要显示它自己的安装状态");
        assertTrue(overlay.contains("FoliaModelManager.downloadAsync("),
                "「全部安装」必须真的去下载，不能只是个说明");

        // 模态的两个必要条件：能被上层收起，且点空白等于取消。
        assertTrue(overlay.contains("public boolean shouldClose()"));
        assertTrue(overlay.contains("public void handleEscape()"));
        assertTrue(overlay.contains("setOnClickCallback((x, y, button) -> {"),
                "点卡片外要等价于「稍后再说」，并吃掉这次点击，否则会落到下层播放器控件上");

        // 只是提示、不是拦路：不装照样能听歌，所以这个浮层不许碰任何已有设置。
        for (String forbidden : new String[]{"AutomixSettings", "ConfigPaths"}) {
            assertFalse(overlay.contains(forbidden),
                    "提示不得改动已有设置：" + forbidden);
        }
    }

    @Test
    @DisplayName("提示在本次启动第一次打开播放器时挂载，并独占鼠标、滚轮与 ESC")
    void theScreenMountsItOnFirstOpenAndHoldsInput() throws IOException {
        String screen = read(screens("NCMScreen.java"));

        assertTrue(screen.contains("public ModelInstallNoticeOverlay modelInstallNoticeOverlay = null;"));
        assertTrue(screen.contains("this.maybeShowModelNotice();"),
                "每帧都要问一次「现在该不该弹」，时机由条件自己收敛");

        String mount = body(screen, "private void maybeShowModelNotice()");
        assertTrue(mount.contains("if (!FoliaModelNotice.shouldShow()) return;"),
                "挂载前必须先过闸门");
        assertTrue(mount.contains("FoliaModelNotice.markShownThisSession();"),
                "挂上之后必须立刻关门");
        // 展开动画期间播放器表面还在动，这时弹居中卡片会跟着漂；全屏歌词与其它模态同理。
        for (String hold : new String[]{"if (PlayerIslandTransition.isRunning()) return;",
                "if (this.musicLyricsPanel != null) return;", "if (isModalOverlayActive()) return;"}) {
            assertTrue(mount.contains(hold), "挂载必须让开：" + hold);
        }

        // 提示必须算进模态浮层，否则滚轮会穿透到下层曲库。
        String modal = body(screen, "private boolean isModalOverlayActive()");
        assertTrue(modal.contains("this.modelInstallNoticeOverlay != null"),
                "提示层要计入模态判定，否则滚轮会连带下层一起滚");

        // 叠放顺序：先到最上层的那一个拿到输入。
        assertBefore(screen,
                "this.modelInstallNoticeOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);",
                "this.confirmationOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);",
                "点击要先给提示层");
        assertBefore(screen, "this.modelInstallNoticeOverlay.handleEscape();",
                "this.confirmationOverlay.cancel();",
                "ESC 要先给提示层，且等价于「稍后再说」，不能顺手把播放器一起关掉");
    }

    @Test
    @DisplayName("关掉播放器只丢掉浮层实例，不重置「本次启动已弹过」")
    void closingTheScreenDoesNotResetTheSessionGate() throws IOException {
        String screen = read(screens("NCMScreen.java"));
        String closed = body(screen, "public void onGuiClosed()");

        assertTrue(closed.contains("this.modelInstallNoticeOverlay = null;"),
                "关闭播放器要丢掉浮层实例，免得下次打开时它还在");
        assertFalse(closed.contains("FoliaModelNotice."),
                "onGuiClosed 不能清掉「本次启动已弹过」的闸门，否则每次开关播放器都会再弹一次");
    }

    private static void assertBefore(String source, String first, String second, String message) {
        int a = source.indexOf(first);
        int b = source.indexOf(second);
        assertTrue(a >= 0, "源码里找不到: " + first);
        assertTrue(b > a, message + "（" + second + " 出现在它之前）");
    }

    /**
     * 某个成员的方法体，按签名定位、按配对的花括号截断。
     *
     * <p>本文件要看的那几个成员里没有带花括号的字符串字面量，写个真解析器只会更容易出错。</p>
     */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "源码里找不到: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "签名后面没有方法体: " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(open, i + 1);
            }
        }
        throw new AssertionError("方法体没有闭合: " + signature);
    }

    private static Path music(String file) {
        return ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "ncm", "music", file));
    }

    private static Path screens(String file) {
        return ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer", "core", "screens", "ncm", file));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
