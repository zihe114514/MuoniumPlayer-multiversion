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
        return heightScale(PlaybackPulse.beat(), compressionPercent());
    }

    /** 当前帧的宽度缩放：与高度互补，压扁的同时往两边涨，深度（Z）不变。 */
    public static float widthScale() {
        if (!isEnabled()) return 1f;
        return widthScale(PlaybackPulse.beat(), widthPercent());
    }

    /** 纯数学：给定脉冲与幅度求高度缩放。静态测试直接钉这条曲线。 */
    public static float heightScale(float squash, float compressionPercent) {
        float percent = Math.max(COMPRESSION_MIN, Math.min(COMPRESSION_MAX, compressionPercent));
        return 1f - percent / 100f * clamp01(squash);
    }

    /** 纯数学：给定脉冲与幅度求宽度缩放。 */
    public static float widthScale(float squash, float widthPercent) {
        float percent = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, widthPercent));
        return 1f + percent / 100f * clamp01(squash);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
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
