package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Spotify 的本机配置：用户自备的 Client ID 与市场代码。
 *
 * <p><b>为什么要用户自己给 Client ID</b>：Spotify 从 2025-05 起只接受机构申请扩展配额，
 * 开发模式一个应用最多 5 个白名单用户 —— 一个面向公众分发的 mod 内置任何 Client ID 都会在
 * 允许个人项目，前提是应用所有者有 Premium），把 Client ID 粘贴进来。于是这份配置是「每个
 * 用户只属于自己」的东西，不进仓库、不随 mod 分发。</p>
 *
 * <p>回调地址固定成本机回环上的一个写死端口：OAuth 的 redirect_uri 必须与开发者后台里登记的
 * 逐字一致，用户只登记一次，所以这里绝不能换成「随机空闲端口」这种对实现友好的形态。
 */
public final class SpotifyConfigStore {

    public static final String ACCOUNTS_BASE = "https://accounts.spotify.com";
    public static final String AUTHORIZE_PATH = "/authorize";
    public static final String TOKEN_PATH = "/api/token";
    public static final String API_BASE = "https://api.spotify.com/v1";

    /** 开发者后台必须登记成一模一样的回调地址；端口见类注释。 */
    public static final String REDIRECT_URI = "http://127.0.0.1:47478/callback";

    /**
     * 授权要申请的 scopes。
     *
     * <p>全部是「读」：搜与播是匿名的元数据能力，登录换来的只有本人曲库
     * （歌单、喜欢的歌、账号展示资料）。写回操作本轮不做，所以不申请
     * {@code playlist-modify-*} / {@code user-library-modify} —— scope 越少，
     * 用户在授权页上看到的警告越小。</p>
     */
    public static final String[] SCOPES = {
            "user-read-private",
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-library-read",
            "user-top-read",
    };

    public static final String DEFAULT_MARKET = "US";

    /** Spotify Client ID 是一串 32 位左右的 base62。长度放宽到 20 以上，只拦手滑截断。 */
    private static final Pattern CLIENT_ID_SHAPE = Pattern.compile("[0-9a-zA-Z]{20,}");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static Data data;
    private static boolean loaded;

    private SpotifyConfigStore() {
    }

    public static final class Config {
        public final String clientId;
        public final String market;

        Config(Data source) {
            this.clientId = nonBlank(source.clientId);
            String market = nonBlank(source.market).trim().toUpperCase(Locale.ROOT);
            this.market = market.isEmpty() ? DEFAULT_MARKET : market;
        }

        /** Client ID 形状对不对。缺配置与形状不对都算「未配置」，UI 据此给出不同的提示。 */
        public boolean clientIdLooksValid() {
            return CLIENT_ID_SHAPE.matcher(clientId).matches();
        }

        public boolean configured() {
            return clientIdLooksValid();
        }
    }

    public static Config snapshot() {
        synchronized (LOCK) {
            ensureLoaded();
            return new Config(data);
        }
    }

    public static String clientId() {
        return snapshot().clientId;
    }

    /** 拼好的 scope 串（授权 URL 与 token 交换共用同一份）。 */
    public static String scope() {
        return String.join(" ", SCOPES);
    }

    /**
     * 保存用户粘贴的 Client ID。形状不对返回 false —— 存一个必然被授权页拒绝的值，
     * 只会让用户在浏览器里看到一次看不懂的 {@code INVALID_CLIENT}。
     */
    public static boolean saveClientId(String rawInput) {
        String clientId = nonBlank(rawInput).trim();
        if (!CLIENT_ID_SHAPE.matcher(clientId).matches()) return false;
        synchronized (LOCK) {
            ensureLoaded();
            data.clientId = clientId;
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
        return true;
    }

    /** 市场代码只影响搜索的地域可见性（某些发行区域限定曲目）。空串回默认值。 */
    public static void saveMarket(String rawInput) {
        String market = nonBlank(rawInput).trim().toUpperCase(Locale.ROOT);
        synchronized (LOCK) {
            ensureLoaded();
            data.market = market;
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    // ── 测试钩子：静测不许碰磁盘，用内存替身撑起同一套语义 ────────────────────

    static void putForTest(String clientId, String market) {
        synchronized (LOCK) {
            data = new Data();
            data.clientId = clientId == null ? "" : clientId;
            data.market = market == null ? "" : market;
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
            if (ConfigPaths.SPOTIFY_CONFIG.isFile()) {
                String raw = new String(Files.readAllBytes(ConfigPaths.SPOTIFY_CONFIG.toPath()),
                        StandardCharsets.UTF_8);
                Data parsed = GSON.fromJson(raw, Data.class);
                if (parsed != null) data = parsed;
            }
        } catch (Throwable throwable) {
            // 配置读失败等价于没配置过：登录页会让用户重新粘一次，不该把游戏开崩。
            System.err.println("[Music/Spotify] 配置读取失败，按未配置处理: " + throwable);
        }
        loaded = true;
    }

    private static void persistLocked() {
        try {
            java.io.File file = ConfigPaths.SPOTIFY_CONFIG;
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) return;
            Files.write(file.toPath(), GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        } catch (IOException throwable) {
            System.err.println("[Music/Spotify] 配置写入失败: " + throwable);
        }
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Data {
        String clientId = "";
        String market = DEFAULT_MARKET;
        long updatedAt;
    }
}
