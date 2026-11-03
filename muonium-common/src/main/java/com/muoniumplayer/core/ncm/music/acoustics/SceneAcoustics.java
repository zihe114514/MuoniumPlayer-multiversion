package com.muoniumplayer.core.ncm.music.acoustics;

import com.muoniumplayer.core.ncm.music.audio.OpenAlReverbBus;

/**
 * 场景声学:按听者所处的声学环境实时修饰播放输出。全部效果在音频线程上对
 * 交错的 PCM 帧原地完成。
 *
 * <h2>网络(房间 = 早期反射 + 晚期尾)</h2>
 * <ul>
 *   <li><b>早期反射 = 真抽头,双耳双环</b>:玩家即声源,射线确认可达的每条
 *       反射是一次真实回声 —— 延迟/能量/声像由 {@link RoomPlan} 交付。左右耳
 *       各一条环形延迟线,抽头按「基准延迟 ± pan·ITD」分别读:耳间时间差
 *       (最大约 0.65ms ≈ 28 样 @44.1k)是侧向反射方向感的生理学主线索
 *       (先声效应,Wallach 1949),能量差(ILD)是辅线索。逐抽头空气吸收
 *       低通保留:远墙回声更暗更柔。抽头能量逐缓冲指数平滑(此前是原位瞬替,
 *       房间微变时能量台阶感)。</li>
 *   <li><b>晚期尾 = 8×8 FDN</b>({@link FdnTail}):逐频带 T60 直驱的物理尾,
 *       替换 Freeverb;低频慢衰减带删除 —— 低频留得久这件事由 T60(low)
 *       表达,不再是「另一套滤波器」。</li>
 *   <li><b>湿声入口直流阻断</b>:回授网络对直流的增益是 1/(1−g),一点直流
 *       偏置就会撑爆内部动态;进网络前先过一节一阶高通(≈10Hz)。</li>
 *   <li><b>干声让位</b>:湿声大时干声按计划压低(duck),混响才不被满幅干声
 *       掩蔽;整体响度(浸液/降水)最后统一乘。</li>
 *   <li><b>直达声链(世界声源)</b>:玩家把「虚拟音箱」放到世界里时,干声
 *       不再等于「贴脸」—— M/S 等功率分解:M 分量(定位)按直达增益衰减、
 *       过动态低通(遮挡/空气吸收的闷)、过双耳小环加真 ITD;S 分量(立体声
 *       宽度)原样保留。pan=0 且满增益且截止全开时逐样本恒等 —— 无声源
 *       零染色。直达声零延迟,反射抽头的时序由树侧按「全路径 − 直达路程」
 *       相对化(见 {@link ListenerSample#hasDirectSource()})。</li>
 *   <li><b>频带上限</b>:每声道一条双二阶低通(RBJ,Q=1/&radic;2),截止在
 *       对数域滑移 —— 浸液那条链,与混响无关。</li>
 * </ul>
 *
 * <h2>目标/现状分离与实时纪律</h2>
 * <p>游戏线程只通过 {@link #applyTarget} 交付不可变的 {@link RoomPlan}(volatile
 * 引用交接,无锁);音频线程每个缓冲朝目标收敛一次。{@link #render} 稳态零分配、
 * 零锁;延迟线只在采样率变化时重建。全部静息时整个方法提前返回。</p>
 */
public final class SceneAcoustics {

    // ---- 调音常量(前置:字段初始化要读它们) ----

    /** 静息态的频带上限:高于它的采样率一半,等同「不压」。 */
    private static final double BAND_OPEN_HZ = 20000.0;
    /** 目标频带的下限:再低就纯粹是轰隆声了,到此为止。 */
    private static final double MIN_BAND_HZ = 120.0;
    private static final double LOG_BAND_OPEN = Math.log10(BAND_OPEN_HZ);

    /** 参数收敛时间常数(秒)。频带慢一点听感自然,其余共用稍快的一档。 */
    private static final double BAND_TAU_SEC = 0.22;
    private static final double WET_TAU_SEC = 0.30;
    /** 抽头能量收敛时间常数(秒):比参数快,慢于房间变化,杜绝能量台阶。 */
    private static final double TAP_GAIN_TAU_SEC = 0.12;

    /** 湿声低于此值视为关:省掉整条网络。 */
    private static final float WET_FLOOR = 0.003f;
    private static final double BUTTERWORTH_Q = 0.7071067811865476;

    /** 空气吸收:高频随路程指数衰减 —— 远墙的回声更暗更柔,近墙保持清晰。 */
    private static final double AIR_FREE_METERS = 6.0;
    private static final double AIR_DECAY_METERS = 16.0;
    private static final double AIR_FC_REF_HZ = 18000.0;
    private static final double AIR_FC_MIN_HZ = 500.0;
    private static final double SPEED_OF_SOUND = 343.0;

    /** 真 ITD:耳间最大时间差(秒)→ 样本数按采样率折。0.65ms 是人头半径
     *  8.75cm 对应的生理上限;pan 全偏时达到,中间线性内插。 */
    private static final double ITD_MAX_SEC = 0.00065;

    /** 软限幅拐点:低于它线性直通。0.89 留 1dB 余量给声卡前的重采样器;
     *  loudness(浸液/降水)按比例缩它 —— 目标量整体变小时限幅跟着退让。 */
    private static final double LIMITER_KNEE = 0.89;

    /**
     * 逐样本软限幅:拐点内线性直通(高保真区),越界平滑压缩。有理式
     * y = knee + (1−knee)·u/(1+u)(u = |x|−knee):值有界(<1)、函数值在拐点
     * 连续(无咔哒),幅度永不超 1;负半轴精确镜像,不引入偶次失真与直流。
     * 只在 wetActive 的样本上调用,静息直通路径完全不经过这里。
     */
    private static float softLimit(double x, float knee) {
        if (x <= knee) {
            if (x >= -knee) {
                return (float) x;
            }
            final double over = -x - knee;
            return (float) -(knee + (1.0 - knee) * over / (1.0 + over));
        }
        final double over = x - knee;
        return (float) (knee + (1.0 - knee) * over / (1.0 + over));
    }

    /** 预延迟上限(毫秒):更远的「墙」交给衰减时长表达。 */
    private static final double PRE_DELAY_MAX_MS = 60.0;
    /** 早期反射抽头窗口(秒):更晚的反射归尾部管。 */
    private static final double ER_WINDOW_SEC = 0.16;
    /** 抽头组交叉淡化时长(秒):延迟位置变化(移动/换房间)的过渡。 */
    private static final double TAP_FADE_SEC = 0.4;
    /** FDN 尾输出的能量补偿:输出抽法(4 线和)与注入量的综合折算。
     *  2.6 = 原 2.2 × 1.18:配合注入量提升,把尾部 RMS 抬到与早反射相当
     *  (实测湿/干能量比 0.206 → ~0.7,强混响区间;软限幅兜住峰值)。 */
    private static final double TAIL_MAKEUP = 2.6;

