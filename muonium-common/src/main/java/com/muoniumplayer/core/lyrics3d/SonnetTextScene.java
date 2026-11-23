package com.muoniumplayer.core.lyrics3d;

import java.util.ArrayList;
import java.util.List;

/** A measured shot and reusable playback poses. No native resources or frame-history state. */
final class SonnetTextScene {
    static final class Glyph {
        final String text;
        final SonnetTextFonts.Geometry geometry;
        final double startMs, settleMs;
        final float baseX, baseY, enterX, enterY, entryRotation;
        float x, y, scale, rotation, alpha, progress;
        Glyph(String text, SonnetTextFonts.Geometry geometry, double startMs, double settleMs,
              float baseX, float baseY, float enterX, float enterY, float entryRotation) {
            this.text = text; this.geometry = geometry;
            this.startMs = startMs; this.settleMs = settleMs;
            this.baseX = baseX; this.baseY = baseY;
            this.enterX = enterX; this.enterY = enterY; this.entryRotation = entryRotation;
        }
    }

    static final class Segment {
        final SonnetTypography.Box box;
        final List<Glyph> glyphs = new ArrayList<>();
        final int seed, normalSeed, textSeed, frameVariant;
        final boolean frame, fixed, chorusEffect, staff;
        final double firstMs, lastMs, guideStartMs, guideEndMs, frameEndMs;
        final float x, y, depth;
        int bodyRgb, glowRgb, accentRgb;
        Segment(SonnetTypography.Box box, SonnetTextFonts fonts, SonnetProgram.Shot shot,
                float width, double duration) {
            this.box = box;
            SonnetTypography.Segment text = box.segment;
            staff = text.text.trim().equals("♪");
            normalSeed = SonnetTypography.hash(text.text + ":" + text.startOffset + ":"
                    + text.endOffset + ":" + box.segmentIndex + ":normal-offset");
            seed = SonnetTypography.hash(shot.id + ":" + text.text + ":" + text.startOffset);
            boolean decor = box.role == SonnetTypography.Role.DECORATION;
            double normal = (Integer.toUnsignedLong(normalSeed) / 4294967295.0 * 2 - 1) * box.fontSize * 0.3;
            double angle = box.rotationRad + (box.flowVertical ? 0 : Math.PI / 2);
            x = box.x + (box.role == SonnetTypography.Role.SUPPORT ? (float) (Math.cos(angle) * normal) : 0);
            y = box.y + (box.role == SonnetTypography.Role.SUPPORT ? (float) (Math.sin(angle) * normal) : 0);
            double random = Integer.toUnsignedLong(seed) / 4294967295.0;
            depth = decor ? (float) ((random > 0.5 ? 1 : -1) * (0.5 + (random * 37 % 1) * 0.8)) : 0;
            List<SonnetTypography.Glyph> times = text.glyphs;
            float total = 0;
            for (SonnetTypography.Glyph glyph : times) total += box.vertical ? box.fontSize * 0.9f
                    : SonnetTypography.advance(glyph, box.fontSize, box.role, fonts::width);
            float cursor = -total / 2;
            float cos = (float) Math.cos(box.rotationRad), sin = (float) Math.sin(box.rotationRad);
            for (int i = 0; i < times.size(); i++) {
                SonnetTypography.Glyph timed = times.get(i);
                float advance = box.vertical ? box.fontSize * 0.9f
                        : SonnetTypography.advance(timed, box.fontSize, box.role, fonts::width);
                float localX = box.vertical ? 0 : cursor + advance / 2;
                float localY = box.vertical ? cursor + advance / 2 : 0;
                cursor += advance;
                float stagger = (i & 1) == 0 ? -1 : 1;
                float enterX = box.enterX + (box.vertical ? stagger * box.fontSize * 0.28f : 0);
                float enterY = box.enterY + (box.vertical ? 0 : stagger * box.fontSize * 0.24f);
                boolean emphasis = box.role == SonnetTypography.Role.HERO || box.role == SonnetTypography.Role.SEMI_HERO;
                glyphs.add(new Glyph(timed.text, fonts.geometry(timed.text, box.role), timed.startMs,
                        timed.startMs + duration, x + localX * cos - localY * sin,
                        y + localX * sin + localY * cos, enterX, enterY,
                        stagger * (emphasis ? 0.055f : 0.035f)));
            }
            if (staff) {
                glyphs.clear();
                glyphs.add(new Glyph("♪", fonts.geometry("♪", box.role), shot.startMs, shot.startMs + 500,
                        x, y, box.enterX, box.enterY, 0));
            }
            firstMs = glyphs.isEmpty() ? text.startMs : glyphs.get(0).startMs;
            lastMs = glyphs.isEmpty() ? text.endMs : glyphs.get(glyphs.size() - 1).startMs;
            guideStartMs = firstMs - SonnetTextMotion.guideLead(text.startMs, text.endMs);
            guideEndMs = firstMs + 650;
            frameEndMs = firstMs + duration * 1.25;
            frame = !staff && !decor && SonnetDecorations.hasFrame(text.text, text.startOffset, text.endOffset);
            frameVariant = SonnetDecorations.frameVariant(text.text, text.startOffset, text.endOffset);
            int sum = box.segmentIndex * 13;
            for (int i = 0; i < text.text.length(); i++) sum += text.text.charAt(i);
            textSeed = sum;
            chorusEffect = shot.chorus || Math.floorMod(sum, 100) < 35;
            fixed = !staff && !decor && text.wordLike && !frame && Math.floorMod(sum, 100) < (chorusEffect ? 26 : 15);
        }
    }

