package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.awt.Color;

/**
 * 形状绘制的几何层。
 *
 * <p>门面方法（{@code SharedRenderingConstants} / {@code RenderSystem} / {@code Rect}）的方法体
 * 全部转发到这里，所以「同一个视觉效果的几何定义」只存在一份。
 *
 * <h2>为什么描边的半径要 -2 再加厚度</h2>
 * 旧的 {@code roq.glsl} / {@code rogq.frag} 有两处不显眼的偏移，合起来决定了描边的实际形状：
 * <ol>
 *   <li>{@code ROQShader} / {@code ROGQShader} 传给着色器的是 {@code radius - 2f}，不是调用方给的 radius；</li>
 *   <li>着色器里的 SDF 是 {@code length(max(position - size + radius + border, 0)) - radius}，
 *       描边的<b>外沿</b>落在圆心内缩 {@code radius + border} 处，
 *       也就是外沿圆角半径等于 {@code radius + border}。</li>
 * </ol>
 * 两条合起来：外沿圆角半径 = {@code (调用方 radius - 2) + thickness}，描边整体<b>向内</b>生长，
 * 外沿与矩形边界齐平。thickness 正好等于 2 时外沿半径与调用方给的 radius 相等——
 * 这解释了为什么工程里绝大多数描边都写 thickness=2，也解释了为什么改成
 * 「以 radius 为外沿半径」会让描边与它包住的圆角背景错开一圈。
 * 视觉设计要一比一保留，所以这个偏移原样搬过来。
 *
 * <h2>抗锯齿</h2>
 * 旧着色器用 {@code 1 - smoothstep(0, 1, distance)} 手工做 1 单位宽的过渡带，
 * 形状的「半透明边界」比标称尺寸内缩约 0.5 单位。Skia 的解析抗锯齿边界正好落在标称尺寸上，
 * 即图形比旧实现大约 0.5 单位——远小于一个物理像素（GUI 缩放通常是 2），
 * 肉眼不可分辨，因此不做补偿：补偿反而会让相邻图形之间露出缝。
 */
public final class SkijaShapes {

    private SkijaShapes() {
    }

    // ------------------------------------------------------------------
    // 矩形
    // ------------------------------------------------------------------

