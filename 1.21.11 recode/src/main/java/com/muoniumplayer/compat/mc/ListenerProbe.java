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

/**
 * 听者环境的采样 shim:把当前游戏状态翻译成 common 侧的 {@link ListenerSample}。
 * 几何部分用斐波那契球面射线:从听者向球面均匀撒 {@link #RAY_COUNT} 条方向
 * (经度走黄金角、纬度均匀分层,天然铺满球面),每条投射到第一次撞上的方块或
 * 流体面,再按镜面反射最多 {@link #MAX_BOUNCES} 次;每次命中沿法线抬起一点向
 * 听者回测「这条反射能不能无遮挡到达」。确认可达的反射逐条记进<b>早期反射
 * 抽头</b>(延迟/相对幅度/到达方向);围合度、房间尺度、墙面反射率、可达比例
 * 是这批射线的统计摘要;<b>多频带能量直方图</b>按 3 频带 × 20ms 桶累积每条
 * 反射的能量到达。世界状态住在树侧,common 只认纯数据。
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

    /** 射线条数:后台线程无预算压力;512 条角分辨率 9°(Steam Audio
     *  实时档同级),直方图每桶 ~32 条命中,T60 拟合与抽头密度达到
     *  实时实现的高配。 */
    private static final int RAY_COUNT = 512;
    /** 首次命中之后最多再弹几次:直方图窗 2s ⇒ 路径要追到 ~600m 才不缺尾部;
     *  3 弹 × 28m 的路径上限只有 ~150m(0.44s),晚期尾全是真空,T60 拟合
     *  读到的只是「回声到达密度」(实机日志:洞穴 ρ=1.30 拟合出 0.38s)。
     *  12 弹 × 40m ≈ 500m 路径,覆盖 1.4s 尾。 */
    private static final int MAX_BOUNCES = 12;
    /** 单段射程(格):96 格 = 2s 声程,与直方图窗对齐 —— 旷野/大峡谷的
     *  远墙一次反射(真实中 100-400ms 后可闻)也能被采到,而不是被
     *  「40 格内没墙 = 逃逸」误判成空旷。 */
    private static final double RAY_RANGE = 96.0;
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
    /** 站定期间每 N 次采样强制重算几何(200ms × 25 = 5s):方块可能变了。 */
    private static final int STALE_EVERY = 25;

    // ---- 分频带空气吸收(能量比例 @ 观察窗内典型路程):高频走得远被吃得多 ----
    /** 每米能量衰减系数(近似 26.3 标准):{低,中,高}。 */
    private static final double[] AIR_ENERGY_PER_METER = {0.9990, 0.9970, 0.9920};

    /** 站定缓存(仅采样线程触碰):上次几何 + 上次眼位/维度 + 采样计数。 */
    private static GeomStats cachedStats;
    private static Vec3 cachedEye;
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
            cachedEye = null;
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

        final GeomStats stats;
        if (!AcousticsRuntime.wantsEnclosure()) {
            stats = GeomStats.NONE;
            cachedStats = null;
            cachedEye = null;
            cachedWorld = null;
            capturesSinceCast = 0;
        } else {
            final boolean moved = cachedEye == null || cachedWorld != world
                    || cachedEye.distanceToSqr(eye) > STATIONARY_SQ;
            capturesSinceCast++;
            if (moved || cachedStats == null || capturesSinceCast >= STALE_EVERY) {
                stats = castField(world, eye);
                cachedStats = stats;
                cachedEye = eye;
                cachedWorld = world;
                capturesSinceCast = 0;
            } else {
                stats = cachedStats;
            }
        }

        // 玩家即声源:声像 = 到达方向在玩家右手方向上的水平投影,每次采样按当前
        // 视角重算(站定时几何走缓存,这一步是唯一的逐帧几何计算)。
        final float yawRad = client.player.getYRot() * ((float) Math.PI / 180f);
        final float[] pans = computePans(stats, yawRad);

        return new ListenerSample() {
            @Override public boolean submerged() { return submerged; }
            @Override public boolean openSky() { return openSky; }
            @Override public float enclosure() { return stats.enclosure; }
            @Override public float precipitation() { return precipitation; }
            @Override public float firstHitMeanMeters() { return stats.firstHitMean; }
            @Override public float bounceSegmentMeanMeters() { return stats.segMean; }
            @Override public float meanReflectivity() { return stats.reflectivity; }
            @Override public float reachableFraction() { return stats.reachable; }
            @Override public int tapCount() { return stats.tapCount; }
            @Override public float[] tapDelaysSec() { return stats.tapDelays; }
            @Override public float[] tapGains() { return stats.tapGains; }
            @Override public float[] tapPans() { return pans; }
            @Override public EnergyField bandHistogram() { return stats.histogram; }
        };
    }

    /** 射线场的统计摘要;{@link #NONE} 即「没采/旷野」。 */
    private static final class GeomStats {
        static final GeomStats NONE = new GeomStats(0f, ListenerSample.NO_HIT, 0f, 0f, 0f,
                0, new float[0], new float[0], new float[0], new float[0], new float[0],
                new EnergyField());

        final float enclosure;
        final float firstHitMean;
        final float segMean;
        final float reflectivity;
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
                  float reflectivity, float reachable,
                  int tapCount, float[] tapDelays, float[] tapGains,
                  float[] tapArrX, float[] tapArrZ, float[] tapHoriz,
                  EnergyField histogram) {
            this.enclosure = enclosure;
            this.firstHitMean = firstHitMean;
            this.segMean = segMean;
            this.reflectivity = reflectivity;
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

    private static GeomStats castField(Level world, Vec3 eye) {
        int firstHits = 0;
        int segCount = 0;
        int reflectHits = 0;
        int bounces = 0;
        int reachable = 0;
        double firstSum = 0;
        double segSum = 0;
        double reflectSum = 0;

        // 早期反射抽头:每条确认可达的反射记一次(容量 = 射线 × 弹次上限)。
        final int tapCapacity = RAY_COUNT * (MAX_BOUNCES + 1);
        final float[] tapDelays = new float[tapCapacity];
        final float[] tapGains = new float[tapCapacity];
        final float[] tapArrX = new float[tapCapacity];
        final float[] tapArrZ = new float[tapCapacity];
        final float[] tapHoriz = new float[tapCapacity];
        int tapCount = 0;

        final EnergyField histogram = new EnergyField();

        for (int i = 0; i < RAY_COUNT; i++) {
            // 精细化:黄金角逆序 + 纬度抖动。逆序让相邻两轮采样的方向序列
            // 交错(玩家移动时直方图/抽头过渡更连贯);纬度加 1/3 桶的
            // 固定偏移打破高纬度的环状对称(纯斐波那契格的伪影)。
            final double idx = (RAY_COUNT - 1 - i) + 0.5;
            final double longitude = GOLDEN_ANGLE * idx;
            final double latitude = Math.asin((double) idx / RAY_COUNT * 2.0 - 1.0
                    + 0.0033 * Math.sin(idx * 12.9898));
            Vec3 dir = new Vec3(
                    Math.cos(latitude) * Math.cos(longitude),
                    Math.sin(latitude),
                    Math.cos(latitude) * Math.sin(longitude));
            Vec3 origin = eye;
            BlockPos ignore = BlockPos.containing(eye);
            double outPath = 0;
            double retention = 1;

            for (int bounce = 0; bounce <= MAX_BOUNCES; bounce++) {
                BlockHitResult hit = rayCast(world, origin, origin.add(dir.scale(RAY_RANGE)), ignore);
                if (hit.getType() == HitResult.Type.MISS) {
                    break; // 逃逸到开阔:这条方向没有更多的反射可记
                }
                final double segLen = origin.distanceTo(hit.getLocation());
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
                bounces++;
                // 逐弹幅度保留:√反射率(三带均值)夹进物理幅度区间后连乘 ——
                // 早期反射是「幅度」量,按能量模型连乘会把近墙反射算到听不见
                final double rhoMean = Math.max(0.02, Math.min(2.25,
                        (surfaceRho[0] + surfaceRho[1] + surfaceRho[2]) / 3.0));
                retention *= Math.max(0.15, Math.min(0.92, Math.sqrt(rhoMean)));

                Vec3 normal = normalOf(hit);
                Vec3 surface = hit.getLocation().add(normal.scale(SKIN));
                if (rayCast(world, surface, eye, null).getType() == HitResult.Type.MISS) {
                    reachable++;
                    // 玩家即声源:全路径 = 出发段累计 + 回听者直线;几何按 1/距离(幅度)
                    final double returnDist = surface.distanceTo(eye);
                    final double path = outPath + returnDist;
                    if (tapCount < tapCapacity) {
                        tapDelays[tapCount] = (float) (path / SPEED_OF_SOUND);
                        tapGains[tapCount] = (float) (retention * 2.0 / path);
                        final double ax = eye.x - surface.x;
                        final double az = eye.z - surface.z;
                        tapArrX[tapCount] = (float) ax;
                        tapArrZ[tapCount] = (float) az;
                        tapHoriz[tapCount] = (float) Math.sqrt(ax * ax + az * az);
                        tapCount++;
                    }

                    // 多频带能量入桶:能量 = 幅度保留² × 分频带反射率 × 空气吸收
                    // × 几何 1/距离²;时间 = 全路径 ÷ 声速。幅度保留是三带共用的
                    // 「几何+平均表面」项,分频带差异由反射率与空气吸收给出。
                    final double arrival = path / SPEED_OF_SOUND;
                    final double geometric = retention * retention * 4.0 / (path * path);
                    for (int band = 0; band < EnergyField.BANDS; band++) {
                        final double air = Math.pow(AIR_ENERGY_PER_METER[band], path);
                        histogram.accumulate(band, arrival, geometric * surfaceRho[band] * air);
                    }
                }

                // 镜面反射:d − 2(d·n)n,从抬离墙面的点继续
                dir = dir.subtract(normal.scale(2.0 * dir.dot(normal)));
                origin = surface;
                ignore = hit.getBlockPos();
            }
        }

        if (firstHits == 0) {
            return GeomStats.NONE;
        }
        final float firstMean = (float) (firstSum / firstHits);
        final float segMean = segCount > 0 ? (float) (segSum / segCount) : firstMean;
        final float reflectivity = reflectHits > 0 ? (float) (reflectSum / reflectHits) : DEFAULT_REFLECTIVITY;
        final float reach = bounces > 0 ? reachable / (float) bounces : 0f;
        return new GeomStats(firstHits / (float) RAY_COUNT, firstMean, segMean, reflectivity, reach,
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
