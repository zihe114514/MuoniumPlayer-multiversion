package com.muoniumplayer.core.rendering.entities.impl;

import lombok.Setter;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.StencilClipManager;
import com.muoniumplayer.core.rendering.animation.Animation;
import com.muoniumplayer.core.rendering.animation.Easing;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.utils.timing.Timer;
import com.muoniumplayer.render.skija.SkijaGfx;

import java.time.Duration;

/**
 * @author MuoniumPlayer Contributors
 * @since 2024/9/17 22:32
 */
public class ScrollText implements SharedConstants {

    public ScrollText() {

    }

    Timer t = new Timer();

    double scrollOffset = 0;

    String cachedText = "";

    @Setter
    long waitTime = 2500;

    @Setter
    boolean oneShot = false;

    public Animation anim = new Animation(Easing.LINEAR, Duration.ofMillis(0));

    public void reset() {
        t.reset();
        scrollOffset = 0;
        anim.reset();
        anim.setStartValue(0);
        anim.setValue(0);
        leftGradientAlpha = 1f;
        isScrolling = false;
    }

    float leftGradientAlpha = 1f;
    public boolean isScrolling = false;

    public void render(CFontRenderer fr, String text, double x, double y, double width, int color) {
        // Layout can transiently produce a negative max width while a panel is animating or being
        // rebuilt. Skia Rect::makeXYWH rejects negative widths, and one bad label must not take down
        // the whole screen, so treat every non-positive/non-finite width as "no drawable space".
        if (fr == null || text == null || text.isEmpty()
                || !Double.isFinite(width) || width <= 0.0) {
            isScrolling = false;
            scrollOffset = 0.0;
            return;
        }

        if (!cachedText.equals(text)) {
            cachedText = text;

            this.reset();
        }

        double w = fr.getStringWidthD(text);

        if (w > width) {

            isScrolling = true;

            double gradWidth = Math.min(6.0, Math.max(0.0, width * .25));
            double exp = 2;

            // 旧实现：把文字画进一个全屏离屏帧缓冲，再把「左右两端渐隐」的遮罩画进第二个
            // 离屏帧缓冲，最后用 stencil.frag 把两者相乘。为此还要自己压一套正交投影
            // （因为离屏帧缓冲是全屏像素尺寸的），一共两个全屏 RGBA 纹理外加一次全屏合成。
            //
            // 换成 Skia 只需要一个<b>刚好这么大</b>的图层：文字画上去，
            // 再用两条横向 alpha 渐变以 DST_IN 把两端的 alpha 乘下去。
            // 没有全屏纹理、没有自己搭投影、没有第二遍全屏合成。
            io.github.humbleui.skija.Canvas canvas = SkijaGfx.canvas();
            if (canvas == null) return;
            double layerHeight = Math.max(1.0, fr.getHeight() + exp * 2);
            io.github.humbleui.types.Rect bounds = io.github.humbleui.types.Rect.makeXYWH(
                    (float) x, (float) (y - exp), (float) width, (float) layerHeight);
            int layer = canvas == null ? -1 : canvas.saveLayer(bounds, null);
            try {
                StencilClipManager.beginClip(() -> Rect.draw(x, y - exp, width, layerHeight, -1, Rect.RectType.EXPAND));

                fr.drawString(text, x + scrollOffset, y, color);

                double dest = -(w - width + 4);

                if (anim.getDuration() != 0) {
                    scrollOffset = anim.run(dest);
                } else {

                    String s = "    ";

                    dest = -(w + fr.getStringWidth(s));

                    boolean delayed = t.isDelayed(waitTime);
                    if (delayed) {
                        scrollOffset = Interpolations.interpolateLinear((float) scrollOffset, (float) dest, 2f);
                        this.leftGradientAlpha = Interpolations.interpolateLinear(this.leftGradientAlpha, 0f, 0.5f);
                    }

                    fr.drawString(s + text, x + w + scrollOffset, y, color);

                    double delta = scrollOffset - dest;

                    if (delta == 0) {
                        scrollOffset = 0;
                        t.reset();
                    }

                    if (delta <= gradWidth + 1) {
                        this.leftGradientAlpha = 1f;
                    }

                }

                StencilClipManager.endClip();

                if (canvas != null) {
                    // 左端：从 leftGradientAlpha 渐变到全不透明；右端：全不透明渐变到全透明。
                    // 中段旧实现画的是纯不透明白，对 alpha 是恒等变换，直接省掉。
                    fadeEdge(canvas, x, y - exp, gradWidth, layerHeight,
                            RGBA.color(1f, 1f, 1f, this.leftGradientAlpha), 0xFFFFFFFF);
                    fadeEdge(canvas, x + width - gradWidth, y - exp, gradWidth, layerHeight,
                            0xFFFFFFFF, 0x00FFFFFF);
                }
            } finally {
                if (layer >= 0) {
                    canvas.restoreToCount(layer);
                }
            }
        } else {

            isScrolling = false;

            scrollOffset = 0;
            fr.drawString(text, x, y, color);
        }

    }

    /**
     * 用一条横向 alpha 渐变把图层在这一小块里的 alpha 乘下去。
     *
     * <p>{@code DST_IN} 的效果是 {@code Da *= Sa}，而且只作用在实际画到的那一块上，
     * 所以左右两端可以各画一次、互不干扰。
     */
    private static void fadeEdge(io.github.humbleui.skija.Canvas canvas,
                                 double x, double y, double width, double height,
                                 int fromArgb, int toArgb) {
        if (canvas == null || !Double.isFinite(width) || width <= 0.0
                || !Double.isFinite(height) || height <= 0.0) return;
        io.github.humbleui.types.Rect rect = io.github.humbleui.types.Rect.makeXYWH(
                (float) x, (float) y, (float) width, (float) height);
        try (io.github.humbleui.skija.Shader gradient =
                     io.github.humbleui.skija.Shader.makeLinearGradient(
                             (float) x, (float) y, (float) (x + width), (float) y,
                             new int[]{fromArgb, toArgb})) {
            io.github.humbleui.skija.Paint paint = SkijaGfx.fillShader(gradient);
            paint.setBlendMode(io.github.humbleui.skija.BlendMode.DST_IN);
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            paint.setBlendMode(SkijaGfx.blendMode());
        }
    }

}
