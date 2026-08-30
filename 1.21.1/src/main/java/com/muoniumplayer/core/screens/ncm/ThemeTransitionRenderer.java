package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.gl.GL11;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;

import java.awt.Color;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;

/**
 * 播放器主题的视觉过渡层。颜色本身由 {@link NCMTheme} 平滑插值，本类负责
 * 从主题按钮向播放器四角扩散的柔软波浪，以及液态玻璃的折射高光。
 */
public final class ThemeTransitionRenderer implements SharedRenderingConstants {

    private static final long WAVE_DURATION_NANOS = 1_180_000_000L;
    private static final int CIRCLE_SEGMENTS = 88;

    private long waveStartedAt = -1L;
    private double originX;
    private double originY;
    private int targetAccent = 0x64A8FF;

    public void begin(double originX, double originY, int targetAccent) {
        this.originX = originX;
        this.originY = originY;
        this.targetAccent = targetAccent & 0xFFFFFF;
        this.waveStartedAt = System.nanoTime();
    }

    public boolean isActive() {
        if (this.waveStartedAt < 0L) return false;
        if (System.nanoTime() - this.waveStartedAt >= WAVE_DURATION_NANOS) {
            this.waveStartedAt = -1L;
            return false;
        }
        return true;
    }

    public void renderWave(double panelX, double panelY, double panelWidth, double panelHeight, float screenAlpha) {
        if (!this.isActive() || screenAlpha <= 0.001f) return;

        float linear = clamp01((float) (System.nanoTime() - this.waveStartedAt) / (float) WAVE_DURATION_NANOS);
        float expansion = easeOutQuint(linear);
        float envelope = (float) Math.sin(Math.PI * linear);
        double maximumRadius = farthestCornerDistance(panelX, panelY, panelWidth, panelHeight, this.originX, this.originY);
        double waveRadius = maximumRadius * (0.025 + expansion * 1.06);
        double softness = Math.max(10.0, Math.min(34.0, maximumRadius * (0.055 + (1.0 - linear) * 0.018)));

        int accent = this.targetAccent;
        int highlight = mixColor(accent, 0xFFFFFF, 0.48f);
        float alpha = screenAlpha * envelope;

        beginSmoothShapes();
        // 先铺一层极淡的柔光，让主题颜色不以硬边瞬间替换。
        drawRadialDisc(this.originX, this.originY, waveRadius * 0.98,
                accent, 0.020f * alpha, 0.0f);
        // 主波峰 + 两层有相位差的尾波，形成液体涟漪感。
        drawSoftRing(this.originX, this.originY, waveRadius, softness,
                highlight, 0.19f * alpha);
        drawSoftRing(this.originX, this.originY,
                Math.max(0.0, waveRadius - softness * 1.35), softness * 0.78,
                accent, 0.115f * alpha);
        drawSoftRing(this.originX, this.originY,
                Math.max(0.0, waveRadius - softness * 2.45), softness * 0.62,
                highlight, 0.055f * alpha);
        endSmoothShapes();
    }

    /** 绘制在基础背景之上、内容之下的液态玻璃反射层。 */
    public void renderLiquidGlassSurface(double x, double y, double width, double height,
                                         double radius, float opacity) {
        opacity = clamp01(opacity);
        if (opacity <= 0.001f || width <= 0.0 || height <= 0.0) return;

        drawGradientCornerLR(x, y, width, height, radius,
                color(218, 239, 255, 28.0f * opacity),
                color(92, 142, 232, 18.0f * opacity));
        roundedRectGradientVertical(x, y, width, height, radius,
                color(255, 255, 255, 23.0f * opacity),
                color(92, 142, 232, 3.0f * opacity));

        // 极慢的漂移只改变反射，不移动任何 UI，保持文字和点击区域稳定。
        double phase = System.nanoTime() / 1_000_000_000.0 * 0.34;
        double driftX = Math.sin(phase) * width * 0.012;
        double driftY = Math.cos(phase * 0.83) * height * 0.014;
        float breathe = 0.92f + (float) Math.sin(phase * 1.17) * 0.08f;

        beginSmoothShapes();
        drawRadialDisc(x + width * 0.18 + driftX, y + height * 0.10 + driftY, width * 0.48,
                0xE9F7FF, 0.105f * opacity * breathe, 0.0f);
        drawRadialDisc(x + width * 0.86 - driftX * 0.7, y + height * 0.78 - driftY, width * 0.42,
                0x708CFF, 0.075f * opacity, 0.0f);
        drawRadialDisc(x + width * 0.54 + driftX * 0.35, y + height * 0.18 - driftY * 0.45, width * 0.24,
                0xFFFFFF, 0.040f * opacity * breathe, 0.0f);
        endSmoothShapes();
    }

