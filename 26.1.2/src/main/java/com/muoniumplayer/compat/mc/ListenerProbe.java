package com.muoniumplayer.compat.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.muoniumplayer.core.ncm.music.acoustics.AcousticsRuntime;
import com.muoniumplayer.core.ncm.music.acoustics.EnergyField;
import com.muoniumplayer.core.ncm.music.acoustics.ListenerSample;
import com.muoniumplayer.core.ncm.music.acoustics.SpeakerState;

/**
 * 听者环境的采样 shim:把当前游戏状态翻译成 common 侧的 {@link ListenerSample}。
 * 几何部分用斐波那契球面射线:从<b>声源原点</b>向球面均匀撒一批
 * 条方向(经度走黄金角、纬度均匀分层,天然铺满球面),每条投射到第一次撞上的
 * 方块或流体面,再按镜面反射最多 {@link #MAX_BOUNCES} 次;每次命中沿法线抬起
 * 一点向<b>听者眼位</b>回测「这条反射能不能无遮挡到达」。确认可达的反射逐条
 * 记进<b>早期反射抽头</b>(延迟/相对幅度/到达方向);围合度、房间尺度、墙面
 * 反射率、可达比例是这批射线的统计摘要;<b>多频带能量直方图</b>按 3 频带 ×
 * 20ms 桶累积每条反射的能量到达。世界状态住在树侧,common 只认纯数据。
 *
 * <h2>声源原点:玩家或世界音箱</h2>
 * <p>没有世界声源时原点 = 玩家眼位(「玩家即声源」旧语义);玩家放置了虚拟
 * 音箱({@link SpeakerState#active()})时原点 = 音箱锚点 —— 整套射线场自动
 * 变成「源→墙→耳」的真实几何:抽头 path 是全路径,反射时序在交付时按
 * 「全路径 − 直达路程」相对化(渲染里直达声零延迟),回测终点仍是眼位,
 * 可达性检查零改动。同时新增<b>直射声采样</b>:源→眼一条射线,累加穿透
 * 方块的遮挡量(实心 1.0 / 非实心 0.15),量出直达路程与方向声像。</p>
 *
 * <h2>线程模型(完全体:采样在后台线程)</h2>
 * <p>本类由 {@code AcousticsRuntime} 在<b>后台工作线程</b>(MIN_PRIORITY 池)上调
 * {@link #capture()},不再占主线程 —— 96 射线 × 4 弹的最坏 ~500 次体素投射
 * 画面对其完全无感。因此:站定缓存仍保留(常态零射线),但强制重算放宽到
 * 每 5s 一次;所有 Minecraft 对象访问都发生在后台线程,风险由 runtime 的
 * try/Throwable 包住(最坏 = 跳过一帧采样)。</p>
 *
 * <h2>多频带反射率</h2>
 * <p>同一面墙对不同频带长得不一样:石材全频亮,羊毛吸高音,木头居中。
 * 反射率表扩成 3 频带三元组 {低,中,高};入桶能量 = 逐弹幅度保留²(能量量)
 * × 该频带反射率 × 分频带空气吸收 × 几何 1/距离²。逐弹幅度保留取三带均值
 * 的 √(早期抽头仍按幅度模型走,与直方图的能量模型分工)。</p>
 */
public final class ListenerProbe implements AcousticsRuntime.ListenerSampler {

    /**
     * 射线条数,两档自适应(照 Wwise 的 CPU Limit Mode:移动时降密保响应,
     * 站定时升密保精度)。
     *
     * <p>为什么移动时 256 够:判「我进没进洞」只需要围合度/首命中的量级,
     * 256 条斐波那契分布的围合度估计误差 &lt;3% —— 房间判定不会因为降密而
     * 翻面。1024 条的真正价值是<b>直方图统计密度</b>(T60 拟合每桶 ~64 条
     * 命中),那是站定后才需要的东西。</p>
     *
     * <p>为什么站定也降到 512:512 条时每桶仍有 ~32 条命中,拟合噪声只
     * 比 1024 高 1.4 倍,听感上不可辨;而射线工作量砍一半。需要极限精度时
     * 把它调回 1024 即可。</p>
     */
    private static final int RAY_COUNT_SETTLED = 512;
    private static final int RAY_COUNT_MOVING = 256;

