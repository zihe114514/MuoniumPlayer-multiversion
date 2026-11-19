package com.muoniumplayer.core.rendering;

import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/** 封面色渐变进度：末端是光源，柔软扇形向已经播放的一侧回照。 */
final class MusicCardProgress {
    static final double TRACK_HEIGHT = 4.0;
    static final double BEAM_LENGTH = 60.0;
    static final double BEAM_SPREAD = 9.5;
    static final double BEAM_SPREAD_RATE = .18;
    static final float BEAM_SIGMA = 2.8f;
    static final float BEAM_OPACITY = .50f;
    static final double THREAD_SPREAD = 2.4;
    static final float THREAD_SIGMA = 1.1f;
    static final float THREAD_OPACITY = .34f;
    static final float TIP_SIGMA = 1.35f;
    private static final float[] BODY_STOPS = {0f, .38f, .68f, .90f, 1f};
    private static final float[] BEAM_STOPS = {0f, .24f, .60f, .87f, 1f};

    private MusicCardProgress() {
    }

    static void draw(Canvas canvas, double x, double y, double width, double fraction,
                     int coverColor, float alpha) {
        if (canvas == null || width <= 0.0 || !Double.isFinite(width) || alpha <= .001f) return;
        float opacity = Math.min(1f, alpha);
        double half = TRACK_HEIGHT * .5;
        canvas.drawRRect(RRect.makeXYWH((float) x, (float) (y - half), (float) width,
                (float) TRACK_HEIGHT, (float) half), SkijaGfx.fill(color(0xFFFFFF, opacity * .16)));
        double progress = Double.isFinite(fraction) ? Math.max(0.0, Math.min(1.0, fraction)) : 0.0;
        double filled = width * progress;
        if (filled <= .2) return;
        double tip = x + filled;
        double beamLength = Math.min(BEAM_LENGTH, filled);
        double spread = Math.min(BEAM_SPREAD, beamLength * BEAM_SPREAD_RATE);
        int pale = MusicCardColors.mix(coverColor, 0xFFFFFF, .66);

        int save = canvas.save();
        try {
            // 光向左展开；右侧只留圆帽的一点柔光，不让未播放轨道也变成亮带。
            canvas.clipRect(Rect.makeLTRB((float) x, (float) (y - spread - BEAM_SIGMA * 3.0),
                    (float) (tip + half * .75), (float) (y + spread + BEAM_SIGMA * 3.0)));
            drawBeam(canvas, tip, y, beamLength, spread, coverColor, opacity * BEAM_OPACITY, BEAM_SIGMA);
            drawBeam(canvas, tip, y, beamLength * .88, Math.min(THREAD_SPREAD, spread * .36),
                    pale, opacity * THREAD_OPACITY, THREAD_SIGMA);
            double core = Math.min(8.0, filled);
            canvas.drawRRect(RRect.makeXYWH((float) (tip - core), (float) (y - 1.15),
                    (float) core, 2.3f, 1.15f), SkijaGfx.fillBlurred(color(0xFFFFFF, opacity * .38), TIP_SIGMA));
        } finally {
            canvas.restoreToCount(save);
        }

        try (Shader body = Shader.makeLinearGradient((float) x, (float) y, (float) tip, (float) y,
                new int[]{color(coverColor, opacity),
                        color(MusicCardColors.mix(coverColor, 0xFFFFFF, .16), opacity),
                        color(MusicCardColors.mix(coverColor, 0xFFFFFF, .44), opacity),
                        color(MusicCardColors.mix(coverColor, 0xFFFFFF, .84), opacity),
                        color(0xFFFFFF, opacity)}, BODY_STOPS)) {
            Paint paint = SkijaGfx.fillShader(body);
            canvas.drawRRect(RRect.makeXYWH((float) x, (float) (y - half), (float) filled,
                    (float) TRACK_HEIGHT, (float) half), paint);
            paint.setShader(null);
        }
        // 细丝嵌在条身上半部，向光源逐渐变亮，避免整段铺一条同亮的白线。
        try (Shader silk = Shader.makeLinearGradient((float) x, (float) y, (float) tip, (float) y,
                new int[]{color(0xFFFFFF, opacity * .06), color(0xFFFFFF, opacity * .18),
                        color(0xFFFFFF, opacity * .45)}, new float[]{0f, .72f, 1f})) {
            Paint paint = SkijaGfx.fillShader(silk);
            canvas.drawRRect(RRect.makeXYWH((float) (x + .8), (float) (y - 1.2),
                    (float) Math.max(0.0, filled - 1.6), .45f, .225f), paint);
            paint.setShader(null);
        }
    }

    private static void drawBeam(Canvas canvas, double tip, double y, double length, double spread,
                                 int tint, float opacity, float sigma) {
        if (length <= .5 || spread <= .05) return;
        try (PathBuilder builder = new PathBuilder();
             Shader light = Shader.makeLinearGradient((float) (tip - length), (float) y, (float) tip, (float) y,
                     new int[]{color(tint, 0.0), color(tint, opacity * .38),
                             color(MusicCardColors.mix(tint, 0xFFFFFF, .32), opacity * .82),
                             color(MusicCardColors.mix(tint, 0xFFFFFF, .76), opacity * .92),
                             color(0xFFFFFF, opacity)}, BEAM_STOPS)) {
            builder.moveTo((float) tip, (float) y)
                    .cubicTo((float) (tip - length * .16), (float) (y - spread * .16),
                            (float) (tip - length * .57), (float) (y - spread * .80),
                            (float) (tip - length), (float) (y - spread))
                    .lineTo((float) (tip - length), (float) (y + spread))
                    .cubicTo((float) (tip - length * .57), (float) (y + spread * .80),
                            (float) (tip - length * .16), (float) (y + spread * .16),
                            (float) tip, (float) y)
                    .closePath();
            try (Path beam = builder.snapshot()) {
                Paint paint = SkijaGfx.fillBlurred(0xFFFFFFFF, sigma);
                try {
                    paint.setShader(light);
                    canvas.drawPath(beam, paint);
                } finally {
                    paint.setShader(null);
                    paint.setMaskFilter(null);
                }
            }
        }
    }

    private static int color(int rgb, double opacity) {
        return MusicCardSurface.color(rgb, opacity);
    }
}