    private static void beginSmoothShapes() {
        SkijaGfx.pushAttrib();
        SkijaGfx.setBlendMode(io.github.humbleui.skija.BlendMode.SRC_OVER);
    }

    private static void endSmoothShapes() {
        SkijaGfx.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        SkijaGfx.popAttrib();
    }

    private static void drawRadialDisc(double centerX, double centerY, double radius,
                                       int color, float centerAlpha, float edgeAlpha) {
        if (radius <= 0.01 || centerAlpha <= 0.001f) return;
        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;

        // 旧实现用 CIRCLE_SEGMENTS 段的扇形近似径向渐变（圆心一个顶点色、圆周一圈另一个）。
        // 换成 Skia 的解析径向渐变：段数不再影响画质，边缘也不会有多边形棱角。
        SkijaShapes.radialDisc(centerX, centerY, radius,
                SkijaGfx.argb(red, green, blue, clamp01(centerAlpha)),
                SkijaGfx.argb(red, green, blue, clamp01(edgeAlpha)));
    }

    private static void drawSoftRing(double centerX, double centerY, double radius,
                                     double width, int color, float peakAlpha) {
        if (radius <= 0.01 || width <= 0.01 || peakAlpha <= 0.001f) return;
        double inner = Math.max(0.0, radius - width);
        double middle = radius;
        double outer = radius + width;
        drawRingStrip(centerX, centerY, inner, middle, color, 0.0f, peakAlpha);
        drawRingStrip(centerX, centerY, middle, outer, color, peakAlpha, 0.0f);
    }

    private static void drawRingStrip(double centerX, double centerY, double innerRadius,
                                      double outerRadius, int color, float innerAlpha, float outerAlpha) {
        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;

        SkijaShapes.radialRing(centerX, centerY, innerRadius, outerRadius,
                SkijaGfx.argb(red, green, blue, clamp01(innerAlpha)),
                SkijaGfx.argb(red, green, blue, clamp01(outerAlpha)));
    }

    private static double farthestCornerDistance(double x, double y, double width, double height,
                                                  double originX, double originY) {
        double d1 = distance(originX, originY, x, y);
        double d2 = distance(originX, originY, x + width, y);
        double d3 = distance(originX, originY, x, y + height);
        double d4 = distance(originX, originY, x + width, y + height);
        return Math.max(Math.max(d1, d2), Math.max(d3, d4));
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static float easeOutQuint(float value) {
        float inverse = 1.0f - clamp01(value);
        return 1.0f - inverse * inverse * inverse * inverse * inverse;
    }

    private static int mixColor(int first, int second, float amount) {
        amount = clamp01(amount);
        int r = Math.round(((first >> 16) & 0xFF) + (((second >> 16) & 0xFF) - ((first >> 16) & 0xFF)) * amount);
        int g = Math.round(((first >> 8) & 0xFF) + (((second >> 8) & 0xFF) - ((first >> 8) & 0xFF)) * amount);
        int b = Math.round((first & 0xFF) + ((second & 0xFF) - (first & 0xFF)) * amount);
        return (r << 16) | (g << 8) | b;
    }

    private static Color color(int red, int green, int blue, float alpha) {
        return new Color(red, green, blue, Math.max(0, Math.min(255, Math.round(alpha))));
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
