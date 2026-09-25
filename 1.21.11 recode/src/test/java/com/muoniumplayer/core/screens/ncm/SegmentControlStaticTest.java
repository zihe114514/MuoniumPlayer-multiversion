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
 * 二段选择器（搜索栏的「歌曲 / 歌单」、账号页的 QQ 链路与酷狗身份）的离线契约测试。
 *
 * <p>和 {@link SheetMotionStaticTest} 同一条理由：这类东西改坏了不会崩，只会难看 —— 滑块还在
 * 半路颜色已经换完、连点时两段指数叠在一起先窜后糊、控件淡出了输入框却没长回来。而且它一加载
 * 就要建 GL 上下文，只能读源码钉性质。</p>
 */
class SegmentControlStaticTest {

    private static final Path NCM = ProjectPaths.sharedJava().resolve(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm"));

    private static final String CONTROL = "SegmentControl.java";
    private static final String ACCOUNT = "AccountManagerOverlay.java";
    private static final Path NAVIGATE = NCM.resolve("panels").resolve("NavigateBar.java");

    @Test
    @DisplayName("滑块走真毫秒时钟，不许留一条帧耦合指数")
    void slideComesFromARealClock() throws IOException {
        String control = read(NCM.resolve(CONTROL));
        assertFalse(control.contains("Interpolations.interpolate"),
                "滑块又回到帧耦合指数：没有时长、尾巴到不了位，连点时两段指数叠在一起就是先窜后糊");
        assertTrue(control.contains("System.nanoTime"),
                "滑块没有自己的真实时间来源");
        assertTrue(control.contains("MAX_FRAME_MS"),
                "没有单帧上限：切后台回来会一次跨过整段");
        // 中途改目标必须从当前位置另起一趟，而不是回到 0 重来。
        String tick = slice(control, "private void tick()");
        assertTrue(tick.contains("legFrom = slide"),
                "换目标时没有记住出发位置：快速连点会跳回起点重走");
    }

    @Test
    @DisplayName("四个时长都要过「动画速度」倍率，不许自己按秒数走")
    void durationsFollowTheSpeedSetting() throws IOException {
        String control = read(NCM.resolve(CONTROL));
        assertTrue(control.contains("SheetClock.speed()"),
                "分段控件不再读全局动画速度：把那条设置拉满时，只有这颗滑块还按自己的秒数走");
        assertTrue(control.contains("frameMs / dur(spec.durationMs)"),
                "滑块的时长没走倍率换算");
        // 倍率只该有一份 clamp，第二份迟早和 HUD 编辑器里那档对不上。
        assertFalse(control.contains("playerUiSwitchSpeed"),
                "共享控件里自己写了一份 playerUiSwitchSpeed：该复用 SheetClock.speed() 那一份");
    }

    @Test
    @DisplayName("三处分段控件全部走同一个共享类，不再各写一份")
    void everySegmentUsesTheSharedControl() throws IOException {
        String account = read(NCM.resolve(ACCOUNT));
        assertFalse(account.contains("segmentChooser") || account.contains("segmentHalf"),
                "账号页还留着自己那份分段控件：它会和共享的那份在动效上分叉");
        assertTrue(count(account, "SegmentControl.attach(") == 2,
                "账号页的 QQ 链路与酷狗身份两格应当都换成共享控件");
        assertTrue(read(NAVIGATE).contains("SegmentControl.attach("),
                "搜索栏的模式开关没有用共享控件");
    }

    @Test
    @DisplayName("支持歌单搜索的来源只有一份名单，可见、可点、让宽都从它推")
    void onePlatformListDecidesTheModeControl() throws IOException {
        String nav = read(NAVIGATE);
        assertTrue(count(nav, "supportsPlaylistSearch(") >= 2,
                "应当有一份共用的判定，并且至少被可见性与让宽两处读到");
        assertFalse(nav.contains("togglePlatform != MusicPlatform"),
                "点击守卫里又写了一份平台名单：这份和决定可见性的那份迟早对不上");
        assertFalse(nav.contains("? 36.0 : 0.0"),
                "输入框让宽那处还留着第三份名单，会让控件压在输入框文字上");
    }

    @Test
    @DisplayName("不支持时整块淡出并把宽度还给输入框，而不是 setHidden 硬消失")
    void fadedSegmentReleasesItsWidth() throws IOException {
        String control = read(NCM.resolve(CONTROL));
        assertFalse(control.contains("setHidden"),
                "共享控件不许 setHidden：一隐藏回调就停，进度会永远淡不完");
        assertTrue(control.contains("public double presence()"),
                "没有把淡出程度报给宿主，宿主就没法把宽度还回去");
        String nav = read(NAVIGATE);
        assertTrue(nav.contains("searchMode.presence()"),
                "搜索框的让宽不再跟着淡出程度走：会出现「控件没了、框还是窄的」");
    }

    @Test
    @DisplayName("占位提示里的词跟着滑块过半才换，不在点击那一刻抢跑")
    void placeholderFollowsTheKnob() throws IOException {
        String nav = read(NAVIGATE);
        assertTrue(nav.contains("searchMode.progress() > .5"),
                "占位提示不再读滑块进度：词会比滑块先到");
        assertFalse(nav.contains("searchField.setPlaceholder(playlistSearchMode"),
                "点击回调里还直接改了占位提示：那是抢在滑块之前换词");
    }

    @Test
    @DisplayName("空图标不留那 6 像素间隙，否则文字被推偏 3 像素")
    void emptyGlyphLeavesNoGap() throws IOException {
        String control = read(NCM.resolve(CONTROL));
        assertTrue(control.contains("gap = mark == 0.0 ? 0.0 : 6.0"),
                "空图标仍然算了 6 像素间隙：只写文字的那两格（酷狗身份、搜索模式）会整体偏 3 像素");
    }

    @Test
    @DisplayName("文字按墨迹盒居中，不是按推进宽度居中")
    void labelsCenterOnInkNotAdvance() throws IOException {
        String control = read(NCM.resolve(CONTROL));
        String layout = slice(control, "hit.setBeforeRenderCallback(");
        assertTrue(layout.contains("inkSpan(spec.font, texts[index])"),
                "文字位置又回到只读推进宽度：CJK 的字形在贴图里带左内白，推进居中会把整串字往右推");
        assertTrue(layout.contains("- inkLeft"),
                "pen 没有减掉墨迹左沿，等于没量墨迹");
        assertTrue(layout.contains("inkSpan(FontManager.fontello16, glyphs[index])"),
                "图标只按推进宽度参与分组居中：图标字体每个字形占 em 的比例差到 0.53~0.95");
        // 量不出来要能退回推进宽度，不能让文字凭空消失。
        String helper = slice(control, "private static double[] inkSpan(");
        assertTrue(helper.contains("return null"), "墨迹量不出来时没有退回推进宽度那条路");

        // 轨道宽度取两半里较宽的那个，否则另一串字会溢出自己那一半。
        String nav = read(NAVIGATE);
        assertTrue(nav.contains("Math.max(FontManager.pf10bold.getStringWidthD(\"歌曲\"),")
                        && nav.contains("FontManager.pf10bold.getStringWidthD(\"歌单\"))"),
                "搜索栏那格的轨道宽度只按一个词算：两个词谁宽谁溢出");
    }

    @Test
    @DisplayName("标签的字体每帧跟宿主重取，不许停在构造那一刻的默认档")
    void captionsFollowTheHostFontEveryFrame() throws IOException {
        String control = read(NCM.resolve(CONTROL));
        String caption = slice(control, "captions[index].setBeforeRenderCallback(");
        int set = caption.indexOf("setFont(spec.font)");
        int place = caption.indexOf("setPosition(slot[index][1]");
        assertTrue(set >= 0,
                "标签的字体只在构造时取一次：那一刻 spec 还是没喂过的默认值（pf12bold），"
                        + "于是排版按宿主的字号算、字却按默认档画，墨迹比留白宽 20%，全从右边溢出");
        assertTrue(set < place,
                "setFont 要排在定位之前：pen 与 inkTop 都是按标签当前那份字体算的");
        // 命中区那半本来就读的是活 spec，两处必须同源。
        assertTrue(slice(control, "hit.setBeforeRenderCallback(").contains("inkSpan(spec.font"),
                "居中算的是活 spec 的字体，标签却画另一份：这两处一旦分叉就永远对不齐");
    }

    private static String slice(final String source, final String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "找不到 " + signature);
        return source.substring(at, Math.min(source.length(), at + 1200));
    }

    private static int count(final String source, final String needle) {
        int n = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) n++;
        return n;
    }

    /**
     * 读源码并<b>剥掉注释</b>。
     *
     * <p>这些断言钉的是「不许出现某种写法」，而共享控件自己的 javadoc 里正好写着
     * {@code Interpolations.interpolate} 与 {@code setHidden} —— 那是解释为什么不用它们。
     * 不剥注释就会自己把自己钉红。</p>
     */
    private static String read(final Path path) throws IOException {
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\n]*", "");
    }
}
