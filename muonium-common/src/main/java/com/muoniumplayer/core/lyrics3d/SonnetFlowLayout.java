package com.muoniumplayer.core.lyrics3d;

import java.util.ArrayList;
import java.util.List;
import com.muoniumplayer.core.lyrics3d.SonnetTypography.Box;
import com.muoniumplayer.core.lyrics3d.SonnetTypography.Kind;

/** Measured reading-order flow passes from Folia Sonnet (AGPL-3.0). */
final class SonnetFlowLayout {
    private final List<Box> boxes;
    private final int heroIndex;
    private final float width, height, gap, stack;
    private final Box hero;
    private SonnetFlowLayout(List<Box> boxes, int heroIndex, float width, float height, float font) {
        this.boxes = boxes; this.heroIndex = heroIndex; this.width = width; this.height = height;
        gap = Math.min(40, Math.max(16, font * 0.35f)); stack = Math.max(24, gap * 1.35f);
        hero = boxes.get(heroIndex);
    }
    static void layout(List<Box> boxes, int heroIndex, Kind kind, float width, float height,
                       float font, int editorialVariant, int secondary, int variantSeed) {
        SonnetFlowLayout flow = new SonnetFlowLayout(boxes, heroIndex, width, height, font);
        if (kind == Kind.FRAGMENT_COLLAGE) {
            for (int i = 0; i < boxes.size(); i++) {
                if (i == heroIndex) continue;
                Box box = boxes.get(i);
                if (Math.abs(Math.round(box.rotationRad / (Math.PI / 2)) % 2) == 1) {
                    float swap = box.width; box.width = box.height; box.height = swap;
                }
                box.rotationRad = 0;
            }
        }
        flow.fit(scale -> {
            switch (kind) {
                case QUIET_TABLEAU: flow.quiet(variantSeed % 4); break;
                case TRACKING_RIBBON: flow.ribbon(variantSeed % 3); break;
                case EDITORIAL_COLUMN: flow.editorial(editorialVariant, secondary); break;
                case FRAGMENT_COLLAGE: flow.collage(variantSeed % 3, scale); break;
                default: flow.cross(); break;
            }
        });
    }

