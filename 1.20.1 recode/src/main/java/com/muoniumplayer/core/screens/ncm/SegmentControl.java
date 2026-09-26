package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;

import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.IntToDoubleFunction;
import java.util.function.Supplier;

/**
 * 多段选择器：一条轨道 + 一颗会滑的滑块 + 每段一个透明命中区。
 *
 * <p>做成一条轨道加一颗滑块，而不是几颗各自换色的按钮：滑块的位置本身就说明了「这是几选一」，
 * 而几颗独立按钮看上去像几个可以各自开关的选项。</p>
 *
 * <p>段数由传进来的 {@code texts} 长度决定：搜索栏那两颗是 2，私人 FM 的六种模式、四组场景标签
 * 也是同一套。两格时滑块位置记在 0..1，多格时记在 0..段数-1，其余口径不变。格子默认等分，
 * 宿主给了 {@link Spec#cellWidth} 就按每一格自己的宽度排（场景标签按文字长短排，不一样宽），
 * 此时滑块在两格之间是「左边缘与宽度各自插值」，不会走一半突然变宽。宿主报
 * {@code selected < 0} 表示这一组没有选中项，滑块与亮字一起淡出、位置原地冻住。</p>
 *
 * <p>滑块走真毫秒时钟，不再用 {@code Interpolations.interpolate}：那条帧耦合指数没有时长，
 * 尾巴无限接近却到不了位，连点时两段指数叠在一起就是「先窜后糊」。这里每一趟记住自己从哪里
 * 出发，中途改目标就从<b>当前位置</b>另起一趟，所以快速连点不会回到 0 重来。</p>
 */
public final class SegmentControl {

    /** 单帧最多补这么多毫秒：切后台回来或严重掉帧时，不让滑块一趟跨过整段。与 {@link SheetClock} 同一档。 */
    private static final double MAX_FRAME_MS = 250.0;
    /** 按下去那一下的回弹。比滑块短得多 —— 它说的是「我按到了」，不是「我切完了」。 */
    private static final double PRESS_MS = 130.0;
    /** 整块淡入淡出（平台从支持这个选择换到不支持）。 */
    private static final double PRESENCE_MS = 200.0;
    /** 悬停淡底。 */
    private static final double HOVER_MS = 110.0;
    /** 淡底最亮到多少：只是「这里可以点」，不能抢过选中态。 */
    private static final float HOVER_ALPHA = .18f;

    /**
     * 跨宿主重建把滑块位置交出去、再要回来。
     *
     * <p>宿主是「数据一到就整棵重排」的那种界面（私人 FM 点模式：{@code selectMode} → 请求 →
     * {@code notifyUi} → {@code reloadCurrentPanel} → 面板重新 {@code onInit} 建一遍子组件），
     * 控件对象本身活不过这一次。没有这份记忆，新实例第一帧只能按「不许白演一趟」那条规则直接
     * 钉在目标上 —— 用户看到的就是滑块瞬移。{@link #last()} 返回 {@link Double#NaN} 表示
     * 「这是第一次」，此时仍然直接钉住。</p>
     */
    public interface SlideMemory {
        double last();

        void remember(double slide);
    }

    /** 宿主每帧报一次：这一段摆在哪、多大、滑块走多久。 */
    public static final class Spec {
        public double x;
        public double y;
        public double width;
        public double height;
        public double radius = 9.0;
        /** 滑块与轨道之间的内缩。 */
        public double inset = 4.0;
        /** 滑块从一端走到另一端要多少毫秒。 */
        public double durationMs = 260.0;
        public CFontRenderer font = FontManager.pf12bold;
        /** 可为 null：搜索栏那一种「点了不重建」的宿主不需要记忆。 */
        public SlideMemory memory;
        /**
         * 每格多宽；{@code null} = 按段数等分。
         *
         * <p>私人 FM 那四组场景标签是按文字长短排的，格与格不一样宽，滑块得贴着每一格自己的盒子走。
         * 等分那一族（搜索栏、模式行）不传这个，走原来那条算式。</p>
         */
        public IntToDoubleFunction cellWidth;
        /**
         * 轨道要不要自己报位置。宿主是 {@code ScrollPanel} 这种「布局会重摆子组件」的容器时置
         * false：轨道只报尺寸，位置交给宿主，否则横向滚过去的那点偏移会被自己写回去。
         */
        public boolean positionsItself = true;
    }

