package com.muoniumplayer.core.ncm.music.acoustics;

/**
 * 世界声源的锚点与降级状态:玩家在世界里放下的那个「虚拟音箱」。
 *
 * <p>数据模型极小 —— 一个世界坐标 + 维度标识 + 是否仍然有效。游戏线程
 * (树侧 {@code SpeakerManager})负责写入与逐 tick 判定降级;音频推导侧
 * ({@link ListenerSample} 的树实现、{@link SceneResponse})只读快照。交接
 * 走 volatile 引用,不可变对象建好即弃,无锁。</p>
 *
 * <h2>降级状态机(判定在树侧,状态本身在这里)</h2>
 * <ul>
 *   <li>{@link Status#ACTIVE}:一切正常,射线原点切到音箱位,直射链生效;</li>
 *   <li>{@link Status#OUT_OF_RANGE}:玩家距音箱超过有效射程 —— 直射与混响
 *       一起平滑淡出(音频侧目标量收敛,无突变);</li>
 *   <li>{@link Status#DIMENSION_MISMATCH}:玩家换了维度(下界/末地)—— 音箱
 *       锚点还在(回到原维度仍有效),但当前维度听不到;</li>
 *   <li>{@link Status#CHUNK_UNLOADED}:音箱所在区块已卸载(单机走远/区块被
 *       服务器释放)—— 保守起见按无效处理,回到加载范围自动恢复。</li>
 * </ul>
 *
 * <p>判定之外的一切(NaN 坐标、越界数值)在 {@link #sanitize} 归一成无效,
 * 防御性编程:json 落盘的数据永远是不可信输入。</p>
 */
public final class SpeakerState {

    /** 降级状态。 */
    public enum Status {
        /** 音箱有效:直射 + 源激发的反射/混响全部生效。 */
        ACTIVE,
        /** 玩家超出有效射程:淡出。 */
        OUT_OF_RANGE,
        /** 玩家不在锚点所在维度。 */
        DIMENSION_MISMATCH,
        /** 音箱所在区块未加载。 */
        CHUNK_UNLOADED
    }

    /** 有效射程(米):与探针射线射程一致(160 格)—— 声源在射程内,
     *  「源→墙→耳」的几何才追得全;超出即 OUT_OF_RANGE 淡出。 */
    public static final double RANGE_METERS = 160.0;

    /** 当前生效的锚点(null = 没有音箱,一切按「玩家即声源」旧语义)。 */
    private static volatile SpeakerState current;

    private final double x;
    private final double y;
    private final double z;
    /** 维度标识(树侧用 {@code level.dimension().toString()});null = 未知。 */
    private final String dimension;

    private SpeakerState(double x, double y, double z, String dimension) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
    }

    /** 放置/更新锚点;坐标非有限时拒绝(返回 null,调用方负责提示失败)。 */
    public static SpeakerState place(double x, double y, double z, String dimension) {
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) {
            return null;
        }
        return new SpeakerState(x, y, z, dimension);
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public String dimension() {
        return dimension;
    }

    /** 读取当前锚点;null = 无声源(旧语义)。 */
    public static SpeakerState get() {
        return current;
    }

    /** 生效/清除锚点(树侧放置/移除时调)。 */
    public static void set(SpeakerState state) {
        current = state;
    }

    /** 当前有没有激活的音箱(树侧已判过降级;这里只看存在性)。 */
    public static boolean active() {
        return current != null;
    }

    /**
     * 持久化往返的不可信输入归一:任何字段不合法 → null(放弃该条状态)。
     * 维度串限长防极端文件;坐标超 ±3e7(远超世界边界)视为损坏。
     */
    public static SpeakerState sanitize(double x, double y, double z, String dimension) {
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) {
            return null;
        }
        final double bound = 3.0e7;
        if (Math.abs(x) > bound || Math.abs(y) > bound || Math.abs(z) > bound) {
            return null;
        }
        if (dimension == null || dimension.isEmpty() || dimension.length() > 256) {
            return null;
        }
        return new SpeakerState(x, y, z, dimension);
    }

    /** 到给定听者位置的水平+垂直总距离(米)。 */
    public double distanceTo(double ex, double ey, double ez) {
        final double dx = ex - x;
        final double dy = ey - y;
        final double dz = ez - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean isFinite(double v) {
        return Double.isFinite(v);
    }

    @Override
    public String toString() {
        return "speaker(" + String.format("%.1f", x) + ", " + String.format("%.1f", y)
                + ", " + String.format("%.1f", z) + " @ " + dimension + ")";
    }
}