    final SonnetProgram.Shot shot;
    final List<Segment> segments = new ArrayList<>();
    final List<Segment> tracking = new ArrayList<>();
    final SonnetTextMotion.Camera camera = new SonnetTextMotion.Camera();
    final double[] sampleX = new double[5], sampleY = new double[5];
    final double durationMs, revealDoneMs, breathPhase;
    final float width, height, baseFont;
    float focusX, focusY;

    SonnetTextScene(SonnetProgram.Shot shot, SonnetTextFonts fonts, float width, float height) {
        this.shot = shot; this.width = width; this.height = height;
        int words = 0;
        for (List<SonnetTypography.Segment> line : shot.lines) {
            for (SonnetTypography.Segment segment : line) if (segment.wordLike) words++;
        }
        float heroScale = shot.kind == SonnetTypography.Kind.TYPE_IMPACT ? 1.55f
                : shot.kind == SonnetTypography.Kind.QUIET_TABLEAU ? 0.82f : 1;
        baseFont = Math.max(24, Math.min(112, width / Math.max(7f, words * 2.15f) * heroScale));
        durationMs = SonnetTextMotion.motionDuration(shot.startMs, shot.endMs);
        double revealed = shot.startMs;
        boolean staffAdded = false;
        for (SonnetTypography.Box box : SonnetTypography.layout(shot.lines, shot.kind,
                width, height, baseFont, fonts::width)) {
            if (box.segment.text.trim().equals("♪")) {
                if (staffAdded || box.role == SonnetTypography.Role.DECORATION) continue;
                staffAdded = true;
            }
            Segment view = new Segment(box, fonts, shot, width, durationMs);
            segments.add(view);
            if (box.role != SonnetTypography.Role.DECORATION && !view.glyphs.isEmpty()) {
                tracking.add(view);
                revealed = Math.max(revealed, view.lastMs);
            }
        }
        revealDoneMs = revealed;
        breathPhase = Integer.toUnsignedLong(SonnetTypography.hash(shot.id)) % 1024 / 1024.0 * Math.PI * 2;
    }

