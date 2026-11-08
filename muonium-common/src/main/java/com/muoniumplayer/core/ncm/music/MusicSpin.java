package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 「陀螺模式」：跟着正在播放的音乐节拍，让人物模型每拍甩动一步（默认 90°，一圈四拍）。
 *
 * <h3>动效</h3>
 * <p>拍点步进：{@link BeatPulseSource} 的脉冲每次升沿把目标角加一步，位移由欠阻尼弹簧
 * 追——与 {@link TransparentMode} 同一套半隐式欧拉积分器和同一组弹簧参数（ω=14、ζ=0.4），
 * 所以两个整活特效的手感是同一种语言。拍点升沿带 BPM 感知的不应期（0.75 个拍周期）：
 * 节拍网格/实时估算的拍点是准的，能量回退档的密集 onset 不会把转速抬爆。暂停时脉冲归零、
 * 目标不再前进，弹簧停在当前位置——「暂停 = 定格」。</p>
 *
 * <h3>作用面（三档，互斥）</h3>
 * <ul>
 *   <li><b>只本机画面</b>：模型旋转只改本机渲染矩阵/渲染朝向，不碰服务端状态——与
 *       通透模式同口径（「只在本机画面生效，不改变碰撞箱」）。</li>
 *   <li><b>发包·视角跟着转</b>：client tick 把真实 yaw 设成旋转角，移动包自动携带——
 *       服务器和其他玩家看到你转，自己的视角也跟着转（经典 derp）。</li>
 *   <li><b>发包·视角不动</b>：树内 mixin 把 {@code sendPosition} 里的 yaw 读取替换成
 *       旋转角——别人看到你转，自己视角原地不动。相机走的是插值缓存字段，不受影响。</li>
 * </ul>
 *
 * <h3>范围（三档）</h3>
 * <p>控制本机画面里谁在转：只有自己 / 其他玩家 / 全部生物玩家。发包两档永远作用于
 * 自己（那是「你」在网络上转），与范围档位正交。</p>
 *
 * <h3>注入形态分两代（树内 mixin 负责）</h3>
 * <p>1.21.10 起（含 26.x）的渲染是 extract/submit 两段式，树内在 extract 阶段把旋转角
 * 加进 {@code LivingEntityRenderState.bodyRot}——模型整体（身体+头）跟着转，名牌挂点是
 * 纯 Y 偏移不受影响、名牌朝向由相机四元数另行决定也不受影响，火焰/阴影/牵引绳全在
 * state 里与本字段无关。1.20.1/1.21.1 是单段式 render，没有 extract，树内在首次
 * translate 落地后用 pose 括号旋转——那里名牌画在括号外（火焰读取点之后），同样不受污染。</p>
 *
 * <h3>为什么自己存文件而不进 HudConfig</h3>
 * <p>理由同 {@link TransparentMode}：HudConfig 是五棵版本树各自一份的留守文件，每加
 * 一个字段都要改五份；这里照它的路子单独一个小 JSON。</p>
 */
public final class MusicSpin {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    /** 可见性档：只本机画面。 */
    public static final int MODE_LOCAL = 0;
    /** 可见性档：发包·视角跟着转（真实 yaw 被旋转角接管）。 */
    public static final int MODE_PACKET_REAL = 1;
    /** 可见性档：发包·视角不动（sendPosition 里的 yaw 读取被替换）。 */
    public static final int MODE_PACKET_SILENT = 2;

    /** 范围档：只有自己。 */
    public static final int RANGE_SELF = 0;
    /** 范围档：视野里的其他玩家。 */
    public static final int RANGE_OTHER_PLAYERS = 1;
    /** 范围档：全部生物玩家（自己+其他玩家+生物）。 */
    public static final int RANGE_ALL = 2;

    /** 每拍转角的合法区间（度），与设置页滑条一致。 */
    public static final float STEP_MIN = 30f, STEP_MAX = 360f;
    /** 模式/范围的合法区间。 */
    public static final int MODE_MIN = MODE_LOCAL, MODE_MAX = MODE_PACKET_SILENT;
    public static final int RANGE_MIN = RANGE_SELF, RANGE_MAX = RANGE_ALL;

    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_MODE = MODE_LOCAL;
    public static final int DEFAULT_RANGE = RANGE_ALL;
    public static final float DEFAULT_STEP = 90f;

    private static boolean loaded;
    private static boolean enabled = DEFAULT_ENABLED;
    private static int mode = DEFAULT_MODE;
    private static int range = DEFAULT_RANGE;
    /** 每个拍点步进的转角（度）：90 就是一圈四拍，120 BPM 时角速度约 180°/s。 */
    private static float stepDegrees = DEFAULT_STEP;

