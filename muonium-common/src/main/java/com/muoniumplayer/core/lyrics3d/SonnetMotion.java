package com.muoniumplayer.core.lyrics3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure text-only kinetic layout and motion for the Sonnet world-lyrics style. */
final class SonnetMotion {

    static final int RIBBON = 0;
    static final int POSTER = 1;
    static final int CROSS = 2;
    static final int EDITORIAL = 3;

    private static final double ENTER_LEAD_MS = 240.0;
    private static final double ENTER_DURATION_MS = 520.0;
    private static final double FLOW_GAP = WorldLyricsState.LYRIC_SIZE * 0.28;

    private SonnetMotion() {
    }

    interface PixelMetrics {
        float widthOf(char c);
    }

    static final class Slot {
        final CadenzaMotion.Token token;
        final int index;
        final boolean hero;
        final boolean semiHero;
        final boolean vertical;
        final boolean guideDecoration;
        final boolean frameDecoration;
        final int entryKind;
        final float direction;
        float x;
        float y;
        float width;
        float height;
        float fontScale;
        float rotation;

        Slot(CadenzaMotion.Token token, int index, boolean hero, boolean semiHero,
             boolean vertical, boolean guideDecoration, boolean frameDecoration,
             int entryKind, float direction, float fontScale, float width, float height) {
            this.token = token;
            this.index = index;
            this.hero = hero;
            this.semiHero = semiHero;
            this.vertical = vertical;
            this.guideDecoration = guideDecoration;
            this.frameDecoration = frameDecoration;
            this.entryKind = entryKind;
            this.direction = direction;
            this.fontScale = fontScale;
            this.width = width;
            this.height = height;
        }
    }

    static final class Layout {
        final int kind;
        final int heroIndex;
        final List<Slot> slots;
        final float width;
        final float height;

        Layout(int kind, int heroIndex, List<Slot> slots, float width, float height) {
            this.kind = kind;
            this.heroIndex = heroIndex;
            this.slots = Collections.unmodifiableList(slots);
            this.width = width;
            this.height = height;
        }
    }

    static final class Pose {
        float alpha;
        float scale = 1f;
        float rotation;
        float dx;
        float dy;
        float glowMul = 1f;
        float echoAlpha;
        float echoDx;
        float echoDy;
        float trailAlpha;
        float spark;
        float guideAlpha;
        float guideProgress;
        float frameAlpha;
        float frameProgress;
        boolean active;
        boolean trailOwner;
        boolean decorationOwner;
        boolean frameOwner;

        void reset() {
            alpha = 0f;
            scale = 1f;
            rotation = dx = dy = 0f;
            glowMul = 1f;
            echoAlpha = echoDx = echoDy = trailAlpha = spark = 0f;
            guideAlpha = guideProgress = frameAlpha = frameProgress = 0f;
            active = trailOwner = decorationOwner = frameOwner = false;
        }
    }

    static Layout layout(List<CadenzaMotion.Token> tokens, PixelMetrics metrics,
                         float maxWidth, float maxHeight, float lineHeight, int seed) {
        if (tokens == null || tokens.isEmpty()) {
            return new Layout(RIBBON, -1, new ArrayList<>(), 0f, 0f);
        }
        int heroIndex = findHero(tokens);
        boolean[] semi = findSemiHeroes(tokens, heroIndex);
        int kind = Math.floorMod(seed, 4);
        List<Slot> slots = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            CadenzaMotion.Token token = tokens.get(i);
            boolean hero = i == heroIndex;
            boolean semiHero = semi[i];
            float scale = hero ? 1.48f : semiHero ? 1.14f : 0.84f;
            boolean vertical = kind == EDITORIAL && hero && isCjk(token.text)
                    && visibleLength(token.text) > 1;
            float width = 0f;
            float maxCharWidth = 0f;
            for (int c = 0; c < token.text.length(); c++) {
                float charWidth = Math.max(WorldLyricsState.LYRIC_SIZE * 0.2f,
                        metrics.widthOf(token.text.charAt(c)));
                width += charWidth;
                maxCharWidth = Math.max(maxCharWidth, charWidth);
            }
            float height;
            if (vertical) {
                width = maxCharWidth * scale;
                height = Math.max(1, token.charCount) * lineHeight * 0.88f * scale;
            } else {
                width *= scale;
                height = lineHeight * scale;
            }
            int entryKind = hero ? 0 : Math.floorMod(seed + i * 31 + kind * 7, 3) + 1;
            float direction = ((i + seed) & 1) == 0 ? -1f : 1f;
            int decorRoll = Math.floorMod(seed + i * 73, 10);
            boolean guideDecoration = hero || semiHero || decorRoll < 4;
            boolean frameDecoration = decorRoll < 4;
            slots.add(new Slot(token, i, hero, semiHero, vertical,
                    guideDecoration, frameDecoration, entryKind,
                    direction, scale, width, height));
        }