    // ---- 目标量(采样线程写,音频线程读) ----

    private static volatile double targetLogBand = LOG_BAND_OPEN;
    private static volatile float targetWet = 0f;
    private static volatile float targetLoudness = 1f;
    private static volatile float targetDryGain = 1f;
    private static volatile float targetPreDelaySec = 0f;
    private static volatile float targetDecaySec = 0f;
    private static volatile float targetDecayLowSec = 0f;
    private static volatile float targetDecayHighSec = 0f;
    private static volatile float targetTone = 0.28f;
    /** 直达声链目标量(世界声源;gain=1/fc=open/pan=0 = 恒等直通)。 */
    private static volatile float targetDirectGain = 1f;
    private static volatile double targetDirectCutoff = DirectPath.FC_OPEN;
    private static volatile float targetDirectPan = 0f;
    /** 不可变房间计划(volatile 引用交接);抽头延迟/能量/声像的真来源。 */
    private static volatile RoomPlan targetPlan = RoomPlan.idle();
    /** 换曲/seek 的清态请求(volatile 标志;音频线程缓冲头消费,见 {@link #flush()})。 */
    private static volatile boolean flushRequested;

    // ---- 现状(仅音频线程触碰) ----

    private static double logBand = LOG_BAND_OPEN;
    private static float wet = 0f;
    private static float loudness = 1f;
    private static float dryGain = 1f;
    private static float preDelaySec;
    private static float decaySec;
    private static float decayLowSec;
    private static float decayHighSec;
    private static float tone = 0.28f;
    /** 直达声链现状(仅音频线程触碰)。 */
    private static float directGain = 1f;
    private static float directCutoff = (float) DirectPath.FC_OPEN;
    private static float directPan;

    private static int ownedRate = -1;
    private static double lastCoefBand = -1.0;
    /** 一次性诊断:「声场生效」只在静息 → 激活的跳变时打一条(回静息复位)。 */
    private static boolean activeLogged;

    /** 每声道一条低通;状态 [ch][x1,x2,y1,y2]。 */
    private static double[][] bandState = new double[0][];
    private static double cB0, cB1, cB2, cA1, cA2;

    /** 湿声入口直流阻断的记忆位(左右通道各一组 —— 立体声喂环后两耳内容不同)。 */
    private static double dcXL, dcYL, dcXR, dcYR;

    /** 中频人声保护:尾输入里 0.8-4kHz 段的提取带通(二阶带通)与其压制比。 */
    private static final double MID_PROTECT_RATIO = 0.45; // ≈ -7dB 轻压
    private static double midBPB0, midBPB1, midBPB2, midBPA1, midBPA2;
    private static double midBPX1, midBPX2, midBPY1, midBPY2;

    /** 湿声输出低频搁架:150Hz 以下压到 KEEP 倍(-7.5dB ⇒ 0.42),
     *  鼓的低频反射退场(低音体感由干声承担;ER+尾统一压)。 */
    private static final double WET_SHELF_HZ = 150.0;
    private static final double WET_SHELF_KEEP = 0.42;
    private static double shelfB0; // 一阶低通系数
    private static double shelfXL1, shelfYL1;
    private static double shelfXR1, shelfYR1;

    /** 节拍侧链:低频包络(二阶低通 @110Hz)+ 滑动最大值 hold(openal-soft     *  SlidingHold 同款)—— 瞬态到达瞬间压、hold 住、瞬间放。双声道检测取
     *  max(|L|,|R|)(单侧瞬态不再被 mono 平均稀释)。 */
    private static final double SIDECHAIN_LP_HZ = 110.0;
    private static final float SIDECHAIN_DUCK = 0.55f;    // 最低压到 0.7(-3.1dB):防冲击的主力已是低切+搁架,侧链只做轻呼吸
    private static final float SIDECHAIN_ATTACK_SEC = 0.005f; // 5ms 快起
    private static final float SIDECHAIN_RELEASE_SEC = 0.35f; // 180ms 慢放:DJ 拍间隔 250ms,两拍间要能放回 0.95+,否则持续 duck 叠成 -6dB
    /** 侧链触发阈(包络平方):底鼓 RMS ≈0.2(约 -14dBFS)以上才触发。 */
    private static final double SC_ENV_THRESHOLD = 0.04;
    /** 上升率阈(包络平方/样本):底鼓攻击沿 50ms 从 0 冲到 0.3 ⇒
     *  ~0.1/1102 样 ≈ 9e-5;放宽到 3e-5 捕捉更缓的鼓沿。 */
    private static final double SC_RISE_PER_SAMPLE = 3e-5;
    private static double scEnvPrev;
    private static double scLpX1, scLpY1;                 // 包络低通状态(L)
    private static double scLpX1R, scLpY1R;               // 包络低通状态(R)
    private static double scLpCoef;                       // 低通系数(rebuild 算)
    private static float sidechainGain = 1f;              // 当前 duck 增益(音频线程)

    /** 直达声链(世界声源):M 分量的一阶低通(动态系数,逐帧整定)+ 双耳
     *  小环(真 ITD;M 是 mono,一条环配左右读位即可,不必两条)。 */
    private static final int DIRECT_RING_SEC_SAMPLES = 96; // 容量余量:ITD 上限 28 样,取 3 倍余
    private static float[] directRing = new float[0];
    private static int directPos;
    private static double directLpCoef;                   // 一阶低通系数(逐帧按目标 fc 整定)
    private static double directLpState;
    private static int directItdMax;
    /** 直达静息阈:gain≥此值且截止全开 → 整条链逐样本直通(零开销)。 */
    private static final float DIRECT_IDLE_GAIN = 0.999f;

    // ---- OpenAL EAX Reverb 总线(世界声源的湿声;音频线程独占) ----
    /** 总线探测每缓冲一次;null/失败即 false,湿声走 FDN 兜底。 */
    private static boolean useEaxBus;
    private static double speakerX, speakerY, speakerZ;
    private static short[] busMono = new short[0];
    private static float[] busOut = new float[0];
    private static short[] busSide = new short[0];
    /** 听者位姿(游戏线程写,音频线程读;AL 的 listener 需要)。 */
    private static volatile double listenerX, listenerY, listenerZ;
    private static volatile float listenerFwdX, listenerFwdY, listenerFwdZ;
    private static volatile float listenerUpX, listenerUpY, listenerUpZ;

    /** 游戏线程每缓冲投递听者位姿(树侧从玩家眼位与视角算)。 */
    public static void setListenerPose(double ex, double ey, double ez,
                                       float fx, float fy, float fz,
                                       float ux, float uy, float uz) {
        listenerX = ex;
        listenerY = ey;
        listenerZ = ez;
        listenerFwdX = fx;
        listenerFwdY = fy;
        listenerFwdZ = fz;
        listenerUpX = ux;
        listenerUpY = uy;
        listenerUpZ = uz;
    }

