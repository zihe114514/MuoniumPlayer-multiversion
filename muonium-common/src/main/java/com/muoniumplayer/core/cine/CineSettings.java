package com.muoniumplayer.core.cine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 「卡点运镜」（MuoniumCine）的设置：播放中闲置后按音乐节拍自动运镜。
 *
 * <h3>为什么自己存文件而不进 HudConfig</h3>
 * <p>照 {@code TransparentMode}/{@code AcousticsSettings} 的路子单独一个小文件：
 * 设置页上每颗开关/滑条都要立即落盘，读失败的代价只是「回到关闭」，不该牵连主配置。
 * {@code HudConfig} 是版本树各自一份的留守文件，每加一个字段要改七份。</p>
 *
 * <h3>线程纪律</h3>
 * <p>读侧（渲染帧的 mixin、tick 桥）只做 {@code load()} + 锁内取值；写侧（设置页）
 * {@code set} 后立即 {@code saveLocked()}。全部 {@code Throwable} 闸，配置坏了回默认值。</p>
 */
public final class CineSettings {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    /** 闲置秒数的合法区间。下限 5s 防误触（走路短暂停手不该进运镜）。 */
    public static final int IDLE_SECONDS_MIN = 5, IDLE_SECONDS_MAX = 3600;
    /** 镜头时长（拍数）的合法区间：1 拍 = 最快的 MV 快切，32 拍 ≈ 主歌长镜头。 */
    public static final int SHOT_BEATS_MIN = 1, SHOT_BEATS_MAX = 32;
    /** 运镜速度乘数区间：0 = 镜头内静止（纯硬切），4 = 四倍角速度（对齐 cameraSpeed）。 */
    public static final float SPEED_MIN = 0f, SPEED_MAX = 4f;
    /** 生物搜索半径区间（格），对齐 Ambient Camera 的 mobFocusRange。 */
    public static final float MOB_RANGE_MIN = 6f, MOB_RANGE_MAX = 64f;
    /** 追踪软度区间（秒），对齐 Ambient Camera 的 transitionSeconds。 */
    public static final float SOFTNESS_MIN = 0.1f, SOFTNESS_MAX = 8f;
    /** FOV 脉冲幅度区间（度）：0 = 关闭 punch。 */
    public static final float FOV_PUNCH_MIN = 0f, FOV_PUNCH_MAX = 20f;
    /** 手持感幅度区间（0..1）：0 = 呆照机。 */
    public static final float HANDHELD_MIN = 0f, HANDHELD_MAX = 1f;
    /** 每侧黑边高度上限（百分比屏高）：13% ≈ 2.39:1 @ 16:9。 */
    public static final int BAR_PERCENT_MAX = 25;
    /** 转场特效位掩码的合法上界：5 种特效（0..4 位）。 */
    public static final int TRANSITION_MASK_ALL = 0b11111;
    /**
     * 单镜头最短秒数（防快歌闪切）：拍制换算出的时长低于它就抬到它。
     * 181BPM 的 4 拍只有 1.3s——闪切会晕；默认 3s 保住「电影感」下限。
     */
    public static final float MIN_SHOT_SECONDS_DEFAULT = 3f;
    public static final float MIN_SHOT_SECONDS_MIN = 1f, MIN_SHOT_SECONDS_MAX = 12f;

    private static boolean loaded;

