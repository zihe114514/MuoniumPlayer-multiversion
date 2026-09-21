package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.rendering.BrandVectorIcons;
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

    protected static final int OK_COLOR = 0x6EDAA0;
    protected static final int WARN_COLOR = 0xE0B268;
    protected static final int BAD_COLOR = 0xF1767D;
    /** 会员金。桂冠奖章与「VIP」字样共用，全项目只此一处。 */
    public static final int VIP_COLOR = 0xE8C071;

    // ──────────────────────────── 状态 ────────────────────────────

    protected volatile boolean closing;

    /** 弹窗整体的出场进度：0 → 1 期间整块淡入并上浮。 */
    private double presentation;
    /** 弹窗高度自身也补间：换页时高度不同，直接跳会「咔」一下。 */
    private double sheetHeight;
    /**
     * 当前页的入场进度。
     *
     * <p>卡片按 {@code slot} 依次开闸（见 {@link #card}），于是换页是一条条落下来而不是整块闪出来。
     * 这一项每帧在弹窗的 beforeRender 里推进，所以只要页面在画就一定在走。</p>
     */
    protected double pageFade;

    public boolean shouldClose() {
        return closing;
    }

    public void dispose() {
        closing = true;
    }

    /** 换页：清空子组件并把入场进度归零。所有 {@code showXxx()} 的第一行都该调它。 */
    protected void beginPage() {
        pageFade = 0.0;
        getChildren().clear();
    }

    // ──────────────────────────── 弹窗骨架 ────────────────────────────

    /**
     * 遮罩 + 弹窗 + 圆角底 + 顶部高光，返回可以往里塞内容的弹窗面板。
     *
     * <p>点遮罩关闭；点弹窗本体不关闭（{@code setOnClickCallback} 直接吃掉）。</p>
     */
    protected Panel sheet(final double preferredWidth, final DoubleSupplier preferredHeight) {
        presentation = 0.0;
        sheetHeight = 0.0;

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
            presentation = Interpolations.interpolate(presentation, 1.0, .2f);
            // 入场进度推到 1 之前不推页面进度，两段动画叠在一起会显得毛躁。
            pageFade = Interpolations.interpolate(pageFade, 1.0, .16f);
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
        });

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
        bg.setBeforeRenderCallback(() -> {
            double gate = clamp01((pageFade - slot * .07) / .55);
            bg.reveal = Interpolations.interpolate(bg.reveal, gate, .3f);
            bg.setBounds(Math.max(1, host.getWidth() - (PAD + CARD_INSET) * 2), Math.max(1, height.getAsDouble()));
            // 入场时从下方 7 像素处落上来；只在入场那几帧有位移，稳定后就是 top 本身。
            bg.setPosition(PAD + CARD_INSET, top.getAsDouble() + (1.0 - bg.reveal) * 7.0);
            bg.setColor(color.getAsInt());
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
        final RoundedRectWidget plate = new RoundedRectWidget();
        dialog.addChild(plate);
        plate.setClickable(false).setRadius(11).setBeforeRenderCallback(() -> {
            plate.setBounds(34, 34);
            plate.setPosition(PAD, 18);
            plate.setColor(mix(NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND), accent.getAsInt(), .28));
        });
        icon(plate, glyph, ICON_M, () -> 17.0, () -> 17.0, accent);

        final LabelWidget title = label(dialog, () -> titleText, FontManager.pf18bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            title.setPosition(PAD + 45, inkTop(title, 28.0));
        });

        final LabelWidget subtitle = label(dialog, subtitleText, FontManager.pf12);
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
        backButton(dialog, back);

        final LabelWidget title = label(dialog, titleText, FontManager.pf18bold);
        title.setBeforeRenderCallback(() -> {
            title.setColor(accent.getAsInt());
            title.setMaxWidth(Math.max(1, dialog.getWidth() - PAD - 52 - PAD));
            title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            title.setPosition(PAD + 40, inkTop(title, 28.0));
        });

        final LabelWidget subtitle = label(dialog, subtitleText, FontManager.pf12);
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