    /** double → PCM16(与 OpenALAudioDevice.write 同一条转换公式)。 */
    private static short toPcm16(double v) {
        double x = v * 32767.0 + 32768.5;
        int i = ((int) x) - 32768;
        if (i > 32767) {
            i = 32767;
        } else if (i < -32768) {
            i = -32768;
        }
        return (short) i;
    }

    /** 尾部预延迟线(可变读距)。 */
    private static float[] preDelayBuf = new float[0];
    private static int preDelayPos;
    private static int preDelaySamples;

    /** 早期反射环:左右耳各一条(真 ITD 的载体);mono 写入,双耳各按 ITD 读。 */
    private static float[] erRingL = new float[0];
    private static float[] erRingR = new float[0];
    private static int erPos;
    /** ITD 上限(样本):环容量按「ER 窗 + 2×ITD 上限」留余。 */
    private static int itdMaxSamples;

    /** 抽头组容量:与推导侧的截断上限同级;预分配,稳态零运行时分配。 */
    private static final int TAP_CAPACITY = 96;
    /** 抽头组 A(生效)与 B(淡入中):基准位置(样本)+ 逐抽头声像。 */
    private static final int[] tapPosA = new int[TAP_CAPACITY];
    private static final float[] tapPanA = new float[TAP_CAPACITY];
    private static final float[] tapGLA = new float[TAP_CAPACITY];
    private static final float[] tapGRA = new float[TAP_CAPACITY];
    /** 每抽头空气吸收低通:系数随路程导出;左右耳状态分开(读位差 ITD 样本,
     *  同一低通吃两耳各自的滤波历史)。 */
    private static final float[] tapAirCoefA = new float[TAP_CAPACITY];
    private static final float[] tapAirStateLA = new float[TAP_CAPACITY];
    private static final float[] tapAirStateRA = new float[TAP_CAPACITY];
    private static int tapCountA;
    private static final int[] tapPosB = new int[TAP_CAPACITY];
    private static final float[] tapPanB = new float[TAP_CAPACITY];
    private static final float[] tapGLB = new float[TAP_CAPACITY];
    private static final float[] tapGRB = new float[TAP_CAPACITY];
    private static final float[] tapAirCoefB = new float[TAP_CAPACITY];
    private static final float[] tapAirStateLB = new float[TAP_CAPACITY];
    private static final float[] tapAirStateRB = new float[TAP_CAPACITY];
    private static int tapCountB;
    private static boolean tapFading;
    private static float tapFadeW = 1f;
    private static RoomPlan lastPlanRef;

    /** 晚期尾:8×8 FDN(逐频带 T60 直驱)。 */
    private static final FdnTail fdn = new FdnTail();
    /** FDN 双耳输出的小容器(每帧复用)。 */
    private static final float[] fdnOut = new float[2];

    private SceneAcoustics() {
    }

    // ════════════════════════ 采样线程侧 ════════════════════════

    /** 交付本轮房间计划(不可变;null 按静息处理)。 */
    public static void applyTarget(RoomPlan plan) {
        final RoomPlan safe = plan == null ? RoomPlan.idle() : plan;
        targetLogBand = safe.bandHz >= BAND_OPEN_HZ
                ? LOG_BAND_OPEN
                : Math.log10(Math.max(MIN_BAND_HZ, Math.min(BAND_OPEN_HZ, safe.bandHz)));
        targetWet = clamp01(safe.wet);
        targetLoudness = clamp01(safe.loudness);
        targetDryGain = clamp01(safe.dryGain);
        targetPreDelaySec = Math.max(0f, Math.min((float) (PRE_DELAY_MAX_MS / 1000.0), safe.predelaySec));
        targetDecaySec = Math.max(0f, Math.min(5.6f, safe.decaySec));
        targetDecayLowSec = Math.max(0f, Math.min(5.6f, safe.decayLowSec));
        targetDecayHighSec = Math.max(0f, Math.min(5.6f, safe.decayHighSec));
        targetTone = (float) Math.max(0.10, Math.min(0.45, safe.tone));
        targetDirectGain = clamp01(safe.directGain);
        targetDirectCutoff = Double.isNaN(safe.directCutoffHz)
                ? DirectPath.FC_OPEN
                : Math.max(200.0, Math.min(DirectPath.FC_OPEN, safe.directCutoffHz));
        targetDirectPan = DirectPath.clampPan(safe.directPan);
        targetPlan = safe;
    }

    /** 全部目标回到静息态(关掉、不在世界、配置为空)。 */
    public static void clearTarget() {
        applyTarget(RoomPlan.idle());
    }

    /** 曲目切换 / 拖动进度:请求清空全部滤波与延迟状态。
     *
     *  <p><b>线程契约</b>:本方法可能在播放控制/UI 线程被调,绝不直接清音频
     *  线程正在读写的状态(那是一份真实的 JMM 数据竞争)—— 只置一个 volatile
     *  标志,音频线程下一个缓冲开头消费它执行 {@link #doFlush()}。代价是
     *  换曲后最多一个缓冲(≈3ms)的余音延迟,换来的是零竞争。</p> */
    public static void flush() {
        flushRequested = true;
    }

    /** 音频线程缓冲头消费 flush 请求(仅音频线程调用)。 */
    private static void doFlush() {
        logBand = targetLogBand;
        wet = targetWet;
        loudness = targetLoudness;
        dryGain = targetDryGain;
        preDelaySec = targetPreDelaySec;
        decaySec = targetDecaySec;
        decayLowSec = targetDecayLowSec;
        decayHighSec = targetDecayHighSec;
        tone = targetTone;
        directGain = targetDirectGain;
        directCutoff = (float) targetDirectCutoff;
        directPan = targetDirectPan;
        directLpState = 0.0;
        if (directRing.length > 0) java.util.Arrays.fill(directRing, 0f);
        for (double[] ch : bandState) java.util.Arrays.fill(ch, 0.0);
        if (preDelayBuf.length > 0) java.util.Arrays.fill(preDelayBuf, 0f);
        if (erRingL.length > 0) java.util.Arrays.fill(erRingL, 0f);
        if (erRingR.length > 0) java.util.Arrays.fill(erRingR, 0f);
        java.util.Arrays.fill(tapAirStateLA, 0f);
        java.util.Arrays.fill(tapAirStateRA, 0f);
        java.util.Arrays.fill(tapAirStateLB, 0f);
        java.util.Arrays.fill(tapAirStateRB, 0f);
        preDelayPos = 0;
        erPos = 0;
        dcXL = dcYL = dcXR = dcYR = 0.0;
        midBPX1 = midBPX2 = midBPY1 = midBPY2 = 0.0;
        shelfXL1 = shelfYL1 = 0.0;
        shelfXR1 = shelfYR1 = 0.0;
        scLpX1 = scLpY1 = 0.0;
        scEnvPrev = 0.0;
        sidechainGain = 1f;
        lastPlanRef = null;
        tapCountA = 0;
        tapCountB = 0;
        tapFading = false;
        tapFadeW = 1f;
        lastCoefBand = -1.0;
        fdn.flush();
    }

