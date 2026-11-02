package com.muoniumplayer.core.ncm.music.ambient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 环境选曲的用户偏好：总开关、心情稳定期。
 *
 * <p>持久化语义照 {@code AcousticsSettings} 那一族：懒加载、锁内读写、写失败静默，
 * 读取路径零 IO —— {@code loaded} 置位后字段读是纯内存。写走具名 setter 立即落盘。</p>
 */
public final class AmbientSettings {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Object LOCK = new Object();

    private static boolean loaded;
    private static boolean enabled;
    /** 新心情必须连续保持这么久才触发切换（防抖），毫秒。 */
    private static int stableMs = 15_000;

    /** 稳定期的合法范围：太短会跟着环境抖动切歌，太长跟不上走位。 */
    public static final int STABLE_MS_MIN = 5_000;
    public static final int STABLE_MS_MAX = 120_000;

    private AmbientSettings() {
    }

    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled;
        }
    }

    public static int stableMs() {
        load();
        synchronized (LOCK) {
            return stableMs;
        }
    }

    /** 总开关；立即落盘。 */
    public static void setEnabled(boolean value) {
        write(value, null);
    }

    /** 稳定期毫秒（自动钳到 {@link #STABLE_MS_MIN}..{@link #STABLE_MS_MAX}）；立即落盘。 */
    public static void setStableMs(int value) {
        write(null, value);
    }

    private static void write(Boolean enable, Integer stable) {
        load();
        synchronized (LOCK) {
            if (enable != null) enabled = enable;
            if (stable != null) stableMs = Math.max(STABLE_MS_MIN, Math.min(STABLE_MS_MAX, stable));
            saveLocked();
        }
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                File file = ConfigPaths.AMBIENT_DJ;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;
                enabled = optBool(root, "enabled", false);
                stableMs = optInt(root, "stableMs", 15_000);
            } catch (Throwable ignored) {
                // 配置坏了用默认值，不能影响播放。
            }
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.AMBIENT_DJ.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("stableMs", stableMs);
            JsonConfigStorage.writeObject(ConfigPaths.AMBIENT_DJ, GSON, root);
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