    private final RoundedRectWidget track = new RoundedRectWidget();
    private final RoundedRectWidget ghost = new RoundedRectWidget();
    private final RoundedRectWidget knob = new RoundedRectWidget();
    /** 段数就是 {@code texts} 的长度：命中区、图标、文字各一格，全部按它分配。 */
    private final int cells;
    private final RoundedRectWidget[] hits;
    private final LabelWidget[] marks;
    private final LabelWidget[] captions;
    /** 每段的 {@code [图标左边缘, 文字左边缘]}，由那一段的命中区算出来给标签读。 */
    private final double[][] slot;

    private final Supplier<Spec> specOf;
    private final IntSupplier selected;
    private final IntSupplier leftColor;
    private final IntSupplier rightColor;
    private final String[] glyphs;
    private final String[] texts;
    private final IntPredicate onSelect;

    private Spec spec = new Spec();
    /** 含缓动的第几段（0 = 第一段）。摆位与颜色只读这一个数，所以不会出现「滑块还在半路、颜色已经换完」。 */
    private double slide;
    private double legFrom;
    private double legT = 1.0;
    private int legTo = -1;
    private double press;
    private double ghostFade;
    /** 滑块与「选中态的亮字」的可见度：宿主报 {@code selected < 0}（这一组没有选中项）时淡到零。 */
    private double knobShow = 1.0;
    private double presence = 1.0;
    private boolean wanted = true;
    private long lastNanos;
    /** 上一帧真正走了多少毫秒，由 {@link #tick()} 算好给同一帧里的淡底与回弹复用。 */
    private double frameMs;

    /**
     * @param onSelect 点了第几段（0 起）；返回 {@code false} 表示这一次点击不作数，
     *                 那就不动滑块、也不给按压回弹。
     */
    public static SegmentControl attach(final AbstractWidget<?> host, final Supplier<Spec> spec,
            final IntSupplier selected, final IntSupplier leftColor, final IntSupplier rightColor,
            final String[] glyphs, final String[] texts, final IntPredicate onSelect) {
        return new SegmentControl(host, spec, selected, leftColor, rightColor, glyphs, texts, onSelect);
    }

    private SegmentControl(final AbstractWidget<?> host, final Supplier<Spec> specOf,
            final IntSupplier selected, final IntSupplier leftColor, final IntSupplier rightColor,
            final String[] glyphs, final String[] texts, final IntPredicate onSelect) {
        this.specOf = specOf;
        this.selected = selected;
        this.leftColor = leftColor;
        this.rightColor = rightColor;
        this.glyphs = glyphs;
        this.texts = texts;
        this.onSelect = onSelect;

        this.cells = Math.max(2, texts.length);
        this.hits = new RoundedRectWidget[this.cells];
        for (int index = 0; index < this.cells; index++) this.hits[index] = new RoundedRectWidget();
        this.marks = new LabelWidget[this.cells];
        this.captions = new LabelWidget[this.cells];
        this.slot = new double[this.cells][2];

        host.addChild(track);
        track.setClickable(false);
        // 时钟只在轨道这一格推进：它是整块的最外层，淡出到零也还在画，所以不会有「进度停住、
        // 于是永远淡不完」那种卡死。子组件一律只读，不许自己再走一遍。
        track.setBeforeRenderCallback(() -> {
            spec = specOf.get();
            track.setBounds(Math.max(1.0, spec.width), Math.max(1.0, spec.height));
            if (spec.positionsItself) track.setPosition(spec.x, spec.y);
            track.setRadius(spec.radius);
            track.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            track.setAlpha((float) presence);
            tick();
        });

        // 淡底排在滑块之前：它标的是「未选中但正指着的那一段」，压在滑块底下才会被选中态吃掉。
        track.addChild(ghost);
        ghost.setClickable(false);
        ghost.setBeforeRenderCallback(() -> {
            int hover = hoveredCell();
            ghostFade = ramp(ghostFade, hover >= 0 && hover != legTo ? 1.0 : 0.0, frameMs, dur(HOVER_MS));
            double[] box = boxAt(Math.max(0, hover));
            ghost.setBounds(box[1], cellHeight());
            ghost.setPosition(box[0], spec.inset);
            ghost.setRadius(knobRadius());
            ghost.setColor(colorAt(hover));
            ghost.setAlpha(HOVER_ALPHA * (float) ghostFade);
        });

        track.addChild(knob);
        knob.setClickable(false);
        knob.setBeforeRenderCallback(() -> {
            press = ramp(press, 0.0, frameMs, dur(PRESS_MS));
            double[] box = boxAt(slide);
            knob.setBounds(box[1], cellHeight());
            knob.setPosition(box[0], spec.inset);
            knob.setRadius(knobRadius());
            knob.setColor(colorAt(slide));
            knob.setAlpha((float) knobShow);
            // 按下去往内压一点再弹回。bounds 每帧先重设，所以这个 expand 不会累加。
            knob.expand(-1.2 * press);
        });

        for (int index = 0; index < cells; index++) {
            buildCell(index);
        }
    }