    // ════════════════════════ 音频线程侧 ════════════════════════

    /** 处理一个输出缓冲;湿声送前两声道,其余声道只吃响度。 */
    public static void render(double[] interleaved, int channels, int sampleRate) {
        if (interleaved == null || channels <= 0 || interleaved.length < channels) {
            return;
        }
        final int ch = Math.min(channels, 2);
        final int frames = interleaved.length / channels;
        if (frames <= 0) {
            return;
        }
        // 换曲/seek 的清态请求:缓冲头消费(音频线程独占执行,零竞争)。
        if (flushRequested) {
            flushRequested = false;
            doFlush();
        }

        final double tLogBand = targetLogBand;
        final float tWet = targetWet;
        final float tLoudness = targetLoudness;
        final float tDry = targetDryGain;
        final float tPreDelay = targetPreDelaySec;
        final float tDecay = targetDecaySec;
        final float tDecayLow = targetDecayLowSec;
        final float tDecayHigh = targetDecayHighSec;
        final float tTone = targetTone;
        final float tDirectGain = targetDirectGain;
        final float tDirectCutoff = (float) targetDirectCutoff;
        final float tDirectPan = targetDirectPan;

        final double bandStep = 1.0 - Math.exp(-frames / (sampleRate * BAND_TAU_SEC));
        final float wetStep = (float) (1.0 - Math.exp(-frames / (sampleRate * WET_TAU_SEC)));
        logBand += (tLogBand - logBand) * bandStep;
        wet += (tWet - wet) * wetStep;
        loudness += (tLoudness - loudness) * wetStep;
        dryGain += (tDry - dryGain) * wetStep;
        preDelaySec += (tPreDelay - preDelaySec) * wetStep;
        decaySec += (tDecay - decaySec) * wetStep;
        decayLowSec += (tDecayLow - decayLowSec) * wetStep;
        decayHighSec += (tDecayHigh - decayHighSec) * wetStep;
        tone += (tTone - tone) * wetStep;
        directGain += (tDirectGain - directGain) * wetStep;
        directCutoff += (tDirectCutoff - directCutoff) * wetStep;
        directPan += (tDirectPan - directPan) * wetStep;

        final boolean bandActive = logBand < LOG_BAND_OPEN - 0.001;
        final boolean wetActive = wet > WET_FLOOR;
        final boolean loudActive = loudness < 0.999f || dryGain < 0.999f;
        final boolean directActive = directGain < DIRECT_IDLE_GAIN
                || directCutoff < DirectPath.FC_OPEN - 1.0
                || Math.abs(directPan) > 0.001f;
        if (!bandActive && !wetActive && !loudActive && !directActive) {
            activeLogged = false; // 回到静息:下次再生效时允许再报一条。
            return;
        }
        if (!activeLogged) {
            activeLogged = true;
            System.out.println("[MuoniumPlayer/Acoustics] 声场生效: " + targetPlan);
        }

        if (sampleRate != ownedRate) {
            rebuild(sampleRate, ch);
        }
        if (bandActive && logBand != lastCoefBand) {
            designLowpass(Math.pow(10.0, logBand), sampleRate);
            lastCoefBand = logBand;
        }

        preDelaySamples = Math.min(preDelayBuf.length - 1, (int) (preDelaySec * sampleRate));

        // FDN 阻尼 + 调制:每缓冲整定一次(增益连续收敛,网络内无瞬切)。
        // decayLow/High 缺席(直方图没拟合出来,解析兜底值)时由中频衰减 + tone
        // 派生:tone 大(软面)高频更快,低频一律比中频留得久。
        double tailLow = decayLowSec;
        double tailHigh = decayHighSec;
        if (tailLow <= 0.0) {
            tailLow = decaySec > 0 ? decaySec * 1.35 : 0.0;
        }
        if (tailHigh <= 0.0) {
            tailHigh = decaySec > 0 ? decaySec * (1.0 - 0.45 * tone) : 0.0;
        }
        fdn.update(tailLow, decaySec, tailHigh);
        fdn.advanceModulation(frames, sampleRate);
        // 扩散度驱动调制深度:低扩散(离散强回声,大空房/峡谷)的尾音更
        // 「颗粒」,调制加深打散残留的周期性;高扩散(漫散射)天然平滑,
        // 深度回落到基础值。plan 是 volatile 不可变引用,读一个 float 安全。
        fdn.setDiffusion(targetPlan.diffusion);

        // 抽头组管理:计划换了就按需交叉淡化(位置变)/平滑(只能量变)。
        final RoomPlan plan = targetPlan;
        if (plan != lastPlanRef) {
            lastPlanRef = plan;
            if (tapCountA == 0 && !tapFading) {
                buildTapSet(tapPosA, tapPanA, tapGLA, tapGRA, tapAirCoefA, plan, sampleRate);
                tapCountA = plan.tapCount;
            } else if (!tapFading && tapsMatchActive(plan)) {
                // 位置一致:能量交给帧内平滑(见 ER 循环),不瞬替。
            } else {
                buildTapSet(tapPosB, tapPanB, tapGLB, tapGRB, tapAirCoefB, plan, sampleRate);
                tapCountB = plan.tapCount;
                tapFading = true;
                tapFadeW = 0f;
            }
        }
        if (tapFading) {
            tapFadeW += frames / (float) (sampleRate * TAP_FADE_SEC);
            if (tapFadeW >= 1f) {
                System.arraycopy(tapPosB, 0, tapPosA, 0, tapCountB);
                System.arraycopy(tapPanB, 0, tapPanA, 0, tapCountB);
                System.arraycopy(tapGLB, 0, tapGLA, 0, tapCountB);
                System.arraycopy(tapGRB, 0, tapGRA, 0, tapCountB);
                System.arraycopy(tapAirCoefB, 0, tapAirCoefA, 0, tapCountB);
                tapCountA = tapCountB;
                tapFading = false;
                tapFadeW = 1f;
            }
        }
        final float fadeW = tapFading ? tapFadeW : 0f;

        final double[] state0 = bandState.length > 0 ? bandState[0] : null;
        final double[] state1 = ch > 1 && bandState.length > 1 ? bandState[1] : null;
        final int ringLen = erRingL.length;
        final int itdMax = itdMaxSamples;
        final float tapStep = (float) (1.0 - Math.exp(-frames / (sampleRate * TAP_GAIN_TAU_SEC)));

        // 直达声链的逐缓冲整定:低通系数按当前截止导出(收敛是缓的,系数
        // 跳变在缓变里听不见);ITD 读距按当前声像。静息态(directActive=false)
        // 下面整段跳过 —— 左右原样直通,与没有这条链逐样本一致。
        final boolean doDirect = directActive && directRing.length > 1;
        final int dRingLen = doDirect ? directRing.length : 0;
        // 截止全开(近场无遮挡)时低通旁路:M 原样进环 —— 一阶低通在 20kHz
        // 也有 -1.6dB,「全带宽」语义必须是真恒等。
        final boolean dLpOpen = directCutoff >= DirectPath.FC_OPEN - 1.0;
        if (doDirect && !dLpOpen) {
            final double fc = Math.min(directCutoff, sampleRate * 0.45);
            directLpCoef = 1.0 - Math.exp(-2.0 * Math.PI * fc / sampleRate);
            directItdMax = Math.max(1, (int) Math.round(ITD_MAX_SEC * sampleRate));
        }

        // ---- 全 AL 渲染(世界声源;Channel 同款架构) ----
        // 声源激活且总线可用时,把【整条输出】拆 M/S 送进 loopback:M(定位)
        // → 3D source(音箱位 + AL 原生距离衰减/声像/HRTF + 遮挡低通 +
        // EAX 湿发送);S(宽度)→ 立体声直连源 × 收缩系数;一次读回全部
        // (直射 + 混响)。走这条路的缓冲,下面的手搓直射链与 FDN 全跳过 ——
        // AL 的曲线就是业界打磨过的那套,听感对齐 Channel。
        OpenAlReverbBus busFull = useEaxBus && plan.useOpenAlReverb
                ? OpenAlReverbBus.get() : null;
        if (busFull != null && frames > 0) {
            if (busMono.length < frames || busOut.length < frames * 2
                    || busSide.length < frames * 2) {
                busMono = new short[frames];
                busOut = new float[frames * 2];
                busSide = new short[frames * 2];
            }
            // AL 需要听者位姿:树侧每缓冲经 volatile 交接(游戏线程写入)。
            busFull.setListener(listenerX, listenerY, listenerZ,
                    listenerFwdX, listenerFwdY, listenerFwdZ,
                    listenerUpX, listenerUpY, listenerUpZ);
            busFull.setSourcePose(plan.speakerX, plan.speakerY, plan.speakerZ,
                    1f, directCutoff >= DirectPath.FC_OPEN ? 1f : 0.5f);
            busFull.setSendGain(Math.min(1f, wet * 1.2f));
            busFull.setReverbParams(Float.NaN,
                    plan.reverbDiffusion, Math.max(0.1f, decaySec), plan.reverbDecayHfRatio,
                    Float.NaN, Math.max(0.004f, preDelaySec),
                    Float.NaN, Float.NaN, Float.NaN,
                    Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                    Float.NaN, Float.NaN);
            // S 宽度收缩(保留上一轮的物理:一点源的宽度随距离收敛)
            final double width = 0.35 + 0.65 * Math.min(1.0, directGain * 1.4);
            busFull.setSideGain((float) width);
            // M/S 拆分进 PCM16 直通缓冲:本帧干声(带 band 低通后的原始输入,
            // 不乘 dryGain —— AL 的距离衰减就是「干声让位」的物理版)。
            for (int i = 0; i < frames; i++) {
                final int o = i * channels;
                final double l = interleaved[o];
                final double r = ch > 1 ? interleaved[o + 1] : l;
                busMono[i] = toPcm16((l + r) * 0.5);
                busSide[i * 2] = toPcm16((l - r) * 0.5 * 2.0);
                busSide[i * 2 + 1] = toPcm16(-(l - r) * 0.5 * 2.0);
            }
            busFull.render(busMono, busSide, busOut);
            // 读回结果原样写回缓冲(已含直射 3D + S + 混响;限幅照做)。
            final float limiterKneeEarly = (float) LIMITER_KNEE * loudness;
            for (int i = 0; i < frames; i++) {
                final int o = i * channels;
                interleaved[o] = softLimit(busOut[i * 2] * dryGain, limiterKneeEarly);
                if (ch > 1) {
                    interleaved[o + 1] = softLimit(busOut[i * 2 + 1] * dryGain, limiterKneeEarly);
                }
            }
            // 抽头能量平滑照走(读回路径没跑 ER,但平滑是针对下一个缓冲的)
            if (!tapFading && tapCountA == plan.tapCount) {
                for (int i = 0; i < tapCountA; i++) {
                    tapGLA[i] += (plan.tapGainL[i] - tapGLA[i]) * tapStep;
                    tapGRA[i] += (plan.tapGainR[i] - tapGRA[i]) * tapStep;
                }
            }
            return; // 本缓冲完成:全 AL 渲染接管,不走下面的逐样本链
        }

        int idx = 0;
        for (int frame = 0; frame < frames; frame++) {
            double left = interleaved[idx];
            double right = ch > 1 ? interleaved[idx + 1] : left;

            if (bandActive) {
                left = biquad(left, state0);
                if (state1 != null) {
                    right = biquad(right, state1);
                }
            }

            // ---- 直射声链(M/S 等功率 + 动态低通 + 真 ITD) ----
            if (doDirect) {
                // M/S 分解:M = 中置定位分量(声源方向),S = 立体声宽度。
                final double mid = (left + right) * 0.5;
                final double side = (left - right) * 0.5;
                // M 过一阶低通(遮挡/空气吸收的「闷」;截止全开时旁路),写单环。
                directLpState = dLpOpen ? mid
                        : directLpState + directLpCoef * (mid - directLpState);
                final float mSample = (float) directLpState;
                directRing[directPos] = mSample;
                // 真 ITD:源在右(pan>0)右耳先到 —— 右耳读位近(年龄小),
                // 左耳读位远(晚到),与 ER 抽头的约定同向。读位双向 wrap:
                // pan>0 时 readL 向上溢、readR 向下溢;pan<0 恰好相反 ——
                // 两个方向都要收(漏一侧就是环形数组越界,音频链会整个熔断)。
                final int itd = (int) (directPan * directItdMax);
                int readL = directPos + itd;
                if (readL >= dRingLen) {
                    readL -= dRingLen;
                } else if (readL < 0) {
                    readL += dRingLen;
                }
                int readR = directPos - itd;
                if (readR < 0) {
                    readR += dRingLen;
                } else if (readR >= dRingLen) {
                    readR -= dRingLen;
                }
                final double mL = directRing[readL];
                final double mR = directRing[readR];
                directPos = directPos + 1 == dRingLen ? 0 : directPos + 1;
                // 等功率对:g(pan) = (cos((pan+1)π/4)·√2, sin((pan+1)π/4)·√2)
                // —— pan=0 时 gL=gR=1(恒等),全偏时单耳 ×√2(等功率)。
                final double gl = DirectPath.gainL(directPan) * Math.sqrt(2.0);
                final double gr = DirectPath.gainR(directPan) * Math.sqrt(2.0);
                // S 收缩:真实立体声音乐的 side 能量很大,只动 M 时定位被稀释
                // (实机:左右声道不明显)。物理:一点源发出的「立体声宽度」
                // 随距离收敛 —— 离得越远两只音箱相对听者的夹角越小。宽度系数
                // width = mix(1 → 0.35,direct 越小收得越多;gain=1 时 =1 恒等)。
                // pan 的作用:把 S 的中心从「玩家正前」挪到「源的方向」——
                // L 减 R 加(源在右),量 = |pan|×side;pan=0 时零作用,恒等。
                final double width = 0.35 + 0.65 * Math.min(1.0, directGain * 1.4);
                final double sShift = Math.abs(directPan) * side * (1.0 - width);
                left = mL * gl * directGain + side * width - sShift;
                right = mR * gr * directGain - side * width + sShift;
            }

            float wetL = 0f;
            float wetR = 0f;
            if (wetActive) {
                // 直流阻断按各通道独立(立体声喂环的前提:左右耳各自的 DC 偏置
                // 单独清,不然宽度分量被 DC 阻断器的共模抑制吃掉)。
                final double blockedL = left - dcXL + 0.9985 * dcYL;
                dcXL = left;
                dcYL = blockedL;
                final double blockedR = right - dcXR + 0.9985 * dcYR;
                dcXR = right;
                dcYR = blockedR;
                final double blocked = (blockedL + blockedR) * 0.5;

                // 早期反射:【立体声喂环】—— 左环喂 L、右环喂 R。每条反射在
                // 左右耳的内容天然携带输入的耳间差(立体声音乐的宽度信息在
                // 反射里保留),叠加抽头级 ITD 读位差与 ILD(tapGL/tapGR),
                // 双耳线索三件套(ITD/ILD/频谱)全齐;此前 mono 喂双环,
                // 反射内容的耳间差被平均掉,只剩增益差 —— 立体声感弱的根因。
                erRingL[erPos] = (float) blockedL;
                erRingR[erPos] = (float) blockedR;
                erPos = erPos + 1 == ringLen ? 0 : erPos + 1;

                // 节拍侧链(预测式):mono 过 110Hz 低通提低频包络。【上升率】
                // d(env)/dt 超阈立即 duck —— ER 首反射 12ms 就到,普通阈值触发
                // (5ms attack)是马后炮,啪已经响了;上升率在攻击沿的最早几样
                // 本就超阈,先于首反射把 ER 与尾一起压住。慢放 350ms:拍间隙回来。
                final double scLp = scLpY1 + scLpCoef * (blocked - scLpY1);
                scLpX1 = blocked;
                scLpY1 = scLp;
                final double scEnv = scLp * scLp;
                final double envRise = scEnv - scEnvPrev;
                scEnvPrev = scEnv;
                // 触发:包络够大【且】正在快速上升(每样本涨超阈/样本率)
                final boolean kickHit = scEnv > SC_ENV_THRESHOLD
                        && envRise > SC_RISE_PER_SAMPLE;
                final float target = kickHit ? SIDECHAIN_DUCK : 1f;
                final float scStep = (float) (1.0 - Math.exp(-1.0
                        / (sampleRate * (target < sidechainGain
                                ? SIDECHAIN_ATTACK_SEC : SIDECHAIN_RELEASE_SEC))));
                sidechainGain += (target - sidechainGain) * Math.min(1f, scStep);
                final float sc = sidechainGain;

                float erL = 0f;
                float erR = 0f;
                for (int i = 0; i < tapCountA; i++) {
                    final int base = tapPosA[i];
                    final int itd = (int) (tapPanA[i] * itdMax);
                    // 真 ITD:声像在右(pan>0)时右耳先到 —— 右耳读位近(年龄小)、
                    // 左耳读位远(晚到),与 ILD(tapGainR>tapGainL)同向。
                    int readL = erPos - base + itd;
                    if (readL < 0) {
                        readL += ringLen;
                    }
                    int readR = erPos - base - itd;
                    if (readR < 0) {
                        readR += ringLen;
                    }
                    // 逐抽头空气吸收:高频随路程衰减,远墙回声更暗更柔
                    final float rawL = erRingL[readL];
                    float filteredL = tapAirStateLA[i] + (rawL - tapAirStateLA[i]) * tapAirCoefA[i];
                    tapAirStateLA[i] = filteredL;
                    final float rawR = erRingR[readR];
                    float filteredR = tapAirStateRA[i] + (rawR - tapAirStateRA[i]) * tapAirCoefA[i];
                    tapAirStateRA[i] = filteredR;
                    erL += filteredL * tapGLA[i];
                    erR += filteredR * tapGRA[i];
                }
                if (tapFading) {
                    float erLB = 0f;
                    float erRB = 0f;
                    for (int i = 0; i < tapCountB; i++) {
                        final int base = tapPosB[i];
                        final int itd = (int) (tapPanB[i] * itdMax);
                        int readL = erPos - base + itd;
                        if (readL < 0) {
                            readL += ringLen;
                        }
                        int readR = erPos - base - itd;
                        if (readR < 0) {
                            readR += ringLen;
                        }
                        final float rawL = erRingL[readL];
                        float filteredL = tapAirStateLB[i] + (rawL - tapAirStateLB[i]) * tapAirCoefB[i];
                        tapAirStateLB[i] = filteredL;
                        final float rawR = erRingR[readR];
                        float filteredR = tapAirStateRB[i] + (rawR - tapAirStateRB[i]) * tapAirCoefB[i];
                        tapAirStateRB[i] = filteredR;
                        erLB += filteredL * tapGLB[i];
                        erRB += filteredR * tapGRB[i];
                    }
                    erL = erL * (1f - fadeW) + erLB * fadeW;
                    erR = erR * (1f - fadeW) + erRB * fadeW;
                }

                // 尾部:预延迟 → FDN(双耳入,双耳出 —— 立体声尾)
                int preRead = preDelayPos - preDelaySamples;
                if (preRead < 0) {
                    preRead += preDelayBuf.length;
                }
                // 双耳预延迟:左右各一条写入位差一点点的环,读同一位 ——
                // 等效左右进尾的信号带 <1ms 的互质时差,配 FDN 内的去相关
                // 预延迟,中高频的尾有真实立体声图案;低频已被 HPF 拦掉。
                // 写入也立体声化:进尾的左右内容各自带宽度(与 ER 喂环同理)。
                final float delayedL = preDelayBuf[preRead];
                final int preReadR = preRead + 1 == preDelayBuf.length ? 0 : preRead + 1;
                final float delayedR = preDelayBuf[preReadR];
                preDelayBuf[preDelayPos] = (float) ((blockedL + blockedR) * 0.5);
                preDelayPos = preDelayPos + 1 == preDelayBuf.length ? 0 : preDelayPos + 1;

                // 中频人声保护:人声核心段(0.8-4kHz)进尾前轻压 —— 歌词清晰度
                // 的行业标准手段。实现:mono 过「中频带通」提取该段,输入减去
                // 该段的 protectionRatio(0.45 ≈ -7dB 轻压),只影响进尾的量,
                // 干声不动。人声糊感显著下降,大空间氛围略薄是已知取舍。
                double monoMix = (delayedL + delayedR) * 0.5;
                double midBand = midBPB0 * monoMix + midBPB1 * midBPX1 + midBPB2 * midBPX2
                        - midBPA1 * midBPY1 - midBPA2 * midBPY2;
                midBPX2 = midBPX1; midBPX1 = monoMix;
                midBPY2 = midBPY1; midBPY1 = midBand;
                double duckedMid = monoMix - midBand * MID_PROTECT_RATIO;
                // 左右按各自偏离 mono 的量分配(保持立体声图案)
                float tailInL = (float) (duckedMid + (delayedL - monoMix));
                float tailInR = (float) (duckedMid + (delayedR - monoMix));

                // ---- 湿声路由:EAX Reverb 总线优先,FDN 兜底 ----
                final OpenAlReverbBus bus = useEaxBus && plan.useOpenAlReverb
                        ? OpenAlReverbBus.get() : null;
                if (bus != null) {
                    // 世界声源(全 AL 渲染,Channel 同款架构):本缓冲的湿声
                    // 已在循环外整缓冲送入总线 —— 这里只取回渲染结果。直射 3D
                    // (距离衰减/声像/HRTF)+ S 直连(宽度收缩)+ EAX 混响
                    // (含 ER/LR 方向)全部由 OpenAL 原生完成。
                    wetL = busOut[0] * sc;
                    wetR = busOut[1] * sc;
                } else {
                    fdn.process(tailInL, tailInR, fdnOut);

                    wetL = (erL * AcousticsDebugTuning.erBoost(1f)
                            + fdnOut[0] * AcousticsDebugTuning.tailMakeup((float) TAIL_MAKEUP))
                            * wet * sc;
                    wetR = (erR * AcousticsDebugTuning.erBoost(1f)
                            + fdnOut[1] * AcousticsDebugTuning.tailMakeup((float) TAIL_MAKEUP))
                            * wet * sc;
                }

                // 湿声输出低频搁架仅自研链走(渲染结果已含 3D 定位与混响方向,
                // EAX 的 DECAY_HFRATIO 已表达「软面尾更暗」;再搁架会双重压低)。
                if (bus == null) {
                    final double lpL = shelfYL1 + shelfB0 * (wetL - shelfYL1);
                    shelfYL1 = lpL;
                    wetL = (float) (shelfXL1 = wetL - (1.0 - WET_SHELF_KEEP) * lpL);
                    final double lpR = shelfYR1 + shelfB0 * (wetR - shelfYR1);
                    shelfYR1 = lpR;
                    wetR = (float) (shelfXR1 = wetR - (1.0 - WET_SHELF_KEEP) * lpR);
                } else {
                    shelfXL1 = shelfXR1 = 0.0;
                }
            }

            // 干+湿合成:湿声与抽头和的最大值有物理上限(ER 抽头 64 条同时
            // 相近 + FDN 尾叠加,极端场景湿声瞬时可达干声的 1.5 倍以上),
            // DJ/劲爆音乐的干声本身就在 0dB 附近,直接相加必然周期性削波。
            // 软限幅按样本工作:tanh 家族的分段近似 —— 1 以内线性直通
            // (真实音乐 99% 的样本不走弯),越界段用平滑曲线压回去,
            // 拐点无导数跳变,听感是「推子压住」而不是「削波炸响」。
            // 阈值随 loudness 缩放:浸液/降水整体变小声时,限幅器跟着退让,
            // 不在小音量下引入多余的非线性。
            final float limiterKnee = (float) LIMITER_KNEE * loudness;
            if (ch > 1) {
                interleaved[idx] = softLimit(left * dryGain + wetL, limiterKnee);
                interleaved[idx + 1] = softLimit(right * dryGain + wetR, limiterKnee);
            } else {
                interleaved[idx] = softLimit(left * dryGain + (wetL + wetR) * 0.5f, limiterKnee);
            }
            idx += channels;
        }

        // 抽头能量朝当前计划平滑:位置一致的「能量瞬替」改为一帧一段的指数
        // 收敛(τ=0.12s),房间微变时无台阶;淡化中的 B 组也一起走。
        if (!tapFading && tapCountA == plan.tapCount) {
            for (int i = 0; i < tapCountA; i++) {
                tapGLA[i] += (plan.tapGainL[i] - tapGLA[i]) * tapStep;
                tapGRA[i] += (plan.tapGainR[i] - tapGRA[i]) * tapStep;
            }
        }
    }

