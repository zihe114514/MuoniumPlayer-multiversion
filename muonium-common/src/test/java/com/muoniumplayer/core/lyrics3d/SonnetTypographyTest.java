package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SonnetTypographyTest {
    private static final SonnetTypography.Measure MEASURE = (text, size, role) ->
            (float) text.codePoints().mapToDouble(c -> Character.isWhitespace(c) ? 0.3 : c < 128 ? 0.56 : 1.0).sum() * size;

    @Test void allTemplatesUseDeterministicMeasuredBoxesWithTheSameReadingSequence() {
        List<List<SonnetTypography.Segment>> lines = List.of(segments("在", "很远的", "天空", "落下", "柔软的", "光", "照亮", "归途", "之后"));
        for (SonnetTypography.Kind kind : SonnetTypography.Kind.values()) {
            List<SonnetTypography.Box> first = SonnetTypography.layout(lines, kind, 1280, 720, 40, MEASURE);
            List<SonnetTypography.Box> second = SonnetTypography.layout(lines, kind, 1280, 720, 40, MEASURE);
            assertEquals(first.size(), second.size());
            List<SonnetTypography.Box> core = first.stream().filter(b -> b.role != SonnetTypography.Role.DECORATION).collect(Collectors.toList());
            assertEquals(lines.get(0).size(), core.size());
            for (int i = 0; i < first.size(); i++) {
                SonnetTypography.Box a = first.get(i), b = second.get(i);
                assertEquals(a.x, b.x); assertEquals(a.y, b.y);
                assertEquals(a.fontSize, b.fontSize); assertEquals(a.rotationRad, b.rotationRad);
                assertTrue(a.fontSize > 0 && Float.isFinite(a.x) && Float.isFinite(a.y));
            }
            for (int i = 0; i < core.size(); i++) {
                assertEquals(i, core.get(i).segmentIndex, kind.toString());
                for (int j = i + 1; j < core.size(); j++) {
                    SonnetTypography.Box a = core.get(i), b = core.get(j);
                    float ox = (a.width + b.width) / 2 - Math.abs(a.x - b.x);
                    float oy = (a.height + b.height) / 2 - Math.abs(a.y - b.y);
                    assertFalse(ox > 0.5f && oy > 0.5f, kind + " overlaps " + i + "/" + j);
                }
            }
        }
    }

    @Test void eachAuthoredLineKeepsItsHeroAndGiantCopiesAreTheirOwnTextRole() {
        List<List<SonnetTypography.Segment>> lines = List.of(segments("前", "核心长词", "后"), segments("仍在", "遥远的地方", "等候"));
        List<SonnetTypography.Box> impact = SonnetTypography.layout(lines, SonnetTypography.Kind.TYPE_IMPACT, 1280, 720, 40, MEASURE);
        assertEquals(2, impact.stream().filter(b -> b.role == SonnetTypography.Role.HERO).count());
        assertEquals(3, impact.stream().filter(b -> b.role == SonnetTypography.Role.DECORATION).count());
        SonnetTypography.Box hero = impact.stream().filter(b -> b.role == SonnetTypography.Role.HERO).findFirst().orElseThrow();
        SonnetTypography.Box support = impact.stream().filter(b -> b.role == SonnetTypography.Role.SUPPORT).findFirst().orElseThrow();
        assertTrue(hero.fontSize > support.fontSize * 2);
        assertTrue(impact.stream().filter(b -> b.role == SonnetTypography.Role.DECORATION)
                .allMatch(b -> b.fontSize <= 4.8f * 40 + 1e-3f));
        assertTrue(SonnetTypography.layout(lines, SonnetTypography.Kind.POSTER_BLOCKS, 1280, 720, 40, MEASURE)
                .stream().noneMatch(b -> b.role == SonnetTypography.Role.DECORATION));
    }

    @Test void typeImpactHeroIsSlightlySmallerWithoutChangingSupportScale() {
        List<List<SonnetTypography.Segment>> lines = List.of(segments("a", "overwhelming", "a"));
        List<SonnetTypography.Box> impact = SonnetTypography.layout(lines,
                SonnetTypography.Kind.TYPE_IMPACT, 1280, 720, 100, MEASURE);
        SonnetTypography.Box hero = impact.stream()
                .filter(box -> box.role == SonnetTypography.Role.HERO).findFirst().orElseThrow();
        SonnetTypography.Box support = impact.stream()
                .filter(box -> box.role == SonnetTypography.Role.SUPPORT).findFirst().orElseThrow();
        assertTrue(hero.fontSize < 4.8f * 100);
        assertTrue(hero.width <= 1280 * 0.72f + 1e-3f);
        assertEquals(1.5f * 100, support.fontSize, 1e-3f);
    }

    @Test void verticalLatinIsQuarterTurnedAsABlockAndCjkKeepsItsGlyphColumns() {
        List<SonnetTypography.Box> latin = SonnetTypography.layout(List.of(segments("a", "LUMINOUS", "night")),
                SonnetTypography.Kind.MASK_REVEAL, 1280, 720, 40, MEASURE);
        SonnetTypography.Box main = latin.stream().filter(b -> b.role == SonnetTypography.Role.HERO).findFirst().orElseThrow();
        assertFalse(main.vertical);
        assertEquals(Math.PI / 2, main.rotationRad, 1e-6);
        assertTrue(main.height > main.width);
        List<SonnetTypography.Box> cjk = SonnetTypography.layout(List.of(segments("在", "しずかな夜", "中")),
                SonnetTypography.Kind.MASK_REVEAL, 1280, 720, 40, MEASURE);
        assertTrue(cjk.stream().filter(b -> b.role == SonnetTypography.Role.HERO).findFirst().orElseThrow().vertical);
    }

    @Test void roleSelectionScoresDurationAndSeparatesSecondaryAccents() {
        List<SonnetTypography.Segment> line = segments("词语", "微弱", "回声", "等候", "你的名字", "仍在", "远处", "闪烁", "不息");
        List<SonnetTypography.Box> boxes = SonnetTypography.layout(List.of(line), SonnetTypography.Kind.TRACKING_RIBBON, 1280, 720, 40, MEASURE);
        int hero = boxes.stream().filter(b -> b.role == SonnetTypography.Role.HERO).findFirst().orElseThrow().segmentIndex;
        assertEquals(4, hero);
        List<SonnetTypography.Box> semi = boxes.stream().filter(b -> b.role == SonnetTypography.Role.SEMI_HERO).collect(Collectors.toList());
        assertFalse(semi.isEmpty());
        for (SonnetTypography.Box b : semi) assertTrue(Math.abs(b.segmentIndex - hero) >= 2);
    }

    @Test void narrowGraphemeAdvanceIsSharedByPackingAndTheRenderedRun() {
        SonnetTypography.Measure narrow = (text, size, role) -> text.length() * size * 0.05f;
        List<SonnetTypography.Box> boxes = SonnetTypography.layout(List.of(segments("iii", "llll", "ii")),
                SonnetTypography.Kind.TRACKING_RIBBON, 1280, 720, 32, narrow);
        for (SonnetTypography.Box box : boxes) {
            assertFalse(box.vertical);
            float actual = SonnetTypography.horizontalAdvance(box.segment, box.fontSize, box.role, narrow);
            assertEquals(actual, box.width, 1e-3, "Packing must use the same minimum advance as rendering");
        }
    }

    @Test void quietTableauBeforeAndAfterRunsUseOppositeEntranceVectors() {
        for (int variant = 0; variant < 4; variant++) {
            List<SonnetTypography.Segment> text = segments("before", "hero", "after");
            List<SonnetTypography.Box> boxes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                SonnetTypography.Box box = new SonnetTypography.Box(text.get(i), i,
                        i == 1 ? SonnetTypography.Role.HERO : SonnetTypography.Role.SUPPORT);
                box.width = 50; box.height = 40; box.fontSize = 32;
                boxes.add(box);
            }
            SonnetFlowLayout.layout(boxes, 1, SonnetTypography.Kind.QUIET_TABLEAU,
                    1280, 720, 32, 0, -1, variant);
            if (variant == 1) {
                assertEquals(20, boxes.get(0).enterX); assertEquals(-20, boxes.get(2).enterX);
            } else if (variant != 3) {
                assertEquals(20, boxes.get(0).enterY); assertEquals(-20, boxes.get(2).enterY);
            } else {
                assertEquals(boxes.get(0).x > 0 ? 30 : -30, boxes.get(0).enterX);
                assertEquals(boxes.get(2).x > 0 ? 30 : -30, boxes.get(2).enterX);
            }
        }
    }

    static List<SonnetTypography.Segment> segments(String... words) {
        List<SonnetTypography.Segment> result = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < words.length; i++) {
            List<SonnetTypography.Glyph> glyphs = new ArrayList<>();
            int[] points = words[i].codePoints().toArray();
            for (int j = 0; j < points.length; j++) glyphs.add(new SonnetTypography.Glyph(
                    new String(Character.toChars(points[j])), i * 400 + j * 80, i * 400 + (j + 1) * 80));
            result.add(new SonnetTypography.Segment(words[i], offset, offset + words[i].length(),
                    i * 400, i * 400 + 350, true, glyphs));
            offset += words[i].length() + 1;
        }
        return result;
    }
}
