package com.muoniumplayer.core.lyrics3d;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.Rect;

import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.circle;
import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.drawPath;
import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.line;
import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.path;
import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.stroke;
import static com.muoniumplayer.core.lyrics3d.SonnetDecorations.strokedCircle;

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

    /** Legacy complete artwork, without a clock. Called in the owner's saved local canvas after paint initialization. */
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

    /**
     * Draws the same seven designs with a complete draw/hold/retract lifecycle. Progress is linear:
     * 0..0.30 draws in, 0.30..0.76 holds, and 0.76..1 retracts; values outside 0..1 are clamped.
     * timeSeconds is a continuous playback clock in seconds, used only for small local breaths.
     * Seeking or pausing with the same inputs produces the same geometry; no frame delta is stored.
     * Coordinates, palette, seed, chorus and opacity have the same meanings as the original draw.
     * The caller still owns placement, glyph motion, parallax and native resource initialization.
     */
    static void draw(Canvas canvas, float fontSize, float stageWidth, int primaryRgb, int accentRgb,
                     int secondaryRgb, int seed, boolean chorus, float opacity,
                     double timeSeconds, float progress) {
        if (!Float.isFinite(progress)) return;
        draw(canvas, fontSize, stageWidth, primaryRgb, accentRgb, secondaryRgb, seed, chorus, opacity,
                timeSeconds, progress / 0.30f, (progress - 0.76f) / 0.24f);
    }

    /**
     * Independent phase variant for a caller with real glyph/shot timestamps. enterProgress is the
     * LINEAR 0..1 draw-in progress (0 hidden, 1 complete); exitProgress is LINEAR 0..1 retraction
     * (0 retained, 1 gone). Both are clamped. Hold uses enter=1, exit=0. timeSeconds must be finite
     * and should follow playback, including pause/seek, rather than wall time. Opacity is an extra
     * multiplier, not a replacement for these geometric phases. Invalid inputs draw nothing.
     *
     * Folia builds the fixed artwork once. Its wrapper uses p=ExpoOut(clamp((t-start)/max(settle-start,
     * 0.08))), alpha=0 while waiting, otherwise 0.16+0.84*p; scale is 0.86+0.14*p (or 0.52+0.48*p
     * for type-impact emphasis), then multiplied by depthScale. Position includes the remaining
     * entry offset and camera parallax. Background entryRotation is zero; finalRotation is placement
     * rotation. There is no internal stroke reveal or retraction in that artwork. This local
     * animation extends it. World lyrics keeps local glyph entry transforms outside this method;
     * its configured billboard owns placement, and deliberately omits PV camera/depth parallax.
     */
    static void draw(Canvas canvas, float fontSize, float stageWidth, int primaryRgb, int accentRgb,
                     int secondaryRgb, int seed, boolean chorus, float opacity,
                     double timeSeconds, float enterProgress, float exitProgress) {
        if (!Float.isFinite(fontSize) || fontSize <= 0f || !Float.isFinite(stageWidth) || stageWidth < 0f
                || !Float.isFinite(opacity) || opacity <= 0f || !Double.isFinite(timeSeconds)
                || !Float.isFinite(enterProgress) || !Float.isFinite(exitProgress)
                || enterProgress <= 0f || exitProgress >= 1f) return;
        float multiplier = chorus ? 1.5f + (seed % 5) * 0.3f : 1f;
        float width = Math.max(fontSize * 2.5f * multiplier, stageWidth * 0.12f * multiplier);
        float height = Math.max(fontSize * 1.8f * multiplier, stageWidth * 0.08f * multiplier);
        if (!Float.isFinite(width) || !Float.isFinite(height)) return;
        int color = seed % 2 == 0 ? primaryRgb : secondaryRgb;
        float alpha = ((chorus ? 0.4f : 0.25f) + (seed % 10) * 0.03f) * Math.min(1f, opacity);
        Animation animation = new Animation(timeSeconds, clamp01(enterProgress), clamp01(exitProgress), seed);
        Design design = design(seed, chorus);
        switch (design) {
            case STRAIGHT_FRAME, ROTATED_FRAME -> {
                boolean rotated = design == Design.ROTATED_FRAME;
                int save = canvas.save();
                try {
                    if (rotated) canvas.rotate(45f);
                    float w = rotated ? width * 0.8f : width;
                    float h = rotated ? height * 0.8f : height;
                    boolean doubled = chorus && seed % 2 == 0;
                    animatedFrame(canvas, w, h, color, alpha, Math.max(1.5f, fontSize * 0.02f),
                            animation.layer(0, doubled ? 2 : 1));
                    if (doubled) animatedFrame(canvas, w * 1.2f, h * 1.2f, color, alpha * 0.5f, 1f,
                            animation.layer(1, 2));
                } finally {
                    canvas.restoreToCount(save);
                }
            }
            case ORBIT_CROSSHAIR -> {
                float radius = Math.min(width, height) * 0.46f;
                animatedOrbit(canvas, 0f, radius, color, alpha, 1.5f, false, animation.layer(0, 5));
                animatedOrbit(canvas, -width * 0.17f, radius * 0.72f, secondaryRgb, alpha * 0.72f,
                        1f, true, animation.layer(1, 5));
                animatedOrbit(canvas, width * 0.17f, radius * 0.72f, secondaryRgb, alpha * 0.72f,
                        1f, false, animation.layer(2, 5));
                LayerMotion horizontal = animation.layer(3, 5);
                LayerMotion vertical = animation.layer(4, 5);
                for (int sign = -1; sign <= 1; sign += 2) {
                    animatedPolyline(canvas, new float[]{0f, 0f, sign * width * 0.62f * horizontal.expansion, 0f},
                            false, color, alpha * 0.64f, 1f, horizontal);
                    animatedPolyline(canvas, new float[]{0f, 0f, 0f, sign * height * 0.62f * vertical.expansion},
                            false, color, alpha * 0.64f, 1f, vertical);
                }
            }
            case SPLIT_ARCHES -> {
                float halfWidth = width * 0.46f;
                float radius = Math.min(width * 0.34f, height * 0.52f);
                for (int index = 0; index < 2; index++) {
                    float x = (index == 0 ? -1f : 1f) * halfWidth * 0.42f;
                    animatedArch(canvas, x, height * 0.42f, radius * 0.72f,
                            index == 0 ? color : secondaryRgb, alpha, 1.5f, animation.layer(index * 2, 5));
                    animatedArch(canvas, x, height * 0.42f, radius * 0.48f,
                            index == 0 ? secondaryRgb : color, alpha * 0.58f, 1f,
                            animation.layer(index * 2 + 1, 5));
                }
                LayerMotion baseline = animation.layer(4, 5);
                animatedPolyline(canvas, new float[]{-halfWidth * baseline.expansion, height * 0.42f * baseline.expansion,
                        halfWidth * baseline.expansion, height * 0.42f * baseline.expansion},
                        false, color, alpha * 0.72f, 2f, baseline);
            }
            case MUSIC_STEPS -> {
                float spacing = width / (STEP_HEIGHTS.length + 1);
                for (int index = 0; index < STEP_HEIGHTS.length; index++) {
                    LayerMotion layer = animation.layer(index, STEP_HEIGHTS.length);
                    float x = (-width * 0.5f + spacing * (index + 1)) * layer.expansion;
                    float baseline = height * (0.12f - index * 0.035f) * layer.expansion;
                    float halfHeight = height * STEP_HEIGHTS[index] * 0.5f * layer.expansion;
                    // Each endpoint breathes about its own baseline, independently of adjacent steps.
                    halfHeight *= 1f + layer.breath * 4f;
                    animatedPolyline(canvas, new float[]{x - spacing * 0.12f * layer.expansion, baseline - halfHeight,
                            x + spacing * 0.12f * layer.expansion, baseline + halfHeight}, false,
                            index % 2 == 0 ? accentRgb : secondaryRgb, alpha * 0.52f,
                            Math.max(2f, height * 0.025f), layer);
                }
            }
            case BENT_LINES -> {
                for (int index = 0; index < 5; index++) {
                    LayerMotion layer = animation.layer(index, 5);
                    float x = -width * 0.34f + index * width * 0.17f;
                    animatedPolyline(canvas, new float[]{(x - width * 0.16f) * layer.expansion,
                            -height * (0.42f - index * 0.035f) * layer.expansion,
                            (x + width * layer.breath) * layer.expansion,
                            -height * (0.08f - index * 0.025f) * layer.expansion,
                            (x - width * 0.015f) * layer.expansion,
                            height * (0.35f + index * 0.035f) * layer.expansion}, false,
                            index % 2 == 0 ? accentRgb : secondaryRgb, alpha * 0.52f,
                            Math.max(2f, height * 0.022f), layer);
                }
            }
            case ORB_HATCH -> {
                float radius = width * 0.5f;
                float spacing = Math.max(4f, width * 0.05f);
                int count = (int) Math.ceil(width / spacing);
                LayerMotion disc = animation.layer(0, count + 1);
                circle(canvas, 0f, 0f, radius * disc.expansion * (float) Math.sqrt(disc.to - disc.from),
                        color, alpha * 0.15f);
                for (int index = 0; index < count; index++) {
                    float offset = -radius + index * spacing;
                    LayerMotion layer = animation.layer(index + 1, count + 1);
                    float lineHeight = (float) Math.sqrt(Math.max(0.0, (double) radius * radius - (double) offset * offset));
                    float x = (offset + radius * 0.4f) * layer.expansion + radius * layer.breath;
                    float y = radius * 0.4f * layer.expansion;
                    float halfHeight = lineHeight * layer.expansion * (1f + layer.breath * 3f);
                    // Preserve the deliberately offset, unclipped hatch from the original artwork.
                    animatedPolyline(canvas, new float[]{x, y - halfHeight, x, y + halfHeight}, false,
                            color, alpha * 0.6f, 1.5f, layer);
                }
            }
        }
    }

    private record Animation(double seconds, float enter, float exit, int seed) {
        LayerMotion layer(int index, int count) {
            return layerMotion(seconds, enter, exit, seed, index, count);
        }
    }

    /** Pure phase math: layer order reverses on exit; visible intervals collapse to their midpoint. */
    static LayerMotion layerMotion(double seconds, float enter, float exit, int seed, int index, int count) {
        if (!Double.isFinite(seconds) || !Float.isFinite(enter) || !Float.isFinite(exit)) {
            return new LayerMotion(0f, 0f, 0f, 0f, 0f);
        }
        float order = count <= 1 ? 0f : clamp01((float) index / (count - 1));
        float reveal = smooth((clamp01(enter) - order * 0.32f) / (count <= 1 ? 1f : 0.68f));
        float retract = smooth((clamp01(exit) - (count <= 1 ? 0f : (1f - order) * 0.22f))
                / (count <= 1 ? 1f : 0.78f));
        float phase = Math.floorMod(seed, 1024) * 0.618f + index * 1.7f;
        double frequency = 1.85 + Math.max(0, index) * 0.11;
        float wave = (float) Math.sin((seconds % (Math.PI * 2 / frequency)) * frequency + phase);
        float breath = wave * 0.012f * reveal * (1f - retract);
        float expansion = (0.72f + reveal * 0.28f) * (1f - retract * 0.65f) + breath;
        float tip = Math.max(reveal * (1f - reveal), retract * (1f - retract)) * 4f;
        return new LayerMotion(reveal * retract * 0.5f, reveal * (1f - retract * 0.5f),
                expansion, breath, tip * (1.1f + wave * 0.2f));
    }

    record LayerMotion(float from, float to, float expansion, float breath, float tip) {
        boolean visible() { return to > from; }
    }

    private static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static float smooth(float value) {
        float t = clamp01(value);
        return t * t * (3f - 2f * t);
    }

    private static void animatedFrame(Canvas canvas, float width, float height, int color, float alpha,
                                      float strokeWidth, LayerMotion layer) {
        float x = width * 0.5f * (layer.expansion + layer.breath);
        float y = height * 0.5f * (layer.expansion - layer.breath);
        animatedPolyline(canvas, new float[]{-x, -y, x, -y, x, y, -x, y, -x, -y}, true,
                color, alpha, strokeWidth, layer);
    }

    private static void animatedPolyline(Canvas canvas, float[] points, boolean closed, int color,
                                         float alpha, float strokeWidth, LayerMotion layer) {
        if (!layer.visible()) return;
        float[] trimmed = trimPolyline(points, layer.from, layer.to);
        if (trimmed.length < 4) return;
        PathBuilder builder = path().moveTo(trimmed[0], trimmed[1]);
        for (int i = 2; i < trimmed.length; i += 2) builder.lineTo(trimmed[i], trimmed[i + 1]);
        if (closed && layer.from == 0f && layer.to == 1f) builder.closePath();
        drawPath(canvas, builder, stroke(color, alpha, strokeWidth));
        tip(canvas, trimmed[trimmed.length - 2], trimmed[trimmed.length - 1], color, alpha, strokeWidth, layer);
    }

    /** Clips by accumulated Euclidean distance, so crossing a short edge cannot consume half a reveal. */
    static float[] trimPolyline(float[] points, float from, float to) {
        if (!Float.isFinite(from) || !Float.isFinite(to) || points.length < 4 || points.length % 2 != 0) {
            return new float[0];
        }
        double total = 0;
        for (int i = 2; i < points.length; i += 2) {
            total += Math.hypot((double) points[i] - points[i - 2], (double) points[i + 1] - points[i - 1]);
        }
        if (!(total > 0) || !Double.isFinite(total)) return new float[0];
        double start = clamp01(from) * total;
        double end = clamp01(to) * total;
        if (end <= start) return new float[0];
        float[] result = new float[points.length + 2];
        int written = 0;
        double walked = 0;
        for (int i = 2; i < points.length; i += 2) {
            double dx = (double) points[i] - points[i - 2];
            double dy = (double) points[i + 1] - points[i - 1];
            double length = Math.hypot(dx, dy);
            double low = Math.max(start, walked);
            double high = Math.min(end, walked + length);
            if (high > low && length > 0) {
                if (written == 0) {
                    result[written++] = (float) (points[i - 2] + dx * ((low - walked) / length));
                    result[written++] = (float) (points[i - 1] + dy * ((low - walked) / length));
                }
                result[written++] = (float) (points[i - 2] + dx * ((high - walked) / length));
                result[written++] = (float) (points[i - 1] + dy * ((high - walked) / length));
            }
            walked += length;
            if (walked >= end) break;
        }
        return java.util.Arrays.copyOf(result, written);
    }

    private static void animatedOrbit(Canvas canvas, float centerX, float radius, int color, float alpha,
                                      float strokeWidth, boolean reverse, LayerMotion layer) {
        if (!layer.visible()) return;
        float x = centerX * layer.expansion;
        float r = radius * (layer.expansion + layer.breath);
        float direction = reverse ? -1f : 1f;
        if (layer.from == 0f && layer.to == 1f) {
            strokedCircle(canvas, x, 0f, r, stroke(color, alpha, strokeWidth));
        } else {
            float angle = -90f + direction * layer.from * 360f;
            drawPath(canvas, path().arcTo(Rect.makeLTRB(x - r, -r, x + r, r), angle,
                    direction * (layer.to - layer.from) * 360f, true), stroke(color, alpha, strokeWidth));
        }
        double headAngle = Math.toRadians(-90f + direction * layer.to * 360f);
        tip(canvas, x + r * (float) Math.cos(headAngle), r * (float) Math.sin(headAngle),
                color, alpha, strokeWidth, layer);
    }

    private static void animatedArch(Canvas canvas, float centerX, float baseline, float radius, int color,
                                     float alpha, float strokeWidth, LayerMotion layer) {
        if (!layer.visible()) return;
        float x = centerX * layer.expansion;
        float b = baseline * layer.expansion;
        float r = radius * (layer.expansion + layer.breath);
        double arcLength = Math.PI * r;
        double total = b * 2.0 + arcLength;
        double start = layer.from * total;
        double end = layer.to * total;
        Point first = archPoint(x, b, r, layer.from);
        PathBuilder builder = path().moveTo(first.x, first.y);
        if (start < b) builder.lineTo(x - r, (float) (b - Math.min(b, end)));
        double arcStart = Math.max(0, start - b);
        double arcEnd = Math.min(arcLength, end - b);
        if (arcEnd > arcStart) builder.arcTo(Rect.makeLTRB(x - r, -r, x + r, r),
                (float) (180 + 180 * arcStart / arcLength), (float) (180 * (arcEnd - arcStart) / arcLength), false);
        if (end > b + arcLength) builder.lineTo(x + r, (float) (end - b - arcLength));
        drawPath(canvas, builder, stroke(color, alpha, strokeWidth));
        Point last = archPoint(x, b, r, layer.to);
        tip(canvas, last.x, last.y, color, alpha, strokeWidth, layer);
    }

    /** Exact line / semicircle / line distance parameterization shared by arch clipping and its tip. */
    static Point archPoint(float centerX, float baseline, float radius, float progress) {
        double arcLength = Math.PI * radius;
        double distance = clamp01(progress) * (baseline * 2.0 + arcLength);
        if (distance <= baseline) return new Point(centerX - radius, (float) (baseline - distance));
        if (distance >= baseline + arcLength) {
            return new Point(centerX + radius, (float) (distance - baseline - arcLength));
        }
        double angle = Math.PI + (distance - baseline) / radius;
        return new Point(centerX + radius * (float) Math.cos(angle), radius * (float) Math.sin(angle));
    }

    record Point(float x, float y) { }

    private static void tip(Canvas canvas, float x, float y, int color, float alpha,
                            float strokeWidth, LayerMotion layer) {
        if (layer.tip > 0f) circle(canvas, x, y, strokeWidth * layer.tip, color, alpha * 0.7f);
    }
}