    private static double biquad(double in, double[] s) {
        final double out = cB0 * in + cB1 * s[0] + cB2 * s[1] - cA1 * s[2] - cA2 * s[3];
        s[1] = s[0];
        s[0] = in;
        s[3] = s[2];
        s[2] = out;
        return out;
    }

    /** RBJ 低通,Q = 1/√2(Butterworth,Q 值再高点低点都会出现可闻的谷/峰)。 */
    private static void designLowpass(double fc, int rate) {
        final double capped = Math.min(fc, rate * 0.475);
        final double w0 = 2.0 * Math.PI * capped / rate;
        final double cosw = Math.cos(w0);
        final double alpha = Math.sin(w0) / (2.0 * BUTTERWORTH_Q);

        final double a0 = 1.0 + alpha;
        cB0 = ((1.0 - cosw) / 2.0) / a0;
        cB1 = (1.0 - cosw) / a0;
        cB2 = cB0;
        cA1 = (-2.0 * cosw) / a0;
        cA2 = (1.0 - alpha) / a0;
    }

    /** 当前生效抽头组与计划的延迟位置是否一致(位置一变就走交叉淡化 ——
     *  原位跳读延迟线等于咔哒声,哪怕只跳 1 样)。 */
    private static boolean tapsMatchActive(RoomPlan plan) {
        if (tapCountA != plan.tapCount) {
            return false;
        }
        for (int i = 0; i < plan.tapCount; i++) {
            if (tapSamplePos(plan, i, ownedRate) != tapPosA[i]) {
                return false;
            }
        }
        return true;
    }

