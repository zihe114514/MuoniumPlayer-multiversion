package com.muoniumplayer.core.lyrics3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Measured typography adapted from Folia's Sonnet layouts (chthollyphile, AGPL-3.0). */
final class SonnetTypography {
    enum Kind { EDITORIAL_COLUMN, TYPE_IMPACT, FRAGMENT_COLLAGE, TRACKING_RIBBON,
        MASK_REVEAL, POSTER_BLOCKS, QUIET_TABLEAU }
    enum Role { HERO, SEMI_HERO, SUPPORT, DECORATION }

    static final class Glyph {
        final String text;
        final double startMs, endMs;
        Glyph(String text, double startMs, double endMs) { this.text = text; this.startMs = startMs; this.endMs = endMs; }
    }

    static final class Segment {
        final String text;
        final int startOffset, endOffset;
        final double startMs, endMs;
        final boolean wordLike;
        final List<Glyph> glyphs;
        Segment(String text, int startOffset, int endOffset, double startMs, double endMs,
                boolean wordLike, List<Glyph> glyphs) {
            this.text = text; this.startOffset = startOffset; this.endOffset = endOffset;
            this.startMs = startMs; this.endMs = endMs; this.wordLike = wordLike;
            this.glyphs = List.copyOf(glyphs);
        }
    }

    @FunctionalInterface interface Measure { float width(String text, float fontSize, Role role); }

    static final class Box {
        final Segment segment;
        int segmentIndex;
        Role role;
        boolean hero, semiHero, vertical, flowVertical;
        float fontSize, width, height, x, y, rotationRad, enterX, enterY;
        float verticalWidth, verticalHeight, verticalFontSize;
        Box(Segment segment, int segmentIndex, Role role) {
            this.segment = segment; this.segmentIndex = segmentIndex; this.role = role;
            hero = role == Role.HERO; semiHero = role == Role.SEMI_HERO;
        }
        Box copy() {
            Box b = new Box(segment, segmentIndex, role);
            b.hero = hero; b.semiHero = semiHero; b.vertical = vertical; b.flowVertical = flowVertical;
            b.fontSize = fontSize; b.width = width; b.height = height; b.x = x; b.y = y;
            b.rotationRad = rotationRad; b.enterX = enterX; b.enterY = enterY;
            b.verticalWidth = verticalWidth; b.verticalHeight = verticalHeight; b.verticalFontSize = verticalFontSize;
            return b;
        }
    }

