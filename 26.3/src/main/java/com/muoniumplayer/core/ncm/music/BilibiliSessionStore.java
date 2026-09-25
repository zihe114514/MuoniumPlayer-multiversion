package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地保存 Bilibili 的 Web Cookie 与账号展示资料。
 *
 * <p>形状与 {@link QishuiSessionStore} 一致：同一个原子写流程、同一份「快照不可变」的读接口。
 * 之所以不复用 {@code music_auth.json}，理由和酷狗/汽水那条注释相同 —— 那个文件由 Cadence 的
 * {@code MusicCredentialStore} 整段重写，它只认识网易云 cookie 与 QQ 的 musicid/musickey，
 * 写进去的 B 站凭据会在下一次保存时被丢掉。</p>
 *
 * <p>不保存二维码的 {@code qrcode_key} 与 {@code refresh_token}：前者是一次性会话、生命周期在
 * {@link BilibiliLogin} 的内存表里，后者只有走 B 站的刷新接口才有意义，而本模组不做自动刷新。</p>
 */
final class BilibiliSessionStore {

    /** 一次读取的不可变快照。字段全部在构造时定稿，调用方可以安全地跨线程持有。 */
    static final class Session {
        final boolean loggedIn;
        final String cookieHeader;
        final String csrf;
        final String userId;
        final String nickname;
        final String avatarUrl;
        final boolean vip;
        final String vipLabel;
        final int vipType;

        private Session(Data data) {
            Map<String, String> cookies = data.cookies == null
                    ? Collections.<String, String>emptyMap() : data.cookies;
            this.loggedIn = !nonBlank(cookies.get("SESSDATA")).isEmpty();
            this.cookieHeader = joinCookies(cookies);
            this.csrf = nonBlank(cookies.get("bili_jct"));
            this.userId = nonBlank(data.userId);
            this.nickname = nonBlank(data.nickname);
            this.avatarUrl = nonBlank(data.avatarUrl);
            this.vip = data.vip;
            this.vipLabel = nonBlank(data.vipLabel);
            this.vipType = data.vipType;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static Data data;
    private static boolean loaded;

    private BilibiliSessionStore() {
    }

    static Session snapshot() {
        synchronized (LOCK) {
            ensureLoaded();
            return new Session(data);
        }
    }

    static boolean isLoggedIn() {
        return snapshot().loggedIn;
    }

    /** 可直接放进 {@code Cookie} 请求头的拼接串；未登录时为空串。 */
    static String getCookieHeader() {
        return snapshot().cookieHeader;
    }

    /** CSRF Token（cookie 中的 {@code bili_jct}），写操作接口必需。没有则空串。 */
    static String getCsrfToken() {
        return snapshot().csrf;
    }

    static String getUserId() {
        return snapshot().userId;
    }

    /**
     * 合并保存扫码拿到的 cookie。
     *
     * <p>是「合并」而不是「覆盖」：B 站在 generate 与 poll 两次响应里分别下发 {@code buvid3}
     * 和 {@code SESSDATA}，分两批到达；整段覆盖会让前一批丢掉，而 {@code buvid3} 缺失会显著提高
     * 后续接口被风控的概率。</p>
     */
    static void saveCookies(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return;
        synchronized (LOCK) {
            ensureLoaded();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                String name = nonBlank(entry.getKey());
                String value = nonBlank(entry.getValue());
                if (!name.isEmpty() && !value.isEmpty()) data.cookies.put(name, value);
            }
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    static void saveProfile(String userId, String nickname, String avatarUrl,
                            boolean vip, int vipType, String vipLabel) {
        synchronized (LOCK) {
            ensureLoaded();
            if (!isBlank(userId)) data.userId = userId.trim();
            if (!isBlank(nickname)) data.nickname = nickname.trim();
            if (!isBlank(avatarUrl)) data.avatarUrl = avatarUrl.trim();
            data.vip = vip;
            data.vipType = vipType;
            if (!isBlank(vipLabel)) data.vipLabel = vipLabel.trim();
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    static void clear() {
        synchronized (LOCK) {
            data = new Data();
            loaded = true;
            try {
                Files.deleteIfExists(ConfigPaths.BILIBILI_AUTH.toPath());
            } catch (IOException ignored) {
                // 退出登录以内存状态为准；文件删不掉时下一次 save 也会覆盖它。
            }
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = ConfigPaths.BILIBILI_AUTH.toPath();
        if (!Files.isRegularFile(path)) {
            data = new Data();
            return;
        }
        try {
            Data parsed = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            data = parsed == null ? new Data() : parsed.normalized();
        } catch (Throwable ignored) {
            data = new Data();
        }
    }

    private static void persistLocked() {
        Path path = ConfigPaths.BILIBILI_AUTH.toPath();
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            System.err.println("[Music/Bilibili] Failed to persist session: " + error.getMessage());
        }
    }

    /**
     * 按 cookie 名排序后拼接。
     *
     * <p>排序不是好看：{@code LinkedHashMap} 的插入顺序取决于 B 站这次先下发了哪几个，
     * 于是同一次登录在两份快照里可能拼出两个不同的串。排序后拼接结果可复现，
     * 也让「保存后重新读出」这条静态不变式成立。</p>
     */
    static String joinCookies(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return "";
        List<String> parts = new ArrayList<>(cookies.size());
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            String name = nonBlank(entry.getKey());
            String value = nonBlank(entry.getValue());
            if (name.isEmpty() || value.isEmpty() || !validCookiePart(name, value)) continue;
            parts.add(name + "=" + value);
        }
        if (parts.isEmpty()) return "";
        Collections.sort(parts);
        return String.join("; ", parts);
    }

    private static boolean validCookiePart(String name, String value) {
        return name.matches("[A-Za-z0-9_!#$%&'*+.^`|~-]+")
                && value.indexOf(';') < 0 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return nonBlank(value).isEmpty();
    }

    private static final class Data {
        Map<String, String> cookies = new LinkedHashMap<>();
        String userId = "";
        String nickname = "";
        String avatarUrl = "";
        boolean vip;
        String vipLabel = "";
        int vipType;
        long updatedAt;

        Data normalized() {
            if (cookies == null) cookies = new LinkedHashMap<>();
            Map<String, String> clean = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                String name = nonBlank(entry.getKey());
                String value = nonBlank(entry.getValue());
                if (!name.isEmpty() && !value.isEmpty()) clean.put(name, value);
            }
            cookies = clean;
            userId = nonBlank(userId);
            nickname = nonBlank(nickname);
            avatarUrl = nonBlank(avatarUrl);
            vipLabel = nonBlank(vipLabel);
            return this;
        }
    }
}
