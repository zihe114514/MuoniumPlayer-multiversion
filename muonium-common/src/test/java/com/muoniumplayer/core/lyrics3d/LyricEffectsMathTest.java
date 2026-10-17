package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3D 歌词特效纯函数的离线探针。（Wiggle 段随功能移除。）
 */
class LyricEffectsMathTest {

    // ==================== Word Pop ====================

    @Test
    void wordPopIsZeroBeforeWordStarts() {
        assertEquals(0.0, LyricEffectsMath.wordPop(-1.0), 1e-9);
        assertEquals(0.0, LyricEffectsMath.wordPop(0.0), 1e-9);
    }

    @Test
    void wordPopIsZeroAfterDecay() {
        assertEquals(0.0, LyricEffectsMath.wordPop(1300.0), 1e-9);
    }

    @Test
    void wordPopIsSinglePositiveHump() {
        // 扫 0..1200ms：先升、过峰、回落趋零。欠阻尼弹簧允许微小的尾巴振幅
        //（与 qplayerSyllableLift 同族的手感），但 500ms 后不得再过峰值的 10%。
        double peak = 0.0;
        long peakAt = -1;
        double maxLate = 0.0;
        for (long t = 10; t <= 1200; t += 10) {
            double v = LyricEffectsMath.wordPop(t);
            if (t <= 500) {
                if (v > peak) {
                    peak = v;
                    peakAt = t;
                }
            } else {
                maxLate = Math.max(maxLate, v);
            }
        }
        assertTrue(peak > 0.5, "峰值应接近 1，实际 " + peak);
        assertTrue(peakAt > 0 && peakAt <= 300, "峰值应在起弹 300ms 内到来，实际 " + peakAt + "ms");
        assertTrue(maxLate < peak * 0.10,
                "后半段振幅应熄灭（<10% 峰值），实际 " + maxLate);
        assertEquals(0.0, LyricEffectsMath.wordPop(1200.0), 1e-9, "1.2s 后应归零");
    }

    // ==================== wordProgress ====================

    @Test
    void wordProgressClampsToUnitInterval() {
        assertEquals(0.0, LyricEffectsMath.wordProgress(1000, 500, 500));
        assertEquals(0.5, LyricEffectsMath.wordProgress(1000, 500, 1250));
        assertEquals(1.0, LyricEffectsMath.wordProgress(1000, 500, 9999));
    }

    @Test
    void wordProgressHandlesZeroDuration() {
        // 零时长词给 320ms 默认窗口（与全屏页 tokensOf 一致）
        assertEquals(0.0, LyricEffectsMath.wordProgress(1000, 0, 1000));
        assertEquals(1.0, LyricEffectsMath.wordProgress(1000, 0, 1400));
    }

    // ==================== Text Flash ====================

    @Test
    void textFlashDecaysExponentially() {
        double atBeat = LyricEffectsMath.textFlash(0.0, 1.0);
        assertEquals(1.0, atBeat, 1e-9);
        double later = LyricEffectsMath.textFlash(0.22, 1.0);
        assertTrue(later > 0.3 && later < 0.45, "一个 tau 后应衰减到 ~0.37，实际 " + later);
        assertEquals(0.0, LyricEffectsMath.textFlash(5.0, 0.0), 1e-9);
    }

    @Test
    void textFlashZeroAmountIsZero() {
        assertEquals(0.0, LyricEffectsMath.textFlash(0.0, 0.0), 1e-9);
    }

    @Test
    void textFlashNegativeTimeIsZero() {
        assertEquals(0.0, LyricEffectsMath.textFlash(-0.5, 1.0), 1e-9);
    }
}
