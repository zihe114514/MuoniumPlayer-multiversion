package com.muoniumplayer.core.cine;

/**
 * 镜头语法表 + 运镜数学：编排器从这挑镜头，逐帧 pose 由这里算。
 *
 * <p>全部纯函数（随机源由调用方传入 {@code long seed}，测试可复现），
 * 参数量级来自电影/游戏运镜实践：orbit 10~30°/s、dolly 3~8 格、
 * crane 4~10 格、exp 平滑位置 τ≈0.2s 朝向 τ≈0.08s、手持噪声 0.5~1.5°。</p>
 *
 * <h3>镜头族</h3>
 * <ul>
 *   <li>{@code ORBIT} —— 绕主体环绕，弧度 30~90°，半径呼吸 ±8%；</li>
 *   <li>{@code DOLLY} —— 沿视线推/拉 3~8 格，smootherstep 缓动；</li>
 *   <li>{@code CRANE} —— 升/降 4~10 格配 pitch 下压，段落边界英雄镜头；</li>
 *   <li>{@code STATIC} —— 静止广角，让主体自己动（能量低谷的呼吸镜头）；</li>
 *   <li>{@code TRACK_MOB} —— 追踪移动生物（主体由编排器选定）。</li>
 * </ul>
 *
 * <h3>防腻规则（调研结论）</h3>
 * <p>调度器侧：不连续同族、方位角差 ≥45°、每乐句一个低运动量镜头。
 * 本文件只提供「按 seed 从候选族挑一族」的工具，约束逻辑在 {@link CineDirector}。</p>
 */
public final class CineShots {

    /** 镜头族。 */
    public enum Kind { ORBIT, DOLLY, CRANE, STATIC, TRACK_MOB }

    /** 环绕角速度区间（度/秒，镜头内匀速段）：慢电影感 10~30°/s。 */
    private static final double ORBIT_SPEED_MIN = 12d, ORBIT_SPEED_MAX = 28d;
    /** orbit 弧度上限（度）：30~90，超了转晕。 */
    static final double ORBIT_ARC_MAX = 90d;
    /** dolly 位移区间（格）。 */
    private static final double DOLLY_DIST_MIN = 3d, DOLLY_DIST_MAX = 8d;
    /** crane 高度区间（格）。 */
    private static final double CRANE_RISE_MIN = 4d, CRANE_RISE_MAX = 10d;
    /** 手持噪声幅度（度）与频率（Hz）：双层正弦叠加。 */
    private static final double HANDHELD_ROLL_DEG = 0.9d;
    private static final double HANDHELD_PITCH_DEG = 0.6d;

    private CineShots() {
    }

    /**
     * smootherstep（五次多项式）：加速度连续，起停无顿挫。
     * 端点外自然钳到 0/1，负输入归 0。
     */
    public static double smootherstep(double t) {
        if (!(t > 0d)) return 0d;
        if (t > 1d) return 1d;
        return t * t * t * (t * (t * 6d - 15d) + 10d);
    }

    /**
     * 帧率无关指数平滑系数：{@code pos += (target-pos) * expLerp(dt, tau)}。
     */
    public static double expLerp(double dtSeconds, double tauSeconds) {
        if (tauSeconds <= 1.0e-4d) return 1d;
        double dt = Math.max(0d, Math.min(0.1d, dtSeconds));
        return 1d - Math.exp(-dt / tauSeconds);
    }

    /** 角度差归一到 [-180,180)。 */
    public static float wrapDegrees(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /**
     * 手持感 roll 偏移（度）：0.3Hz 主摆 + 0.7Hz 微抖，幅度随 intensity 缩放。
     * 纯函数——t 秒进、度数出。
     */
    public static double handheldRoll(double timeSeconds, double intensity) {
        return intensity * HANDHELD_ROLL_DEG * (
                Math.sin(2d * Math.PI * 0.3d * timeSeconds)
                        + 0.35d * Math.sin(2d * Math.PI * 0.7d * timeSeconds + 1.3d));
    }

    /** 手持感 pitch 偏移（度）：0.4Hz + 0.2 残差。 */
    public static double handheldPitch(double timeSeconds, double intensity) {
        return intensity * HANDHELD_PITCH_DEG * (
                Math.sin(2d * Math.PI * 0.4d * timeSeconds + 0.7d)
                        + 0.3d * Math.sin(2d * Math.PI * 1.1d * timeSeconds + 2.1d));
    }

    /**
     * FOV punch 包络：鼓点瞬间冲出后指数回落。
     *
     * @param amplitudeDeg  最大幅度（度，正数）
     * @param sincePunchNanos 鼓点时刻
     * @param nowNanos      当前时刻
     * @param tauSeconds    衰减时间常数（≈0.2s）
     */
    public static double fovPunch(double amplitudeDeg, long sincePunchNanos, long nowNanos, double tauSeconds) {
        if (amplitudeDeg <= 0d || sincePunchNanos <= 0L || nowNanos < sincePunchNanos) return 0d;
        double elapsed = (nowNanos - sincePunchNanos) / 1.0e9d;
        return amplitudeDeg * Math.exp(-elapsed / Math.max(0.02d, tauSeconds));
    }

    /** 从 [min,max) 均匀取值，seed 可复现（splitmix64 混盐，不共享全局随机源）。 */
    static double randRange(long seed, int salt, double min, double max) {
        long mixed = seed + salt * -7046029254386353131L;
        mixed ^= mixed >>> 30;
        mixed *= -49064778989728563L;
        mixed ^= mixed >>> 27;
        mixed *= -4265267296055464877L;
        mixed ^= mixed >>> 31;
        double unit = (mixed & 0x1FFFFFFFFFFFFFL) / (double) (1L << 53);
        return min + (max - min) * unit;
    }
}
