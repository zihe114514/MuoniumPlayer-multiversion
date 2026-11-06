package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 听者环境的一帧快照。由各版本树的 shim 在游戏线程上采样,数值全部按「声学
 * 意义」给出,不暴露任何游戏类型 —— common 侧只认这份纯数据。
 *
 * <p>几何字段来自树侧的球面射线:从听者向球面均匀撒一批射线(听者即声源),
 * 各自投射到第一次撞上的方块或流体面,再按镜面反射继续弹;每弹一次向听者回测
 * 一次「这面墙的反射声能不能无遮挡到达」。确认可达的反射逐条记进
 * <b>早期反射抽头</b>(延迟/相对能量/声像),其余字段是这批射线的统计摘要 ——
 * common 侧不做任何世界访问,也不关心射线怎么撒。</p>
 */
public interface ListenerSample {

    /** 「没有测到首次命中」哨兵:所有方向都逃逸(旷野)。 */
    float NO_HIT = -1f;

    /** 双耳(严格说是眼睛)浸在液体里。 */
    boolean submerged();

    /** 头顶直望天空无遮挡(树冠、屋檐都算遮挡)。 */
    boolean openSky();

    /**
     * 围合度 0..1:首次投射中有多少比例的方向在射程内撞上实体。
     * 0 = 四野空旷,1 = 全方向近处有墙。
     */
    float enclosure();

    /** 降水强度:0 无雨;0.5 降雨;1 雷暴(只统计听者头顶真正在降水时)。 */
    float precipitation();

    /** 首次命中距离的平均值(米),即「最近的墙平均有多远」;全方向逃逸时为 {@link #NO_HIT}。 */
    float firstHitMeanMeters();

    /**
     * 弹射段平均长度(米):从第一次命中起的各段反射路程均值,即「相邻两次反射
     * 间隔多远」,是房间尺度的直接写照。没有弹射段时回落到首命中均值。
     */
    float bounceSegmentMeanMeters();

    /** 命中面反射率均值(0..约 1.5,硬质石材可大于 1);没有命中时为 0。 */
    float meanReflectivity();

    /**
     * 可达比例 0..1:全部命中中,确认反射声能无遮挡回到听者的比例
     * (几何上有墙 ≠ 声学上有回声,隔着一堵墙的反射不算数)。没有命中时为 0。
     */
    float reachableFraction();

    // ---- 早期反射抽头(玩家即声源:每条 = 一次真实回到耳朵的反射) ----

    /** 抽头条数;下面三个数组长度都与它一致(树侧保证)。 */
    int tapCount();

    /** 每条反射的全路径延迟(秒):出发段累计 + 回听者段,已除以声速。 */
    float[] tapDelaysSec();

    /**
     * 每条反射的相对幅度(未定标):逐弹幅度保留(√反射率,夹 0.15..0.92)连乘,
     * 乘 2m/全路径 的几何衰减 —— 早期反射是「幅度」量,几何按 1/距离走
     * (1/距离² 是能量);按能量模型连乘会把近墙反射算到听不见。
     * 定标(归一、强度)由 common 侧统一做。
     */
    float[] tapGains();

    /** 每条反射的声像 −1(正左)..1(正右):到达方向在玩家右手方向上的投影。 */
    float[] tapPans();

    /**
     * 多频带能量直方图:这批射线按 3 频带 × 20ms 桶累积的「能量何时从哪个
     * 频带回到听者」。逐弹能量 = 幅度保留²(能量量)× 分频带反射率 × 分频带
     * 空气吸收 × 几何 1/距离²;入桶时刻 = 全路径 ÷ 声速。
     *
     * <p>逐频带 T60 从它解析(见 {@link HistogramEstimator}),是晚期尾的
     * 物理来源;旧的单值统计摘要仍在(直方图的旁路产物,兜底与诊断用)。
     * 返回共享实例即可 —— 采样线程内写完才读,交接后不再变。</p>
     */
    EnergyField bandHistogram();

    // ---- 世界声源(树侧实现了才有效;default = 旧行为「玩家即声源」) ----