    /** 测试 IO 闸：置位后 load/save 全部旁路（测试 JVM 不许碰真实工作目录的 cine.json）。 */
    static boolean testIoHooked;
    private static boolean enabled;
    /**
     * 播放中持续多少秒无输入后进入运镜。默认 300s —— 对齐 Ambient Camera 的
     * idleSeconds（5..3600）：那是「屏保」语义，我们继承同一口径（卡点运镜是
     * 同场景替代品，不是常驻运镜）；调试时改 json 即可。
     */
    private static int idleSeconds = 300;
    /** 每个镜头的基准时长（拍数）。实际时长还会被段落能量调制（副歌更快）。 */
    private static int shotBeats = 4;
    /**
     * 镜头内运镜（环绕/推拉）的全局速度乘数。默认 0.7、区间 0..4 ——
     * 对齐 Ambient Camera 的 cameraSpeed（0=冻结机位，2=双倍速）。
     */
    private static float speedMultiplier = 0.7f;
    /** 鼓点 FOV 脉冲的最大幅度（度）。 */
    private static float fovPunchDegrees = 8f;
    /** 手持感噪声幅度（0..1）。 */
    private static float handheldIntensity = 0.4f;
    /** 运镜时隐藏 HUD（退出时恢复原状）。 */
    private static boolean hideHud = true;
    /** 顶部/底部电影黑边。默认关：黑边是口味问题（同 Ambient Camera）。 */
    private static boolean showCinematicBars;
    private static int cinematicBarPercent = 13;
    /**
     * 转场特效位掩码（bit0 黑淡、bit1 白淡、bit2 横擦、bit3 竖擦、bit4 光圈）。
     * 默认只开黑淡 —— 对齐 Ambient Camera 的 transitionEffect 默认值 1。
     */
    private static int transitionMask = 0b00001;
    /**
     * 转场时长（秒，每半程）。默认 1.0 —— 对齐 Ambient Camera 的
     * fadeDurationSeconds（0.1..3）。
     */
    private static float transitionSeconds = 1.0f;
    /** 生物追踪镜头总开关。 */
    private static boolean enableMobFocus = true;
    /** 生物搜索半径（格）。默认 32、区间 6..64 —— 对齐 Ambient Camera 的 mobFocusRange。 */
    private static float mobFocusRange = 32f;
    /** 排除敌对生物做镜头主体（黑暗里突然怼脸苦力怕是恐怖片不是 MV）。 */
    private static boolean excludeHostileMobs = true;
    private static boolean excludeBats = true;
    /**
     * 朝向/位置追踪软度（秒）。默认 0.8、区间 0.1..8 —— 对齐 Ambient Camera 的
     * transitionSeconds（tracking softness）；换算成 exp 平滑的响应：response = 3.2/softness。
     */
    private static float trackingSoftness = 0.8f;
    /** 状态提示（action bar）：启动/退出运镜时打一行。 */
    private static boolean showStatusMessages = true;
    /**
     * 单镜头最短秒数。拍制时长低于它就抬到它（防快歌闪切）；同时是 12s 兜底
     * 之外的玩家可调节奏旋钮。默认 3s。
     */
    private static float minShotSeconds = MIN_SHOT_SECONDS_DEFAULT;

    /** Captured after all field initializers, before any disk load. */
    private static final JsonObject DEFAULTS = toJsonLocked();

    private CineSettings() {
    }

    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static void setEnabled(boolean value) {
        load();
        synchronized (LOCK) {
            enabled = value;
            saveLocked();
        }
    }

    public static int idleSeconds() {
        load();
        synchronized (LOCK) {
            return idleSeconds;
        }
    }

    public static void setIdleSeconds(int value) {
        int v = clamp(value, IDLE_SECONDS_MIN, IDLE_SECONDS_MAX);
        load();
        synchronized (LOCK) {
            idleSeconds = v;
            saveLocked();
        }
    }

    public static int shotBeats() {
        load();
        synchronized (LOCK) {
            return shotBeats;
        }
    }

    public static void setShotBeats(int value) {
        int v = clamp(value, SHOT_BEATS_MIN, SHOT_BEATS_MAX);
        load();
        synchronized (LOCK) {
            shotBeats = v;
            saveLocked();
        }
    }

    public static float speedMultiplier() {
        load();
        synchronized (LOCK) {
            return speedMultiplier;
        }
    }

    public static void setSpeedMultiplier(float value) {
        float v = clamp(value, SPEED_MIN, SPEED_MAX);
        load();
        synchronized (LOCK) {
            speedMultiplier = v;
            saveLocked();
        }
    }

    public static float fovPunchDegrees() {
        load();
        synchronized (LOCK) {
            return fovPunchDegrees;
        }
    }

    public static void setFovPunchDegrees(float value) {
        float v = clamp(value, FOV_PUNCH_MIN, FOV_PUNCH_MAX);
        load();
        synchronized (LOCK) {
            fovPunchDegrees = v;
            saveLocked();
        }
    }

    public static float handheldIntensity() {
        load();
        synchronized (LOCK) {
            return handheldIntensity;
        }
    }

    public static void setHandheldIntensity(float value) {
        float v = clamp(value, HANDHELD_MIN, HANDHELD_MAX);
        load();
        synchronized (LOCK) {
            handheldIntensity = v;
            saveLocked();
        }
    }

    public static boolean hideHud() {
        load();
        synchronized (LOCK) {
            return hideHud;
        }
    }

    public static void setHideHud(boolean value) {
        load();
        synchronized (LOCK) {
            hideHud = value;
            saveLocked();
        }
    }

    public static boolean showCinematicBars() {
        load();
        synchronized (LOCK) {
            return showCinematicBars;
        }
    }

    public static void setShowCinematicBars(boolean value) {
        load();
        synchronized (LOCK) {
            showCinematicBars = value;
            saveLocked();
        }
    }

    public static int cinematicBarPercent() {
        load();
        synchronized (LOCK) {
            return cinematicBarPercent;
        }
    }