    static List<Box> layout(List<List<Segment>> source, Kind kind, float width, float height,
                            float baseFontSize, Measure measure) {
        List<List<Segment>> lines = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        for (List<Segment> input : source) {
            List<Segment> line = new ArrayList<>();
            for (Segment s : input) if (!s.text.isBlank()) line.add(s);
            if (!line.isEmpty()) { lines.add(line); segments.addAll(line); }
        }
        if (segments.isEmpty()) return List.of();
        Set<Integer> heroes = new HashSet<>(), semiHeroes = new HashSet<>();
        int offset = 0;
        for (List<Segment> line : lines) {
            int hero = hero(line);
            heroes.add(offset + hero);
            for (int semi : semiHeroes(line, hero)) semiHeroes.add(offset + semi);
            offset += line.size();
        }
        int heroIndex = hero(segments), variantSeed = segments.size();
        StringBuilder signature = new StringBuilder();
        for (Segment s : segments) {
            variantSeed += Math.max(1, s.text.trim().length());
            if (signature.length() > 0) signature.append('\u241f');
            signature.append(s.text);
        }
        int editorial = variantSeed % 5, secondary = -1;
        if (editorial == 3 && segments.size() > 2) {
            double best = -Double.MAX_VALUE;
            for (int i = 0; i < segments.size(); i++) {
                Segment s = segments.get(i);
                if (i == heroIndex || !s.wordLike || visible(s) == 0) continue;
                double score = score(s) + (Math.abs(i - heroIndex) > 1 ? 50 : 0);
                if (score > best) { best = score; secondary = i; }
            }
            if (secondary < 0) editorial = 0;
        } else if (editorial == 3) editorial = 0;
        else if (editorial == 4 && segments.size() < 2) editorial = 2;
        if (kind == Kind.EDITORIAL_COLUMN && editorial == 3 && secondary >= 0) heroes.add(secondary);

        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            boolean main = heroes.contains(i), semi = semiHeroes.contains(i) && !main;
            float heroScale, supportScale;
            boolean vertical = false;
            switch (kind) {
                case EDITORIAL_COLUMN:
                    heroScale = editorial == 3 ? 3.8f : editorial == 4 ? 4.2f : editorial == 2 ? 3.2f : 4;
                    supportScale = editorial == 3 ? 1.3f : editorial == 4 ? 1.25f : 1.2f;
                    vertical = (main || semi) && editorial != 2 && editorial != 3; break;
                case TYPE_IMPACT: heroScale = 4.8f; supportScale = 1.5f; break;
                case FRAGMENT_COLLAGE:
                    heroScale = 3.2f; supportScale = 1.35f; vertical = semi || i % 4 == 0; break;
                case TRACKING_RIBBON: heroScale = 3.5f; supportScale = 1.5f; break;
                case MASK_REVEAL: heroScale = 4.5f; supportScale = 1.6f; vertical = main || semi; break;
                case POSTER_BLOCKS: heroScale = 4.4f; supportScale = 1.15f; break;
                case QUIET_TABLEAU:
                default:
                    heroScale = 3; supportScale = 1.15f;
                    vertical = (main || semi) && variantSeed % 4 < 2; break;
            }
            Role role = main ? Role.HERO : semi ? Role.SEMI_HERO : Role.SUPPORT;
            Box box = new Box(segments.get(i), i, role);
            box.fontSize = baseFontSize * (main ? heroScale : semi ? Math.max(supportScale * 1.35f, heroScale * 0.72f) : supportScale);
            boolean rotates = vertical && visible(box.segment) > 1 && !cjk(box.segment.text);
            box.vertical = vertical && !rotates;
            box.rotationRad = rotates ? (float) Math.PI / 2 : 0;
            float fitRatio = kind == Kind.TYPE_IMPACT && main ? 0.72f : 0.82f;
            measure(box, measure, width * fitRatio, height * fitRatio);
            if (kind == Kind.POSTER_BLOCKS && cjk(box.segment.text)) {
                Box alternative = box.copy(); alternative.vertical = true; alternative.rotationRad = 0;
                measure(alternative, measure, width * 0.82f, height * 0.82f);
                box.verticalWidth = alternative.width; box.verticalHeight = alternative.height;
                box.verticalFontSize = alternative.fontSize;
            }
            boxes.add(box);
        }
        if (kind == Kind.POSTER_BLOCKS) SonnetPosterLayout.layout(boxes, width, height, baseFontSize, hash(signature.toString()));
        else SonnetFlowLayout.layout(boxes, heroIndex, kind, width, height, baseFontSize,
                editorial, secondary, variantSeed);
        Box hero = boxes.get(heroIndex);
        hero.enterX = 0; hero.enterY = height * 0.15f;
        List<Box> output = new ArrayList<>();
        if (kind != Kind.QUIET_TABLEAU && kind != Kind.POSTER_BLOCKS) {
            int count = 0;
            float outlineScaleLimit = kind == Kind.TYPE_IMPACT ? 4.8f : 5.5f;
            for (Box b : boxes) {
                if (!b.hero) continue;
                Box copy = b.copy(); copy.role = Role.DECORATION; copy.hero = copy.semiHero = false;
                float size = baseFontSize * Math.max(2.8f,
                        Math.min(b.fontSize / baseFontSize * 3.5f, outlineScaleLimit));
                copy.vertical = false; copy.fontSize = size;
                copy.x = b.x - width * (0.1f - count * 0.03f);
                copy.y = b.y - height * (0.05f - count * 0.02f);
                copy.rotationRad = -0.15f + ((count & 1) == 0 ? 0 : 0.05f);
                copy.enterX = -width * 0.05f; copy.enterY = -height * 0.05f;
                copy.width = horizontalAdvance(copy.segment, size, Role.DECORATION, measure);
                copy.height = size * 1.2f;
                output.add(copy); count++;
            }
            if (boxes.size() > 1 && count > 0) {
                Box h = boxes.stream().filter(b -> b.hero).findFirst().orElse(hero);
                Box end = boxes.get(boxes.size() - 1);
                Box copy = (end.hero ? boxes.get(0) : end).copy();
                copy.role = Role.DECORATION; copy.hero = copy.semiHero = false; copy.vertical = false;
                copy.fontSize = baseFontSize * Math.max(1.8f, Math.min(h.fontSize / baseFontSize * 2.2f, 3.5f));
                copy.x = h.x + width * 0.25f; copy.y = h.y + height * 0.15f;
                copy.rotationRad = 0.08f; copy.enterX = width * 0.05f; copy.enterY = height * 0.05f;
                copy.width = horizontalAdvance(copy.segment, copy.fontSize, Role.DECORATION, measure);
                copy.height = copy.fontSize * 1.2f; output.add(copy);
            }
        }
        output.addAll(boxes);
        return output;
    }

    private static void measure(Box box, Measure measure, float maxWidth, float maxHeight) {
        if (box.vertical) {
            box.width = 0;
            for (Glyph g : box.segment.glyphs) if (!g.text.isBlank()) {
                box.width = Math.max(box.width, Math.max(box.fontSize * 0.2f, measure.width(g.text, box.fontSize, box.role)));
            }
            box.height = Math.max(1, box.segment.glyphs.size()) * box.fontSize * 0.9f;
        } else if (box.rotationRad != 0) {
            float advance = 0;
            for (Glyph g : box.segment.glyphs) if (!g.text.isBlank()) {
                advance += Math.max(box.fontSize * 0.2f, measure.width(g.text, box.fontSize, box.role));
            }
            box.width = box.fontSize * 1.2f; box.height = advance;
        } else {
            box.width = horizontalAdvance(box.segment, box.fontSize, box.role, measure);
            box.height = box.fontSize * 1.2f;
        }
        float fit = Math.min(1, Math.min(maxWidth / Math.max(1, box.width), maxHeight / Math.max(1, box.height)));
        box.width *= fit; box.height *= fit; box.fontSize *= fit;
    }

    static float advance(Glyph glyph, float size, Role role, Measure measure) {
        return Math.max(size * 0.2f, measure.width(glyph.text, size, role));
    }

    static float horizontalAdvance(Segment segment, float size, Role role, Measure measure) {
        float total = 0;
        for (Glyph glyph : segment.glyphs) total += advance(glyph, size, role, measure);
        return total;
    }

    static int hero(List<Segment> segments) {
        int best = 0; double score = -Double.MAX_VALUE;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (!s.wordLike || visible(s) == 0) continue;
            double candidate = score(s);
            if (candidate > score) { best = i; score = candidate; }
        }
        return best;
    }

    static double score(Segment segment) {
        return Math.min(visible(segment), 8) * 14 + Math.min(2.5, Math.max(0, segment.endMs - segment.startMs) / 1000) * 18;
    }

    private static List<Integer> semiHeroes(List<Segment> segments, int hero) {
        List<Integer> result = new ArrayList<>(2);
        long count = segments.stream().filter(s -> s.wordLike && visible(s) > 0).count();
        if (count < 4) return result;
        double threshold = score(segments.get(hero)) * 0.35;
        boolean right = hero <= (segments.size() - 1) / 2;
        int primary = best(segments, hero, threshold, right, -1);
        if (primary < 0) primary = best(segments, hero, threshold, !right, -1);
        if (primary >= 0) result.add(primary);
        if (count >= 9 && primary >= 0) {
            int secondary = best(segments, hero, threshold, !right, primary);
            if (secondary >= 0) result.add(secondary);
        }
        result.sort(Integer::compare);
        return result;
    }

    private static int best(List<Segment> segments, int hero, double threshold, boolean right, int previous) {
        int best = -1; double score = -Double.MAX_VALUE;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (!s.wordLike || visible(s) < 2 || Math.abs(i - hero) < 2 || (i > hero) != right
                    || previous >= 0 && Math.abs(i - previous) < 2) continue;
            double candidate = score(s);
            if (candidate >= threshold && candidate > score) { best = i; score = candidate; }
        }
        return best;
    }

    private static int visible(Segment segment) {
        int count = 0;
        for (Glyph g : segment.glyphs) if (!g.text.isBlank()) count++;
        return count;
    }

    static boolean cjk(String text) {
        return text.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff
                || c >= 0x3040 && c <= 0x30ff || c >= 0xac00 && c <= 0xd7af);
    }

    static int hash(String text) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < text.length(); i++) hash = (hash ^ text.charAt(i)) * 16777619;
        return hash;
    }
}
