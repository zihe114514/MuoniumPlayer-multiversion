package com.muoniumplayer.core.lyrics3d;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.Rect;

import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.circle;
import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.drawPath;
import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.line;
import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.path;
import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.stroke;

/** Native paths for the seven fixed text ornaments; owns no canvas, paints or other native resources. */
final class SonnetFixedGeometry {
    enum Design { STRAIGHT_FRAME, ROTATED_FRAME, ORBIT_CROSSHAIR, SPLIT_ARCHES, ORB_HATCH, MUSIC_STEPS, BENT_LINES }
    private static final Design[] HOLLOW = {
            Design.STRAIGHT_FRAME, Design.ROTATED_FRAME, Design.ORBIT_CROSSHAIR, Design.SPLIT_ARCHES
    };
    private static final float[] STEP_HEIGHTS = {0.24f, 0.35f, 0.2f, 0.82f, 0.3f, 0.1f, 0.23f, 0.16f};

    private SonnetFixedGeometry() { }

    static Design design(int seed, boolean chorus) {
        int divisor = chorus ? 10 : 4;
        int choice = Math.floorMod(seed, divisor);
        boolean hollow = chorus ? choice < 9 : choice == 1 || choice == 2;
        int group = Math.floorDiv(seed, divisor);
        if (hollow) return HOLLOW[Math.floorMod(group + choice, HOLLOW.length)];
        // Java's % preserves JS's signed remainder. Undefined negative solid-array entries in
        // upstream also reach its final orb-hatch branch, rather than wrapping into another design.
        return switch (group % 3) {
            case 1 -> Design.MUSIC_STEPS;
            case 2 -> Design.BENT_LINES;
            default -> Design.ORB_HATCH;
        };
    }

    /** Called only inside SonnetDecorations' saved local canvas, after lazy paint initialization. */
    static void draw(Canvas canvas, float fontSize, float stageWidth, int primaryRgb, int accentRgb,
                     int secondaryRgb, int seed, boolean chorus, float opacity) {
        Design design = design(seed, chorus);
        int color = seed % 2 == 0 ? primaryRgb : secondaryRgb;
        float alpha = ((chorus ? 0.4f : 0.25f) + (seed % 10) * 0.03f) * opacity;
        float multiplier = chorus ? 1.5f + (seed % 5) * 0.3f : 1f;
        float width = Math.max(fontSize * 2.5f * multiplier, stageWidth * 0.12f * multiplier);
        float height = Math.max(fontSize * 1.8f * multiplier, stageWidth * 0.08f * multiplier);
        switch (design) {
            case STRAIGHT_FRAME, ROTATED_FRAME -> {
                int save = canvas.save();
                try {
                    if (design == Design.ROTATED_FRAME) canvas.rotate(45f);
                    float w = design == Design.ROTATED_FRAME ? width * 0.8f : width;
                    float h = design == Design.ROTATED_FRAME ? height * 0.8f : height;
                    canvas.drawRect(Rect.makeLTRB(-w * 0.5f, -h * 0.5f, w * 0.5f, h * 0.5f),
                            stroke(color, alpha, Math.max(1.5f, fontSize * 0.02f)));
                    if (chorus && seed % 2 == 0) {
                        canvas.drawRect(Rect.makeLTRB(-w * 0.6f, -h * 0.6f, w * 0.6f, h * 0.6f),
                                stroke(color, alpha * 0.5f, 1f));
                    }
                } finally {
                    canvas.restoreToCount(save);
                }
            }
            case ORBIT_CROSSHAIR -> drawOrbitCrosshair(canvas, width, height, color, secondaryRgb, alpha);
            case SPLIT_ARCHES -> drawSplitArches(canvas, width, height, color, secondaryRgb, alpha);
            case MUSIC_STEPS -> drawMusicSteps(canvas, width, height, accentRgb, secondaryRgb, alpha);
            case BENT_LINES -> drawBentLines(canvas, width, height, accentRgb, secondaryRgb, alpha);
            case ORB_HATCH -> drawOrbHatch(canvas, width, color, alpha);
        }
    }

