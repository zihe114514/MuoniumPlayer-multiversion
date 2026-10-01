package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.screens.ncm.TransitionPerformanceBudget.Quality;
import com.muoniumplayer.core.screens.ncm.TransitionPerformanceBudget.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「液态玻璃会闪」这件事的账，全在 JVM 上拿假帧序列算，不启动客户端。
 *
 * <h2>要钉住的症状</h2>
 * 2026-09-30 玩家反馈：「液态玻璃不稳定，有的时候会闪烁，表现为暗一下，然后恢复」。
 * 成因不是采集、也不是坐标：{@code GlassLook.amount()} 以前直接乘 {@code isMinimal()} 那个
 * <em>布尔</em>，而极低档是「平滑帧时一跨过 25ms 就当场生效」的。玻璃一摘，整块面板从
 * 「糊过的亮画面」<em>一帧</em>切回「原来的实色深底」——那就是「暗一下」；等帧时好过
 * 15.5ms 连续 45 帧再装回来，那就是「然后恢复」。而玻璃本身是贵的那一层，
 * 于是「掉帧 → 摘 → 帧率好了 → 装 → 又掉帧」是一条会自己维持的回路。
 *
 * <h2>这里钉住的四条</h2>
 * <ol>
 *   <li>一次随机卡顿（一帧 200ms）不许动档：EMA 会被它顶上去并慢慢回落十几帧，
 *       但那十几帧全都是快的，慢帧累计必须被清零。</li>
 *   <li>真掉到 30fps 且<em>正在转场</em>时照样降载，但玻璃走的是 250ms 斜坡，
 *       不是 0/1 的台阶——症状从「闪一下」变成「淡出」。</li>
 *   <li>界面好好开着（没有转场）时玻璃一位都不动，因为那正是玩家盯着看的那一段。</li>
 *   <li>装回来要同时等够「1.5 秒好帧」和「离上次换档 2 秒」，不许和摘出去打乒乓。</li>
 * </ol>
 */
class GlassFlickerStaticTest {

    private static final long NANOS_PER_MS = 1_000_000L;

    /** 100fps 的一帧：快机器装回来的时候走的就是这一档。 */
    private static final double FAST_FRAME_MS = 10.0;
    /** 30fps —— 踩在极低档门槛（25ms）之上。 */
    private static final double SLOW_FRAME_MS = 33.0;

    /** 拿假时钟走 {@code frames} 帧：每帧 {@code frameMs}，平滑帧时按调用方给的值喂。 */
    private static long advance(State state, long nowNanos, int frames, double smoothedMs,
                               double frameMs, boolean transitionActive) {
        long clock = nowNanos;
        long frameNanos = (long) (frameMs * NANOS_PER_MS);
        for (int i = 0; i < frames; i++) {
            clock += frameNanos;
            TransitionPerformanceBudget.step(state, smoothedMs, frameMs, frameNanos,
                    clock, transitionActive);
        }
        return clock;
    }

    @Test
    @DisplayName("一次 200ms 的随机卡顿不许把玻璃摘掉")
    void transientHitchLeavesGlassAlone() {
        State state = new State();
        long clock = 0L;

        // 第 0 帧卡了 200ms：EMA 从 16.7 一步跳到 44.2（0.15 权重），远超极低档门槛。
        clock = advance(state, clock, 1, 44.2, 200.0, false);
        assertEquals(Quality.BALANCED, state.quality, "单帧尖峰不该当场降档");

        // 接下来 EMA 慢慢回落的这十几帧里，每一帧自己都是快的（16.7ms）——这正是原来那版
        // 会误判的地方：它只看平滑值，于是在这里就把玻璃摘了。
        double[] decay = {40.1, 36.3, 32.9, 29.9, 27.4, 25.1, 23.2, 20.7, 18.4, 16.7};
        for (double smoothed : decay) {
            clock = advance(state, clock, 1, smoothed, 16.7, false);
            assertEquals(Quality.BALANCED, state.quality,
                    "平滑帧时回落期间（本帧都是快的）不许降档，卡在 " + smoothed);
        }
        assertEquals(1f, state.glassWeight, 0f, "这一整段里玻璃强度一位都不该动");
    }

