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

import java.util.ArrayList;
import java.util.List;

/**
 * La Folia staff adapted from Project Folia's Sonnet staff notation/view (chthollyphile), AGPL-3.0.
 * The melody is public domain. Coordinates are design pixels centered at (0, 0); the caller owns
 * placement, transforms and overall alpha. Draw and close on the render thread, without reentrancy.
 * Native resources are lazy, owned here, and must be closed before Skija shutdown.
 */
final class SonnetStaff {
    static final double TOTAL_BEATS = 24.0;
    static final double LOOP_MS = 8000.0;
    static final List<Note> NOTES = notes();
    private static Paint fillPaint;
    private static Paint strokePaint;
    private static PathBuilder pathBuilder;

    private SonnetStaff() { }

    static final class Note {
        final String pitch;
        final int staffStep;
        final double beats;
        final double startBeat;
        final boolean sharp;

        Note(String pitch, int staffStep, double beats, double startBeat, boolean sharp) {
            this.pitch = pitch;
            this.staffStep = staffStep;
            this.beats = beats;
            this.startBeat = startBeat;
            this.sharp = sharp;
        }
    }

    private static List<Note> notes() {
        int[] steps = {6, 6, 7, 5, 5, 5, 6, 6, 6, 7, 7, 7, 8, 8, 8, 7, 7, 7, 6, 6, 5, 6};
        double[] beats = {1, 1.5, 0.5, 1, 1, 1, 1, 1.5, 0.5, 1, 1, 1, 1, 1.5, 0.5, 1, 1, 1, 1, 1.5, 0.5, 3};
        List<Note> notes = new ArrayList<>(steps.length);
        double start = 0;
        for (int index = 0; index < steps.length; index++) {
            int step = steps[index];
            String pitch = switch (step) {
                case 5 -> "C#5";
                case 7 -> "E5";
                case 8 -> "F5";
                default -> "D5";
            };
            notes.add(new Note(pitch, step, beats[index], start, step == 5));
            start += beats[index];
        }
        return List.copyOf(notes);
    }

    static double cycleElapsedMs(double elapsedMs) {
        double remainder = elapsedMs % LOOP_MS;
        return remainder == 0 ? 0 : remainder < 0 ? remainder + LOOP_MS : remainder;
    }

    static double beatAt(double elapsedMs) {
        return cycleElapsedMs(elapsedMs) / LOOP_MS * TOTAL_BEATS;
    }

    /** Half-open note windows, with the same positive-modulo loop for forward and backward seeks. */
    static int noteAt(double elapsedMs) {
        double beat = beatAt(elapsedMs);
        for (int index = 0; index < NOTES.size(); index++) {
            Note note = NOTES.get(index);
            if (beat >= note.startBeat && beat < note.startBeat + note.beats) return index;
        }
        return -1;
    }

