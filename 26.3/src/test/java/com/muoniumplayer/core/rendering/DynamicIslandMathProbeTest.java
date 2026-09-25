package com.muoniumplayer.core.rendering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灵动岛纯数学部分的离线探针（不碰 GL，不需要客户端）。
 *
 * <p>{@code DynamicIslandMath} 与 {@code IslandVolumeIcons} 是 package-private / 常量类，
 * 测试放同包下直接调。音量分档的阈值来自 DownloadDynamicIsland#volumeGlyph 的
 * {@code >=80 / >=60 / >=35 / else}，这里锁住"百分比解析 -> 分档边界"这条链，
 * 避免移植期把通知文案格式或阈值改掉导致图标与屏幕百分比错档。
 */
class DynamicIslandMathProbeTest {

    /** 复刻 DownloadDynamicIsland#volumeGlyph 的分档，供边界断言使用。 */
    private static String volumeGlyphOf(int percent) {
        if (percent >= 80) return IslandVolumeIcons.VOLUME_HIGH;
        if (percent >= 60) return IslandVolumeIcons.VOLUME_MID;
        if (percent >= 35) return IslandVolumeIcons.VOLUME_LOW;
        return IslandVolumeIcons.VOLUME_MUTE;
    }

    @Test
    void noticePercentIsParsedOutOfTheDisplayedText() {
        assertEquals(75, DynamicIslandMath.parseNoticePercent("75%"));
        assertEquals(0, DynamicIslandMath.parseNoticePercent("0%"));
        assertEquals(100, DynamicIslandMath.parseNoticePercent("100%"));
        assertEquals(42, DynamicIslandMath.parseNoticePercent("音量 42%"));
    }

    @Test
    void volumeTierBoundariesAreExact() {
        assertEquals(IslandVolumeIcons.VOLUME_HIGH, volumeGlyphOf(100));
        assertEquals(IslandVolumeIcons.VOLUME_HIGH, volumeGlyphOf(80));
        assertEquals(IslandVolumeIcons.VOLUME_MID, volumeGlyphOf(79));
        assertEquals(IslandVolumeIcons.VOLUME_MID, volumeGlyphOf(60));
        assertEquals(IslandVolumeIcons.VOLUME_LOW, volumeGlyphOf(59));
        assertEquals(IslandVolumeIcons.VOLUME_LOW, volumeGlyphOf(35));
        assertEquals(IslandVolumeIcons.VOLUME_MUTE, volumeGlyphOf(34));
        assertEquals(IslandVolumeIcons.VOLUME_MUTE, volumeGlyphOf(0));
    }

    @Test
    void volumeIconCodepointsAreDistinctAndInThePrivateUseArea() {
        String[] glyphs = {
                IslandVolumeIcons.GAUGE,
                IslandVolumeIcons.VOLUME_HIGH,
                IslandVolumeIcons.VOLUME_MID,
                IslandVolumeIcons.VOLUME_LOW,
                IslandVolumeIcons.VOLUME_MUTE
        };
        for (int i = 0; i < glyphs.length; i++) {
            assertEquals(1, glyphs[i].length(), "fontello 图标必须是单码位");
            int cp = glyphs[i].codePointAt(0);
            assertTrue(cp >= 0xE000 && cp <= 0xF8FF, "码位应落在私有区: " + Integer.toHexString(cp));
            for (int j = i + 1; j < glyphs.length; j++) {
                assertTrue(!glyphs[i].equals(glyphs[j]), "图标码位重复: " + Integer.toHexString(cp));
            }
        }
    }

    @Test
    void clampAndInterpolationHelpersStayInRange() {
        assertEquals(0.0, DynamicIslandMath.clamp01(-5.0));
        assertEquals(1.0, DynamicIslandMath.clamp01(5.0));
        assertEquals(0.25, DynamicIslandMath.clamp01(0.25));
        assertEquals(0.0f, DynamicIslandMath.clamp01f(-1.0f));
        assertEquals(1.0f, DynamicIslandMath.clamp01f(2.0f));

        assertEquals(0.0, DynamicIslandMath.smoothStep(0.0));
        assertEquals(1.0, DynamicIslandMath.smoothStep(1.0));
        assertEquals(0.5, DynamicIslandMath.smoothStep(0.5), 1e-9);

        assertEquals(10.0, DynamicIslandMath.lerp(0.0, 20.0, 0.5));
        assertEquals(0.0, DynamicIslandMath.lerp(0.0, 20.0, 0.0));
        assertEquals(20.0, DynamicIslandMath.lerp(0.0, 20.0, 1.0));

        assertEquals(0, DynamicIslandMath.clamp255(-10f));
        assertEquals(255, DynamicIslandMath.clamp255(999f));
        assertEquals(5.0, DynamicIslandMath.clamp(5.0, 0.0, 10.0));
        assertEquals(0.0, DynamicIslandMath.clamp(-1.0, 0.0, 10.0));
        assertEquals(10.0, DynamicIslandMath.clamp(11.0, 0.0, 10.0));
    }
}