    private static void drawOrbitCrosshair(Canvas canvas, float width, float height,
                                            int color, int secondary, float alpha) {
        float radius = Math.min(width, height) * 0.46f;
        canvas.drawCircle(0f, 0f, radius, stroke(color, alpha, 1.5f));
        canvas.drawCircle(-width * 0.17f, 0f, radius * 0.72f, stroke(secondary, alpha * 0.72f, 1f));
        canvas.drawCircle(width * 0.17f, 0f, radius * 0.72f, stroke(secondary, alpha * 0.72f, 1f));
        line(canvas, -width * 0.62f, 0f, width * 0.62f, 0f, color, alpha * 0.64f, 1f);
        line(canvas, 0f, -height * 0.62f, 0f, height * 0.62f, color, alpha * 0.64f, 1f);
    }

    private static void drawSplitArches(Canvas canvas, float width, float height,
                                         int color, int secondary, float alpha) {
        float halfWidth = width * 0.46f;
        float radius = Math.min(width * 0.34f, height * 0.52f);
        for (int index = 0; index < 2; index++) {
            float x = (index == 0 ? -1f : 1f) * halfWidth * 0.42f;
            arch(canvas, x, height * 0.42f, radius * 0.72f, index == 0 ? color : secondary, alpha, 1.5f);
            arch(canvas, x, height * 0.42f, radius * 0.48f, index == 0 ? secondary : color, alpha * 0.58f, 1f);
        }
        line(canvas, -halfWidth, height * 0.42f, halfWidth, height * 0.42f, color, alpha * 0.72f, 2f);
    }

    private static void arch(Canvas canvas, float x, float baseline, float radius,
                              int color, float alpha, float width) {
        // A true semicircular native path, with the same clockwise pi..2pi arc as Pixi.
        PathBuilder builder = path().moveTo(x - radius, baseline).lineTo(x - radius, 0f)
                .arcTo(Rect.makeLTRB(x - radius, -radius, x + radius, radius), 180f, 180f, false)
                .lineTo(x + radius, baseline);
        drawPath(canvas, builder, stroke(color, alpha, width));
    }

    private static void drawMusicSteps(Canvas canvas, float width, float height,
                                        int accent, int secondary, float alpha) {
        float spacing = width / (STEP_HEIGHTS.length + 1);
        for (int index = 0; index < STEP_HEIGHTS.length; index++) {
            float x = -width * 0.5f + spacing * (index + 1);
            float baseline = height * (0.12f - index * 0.035f);
            float halfHeight = height * STEP_HEIGHTS[index] * 0.5f;
            line(canvas, x - spacing * 0.12f, baseline - halfHeight, x + spacing * 0.12f, baseline + halfHeight,
                    index % 2 == 0 ? accent : secondary, alpha * 0.52f, Math.max(2f, height * 0.025f));
        }
    }

    private static void drawBentLines(Canvas canvas, float width, float height,
                                       int accent, int secondary, float alpha) {
        for (int index = 0; index < 5; index++) {
            float x = -width * 0.34f + index * width * 0.17f;
            PathBuilder builder = path().moveTo(x - width * 0.16f, -height * (0.42f - index * 0.035f))
                    .lineTo(x, -height * (0.08f - index * 0.025f))
                    .lineTo(x - width * 0.015f, height * (0.35f + index * 0.035f));
            drawPath(canvas, builder, stroke(index % 2 == 0 ? accent : secondary,
                    alpha * 0.52f, Math.max(2f, height * 0.022f)));
        }
    }

    private static void drawOrbHatch(Canvas canvas, float width, int color, float alpha) {
        float radius = width * 0.5f;
        circle(canvas, 0f, 0f, radius, color, alpha * 0.15f);
        float spacing = Math.max(4f, width * 0.05f);
        PathBuilder builder = path();
        // The offset hatch is intentionally NOT clipped to the orb: this asymmetry is upstream artwork.
        for (float offset = -radius; offset < radius; offset += spacing) {
            float lineHeight = (float) Math.sqrt(Math.max(0f, radius * radius - offset * offset));
            builder.moveTo(offset + radius * 0.4f, -lineHeight + radius * 0.4f)
                    .lineTo(offset + radius * 0.4f, lineHeight + radius * 0.4f);
        }
        drawPath(canvas, builder, stroke(color, alpha * 0.6f, 1.5f));
    }
}
