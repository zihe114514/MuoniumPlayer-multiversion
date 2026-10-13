package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 底部播放条不显示私人 FM「换一首」按钮的契约测试。
 *
 * <p>这颗按钮曾把居中传输簇的右缘外扩 54 单位，与右下角待播/一起听（右锚点、位置固定）
 * 一挤，让位量就把整个按钮组推离居中的进度条——2026-10-13 的错位截图：按钮组悬在进度条
 * 上方、压住剩余时间标签。跳歌动作由 FM 页面自己的「换一首」承担（见 PersonalFmPanel），
 * 底栏不再有自己的那份。</p>
 *
 * <p>全部读源码：ControlsBar 一装配就要建 GL 上下文，跑不起来。</p>
 */
class ControlsBarFmSkipStaticTest {

    private static final Path BAR = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "ControlsBar.java"));

    @Test
    @DisplayName("底栏没有自己的「换一首」按钮，跳歌由 FM 页面承担")
    void barHasNoFmSkipButton() throws IOException {
        String body = read(BAR);
        assertFalse(body.contains("fmSkip"), "底栏里又出现了 fmSkip 按钮");
        assertFalse(body.contains("skipCurrentAndRequestNext"),
                "底栏不该调电台跳歌端点：按钮被删了，调用点也应随之消失");
        // 计数而不是逐字包含：类注释里允许留一句「为什么没有这颗按钮」的解释。
        assertEquals(0, count(body, "RoundedButtonWidget(\"换一首\""),
                "底栏又建了一颗文字为「换一首」的按钮");
    }

    @Test
    @DisplayName("让位公式不再为 FM 外扩传输簇右缘")
    void transportRightHasNoPersonalFmWidening() throws IOException {
        String body = read(BAR);
        String shift = lineOf(body, "double transportRight");
        assertFalse(shift.contains("isPersonalFmActive"),
                "transportRight 仍在按 FM 外扩：按钮组会再次被推离居中的进度条");
        assertFalse(shift.contains("54.0"), "FM 外扩的 54 单位又回到了让位公式里");
        // 居中簇右缘 = 半宽 + playMode 右缘（50+20）。这个数错了，窄面板避让就全部失准。
        assertTrue(shift.contains("half + 70.0"), "transportRight 不再按「half + 70.0」算居中簇右缘");
        // FM 分支删干净之后，播放条级回调里不应再有 isPersonalFmActive 的布局分支。
        // （播放模式按钮的 F 字形降级在它自己的回调里，不受影响。）
        String panelCallback = between(body,
                "this.setBeforeRenderCallback(() -> {\n            double half = this.getWidth() * .5;",
                "centerClusterRight = besideLabel");
        assertFalse(panelCallback.contains("isPersonalFmActive"),
                "让位回调里还留着 FM 分支：布局又在按模式分叉了");
    }

    /** 取包含 {@code marker} 的那一整行；这个记号必须整份源码只有一处。 */
    private static String lineOf(String body, String marker) {
        int at = body.indexOf(marker);
        assertTrue(at >= 0, "找不到 " + marker);
        assertTrue(body.indexOf(marker, at + marker.length()) < 0, marker + " 出现了不止一次");
        int lineStart = body.lastIndexOf('\n', at) + 1;
        int lineEnd = body.indexOf('\n', at);
        return body.substring(lineStart, lineEnd < 0 ? body.length() : lineEnd);
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String body, String from, String to) {
        int start = body.indexOf(from);
        int end = body.indexOf(to);
        assertTrue(start >= 0, "找不到起点：" + from);
        assertTrue(end > start, "找不到终点或顺序错了：" + to);
        return body.substring(start, end);
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            total++;
        }
        return total;
    }
}