    // ---- 弹簧与步进（渲染线程 + client tick 单消费者；见 advance）----
    /** 弹簧当前角（度，无界累计，消费侧各自 wrap）。 */
    private static float springAngle;
    /** 弹簧当前角速度（度/秒）。 */
    private static float springVelocity;
    /** 步进目标角（度）。 */
    private static float targetAngle;
    private static long lastAdvanceNanos;
    /** 同帧复用缓存：最近一次完整推进的角。 */
    private static float lastFrameAngle;
    /** 上一帧节拍脉冲：升沿检测用。 */
    private static float lastPulse;
    /** 上一次步进的时刻：不应期用。 */
    private static long lastStepNanos;
    /** 最近一次看到「在放」的时刻：发包档的活性窗口（暂停超过窗口就不再接管朝向）。 */
    private static long activeUntilNanos;

    /** 开关翻转时把弹簧归位：关着积累的角度不该在下次开着的首帧突然甩出来。 */
    private static void resetSpring() {
        springAngle = 0f;
        springVelocity = 0f;
        targetAngle = 0f;
        lastAdvanceNanos = 0L;
        lastFrameAngle = 0f;
        lastPulse = 0f;
        lastStepNanos = 0L;
        activeUntilNanos = 0L;
    }

    private MusicSpin() {
    }

    /** 总开关。关闭时所有取值入口返回 0/原样，调用方不需要自己判开关。 */
    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static void setEnabled(boolean value) {
        resetSpring(); // 开关翻转即归位：关着积累的角度不该在下次开着的首帧突然甩出来
        write(value, null, null, null);
    }

    public static int mode() {
        load();
        synchronized (LOCK) {
            return mode;
        }
    }

    public static void setMode(int value) {
        write(null, value, null, null);
    }

    public static int range() {
        load();
        synchronized (LOCK) {
            return range;
        }
    }

    public static void setRange(int value) {
        write(null, null, value, null);
    }

    public static float stepDegrees() {
        load();
        synchronized (LOCK) {
            return stepDegrees;
        }
    }

    public static void setStepDegrees(float value) {
        write(null, null, null, value);
    }

    // ---- 设置页的 choice 辅助（与 FocusVolumeDucker 的 choice 接线同构）----

    private static final String[] MODE_LABELS = {"只本机画面", "发包·视角跟着转", "发包·视角不动"};
    private static final String[] RANGE_LABELS = {"只有自己", "其他玩家", "全部生物玩家"};

    public static String[] modeLabels() {
        return MODE_LABELS.clone();
    }

    public static String modeLabel() {
        return MODE_LABELS[clampInt(mode(), MODE_MIN, MODE_MAX)];
    }

    public static void cycleMode() {
        setMode(mode() >= MODE_MAX ? MODE_MIN : mode() + 1);
    }

    public static void setModeByLabel(String label) {
        for (int i = 0; i < MODE_LABELS.length; i++) {
            if (MODE_LABELS[i].equals(label)) {
                setMode(i);
                return;
            }
        }
    }

    public static String[] rangeLabels() {
        return RANGE_LABELS.clone();
    }

    public static String rangeLabel() {
        return RANGE_LABELS[clampInt(range(), RANGE_MIN, RANGE_MAX)];
    }

    public static void cycleRange() {
        setRange(range() >= RANGE_MAX ? RANGE_MIN : range() + 1);
    }

    public static void setRangeByLabel(String label) {
        for (int i = 0; i < RANGE_LABELS.length; i++) {
            if (RANGE_LABELS[i].equals(label)) {
                setRange(i);
                return;
            }
        }
    }

    // ---- 取值入口（树内 mixin / client tick 调）----

    /**
     * 实体是否参与本机旋转（纯判定，无任何状态推进）。
     *
     * <p>单段式树的 pose 括号 push/pop 两个注入点都要问同一个问题——两次调用隔着
     * 一次模型渲染，间隔会超过同帧去重窗，所以判定必须不带推进、结果恒等，括号
     * 才不会一开一关错位（TransparentEntityMixin 注释里那个 Pose stack not empty
     * 的实测坑，同款纪律）。「视角跟着转」档的自己恒 false：真实 yaw 已接管，模型
     * 不需要矩阵再转。</p>
     */
    public static boolean entitySpins(boolean isSelf, boolean isPlayer, boolean isLiving) {
        if (!isEnabled()) {
            return false;
        }
        if (isSelf && mode() == MODE_PACKET_REAL) {
            return false;
        }
        return appliesTo(range(), isSelf, isPlayer, isLiving);
    }

