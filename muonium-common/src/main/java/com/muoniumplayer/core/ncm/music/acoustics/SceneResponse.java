package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 把一帧 {@link ListenerSample} 翻译成音频网络该呈现的完整 {@link RoomPlan}。
 *
 * <p>纯函数:输入相同输出必然相同,不持有任何状态 —— 状态机(采样节流、开关
 * 记忆)住在调用方 {@link AcousticsRuntime} 里。单独拆出来是为了能对着同一份
 * 输入做单元测试,而不是连着游戏线程一起测。</p>
 *
 * <h2>判定规则(从重到轻,可叠加)</h2>
 * <ul>
 *   <li><b>浸液</b>:频带压到极低 + 轻微压响度,湿声减半(水面对声音是一面
 *       强反射镜,但水体本身吸声凶狠,两相抵消后仍以闷为主)。</li>
 *   <li><b>房间声学(几何导出)</b>:射线摘要直接解析成混响的全部参数 ——
 *       <ul>
 *         <li><b>早期反射抽头</b>:玩家即声源,每条确认可达的反射就是一次
 *             真实回声,延迟/能量/声像原样进计划(归一上限与强度在屋里定标);</li>
 *         <li><b>预延迟</b> = 首命中均值 ÷ 声速:第一次反射什么时候到;</li>
 *         <li><b>衰减时长</b>:反射率夹进能量保留区间,算每弹保留 e 的混响弹
 *             多少次掉到 −60dB,乘弹射段均值(每弹耗时 = 路程 ÷ 声速)——
 *             石头大厅与贴羊毛的小卧室由此拉开数量级;</li>
 *         <li><b>湿声</b> = 围合 × 可达 × 材质因子,再吃 <b>音乐增益</b>:
 *             音乐的干声满幅立体声,湿声按世界音效的标定必然被掩蔽 ——
 *             实测教训(参见 docs/方案-场景混响-真抽头与Freeverb尾.md):
 *             boost ×1.3、有房间时给地板值、干声按湿声让位(最多 −28%)。</li>
 *         <li><b>音色</b>:反射率越低(软、吸声)尾巴越暗。</li>
 *       </ul>
 *       全开阔(围合度过低或无首命中)时全部静息。</li>
 *   <li><b>降水</b>:只压响度不碰频谱。雨声、雷声本来就覆盖中高频,把音乐
 *       让开一点,环境声自己就会浮上来;再压频带会闷成一团。</li>
 *   <li><b>强度档</b>:以上全部结论在「无效果」和「满效果」之间整体插值。
 *       频带按对数插(感知均匀),其余按线性插。</li>
 * </ul>
 */
public final class SceneResponse {

    private SceneResponse() {
    }

    /** 中性音色 = 静息态与强度插值的锚点(与 SceneAcoustics 的缺省一致)。 */
    public static final float NEUTRAL_TONE = 0.28f;

