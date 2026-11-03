package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 「通透模式」：跟着正在播放的音乐节拍，把视野里的生物与玩家模型像果冻一样压扁回弹。
 *
 * <h3>节拍从哪来</h3>
 * <p>直接读 {@link PlaybackPulse#beat()} —— 它挂在正在响的那一路输出上做 onset 检测，
 * 任何歌、任何格式都自动卡点，切歌不用管，停下来自然归零。这里不做任何音频分析，
 * 也不维护自己的时钟。</p>
 *
 * <h3>压缩数学</h3>
 * <p>一套经典的果冻挤压：高度按 {@code 1 - 幅度×squash} 压下去，宽度按
 * {@code 1 + 幅度×squash} 涨出来（深度不动），脚底锚定原地。squash 是 0..1 的节拍脉冲，
 * 鼓点上冲高、拍间回落，所以模型会随节拍一压一弹。</p>
 *
 * <h3>为什么自己存文件而不进 HudConfig</h3>
 * <p>{@code HudConfig} 是五棵版本树各自一份的留守文件（见 {@code ConfigPaths} 的注释），
 * 每加一个字段都要改五份。这里照 {@code AcousticsSettings} 的路子单独一个小文件：
 * 设置页上每颗开关/滑条都要立即落盘，读失败的代价只是「回到关闭」，不该牵连主配置。</p>
 */
public final class TransparentMode {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    /** 压缩幅度的合法区间（百分比），与设置页滑条一致。 */
    public static final float COMPRESSION_MIN = 0f, COMPRESSION_MAX = 90f;
    /** 变宽幅度的合法区间（百分比）：最大压缩时额外涨出的横向宽度，深度不变。 */
    public static final float WIDTH_MIN = 0f, WIDTH_MAX = 200f;

    private static boolean loaded;
    private static boolean enabled;
    /** 最大压缩幅度百分比：squash=1 时模型高度压到 1-compression/100。 */
    private static float compressionPercent = 50f;
    /** 最大变宽幅度百分比：squash=1 时模型宽度涨到 1+width/100。 */
    private static float widthPercent = 50f;

    // ---- 果冻弹簧（渲染线程单消费者，非线程安全；见 springSquash）----
    /** 弹簧当前位移：0 = 原样，正 = 压扁，负 = 过冲拉高。 */
    private static float springOffset;
    /** 弹簧当前速度。 */
    private static float springVelocity;
    private static long lastSpringNanos;

    /**
     * 弹簧固有频率（rad/s）。约 0.45s 一个整周期：下压半程 ~0.24s，正好贴住
     * friendswine 那条 Blockbench 关键帧曲线（0→满压 0.25s）的手感——比
     * {@link PlaybackPulse} 的脉冲衰减（tau≈0.19s）慢半拍，鼓点打进来的是
     * 「顺势一压」而不是「瞬间打满」。
     */
    private static final float SPRING_OMEGA = 14f;
    /** 阻尼比 ζ≈0.4：明显欠阻尼，释放时过冲到负值（拉高变瘦）再稳住，果冻感所在。 */
    private static final float SPRING_DAMPING = 0.4f;
    /** 单帧最大推进秒数：卡顿一下不该让弹簧炸开。 */
    private static final float MAX_FRAME_SECONDS = 0.1f;
    /**
     * 积分子步上限（秒）。半隐式欧拉的稳定域大约在 ω·dt ≤ 2：ω=14 时整帧 dt>0.14s
     * 就越界，掉帧到 100ms 已经贴着危险区。按 1/120s 切子步，任何真实帧长都落在
     * 稳定域深处，代价可以忽略（每帧至多 12 次乘加）。
     */
    private static final float MAX_SUBSTEP_SECONDS = 1f / 120f;

    private TransparentMode() {
    }

    /** 总开关。关闭时渲染层拿到的高度/宽度缩放恒为 1，完全不付矩阵成本。 */
    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static void setEnabled(boolean value) {
        write(value, null, null);
    }

    public static float compressionPercent() {
        load();
        synchronized (LOCK) {
            return compressionPercent;
        }
    }

    public static void setCompressionPercent(float value) {
        write(null, value, null);
    }

    public static float widthPercent() {
        load();
        synchronized (LOCK) {
            return widthPercent;
        }
    }

    public static void setWidthPercent(float value) {
        write(null, null, value);
    }

    /**
     * 当前帧的高度缩放：节拍越响压得越扁，脚底不动。
     *
     * <p>只在开关打开时读一次节拍；关闭时恒为 1，调用方不需要自己判开关。
     * 节拍源不可用（{@link PlaybackPulse} 永久降级）时脉冲是 0，自然退回不压。</p>
     */
    public static float heightScale() {
        if (!isEnabled()) return 1f;
        return heightScale(springSquash(), compressionPercent());
    }

    /** 当前帧的宽度缩放：与高度互补，压扁的同时往两边涨，深度（Z）不变。 */
    public static float widthScale() {
        if (!isEnabled()) return 1f;
        return widthScale(springSquash(), widthPercent());
    }

    /**
     * 过了弹簧的压缩量：目标就是原始节拍脉冲（0..1），但位移按欠阻尼弹簧追它。
     *
     * <p>直接画原始包络为什么不行：onset 那一帧脉冲从 0 瞬间打到峰值，渲染出来就是
     * 「咔一下压扁、再慢慢弹回」，原版没有任何平滑。这里让位移带速度地追目标——
     * 下压被弹簧顺住，回弹时欠阻尼地过冲到负值（模型短暂拉高变瘦）再稳住，
     * 才有果冻的弹性。弹簧参数的取舍见常量注释。</p>
     *
     * <p>推进按墙上时钟做，与 {@code PlaybackPulse} 同款约定：同帧多消费者安全；
     * 关联的取值入口（{@link #heightScale()}/{@link #widthScale()}）每帧各调一次，
     * 先后两次推进的间隔小于半毫秒时直接复用上一次结果。渲染线程单消费者，
     * 状态不做同步。</p>
     */
    static float springSquash() {
        float target = clamp01(PlaybackPulse.beat());
        long now = System.nanoTime();
        float dt = lastSpringNanos == 0L ? 0f : (now - lastSpringNanos) / 1.0e9f;
        lastSpringNanos = now;
        if (dt <= 0f) return springOffset; // 首帧或同帧重复调用：不动
        float[] next = integrate(springOffset, springVelocity, target, dt);
        springOffset = next[0];
        springVelocity = next[1];
        return springOffset;
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
        // 数值兜底：异常输入下位移不允许画出翻转型模型。
        if (!Float.isFinite(offset) || Math.abs(offset) > 4f) {
            offset = 0f;
            velocity = 0f;
        }
        return new float[] {offset, velocity};
    }

    /**
     * 测试专用：与 {@link #springSquash()} 同一积分器，但目标与帧长由调用方给，
     * 不碰墙上时钟与节拍源。生产路径不走这里。
     */
    static final class SpringProbe {
        private float offset;
        private float velocity;

        float step(float target, float dtSeconds) {
            float clampedTarget = clamp01(target);
            float dt = Math.max(0f, Math.min(MAX_FRAME_SECONDS, dtSeconds));
            if (dt == 0f) return offset;
            float[] next = integrate(offset, velocity, clampedTarget, dt);
            offset = next[0];
            velocity = next[1];
            return offset;
        }
    }

    /** 纯数学：给定脉冲与幅度求高度缩放。静态测试直接钉这条曲线。 */
    public static float heightScale(float squash, float compressionPercent) {
        float percent = Math.max(COMPRESSION_MIN, Math.min(COMPRESSION_MAX, compressionPercent));
        // 弹簧位移可过冲到负值（拉高）：负 squash 不钳零，负号即「比原样高」。
        return 1f - percent / 100f * clampAbs(squash);
    }

    /** 纯数学：给定脉冲与幅度求宽度缩放。 */
    public static float widthScale(float squash, float widthPercent) {
        float percent = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, widthPercent));
        return 1f + percent / 100f * clampAbs(squash);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /** 允许负过冲（拉高/变瘦），但钳住幅度不画出翻转型模型。 */
    private static float clampAbs(float value) {
        if (!Float.isFinite(value)) return 0f;
        return Math.max(-1f, Math.min(1f, value));
    }

    private static void write(Boolean enable, Float compression, Float width) {
        load();
        synchronized (LOCK) {
            if (enable != null) enabled = enable;
            if (compression != null) compressionPercent = Math.max(COMPRESSION_MIN, Math.min(COMPRESSION_MAX, compression));
            if (width != null) widthPercent = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, width));
            saveLocked();
        }
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.TRANSPARENT_MODE;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                enabled = optBool(root, "enabled", false);
                compressionPercent = optFloat(root, "compression", compressionPercent);
                widthPercent = optFloat(root, "width", widthPercent);
            } catch (Throwable ignored) {
                // 配置坏了用默认值,不能影响播放。
            }
        }
    }

    private static void saveLocked() {
        try {
            File file = ConfigPaths.TRANSPARENT_MODE;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("compression", compressionPercent);
            root.addProperty("width", widthPercent);
            JsonConfigStorage.writeObject(file, GSON, root);
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
}
