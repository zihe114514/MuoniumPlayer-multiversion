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
            // 衰减时长:每弹保留 e(反射率夹进物理区间),−60dB 所需弹射数 × 每弹耗时
            final double perBounce = Math.min(DECAY_ENERGY_MAX, Math.max(DECAY_ENERGY_MIN, rho));
            final double bouncesToSilence = Math.log(1e-6) / Math.log(perBounce);
            decay = (float) Math.max(RT60_MIN_SEC,
                    Math.min(RT60_MAX_SEC, bouncesToSilence * segment / SPEED_OF_SOUND));

            wet = (float) Math.min(WET_CEIL, enclosure
                    * (WET_BASE + WET_REACH_SPAN * reach)
                    * (WET_MATERIAL_FLOOR + WET_MATERIAL_SPAN * Math.min(rho, 1.2f) / 1.2f)
                    * MUSIC_WET_BOOST);
            tone = TONE_MIN + TONE_SPAN * (Math.min(rho, 1.5f) / 1.5f);
            if (submerged) {
                wet *= UNDERWATER_WET_DAMP;
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
                for (int i = 0; i < tapCount; i++) {
                    final float gain = sample.tapGains()[picked[i]] * norm * s;
                    final float pan = Math.max(-1f, Math.min(1f, sample.tapPans()[picked[i]]));
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
        final float loudFinal = 1f - (1f - loud) * s;
        // 干声让位:湿声越大干声越退(最多 DRY_DUCK),混响才不被满幅干声掩蔽。
        final float duck = 1f - DRY_DUCK_MAX * Math.min(1f, wet * 1.25f);
        return new RoomPlan(Math.pow(10.0, logBand), wet, loudFinal * duck, loudFinal,
                predelay, decay, NEUTRAL_TONE + (tone - NEUTRAL_TONE) * s,
                tapDelay, tapGainL, tapGainR);
    }

    /**
     * 选抽头:延迟超出 ER 区间的归尾部管;能量太小的没听感意义;按能量降序
     * 截到 {@link #TAP_MAX} 条。选择排序式的部分排序,条数小,不值得整排。
     */
    private static int[] pickTaps(ListenerSample sample) {
        final int total = Math.min(Math.min(sample.tapCount(),
                sample.tapDelaysSec().length), Math.min(sample.tapGains().length, sample.tapPans().length));
        if (total == 0) {
            return EMPTY_INDEX;
        }
        int[] idx = new int[Math.min(TAP_MAX, total)];
        int n = 0;
        for (int i = 0; i < total && n < TAP_MAX; i++) {
            if (sample.tapDelaysSec()[i] <= ER_MAX_SEC && sample.tapGains()[i] > TAP_GAIN_FLOOR) {
                idx[n++] = i;
            }
        }
        // 插入排序按能量降序(已截到 ≤24 条)
        for (int a = 1; a < n; a++) {
            final int cur = idx[a];
            int b = a - 1;
            while (b >= 0 && sample.tapGains()[idx[b]] < sample.tapGains()[cur]) {
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

    private static final float[] EMPTY = new float[0];
    private static final int[] EMPTY_INDEX = new int[0];

    // ---- 判定档位 ----

    /** 浸液时的频带上限:隔水只剩闷响。 */
    private static final double BAND_SUBMERGED_HZ = 420.0;
    /** 浸液时的响度系数。 */
    private static final float SUBMERGED_LOUDNESS = 0.82f;
    /** 满级降水的响度让位比例。 */
    private static final float PRECIP_ATTENUATION = 0.22f;
    /** 浸液时湿声折减:水镜面反射强,但水体吸声更凶。 */
    private static final float UNDERWATER_WET_DAMP = 0.5f;

    /** 围合度低于此值视为旷野:零星一两方向有墙不足以成房间。 */
    private static final float ENCLOSURE_FLOOR = 0.10f;
    /** 声速(米/秒):几何量 → 时间的唯一换算。 */
    private static final double SPEED_OF_SOUND = 343.0;

    /** 衰减时长估算:反射率夹进能量保留区间(软面下限 / 硬面上限)。 */
    private static final double DECAY_ENERGY_MIN = 0.10;
    private static final double DECAY_ENERGY_MAX = 0.92;
    private static final double RT60_MIN_SEC = 0.15;
    private static final double RT60_MAX_SEC = 4.5;

    /** 湿声 = 围合 ×(基础 + 可达比例项)× 材质因子,上限保网络动态余量。 */
    private static final float WET_BASE = 0.13f;
    private static final float WET_REACH_SPAN = 0.72f;
    private static final float WET_MATERIAL_FLOOR = 0.35f;
    private static final float WET_MATERIAL_SPAN = 0.65f;
    private static final float WET_CEIL = 0.85f;

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

    /** 抽头上限(超出按能量截断)。 */
    private static final int TAP_MAX = 24;
    /** 抽头延迟上限:更晚的反射归尾部管。 */
    private static final float ER_MAX_SEC = 0.14f;
    /** 归一后最强抽头的能量上限(防单面墙炸响)。 */
    private static final float TAP_GAIN_CEIL = 0.4f;
    /** 低于此能量的抽头直接丢弃。 */
    private static final float TAP_GAIN_FLOOR = 1e-4f;
}
