package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.BrandVectorIcons;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.ScissorClipManager;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.core.rendering.ui.widgets.BrandIconWidget;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
// 图标码位表只有这一份：字体 party-icons.ttf 在 core 的资源里，FontManager 里的四档渲染器也在
// core，唯独码位常量落在 mpparty-common —— 那份源码目录被四个版本工程直接加进 main 源码集，
// 和 core 是同一次编译，所以这里 import 它不会引入任何构建依赖。宁可跨包 import，也不要把码位
// 表抄一份进 core：一份字体两张表，哪天改了一张就会静默画出另一枚图标。
import com.muoniumplayer.party.ui.PartyIcons;

import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 播放器二级浮层（音乐来源、账号与登录）的共用表现层。
 *
 * <p><b>为什么要有这一层。</b>这两个浮层原来各自把「遮罩 + 弹窗 + 标题 + 卡片 + 状态行」手写一遍，
 * 于是同一件事有两套写法、两套间距、两套配色推导；再加上一起听面板又是第三套，同一个播放器里
 * 三种卡片长得都不太一样。这个基类把版式与动效收成一份，两个浮层只写「有哪些内容」。</p>
 *
 * <p><b>坐标一律按当帧父组件尺寸算。</b>弹窗高度会被视口夹（{@code min(想要的高度, 视口 - 24)}），
 * 所以任何写死的 y 在 GUI 缩放开大、窗口变矮的时候都会跑到页脚上面去。这里提供的
 * {@link #body(Panel, DoubleSupplier, DoubleSupplier)} 是可滚 + 硬裁的内容区，
 * 页脚用 {@code host.getHeight() - N} 贴底，两边都不会互相压。</p>
 */
public abstract class NcmSheet extends NCMPanel {

    // ──────────────────────────── 尺寸与配色 ────────────────────────────

    /**
     * 图标方格四档。
     *
     * <p>只有这四档，数值必须和 {@code FontManager.partyIcon24/32/40/56} 实际画出来的方格
     * （12 / 16 / 20 / 28 逻辑像素）一致：字形是按字号栅格化的，绘制时再缩放会重采样发虚。
     * 更要紧的是这几个数还被当成"图标占多宽"参与排版（见 {@link #pill} 与 {@link #action}），
     * 填大了图标和它后面的文字之间就会多出一条说不清的空隙。</p>
     */
    protected static final double ICON_S = 12.0;
    protected static final double ICON_M = 16.0;
    protected static final double ICON_L = 20.0;
    protected static final double ICON_XL = 28.0;

    /** 弹窗内容到左右边缘的留白。 */
    protected static final double PAD = 20.0;

    /**
     * 卡片左右各预留的空隙，悬停放大就往这块空隙里长。
     *
     * <p>按百分比放大会溢出裁剪边界（圆角与描边被削平），而且宽窄面板上的位移量看着不一样。
     * 固定像素生长：长到头刚好填满预留。</p>
     */
    protected static final double CARD_INSET = 3.0;

    // ──────────────────────────── 分层到场时机 ────────────────────────────
    //
    // 全是「本页内容时钟走到第几成」这个无量纲数，不是毫秒：时长由 SheetClock 决定，这里只排先后。
    // 用同一个数排，退场时才会严格反序收回去 —— 各层自己计时的话，退场就是同时消失，白排。

    /** 第一张卡开始到场的时刻。页眉在它的左边一档，于是顺序是「底板 → 页眉 → 卡 → 页脚」。 */
    private static final double CARD_FIRST = .26;
    /** 相邻两张卡之间的间隔。 */
    private static final double CARD_STEP = .04;
    /** 单张卡从起闸到落位要走过多长一段。 */
    private static final double CARD_TRAVEL = .54;
    /** 超过这个序号的卡并到最后一档一起到场：长列表不能永远排在窗口外面。 */
    private static final int CARD_STAGGER_LIMIT = 12;

    private static final double HEADER_FIRST = .16;
    private static final double HEADER_TRAVEL = .48;
    private static final double FOOTER_FIRST = .50;

    /** 翻页时内容沿水平方向偏移这么多逻辑像素。只在场外存在，落位为零，不影响点击判定。 */
    private static final double PAGE_SLIDE_DISTANCE = 12.0;

    protected static final int OK_COLOR = 0x6EDAA0;
    protected static final int WARN_COLOR = 0xE0B268;
    protected static final int BAD_COLOR = 0xF1767D;
    /** 会员金。桂冠奖章与「VIP」字样共用，全项目只此一处。 */
    public static final int VIP_COLOR = 0xE8C071;

    // ──────────────────────────── 状态 ────────────────────────────

    protected volatile boolean closing;

    /**
     * 弹窗整体这一档的进度：遮罩暗下去、底板上浮起来都读它。
     *
     * <p>由 {@link #onRender} 每帧从 {@link #clock} 写一次，所以 {@code sheet()} 里那些回调只是
     * 读数 —— 谁都不许再自己 {@code interpolate} 一遍，两条曲线叠在一起就是「先窜后糊」。</p>
     */
    protected double presentation;
    /** 弹窗高度自身也补间：换页时高度不同，直接跳会「咔」一下。 */
    private double sheetHeight;
    /**
     * 当前<b>这一页</b>内容的入场进度（未经缓动的原始值，各层在它上面开时间窗）。
     *
     * <p>和 {@link #presentation} 分两条是有原因的：这一层第一次建起来时两条一起从 0 走，之后
     * 每翻一页只重跑 {@link #page} 那一条。合成一条的话，点进一级账号详情就连遮罩和底板都要
     * 重新淡一遍，看着像整个界面又开了一次。</p>
     */
    protected double pageFade;

    /**
     * 这一层进出场的时钟。
     *
     * <p>必须和 {@link #page} 同一档时长：{@link SheetHeroRelay} 的不透明度是「1 − 落点那张卡的
     * {@code reveal}」，两条互补。各走各的秒数会让接力先收手、卡片后到场，中间那一帧什么都没有。</p>
     */
    private final SheetClock clock = SheetClock.cascade();
    /** 当前页内容那一条：换页重新走，进出场跟着整层一起走。 */
    private final SheetClock page = SheetClock.cascade();
    /** 内容往哪一侧滑进来：-1 回翻、+1 前进、0 第一次建页（那就只做纵向落位，不给水平位移）。 */
    private int pageDirection;
    /** 由 {@link #back(Runnable)} 立起、{@link #beginPage()} 读完就清的换页方向。 */
    private boolean nextPageBackward;

    /**
     * 什么时候可以回收这一层：退场播完，而不是「用户已经点了关闭」。
     *
     * <p>关闭请求和真正回收之间隔着这一段动画，所以 {@link #closing} 不能直接当返回值 ——
     * 那样 {@code NCMScreen} 下一帧就把这一层抹掉，退场动画一帧都播不出来。</p>
     */
    public boolean shouldClose() {
        return clock.isGone();
    }

    /** 已经收到关闭请求、正在退场。吃掉输入那条规则在 {@link NCMPanel} 里。 */
    @Override
    protected boolean isDismissing() {
        return closing;
    }

    public void dispose() {
        closing = true;
        clock.requestDismiss();
        page.requestDismiss();
    }

    /**
     * 把一趟「往回翻」包成 {@code Runnable}，交给 {@code subHeader()} / {@code backButton()} 那类返回键。
     *
     * <p>为什么是一个一次性标记而不是给每个 {@code showXxx()} 加一个 boolean 参数：同一批页面里
     * {@code showDetail(platform)} 既是从总览点进去（前进）、也是从 Cookie 页退回来（后退），
     * 传参就得给每条调用都改一遍签名 —— {@code AccountManagerOverlay} 里有七处。而「谁按下返回键
     * 谁知道方向」这个信息本来就在调用点上。{@code NCMScreen} 的 {@code pendingNavigationBackward}
     * 用的是同一个套路。</p>
     */
    protected Runnable back(final Runnable action) {
        return () -> {
            nextPageBackward = true;
            action.run();
        };
    }

    /** 换页：清空子组件，只把内容那一档重新走一遍。所有 {@code showXxx()} 的第一行都该调它。 */
    protected void beginPage() {
        if (closing) return;
        pageDirection = nextPageBackward ? -1 : 1;
        nextPageBackward = false;
        page.restart();
        // 落点卡跟着旧页一起被抛弃了：置空等总览页重建时重新认领，滚入的一次性标记一并重算。
        // 不置空的话 afterRenderChildren 还在读旧卡的坐标 —— 那棵子树已经没人排版了。
        heroPlate = null;
        heroScrolledIntoView = false;
        getChildren().clear();
    }

    /**
     * 每帧把两条时钟各推进一次。
     *
     * <p>放在 {@code onRender} 而不是 {@code sheet()} 的回调里：{@code renderWidget} 的顺序是
     * 宿主回调 → 宿主 onRender → 子组件，所以在这一格里写的值，同一帧里所有子组件都读得到。
     * 更要紧的是<b>自己建弹窗骨架</b>的那一层（{@code LyricSearchOverlay} 不走 {@code sheet()}）
     * 也照样在走钟 —— 退场进度没人推的话，那一层就永远回收不掉。</p>
     */
    @Override
    public void onRender(double mouseX, double mouseY) {
        super.onRender(mouseX, mouseY);
        presentation = TransitionCurveCache.MOTION.at(clock.tick());
        pageFade = page.tick();
        // 赶在滚动区排版之前：这一帧设进去的偏移，本帧 alignChildren 就会带上。
        scrollHeroIntoViewIfNeeded();
    }

    // ──────────────────────────── 弹窗骨架 ────────────────────────────

    /**
     * 遮罩 + 弹窗 + 圆角底 + 顶部高光，返回可以往里塞内容的弹窗面板。
     *
     * <p>点遮罩关闭；点弹窗本体不关闭（{@code setOnClickCallback} 直接吃掉）。</p>
     *
     * <p>这里<b>不</b>重置任何进度：进出场那一条走 {@link #clock}，翻页那一条走 {@link #page}，
     * 都由 {@link #onRender} 推。连弹窗高度也不重置 —— 重置就等于每次点进一级都从 0 撑开，
     * 而那正是「高度变化直接跳会咔一下」这句话要避免的东西。让它从上一版的高度补间过去。</p>
     */
    protected Panel sheet(final double preferredWidth, final DoubleSupplier preferredHeight) {
        final RectWidget mask = new RectWidget();
        addChild(mask);
        mask.setClickable(false);
        mask.setColor(0x000000);
        mask.setBeforeRenderCallback(() -> {
            mask.setMargin(0);
            mask.setAlpha((float) (presentation * .52));
        });
        setOnClickCallback((x, y, button) -> {
            if (button == 0) dispose();
            return button == 0;
        });

        final Panel dialog = new Panel();
        addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            double width = Math.max(1, Math.min(preferredWidth, getWidth() - 24));
            double target = Math.max(1, Math.min(preferredHeight.getAsDouble(), getHeight() - 24));
            sheetHeight = sheetHeight <= 0.0 ? target : Interpolations.interpolate(sheetHeight, target, .22f);
            dialog.setBounds(width, sheetHeight);
            dialog.setAlpha((float) presentation);
            dialog.setPosition(getWidth() * .5 - width * .5,
                    getHeight() * .5 - sheetHeight * .5 + (1.0 - presentation) * 14.0);
        });

        final RoundedRectWidget background = new RoundedRectWidget();
        dialog.addChild(background);
        background.setClickable(false).setRadius(14).setBeforeRenderCallback(() -> {
            background.setMargin(0);
            background.setColor(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND));
            // 底板自己那层深色要压薄，否则它把刚贴上去的糊背景整个盖死——那正是
            // 2026-09-30「二级菜单没有液态玻璃」的后半段：接了背景，但背景在底色底下。
            background.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        });
        // 七张二级菜单（音乐来源、账号管理、公告、字体、歌词检索、新建与导入歌单）都从这里长出来，
        // 所以玻璃接在这一处就全覆盖。走 SHEET 而不是 PLATE：弹窗背后是我们自己的面板加一层遮罩，
        // 这两样都不在背景采集里，所以贴真背景时要按遮罩那一档压暗（见 GlassLook.paintSheetFrost）——
        // 不压就是「在压暗的桌面上开一扇透着大晴天的窗」，压过头又变回一块不透明的板。
        background.setGlassSheet(GlassLook.Surface.PLAYER);

        // 顶部一道极淡的白，把标题那一段轻轻托起来。没有它整块弹窗是一片死板的纯色。
        final Wash gloss = new Wash(false, 14.0);
        background.addChild(gloss);
        gloss.tint(0xFFFFFF, 0xFFFFFF).alphas(.055, 0.0);
        gloss.setBeforeRenderCallback(() -> {
            gloss.setBounds(Math.max(1, background.getWidth()), Math.min(96, background.getHeight()));
            gloss.setPosition(0, 0);
        });

        stroke(background, 14.0, () -> NCMScreen.getColor(NCMScreen.ColorType.BORDER), () -> .55);
        return dialog;
    }

    /**
     * 内容区：一块<b>硬裁</b>的面板，内容画不下就被切，绝不盖到页脚上。
     *
     * <p>{@code bottomReserve} 是这一页页脚（按钮排 / 说明行）占掉的高度。裁剪是兜底而不是布局
     * 手段 —— 定高卡片在极矮视口下只能被切，但宁可被切，也不能压在底部说明和按钮上面：前者一眼
     * 看得出「这里放不下」，后者看起来是界面坏了。</p>
     */
    protected Panel body(final Panel dialog, final DoubleSupplier top, final DoubleSupplier bottomReserve) {
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
        dialog.addChild(panel);
        panel.setBeforeRenderCallback(() -> {
            double y = top.getAsDouble();
            panel.setBounds(Math.max(1, dialog.getWidth()),
                    Math.max(1, dialog.getHeight() - y - bottomReserve.getAsDouble()));
            panel.setPosition(0, y);
        });
        return panel;
    }

    /** 内容区里的可滚列表。宽度铺满内容区（含 {@link #PAD} 留白由子项自己让出）。 */
    protected ScrollPanel scroller(final Panel body) {
        final ScrollPanel list = new ScrollPanel();
        body.addChild(list);
        list.setSpacing(10).setScrollStrength(36)
                .setAlignment(ScrollPanel.Alignment.VERTICAL_WITH_HORIZONTAL_FILL)
                .setBeforeRenderCallback(() -> {
                    list.setBounds(Math.max(1, body.getWidth() - PAD * 2), Math.max(1, body.getHeight()));
                    list.setPosition(PAD, 0);
                });
        return list;
    }

    /**
     * 整页滚动区：宽度与内容区（即弹窗）等宽。
     *
     * <p>和 {@link #scroller(Panel)} 的区别是不自己让出 {@link #PAD}。行宽等于弹窗宽度，
     * {@link #card} 那套「按宿主宽度推算左右留白」的写法才能原样用在行里。
     * 整页能滚是刚需：GUI 缩放开到 3、4 时可用高度只剩两三百逻辑像素，不能滚
     * 就只能裁，而被裁掉的那几项用户永远点不到。</p>
     */
    protected ScrollPanel feed(final Panel body) {
        final ScrollPanel list = new ScrollPanel();
        body.addChild(list);
        list.setSpacing(0);
        list.setScrollStrength(34);
        list.setAlignment(ScrollPanel.Alignment.VERTICAL);
        // 这一格宽度就等于弹窗宽度，外侧没有页面留白可住：指示条必须回到视口内侧，
        // 否则会贴住弹窗圆角、甚至画到弹窗外面去。
        list.setIndicatorInsideViewport();
        list.setBeforeRenderCallback(() -> {
            list.setBounds(Math.max(1, body.getWidth()), Math.max(1, body.getHeight()));
            list.setPosition(0, 0);
        });
        return list;
    }

    /** {@link #feed} 里的一行。纵向位置由滚动区排，这里只管宽高。 */
    protected Panel strip(final ScrollPanel list, final DoubleSupplier height) {
        final Panel row = new Panel();
        list.addChild(row);
        row.setBeforeRenderCallback(() -> row.setBounds(Math.max(1, list.getWidth()),
                Math.max(1, height.getAsDouble())));
        return row;
    }

    protected Panel strip(final ScrollPanel list, final double height) {
        return strip(list, () -> height);
    }

    /**
     * 铺满宿主的一层透明容器，整块控透明度。
     *
     * <p>给「要么整组出现、要么整组不出现」的东西用（选中徽标、二维码占位图）。
     * 不能靠里面每一件各自改颜色：{@link #pill} 这类组件自己已经占了
     * {@code beforeRenderCallback}，调用方再挂一个会把它的排版覆盖掉。</p>
     */
    protected Panel veil(final AbstractWidget<?> host, final DoubleSupplier alpha) {
        final Panel layer = new Panel();
        host.addChild(layer);
        layer.setClickable(false);
        layer.setBeforeRenderCallback(() -> {
            layer.setBounds(Math.max(1, host.getWidth()), Math.max(1, host.getHeight()));
            layer.setPosition(0, 0);
            layer.setAlpha((float) clamp01(alpha.getAsDouble()));
        });
        return layer;
    }

    // ──────────────────────────── 卡片 ────────────────────────────

    /**
     * 一张卡片。
     *
     * <p>{@code slot} 决定入场顺序（0 最先）。悬停时往两侧各长 {@link #CARD_INSET} 像素 ——
     * 位移比换色更能说明「这一块是可以动手的」。</p>
     */
    protected Card card(final AbstractWidget<?> host, final int slot, final DoubleSupplier top,
                        final DoubleSupplier height, final IntSupplier color) {
        final Card bg = new Card();
        host.addChild(bg);
        bg.setRadius(12);
        bg.setGlassRim(GlassLook.Surface.PLAYER);
        bg.setBeforeRenderCallback(() -> {
            bg.reveal = cardGate(slot);
            bg.setBounds(Math.max(1, host.getWidth() - (PAD + CARD_INSET) * 2), Math.max(1, height.getAsDouble()));
            // 入场时从下方 7 像素处落上来，再沿翻页方向偏一档；稳定后位移为零，就是 top/left 本身。
            bg.setPosition(PAD + CARD_INSET + pageSlide() * (1.0 - bg.reveal),
                    top.getAsDouble() + (1.0 - bg.reveal) * 7.0);
            bg.setColor(color.getAsInt());
            // 卡落在弹窗那块玻璃里面：只上自己那道边缘光，底色压薄。给它再贴一层真背景，
            // 等于在同一处风景上再开一扇窗，读起来是「这块没上色」而不是「这是一块玻璃」。
            bg.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            bg.setAlpha((float) bg.reveal);
        });
        bg.setTransformations(() -> {
            double w = Math.max(1, bg.getWidth());
            bg.scaleAtPos(bg.getX() + w * .5, bg.getY() + bg.getHeight() * .5,
                    1.0 + (CARD_INSET * 2 / w) * bg.hover);
        });
        return bg;
    }

    /**
     * 第 {@code slot} 张卡这一帧该有多实。
     *
     * <p>窗口在 {@link #pageFade} 上按序号平移，长度都一样 —— 于是这是一条<b>等间距的瀑布</b>，
     * 而不是越往后越慢。超过 {@link #CARD_STAGGER_LIMIT} 的序号并到最后一档一起到场：
     * 宁可少一点错峰，也不能让长列表底部的卡片永远排在窗口外面（旧的 {@code pageFade} 只会渐近
     * 到 1，第十四张之后的卡就是这么被永久留在透明状态的，公告页当年得专门写个钳位绕开它）。</p>
     */
    protected double cardGate(final int slot) {
        double from = CARD_FIRST + Math.min(slot, CARD_STAGGER_LIMIT) * CARD_STEP;
        // 窗口右端必须钉在 1.0 以内。第 12 档的 from 是 .74，加上 .54 的行程就是 1.28，
        // 而 pageFade 最多只走到 1.0 —— 那一档就永久停在 MOTION(.48)≈87% 不透明度、
        // 低 0.9 像素的位置上，正是上面这段注释承诺要避免的事。以前整条瀑布只有 153ms，
        // 没人看得见这个尾巴；时长放开到 383ms 之后它就藏不住了。
        double to = Math.min(from + CARD_TRAVEL, 1.0);
        return TransitionCurveCache.MOTION.at(SheetClock.window(pageFade, from, to));
    }

    /** 页眉那一档：比底板晚到场，比第一张卡更早。 */
    protected double headerGate() {
        return TransitionCurveCache.MOTION.at(SheetClock.window(pageFade, HEADER_FIRST, HEADER_TRAVEL));
    }

    /** 页脚那一档（说明行、主操作按钮）：整页最后落位的东西。 */
    protected double footerGate() {
        return TransitionCurveCache.MOTION.at(SheetClock.window(pageFade, FOOTER_FIRST, 1.0));
    }

    /** 翻页给内容那一级带来的水平位移；第一次建页时为零，只做纵向落位。 */
    protected double pageSlide() {
        return pageDirection * PAGE_SLIDE_DISTANCE;
    }

    /**
     * 关掉悬停生长。纯展示的卡片必须调一下：会跟着鼠标长大的卡片看着就像能点。
     *
     * <p>还有一层副作用：放大是绕着卡片中心做的，滚动区第一行的卡片一长就会越过
     * 滚动区上边界、被硬裁切掉半像素的圆角。</p>
     */
    protected Card plain(final Card card) {
        card.setClickable(false);
        card.setTransformations(null);
        return card;
    }

    // ──────────────────────────── 触发点接力 ────────────────────────────

    /** 触发它的那颗按钮交出来的起点；没有就是这一层不是从某个控件点开的，接力整趟放弃。 */
    private SheetHeroRelay.Origin heroOrigin;
    /** 这块飞行板要落进的那张卡，由 {@link #hero(Card)} 标出来。 */
    private Card heroPlate;
    /** 本层打开后是否已经把落点卡滚进过视口：只做一次，之后用户滚到哪儿都不再拽。 */
    private boolean heroScrolledIntoView;

    /**
     * 认领一次接力。<b>必须在 {@code onInit()} 之前调</b> —— 那之后 {@code sheet()} 已经建好了，
     * 起点就没有地方可去了。
     */
    public void claimHero(final SheetHeroRelay.Origin from) {
        if (from != null && this.heroOrigin == null) this.heroOrigin = from;
    }

    /** 标出接力板要落进的那张卡，原样返回它，所以能直接套在 {@code card(...)} 外面写。 */
    protected <T extends Card> T hero(final T plate) {
        this.heroPlate = plate;
        return plate;
    }

    /**
     * 落点卡所在的滚动视口：卡片 ← 行 ← 滚动区。行与卡片都是各页自排的，中途层级可能变，
     * 所以每帧现走父链找；找不到（落点不在滚动区里）返回 {@code null} —— 那一侧视为不被裁剪。
     */
    private ScrollPanel heroViewport() {
        for (AbstractWidget<?> at = heroPlate.getParent(); at != null; at = at.getParent()) {
            if (at instanceof ScrollPanel) return (ScrollPanel) at;
        }
        return null;
    }

    /**
     * 把落点卡滚进视口：打开后的头两帧做一次、只做一次。
     *
     * <p>为什么要滚：当前来源排在列表下方时（YouTube 往后），落点卡在打开那一刻还在视口下沿
     * 之外 —— 接力板的落点坐标每帧现读，板子就朝着屏幕外飞，中途被底板 stencil 齐根切平。
     * 滚进视口之后落点有了诚实的坐标，接力照常起飞。</p>
     *
     * <p>由 {@link #feed} 的滚动区在每帧排版<b>之前</b>调：滚动偏移必须赶在 {@code alignChildren}
     * 之前设进去，本帧的行坐标才带上这一滚，接力第二帧就能朝真目标飞。需要的偏移按落点行<b>前面
     * 各行的累计高度</b>现算，而不是读卡片当前的绝对坐标 —— 建树后的第一帧所有行都还没量出尺寸、
     * 全堆在顶部，那个坐标是错的；累计高度只依赖行高，第一帧过完就是真值。</p>
     */
    private void scrollHeroIntoViewIfNeeded() {
        if (heroScrolledIntoView || heroPlate == null) return;
        final ScrollPanel viewport = heroViewport();
        if (viewport == null) {
            heroScrolledIntoView = true;
            return;
        }
        final AbstractWidget<?> row = heroPlate.getParent();
        if (row == viewport) {
            heroScrolledIntoView = true;
            return;
        }
        // 行高还没量出来（建树后第一帧）就不动：这时算了也是错值，下一帧再算。
        if (row.getHeight() <= 1.0) return;
        double above = 0.0;
        boolean found = false;
        for (final AbstractWidget<?> child : viewport.getChildren()) {
            if (child == row) {
                found = true;
                break;
            }
            if (child.isHidden()) continue;
            above += child.getHeight() + viewport.getSpacing();
        }
        if (!found) return;
        heroScrolledIntoView = true;
        // 最小位移：够到视口下沿就行，不故意把卡片顶到视口顶部去。留出容差，
        // 卡片入场时还要从下方 7 像素落上来，别让入场位被裁剪沿切掉。
        final double bottom = above + row.getHeight() + SheetHeroRelay.VISIBILITY_TOLERANCE;
        if (bottom > viewport.getHeight()) {
            viewport.scrollToOffsetImmediately(bottom - viewport.getHeight());
            return;
        }
        final double top = above - SheetHeroRelay.VISIBILITY_TOLERANCE;
        if (top < 0.0) viewport.scrollToOffsetImmediately(top);
    }

    /**
     * 起飞前的最后一道闸：落点卡必须落在它自己的滚动视口里。飞行板画在整棵控件树之外，只被
     * 浮层底板那一刀 stencil 罩着；卡片被用户滚出视口后，它的绝对矩形越过了视口下沿 —— 照它飞
     * 就是朝着屏幕外飞，中途被 stencil 切平。这时候整趟收手，让卡片自己那格淡入顶上，跟
     * {@link PlaylistCoverHero} 的「落点不存在」是同一条退路。
     */
    private boolean heroLandingOutsideViewport() {
        final ScrollPanel viewport = heroViewport();
        return viewport != null && !SheetHeroRelay.fullyInside(viewport, heroPlate.getX(), heroPlate.getY(),
                heroPlate.getWidth(), heroPlate.getHeight());
    }

    /**
     * 飞行板画在<b>所有子组件之后</b>：它起飞时要压过自己那层遮罩（那块按钮本来就在遮罩底下），
     * 落位时又要盖在自己那张卡的底板上，直到卡片自己淡满才交棒。
     *
     * <p>{@code heroOrigin} 不清空：回程还得用它。进度走到 1 时 {@link SheetHeroRelay#render}
     * 自己就不画了，所以翻页那一档（底板已经停在全亮）不会把它重新放出来。</p>
     */
    @Override
    protected void afterRenderChildren(double mouseX, double mouseY) {
        super.afterRenderChildren(mouseX, mouseY);
        if (heroOrigin == null || heroPlate == null) return;
        if (heroLandingOutsideViewport()) return;
        SheetHeroRelay.render(heroOrigin, heroPlate.getX(), heroPlate.getY(),
                heroPlate.getWidth(), heroPlate.getHeight(), heroPlate.getRadius(),
                presentation, heroPlate.reveal);
    }

    /** 铺满宿主的一圈描边。用来标「这一块是当前选中的」，比整块换色轻。 */
    protected void stroke(final AbstractWidget<?> on, final double radius,
                          final IntSupplier color, final DoubleSupplier alpha) {
        final Stroke outline = new Stroke();
        on.addChild(outline);
        outline.radius = radius;
        outline.setClickable(false).setBeforeRenderCallback(() -> {
            outline.setBounds(Math.max(1, on.getWidth()), Math.max(1, on.getHeight()));
            outline.setPosition(0, 0);
            outline.setColor(color.getAsInt());
            outline.setAlpha((float) clamp01(alpha.getAsDouble()));
        });
    }

    /** 一层渐变面。给重点区域垫极淡的主色，别让整块弹窗只有一种底色。 */
    protected Wash wash(final AbstractWidget<?> on, final boolean horizontal, final double radius) {
        final Wash layer = new Wash(horizontal, radius);
        on.addChild(layer);
        return layer;
    }

    // ──────────────────────────── 胶囊徽标 ────────────────────────────

    /**
     * 右对齐的状态胶囊：图标 + 一小行字，底色由前景色朝页面底色混出来。
     *
     * <p>右对齐而不是定宽：「已登录 / 未登录 / 已选用」字数不同，定宽会让短的那个中间空一块。
     * 右边缘对齐之后，一列胶囊的右侧永远在同一条竖线上。</p>
     *
     * @return 胶囊本体，调用方可以读它的宽度给旁边的文字让位
     */
    protected RoundedRectWidget pill(final AbstractWidget<?> host, final Supplier<String> glyph,
                                     final Supplier<String> text, final IntSupplier ink,
                                     final DoubleSupplier rightX, final DoubleSupplier centerY) {
        final double height = 19.0;
        final RoundedRectWidget plate = new RoundedRectWidget();
        host.addChild(plate);
        plate.setClickable(false).setRadius(height * .5);
        // 胶囊落在弹窗那块玻璃<em>里面</em>：再给它贴一层真背景，等于在同一处风景上再开一扇窗，
        // 形状完全重合，读起来是「这块没上色」。所以它只上自己那道边缘光。
        plate.setGlassRim(GlassLook.Surface.PLAYER);
        // [0] 图标中心 x、[1] 文字左边缘，都是相对胶囊自身的坐标。
        final double[] slot = new double[2];
        plate.setBeforeRenderCallback(() -> {
            String mark = glyph == null ? "" : glyph.get();
            String caption = text.get() == null ? "" : text.get();
            double iconWidth = mark.isEmpty() ? 0.0 : ICON_S;
            double gap = (mark.isEmpty() || caption.isEmpty()) ? 0.0 : 4.0;
            double width = 8.0 + iconWidth + gap + FontManager.pf10bold.getStringWidthD(caption) + 8.0;
            plate.setBounds(width, height);
            plate.setPosition(rightX.getAsDouble() - width, centerY.getAsDouble() - height * .5);
            plate.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND), ink.getAsInt(), .26));
            slot[0] = 8.0 + iconWidth * .5;
            slot[1] = 8.0 + iconWidth + gap;
        });
        if (glyph != null) {
            icon(plate, glyph, ICON_S, () -> slot[0], () -> height * .5, ink);
        }
        final LabelWidget caption = label(plate, text, FontManager.pf10bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(ink.getAsInt());
            caption.setPosition(slot[1], inkTop(caption, height * .5));
        });
        return plate;
    }

    // ──────────────────────────── 页眉 / 页脚 ────────────────────────────

    /** 一级页的页眉：左侧图标盘 + 标题 + 副标题。 */
    protected void header(final Panel dialog, final String glyph, final String titleText,
                          final Supplier<String> subtitleText, final IntSupplier accent) {
        // 整组套一层只读进度的容器：页眉必须比底板早、比第一张卡更早就落位，
        // 一件件给 alpha 是做不到「一组同时」的，而标题和它的图标盘显然该同时。
        final Panel crown = veil(dialog, this::headerGate);

        final RoundedRectWidget plate = new RoundedRectWidget();
        crown.addChild(plate);
        plate.setClickable(false).setRadius(11).setBeforeRenderCallback(() -> {
            plate.setBounds(34, 34);
            plate.setPosition(PAD, 18);
            plate.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND), accent.getAsInt(), .28));
        });
        plate.setGlassRim(GlassLook.Surface.PLAYER);
        // 字形必须留在 plate 底下：那两个 17 是「34 见方的盘子的中心」，换成弹窗坐标系就跑到盘子外面去了。
        icon(plate, glyph, ICON_M, () -> 17.0, () -> 17.0, accent);

        final LabelWidget title = label(crown, () -> titleText, FontManager.pf18bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(PAD + 45, inkTop(title, 28.0));
        });

        final LabelWidget subtitle = label(crown, subtitleText, FontManager.pf12);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - PAD * 2 - 45));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(PAD + 45, inkTop(subtitle, 44.0));
        });
    }

    /** 二级页的页眉：返回键 + 标题 + 副标题。标题左边缘让给返回键。 */
    protected void subHeader(final Panel dialog, final Runnable back, final Supplier<String> titleText,
                             final Supplier<String> subtitleText, final IntSupplier accent) {
        // 返回键不套进 veil：那一层是不吃点击的，把它留在弹窗上，按钮自己跟着底板淡入。
        backButton(dialog, back);
        final Panel crown = veil(dialog, this::headerGate);

        final LabelWidget title = label(crown, titleText, FontManager.pf18bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(accent.getAsInt());
            title.setMaxWidth(Math.max(1, dialog.getWidth() - PAD - 52 - PAD));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(PAD + 40, inkTop(title, 28.0));
        });

        final LabelWidget subtitle = label(crown, subtitleText, FontManager.pf12);
        subtitle.setBeforeRenderCallback(() -> {
            subtitle.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            subtitle.setMaxWidth(Math.max(1, dialog.getWidth() - PAD * 2 - 40));
            subtitle.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            subtitle.setPosition(PAD + 40, inkTop(subtitle, 44.0));
        });
    }

    protected RoundedButtonWidget backButton(final Panel dialog, final Runnable action) {
        final RoundedButtonWidget back = new RoundedButtonWidget("", FontManager.pf12bold);
        dialog.addChild(back);
        back.setRadius(9);
        back.setShouldOverrideMouseCursor(true);
        back.setGlassRim(GlassLook.Surface.PLAYER);
        back.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
        back.setBeforeRenderCallback(() -> {
            back.setBounds(30, 30);
            back.setPosition(PAD - 4, 20);
            back.setColor(back.isHovering() ? NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_HOVER)
                    : NCMScreen.getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));
            back.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
            back.setTextColor(0x00FFFFFF);
        });
        icon(back, PartyIcons.CHEVRON_LEFT, ICON_M, () -> 15.0, () -> 15.0,
                () -> NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        return back;
    }

    /** 分组小标题。左边一小段主色竖条，比一行灰字更容易把下面那几张卡认成一组。 */
    protected void sectionLabel(final AbstractWidget<?> host, final String text,
                                final DoubleSupplier top, final IntSupplier accent) {
        final RoundedRectWidget tick = new RoundedRectWidget();
        host.addChild(tick);
        tick.setClickable(false).setRadius(1.5).setBeforeRenderCallback(() -> {
            tick.setBounds(3, 11);
            tick.setPosition(PAD + CARD_INSET, top.getAsDouble() + 1);
            tick.setColor(accent.getAsInt());
            tick.setAlpha(.85f);
        });

        final LabelWidget caption = label(host, () -> text, FontManager.pf12bold);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
            caption.setPosition(PAD + CARD_INSET + 9, inkTop(caption, top.getAsDouble() + 6.5));
        });
    }

    /** 页脚一句说明，前面一枚信息圆。纯一行灰字很容易被当成上面列表的一部分。 */
    protected void tip(final AbstractWidget<?> host, final Supplier<String> text,
                       final IntSupplier color, final DoubleSupplier top) {
        tip(host, () -> PartyIcons.INFO, text, color, top);
    }

    /** 同上，但图标自定。状态行用它：成功 / 失败 / 等待三种语气各有自己的图标。 */
    protected void tip(final AbstractWidget<?> host, final Supplier<String> glyph, final Supplier<String> text,
                       final IntSupplier color, final DoubleSupplier top) {
        icon(host, glyph, ICON_S, () -> PAD + ICON_S * .5, () -> top.getAsDouble() + 6.0, color);

        final LabelWidget caption = label(host, text, FontManager.pf12);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(color.getAsInt());
            caption.setMaxWidth(Math.max(1, host.getWidth() - PAD * 2 - ICON_S - 5.0));
            caption.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            caption.setPosition(PAD + ICON_S + 5.0, inkTop(caption, top.getAsDouble() + 6.0));
        });
    }

    protected void divider(final Panel dialog, final DoubleSupplier y) {
        final RectWidget line = new RectWidget();
        dialog.addChild(line);
        line.setClickable(false).setBeforeRenderCallback(() -> {
            line.setBounds(Math.max(1, dialog.getWidth() - PAD * 2), 1);
            line.setPosition(PAD, y.getAsDouble());
            line.setColor(NCMScreen.getColor(NCMScreen.ColorType.BORDER));
            line.setAlpha(.5f);
        });
    }

    // ──────────────────────────── 按钮 ────────────────────────────

    /**
     * 主操作按钮：图标 + 文字整组水平居中。
     *
     * <p>整组居中而不是「图标定死在左、文字居中」：后者在文案长短变化时（"开始扫码登录" →
     * "等待扫码…"）图标和文字之间的空隙会忽大忽小。</p>
     */
    protected RoundedButtonWidget action(final AbstractWidget<?> host, final Supplier<String> glyph,
                                         final Supplier<String> text, final CFontRenderer font,
                                         final IntSupplier ink, final Runnable onClick) {
        // 内置标题留空：它只会把文字居中到整颗按钮中间，而这里要居中的是“图标 + 文字”整组。
        final RoundedButtonWidget button = new RoundedButtonWidget("", font);
        host.addChild(button);
        button.setRadius(9);
        // 弹窗里的这颗也是玻璃面：文字是它的子控件，边缘光不会把它一起淡掉。
        button.setGlassRim(GlassLook.Surface.PLAYER);
        button.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1.0f));
        button.setShouldOverrideMouseCursor(true);
        button.setOnClickCallback((x, y, mouseButton) -> {
            if (mouseButton != 0) return false;
            onClick.run();
            return true;
        });
        // [0] 图标中心 x、[1] 文字左边缘。
        final double[] slot = new double[2];
        final RoundedRectWidget arrange = new RoundedRectWidget();
        button.addChild(arrange);
        arrange.setClickable(false).setBeforeRenderCallback(() -> {
            arrange.setBounds(0, 0);
            arrange.setAlpha(0f);
            String mark = glyph == null ? "" : glyph.get();
            double iconWidth = mark.isEmpty() ? 0.0 : ICON_S;
            double gap = mark.isEmpty() ? 0.0 : 5.0;
            double total = iconWidth + gap + font.getStringWidthD(text.get() == null ? "" : text.get());
            double left = (button.getWidth() - total) * .5;
            slot[0] = left + iconWidth * .5;
            slot[1] = left + iconWidth + gap;
        });
        if (glyph != null) {
            icon(button, glyph, ICON_S, () -> slot[0], () -> button.getHeight() * .5, ink);
        }
        // 前景色必须由调用方给：{@code setTextColor} 只能改到那枚空的内置标题上，
        // 而 {@link AbstractWidget} 的默认颜色是黑色 —— 不设就是一行黑字。
        final LabelWidget caption = label(button, text, font);
        caption.setBeforeRenderCallback(() -> {
            caption.setColor(ink.getAsInt());
            caption.setPosition(slot[1], inkTop(caption, button.getHeight() * .5));
        });
        return button;
    }

    /** 开关：一条轨道加一颗滑块，比「开 / 关」两个字一眼就能读出状态。 */
    protected void toggle(final AbstractWidget<?> host, final BooleanSupplier state, final Runnable flip,
                          final DoubleSupplier rightX, final DoubleSupplier centerY, final IntSupplier accent) {
        final double width = 34.0;
        final double height = 18.0;
        final RoundedRectWidget track = new RoundedRectWidget();
        host.addChild(track);
        track.setRadius(height * .5);
        track.setShouldOverrideMouseCursor(true);
        track.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            flip.run();
            return true;
        });
        // 滑块位置补间：直接跳会让「点了一下」这件事没有反馈。
        final double[] knob = {state.getAsBoolean() ? 1.0 : 0.0};
        track.setBeforeRenderCallback(() -> {
            knob[0] = Interpolations.interpolate(knob[0], state.getAsBoolean() ? 1.0 : 0.0, .28f);
            track.setBounds(width, height);
            track.setPosition(rightX.getAsDouble() - width, centerY.getAsDouble() - height * .5);
            track.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND),
                    accent.getAsInt(), knob[0] * (track.isHovering() ? 1.0 : .88)));
        });

        final RoundedRectWidget dot = new RoundedRectWidget();
        track.addChild(dot);
        dot.setClickable(false).setBeforeRenderCallback(() -> {
            double size = height - 5.0;
            dot.setRadius(size * .5);
            dot.setBounds(size, size);
            dot.setPosition(2.5 + (width - size - 5.0) * knob[0], 2.5);
            dot.setColor(0xFFFFFF);
            dot.setAlpha(knob[0] > .5 ? 1f : .82f);
        });
    }

    // ──────────────────────────── 图标与文字对齐 ────────────────────────────

    /** 按方格边长取图标字体。传中间值会落到最近一档 —— 宁可差一档，也不能靠缩放，那会发虚。 */
    public static CFontRenderer iconFont(final double box) {
        if (box <= (ICON_S + ICON_M) * .5) return FontManager.partyIcon24;
        if (box <= (ICON_M + ICON_L) * .5) return FontManager.partyIcon32;
        if (box <= (ICON_L + ICON_XL) * .5) return FontManager.partyIcon40;
        return FontManager.partyIcon56;
    }

    private static final Map<CFontRenderer, GlyphInkMetrics> EM_SQUARE =
            new IdentityHashMap<CFontRenderer, GlyphInkMetrics>();

    /**
     * 图标字体的 em 方格（{@link PartyIcons#FRAME} 的墨迹盒）。
     *
     * <p>不按图标自己的墨迹盒居中：chevron 只占 0.27 em 宽，group 占 0.92 em，各自居中会把细长的
     * 那些「撑」到和实心图形一样大、一行里一高一低。满格方块量出来的就是 Material 的 24 设计网格。</p>
     */
    private static GlyphInkMetrics emSquare(final CFontRenderer font) {
        if (font == null) return null;
        synchronized (EM_SQUARE) {
            if (EM_SQUARE.containsKey(font)) return EM_SQUARE.get(font);
        }
        GlyphInkMetrics measured = GlyphInkMetrics.of(font, PartyIcons.FRAME.charAt(0));
        synchronized (EM_SQUARE) {
            EM_SQUARE.put(font, measured);
        }
        return measured;
    }

    /** 一枚图标，墨迹中心落在宿主坐标系的 {@code (cx, cy)} 上。 */
    protected LabelWidget icon(final AbstractWidget<?> host, final Supplier<String> glyph, final double box,
                               final DoubleSupplier cx, final DoubleSupplier cy, final IntSupplier color) {
        final CFontRenderer font = iconFont(box);
        final LabelWidget widget = label(host, glyph, font);
        widget.setBeforeRenderCallback(() -> {
            widget.setColor(color.getAsInt());
            GlyphInkMetrics square = emSquare(font);
            if (square == null) {
                // 量不出来（字体还没加载好）就退回行盒居中：位置略偏，但图标仍在该在的地方。
                widget.setPosition(cx.getAsDouble() - widget.getWidth() * .5,
                        cy.getAsDouble() - widget.getHeight() * .5);
                return;
            }
            widget.setPosition(square.alignCenterX(cx.getAsDouble()), square.alignCenterY(cy.getAsDouble()));
        });
        return widget;
    }

    protected LabelWidget icon(final AbstractWidget<?> host, final String glyph, final double box,
                               final DoubleSupplier cx, final DoubleSupplier cy, final IntSupplier color) {
        return icon(host, () -> glyph, box, cx, cy, color);
    }

    /**
     * 给不继承本类的界面（导航栏、主页等）摆图标用：把 em 方格中心放到 {@code (cx, cy)}。
     *
     * <p>暴露出来而不是让那些界面各自量一遍：量错了图标就会在行里一高一低，而这种偏移
     * 单看一处根本发现不了，非得把两处摆在一起才看得出来。</p>
     */
    public static void placeIcon(final LabelWidget widget, final CFontRenderer font,
                                 final double cx, final double cy) {
        GlyphInkMetrics square = emSquare(font);
        if (square == null) {
            widget.setPosition(cx - widget.getWidth() * .5, cy - widget.getHeight() * .5);
            return;
        }
        widget.setPosition(square.alignCenterX(cx), square.alignCenterY(cy));
    }

    /**
     * 一枚<b>非</b> party-icons 字形（品牌字标），按它自己的墨迹盒居中。
     *
     * <p>不能走 {@link #icon}：那一套按 {@link PartyIcons#FRAME} 量出的 em 方格居中，而品牌
     * 字体里没有这枚满格方块。品牌字标在一行里总是孤立的一枚，各自居中不会显出高低差。</p>
     */
    protected LabelWidget glyph(final AbstractWidget<?> host, final String text, final CFontRenderer font,
                                final DoubleSupplier cx, final DoubleSupplier cy, final IntSupplier color) {
        final LabelWidget widget = label(host, text, font);
        final char probe = has(text) ? text.charAt(0) : ' ';
        widget.setBeforeRenderCallback(() -> {
            widget.setColor(color.getAsInt());
            GlyphInkMetrics ink = GlyphInkMetrics.of(font, probe);
            if (ink == null) {
                widget.setPosition(cx.getAsDouble() - widget.getWidth() * .5,
                        cy.getAsDouble() - widget.getHeight() * .5);
                return;
            }
            widget.setPosition(ink.alignCenterX(cx.getAsDouble()), ink.alignCenterY(cy.getAsDouble()));
        });
        return widget;
    }

    /**
     * 一枚品牌标识：有矢量数据的走矢量（{@link BrandVectorIcons}），其余走字形字标。
     *
     * <p>尺寸按 {@link BrandVectorIcons#MARK_RATIO} 折 <b>容器</b>，而不是各自的字体实例 ——
     * 改矢量之前两家没有品牌字形，三处界面各挑了一个字号不同的正文字体来顶替，实测同一枚标识
     * 在引导页的小底盘里墨迹高 6.2、在音源卡的圆盘里 17.9，本来就对不齐。现在统一成容器的一半。</p>
     *
     * <p>兜底那条分支不是装饰：矢量数据解析不出来（Skija 路径不支持、字符串被改坏）时，
     * 走它至少还能看见一枚字标，而不是一块空白。</p>
     */
    protected void brandMark(final AbstractWidget<?> host, final MusicPlatform platform, final double size,
                             final String fallbackGlyph, final CFontRenderer fallbackFont,
                             final DoubleSupplier cx, final DoubleSupplier cy, final IntSupplier color) {
        if (!BrandVectorIcons.has(platform)) {
            glyph(host, fallbackGlyph, fallbackFont, cx, cy, color);
            return;
        }
        final BrandIconWidget mark = new BrandIconWidget(platform, size);
        host.addChild(mark);
        mark.setBeforeRenderCallback(() -> {
            mark.setColor(color.getAsInt());
            mark.setPosition(cx.getAsDouble() - size * .5, cy.getAsDouble() - size * .5);
        });
    }

    protected LabelWidget label(final AbstractWidget<?> host, final String text, final CFontRenderer font) {
        return label(host, () -> text, font);
    }

    protected LabelWidget label(final AbstractWidget<?> host, final Supplier<String> text, final CFontRenderer font) {
        final LabelWidget widget = new LabelWidget(text, font);
        host.addChild(widget);
        widget.setClickable(false);
        return widget;
    }

    /**
     * 让一行文字的<b>墨迹</b>中心落在 {@code centerY} 上，返回该传给 {@code setPosition} 的 y。
     *
     * <p>「文字看着没居中」的根因：数字只有大写高度，中文占满字身，两者共享基线，所以按行盒
     * （{@code getHeight()}）摆出来的同一行里，"1.4.1" 会明显比旁边的"已登录"高几像素。</p>
     */
    public static double inkTop(final LabelWidget label, final double centerY) {
        GlyphInkMetrics ink = GlyphInkMetrics.of(label.getFont(), inkProbe(label.getLabel()));
        if (ink == null) return centerY - label.getHeight() * .5;
        return ink.alignCenterY(centerY);
    }

    /**
     * 一行文字的<b>墨迹</b>高度（逻辑像素），量不出来退回行盒高度。
     *
     * <p>给同一行里的图形记号定尺寸用：字号档位、中日韩与拉丁的字身差别都很大，写死一个
     * "图标多大"迟早和某一行的文字对不上。按旁边那行文字自己的墨迹量，换字体换字号都跟着走。</p>
     */
    public static double labelInkHeight(final LabelWidget label) {
        GlyphInkMetrics ink = GlyphInkMetrics.of(label.getFont(), inkProbe(label.getLabel()));
        if (ink == null || ink.height <= 0) return label.getHeight();
        return ink.height;
    }

    /** 优先拿中日韩字符量墨迹带：它们占满字身，按它居中之后夹在中间的数字与拉丁字母也在带内。 */
    private static char inkProbe(final String text) {
        char latin = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c <= ' ') continue;
                if (c >= 0x2E80) return c;
                if (latin == 0) latin = c;
            }
        }
        return latin == 0 ? '\u4e2d' : latin;
    }

    // ──────────────────────────── 数值小工具 ────────────────────────────

    public static double clamp01(final double value) {
        return value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
    }

    /**
     * 在两个 {@code 0xRRGGBB} 之间线性插值。
     *
     * <p>只混 RGB：{@code setColor(int)} 走的是 {@code new Color(int)}，高位 alpha 会被丢掉
     * （透明度由组件自己的 alpha 决定），传半透明色是没用的。要让一个控件"淡一点"，只能把它的
     * 颜色往背景或前景混。</p>
     */
    public static int mix(final int from, final int to, final double amount) {
        double t = clamp01(amount);
        int r = channel((from >> 16) & 0xFF, (to >> 16) & 0xFF, t);
        int g = channel((from >> 8) & 0xFF, (to >> 8) & 0xFF, t);
        int b = channel(from & 0xFF, to & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int channel(final int from, final int to, final double t) {
        return Math.max(0, Math.min(255, (int) Math.round(from + (to - from) * t)));
    }

    protected static boolean has(final String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 取首个可见字符做字母头像。中日韩取一个字，拉丁取一个大写字母。 */
    protected static String initial(final String value) {
        if (!has(value)) return "?";
        String trimmed = value.trim();
        return trimmed.substring(0, 1).toUpperCase();
    }

    // ──────────────────────────── 组件 ────────────────────────────

    /** 只画一圈描边的组件。 */
    protected static final class Stroke extends AbstractWidget<Stroke> {

        private double radius;
        private double thickness = 1.0;

        @Override
        public void onRender(double mouseX, double mouseY) {
            if (getAlpha() <= .004f) return;
            roundedOutline(getX(), getY(), getWidth(), getHeight(), radius, thickness, getColor());
        }
    }

    /** 两端 alpha 各自可调的渐变面；两端都会再乘一次组件自己的 alpha，整块淡出时不会先塌一半。 */
    protected static final class Wash extends AbstractWidget<Wash> {

        private final boolean horizontal;
        private final double radius;
        private int from;
        private int to;
        private double fromAlpha = 1.0;
        private double toAlpha = 1.0;

        private Wash(final boolean horizontal, final double radius) {
            this.horizontal = horizontal;
            this.radius = radius;
            this.setClickable(false);
        }

        public Wash tint(final int from, final int to) {
            this.from = from;
            this.to = to;
            return this;
        }

        public Wash alphas(final double from, final double to) {
            this.fromAlpha = from;
            this.toAlpha = to;
            return this;
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float alpha = getAlpha();
            if (alpha <= .004f) return;
            Color head = new Color(reAlpha(from, (float) (fromAlpha * alpha)), true);
            Color tail = new Color(reAlpha(to, (float) (toAlpha * alpha)), true);
            if (horizontal) {
                roundedRectGradientHorizontal(getX(), getY(), getWidth(), getHeight(), radius, head, tail);
            } else {
                roundedRectGradientVertical(getX(), getY(), getWidth(), getHeight(), radius, head, tail);
            }
        }
    }

    /**
     * 卡片底板。
     *
     * <p>自己算一次悬停：框架的 {@code hovering} 在<b>任意可点子组件</b>被悬停时会被清掉，而卡片
     * 里往往还有一两颗按钮，直接用它的话鼠标一挪到按钮上整张卡的悬停态就塌了。</p>
     */
    protected final class Card extends RoundedRectWidget {

        public double hover;
        public double reveal;

        @Override
        public void onRender(double mouseX, double mouseY) {
            boolean inside = getAlpha() > .5f && testHovered(mouseX, mouseY);
            hover = Interpolations.interpolate(hover, inside ? 1.0 : 0.0, .25f);
            super.onRender(mouseX, mouseY);
        }
    }
}