    /** 首次命中之后最多再弹几次:直方图窗 3s ⇒ 路径要追到 ~1km 才不缺
     *  尾部。16 弹 × 160m 上限 ≈ 2.5km,真实大厅/峡谷的多次回声(3-4 次可闻
     *  拍击)也能进直方图,而不是被弹数上限截断。 */
    private static final int MAX_BOUNCES = 16;
    /** 单段射程(格):160 格 ≈ 0.47s 声程 —— 旷野/大峡谷的远墙一次反射
     *  (真实中 100-400ms 后可闻)采得到,洞口对面的第二次回声也采得到。 */
    private static final double RAY_RANGE = 160.0;
    /** 黄金角(弧度):经度步进,配纬度均匀分层构成斐波那契格。 */
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    /** 反射/回测起点沿法线抬离墙面的距离,避免立刻重新命中同一面。 */
    private static final double SKIN = 0.01;
    /** 命中方块没有进反射率表时的兜底(普通砖石混砌)。 */
    private static final float DEFAULT_REFLECTIVITY = 0.5f;
    /** 声速(米/秒):几何量 → 时间,与共享层同一换算。 */
    private static final double SPEED_OF_SOUND = 343.0;

    /** 站定判据(平方距离,格²):挪动不足 0.5 格视为没动。 */
    private static final double STATIONARY_SQ = 0.25;
    /** 站定期间每 N 次采样强制重算几何(500ms × 12 = 6s):方块可能变了。 */
    private static final int STALE_EVERY = 12;

    // ---- 一阶绕射(遮挡边缘) ----

    /** 边缘候选方向数:24 条覆盖球面,配合「只取最短代价」已足够 ——
     *  再密只是多花时间在同一个墙角上找更优点。 */
    private static final int DIFFRACTION_DIRS = 24;
    /** 候选点相对遮挡块中心的半径 = 块对角线一半(0.866)再加一点皮肤,
     *  保证候选点落在块面外,两段投射不会立刻命中同一块。 */
    private static final double DIFFRACTION_SKIN = 0.08;
    /** 候选点离源-耳直线的最短距离下限(格):太靠近直线的那是穿透不是绕射。 */
    private static final double DIFFRACTION_MIN_OFFSET = 0.35;
    /** 最大额外出程(格):绕太远的路径听不见,也失去指向性。8 格 ≈ 23ms
     *  额外延迟,再远就该由晚期混响而不是直达声来表达。 */
    private static final double DIFFRACTION_MAX_EXCESS = 8.0;
    /** 代价函数里折角的权重(格/180°):同样额外出程时,选拐得少的那条。 */
    private static final double DIFFRACTION_BEND_WEIGHT = 1.5;

    // ---- 分频带空气吸收(能量比例 @ 观察窗内典型路程):高频走得远被吃得多 ----
    /** 每米能量衰减系数(近似 26.3 标准):{低,中,高}。 */
    private static final double[] AIR_ENERGY_PER_METER = {0.9990, 0.9970, 0.9920};

    /** 站定缓存(仅采样线程触碰):上次几何 + 上次原点/眼位/维度 + 采样计数。 */
    private static GeomStats cachedStats;
    private static Vec3 cachedOrigin;
    private static Vec3 cachedEar;
    private static Level cachedWorld;
    private static int capturesSinceCast;

    private ListenerProbe() {
    }

