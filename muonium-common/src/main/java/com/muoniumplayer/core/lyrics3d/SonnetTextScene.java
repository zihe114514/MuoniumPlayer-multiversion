package com.muoniumplayer.core.lyrics3d;

import java.util.ArrayList;
import java.util.List;
import io.github.humbleui.skija.TextBlob;

/** A measured shot and reusable playback poses. No native resources or frame-history state. */
final class SonnetTextScene {
    static final class Glyph {
        final String text;
        final SonnetTextFonts.Geometry geometry;
        final double startMs, settleMs;
        final float baseX, baseY, enterX, enterY, entryRotation, settledRotation;
        float x, y, scale, rotation, alpha, progress;
        Glyph(String text, SonnetTextFonts.Geometry geometry, double startMs, double settleMs,
              float baseX, float baseY, float enterX, float enterY,
              float entryRotation, float settledRotation) {
            this.text = text; this.geometry = geometry;
            this.startMs = startMs; this.settleMs = settleMs;
            this.baseX = baseX; this.baseY = baseY;
            this.enterX = enterX; this.enterY = enterY; this.entryRotation = entryRotation;
            this.settledRotation = settledRotation;
        }
    }

    static final class Segment {
        final SonnetTypography.Box box;
        final List<Glyph> glyphs = new ArrayList<>();
        final int seed, normalSeed, textSeed, frameVariant;
        final boolean frame, fixed, chorusEffect, staff;
        final double firstMs, lastMs, guideStartMs, guideEndMs, frameEndMs;
        final float x, y;
        private TextBlob stableBlob;
        private int stableBlobCount;
        /** One upcoming stable prefix built during the lead-in, before it becomes visible. */
        private TextBlob prewarmedStableBlob;
        private int prewarmedStableBlobCount;
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
                        stagger * (emphasis ? 0.055f : 0.035f), box.rotationRad));
            }
            if (staff) {
                glyphs.clear();
                glyphs.add(new Glyph("♪", fonts.geometry("♪", box.role), shot.startMs, shot.startMs + 500,
                        x, y, box.enterX, box.enterY, 0, box.rotationRad));
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

        TextBlob stableBlob(SonnetTextFonts fonts, int count) {
            if (count < 2 || count > glyphs.size()) return null;
            if (stableBlob != null && stableBlobCount == count) return stableBlob;
            if (prewarmedStableBlob != null && prewarmedStableBlobCount <= count) {
                if (stableBlob != null) stableBlob.close();
                stableBlob = prewarmedStableBlob;
                stableBlobCount = prewarmedStableBlobCount;
                prewarmedStableBlob = null;
                prewarmedStableBlobCount = 0;
                return stableBlob;
            }
            TextBlob next = fonts.stablePrefix(glyphs, count, box.role, box.fontSize, false);
            if (next != null) {
                if (stableBlob != null) stableBlob.close();
                stableBlob = next;
                stableBlobCount = count;
            }
            return stableBlob != null && stableBlobCount <= count ? stableBlob : null;
        }

        /** Builds at most one future prefix; the caller limits how many segments are prepared per frame. */
        TextBlob prewarmStableBlob(SonnetTextFonts fonts, int count) {
            if (count < 2 || count > glyphs.size()
                    || stableBlobCount == count || prewarmedStableBlobCount == count) return null;
            if (prewarmedStableBlob != null && prewarmedStableBlobCount < count) return null;
            TextBlob next = fonts.stablePrefix(glyphs, count, box.role, box.fontSize, true);
            if (next == null) return null;
            if (prewarmedStableBlob != null) prewarmedStableBlob.close();
            prewarmedStableBlob = next;
            prewarmedStableBlobCount = count;
            return next;
        }

        double stableReadyMs(int count, double ghostDurationMs) {
            if (count < 2 || count > glyphs.size()) return Double.POSITIVE_INFINITY;
            double ready = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < count; i++) {
                Glyph glyph = glyphs.get(i);
                ready = Math.max(ready, glyph.settleMs);
                if (box.role == SonnetTypography.Role.SEMI_HERO) {
                    ready = Math.max(ready, glyph.startMs + ghostDurationMs);
                }
            }
            return ready;
        }

        int stableBlobCount() { return stableBlobCount; }

        void close() {
            if (stableBlob != null) stableBlob.close();
            if (prewarmedStableBlob != null) prewarmedStableBlob.close();
            stableBlob = null;
            stableBlobCount = 0;
            prewarmedStableBlob = null;
            prewarmedStableBlobCount = 0;
        }
    }

    final SonnetProgram.Shot shot;
    final List<Segment> segments = new ArrayList<>();
    final double durationMs;
    final float width, height, baseFont;
    /** One time-independent placement for the whole measured layout, never a PV camera. */
    final float layoutCenterX, layoutCenterY, layoutScale;

    SonnetTextScene(SonnetProgram.Shot shot, SonnetTextFonts fonts, float width, float height) {
        this.shot = shot; this.width = width; this.height = height;
        int words = 0;
        for (List<SonnetTypography.Segment> line : shot.lines) {
            for (SonnetTypography.Segment segment : line) if (segment.wordLike) words++;
        }
        float heroScale = shot.kind == SonnetTypography.Kind.TYPE_IMPACT ? 1.55f
                : shot.kind == SonnetTypography.Kind.QUIET_TABLEAU ? 0.82f : 1;
        baseFont = Math.max(24, Math.min(112, width / Math.max(7f, words * 2.15f) * heroScale));
        durationMs = SonnetTextMotion.motionDuration(shot.startMs, shot.renderEndMs);
        boolean staffAdded = false;
        for (SonnetTypography.Box box : SonnetTypography.layout(shot.lines, shot.kind,
                width, height, baseFont, fonts::width)) {
            if (box.segment.text.trim().equals("♪")) {
                if (staffAdded || box.role == SonnetTypography.Role.DECORATION) continue;
                staffAdded = true;
            }
            Segment view = new Segment(box, fonts, shot, width, durationMs);
            segments.add(view);
        }
        float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (Segment segment : segments) {
            if (segment.box.role == SonnetTypography.Role.DECORATION) continue;
            left = Math.min(left, segment.x - segment.box.width * 0.5f);
            right = Math.max(right, segment.x + segment.box.width * 0.5f);
            top = Math.min(top, segment.y - segment.box.height * 0.5f);
            bottom = Math.max(bottom, segment.y + segment.box.height * 0.5f);
        }
        if (left <= right && top <= bottom) {
            layoutCenterX = (left + right) * 0.5f;
            layoutCenterY = (top + bottom) * 0.5f;
            // Fit once, using all core words, so later reveals cannot push earlier words out of frame.
            // Giant outline copies are intentional overflow and must not shrink the actual lyrics.
            layoutScale = Math.min(1f, Math.min(width * 0.86f / Math.max(1, right - left),
                    height * 0.8f / Math.max(1, bottom - top)));
        } else {
            layoutCenterX = layoutCenterY = 0;
            layoutScale = 1;
        }
    }

    void update(double timeMs) {
        for (Segment segment : segments) {
            boolean emphasis = segment.box.role == SonnetTypography.Role.HERO
                    || segment.box.role == SonnetTypography.Role.SEMI_HERO;
            for (Glyph glyph : segment.glyphs) {
                double p = SonnetTextMotion.progress(glyph.startMs, glyph.settleMs, timeMs);
                float offset = (float) (1 - p);
                glyph.progress = (float) p;
                glyph.alpha = timeMs < glyph.startMs ? 0 : (float) (0.16 + p * 0.84);
                glyph.scale = (float) (emphasis && shot.kind == SonnetTypography.Kind.TYPE_IMPACT
                        ? 0.52 + p * 0.48 : 0.86 + p * 0.14);
                glyph.x = glyph.baseX + glyph.enterX * offset;
                glyph.y = glyph.baseY + glyph.enterY * offset;
                glyph.rotation = segment.box.rotationRad + glyph.entryRotation * offset;
            }
        }
    }

    void close() {
        for (Segment segment : segments) segment.close();
        segments.clear();
    }

}
