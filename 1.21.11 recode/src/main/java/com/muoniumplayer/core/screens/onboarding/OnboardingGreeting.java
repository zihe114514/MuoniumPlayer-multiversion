package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.PathMeasure;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Point;
import io.github.humbleui.types.Rect;

/**
 * Apple-style rotating greeting used by the welcome page.
 *
 * <p>The English word uses the traced “hello” centerline. Other languages are
 * converted to Skia glyph paths and revealed with the same PathMeasure technique,
 * so the animation stays vector-only and resolution independent.</p>
 */
public final class OnboardingGreeting implements AutoCloseable {
    private static final long CYCLE_NANOS = 3_600_000_000L;
    private static final long DRAW_NANOS = 1_750_000_000L;
    private static final long FADE_NANOS = 280_000_000L;

    private static final String HELLO_PATH =
            "M12 104 C44 101 63 85 96 51 C116 30 145 15 166 13 C151 39 133 66 122 91 "
                    + "C114 109 126 121 139 107 C151 93 165 57 179 43 C189 34 196 38 190 51 "
                    + "C182 67 163 90 168 103 C175 116 200 110 220 87 C236 69 251 43 267 39 "
                    + "C282 35 283 55 271 76 C259 97 243 108 251 115 C264 126 299 97 317 77 "
                    + "C335 57 348 42 361 45 C373 48 361 74 347 91 C334 107 341 119 357 111 "
                    + "C378 100 389 66 409 51 C427 38 438 45 430 62 C420 83 402 103 414 112 "
                    + "C431 124 457 103 477 82 C493 65 505 53 518 57 C532 62 520 83 506 98 "
                    + "C493 112 500 123 516 116 C541 106 561 71 591 56 C615 44 641 49 656 61 "
                    + "C675 77 662 101 641 111";

    private final String[] labels = {"hello", "你好", "hola", "bonjour"};
    private final Path[] paths = new Path[labels.length];
    private final Rect[] bounds = new Rect[labels.length];
    private int index;
    private long startedAt = System.nanoTime();