    public static void setCinematicBarPercent(int value) {
        int v = clamp(value, 0, BAR_PERCENT_MAX);
        load();
        synchronized (LOCK) {
            cinematicBarPercent = v;
            saveLocked();
        }
    }

    public static int transitionMask() {
        load();
        synchronized (LOCK) {
            return transitionMask;
        }
    }

    public static void setTransitionMask(int value) {
        int v = value & TRANSITION_MASK_ALL;
        load();
        synchronized (LOCK) {
            transitionMask = v;
            saveLocked();
        }
    }

    public static float transitionSeconds() {
        load();
        synchronized (LOCK) {
            return transitionSeconds;
        }
    }

    public static void setTransitionSeconds(float value) {
        float v = clamp(value, 0.1f, 3f);
        load();
        synchronized (LOCK) {
            transitionSeconds = v;
            saveLocked();
        }
    }

    public static boolean enableMobFocus() {
        load();
        synchronized (LOCK) {
            return enableMobFocus;
        }
    }

    public static void setEnableMobFocus(boolean value) {
        load();
        synchronized (LOCK) {
            enableMobFocus = value;
            saveLocked();
        }
    }

    public static boolean excludeHostileMobs() {
        load();
        synchronized (LOCK) {
            return excludeHostileMobs;
        }
    }

    public static void setExcludeHostileMobs(boolean value) {
        load();
        synchronized (LOCK) {
            excludeHostileMobs = value;
            saveLocked();
        }
    }

    public static boolean excludeBats() {
        load();
        synchronized (LOCK) {
            return excludeBats;
        }
    }

    public static void setExcludeBats(boolean value) {
        load();
        synchronized (LOCK) {
            excludeBats = value;
            saveLocked();
        }
    }

    public static float mobFocusRange() {
        load();
        synchronized (LOCK) {
            return mobFocusRange;
        }
    }

    public static void setMobFocusRange(float value) {
        float v = clamp(value, MOB_RANGE_MIN, MOB_RANGE_MAX);
        load();
        synchronized (LOCK) {
            mobFocusRange = v;
            saveLocked();
        }
    }

    public static float trackingSoftness() {
        load();
        synchronized (LOCK) {
            return trackingSoftness;
        }
    }

    public static void setTrackingSoftness(float value) {
        float v = clamp(value, SOFTNESS_MIN, SOFTNESS_MAX);
        load();
        synchronized (LOCK) {
            trackingSoftness = v;
            saveLocked();
        }
    }

    public static boolean showStatusMessages() {
        load();
        synchronized (LOCK) {
            return showStatusMessages;
        }
    }

    public static void setShowStatusMessages(boolean value) {
        load();
        synchronized (LOCK) {
            showStatusMessages = value;
            saveLocked();
        }
    }

    public static float minShotSeconds() {
        load();
        synchronized (LOCK) {
            return minShotSeconds;
        }
    }

    public static void setMinShotSeconds(float value) {
        float v = clamp(value, MIN_SHOT_SECONDS_MIN, MIN_SHOT_SECONDS_MAX);
        load();
        synchronized (LOCK) {
            minShotSeconds = v;
            saveLocked();
        }
    }

    /** 测试专用：清 loaded 标记与状态，隔离文件系统。 */
    static void resetForTest() {
        synchronized (LOCK) {
            loaded = false;
            testIoHooked = true;
            enabled = false;
            idleSeconds = 300;
            shotBeats = 4;
            speedMultiplier = 0.7f;
            fovPunchDegrees = 8f;
            handheldIntensity = 0.4f;
            hideHud = true;
            showCinematicBars = false;
            cinematicBarPercent = 13;
            transitionMask = 0b00001;
            transitionSeconds = 1.0f;
            enableMobFocus = true;
            mobFocusRange = 32f;
            excludeHostileMobs = true;
            excludeBats = true;
            trackingSoftness = 0.8f;
            showStatusMessages = true;
            minShotSeconds = MIN_SHOT_SECONDS_DEFAULT;
        }
    }

    /** 测试专用：注入已加载状态（绕过文件读），验证 save 布局用。 */
    static void forceForTest(boolean enabledIn, int idleSecondsIn, int maskIn) {
        synchronized (LOCK) {
            loaded = true;
            enabled = enabledIn;
            idleSeconds = clamp(idleSecondsIn, IDLE_SECONDS_MIN, IDLE_SECONDS_MAX);
            transitionMask = maskIn & TRANSITION_MASK_ALL;
        }
    }

    /** 测试专用：直读当前内存值的 JSON 投影。 */
    static JsonObject snapshotForTest() {
        synchronized (LOCK) {
            return toJsonLocked();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            if (testIoHooked) return; // 测试闸:不读真实文件
            try {
                File file = ConfigPaths.CINE;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                readLocked(root);
            } catch (Throwable ignored) {
                // 配置坏了用默认值,不能影响播放。
            }
        }
    }

