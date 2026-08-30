package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Shader;

/**
 * 绘制状态与 Paint 复用池。
 *
 * <p>这个类替代的是旧渲染层里散落在各处的固定管线状态：{@code glColor4f} 的「当前颜色」、
 * {@code glBlendFunc} 的混合方程、{@code glPushMatrix/glPushAttrib} 的两个状态栈。
 * Skia 没有全局状态，颜色与混合模式都挂在 {@link Paint} 上，因此必须由这里替它们记账。
 *
 * <h2>为什么要复用 Paint</h2>
 * {@link Paint} 是 native 对象，构造与销毁都要过 JNI 并进 Cleaner 队列。一帧里画几百个
 * 矩形，如果每次 {@code new Paint()}，光是原生对象的分配回收就足以吃掉整帧预算。
 * 这里只保留两个实例（填充 / 描边），每次绘制前把字段改成本次需要的值。
 *
 * <p>因此有一条硬性约定：<b>拿到的 Paint 只在本次绘制调用内有效</b>，
 * 不能存起来跨调用使用。
 *
 * <h2>两个独立的状态栈</h2>
 * OpenGL 的矩阵栈（{@code glPushMatrix}）与属性栈（{@code glPushAttrib}）是两个互不相干的栈，
 * 业务代码里确实存在交错 push/pop 的写法。所以这里也分成两套：
 * <ul>
 *   <li>矩阵栈 → {@link Canvas#save()} / {@link Canvas#restore()}</li>
 *   <li>属性栈 → 只压弹本类的颜色与混合模式，<b>不碰 canvas</b></li>
 * </ul>
 * 如果属性栈也去 save/restore canvas，交错的 push/pop 会让 canvas 的栈错位，
 * 后果是整块 UI 的变换错乱。
 *
 * <p>两个栈都做了深度保护：多余的 pop 被忽略，不会把 canvas 弹穿到宿主层的基准深度之下。
 */
public final class SkijaGfx {

    /** 默认混合方程：等价于旧代码里到处写的 {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA}。 */
    private static final BlendMode DEFAULT_BLEND = BlendMode.SRC_OVER;

    private static Paint fillPaint;
    private static Paint strokePaint;

    /** 当前颜色，ARGB。对应 {@code glColor4f}。默认不透明白，与 GL 一致。 */
    private static int currentColor = 0xFFFFFFFF;
    private static BlendMode currentBlend = DEFAULT_BLEND;

    // ---- 属性栈（颜色 + 混合模式）----
    private static int[] colorStack = new int[64];
    private static BlendMode[] blendStack = new BlendMode[64];
    private static int attribDepth;

    // ---- 矩阵栈深度（用于拦住多余的 popMatrix）----
    private static int matrixDepth;

    /** 遇到没有对应 BlendMode 的 GL 混合因子组合时，只抱怨一次。 */
    private static boolean warnedUnknownBlend;

    private SkijaGfx() {
    }

    // ------------------------------------------------------------------
    // 帧边界
    // ------------------------------------------------------------------

    /**
     * 由 {@link SkijaHost#beginFrame()} 调用。把上一帧可能泄漏的状态清干净，
     * 保证每帧都从「不透明白 + SRC_OVER + 空栈」开始，行为与旧代码每帧
     * {@code glPushAttrib(GL_ALL_ATTRIB_BITS)} 一致。
     */
    static void beginFrame() {
        currentColor = 0xFFFFFFFF;
        currentBlend = DEFAULT_BLEND;
        attribDepth = 0;
        matrixDepth = 0;
        boundTexture = 0;
        linearFiltering = false;
    }

    /** 由 {@link SkijaHost#endFrame()} 调用。 */
    static void endFrame() {
        attribDepth = 0;
        matrixDepth = 0;
    }

    /** 帧内且宿主层可用时为 true。false 时所有绘制都必须整段跳过。 */
    public static boolean active() {
        return SkijaHost.inFrame() && SkijaHost.canvas() != null;
    }

    /** 当前帧的 canvas，帧外为 null。 */
    public static Canvas canvas() {
        return SkijaHost.canvas();
    }

    // ------------------------------------------------------------------
    // Paint 取用
    // ------------------------------------------------------------------

    private static Paint fillPaint() {
        if (fillPaint == null) {
            fillPaint = new Paint().setAntiAlias(true);
        }
        return fillPaint;
    }