    @Test
    @DisplayName("真掉到 30fps 要连续 0.4 秒才降一档，之后玻璃是淡出不是切换")
    void sustainedSlowFramesDegradeButNotBeforeTheirTime() {
        State state = new State();
        long clock = 0L;

        // 每帧 33ms、每帧都慢 —— 累计到 400ms 才算数：33ms 一帧就是第 13 帧（429ms）。
        clock = advance(state, clock, 12, 30.0, SLOW_FRAME_MS, true);
        assertEquals(Quality.BALANCED, state.quality, "不满 0.4 秒不许降档");
        assertEquals(1f, state.glassWeight, 0f, "没换档之前强度不动");
        clock = advance(state, clock, 1, 30.0, SLOW_FRAME_MS, true);
        assertEquals(Quality.MINIMAL, state.quality, "连续 429ms 的 30fps 应该降一档");
        assertTrue(state.glassWeight > 0.85f,
                "换档那一帧最多刚起步，实际 " + state.glassWeight);

        // 之后是 250ms 的淡出，不是一帧到位：33ms 一帧，每帧只走掉 33/250 ≈ 0.132。
        clock = advance(state, clock, 3, 30.0, SLOW_FRAME_MS, true);
        assertTrue(state.glassWeight > 0.3f && state.glassWeight < 0.6f,
                "换档三帧以后该还在半透明上，实际 " + state.glassWeight);
        advance(state, clock, 8, 30.0, SLOW_FRAME_MS, true);
        assertEquals(0f, state.glassWeight, 0f, "十来帧（三百多毫秒）之后才走到 0，这就是「淡出」");
    }

    @Test
    @DisplayName("界面没在转场时，掉帧也不动玻璃一块")
    void steadyStateNeverTouchesGlass() {
        State state = new State();

        // 同样的持续 30fps：档位会降（歌词窗口那些还是要省），但玻璃强度必须全程是 1。
        advance(state, 0L, 60, 30.0, SLOW_FRAME_MS, false);
        assertEquals(Quality.MINIMAL, state.quality, "极低档本身还是要能进来，别的降载策略指着它");
        assertEquals(1f, state.glassWeight, 0f,
                "稳态下摘玻璃就是玩家看见的「暗一下」——这一档只在转场期间允许");

        // 反向证据：同一段帧序列只要开着转场，玻璃就应该淡干净，不然那条闸等于没有。
        State withTransition = new State();
        advance(withTransition, 0L, 60, 30.0, SLOW_FRAME_MS, true);
        assertEquals(0f, withTransition.glassWeight, 0f);
    }

    @Test
    @DisplayName("装回来要同时等够 1.5 秒好帧和 2 秒按住，之后掉帧仍能降载")
    void recoveryWaitsForBothStreakAndHold() {
        State state = new State();
        long clock = advance(state, 0L, 13, 30.0, SLOW_FRAME_MS, true);
        assertEquals(Quality.MINIMAL, state.quality);
        long flippedAt = clock;

        // 转场结束、帧率回来了（10ms 一帧）。走 150 帧 = 1500ms：好帧累计刚好够，
        // 但离上次换档只有 1500ms，那道 2 秒的按住得把它压住。
        clock = advance(state, clock, 150, 12.0, FAST_FRAME_MS, false);
        assertEquals(Quality.MINIMAL, state.quality,
                "好帧已经累计 1.5 秒，但 2 秒那道按住还差 "
                        + (2_000_000_000L - (clock - flippedAt)) / NANOS_PER_MS + "ms，就该继续压着");
        assertEquals(1f, state.glassWeight, 0f,
                "档位还没换，但转场一结束玻璃就该淡回来——玻璃看的是强度，不是档位");

        clock = advance(state, clock, 55, 12.0, FAST_FRAME_MS, false);
        assertEquals(Quality.BALANCED, state.quality, "两道都过了就该装回来");
        assertTrue(clock - flippedAt >= 2_000_000_000L, "换档间隔不足 2 秒");

        // 换档之后马上又掉帧：先要跨过新的 2 秒窗口，再攒够 0.4 秒慢帧。
        // 这一条挡的就是「玻璃自己把帧率吃下去」那条正反馈——它以前没有下限。
        long recoveredAt = clock;
        advance(state, clock, 13, 30.0, SLOW_FRAME_MS, false);
        assertEquals(Quality.BALANCED, state.quality, "刚换完档又想翻？按住那一段不许翻");
        assertEquals(1f, state.glassWeight, 0f);
        // 空转 2.1 秒（这一段没有帧，只是让假时钟跨过那道窗口），然后再来 200 帧慢帧。
        advance(state, recoveredAt + 2_100_000_000L, 200, 30.0, SLOW_FRAME_MS, false);
        assertEquals(Quality.MINIMAL, state.quality, "跨过按住窗口以后，真掉帧还是要能降载");
    }

