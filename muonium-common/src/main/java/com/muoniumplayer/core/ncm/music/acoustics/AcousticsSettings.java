package com.muoniumplayer.core.ncm.music.acoustics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 场景声学的用户偏好:总开关、三个分项、强度档。
 *
 * <p>持久化语义照 {@code VanillaMusicDucker} 那一族:懒加载、锁内读写、写失败静默,
 * 读取路径零 IO —— {@code loaded} 置位后字段读是纯内存。与那族不同的地方:字段有
 * 五个,不再是一个布尔,所以写入统一走 {@link #snapshot} 集中改,避免调用方各自
 * 「读-改-存」把中间态写盘。</p>
 */
public final class AcousticsSettings {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    private static boolean loaded;
    private static boolean enabled;
    private static boolean submersion = true;
    private static boolean enclosure = true;
    private static boolean rainfall = true;
    /** 强度档 0..100(直接存 UI 口径,避免两套单位换算)。 */
    private static int strengthPercent = 100;

    private AcousticsSettings() {
    }

    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static boolean isSubmersion() {
        load();
        synchronized (LOCK) {
            return submersion;
        }
    }

    public static boolean isEnclosure() {
        load();
        synchronized (LOCK) {
            return enclosure;
        }
    }

    public static boolean isRainfall() {
        load();
        synchronized (LOCK) {
            return rainfall;
        }
    }

    /** 强度 0..1(UI 的 0..100 除以一百)。 */
    public static float strength() {
        load();
        synchronized (LOCK) {
            return strengthPercent / 100f;
        }
    }

    public static int strengthPercent() {
        load();
        synchronized (LOCK) {
            return strengthPercent;
        }
    }

    // 写入统一走具名 setXxx(见下):曾经的 snapshot(五个同类型可空参数按位置传)
    // 出过实测事故 —— 分项值错位进 enable 槽位,点分项开关等于静默关总开关。

    /** 总开关;立即落盘。 */
    public static void setEnabled(boolean value) {
        write(value, null, null, null, null);
    }

    /** 「水面之下」分项;立即落盘。 */
    public static void setSubmersion(boolean value) {
        write(null, value, null, null, null);
    }

    /** 「封闭空间」分项;立即落盘。 */
    public static void setEnclosure(boolean value) {
        write(null, null, value, null, null);
    }

    /** 「降水天气」分项;立即落盘。 */
    public static void setRainfall(boolean value) {
        write(null, null, null, value, null);
    }

    /** 强度 0..100(UI 口径);立即落盘。 */
    public static void setStrengthPercent(int value) {
        write(null, null, null, null, value);
    }

    private static void write(Boolean enable, Boolean submersionOn, Boolean enclosureOn,
                              Boolean rainfallOn, Integer strengthPct) {
        load();
        synchronized (LOCK) {
            if (enable != null) enabled = enable;
            if (submersionOn != null) submersion = submersionOn;
            if (enclosureOn != null) enclosure = enclosureOn;
            if (rainfallOn != null) rainfall = rainfallOn;
            if (strengthPct != null) strengthPercent = Math.max(0, Math.min(100, strengthPct));
            saveLocked();
        }
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.SCENE_ACOUSTICS;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                enabled = optBool(root, "enabled", false);
                submersion = optBool(root, "submersion", true);
                enclosure = optBool(root, "enclosure", true);
                rainfall = optBool(root, "rainfall", true);
                strengthPercent = optInt(root, "strength", 100);
            } catch (Throwable ignored) {
                // 配置坏了用默认值,不能影响播放。
            }
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.SCENE_ACOUSTICS.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("submersion", submersion);
            root.addProperty("enclosure", enclosure);
            root.addProperty("rainfall", rainfall);
            root.addProperty("strength", strengthPercent);
            JsonConfigStorage.writeObject(ConfigPaths.SCENE_ACOUSTICS, GSON, root);
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

    private static int optInt(JsonObject root, String key, int fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) return fallback;
        try {
            return root.get(key).getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
