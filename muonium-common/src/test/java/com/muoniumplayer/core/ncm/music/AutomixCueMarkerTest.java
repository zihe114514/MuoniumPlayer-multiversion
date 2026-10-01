package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutomixCueMarker} 的算术与平滑。
 *
 * <p>这一类看着小到不值得测，但它正是「进度条上那根线出现在没该在的地方」的唯一来源。
 * 三处绘制点都不自己做除法，只取 {@link AutomixCueMarker#cueFraction()} 与
 * {@link AutomixCueMarker#bandEndFraction()}，所以钳位规则必须钉在这里：</p>
 *
 * <ul>
 *   <li><b>越界一律钳进 {@code [0,1]}，不画到条外。</b>计划里的 {@code fireMillis} 是规划那一刻
 *       按当时的时长算的，而时长会在解码完成后落地、也会在换源后变化，切点落在曲尾之外是
 *       真实存在的一种状态；</li>
 *   <li><b>带子不许倒着长。</b>进场入点极近、或交叠被 {@code LATE_FIRE_FADE_MILLIS} 那类
 *       缩短过的计划，都可能算出终点早于起点；</li>
 *   <li><b>时长还没落地时读数为 0，不是 NaN。</b>{@code 0 / 0} 会变成 NaN，而 NaN 传进绘制
 *       层是一个既不在起点也不在终点的坐标。</li>
 * </ul>
 *
 * <p>平滑那几条是真跑时间测的，不是读源码：记号在交接前会被换掉最多三次（包络到货的回头重算、
 * 拖过切点的顶住重算），瞬移读起来像 bug，而跨越两首歌的补间读起来像接缝自己在往后退。</p>
 */
class AutomixCueMarkerTest {

    private static final long FOUR_MINUTES = 240_000L;

    @Test
    @DisplayName("切点与带子终点都钳在条内，越界不外溢")
    void fractionsClampIntoTheBar() {
        AutomixCueMarker cue = new AutomixCueMarker(60_000L, 75_000L, FOUR_MINUTES, false);
        assertEquals(0.25, cue.cueFraction(), 1e-9);
        assertEquals(0.3125, cue.bandEndFraction(), 1e-9);
        assertEquals(0.0625, cue.bandWidthFraction(), 1e-9);

        // 计划按旧时长算出、时长后来变短了：切点落在条外，只能贴右端。
        AutomixCueMarker past = new AutomixCueMarker(300_000L, 360_000L, FOUR_MINUTES, false);
        assertEquals(1.25, 300_000L / (double) FOUR_MINUTES, 1e-9);
        assertEquals(1.0, past.cueFraction(), 1e-9);
        assertEquals(1.0, past.bandEndFraction(), 1e-9);
        assertEquals(0.0, past.bandWidthFraction(), 1e-9);

        // 负数（理论上只可能来自算错的计划）走同一支钳位。
        AutomixCueMarker negative = new AutomixCueMarker(-1_000L, -500L, FOUR_MINUTES, false);
        assertEquals(0.0, negative.cueFraction(), 1e-9);
        assertEquals(0.0, negative.bandEndFraction(), 1e-9);
    }

    @Test
    @DisplayName("带子永远不从右往左长")
    void bandNeverRunsBackwards() {
        // 交叠窗被缩短、或入点贴到曲尾时，终点会算到起点前面去。
        AutomixCueMarker inverted = new AutomixCueMarker(100_000L, 40_000L, FOUR_MINUTES, true);
        assertEquals(100_000L, inverted.bandEndMillis);
        assertEquals(0.0, inverted.bandWidthFraction(), 1e-9);
        assertTrue(inverted.cueFraction() <= inverted.bandEndFraction(),
                "终点跑到起点前面，绘制层会画出一条往左长的带子");
    }

    @Test
    @DisplayName("时长还没落地时读数取 0，不产生 NaN")
    void unknownDurationYieldsZeroFractions() {
        AutomixCueMarker cue = new AutomixCueMarker(30_000L, 40_000L, 0L, false);
        assertEquals(0.0, cue.cueFraction(), 1e-9);
        assertEquals(0.0, cue.bandEndFraction(), 1e-9);
        assertFalse(Double.isNaN(cue.cueFraction()),
                "NaN 传进绘制层就是一个既不在起点也不在终点的坐标");
    }

    @Test
    @DisplayName("还剩多久到接缝：越过起点之后一律 0")
    void remainingMillisNeverGoesNegative() {
        AutomixCueMarker cue = new AutomixCueMarker(60_000L, 75_000L, FOUR_MINUTES, false);
        assertEquals(20_000L, cue.remainingMillis(40_000L));
        assertEquals(0L, cue.remainingMillis(60_000L));
        assertEquals(0L, cue.remainingMillis(200_000L));
        // 播放头还没落地（0）与读到一个负数都不该把这个数变成「整条都剩着」以外的东西。
        assertEquals(60_000L, cue.remainingMillis(0L));
        assertEquals(60_000L, cue.remainingMillis(-5_000L));
    }

    @Test
    @DisplayName("平滑器第一帧直接吸附，不做跨越整条的补间")
    void glideSnapsOnTheFirstReading() {
        AutomixCueMarker.Glide glide = new AutomixCueMarker.Glide();
        AutomixCueMarker cue = new AutomixCueMarker(180_000L, 200_000L, FOUR_MINUTES, false);

        assertFalse(glide.update(null).valid(), "还没吸过任何读数时这一帧不该画");

        AutomixCueMarker.Positions first = glide.update(cue);
        assertTrue(first.valid());
        assertEquals(0.75, first.cueFraction, 1e-9);
        // 交叠带 = 20 秒 / 240 秒。
        assertEquals(1.0 / 12.0, first.bandWidth(), 1e-3);
    }

    @Test
    @DisplayName("计划被换掉时记号收敛过去，而不是瞬移")
    void glideConvergesWhenThePlanMoves() {
        AutomixCueMarker.Glide glide = new AutomixCueMarker.Glide();
        glide.update(new AutomixCueMarker(192_000L, 200_000L, FOUR_MINUTES, false));

        // 回头重算把接缝提前了一大截。
        AutomixCueMarker moved = new AutomixCueMarker(48_000L, 60_000L, FOUR_MINUTES, false);
        sleep(30L);
        AutomixCueMarker.Positions mid = glide.update(moved);
        assertTrue(mid.cueFraction < 0.8 - 1e-6 && mid.cueFraction > 0.2 + 1e-6,
                "既没有瞬移到新位置，也没有留在旧位置：" + mid.cueFraction);

        AutomixCueMarker.Positions settled = settle(glide, moved);
        assertEquals(0.2, settled.cueFraction, 1e-3);
        assertEquals(0.25, settled.bandEndFraction, 1e-3);
    }

    @Test
    @DisplayName("本帧没有标记时不动，等调用方把它丢掉")
    void glideHoldsWhileThereIsNoMarker() {
        AutomixCueMarker.Glide glide = new AutomixCueMarker.Glide();
        AutomixCueMarker cue = new AutomixCueMarker(120_000L, 130_000L, FOUR_MINUTES, false);
        AutomixCueMarker.Positions before = glide.update(cue);
        AutomixCueMarker.Positions held = glide.update(null);
        assertEquals(before.cueFraction, held.cueFraction, 1e-9);
        assertEquals(before.bandEndFraction, held.bandEndFraction, 1e-9);
    }

    @Test
    @DisplayName("清过账之后重新吸附：换歌不做跨越两首的补间")
    void resetMakesTheNextReadingSnap() {
        AutomixCueMarker.Glide glide = new AutomixCueMarker.Glide();
        glide.update(new AutomixCueMarker(220_000L, 230_000L, FOUR_MINUTES, false));
        glide.reset();
        AutomixCueMarker.Positions snapped = glide.update(
                new AutomixCueMarker(6_000L, 9_000L, FOUR_MINUTES, false));
        assertEquals(0.025, snapped.cueFraction, 1e-9);
    }

    /** 一帧一帧推到目标上，返回收敛后的读数；超时由断言自己报出来。 */
    private static AutomixCueMarker.Positions settle(AutomixCueMarker.Glide glide,
                                                    AutomixCueMarker target) {
        long deadline = System.currentTimeMillis() + 1_500L;
        AutomixCueMarker.Positions last = null;
        while (System.currentTimeMillis() < deadline) {
            sleep(15L);
            last = glide.update(target);
            if (Math.abs(last.cueFraction - target.cueFraction()) < 1e-4
                    && Math.abs(last.bandEndFraction - target.bandEndFraction()) < 1e-4) {
                return last;
            }
        }
        throw new AssertionError("平滑器没有在 1.5 秒内收敛到最新计划：" + last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
