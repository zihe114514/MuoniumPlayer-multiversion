package com.muoniumplayer.core.ncm.music;

import java.util.Locale;

/**
 * 进阶模式的<b>一键排列</b>：把一串待播曲目重排成「相邻两首接得上」的顺序。
 *
 * <p>它与交叠本身完全解耦——这里不决定怎么淡出、也不决定用什么曲线，只决定
 * <b>哪两首会挨在一起</b>。所以关闭本类不会让 {@link AutomixAutoCue} 或交接曲线有任何变化，
 * 打开它也不会让任何一条曲线变形：它改的是队列，不是混音。</p>
 *
 * <h2>为什么排在前面的是「安静、低、慢」</h2>
 *
 * <p>目标函数把每首歌量成一个 0..100 的 {@code fit}（速度、电平、明亮度三项加权），
 * 再要求第 i 格的 {@code fit} 落在这条理想曲线上——{@code 10 + 80·p^1.25}，
 * 也就是<b>越往后越燃</b>。这不是审美偏好，是交接的物理约束：相邻两首的速度比越接近 1:1
 * 或 1:2 越容易接（对拍、锁速度都在预算内），电平差越大越只能靠增益硬拉，
 * 而一场「从头到尾一样响」的列表里，任何一首接任何一首都没有坡度可言。</p>
 *
 * <h2>三条不变量</h2>
 *
 * <ol>
 *   <li><b>没量过的歌不动。</b>特征为 {@code null}（还没下载、包络没扫出来）的曲目留在原来的格子里，
 *       只有量过的歌在它们原本占的那些格子之间重排。结果长度与输入一致，一首都不会凭空少掉。</li>
 *   <li><b>算不出来就不参与，不猜。</b>{@code tempo} 或 {@code level} 缺项时那一项<b>连同权重</b>
 *       退出归一化，而不是补一个中位值——补一个值会让「这首歌没测出来」在结果里彻底看不见。</li>
 *   <li><b>确定性。</b>同样的输入永远给同样的顺序：贪心里严格小于才换、平手取原下标较小的那个，
 *       不用随机数也不设随机种子。用户在同一个列表上按两次得到两个结果，是不能接受的。</li>
 * </ol>
 */
final class AutomixFlowPlanner {

    /** 理想曲线：第 i 格应该有多「燃」，0..100。 */
    static final double IDEAL_BASE = 10.0;
    static final double IDEAL_RISE = 80.0;
    /** 理想曲线的起势指数——前段爬得慢，后段才抬起来。 */
    static final double IDEAL_CURVE = 1.25;

    /** 三项权重：速度、电平、明亮度。 */
    static final double W_TEMPO = 0.4;
    static final double W_LEVEL = 0.4;
    static final double W_TONE = 0.2;
    /** 落点偏离理想曲线的代价权重，与相邻代价比。 */
    static final double W_FIT = 1.5;
    /** 速度比的代价权重：1:1 与 1:2 都算免费，其余按最接近的那个倍率算偏差。 */
    static final double W_TEMPO_STEP = 1.0;
    /** 进出电平的落差代价。 */
    static final double W_LEVEL_STEP = 0.5;

    private AutomixFlowPlanner() {
    }

    /**
     * 一首曲目的接缝特征。约定与 {@code FoliaTrackProfile} 一致：
     * {@code NaN} 是「没量出来」，不是「等于 0」。
     */
    static final class Feature {
        /** 主速度，BPM。 */
        double tempo = Double.NaN;
        /** 头段速度、尾段速度：两者差得多说明这首歌自己就在变速。 */
        double headTempo = Double.NaN;
        double tailTempo = Double.NaN;
        /** 整体电平，dBFS（越接近 0 越响）。 */
        double level = Double.NaN;
        /** 头 15 秒电平与尾 15 秒电平。前者决定「接进来时有多响」，后者决定「退出去时有多响」。 */
        double headLevel = Double.NaN;
        double tailLevel = Double.NaN;
        /** 明亮度，0..1（高频占比高于低频的程度）。 */
        double tone = Double.NaN;
        /** 时长，秒。 */
        double seconds = Double.NaN;

        boolean isSortable() {
            return Double.isFinite(tempo) && tempo > 1.0
                    && (Double.isFinite(headLevel) || Double.isFinite(level));
        }

        double incomingLevel() {
            return Double.isFinite(headLevel) ? headLevel : level;
        }

