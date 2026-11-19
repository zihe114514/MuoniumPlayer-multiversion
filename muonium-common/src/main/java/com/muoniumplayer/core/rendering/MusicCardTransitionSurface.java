package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Path;
import io.github.humbleui.types.Rect;

/** Directional card trails and velocity-aligned blur share a frame-local, resource-free sampler. */
final class MusicCardTransitionSurface {
    private static final float[][] TRAIL_LAYERS = {{1.0f, .66f}, {.62f, .34f}, {.30f, .12f}};

    private MusicCardTransitionSurface() {
    }

    static void draw(MusicCardMotion.Frame frame, float alpha, CloudMusic.PlaybackSnapshot snapshot,
                     MusicCardMotionBlur.Sample motion) {
        Canvas canvas = SkijaGfx.canvas();
        float opacity = (float) (alpha * frame.alpha());
        if (canvas == null || opacity <= .001f || snapshot == null || snapshot.music == null) return;
        try (Path shape = MusicCardShape.outline(frame.x(), frame.y(), frame.width(), frame.height(), frame.radius())) {
            drawMotionTrails(canvas, shape, frame, opacity, motion);
            int save = canvas.save();
            try {
                canvas.translate(0f, 2f);
                canvas.drawPath(shape, SkijaGfx.fillBlurred(MusicCardSurface.color(0x000000, opacity * .22), 3.2f));
            } finally {
                canvas.restoreToCount(save);
            }
            MusicCapsuleCard.INSTANCE.drawSurface(frame.x(), frame.y(), frame.width(), frame.height(), frame.radius(), opacity);
            save = canvas.save();
            try {
                canvas.clipPath(shape, true);
                float contentAlpha = (float) (opacity * frame.contentReveal());
                if (contentAlpha <= .001f) return;
                if (motion == null || !motion.active() || motion.blurMix() < .012
                        || SkijaGfx.context() == null) {
                    drawContent(frame, contentAlpha, snapshot);
                } else {
                    float pad = (float) (motion.sigmaLong() * 3.0 + motion.trailDistance() + 1.0);
                    Rect bounds = Rect.makeXYWH((float) frame.x() - pad, (float) frame.y() - pad,
                            (float) frame.width() + pad * 2f, (float) frame.height() + pad * 2f);
                    drawDirectionalContent(canvas, frame, contentAlpha, snapshot, motion, bounds);
                }
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }

    private static void drawMotionTrails(Canvas canvas, Path shape, MusicCardMotion.Frame frame,
                                         float opacity, MusicCardMotionBlur.Sample motion) {
        if (motion == null || motion.trailOpacity() <= .003) return;
        int depth = canvas.save();
        try {
            Rect clip = Rect.makeXYWH((float) (frame.x() - 12.0), (float) (frame.y() - 12.0),
                    (float) (frame.width() + 24.0), (float) (frame.height() + 24.0));
            canvas.clipRect(clip);
            for (float[] layer : TRAIL_LAYERS) {
                double offset = motion.trailDistance() * layer[0];
                int save = canvas.save();
                try {
                    canvas.translate((float) (-motion.trailDirectionX() * offset),
                            (float) (-motion.trailDirectionY() * offset));
                    canvas.drawPath(shape, SkijaGfx.fill(MusicCardSurface.color(
                            MusicCapsuleCard.motionTrailColor(), opacity * motion.trailOpacity() * layer[1])));
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        } finally {
            canvas.restoreToCount(depth);
        }
    }

    private static void drawDirectionalContent(Canvas canvas, MusicCardMotion.Frame frame, float alpha,
                                               CloudMusic.PlaybackSnapshot snapshot,
                                               MusicCardMotionBlur.Sample motion, Rect bounds) {
        float[] kernel = MusicCardMotionBlur.directionalKernel(motion.directionX(), motion.directionY(), motion.speed(),
                motion.blurMix() * .20);
        try (ImageFilter directionalBlur = ImageFilter.makeMatrixConvolution(7, 7, kernel,
                1f, 0f, 3, 3, FilterTileMode.DECAL, false, null);
             Paint paint = new Paint().setAlphaf(1f).setImageFilter(directionalBlur)) {
            int layer = canvas.saveLayer(bounds, paint);
            try {
                drawContent(frame, alpha, snapshot);
            } finally {
                canvas.restoreToCount(layer);
            }
        }
    }

    private static void drawContent(MusicCardMotion.Frame frame, float alpha, CloudMusic.PlaybackSnapshot snapshot) {
        MusicCapsuleCard.INSTANCE.drawCardContent(frame, alpha, snapshot);
    }
}
