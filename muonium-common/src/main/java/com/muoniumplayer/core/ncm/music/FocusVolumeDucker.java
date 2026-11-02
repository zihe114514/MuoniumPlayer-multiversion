package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 「游戏窗口失去焦点时压低本 mod 的音乐」：切出去查东西、回个消息时歌还在，但退到背景里。
 *
 * <h2>压的是哪一层</h2>
 * <p>只压本 mod 自己的输出：{@link AudioPlayer} 的有效振幅（{@code effectiveAmp()} 与 automix
 * 音频线程帧 {@code applyAutomixFrame} 两条写路径）和直播流 {@link LiveStreamPlayer} 的线路增益，
 * 都乘上 {@link #gain()}。<b>不</b>改用户的持久化音量（{@code HudConfig.playerVolume}）——
 * 回到游戏时不需要「恢复」任何东西，期间拖音量条也不会打架；<b>不</b>碰原版声音类别与外部播放器
 * 镜像（SMTC / Spotify Connect）：那些声音不是我们的引擎出的，替用户改系统里别人的音量是越权。</p>
 *
 * <h2>判据与驱动</h2>
 * <p>焦点状态由各版本树的客户端 tick 读 {@code Minecraft.isWindowActive()} 后传进来
 * （共享层不碰 MC 符号）。客户端 tick 在窗口失焦、单人暂停菜单下照常跑，所以这里能可靠地
 * 看到失焦与回焦两个沿。</p>
 *
 * <h2>为什么要做斜坡</h2>
 * <p>JSyn 的 {@code amp()} 直接写端口、没有平滑；一下子从 100% 掉到 25% 会有明显的「啪」。
 * 这里按真实时间做线性斜坡：压下去慢一点（{@link #FADE_OUT_NANOS}），回来快一点
 * （{@link #FADE_IN_NANOS}）—— 回到游戏时音乐应当几乎立刻回来。20 TPS 的台阶每级不到 1.5 dB，
 * 听感上是连续的。</p>
 *
 * <h2>持久化</h2>
 * <p>{@code config/muonium/focus_volume_ducker.json}，照 {@link VanillaMusicDucker} 那一族：懒加载、
 * 锁内写、写失败静默。tick 路径在 {@code loaded} 置位后零 IO。</p>
 */
public final class FocusVolumeDucker {

    /** 可选的压低档位（占原音量的百分比）。0 即静音。 */
    public static final int[] LEVELS = {0, 10, 25, 50};
    public static final int DEFAULT_LEVEL = 25;

    /** 满幅（1 → 0）的斜坡时长：失焦时往下走。 */
    static final long FADE_OUT_NANOS = 600_000_000L;
    /** 满幅（0 → 1）的斜坡时长：回焦时往上走。 */
    static final long FADE_IN_NANOS = 250_000_000L;

    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    /** 双检锁的快路径标记：volatile，且只在读盘结束后置位。 */
    private static volatile boolean loaded;
    /** 开关（默认开）。只在锁内写；tick 与渲染线程无锁读。 */
    private static volatile boolean enabled = true;
    /** 压低到原音量的百分比，取值见 {@link #LEVELS}。 */
    private static volatile int levelPercent = DEFAULT_LEVEL;

    /** 当前施加在输出上的倍率。音频线程（automix 帧）也读它，所以是 volatile。 */
    private static volatile float gain = 1.0f;
    /** 上一次 tick 的时刻；只有主线程碰。0 = 还没 tick 过。 */
    private static long lastTickNanos;

    private FocusVolumeDucker() {
    }

    /** 输出倍率，{@code 0..1}。窗口在前台（或开关关着）时恒为 1。 */
    public static float gain() {
        return gain;
    }

    /**
     * 每个客户端 tick 调一次。所有异常吞掉：这条在主线程每拍都走，炸了不能带走游戏。
     *
     * @param windowActive 游戏窗口此刻是否拥有焦点
     */
    public static void onClientTick(boolean windowActive) {
        try {
            load();
            long now = System.nanoTime();
            long elapsed = lastTickNanos == 0L ? 0L : Math.max(0L, now - lastTickNanos);
            lastTickNanos = now;

            float current = gain;
            float next = step(current, targetGain(enabled, levelPercent, windowActive), elapsed);
            if (next == current) return;
            gain = next;
            pushToOutputs();
        } catch (Throwable ignored) {
            // 宁可这一拍不压，也不能让异常冒到 tick 循环里。
        }
    }

    /** 目标倍率：开着且失焦时为所选档位，否则为 1。 */
    static float targetGain(boolean enabled, int levelPercent, boolean windowActive) {
        if (!enabled || windowActive) return 1.0f;
        return Math.max(0, Math.min(100, levelPercent)) / 100.0f;
    }

    /** 按经过的真实时间朝目标线性推进一步，不越过目标。 */
    static float step(float current, float target, long elapsedNanos) {
        if (!Float.isFinite(current)) return target;
        if (current == target) return current;
        if (current > target) {
            float next = current - (float) elapsedNanos / FADE_OUT_NANOS;
            return Math.max(target, next);
        }
        float next = current + (float) elapsedNanos / FADE_IN_NANOS;
        return Math.min(target, next);
    }

    /**
     * 把新倍率推给正在出声的 deck。直播流不需要推：它在每次写线时都按
     * {@link #gain()} 重算一次线路增益。
     */
    private static void pushToOutputs() {
        AudioPlayer active = CloudMusic.player;
        if (active != null) active.refreshAmp();
        AudioPlayer fading = CloudMusic.fadingOutPlayer;
        if (fading != null && fading != active) fading.refreshAmp();
    }

    // ════════════════════════════ 设置 ════════════════════════════

    public static boolean isEnabled() {
        load();
        return enabled;
    }

    public static void setEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            if (enabled == value) return;
            enabled = value;
            saveLocked();
        }
    }

    public static int getLevelPercent() {
        load();
        return levelPercent;
    }

    public static void setLevelPercent(int percent) {
        load();
        int safe = normalizeLevel(percent);
        synchronized (LOCK) {
            if (levelPercent == safe) return;
            levelPercent = safe;
            saveLocked();
        }
    }

    /** 设置面板上的档位文案。 */
    public static String getLevelLabel() {
        return labelOf(getLevelPercent());
    }

    /** 设置面板「点一下换下一档」。 */
    public static void cycleLevel() {
        int current = getLevelPercent();
        for (int i = 0; i < LEVELS.length; i++) {
            if (LEVELS[i] == current) {
                setLevelPercent(LEVELS[(i + 1) % LEVELS.length]);
                return;
            }
        }
        setLevelPercent(DEFAULT_LEVEL);
    }

    /** 设置面板的档位文案全集，顺序与 {@link #LEVELS} 一致。 */
    public static String[] levelLabels() {
        String[] labels = new String[LEVELS.length];
        for (int i = 0; i < LEVELS.length; i++) labels[i] = labelOf(LEVELS[i]);
        return labels;
    }

    /** 按文案选档；认不出来的文案忽略。 */
    public static void setLevelByLabel(String label) {
        for (int level : LEVELS) {
            if (labelOf(level).equals(label)) {
                setLevelPercent(level);
                return;
            }
        }
    }

    static String labelOf(int percent) {
        return percent <= 0 ? "静音" : percent + "%";
    }

    /** 落盘里读到的值不在档位表里时，回到默认档。 */
    static int normalizeLevel(int percent) {
        for (int level : LEVELS) if (level == percent) return level;
        return DEFAULT_LEVEL;
    }

    private static void load() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            try {
                File file = ConfigPaths.FOCUS_VOLUME_DUCKER;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                if (root.has("enabled") && !root.get("enabled").isJsonNull()) {
                    enabled = root.get("enabled").getAsBoolean();
                }
                if (root.has("levelPercent") && !root.get("levelPercent").isJsonNull()) {
                    levelPercent = normalizeLevel(root.get("levelPercent").getAsInt());
                }
            } catch (Throwable ignored) {
                // 配置坏了用默认值，不能影响播放。
            } finally {
                loaded = true;
            }
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.FOCUS_VOLUME_DUCKER.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("levelPercent", levelPercent);
            JsonConfigStorage.writeObject(ConfigPaths.FOCUS_VOLUME_DUCKER, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}
