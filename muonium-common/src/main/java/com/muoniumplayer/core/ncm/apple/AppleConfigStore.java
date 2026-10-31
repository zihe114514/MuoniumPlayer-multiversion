package com.muoniumplayer.core.ncm.apple;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Apple Music 的本机配置：media-user-token、storefront 与本音源的偏好开关。
 *
 * <p><b>凭据模型与 Spotify 不同</b>：Spotify 是用户自建 Client ID 走 OAuth；Apple 没有
 * 面向个人的 OAuth，目录能力（搜索/歌单/歌词）靠从 music.apple.com 网页端抓的公开
 * bearer token（{@link AppleTokenManager}，无需登录），账号能力（本人资料库歌单）靠
 * 用户从浏览器导出的 cookies 里的 {@code media-user-token}。所以这里存的是「token +
 * 偏好」，整份 cookies.txt 由 {@link AppleCookieSupport} 单独写在
 * {@link ConfigPaths#APPLE_MUSIC_DIR} 下（引擎按文件路径读取）。</p>
 */
public final class AppleConfigStore {

    public static final String DEFAULT_STOREFRONT = "cn";

    /** AMP API 常用的 storefront 白名单形状：两位小写国家码。 */
    private static final String STOREFRONT_SHAPE = "[a-z]{2}";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static Data data;
    private static boolean loaded;

    private AppleConfigStore() {
    }

    public static final class Config {
        public final String mediaUserToken;
        public final String storefront;
        /** 30 秒 preview 兜底开关（默认开：本机引擎与换源都失败时至少能听 30 秒）。 */
        public final boolean previewFallback;
        /** 可选代理 host:port，CN 直连 amp-api 不稳时用；空串 = 不走代理。 */
        public final String proxy;
        /** 纯 Java 播放引擎总开关（配置文件可关；UI 不暴露）。 */
        public final boolean nativeEngineEnabled;
        /** 外置 .wvd 设备文件路径；空串 = 用内置设备。 */
        public final String wvdDevicePath;

        Config(Data source) {
            this.mediaUserToken = nonBlank(source.mediaUserToken);
            String storefront = nonBlank(source.storefront).trim().toLowerCase(Locale.ROOT);
            this.storefront = storefront.isEmpty() ? DEFAULT_STOREFRONT : storefront;
            this.previewFallback = source.previewFallback;
            this.proxy = nonBlank(source.proxy);
            this.nativeEngineEnabled = source.nativeEngineEnabled;
            this.wvdDevicePath = nonBlank(source.wvdDevicePath);
        }

        /** media-user-token 在手 = 账号能力可用（资料库歌单、逐词歌词）。 */
        public boolean loggedIn() {
            return !mediaUserToken.isEmpty();
        }

        public boolean storefrontLooksValid() {
            return storefront.matches(STOREFRONT_SHAPE);
        }
    }

    public static Config snapshot() {
        synchronized (LOCK) {
            ensureLoaded();
            return new Config(data);
        }
    }

    public static String mediaUserToken() {
        return snapshot().mediaUserToken;
    }

    public static String storefront() {
        return snapshot().storefront;
    }

    /** media-user-token 由 cookies 导入流程写入（{@link AppleCookieSupport#importCookies}）。 */
    static void saveMediaUserToken(String token) {
        synchronized (LOCK) {
            ensureLoaded();
            data.mediaUserToken = token == null ? "" : token.trim();
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    /** cookies 被清除时一并清掉账号态；storefront 与偏好是用户设置，保留。 */
    static void clearAccount() {
        synchronized (LOCK) {
            ensureLoaded();
            data.mediaUserToken = "";
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    public static void saveStorefront(String rawInput) {
        String storefront = nonBlank(rawInput).trim().toLowerCase(Locale.ROOT);
        synchronized (LOCK) {
            ensureLoaded();
            data.storefront = storefront;
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    public static void savePreviewFallback(boolean enabled) {
        synchronized (LOCK) {
            ensureLoaded();
            data.previewFallback = enabled;
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    public static void saveProxy(String rawInput) {
        synchronized (LOCK) {
            ensureLoaded();
            data.proxy = nonBlank(rawInput);
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    public static void saveNativeEngineEnabled(boolean enabled) {
        synchronized (LOCK) {
            ensureLoaded();
            data.nativeEngineEnabled = enabled;
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    public static void saveWvdDevicePath(String rawInput) {
        synchronized (LOCK) {
            ensureLoaded();
            data.wvdDevicePath = nonBlank(rawInput);
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    // ── 测试钩子：静测不许碰磁盘，用内存替身撑起同一套语义 ────────────────────

    static void putForTest(String mediaUserToken, String storefront,
                           boolean nativeEngineEnabled, boolean previewFallback) {
        synchronized (LOCK) {
            data = new Data();
            data.mediaUserToken = mediaUserToken == null ? "" : mediaUserToken;
            data.storefront = storefront == null ? "" : storefront;
            data.nativeEngineEnabled = nativeEngineEnabled;
            data.previewFallback = previewFallback;
            loaded = true;
        }
    }

    static void clearForTest() {
        synchronized (LOCK) {
            data = new Data();
            loaded = true;
        }
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────

    private static void ensureLoaded() {
        if (loaded) return;
        data = new Data();
        try {
            if (ConfigPaths.APPLE_MUSIC_AUTH.isFile()) {
                String raw = new String(Files.readAllBytes(ConfigPaths.APPLE_MUSIC_AUTH.toPath()),
                        StandardCharsets.UTF_8);
                Data parsed = GSON.fromJson(raw, Data.class);
                if (parsed != null) data = parsed;
            }
        } catch (Throwable throwable) {
            // 配置读失败等价于没配置过：登录页会让用户重新粘一次，不该把游戏开崩。
            System.err.println("[Music/Apple] 配置读取失败，按未配置处理: " + throwable);
        }
        loaded = true;
    }

    private static void persistLocked() {
        try {
            java.io.File file = ConfigPaths.APPLE_MUSIC_AUTH;
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) return;
            Files.write(file.toPath(), GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        } catch (IOException throwable) {
            System.err.println("[Music/Apple] 配置写入失败: " + throwable);
        }
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Data {
        String mediaUserToken = "";
        String storefront = DEFAULT_STOREFRONT;
        boolean previewFallback = true;
        String proxy = "";
        /** 纯 Java 播放引擎总开关。 */
        boolean nativeEngineEnabled = true;
        /** 外置 .wvd 设备文件路径；空串 = 用内置设备。 */
        String wvdDevicePath = "";
        long updatedAt;
    }
}
