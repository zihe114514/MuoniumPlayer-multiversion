package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 球面射线场核心:从声源向球面撒射线,镜面弹射,把结果汇总成 {@link Stats}。
 *
 * <p>世界访问全部经 {@link Tracer} 抽象,本类不认任何游戏类型 —— 七个版本树的
 * {@code ListenerProbe} 只负责把体素投射适配成 Tracer,统计与物理判定只此一份
 * (也因此能用合成体素世界做无头测试)。</p>
 *
 * <h2>相对旧实现新增的物理量</h2>
 * <ul>
 *   <li><b>晚期围合度</b>({@link Stats#trapped}):按「一条射线在逃逸前弹了几次」
 *       加权。地板只给一次反射、反射后朝天逃逸,权重 0;房间/矿洞里射线反复弹射,
 *       权重 1。旧的「首命中占比」把脚下的地面也算成墙,旷野的围合度因此恒约 0.5,
 *       石头地板上就长出大厅混响。</li>
 *   <li><b>逃逸率</b>({@link Stats#escape}):每次命中之后,下一段射线飞向开阔
 *       (射程内无命中)的概率。它是能量泄漏项:有效每弹保留 = 反射率 × (1 − 逃逸率)。</li>
 *   <li><b>逐频带能量连乘</b>:直方图的能量是该射线沿途每次反射的三带反射率之积
 *       (限幅到物理上限),羊毛房的高频尾因此逐弹快于中频,不再靠均值近似。</li>
 *   <li>能量跌破 {@link #ENERGY_CUTOFF} 的射线提前终止,吸声房间不再空耗体素投射。</li>
 * </ul>
 */
public final class AcousticRayField {

    private AcousticRayField() {
    }

    /** 首次命中之后最多再弹几次。 */
    public static final int MAX_BOUNCES = 16;
    /** 单段射程(格)。 */
    public static final double RAY_RANGE = 160.0;
    /** 反射/回测起点沿法线抬离墙面的距离。 */
    private static final double SKIN = 0.01;
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    private static final double SPEED_OF_SOUND = 343.0;
    private static final float DEFAULT_REFLECTIVITY = 0.5f;
    /** 每弹能量保留的物理上限:表里石材的 ρ>1 是响度标定值,不是能量增益。 */
    private static final double ENERGY_REFLECT_MAX = 0.97;
    private static final double ENERGY_REFLECT_MIN = 0.02;
    /** 逐频带能量乘积(未含几何)低于此值的射线提前终止。 */
    public static final double ENERGY_CUTOFF = 2e-4;
    /** 一条射线弹了多少次算「完全困在空间里」:晚期围合权重 = (弹数−1)/此值,封顶 1。 */
    private static final double TRAP_FULL_HITS = 4.0;
    /** 分频带空气吸收(能量比例/米):{低,中,高}。 */
    private static final double[] AIR_ENERGY_PER_METER = {0.9990, 0.9970, 0.9920};
    private static final double AIR_HF_CUTOFF_HZ = 8000.0;

    /** 体素世界的投射抽象。所有坐标为世界米。 */
    public interface Tracer {
        /** 点所在方块的身份键(射线起点所在方块不参与首次命中);无意义可返回 null。 */
        Object blockKeyAt(double x, double y, double z);

        /**
         * 沿线段 from→to 的最近命中;射程内无命中返回 null。命中键等于
         * {@code ignoreKey}(非 null)的方块被跳过。
         */
        Hit cast(double fx, double fy, double fz, double tx, double ty, double tz, Object ignoreKey);

        /** 方块的三频带反射率 {低,中,高}(按材质表,硬质石材可大于 1)。 */
        default void reflectivity(Object blockKey, float[] outBands) {
            final float[] values = reflectivity(blockKey);
            if (values == null || values.length < EnergyField.BANDS) {
                outBands[0] = outBands[1] = outBands[2] = DEFAULT_REFLECTIVITY;
            } else {
                System.arraycopy(values, 0, outBands, 0, EnergyField.BANDS);
            }
        }

        /** 分配型旧桥;高频采样器应覆盖 {@link #reflectivity(Object, float[])}。 */
        default float[] reflectivity(Object blockKey) {
            return null;
        }
    }

    /** 一次命中:位置、面法线(整数轴向)、方块键。 */
    public static final class Hit {
        public final double x;
        public final double y;
        public final double z;
        public final int nx;
        public final int ny;
        public final int nz;
        public final Object key;

        public Hit(double x, double y, double z, int nx, int ny, int nz, Object key) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.key = key;
        }
    }

    /** 射线场的统计摘要;{@link #NONE} 即「没采/旷野」。 */
    public static final class Stats {
        public static final Stats NONE = new Stats(0f, 0f, 0f, 0, ListenerSample.NO_HIT, 0f, 0f,
                new float[EnergyField.BANDS], 0f, 0,
                new float[0], new float[0], new float[0], new float[0], new float[0], new float[0],
                new EnergyField());

        /** 首次命中占比(含地面)。 */
        public final float enclosure;
        /** 晚期围合度:逃逸前反复弹射的射线占比(加权)。 */
        public final float trapped;
        /** 每次命中后飞向开阔的概率。 */
        public final float escape;
        /** Total successful outgoing segments after first hit, including final miss. */
        public final int escapeTransitions;
        public final float firstHitMean;
        public final float segMean;
        public final float reflectivity;
        public final float[] bandRho;
        public final float reachable;
        public final int tapCount;
        public final float[] tapDelays;
        public final float[] tapGains;
        public final float[] tapHighRatio;
        /** 每条抽头的到达向量(墙→耳)水平分量与模长:声像每次采样按视角现算。 */
        public final float[] tapArrX;
        public final float[] tapArrZ;
        public final float[] tapHoriz;
        public final EnergyField histogram;

        Stats(float enclosure, float trapped, float escape, int escapeTransitions,
              float firstHitMean, float segMean,
              float reflectivity, float[] bandRho, float reachable, int tapCount,
              float[] tapDelays, float[] tapGains, float[] tapHighRatio,
              float[] tapArrX, float[] tapArrZ, float[] tapHoriz, EnergyField histogram) {
            this.enclosure = enclosure;
            this.trapped = trapped;
            this.escape = escape;
            this.escapeTransitions = escapeTransitions;
            this.firstHitMean = firstHitMean;
            this.segMean = segMean;
            this.reflectivity = reflectivity;
            this.bandRho = bandRho;
            this.reachable = reachable;
            this.tapCount = tapCount;
            this.tapDelays = tapDelays;
            this.tapGains = tapGains;
            this.tapHighRatio = tapHighRatio;
            this.tapArrX = tapArrX;
            this.tapArrZ = tapArrZ;
            this.tapHoriz = tapHoriz;
            this.histogram = histogram;
        }
    }

    /** 从 origin 向球面撒 rayCount 条射线,回测终点为 ear。 */
    public static Stats cast(Tracer world, double ox, double oy, double oz,
                             double ex, double ey, double ez, int rayCount) {
        final int rays = Math.max(32, rayCount);
        int firstHits = 0;
        int segCount = 0;
        int reflectHits = 0;
        int escapeTransitions = 0;
        int escapedPostHit = 0;
        int reachable = 0;
        double trappedWeight = 0;
        double firstSum = 0;
        double segSum = 0;
        double reflectSum = 0;
        final double[] bandRhoSum = new double[EnergyField.BANDS];
        final float[] tapDelays = new float[Math.multiplyExact(rays, MAX_BOUNCES + 1)];
        final float[] tapGains = new float[tapDelays.length];
        final float[] tapHighRatio = new float[tapDelays.length];
        final float[] tapArrX = new float[tapDelays.length];
        final float[] tapArrZ = new float[tapDelays.length];
        final float[] tapHoriz = new float[tapDelays.length];
        int tapCount = 0;

        // 渲染里直达声零延迟:抽头/直方图的到达时刻要减去直达路程
        final double directBase = Math.sqrt((ox - ex) * (ox - ex) + (oy - ey) * (oy - ey)
                + (oz - ez) * (oz - ez));
        final EnergyField histogram = new EnergyField();
        final double[] eRet = new double[EnergyField.BANDS];
        final float[] rho = new float[EnergyField.BANDS];

        for (int i = 0; i < rays; i++) {
            // 黄金角逆序 + 纬度微抖:相邻两轮方向序列交错,打破纯斐波那契格的环状伪影
            final double idx = (rays - 1 - i) + 0.5;
            final double longitude = GOLDEN_ANGLE * idx;
            final double latitude = Math.asin(Math.max(-1.0, Math.min(1.0,
                    idx / rays * 2.0 - 1.0 + 0.0033 * Math.sin(idx * 12.9898))));
            double dx = Math.cos(latitude) * Math.cos(longitude);
            double dy = Math.sin(latitude);
            double dz = Math.cos(latitude) * Math.sin(longitude);
            double fx = ox;
            double fy = oy;
            double fz = oz;
            Object ignore = world.blockKeyAt(ox, oy, oz);
            double outPath = 0;
            double retention = 1;
            double energyReach = 1;
            eRet[0] = eRet[1] = eRet[2] = 1.0;
            int hits = 0;
            Object previousHitKey = null;
            int previousNx = 0, previousNy = 0, previousNz = 0;
            double previousDx = 0, previousDy = 0, previousDz = 0;

            for (int bounce = 0; bounce <= MAX_BOUNCES; bounce++) {
                final Hit hit = world.cast(fx, fy, fz,
                        fx + dx * RAY_RANGE, fy + dy * RAY_RANGE, fz + dz * RAY_RANGE, ignore);
                if (hit == null) {
                    if (bounce > 0) {
                        escapeTransitions++;
                        escapedPostHit++;
                    }
                    break;
                }
                if (bounce > 0) {
                    escapeTransitions++;
                }
                final boolean rehitSameFace = hit.key != null && hit.key.equals(previousHitKey)
                        && hit.nx == previousNx && hit.ny == previousNy && hit.nz == previousNz
                        && dx * previousDx + dy * previousDy + dz * previousDz > -0.25;
                if (rehitSameFace) {
                    energyReach *= ENERGY_CUTOFF;
                    break;
                }
                previousHitKey = hit.key;
                previousNx = hit.nx;
                previousNy = hit.ny;
                previousNz = hit.nz;
                previousDx = dx;
                previousDy = dy;
                previousDz = dz;
                final double segLen = Math.sqrt((hit.x - fx) * (hit.x - fx)
                        + (hit.y - fy) * (hit.y - fy) + (hit.z - fz) * (hit.z - fz));
                outPath += segLen;
                if (bounce == 0) {
                    firstHits++;
                    firstSum += segLen;
                } else {
                    segCount++;
                    segSum += segLen;
                }
                world.reflectivity(hit.key, rho);
                reflectHits++;
                hits++;
                reflectSum += (rho[0] + rho[1] + rho[2]) / 3.0;
                double rhoMean = 0;
                for (int band = 0; band < EnergyField.BANDS; band++) {
                    bandRhoSum[band] += rho[band];
                    eRet[band] *= Math.max(ENERGY_REFLECT_MIN, Math.min(ENERGY_REFLECT_MAX, rho[band]));
                    rhoMean += rho[band];
                }
                rhoMean = Math.max(0.02, Math.min(2.25, rhoMean / EnergyField.BANDS));
                // 早期抽头走「幅度」模型:逐弹 √反射率连乘
                retention *= Math.max(0.15, Math.min(0.92, Math.sqrt(rhoMean)));

                final double sx = hit.x + hit.nx * SKIN;
                final double sy = hit.y + hit.ny * SKIN;
                final double sz = hit.z + hit.nz * SKIN;
                if (world.cast(sx, sy, sz, ex, ey, ez, null) == null) {
                    reachable++;
                    final double rx = ex - sx;
                    final double rz = ez - sz;
                    final double returnDist = Math.sqrt(rx * rx + (ey - sy) * (ey - sy) + rz * rz);
                    final double path = outPath + returnDist;
                    final double arrival = (path - directBase) / SPEED_OF_SOUND;
                    if (tapCount < tapDelays.length) {
                        tapDelays[tapCount] = (float) arrival;
                        tapGains[tapCount] = (float) (retention * 2.0 / path);
                        tapHighRatio[tapCount] = (float) Math.min(1.0,
                                Math.sqrt(eRet[2] / Math.max(ENERGY_REFLECT_MIN, eRet[1])));
                        tapArrX[tapCount] = (float) rx;
                        tapArrZ[tapCount] = (float) rz;
                        tapHoriz[tapCount] = (float) Math.sqrt(rx * rx + rz * rz);
                        tapCount++;
                    }
                    // 能量 = 逐频带反射率连乘 × 空气吸收 × 几何 1/距离²
                    final double geometric = 4.0 / (path * path);
                    for (int band = 0; band < EnergyField.BANDS; band++) {
                        histogram.accumulate(band, arrival, geometric * eRet[band]
                                * Math.pow(AIR_ENERGY_PER_METER[band], path));
                    }
                }

                // 沿途能量已耗尽:这条射线对直方图/抽头不再有贡献
                if (Math.max(eRet[0], Math.max(eRet[1], eRet[2])) < ENERGY_CUTOFF) {
                    break;
                }
                // 镜面反射:d − 2(d·n)n
                final double dot = dx * hit.nx + dy * hit.ny + dz * hit.nz;
                dx -= 2.0 * dot * hit.nx;
                dy -= 2.0 * dot * hit.ny;
                dz -= 2.0 * dot * hit.nz;
                fx = sx;
                fy = sy;
                fz = sz;
                ignore = hit.key;
            }
            if (hits > 0) {
                trappedWeight += Math.min(1.0, Math.max(0, hits - 1) / TRAP_FULL_HITS);
            }
        }

        if (firstHits == 0) {
            return Stats.NONE;
        }
        // 一条射线从首命中继续多次无命中,等价于声线持续逸出;用它降低实际
        // 晚期围合,避免把超过射程的开口当成被困射线。
        final float lateEnclosure = (float) (trappedWeight / rays)
                * (1f - 0.75f * (escapeTransitions > 0
                        ? escapedPostHit / (float) escapeTransitions : 0f));
        final float firstMean = (float) (firstSum / firstHits);
        final float segMean = segCount > 0 ? (float) (segSum / segCount) : firstMean;
        final float reflectivity = reflectHits > 0
                ? (float) (reflectSum / reflectHits) : DEFAULT_REFLECTIVITY;
        final float[] bandRho = new float[EnergyField.BANDS];
        for (int band = 0; band < EnergyField.BANDS; band++) {
            bandRho[band] = reflectHits > 0
                    ? (float) (bandRhoSum[band] / reflectHits) : DEFAULT_REFLECTIVITY;
        }
        final float reach = reflectHits > 0 ? reachable / (float) reflectHits : 0f;
        return new Stats(firstHits / (float) rays,
                lateEnclosure,
                escapeTransitions > 0 ? escapedPostHit / (float) escapeTransitions : 0f,
                escapeTransitions, firstMean, segMean, reflectivity, bandRho, reach,
                tapCount, tapDelays, tapGains, tapHighRatio, tapArrX, tapArrZ, tapHoriz, histogram);
    }
}