    /**
     * 直角矩形，按左上/右下两点给定。
     *
     * <p>调用方给出的两点可能是反的（旧 {@code RenderSystem.drawRect} 显式做过交换），
     * 这里同样先规范化。Skia 的 {@code Rect} 要求 left&lt;=right，不规范化会画不出东西。
     */
    public static void rect(double left, double top, double right, double bottom, int argb) {
        if (SkijaClip.recording()) {
            SkijaClip.record(io.github.humbleui.types.Rect.makeLTRB(
                    (float) Math.min(left, right), (float) Math.min(top, bottom),
                    (float) Math.max(left, right), (float) Math.max(top, bottom)));
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        if (left > right) {
            double t = left;
            left = right;
            right = t;
        }
        if (top > bottom) {
            double t = top;
            top = bottom;
            bottom = t;
        }
        canvas.drawRect(Rect.makeLTRB((float) left, (float) top, (float) right, (float) bottom),
                SkijaGfx.fill(argb));
    }

    // ------------------------------------------------------------------
    // 圆角矩形
    // ------------------------------------------------------------------

    /**
     * 规范化成 Skia 的 {@link RRect}。
     *
     * <p>负的宽高在旧着色器里被显式交换过（{@code RQShader.draw} 开头那两个 if），
     * 半径为负则会让 Skia 画出空图形，所以都在这里夹住。
     * 半径大于短边一半时 Skia 会自己等比缩小，行为与旧 SDF 一致，不用额外处理。
     */
    public static RRect rrect(double x, double y, double width, double height, double radius) {
        if (width < 0) {
            x += width;
            width = -width;
        }
        if (height < 0) {
            y += height;
            height = -height;
        }
        float r = (float) Math.max(radius, 0.0);
        return RRect.makeXYWH((float) x, (float) y, (float) width, (float) height, r);
    }

    public static void roundedRect(double x, double y, double width, double height,
                                   double radius, int argb) {
        if (SkijaClip.recording()) {
            SkijaClip.record(rrect(x, y, width, height, radius));
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        canvas.drawRRect(rrect(x, y, width, height, radius), SkijaGfx.fill(argb));
    }

    /** 用任意 shader 填充圆角矩形。shader 的生命周期由调用方负责。 */
    public static void roundedRectShader(double x, double y, double width, double height,
                                        double radius, Shader shader) {
        if (SkijaClip.recording()) {
            SkijaClip.record(rrect(x, y, width, height, radius));
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        Paint paint = SkijaGfx.fillShader(shader);
        canvas.drawRRect(rrect(x, y, width, height, radius), paint);
        paint.setShader(null);
    }

    // ------------------------------------------------------------------
    // 圆角描边
    // ------------------------------------------------------------------

    /**
     * 描边用的 {@link RRect}：Skia 的描边是以路径为<b>中心线</b>左右各铺一半宽度，
     * 而旧实现的描边完全在矩形<b>内侧</b>。所以把矩形内缩半个厚度，
     * 半径同样减去半个厚度，得到的中心线描出来后外沿正好与原矩形边界齐平。
     */
    private static RRect strokeRRect(double x, double y, double width, double height,
                                     double radius, double thickness) {
        double t = Math.max(thickness, 0.0);
        double outerRadius = Math.max(radius - 2.0 + t, 0.0);
        return rrect(x + t * 0.5, y + t * 0.5, width - t, height - t,
                Math.max(outerRadius - t * 0.5, 0.0));
    }

    public static void roundedOutline(double x, double y, double width, double height,
                                      double radius, double thickness, int argb) {
        if (SkijaClip.recording()) {
            // 用描边当裁剪形状没有意义，但为了不把整块内容裁没，仍按整个圆角矩形登记。
            SkijaClip.record(rrect(x, y, width, height, radius));
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        canvas.drawRRect(strokeRRect(x, y, width, height, radius, thickness),
                SkijaGfx.stroke(argb, thickness));
    }

    /** 四角渐变描边，对应旧的 {@code ROGQShader}。 */
    public static void roundedOutlineGradient(double x, double y, double width, double height,
                                              double radius, double thickness,
                                              Color bottomLeft, Color topLeft,
                                              Color bottomRight, Color topRight) {
        if (SkijaClip.recording()) {
            SkijaClip.record(rrect(x, y, width, height, radius));
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        SkijaEffects.strokeCornerGradient(canvas,
                strokeRRect(x, y, width, height, radius, thickness), thickness,
                x, y, width, height, bottomLeft, topLeft, bottomRight, topRight, 1.0f);
    }

    // ------------------------------------------------------------------
    // 渐变
    // ------------------------------------------------------------------

    /** 四角渐变填充圆角矩形，对应旧的 {@code RQGShader}。 */
    public static void roundedRectCornerGradient(double x, double y, double width, double height,
                                                 double radius,
                                                 Color bottomLeft, Color topLeft,
                                                 Color bottomRight, Color topRight,
                                                 float alpha) {
        if (SkijaClip.recording()) {
            SkijaClip.record(rrect(x, y, width, height, radius));
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        SkijaEffects.fillCornerGradient(canvas, rrect(x, y, width, height, radius),
                x, y, width, height, bottomLeft, topLeft, bottomRight, topRight, alpha);
    }

    /**
     * 径向渐变圆盘：中心一个颜色、边缘另一个。
     *
     * <p>旧实现是一个 {@code GL_TRIANGLE_FAN}：圆心一个顶点色、圆周一圈另一个顶点色，
     * 靠 64 段扇形近似出径向渐变。Skia 的 {@code makeRadialGradient} 是解析求值的，
     * 段数不再影响画质，边缘也不会有多边形棱角。
     */
    public static void radialDisc(double centerX, double centerY, double radius,
                                  int centerArgb, int edgeArgb) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || radius <= 0) {
            return;
        }
        if (SkijaClip.recording()) {
            SkijaClip.record(centerX - radius, centerY - radius, radius * 2, radius * 2, radius);
            return;
        }
        try (Shader shader = Shader.makeRadialGradient((float) centerX, (float) centerY,
                (float) radius, new int[]{centerArgb, edgeArgb})) {
            Paint paint = SkijaGfx.fillShader(shader);
            canvas.drawCircle((float) centerX, (float) centerY, (float) radius, paint);
            paint.setShader(null);
        }
    }

    /**
     * 径向渐变圆环：{@code innerRadius} 处一个颜色、{@code outerRadius} 处另一个，
     * 内圈以内完全透明。
     *
     * <p>旧实现是一个 {@code GL_TRIANGLE_STRIP}：内外两圈顶点各带一个颜色。
     * 换成径向渐变以后连「柔光环」的两段（0→峰值、峰值→0）也可以照原样各画一次，
     * 叠加结果一致。
     */
    public static void radialRing(double centerX, double centerY,
                                  double innerRadius, double outerRadius,
                                  int innerArgb, int outerArgb) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || outerRadius <= 0 || outerRadius <= innerRadius) {
            return;
        }
        if (SkijaClip.recording()) {
            SkijaClip.record(centerX - outerRadius, centerY - outerRadius,
                    outerRadius * 2, outerRadius * 2, outerRadius);
            return;
        }
        float split = (float) Math.max(0.0, Math.min(1.0, innerRadius / outerRadius));
        int transparentInner = innerArgb & 0x00FFFFFF;
        try (Shader shader = Shader.makeRadialGradient((float) centerX, (float) centerY,
                (float) outerRadius,
                new int[]{transparentInner, innerArgb, outerArgb},
                new float[]{0f, split, 1f})) {
            Paint paint = SkijaGfx.fillShader(shader);
            canvas.drawCircle((float) centerX, (float) centerY, (float) outerRadius, paint);
            paint.setShader(null);
        }
    }

    /**
     * 直角矩形的单方向线性渐变，对应旧的 {@code RenderSystem.drawGradientRect*}。
     *
     * <p>这一类只有两个颜色、方向单一，用 Skia 现成的线性渐变就是精确等价，
     * 不需要走 {@link SkijaEffects} 的自定义着色器。
     */
    public static void gradientRect(double left, double top, double right, double bottom,
                                    int startArgb, int endArgb, boolean horizontal) {
        if (SkijaClip.recording()) {
            SkijaClip.record(io.github.humbleui.types.Rect.makeLTRB(
                    (float) Math.min(left, right), (float) Math.min(top, bottom),
                    (float) Math.max(left, right), (float) Math.max(top, bottom)));
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        if (left > right) {
            double t = left;
            left = right;
            right = t;
        }
        if (top > bottom) {
            double t = top;
            top = bottom;
            bottom = t;
        }
        float x0 = (float) left;
        float y0 = (float) top;
        float x1 = horizontal ? (float) right : (float) left;
        float y1 = horizontal ? (float) top : (float) bottom;
        try (Shader shader = Shader.makeLinearGradient(x0, y0, x1, y1,
                new int[]{startArgb, endArgb})) {
            Paint paint = SkijaGfx.fillShader(shader);
            canvas.drawRect(Rect.makeLTRB((float) left, (float) top,
                    (float) right, (float) bottom), paint);
            paint.setShader(null);
        }
    }

    /**
     * 圆角矩形的单方向线性渐变。
     *
     * <p>旧实现走的是四角渐变着色器（把两个颜色各给两个角），所以这里也保留
     * {@code radius} 的语义，只是用精确等价的线性渐变来画。
     */
    public static void roundedRectGradient(double x, double y, double width, double height,
                                           double radius, int startArgb, int endArgb,
                                           boolean horizontal) {
        if (SkijaClip.recording()) {
            SkijaClip.record(rrect(x, y, width, height, radius));
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        float x1 = horizontal ? (float) (x + width) : (float) x;
        float y1 = horizontal ? (float) y : (float) (y + height);
        try (Shader shader = Shader.makeLinearGradient((float) x, (float) y, x1, y1,
                new int[]{startArgb, endArgb})) {
            Paint paint = SkijaGfx.fillShader(shader);
            canvas.drawRRect(rrect(x, y, width, height, radius), paint);
            paint.setShader(null);
        }
    }
}