    /**
     * 音乐正从一个世界中的声源(虚拟音箱)发出。true 时:
     * <ul>
     *   <li>几何字段(抽头/直方图/围合)来自<b>以音箱为原点</b>的射线场 ——
     *       每条抽头是真实的「源→墙→耳」路径,混响是音箱所在房间的物理混响;</li>
     *   <li>反射抽头的延迟是<b>相对直达声</b>的(全路径 − 直达路程),因为
     *       渲染里直达声零延迟;</li>
     *   <li>直射声字段({@link #directDistanceMeters()} 等)有效,音频侧对
     *       干声做距离衰减/遮挡闷音/双耳定位。</li>
     * </ul>
     */
    default boolean hasDirectSource() {
        return false;
    }

    /** 直达路程(米,源→耳直线);无声源时 0。 */
    default float directDistanceMeters() {
        return 0f;
    }

    /**
     * 三频带反射率 {低,中,高}(材质表原值,未平均):逐频带 T60 的
     * 材质驱动来源 —— 羊毛高频 ρ=0.03 vs 中频 0.1,高频尾应快一倍;
     * 石头全频亮,三带同尾。default 交 null = 旧采样器,走均值近似。
     */
    default float[] bandReflectivities() {
        return null;
    }

    /** 直射遮挡量:路径上穿透方块的遮挡累计(实心 1.0 / 非实心 0.15);无声源 0。 */
    default float directOcclusion() {
        return 0f;
    }

    /**
     * 是否找到一阶绕射路径(源→遮挡边缘→耳)。
     *
     * <p>物理:声波碰到硬边缘会拐弯继续传播,这条路径往往比「穿透方块」
     * 强一个量级 —— 实机听感差异就是「音箱在墙角后面,声音从墙角绕过来」
     * 而不是「突然变小变闷」。树侧在直达被挡时沿遮挡边缘搜索折线路径。</p>
     *
     * <p>default false = 旧采样器没有这份数据,退化为纯遮挡(修前行为)。</p>
     */
    default boolean hasDiffraction() {
        return false;
    }

    /**
     * 绕射路径全长(米,源→边缘→耳)。{@link #hasDiffraction()} 为 false 时无意义。
     */
    default float diffractionPathMeters() {
        return 0f;
    }

    /**
     * 绕射路径相对直达直线的额外出程(米,≥0):决定绕射衰减的大小
     * —— 绕得越远损失越多。
     */
    default float diffractionExcessMeters() {
        return 0f;
    }

    /**
     * 绕射折角(度):源→边缘 与 边缘→耳 两段的夹角。0 = 近乎直线,
     * 180 = 绕过一整面墙。与额外出程一起决定衰减与染色。
     */
    default float diffractionBendDegrees() {
        return 0f;
    }

    /**
     * 绕射边缘的世界坐标(米):音频侧把虚拟声源挪到这里,声像因此偏向
     * 墙角/门缝 —— 这是「听出声音从哪儿绕过来」的关键,不是纯音量变化。
     */
    default double diffractionEdgeX() {
        return 0.0;
    }

    default double diffractionEdgeY() {
        return 0.0;
    }

    default double diffractionEdgeZ() {
        return 0.0;
    }

    /**
     * 绕射声像 −1(正左)..1(正右):边缘方向在玩家右手方向上的投影。
     *
     * <p>与 {@link #directPan()} 同一投影口径,但基准是「边缘→耳」而不是
     * 「源→耳」—— 绕射声被人耳定位在缺口/墙角处,这是绕射感的核心线索。</p>
     */
    default float diffractionPan() {
        return 0f;
    }

    /** 直达声像 −1(源在正左)..1(源在正右):源方向在玩家右手方向的投影。 */
    default float directPan() {
        return 0f;
    }

    /** 声源世界坐标 X(树侧 SpeakerState);无声源 0。EAX 总线的 3D 定位用。 */
    default double speakerX() {
        return 0;
    }

    /** 声源世界坐标 Y。 */
    default double speakerY() {
        return 0;
    }

    /** 声源世界坐标 Z。 */
    default double speakerZ() {
        return 0;
    }
}
