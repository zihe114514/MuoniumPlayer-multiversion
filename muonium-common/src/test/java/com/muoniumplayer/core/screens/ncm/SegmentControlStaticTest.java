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
 * 分段选择器（搜索栏的「歌曲 / 歌单」、账号页的 QQ 链路与酷狗身份、私人 FM 的六种模式）的离线契约测试。
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
        assertTrue(control.contains("dur(legDuration())"),
                "滑块的时长没走倍率换算：legDuration() 算出来的毫秒必须再过 dur()，"
                        + "否则把「动画速度」拉满时只有这颗滑块还按自己的秒数走");
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

    @Test
    @DisplayName("段数由标签长度决定，两格那一档的时长一个字不改")
    void cellCountComesFromTheLabels() throws IOException {
        String control = read(NCM.resolve(CONTROL));
        assertTrue(control.contains("this.cells = Math.max(2, texts.length)"),
                "段数又写死成 2：私人 FM 的六种模式接不上这套，只能再抄一份控件");
        String cell = slice(control, "private double cellWidth()");
        assertTrue(cell.contains("/ cells"),
                "轨道宽度又按两半等分：三格以上滑块对不齐每一段的中心");
        String leg = slice(control, "private double legDuration()");
        assertTrue(leg.contains("spec.durationMs *"),
                "这一趟不再以宿主给的时长为基准：私人 FM 那一档 1150ms 会失去落点");
        assertTrue(leg.contains("Math.abs(legTo - legFrom) / Math.max(1, cells - 1)"),
                "这一趟的位移没有按段数归一：六格跳五格会和相邻一跳同样快，读不出「从哪来到哪去」");
        // 两格时距离恒为一格，系数落在 1.0 —— 搜索栏那一条 280ms 的观感不能因为推广而变。
        assertTrue(leg.contains("0.55 + 0.45 * Math.min(1.0, distance)"),
                "时长系数改了：请连带核对搜索栏那两格的观感，两格时系数必须正好是 1.0");

        String build = slice(control, "final IntSupplier ink = ()");
        assertTrue(build.contains("1.0 - Math.abs(slide - index)"),
                "文字亮度不再是「离滑块几格」：三格以上会出现隔着一段还跟着一起变白");
    }

    @Test
    @DisplayName("宿主整棵重排时滑块从记忆里接上，不许瞬移")
    void slideSurvivesTheHostRebuildingIt() throws IOException {
        String control = read(NCM.resolve(CONTROL));
        assertTrue(control.contains("public interface SlideMemory"),
                "没有跨重建交递滑块位置的口子：「数据一到就重排子组件」的宿主每次点击都会看到瞬移");
        String tick = slice(control, "if (legTo < 0)");
        assertTrue(tick.contains("slide = carried()"),
                "第一帧又直接钉在目标上：重排之后新实例没有起点，滑块就是瞬移");
        assertTrue(slice(control, "private double carried()").contains("Double.isNaN"),
                "记忆读不到值时（第一次打开）必须退回「钉住目标」，否则白演一趟从 0 滑过来");
    }

    @Test
    @DisplayName("每格可以不等宽；没有选中项时滑块淡出而不是滑回第一格")
    void cellsMayVaryAndSelectionMayBeNone() throws IOException {
        String control = read(NCM.resolve(CONTROL));
        assertTrue(control.contains("public IntToDoubleFunction cellWidth"),
                "每格一样宽的口径没了：场景标签按文字长短排，等分会把长标签挤出去");
        String box = slice(control, "private double[] boxAt(");
        assertTrue(box.contains("cellWidthAt(from) + (cellWidthAt(to) - cellWidthAt(from)) * f"),
                "滑块在两格之间只插值了左边缘：宽度不变，走一半会突然从窄格跳到宽格的尺寸");
        assertTrue(box.contains("Math.min(cells - 1, from + 1)"),
                "最后一格没有退路：插值会读到不存在的下一格");
        // 等分那一族代进去必须退化成原来那条算式，不然搜索栏与模式行的观感会被连带改掉。
        assertTrue(control.contains("if (spec.cellWidth == null) return spec.inset + cellWidth() * index"),
                "等分口径不再走 inset + cellWidth * index：两格那一档的摆位会跟着变");

        String tick = slice(control, "private void tick()");
        assertTrue(tick.contains("chosen < 0"),
                "宿主没法报「这一组没有选中项」：四组场景标签会各自点亮一枚没被选中的");
        assertTrue(tick.contains("spec.memory.remember(slide)"),
                "没有选中态时把滑块位置悄悄归零：换回这一组会从第一格再滑一趟");
        assertTrue(control.contains("public boolean positionsItself"),
                "轨道不能关掉自报位置：放进 ScrollPanel 时它会把滚动偏移写回零");
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