    /**
     * 当前帧某个实体的模型旋转角（度，已 wrap 到 ±180）；0 = 不转。
     *
     * <p>推进一次弹簧与节拍步进（同帧复用）——单段式树只在 push 注入点调它，
     * pop 端用 {@link #entitySpins} 对称判定；双段式树走
     * {@link #entityAngleDegrees} 一次性入口。{@code isSelf} 且「视角不动」档时
     * {@code selfRealYaw} 参与差值：本机模型要呈现的是网络上别人看到的朝向
     * （包层角），而模型本身画在真实 yaw 上。</p>
     */
    public static float entityModelAngle(boolean isSelf, float selfRealYaw) {
        if (!isEnabled()) {
            return 0f;
        }
        float angle = advance();
        if (angle == 0f) {
            return 0f;
        }
        if (isSelf && mode() == MODE_PACKET_SILENT) {
            return wrapDegrees(angle - selfRealYaw);
        }
        return wrapDegrees(angle);
    }

    /**
     * 一次性入口：判定 + 取角（双段式树的 extract 单点调用，无对称约束）。
     * {@code isSelf} 时 {@code selfRealYaw} 用于「发包·视角不动」档的差值；非自己的
     * 实体传任意值即可。
     */
    public static float entityAngleDegrees(boolean isSelf, boolean isPlayer, boolean isLiving,
                                           float selfRealYaw) {
        if (!entitySpins(isSelf, isPlayer, isLiving)) {
            return 0f;
        }
        return entityModelAngle(isSelf, selfRealYaw);
    }

    /**
     * client tick 入口：推进节拍步进与弹簧（无论有没有实体要画，拍点都得数）。
     *
     * @return {@link Float#NaN} 表示不动朝向；否则是「发包·视角跟着转」档此刻应写入
     *         的真实 yaw。返回值由树内代码写回 {@code player}，共享层不碰 MC 符号。
     */
    public static float onClientTick(boolean inWorld, float realYaw) {
        if (!isEnabled()) {
            return Float.NaN;
        }
        float angle = advance();
        if (mode() == MODE_PACKET_REAL && inWorld && nowNanos() < activeUntilNanos) {
            return wrapDegrees(angle);
        }
        return Float.NaN;
    }

    /**
     * {@code sendPosition} 内 yaw 读取的替换点（树内 @Redirect 调）。
     *
     * <p>「发包·视角不动」档且活性窗口内返回包层角，否则原样返回——关闭或暂停超过
     * 活性窗口后，下一个 yRot 差值包会把服务端朝向自然收敛回真实相机，无跳变。</p>
     */
    public static float packetYaw(float realYaw) {
        if (!isEnabled() || mode() != MODE_PACKET_SILENT) {
            return realYaw;
        }
        if (nowNanos() >= activeUntilNanos) {
            return realYaw;
        }
        return wrapDegrees(lastFrameAngle);
    }

    /** 范围档判定：控制本机画面里谁在转。 */
    private static boolean appliesTo(int rangeNow, boolean isSelf, boolean isPlayer, boolean isLiving) {
        switch (rangeNow) {
            case RANGE_SELF: return isSelf;
            case RANGE_OTHER_PLAYERS: return isPlayer && !isSelf;
            case RANGE_ALL: return isLiving;
            default: return false;
        }
    }

    // ---- 步进 + 弹簧推进 ----

    /**
     * 推进一次：节拍脉冲升沿把目标角加一步（带 BPM 感知不应期），弹簧朝目标积分。
     *
     * <p>同帧复用：相邻两次推进间隔小于半毫秒时直接吃缓存——entityAngleDegrees 每帧
     * 每实体各调一次，onClientTick 又调一次，弹簧一帧只积分一次，行为与帧率严格解耦
     * （与 {@link TransparentMode#springSquash()} 同款约定）。渲染线程 + client tick
     * 单消费者，状态不做同步。</p>
     */
    private static float advance() {
        long now = nowNanos();
        float dt = lastAdvanceNanos == 0L ? 0f : (now - lastAdvanceNanos) / 1.0e9f;
        if (dt <= 0.0005f && lastAdvanceNanos != 0L) {
            return lastFrameAngle;
        }
        lastAdvanceNanos = now;

        float pulse = BeatPulseSource.beat();
        if (pulse > ACTIVE_PULSE_FLOOR) {
            activeUntilNanos = now + ACTIVE_HOLD_NANOS;
        }
        if (shouldStep(pulse, lastPulse, now, lastStepNanos, refractoryNanos())) {
            targetAngle += clampFloat(stepDegrees(), STEP_MIN, STEP_MAX);
            lastStepNanos = now;
        }
        lastPulse = pulse;

        if (dt <= 0f) {
            return springAngle; // 首帧：不动
        }
        float[] next = integrate(springAngle, springVelocity, targetAngle, dt);
        springAngle = next[0];
        springVelocity = next[1];
        lastFrameAngle = springAngle;
        return springAngle;
    }

