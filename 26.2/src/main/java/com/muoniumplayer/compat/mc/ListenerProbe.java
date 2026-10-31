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
import com.muoniumplayer.core.ncm.music.acoustics.ListenerSample;

/**
 * 听者环境的采样 shim:把当前游戏状态翻译成 common 侧的 {@link ListenerSample}。
 * 几何部分用斐波那契球面射线:从听者向球面均匀撒 {@link #RAY_COUNT} 条方向
 * (经度走黄金角、纬度均匀分层,天然铺满球面),每条投射到第一次撞上的方块或
 * 流体面,再按镜面反射最多 {@link #MAX_BOUNCES} 次;每次命中沿法线抬起一点向
 * 听者回测「这条反射能不能无遮挡到达」。确认可达的反射逐条记进<b>早期反射
 * 抽头</b>(延迟/相对能量/到达方向);围合度、房间尺度、墙面反射率、可达比例
 * 是这批射线的统计摘要。世界状态住在树侧,common 只认纯数据。
 *
 * <h2>性能契约(主线程每 200ms 一次,不能顿)</h2>
 * <ul>
 *   <li><b>空气快路径</b>:投射逐方块先查 isAir,空气直接放行 —— 省掉空气格上
 *     的流体查询与碰撞箱裁剪,旷野长射线的大头开销就在这里。结果逐位不变。</li>
 *   <li><b>站定缓存</b>:听者没挪窝(0.5 格内)就不重打射线,几何与抽头照用
 *     上一次的结果,只按当前视角重算声像 —— 听歌时人基本是站着的,这是常态
 *     零开销;每 {@link #STALE_EVERY} 次采样强制重算一次,防方块变动(开关门、
 *     挖方块)漏检。走动时每帧照常重打,与旧版一致。</li>
 * </ul>
 *
 * <p>调用约束:必须在<b>主线程</b>上跑(client tick 即是);采不到(主菜单、
 * 无玩家)返回 {@code null},运行时会把目标量回静息。</p>
 */
public final class ListenerProbe implements AcousticsRuntime.ListenerSampler {

    /** 射线条数:球面均匀性由斐波那契格保证,24 条对统计摘要已足够稳。 */
    private static final int RAY_COUNT = 24;
    /** 首次命中之后最多再弹几次。 */
    private static final int MAX_BOUNCES = 3;
    /** 单段射程(格):再远的第一反射对音乐没有听感意义。 */
    private static final double RAY_RANGE = 28.0;
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
    /** 站定期间每 N 次采样强制重算几何(200ms × 15 = 3s):方块可能变了。 */
    private static final int STALE_EVERY = 15;

    /** 站定缓存(仅主线程触碰):上次几何 + 上次眼位/维度 + 采样计数。 */
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
        };
    }

    /** 射线场的统计摘要;{@link #NONE} 即「没采/旷野」。 */
    private static final class GeomStats {
        static final GeomStats NONE = new GeomStats(0f, ListenerSample.NO_HIT, 0f, 0f, 0f,
                0, new float[0], new float[0], new float[0], new float[0], new float[0]);

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

        GeomStats(float enclosure, float firstHitMean, float segMean,
                  float reflectivity, float reachable,
                  int tapCount, float[] tapDelays, float[] tapGains,
                  float[] tapArrX, float[] tapArrZ, float[] tapHoriz) {
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

        for (int i = 0; i < RAY_COUNT; i++) {
            final double longitude = GOLDEN_ANGLE * i;
            final double latitude = Math.asin((double) i / RAY_COUNT * 2.0 - 1.0);
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
                final float surfaceRho = reflectivity(world, hit);
                reflectHits++;
                reflectSum += surfaceRho;
                bounces++;
                // 逐弹能量保留(SPR 同式:漫反射折算)
                retention *= 0.25 * (surfaceRho * 0.75 + 0.25);

                Vec3 normal = normalOf(hit);
                Vec3 surface = hit.getLocation().add(normal.scale(SKIN));
                if (rayCast(world, surface, eye, null).getType() == HitResult.Type.MISS) {
                    reachable++;
                    // 玩家即声源:全路径 = 出发段累计 + 回听者直线;球面展开按 2m 参考
                    final double returnDist = surface.distanceTo(eye);
                    final double path = outPath + returnDist;
                    if (tapCount < tapCapacity) {
                        tapDelays[tapCount] = (float) (path / SPEED_OF_SOUND);
                        tapGains[tapCount] = (float) (retention * 4.0 / (path * path));
                        final double ax = eye.x - surface.x;
                        final double az = eye.z - surface.z;
                        tapArrX[tapCount] = (float) ax;
                        tapArrZ[tapCount] = (float) az;
                        tapHoriz[tapCount] = (float) Math.sqrt(ax * ax + az * az);
                        tapCount++;
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
                tapCount, tapDelays, tapGains, tapArrX, tapArrZ, tapHoriz);
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
     * 反射率表(0..约 1.5,硬质石材大于 1):按方块的 SoundType 分档 ——
     * 石材/金属亮,羊毛苔藓吸声,水体是强反射镜。数值与材质的「软硬」直觉一致。
     */
    private static float reflectivity(Level world, BlockHitResult hit) {
        BlockState state = world.getBlockState(hit.getBlockPos());
        if (!state.getFluidState().isEmpty()) {
            return 0.6f;
        }
        final SoundType sound = state.getSoundType();
        if (sound == SoundType.STONE || sound == SoundType.DEEPSLATE
                || sound == SoundType.NETHER_BRICKS || sound == SoundType.BASALT
                || sound == SoundType.TUFF || sound == SoundType.CALCITE
                || sound == SoundType.AMETHYST || sound == SoundType.BONE_BLOCK
                || sound == SoundType.NETHERITE_BLOCK) {
            return 1.5f;
        }
        if (sound == SoundType.METAL || sound == SoundType.COPPER) {
            return 1.25f;
        }
        if (sound == SoundType.NETHERRACK) {
            return 1.1f;
        }
        if (sound == SoundType.GLASS) {
            return 0.75f;
        }
        if (sound == SoundType.WOOD || sound == SoundType.STEM) {
            return 0.4f;
        }
        if (sound == SoundType.GRAVEL || sound == SoundType.GRASS) {
            return 0.3f;
        }
        if (sound == SoundType.SAND || sound == SoundType.SOUL_SAND
                || sound == SoundType.SOUL_SOIL || sound == SoundType.CORAL_BLOCK) {
            return 0.2f;
        }
        if (sound == SoundType.SNOW) {
            return 0.15f;
        }
        if (sound == SoundType.WOOL || sound == SoundType.MOSS || sound == SoundType.HONEY_BLOCK) {
            return 0.1f;
        }
        return DEFAULT_REFLECTIVITY;
    }
}