    private static void buildTapSet(int[] posOut, float[] panOut, float[] glOut, float[] grOut,
                                    float[] coefOut, RoomPlan plan, int rate) {
        for (int i = 0; i < plan.tapCount; i++) {
            posOut[i] = tapSamplePos(plan, i, rate);
            panOut[i] = plan == targetPlan ? tapPanOf(plan, i) : 0f;
            glOut[i] = plan.tapGainL[i];
            grOut[i] = plan.tapGainR[i];
            // 空气吸收:fc = 18kHz·e^(−(路程−6m)/16m),下限 500Hz —— 6 米内不染色
            final double path = plan.tapDelaySec[i] * SPEED_OF_SOUND;
            final double fc = Math.max(AIR_FC_MIN_HZ,
                    AIR_FC_REF_HZ * Math.exp(-Math.max(0.0, path - AIR_FREE_METERS) / AIR_DECAY_METERS));
            coefOut[i] = (float) (1.0 - Math.exp(-2.0 * Math.PI * fc / rate));
        }
    }

    /** 计划里第 i 条抽头的声像(等功率声像在推导侧已算;此处只回读 pan)。 */
    private static float tapPanOf(RoomPlan plan, int i) {
        // RoomPlan 只有分耳能量;pan 从能量比反推(等功率律的可逆式),
        // 语义 = sign(右强)·幅度差。ITD 只需要方向,不需要精度。
        final float l = plan.tapGainL[i];
        final float r = plan.tapGainR[i];
        final float sum = Math.abs(l) + Math.abs(r);
        if (sum < 1e-9f) {
            return 0f;
        }
        // 等功率:cos²(θ)=(1+pan)π/4 的逆 —— 用能量比近似方向即可
        return Math.max(-1f, Math.min(1f, 2f * (Math.abs(r) - Math.abs(l)) / sum));
    }

