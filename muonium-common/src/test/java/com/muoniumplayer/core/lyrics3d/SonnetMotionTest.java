package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonnetMotionTest {

    @Test
    void heroScoreUsesBothVisibleLengthAndWordDuration() {
        List<CadenzaMotion.Token> tokens = tokens("短", "长词核心", "尾声");
        assertEquals(1, SonnetMotion.findHero(tokens));
        assertTrue(SonnetMotion.heroScore(tokens.get(1)) > SonnetMotion.heroScore(tokens.get(0)));
    }

    @Test
    void allLayoutsAreDeterministicAndFitTheirBillboardBounds() {
        List<CadenzaMotion.Token> tokens = tokens(
                "在", "很远的", "天空", "落下", "柔软的", "光", "照亮", "归途", "之后");
        for (int kind = SonnetMotion.RIBBON; kind <= SonnetMotion.EDITORIAL; kind++) {
            SonnetMotion.Layout first = SonnetMotion.layout(tokens, c -> 10f,
                    180f, 100f, 11f, kind);
            SonnetMotion.Layout second = SonnetMotion.layout(tokens, c -> 10f,
                    180f, 100f, 11f, kind);
            assertEquals(kind, first.kind);
            assertEquals(first.heroIndex, second.heroIndex);
            assertEquals(first.slots.size(), second.slots.size());
            assertTrue(first.width <= 180.01f);
            assertTrue(first.height <= 100.01f);
            for (int i = 0; i < first.slots.size(); i++) {
                SonnetMotion.Slot a = first.slots.get(i);
                SonnetMotion.Slot b = second.slots.get(i);
                assertEquals(a.x, b.x, 1e-6f);
                assertEquals(a.y, b.y, 1e-6f);
                assertEquals(a.fontScale, b.fontScale, 1e-6f);
                assertEquals(a.guideDecoration, b.guideDecoration);
                assertEquals(a.frameDecoration, b.frameDecoration);
                assertEquals(i, a.index);
                assertTrue(a.fontScale > 0f);
                for (int j = i + 1; j < first.slots.size(); j++) {
                    SonnetMotion.Slot other = first.slots.get(j);
                    float overlapX = Math.min(a.x + a.width * 0.5f, other.x + other.width * 0.5f)
                            - Math.max(a.x - a.width * 0.5f, other.x - other.width * 0.5f);
                    float overlapY = Math.min(a.y + a.height * 0.5f, other.y + other.height * 0.5f)
                            - Math.max(a.y - a.height * 0.5f, other.y - other.height * 0.5f);
                    assertFalse(overlapX > 0.1f && overlapY > 0.1f,
                            "layout " + kind + " overlaps token boxes " + i + " and " + j);
                }
            }
        }
    }

    @Test
    void heroAndSemiHeroStayInReadingOrder() {
        List<CadenzaMotion.Token> tokens = tokens(
                "前方", "微弱", "回声", "等候", "你的名字", "仍在", "远处", "闪烁", "不息");
        SonnetMotion.Layout layout = SonnetMotion.layout(tokens, c -> 10f,
                200f, 120f, 11f, 1);
        assertEquals(4, layout.heroIndex);
        assertTrue(layout.slots.get(layout.heroIndex).hero);
        assertFalse(layout.slots.get(0).hero);
        for (int i = 0; i < layout.slots.size(); i++) {
            assertEquals(i, layout.slots.get(i).index);
            assertEquals(tokens.get(i).charOffset, layout.slots.get(i).token.charOffset);
        }
        assertNotEquals(layout.slots.get(0).fontScale,
                layout.slots.get(layout.heroIndex).fontScale);
    }

    @Test
    void wordEntryEchoPulseAndExitAreTimeAddressable() {
        List<CadenzaMotion.Token> tokens = tokens("前", "核心长词", "尾");
        SonnetMotion.Layout layout = SonnetMotion.layout(tokens, c -> 10f,
                180f, 90f, 11f, 0);
        SonnetMotion.Slot hero = layout.slots.get(layout.heroIndex);
        SonnetMotion.Pose pose = new SonnetMotion.Pose();

        SonnetMotion.pose(hero, hero.token.charCount / 2, 0.0, 500.0, 1f, false, pose);
        assertEquals(0f, pose.alpha, 1e-6f, "字词入场前不可见");

        SonnetMotion.pose(hero, hero.token.charCount / 2, 500.0, 500.0, 1f, false, pose);
        assertTrue(pose.alpha > 0f);
        assertTrue(pose.echoAlpha > 0f);
        assertTrue(pose.trailOwner);
        assertTrue(pose.spark > 0f);

        SonnetMotion.pose(hero, hero.token.charCount / 2, 3000.0, 500.0, 1f, false, pose);
        assertEquals(1f, pose.alpha, 1e-5f);
        assertEquals(0f, pose.echoAlpha, 1e-5f);
        assertFalse(pose.active);

        SonnetMotion.pose(hero, hero.token.charCount / 2, 3000.0, 500.0, 0.2f, true, pose);
        assertEquals(0.2f, pose.alpha, 1e-5f);
        assertTrue(pose.scale < 1f || pose.dx != 0f || pose.dy != 0f,
                "退场应追加姿态，而不是只乘整行 alpha");
    }

    @Test
    void textDecorationsAreDeterministicAndFollowWordEntry() {
        List<CadenzaMotion.Token> tokens = tokens("前", "核心长词", "尾");
        SonnetMotion.Layout layout = SonnetMotion.layout(tokens, c -> 10f,
                180f, 90f, 11f, 0);
        SonnetMotion.Slot hero = layout.slots.get(layout.heroIndex);
        SonnetMotion.Pose pose = new SonnetMotion.Pose();

        assertTrue(hero.guideDecoration);
        assertTrue(hero.frameDecoration);
        SonnetMotion.pose(hero, hero.token.charCount / 2, 0.0, 500.0, 1f, false, pose);
        assertFalse(pose.decorationOwner);

        SonnetMotion.pose(hero, hero.token.charCount / 2, 500.0, 500.0, 1f, false, pose);
        assertTrue(pose.decorationOwner);
        assertTrue(pose.guideAlpha > 0f);
        assertTrue(pose.guideProgress > 0f && pose.guideProgress < 1f);
        assertTrue(pose.frameOwner);
        assertTrue(pose.frameAlpha > 0f);
        assertTrue(pose.frameProgress > 0f);

        SonnetMotion.pose(hero, hero.token.charCount / 2, 3000.0, 500.0, 1f, false, pose);
        assertEquals(0f, pose.guideAlpha, 1e-6f);
        assertEquals(1f, pose.frameProgress, 1e-6f);
    }

    @Test
    void editorialLayoutStacksJapaneseKanaVertically() {
        SonnetMotion.Layout layout = SonnetMotion.layout(tokens("前", "しずかな", "夜"),
                c -> 10f, 180f, 100f, 11f, SonnetMotion.EDITORIAL);
        assertTrue(layout.slots.get(layout.heroIndex).vertical);
    }

    private static List<CadenzaMotion.Token> tokens(String... words) {
        String line = String.join(" ", words);
        List<Long> times = new ArrayList<>();
        for (int wordIndex = 0; wordIndex < words.length; wordIndex++) {
            for (int i = 0; i < words[wordIndex].length(); i++) {
                times.add(wordIndex * 500L);
            }
        }
        long[] charTimes = new long[times.size()];
        for (int i = 0; i < times.size(); i++) charTimes[i] = times.get(i);
        return CadenzaMotion.splitTokens(line, charTimes, line);
    }
}