    private void buildCell(final int index) {
        final RoundedRectWidget hit = hits[index];
        track.addChild(hit);
        hit.setShouldOverrideMouseCursor(true);
        hit.setOnClickCallback((x, y, button) -> {
            if (button != 0 || !onSelect.test(index)) return false;
            press = 1.0;
            return true;
        });
        hit.setBeforeRenderCallback(() -> {
            double cell = cellWidthAt(index);
            hit.setBounds(cell, cellHeight());
            hit.setPosition(cellLeftAt(index), spec.inset);
            hit.setRadius(knobRadius());
            hit.setAlpha(0f);
            hit.setClickable(presence > .5);
            // 只有真有图标时才留那 6 像素间隙：空串也照样留，会把文字往右推偏 3 像素。
            double mark = glyphs[index].isEmpty() ? 0.0 : FontManager.fontello16.getStringWidthD(glyphs[index]);
            double gap = mark == 0.0 ? 0.0 : 6.0;
            double[] text = inkSpan(spec.font, texts[index]);
            double[] icon = mark == 0.0 ? null : inkSpan(FontManager.fontello16, glyphs[index]);
            // 整组的墨迹盒，坐标原点是「图标的 pen」（没有图标时就是文字的 pen）。
            double inkRight = mark + gap + (text == null ? spec.font.getStringWidthD(texts[index]) : text[1]);
            double inkLeft = mark == 0.0
                    ? (text == null ? 0.0 : text[0])
                    : (icon == null ? 0.0 : icon[0]);
            double pad = Math.max(0.0, (cell - (inkRight - inkLeft)) * .5);
            double pen = cellLeftAt(index) + pad - inkLeft;
            slot[index][0] = pen;
            slot[index][1] = pen + mark + gap;
        });

        // 滑块正压在这一段上时最亮，走开一格就完全回到次要文字色：亮度只跟 |slide - 这一段| 挂钩，
        // 所以三格以上不会出现「隔着一段还跟着一起变白」。整组没有选中态时（{@code knobShow}
        // 归零）所有标签一起回到次要色 —— 私人 FM 那四组里只有当前那一组有选中。
        final IntSupplier ink = () -> NcmSheet.mix(
                NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), 0xFFFFFF,
                Math.max(0.0, (1.0 - Math.abs(slide - index)) * knobShow));

        marks[index] = new LabelWidget(glyphs[index], FontManager.fontello16);
        track.addChild(marks[index]);
        marks[index].setClickable(false);
        marks[index].setBeforeRenderCallback(() -> {
            marks[index].setColor(ink.getAsInt());
            marks[index].setPosition(slot[index][0], NcmSheet.inkTop(marks[index], track.getHeight() * .5));
        });