    private static int tapSamplePos(RoomPlan plan, int i, int rate) {
        final int len = erRingL.length;
        final int pos = Math.round(plan.tapDelaySec[i] * rate);
        return Math.max(1 + itdMaxSamples, Math.min(len - 1 - itdMaxSamples, pos));
    }

    // ---- 资源 ----

    /** 采样率变了:重建各条延迟线并复位状态;抽头组按当前计划重建。 */
    private static void rebuild(int rate, int channels) {
        ownedRate = rate;
        bandState = new double[channels][4];
        for (double[] s : bandState) java.util.Arrays.fill(s, 0.0);

        // EAX Reverb 总线:每条引擎采样率一个;失败静默(useEaxBus=false → FDN 兜底)。
        // 只在音频线程(rebuild 由 render 调)执行 —— AL 上下文绑定纪律同 OpenALAudioDevice。
        try {
            if (useEaxBus || com.muoniumplayer.core.ncm.music.audio.OpenAlReverbBus.get() == null) {
                com.muoniumplayer.core.ncm.music.audio.OpenAlReverbBus created =
                        com.muoniumplayer.core.ncm.music.audio.OpenAlReverbBus.createIfAvailable(rate, 512);
                useEaxBus = created != null;
            } else {
                useEaxBus = true;
            }
        } catch (Throwable t) {
            useEaxBus = false;
            System.out.println("[MuoniumPlayer/Acoustics] EAX 总线不可用,湿声走自研链: " + t);
        }

        preDelayBuf = new float[Math.max(2, (int) (PRE_DELAY_MAX_MS * rate / 1000.0) + 2)];
        preDelayPos = 0;

        // 中频人声保护带通:中心 1.8kHz、Q≈0.8(覆盖 0.8-4kHz 人声核心)
        final double midFc = 1800.0;
        final double midW = 2.0 * Math.PI * Math.min(midFc, rate * 0.45) / rate;
        final double midAlpha = Math.sin(midW) / (2.0 * 0.8);
        final double midA0 = 1.0 + midAlpha;
        midBPB0 = midAlpha / midA0;
        midBPB1 = 0.0;
        midBPB2 = -midAlpha / midA0;
        midBPA1 = (-2.0 * Math.cos(midW)) / midA0;
        midBPA2 = (1.0 - midAlpha) / midA0;
        midBPX1 = midBPX2 = midBPY1 = midBPY2 = 0.0;
        // 湿声输出低频搁架:一阶低通(150Hz)提取低频,湿声减去
        // (1-WET_SHELF_KEEP)×低频 —— 20Hz 处湿声剩 KEEP(-7.5dB ⇒ 0.42),
        // 150Hz 处 -3dB 点,以上渐回 1。一阶减法实现,曲线可预测不踩
        // biquad shelf 的流派坑。
        final double shelfFc = Math.min(WET_SHELF_HZ, rate * 0.45);
        shelfB0 = 1.0 - Math.exp(-2.0 * Math.PI * shelfFc / rate);
        shelfXL1 = shelfYL1 = 0.0;
        shelfXR1 = shelfYR1 = 0.0;
        // 侧链包络低通 @110Hz(检测底鼓)
        final double scW = 2.0 * Math.PI * Math.min(SIDECHAIN_LP_HZ, rate * 0.45) / rate;
        scLpCoef = 1.0 - Math.exp(-2.0 * Math.PI * Math.min(scW / (2.0 * Math.PI), rate * 0.45) / rate);
        scLpX1 = scLpY1 = 0.0;
        sidechainGain = 1f;

        // 双耳 ER 环:容量 = ER 窗 + 2×ITD(读位可越过基准 ±ITD)
        itdMaxSamples = Math.max(1, (int) Math.round(ITD_MAX_SEC * rate));
        final int ringLen = Math.max(8, (int) (ER_WINDOW_SEC * rate) + 2 * itdMaxSamples + 2);
        erRingL = new float[ringLen];
        erRingR = new float[ringLen];
        erPos = 0;

        // 直达声环:容量 = ITD 上限 ×3 的余量(M 单环,读位 ±ITD 内)。
        directItdMax = itdMaxSamples;
        directRing = new float[Math.max(8, DIRECT_RING_SEC_SAMPLES + 2 * itdMaxSamples)];
        directPos = 0;
        directLpState = 0.0;
        final RoomPlan plan = targetPlan;
        buildTapSet(tapPosA, tapPanA, tapGLA, tapGRA, tapAirCoefA, plan, rate);
        tapCountA = plan.tapCount;
        tapCountB = 0;
        java.util.Arrays.fill(tapAirStateLA, 0f);
        java.util.Arrays.fill(tapAirStateRA, 0f);
        java.util.Arrays.fill(tapAirStateLB, 0f);
        java.util.Arrays.fill(tapAirStateRB, 0f);
        tapFading = false;
        tapFadeW = 1f;
        lastPlanRef = plan;

        fdn.rebuild(rate);
        lastCoefBand = -1.0;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 静息频带(Hz),供推导侧判断「是否真的压了」。 */
    public static double bandOpenHz() {
        return BAND_OPEN_HZ;
    }

    /** 频带下限(Hz),供推导侧夹取。 */
    public static double bandFloorHz() {
        return MIN_BAND_HZ;
    }
}
