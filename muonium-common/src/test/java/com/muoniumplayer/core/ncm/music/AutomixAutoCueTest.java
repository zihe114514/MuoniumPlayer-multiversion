package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进阶模式选点的数值契约。
 *
 * <p>这一层是纯函数：喂合成包络，拿一对点。源存在性测试（{@code FoliaAutomixStaticTest} 那一族）
 * 覆盖不到「同一个输入必须给同一个输出」和「该弃权的时候真的弃权」这两条——而后者是这套东西
 * 唯一会让用户<b>听出来</b>的地方：接错一次接缝，比整场都不去接要糟得多。</p>
 */
class AutomixAutoCueTest {

    private static final long COARSE = AutomixEnvelope.HOP_MILLIS;
    private static final long FINE = AutomixEnvelope.FINE_HOP_MILLIS;

    /** 把 0..1 的数组按量化格式装成一份包络；低频/高频摆成同一半，让频段互补对所有候选都是中立值。 */
    private static AutomixEnvelope envelope(long durationMillis, double[] coarse, double[] fine) {
        int frames = (int) (durationMillis / COARSE);
        int fineFrames = (int) (durationMillis / FINE);
        byte[] rms = new byte[frames];
        byte[] low = new byte[frames];
        byte[] high = new byte[frames];
        byte[] onset = new byte[frames];
        for (int i = 0; i < frames; i++) {
            double value = i < coarse.length ? coarse[i] : 0.0;
            rms[i] = quantize(value);
            low[i] = quantize(value / 2.0);
            high[i] = quantize(value / 2.0);
        }
        byte[] fineBytes = new byte[fineFrames];
        for (int i = 0; i < fineFrames; i++) {
            fineBytes[i] = quantize(i < fine.length ? fine[i] : 0.0);
        }
        return new AutomixEnvelope(durationMillis, 0.5f, rms, low, high, onset, fineBytes);
    }

    private static byte quantize(double value) {
        return (byte) Math.max(0, Math.min(255, Math.round(value * 255.0)));
    }