    /** 采一帧;不在世界里返回 null。 */
    public static ListenerProbe of(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return null;
        }
        return new ListenerProbe();
    }

    @Override
    public ListenerSample capture() {
        final Minecraft client = Minecraft.getInstance();
        final Level world = client.level;
        if (world == null || client.player == null) {
            // 退出世界回主菜单:静态缓存别钉住已卸载的 ClientLevel(整包 chunk)
            cachedStats = null;
            cachedOrigin = null;
            cachedEar = null;
            cachedWorld = null;
            capturesSinceCast = 0;
            return null;
        }

        final Vec3 eye = client.player.getEyePosition();
        final BlockPos eyePos = BlockPos.containing(eye.x, eye.y, eye.z);
        final boolean submerged = AcousticsRuntime.wantsSubmersion() && client.player.isUnderWater();
        final boolean openSky = world.canSeeSky(eyePos);

        final float precipitation = AcousticsRuntime.wantsRainfall() && world.isRainingAt(eyePos)
                ? (world.isThundering() ? 1f : 0.5f)
                : 0f;

        // 世界声源:锚点在本维度且玩家在射程内才切换射线原点。判定读的是
        // SpeakerManager 每 tick 写的运行状态(它才有 chunk 加载语义)。
        final SpeakerState speaker = SpeakerManager.samplingOrigin(world);
        final Vec3 origin = speaker != null
                ? new Vec3(speaker.x(), speaker.y(), speaker.z())
                : eye;

        final GeomStats stats;
        if (!AcousticsRuntime.wantsEnclosure()) {
            stats = GeomStats.NONE;
            cachedStats = null;
            cachedOrigin = null;
            cachedEar = null;
            cachedWorld = null;
            capturesSinceCast = 0;
        } else {
            // 两个点都要看:原点决定射线从哪儿发出,眼位决定「可达」回测的
            // 终点与声像 —— 音箱模式下玩家走动时原点不动、但整张抽头的
            // 延迟/可达性都在变,只看原点会把几何冻结到下一个强制重算。
            final boolean moved = cachedOrigin == null || cachedEar == null
                    || cachedWorld != world
                    || cachedOrigin.distanceToSqr(origin) > STATIONARY_SQ
                    || cachedEar.distanceToSqr(eye) > STATIONARY_SQ;
            capturesSinceCast++;
            if (moved || cachedStats == null || capturesSinceCast >= STALE_EVERY) {
                // 射线数自适应(见 RAY_COUNT_*):移动时降密保响应,站定升密保精度。
                stats = castField(world, origin, eye, moved ? RAY_COUNT_MOVING : RAY_COUNT_SETTLED);
                cachedStats = stats;
                cachedOrigin = origin;
                cachedEar = eye;
                cachedWorld = world;
                capturesSinceCast = 0;
            } else {
                stats = cachedStats;
            }
        }

        // 声像 = 到达方向在玩家右手方向上的水平投影,每次采样按当前视角重算
        // (站定时几何走缓存,这一步是唯一的逐帧几何计算)。
        final float yawRad = client.player.getYRot() * ((float) Math.PI / 180f);
        final float[] pans = computePans(stats, yawRad);

        // 直射声(世界声源才有):源→眼直线路程、遮挡量、方向声像。
        final boolean hasSource = speaker != null;
        final float directDist;
        final float directOccl;
        final float directPan;
        // 一阶绕射(仅当确实被实心方块挡住时才搜索 —— 开阔地零成本)
        DiffractionPath diffraction = null;
        float diffractionPan = 0f;
        if (hasSource) {
            directDist = (float) origin.distanceTo(eye);
            directOccl = directOcclusion(world, origin, eye);
            // 源方向在玩家右手方向上的投影(与抽头声像同款投影)
            final double dx = eye.x - origin.x;
            final double dz = eye.z - origin.z;
            final double horiz = Math.sqrt(dx * dx + dz * dz);
            directPan = horiz > 1e-6
                    ? (float) Math.max(-1.0, Math.min(1.0,
                            (dx * -Math.cos(yawRad) + dz * -Math.sin(yawRad)) / horiz))
                    : 0f;
            // 有实心遮挡才值得找绕射路径:0.15 档(栅栏/玻璃)不挡声波拐弯。
            if (directOccl >= 1.0f) {
                diffraction = findDiffraction(world, origin, eye);
                if (diffraction != null) {
                    // 绕射声像:边缘方向在右手方向上的投影 —— 人耳把绕射声
                    // 定位在缺口/墙角处,这是「听出从哪儿绕过来」的线索。
                    final double ex = eye.x - diffraction.edge.x;
                    final double ez = eye.z - diffraction.edge.z;
                    final double edgeHoriz = Math.sqrt(ex * ex + ez * ez);
                    diffractionPan = edgeHoriz > 1e-6
                            ? (float) Math.max(-1.0, Math.min(1.0,
                                    (ex * -Math.cos(yawRad) + ez * -Math.sin(yawRad))
                                            / edgeHoriz))
                            : 0f;
                }
            }
        } else {
            directDist = 0f;
            directOccl = 0f;
            directPan = 0f;
        }
        final DiffractionPath diff = diffraction;
        final float diffPan = diffractionPan;

        return new ListenerSample() {
            @Override public boolean submerged() { return submerged; }
            @Override public boolean openSky() { return openSky; }
            @Override public float enclosure() { return stats.enclosure; }
            @Override public float precipitation() { return precipitation; }
            @Override public float firstHitMeanMeters() { return stats.firstHitMean; }
            @Override public float bounceSegmentMeanMeters() { return stats.segMean; }
            @Override public float meanReflectivity() { return stats.reflectivity; }
            @Override public float[] bandReflectivities() { return stats.bandRho; }
            @Override public float reachableFraction() { return stats.reachable; }
            @Override public int tapCount() { return stats.tapCount; }
            @Override public float[] tapDelaysSec() { return stats.tapDelays; }
            @Override public float[] tapGains() { return stats.tapGains; }
            @Override public float[] tapPans() { return pans; }
            @Override public EnergyField bandHistogram() { return stats.histogram; }
            @Override public boolean hasDirectSource() { return hasSource; }
            @Override public float directDistanceMeters() { return directDist; }
            @Override public float directOcclusion() { return directOccl; }
            @Override public float directPan() { return directPan; }
            @Override public boolean hasDiffraction() { return diff != null; }
            @Override public float diffractionPathMeters() {
                return diff == null ? 0f : (float) diff.pathMeters;
            }
            @Override public float diffractionExcessMeters() {
                return diff == null ? 0f : (float) diff.excessMeters;
            }
            @Override public float diffractionBendDegrees() {
                return diff == null ? 0f : (float) diff.bendDegrees;
            }
            @Override public double diffractionEdgeX() {
                return diff == null ? 0 : diff.edge.x;
            }
            @Override public double diffractionEdgeY() {
                return diff == null ? 0 : diff.edge.y;
            }
            @Override public double diffractionEdgeZ() {
                return diff == null ? 0 : diff.edge.z;
            }
            @Override public float diffractionPan() { return diffPan; }
            @Override public double speakerX() { return hasSource ? speaker.x() : 0; }
            @Override public double speakerY() { return hasSource ? speaker.y() : 0; }
            @Override public double speakerZ() { return hasSource ? speaker.z() : 0; }
        };
    }

    /**
     * 采样原点(共享层据此判站定、切采样周期):世界声源在用时返回锚点,
     * 否则返回眼位。
     *
     * <p><b>主线程每 tick 调用</b> —— 因此复用同一个数组,不新建。返回值
     * 的持有者只读不存,不存在跨线程共享问题(游戏线程写、游戏线程读)。</p>
     */
    @Override
    public double[] originMeters() {
        try {
            final Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) {
                return null;
            }
            final SpeakerState speaker = SpeakerManager.samplingOrigin(client.level);
            if (speaker != null) {
                ORIGIN[0] = speaker.x();
                ORIGIN[1] = speaker.y();
                ORIGIN[2] = speaker.z();
                return ORIGIN;
            }
            final Vec3 eye = client.player.getEyePosition();
            ORIGIN[0] = eye.x;
            ORIGIN[1] = eye.y;
            ORIGIN[2] = eye.z;
            return ORIGIN;
        } catch (Throwable ignored) {
            return null;   // 采不到就不参与站定判定(退化为快周期)
        }
    }

    /** 站定判定的复用缓冲(仅游戏线程读写)。 */
    private static final double[] ORIGIN = new double[3];

    /** 射线场的统计摘要;{@link #NONE} 即「没采/旷野」。 */
    private static final class GeomStats {
        static final GeomStats NONE = new GeomStats(0f, ListenerSample.NO_HIT, 0f, 0f,
                new float[0], 0f,
                0, new float[0], new float[0], new float[0], new float[0], new float[0],
                new EnergyField());

        final float enclosure;
        final float firstHitMean;
        final float segMean;
        final float reflectivity;
        /** 三频带反射率均值 {低,中,高}:逐频带 T60 的材质驱动。 */
        final float[] bandRho;
        final float reachable;
        final int tapCount;
        final float[] tapDelays;
        final float[] tapGains;
        /** 每条抽头的到达向量(墙→耳)水平分量与水平模长:声像每次采样现算。 */
        final float[] tapArrX;
        final float[] tapArrZ;
        final float[] tapHoriz;
        /** 多频带能量直方图:晚期尾的物理来源。 */
        final EnergyField histogram;

        GeomStats(float enclosure, float firstHitMean, float segMean,
                  float reflectivity, float[] bandRho, float reachable,
                  int tapCount, float[] tapDelays, float[] tapGains,
                  float[] tapArrX, float[] tapArrZ, float[] tapHoriz,
                  EnergyField histogram) {
            this.enclosure = enclosure;
            this.firstHitMean = firstHitMean;
            this.segMean = segMean;
            this.reflectivity = reflectivity;
            this.bandRho = bandRho;
            this.reachable = reachable;
            this.tapCount = tapCount;
            this.tapDelays = tapDelays;
            this.tapGains = tapGains;
            this.tapArrX = tapArrX;
            this.tapArrZ = tapArrZ;
            this.tapHoriz = tapHoriz;
            this.histogram = histogram;
        }
    }

    private static GeomStats castField(Level world, Vec3 origin, Vec3 ear, int rayCount) {
        final int rays = Math.max(32, rayCount);   // 防御:低于 32 条统计无意义
        int firstHits = 0;
        int segCount = 0;
        int reflectHits = 0;
        int bounces = 0;
        int reachable = 0;
        double firstSum = 0;
        double segSum = 0;
        double reflectSum = 0;
        final double[] bandRhoSum = new double[EnergyField.BANDS];

        // 早期反射抽头:每条确认可达的反射记一次(容量 = 射线 × 弹次上限)。
        final int tapCapacity = rays * (MAX_BOUNCES + 1);
        final float[] tapDelays = new float[tapCapacity];
        final float[] tapGains = new float[tapCapacity];
        final float[] tapArrX = new float[tapCapacity];
        final float[] tapArrZ = new float[tapCapacity];
        final float[] tapHoriz = new float[tapCapacity];
        int tapCount = 0;

        // 反射时序相对化的基准:声源激发时直达路程(渲染里直达声零延迟,
        // 全路径延迟要减掉它 —— 否则远处的第一反射会凭空提前几十毫秒)。
        final double directBase = origin.distanceTo(ear);

        final EnergyField histogram = new EnergyField();

        for (int i = 0; i < rays; i++) {
            // 精细化:黄金角逆序 + 纬度抖动。逆序让相邻两轮采样的方向序列
            // 交错(移动时直方图/抽头过渡更连贯);纬度加 1/3 桶的
            // 固定偏移打破高纬度的环状对称(纯斐波那契格的伪影)。
            final double idx = (rays - 1 - i) + 0.5;
            final double longitude = GOLDEN_ANGLE * idx;
            final double latitude = Math.asin((double) idx / rays * 2.0 - 1.0
                    + 0.0033 * Math.sin(idx * 12.9898));
            Vec3 dir = new Vec3(
                    Math.cos(latitude) * Math.cos(longitude),
                    Math.sin(latitude),
                    Math.cos(latitude) * Math.sin(longitude));
            Vec3 from = origin;
            BlockPos ignore = BlockPos.containing(origin);
            double outPath = 0;
            double retention = 1;

            for (int bounce = 0; bounce <= MAX_BOUNCES; bounce++) {
                // 起点必须是 from(上一次的反弹落点),不是 origin —— 从第二弹
                // 起方向已经是反射后的,起点还钉在声源的话命中点与真实几何
                // 无关:段长/累计路径/入桶时刻/反射率统计全部失真。
                BlockHitResult hit = rayCast(world, from, from.add(dir.scale(RAY_RANGE)), ignore);
                if (hit.getType() == HitResult.Type.MISS) {
                    break; // 逃逸到开阔:这条方向没有更多的反射可记
                }
                final double segLen = from.distanceTo(hit.getLocation());
                outPath += segLen;
                if (bounce == 0) {
                    firstHits++;
                    firstSum += segLen;
                } else {
                    segCount++;
                    segSum += segLen;
                }
                final float[] surfaceRho = reflectivity(world, hit);
                reflectHits++;
                reflectSum += (surfaceRho[0] + surfaceRho[1] + surfaceRho[2]) / 3.0;
                for (int band = 0; band < EnergyField.BANDS; band++) {
                    bandRhoSum[band] += surfaceRho[band];
                }
                bounces++;
                // 逐弹幅度保留:√反射率(三带均值)夹进物理幅度区间后连乘 ——
                // 早期反射是「幅度」量,按能量模型连乘会把近墙反射算到听不见
                final double rhoMean = Math.max(0.02, Math.min(2.25,
                        (surfaceRho[0] + surfaceRho[1] + surfaceRho[2]) / 3.0));
                retention *= Math.max(0.15, Math.min(0.92, Math.sqrt(rhoMean)));

                Vec3 normal = normalOf(hit);
                Vec3 surface = hit.getLocation().add(normal.scale(SKIN));
                if (rayCast(world, surface, ear, null).getType() == HitResult.Type.MISS) {
                    reachable++;
                    // 玩家即声源:全路径 = 出发段累计 + 回听者直线;几何按 1/距离(幅度)
                    final double returnDist = surface.distanceTo(ear);
                    final double path = outPath + returnDist;
                    if (tapCount < tapCapacity) {
                        // 时序相对化:渲染里直达声零延迟,抽头必须按「全路径 −
                        // 直达路程」交付,否则远处音箱的每条早反射都凭空晚
                        // directDist/343 秒(ER 与直达/尾部的时间结构错乱)。
                        // 玩家即声源时 directBase=0,与修前逐值一致。
                        tapDelays[tapCount] = (float) ((path - directBase) / SPEED_OF_SOUND);
                        tapGains[tapCount] = (float) (retention * 2.0 / path);
                        final double ax = ear.x - surface.x;
                        final double az = ear.z - surface.z;
                        tapArrX[tapCount] = (float) ax;
                        tapArrZ[tapCount] = (float) az;
                        tapHoriz[tapCount] = (float) Math.sqrt(ax * ax + az * az);
                        tapCount++;
                    }

                    // 多频带能量入桶:能量 = 幅度保留² × 分频带反射率 × 空气吸收
                    // × 几何 1/距离²;时间与抽头同口径(相对直达的到达时刻)——
                    // 直方图喂 T60 拟合,而拟合窗 [−2.5,−0.5]dB 的横轴语义必须
                    // 与渲染里「尾部从直达声之后多久开始」一致。玩家即声源时
                    // directBase=0,与修前逐值一致。负值(直达极远的极端几何)
                    // 由 EnergyField.accumulate 直接忽略。
                    final double arrival = (path - directBase) / SPEED_OF_SOUND;
                    final double geometric = retention * retention * 4.0 / (path * path);
                    for (int band = 0; band < EnergyField.BANDS; band++) {
                        final double air = Math.pow(AIR_ENERGY_PER_METER[band], path);
                        histogram.accumulate(band, arrival, geometric * surfaceRho[band] * air);
                    }
                }

                // 镜面反射:d − 2(d·n)n,从抬离墙面的点继续
                dir = dir.subtract(normal.scale(2.0 * dir.dot(normal)));
                from = surface;
                ignore = hit.getBlockPos();
            }
        }

        if (firstHits == 0) {
            return GeomStats.NONE;
        }
        final float firstMean = (float) (firstSum / firstHits);
        final float segMean = segCount > 0 ? (float) (segSum / segCount) : firstMean;
        final float reflectivity = reflectHits > 0 ? (float) (reflectSum / reflectHits) : DEFAULT_REFLECTIVITY;
        final float[] bandRho = new float[EnergyField.BANDS];
        for (int band = 0; band < EnergyField.BANDS; band++) {
            bandRho[band] = reflectHits > 0 ? (float) (bandRhoSum[band] / reflectHits) : DEFAULT_REFLECTIVITY;
        }
        final float reach = bounces > 0 ? reachable / (float) bounces : 0f;
        return new GeomStats(firstHits / (float) rays, firstMean, segMean, reflectivity,
                bandRho, reach,
                tapCount, tapDelays, tapGains, tapArrX, tapArrZ, tapHoriz, histogram);
    }

    /** 声像 −1(正左)..1(正右):到达方向在玩家右手方向上的投影。 */
    private static float[] computePans(GeomStats stats, float yawRad) {
        final float[] pans = new float[stats.tapCount];
        // 玩家右手方向:朝向水平旋转 −90°(yaw=0 面南时右手在西)。
        final double rightX = -Math.cos(yawRad);
        final double rightZ = -Math.sin(yawRad);
        for (int i = 0; i < stats.tapCount; i++) {
            final double horiz = stats.tapHoriz[i];
            pans[i] = horiz > 1e-6
                    ? (float) Math.max(-1.0, Math.min(1.0,
                            (stats.tapArrX[i] * rightX + stats.tapArrZ[i] * rightZ) / horiz))
                    : 0f;
        }
        return pans;
    }

    /**
     * 体素投射:方块按碰撞箱、流体按自身形状,取更近的命中。走官方的
     * {@code traverseBlocks}(体素 DDA,只查路径经过的格子);空气快路径
     * (isAir 直接放行)与无流体免查是纯执行优化,结果与完整裁剪逐位一致。
     */
    private static BlockHitResult rayCast(Level world, Vec3 from, Vec3 to, BlockPos ignore) {
        return BlockGetter.traverseBlocks(from, to, world, (getter, pos) -> {
            if (pos.equals(ignore)) {
                return null;
            }
            BlockState state = getter.getBlockState(pos);
            if (state.isAir()) {
                return null;
            }
            FluidState fluid = getter.getFluidState(pos);
            VoxelShape shape = ClipContext.Block.COLLIDER.get(state, getter, pos, CollisionContext.empty());
            BlockHitResult blockHit = getter.clipWithInteractionOverride(from, to, pos, shape, state);
            if (fluid.isEmpty()) {
                return blockHit;
            }
            BlockHitResult fluidHit = fluid.getShape(getter, pos).clip(from, to, pos);
            if (fluidHit == null) {
                return blockHit;
            }
            if (blockHit == null) {
                return fluidHit;
            }
            return from.distanceToSqr(blockHit.getLocation()) <= from.distanceToSqr(fluidHit.getLocation())
                    ? blockHit : fluidHit;
        }, getter -> BlockHitResult.miss(to,
                Direction.getApproximateNearest(from.x - to.x, from.y - to.y, from.z - to.z),
                BlockPos.containing(to)));
    }

    private static Vec3 normalOf(BlockHitResult hit) {
        Direction face = hit.getDirection();
        return new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
    }

    /**
     * 直射遮挡量:源→耳直线穿过路径上每个非空气方块记一次遮挡 —— 实心方块
     * 1.0、非实心(栅栏/玻璃/树叶等无完整碰撞箱)0.15(sonicether/Sound-Physics
     * 同款记账)。与反射回测不同,这条不设 SKIN 抬升与提前终止:穿墙的「闷」
     * 就来自把每个挡路的格子都算上。世界未加载区段直接按 MISS 处理(体素
     * DDA 对未加载 chunk 读到空气,与「旷野」一致 —— 防御性降级)。
     */
    private static float directOcclusion(Level world, Vec3 from, Vec3 to) {
        double[] occlusion = {0};
        BlockGetter.traverseBlocks(from, to, world, (getter, pos) -> {
            BlockState state = getter.getBlockState(pos);
            if (state.isAir()) {
                return null;
            }
            VoxelShape shape = ClipContext.Block.COLLIDER.get(state, getter, pos, CollisionContext.empty());
            if (!shape.isEmpty()) {
                // 实心整块 1.0、非完整碰撞箱(栅栏/玻璃板/楼梯)0.15 —— Sound
                // Physics 同款记账。
                occlusion[0] += state.isCollisionShapeFullBlock(getter, pos) ? 1.0 : 0.15;
            }
            // 回调返回 null = 继续推进:把整条路径上的遮挡都记完
            return null;
        }, getter -> BlockHitResult.miss(to, Direction.getApproximateNearest(
                from.x - to.x, from.y - to.y, from.z - to.z), BlockPos.containing(to)));
        return (float) Math.min(6.0, occlusion[0]);
    }

    /**
     * 一阶绕射路径搜索:直达被挡时,找一条「源 → 遮挡边缘 → 耳」的折线,
     * 让声音能拐过墙角/门框继续传播。
     *
     * <p>做法(与 Wwise Reflect 的 diffraction、Steam Audio 的 pathing 同族):
     * 从遮挡最重的那个方块中心作球面采样 —— 采样方向只在<b>垂直于源-耳连线</b>
     * 的平面附近(边缘就在那个圈上)。对每个候选点验两段直线都无遮挡,
     * 取绕射代价最小的一个。候选点落在方块棱角附近时就是物理上的「边缘」,
     * 不需要真的去解析几何棱线(体素世界里棱线本来就是方块的边)。</p>
     *
     * <p>代价:候选数 {@link #DIFFRACTION_DIRS} × 2 次体素投射 ≈ 32 次,只在
     * <b>直达确实被挡</b>时才跑,而且只在采样线程上(200ms 一次)。开阔地
     * 完全不会进入这里。</p>
     *
     * @return 绕射结果;找不到合适路径返回 {@code null}(退化为纯穿透)
     */

    private static DiffractionPath findDiffraction(Level world, Vec3 source, Vec3 ear) {
        final Vec3 straight = ear.subtract(source);
        final double straightLen = straight.length();
        if (straightLen < 1e-3) {
            return null;
        }
        // 沿直线找出第一个实心遮挡格:边缘一定贴着它。
        BlockPos blocker = firstBlocker(world, source, ear);
        if (blocker == null) {
            return null;
        }
        final Vec3 center = Vec3.atCenterOf(blocker);

        // 围绕遮挡块采样候选边缘点:球面上的方向(斐波那契),半径取
        // 遮挡块对角线的一半再外扩一点 —— 让候选点落在块表面之外,
        // 避免起点仍嵌在方块里被判成遮挡。
        final double radius = 0.5 * Math.sqrt(3.0) + DIFFRACTION_SKIN;
        DiffractionPath best = null;
        double bestCost = Double.MAX_VALUE;

        for (int i = 0; i < DIFFRACTION_DIRS; i++) {
            // 斐波那契球面方向
            final double idx = i + 0.5;
            final double longitude = GOLDEN_ANGLE * i;
            final double latitude = Math.asin(idx / DIFFRACTION_DIRS * 2.0 - 1.0);
            final double dx = Math.cos(latitude) * Math.cos(longitude);
            final double dy = Math.sin(latitude);
            final double dz = Math.cos(latitude) * Math.sin(longitude);
            final Vec3 edge = center.add(dx * radius, dy * radius, dz * radius);

            // 只在「不在源-耳直线附近」的候选上找:直线上的点当然通畅,
            // 但那不是绕射,是穿透。
            final double edgeToLine = distanceToSegment(edge, source, ear);
            if (edgeToLine < DIFFRACTION_MIN_OFFSET) {
                continue;
            }
            // 两段都要通畅(允许穿非实心方块:栅栏/树叶不挡绕射)
            if (hasSolidBlocker(world, source, edge) || hasSolidBlocker(world, edge, ear)) {
                continue;
            }
            final double seg1 = source.distanceTo(edge);
            final double seg2 = edge.distanceTo(ear);
            final double pathLen = seg1 + seg2;
            final double excess = Math.max(0.0, pathLen - straightLen);
            if (excess > DIFFRACTION_MAX_EXCESS) {
                continue;   // 绕太远,听不见,也失去「从墙角过来」的指向性
            }
            final double bend = bendDegrees(source, edge, ear);
            // 代价 = 额外出程 + 折角惩罚:更近、更「直」的缺口赢。
            final double cost = excess + DIFFRACTION_BEND_WEIGHT * (bend / 180.0);
            if (cost < bestCost) {
                bestCost = cost;
                best = new DiffractionPath(edge, pathLen, excess, bend);
            }
        }
        return best;
    }

    /** 绕射路径的结果(不可变,采样线程内用完即弃)。 */
    private static final class DiffractionPath {
        final Vec3 edge;
        final double pathMeters;
        final double excessMeters;
        final double bendDegrees;

        DiffractionPath(Vec3 edge, double pathMeters, double excessMeters, double bendDegrees) {
            this.edge = edge;
            this.pathMeters = pathMeters;
            this.excessMeters = excessMeters;
            this.bendDegrees = bendDegrees;
        }
    }

    /** 沿源→耳直线找到第一个实心遮挡块;没有返回 null。 */

    private static BlockPos firstBlocker(Level world, Vec3 from, Vec3 to) {
        final BlockPos[] found = {null};
        BlockGetter.traverseBlocks(from, to, world, (getter, pos) -> {
            final BlockState state = getter.getBlockState(pos);
            if (!state.isAir() && state.isCollisionShapeFullBlock(getter, pos)) {
                found[0] = pos.immutable();
                return BlockPos.containing(to);   // 非 null = 提前终止
            }
            return null;
        }, getter -> null);
        return found[0];
    }

    /** 两段之间是否有实心整块挡住(非完整碰撞箱不算:栅栏/玻璃/树叶放行)。 */
    private static boolean hasSolidBlocker(Level world, Vec3 from, Vec3 to) {
        final boolean[] blocked = {false};
        BlockGetter.traverseBlocks(from, to, world, (getter, pos) -> {
            final BlockState state = getter.getBlockState(pos);
            if (!state.isAir() && state.isCollisionShapeFullBlock(getter, pos)) {
                blocked[0] = true;
                return BlockPos.containing(to);
            }
            return null;
        }, getter -> null);
        return blocked[0];
    }

    /** 点到线段的最短距离(绕射候选点必须离直线够远,否则那是穿透不是绕射)。 */
    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        final Vec3 ab = b.subtract(a);
        final double len2 = ab.lengthSqr();
        if (len2 < 1e-9) {
            return p.distanceTo(a);
        }
        double t = p.subtract(a).dot(ab) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    /** 折角(度):源→边缘 与 边缘→耳 两段方向的夹角。 */
    private static double bendDegrees(Vec3 source, Vec3 edge, Vec3 ear) {
        final Vec3 a = edge.subtract(source);
        final Vec3 b = ear.subtract(edge);
        final double la = a.length();
        final double lb = b.length();
        if (la < 1e-6 || lb < 1e-6) {
            return 0.0;
        }
        final double cos = Math.max(-1.0, Math.min(1.0, a.dot(b) / (la * lb)));
        return Math.toDegrees(Math.acos(cos));
    }

    /**
     * 分频带反射率表 {低,中,高}(0..约 1.5,硬质石材大于 1):按方块的 SoundType
     * 分档。频带物理直觉:高频容易被多孔/软质表面吸掉(羊毛、苔藓、沙),低频
     * 绕射强、几乎只被专门吸声结构吃掉;石头全频亮,木头高频略哑。
     */
    private static float[] reflectivity(Level world, BlockHitResult hit) {
        BlockState state = world.getBlockState(hit.getBlockPos());
        if (!state.getFluidState().isEmpty()) {
            return new float[] {0.7f, 0.6f, 0.5f};
        }
        final SoundType sound = state.getSoundType();
        if (sound == SoundType.STONE || sound == SoundType.DEEPSLATE
                || sound == SoundType.NETHER_BRICKS || sound == SoundType.BASALT
                || sound == SoundType.TUFF || sound == SoundType.CALCITE
                || sound == SoundType.AMETHYST || sound == SoundType.BONE_BLOCK
                || sound == SoundType.NETHERITE_BLOCK) {
            return new float[] {1.5f, 1.5f, 1.45f};
        }
        if (sound == SoundType.METAL || sound == SoundType.COPPER) {
            return new float[] {1.25f, 1.25f, 1.3f};
        }
        if (sound == SoundType.NETHERRACK) {
            return new float[] {1.15f, 1.1f, 1.0f};
        }
        if (sound == SoundType.GLASS) {
            return new float[] {0.8f, 0.75f, 0.75f};
        }
        if (sound == SoundType.WOOD || sound == SoundType.STEM) {
            return new float[] {0.65f, 0.6f, 0.5f};
        }
        if (sound == SoundType.GRAVEL || sound == SoundType.GRASS) {
            return new float[] {0.35f, 0.3f, 0.2f};
        }
        if (sound == SoundType.SAND || sound == SoundType.SOUL_SAND
                || sound == SoundType.SOUL_SOIL || sound == SoundType.CORAL_BLOCK) {
            return new float[] {0.25f, 0.2f, 0.12f};
        }
        if (sound == SoundType.SNOW) {
            return new float[] {0.18f, 0.15f, 0.08f};
        }
        if (sound == SoundType.WOOL || sound == SoundType.MOSS || sound == SoundType.HONEY_BLOCK) {
            return new float[] {0.2f, 0.1f, 0.03f};
        }
        // 未知方块兜底 0.65(中值偏高):进到这里的多数是模组方块/建筑
        // 材质(石材混凝土类),物理反射率中上;0.5 会让大型模组建筑
        // 的房间整体偏闷。
        return new float[] {0.65f, 0.65f, 0.60f};
    }
}