    private interface Placement { void place(float scale); }
    private void fit(Placement placement) {
        float[][] original = new float[boxes.size()][3];
        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i); original[i] = new float[]{b.fontSize, b.width, b.height};
        }
        for (float scale : new float[]{1, 0.92f, 0.84f, 0.76f, 0.68f, 0.6f, 0.52f}) {
            for (int i = 0; i < boxes.size(); i++) {
                Box b = boxes.get(i); b.fontSize = original[i][0] * scale;
                b.width = original[i][1] * scale; b.height = original[i][2] * scale;
            }
            placement.place(scale);
            boolean fits = true;
            for (Box b : boxes) {
                if (Math.abs(b.x) + b.width / 2 > width * 0.48f + 0.5f
                        || Math.abs(b.y) + b.height / 2 > height * 0.46f + 0.5f) { fits = false; break; }
            }
            if (fits) return;
        }
    }

    private void quiet(int variant) {
        boolean horizontal = variant == 2 || variant == 3;
        for (Box b : boxes) b.flowVertical = !horizontal;
        hero.x = 0; hero.y = horizontal ? 0 : -height * 0.1f;
        float step = maxWidth(boxes) + stack + (variant == 3 ? 70 : 0);
        float limit = height * 0.46f;
        int column = 0;
        float y = hero.y - hero.height / 2 - stack;
        for (int i = heroIndex - 1; i >= 0; i--) {
            Box b = boxes.get(i);
            if (y - b.height < -limit) { column++; y = limit; }
            b.x = quietX(b, i, variant) + column * step; b.y = y - b.height / 2;
            y -= b.height + stack;
            quietEnter(b, variant, true);
        }
        column = 0; y = hero.y + hero.height / 2 + stack;
        for (int i = heroIndex + 1; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            if (y + b.height > limit) { column++; y = -limit; }
            b.x = quietX(b, i, variant) - column * step; b.y = y + b.height / 2;
            y += b.height + stack;
            quietEnter(b, variant, false);
        }
    }
    private float quietX(Box b, int i, int variant) {
        return variant == 1 ? hero.x - hero.width / 2 + b.width / 2
                : variant == 3 ? hero.x + ((i & 1) == 0 ? 35 : -35) : hero.x;
    }
    private void quietEnter(Box b, int variant, boolean before) {
        b.enterX = variant == 1 ? (before ? 20 : -20) : variant == 3 ? (b.x > hero.x ? 30 : -30) : 0;
        b.enterY = variant == 1 || variant == 3 ? 0 : before ? 20 : -20;
    }

    private void ribbon(int variant) {
        for (Box b : boxes) b.flowVertical = false;
        hero.x = hero.y = 0;
        float x = -hero.width / 2 - gap;
        for (int i = heroIndex - 1; i >= 0; i--) {
            Box b = boxes.get(i); b.x = x - b.width / 2; b.y = ribbonY(b, i, variant);
            x -= b.width + gap; b.enterX = variant == 2 ? 20 : 30; b.enterY = 0;
        }
        x = hero.width / 2 + gap;
        for (int i = heroIndex + 1; i < boxes.size(); i++) {
            Box b = boxes.get(i); b.x = x + b.width / 2; b.y = ribbonY(b, i, variant);
            x += b.width + gap; b.enterX = variant == 2 ? -20 : -30; b.enterY = 0;
        }
    }
    private float ribbonY(Box b, int i, int variant) {
        return variant == 1 ? hero.y + hero.height / 2 - b.height / 2
                : variant == 2 ? hero.y - hero.height / 2 + b.height / 2 : hero.y + ((i & 1) == 0 ? 10 : -10);
    }

    private void editorial(int variant, int secondary) {
        if (variant == 0) {
            for (Box b : boxes) b.flowVertical = true;
            hero.x = -width * 0.15f; hero.y = 0;
            float y = -hero.height / 2 + stack * 0.5f;
            for (int i = 0; i < heroIndex; i++) {
                Box b = boxes.get(i); b.x = hero.x + hero.width / 2 + gap + b.width / 2;
                b.y = y + b.height / 2; y += b.height + stack; b.enterX = -20; b.enterY = 0;
            }
            y = -hero.height / 2 + stack * 0.5f;
            for (int i = heroIndex + 1; i < boxes.size(); i++) {
                Box b = boxes.get(i); b.x = hero.x - hero.width / 2 - gap - b.width / 2;
                b.y = y + b.height / 2; y += b.height + stack; b.enterX = 20; b.enterY = 0;
            }
        } else if (variant == 1) {
            for (Box b : boxes) b.flowVertical = true;
            float right = width * 0.28f, limit = height * 0.46f;
            float step = maxWidth(boxes) + stack;
            float totalHeight = sumHeight(boxes) + stack * (boxes.size() - 1);
            boolean single = sumHeight(boxes) * 0.52f + stack * (boxes.size() - 1) <= limit * 2;
            int rail = 0; float y = single ? -totalHeight / 2 : -limit;
            for (Box b : boxes) {
                if (!single && y + b.height > limit) { rail++; y = -limit; }
                b.x = right - rail * step - b.width / 2; b.y = y + b.height / 2;
                y += b.height + stack; b.enterX = 20; b.enterY = 0;
            }
        } else if (variant == 2) {
            for (Box b : boxes) b.flowVertical = false;
            hero.x = 0; hero.y = -height * 0.25f;
            List<Box> before = boxes.subList(0, heroIndex), after = boxes.subList(heroIndex + 1, boxes.size());
            if (!before.isEmpty()) {
                float y = hero.y - hero.height / 2 - stack - maxHeight(before) / 2;
                row(before, y, 0);
                for (Box b : before) b.enterY = -20;
            }
            float left = -hero.width * 0.25f - gap, right = hero.width * 0.25f + gap;
            float y = hero.y + hero.height / 2 + stack;
            for (int pair = 0; pair < after.size(); pair += 2) {
                Box a = after.get(pair), b = pair + 1 < after.size() ? after.get(pair + 1) : null;
                a.x = left - a.width / 2; a.y = y + a.height / 2; a.enterX = -20; a.enterY = 0;
                if (b != null) { b.x = right + b.width / 2; b.y = y + b.height / 2; b.enterX = 20; b.enterY = 0; }
                y += Math.max(a.height, b == null ? 0 : b.height) + stack;
            }
        } else if (variant == 3) {
            for (Box b : boxes) b.flowVertical = false;
            hero.x = hero.y = 0;
            int cut = Math.min(heroIndex, secondary) + 1;
            List<Box> first = boxes.subList(0, cut), second = boxes.subList(cut, boxes.size());
            float h1 = maxHeight(first), h2 = maxHeight(second), total = h1 + stack + h2;
            float y1 = -total / 2 + h1 / 2, y2 = y1 + h1 / 2 + stack + h2 / 2;
            float w1 = row(first, y1, 30), w2 = row(second, y2, -30);
            float shift = Math.max(w1, w2) * 0.12f;
            for (Box b : first) b.x -= shift;
            for (Box b : second) b.x += shift;
        } else {
            for (int i = 0; i < boxes.size(); i++) boxes.get(i).flowVertical = i == heroIndex;
            boolean right = heroIndex == boxes.size() - 1;
            float leftEdge = -width * 0.4f, rightEdge = width * 0.4f;
            float y = flowWords(boxes.subList(0, heroIndex), -height * 0.34f,
                    leftEdge, rightEdge, Float.NEGATIVE_INFINITY, leftEdge, rightEdge, right);
            y += stack;
            float pillarLeft = right ? rightEdge - hero.width : leftEdge;
            hero.x = pillarLeft + hero.width / 2; hero.y = y + hero.height / 2;
            float bottom = y + hero.height + stack;
            float besideLeft = right ? leftEdge : pillarLeft + hero.width + gap;
            float besideRight = right ? pillarLeft - gap : rightEdge;
            flowWords(boxes.subList(heroIndex + 1, boxes.size()), y,
                    leftEdge, rightEdge, bottom, besideLeft, besideRight, right);
        }
    }

    private float flowWords(List<Box> words, float y, float left, float right, float pillarBottom,
                            float besideLeft, float besideRight, boolean heroRight) {
        float start = y < pillarBottom - 0.5f ? besideLeft : left;
        float end = y < pillarBottom - 0.5f ? besideRight : right;
        float x = start, rowHeight = 0;
        for (Box b : words) {
            if (x > start && x + b.width > end) {
                y += rowHeight + stack;
                start = y < pillarBottom - 0.5f ? besideLeft : left;
                end = y < pillarBottom - 0.5f ? besideRight : right;
                x = start; rowHeight = 0;
            }
            b.x = x + b.width / 2; b.y = y + b.height / 2;
            b.enterX = heroRight ? -25 : 25; b.enterY = 0;
            x += b.width + gap; rowHeight = Math.max(rowHeight, b.height);
        }
        return words.isEmpty() ? y : y + rowHeight;
    }

    private float row(List<Box> row, float y, float enterX) {
        float w = sumWidth(row) + gap * (row.size() - 1), x = -w / 2;
        for (Box b : row) {
            b.x = x + b.width / 2; b.y = y; x += b.width + gap;
            b.enterX = enterX; b.enterY = 0;
        }
        return w;
    }

    private void collage(int variant, float scale) {
        hero.x = hero.y = 0;
        double radius = Math.hypot(hero.width, hero.height) / 2 + stack;
        int count = Math.max(1, boxes.size() - 1), ordinal = 0;
        double angle = Math.PI / 4;
        List<Box> placed = new ArrayList<>(); placed.add(hero);
        for (int i = 0; i < boxes.size(); i++) {
            if (i == heroIndex) continue;
            Box b = boxes.get(i);
            double r = radius + (variant == 1 ? 35 + ordinal / (double) count * 150
                    : variant == 2 ? ordinal % 2 == 1 ? 140 : 50 : 45 + ordinal * 23 % 90) * scale;
            ordinal++;
            double candidate = angle;
            boolean clear = false;
            for (int ring = 0; ring < 14 && !clear; ring++) {
                for (int attempt = 0; attempt < 400; attempt++) {
                    b.x = (float) (Math.cos(candidate) * r); b.y = (float) (Math.sin(candidate) * r * 0.65);
                    clear = true;
                    for (Box other : placed) {
                        float separation = Math.max(Math.abs(b.x - other.x) - (b.width + other.width) / 2,
                                Math.abs(b.y - other.y) - (b.height + other.height) / 2);
                        if (separation < gap) { clear = false; break; }
                    }
                    if (clear) break;
                    candidate += 0.07;
                }
                if (!clear) r += (36 + ring * 12) * scale;
            }
            angle = candidate + 0.02; placed.add(b);
            b.flowVertical = Math.abs(Math.cos(candidate)) >= Math.abs(Math.sin(candidate));
            b.enterX = (float) Math.cos(candidate) * -60; b.enterY = (float) Math.sin(candidate) * -60;
        }
    }

    private void cross() {
        hero.x = hero.y = 0;
        int top = heroIndex / 2, right = (boxes.size() - 1 - heroIndex + 1) / 2;
        float topStretch = fillColumn(boxes.subList(0, top));
        float bottomStretch = fillColumn(boxes.subList(heroIndex + right + 1, boxes.size()));
        float x = -hero.width / 2 - stack;
        for (int i = heroIndex - 1; i >= top; i--) {
            Box b = boxes.get(i); b.flowVertical = false;
            b.x = x - b.width / 2; b.y = (i & 1) == 0 ? 10 : -10;
            x -= b.width + gap; b.enterX = -30; b.enterY = 0;
        }
        float y = -hero.height / 2 - stack;
        for (int i = top - 1; i >= 0; i--) {
            Box b = boxes.get(i); b.flowVertical = true;
            b.x = (i & 1) == 0 ? 15 : -15; b.y = y - b.height / 2;
            y -= b.height + stack + topStretch; b.enterX = 0; b.enterY = -30;
        }
        x = hero.width / 2 + stack;
        for (int i = heroIndex + 1; i <= heroIndex + right; i++) {
            Box b = boxes.get(i); b.flowVertical = false;
            b.x = x + b.width / 2; b.y = (i & 1) == 0 ? 10 : -10;
            x += b.width + gap; b.enterX = 30; b.enterY = 0;
        }
        y = hero.height / 2 + stack;
        for (int i = heroIndex + right + 1; i < boxes.size(); i++) {
            Box b = boxes.get(i); b.flowVertical = true;
            b.x = (i & 1) == 0 ? 15 : -15; b.y = y + b.height / 2;
            y += b.height + stack + bottomStretch; b.enterX = 0; b.enterY = 30;
        }
    }

    private float fillColumn(List<Box> column) {
        if (column.isEmpty()) return 0;
        float available = Math.max(0, height * 0.46f - hero.height / 2 - stack);
        if (available <= 0) return 0;
        float gaps = stack * (column.size() - 1), content = sumHeight(column), target = available * 0.72f;
        if (content + gaps < target) {
            float boost = Math.min(2.2f, (target - gaps) / Math.max(1, content));
            for (Box b : column) {
                float factor = Math.min(boost, hero.fontSize * 0.6f / b.fontSize);
                if (factor > 1.05f) { b.fontSize *= factor; b.width *= factor; b.height *= factor; }
            }
        }
        if (column.size() < 2) return 0;
        float pitch = (available * 0.95f - sumHeight(column)) / (column.size() - 1);
        return Math.max(0, Math.min(stack * 2, pitch - stack));
    }

    private static float sumWidth(List<Box> boxes) { float v = 0; for (Box b : boxes) v += b.width; return v; }
    private static float sumHeight(List<Box> boxes) { float v = 0; for (Box b : boxes) v += b.height; return v; }
    private static float maxWidth(List<Box> boxes) { float v = 0; for (Box b : boxes) v = Math.max(v, b.width); return v; }
    private static float maxHeight(List<Box> boxes) { float v = 0; for (Box b : boxes) v = Math.max(v, b.height); return v; }
}