    /**
     * 步进不应期：0.75 个拍周期（按 {@link BeatPulseSource#bpm()} 估）。
     * BPM 拿不到（能量回退档还没收敛）按 120 BPM 的 0.75 倍兜底——宁可转慢一点，
     * 也不能让能量档的密集 onset 把角速度抬到看不清。
     */
    private static long refractoryNanos() {
        float bpm = BeatPulseSource.bpm();
        double periodSeconds = bpm > 0f ? 60.0 / bpm : 0.5;
        return (long) (periodSeconds * 0.75 * 1.0e9);
    }

    /** 升沿 + 不应期判定：拍点包络从阈值下冲上阈值，且距上次步进满一个不应期。纯函数。 */
    static boolean shouldStep(float pulse, float lastPulse, long nowNanos, long lastStepNanos,
                              long refractory) {
        return pulse > STEP_EDGE_THRESHOLD && lastPulse <= STEP_EDGE_THRESHOLD
                && nowNanos - lastStepNanos >= refractory;
    }

    /** 半隐式欧拉积分（可切子步）；纯函数——不碰弹簧状态，commit 由调用方决定。 */
    private static float[] integrate(float offset, float velocity, float target, float dt) {
        float omega = SPRING_OMEGA;
        float stiffness = omega * omega;
        float damping = 2f * SPRING_DAMPING * omega;
        int steps = Math.max(1, (int) Math.ceil(dt / MAX_SUBSTEP_SECONDS));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            velocity += (stiffness * (target - offset) - damping * velocity) * h;
            offset += velocity * h;
        }
        // 数值兜底：异常输入下角度不允许甩出无穷大。
        if (!Float.isFinite(offset) || Math.abs(offset) > 1e7f) {
            offset = 0f;
            velocity = 0f;
        }
        return new float[] {offset, velocity};
    }

    /**
     * 弹簧固有频率（rad/s）与阻尼比：与 {@link TransparentMode} 完全同一组——两个
     * 整活特效必须是一种手感语言，调参时两边一起调。
     */
    private static final float SPRING_OMEGA = 14f;
    private static final float SPRING_DAMPING = 0.4f;
    /** 单帧最大推进秒数：卡顿一下不该让弹簧炸开。 */
    private static final float MAX_FRAME_SECONDS = 0.1f;
    /** 积分子步上限（秒），理由见 TransparentMode 同名常量。 */
    private static final float MAX_SUBSTEP_SECONDS = 1f / 120f;
    /** 升沿阈值：拍点包络从 0.5 以下冲上 0.5 以上记一次拍。 */
    private static final float STEP_EDGE_THRESHOLD = 0.5f;
    /** 活性判定：脉冲高过这个值就算「在放」。 */
    private static final float ACTIVE_PULSE_FLOOR = 0.02f;
    /** 活性窗口：最后一次看到脉冲后 2s 内，发包档仍然接管朝向（静音段落不抖）。 */
    private static final long ACTIVE_HOLD_NANOS = 2_000_000_000L;

    /**
     * 测试专用：与 {@link #advance()} 同一升沿判定与积分器，但节拍脉冲、帧长、时钟与
     * 不应期全部由调用方给，不碰墙上时钟与节拍源（CloudMusic 在纯 JVM 测试里不可用）。
     * 生产路径不走这里。
     */
    static final class SpinProbe {
        private final float stepDegrees;
        private float angle;
        private float velocity;
        private float target;
        private float lastPulse;
        private long lastStepNanos;

        SpinProbe(float stepDegrees) {
            this.stepDegrees = clampFloat(stepDegrees, STEP_MIN, STEP_MAX);
        }

        /** 喂一帧：pulse 是这一帧的节拍脉冲，nowNanos 是这一帧的时钟。返回推进后的角。 */
        float step(float pulse, float dtSeconds, long nowNanos, long refractoryNanos) {
            if (shouldStep(pulse, lastPulse, nowNanos, lastStepNanos, refractoryNanos)) {
                target += stepDegrees;
                lastStepNanos = nowNanos;
            }
            lastPulse = pulse;
            float dt = Math.max(0f, Math.min(MAX_FRAME_SECONDS, dtSeconds));
            if (dt == 0f) return angle;
            float[] next = integrate(angle, velocity, target, dt);
            angle = next[0];
            velocity = next[1];
            return angle;
        }
    }

    // ---- 纯数学 ----

    /** wrap 到 (−180, 180]：消费侧（矩阵/朝向字段）要的是最短路径角。 */
    static float wrapDegrees(float degrees) {
        if (!Float.isFinite(degrees)) return 0f;
        float wrapped = degrees % 360f;
        if (wrapped <= -180f) wrapped += 360f;
        else if (wrapped > 180f) wrapped -= 360f;
        return wrapped;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        if (!Float.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    // ---- 配置（照 TransparentMode 的懒加载小 JSON 路子）----

    /**
     * Main/render-thread seam, one write; preserves original bounds and resets spring only on a flip.
     * 预设域（{@code SpinPresetDomain}）的批量应用入口，与 {@link TransparentMode#applyAll} 同构。
     */
    public static void applyAll(boolean enable, int modeValue, int rangeValue, float step) {
        load();
        synchronized (LOCK) {
            if (enabled != enable) resetSpring();
            enabled = enable;
            if (modeValue >= MODE_MIN && modeValue <= MODE_MAX) mode = modeValue;
            if (rangeValue >= RANGE_MIN && rangeValue <= RANGE_MAX) range = rangeValue;
            if (Float.isFinite(step)) stepDegrees = Math.max(STEP_MIN, Math.min(STEP_MAX, step));
            try {
                JsonConfigStorage.writeObject(ConfigPaths.SPIN_MODE, GSON, toJsonLocked());
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("陀螺配置落盘失败", failure);
            }
        }
    }

    public static JsonObject toJson() {
        load();
        synchronized (LOCK) {
            return toJsonLocked();
        }
    }

    public static JsonObject defaultsJson() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", DEFAULT_ENABLED);
        root.addProperty("mode", DEFAULT_MODE);
        root.addProperty("range", DEFAULT_RANGE);
        root.addProperty("step", DEFAULT_STEP);
        return root;
    }

    private static JsonObject toJsonLocked() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        root.addProperty("mode", mode);
        root.addProperty("range", range);
        root.addProperty("step", stepDegrees);
        return root;
    }

    private static void write(Boolean enable, Integer modeValue, Integer rangeValue, Float step) {
        load();
        synchronized (LOCK) {
            if (enable != null && enable != enabled) resetSpring();
            if (enable != null) enabled = enable;
            if (modeValue != null && modeValue >= MODE_MIN && modeValue <= MODE_MAX) mode = modeValue;
            if (rangeValue != null && rangeValue >= RANGE_MIN && rangeValue <= RANGE_MAX) range = rangeValue;
            if (step != null && Float.isFinite(step)) {
                stepDegrees = Math.max(STEP_MIN, Math.min(STEP_MAX, step));
            }
            saveLocked();
        }
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.SPIN_MODE;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                enabled = optBool(root, "enabled", false);
                mode = clampInt((int) optFloat(root, "mode", mode), MODE_MIN, MODE_MAX);
                range = clampInt((int) optFloat(root, "range", range), RANGE_MIN, RANGE_MAX);
                stepDegrees = clampFloat(optFloat(root, "step", stepDegrees), STEP_MIN, STEP_MAX);
            } catch (Throwable ignored) {
                // 配置坏了用默认值,不能影响播放。
            }
        }
    }

    private static void saveLocked() {
        try {
            File file = ConfigPaths.SPIN_MODE;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonConfigStorage.writeObject(file, GSON, toJsonLocked());
        } catch (Throwable ignored) {
        }
    }

    private static boolean optBool(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) return fallback;
        try {
            return root.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float optFloat(JsonObject root, String key, float fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) return fallback;
        try {
            return root.get(key).getAsFloat();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    // ---- 测试时钟（与 BeatPulseSource 的测试钩子同款约定）----

    private static volatile boolean testClockHooked;
    private static long testNowNanos = 1_000_000_000L;

    /** 推进测试时钟：advance 从此取测试值，生产路径（未挂钩）不受影响。 */
    static void advanceClockForTest(long nanos) {
        testNowNanos = nanos;
        testClockHooked = true;
    }

    private static long nowNanos() {
        return testClockHooked ? testNowNanos : System.nanoTime();
    }

    static void resetForTest() {
        resetSpring();
        loaded = false;
        enabled = DEFAULT_ENABLED;
        mode = DEFAULT_MODE;
        range = DEFAULT_RANGE;
        stepDegrees = DEFAULT_STEP;
        testClockHooked = false;
        testNowNanos = 1_000_000_000L;
    }
}