    public static RoomPlan respond(ListenerSample sample, float strength) {
        if (sample == null) {
            return RoomPlan.idle();
        }
        final float s = clamp01(strength);
        if (s <= 0f) {
            return RoomPlan.idle();
        }

        double band = SceneAcoustics.bandOpenHz();
        float loud = 1f;
        final boolean submerged = sample.submerged();
        if (submerged) {
            band = Math.min(band, BAND_SUBMERGED_HZ);
            loud *= SUBMERGED_LOUDNESS;
        }
        loud *= 1f - PRECIP_ATTENUATION * clamp01(sample.precipitation());

        float wet = 0f;
        float predelay = 0f;
        float decay = 0f;
        float decayLow = 0f;
        float decayHigh = 0f;
        float diffusion = 1f;
        float tone = NEUTRAL_TONE;
        int tapCount = 0;
        float[] tapDelay = EMPTY;
        float[] tapGainL = EMPTY;
        float[] tapGainR = EMPTY;

        final float enclosure = clamp01(sample.enclosure());
        final float firstHit = sample.firstHitMeanMeters();
        if (enclosure >= ENCLOSURE_FLOOR && firstHit > ListenerSample.NO_HIT) {
            final double nearest = Math.max(0.5, firstHit);
            double segment = sample.bounceSegmentMeanMeters();
            if (segment <= 0d) {
                segment = nearest; // 没有弹射段(贴脸小间)时按首命中距离兜底
            }
            float rho = sample.meanReflectivity();
            if (rho <= 0f) {
                rho = DEFAULT_REFLECTIVITY;
            }
            final float reach = clamp01(sample.reachableFraction());

            predelay = (float) Math.max(PRED_MIN_SEC,
                    Math.min(PRED_MAX_SEC, nearest / SPEED_OF_SOUND));
            // 世界声源:预延迟也是「相对直达」的时序 —— 首反射的完整旅程是
            // 源→墙→耳(nearest 只是出发段;回程以同尺度近似),减去直达路程
            // 才是「直达声之后多久,尾部的第一波开始」。远处音箱(直达 30m、
            // 首反射 10m)的预延迟因此接近 0 而不是凭空的 29ms。
            if (sample.hasDirectSource()) {
                predelay = (float) Math.max(PRED_MIN_SEC,
                        Math.min(PRED_MAX_SEC, (nearest * 2 - sample.directDistanceMeters())
                                / SPEED_OF_SOUND));
            }
            // 衰减时长:每弹能量保留 e,−60dB 所需弹射数 × 每弹耗时。
            // ρ 直接夹 [0.10,0.92] —— 早期的「低 ρ 抬升线」会把羊毛(0.1)
            // 抬到 0.485,与木头(0.6)几乎同尾,材质的吸音差异被抹平
            // (实机:羊毛房测不出吸音感)。现在有真实直方图校验,解析式
            // 忠实于材质;极短尾由 RT60_MIN_SEC 下限保底,不会「死寂」。
            final double perBounce = Math.min(DECAY_ENERGY_MAX,
                    Math.max(DECAY_ENERGY_MIN, rho));
            final double bouncesToSilence = Math.log(1e-6) / Math.log(perBounce);
            // 围合度调制尾长:敞开空间的声能从开敞面逃逸,「混响时间」物理上
            // 与封闭面积比强相关 —— 院子只有 30% 方向有墙却给 5.5s 的大厅尾
            // 是不符实际的(实机 review 发现)。0.3 围合 ≈ ×0.58,全封闭 ×1。
            final double enclosureDecayFactor = 0.4 + 0.6 * enclosure;
            decay = (float) Math.max(RT60_MIN_SEC,
                    Math.min(RT60_MAX_SEC, bouncesToSilence * segment / SPEED_OF_SOUND
                            * enclosureDecayFactor));

            // ---- 逐频带 T60:直方图拟合优先,解析式兜底 ----
            // 拟合值必须与解析式互相校验:射线弹数/射程有物理上限(4 弹 × 28m
            // ≈ 0.44s 观察窗),晚期尾在直方图里天然缺尾部,拟合读到的斜率会
            // 混入「到达密度」成分而系统性偏短。规则:拟合值 < 解析值时取两者
            // 较大(直方图只能证明「至少这么长」—— 窗内看得见的衰减斜率是
            // 真衰减的下界);拟合值 > 解析值时信任拟合(说明射线追到了真实
            // 的长尾,几何统计反而低估了)。低/高频同理。
            float lowFinal = decay;
            float highFinal = decay;
            // debug 低频倍率:DJ 鼓点混响糊 → 单独压低频尾(直方图与解析值之后统一乘)
            final float lowScale = AcousticsDebugTuning.lowDecayScale(Float.NaN) * AcousticsDebugTuning.decayScale(Float.NaN);
            final float midScale = AcousticsDebugTuning.decayScale(Float.NaN);
            final HistogramEstimator.Result hist = HistogramEstimator.estimate(sample.bandHistogram());
            if (hist != null && hist.decayMidSec > 0.0) {
                // 拟合值同样乘围合因子:露天场地的直方图尾(能量逃逸前的
                // 回声)不该按封闭房间解读。
                decay = clampDecay(Math.max(decay, hist.decayMidSec * enclosureDecayFactor)
                        * (Float.isNaN(midScale) ? 1f : midScale));
                if (hist.decayLowSec > 0.0) {
                    lowFinal = clampDecay(Math.max(decay * DECAY_LOW_FALLBACK,
                            hist.decayLowSec * enclosureDecayFactor)
                            * (Float.isNaN(lowScale) ? 1f : lowScale));
                }
                if (hist.decayHighSec > 0.0) {
                    highFinal = clampDecay(Math.max(decay * DECAY_HIGH_FALLBACK,
                            hist.decayHighSec * enclosureDecayFactor)
                            * (Float.isNaN(midScale) ? 1f : midScale));
                }
            } else {
                lowFinal = clampDecay(decay * DECAY_LOW_FALLBACK * (Float.isNaN(lowScale) ? 1f : lowScale));
                highFinal = clampDecay(decay * DECAY_HIGH_FALLBACK * (Float.isNaN(midScale) ? 1f : midScale));
            }
            decayLow = lowFinal;
            decayHigh = highFinal;
            diffusion = hist != null && hist.decayMidSec > 0.0
                    ? (float) Math.max(0.0, Math.min(1.0, hist.diffusion))
                    : 1f;

            wet = (float) Math.min(WET_CEIL, enclosure
                    * (WET_BASE + WET_REACH_SPAN * reach)
                    * wetMaterialFactor(rho)
                    * MUSIC_WET_BOOST
                    * AcousticsDebugTuning.wetBoost(1f));
            // 长尾微折减:洞穴/矿洞(T60>2.5s)的湿声轻收最多 8% —— 实机
            // 反馈大空间混响略过;小房间(T60<0.5s)完全不受影响。
            if (decay > LONG_TAIL_SOFTEN_FROM) {
                wet *= 1.0 - LONG_TAIL_SOFTEN_MAX
                        * Math.min(1.0, (decay - LONG_TAIL_SOFTEN_FROM)
                                / (RT60_MAX_SEC - LONG_TAIL_SOFTEN_FROM));
            }
            tone = TONE_MIN + TONE_SPAN * (Math.min(rho, 1.5f) / 1.5f);
            if (submerged) {
                wet *= UNDERWATER_WET_DAMP;
                // 水下混响:水体吸声极强,真实水下「混响」<0.2s(只有水面
                // 一次强反射)。尾长再砍 60%。
                decay *= UNDERWATER_DECAY_FACTOR;
                decayLow *= UNDERWATER_DECAY_FACTOR;
                decayHigh *= UNDERWATER_DECAY_FACTOR;
            }

            // ---- 早期反射抽头:定标 + 强度 + 等功率声像 ----
            final int[] picked = pickTaps(sample);
            tapCount = picked.length;
            if (tapCount > 0) {
                tapDelay = new float[tapCount];
                tapGainL = new float[tapCount];
                tapGainR = new float[tapCount];
                float maxGain = 0f;
                for (int i = 0; i < tapCount; i++) {
                    tapDelay[i] = sample.tapDelaysSec()[picked[i]];
                    maxGain = Math.max(maxGain, sample.tapGains()[picked[i]]);
                }
                // 归一:最强一条 ≤0.4(湿声总量另算,抽头只跟自己的相对能量成比例)
                final float norm = maxGain > 1e-6f ? Math.min(1f, TAP_GAIN_CEIL / maxGain) : 0f;
                // 总能量约束:密集房间可达 74-83 条反射,64 条相近能量的抽头同时
                // 叠加,单耳瞬时和可达最强条的 10-20 倍 —— DJ 音乐满幅输入下
                // ER 通路自身就能超 1.0 削波。把「单耳幅度和」约束到
                // TAP_SUM_CEIL:超出按比例整体压(等效把过密反射视为同一面
                // 墙的能量摊薄,物理上就是扩散场能量守恒)。
                float sumL = 0f;
                float sumR = 0f;
                for (int i = 0; i < tapCount; i++) {
                    final float gain = sample.tapGains()[picked[i]] * norm * s;
                    final double angle = (Math.max(-1f, Math.min(1f,
                            sample.tapPans()[picked[i]])) + 1f) * (Math.PI / 4.0);
                    sumL += (float) (gain * Math.cos(angle));
                    sumR += (float) (gain * Math.sin(angle));
                }
                final float worstSum = Math.max(sumL, sumR);
                final float sumCeil = AcousticsDebugTuning.tapSumCeil(TAP_SUM_CEIL);
                final float sumNorm = worstSum > sumCeil ? sumCeil / worstSum : 1f;
                for (int i = 0; i < tapCount; i++) {
                    final float gain = sample.tapGains()[picked[i]] * norm * s * sumNorm;
                    // 声像微抖:确定性 ±0.06,防多条反射对齐成同一个幻像点
                    final float pan = Math.max(-1f, Math.min(1f,
                            sample.tapPans()[picked[i]] + 0.06f * (float) Math.sin(picked[i] * 2.399)));
                    // 等功率律:pan −1..1 → 左右能量互补
                    final double angle = (pan + 1f) * (Math.PI / 4.0);
                    tapGainL[i] = (float) (gain * Math.cos(angle));
                    tapGainR[i] = (float) (gain * Math.sin(angle));
                }
            }

            // 强度插值:频带走对数域(见类注释),其余线性。
        }

        // 强度插值(湿声/衰减/预延迟/响度;抽头增益已在上面乘过 s)
        final double logBand = Math.log10(SceneAcoustics.bandOpenHz())
                + (Math.log10(Math.max(SceneAcoustics.bandFloorHz(), band)) - Math.log10(SceneAcoustics.bandOpenHz())) * s;
        wet *= s;
        predelay *= s;
        decay *= s;
        decayLow *= s;
        decayHigh *= s;
        final float loudFinal = 1f - (1f - loud) * s;
        // 干声让位:湿声越大干声越退(最多 DRY_DUCK),混响才不被满幅干声掩蔽。
        final float duck = 1f - DRY_DUCK_MAX * Math.min(1f, wet * 1.25f);

        // ---- 直射声链(世界声源;无声源 = 恒等直通) ----
        float directGain = 1f;
        double directCutoff = DirectPath.FC_OPEN;
        float directPan = 0f;
        float directDist = 0f;
        if (sample.hasDirectSource()) {
            directDist = Math.max(0f, sample.directDistanceMeters());
            final double occl = Math.max(0.0, sample.directOcclusion());
            directGain = (float) DirectPath.directGain(directDist, occl);
            directCutoff = DirectPath.totalCutoffHz(occl, directDist);
            directPan = DirectPath.clampPan(sample.directPan());
        }

        return new RoomPlan(Math.pow(10.0, logBand), wet, loudFinal * duck, loudFinal,
                predelay, decay, NEUTRAL_TONE + (tone - NEUTRAL_TONE) * s,
                decayLow, decayHigh, diffusion,
                tapDelay, tapGainL, tapGainR,
                directGain, directCutoff, directPan, directDist);
    }

