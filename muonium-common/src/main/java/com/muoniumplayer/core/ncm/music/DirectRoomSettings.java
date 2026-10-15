package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 直连房间（无服务端 mod 的一起听）的行为开关。
 *
 * <p>存两项：
 * <ol>
 *   <li>{@code lastPort} —— 上一次开直连房间用的监听端口；</li>
 *   <li>{@code lastHost} —— 上一次填的邀请串地址（穿透地址或局域网 IP）。</li>
 * </ol>
 *
 * <p>监听地址固定 {@code 0.0.0.0}（局域网 IP 直连与 frp 本机回连都通，
 * 走哪条由邀请串里的地址决定），不再有开关。</p>
 */
public final class DirectRoomSettings {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static final String KEY_LAST_PORT = "lastPort";
    private static final String KEY_LAST_HOST = "lastHost";

    private static volatile boolean loaded;
    private static int lastPort = 24500;
    private static String lastHost = "";

    private DirectRoomSettings() {
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            JsonObject root = readRootLocked();
            lastPort = intOf(root, KEY_LAST_PORT, 24500);
            if (lastPort < 1024 || lastPort > 65535) lastPort = 24500;
            lastHost = strOf(root, KEY_LAST_HOST, "");
        }
    }

    public static int lastPort() {
        load();
        synchronized (LOCK) {
            return lastPort;
        }
    }

    public static void setLastPort(int value) {
        load();
        synchronized (LOCK) {
            if (lastPort == value) return;
            lastPort = value;
            saveLocked();
        }
    }

    /**
     * 上一次填的邀请串地址（内网穿透给的地址或局域网 IP）。
     * 空串 = 没填过。
     */
    public static String lastHost() {
        load();
        synchronized (LOCK) {
            return lastHost;
        }
    }

    public static void setLastHost(String value) {
        load();
        synchronized (LOCK) {
            String next = value == null ? "" : value.trim();
            if (lastHost.equals(next)) return;
            lastHost = next;
            saveLocked();
        }
    }

    private static JsonObject readRootLocked() {
        try {
            File file = ConfigPaths.DIRECT_ROOM;
            if (!file.isFile()) return null;
            return JsonConfigStorage.readObject(file, GSON);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int intOf(JsonObject root, String key, int def) {
        try {
            if (root == null || !root.has(key) || root.get(key).isJsonNull()) return def;
            return root.get(key).getAsInt();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String strOf(JsonObject root, String key, String def) {
        try {
            if (root == null || !root.has(key) || root.get(key).isJsonNull()) return def;
            String v = root.get(key).getAsString();
            return v == null ? def : v;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.DIRECT_ROOM.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;
            JsonObject root = new JsonObject();
            root.addProperty(KEY_LAST_PORT, lastPort);
            root.addProperty(KEY_LAST_HOST, lastHost);
            JsonConfigStorage.writeObject(ConfigPaths.DIRECT_ROOM, GSON, root);
        } catch (Throwable ignored) {
        }
    }
}
