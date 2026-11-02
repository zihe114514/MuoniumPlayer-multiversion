package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import repackage.com.jsyn.engine.SynthesisEngine;
import repackage.processing.sound.Engine;

import java.io.File;

/**
 * 「游戏窗口失去焦点时压低本 mod 的音乐」：切出去查东西、回个消息时歌还在，但退到背景里。
 *
 * <h2>压在哪一层</h2>
 * <p>压在 JSyn 输出链的<b>最末一级</b>（{@link Engine#installFinalOutputProcessor}）：所有 deck 混好、
 * 蝰蛇音效与场景声学都处理完之后、写设备之前，对最终 PCM 乘倍率。直播流走独立的
 * {@link LiveStreamPlayer} 线路，在它的线路增益里乘 {@link #gain()}。</p>
 * <ul>
 *   <li><b>为什么不压在 deck 上</b>：deck 振幅在蝰蛇音效的压缩器 / 回放增益控制之前，压下去之后那些动态处理
 *       看到电平在掉会反手往回推，与渐变互相拉扯，听着像歌卡了一下；而且 deck 的振幅端口只能按缓冲改，
 *       天然是台阶。放到最末一级，前面谁都看不见它，也不碰 deck、交叉淡化与节拍可视化。</li>
 *   <li><b>不</b>改用户的持久化音量（{@code HudConfig.playerVolume}）：回到游戏不需要「恢复」任何东西，
 *       期间拖音量条也不会打架。</li>
 *   <li><b>不</b>碰原版声音类别与外部播放器镜像（SMTC / Spotify Connect）：那些声音不是我们的引擎出的。</li>
 * </ul>
 *
 * <h2>判据与驱动</h2>
 * <p>焦点状态由各版本树的客户端 tick 读 {@code Minecraft.isWindowActive()} 后传进来
 * （共享层不碰 MC 符号）。客户端 tick 在窗口失焦、单人暂停菜单下照常跑。</p>
 *
 * <h2>过渡怎么做</h2>
 * <ul>
 *   <li><b>按时间算</b>：每次焦点变化，主线程只记一份快照（起点时刻、起点进度、方向），任何线程任何时刻
 *       都能从快照算出当前进度 —— 没有共享可变状态。中途反悔从当前进度原路折返，不跳。</li>
 *   <li><b>逐采样插值</b>：最末一级每个缓冲（64 帧 ≈ 1.5ms）取一次目标倍率，从上一缓冲的末值到本缓冲的
 *       目标逐帧线性过渡 —— 没有台阶。稳态倍率为 1 时直接返回，零开销。</li>
 *   <li><b>听感匀速</b>：进度先过 smoothstep（起止都柔），再按<b>分贝</b>插值成倍率。</li>
 *   <li><b>时长与档位无关</b>：压下 {@link #FADE_OUT_NANOS}、恢复 {@link #FADE_IN_NANOS}。</li>
 * </ul>
 *
 * <h2>持久化</h2>
 * <p>{@code config/muonium/focus_volume_ducker.json}，照 {@link VanillaMusicDucker} 那一族：懒加载、
 * 锁内写、写失败静默。tick 路径在 {@code loaded} 置位后零 IO。</p>
 */
public final class FocusVolumeDucker {

    /** 可选的压低档位（占原音量的百分比）。0 即静音。 */
    public static final int[] LEVELS = {0, 10, 25, 50};
    public static final int DEFAULT_LEVEL = 25;

    /** 失焦时从原音量压到档位的总时长。 */
    static final long FADE_OUT_NANOS = 1_500_000_000L;
    /** 回焦时从档位回到原音量的总时长。 */
    static final long FADE_IN_NANOS = 1_000_000_000L;
    /** 「静音」档在渐变途中按这个分贝下限插值，到终点再落到真正的 0。 */
    static final float MUTE_FLOOR_DB = -60f;

    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    /** 双检锁的快路径标记：volatile，且只在读盘结束后置位。 */
    private static volatile boolean loaded;
    /** 开关（默认开）。只在锁内写；tick 与音频线程无锁读。 */
    private static volatile boolean enabled = true;
    /** 压低到原音量的百分比，取值见 {@link #LEVELS}。 */
    private static volatile int levelPercent = DEFAULT_LEVEL;

    /** 当前渐变的快照。只有主线程替换；任何线程都可以读来算倍率。 */
    private static volatile Ramp ramp = Ramp.IDLE;

    /** 最末一级上一个缓冲结束时的倍率；只有音频线程碰。 */
    private static float lastBufferGain = 1.0f;

