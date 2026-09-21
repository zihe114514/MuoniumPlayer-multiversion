package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 跨平台「自动换源」的开关。
 *
 * <p>只有一项：一首歌在自己的链路上彻底取不到流（含网易云解灰流水线也救不回来）之后，
 * 要不要拿着曲名 / 艺人去别的平台重新匹配一条<b>真正的</b>曲目来替换播放。
 * 默认开启 —— 关掉就完全回到原来的行为（解析失败 → 灵动岛报「没有可用音频源」→ 停下等用户操作）。</p>
 *
 * <p>与 {@link NeteaseUnblockSettings} 分开各存一个文件：解灰换的是「这一条网易云曲目的取流地址」，
 * 本开关换的是「播放的到底是哪一首歌」，两者的失败半径完全不同，不该共用一次损坏的配置读写。</p>
 *
 * <p>手动右键换源不受这个开关约束：那是用户的显式意图。开关只压制<b>播放线程里的自动触发</b>。</p>
 */
public final class SwitchSourceSettings {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static final String KEY_ENABLED = "autoSwitchSource";

    private static volatile boolean loaded;
    private static boolean enabled = true;

    private SwitchSourceSettings() {
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            enabled = loadFlagLocked();
        }
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
            if (enabled == value) return;
            enabled = value;
            saveLocked();
        }
    }

    private static boolean loadFlagLocked() {
        try {
            File file = ConfigPaths.SWITCH_SOURCE;
            if (!file.isFile()) return true;
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            if (root == null || !root.has(KEY_ENABLED) || root.get(KEY_ENABLED).isJsonNull()) return true;
            return root.get(KEY_ENABLED).getAsBoolean();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.SWITCH_SOURCE.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty(KEY_ENABLED, enabled);
            JsonConfigStorage.writeObject(ConfigPaths.SWITCH_SOURCE, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}
