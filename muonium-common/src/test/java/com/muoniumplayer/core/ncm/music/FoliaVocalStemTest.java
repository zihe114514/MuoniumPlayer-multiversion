package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 素材这条路的两个<b>判据</b>：能不能信这份素材、它盖不盖得住这条接缝。
 *
 * <p>这两条都是纯算术，但它们是「要不要减人声」的开关：判错一次的方向不是听感差一点，而是
 * 交接期两个人一起唱（盖不住却接了），或者把伴奏啃掉一块（素材错位）。所以按数值钉住。</p>
 */
class FoliaVocalStemTest {

    private static final int RATE = 44_100;

    private static FoliaStemProfile profile(float projection, long frames) {
        FoliaStemProfile stems = new FoliaStemProfile();
        stems.windowLengthMillis = 30_000L;
        stems.confidence = .5f;
        stems.vocalProjection = projection;
        stems.materialFrames = frames;
        return stems;
    }

    /** 30 秒素材，从第 0 帧起。 */
    private static FoliaVocalStem stemSeconds(double seconds) {
        return new FoliaVocalStem(new float[(int) (seconds * RATE)], 0L, 1.0f);
    }

    @Test
    @DisplayName("投影系数落在可信区间才算有素材")
    void projectionBounds() {
        assertTrue(profile(1.0f, 44_100L).hasVocalMaterial());
        assertTrue(profile(FoliaStemProfile.MIN_PROJECTION, 1L).hasVocalMaterial());
        assertTrue(profile(FoliaStemProfile.MAX_PROJECTION, 1L).hasVocalMaterial());
        assertFalse(profile(.69f, 44_100L).hasVocalMaterial(), "减不干净：素材比原曲里的人声轻太多");
        assertFalse(profile(1.61f, 44_100L).hasVocalMaterial(), "会啃伴奏：素材比原曲里的人声响太多");
        assertFalse(profile(0f, 44_100L).hasVocalMaterial());
    }

    @Test
    @DisplayName("素材上线之前写的 profile 没有这两个字段，必须安静地不使用")
    void olderProfileWithoutTheFieldsIsIgnored() {
        // Gson 读一份旧 JSON 时不会填新字段：float 留 0、long 留 0。这不能被当成「测出来是 0」。
        assertFalse(profile(0f, 0L).hasVocalMaterial());
        // 而它的时间戳证据仍然有效 —— isUsable() 不能因为没素材就翻脸，否则每次升级都要重算全部歌。
        assertTrue(profile(0f, 0L).isUsable());
    }

    @Test
    @DisplayName("时间戳不可用时素材也不算可用")
    void unusableProfileCarriesNoMaterial() {
        FoliaStemProfile stems = profile(1.0f, 44_100L);
        stems.confidence = .05f;
        assertFalse(stems.isUsable());
        assertFalse(stems.hasVocalMaterial());
    }

    @Test
    @DisplayName("接缝前半段必须在素材窗口里，后半段越出去无所谓")
    void coverageNeedsTheFirstHalfOfTheSeam() {
        FoliaVocalStem thirty = stemSeconds(30.0);
        assertTrue(thirty.covers(0L, 8_000L, RATE), "从头交叠：正是最常见的情况");
        assertTrue(thirty.covers(26_000L, 8_000L, RATE), "后半段越出窗口只是人声早点回来");
        assertFalse(thirty.covers(29_000L, 8_000L, RATE), "前半段越出去 = 两个人一起唱");
        assertFalse(thirty.covers(0L, 8_000L, 0), "采样率问不到就别接");
    }

    @Test
    @DisplayName("窗口起点不在 0 时，覆盖区间跟着平移")
    void coverageShiftsWithTheWindowOrigin() {
        FoliaVocalStem tail = new FoliaVocalStem(new float[30 * RATE], 100L * RATE, 1.0f);
        assertEquals(100_000L, tail.firstMillis(RATE));
        assertEquals(130_000L, tail.endMillis(RATE));
        assertFalse(tail.covers(0L, 8_000L, RATE), "歌曲开头的接缝不在这一段里");
        assertTrue(tail.covers(100_000L, 8_000L, RATE));
    }
}