    /** 输出链最末一级：每缓冲从上一缓冲的末值到当前目标逐帧过渡。音频回调里跑：零分配、无锁、不抛。 */
    static final SynthesisEngine.OutputProcessor OUTPUT_STAGE = (interleaved, channels, sampleRate) -> {
        try {
            float from = lastBufferGain;
            float to = gainAt(System.nanoTime());
            lastBufferGain = to;
            applyRamp(interleaved, channels, from, to);
        } catch (Throwable ignored) {
            // 这一缓冲原样放行，下一缓冲再算。
        }
    };

    private FocusVolumeDucker() {
    }

    /** 输出倍率，{@code 0..1}。窗口在前台（或开关关着）且不在回升途中时恒为 1。 */
    public static float gain() {
        return gainAt(System.nanoTime());
    }

    private static float gainAt(long nowNanos) {
        Ramp r = ramp;
        if (r == Ramp.IDLE) return 1.0f;
        return gainFor(r.progressAt(nowNanos), levelPercent);
    }

    /**
     * 每个客户端 tick 调一次。所有异常吞掉：这条在主线程每拍都走，炸了不能带走游戏。
     *
     * @param windowActive 游戏窗口此刻是否拥有焦点
     */
    public static void onClientTick(boolean windowActive) {
        try {
            load();
            // 每拍登记一次：两次 volatile 写，换来「引擎晚建 / 切驱动 / 被意外摘掉」都能自愈；
            // 登记本身不会把音频设备开起来。
            Engine.installFinalOutputProcessor(OUTPUT_STAGE);

            long now = System.nanoTime();
            boolean ducking = enabled && !windowActive;
            Ramp current = ramp;
            if (current.ducking != ducking) {
                // 方向变了：从此刻的进度起一段新的渐变。
                float p = current.progressAt(now);
                ramp = (!ducking && p <= 0f) ? Ramp.IDLE : new Ramp(now, p, ducking);
            } else if (!ducking && current != Ramp.IDLE && current.progressAt(now) <= 0f) {
                // 回升走完：换回 IDLE，让 gain() 与最末一级走快路径。
                ramp = Ramp.IDLE;
            }
        } catch (Throwable ignored) {
            // 宁可这一拍不压，也不能让异常冒到 tick 循环里。
        }
    }

    /** smoothstep：起止斜率都是 0，进出都柔。 */
    static float ease(float p) {
        float t = Math.max(0f, Math.min(1f, p));
        return t * t * (3f - 2f * t);
    }

    /** 压低进度（0 = 原音量，1 = 压到档位）换算成倍率：缓动后按分贝插值。 */
    static float gainFor(float progress, int levelPercent) {
        float p = Math.max(0f, Math.min(1f, progress));
        if (p <= 0f) return 1.0f;
        int level = Math.max(0, Math.min(100, levelPercent));
        if (level >= 100) return 1.0f;
        if (level == 0 && p >= 1f) return 0.0f;
        float targetDb = level == 0 ? MUTE_FLOOR_DB : (float) (20.0 * Math.log10(level / 100.0));
        return (float) Math.pow(10.0, ease(p) * targetDb / 20.0);
    }

    /** 对交错 PCM 缓冲施加从 {@code from} 到 {@code to} 的逐帧线性倍率；两端都是 1 时原样返回。 */
    static void applyRamp(double[] interleaved, int channels, float from, float to) {
        if (interleaved == null || channels <= 0) return;
        if (from == 1.0f && to == 1.0f) return;
        int frames = interleaved.length / channels;
        if (frames <= 0) return;
        if (from == to) {
            for (int i = 0, n = frames * channels; i < n; i++) interleaved[i] *= to;
            return;
        }
        double step = (to - from) / (double) frames;
        double g = from;
        int idx = 0;
        for (int f = 0; f < frames; f++) {
            g += step;
            for (int c = 0; c < channels; c++) interleaved[idx++] *= g;
        }
    }

    /** 一段渐变的不可变快照：从 {@code startNanos} 时的 {@code startProgress} 出发，朝 {@code ducking} 指的方向走。 */
    static final class Ramp {
        static final Ramp IDLE = new Ramp(0L, 0f, false);

        final long startNanos;
        final float startProgress;
        final boolean ducking;

        Ramp(long startNanos, float startProgress, boolean ducking) {
            this.startNanos = startNanos;
            this.startProgress = Math.max(0f, Math.min(1f, startProgress));
            this.ducking = ducking;
        }

        /** 该快照在 {@code nowNanos} 时的压低进度，夹在 0..1。 */
        float progressAt(long nowNanos) {
            if (this == IDLE) return 0f;
            long elapsed = Math.max(0L, nowNanos - startNanos);
            if (ducking) {
                return Math.min(1f, startProgress + (float) elapsed / FADE_OUT_NANOS);
            }
            return Math.max(0f, startProgress - (float) elapsed / FADE_IN_NANOS);
        }
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