    private static Paint strokePaint() {
        if (strokePaint == null) {
            strokePaint = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE);
        }
        return strokePaint;
    }

    /**
     * 纯色填充用的 Paint。
     *
     * <p>每次都显式清掉 shader / colorFilter / maskFilter / imageFilter。渐变与模糊用完
     * 如果不清，会污染下一次本该是纯色的绘制——而且症状是随机的（取决于绘制顺序），
     * 极难定位。清空的代价是四次 JNI 调用，可以忽略。
     */
    public static Paint fill(int argb) {
        Paint p = fillPaint();
        p.setShader(null);
        p.setColorFilter(null);
        p.setMaskFilter(null);
        p.setImageFilter(null);
        p.setMode(PaintMode.FILL);
        p.setColor(argb);
        p.setBlendMode(currentBlend);
        return p;
    }

    public static Paint fill(float r, float g, float b, float a) {
        return fill(argb(r, g, b, a));
    }

    /** 渐变 / 贴图填充用的 Paint。颜色置成不透明白，避免额外调制。 */
    public static Paint fillShader(Shader shader) {
        Paint p = fillPaint();
        p.setColorFilter(null);
        p.setMaskFilter(null);
        p.setImageFilter(null);
        p.setMode(PaintMode.FILL);
        p.setColor(0xFFFFFFFF);
        p.setShader(shader);
        p.setBlendMode(currentBlend);
        return p;
    }

    public static Paint stroke(int argb, double width) {
        Paint p = strokePaint();
        p.setShader(null);
        p.setColorFilter(null);
        p.setMaskFilter(null);
        p.setImageFilter(null);
        p.setMode(PaintMode.STROKE);
        p.setStrokeWidth((float) Math.max(width, 0.0));
        p.setColor(argb);
        p.setBlendMode(currentBlend);
        return p;
    }

    public static Paint strokeShader(Shader shader, double width) {
        Paint p = strokePaint();
        p.setColorFilter(null);
        p.setMaskFilter(null);
        p.setImageFilter(null);
        p.setMode(PaintMode.STROKE);
        p.setStrokeWidth((float) Math.max(width, 0.0));
        p.setColor(0xFFFFFFFF);
        p.setShader(shader);
        p.setBlendMode(currentBlend);
        return p;
    }

    // ------------------------------------------------------------------
    // 当前颜色（旧 glColor4f）
    // ------------------------------------------------------------------

    public static void setColor(int argb) {
        currentColor = argb;
    }

    public static void setColor(float r, float g, float b, float a) {
        currentColor = argb(r, g, b, a);
    }

    public static void resetColor() {
        currentColor = 0xFFFFFFFF;
    }

    public static int color() {
        return currentColor;
    }

    /** 当前颜色的 alpha，0..1。贴图与文字绘制要用它做整体调制。 */
    public static float colorAlpha() {
        return ((currentColor >>> 24) & 0xFF) / 255.0f;
    }

    // ------------------------------------------------------------------
    // 当前绑定的纹理与采样方式（旧固定管线的隐式状态）
    // ------------------------------------------------------------------

    private static int boundTexture;
    private static boolean linearFiltering;

    /**
     * 记住「当前绑定的纹理」。
     *
     * <p>旧代码有一批绘制入口是隐式的：先 {@code glBindTexture}，再画一个矩形，
     * 纹理从当前 GL 状态取。Skia 里每次绘制都要显式给出图像，所以这个隐式状态
     * 得由这里替它记着。真正的 {@code glBindTexture} 不再发出——
     * Skia 自己管纹理绑定，在它中间插入裸 GL 绑定会让它的状态缓存失真。
     */
    public static void setBoundTexture(int glId) {
        boundTexture = glId;
    }

    public static int boundTexture() {
        return boundTexture;
    }

    /** 对应旧的 {@code RenderSystem.linearFilter()} / {@code nearestFilter()}。 */
    public static void setLinearFiltering(boolean linear) {
        linearFiltering = linear;
    }

    public static boolean linearFiltering() {
        return linearFiltering;
    }

    // ------------------------------------------------------------------
    // 混合模式
    // ------------------------------------------------------------------

    public static void setBlendMode(BlendMode mode) {
        currentBlend = mode == null ? DEFAULT_BLEND : mode;
    }

    public static BlendMode blendMode() {
        return currentBlend;
    }

    public static void resetBlendMode() {
        currentBlend = DEFAULT_BLEND;
    }

    /**
     * 把 GL 的混合因子组合翻译成 Skia 的 {@link BlendMode}。
     *
     * <p>这不是一一对应的：GL 允许任意因子组合，Skia 只有一组预定义方程。业务代码实际用到的
     * 组合就那么几种，逐一列出；表外的组合退回 {@code SRC_OVER} 并打<b>一次</b>日志，
     * 这样既不会刷屏，也不会让「悄悄画错」无从察觉。
     */
    public static BlendMode fromGlBlendFunc(int src, int dst) {
        // GL 枚举值写成字面量，避免依赖将要删掉的 compat/gl。
        final int ZERO = 0x0000;
        final int ONE = 0x0001;
        final int SRC_COLOR = 0x0300;
        final int ONE_MINUS_SRC_COLOR = 0x0301;
        final int SRC_ALPHA = 0x0302;
        final int ONE_MINUS_SRC_ALPHA = 0x0303;
        final int DST_ALPHA = 0x0304;
        final int ONE_MINUS_DST_ALPHA = 0x0305;
        final int DST_COLOR = 0x0306;

        if (src == SRC_ALPHA && dst == ONE_MINUS_SRC_ALPHA) return BlendMode.SRC_OVER;
        if (src == ONE && dst == ONE_MINUS_SRC_ALPHA) return BlendMode.SRC_OVER;
        if (src == ONE && dst == ZERO) return BlendMode.SRC;
        if (src == ZERO && dst == ONE) return BlendMode.DST;
        if (src == ONE && dst == ONE) return BlendMode.PLUS;
        if (src == SRC_ALPHA && dst == ONE) return BlendMode.PLUS;
        if (src == ONE_MINUS_DST_ALPHA && dst == ONE) return BlendMode.DST_OVER;
        if (src == ONE_MINUS_DST_ALPHA && dst == DST_ALPHA) return BlendMode.DST_OVER;
        if (src == DST_COLOR && dst == ZERO) return BlendMode.MODULATE;
        if (src == ZERO && dst == SRC_COLOR) return BlendMode.MODULATE;
        if (src == DST_ALPHA && dst == ZERO) return BlendMode.DST_IN;
        if (src == ZERO && dst == SRC_ALPHA) return BlendMode.SRC_IN;
        if (src == ZERO && dst == ONE_MINUS_SRC_ALPHA) return BlendMode.DST_OUT;
        if (src == ONE_MINUS_DST_ALPHA && dst == ZERO) return BlendMode.SRC_OUT;
        if (src == ONE_MINUS_SRC_COLOR && dst == ONE_MINUS_SRC_ALPHA) return BlendMode.SRC_OVER;

        if (!warnedUnknownBlend) {
            warnedUnknownBlend = true;
            System.out.println("[MuoniumPlayer/SkijaGfx] 未映射的 GL 混合因子组合 src=0x"
                    + Integer.toHexString(src) + " dst=0x" + Integer.toHexString(dst)
                    + "，已退回 SRC_OVER（后续同类不再提示）");
        }
        return DEFAULT_BLEND;
    }

    // ------------------------------------------------------------------
    // 矩阵栈
    // ------------------------------------------------------------------

    public static void pushMatrix() {
        Canvas c = canvas();
        if (c == null) {
            return;
        }
        c.save();
        matrixDepth++;
    }

    /** 多余的 pop 被忽略：绝不能把 canvas 弹到宿主层的基准深度之下。 */
    public static void popMatrix() {
        Canvas c = canvas();
        if (c == null || matrixDepth <= 0) {
            return;
        }
        c.restore();
        matrixDepth--;
    }

    /**
     * 当前矩阵栈深度。配合 {@link #restoreDepthTo(int)} 用于「不管中间压了多少层，
     * 一次性回到某个基准」的场景（裁剪的 begin/end 就是这样）。
     *
     * <p>为什么不直接用 {@code Canvas.getSaveCount()} + {@code restoreToCount()}：
     * 那样会绕过本类的深度计数，之后一次多余的 {@link #popMatrix()} 就会把 canvas
     * 弹到宿主层的基准之下，整帧的坐标系报废。所有 save/restore 都必须经过这里记账。
     */
    public static int saveDepth() {
        return matrixDepth;
    }

    /** 连续 restore 到指定深度。目标深度比当前还深时什么都不做。 */
    public static void restoreDepthTo(int depth) {
        Canvas c = canvas();
        if (c == null) {
            matrixDepth = Math.max(depth, 0);
            return;
        }
        while (matrixDepth > depth && matrixDepth > 0) {
            c.restore();
            matrixDepth--;
        }
    }

    public static void translate(double x, double y) {
        Canvas c = canvas();
        if (c != null) {
            c.translate((float) x, (float) y);
        }
    }

    public static void scale(double x, double y) {
        Canvas c = canvas();
        if (c != null) {
            c.scale((float) x, (float) y);
        }
    }

    /**
     * 绕 z 轴旋转。旧代码只用 {@code glRotatef(angle, 0, 0, 1)} 这一种形式；
     * 绕 x/y 轴旋转是真正的 3D 变换，由 {@link SkijaHost} 之外的调用方用 Matrix44 自行处理。
     */
    public static void rotateZ(float degrees) {
        Canvas c = canvas();
        if (c != null) {
            c.rotate(degrees);
        }
    }

    /**
     * 叠加一个 4×4 变换，用来表达 2D 变换表达不出来的东西（透视投影、绕 x/y 轴的三维旋转）。
     *
     * <p>Skia 的 canvas 变换本身就是 4×4 的，支持透视除法，所以封面流那种
     * 「透视 + 绕 y 轴翻转」可以直接交给它，不需要另搭一套投影管线。
     */
    public static void concat(io.github.humbleui.skija.Matrix44 matrix) {
        Canvas c = canvas();
        if (c != null && matrix != null) {
            c.concat(matrix);
        }
    }

    /** 绕 y 轴的三维旋转（角度制）。 */
    public static void rotateY(float degrees) {
        double rad = Math.toRadians(degrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        concat(new io.github.humbleui.skija.Matrix44(
                cos, 0f, sin, 0f,
                0f, 1f, 0f, 0f,
                -sin, 0f, cos, 0f,
                0f, 0f, 0f, 1f));
    }

    /** 绕 x 轴的三维旋转（角度制）。 */
    public static void rotateX(float degrees) {
        double rad = Math.toRadians(degrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        concat(new io.github.humbleui.skija.Matrix44(
                1f, 0f, 0f, 0f,
                0f, cos, -sin, 0f,
                0f, sin, cos, 0f,
                0f, 0f, 0f, 1f));
    }

    /**
     * 剪切变换。{@code x' = x + sx * y}，{@code y' = y + sy * x}。
     * 用来模拟斜体（字体文件里没有斜体字形时）。
     */
    public static void skew(double sx, double sy) {
        Canvas c = canvas();
        if (c != null) {
            c.skew((float) sx, (float) sy);
        }
    }

    // ------------------------------------------------------------------
    // 属性栈
    // ------------------------------------------------------------------

    public static void pushAttrib() {
        if (attribDepth == colorStack.length) {
            int[] nc = new int[colorStack.length * 2];
            BlendMode[] nb = new BlendMode[blendStack.length * 2];
            System.arraycopy(colorStack, 0, nc, 0, colorStack.length);
            System.arraycopy(blendStack, 0, nb, 0, blendStack.length);
            colorStack = nc;
            blendStack = nb;
        }
        colorStack[attribDepth] = currentColor;
        blendStack[attribDepth] = currentBlend;
        attribDepth++;
    }

    public static void popAttrib() {
        if (attribDepth <= 0) {
            return;
        }
        attribDepth--;
        currentColor = colorStack[attribDepth];
        currentBlend = blendStack[attribDepth];
    }

    // ------------------------------------------------------------------
    // 颜色工具
    // ------------------------------------------------------------------

    /** 0..1 的分量转 ARGB。超范围先夹紧：旧代码里累加出来的 alpha 偶尔会略微越界。 */
    public static int argb(float r, float g, float b, float a) {
        return (clamp255(a) << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(float v) {
        int i = Math.round(v * 255.0f);
        if (i < 0) return 0;
        if (i > 255) return 255;
        return i;
    }

    /** 把 ARGB 的 alpha 再乘一个系数，用于「整层淡入淡出」。 */
    public static int mulAlpha(int argb, float factor) {
        int a = Math.round(((argb >>> 24) & 0xFF) * factor);
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** {@code java.awt.Color} 转 ARGB。业务代码大量使用 AWT 的 Color 传色。 */
    public static int argb(java.awt.Color color) {
        return (color.getAlpha() << 24) | (color.getRed() << 16)
                | (color.getGreen() << 8) | color.getBlue();
    }

    /** 释放复用的 Paint。只在渲染层整体关闭时调用。 */
    static void dispose() {
        if (fillPaint != null) {
            fillPaint.close();
            fillPaint = null;
        }
        if (strokePaint != null) {
            strokePaint.close();
            strokePaint = null;
        }
    }
}
