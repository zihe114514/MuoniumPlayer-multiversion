package com.muoniumplayer.core.rendering;

import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/** 音乐卡的材质：暗色顶层、低处的封面色漫反射与贴着轮廓的细边缘光。 */
final class MusicCardSurface {
    private MusicCardSurface() {
    }

    static void paint(Canvas canvas, double x, double y, double width, double height, double radius,
                      int accent, int secondary, float alpha, boolean frosted) {
        try (Path shape = MusicCardShape.outline(x, y, width, height, radius)) {
            paint(canvas, shape, x, y, width, height, radius, accent, secondary, alpha, frosted);
        }
    }

    static void paint(Canvas canvas, Path shape, double x, double y, double width, double height, double radius,
                      int accent, int secondary, float alpha, boolean frosted) {
        int save = canvas.save();
        try {
            canvas.clipPath(shape, true);
            int top = color(0x010204, alpha * .995);
            int mid = color(0x080B10, alpha * (frosted ? .82 : .99));
            int bottom = color(0x171A20, alpha * (frosted ? .40 : .97));
            fill(canvas, shape, x, y, x, y + height,
                    new int[]{top, mid, bottom}, new float[]{0f, .40f, 1f});

            // 色光只在下半部展开；上半部保持安静，歌词与封面不被彩色雾层盖住。
            lobe(canvas, x + width * .21, y + height * .96, width * .62, height * .76,
                    accent, alpha * .22f);
            lobe(canvas, x + width * .77, y + height * 1.03, width * .68, height * .81,
                    secondary, alpha * .18f);
            lobe(canvas, x + width * .49, y + height * 1.08, width * .47, height * .40,
                    MusicCardColors.mix(accent, secondary, .45), alpha * .12f);

            // 底边反射分成宽的软回光与窄的边缘亮带；不是整圈同亮的白描边。
            reflectedEdge(canvas, shape, x, y, width, height, accent, secondary, alpha);
            try (Shader edge = Shader.makeLinearGradient((float) x, (float) y, (float) x, (float) (y + height),
                    new int[]{color(0xE7EAF0, alpha * .12), color(0xD6DAE5, alpha * .025),
                            color(0xE3EBED, alpha * .035), color(0xFFFFFF, alpha * .16)},
                    new float[]{0f, .13f, .65f, 1f})) {
                Paint paint = SkijaGfx.strokeShader(edge, .68);
                canvas.drawPath(shape, paint);
                paint.setShader(null);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void reflectedEdge(Canvas canvas, Path shape, double x, double y, double width, double height,
                                      int accent, int secondary, float alpha) {
        int save = canvas.save();
        try {
            canvas.clipRect(Rect.makeXYWH((float) x, (float) (y + height * .70), (float) width, (float) (height * .30)));
            try (Shader reflection = Shader.makeLinearGradient((float) x, (float) y, (float) (x + width), (float) y,
                    new int[]{color(accent, 0), color(accent, alpha * .36),
                            color(MusicCardColors.mix(accent, secondary, .5), alpha * .20),
                            color(secondary, alpha * .34), color(secondary, 0)},
                    new float[]{0f, .18f, .50f, .82f, 1f})) {
                Paint paint = SkijaGfx.strokeShader(reflection, 5.0);
                canvas.drawPath(shape, paint);
                paint.setShader(null);
                paint = SkijaGfx.strokeShader(reflection, 1.15);
                canvas.drawPath(shape, paint);
                paint.setShader(null);
            }
            lobe(canvas, x + width * .20, y + height + 1.0, width * .34, height * .17,
                    MusicCardColors.mix(accent, 0xFFFFFF, .20), alpha * .25f);
            lobe(canvas, x + width * .81, y + height + 1.0, width * .30, height * .17,
                    MusicCardColors.mix(secondary, 0xFFFFFF, .15), alpha * .22f);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    static void lobe(Canvas canvas, double centerX, double centerY, double width, double height,
                     int tint, float opacity) {
        if (width <= 0.0 || height <= 0.0 || opacity <= .001f) return;
        int save = canvas.save();
        try {
            canvas.translate((float) centerX, (float) centerY);
            canvas.scale((float) width, (float) height);
            try (Shader light = Shader.makeRadialGradient(0f, 0f, 1f,
                    new int[]{color(tint, opacity), color(tint, opacity * .45), color(tint, 0)},
                    new float[]{0f, .40f, 1f})) {
                Paint paint = SkijaGfx.fillShader(light);
                canvas.drawCircle(0f, 0f, 1f, paint);
                paint.setShader(null);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    static void fill(Canvas canvas, Path shape, double x0, double y0, double x1, double y1,
                     int[] colors, float[] positions) {
        try (Shader gradient = Shader.makeLinearGradient((float) x0, (float) y0, (float) x1, (float) y1, colors, positions)) {
            Paint paint = SkijaGfx.fillShader(gradient);
            canvas.drawPath(shape, paint);
            paint.setShader(null);
        }
    }

    static int color(int rgb, double alpha) {
        return (Math.max(0, Math.min(255, (int) Math.round(alpha * 255.0))) << 24) | (rgb & 0xFFFFFF);
    }
}
