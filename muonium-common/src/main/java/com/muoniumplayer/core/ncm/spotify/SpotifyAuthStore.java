package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 本地保存 Spotify 的 OAuth token 与账号展示资料。
 *
 * <p>语义对齐 {@code YouTubeSessionStore} 的两处：导入（这里是从 token 端点的响应）是整体
 * 替换；用户主动登出走 {@link #clear()}。与 YouTube 不同的是<b>失效处理</b>：YouTube 的
 * Cookie 可能只是临时被风控，所以只打标记不清凭据；Spotify 的 refresh token 一旦被
 * {@code invalid_grant} 判死（或 401 重放仍被拒）就是真死了，见 {@link #markReauthRequired}。</p>
 *
 * <p>与酷狗 / 汽水 / B 站同理单开一个文件：{@code music_auth.json} 由 Cadence 的凭据存储
 * 整段重写，只认识网易云与 QQ，写进去的第三方凭据会在它下一次保存时被丢掉。</p>
 */
public final class SpotifyAuthStore {

    /** 刷新判定提前量：声明过期前 30 秒就不再用它发请求，避免路上过期。 */
    private static final long EXPIRY_MARGIN_MILLIS = 30_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static Data data;
    private static boolean loaded;
    /** 静测模式：只活在内存里，绝不碰磁盘（测试进程的「当前目录」不是用户配置目录）。 */
    private static boolean diskless;

    private SpotifyAuthStore() {
    }

    public static final class Session {
        public final boolean loggedIn;
        public final String accessToken;
        public final String refreshToken;
        /** token 声明的过期时刻（epoch 毫秒）；0 表示未知，按已过期处理。 */
        public final long expiresAt;
        public final String scope;
        public final String accountName;
        public final String accountId;
        public final String avatarUrl;
        public final long authorizedAt;
        /** 凭据还在本地，但刷新端点已判定失效——界面据此提示重新授权。 */
        public final boolean reauthRequired;

        Session(Data source) {
            this.accessToken = nonBlank(source.accessToken);
            this.refreshToken = nonBlank(source.refreshToken);
            this.expiresAt = source.expiresAt;
            this.scope = nonBlank(source.scope);
            this.accountName = nonBlank(source.accountName);
            this.accountId = nonBlank(source.accountId);
            this.avatarUrl = nonBlank(source.avatarUrl);
            this.authorizedAt = Math.max(0L, source.authorizedAt);
            this.reauthRequired = source.reauthRequired;
            this.loggedIn = !this.accessToken.isEmpty() || !this.refreshToken.isEmpty();
        }

        /** access token 现在还能不能直接用（不用先刷新）。 */
        public boolean tokenReady() {
            return !accessToken.isEmpty() && expiresAt - EXPIRY_MARGIN_MILLIS > System.currentTimeMillis();
        }

        public boolean canRefresh() {
            return !refreshToken.isEmpty();
        }

        public boolean isReauthRequired() {
            return reauthRequired;
        }

        /**
         * 已授权的 scope 串里是否带着「客户端控制」需要的那两条。
         *
         * <p>播放 scope 是后来加的：在这之前完成授权的用户 token 仍然有效，只是没有控制
         * 播放的权限——这种「登录着但差权限」不算凭据死了，不该走 {@link #markReauthRequired}
         * 清凭据，由设备面板提示重新授权一遍就好。</p>
         */
        public boolean hasPlaybackScopes() {
            return hasGrantedScope("user-read-playback-state")
                    && hasGrantedScope("user-modify-playback-state");
        }

        private boolean hasGrantedScope(String wanted) {
            if (scope.isEmpty()) return false;
            for (String granted : scope.split("\\s+")) {
                if (granted.equals(wanted)) return true;
            }
            return false;
        }
    }

    public static Session snapshot() {
        synchronized (LOCK) {
            ensureLoaded();
            return new Session(data);
        }
    }

    public static boolean isLoggedIn() {
        return snapshot().loggedIn;
    }

    /**
     * 从 token 端点的响应落一份新凭据。
     *
     * <p>Spotify 的刷新响应<b>不一定带新的 refresh_token</b>（轮换是条件性的），没有时就保留
     * 旧的那枚——把它当成已轮换清掉，下次刷新就是 400。账号资料由调用方在拿到 {@code /me}
     * 之后用 {@link #updateProfile} 单独补。</p>
     */
    public static void saveTokenResponse(JsonObject payload) {
        if (payload == null) return;
        synchronized (LOCK) {
            ensureLoaded();
            String access = text(payload, "access_token");
            if (!access.isEmpty()) data.accessToken = access;
            String refresh = text(payload, "refresh_token");
            if (!refresh.isEmpty()) data.refreshToken = refresh;
            long expiresIn = payload.has("expires_in") && payload.get("expires_in").isJsonPrimitive()
                    ? payload.get("expires_in").getAsLong() : 3600L;
            data.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
            String scope = text(payload, "scope");
            if (!scope.isEmpty()) data.scope = scope;
            data.reauthRequired = false;
            data.authorizedAt = System.currentTimeMillis();
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    /** 只更新内存里的 access token（刷新成功但用户配置未变时也走这里落盘）。 */
    static void saveRefreshedToken(String accessToken, long expiresAtMillis, String scope) {
        synchronized (LOCK) {
            ensureLoaded();
            data.accessToken = accessToken == null ? "" : accessToken;
            data.expiresAt = expiresAtMillis;
            if (scope != null && !scope.trim().isEmpty()) data.scope = scope.trim();
            data.reauthRequired = false;
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    /** {@code /me} 拿到的展示资料；失败只影响昵称头像显示，不值得让登录失败。 */
    public static void updateProfile(String accountName, String accountId, String avatarUrl) {
        synchronized (LOCK) {
            ensureLoaded();
            if (nonBlank(accountName).isEmpty() && nonBlank(accountId).isEmpty()
                    && nonBlank(avatarUrl).isEmpty()) return;
            if (!nonBlank(accountName).isEmpty()) data.accountName = accountName.trim();
            if (!nonBlank(accountId).isEmpty()) data.accountId = accountId.trim();
            if (!nonBlank(avatarUrl).isEmpty()) data.avatarUrl = avatarUrl.trim();
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    /**
     * 刷新端点判定凭据已死（或 401 重放后仍被拒）：清掉 token 并标记需重新授权。
     *
     * <p>refresh token 一并清掉是刻意的：走到这里的两条路（刷新回 {@code invalid_grant}、
     * 401 重放仍失败）都说明这枚凭据已经换不出新 token 了，留着只会让下一次操作
     * 再撞一次同样的墙。需要重新授权的状态由 {@link #isReauthRequired()} 表达。</p>
     */
    public static void markReauthRequired() {
        synchronized (LOCK) {
            ensureLoaded();
            data.reauthRequired = true;
            data.accessToken = "";
            data.refreshToken = "";
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    public static boolean isReauthRequired() {
        synchronized (LOCK) {
            ensureLoaded();
            return data.reauthRequired;
        }
    }

    /** 退出登录：清内存与磁盘上的凭据。与 {@link #markReauthRequired} 不同，这是用户主动要删。 */
    public static void clear() {
        synchronized (LOCK) {
            data = new Data();
            loaded = true;
            if (diskless) return;
            try {
                Files.deleteIfExists(ConfigPaths.SPOTIFY_AUTH.toPath());
            } catch (IOException throwable) {
                System.err.println("[Music/Spotify] 凭据文件删除失败: " + throwable);
            }
        }
    }

    // ── 测试钩子：静测不碰磁盘 ──────────────────────────────────────────────

    static void putForTest(String accessToken, String refreshToken, long expiresAt) {
        synchronized (LOCK) {
            data = new Data();
            data.accessToken = accessToken == null ? "" : accessToken;
            data.refreshToken = refreshToken == null ? "" : refreshToken;
            data.expiresAt = expiresAt;
            loaded = true;
            diskless = true;
        }
    }

    static void clearForTest() {
        synchronized (LOCK) {
            data = new Data();
            loaded = true;
            diskless = true;
        }
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────

    private static void ensureLoaded() {
        if (loaded) return;
        data = new Data();
        diskless = false;
        try {
            if (ConfigPaths.SPOTIFY_AUTH.isFile()) {
                String raw = new String(Files.readAllBytes(ConfigPaths.SPOTIFY_AUTH.toPath()),
                        StandardCharsets.UTF_8);
                Data parsed = GSON.fromJson(raw, Data.class);
                if (parsed != null) data = parsed;
            }
        } catch (Throwable throwable) {
            System.err.println("[Music/Spotify] 凭据读取失败，按未登录处理: " + throwable);
        }
        loaded = true;
    }

    private static void persistLocked() {
        if (diskless) return;
        try {
            java.io.File file = ConfigPaths.SPOTIFY_AUTH;
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) return;
            Files.write(file.toPath(), GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        } catch (IOException throwable) {
            System.err.println("[Music/Spotify] 凭据写入失败: " + throwable);
        }
    }

    private static String text(JsonObject object, String member) {
        try {
            if (object == null || !object.has(member) || object.get(member).isJsonNull()) return "";
            return object.get(member).getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Data {
        String accessToken = "";
        String refreshToken = "";
        long expiresAt;
        String scope = "";
        String accountName = "";
        String accountId = "";
        String avatarUrl = "";
        long authorizedAt;
        long updatedAt;
        boolean reauthRequired;
    }
}
