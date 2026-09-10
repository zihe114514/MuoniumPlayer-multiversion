package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 本地保存汽水 Passport Cookie 与账号展示资料，不记录二维码 token 或轮询状态。 */
final class QishuiSessionStore {

    static final class Session {
        final boolean loggedIn;
        final String cookieHeader;
        final String userId;
        final String nickname;
        final String douyinId;
        final String avatarUrl;
        final boolean vip;
        final String vipStage;

        private Session(Data data) {
            Map<String, String> cookies = data.cookies == null ? Collections.emptyMap() : data.cookies;
            this.loggedIn = containsSessionCookie(cookies);
            this.cookieHeader = joinCookies(cookies);
            this.userId = nonBlank(data.userId);
            this.nickname = nonBlank(data.nickname);
            this.douyinId = nonBlank(data.douyinId);
            this.avatarUrl = nonBlank(data.avatarUrl);
            this.vip = data.vip;
            this.vipStage = nonBlank(data.vipStage);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static Data data;
    private static boolean loaded;

    private QishuiSessionStore() {
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

    static String getCookieHeader() {
        return snapshot().cookieHeader;
    }

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

    static void saveProfile(String userId, String nickname, String douyinId,
                            String avatarUrl, boolean vip, String vipStage) {
        synchronized (LOCK) {
            ensureLoaded();
            if (!isBlank(userId)) data.userId = userId.trim();
            if (!isBlank(nickname)) data.nickname = nickname.trim();
            if (!isBlank(douyinId)) data.douyinId = douyinId.trim();
            if (!isBlank(avatarUrl)) data.avatarUrl = avatarUrl.trim();
            data.vip = vip;
            if (!isBlank(vipStage)) data.vipStage = vipStage.trim();
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    static void clear() {
        synchronized (LOCK) {
            data = new Data();
            loaded = true;
            try {
                Files.deleteIfExists(ConfigPaths.QISHUI_AUTH.toPath());
            } catch (IOException ignored) {
                // 退出登录以内存状态为准；文件删不掉时下次 save 也会覆盖它。
            }
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = ConfigPaths.QISHUI_AUTH.toPath();
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
        Path path = ConfigPaths.QISHUI_AUTH.toPath();
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
            System.err.println("[Music/Qishui] Failed to persist session: " + error.getMessage());
        }
    }

    private static boolean containsSessionCookie(Map<String, String> cookies) {
        return !nonBlank(cookies.get("sessionid")).isEmpty()
                || !nonBlank(cookies.get("sessionid_ss")).isEmpty()
                || !nonBlank(cookies.get("sid_tt")).isEmpty()
                || !nonBlank(cookies.get("sid_guard")).isEmpty();
    }

    private static String joinCookies(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return "";
        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(cookies).entrySet()) {
            String name = nonBlank(entry.getKey());
            String value = nonBlank(entry.getValue());
            if (name.isEmpty() || value.isEmpty() || !validCookiePart(name, value)) continue;
            if (header.length() > 0) header.append("; ");
            header.append(name).append('=').append(value);
        }
        if (header.length() == 0) return "";
        return header.append(';').toString();
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
        String douyinId = "";
        String avatarUrl = "";
        boolean vip;
        String vipStage = "";
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
            douyinId = nonBlank(douyinId);
            avatarUrl = nonBlank(avatarUrl);
            vipStage = nonBlank(vipStage);
            return this;
        }
    }
}