        double outgoingLevel() {
            return Double.isFinite(tailLevel) ? tailLevel : level;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "tempo %.1f level %.1f tone %.2f", tempo, level, tone);
        }
    }

    /**
     * 排出新顺序。
     *
     * @param features 与队列等长，{@code null} 表示这一首没量过（留在原位）
     * @return {@code order[i] = j}：新顺序第 i 格放原来第 j 首。永远是一个全排列，
     *         长度与入参一致；没有可排内容时就是恒等排列。
     */
    static int[] plan(Feature[] features) {
        if (features == null) return new int[0];
        int size = features.length;
        int[] order = new int[Math.max(1, size)];
        for (int i = 0; i < order.length; i++) order[i] = i;
        if (size < 2) return order;

        int[] slots = new int[size];      // 可排的格子（有特征的原始位置）
        int[] sources = new int[size];    // 可排的曲目（有特征的原始下标）
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (features[i] != null && features[i].isSortable()) {
                slots[count] = i;
                sources[count] = i;
                count++;
            }
        }
        if (count < 2) return order;

        double maxTempo = 0.0, maxLevelSpan = 0.0, maxTone = 0.0;
        for (int i = 0; i < count; i++) {
            Feature feature = features[sources[i]];
            maxTempo = Math.max(maxTempo, feature.tempo);
            maxTone = Math.max(maxTone, finite(feature.tone) ? feature.tone : 0.0);
        }
        // 电平先折到 0..1：dBFS 是负的，跨度取批内最大最小差，否则「整批都很轻」会被放大成满量程。
        double loudest = -Double.MAX_VALUE, quietest = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double value = levelOf(features[sources[i]]);
            if (!Double.isFinite(value)) continue;
            loudest = Math.max(loudest, value);
            quietest = Math.min(quietest, value);
        }
        boolean levelsKnown = loudest > quietest;
        maxLevelSpan = levelsKnown ? loudest - quietest : 1.0;

        // 代价函数只比<b>归一到 0..1</b>之后的电平：dBFS 直接相减会让一个 6 dB 的台阶压过整条
        // 速度项，而相邻两首差 6 dB 相当常见。这里是批内副本，绝不改调用方传进来的数组。
        Feature[] batch = new Feature[size];
        for (int i = 0; i < size; i++) batch[i] = normalize(features[i], quietest, maxLevelSpan, levelsKnown);

        double[] fit = new double[count];
        for (int i = 0; i < count; i++) {
            fit[i] = fitScore(batch[sources[i]], maxTempo, maxLevelSpan, quietest, levelsKnown, maxTone);
        }

        boolean[] taken = new boolean[count];
        int[] assignment = new int[count];
        for (int position = 0; position < count; position++) {
            double ideal = idealCurve(position, count);
            int best = -1;
            double bestCost = Double.MAX_VALUE;
            for (int candidate = 0; candidate < count; candidate++) {
                if (taken[candidate]) continue;
                double cost = W_FIT * Math.abs(fit[candidate] - ideal);
                if (position > 0) {
                    cost += transitionCost(batch[sources[assignment[position - 1]]],
                            batch[sources[candidate]]);
                }
                // 严格小于才换：平手取原下标更小的那一个，这是「同样输入同样顺序」的全部来源。
                if (cost < bestCost - 1e-9) {
                    bestCost = cost;
                    best = candidate;
                }
            }
            if (best < 0) break;
            taken[best] = true;
            assignment[position] = best;
        }

        for (int position = 0; position < count; position++) {
            if (!taken[assignment[position]]) continue;
            order[slots[position]] = sources[assignment[position]];
        }
        return order;
    }

    /** 一份既有顺序的总代价，与 {@link #plan} 用同一条式子——「这次排列到底有没有变好」必须可比。 */
    static double costOf(Feature[] features, int[] order) {
        if (features == null || order == null || features.length < 2) return 0.0;
        int size = features.length;
        double total = 0.0;
        int sortable = 0;
        for (Feature feature : features) if (feature != null && feature.isSortable()) sortable++;
        if (sortable < 2) return 0.0;
        double maxTempo = 0.0, maxTone = 0.0, loudest = -Double.MAX_VALUE, quietest = Double.MAX_VALUE;
        for (Feature feature : features) {
            if (feature == null || !feature.isSortable()) continue;
            maxTempo = Math.max(maxTempo, feature.tempo);
            maxTone = Math.max(maxTone, finite(feature.tone) ? feature.tone : 0.0);
            double value = levelOf(feature);
            if (Double.isFinite(value)) {
                loudest = Math.max(loudest, value);
                quietest = Math.min(quietest, value);
            }
        }
        boolean levelsKnown = loudest > quietest;
        double span = Math.max(1e-6, loudest - quietest);
        Feature[] batch = new Feature[size];
        for (int i = 0; i < size; i++) batch[i] = normalize(features[i], quietest, span, levelsKnown);
        double[] fit = new double[size];
        for (int i = 0; i < size; i++) {
            Feature feature = batch[order[i]];
            if (feature == null || !feature.isSortable()) continue;
            fit[i] = fitScore(feature, maxTempo, span, quietest, levelsKnown, maxTone);
            double ideal = idealCurve(i, sortable);
            total += W_FIT * Math.abs(fit[i] - ideal);
            if (i > 0) total += transitionCost(batch[order[i - 1]], feature);
        }
        return total;
    }

    /**
     * 把一份特征的三个电平量折到批内 0..1，其余原样保留。
     *
     * <p>{@code levelsKnown} 为假（整批一样响，或只有一个可量）时不猜：电平量直接留 NaN，
     * 让 {@link #fitScore} 与 {@link #transitionCost} 各自把这一项连同权重退出分母。</p>
     */
    private static Feature normalize(Feature source, double quietest, double span, boolean levelsKnown) {
        if (source == null) return null;
        Feature copy = new Feature();
        copy.tempo = source.tempo;
        copy.headTempo = source.headTempo;
        copy.tailTempo = source.tailTempo;
        copy.tone = source.tone;
        copy.seconds = source.seconds;
        if (levelsKnown && span > 1e-9) {
            copy.level = map(source.level, quietest, span);
            copy.headLevel = map(source.headLevel, quietest, span);
            copy.tailLevel = map(source.tailLevel, quietest, span);
        } else {
            copy.level = source.level;
            copy.headLevel = source.headLevel;
            copy.tailLevel = source.tailLevel;
        }
        return copy;
    }

    private static double map(double value, double quietest, double span) {
        if (!Double.isFinite(value)) return Double.NaN;
        return Math.max(0.0, Math.min(1.0, (value - quietest) / span));
    }

    /**
     * 三项加权，批内归一到 0..100。缺项连同权重一起退出分母。
     *
     * <p>传进来的 {@code feature} 必须是 {@link #normalize} 之后的副本——它的三个电平量
     * 已经是批内 0..1，所以这里直接取用，不再减 {@code quietest}。后两个参数只用于
     * 「这批到底有没有电平可谈」这一个判断。</p>
     */
    private static double fitScore(Feature feature, double maxTempo, double levelSpan,
                                   double quietest, boolean levelsKnown, double maxTone) {
        double total = 0.0;
        double weight = 0.0;
        if (finite(feature.tempo) && maxTempo > 0.0) {
            total += W_TEMPO * (feature.tempo / maxTempo);
            weight += W_TEMPO;
        }
        double level = levelOf(feature);
        if (levelsKnown && Double.isFinite(level)) {
            total += W_LEVEL * Math.max(0.0, Math.min(1.0, level));
            weight += W_LEVEL;
        }
        if (finite(feature.tone) && maxTone > 0.0) {
            total += W_TONE * (feature.tone / maxTone);
            weight += W_TONE;
        }
        if (weight <= 0.0) return 50.0;   // 全缺：摆在理想曲线上，不给它任何倾向
        return Math.max(0.0, Math.min(100.0, total / weight * 100.0));
    }

    /**
     * 相邻两首的接合代价。速度比 1:1 与 1:2 <b>都</b>算免费——翻倍或减半接歌是常规做法而不是失误，
     * 把它当成偏差会把整张专辑往同一个 BPM 上挤。包内可见给测试。
     */
    static double transitionCost(Feature previous, Feature next) {
        if (previous == null || next == null) return 0.0;
        double cost = 0.0;
        if (finite(previous.tempo) && finite(next.tempo) && previous.tempo > 1.0 && next.tempo > 1.0) {
            cost += W_TEMPO_STEP * octaveDeviation(next.tempo / previous.tempo) * 100.0;
        }
        double outLevel = previous.outgoingLevel();
        double inLevel = next.incomingLevel();
        if (finite(outLevel) && finite(inLevel)) {
            cost += W_LEVEL_STEP * Math.abs(inLevel - outLevel) * 50.0;
        }
        return cost;
    }

    /**
     * 把速度比折进一个八度，再量它离「整八度」有多远。
     *
     * <p>折的理由：接歌时 120→240 和 240→120 都不算偏差——把下一首按双倍速或减半接是常规做法，
     * 只有折完仍然对不上（比如 1.25 倍）才是真的接不稳。不折的话，一条从慢到快的列表会被
     * 排成「谁都不敢翻倍」，反而把最自然的那类接缝排掉了。</p>
     *
     * <p>折完落在 {@code [1,2)}，偏差取 {@code min(folded−1, 2−folded)}：整八度是 0，
     * 最差的三全音约 0.41。</p>
     */
    static double octaveDeviation(double ratio) {
        if (!Double.isFinite(ratio) || ratio <= 0.0) return 0.0;   // 比不出来就不表态
        double folded = ratio;
        while (folded >= 2.0) folded *= 0.5;
        while (folded < 1.0) folded *= 2.0;
        return Math.min(folded - 1.0, 2.0 - folded);
    }

    private static double idealCurve(int position, int count) {
        if (count < 2) return IDEAL_BASE + IDEAL_RISE * .5;
        double p = position / (double) (count - 1);
        return IDEAL_BASE + IDEAL_RISE * Math.pow(p, IDEAL_CURVE);
    }

    /** 用于归一化的「这首歌有多响」：优先整体电平，缺了就取头尾里更响的那个。 */
    private static double levelOf(Feature feature) {
        if (finite(feature.level)) return feature.level;
        double head = feature.headLevel;
        double tail = feature.tailLevel;
        if (finite(head) && finite(tail)) return Math.max(head, tail);
        if (finite(head)) return head;
        if (finite(tail)) return tail;
        return Double.NaN;
    }

    /**
     * 从<b>已有</b>缓存量出一首曲目的特征：整曲包络给电平、明亮度、时长，profile 给速度。
     *
     * <p>两者都可以缺。只有 profile 没包络时，电平与明亮度留 NaN——这首歌会参与排序，
     * 但只是按速度排；两者都没有时返回 {@code null}，它在 {@link #plan} 里就是「这一格不动」。
     * 本方法<b>不解码、不起进程、不写盘</b>：量不出来是预热的责任，不是这里的。</p>
     */
    static Feature featureOf(AutomixEnvelope envelope, FoliaTrackProfile profile) {
        if (envelope == null && profile == null) return null;
        Feature feature = new Feature();
        if (profile != null) {
            if (profile.beatConfidence >= FoliaAutomixPlanner.MIN_GRID_CONFIDENCE && profile.bpm > 1f) {
                feature.headTempo = profile.bpm;
            }
            if (profile.outroBeatConfidence >= FoliaAutomixPlanner.MIN_GRID_CONFIDENCE
                    && profile.outroBpm > 1f) {
                feature.tailTempo = profile.outroBpm;
            }
            // 主速度取尾段那个：接下一首时真正要紧的是「这一首退场时有多快」，不是它开头多快。
            feature.tempo = Double.isFinite(feature.tailTempo) ? feature.tailTempo : feature.headTempo;
        }
        if (envelope != null && envelope.isUsable()) {
            double peak = Math.max(1e-6, envelope.levelPeak);
            long duration = envelope.durationMillis;
            feature.seconds = duration / 1000.0;
            feature.level = decibels(mean(envelope.rms, 0L, duration) * peak);
            long edge = Math.min((long) (EDGE_SECONDS * 1000.0), duration / 2L);
            if (edge > 0L) {
                feature.headLevel = decibels(mean(envelope.rms, 0L, edge) * peak);
                feature.tailLevel = decibels(mean(envelope.rms, duration - edge, duration) * peak);
            }
            double low = mean(envelope.low, 0L, duration);
            double high = mean(envelope.high, 0L, duration);
            if (low + high > 1e-4) feature.tone = high / (low + high);
        }
        return feature;
    }

    /** 头尾各取这一段量电平与明亮度。 */
    private static final double EDGE_SECONDS = 15.0;

    private static double mean(byte[] values, long fromMillis, long toMillis) {
        if (values == null || values.length == 0 || toMillis <= fromMillis) return 0.0;
        int from = (int) Math.min(values.length - 1L, Math.max(0L, fromMillis / AutomixEnvelope.HOP_MILLIS));
        int to = (int) Math.min(values.length, Math.max(from + 1, toMillis / AutomixEnvelope.HOP_MILLIS));
        double sum = 0.0;
        for (int i = from; i < to; i++) sum += (values[i] & 0xFF) / 255.0;
        return sum / (to - from);
    }

    private static double decibels(double magnitude) {
        return 20.0 * Math.log10(Math.max(1e-6, magnitude));
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static int max(int a, int b) {
        return a > b ? a : b;
    }
}