    public OnboardingGreeting() {
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i];
            Path path = null;
            if ("hello".equals(label)) {
                try {
                    path = Path.makeFromSVGString(HELLO_PATH);
                } catch (Throwable ignored) {
                    path = null;
                }
            }
            if (path == null) {
                Typeface typeface = typefaceFor(label);
                path = textPath(label, typeface, 104.0f);
                if (typeface != null) {
                    typeface.close();
                }
            }
            paths[i] = path;
            bounds[i] = path == null ? Rect.makeWH(1, 1) : path.getBounds();
        }
    }

    public void render(double centerX, double centerY, double maxWidth, double maxHeight, double alpha) {
        long now = System.nanoTime();
        long age = now - startedAt;
        if (age >= CYCLE_NANOS) {
            index = (index + 1) % labels.length;
            startedAt = now;
            age = 0;
        }

        double fade = Math.min(1.0,
                Math.min(age / (double) FADE_NANOS, (CYCLE_NANOS - age) / (double) FADE_NANOS));
        double progress = Math.min(1.0, Math.max(0.0, age / (double) DRAW_NANOS));
        double opacity = alpha * Math.max(0.0, Math.min(1.0, fade));
        if (opacity <= 0.01 || paths[index] == null) {
            return;
        }

        Rect bounds = this.bounds[index];
        float pathWidth = Math.max(1.0f, bounds.getWidth());
        float pathHeight = Math.max(1.0f, bounds.getHeight());
        float scale = (float) Math.min(maxWidth / pathWidth, maxHeight / pathHeight);
        float drawWidth = pathWidth * scale;
        float drawHeight = pathHeight * scale;
        float left = (float) (centerX - drawWidth * 0.5 - bounds.getLeft() * scale);
        float top = (float) (centerY - drawHeight * 0.5 - bounds.getTop() * scale);

        try (Path scaled = paths[index].makeScale(scale, scale);
             Path placed = scaled.makeOffset(left, top);
             PathMeasure measure = new PathMeasure(placed);
             PathBuilder builder = new PathBuilder()) {

            float totalLength = 0.0f;
            do {
                totalLength += measure.getLength();
            } while (measure.nextContour());
            measure.setPath(placed, false);

            float remaining = totalLength * (float) progress;
            Point tip = new Point(left, top);
            while (true) {
                float length = measure.getLength();
                float take = Math.min(length, remaining);
                if (take > 0.0f) {
                    measure.getSegment(0.0f, take, builder, true);
                    Point candidate = measure.getPosition(take);
                    if (candidate != null) {
                        tip = candidate;
                    }
                }
                remaining -= take;
                if (remaining <= 0.0f || !measure.nextContour()) {
                    break;
                }
            }

            try (Path partial = builder.snapshot()) {
                Canvas canvas = SkijaGfx.canvas();
                if (canvas == null) {
                    return;
                }

                float pulse = 0.72f + 0.28f * (float) Math.sin(now / 460_000_000.0);
                int glowColor = RGBA.color(74, 125, 255, (int) (72 * opacity * pulse));
                try (MaskFilter blur = MaskFilter.makeBlur(FilterBlurMode.NORMAL, 10.0f, true);
                     Paint glow = new Paint()) {
                    glow.setAntiAlias(true);
                    glow.setMode(PaintMode.STROKE);
                    glow.setStrokeWidth(10.0f);
                    glow.setStrokeCap(PaintStrokeCap.ROUND);
                    glow.setStrokeJoin(PaintStrokeJoin.ROUND);
                    glow.setColor(glowColor);
                    glow.setMaskFilter(blur);
                    canvas.drawPath(partial, glow);
                }

                int startColor = RGBA.color(101, 189, 255, (int) (250 * opacity));
                int endColor = RGBA.color(226, 132, 255, (int) (250 * opacity));
                try (Shader shader = Shader.makeLinearGradient(
                        left, top, left + drawWidth, top + drawHeight,
                        new int[]{startColor, endColor});
                     Paint paint = new Paint()) {
                    paint.setAntiAlias(true);
                    paint.setMode(PaintMode.STROKE);
                    paint.setStrokeWidth(3.4f);
                    paint.setStrokeCap(PaintStrokeCap.ROUND);
                    paint.setStrokeJoin(PaintStrokeJoin.ROUND);
                    paint.setShader(shader);
                    canvas.drawPath(partial, paint);
                }

                OnboardingUi.roundedRect(tip.getX() - 3.5, tip.getY() - 3.5, 7, 7, 3.5,
                        222, 242, 255, (int) (210 * opacity * pulse));
            }
        } catch (Throwable ignored) {
            // A platform Skia build without PathMeasure support must not block onboarding.
        }
    }

    private static Path textPath(String text, Typeface typeface, float size) {
        if (typeface == null || text == null || text.isEmpty()) {
            return new Path();
        }
        try (Font font = new Font(typeface, size);
             PathBuilder builder = new PathBuilder()) {
            short[] glyphs = font.getStringGlyphs(text);
            float[] positions = font.getXPositions(glyphs);
            for (int i = 0; i < glyphs.length; i++) {
                try (Path glyph = font.getPath(glyphs[i])) {
                    if (glyph != null) {
                        builder.addPath(glyph, positions[i], 0.0f);
                    }
                }
            }
            return builder.snapshot();
        } catch (Throwable ignored) {
            return new Path();
        }
    }

    private static Typeface typefaceFor(String text) {
        FontMgr manager = FontMgr.getDefault();
        boolean ascii = !text.isEmpty() && text.chars().allMatch(value -> value < 128);
        Typeface typeface;
        if (ascii) {
            typeface = manager.matchFamiliesStyle(
                    new String[]{"Segoe Script", "Comic Sans MS", "Brush Script MT", "Arial"},
                    FontStyle.ITALIC);
        } else {
            int codePoint = text.codePointAt(0);
            typeface = manager.matchFamilyStyleCharacter(null, FontStyle.NORMAL,
                    new String[]{"zh"}, codePoint);
            if (typeface == null) {
                typeface = manager.matchFamiliesStyle(
                        new String[]{"KaiTi", "STKaiti", "SimKai", "Microsoft YaHei", "Arial"},
                        FontStyle.NORMAL);
            }
        }
        if (typeface == null) {
            typeface = manager.matchFamilyStyle("Arial", FontStyle.NORMAL);
        }
        return typeface;
    }

    @Override
    public void close() {
        for (Path path : paths) {
            if (path != null) {
                path.close();
            }
        }
    }
}