    /**
     * 选抽头:延迟超出 ER 区间的归尾部管;能量太小的没听感意义;按能量降序
     * 截到 {@link #TAP_MAX} 条。两段式:先线性扫一遍收集全部候选(密集房间
     * 实测 74-83 条,超过容量),用「当前最小值让位」的选择式保留 top-N ——
     * 后到的强反射必须能挤掉先到的弱反射;最后对入选者插入排序按能量降序。
     */
    private static int[] pickTaps(ListenerSample sample) {
        final int total = Math.min(Math.min(sample.tapCount(),
                sample.tapDelaysSec().length), Math.min(sample.tapGains().length, sample.tapPans().length));
        if (total == 0) {
            return EMPTY_INDEX;
        }
        final float[] gains = sample.tapGains();
        final float[] delays = sample.tapDelaysSec();
        int[] idx = new int[Math.min(TAP_MAX, total)];
        int n = 0;
        int weakest = -1; // 入选者里能量最小者的下标(idx 数组下标)
        for (int i = 0; i < total; i++) {
            if (delays[i] > ER_MAX_SEC || gains[i] <= TAP_GAIN_FLOOR) {
                continue;
            }
            if (n < idx.length) {
                idx[n++] = i;
                if (weakest < 0 || gains[i] < gains[idx[weakest]]) {
                    weakest = n - 1;
                }
            } else if (gains[i] > gains[idx[weakest]]) {
                // 满员:强反射挤掉当前最弱者,再重找最弱
                idx[weakest] = i;
                weakest = 0;
                for (int k = 1; k < n; k++) {
                    if (gains[idx[k]] < gains[idx[weakest]]) {
                        weakest = k;
                    }
                }
            }
        }
        // 插入排序按能量降序(≤64 条)
        for (int a = 1; a < n; a++) {
            final int cur = idx[a];
            int b = a - 1;
            while (b >= 0 && gains[idx[b]] < gains[cur]) {
                idx[b + 1] = idx[b];
                b--;
            }
            idx[b + 1] = cur;
        }
        if (n == idx.length) {
            return idx;
        }
        final int[] trimmed = new int[n];
        System.arraycopy(idx, 0, trimmed, 0, n);
        return trimmed;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 直方图拟合出的 T60 夹进与解析式同一量程(静息语义仍由 0 表示)。 */
    private static float clampDecay(double sec) {
        return (float) Math.max(RT60_MIN_SEC, Math.min(RT60_MAX_SEC, sec));
    }

    private static final float[] EMPTY = new float[0];
    private static final int[] EMPTY_INDEX = new int[0];

    // ---- 判定档位 ----

    /** 浸液时的频带上限:隔水只剩闷响。 */
    private static final double BAND_SUBMERGED_HZ = 420.0;
    /** 浸液时的响度系数。 */
    private static final float SUBMERGED_LOUDNESS = 0.82f;
    /** 满级降水的响度让位比例。 */
    private static final float PRECIP_ATTENUATION = 0.28f;
    /** 浸液时湿声折减:水镜面反射强,但水体吸声更凶。 */
    private static final float UNDERWATER_WET_DAMP = 0.5f;
    /** 浸液时尾长折减:真实水下混响 <0.2s。 */
    private static final double UNDERWATER_DECAY_FACTOR = 0.4;

    /** 围合度低于此值视为旷野:零星一两方向有墙不足以成房间。 */
    private static final float ENCLOSURE_FLOOR = 0.10f;
    /** 声速(米/秒):几何量 → 时间的唯一换算。 */
    private static final double SPEED_OF_SOUND = 343.0;

    /** 衰减时长估算:反射率夹进能量保留区间(软面下限 / 硬面上限)。 */
    private static final double DECAY_ENERGY_MIN = 0.10;
    private static final double DECAY_ENERGY_MAX = 0.92;
    /** 低反射率端抬升:e = 0.45+0.35ρ 封 0.88 —— 小房间余音从「录音棚」回到「卧室」。 */
    private static final double RT60_MIN_SEC = 0.15;
    /** T60 上限 5.5s:实测洞穴拟合 4.1s 贴着旧上限 4.5,余量不足;
     *  5.5 给真实大厅/sub-bass 尾留空间。 */
    private static final double RT60_MAX_SEC = 5.5;
    /** 直方图拟合失败时的频带兜底偏置:低频 ×1.35 留得久,高频 ×0.8 被吃得快。 */
    /** 长尾湿声微折减:decay 超过 3.0s 起线性收,5.5s 处收满 8%
     *  (起点随上限放宽后移,保持『只砍最长的尾』的本意)。 */
    private static final double LONG_TAIL_SOFTEN_FROM = 3.0;
    private static final double LONG_TAIL_SOFTEN_MAX = 0.08;
    /** 直方图拟合失败时的频带兜底偏置:高频 ×0.8 被吃得快;低频 ×1.0
     *  —— 原值 1.35 在 DJ/劲爆曲上把底鼓尾巴拉长一倍(轰头+节拍冲击),
     *  实机调参定稿 1.0:低频尾与中频等长,长尾氛围靠 FDN 的低 shelf
     *  (relLow)表达,不再额外偏置。 */
    private static final double DECAY_LOW_FALLBACK = 1.0;
    private static final double DECAY_HIGH_FALLBACK = 0.8;

    /** 湿声 = 围合 ×(基础 + 可达比例项)× 材质因子,上限保网络动态余量。 */
    private static final float WET_BASE = 0.17f;
    private static final float WET_REACH_SPAN = 0.72f;
    /** 湿声材质因子:指数曲线(ρ^1.8 归一)拉开吸音档次 —— 羊毛(ρ=0.1)
     *  ×0.24、木头(0.6)×0.69、石头(1.2)×1.0,「吸音感」可闻;
     *  原线性 0.35+0.65ρ 的羊毛/木头比只有 1.7 倍,听不出。 */
    private static double wetMaterialFactor(float rho) {
        final double rhoN = Math.min(rho, 1.2f) / 1.2f;
        return Math.min(1.0, 0.18 + 0.82 * Math.pow(rhoN, 1.8));
    }
    private static final float WET_CEIL = 0.95f;

    /** 音乐专属:干声满幅立体声会掩蔽世界音效量级的湿声,整体抬高(实测教训)。 */
    private static final float MUSIC_WET_BOOST = 1.3f;
    /** 干声让位上限:湿声拉满时干声退到 0.72。 */
    private static final float DRY_DUCK_MAX = 0.28f;

    /** 预延迟上下限:再近的墙也要留出可闻间隔,再远的墙交给衰减时长表达。 */
    private static final double PRED_MIN_SEC = 0.004;
    private static final double PRED_MAX_SEC = 0.055;

    /** 音色(梳内阻尼系数)随反射率的量程:软面暗、硬面亮。 */
    private static final float TONE_MIN = 0.12f;
    private static final float TONE_SPAN = 0.30f;
    /** 射线一个都没带反射率时的兜底(普通砖石混砌)。 */
    private static final float DEFAULT_REFLECTIVITY = 0.5f;

    // ---- 早期反射抽头 ----

    /** 抽头上限(超出按能量截断):实机日志显示密集房间可测到 74-83 条可达反射,
     *  只交付 24 条会肉眼可见地「稀」—— 早声场密度就是细腻感的本体。 */
    private static final int TAP_MAX = 64;
    /** 归一后最强抽头的能量上限:单条太响是「点射/slap」,摊薄到很多条中弱反射
     *  才是真实小房间的样子。 */
    private static final float TAP_GAIN_CEIL = 0.22f;
    /** 单耳抽头幅度和上限:密集房间几十条相近抽头同时叠加会周期性超 1.0
     *  (DJ 满幅音乐下直接削波炸耳);超出整体等比压缩 —— 扩散场能量守恒。
     *  1.1 = 原 0.9 × 1.22:小空间的混响密度感主要来自早反射能量和,
     *  提升让 ER 通路能量 ×1.49(小空间听感「更差」的同源修复);
     *  逐样本软限幅兜住合成后的峰值。 */
    private static final float TAP_SUM_CEIL = 1.1f;
    /** 抽头延迟上限:更晚的反射归尾部管。 */
    private static final float ER_MAX_SEC = 0.14f;
    /** 低于此能量的抽头直接丢弃。 */
    private static final float TAP_GAIN_FLOOR = 1e-4f;
}
