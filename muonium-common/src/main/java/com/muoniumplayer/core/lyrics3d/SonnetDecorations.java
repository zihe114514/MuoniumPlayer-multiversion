package com.muoniumplayer.core.lyrics3d;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.Rect;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Text-attached Sonnet artwork adapted from Project Folia (chthollyphile), AGPL-3.0.
 * Ports its guide, frame and fixed-geometry modules.
 * Coordinates and stroke sizes are upstream design pixels; the caller supplies the design-to-world
 * canvas transform. Draw calls and {@link #close()} belong to the render thread, without reentrancy.
 * Paints and the path builder are lazy so pure selection/timing needs no native library. All native
 * resources belong here, never to the supplied canvas; close before its Skija context is released.
 */
public final class SonnetDecorations {
    private static Paint fillPaint;
    private static Paint strokePaint;
    private static PathBuilder pathBuilder;
    // Plans contain random choices only, independent of time, font size, canvas and native resources.
    private static final Map<Long, GuidePlan> GUIDE_PLANS = new LinkedHashMap<>(64, 0.75f, true);
    private static final int MAX_GUIDE_PLANS = 128;

    private SonnetDecorations() { }

    /**
     * Progress is the LINEAR fraction of [first glyph start - lead, first glyph start + .65s],
     * where lead = min(.38, max(.2, .18 + segment duration * .1)). Do not ease it beforehand.
     * Includes upstream container maxAlpha (.95/.7); rgb supplies color, not another alpha.
     * With no timingPhase in this contract, seed parity chooses the zero-enterX fallback side.
     */
    public static void drawGuide(Canvas canvas, float centerX, float centerY, int rgb,
                                 float fontSize, float enterX, float enterY,
                                 float normalizedProgress, boolean emphasis, int seed) {
        if (!valid(centerX, centerY, fontSize) || !Float.isFinite(enterX)
                || !Float.isFinite(enterY) || !Float.isFinite(normalizedProgress)
                || normalizedProgress < 0f || normalizedProgress >= 1f) return;
        ensureResources();
        GuidePlan plan = guidePlan(seed, emphasis);
        float progress = normalizedProgress;
        float fade = guideFade(progress);
        float alpha = emphasis ? 0.95f : 0.7f;
        float strokeAlpha = emphasis ? 0.82f : 0.55f;
        int save = canvas.save();
        try {
            canvas.translate(centerX, centerY);
            if (fade > 0f) {
                float startX = enterX != 0f ? enterX : ((seed & 1) == 0 ? -1f : 1f) * fontSize * 1.8f;
                float startY = enterY != 0f ? enterY : -fontSize * 0.9f;
                float drawn = guideDrawProgress(progress);
                if (drawn > 0f) {
                    taperedCurve(canvas, rgb, startX, startY, startX * 0.6f, startY * 0.4f,
                            startX * 0.2f, startY * 0.1f, 0f, 0f, 0f, drawn, 20,
                            emphasis ? 2.5f : 1.5f, emphasis ? 5f : 3f,
                            strokeAlpha * fade * 1.6f, alpha);
                    float hx = bezier(startX, startX * 0.6f, startX * 0.2f, 0f, drawn);
                    float hy = bezier(startY, startY * 0.4f, startY * 0.1f, 0f, drawn);
                    circle(canvas, hx, hy, emphasis ? 14f : 9f, rgb, alpha * 0.5f * fade);
                    circle(canvas, hx, hy, emphasis ? 4.5f : 3f, 0xFFFFFF, alpha * fade);
                }
                drawThread(canvas, plan.sCurve, progress, fontSize, rgb, emphasis, strokeAlpha, fade, alpha);
                drawThread(canvas, plan.ribbon, progress, fontSize, rgb, emphasis, strokeAlpha, fade, alpha);
                Spline spline = plan.spline;
                if (spline != null) {
                    float local = (progress - spline.delay) / spline.duration;
                    if (local > 0f && local < 1.3f) {
                        float head = clamp01(local * 1.5f);
                        float tail = clamp01((local - 0.3f) * 1.5f);
                        if (head > tail) {
                            float tx = (spline.x + spline.dx * tail) * fontSize;
                            float ty = (spline.y + spline.dy * tail) * fontSize;
                            float hx = (spline.x + spline.dx * head) * fontSize;
                            float hy = (spline.y + spline.dy * head) * fontSize;
                            line(canvas, tx, ty, hx, hy, rgb, alpha * strokeAlpha * fade * 0.7f, spline.thickness);
                            line(canvas, tx, ty, hx, hy, 0xFFFFFF, alpha * strokeAlpha * fade * 0.9f,
                                    spline.thickness * 0.3f);
                        }
                    }
                }
            }
            // Particles remain alive after the curve fades at .7; their own lifetime ends at 1.
            float burst = guideBurstProgress(progress);
            float distance = 1f - (float) Math.pow(1f - burst, 3);
            float particleAlpha = alpha * (1f - burst) * (emphasis ? 1f : 0.8f) * (emphasis ? 0.8f : 0.6f);
            for (Particle particle : plan.particles) {
                int particleSave = canvas.save();
                try {
                    canvas.translate((float) Math.cos(particle.angle) * particle.speed * distance,
                            (float) Math.sin(particle.angle) * particle.speed * distance);
                    canvas.rotate((float) Math.toDegrees(particle.rotationSpeed * burst));
                    canvas.scale(1f - burst * 0.4f, 1f - burst * 0.4f);
                    drawParticle(canvas, particle.kind, particle.size, rgb, particleAlpha);
                } finally {
                    canvas.restoreToCount(particleSave);
                }
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** Exact upstream FNV-1a over UTF-16 text:startOffset:endOffset:frame-decor (40% subset). */
    public static boolean hasFrame(String text, int startOffset, int endOffset) {
        return (frameHash(text, startOffset, endOffset) & 1023) / 1024.0 < 0.4;
    }

    public static int frameVariant(String text, int startOffset, int endOffset) {
        return (frameHash(text, startOffset, endOffset) >>> 10) % 4;
    }

    static int frameHash(String text, int startOffset, int endOffset) {
        String key = text + ":" + startOffset + ":" + endOffset + ":frame-decor";
        int hash = 0x811C9DC5;
        for (int i = 0; i < key.length(); i++) hash = (hash ^ key.charAt(i)) * 16777619;
        return hash;
    }

    /** Local dimensions are BEFORE rotation. rawProgress is linear; expo and elastic are applied here once. */
    public static void drawFrame(Canvas canvas, float centerX, float centerY, float rotationRad,
                                 float localWidth, float localHeight, float fontSize, int rgb,
                                 int variant, float rawProgress) {
        if (!valid(centerX, centerY, fontSize) || !Float.isFinite(rotationRad)
                || !Float.isFinite(localWidth) || !Float.isFinite(localHeight)
                || localWidth < 0f || localHeight < 0f || !Float.isFinite(rawProgress)
                || rawProgress <= 0f) return;
        float eased = expoOut(clamp01(rawProgress));
        float pad = clamp(fontSize * 0.22f, 8f, 20f);
        float halfW = localWidth * 0.5f + pad;
        float halfH = localHeight * 0.5f + pad;
        int kind = Math.floorMod(variant, 4);
        float gap = clamp(Math.min(halfW, halfH) * (kind == 1 ? 0.42f : 0.3f), 6f, 30f);
        float width = clamp(fontSize * 0.03f, 1.2f, 2.2f);
        int color = dim(rgb, 0.55f);
        ensureResources();
        int save = canvas.save();
        try {
            canvas.translate(centerX, centerY);
            canvas.rotate((float) Math.toDegrees(rotationRad));
            for (int side = 0; side < 4; side++) {
                float sideProgress = frameSideProgress(eased, side);
                if (sideProgress <= 0f) continue;
                float x0 = side == 0 ? -halfW + gap : side == 2 ? halfW - gap : side == 1 ? halfW : -halfW;
                float y0 = side == 1 ? -halfH + gap : side == 3 ? halfH - gap : side == 0 ? -halfH : halfH;
                float x1 = side == 0 ? halfW - gap : side == 2 ? -halfW + gap : x0;
                float y1 = side == 1 ? halfH - gap : side == 3 ? -halfH + gap : y0;
                if (kind != 3) {
                    line(canvas, x0, y0, x0 + (x1 - x0) * sideProgress, y0 + (y1 - y0) * sideProgress,
                            color, 0.85f, width);
                } else {
                    float length = (float) Math.hypot(x1 - x0, y1 - y0);
                    if (length < 1f) continue;
                    float ux = (x1 - x0) / length;
                    float uy = (y1 - y0) / length;
                    float dash = clamp(fontSize * 0.14f, 5f, 8f);
                    float traced = length * sideProgress;
                    for (float offset = 0f; offset + dash <= traced + 0.001f; offset += dash * 1.7f) {
                        line(canvas, x0 + ux * offset, y0 + uy * offset,
                                x0 + ux * (offset + dash), y0 + uy * (offset + dash), color, 0.85f, width);
                    }
                }
            }
            for (int corner = 0; corner < 4; corner++) {
                float op = frameOrnamentProgress(eased, corner);
                if (op <= 0.02f) continue;
                float sx = corner == 0 || corner == 3 ? -1f : 1f;
                float sy = corner < 2 ? -1f : 1f;
                float x = sx * halfW;
                float y = sy * halfH;
                if (kind == 0) {
                    float arm = clamp(pad * 0.8f, 5f, 12f) * op;
                    cornerBracket(canvas, x + sx * 3f, y + sy * 3f, sx, sy, arm,
                            color, 0.9f * Math.min(1f, op), width);
                } else if (kind == 1) {
                    float radius = clamp(pad * 0.55f, 4f, 9f);
                    float petal = radius * 0.44f * op;
                    circle(canvas, x - radius, y, petal, color, 0.8f);
                    circle(canvas, x + radius, y, petal, color, 0.8f);
                    circle(canvas, x, y - radius, petal, color, 0.8f);
                    circle(canvas, x, y + radius, petal, color, 0.8f);
                    circle(canvas, x, y, radius * 0.3f * op, 0xFFFFFF, 0.5f);
                } else if (kind == 2) {
                    float arm = clamp(pad, 6f, 14f) * op;
                    float ix = x + sx * 2.5f;
                    float iy = y + sy * 2.5f;
                    cornerBracket(canvas, ix, iy, sx, sy, arm, color, 0.9f, width * 1.4f);
                    cornerBracket(canvas, ix + sx * 4f, iy + sy * 4f, sx, sy,
                            arm, color, 0.5f, width * 0.8f);
                    // Each side's midpoint shares its corner's independently staggered envelope.
                    float mx = corner == 0 || corner == 2 ? 0f : sx * halfW;
                    float my = corner == 1 || corner == 3 ? 0f : sy * halfH;
                    float size = clamp(pad * 0.45f, 3.5f, 7f) * op;
                    polygon(canvas, fill(color, 0.85f), mx, my - size, mx + size, my, mx, my + size, mx - size, my);
                } else {
                    float diagX = sx * (float) (1 / Math.sqrt(2));
                    float diagY = sy * (float) (1 / Math.sqrt(2));
                    float perpX = sy * (float) (1 / Math.sqrt(2));
                    float perpY = -sx * (float) (1 / Math.sqrt(2));
                    float size = clamp(pad * 0.6f, 4f, 9f) * op;
                    float bx = x + diagX * 2f;
                    float by = y + diagY * 2f;
                    polygon(canvas, fill(color, 0.85f), x + diagX * (2f + size), y + diagY * (2f + size),
                            bx + perpX * size * 0.7f, by + perpY * size * 0.7f,
                            bx - perpX * size * 0.7f, by - perpY * size * 0.7f);
                    circle(canvas, x, y, width, color, 0.9f);
                }
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** All seven text-attached fixed designs; alpha multiplies their upstream artwork opacities. */
    public static void drawFixedGeometry(Canvas canvas, float centerX, float centerY, float rotationRad,
                                         float fontSize, float stageWidth, int primaryRgb, int accentRgb,
                                         int secondaryRgb, int seed, boolean chorus, float alpha) {
        if (!valid(centerX, centerY, fontSize) || !Float.isFinite(rotationRad)
                || !Float.isFinite(stageWidth) || stageWidth < 0f || !Float.isFinite(alpha) || alpha <= 0f) return;
        ensureResources();
        int save = canvas.save();
        try {
            canvas.translate(centerX, centerY);
            canvas.rotate((float) Math.toDegrees(rotationRad));
            SonnetFixedGeometry.draw(canvas, fontSize, stageWidth, primaryRgb, accentRgb, secondaryRgb,
                    seed, chorus, clamp01(alpha));
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void drawThread(Canvas canvas, Curve curve, float progress, float size, int rgb,
                                   boolean emphasis, float strokeAlpha, float fade, float alpha) {
        if (curve == null) return;
        float local = (progress - curve.delay) / 0.55f;
        if (local <= 0f || local >= 1.3f) return;
        float head = Math.min(1f, local);
        float tail = Math.max(0f, local - 0.35f);
        if (head > tail) {
            taperedCurve(canvas, rgb, curve.x0 * size, curve.y0 * size, curve.x1 * size, curve.y1 * size,
                    curve.x2 * size, curve.y2 * size, curve.x3 * size, curve.y3 * size, tail, head, 25,
                    emphasis ? 2f : 1f, emphasis ? 5f : 2.5f, strokeAlpha * fade * 0.9f, alpha);
        }
        if (head > 0f && head < 1f) {
            float x = bezier(curve.x0, curve.x1, curve.x2, curve.x3, head) * size;
            float y = bezier(curve.y0, curve.y1, curve.y2, curve.y3, head) * size;
            circle(canvas, x, y, emphasis ? 7f : 4f, rgb, alpha * 0.9f * fade);
            circle(canvas, x, y, emphasis ? 2.5f : 1.5f, 0xFFFFFF, alpha * fade);
            canvas.drawCircle(x, y, emphasis ? 20f : 12f, stroke(rgb, alpha * 0.4f * fade, emphasis ? 2f : 1f));
        }
    }

    private static void taperedCurve(Canvas canvas, int rgb, float x0, float y0, float x1, float y1,
                                     float x2, float y2, float x3, float y3, float from, float to,
                                     int steps, float baseWidth, float extraWidth, float opacity, float containerAlpha) {
        float previousX = bezier(x0, x1, x2, x3, from);
        float previousY = bezier(y0, y1, y2, y3, from);
        for (int i = 1; i <= steps; i++) {
            float ratio = (float) i / steps;
            float t = from + ratio * (to - from);
            float x = bezier(x0, x1, x2, x3, t);
            float y = bezier(y0, y1, y2, y3, t);
            float intensity = ratio * ratio;
            line(canvas, previousX, previousY, x, y, rgb,
                    Math.min(1f, intensity * opacity) * containerAlpha, baseWidth + intensity * extraWidth);
            previousX = x;
            previousY = y;
        }
    }

    private static void drawParticle(Canvas canvas, int kind, float size, int rgb, float alpha) {
        if (alpha <= 0f) return;
        Paint paint = fill(rgb, alpha);
        switch (kind) {
            case 0 -> canvas.drawCircle(0f, 0f, size, paint);
            case 1 -> canvas.drawRect(Rect.makeLTRB(-size, -size, size, size), paint);
            // Native filled plus, with the same two-pixel arms as the upstream stroked cross.
            case 2 -> polygon(canvas, paint, -size, -1f, -1f, -1f, -1f, -size, 1f, -size,
                    1f, -1f, size, -1f, size, 1f, 1f, 1f, 1f, size, -1f, size, -1f, 1f, -size, 1f);
            default -> polygon(canvas, paint, 0f, -size, size, 0f, 0f, size, -size, 0f);
        }
    }

    static float guideDrawProgress(float progress) { return clamp01(progress / 0.35f); }
    static float guideFade(float progress) { return 1f - clamp01((progress - 0.4f) / 0.3f); }
    static float guideBurstProgress(float progress) { return clamp01((progress - 0.3f) / 0.7f); }
    static float frameSideProgress(float eased, int side) { return clamp01((eased - side * 0.2f) / 0.4f); }
    static float frameOrnamentProgress(float eased, int corner) {
        return elasticOut(clamp01((eased - 0.35f - corner * 0.14f) / 0.3f));
    }
    static float expoOut(float t) { return t == 1f ? 1f : (float) (1 - Math.pow(2, -10 * t)); }
    static float elasticOut(float t) {
        return (float) (Math.pow(2, -10 * t) * Math.sin((t - 0.3 / 4) * (2 * Math.PI) / 0.3) + 1);
    }
    static float bezier(float p0, float p1, float p2, float p3, float t) {
        float u = 1f - t;
        return u * u * u * p0 + 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t * p3;
    }
    static float clamp01(float value) { return clamp(value, 0f, 1f); }
    static float clamp(float value, float low, float high) { return Math.min(high, Math.max(low, value)); }
    private static boolean valid(float x, float y, float size) {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(size) && size > 0f;
    }
    private static int dim(int rgb, float amount) {
        return Math.round(((rgb >>> 16) & 255) * amount) << 16
                | Math.round(((rgb >>> 8) & 255) * amount) << 8 | Math.round((rgb & 255) * amount);
    }

    private static void ensureResources() {
        if (fillPaint == null) fillPaint = new Paint().setAntiAlias(true).setMode(PaintMode.FILL).setBlendMode(BlendMode.SRC_OVER);
        if (strokePaint == null) strokePaint = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE)
                .setStrokeCap(PaintStrokeCap.BUTT).setStrokeJoin(PaintStrokeJoin.MITER).setBlendMode(BlendMode.SRC_OVER);
        if (pathBuilder == null) pathBuilder = new PathBuilder();
    }
    static Paint fill(int rgb, float alpha) { return fillPaint.setColor(argb(rgb, alpha)); }
    static Paint stroke(int rgb, float alpha, float width) {
        return strokePaint.setColor(argb(rgb, alpha)).setStrokeWidth(width);
    }
    private static int argb(int rgb, float alpha) { return Math.round(clamp01(alpha) * 255f) << 24 | (rgb & 0xFFFFFF); }
    static PathBuilder path() { return pathBuilder.reset(); }
    static void drawPath(Canvas canvas, PathBuilder builder, Paint paint) {
        try (Path path = builder.snapshot()) { canvas.drawPath(path, paint); }
    }
    static void line(Canvas canvas, float x0, float y0, float x1, float y1, int rgb, float alpha, float width) {
        canvas.drawLine(x0, y0, x1, y1, stroke(rgb, alpha, width));
    }
    static void circle(Canvas canvas, float x, float y, float radius, int rgb, float alpha) {
        canvas.drawCircle(x, y, radius, fill(rgb, alpha));
    }
    static void polygon(Canvas canvas, Paint paint, float... xy) {
        PathBuilder builder = path().moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) builder.lineTo(xy[i], xy[i + 1]);
        builder.closePath();
        drawPath(canvas, builder, paint);
    }
    private static void cornerBracket(Canvas canvas, float x, float y, float sx, float sy, float arm,
                                      int rgb, float alpha, float width) {
        drawPath(canvas, path().moveTo(x + sx * arm, y).lineTo(x, y).lineTo(x, y + sy * arm), stroke(rgb, alpha, width));
    }

    static GuidePlan guidePlan(int seed, boolean emphasis) {
        long key = Integer.toUnsignedLong(seed) * 2L + (emphasis ? 1 : 0);
        GuidePlan cached = GUIDE_PLANS.get(key);
        if (cached != null) return cached;
        SeededRandom random = new SeededRandom(seed);
        Particle[] particles = new Particle[emphasis ? 6 : 3];
        for (int i = 0; i < particles.length; i++) {
            particles[i] = new Particle((int) (random.next() * 4), (float) ((emphasis ? 3 : 1.5) + random.next() * 3.5),
                    (float) (random.next() * Math.PI * 2), (float) ((15 + random.next() * 45) * (emphasis ? 1 : 0.7)),
                    (float) ((random.next() - 0.5) * 8));
        }
        Curve sCurve = null;
        if (emphasis || random.next() > 0.4) {
            float d = random.next() > 0.5 ? 1f : -1f;
            float y = (float) ((random.next() - 0.5) * 0.8);
            sCurve = new Curve(-d * 2.5f, y + 1.5f, -d * 0.8f, y - 2f,
                    d * 0.8f, y + 2f, d * 2.5f, y - 1.5f, (float) (random.next() * 0.15));
        }
        Curve ribbon = null;
        if (emphasis || random.next() > 0.6) {
            float d = random.next() > 0.5 ? 1f : -1f;
            ribbon = new Curve(-2f, -d * 1.8f, 2f, d * 1.8f, -2f, d * 1.8f,
                    2f, -d * 1.8f, (float) (random.next() * 0.1));
        }
        Spline spline = null;
        if (random.next() > 0.4) {
            double length = 1.2 + random.next() * 1.5;
            float thickness = (float) ((emphasis ? 6 : 3) + random.next() * 8);
            double angle = (random.next() - 0.5) * Math.PI;
            spline = new Spline((float) ((random.next() - 0.5) * 1.2), (float) ((random.next() - 0.5) * 1.2),
                    (float) (Math.cos(angle) * length), (float) (Math.sin(angle) * length), thickness,
                    (float) (random.next() * 0.15), (float) (0.25 + random.next() * 0.2));
        }
        GuidePlan plan = new GuidePlan(particles, sCurve, ribbon, spline);
        if (GUIDE_PLANS.size() >= MAX_GUIDE_PLANS) GUIDE_PLANS.remove(GUIDE_PLANS.keySet().iterator().next());
        GUIDE_PLANS.put(key, plan);
        return plan;
    }

    record Particle(int kind, float size, float angle, float speed, float rotationSpeed) { }
    record Curve(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, float delay) { }
    record Spline(float x, float y, float dx, float dy, float thickness, float delay, float duration) { }
    record GuidePlan(Particle[] particles, Curve sCurve, Curve ribbon, Spline spline) { }

    // Mulberry32: independent per segment, matching the upstream shared seeded random stream.
    private static final class SeededRandom {
        private int state;
        SeededRandom(int seed) { state = seed; }
        double next() {
            state += 0x6D2B79F5;
            int t = (state ^ (state >>> 15)) * (1 | state);
            t = (t + (t ^ (t >>> 7)) * (61 | t)) ^ t;
            return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
        }
    }

    /** Idempotent render-thread release; a subsequent draw recreates resources lazily. */
    public static void close() {
        if (pathBuilder != null) { pathBuilder.close(); pathBuilder = null; }
        if (strokePaint != null) { strokePaint.close(); strokePaint = null; }
        if (fillPaint != null) { fillPaint.close(); fillPaint = null; }
        GUIDE_PLANS.clear();
    }
}
