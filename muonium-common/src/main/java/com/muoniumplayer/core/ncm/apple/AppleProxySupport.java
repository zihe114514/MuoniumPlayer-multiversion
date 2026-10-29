package com.muoniumplayer.core.ncm.apple;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * Apple 音源的可选 HTTP 代理（host:port，来自 {@link AppleConfigStore}）。
 *
 * <p>CN 网络直连 amp-api.music.apple.com 经常丢包/被重置，搜索与歌词会「时好时坏」；
 * 给这一条链路单独一个代理口（不污染全局设置），是与 YouTube 代理同款的理由：
 * 平台级网络特征不同，代理需求也不同。</p>
 */
public final class AppleProxySupport {

    private AppleProxySupport() {
    }

    /** 无代理或配置非法时返回 null（调用方走直连）。 */
    public static Proxy currentProxyOrNull() {
        String raw = AppleConfigStore.snapshot().proxy;
        String host = parseHost(raw);
        int port = parsePort(raw);
        if (host.isEmpty() || port < 1 || port > 65535) return null;
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
    }

    /** 形状校验（host:port）；设置页保存前用它拦手滑。 */
    public static boolean looksValid(String raw) {
        if (raw == null || raw.trim().isEmpty()) return true;
        String host = parseHost(raw.trim());
        int port = parsePort(raw.trim());
        return !host.isEmpty() && port >= 1 && port <= 65535;
    }

    private static String parseHost(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        int split = trimmed.lastIndexOf(':');
        if (split <= 0 || split == trimmed.length() - 1) return "";
        String host = trimmed.substring(0, split);
        if (host.isEmpty() || host.indexOf('/') >= 0 || host.indexOf('@') >= 0
                || host.indexOf(':') >= 0) return "";
        return host;
    }

    private static int parsePort(String text) {
        if (text == null) return -1;
        String trimmed = text.trim();
        int split = trimmed.lastIndexOf(':');
        if (split < 0 || split == trimmed.length() - 1) return -1;
        try {
            return Integer.parseInt(trimmed.substring(split + 1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
