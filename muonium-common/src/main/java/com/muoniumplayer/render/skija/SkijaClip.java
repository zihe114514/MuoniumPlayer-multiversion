package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathOp;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 裁剪。取代旧的模板缓冲裁剪（{@code StencilClipManager}）和裁剪盒裁剪（{@code ScissorClipManager}）。
 *
 * <h2>旧实现在做什么</h2>
 * 模板裁剪的调用序列是：进入「只写模板不写颜色」模式 → 调用方把裁剪形状<b>画一遍</b> →
 * 切到「按模板测试」模式 → 调用方画内容 → 退出时把本层模板值减回去。
 * 嵌套靠 {@code GL_INCR} 的计数值实现。整套东西加上状态保存、失效重同步、
 * 异常回滚，接近 260 行，而且脆——它依赖十几项全局 GL 状态在整条绘制链上保持一致。
 *
 * <h2>这里怎么做</h2>
 * Skia 的裁剪是 canvas 状态的一部分，{@code save()/restore()} 天然带嵌套和自动求交，
 * 没有全局状态可失效。唯一的难点是「裁剪形状是靠调用方画出来的」这个接口形式。
 *
 * <p>解法是<b>录制</b>：{@link #beginClip(Runnable)} 打开录制开关后跑一遍调用方的绘制回调，
 * 此时 {@link SkijaShapes} / {@link SkijaImages} 不真的画，而是把形状登记到这里；
 * 回调结束就把登记到的形状变成 {@code clipRRect} / {@code clipPath}。
 * 业务代码一个字都不用改，语义反而更准（旧实现的模板计数只有 8 位，
 * 而且形状的抗锯齿边缘会被当成「在裁剪内」，圆角边上会漏出一圈锯齿）。
 *
 * <h2>坐标空间的前提</h2>
 * 录到的形状坐标是<b>回调运行时的局部坐标</b>，而裁剪是在 {@code beginClip} 的坐标系下应用的。
 * 两者一致的前提是回调内部不再自己做变换。工程里所有裁剪形状回调都是
 * 「画一个圆角矩形」这一种形态，满足这个前提。回调里如果压了变换又画形状，
 * 裁剪会落在错误的位置——真要出现这种写法，应当在回调外面做变换。
 */
public final class SkijaClip {

    /** 录制中：形状绘制调用不落到画布上，改为登记到 {@link #SHAPES}。 */
    private static boolean recording;
    private static final List<RRect> SHAPES = new ArrayList<>();

    /** 每一层裁剪记下进入时的矩阵栈深度，退出时一次性回到它。 */
    private static final Deque<Integer> CLIP_MARKS = new ArrayDeque<>();
    private static final Deque<Integer> SCISSOR_MARKS = new ArrayDeque<>();

    private SkijaClip() {
    }

    static void resetFrame() {
        recording = false;
        SHAPES.clear();
        CLIP_MARKS.clear();
        SCISSOR_MARKS.clear();
    }

    // ------------------------------------------------------------------
    // 形状登记（由 SkijaShapes / SkijaImages 调用）
    // ------------------------------------------------------------------

    /** true 时形状绘制方法必须登记形状而不是绘制。 */
    public static boolean recording() {
        return recording;
    }

    public static void record(RRect rrect) {
        if (recording && rrect != null) {
            SHAPES.add(rrect);
        }
    }

    public static void record(Rect rect) {
        if (recording && rect != null) {
            SHAPES.add(RRect.makeLTRB(rect.getLeft(), rect.getTop(),
                    rect.getRight(), rect.getBottom(), 0f));
        }
    }

    public static void record(double x, double y, double width, double height, double radius) {
        if (recording) {
            SHAPES.add(SkijaShapes.rrect(x, y, width, height, radius));
        }
    }

    // ------------------------------------------------------------------
    // 模板裁剪的替代
    // ------------------------------------------------------------------

    public static void beginClip(Runnable drawClipShape) {
        if (drawClipShape == null) {
            throw new IllegalArgumentException("Clip shape cannot be null");
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            // 帧外调用：仍然压一层标记，好让 endClip 保持配对，不至于把别人的层弹掉。
            CLIP_MARKS.push(-1);
            return;
        }
        CLIP_MARKS.push(SkijaGfx.saveDepth());
        SkijaGfx.pushMatrix();

        boolean outerRecording = recording;
        int baseShapeCount = SHAPES.size();
        recording = true;
        try {
            drawClipShape.run();
        } catch (Throwable t) {
            // 形状没画完就抛异常：旧实现会回滚这一层。这里把已经登记的形状丢掉，
            // 层还是留着（endClip 会配对地弹掉），但不加任何裁剪——
            // 「不裁」远比「裁成空白导致整块 UI 消失」容易发现和排查。
            recording = outerRecording;
            trim(baseShapeCount);
            throw t;
        }
        recording = outerRecording;

        applyRecorded(canvas, baseShapeCount);
        trim(baseShapeCount);
    }

    /** 旧接口里还有一个「先 beginClip、自己画形状、再 updateClip」的两段式写法，一并支持。 */
    public static void beginClip() {
        Canvas canvas = SkijaGfx.canvas();
        CLIP_MARKS.push(canvas == null ? -1 : SkijaGfx.saveDepth());
        if (canvas != null) {
            SkijaGfx.pushMatrix();
        }
        recording = true;
    }

    public static void updateClip() {
        if (!recording) {
            return;
        }
        recording = false;
        Canvas canvas = SkijaGfx.canvas();
        if (canvas != null) {
            applyRecorded(canvas, 0);
        }
        SHAPES.clear();
    }

    public static void endClip() {
        Integer mark = CLIP_MARKS.poll();
        if (mark == null) {
            System.err.println("[MuoniumPlayer/SkijaClip] 裁剪栈下溢：endClip 比 beginClip 多了一次");
            return;
        }
        if (mark >= 0) {
            SkijaGfx.restoreDepthTo(mark);
        }
    }

    public static boolean hasActiveClip() {
        return !CLIP_MARKS.isEmpty();
    }

    private static void trim(int size) {
        while (SHAPES.size() > size) {
            SHAPES.remove(SHAPES.size() - 1);
        }
    }

    private static void applyRecorded(Canvas canvas, int from) {
        int count = SHAPES.size() - from;
        if (count <= 0) {
            // 回调没画出任何形状。旧实现下模板里没有任何 1，之后的绘制会全被拒绝，
            // 也就是整块内容消失。这里选择不加裁剪：宁可多画一点，也不要整块 UI 凭空不见。
            return;
        }
        if (count == 1) {
            // 单形状是绝大多数情况，直接 clipRRect —— 抗锯齿质量最好，也不用建 Path。
            canvas.clipRRect(SHAPES.get(from), true);
            return;
        }
        Path union = Path.makeRRect(SHAPES.get(from));
        try {
            for (int i = from + 1; i < SHAPES.size(); i++) {
                try (Path next = Path.makeRRect(SHAPES.get(i))) {
                    Path merged = Path.makeCombining(union, next, PathOp.UNION);
                    union.close();
                    union = merged;
                }
            }
            canvas.clipPath(union, true);
        } finally {
            union.close();
        }
    }

    // ------------------------------------------------------------------
    // 裁剪盒的替代
    // ------------------------------------------------------------------

    /**
     * 矩形硬裁剪。
     *
     * <p>旧实现要把四个角用 {@code gluProject} 手工投影到窗口坐标、和上一层裁剪盒求交、
     * 再 {@code glScissor}。Skia 里 {@code clipRect} 本身就跟随当前变换并自动与已有裁剪求交，
     * 这一整套换算全部消失——顺带也消掉了它对「回读 GL 矩阵」的依赖，
     * 那是旧实现里最容易因为别处改了矩阵而算错的一环。
     *
     * <p>抗锯齿关掉，对齐旧的 {@code glScissor}：它是逐像素的硬边界。
     * 滚动视口的上下边界用硬边更干净，开抗锯齿会在边界上留一行半透明的残影。
     */
    public static void scissorBegin(double x, double y, double width, double height) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            SCISSOR_MARKS.push(-1);
            return;
        }
        SCISSOR_MARKS.push(SkijaGfx.saveDepth());
        SkijaGfx.pushMatrix();
        canvas.clipRect(Rect.makeXYWH((float) x, (float) y,
                (float) Math.max(0, width), (float) Math.max(0, height)), false);
    }

    public static void scissorEnd() {
        Integer mark = SCISSOR_MARKS.poll();
        if (mark == null) {
            System.err.println("[MuoniumPlayer/SkijaClip] 裁剪盒栈下溢：end 比 begin 多了一次");
            return;
        }
        if (mark >= 0) {
            SkijaGfx.restoreDepthTo(mark);
        }
    }
}
