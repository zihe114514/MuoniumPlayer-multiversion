package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放条音质菜单的离线契约测试。
 *
 * <p>这一层改坏了不会崩，只会退回用户报过的那三件事上：八行一列的横幅盖住半条进度条、选中态靠
 * 文字里塞一个「✓」（占宽、和中文基线对不齐、未选中的行还得补空格才不跳字）、展开动画是帧耦合的
 * 指数逼近（默认 3x 倍率下两三帧吃掉九成，剩下半透明的一截拖好几帧）。</p>
 *
 * <p>档位图标那一条是<b>真跑</b>：把八个档位各要一枚图标，逐枚去 {@link FoliaLucideIcons#names()}
 * 里查有没有这一枚。读源码钉不住这件事 —— 常量名写错、图标从生成器表里被删掉，源码看着都对，
 * 界面上一片空白。</p>
 */
class QualityMenuStaticTest {

    private static final Path CONTROLS = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "ControlsBar.java"));

    @Test
    @DisplayName("八个档位各有一枚真存在的矢量图标，且互不重复")
    void everyTierHasItsOwnVectorIcon() {
        List<String> used = new ArrayList<>();
        for (Quality quality : Quality.values()) {
            String icon = ControlsBar.qualityTierIcon(quality);
            assertTrue(icon != null && !icon.isEmpty(), quality + " 没有档位图标");
            assertTrue(FoliaLucideIcons.names().contains(icon),
                    quality + " 指的是 " + icon + "，图标表里没有这一枚，画出来就是一块空白");
            used.add(icon);
        }
        Set<String> distinct = new HashSet<>(used);
        assertEquals(used.size(), distinct.size(),
                "有两档共用同一枚图标：菜单里看不出区别，图标就白加了 → " + used);
        // 前四档必须是递增的信号阶梯：这一族的含义就是「码率一档比一档高」。
        assertEquals(List.of(FoliaLucideIcons.SIGNAL_LOW, FoliaLucideIcons.SIGNAL_MEDIUM,
                        FoliaLucideIcons.SIGNAL_HIGH, FoliaLucideIcons.SIGNAL),
                used.subList(0, 4), "前四档不再是递增的信号阶梯：" + used);
    }

    @Test
    @DisplayName("菜单排成两列，列宽按各自最宽的一行，右边缘贴按钮")
    void menuIsTwoColumnsAnchoredToTheButtonRight() throws IOException {
        String body = read(CONTROLS);
        assertTrue(body.contains("QUALITY_COLUMNS = 2"), "两列排版又退回一列");
        // 行优先：读的顺序就是枚举里的档位顺序，不用在脑子里重排一遍。
        assertTrue(body.contains("index % QUALITY_COLUMNS") && body.contains("index / QUALITY_COLUMNS"),
                "行列下标反了：档位顺序会变成竖着读");
        assertTrue(body.contains("qualityColumnWidths[column] = Math.max"),
                "两列又共用一个宽度：「母带」那一列会被「沉浸音质」撑出大片空白");
        // 两列比那颗按钮宽得多，所以对齐的是右边缘（按钮贴在页脚右侧），只有放不下才顶回去。
        assertTrue(flat(body).contains("qualitySelector.getRelativeX() + qualitySelector.getWidth() - menuWidth"),
                "菜单还在按按钮的宽度自己收缩：两列会被挤成一列的宽度");
        assertTrue(body.contains("Math.max(4.0,"),
                "右对齐之后没有左边界兜底：窄面板上菜单会伸出播放器外面");
    }

    @Test
    @DisplayName("勾选位常驻留白，选中态是矢量而不是塞进文字里的符号")
    void selectionIsAVectorWithAReservedGutter() throws IOException {
        String body = read(CONTROLS);
        // 空标签也要留出整条图标 + 勾选的留白，否则列宽就只按文字算，勾会压住最后一个字。
        assertTrue(body.contains("QUALITY_ROW_INSET + QUALITY_ICON_SIZE + QUALITY_ICON_GAP")
                        && body.contains("+ QUALITY_CHECK_GAP + QUALITY_CHECK_SIZE + QUALITY_ROW_INSET"),
                "行宽不再包含勾选位：切档时列宽会跳一下");
        assertTrue(body.contains("qualityOptionCheck.setHidden(!selected)"),
                "勾选不再随选中态开关");
        assertTrue(body.contains("new LabelWidget(() -> formatQuality(option)"),
                "标签里还拼着勾选符号或补位空格");
        // 「✓」与那两枚全角箭头各只许出现一次：那一次是矢量画不出来时的字体兜底，不是画在界面上的东西。
        assertEquals(1, count(body, "\u2713"), "「✓」出现了不止一次：文字里大概还留着一个");
        assertEquals(1, count(body, "\uFE3F"), "全角「︿」出现了不止一次：按钮文字里大概还留着一个");
        assertEquals(1, count(body, "\uFE40"), "全角「﹀」出现了不止一次：按钮文字里大概还留着一个");
    }

    @Test
    @DisplayName("展开动画走真毫秒时钟，进场比退场长")
    void menuRunsOnARealClock() throws IOException {
        String body = read(CONTROLS);
        String ticking = between(body, "RoundedRectWidget qualitySelector = new RoundedRectWidget();",
                "LabelWidget qualityText");
        assertFalse(ticking.contains("Interpolations.interpolate"),
                "展开动画又回到帧耦合指数：没有时长也没有终点，读起来是「先窜一下再慢慢糊住」");
        assertTrue(ticking.contains("System.nanoTime"), "没有自己的真实时间来源");
        assertTrue(ticking.contains("QUALITY_MAX_FRAME_MS"),
                "没有单帧上限：切后台回来一帧跨过整条曲线，展开过程直接被跳过");
        // 倍率只该有一份，第二份迟早和 HUD 编辑器里那一档对不上。
        assertTrue(ticking.contains("SheetClock.speed()"), "时长没有过「界面切换动画速度」倍率");
        assertFalse(ticking.contains("playerUiSwitchSpeed"),
                "播放条自己写了一份 playerUiSwitchSpeed：该复用 SheetClock.speed()");
        // 线性推进到端点再套曲线：中途反悔（连点按钮）才能从当前位置原路折回。
        assertTrue(ticking.contains("qualityMenuOpen ? frameMs : -frameMs"),
                "退场不是从当前位置往回走：进场没播完就关，会先跳回全开再重来");
        String constants = between(body, "QUALITY_OPEN_MS", "qualityTierIcon");
        assertTrue(constants.contains("QUALITY_OPEN_MS = ") && constants.contains("QUALITY_CLOSE_MS = "),
                "进退场时长常量被拆走了");
        assertTrue(openMillis() > closeMillis(), "退场比进场还磨蹭：关闭应当比打开果断");
    }

    @Test
    @DisplayName("揭示仍然跟着上边沿扫，收起途中不再吃点击")
    void revealStillTracksTheSweepingEdge() throws IOException {
        String body = read(CONTROLS);
        assertTrue(body.contains("qualityMenuBackground.getRelativeY()"),
                "选项不再等背景的上边沿扫过才显形：展开途中会有行浮在面板之外");
        assertTrue(body.contains("qualityMenuOpen && reveal > .35"),
                "收起动画期间还在收点击：已经看不清的一行能被误点中");
    }

    private static double openMillis() throws IOException {
        return constant(read(CONTROLS), "QUALITY_OPEN_MS");
    }

    private static double closeMillis() throws IOException {
        return constant(read(CONTROLS), "QUALITY_CLOSE_MS");
    }

    private static double constant(final String source, final String name) {
        int at = source.indexOf(name + " = ");
        assertTrue(at >= 0, "找不到常量 " + name);
        int end = source.indexOf(';', at);
        return Double.parseDouble(source.substring(at + name.length() + 3, end).trim());
    }

    private static int count(final String source, final String needle) {
        int total = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            total++;
        }
        return total;
    }

    /** 折行只影响排版不影响语义：把空白压成单个空格再比，免得改个换行就把测试钉红。 */
    private static String flat(final String source) {
        return source.replaceAll("\\s+", " ");
    }

    /** 截到下一个标记之前：只钉某一段回调里的写法，免得被同文件别处的合法用法干扰。 */
    private static String between(final String source, final String from, final String until) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点 " + from);
        int end = source.indexOf(until, start);
        assertTrue(end > start, "找不到终点 " + until);
        return source.substring(start, end);
    }

    /**
     * 读源码并<b>剥掉注释</b>。
     *
     * <p>这些断言钉的是「不许出现某种写法」，而实现自己的 javadoc 里正好写着
     * {@code Interpolations.interpolate} —— 那是解释为什么不用它。不剥注释就会自己把自己钉红。</p>
     */
    private static String read(final Path path) throws IOException {
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\n]*", "");
    }
}
