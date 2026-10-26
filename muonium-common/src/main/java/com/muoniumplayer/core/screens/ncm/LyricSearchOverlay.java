package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.LyricSearchHit;
import com.muoniumplayer.core.ncm.music.LyricSearchService;
import com.muoniumplayer.core.ncm.music.LyricSearchSource;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.FoliaLucideIcons;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedImageWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaGfx;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * 歌词搜索弹窗：给正在放的这首歌手动挑一份歌词。
 *
 * <p>版式逐条照参考实现的搜索匹配弹窗（1 单位 = 1 CSS 像素），右下角
 * 那一块预览照参考实现的歌词预览面板。四张标签是网易云音乐 / AMLLDB / QQ 音乐 / 酷狗音乐，
 * 左栏选候选、右栏预览，底部「取消 / 保存」；保存写进 {@code LyricManualMemory} 之后由加载链的
 * 第 0 级精确重取。打开即自动搜一次并选中第一条，这一点也和 folia 一致。</p>
 *
 * <h3>组件树什么时候重建</h3>
 * <p>只有<b>行数量</b>变了才重建（开始搜索、搜索回来、换标签页 —— 那三种情况会在「旋转占位块」
 * 「空态」「若干张卡片」之间切换）。选中哪一条、预览到哪一句、按钮是「保存」还是「匹配中」全都
 * 由渲染时求值的供应器现取，所以点一张卡片不会让输入框掉焦点。</p>
 *
 * <h3>为什么不复用 {@link NcmSheet#sheet}</h3>
 * <p>那一套的圆角、内边距与页眉是为「一栏卡片列表」的浮层定的；这一页是左右两栏 + 独立页脚的
 * 对话框，照 folia 的尺寸走才谈得上还原。这里复用的是同一套组件基类、同一套混色与墨迹居中的
 * 规矩，只有弹窗外壳自己搭。</p>
 */
public final class LyricSearchOverlay extends NcmSheet {

    // ──────────────────────────── folia 的档位 ────────────────────────────

    /**
     * 四档文字各自的行盒（垂直居时要套进去的那一条带）：标题 {@code pf20bold}、正文
     * {@code pf18} / {@code pf18bold}、次级 {@code pf16}、徽标 {@code pf12bold}。
     *
     * <p><b>这里没有「字号」常量，也不该有。</b>渲染器名字里那个数不是它占的高度：
     * {@code createText(18)} 走的是 {@code sizePx = 18 * 0.5}（旧的「2 倍栅格化 + 0.5 倍绘制」早就没了，
     * 那个 0.5 却还留在构造函数里），所以 pf18 真正落屏只有 9 格。居中要减的是
     * {@link CFontRenderer#getFontHeight()}，见 {@link #textY(CFontRenderer, double, double)}。</p>
     *
     * <p>字号本身仍是这一页唯一偏离 folia 原值之处（正文从 {@code text-sm} 抬到 18 那一档），
     * 抬档的理由是量纲：这一页一格只是「弹窗单位」，播放器正文在布局轴上。按实测折算，这一页正文
     * 约 7.3 个布局单位，播放器 {@code pf14} 是 6.75 —— 同级。字抬上来腾不出地方，就把装饰性的
     * 高度压下去：页眉页脚的 {@code py-5} 收成 12、栏内边距与 {@code gap-5} 收成 16/14、空态那一格
     * 160 收成 120、右栏封面 128 收成 96。里外一算，候选列表在默认窗口下能看见约三张卡。</p>
     */
    private static final double LH_TITLE = 24.0, LH_BODY = 22.0, LH_SUB = 20.0, LH_BADGE = 18.0;

    /** {@code max-w-5xl} / {@code max-h-[85vh]} / 遮罩的 {@code p-4}。 */
    private static final double DIALOG_MAX_W = 1024.0;
    private static final double DIALOG_MAX_RATIO = .85;
    private static final double BACKDROP_PAD = 16.0;
    /** {@code rounded-3xl / 2xl / xl / md / rounded}。 */
    private static final double R_DIALOG = 24.0, R_PLATE = 16.0, R_CARD = 16.0, R_BUTTON = 12.0,
            R_COVER = 12.0, R_PREVIEW = 12.0, R_BADGE = 6.0, R_MINI = 4.0;
    /** 页眉/页脚的横向与纵向内边距：folia 的 {@code px-6 py-5} 在这里收成 16/12。 */
    private static final double PAD_X = 16.0, PY_FOOTER = 12.0;
    /** 头部：{@code py} + 标题行盒 + {@code mt-0.5} + 副标题行盒 + {@code py} + {@code border-b}。 */
    private static final double HEAD_GAP = 2.0;
    private static final double HEADER_H = PY_FOOTER + LH_TITLE + HEAD_GAP + LH_BODY + PY_FOOTER + 1;
    /** 页脚：{@code border-t} + {@code py} + 按钮 + {@code py}。 */
    private static final double BUTTON_H = 30.0;
    private static final double FOOTER_H = 1 + PY_FOOTER + BUTTON_H + PY_FOOTER;
    /** 左栏：{@code p-6 gap-5} 收成一档，标签行仍是「文字 + {@code pb} + 下划线 + {@code pb} + 分隔线」。 */
    private static final double COL_PAD = 16.0, COL_GAP = 14.0;
    private static final double TAB_TEXT_H = LH_BODY, TAB_PB = 5.0, TAB_BORDER = 2.0;
    private static final double TAB_ROW_H = TAB_TEXT_H + TAB_PB + TAB_BORDER + TAB_PB + 1.0;
    private static final double TAB_GAP = 16.0, TAB_PAD_X = 4.0;
    /** 搜索行：22 的行盒上下各 9，圆角 16，右侧 {@code gap-3} 接搜索键。 */
    private static final double SEARCH_H = 40.0, SEARCH_GAP = 12.0, SEARCH_PAD_X = 16.0;
    /** 候选卡：{@code space-y-3} 一排，滚动容器自身 {@code pr-1}。 */
    private static final double CARD_GAP = 10.0, LIST_PR = 4.0;
    private static final double CARD_PAD = 14.0, CARD_COVER = 40.0, CARD_INNER_GAP = 16.0;
    private static final double CARD_LINE1 = LH_BODY, CARD_SUB = LH_SUB, CARD_SUB_MT = 3.0;
    private static final double CARD_TEXT_H = CARD_LINE1 + CARD_SUB_MT + CARD_SUB + CARD_SUB_MT + CARD_SUB;
    private static final double CARD_H = CARD_PAD * 2 + CARD_TEXT_H;
    /** 加载与空态那一格：folia 是 {@code h-40}，这里让出 40 给候选卡。 */
    private static final double PLACEHOLDER_H = 120.0;
    /** 右栏：封面 + {@code mt} + 居中的信息块（{@code space-y-1.5}）。 */
    private static final double RIGHT_COVER = 96.0, RIGHT_INFO_MT = 12.0, RIGHT_BLOCK_GAP = 6.0;
    private static final double INFO_LINE1 = 26.0, INFO_LINE2 = LH_BODY, INFO_LINE3 = LH_SUB;
    private static final double INFO_PAD_X = 8.0;
    /** 预览块：钉在右栏底部；里面是 {@code p-3}(12) 再 {@code px-2}(8)。 */
    private static final double PREVIEW_H = 104.0, PREVIEW_MT = 12.0;
    private static final double PREVIEW_PAD_X = 12.0, PREVIEW_PAD_Y = 10.0, PREVIEW_TEXT_INSET = 8.0;
    /** 预览的两行文字框，中间隔 {@code mt-1.5}。 */
    private static final double PREVIEW_L1_H = 30.0, PREVIEW_L2_H = 22.0, PREVIEW_L2_MT = 6.0;

    // ──────────────────────────── folia 写死的那套色 ────────────────────────────

    private static final int ZINC_900 = 0x18181B, ZINC_800 = 0x27272A, ZINC_500 = 0x71717A,
            ZINC_400 = 0xA1A1AA, ZINC_200 = 0xE4E4E7, ZINC_100 = 0xF4F4F5;
    private static final int WHITE = 0xFFFFFF, BLACK = 0x000000;
    private static final int BLUE_300 = 0x93C5FD, BLUE_400 = 0x60A5FA, BLUE_500 = 0x3B82F6,
            BLUE_600 = 0x2563EB;
    private static final int INDIGO_400 = 0x818CF8, INDIGO_500 = 0x6366F1;
    private static final int EMERALD_400 = 0x34D399, EMERALD_500 = 0x10B981, EMERALD_600 = 0x059669;
    private static final int VIOLET_400 = 0xA78BFA, VIOLET_500 = 0x8B5CF6;

    // ──────────────────────────── 运行状态 ────────────────────────────

    /** 打开时那一首歌。整页都是为它服务的，中途切歌不会把这页改成另一首的搜索。 */
    private final Music track;
    private LyricSearchSource source = LyricSearchSource.NETEASE;
    /** 候选列表与两个进行中标志由后台线程写、渲染线程读。 */
    private volatile List<LyricSearchHit> results = Collections.emptyList();
    private volatile boolean searching;
    private volatile boolean matching;
    /** 本轮请求的序号：晚到的旧响应靠它作废，换标签页时上一源的结果不会盖下来。 */
    private volatile int searchSeq;
    private volatile int previewSeq;
    private LyricSearchHit selected;
    /** 预览歌词：{@code previewError} 非空表示这一档取失败了，界面上给「重新加载」。 */
    private volatile List<LyricLine> previewLines;
    private volatile String previewError;
    private volatile boolean previewLoading;
    private String queryDraft = "";
    /** 重建时保住输入框的焦点与内容，否则每次搜索回来光标就没了。 */
    private boolean inputHadFocus = true;
    private TextFieldWidget input;
    /** 弹窗高度单独补间，换标签页时高度会变（AMLL 那一档少一行）。遮罩与弹窗的入场进度用基类那一条时钟。 */
    private double shownHeight;
    /** 结构版本号：只有它变了才重搭组件树。 */
    private int revision = 1;

    // ──────────────────────────── 性能取样 ────────────────────────────
    //
    // 「打开这一页会冻一下」这件事，光读代码定不下位：控件树重建、结果落地那一帧的整层绘制、
    // 封面贴图在主线程上传，三者都能在日志里长成同一次掉帧。所以这里把打开一次分成四段各量一遍，
    // 攒成一行报出去 —— 哪一段是元凶，看那一行就知道，不用再猜第二轮。
    //
    // 每个实例只报一次（每次打开都是新实例，所以每次打开一行），量的是本实例活着的那几十帧。

    /** 构造开始的那一刻，用来把「构造 + 首次建树」算成一段。 */
    private final long perfOpenedAt = System.nanoTime();
    private double perfInitMs;
    private double perfSearchMs;
    private int perfResultCount;
    private int perfRebuilds;
    private double perfRebuildMaxMs;
    private int perfNodes;
    private int perfFrames;
    private double perfLayerMaxMs;
    private double perfIntervalMaxMs;
    private int perfSlowFrames;
    private double perfUploadMs;
    private int perfUploads;
    private int perfUploadsMaxPerFrame;
    private long perfFrameEnter;
    private long perfLastFrameEnter;
    private long perfUploadBase;
    private long perfUploadNanosBase;
    private long perfUploadsAtLastFrame;
    /** 检索发起的那一刻（工作线程之前），结果落地时用它算往返耗时。 */
    private long perfSearchStartedAt;
    /** 结果落地后再量这么多帧就收口报一行；一直没结果的话按打开后这么多帧收口。 */
    private static final int PERF_WINDOW_FRAMES = 60;
    private int perfWindowLeft = -1;
    private boolean perfReported;

    public LyricSearchOverlay() {
        this(LyricSearchService.currentTrack());
    }

    /**
     * 给<b>指定的一首歌</b>挑歌词。
     *
     * <p>整页都围着 {@code target} 转：打分拿它比、保存写它的 stableKey。中途切歌不会把这页改成
     * 另一首的搜索 —— 那是新的一页的事。</p>
     *
     * <p>预览那一块仍按播放时钟走逐字点亮。目标不是正在放的歌时没有可信的时钟，所以那种情况下
     * 停在 0，看到的是这句的开头而不是乱跳的高亮。</p>
     */
    public LyricSearchOverlay(Music target) {
        this.track = target;
        this.queryDraft = LyricSearchService.defaultQuery(target);
    }

    @Override
    public void onInit() {
        rebuild();
        runSearch(queryDraft);
        // 「打开就冻一下」的第一段：构造 + 首次建树 + 发起检索，全在主线程这一帧里。
        perfInitMs = (System.nanoTime() - perfOpenedAt) / 1e6;
        perfUploadBase = Textures.perfUploads;
        perfUploadNanosBase = Textures.perfUploadNanos;
        perfUploadsAtLastFrame = perfUploadBase;
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        perfFrameEnter = System.nanoTime();
        if (perfLastFrameEnter > 0) {
            // 整帧间隔是从上一帧进这一层，到本帧又进这一层。游戏整体卡不卡看它，
            // 本层自己吃了多少看 perfLayerMaxMs —— 两个数一比就知道那一顿是不是这一页造成的。
            double intervalMs = (perfFrameEnter - perfLastFrameEnter) / 1e6;
            if (intervalMs > perfIntervalMaxMs) perfIntervalMaxMs = intervalMs;
        }
        perfLastFrameEnter = perfFrameEnter;

        if (revision != builtRevision) {
            rebuild();
        }
        super.onRender(mouseX, mouseY);
    }

    @Override
    protected void afterRenderChildren(double mouseX, double mouseY) {
        super.afterRenderChildren(mouseX, mouseY);
        samplePerfFrame();
    }

    /** 收口前逐帧记三类数：本层绘制耗时、整帧间隔、以及这一帧里主线程传了几张贴图。 */
    private void samplePerfFrame() {
        if (perfReported) return;

        double layerMs = (System.nanoTime() - perfFrameEnter) / 1e6;
        perfFrames++;
        if (layerMs > perfLayerMaxMs) perfLayerMaxMs = layerMs;
        if (layerMs > 16.7) perfSlowFrames++;

        long uploads = Textures.perfUploads;
        int thisFrame = (int) (uploads - perfUploadsAtLastFrame);
        perfUploadsAtLastFrame = uploads;
        if (thisFrame > perfUploadsMaxPerFrame) perfUploadsMaxPerFrame = thisFrame;

        if (perfWindowLeft < 0) {
            // 结果一直没回来（检索失败、源卡住）也要有个交代，别为了等这一行把日志吊着。
            if (perfFrames >= PERF_WINDOW_FRAMES * 4) reportPerf(uploads);
            return;
        }
        if (--perfWindowLeft <= 0) reportPerf(uploads);
    }

    /** 一行报完，之后不再量。真机报告看的就是这一行：哪一段顶起来的，元凶在那一段里。 */
    private void reportPerf(long uploads) {
        if (perfReported) return;
        perfReported = true;
        perfUploads = (int) (uploads - perfUploadBase);
        perfUploadMs = (Textures.perfUploadNanos
                - perfUploadNanosBase) / 1e6;
        System.err.println("[Music/Perf] 歌词搜索页 打开一次: 打开首帧=" + ms(perfInitMs)
                + "ms 检索=" + ms(perfSearchMs) + "ms(" + perfResultCount + "条)"
                + " 建树=" + perfRebuilds + "次/最慢" + ms(perfRebuildMaxMs) + "ms/" + perfNodes + "节点"
                + " || 之后" + perfFrames + "帧: 本层最宽=" + ms(perfLayerMaxMs)
                + "ms 整帧间隔最宽=" + ms(perfIntervalMaxMs) + "ms 本层慢帧=" + perfSlowFrames
                + " || 主线程贴图=" + perfUploads + "张/" + ms(perfUploadMs)
                + "ms 单帧最多" + perfUploadsMaxPerFrame + "张");
    }

    private static String ms(double value) {
        return String.format("%.1f", value);
    }

    public void handleEscape() {
        dispose();
    }

    private int builtRevision = 0;

    private void touch() {
        revision++;
    }

    // ════════════════════════════ 组件树 ════════════════════════════

    private void rebuild() {
        long startedAt = System.nanoTime();
        int at = revision;
        String draft = input == null ? queryDraft : input.getText();
        inputHadFocus = input == null || input.isFocused();
        queryDraft = draft == null ? "" : draft;

        beginPage();
        builtRevision = at;

        final RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setClickable(false);
        mask.setColor(BLACK);
        mask.setBeforeRenderCallback(() -> {
            mask.setMargin(0);
            mask.setAlpha((float) (presentation * .60));
        });
        // folia 是「在遮罩上按下、并在遮罩上抬起」才关；这里等价成点遮罩关。
        setOnClickCallback((x, y, button) -> {
            if (button == 0) dispose();
            return button == 0;
        });

        final Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            // presentation 由 NcmSheet#onRender 从那条进出场时钟写，这里只读数。
            // 原来这一格自己 interpolate 一遍，于是重建组件树（每收到一次搜索结果都要重建）
            // 会把入场进度按住在 .2 那一档，退场更是没有任何东西在推进度。
            double width = dialogWidth();
            double target = dialogHeight();
            shownHeight = shownHeight <= 0.0 ? target : Interpolations.interpolate(shownHeight, target, .22f);
            dialog.setBounds(Math.max(1, width), Math.max(1, shownHeight));
            dialog.setAlpha((float) presentation);
            dialog.setPosition(getWidth() * .5 - width * .5,
                    getHeight() * .5 - shownHeight * .5 + (1.0 - presentation) * 14.0);
            logAxisOnce(width, shownHeight);
        });

        final RoundedRectWidget surface = new RoundedRectWidget();
        dialog.addChild(surface);
        surface.setClickable(false).setRadius(R_DIALOG);
        surface.setBeforeRenderCallback(() -> {
            surface.setMargin(0);
            surface.setColor(surfaceColor());
        });
        stroke(surface, R_DIALOG, LyricSearchOverlay::borderStrong, () -> 1.0);

        this.dialog = dialog;
        header(dialog);
        leftColumn();
        rightColumn();
        footer();

        if (!perfReported) {
            perfRebuilds++;
            double took = (System.nanoTime() - startedAt) / 1e6;
            if (took > perfRebuildMaxMs) perfRebuildMaxMs = took;
            perfNodes = countNodes(this);
        }
    }

    /** 控件树的节点数：建树耗时高不高，先看这一页到底搭了多少个东西。 */
    private static int countNodes(com.muoniumplayer.core.rendering.ui.AbstractWidget<?> widget) {
        int total = 1;
        for (com.muoniumplayer.core.rendering.ui.AbstractWidget<?> child : widget.getChildren()) {
            total += countNodes(child);
        }
        return total;
    }

    /** 只在第一次定型时打一行量纲日志；每次打开都是新实例，所以每次打开都会有一行。 */
    private boolean axisLogged;

    /**
     * 把这一页<b>真正落到屏幕上的量纲</b>打一行出来。
     *
     * <p>「字太小」「缩在角落里」这类反馈在这页上有两种完全不同的成因：倍率那一档没生效（单位/布局
     * 算错，或矩阵那条路根本没走到），和生效了但排版数值本身偏小。两者的日志一眼能分开 —— 前者
     * {@code px/单位} 与设置值对不上，后者对得上、只是数字小。没有这一行，只能靠猜。</p>
     */
    private void logAxisOnce(double width, double height) {
        if (axisLogged || width <= 0.0 || height <= 0.0) return;
        axisLogged = true;
        double unit = WindowAdaptiveScale.lyricSearchUnitsPerLayout();
        // 1 单位 = 1/unit 个布局单位，而 1 个布局单位 = 2 物理像素，于是 1 单位 = 2/unit 像素。
        double pxPerUnit = WindowAdaptiveScale.DESIGN_PER_LAYOUT / unit;
        System.err.println("[Music/LyricSearch] 量纲: 设置倍率=" + String.format("%.2f", pxPerUnit)
                + " 单位/布局=" + String.format("%.3f", unit)
                + " 宿主=" + (int) getWidth() + "x" + (int) getHeight()
                + " 弹窗=" + (int) width + "x" + (int) height
                + " 正文=pf18行盒" + String.format("%.1f", FontManager.pf18.getFontHeight())
                + "单位≈" + String.format("%.1f", FontManager.pf18.getFontHeight() / unit) + "布局单位"
                + "(播放器正文pf14=6.75布局单位)"
                + " 可见候选=" + String.format("%.1f", (listH() + CARD_GAP) / (CARD_H + CARD_GAP)) + "张");
    }


    /**
     * 让一行字在 {@code [rowTop, rowTop + rowHeight]} 这条行盒里垂直居中，返回该传给
     * {@code drawString} / {@code setPosition} 的 y。
     *
     * <h3>为什么减数不能是「字号」</h3>
     * <p>这一页原来写的是 {@code (行盒 - 18) * .5}，那个 18 是<b>渲染器名字里的数字</b>。
     * {@code FontManager.createText(18, "pf_middleblack")} 交给 Skia 的字号是 {@code 18 * 0.5 = 9}，
     * 中文墨迹离屏量出来占 {@code y+0.63 ~ y+8.75}。于是 30 高的页脚按钮里字被摆到 y=6，
     * 墨迹中心落在 10.7，而键的中心是 15 —— 看着就是贴着上沿。</p>
     *
     * <p>换成 {@link CFontRenderer#getFontHeight()}（= 行盒 - 4.25）当减数：12 / 16 / 18 / 20 四档
     * 与各自实测墨迹中心的差在 0.2 格以内，六分之一像素。输入框自己
     * （{@code TextField#getRenderOffsetY}）用的也是这一个量。</p>
     *
     * <p>没有按「这一串字的墨迹」逐串居中（{@code NcmSheet#inkTop} 那套），是因为这里一屏排三十多张
     * 候选卡：按内容居中会让「稻香」和「Starfall (Live)」各自找中心，同一列的基线跟着内容抖。
     * 行盒与内容无关。字体还没解析出来时 {@code getFontHeight()} 是负数，那时退回带的上沿，不抛。</p>
     */
    private static double textY(CFontRenderer font, double rowTop, double rowHeight) {
        double box = font == null ? 0.0 : font.getFontHeight();
        return rowTop + (rowHeight - (box > 0.0 ? box : rowHeight)) * .5;
    }

    /** 同一件事，字体从标签自己身上取。 */
    private static double textY(LabelWidget label, double rowTop, double rowHeight) {
        return textY(label.getFont(), rowTop, rowHeight);
    }

    /** {@code w-full max-w-5xl} 再被遮罩的 {@code p-4} 夹一道。 */
    private double dialogWidth() {
        // 地板 240 而不是 360：手机比例视口下宿主可能只有 300 多宽，硬地板 360 会让弹窗
        // 比宿主还宽、关闭键点不到。两栏布局在 240 宽下仍成立（右栏信息块最窄 ~60px，
        // 靠截断兜底），宁窄勿出界。
        return Math.min(DIALOG_MAX_W, Math.max(240.0, getWidth() - BACKDROP_PAD * 2));
    }

    /**
     * {@code max-h-[85vh]}：内容自然高度与视口之间取小。
     *
     * <p>自然高度是两栏各自想要的高度的较大者，加页眉页脚。左栏取决于候选条数（folia 里
     * {@code flex-1} 的那一块在没有滚动上限前就是内容高度），右栏取决于歌名被折成几行 —— 两处
     * 都不是定值，所以每帧现算。</p>
     */
    private double dialogHeight() {
        double left = HEADER_H + FOOTER_H + COL_PAD * 2 + TAB_ROW_H + COL_GAP
                + (source.supportsManualSearch() ? SEARCH_H + COL_GAP : 0.0)
                + resultsContentHeight();
        double right = HEADER_H + FOOTER_H + COL_PAD * 2 + RIGHT_COVER + RIGHT_INFO_MT
                + infoHeight() + PREVIEW_MT + PREVIEW_H;
        double natural = Math.max(left, right);
        double viewport = getHeight() - BACKDROP_PAD * 2;
        return Math.max(240.0, Math.min(Math.min(natural, getHeight() * DIALOG_MAX_RATIO), viewport));
    }

    private double resultsContentHeight() {
        if (searching) return PLACEHOLDER_H;
        List<LyricSearchHit> snapshot = results;
        if (snapshot.isEmpty()) return PLACEHOLDER_H;
        return snapshot.size() * CARD_H + (snapshot.size() - 1) * CARD_GAP;
    }

    /** 右栏信息块：歌名（可能两行）+ {@code space-y-1.5} + 歌手 + 专辑。 */
    private double infoHeight() {
        return titleLines() * INFO_LINE1 + RIGHT_BLOCK_GAP + INFO_LINE2 + RIGHT_BLOCK_GAP + INFO_LINE3;
    }

    private double bodyTop() {
        return HEADER_H;
    }

    private double bodyBottom() {
        return Math.max(1, shownHeight - FOOTER_H);
    }

    // ═════════════════════════ 逐帧现取的几何 ═════════════════════════

    /**
     * 弹窗本体，每帧由它自己的回调定尺寸。
     *
     * <p>两栏、候选区、页脚全部从它现取宽高，<b>不在搭组件树的时候抄一份</b>。抄一份会错两次：
     * 一次是弹窗高度在补间（{@link #shownHeight}），抄下来就停在打开那一帧；一次是新容器此刻还没被
     * 自己的回调定过尺寸，量回来是 0 —— 被 {@code Math.max(1, …)} 夹成 1 之后，整块列表就只剩
     * 一条缝。{@link NcmSheet} 那套浮层从头到尾都是「在回调里读宿主」，道理是一样的。</p>
     */
    private Panel dialog;

    /** {@link #clip} 的 x 要供应器；左栏就从弹窗的 0 开始，给这个常量起个名字免得每处写 lambda。 */
    private static final DoubleSupplier ZERO = () -> 0.0;

    /** 弹窗这一帧的宽。 */
    private double dialogW() {
        return Math.max(1, dialogWidth());
    }

    /** {@code w-[62%]}：左栏（标签 + 搜索 + 候选列表）。 */
    private double leftW() {
        return dialogW() * .62;
    }

    /** {@code flex-1}：右栏（封面 + 信息 + 预览）。 */
    private double rightW() {
        return Math.max(1, dialogW() - leftW());
    }

    /** 两栏共用的内容高度：页眉下沿到页脚上沿。 */
    private double bodyH() {
        return Math.max(1, bodyBottom() - bodyTop());
    }

    /** {@code p-6} 扣掉之后的左栏内容宽。 */
    private double leftContentW() {
        return Math.max(1, leftW() - COL_PAD * 2);
    }

    /** 候选列表的宽：内容宽再让出 {@code pr-1} 那条滚动缝。 */
    private double listW() {
        return Math.max(1, leftContentW() - LIST_PR);
    }

    /** 右栏内容宽：{@code px-6} 加 {@code pr-1}。 */
    private double rightContentW() {
        return Math.max(1, rightW() - COL_PAD * 2 - LIST_PR);
    }

    /** 候选区顶部：标签行下面，有搜索框的那一档再多让一行。 */
    private double listTop() {
        return COL_PAD + TAB_ROW_H + COL_GAP
                + (source.supportsManualSearch() ? SEARCH_H + COL_GAP : 0.0);
    }

    /** 候选区可用高：内容高减去顶部那一叠，底部留 {@code p-6}。 */
    private double listH() {
        return Math.max(1, bodyH() - listTop() - COL_PAD);
    }

    private static int surfaceColor() {
        return isLight() ? WHITE : ZINC_900;
    }

    private static boolean isLight() {
        return NCMTheme.isLightPreset();
    }

    /** Tailwind 的 {@code bg-white/5} 这类写法：半透明叠在实心面板上等价于按 alpha 混色。 */
    private static int overlay(int tint, double alpha) {
        return mix(surfaceColor(), tint, alpha);
    }

    private static int borderStrong() {
        return isLight() ? overlay(BLACK, .05) : overlay(WHITE, .10);
    }

    private static int borderFaint() {
        return isLight() ? overlay(BLACK, .05) : overlay(WHITE, .05);
    }

    private static int textPrimary() {
        return isLight() ? ZINC_900 : WHITE;
    }

    private static int textSecondary() {
        return isLight() ? ZINC_500 : ZINC_400;
    }

    /** {@code text-blue-600} / {@code text-blue-300}：浅色主题那一份。 */
    private static int accentText() {
        return isLight() ? BLUE_600 : BLUE_300;
    }

    /** 标签页下划线：{@code border-blue-500} / {@code border-blue-400}。 */
    private static int accentLine() {
        return isLight() ? BLUE_500 : BLUE_400;
    }

    /** {@code bg-blue-500/10 hover:bg-blue-500/20}（浅色）与 {@code /20 → /30}（深色）。 */
    private static int blueButton(boolean hovering) {
        return overlay(BLUE_500, isLight() ? (hovering ? .20 : .10) : (hovering ? .30 : .20));
    }

    /** {@code bg-blue-500/10}：卡片上那颗匹配分的底。 */
    private static int blueTint() {
        return overlay(BLUE_500, .10);
    }

    // ──────────────────────────── 页眉 ────────────────────────────

    /**
     * {@code flex items-center justify-between px-6 py-5 border-b}。
     *
     * <p>关闭键是这一行的第二个 flex 子项，被 {@code items-center} 居中到那两行字的整块中间，
     * 距右边缘 {@code px-6}（这里是 16）—— 它不是钉在右上角的。</p>
     */
    private void header(final Panel dialog) {
        final LabelWidget title = label(dialog, "匹配歌词", FontManager.pf20bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(textPrimary());
            title.setPosition(PAD_X, textY(title, PY_FOOTER, LH_TITLE));
        });

        final LabelWidget subtitle = label(dialog, () -> track == null ? "" : track.getName(),
                FontManager.pf18);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(textSecondary());
            subtitle.setMaxWidth(Math.max(1, dialogWidth() - PAD_X * 2 - 34 - 16));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(PAD_X, textY(subtitle, PY_FOOTER + LH_TITLE + HEAD_GAP, LH_BODY));
        });

        final RoundedRectWidget close = new RoundedRectWidget();
        dialog.addChild(close);
        close.setRadius(17).setShouldOverrideMouseCursor(true);
        close.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            dispose();
            return true;
        });
        // {@code p-2} 包 18 的图标 = 34 见方；静止时<b>没有</b>底色，只有悬停那一层。
        close.setBeforeRenderCallback(() -> {
            close.setBounds(34, 34);
            close.setPosition(dialogWidth() - PAD_X - 34,
                    PY_FOOTER + (LH_TITLE + HEAD_GAP + LH_BODY - 34) * .5);
            close.setColor(close.isHovering() ? closeHoverPlate() : surfaceColor());
        });
        lucide(close, FoliaLucideIcons.CLOSE, PartyIcons.CLOSE, 18, () -> 17.0, () -> 17.0,
                LyricSearchOverlay::textPrimary);

        final RectWidget edge = new RectWidget();
        dialog.addChild(edge);
        edge.setClickable(false);
        edge.setBeforeRenderCallback(() -> {
            edge.setBounds(Math.max(1, dialogWidth()), 1);
            edge.setPosition(0, HEADER_H - 1);
            edge.setColor(borderStrong());
        });
    }

    /** 深色 {@code hover:bg-white/10}；浅色 {@code hover:bg-zinc-200/50} —— 后者是实心近似。 */
    private static int closeHoverPlate() {
        return isLight() ? mix(ZINC_900, ZINC_200, .5) : overlay(WHITE, .10);
    }

    // ──────────────────────────── 左栏 ────────────────────────────

    private void leftColumn() {
        final Panel column = clip(dialog, ZERO, bodyTop(), this::leftW, this::bodyH);

        final RectWidget edge = new RectWidget();
        column.addChild(edge);
        edge.setClickable(false);
        edge.setBeforeRenderCallback(() -> {
            edge.setBounds(1, Math.max(1, column.getHeight()));
            edge.setPosition(Math.max(1, column.getWidth()) - 1, 0);
            edge.setColor(borderStrong());
        });

        tabRow(column, COL_PAD);
        if (source.supportsManualSearch()) {
            searchRow(column, COL_PAD + TAB_ROW_H + COL_GAP);
        }
        listArea(column);
    }

    /** {@code flex border-b pb-2 gap-4}：四个标签 + 一条压在 pb-2 下面的分隔线。 */
    private void tabRow(final Panel host, final double top) {
        double x = COL_PAD;
        for (LyricSearchSource value : LyricSearchSource.values()) {
            x += tab(host, top, x, value) + TAB_GAP;
        }
        final RectWidget rule = new RectWidget();
        host.addChild(rule);
        rule.setClickable(false);
        rule.setBeforeRenderCallback(() -> {
            rule.setBounds(Math.max(1, leftContentW()), 1);
            rule.setPosition(COL_PAD, top + TAB_ROW_H - 1);
            rule.setColor(borderStrong());
        });
    }

    /** 一枚标签；返回它自己的宽度（{@code px-1} 让下一个标签接着排）。 */
    private double tab(final Panel host, final double top, final double x, final LyricSearchSource value) {
        final boolean activeNow = source == value;
        final CFontRenderer font = activeNow ? FontManager.pf18bold : FontManager.pf18;
        final double textW = Math.max(1, font.getStringWidthD(value.label));
        final double buttonW = textW + TAB_PAD_X * 2;

        final RoundedRectWidget hit = new RoundedRectWidget();
        host.addChild(hit);
        hit.setRadius(4).setShouldOverrideMouseCursor(true);
        hit.setOnClickCallback((mx, my, button) -> {
            if (button != 0 || source == value) return false;
            switchSource(value);
            return true;
        });
        hit.setBeforeRenderCallback(() -> {
            hit.setBounds(buttonW, TAB_TEXT_H + TAB_PB + TAB_BORDER);
            hit.setPosition(x, top);
            // 静止与悬停都没有底色；用面板色画一块看不见的挡板，图标才不会被 setAlpha(0) 一起抹掉。
            hit.setColor(surfaceColor());
        });
        // 非活跃标签的悬停色：深色 {@code hover:text-zinc-200}，浅色改按 zinc-500 收 ——
        // folia 那一份浅色写的也是 zinc-200，落在白底上等于扫过去字直接消失。
        final LabelWidget caption = label(host, value.label, font);
        caption.setBeforeRenderCallback(() -> {
            int ink;
            if (source == value) ink = accentText();
            else ink = hit.isHovering() ? (isLight() ? ZINC_500 : ZINC_200) : ZINC_400;
            caption.setColor(ink);
            caption.setPosition(x + TAB_PAD_X, textY(caption, top, TAB_TEXT_H));
        });
        if (activeNow) {
            final RectWidget underline = new RectWidget();
            host.addChild(underline);
            underline.setClickable(false);
            underline.setBeforeRenderCallback(() -> {
                underline.setBounds(Math.max(1, buttonW), TAB_BORDER);
                underline.setPosition(x, top + TAB_TEXT_H + TAB_PB);
                underline.setColor(accentLine());
            });
        }
        return buttonW;
    }

    /** {@code flex gap-3}：一只输入盘 + 一颗搜索键，两样都是 {@link #SEARCH_H} 高。 */
    private void searchRow(final Panel host, final double top) {
        final CFontRenderer bodyBold = FontManager.pf18bold;

        final RoundedRectWidget plate = new RoundedRectWidget();
        host.addChild(plate);
        plate.setClickable(false).setRadius(R_PLATE);
        plate.setBeforeRenderCallback(() -> {
            plate.setBounds(searchPlateW(), SEARCH_H);
            plate.setPosition(COL_PAD, top);
            plate.setColor(inputPlate());
        });
        stroke(plate, R_PLATE, this::inputBorder, () -> 1.0);
        lucide(plate, FoliaLucideIcons.SEARCH, PartyIcons.SEARCH, 18, () -> 16.0,
                () -> SEARCH_H * .5, LyricSearchOverlay::textSecondary);

        // 回车即搜：TextFieldWidget 聚焦后吞掉所有按键，默认没人处理回车，所以子类化补上。
        input = new TextFieldWidget(FontManager.pf18) {
            @Override
            public boolean onKeyTyped(char character, int keyCode) {
                if (isFocused() && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
                    runSearch(getText());
                    return true;
                }
                return super.onKeyTyped(character, keyCode);
            }
        };
        host.addChild(input);
        input.getTextField().setMaxStringLength(4096);
        input.setEnabled(true).setFocused(inputHadFocus);
        input.setText(queryDraft);
        // folia 的输入框没有 placeholder：里面永远已经填好了「标题 - 歌手 - 专辑」。
        input.drawUnderline(false);
        input.setBeforeRenderCallback(() -> {
            input.setBounds(Math.max(10, searchPlateW() - 16 - 18 - 12), LH_BODY);
            input.setPosition(COL_PAD + 16 + 18 + 12, top + (SEARCH_H - LH_BODY) * .5);
            input.setColor(textPrimary());
            input.setDisabledTextColor(textSecondary());
        });

        final RoundedRectWidget button = new RoundedRectWidget();
        button.setRadius(R_PLATE).setShouldOverrideMouseCursor(true);
        button.setOnClickCallback((mx, my, kb) -> {
            if (kb != 0 || searching) return false;
            runSearch(input.getText());
            return true;
        });
        host.addChild(button);
        button.setBeforeRenderCallback(() -> {
            button.setBounds(searchButtonW(), SEARCH_H);
            button.setPosition(COL_PAD + searchPlateW() + SEARCH_GAP, top);
            // 深色 {@code bg-blue-500/20 hover:/30}，浅色 {@code /10 → /20}；<b>没有</b>禁用态。
            button.setColor(blueButton(button.isHovering()));
        });
        final LabelWidget caption = label(button, "搜索", bodyBold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(accentText());
            // 这颗 caption 挂在按钮底下，按钮的相对 y 就是 0。
            caption.setPosition((searchButtonW() - caption.getWidth()) * .5, textY(caption, 0, SEARCH_H));
            caption.setHidden(searching);
        });
        spinner(button, FoliaLucideIcons.LOADER, PartyIcons.HOURGLASS, 16, () -> searchButtonW() * .5,
                () -> SEARCH_H * .5, LyricSearchOverlay::accentText, () -> searching ? 1.0 : 0.0);
    }

    /** 「搜索」那颗键的宽：字宽 + {@code px-4}，最窄 48。 */
    private double searchButtonW() {
        return buttonWidth("搜索", 48.0);
    }

    /** 输入盘吃掉剩下的宽度：{@code flex-1} 加 {@code gap-3}。 */
    private double searchPlateW() {
        return Math.max(60.0, leftContentW() - SEARCH_GAP - searchButtonW());
    }



    /** {@code bg-white/5 focus:bg-white/10}（浅色用黑）。 */
    private int inputPlate() {
        int tint = isLight() ? BLACK : WHITE;
        boolean focused = input != null && input.isFocused();
        return overlay(tint, focused ? .10 : .05);
    }

    /** {@code border-white/10 focus:border-white/20}。 */
    private int inputBorder() {
        boolean focused = input != null && input.isFocused();
        return overlay(isLight() ? BLACK : WHITE, focused ? .20 : .10);
    }

    /** 候选区：{@code min-h-0 flex-1 overflow-y-auto space-y-3 pr-1}。 */
    private void listArea(final Panel host) {
        final Panel viewport = clip(host, () -> COL_PAD, listTop(), this::listW, this::listH);
        final ScrollPanel list = new ScrollPanel();
        viewport.addChild(list);
        list.setSpacing(CARD_GAP).setScrollStrength(36)
                .setAlignment(ScrollPanel.Alignment.VERTICAL);
        list.setBeforeRenderCallback(() -> {
            list.setBounds(Math.max(1, viewport.getWidth()), Math.max(1, viewport.getHeight()));
            list.setPosition(0, 0);
        });

        if (searching) {
            Panel row = strip(list, PLACEHOLDER_H);
            spinner(row, FoliaLucideIcons.LOADER, PartyIcons.HOURGLASS, 28,
                    () -> listW() * .5, () -> PLACEHOLDER_H * .5,
                    LyricSearchOverlay::textSecondary, () -> .5);
            return;
        }

        final List<LyricSearchHit> snapshot = results;
        if (snapshot.isEmpty()) {
            emptyState(strip(list, PLACEHOLDER_H));
            return;
        }
        for (LyricSearchHit hit : snapshot) {
            candidateCard(strip(list, CARD_H), hit);
        }
    }

    /** {@code h-40 opacity-50}：图标与文字一起淡，所以淡的是颜色而不是组件 alpha。 */
    private void emptyState(final Panel row) {
        final int ink = mix(textSecondary(), surfaceColor(), .5);
        lucide(row, FoliaLucideIcons.MUSIC, PartyIcons.NOTE, 40, () -> listW() * .5,
                () -> PLACEHOLDER_H * .5 - 12, () -> ink);
        final LabelWidget caption = label(row, "未找到结果，请尝试不同的关键词。", FontManager.pf18);
        caption.setBeforeRenderCallback(() -> {
            double width = listW();
            caption.setColor(ink);
            caption.setMaxWidth(Math.max(1, width - 24));
            caption.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            caption.setPosition((width - caption.getWidth()) * .5, PLACEHOLDER_H * .5 + 10);
        });
    }

    /**
     * 一张候选卡。
     *
     * <p>{@code flex items-start} —— 封面、文字列、选中的对勾全部<b>顶对齐</b>，不是垂直居中；
     * 对勾因此落在卡片右上角（{@code p-4} 那一圈里）。</p>
     */
    private void candidateCard(final Panel row, final LyricSearchHit hit) {
        final RoundedRectWidget card = new RoundedRectWidget();
        row.addChild(card);
        card.setRadius(R_CARD).setShouldOverrideMouseCursor(true);
        card.setOnClickCallback((mx, my, button) -> {
            if (button != 0) return false;
            select(hit);
            return true;
        });
        card.setBeforeRenderCallback(() -> {
            card.setBounds(listW(), CARD_H);
            card.setPosition(0, 0);
            card.setColor(picked(hit) ? selectedPlate() : cardPlate(card.isHovering()));
        });
        stroke(card, R_CARD, () -> picked(hit) ? selectedBorder() : borderFaint(), () -> 1.0);

        final RoundedRectWidget coverPlate = new RoundedRectWidget();
        card.addChild(coverPlate);
        coverPlate.setClickable(false).setRadius(R_COVER);
        coverPlate.setBeforeRenderCallback(() -> {
            coverPlate.setBounds(CARD_COVER, CARD_COVER);
            coverPlate.setPosition(CARD_PAD, CARD_PAD);
            coverPlate.setColor(coverPlaceholder());
        });
        // 供应器要「每帧现问」，不能在这里把 Location 取出来一次就锁死：封面是异步取的，取流状态
        // 就挂在这个供应器上被逐帧推动（见 {@link #coverLocation}），捕获成一次调用等于把重试的
        // 门也一起关掉了。
        final RoundedImageWidget coverImg = new RoundedImageWidget(() -> coverLocation(hit.coverUrl),
                0, 0, 0, 0);
        card.addChild(coverImg);
        coverImg.setClickable(false).fadeIn().setLinearFilter(true);
        coverImg.setBeforeRenderCallback(() -> {
            coverImg.setRadius(R_COVER);
            coverImg.setBounds(CARD_COVER, CARD_COVER);
            coverImg.setPosition(CARD_PAD, CARD_PAD);
        });
        lucide(coverPlate, FoliaLucideIcons.MUSIC, PartyIcons.NOTE, 18, () -> CARD_COVER * .5,
                () -> CARD_COVER * .5, LyricSearchOverlay::textSecondary);

        final double textLeft = CARD_PAD + CARD_COVER + CARD_INNER_GAP;

        // 第一行：歌名（truncate）+ 匹配分徽标，两样在 {@link #CARD_LINE1} 高的行盒里居中。
        final double badgeW = Math.max(26.0, FontManager.pf12bold.getStringWidthD(hit.score + "%") + 12);
        final LabelWidget title = label(card, hit.title, FontManager.pf18bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(textPrimary());
            title.setMaxWidth(Math.max(10, cardTextRight(hit) - textLeft - 8 - badgeW));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(textLeft, textY(title, CARD_PAD, CARD_LINE1));
        });

        final RoundedRectWidget badge = new RoundedRectWidget();
        card.addChild(badge);
        badge.setClickable(false).setRadius(R_BADGE);
        badge.setBeforeRenderCallback(() -> {
            badge.setBounds(badgeW, LH_BADGE);
            badge.setPosition(textLeft + 8 + title.getWidth(), CARD_PAD + (CARD_LINE1 - LH_BADGE) * .5);
            badge.setColor(blueTint());
        });
        final LabelWidget badgeText = label(card, hit.score + "%", FontManager.pf12bold);
        badgeText.setBeforeRenderCallback(() -> {
            // {@code text-blue-400}：两套主题都是这一档，浅色并没有换成 blue-600。
            badgeText.setColor(BLUE_400);
            badgeText.setPosition(badge.getRelativeX() + 6, textY(badgeText, badge.getRelativeY(), LH_BADGE));
        });

        final LabelWidget artist = label(card, hit.artists.isEmpty() ? "-" : hit.artists, FontManager.pf16);
        artist.setBeforeRenderCallback(() -> {
            artist.setColor(textSecondary());
            artist.setMaxWidth(Math.max(10, cardTextRight(hit) - textLeft));
            artist.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            artist.setPosition(textLeft, textY(artist, CARD_PAD + CARD_LINE1 + CARD_SUB_MT, CARD_SUB));
        });
        final LabelWidget album = label(card, hit.album.isEmpty() ? "-" : hit.album, FontManager.pf16);
        album.setBeforeRenderCallback(() -> {
            album.setColor(textSecondary());
            album.setMaxWidth(Math.max(10, cardTextRight(hit) - textLeft));
            album.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            album.setPosition(textLeft, textY(album,
                    CARD_PAD + CARD_LINE1 + CARD_SUB_MT + CARD_SUB + CARD_SUB_MT, CARD_SUB));
        });

        final LucideIcon mark = lucide(card, FoliaLucideIcons.CHECK, PartyIcons.CHECK, 18,
                () -> listW() - CARD_PAD - 9, () -> CARD_PAD + 9, LyricSearchOverlay::accentText);
        mark.setBeforeRenderCallback(() -> mark.setHidden(!picked(hit)));
    }

    private boolean picked(LyricSearchHit hit) {
        return selected == hit;
    }

    /** 卡片文字列的右边界：选中那一档要给右上角的对勾让位。 */
    private double cardTextRight(LyricSearchHit hit) {
        return listW() - CARD_PAD - (picked(hit) ? 18 + CARD_INNER_GAP : 0);
    }

    private static int cardPlate(boolean hovering) {
        int tint = isLight() ? BLACK : WHITE;
        return overlay(tint, hovering ? .10 : .05);
    }

    /** {@code bg-blue-500/10}（浅色）/ {@code /20}。 */
    private static int selectedPlate() {
        return overlay(BLUE_500, isLight() ? .10 : .20);
    }

    /** {@code border-blue-500/30}（浅色）/ {@code /50}。 */
    private static int selectedBorder() {
        return overlay(BLUE_500, isLight() ? .30 : .50);
    }

    /** 卡片封面那格 {@code bg-white/5} / {@code bg-black/5}。 */
    private static int coverPlaceholder() {
        return overlay(isLight() ? BLACK : WHITE, .05);
    }

    // ──────────────────────────── 右栏 ────────────────────────────

    private void rightColumn() {
        final Panel column = clip(dialog, this::leftW, bodyTop(), this::rightW, this::bodyH);

        final RectWidget edge = new RectWidget();
        column.addChild(edge);
        edge.setClickable(false);
        edge.setBeforeRenderCallback(() -> {
            edge.setBounds(1, Math.max(1, column.getHeight()));
            edge.setPosition(0, 0);
            edge.setColor(borderStrong());
        });

        // 上面那一段（封面 + 信息）自己可滚：{@code flex-1 min-h-0 overflow-y-auto pr-1}。
        final Panel upper = clip(column, () -> COL_PAD, COL_PAD, this::rightContentW, this::rightUpperH);
        final ScrollPanel list = new ScrollPanel();
        upper.addChild(list);
        list.setSpacing(0).setScrollStrength(28).setAlignment(ScrollPanel.Alignment.VERTICAL);
        list.setBeforeRenderCallback(() -> {
            list.setBounds(Math.max(1, upper.getWidth()), Math.max(1, upper.getHeight()));
            list.setPosition(0, 0);
        });
        infoStrip(strip(list, () -> RIGHT_COVER + RIGHT_INFO_MT + infoHeight()));

        // 预览块定高钉底。它可以点（失败态里那颗「重新加载」），所以必须保持可交互。
        final LyricPreviewBox preview = new LyricPreviewBox();
        column.addChild(preview);
        preview.setBeforeRenderCallback(() -> {
            preview.setBounds(rightPreviewW(), PREVIEW_H);
            preview.setPosition(COL_PAD, rightPreviewTop());
        });
    }

    /** 预览块的上沿：{@code h-28 mt-4} 钉在右栏底部。 */
    private double rightPreviewTop() {
        return Math.max(1, bodyH()) - COL_PAD - PREVIEW_H;
    }

    /** 右栏上面那一段（封面 + 信息）的可用高：到预览块上沿为止，中间隔着 {@code mt-4}。 */
    private double rightUpperH() {
        return Math.max(1, rightPreviewTop() - COL_PAD - PREVIEW_MT);
    }

    /** 预览块的宽：右栏内容宽。它不是滚动区，所以不必让出 {@code pr-1}。 */
    private double rightPreviewW() {
        return Math.max(1, rightW() - COL_PAD * 2);
    }

    /** 右栏上面那一段：128 封面 + {@code mt-4} 的居中信息块，整体 {@code items-center}。 */
    private void infoStrip(final Panel row) {
        final RoundedRectWidget coverPlate = new RoundedRectWidget();
        row.addChild(coverPlate);
        coverPlate.setClickable(false).setRadius(R_PLATE);
        coverPlate.setBeforeRenderCallback(() -> {
            double width = rightContentW();
            coverPlate.setBounds(RIGHT_COVER, RIGHT_COVER);
            coverPlate.setPosition((width - RIGHT_COVER) * .5, 0);
            // {@code bg-zinc-800}：这一格两套主题都不换。
            coverPlate.setColor(ZINC_800);
        });
        final RoundedImageWidget cover = new RoundedImageWidget(this::currentCover, 0, 0, 0, 0);
        row.addChild(cover);
        cover.setClickable(false).fadeIn().setLinearFilter(true);
        cover.setBeforeRenderCallback(() -> {
            cover.setRadius(R_PLATE);
            cover.setBounds(RIGHT_COVER, RIGHT_COVER);
            cover.setPosition((rightContentW() - RIGHT_COVER) * .5, 0);
        });
        lucide(coverPlate, FoliaLucideIcons.MUSIC, PartyIcons.NOTE, 28, () -> RIGHT_COVER * .5,
                () -> RIGHT_COVER * .5, () -> mix(ZINC_800, WHITE, .10));

        final double infoTop = RIGHT_COVER + RIGHT_INFO_MT;

        final LabelWidget title = label(row, this::selectedTitle, FontManager.pf20bold);
        title.setBeforeRenderCallback(() -> {
            double width = rightContentW();
            title.setColor(textPrimary());
            title.setMaxWidth(infoTextW());
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition((width - title.getWidth()) * .5, textY(title, infoTop, INFO_LINE1));
        });
        final LabelWidget artist = label(row, this::selectedArtist, FontManager.pf18);
        artist.setBeforeRenderCallback(() -> {
            // {@code opacity-75} 叠在正文色上。
            artist.setColor(mix(textPrimary(), surfaceColor(), .25));
            artist.setMaxWidth(infoTextW());
            artist.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            artist.setPosition((rightContentW() - artist.getWidth()) * .5,
                    textY(artist, infoTop + titleLines() * INFO_LINE1 + RIGHT_BLOCK_GAP, INFO_LINE2));
        });
        final LabelWidget album = label(row, this::selectedAlbum, FontManager.pf16);
        album.setBeforeRenderCallback(() -> {
            // {@code opacity-60}。
            album.setColor(mix(textPrimary(), surfaceColor(), .40));
            album.setMaxWidth(infoTextW());
            album.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            album.setPosition((rightContentW() - album.getWidth()) * .5,
                    textY(album, infoTop + titleLines() * INFO_LINE1 + RIGHT_BLOCK_GAP
                            + INFO_LINE2 + RIGHT_BLOCK_GAP, INFO_LINE3));
        });
    }

    /** 信息块里那三行字的折行宽：内容宽再让出 {@code px-1} 那点留白。 */
    private double infoTextW() {
        return Math.max(10, rightContentW() - INFO_PAD_X * 2);
    }

    /**
     * 歌名被 {@code line-clamp-2} 折成几行。
     *
     * <p>折行宽度与标题那一行用的是同一个数，所以只在渲染时算一遍缓存起来；下一帧宽度变了会重算。</p>
     */
    private int titleLines() {
        String text = selectedTitle();
        double wrap = Math.max(10, infoTextW());
        if (text.equals(clampText) && wrap == clampWidth) return clampLines;
        clampLines = Math.max(1, Math.min(2, FontManager.pf20bold.fitWidth(text, wrap).length));
        clampText = text;
        clampWidth = wrap;
        return clampLines;
    }

    /** 上一次算折行用的是这行字和这个宽度，得到这么多行。纯缓存，不参与逻辑。 */
    private String clampText = "";
    private double clampWidth = -1;
    private int clampLines = 1;

    private String selectedTitle() {
        LyricSearchHit hit = selected;
        if (hit != null) return safe(hit.title);
        return track == null ? "" : safe(track.getName());
    }

    private String selectedArtist() {
        LyricSearchHit hit = selected;
        if (hit != null) return hit.artists.isEmpty() ? "-" : hit.artists;
        if (track == null) return "";
        String artists = safe(track.getArtistsName());
        return artists.equalsIgnoreCase("Unknown") ? "" : artists;
    }

    private String selectedAlbum() {
        LyricSearchHit hit = selected;
        if (hit != null) return hit.album.isEmpty() ? "-" : hit.album;
        if (track == null) return "";
        try {
            return track.getAlbum() == null ? "" : safe(track.getAlbum().getName());
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 右栏那格封面：选中候选就用它的封面，否则用正在放的这一首（镜像中则是外部播放器那张）。 */
    private Location currentCover() {
        LyricSearchHit hit = selected;
        if (hit != null) return coverLocation(hit.coverUrl);
        // 走 preferredCoverLocation 而不是直接取静态封面：系统播放器镜像下这一首可能压根没有
        // 曲库封面，而外部那张图已经装在另一个命名空间里了。
        return track == null ? null : CloudMusic.preferredCoverLocation(track, track.getSmallCoverLocation());
    }

    // ──────────────────────────── 页脚 ────────────────────────────

    private void footer() {
        final RectWidget rule = new RectWidget();
        dialog.addChild(rule);
        rule.setClickable(false);
        rule.setBeforeRenderCallback(() -> {
            rule.setBounds(dialogW(), 1);
            rule.setPosition(0, bodyBottom());
            rule.setColor(borderStrong());
        });

        final RoundedRectWidget cancel = footerButton(dialog, "取消", false);
        cancel.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            dispose();
            return true;
        });
        cancel.setBeforeRenderCallback(() -> {
            cancel.setBounds(cancelW(), BUTTON_H);
            cancel.setPosition(dialogW() - PAD_X - cancelW() - FOOTER_GAP - saveW(), footerTop());
            // {@code bg-zinc-100/80 hover:bg-zinc-200}（浅色）对 {@code bg-white/5 → /10}（深色）。
            cancel.setColor(isLight() ? (cancel.isHovering() ? ZINC_200 : mix(ZINC_900, ZINC_100, .8))
                    : overlay(WHITE, cancel.isHovering() ? .10 : .05));
        });

        final RoundedRectWidget save = footerButton(dialog, "保存", true);
        save.setOnClickCallback((x, y, button) -> {
            if (button != 0 || selected == null || matching) return false;
            confirm();
            return true;
        });
        save.setBeforeRenderCallback(() -> {
            save.setBounds(saveW(), BUTTON_H);
            save.setPosition(dialogW() - PAD_X - saveW(), footerTop());
            save.setColor(blueButton(save.isHovering() && selected != null && !matching));
            // {@code disabled:opacity-50}：整颗连字一起淡，所以动的是 alpha 不是颜色。
            save.setAlpha(selected == null || matching ? .5f : 1f);
        });
    }

    private static final double FOOTER_GAP = 12.0;

    /** 页脚那一排按钮的顶：贴着分隔线，在 {@code py} + 按钮 + {@code py} 里居中。 */
    private double footerTop() {
        return bodyBottom() + (FOOTER_H - BUTTON_H) * .5;
    }

    private double cancelW() {
        return buttonWidth("取消", 64.0);
    }

    private double saveW() {
        return buttonWidth(matching ? "匹配中..." : "保存", 64.0);
    }

    /** 只有文字的按钮：字宽 + {@code px-4}，最窄 {@code min}。搜索键与页脚两颗共用。 */
    private static double buttonWidth(String text, double min) {
        return Math.max(min, FontManager.pf18bold.getStringWidthD(text) + SEARCH_PAD_X * 2);
    }

    /** 页脚那颗按钮：只有文字，{@code px-4 py-2 rounded-xl}。 */
    private RoundedRectWidget footerButton(final Panel dialog, final String text, boolean accent) {
        final RoundedRectWidget button = new RoundedRectWidget();
        dialog.addChild(button);
        button.setRadius(R_BUTTON).setShouldOverrideMouseCursor(true);
        final LabelWidget caption = label(button, text, FontManager.pf18bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(accent ? accentText() : textPrimary());
            // 页脚那颗键的相对 y 就是 0，整颗键高即行盒。
            caption.setPosition((button.getWidth() - caption.getWidth()) * .5,
                    textY(caption, 0, BUTTON_H));
        });
        return button;
    }

    // ════════════════════════════ 行为 ════════════════════════════

    /** 换标签页：清结果与选中，检索词回到默认那一条再搜一次 —— 与 folia 的 {@code [song, source]} 副作用同构。 */
    private void switchSource(LyricSearchSource value) {
        if (source == value) return;
        source = value;
        results = Collections.emptyList();
        selected = null;
        clearPreview();
        runSearch(LyricSearchService.defaultQuery(track));
    }

    private void runSearch(String query) {
        final LyricSearchSource at = source;
        final int seq = ++searchSeq;
        queryDraft = query == null ? "" : query;
        perfSearchStartedAt = System.nanoTime();
        searching = true;
        results = Collections.emptyList();
        selected = null;
        clearPreview();
        touch();
        MultiThreadingUtil.runAsync(() -> {
            List<LyricSearchHit> hits = Collections.emptyList();
            try {
                hits = LyricSearchService.search(at, queryDraft, track);
            } catch (Throwable failure) {
                System.err.println("[Music/LyricSearch] 检索失败: " + failure);
            }
            final List<LyricSearchHit> done = hits;
            MultiThreadingUtil.runOnMainThread(() -> finishSearch(seq, done));
        });
    }

    private void finishSearch(int seq, List<LyricSearchHit> hits) {
        if (seq != searchSeq) return;
        if (perfSearchStartedAt > 0) {
            perfSearchMs = (System.nanoTime() - perfSearchStartedAt) / 1e6;
            perfSearchStartedAt = 0;
        }
        perfResultCount = hits == null ? 0 : hits.size();
        // 结果回来后的下一帧才是「冻」最可能出现的地方：整树重搭 + 可见候选各拉一张封面，
        // 封面到手又各自往主线程排一次上传。窗口从这一帧之后开始数。
        perfWindowLeft = PERF_WINDOW_FRAMES;
        searching = false;
        results = hits == null ? Collections.<LyricSearchHit>emptyList() : hits;
        logIfAllCoversMissing(results);
        // 有结果就自动选中第一条：用户不点也能直接保存，与 folia 的 {@code results[0]} 一致。
        selected = results.isEmpty() ? null : results.get(0);
        loadPreview(selected);
        touch();
    }

    /**
     * 一整列候选<b>全部</b>没有封面地址时留一行日志。
     *
     * <p>封面这条链上有两种完全不同的失败：地址压根没拼出来（上游字段名或结构变了），和地址拼出来了
     * 却取不到（图床、网络、解码）。它们在界面上长得一模一样 —— 卡片底下那枚音符占位会把两种都画成
     * 「没有封面」。前者不会走到取流，所以 {@link #coverLocation} 那条「试了 N 趟」的日志是沉默的；
     * 这一行就是补上那半边，让下一次真机报告能直接二分，而不是靠猜。</p>
     *
     * <p>只在<b>全空</b>时说：某几条本来就没封面是常态，逐条打就成噪音了。</p>
     *
     * <p>真机报告说「只有 QQ 那一页整列没有封面」，但离线抓的 QQ 搜索响应里 20/20 条的
     * {@code albummid} 都非空、拼出来的 URL 也都取得到图，所以这两条日志到底点亮哪一边，只能等
     * 下一次真机。在那之前别把任何一边当成结论。</p>
     */
    private static void logIfAllCoversMissing(List<LyricSearchHit> hits) {
        if (hits.size() < 2) return;
        for (LyricSearchHit hit : hits) {
            if (hit.coverUrl != null && !hit.coverUrl.isEmpty()) return;
        }
        System.err.println("[Music/LyricSearch] " + hits.get(0).source + " 的 " + hits.size()
                + " 条候选全都没有封面地址（上游字段没拼出来，不是取流失败）");
    }

    private void select(LyricSearchHit hit) {
        if (selected == hit) return;
        selected = hit;
        loadPreview(hit);
    }

    private void clearPreview() {
        previewSeq++;
        previewLines = null;
        previewError = null;
        previewLoading = false;
    }

    /** 选中一条就把它的歌词取回来铺在右下角 —— 和「保存」用的是同一条取词链路。 */
    private void loadPreview(final LyricSearchHit hit) {
        final int seq = ++previewSeq;
        if (hit == null) {
            clearPreview();
            return;
        }
        previewLines = null;
        previewError = null;
        previewLoading = true;
        MultiThreadingUtil.runAsync(() -> {
            List<LyricLine> lines = null;
            String reason = null;
            try {
                lines = LyricSearchService.fetchLyrics(hit);
            } catch (Throwable failure) {
                reason = failure.getMessage() == null ? "获取歌词失败" : failure.getMessage();
            }
            final List<LyricLine> done = lines;
            final String why = reason;
            MultiThreadingUtil.runOnMainThread(() -> {
                if (seq != previewSeq) return;
                previewLoading = false;
                if (done == null || done.isEmpty()) {
                    previewError = why == null || why.trim().isEmpty() ? "无可用歌词" : why.trim();
                    return;
                }
                previewLines = done;
            });
        });
    }

    /** 保存 = 先确认这条真能取到词，再钉住；取不到就不动用户原来的歌词。 */
    private void confirm() {
        final LyricSearchHit hit = selected;
        if (hit == null || matching) return;
        matching = true;
        MultiThreadingUtil.runAsync(() -> {
            List<LyricLine> lines = null;
            try {
                lines = LyricSearchService.fetchLyrics(hit);
            } catch (Throwable ignored) {
                // 取词失败和「没有这份词」走同一条收尾：原因留在预览块里给用户看。
            }
            final boolean ok = lines != null && !lines.isEmpty();
            MultiThreadingUtil.runOnMainThread(() -> {
                matching = false;
                if (!ok) {
                    previewError = "获取歌词失败";
                    previewLines = null;
                    return;
                }
                CloudMusic.pinLyric(track, hit);
                dispose();
            });
        });
    }

    // ════════════════════════════ 小工具 ════════════════════════════

    /**
     * 一块硬裁的容器：内容超出自己的边界就被切掉，不会盖到邻栏上。
     *
     * <p>左上的 x 与宽高都是供应器而不是数值 —— 刚 {@code new} 出来的面板还没被自己的回调定过
     * 尺寸，在搭建时量它必然是 0，而右栏的起点又要跟着弹窗这一帧的宽走（两栏是按百分比分的）。
     * y 只有常量，就留成数值。</p>
     */
    private Panel clip(final AbstractWidget<?> host, final DoubleSupplier x, final double y,
                       final DoubleSupplier width, final DoubleSupplier height) {
        final Panel panel = new Panel() {
            @Override
            protected void beforeRenderChildren(double mouseX, double mouseY) {
                ScissorClipManager.begin(getX(), getY(), getWidth(), getHeight());
            }

            @Override
            protected void afterRenderChildren(double mouseX, double mouseY) {
                ScissorClipManager.end();
            }
        };
        host.addChild(panel);
        panel.setClickable(false);
        panel.setBeforeRenderCallback(() -> panel.setBounds(Math.max(0, x.getAsDouble()), y,
                Math.max(1, width.getAsDouble()), Math.max(1, height.getAsDouble())));
        return panel;
    }

    /**
     * 一枚 lucide 矢量图标，画不出来时退回 party 字形。
     *
     * <p>为什么两层：矢量那一份与 folia 逐点同源，字形那一份保证「就算 Skija 这一帧没开也看得见
     * 东西」。{@link FoliaLucideIcons#draw} 在帧外返回 false 而不是抛异常。</p>
     */
    private LucideIcon lucide(final AbstractWidget<?> host, final String name, final String fallback,
                              final double size, final DoubleSupplier cx, final DoubleSupplier cy,
                              final IntSupplier color) {
        final LucideIcon icon = new LucideIcon(name, fallback, size, cx, cy, color);
        host.addChild(icon);
        icon.setClickable(false);
        return icon;
    }

    /** 旋转着的 loader：{@code animate-spin} 那一圈。 */
    private LucideIcon spinner(final AbstractWidget<?> host, final String name, final String fallback,
                               final double size, final DoubleSupplier cx, final DoubleSupplier cy,
                               final IntSupplier color, final DoubleSupplier alpha) {
        final LucideIcon icon = new LucideIcon(name, fallback, size, cx, cy, color);
        icon.spin = true;
        icon.extraAlpha = alpha;
        host.addChild(icon);
        icon.setClickable(false);
        return icon;
    }

    private final class LucideIcon extends AbstractWidget<LucideIcon> {
        private final String name;
        private final String fallback;
        private final double size;
        private final DoubleSupplier cx;
        private final DoubleSupplier cy;
        private final IntSupplier color;
        private boolean spin;
        private DoubleSupplier extraAlpha = () -> 1.0;
        private double angle;
        private long lastFrame = System.nanoTime();

        private LucideIcon(String name, String fallback, double size, DoubleSupplier cx, DoubleSupplier cy,
                           IntSupplier color) {
            this.name = name;
            this.fallback = fallback;
            this.size = size;
            this.cx = cx;
            this.cy = cy;
            this.color = color;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            double alpha = getAlpha() * clamp01(extraAlpha.getAsDouble());
            if (alpha <= .004) return;
            double centerX = cx.getAsDouble();
            double centerY = cy.getAsDouble();
            setBounds(centerX - size * .5, centerY - size * .5, size, size);
            int ink = reAlpha(color.getAsInt(), (float) alpha);
            if (FoliaLucideIcons.draw(name, centerX, centerY, size, ink)) return;

            long now = System.nanoTime();
            double seconds = Math.min(.25, (now - lastFrame) / 1_000_000_000.0);
            lastFrame = now;
            angle = (angle + seconds * 300.0) % 360.0;
            if (spin && SkijaGfx.active()) {
                // 字形兜底也要转：Loader2 那枚「+」静止时看着像一个加号按钮。
                SkijaGfx.pushMatrix();
                try {
                    SkijaGfx.translate(centerX, centerY);
                    SkijaGfx.rotateZ((float) angle);
                    drawFallback(0, 0, ink);
                } finally {
                    SkijaGfx.popMatrix();
                }
            } else {
                drawFallback(centerX, centerY, ink);
            }
        }

        private void drawFallback(double centerX, double centerY, int ink) {
            CFontRenderer font = iconFont(size);
            GlyphInkMetrics square = GlyphInkMetrics.of(font, PartyIcons.FRAME.charAt(0));
            if (square == null) {
                font.drawString(fallback, centerX - size * .5, centerY - size * .5, ink);
                return;
            }
            font.drawString(fallback, square.alignCenterX(centerX), square.alignCenterY(centerY), ink);
        }
    }

    /**
     * 封面地址 → 贴图位置：按地址去重，带浏览器请求头，失败后有限次重试。
     *
     * <h3>去重要，但只发一次不够</h3>
     * <p>渲染每帧都会问一次这个地址（{@code RoundedImageWidget.onRender} 里那个供应器），而贴图
     * 上传是异步的：从发起到 {@code getTexture} 拿得到东西之间有几十帧，期间「还没就绪」一直成立，
     * 每帧调一遍就是每帧一条 HTTP。所以必须按地址去重；但去重成「发一次就不再管」也不够 —— 那一次
     * 要是失败，地址就永远停在一张空贴上，卡片上一直是音符占位，而且和「这条候选本来就没封面」长得
     * 一模一样，看的人分不出来。</p>
     *
     * <h3>为什么自己取流，不走 {@link Textures#downloadTextureAndLoadAsync}</h3>
     * <p>那条路一个请求头都不设，失败只往日志里丢一条栈，而且<b>一次定终身</b>：这一页的候选会随滚动
     * 反复重建，一条没抢上的地址就永远停在空贴上。这里自己发请求，图的是上面那套有限次重试的状态机。
     * 顺带把请求头按浏览器那套写：Accept 只声明 {@code ImageIO} 解得动的类型 —— 把 webp/avif 报上去，
     * CDN 就会回一份 Java 解不了的字节，那比回 PNG 更糟；Referer 按主机给，不拿无关地址去递第三方域名。</p>
     *
     * <p><b>注意：请求头不是「QQ 那一页整列没封面」的解药，别再这么记。</b>当时我先认定是
     * {@code y.gtimg.cn} 挡 {@code Java/17} 这个 UA，实测把这个判断推翻了 —— 同一条 URL 用
     * {@code Java/17.0.9}、不带 UA、{@code curl/8.4.0} 和浏览器 UA 一样都是 {@code 200 image/jpeg}；
     * 照 {@code downloadStream} 那套设置（不设头、10 秒读超时）复现的探针也拿到了完整字节并且
     * {@code ImageIO} 解出了 300×300。所以这里加的头和重试算加固，真机那一半仍然没定论，
     * 交给两条互斥的日志去分：见 {@link #logIfAllCoversMissing} 与 {@link CoverRequest#logGiveUp()}。</p>
     */
    private static final Map<String, CoverRequest> COVER_REQUESTS =
            new ConcurrentHashMap<String, CoverRequest>();
    private static final String COVER_DIR = "muonium/textures/lyric_search/";
    /** 一个地址最多问几趟。真机上的失败多半不是「再试一趟就通」那种，次数要有顶，别把重试变成刷屏。 */
    private static final int COVER_MAX_ATTEMPTS = 4;
    /** 两趟之间的最小间隔，也是「还在路上」和「这趟失败了」共用的那一口气。 */
    private static final long COVER_RETRY_COOLDOWN_MS = 1_200L;
    /** 这张表按地址只涨不缩；攒到这一档就整表清空，重新露面的地址重新计一次。 */
    private static final int COVER_TABLE_SOFT_LIMIT = 256;
    /**
     * 同时在途的封面请求上限。
     *
     * <p>一屏能看见五六张候选，每张各要一次封面；不设上限就是同一帧里发五六条 HTTP，几毫秒后它们
     * 先后到手，又各自往主线程排一次贴图上传 —— 一次上传要把整张图的像素拷进显存，五六张挤在两帧
     * 里就是打开这一页时最像「冻一下」的那一段。排队不亏：封面晚半秒出来只是那半秒里是音符占位，
     * 而挤在一起是把整帧吃掉。</p>
     */
    private static final int COVER_MAX_IN_FLIGHT = 3;
    private static final java.util.concurrent.atomic.AtomicInteger COVER_IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final String COVER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120.0.0.0 Safari/537.36";

    private static Location coverLocation(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        String key = url.trim();
        CoverRequest request = COVER_REQUESTS.get(key);
        if (request == null) {
            if (COVER_REQUESTS.size() >= COVER_TABLE_SOFT_LIMIT) COVER_REQUESTS.clear();
            // 文件名带上长度：光靠 hashCode 有极小概率撞上另一个地址，撞上了会拿错图而不是报错，
            // 那种错最难查。
            Location location = Location.of(COVER_DIR + Integer.toHexString(key.hashCode())
                    + "-" + key.length() + ".png");
            CoverRequest fresh = new CoverRequest(key, location);
            CoverRequest seen = COVER_REQUESTS.putIfAbsent(key, fresh);
            request = seen == null ? fresh : seen;
        }
        request.pump();
        return request.location;
    }

    /** 一个封面地址的取流状态：试了几趟、下一趟什么时候准走、到手没有。 */
    private static final class CoverRequest {
        private final String url;
        private final Location location;
        private int attempts;
        private long nextTryAt;
        private boolean inFlight;
        private boolean loaded;
        private boolean logged;

        private CoverRequest(String url, Location location) {
            this.url = url;
            this.location = location;
        }

        /**
         * 每帧由渲染问一次。除了两次哈希查表什么都不做，真正的活计丢到工作线程上 ——
         * 这一档在渲染路径里，阻塞一帧就是掉一帧。
         */
        private synchronized void pump() {
            if (loaded) return;
            if (TextureManager.getInstance().getTexture(location) != null) {
                loaded = true;
                return;
            }
            long now = System.currentTimeMillis();
            if (inFlight || now < nextTryAt) return;
            if (attempts >= COVER_MAX_ATTEMPTS) {
                logGiveUp();
                return;
            }
            // 名额满了这一帧就不动，下一帧再问 —— 这一档每帧都会进来一次，排队不需要额外的定时器。
            // 必须在 attempts++ 之前：没真发出去的趟数不能算进「试过几趟」。
            if (!tryAcquireCoverSlot()) return;
            attempts++;
            inFlight = true;
            nextTryAt = now + COVER_RETRY_COOLDOWN_MS;
            try {
                fetch();
            } catch (RuntimeException couldNotSubmit) {
                // 线程池拒收（关闭中、上限）时把名额还回去，否则这一页后面所有封面都排不上。
                releaseCoverSlot();
                inFlight = false;
            }
        }

        private static boolean tryAcquireCoverSlot() {
            for (;;) {
                int now = COVER_IN_FLIGHT.get();
                if (now >= COVER_MAX_IN_FLIGHT) return false;
                if (COVER_IN_FLIGHT.compareAndSet(now, now + 1)) return true;
            }
        }

        private static void releaseCoverSlot() {
            COVER_IN_FLIGHT.decrementAndGet();
        }

        private void fetch() {
            MultiThreadingUtil.runAsync(() -> {
                boolean ok = false;
                try (InputStream stream = HttpUtils.get(url, null, coverHeaders(url), 5_000, 10_000)) {
                    if (stream != null) {
                        BufferedImage image = DynamicTexture.readImage(stream);
                        if (image != null) {
                            // loadTexture 自己会把上传排回主线程，工作线程直接调就行。
                            Textures.loadTexture(location, image);
                            ok = true;
                        }
                    }
                } catch (Throwable ignored) {
                    // 非 2xx、断流、解不出图走同一条收尾：都算这趟没到手，交给重试与最后的日志。
                }
                synchronized (this) {
                    inFlight = false;
                    loaded = ok;
                }
                releaseCoverSlot();
            });
        }

        private void logGiveUp() {
            if (logged) return;
            logged = true;
            System.err.println("[Music/LyricSearch] 封面取不到（试了 " + attempts + " 趟）: " + url);
        }
    }

    /** 取封面要用的请求头。Accept 里不写 webp/avif，理由见 {@link #coverLocation}。 */
    private static Map<String, String> coverHeaders(String url) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", COVER_USER_AGENT);
        headers.put("Accept", "image/png, image/jpeg, image/*;q=0.5");
        String referer = coverReferer(url);
        if (referer != null) headers.put("Referer", referer);
        return headers;
    }

    /** 只有腾讯的图床要 Referer；不给无关域名递第三方地址。 */
    private static String coverReferer(String url) {
        String host = hostOf(url);
        return host.endsWith("gtimg.cn") || host.endsWith("qq.com") ? "https://y.qq.com/" : null;
    }

    private static String hostOf(String url) {
        String value = safe(url).trim().toLowerCase(Locale.ROOT);
        int schemeEnd = value.indexOf("://");
        if (schemeEnd >= 0) value = value.substring(schemeEnd + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(0, colon);
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    // ════════════════════════════ 预览块 ════════════════════════════

    /**
     * 右下角那块歌词预览，照参考实现的歌词预览面板逐条搬。
     *
     * <p>整块是<b>一个</b>即时绘制组件，里面没有子组件：它要画的是一句被逐字点亮、还会横向滚走、
     * 换行时上下两行各自淡入淡出的文字 —— 用组件树表达这套动画只会在每帧改一堆供应器，反而更难
     * 对齐时序。</p>
     *
     * <h3>四种状态</h3>
     * <p>没选中（虚线框 + 音符 +「请在左侧选择一首歌曲」）、加载中、取词失败（带「重新加载」那颗
     * 小按钮）、有词。有词时再分：整首没有一句带时间的行 → 一个「无」字。</p>
     *
     * <h3>逐字填色</h3>
     * <p>CSS 那边是「每个 span 自己一条硬停渐变」，也就是<b>逐字</b>各扫各的，不是整行共用一条
     * 渐变。这里一样：以字为单位切色，切换点落在某个字的中间时，那个字整体算已唱完 —— 一个 14
     * 像素的字里半个字的差别看不出来，逐字量一次宽度反而要在每帧多跑一遍测量。</p>
     */
    private final class LyricPreviewBox extends AbstractWidget<LyricPreviewBox> {

        /** 上下两行各自的一套换行动画：{@code mode="wait"} 是先退场 150ms 再入场 150ms。 */
        private final Track primary = new Track();
        private final Track secondary = new Track(0.04);
        private List<LyricLine> boundLines;
        /** 「重新加载」那颗小按钮的矩形，点击判定与绘制共用。 */
        private double reloadX, reloadY, reloadW = 60, reloadH = 22;
        private boolean reloadHover;

        @Override
        public void onRender(double mouseX, double mouseY) {
            double width = getWidth(), height = getHeight();
            int surface = surfaceColor();
            boolean light = isLight();
            int boxBg = light ? overlay(BLACK, .02) : overlay(WHITE, .02);
            int boxBorder = light ? overlay(BLACK, .05) : overlay(WHITE, .05);

            roundedRect(getX(), getY(), width, height, R_PREVIEW, boxBg);
            if (selected == null) {
                // {@code border-dashed border-white/10}：这一档用的是通用描边色，不是预览块的淡描边。
                drawDashed(borderStrong());
            } else {
                roundedOutline(getX(), getY(), width, height, R_PREVIEW, 1, new Color(boxBorder));
            }

            if (selected == null) {
                centeredPlaceholder(width, height, PartyIcons.NOTE, FoliaLucideIcons.MUSIC, 24,
                        "请在左侧选择一首歌曲", 6, mix(textSecondary(), surface, .6));
                primary.reset();
                secondary.reset();
                return;
            }
            if (previewLoading) {
                centeredPlaceholder(width, height, PartyIcons.HOURGLASS, FoliaLucideIcons.LOADER, 20,
                        "加载歌词中...", 8, mix(textPrimary(), surface, .4));
                primary.reset();
                secondary.reset();
                return;
            }
            if (previewError != null) {
                errorState(width, height, mouseX, mouseY);
                primary.reset();
                secondary.reset();
                return;
            }

            List<LyricLine> lines = previewLines;
            if (lines == null || lines.isEmpty()) {
                primary.reset();
                secondary.reset();
                return;
            }

            long time = previewClock();
            LyricLine[] pair = resolveDisplay(lines, time);
            LyricLine current = pair[0];
            LyricLine next = pair[1];
            String subText = subLineText(lines, current, next);
            boolean isTranslation = subIsTranslation(lines, current);

            // 上下两行各自一把 key，与 folia 的 `line-${lineKey}` / `sub-${...}` 同构。
            String primaryPath = "line-" + (current == null ? "empty" : lineKey(current));
            String secondaryPath = "sub-" + (next == null ? "none" : (next.timestamp + "-" + safe(next.lyric)))
                    + "-" + subText;
            if (boundLines != lines) {
                boundLines = lines;
                // 换了一批词不是「换了一行」：直接摆好，不要重播一次入场。
                primary.snap(primaryPath, current, null);
                secondary.snap(secondaryPath, null, subText);
            } else {
                primary.offer(primaryPath, current, null);
                secondary.offer(secondaryPath, null, subText);
            }

            // {@code p-3}(12) + {@code px-2}(8)：文字区左右各缩 20。
            double textInset = PREVIEW_PAD_X + PREVIEW_TEXT_INSET;
            double innerLeft = getX() + textInset;
            double innerW = Math.max(10, width - textInset * 2);
            // 两行框高 {@link #PREVIEW_L1_H} / {@link #PREVIEW_L2_H}，中间 6，整块在可用高度里垂直居中。
            double stack = PREVIEW_L1_H + PREVIEW_L2_MT + PREVIEW_L2_H;
            double blockTop = getY() + PREVIEW_PAD_Y
                    + Math.max(0, height - PREVIEW_PAD_Y * 2 - stack) * .5;
            double line1Center = blockTop + PREVIEW_L1_H * .5;
            double line2Center = blockTop + PREVIEW_L1_H + PREVIEW_L2_MT + PREVIEW_L2_H * .5;

            // 右上角那排特征标签：逐字 / 含翻译 / 含罗马音。
            drawBadges(width, isWordByWord(lines), anyTranslation(lines), anyRomanization(lines));

            CFontRenderer head = FontManager.pf18bold;
            CFontRenderer tail = FontManager.pf16bold;
            // {@code activeColor}/{@code mutedColor} 是两个写死的常量：蓝 + 三成不透明度的反色。
            int active = light ? BLUE_600 : BLUE_400;
            int muted = light ? mix(boxBg, BLACK, .35) : mix(boxBg, WHITE, .30);
            /*
             * 第二行的字色照那三行 class：翻译是 {@code text-emerald-600} / {@code text-emerald-400/80}
             * （80% 的 emerald-400 压在预览块底色上），下一句是 {@code text-zinc-400} / {@code text-zinc-500}。
             */
            int subInk;
            if (isTranslation) {
                subInk = light ? EMERALD_600 : mix(boxBg, EMERALD_400, .8);
            } else {
                subInk = light ? ZINC_400 : ZINC_500;
            }

            // {@code overflow-hidden}：超长的那一句必须被切在文字区里。
            ScissorClipManager.begin(innerLeft - 8, getY() + PREVIEW_PAD_Y,
                    Math.max(1, innerW + 16), Math.max(1, height - PREVIEW_PAD_Y * 2));
            try {
                primary.draw(head, innerLeft, innerW, line1Center, time, active, muted);
                secondary.drawText(tail, innerLeft, innerW, line2Center, subInk);
            } finally {
                ScissorClipManager.end();
            }
        }

        /** {@code text-xs mb-2} 的错误行 + {@code px-3 py-1.5 rounded-lg} 的重试键。 */
        private void errorState(double width, double height, double mouseX, double mouseY) {
            CFontRenderer font = FontManager.pf16;
            String message = previewError;
            String trimmed = font.trim(message, width - 24);
            double total = font.getStringWidthD(trimmed);
            CFontRenderer buttonFont = FontManager.pf16bold;
            reloadW = Math.max(56, buttonFont.getStringWidthD("重新加载") + 24);
            reloadH = 24;
            // 错误行 + 8 + 重试键，整块在框里垂直居中。那一行的带高就是它自己的行盒，
            // 所以画在带顶上等于画在带里，不用再居中一次。
            double lineH = font.getFontHeight();
            double textTop = getY() + (height - lineH - 8 - reloadH) * .5;
            font.drawString(trimmed, getX() + (width - total) * .5, textTop,
                    reAlpha(textSecondary(), getAlpha()));

            reloadX = getX() + (width - reloadW) * .5;
            reloadY = textTop + lineH + 8;
            reloadHover = mouseX >= reloadX && mouseX <= reloadX + reloadW
                    && mouseY >= reloadY && mouseY <= reloadY + reloadH;
            roundedRect(reloadX, reloadY, reloadW, reloadH, R_BUTTON, blueButton(reloadHover));
            double textW = buttonFont.getStringWidthD("重新加载");
            buttonFont.drawString("重新加载", reloadX + (reloadW - textW) * .5,
                    textY(buttonFont, reloadY, reloadH), reAlpha(accentText(), getAlpha()));
        }

        @Override
        public boolean onMouseClicked(double relativeX, double relativeY, int mouseButton) {
            if (mouseButton != 0 || previewError == null) return false;
            // reloadX/reloadY 是绘制时存的<b>绝对</b>坐标（含 getX()/getY()），
            // 而框架派发给这里的 relativeX/relativeY 是相对本控件的偏移（见 AbstractWidget
            // 的 mouseX - child.getX()）。以前直接拿相对值比绝对矩形，按钮在原点右侧就永远
            // 点不到——「看得见、点不动」。这里把相对坐标换算回绝对再比。
            double absoluteX = getX() + relativeX;
            double absoluteY = getY() + relativeY;
            if (absoluteX < reloadX || absoluteX > reloadX + reloadW
                    || absoluteY < reloadY || absoluteY > reloadY + reloadH) return false;
            loadPreview(selected);
            return true;
        }

        private void centeredPlaceholder(double width, double height, String glyph, String lucideName,
                                         double iconSize, String text, double gap, int ink) {
            CFontRenderer caption = FontManager.pf16;
            // 文字那一条带就是它自己的行盒：拿字号数字（16）当带高会把整块往下多推 8 格。
            double total = iconSize + gap + caption.getFontHeight();
            double top = getY() + (height - total) * .5;
            // 占位态的图标与文字都跟着整块一起淡（folia 在那里写了 opacity-40 / opacity-50）。
            if (FoliaLucideIcons.draw(lucideName, getX() + width * .5, top + iconSize * .5, iconSize,
                    reAlpha(ink, getAlpha()))) {
                // 矢量画出来了就不需要字形兜底。
            } else {
                CFontRenderer font = iconFont(iconSize);
                GlyphInkMetrics square = GlyphInkMetrics.of(font, PartyIcons.FRAME.charAt(0));
                double cx = getX() + width * .5, cy = top + iconSize * .5;
                if (square == null) {
                    font.drawString(glyph, cx - iconSize * .5, cy - iconSize * .5, reAlpha(ink, getAlpha()));
                } else {
                    font.drawString(glyph, square.alignCenterX(cx), square.alignCenterY(cy),
                            reAlpha(ink, getAlpha()));
                }
            }
            double textW = caption.getStringWidthD(text);
            caption.drawString(text, getX() + (width - textW) * .5, top + iconSize + gap,
                    reAlpha(ink, getAlpha()));
        }

        /** 一圈 3 开 3 关的短横：CSS 的 {@code border-dashed} 没有对应原语，手工排一段。 */
        private void drawDashed(int ink) {
            double x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int color = reAlpha(ink, getAlpha());
            for (double at = x + R_PREVIEW; at < x + w - R_PREVIEW; at += 6) {
                Rect.draw(at, y, Math.min(3, x + w - R_PREVIEW - at), 1, color);
                Rect.draw(at, y + h - 1, Math.min(3, x + w - R_PREVIEW - at), 1, color);
            }
            for (double at = y + R_PREVIEW; at < y + h - R_PREVIEW; at += 6) {
                Rect.draw(x, at, 1, Math.min(3, y + h - R_PREVIEW - at), color);
                Rect.draw(x + w - 1, at, 1, Math.min(3, y + h - R_PREVIEW - at), color);
            }
        }

        private void drawBadges(double width, boolean wordByWord, boolean translation, boolean romanization) {
            double right = getX() + width - 10;
            double top = getY() + 8;
            if (romanization) {
                right = badge(FoliaLucideIcons.LANGUAGES, PartyIcons.TEXT_FORMAT, "含罗马音",
                        VIOLET_500, VIOLET_400, right, top);
            }
            if (translation) {
                right = badge(FoliaLucideIcons.LANGUAGES, PartyIcons.TEXT_FORMAT, "含翻译",
                        EMERALD_500, EMERALD_400, right, top);
            }
            if (wordByWord) {
                badge(FoliaLucideIcons.SPARKLE, PartyIcons.NOTE, "逐字", INDIGO_500, INDIGO_400, right, top);
            }
        }

        /** 一枚徽标；返回它左边缘的 x，好让前一枚接着排。字是 {@code pf12bold} 那一档。 */
        private double badge(String name, String glyph, String text, int tint, int ink,
                             double right, double top) {
            CFontRenderer font = FontManager.pf12bold;
            double textW = font.getStringWidthD(text);
            double boxW = 6 + 7 + 2 + textW + 6;
            double left = right - boxW;
            int argb = reAlpha(ink, getAlpha());
            // {@code bg-indigo-500/10 border-indigo-500/10}：底与描边用的是同一个淡色。
            int tintPlate = reAlpha(mix(surfaceColor(), tint, .10), getAlpha());
            roundedRect(left, top, boxW, LH_BADGE, R_MINI, tintPlate);
            roundedOutline(left, top, boxW, LH_BADGE, R_MINI, 1, new Color(tintPlate));
            if (!FoliaLucideIcons.draw(name, left + 6 + 3.5, top + LH_BADGE * .5, 7, argb)) {
                CFontRenderer icon = iconFont(7);
                GlyphInkMetrics square = GlyphInkMetrics.of(icon, PartyIcons.FRAME.charAt(0));
                if (square != null) {
                    icon.drawString(glyph, square.alignCenterX(left + 9.5),
                            square.alignCenterY(top + LH_BADGE * .5), argb);
                }
            }
            font.drawString(text, left + 6 + 7 + 2, textY(font, top, LH_BADGE), argb);
            return left - 6;
        }
    }

    /**
     * 一行的换行动画状态。
     *
     * <p>framer-motion 的 {@code AnimatePresence mode="wait"} 是「旧的先退完、新的再进」，所以这里
     * 同时只有一份在动。退场那 150ms 画的是<b>上一份快照</b>（句子和整行文字都取自快照）—— 否则
     * 观众会在旧句子淡出的过程中先读到新句子。</p>
     *
     * <p>时间推进用<b>真实帧间隔</b>：这一页可能跑在 30 帧也可能跑在 240 帧，按帧数推进会让同一份
     * CSS（{@code duration: .15}）在两台机器上演出两种快慢。</p>
     */
    private final class Track {
        /** {@code transition.duration}。 */
        private static final double DURATION_SECONDS = .15;
        /** {@code transition.delay}：第二行比第一行晚 40ms 起步，退场也晚。 */
        private final double delaySeconds;
        private String key;
        private LyricLine snapshot;
        private String textSnapshot;
        /** 入场从 0 爬到 1，退场从 1 掉回 0。 */
        private double phase = 1.0;
        private boolean exiting;
        /** 还差多少秒才准开始动（起步等待）。 */
        private double hold;
        private long lastNanos = System.nanoTime();

        private Track() {
            this(0.0);
        }

        private Track(double delaySeconds) {
            this.delaySeconds = delaySeconds;
        }

        private void reset() {
            key = null;
            snapshot = null;
            textSnapshot = null;
            phase = 1.0;
            exiting = false;
            hold = 0;
        }

        /** 换到一批新词的第一帧：那不叫「换了一行」，直接摆好，不要重播入场。 */
        private void snap(String nextKey, LyricLine line, String text) {
            key = nextKey;
            snapshot = line;
            textSnapshot = text;
            phase = 1.0;
            exiting = false;
            hold = 0;
        }

        private void offer(String nextKey, LyricLine line, String text) {
            double seconds = tick();
            if (nextKey == null ? key == null : nextKey.equals(key)) {
                if (!exiting) phase = Math.min(1.0, phase + advance(seconds));
                return;
            }
            if (!exiting && phase > .02) {
                // 上一份还没画实就变了：直接换新的一份，不必再退一遍场。
                apply(nextKey, line, text);
                return;
            }
            exiting = true;
            phase = Math.max(0.0, phase - advance(seconds));
            if (phase <= 0.0) apply(nextKey, line, text);
        }

        private void apply(String nextKey, LyricLine line, String text) {
            key = nextKey;
            snapshot = line;
            textSnapshot = text;
            exiting = false;
            phase = 0.0;
            hold = delaySeconds;
        }

        /** 这一帧距上一帧过了多少秒；夹一道，切后台回来不该补出一大段动画。 */
        private double tick() {
            long now = System.nanoTime();
            double seconds = (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;
            return Math.max(0.0, Math.min(.1, seconds));
        }

        /** 起步等待先吃掉帧时间，剩下的才按 150ms 一段换算成进度增量。 */
        private double advance(double seconds) {
            if (hold > 0) {
                double used = Math.min(hold, seconds);
                hold -= used;
                seconds -= used;
            }
            return seconds / DURATION_SECONDS;
        }

        /** 该画成什么样：0 全隐、1 全显。进出两条曲线都前冲，和 framer 的 {@code easeOut} 同形。 */
        private double progress() {
            double p = clamp01(phase);
            return exiting ? 1.0 - easeOut(1.0 - p) : easeOut(p);
        }

        private static double easeOut(double value) {
            double rest = 1.0 - value;
            return 1.0 - rest * rest * rest;
        }

        /** 第一行：整句或一串逐字的词，按 {@code overflow-hidden} 那格容器排布。 */
        private void draw(CFontRenderer font, double left, double width, double centerY, long time,
                          int active, int muted) {
            LyricLine line = snapshot;
            if (line == null) return;
            double p = progress();
            float alpha = (float) (getAlpha() * p);
            if (alpha <= .01f) return;
            double yTop = centerY - font.sizePx * .5 + (exiting ? -8.0 : 8.0) * (1.0 - p);

            List<LyricLine.Word> words = timedWords(line);
            if (words.isEmpty()) {
                String text = safe(line.lyric);
                double textW = font.getStringWidthD(text);
                double maxScroll = Math.max(0, textW - width);
                double ratio = progressOf(time, line.timestamp, line.duration);
                // 放得下居中；放不下靠左，再按整行进度把句子推过去。
                drawSwept(font, text,
                        maxScroll <= 0 ? left + (width - textW) * .5 : left - ratio * maxScroll,
                        yTop, alpha, ratio, active, muted);
                return;
            }

            double gap = font.sizePx * .25;   // {@code marginRight: .25em}
            int count = words.size();
            double[] xs = new double[count];
            double[] widths = new double[count];
            double total = 0;
            int activeWord = -1;
            for (int i = 0; i < count; i++) {
                LyricLine.Word word = words.get(i);
                xs[i] = total;
                widths[i] = font.getStringWidthD(safe(word.word));
                total += widths[i] + gap;
                if (time >= word.timestamp && time <= word.timestamp + Math.max(1L, word.duration)) {
                    activeWord = i;
                }
            }
            total -= gap;
            double maxScroll = Math.max(0, total - width);
            double scroll;
            if (maxScroll <= 0) scroll = 0;
            else if (activeWord >= 0) scroll = xs[activeWord] + widths[activeWord] * .5 - width * .5;
            else if (time < words.get(0).timestamp) scroll = 0;
            // 找不到「正在唱的字」时停在最右，这是 folia 那句 `let targetScroll = maxScroll`。
            else scroll = maxScroll;
            scroll = Math.max(0, Math.min(maxScroll, scroll));
            double origin = maxScroll > 0 ? left - scroll : left + (width - total) * .5;
            for (int i = 0; i < count; i++) {
                LyricLine.Word word = words.get(i);
                drawSwept(font, safe(word.word), origin + xs[i], yTop, alpha,
                        progressOf(time, word.timestamp, word.duration), active, muted);
            }
        }

        /** 第二行：整行居中、超长截断，没有逐字轨。 */
        private void drawText(CFontRenderer font, double left, double width, double centerY, int ink) {
            String text = textSnapshot;
            if (text == null || text.isEmpty()) return;
            double p = progress();
            float alpha = (float) (getAlpha() * p);
            if (alpha <= .01f) return;
            String trimmed = font.trim(text, width);
            font.drawString(trimmed, left + (width - font.getStringWidthD(trimmed)) * .5,
                    centerY - font.sizePx * .5 + (exiting ? -6.0 : 6.0) * (1.0 - p),
                    reAlpha(ink, alpha));
        }

        /**
         * 一段字按「已经唱到哪儿」切成蓝与灰两半，画在 {@code originX} 这个起点上。
         *
         * <p>居中与横向滚动都由调用方算完再传进来 —— 一个词自己居中会把整句挤成一串各自为政的
         * 小字，那正是上一版预览的行为，也是它对不上 folia 的原因。</p>
         *
         * <p>CSS 的硬停渐变可以把一个字劈成两半，这里以<b>字</b>为单位翻过去：14 像素的字里半个字
         * 的差别看不出来，而逐字劈要在每一帧多跑一整套字形测量。</p>
         */
        private void drawSwept(CFontRenderer font, String text, double originX, double yTop,
                               float alpha, double progress, int active, int muted) {
            if (text.isEmpty()) return;
            if (progress <= 0.0) {
                font.drawString(text, originX, yTop, reAlpha(muted, alpha));
                return;
            }
            if (progress >= 1.0) {
                font.drawString(text, originX, yTop, reAlpha(active, alpha));
                return;
            }
            double cutAt = font.getStringWidthD(text) * progress;
            int split = 0;
            for (int i = 1; i <= text.length(); i++) {
                if (font.getStringWidthD(text.substring(0, i)) > cutAt) break;
                split = i;
            }
            // 下一个字的中点已经越过切点，就把它整块算进「已唱完」那一侧。
            if (split < text.length()) {
                double headEnd = split == 0 ? 0 : font.getStringWidthD(text.substring(0, split));
                double nextEnd = font.getStringWidthD(text.substring(0, split + 1));
                if ((headEnd + nextEnd) * .5 <= cutAt) split++;
            }
            String head = text.substring(0, split);
            if (!head.isEmpty()) font.drawString(head, originX, yTop, reAlpha(active, alpha));
            String tail = text.substring(split);
            if (!tail.isEmpty()) {
                font.drawString(tail, originX + font.getStringWidthD(head), yTop, reAlpha(muted, alpha));
            }
        }
    }

    // ──────────────────────────── 歌词时间轴小工具 ────────────────────────────

    /**
     * 预览走的时钟：跟全屏歌词页同源（进度 + 用户的歌词偏移）。
     *
     * <p>但只在「预览的这首歌正是当前在放的」时才走。从歌单某一行打开、又或根本没在播放时，
     * 返回 0 让预览停在第一句——不然会给一首没在播的歌配上一首在播的词的进度，看着像是搜错了。</p>
     */
    private long previewClock() {
        Music playing = CloudMusic.currentlyPlaying;
        if (track == null || playing == null) return 0L;
        // 换源后正在出声的是替身（currentlyPlaying），弹窗绑的还是原曲那一行：
        // stableKey 对不上，再看换源配对键（与 isSelected 同一口径），预览时钟才不停在第一句。
        if (track != playing && !playing.isCrossSourcePeerOf(track)
                && !java.util.Objects.equals(track.getStableKey(), playing.getStableKey()))
            return 0L;
        CloudMusic.PlaybackSnapshot snapshot = CloudMusic.getSnapshot();
        if (snapshot.music == null) return 0L;
        return snapshot.positionMs + CloudMusic.getLyricOffsetMillis();
    }

    private static String lineKey(LyricLine line) {
        return line.timestamp + "|" + line.duration + "|" + safe(line.lyric);
    }

    /**
     * 这一时刻该显示哪两句：返回 {@code {当前行, 下一行}}，两者都可能为空。
     *
     * <p>照 {@code resolveDisplay} 的两级找法。第一级是 {@code findLatestActiveLineIndex}——从后
     * 往前找第一条「已经开始且还没结束」的行，下一行取它后面那条。第二级是两行之间有空隙时（第一级
     * 找不到结果）：仍然把<b>刚唱完的那一行</b>留在屏幕上，下一行取即将开始的那一条；只有整首还没
     * 开唱时当前行为空。</p>
     */
    static LyricLine[] resolveDisplay(List<LyricLine> lines, long time) {
        int active = latestActiveIndex(lines, time);
        if (active != -1) {
            return new LyricLine[]{lines.get(active),
                    active + 1 < lines.size() ? lines.get(active + 1) : null};
        }
        int upcoming = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) != null && lines.get(i).timestamp > time) {
                upcoming = i;
                break;
            }
        }
        int previous = upcoming == -1 ? lines.size() - 1 : upcoming - 1;
        return new LyricLine[]{previous >= 0 ? lines.get(previous) : null,
                upcoming == -1 ? null : lines.get(upcoming)};
    }

    /** {@code findLatestActiveLineIndex}：倒着找最后一条覆盖了这一时刻的行，没有就给 -1。 */
    static int latestActiveIndex(List<LyricLine> lines, long time) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            LyricLine line = lines.get(i);
            if (line == null || time < line.timestamp) continue;
            if (time <= line.timestamp + Math.max(0L, line.duration)) return i;
        }
        return -1;
    }

    /**
     * 第二行显示什么：{@code subLineText} 的那三目。
     *
     * <p>当前行自带翻译就显示翻译（这是「双语对照」）；没有翻译就提前把下一句摆出来当预告。
     * 整首还没开唱时（{@code current == null}）拿第一行套同一条规则，用户能在 0 秒看到歌词的开头，
     * 而不是等前奏放完才蹦出字来。</p>
     */
    static String subLineText(List<LyricLine> lines, LyricLine current, LyricLine next) {
        if (current != null) {
            if (notBlank(current.translationText)) return current.translationText;
            return next == null ? "" : safe(next.lyric);
        }
        LyricLine first = lines.isEmpty() ? null : lines.get(0);
        if (first != null && notBlank(first.translationText)) return first.translationText;
        if (lines.size() > 1 && lines.get(1) != null) return safe(lines.get(1).lyric);
        return "";
    }

    /** 第二行是不是翻译：决定它用 emerald 还是 zinc 那套字色。 */
    static boolean subIsTranslation(List<LyricLine> lines, LyricLine current) {
        if (current != null) return notBlank(current.translationText);
        LyricLine first = lines.isEmpty() ? null : lines.get(0);
        return first != null && notBlank(first.translationText);
    }

    private static List<LyricLine.Word> timedWords(LyricLine line) {
        if (line == null || line.words.isEmpty()) return Collections.emptyList();
        return line.words;
    }

    private static double progressOf(long time, long start, long duration) {
        if (duration <= 0L) return time >= start ? 1.0 : 0.0;
        return Math.max(0.0, Math.min(1.0, (time - start) / (double) duration));
    }

    private static boolean isWordByWord(List<LyricLine> lines) {
        for (LyricLine line : lines) {
            if (line != null && line.hasTimedWords()) return true;
        }
        return false;
    }

    private static boolean anyTranslation(List<LyricLine> lines) {
        for (LyricLine line : lines) {
            if (line != null && notBlank(line.translationText)) return true;
        }
        return false;
    }

    private static boolean anyRomanization(List<LyricLine> lines) {
        for (LyricLine line : lines) {
            if (line != null && notBlank(line.romanizationText)) return true;
        }
        return false;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