    public static JsonObject toJson() {
        load();
        synchronized (LOCK) { return toJsonLocked(); }
    }

    public static JsonObject defaultsJson() { return DEFAULTS.deepCopy(); }

    /** Main-thread preset seam; original clamp/load semantics, missing keys preserve current values. */
    public static void applyJson(JsonObject root) {
        if (root == null) return;
        load();
        synchronized (LOCK) {
            readLocked(root);
            if (!testIoHooked) {
                try { JsonConfigStorage.writeObject(ConfigPaths.CINE, GSON, toJsonLocked()); }
                catch (java.io.IOException failure) { throw new IllegalStateException("运镜配置落盘失败", failure); }
            }
        }
    }

    private static void readLocked(JsonObject root) {
        enabled = optBool(root, "enabled", enabled);
        idleSeconds = clamp(optInt(root, "idleSeconds", idleSeconds), IDLE_SECONDS_MIN, IDLE_SECONDS_MAX);
        shotBeats = clamp(optInt(root, "shotBeats", shotBeats), SHOT_BEATS_MIN, SHOT_BEATS_MAX);
        speedMultiplier = clamp(optFloat(root, "speedMultiplier", speedMultiplier), SPEED_MIN, SPEED_MAX);
        fovPunchDegrees = clamp(optFloat(root, "fovPunchDegrees", fovPunchDegrees), FOV_PUNCH_MIN, FOV_PUNCH_MAX);
        handheldIntensity = clamp(optFloat(root, "handheldIntensity", handheldIntensity), HANDHELD_MIN, HANDHELD_MAX);
        hideHud = optBool(root, "hideHud", hideHud);
        showCinematicBars = optBool(root, "showCinematicBars", showCinematicBars);
        cinematicBarPercent = clamp(optInt(root, "cinematicBarPercent", cinematicBarPercent), 0, BAR_PERCENT_MAX);
        transitionMask = optInt(root, "transitionMask", transitionMask) & TRANSITION_MASK_ALL;
        transitionSeconds = clamp(optFloat(root, "transitionSeconds", transitionSeconds), 0.1f, 3f);
        enableMobFocus = optBool(root, "enableMobFocus", enableMobFocus);
        mobFocusRange = clamp(optFloat(root, "mobFocusRange", mobFocusRange), MOB_RANGE_MIN, MOB_RANGE_MAX);
        excludeHostileMobs = optBool(root, "excludeHostileMobs", excludeHostileMobs);
        excludeBats = optBool(root, "excludeBats", excludeBats);
        trackingSoftness = clamp(optFloat(root, "trackingSoftness", trackingSoftness), SOFTNESS_MIN, SOFTNESS_MAX);
        showStatusMessages = optBool(root, "showStatusMessages", showStatusMessages);
        minShotSeconds = clamp(optFloat(root, "minShotSeconds", minShotSeconds), MIN_SHOT_SECONDS_MIN, MIN_SHOT_SECONDS_MAX);
    }

    private static void saveLocked() {
        if (testIoHooked) return; // 测试闸:不写真实文件
        try {
            File file = ConfigPaths.CINE;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonConfigStorage.writeObject(file, GSON, toJsonLocked());
        } catch (Throwable ignored) {
        }
    }

    private static JsonObject toJsonLocked() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        root.addProperty("idleSeconds", idleSeconds);
        root.addProperty("shotBeats", shotBeats);
        root.addProperty("speedMultiplier", speedMultiplier);
        root.addProperty("fovPunchDegrees", fovPunchDegrees);
        root.addProperty("handheldIntensity", handheldIntensity);
        root.addProperty("hideHud", hideHud);
        root.addProperty("showCinematicBars", showCinematicBars);
        root.addProperty("cinematicBarPercent", cinematicBarPercent);
        root.addProperty("transitionMask", transitionMask);
        root.addProperty("transitionSeconds", transitionSeconds);
        root.addProperty("enableMobFocus", enableMobFocus);
        root.addProperty("mobFocusRange", mobFocusRange);
        root.addProperty("excludeHostileMobs", excludeHostileMobs);
        root.addProperty("excludeBats", excludeBats);
        root.addProperty("trackingSoftness", trackingSoftness);
        root.addProperty("showStatusMessages", showStatusMessages);
        root.addProperty("minShotSeconds", minShotSeconds);
        return root;
    }

    private static boolean optBool(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) return fallback;
        try {
            return root.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int optInt(JsonObject root, String key, int fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) return fallback;
        try {
            return root.get(key).getAsInt();
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