    void update(double timeMs) {
        double progress = (timeMs - shot.startMs) / Math.max(1, shot.endMs - shot.startMs);
        SonnetTextMotion.camera(shot.kind, progress, timeMs / 1000, revealDoneMs / 1000, breathPhase, Math.max(0, timeMs - shot.endMs) / 1000, camera);
        focus(timeMs);
        for (Segment segment : segments) {
            boolean emphasis = segment.box.role == SonnetTypography.Role.HERO
                    || segment.box.role == SonnetTypography.Role.SEMI_HERO;
            for (Glyph glyph : segment.glyphs) {
                double p = SonnetTextMotion.progress(glyph.startMs, glyph.settleMs, timeMs);
                float offset = (float) (1 - p);
                glyph.progress = (float) p;
                glyph.alpha = timeMs < glyph.startMs ? 0 : (float) (0.16 + p * 0.84);
                glyph.scale = (float) ((emphasis && shot.kind == SonnetTypography.Kind.TYPE_IMPACT
                        ? 0.52 + p * 0.48 : 0.86 + p * 0.14) * (1 + segment.depth * 0.45));
                glyph.x = glyph.baseX + glyph.enterX * offset + (float) (camera.x * width * segment.depth * 2.5);
                glyph.y = glyph.baseY + glyph.enterY * offset + (float) (camera.y * height * segment.depth * 2.5);
                glyph.rotation = segment.box.rotationRad + glyph.entryRotation * offset;
            }
        }
    }

    private void focus(double nowMs) {
        if (tracking.isEmpty()) { focusX = focusY = 0; return; }
        for (int sample = 0; sample < 5; sample++) {
            double time = Math.max(shot.startMs, Math.min(shot.endMs, nowMs + (sample - 2) * 60));
            double maxLog = -Double.MAX_VALUE;
            for (Segment segment : tracking) {
                double distance = time < segment.firstMs ? segment.firstMs - time : Math.max(0, time - segment.lastMs);
                maxLog = Math.max(maxLog, -distance * distance / (2 * 350 * 350));
            }
            double fx = 0, fy = 0, weight = 0;
            for (Segment segment : tracking) {
                double distance = time < segment.firstMs ? segment.firstMs - time : Math.max(0, time - segment.lastMs);
                double w = Math.exp(-distance * distance / (2 * 350 * 350) - maxLog);
                Glyph first = segment.glyphs.get(0), last = segment.glyphs.get(segment.glyphs.size() - 1);
                double exactX = first.baseX, exactY = first.baseY;
                if (time >= last.startMs) { exactX = last.baseX; exactY = last.baseY; }
                else if (time > first.startMs) {
                    for (int i = 0; i + 1 < segment.glyphs.size(); i++) {
                        Glyph a = segment.glyphs.get(i), b = segment.glyphs.get(i + 1);
                        if (time < a.startMs || time > b.startMs) continue;
                        double p = (time - a.startMs) / Math.max(1, b.startMs - a.startMs);
                        exactX = a.baseX + (b.baseX - a.baseX) * p;
                        exactY = a.baseY + (b.baseY - a.baseY) * p;
                        break;
                    }
                }
                double cx = (first.baseX + last.baseX) / 2.0, cy = (first.baseY + last.baseY) / 2.0;
                fx += (cx + (exactX - cx) * 0.5) * w;
                fy += (cy + (exactY - cy) * 0.5) * w;
                weight += w;
            }
            sampleX[sample] = fx / Math.max(1e-12, weight);
            sampleY[sample] = fy / Math.max(1e-12, weight);
        }
        double fx = 0, fy = 0, weight = 0;
        for (int i = 0; i < 5; i++) {
            double dx = sampleX[i] - sampleX[2], dy = sampleY[i] - sampleY[2];
            if (dx * dx + dy * dy > 96 * 96) continue;
            double w = i == 2 ? 6 : (i == 0 || i == 4 ? 1 : 4);
            fx += sampleX[i] * w; fy += sampleY[i] * w; weight += w;
        }
        focusX = (float) (fx / Math.max(1, weight));
        focusY = (float) (fy / Math.max(1, weight));
    }
}