        captions[index] = new LabelWidget(texts[index], spec.font);
        track.addChild(captions[index]);
        captions[index].setClickable(false);
        captions[index].setBeforeRenderCallback(() -> {
            // 构造这一刻读到的 spec 还是宿主没喂过的默认值（见构造函数里那句 new Spec()），
            // 所以字体必须每帧跟着宿主走。漏了这一句的后果是：排版按宿主的字号算，画出来却是
            // 默认那档 ——「歌曲/歌单」用 pf10bold 量出 18 逻辑像素的半宽，字却按 pf12bold 画，
            // 于是墨迹比留白宽出 20%，全从右边溢出，看上去就是「文字没居中、左右间隙不一样」。
            captions[index].setFont(spec.font);
            captions[index].setColor(ink.getAsInt());
            captions[index].setPosition(slot[index][1], NcmSheet.inkTop(captions[index], track.getHeight() * .5));
        });
    }

    /**
     * 一串字相对 pen 的墨迹盒 {@code {左沿, 右沿}}，量不出来返回 {@code null}。
     *
     * <p>{@link CFontRenderer#getStringWidthD(String)} 量的是 pen 的<b>推进</b>宽度，字形的墨迹在
     * 里面还带着内白。实测内置字体「歌单」推进 10.0 逻辑像素、上墨 9.49、左内白 0.26 —— 正文这一档
     * 两者只差 0.25 像素，肉眼看不出来；但图标那一档差得很多（各家图标字体一个字占满 em 方格的比例
     * 从 0.53 到 0.95 都有，见 {@link GlyphInkMetrics}），照推进宽度居中的话整组就会往一边倒。
     * 垂直方向早就改成量墨迹了（{@link NcmSheet#inkTop}），这里补的是同一件事的另一半。</p>
     *
     * <p>首尾各量一次就够：中间那些字的推进宽度用 {@code getStringWidthD} 的前缀差取，
     * 和 {@code drawGlyphs} 走的是同一套累加口径。</p>
     */
    private static double[] inkSpan(final CFontRenderer font, final String text) {
        if (font == null || text == null || text.isEmpty()) {
            return null;
        }
        final GlyphInkMetrics first = GlyphInkMetrics.of(font, text.charAt(0));
        final GlyphInkMetrics last = text.length() == 1
                ? first : GlyphInkMetrics.of(font, text.charAt(text.length() - 1));
        if (first == null || last == null) {
            return null;
        }
        final double head = font.getStringWidthD(text.substring(0, text.length() - 1));
        return new double[]{first.offsetX, head + last.offsetX + last.width};
    }

    /**
     * 平台不支持这一格时整块淡出，而不是 {@code setHidden(true)} 硬消失。
     *
     * <p>淡出期间它还在走时钟，所以宿主可以拿 {@link #presence()} 把让出来的宽度还给输入框 ——
     * 淡出与变宽是同一件事的两面，不会出现「pill 没了但输入框还是窄的」。</p>
     */
    public SegmentControl setEnabled(final boolean on) {
        this.wanted = on;
        return this;
    }

    /** 当前淡出程度 0..1。 */
    public double presence() {
        return presence;
    }

    /**
     * 滑块此刻在第几段（含缓动，0 = 第一段）。两格时取值 0..1，多格时到 段数-1。
     *
     * <p>宿主拿它决定<b>看得见的文字</b>要不要跟着换 —— 例如搜索框的占位提示。让文字在
     * {@code .5} 处翻页而不是在点击那一刻就改，才不会出现「词已经换了、滑块还在半路」。</p>
     */
    public double progress() {
        return slide;
    }

    private void tick() {
        final long now = System.nanoTime();
        frameMs = lastNanos == 0L ? 0.0 : Math.min(MAX_FRAME_MS, (now - lastNanos) / 1_000_000.0);
        lastNanos = now;

        final int chosen = selected.getAsInt();
        knobShow = ramp(knobShow, chosen < 0 ? 0.0 : 1.0, frameMs, dur(HOVER_MS));
        if (chosen < 0) {
            // 这一组里没有当前选中的那一枚：滑块淡出就够了，不许它悄悄滑回第一格 ——
            // 换回这一组时应该停在原来那一枚上，而不是从零再走一趟。
            presence = ramp(presence, wanted ? 1.0 : 0.0, frameMs, dur(PRESENCE_MS));
            if (spec.memory != null) spec.memory.remember(slide);
            return;
        }
        final int want = Math.max(0, Math.min(cells - 1, chosen));
        if (legTo < 0) {
            // 第一帧不许从 0 滑过去：那会让每次开界面都白演一趟。宿主是整棵重排的话，这里
            // 从记忆里接上上一帧的位置，滑块继续走完这一趟而不是瞬移。
            legTo = want;
            slide = carried();
            legFrom = slide;
            legT = Math.abs(slide - want) < 1e-6 ? 1.0 : 0.0;
        } else if (want != legTo) {
            legTo = want;
            legFrom = slide;
            legT = 0.0;
        }
        if (legT < 1.0) {
            legT = Math.min(1.0, legT + frameMs / dur(legDuration()));
            slide = legFrom + (legTo - legFrom) * TransitionCurveCache.MOTION_LAGGING.at(legT);
        }
        presence = ramp(presence, wanted ? 1.0 : 0.0, frameMs, dur(PRESENCE_MS));
        if (spec.memory != null) spec.memory.remember(slide);
    }

    /**
     * 这一趟要走多少毫秒：按跨过的格数放大。
     *
     * <p>六格的模式行从第一格跳到最后一格，若和相邻一跳用同一个时长，位移大、时间短，
     * 滑块就是一闪而过，读不出「从哪来到哪去」。两格时距离恒为一格，系数正好是 1 ——
     * 搜索栏那一档的时长一个字都没改。</p>
     */
    private double legDuration() {
        double distance = Math.abs(legTo - legFrom) / Math.max(1, cells - 1);
        return spec.durationMs * (0.55 + 0.45 * Math.min(1.0, distance));
    }

    /** 第一帧该从哪开始：没有记忆（或宿主没给）就钉在目标上。 */
    private double carried() {
        if (spec.memory == null) return legTo;
        double last = spec.memory.last();
        return Double.isNaN(last) ? legTo : Math.max(0.0, Math.min(cells - 1, last));
    }

    /**
     * 宿主设的时长换算成这一趟真正要走多少毫秒。
     *
     * <p>必须除以 {@code HudConfig#playerUiSwitchSpeed}：「动画速度」那条设置管的是整个播放器，
     * 浮层、翻页、封面接力都除了它，这里不除就会出现「把速度拉满，只有这颗滑块还在按自己的秒数走」。</p>
     */
    private static double dur(final double ms) {
        return Math.max(1.0, ms / SheetClock.speed());
    }

    private int hoveredCell() {
        for (int index = cells - 1; index >= 0; index--) {
            if (hits[index].isHovering()) return index;
        }
        return -1;
    }

    /** 一段多宽：轨道扣掉两侧内缩之后按段数等分，等宽是滑块能对齐每一段中心的前提。 */
    private double cellWidth() {
        return Math.max(1.0, (spec.width - spec.inset * 2) / cells);
    }

    /** 第 {@code index} 格多宽：宿主给了每格口径就按它，否则等分。 */
    private double cellWidthAt(final int index) {
        return spec.cellWidth == null ? cellWidth() : Math.max(1.0, spec.cellWidth.applyAsDouble(index));
    }

    /** 第 {@code index} 格的左边缘：每格不等宽时是从第一格累加出来的。 */
    private double cellLeftAt(final int index) {
        if (spec.cellWidth == null) return spec.inset + cellWidth() * index;
        double at = spec.inset;
        for (int i = 0; i < index; i++) at += cellWidthAt(i);
        return at;
    }

    /**
     * 滑块（或悬停淡底）走到 {@code position} 这一处的盒子 {@code {左, 宽}}。
     *
     * <p>每格不一样宽时，两格之间不能只插值左边缘 —— 那样滑块会在一半的位置突然变宽。
     * 左边缘与宽度各自按两端那一格插值，走完整条轨道的视觉面积才是连续的。
     * 等分那一族代进去恰好退化成 {@code inset + cellWidth * position} 与一个常数宽。</p>
     */
    private double[] boxAt(final double position) {
        double pos = Math.max(0.0, Math.min(cells - 1, position));
        int from = (int) Math.floor(pos);
        int to = Math.min(cells - 1, from + 1);
        double f = pos - from;
        return new double[]{
                cellLeftAt(from) + (cellLeftAt(to) - cellLeftAt(from)) * f,
                cellWidthAt(from) + (cellWidthAt(to) - cellWidthAt(from)) * f};
    }

    private double cellHeight() {
        return Math.max(1.0, spec.height - spec.inset * 2);
    }

    /**
     * 滑块走到 {@code position} 这一处该是什么颜色。
     *
     * <p>两格时这就是左右两色的连续渐变；段数多于两时，第一格取 {@code leftColor}，其余都取
     * {@code rightColor}（私人 FM 六种模式传的是同一个 ACCENT，于是全程一个色）。</p>
     */
    private int colorAt(final double position) {
        return NcmSheet.mix(leftColor.getAsInt(), rightColor.getAsInt(),
                Math.max(0.0, Math.min(1.0, position)));
    }

    private double knobRadius() {
        return Math.max(1.0, spec.radius - spec.inset * .5);
    }

    private static double ramp(final double value, final double target, final double deltaMs,
                               final double durationMs) {
        if (durationMs <= 0.0) return target;
        final double step = deltaMs / durationMs;
        return target > value ? Math.min(target, value + step) : Math.max(target, value - step);
    }
}
