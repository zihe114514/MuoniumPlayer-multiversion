package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInkMetrics;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;

import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 二段选择器：一条轨道 + 一颗会滑的滑块 + 两个透明命中区。
 *
 * <p>做成一条轨道加一颗滑块，而不是两颗各自换色的按钮：滑块的位置本身就说明了「这是二选一」，
 * 而两颗独立按钮看上去像两个可以各自开关的选项。</p>
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

    /** 宿主每帧报一次：这一格摆在哪、多大、滑块走多久。 */
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
    }

    private final RoundedRectWidget track = new RoundedRectWidget();
    private final RoundedRectWidget ghost = new RoundedRectWidget();
    private final RoundedRectWidget knob = new RoundedRectWidget();
    private final RoundedRectWidget[] hits = {new RoundedRectWidget(), new RoundedRectWidget()};
    private final LabelWidget[] marks = new LabelWidget[2];
    private final LabelWidget[] captions = new LabelWidget[2];
    /** 每半的 {@code [图标左边缘, 文字左边缘]}，由那一半的命中区算出来给标签读。 */
    private final double[][] slot = new double[2][2];

    private final Supplier<Spec> specOf;
    private final IntSupplier selected;
    private final IntSupplier leftColor;
    private final IntSupplier rightColor;
    private final String[] glyphs;
    private final String[] texts;
    private final IntPredicate onSelect;

    private Spec spec = new Spec();
    /** 含缓动的 0..1，0 = 左半。摆位与颜色只读这一个数，所以不会出现「滑块还在半路、颜色已经换完」。 */
    private double slide;
    private double legFrom;
    private double legT = 1.0;
    private int legTo = -1;
    private double press;
    private double ghostFade;
    private double presence = 1.0;
    private boolean wanted = true;
    private long lastNanos;
    /** 上一帧真正走了多少毫秒，由 {@link #tick()} 算好给同一帧里的淡底与回弹复用。 */
    private double frameMs;

    /**
     * @param onSelect 点了第几半（0 左、1 右）；返回 {@code false} 表示这一次点击不作数，
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

        host.addChild(track);
        track.setClickable(false);
        // 时钟只在轨道这一格推进：它是整块的最外层，淡出到零也还在画，所以不会有「进度停住、
        // 于是永远淡不完」那种卡死。子组件一律只读，不许自己再走一遍。
        track.setBeforeRenderCallback(() -> {
            spec = specOf.get();
            track.setBounds(Math.max(1.0, spec.width), Math.max(1.0, spec.height));
            track.setPosition(spec.x, spec.y);
            track.setRadius(spec.radius);
            track.setColor(NCMScreen.getColor(NCMScreen.ColorType.INPUT_BACKGROUND));
            track.setAlpha((float) presence);
            tick();
        });

        // 淡底排在滑块之前：它标的是「未选中但正指着的那半」，压在滑块底下才会被选中态吃掉。
        track.addChild(ghost);
        ghost.setClickable(false);
        ghost.setBeforeRenderCallback(() -> {
            int hover = hoveredHalf();
            ghostFade = ramp(ghostFade, hover >= 0 && hover != legTo ? 1.0 : 0.0, frameMs, dur(HOVER_MS));
            ghost.setBounds(halfWidth(), halfHeight());
            ghost.setPosition(spec.inset + halfWidth() * Math.max(0, hover), spec.inset);
            ghost.setRadius(knobRadius());
            ghost.setColor(hover == 1 ? rightColor.getAsInt() : leftColor.getAsInt());
            ghost.setAlpha(HOVER_ALPHA * (float) ghostFade);
        });

        track.addChild(knob);
        knob.setClickable(false);
        knob.setBeforeRenderCallback(() -> {
            press = ramp(press, 0.0, frameMs, dur(PRESS_MS));
            knob.setBounds(halfWidth(), halfHeight());
            knob.setPosition(spec.inset + halfWidth() * slide, spec.inset);
            knob.setRadius(knobRadius());
            knob.setColor(NcmSheet.mix(leftColor.getAsInt(), rightColor.getAsInt(), slide));
            // 按下去往内压一点再弹回。bounds 每帧先重设，所以这个 expand 不会累加。
            knob.expand(-1.2 * press);
        });

        for (int index = 0; index < 2; index++) {
            buildHalf(index);
        }
    }

    private void buildHalf(final int index) {
        final RoundedRectWidget hit = hits[index];
        track.addChild(hit);
        hit.setShouldOverrideMouseCursor(true);
        hit.setOnClickCallback((x, y, button) -> {
            if (button != 0 || !onSelect.test(index)) return false;
            press = 1.0;
            return true;
        });
        hit.setBeforeRenderCallback(() -> {
            hit.setBounds(halfWidth(), halfHeight());
            hit.setPosition(spec.inset + halfWidth() * index, spec.inset);
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
            double pad = Math.max(0.0, (halfWidth() - (inkRight - inkLeft)) * .5);
            double pen = spec.inset + halfWidth() * index + pad - inkLeft;
            slot[index][0] = pen;
            slot[index][1] = pen + mark + gap;
        });

        final IntSupplier ink = () -> NcmSheet.mix(
                NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT), 0xFFFFFF,
                index == 0 ? 1.0 - slide : slide);

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
     * 滑块此刻在第几半（含缓动，0 = 左半、1 = 右半）。
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

        final int want = selected.getAsInt() == 1 ? 1 : 0;
        if (legTo < 0) {
            // 第一帧不许从 0 滑过去：那会让每次开界面都白演一趟。
            legTo = want;
            slide = want;
            legFrom = want;
            legT = 1.0;
        } else if (want != legTo) {
            legTo = want;
            legFrom = slide;
            legT = 0.0;
        }
        if (legT < 1.0) {
            legT = Math.min(1.0, legT + frameMs / dur(spec.durationMs));
            slide = legFrom + (legTo - legFrom) * TransitionCurveCache.MOTION_LAGGING.at(legT);
        }
        presence = ramp(presence, wanted ? 1.0 : 0.0, frameMs, dur(PRESENCE_MS));
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

    private int hoveredHalf() {
        if (hits[1].isHovering()) return 1;
        if (hits[0].isHovering()) return 0;
        return -1;
    }

    private double halfWidth() {
        return Math.max(1.0, (spec.width - spec.inset * 2) * .5);
    }

    private double halfHeight() {
        return Math.max(1.0, spec.height - spec.inset * 2);
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