    @Test
    @DisplayName("转场没结束就不回升：好帧攒满 1.5 秒也不换档")
    void recoveryWaitsForTheTransitionToFinish() {
        State state = new State();
        long clock = advance(state, 0L, 13, 30.0, SLOW_FRAME_MS, true);
        assertEquals(Quality.MINIMAL, state.quality);
        // 转场一直在播（例如播放器停在展开动画上）：好帧再多也不装档，避免在动画中途改细节档位。
        advance(state, clock, 400, 12.0, FAST_FRAME_MS, true);
        assertEquals(Quality.MINIMAL, state.quality, "转场期间不回升");
    }

    @Test
    @DisplayName("迟滞带里不表态：15.5~18.5ms 既不够降也不够升")
    void hysteresisBandKeepsCurrentTier() {
        assertEquals(Quality.LOW, TransitionPerformanceBudget.targetFor(Quality.BALANCED, 18.6));
        assertEquals(Quality.MINIMAL, TransitionPerformanceBudget.targetFor(Quality.LOW, 25.0));
        // 带内：从任何一档看都是「维持」。60fps 带 vsync 抖动时最常落在的就是这一段。
        assertEquals(Quality.BALANCED, TransitionPerformanceBudget.targetFor(Quality.BALANCED, 16.7));
        assertEquals(Quality.BALANCED, TransitionPerformanceBudget.targetFor(Quality.BALANCED, 16.0));
        assertEquals(Quality.MINIMAL, TransitionPerformanceBudget.targetFor(Quality.MINIMAL, 16.0));
        // 而跨过 15.5 才算真的好：这一段才允许回升。
        assertEquals(Quality.BALANCED, TransitionPerformanceBudget.targetFor(Quality.MINIMAL, 15.4));
    }

    @Test
    @DisplayName("斜坡：250ms 走完，超长卡顿一步到位，到了就不再动")
    void rampBehaviourIsBounded() {
        assertEquals(0.868f, TransitionPerformanceBudget.rampToward(1f, 0f, 33 * NANOS_PER_MS), 0.001f,
                "33ms 一帧走掉 33/250");
        assertEquals(1f, TransitionPerformanceBudget.rampToward(0.2f, 1f, 3_000 * NANOS_PER_MS), 0f,
                "三秒的空档直接走到头，不许把玻璃冻在半透明");
        assertEquals(0f, TransitionPerformanceBudget.rampToward(0f, 0f, 50 * NANOS_PER_MS), 0f,
                "已经在目标上就一位不动");
        // 反向也一样：装回来同样是淡入，不是「啪」一下亮。
        float half = TransitionPerformanceBudget.rampToward(0f, 1f, 125 * NANOS_PER_MS);
        assertTrue(half > 0.4f && half < 0.6f, "半条斜坡该在中间，实际 " + half);
    }

    @Test
    @DisplayName("换档判据单独钉：降看慢帧累计，升看好帧累计加上没在转场")
    void flipPredicateKeepsTheAsymmetry() {
        assertTrue(TransitionPerformanceBudget.allowsFlip(Quality.BALANCED, Quality.MINIMAL,
                400_000_000L, 0L, Long.MAX_VALUE, false), "慢帧够了就该降");
        assertFalse(TransitionPerformanceBudget.allowsFlip(Quality.BALANCED, Quality.LOW,
                399_000_000L, 0L, Long.MAX_VALUE, false), "差 1ms 都不许降");
        assertTrue(TransitionPerformanceBudget.allowsFlip(Quality.MINIMAL, Quality.BALANCED,
                0L, 1_500_000_000L, Long.MAX_VALUE, false), "好帧够了且不转场就该升");
        assertFalse(TransitionPerformanceBudget.allowsFlip(Quality.MINIMAL, Quality.BALANCED,
                0L, 1_500_000_000L, Long.MAX_VALUE, true), "正在转场就不许趁机装回来");
        assertFalse(TransitionPerformanceBudget.allowsFlip(Quality.BALANCED, Quality.MINIMAL,
                9_000_000_000L, 0L, 1_999_000_000L, false), "离上次换档不足 2 秒就按住");
        assertFalse(TransitionPerformanceBudget.allowsFlip(Quality.BALANCED, Quality.BALANCED,
                9_000_000_000L, 9_000_000_000L, Long.MAX_VALUE, false), "同一档没有「换」可言");
    }
}