    private static double[] filled(int length, double value) {
        double[] out = new double[length];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static int coarseFrames(long durationMillis) {
        return (int) (durationMillis / COARSE);
    }

    private static int fineFrames(long durationMillis) {
        return (int) (durationMillis / FINE);
    }

    /** 一条每 {@code period} 帧半周期交替的细包络：用来造「律动轮廓」这个形状项看的东西。 */
    private static double[] oscillate(long durationMillis, int period) {
        double[] out = new double[fineFrames(durationMillis)];
        for (int i = 0; i < out.length; i++) {
            out[i] = (i % period < period / 2) ? .95 : .15;
        }
        return out;
    }

    /**
     * 在 {@code startMillis} 起的 {@code lengthMillis} 内压一段「一段安静 + 一个交替图案」。
     *
     * <p>交替图案只出现在细包络里（形状相关看它），粗包络在那里给<b>更低</b>的电平（电平项看它）。
     * 这样一个候选只有在两处图案对得上、且电平也对得上时才同时拿到两项满分——而「到处都平」的
     * 那些候选拿不到形状项（方差为零按缺料处理），电平项也只在两段同为低电平时才满。</p>
     */
    private static void stamp(double[] coarse, double[] fine, long startMillis, long lengthMillis) {
        int coarseFrom = (int) (startMillis / COARSE);
        int coarseLength = (int) (lengthMillis / COARSE);
        for (int i = 0; i < coarseLength && coarseFrom + i < coarse.length; i++) {
            coarse[coarseFrom + i] = .3;
        }
        int fineFrom = (int) (startMillis / FINE);
        int fineLength = (int) (lengthMillis / FINE);
        for (int i = 0; i < fineLength && fineFrom + i < fine.length; i++) {
            fine[fineFrom + i] = (i % 20 < 10) ? .95 : .15;
        }
    }

    private static AutomixAutoCue.Request request(AutomixEnvelope a, AutomixEnvelope b) {
        AutomixAutoCue.Request request = new AutomixAutoCue.Request();
        request.outgoing = a;
        request.incoming = b;
        // 启发式位置落在搜索区间<b>之内</b>：它必须是候选之一，测试才谈得上「不比它差」。
        request.heuristicFireMillis = a == null ? 0L : Math.max(0L, a.durationMillis - 25_000L);
        request.heuristicCueMillis = 0L;
        request.heuristicOverlapMillis = 4_000L;
        request.preferredOverlapMillis = 4_000L;
        request.beatAlignEnabled = false;
        return request;
    }

    @Test
    @DisplayName("图案与电平都对得上的一对必须被选中——启发式根本不看那里")
    void findsTheMatchingPairOutsideTheHeuristicsWindow() {
        long duration = 120_000L;
        double[] aCoarse = filled((int) (duration / COARSE), .85);
        double[] aFine = filled((int) (duration / FINE), .85);
        stamp(aCoarse, aFine, 60_000L, 4_000L);

        long incomingDuration = 100_000L;
        double[] bCoarse = filled((int) (incomingDuration / COARSE), .55);
        double[] bFine = filled((int) (incomingDuration / FINE), .55);
        stamp(bCoarse, bFine, 8_000L, 4_000L);

        AutomixAutoCue.Request request =
                request(envelope(duration, aCoarse, aFine), envelope(incomingDuration, bCoarse, bFine));
        AutomixAutoCue.Result result = AutomixAutoCue.plan(request);

        assertNotNull(result, "两首歌都放得下窗口，不该判成无路可走");
        assertTrue(result.score >= AutomixAutoCue.ABSTAIN_SCORE,
                "图案与电平都对应的一对必须过弃权线，实际 " + result.score + " · " + result.detail);
        assertTrue(result.isAdopted(), "必须比启发式对照位更好");
        // 落点的容差是 2.5 秒而不是 1.5：候选按 500 ms 铺，而九项里尾段保留一项会把它再往
        // 晚的方向推半秒。真正要钉住的是「它落在图案那一头、而不是启发式那一段」。
        assertTrue(Math.abs(result.fireMillis - 60_000L) <= 2_500L,
                "出场点要落在图案那一头，实际 " + result.fireMillis);
        assertTrue(Math.abs(result.cueMillis - 8_000L) <= 1_500L,
                "进场点要落在 B 的图案那一头，实际 " + result.cueMillis);

        // 这一对确实比启发式早退场，那就必须按多吃掉的那一段付加价：每多一分钟 0.05。
        long baselineDiscarded = duration
                - (request.heuristicFireMillis + request.heuristicOverlapMillis);
        long eatenEarly = result.discardedMillis - baselineDiscarded;
        assertTrue(eatenEarly > 0L, "这一对本来就是「比启发式早退场」的例子，不然这条没在测什么");
        assertTrue(result.fireMillis < request.heuristicFireMillis,
                "这一对本来就是「比启发式早退场」的例子，不然这条没在测什么");
        assertEquals(result.baselineScore + 0.05 * eatenEarly / 60_000.0, result.requiredScore, 1e-9,
                "采纳线要正好是「启发式 + 每多吃一分钟 0.05」，多吃 " + (eatenEarly / 1000L) + " 秒");
        assertTrue(result.score >= result.requiredScore,
                "既然采纳了，就必须越过抬起来的那条线：" + result.score + " vs " + result.requiredScore);
    }

    @Test
    @DisplayName("早退场的加价：每多吃一分钟抬 0.05、上限 0.20、往晚退一分钱不收")
    void earlyCutMarginArithmeticIsPinned() {
        assertEquals(0.0, AutomixAutoCue.earlyCutMargin(10_000L, 60_000L), 1e-9,
                "比启发式吃得更少（退得更晚）不该被加价——它更接近「让这首唱完」");
        assertEquals(0.0, AutomixAutoCue.earlyCutMargin(60_000L, 60_000L), 1e-9, "同一位置零加价");
        assertEquals(0.05, AutomixAutoCue.earlyCutMargin(120_000L, 60_000L), 1e-9, "多吃一分钟 = 0.05");
        assertEquals(0.15, AutomixAutoCue.earlyCutMargin(60_000L + 3 * 60_000L, 60_000L), 1e-9,
                "多吃三分钟 = 0.15");
        assertEquals(0.20, AutomixAutoCue.earlyCutMargin(60_000L + 30 * 60_000L, 60_000L), 1e-9,
                "封顶 0.20：不然「多切半首歌」会把整曲搜索变成数学上不可能被采纳的东西");
    }

    @Test
    @DisplayName("位置之间分不出高低时，必须由尾段保留项把退场点推到最晚")
    void tailTermPrefersTheLatestExit() {
        long duration = 120_000L;
        // 纯平的两条包络：形状与起声算不出（方差为零），电平/涟漪/互补/弧对每个位置都给同一个分。
        // 于是搜索里唯一随位置变的就只剩尾段保留——它必须赢，选出来的点要贴着曲尾。
        AutomixEnvelope flat = envelope(duration, filled(coarseFrames(duration), .6),
                filled(fineFrames(duration), .6));

        AutomixAutoCue.Result result = AutomixAutoCue.plan(request(flat, flat));

        assertNotNull(result);
        assertTrue(result.detail.contains("尾段="), "诊断里要看得见这一项：" + result.detail);
        assertTrue(result.discardedMillis <= 12_000L,
                "没有别的证据时不该为了「接得更早」把这首砍掉一截：退场 " + result.fireMillis
                        + " / " + duration + "，被跳过 " + (result.discardedMillis / 1000L) + " 秒");
        // 这一项是政策先验，不该算进「量到了多少证据」：证据计数的上限永远是八项那 7.15。
        assertTrue(result.measuredWeight <= AutomixAutoCue.WEIGHT_SUM
                        - AutomixAutoCue.WEIGHTS[AutomixAutoCue.TAIL_TERM] + 1e-9,
                "证据计数把尾段那 1.00 算进去了，实际 " + result.measuredWeight);
        assertTrue(result.detail.matches("(?s).*尾段=(0\\.9[0-9]|1\\.00).*"),
                "贴着曲尾退场时这一项该接近满分，诊断：" + result.detail);
    }

    @Test
    @DisplayName("两段都满电平但律动互不相干：宁可照旧按启发式切")
    void rejectsAnUncorrelatedLoudPair() {
        long duration = 120_000L;
        // 粗包络两边都摆平：电平、涟漪、弧都拿得到分，谁也别想靠它们占便宜。
        // 真正分得开的是细包络——一边每 20 帧交替、一边每 7 帧交替，相关算得出来却接近 0，
        // 而形状项是八项里权重最大的那一个（1.6）。频段两边故意做成一样：它们会打架，
        // 「互补」那一项因此恒为 0，这一对没有任何一项可以翻身。
        AutomixEnvelope a = envelope(duration, filled(coarseFrames(duration), .8), oscillate(duration, 20));
        AutomixEnvelope b = envelope(duration, filled(coarseFrames(duration), .8), oscillate(duration, 7));
        AutomixAutoCue.Request request = request(a, b);
        request.beatAlignEnabled = true;
        request.outgoingGrid = new AutomixAnalyzer.BeatGrid(120f, 0f, 0f, .9f);
        request.incomingGrid = new AutomixAnalyzer.BeatGrid(170f, 0f, 0f, .9f);
        request.lastVocalEndMillis = duration - 10_000L;

        AutomixAutoCue.Result result = AutomixAutoCue.plan(request);

        assertNotNull(result);
        assertTrue(result.measuredWeight >= AutomixAutoCue.MIN_MEASURED_WEIGHT,
                "这一对是全项可量的，不能靠「没量够」弃权：" + result.detail);
        assertTrue(!result.isAdopted(),
                "满电平却接不上的一对必须被弃掉，实际 " + result.score + " · " + result.detail);
        assertTrue(result.score < AutomixAutoCue.ABSTAIN_SCORE, "弃权线本身要成立");
    }

    @Test
    @DisplayName("只量出一两项的虚高分不能采纳——缺料项会退出分母，会把分数抬起来")
    void refusesToAdoptWithoutMeasuringEnough() {
        long duration = 120_000L;
        // 两边都是纯平：形状与起声都算不出（方差为零），速度与人声都没有证据。
        // 剩下的四项可以很有说服力——那正是「没量到什么但运气好」的样子。
        AutomixEnvelope a = envelope(duration, filled(coarseFrames(duration), .5),
                filled(fineFrames(duration), .5));
        AutomixAutoCue.Result alone = AutomixAutoCue.plan(request(a, a));

        assertNotNull(alone);
        assertTrue(alone.measuredWeight < AutomixAutoCue.MIN_MEASURED_WEIGHT,
                "平坦包络本该量不出多少，实际量到 " + alone.measuredWeight + " · " + alone.detail);
        assertTrue(!alone.isAdopted(),
                "没量够就不许改用户的接缝，哪怕分数好看：" + alone.score);
    }

    @Test
    @DisplayName("被采纳的那一对不比启发式对照位差，且窗口夹在用户能设的上下限内")
    void adoptedPairIsNeverWorseThanBaselineAndAlwaysFits() {
        long duration = 90_000L;
        AutomixEnvelope a = envelope(duration, filled((int) (duration / COARSE), .45),
                filled((int) (duration / FINE), .45));
        AutomixEnvelope b = envelope(duration, filled((int) (duration / COARSE), .5),
                filled((int) (duration / FINE), .5));

        AutomixAutoCue.Result result = AutomixAutoCue.plan(request(a, b));

        assertNotNull(result);
        // 契约是「越过它自己那条抬起来的采纳线」，而不是「裸分一定压过启发式」：
        // 短名单只留粗排前 96 对，启发式那一格不一定在细排里；真正决定改不改用户接缝的
        // 是 requiredScore 这条线（= 启发式分 + 早退场的加价），所以钉它。
        assertTrue(result.requiredScore >= result.baselineScore - 1e-9,
                "采纳线只能等于或高于启发式对照分，不能更低：" + result.requiredScore
                        + " vs " + result.baselineScore);
        if (result.isAdopted()) {
            assertTrue(result.score >= result.requiredScore - 1e-9,
                    "既然采纳了就必须越过采纳线：" + result.score + " vs " + result.requiredScore);
        } else {
            assertTrue(result.score < result.requiredScore
                            || result.score < AutomixAutoCue.ABSTAIN_SCORE
                            || result.measuredWeight < AutomixAutoCue.MIN_MEASURED_WEIGHT,
                    "弃权必须给出理由，不能是「既没过线又没说不过」：" + result.note);
        }
        long floor = (long) (AutomixSettings.MIN_OVERLAP_SECONDS * 1000.0);
        long ceiling = (long) (AutomixSettings.MAX_OVERLAP_SECONDS * 1000.0);
        assertTrue(result.overlapMillis >= floor && result.overlapMillis <= ceiling,
                "候选窗口要夹在用户能设的交叠上下限内，实际 " + result.overlapMillis);
        assertTrue(result.fireMillis + result.overlapMillis < duration, "出场窗口不能越过文件末尾");
        assertTrue(result.cueMillis + result.overlapMillis < duration, "进场窗口不能越过文件末尾");
        assertTrue(result.fireMillis >= AutomixAutoCue.MIN_CUT_FRACTION * duration,
                "不能在歌刚开头就退场，实际 " + result.fireMillis);
    }

    @Test
    @DisplayName("同样的输入两次必须给同样的输出")
    void searchIsDeterministic() {
        long duration = 80_000L;
        double[] coarse = filled((int) (duration / COARSE), .6);
        double[] fine = filled((int) (duration / FINE), .6);
        stamp(coarse, fine, 30_000L, 3_000L);
        AutomixEnvelope a = envelope(duration, coarse, fine);
        AutomixEnvelope b = envelope(duration, coarse, fine);

        AutomixAutoCue.Result first = AutomixAutoCue.plan(request(a, b));
        AutomixAutoCue.Result second = AutomixAutoCue.plan(request(a, b));

        assertNotNull(first);
        assertEquals(first.fireMillis, second.fireMillis);
        assertEquals(first.cueMillis, second.cueMillis);
        assertEquals(first.overlapMillis, second.overlapMillis);
        assertEquals(first.score, second.score, 1e-12);
    }

    @Test
    @DisplayName("两首歌放不下任何一档候选窗口时返回 null，而不是硬造一个越界的点")
    void refusesWhenNothingFits() {
        AutomixEnvelope tooShort = envelope(5_000L, new double[250], new double[500]);
        assertNull(AutomixAutoCue.plan(request(tooShort, tooShort)));
    }

    @Test
    @DisplayName("包络缺失就不选点：规划路径上绝不允许现场扫描")
    void abstainsWithoutEnvelopes() {
        AutomixEnvelope ready = envelope(60_000L, new double[3_000], new double[6_000]);
        AutomixAutoCue.Request request = request(ready, ready);
        request.incoming = null;
        assertNull(AutomixAutoCue.plan(request));
        request.incoming = ready;
        request.outgoing = null;
        assertNull(AutomixAutoCue.plan(request));
    }

    @Test
    @DisplayName("缺证据的项连同权重退出分母，不给假满分")
    void unknownTermsDoNotCountAsPerfection() {
        // 没有网格（⑥缺）、没有人声证据（⑧缺）、平坦包络（①⑤缺）：只剩四项。
        long duration = 60_000L;
        AutomixEnvelope a = envelope(duration, filled((int) (duration / COARSE), .5),
                filled((int) (duration / FINE), .5));
        AutomixAutoCue.Result result = AutomixAutoCue.plan(request(a, a));

        assertNotNull(result);
        assertTrue(result.score < 1.0, "只剩中性项不可能拿满，实际 " + result.score);
        assertTrue(result.detail.contains("—"), "缺料项要在诊断里显出来：「" + result.detail + "」");
        assertTrue(result.detail.indexOf("—") < result.detail.length(), "形状与漂移与人声三项都该是缺料");
    }

    @Test
    @DisplayName("播放头之后的位置才允许选——不许往回挑一个已经播过去的退场点")
    void neverPicksAFirePointInThePast() {
        long duration = 120_000L;
        double[] aCoarse = filled(coarseFrames(duration), .85);
        double[] aFine = filled(fineFrames(duration), .85);
        stamp(aCoarse, aFine, 60_000L, 4_000L);
        long incomingDuration = 100_000L;
        double[] bCoarse = filled(coarseFrames(incomingDuration), .55);
        double[] bFine = filled(fineFrames(incomingDuration), .55);
        stamp(bCoarse, bFine, 8_000L, 4_000L);

        AutomixAutoCue.Request request = request(
                envelope(duration, aCoarse, aFine), envelope(incomingDuration, bCoarse, bFine));
        // 播放头已经走到 70 秒：图案在 60 秒，再好也不能回头。
        request.minimumFireMillis = 70_000L;
        AutomixAutoCue.Result result = AutomixAutoCue.plan(request);

        assertNotNull(result);
        assertTrue(result.fireMillis >= 70_000L,
                "退场点必须严格在播放头之后，实际 " + result.fireMillis);
    }

    @Test
    @DisplayName("九项权重之和与数组一致：漏一项会让所有分数悄悄偏高")
    void weightsSumIsPinned() {
        double sum = 0.0;
        for (double weight : AutomixAutoCue.WEIGHTS) sum += weight;
        assertEquals(AutomixAutoCue.WEIGHT_SUM, sum, 1e-9);
        assertEquals(9, AutomixAutoCue.WEIGHTS.length);
        // 尾段保留必须是最后一项：它是「政策先验」那一项，证据计数按 TAIL_TERM 排除它。
        // 以后再加一项时如果加在它后面，排除的是错的项，平坦包络会悄悄越过「没量够」那道闸。
        assertEquals(AutomixAutoCue.WEIGHTS.length - 1, AutomixAutoCue.TAIL_TERM);
        assertEquals(7.15, sum - AutomixAutoCue.WEIGHTS[AutomixAutoCue.TAIL_TERM], 1e-9,
                "证据那八项的合计变了：MIN_MEASURED_WEIGHT 的口径要一起重新想");
    }
}
