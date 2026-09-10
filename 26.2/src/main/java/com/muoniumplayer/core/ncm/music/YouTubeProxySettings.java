package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * YouTube 专用 HTTP 代理设置。
 *
 * <p>这个配置只被 {@link YouTubeMusicService} 与 YouTube 曲目的音频 / 封面下载使用。网易云、
 * QQ、酷狗、GD、Bilibili、更新检查与 LX 脚本网络都不读取它，避免“为了 YouTube 挂代理”改变
 * 其他来源的访问路径。</p>
 */
public final class YouTubeProxySettings {

    private static final Object LOCK = new Object();
    private static final Gson GSON = new Gson();

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ENDPOINT = "endpoint";

    private static volatile boolean loaded;
    private static boolean enabled;
    private static String endpoint = "";

    private YouTubeProxySettings() {
    }

    public static void load() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;

            try {
                File file = ConfigPaths.YOUTUBE_PROXY;
                if (!file.isFile()) return;
                JsonObject root = JsonConfigStorage.readObject(file, GSON);
                if (root == null) return;

                if (root.has(KEY_ENDPOINT) && !root.get(KEY_ENDPOINT).isJsonNull()) {
                    endpoint = normalizeEndpoint(root.get(KEY_ENDPOINT).getAsString());
                }
                if (root.has(KEY_ENABLED) && !root.get(KEY_ENABLED).isJsonNull()) {
                    enabled = root.get(KEY_ENABLED).getAsBoolean();
                }
            } catch (Throwable ignored) {
                enabled = false;
                endpoint = "";
            }
        }
    }

    /** 代理当前是否真正参与 YouTube 请求。无效地址会被视为未启用。 */
    public static boolean isEnabled() {
        load();
        synchronized (LOCK) {
            return enabled && !endpoint.isEmpty();
        }
    }

    /** 是否存在一个格式可用但可能未启用的代理地址。 */
    public static boolean isConfigured() {
        load();
        synchronized (LOCK) {
            return !endpoint.isEmpty();
        }
    }

    public static String getEndpoint() {
        load();
        synchronized (LOCK) {
            return endpoint;
        }
    }

    /** 原子保存开关与地址。地址为空可以保存（等于关闭），但无效地址不会写入。 */
    public static boolean setConfiguration(boolean enable, String value) {
        String normalized = normalizeEndpoint(value);
        if (enable && normalized.isEmpty()) return false;

        load();
        synchronized (LOCK) {
            enabled = enable;
            endpoint = normalized;
            saveLocked();
            return true;
        }
    }

    /** 返回 YouTube 请求应使用的 HTTP 代理；未启用时返回 {@code null}。 */
    public static Proxy createHttpProxy() {
        if (!isEnabled()) return null;

        Endpoint parsed = parse(getEndpoint());
        if (parsed == null) return null;
        return new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(parsed.host, parsed.port));
    }

    public static InetSocketAddress createInetSocketAddress() {
        if (!isEnabled()) return null;

        Endpoint parsed = parse(getEndpoint());
        return parsed == null ? null : InetSocketAddress.createUnresolved(parsed.host, parsed.port);
    }

    public static String describe() {
        if (!isConfigured()) return "未配置 · YouTube 使用直连";
        String address = getEndpoint();
        return isEnabled() ? "已启用 · HTTP " + address : "已保存 · " + address + "（未启用）";
    }

    private static void saveLocked() {
        try {
            File parent = ConfigPaths.YOUTUBE_PROXY.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) return;

            JsonObject root = new JsonObject();
            root.addProperty(KEY_ENABLED, enabled);
            if (!endpoint.isEmpty()) root.addProperty(KEY_ENDPOINT, endpoint);
            JsonConfigStorage.writeObject(ConfigPaths.YOUTUBE_PROXY, GSON, root);
        } catch (Throwable ignored) {
        }
    }

    private static String normalizeEndpoint(String value) {
        Endpoint parsed = parse(value);
        if (parsed == null) return "";

        String host = parsed.host.indexOf(':') >= 0 ? "[" + parsed.host + "]" : parsed.host;
        return host + ":" + parsed.port;
    }

    private static Endpoint parse(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return null;

        int scheme = text.indexOf("://");
        if (scheme >= 0) text = text.substring(scheme + 3);
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        if (text.isEmpty() || text.indexOf(' ') >= 0 || text.indexOf('\t') >= 0) return null;

        String host;
        String portText;
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            if (end < 0 || end + 1 >= text.length() || text.charAt(end + 1) != ':') return null;
            host = text.substring(1, end);
            portText = text.substring(end + 2);
        } else {
            int split = text.lastIndexOf(':');
            if (split <= 0 || split + 1 >= text.length()) return null;
            host = text.substring(0, split);
            portText = text.substring(split + 1);
        }

        if (host.isEmpty() || host.indexOf('/') >= 0 || host.indexOf('@') >= 0
                || host.indexOf(':') >= 0) return null;
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) return null;
            return new Endpoint(host, port);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}