    /** elapsedMs is absolute playback time minus the shot start, not time since the previous draw. */
    static void draw(Canvas canvas, float stageWidth, float baseFontSize, int primaryRgb, int accentRgb,
                     double elapsedMs) {
        if (canvas == null || !Float.isFinite(stageWidth) || stageWidth <= 0
                || !Float.isFinite(baseFontSize) || baseFontSize <= 0 || !Double.isFinite(elapsedMs)) return;
        ensureResources();
        float staffWidth = Math.max(300, stageWidth * 0.6f);
        float spacing = baseFontSize * 0.25f;
        float halfWidth = staffWidth / 2;
        float halfHeight = spacing * 2;
        float playableWidth = staffWidth * 0.92f;
        float beatWidth = playableWidth / (float) TOTAL_BEATS;
        for (int line = 0; line < 5; line++) {
            float y = -halfHeight + line * spacing;
            canvas.drawLine(-halfWidth, y, halfWidth, y, stroke(primaryRgb, 0.3f, 2));
        }
        for (int bar = 1; bar < 8; bar++) {
            float x = -playableWidth / 2 + beatWidth * bar * 3;
            canvas.drawLine(x, -halfHeight, x, halfHeight, stroke(primaryRgb, 0.16f, 1));
        }
        canvas.drawLine(-halfWidth + 10, -halfHeight, -halfWidth + 10, halfHeight, stroke(primaryRgb, 0.5f, 4));
        canvas.drawLine(halfWidth - 10, -halfHeight, halfWidth - 10, halfHeight, stroke(primaryRgb, 0.5f, 2));
        canvas.drawLine(halfWidth - 4, -halfHeight, halfWidth - 4, halfHeight, stroke(primaryRgb, 0.5f, 6));

        double cycleMs = cycleElapsedMs(elapsedMs);
        double beat = cycleMs / LOOP_MS * TOTAL_BEATS;
        float cursorX = -playableWidth / 2 + beatWidth * (float) beat;
        canvas.drawLine(cursorX, -halfHeight - spacing * 0.8f, cursorX, halfHeight + spacing * 0.8f,
                stroke(accentRgb, 0.34f, 1.5f));
        for (int index = 0; index < NOTES.size(); index++) {
            Note note = NOTES.get(index);
            boolean active = beat >= note.startBeat && beat < note.startBeat + note.beats;
            double pulse = active ? (Math.sin(cycleMs / 1000 * Math.PI * 5 + index * 0.4) + 1) * 0.5 : 0;
            float noteScale = active ? (float) (1 + pulse * 0.12) : 1;
            float radiusX = spacing * 0.42f * noteScale;
            float radiusY = spacing * 0.29f * noteScale;
            float x = -playableWidth / 2 + beatWidth * (float) (note.startBeat + note.beats * 0.5);
            float y = halfHeight - note.staffStep * spacing * 0.5f;
            float alpha = active ? (float) (0.78 + pulse * 0.16) : 0.28f + index % 3 * 0.03f;
            boolean stemDown = note.staffStep >= 6;
            float stemX = x + (stemDown ? -radiusX : radiusX);
            float stemEndY = y + (stemDown ? spacing * 3.1f : -spacing * 3.1f);
            fillPaint.setColor(argb(accentRgb, alpha));
            canvas.drawOval(Rect.makeLTRB(x - radiusX, y - radiusY, x + radiusX, y + radiusY), fillPaint);
            Paint stemPaint = stroke(primaryRgb, Math.min(0.9f, alpha + 0.08f), 1.6f);
            canvas.drawLine(stemX, y, stemX, stemEndY, stemPaint);
            if (note.beats <= 0.5) {
                float direction = stemDown ? -1 : 1;
                pathBuilder.reset().moveTo(stemX, stemEndY).quadTo(stemX + spacing * 1.1f,
                        stemEndY + spacing * 0.55f * direction,
                        stemX + spacing * 0.1f, stemEndY + spacing * direction);
                drawPath(canvas, stemPaint);
            }
            if (note.sharp) {
                float sharpX = x - radiusX * 2.3f;
                float sharpHalfHeight = spacing * 1.15f * 0.5f;
                pathBuilder.reset()
                        .moveTo(sharpX - spacing * 0.16f, y - sharpHalfHeight)
                        .lineTo(sharpX - spacing * 0.16f, y + sharpHalfHeight)
                        .moveTo(sharpX + spacing * 0.16f, y - sharpHalfHeight)
                        .lineTo(sharpX + spacing * 0.16f, y + sharpHalfHeight)
                        .moveTo(sharpX - spacing * 0.34f, y - spacing * 0.12f)
                        .lineTo(sharpX + spacing * 0.34f, y - spacing * 0.28f)
                        .moveTo(sharpX - spacing * 0.34f, y + spacing * 0.28f)
                        .lineTo(sharpX + spacing * 0.34f, y + spacing * 0.12f);
                drawPath(canvas, stroke(primaryRgb, Math.min(0.86f, alpha + 0.08f), 1.2f));
            }
        }
    }

    private static void ensureResources() {
        if (fillPaint == null) fillPaint = new Paint().setAntiAlias(true).setMode(PaintMode.FILL)
                .setBlendMode(BlendMode.SRC_OVER);
        if (strokePaint == null) strokePaint = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE)
                .setStrokeCap(PaintStrokeCap.BUTT).setStrokeJoin(PaintStrokeJoin.MITER).setBlendMode(BlendMode.SRC_OVER);
        if (pathBuilder == null) pathBuilder = new PathBuilder();
    }

    private static Paint stroke(int rgb, float alpha, float width) {
        return strokePaint.setColor(argb(rgb, alpha)).setStrokeWidth(width);
    }

    private static int argb(int rgb, float alpha) {
        return Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24 | rgb & 0xFFFFFF;
    }

    private static void drawPath(Canvas canvas, Paint paint) {
        try (Path path = pathBuilder.snapshot()) { canvas.drawPath(path, paint); }
    }

    /** Idempotent release; the next draw recreates the owned paints and path builder. */
    static void close() {
        if (pathBuilder != null) { pathBuilder.close(); pathBuilder = null; }
        if (strokePaint != null) { strokePaint.close(); strokePaint = null; }
        if (fillPaint != null) { fillPaint.close(); fillPaint = null; }
    }
}