        switch (kind) {
            case POSTER:
                layoutPoster(slots, heroIndex);
                break;
            case CROSS:
                layoutCross(slots, heroIndex);
                break;
            case EDITORIAL:
                layoutEditorial(slots, heroIndex);
                break;
            case RIBBON:
            default:
                layoutRibbon(slots, heroIndex);
                break;
        }

        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (Slot slot : slots) {
            minX = Math.min(minX, slot.x - slot.width * 0.5f);
            maxX = Math.max(maxX, slot.x + slot.width * 0.5f);
            minY = Math.min(minY, slot.y - slot.height * 0.5f);
            maxY = Math.max(maxY, slot.y + slot.height * 0.5f);
        }
        float contentWidth = Math.max(1f, maxX - minX);
        float contentHeight = Math.max(1f, maxY - minY);
        float fit = Math.min(1f, Math.min(maxWidth / contentWidth, maxHeight / contentHeight));
        if (fit < 1f) {
            for (Slot slot : slots) {
                slot.x *= fit;
                slot.y *= fit;
                slot.width *= fit;
                slot.height *= fit;
                slot.fontScale *= fit;
            }
            contentWidth *= fit;
            contentHeight *= fit;
        }
        return new Layout(kind, heroIndex, slots, contentWidth, contentHeight);
    }

    static double heroScore(CadenzaMotion.Token token) {
        int length = visibleLength(token.text);
        double duration = Math.max(0.0, token.endMs - token.startMs) / 1000.0;
        return Math.min(length, 8) * 14.0 + Math.min(duration, 2.5) * 18.0;
    }

    static int findHero(List<CadenzaMotion.Token> tokens) {
        if (tokens == null || tokens.isEmpty()) return -1;
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < tokens.size(); i++) {
            double score = heroScore(tokens.get(i));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    static void pose(Slot slot, int localCharIndex, double elapsedMs, double appearAtMs,
                     float rowAlpha, boolean exiting, Pose out) {
        out.reset();
        CadenzaMotion.Token token = slot.token;
        double eventAt = appearAtMs;
        double charDelay = Math.min(Math.max(0, localCharIndex), 6) * 12.0;
        double progress = clamp01((elapsedMs - (eventAt - ENTER_LEAD_MS + charDelay))
                / ENTER_DURATION_MS);
        if (elapsedMs < eventAt - ENTER_LEAD_MS + charDelay) return;

        double eased = easeOutCubic(progress);
        double back = easeOutBack(progress);
        double remaining = 1.0 - eased;
        double h = WorldLyricsState.LYRIC_SIZE;
        if (slot.entryKind == 0) {
            out.scale = (float) (0.24 + 0.76 * back);
            out.dy = (float) (-remaining * h * 2.2);
            out.rotation = (float) (-remaining * 16.0);
        } else if (slot.entryKind == 1) {
            out.scale = (float) (0.82 + 0.18 * back);
            out.dx = (float) (-slot.direction * remaining * h * 3.8);
            out.dy = (float) (Math.sin(progress * Math.PI) * -h * 0.34);
        } else if (slot.entryKind == 2) {
            out.scale = (float) (0.78 + 0.22 * back);
            out.dy = (float) (-remaining * h * 3.0);
            out.rotation = (float) (slot.direction * remaining * 22.0);
        } else {
            out.scale = (float) (0.38 + 0.62 * back);
            out.dx = (float) (slot.direction * remaining * h * 1.8);
            out.rotation = (float) (-slot.direction * remaining * 180.0);
        }

        double reveal = smoothstep(clamp01(progress / 0.68));
        out.alpha = (float) (reveal * Math.max(0f, Math.min(1f, rowAlpha)));
        out.decorationOwner = localCharIndex == Math.max(0, token.charCount / 2);
        out.frameOwner = out.decorationOwner && slot.frameDecoration;
        if (out.decorationOwner) {
            double entryAge = Math.max(0.0, elapsedMs - (eventAt - ENTER_LEAD_MS + charDelay));
            if (slot.guideDecoration) {
                out.guideProgress = (float) easeOutCubic(clamp01(entryAge / 280.0));
                double guideFade = 1.0 - smoothstep(clamp01((entryAge - 250.0) / 420.0));
                out.guideAlpha = (float) (guideFade * Math.max(0f, Math.min(1f, rowAlpha)));
            }
            if (out.frameOwner) {
                out.frameProgress = (float) easeOutCubic(clamp01(entryAge / 360.0));
                out.frameAlpha = out.alpha * 0.72f;
            }
        }
        boolean timed = token.endMs > token.startMs;
        double activeStart = timed ? token.startMs : appearAtMs;
        double activeEnd = timed ? token.endMs : activeStart + 150.0;
        out.active = elapsedMs >= activeStart && elapsedMs <= activeEnd;
        double pulse = out.active
                ? 0.5 + 0.5 * Math.sin((elapsedMs - activeStart) * 0.018 + slot.index * 1.7)
                : 0.0;
        out.scale *= (float) (1.0 + pulse * (slot.hero ? 0.045 : slot.semiHero ? 0.025 : 0.012));
        out.glowMul = (float) (1.0 + pulse * 0.38 + (slot.hero ? 0.35 : slot.semiHero ? 0.16 : 0.0));

        float echo = slot.hero || slot.semiHero ? (float) ((1.0 - eased) * 0.32) : 0f;
        out.echoAlpha = echo;
        out.echoDx = (float) (-out.dx * 0.62 - slot.direction * echo * h * 0.18);
        out.echoDy = -out.dy * 0.62f;
        out.trailAlpha = slot.hero ? echo * 0.72f : 0f;
        out.trailOwner = localCharIndex == Math.max(0, token.charCount / 2);
        if (slot.hero && out.trailOwner) {
            out.spark = (float) clamp01(1.0 - Math.abs(elapsedMs - appearAtMs) / 130.0);
        }

        if (exiting) {
            double exit = clamp01((1.0 - rowAlpha) * 1.35 - slot.index * 0.025);
            double exitEase = easeInCubic(exit);
            out.dx += (float) (slot.direction * exitEase * h * 1.35);
            out.dy += (float) (-exitEase * h * 0.45);
            out.rotation += (float) (slot.direction * exitEase * 28.0);
            out.scale *= (float) (1.0 - 0.22 * exit);
        }
    }

    private static boolean[] findSemiHeroes(List<CadenzaMotion.Token> tokens, int heroIndex) {
        boolean[] selected = new boolean[tokens.size()];
        if (tokens.size() < 4 || heroIndex < 0) return selected;
        double threshold = heroScore(tokens.get(heroIndex)) * 0.35;
        boolean heroLeansEarly = heroIndex <= (tokens.size() - 1) / 2;
        int first = bestCandidate(tokens, heroIndex, threshold, heroLeansEarly);
        if (first < 0) first = bestCandidate(tokens, heroIndex, threshold, !heroLeansEarly);
        if (first >= 0) selected[first] = true;
        if (tokens.size() >= 9 && first >= 0) {
            int second = bestCandidate(tokens, heroIndex, threshold, !heroLeansEarly);
            if (second >= 0 && Math.abs(second - first) >= 2) selected[second] = true;
        }
        return selected;
    }

    private static int bestCandidate(List<CadenzaMotion.Token> tokens, int heroIndex,
                                     double threshold, boolean rightSide) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < tokens.size(); i++) {
            if (i == heroIndex || Math.abs(i - heroIndex) < 2) continue;
            if ((i > heroIndex) != rightSide || visibleLength(tokens.get(i).text) < 2) continue;
            double score = heroScore(tokens.get(i));
            if (score >= threshold && score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    private static void layoutRibbon(List<Slot> slots, int heroIndex) {
        Slot hero = slots.get(heroIndex);
        hero.x = 0f;
        hero.y = 0f;
        float cursor = -hero.width * 0.5f - (float) FLOW_GAP;
        for (int i = heroIndex - 1; i >= 0; i--) {
            Slot slot = slots.get(i);
            slot.x = cursor - slot.width * 0.5f;
            slot.y = (i & 1) == 0 ? -WorldLyricsState.LYRIC_SIZE * 0.12f : 0f;
            cursor -= slot.width + (float) FLOW_GAP;
        }
        cursor = hero.width * 0.5f + (float) FLOW_GAP;
        for (int i = heroIndex + 1; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            slot.x = cursor + slot.width * 0.5f;
            slot.y = (i & 1) == 0 ? WorldLyricsState.LYRIC_SIZE * 0.12f : 0f;
            cursor += slot.width + (float) FLOW_GAP;
        }
    }

    private static void layoutPoster(List<Slot> slots, int heroIndex) {
        Slot hero = slots.get(heroIndex);
        hero.x = 0f;
        hero.y = 0f;
        List<Slot> before = slots.subList(0, heroIndex);
        List<Slot> after = slots.subList(heroIndex + 1, slots.size());
        float beforeHeight = maxHeight(before);
        float afterHeight = maxHeight(after);
        placeCenteredRow(before, -hero.height * 0.5f - (float) FLOW_GAP - beforeHeight * 0.5f);
        placeCenteredRow(after, hero.height * 0.5f + (float) FLOW_GAP + afterHeight * 0.5f);
    }

    private static void layoutCross(List<Slot> slots, int heroIndex) {
        Slot hero = slots.get(heroIndex);
        hero.x = 0f;
        hero.y = 0f;
        int beforeSplit = heroIndex / 2;
        List<Slot> top = slots.subList(0, beforeSplit);
        List<Slot> left = slots.subList(beforeSplit, heroIndex);
        int afterSplit = heroIndex + 1 + (slots.size() - heroIndex - 1) / 2;
        List<Slot> right = slots.subList(heroIndex + 1, afterSplit);
        List<Slot> bottom = slots.subList(afterSplit, slots.size());
        float topHeight = columnHeight(top);
        float bottomHeight = columnHeight(bottom);
        float leftWidth = rowWidth(left);
        float rightWidth = rowWidth(right);
        placeCenteredColumnAt(top, 0f,
                -hero.height * 0.5f - (float) FLOW_GAP - topHeight * 0.5f);
        placeCenteredRowAt(left,
                -hero.width * 0.5f - (float) FLOW_GAP - leftWidth * 0.5f, 0f);
        placeCenteredRowAt(right,
                hero.width * 0.5f + (float) FLOW_GAP + rightWidth * 0.5f, 0f);
        placeCenteredColumnAt(bottom, 0f,
                hero.height * 0.5f + (float) FLOW_GAP + bottomHeight * 0.5f);
    }

    private static void layoutEditorial(List<Slot> slots, int heroIndex) {
        Slot hero = slots.get(heroIndex);
        hero.x = 0f;
        hero.y = 0f;
        List<Slot> left = slots.subList(0, heroIndex);
        List<Slot> right = slots.subList(heroIndex + 1, slots.size());
        placeCenteredColumn(left, -hero.width * 0.5f - (float) FLOW_GAP - maxWidth(left) * 0.5f);
        placeCenteredColumn(right, hero.width * 0.5f + (float) FLOW_GAP + maxWidth(right) * 0.5f);
    }

    private static void placeCenteredRow(List<Slot> slots, float y) {
        if (slots.isEmpty()) return;
        float total = (float) FLOW_GAP * (slots.size() - 1);
        for (Slot slot : slots) total += slot.width;
        float cursor = -total * 0.5f;
        for (Slot slot : slots) {
            slot.x = cursor + slot.width * 0.5f;
            slot.y = y;
            cursor += slot.width + (float) FLOW_GAP;
        }
    }

    private static void placeCenteredColumn(List<Slot> slots, float x) {
        placeCenteredColumnAt(slots, x, 0f);
    }

    private static void placeCenteredColumnAt(List<Slot> slots, float x, float centerY) {
        if (slots.isEmpty()) return;
        float total = columnHeight(slots);
        float cursor = centerY - total * 0.5f;
        for (Slot slot : slots) {
            slot.x = x;
            slot.y = cursor + slot.height * 0.5f;
            cursor += slot.height + (float) FLOW_GAP;
        }
    }

    private static void placeCenteredRowAt(List<Slot> slots, float centerX, float y) {
        if (slots.isEmpty()) return;
        float total = rowWidth(slots);
        float cursor = centerX - total * 0.5f;
        for (Slot slot : slots) {
            slot.x = cursor + slot.width * 0.5f;
            slot.y = y;
            cursor += slot.width + (float) FLOW_GAP;
        }
    }

    private static float rowWidth(List<Slot> slots) {
        if (slots.isEmpty()) return 0f;
        float total = (float) FLOW_GAP * (slots.size() - 1);
        for (Slot slot : slots) total += slot.width;
        return total;
    }

    private static float columnHeight(List<Slot> slots) {
        if (slots.isEmpty()) return 0f;
        float total = (float) FLOW_GAP * (slots.size() - 1);
        for (Slot slot : slots) total += slot.height;
        return total;
    }

    private static float maxWidth(List<Slot> slots) {
        float max = 0f;
        for (Slot slot : slots) max = Math.max(max, slot.width);
        return max;
    }

    private static float maxHeight(List<Slot> slots) {
        float max = 0f;
        for (Slot slot : slots) max = Math.max(max, slot.height);
        return max;
    }

    private static int visibleLength(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) count++;
        }
        return count;
    }

    private static boolean isCjk(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !isCjkChar(c)) return false;
        }
        return true;
    }

    private static boolean isCjkChar(char c) {
        return (c >= '\u2E80' && c <= '\u2FFF')
                || (c >= '\u3000' && c <= '\u30FF')
                || (c >= '\u31F0' && c <= '\u31FF')
                || (c >= '\u3400' && c <= '\u9FFF')
                || (c >= '\uF900' && c <= '\uFAFF')
                || (c >= '\uAC00' && c <= '\uD7AF')
                || (c >= '\uFF65' && c <= '\uFF9F');
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double smoothstep(double value) {
        double t = clamp01(value);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double easeOutCubic(double value) {
        double t = clamp01(value);
        return 1.0 - Math.pow(1.0 - t, 3.0);
    }

    private static double easeInCubic(double value) {
        double t = clamp01(value);
        return t * t * t;
    }

    private static double easeOutBack(double value) {
        double t = clamp01(value) - 1.0;
        return 1.0 + 2.70158 * t * t * t + 1.70158 * t * t;
    }
}
