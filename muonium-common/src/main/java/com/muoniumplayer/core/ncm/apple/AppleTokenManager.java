package com.muoniumplayer.core.ncm.apple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AMP API 公开 bearer token 的自动获取。
 *
 * <p><b>原理</b>：music.apple.com 网页播放器把一枚公开 JWT（{@code eyJh…} 开头的
 * JWS）打进它的前端 bundle。抓首页找到 bundle 地址，再从 bundle 里正则提出 token，
 * 就能以「网页播放器」的身份访问 amp-api 的<b>公开目录</b>（搜索/歌单/榜单/歌词预览）。
 * 这是 Apple 面向所有访客的能力，不需要任何登录。</p>
 *
 * <p><b>生命周期</b>：这枚 token 有效期以周计，但 Apple 换前端bundle 时旧 token 可能
 * 随时失效。所以内存缓存 + 磁盘缓存 + 失效即重抓（401 由 {@link AppleApiClient} 打回
 * {@link #invalidate()}），抓取用单飞防止并发窗口内 N 个线程同时打两跳网络。</p>
 */
public final class AppleTokenManager {

    private static final String HOMEPAGE = "https://music.apple.com";
    /**
     * 首页里前端 bundle 的引用。2026-10 起是 {@code /assets/index~<hash>.js}（老形态
     * {@code index-legacy~} 已弃用但兼容匹配）；正则按公开前端的
     * {@code assets/index[~-]} 对齐，两代形态都认。
     */
    private static final Pattern BUNDLE_PATTERN =
            Pattern.compile("/(assets/index[~\\-][^/\"]+\\.js)");
    /**
     * bundle 里的三段 JWT。2026-10 的 bundle 里有三枚（kid 各异），只有 iss=AMPWebPlay
     * 那枚能打 amp-api；JWT 的 header 是 {@code {"typ":"JWT",...}}，base64 后以
     * {@code eyJ0eXAi} 开头 —— 老教程里「以 eyJh 开头」的规则已失效（那是
     * {@code {"alg"} 开头时代的产物），不能再用前缀过滤。
     */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[\"'](eyJ[A-Za-z0-9_\\-]+\\.eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+)[\"']");
    /** JWT 三段形状（磁盘缓存与测试钩子的合法性校验用）。 */
    private static final Pattern TOKEN_SHAPE =
            Pattern.compile("eyJ[A-Za-z0-9_\\-]+\\.eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
    /** payload.iss 的 base64 片段：只有 AMPWebPlay 这枚是网页播放器目录 token。 */
    private static final String AMP_WEB_PLAY_ISS =
            base64UrlEncode("{\"iss\":\"AMPWebPlay\"").substring(0, 16);

    private static final long MEMORY_TTL_MILLIS = 30L * 60L * 1000L;
    private static final long DISK_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    private static final Object FETCH_LOCK = new Object();
    private static final AtomicReference<String> memoryToken = new AtomicReference<>("");

    private AppleTokenManager() {
    }

    /** 取一枚可用的公开 token；三跳（内存→磁盘→网络）都失败时抛 IOException。 */
    public static String current() throws IOException {
        String cached = memoryToken.get();
        if (!cached.isEmpty() && fresh(cached, MEMORY_TTL_MILLIS)) return cached;
        synchronized (FETCH_LOCK) {
            cached = memoryToken.get();
            if (!cached.isEmpty() && fresh(cached, MEMORY_TTL_MILLIS)) return cached;
            String disk = readDisk();
            if (!disk.isEmpty()) {
                memoryToken.set(disk);
                return disk;
            }
            String fetched = fetch();
            memoryToken.set(fetched);
            writeDisk(fetched);
            return fetched;
        }
    }

    /** 401 时由 API 客户端打回：只作废内存与磁盘缓存，下次调用重新抓。 */
    public static void invalidate() {
        memoryToken.set("");
        try {
            Files.deleteIfExists(diskFile().toPath());
        } catch (IOException ignored) {
        }
    }

    /** 测试钩子：静测不碰网络，直接注入一枚形状合法的假 token。 */
    static void putForTest(String token, long ageMillis) {
        memoryToken.set(token == null ? "" : token);
        lastFetchMillis = System.currentTimeMillis() - ageMillis;
    }

    static void clearForTest() {
        memoryToken.set("");
        lastFetchMillis = 0L;
    }

    // ── 抓取（两跳网络） ─────────────────────────────────────────────────────

    private static String fetch() throws IOException {
        // 第一跳：首页。拿到 bundle 引用。
        String homepage = Http.get(HOMEPAGE);
        String bundle = firstGroup(BUNDLE_PATTERN.matcher(homepage));
        if (bundle == null) {
            throw new IOException("apple 首页未找到 bundle 引用（页面结构可能变了）");
        }
        // 第二跳：bundle。提出所有三段 JWT，取 iss=AMPWebPlay 那枚。
        String bundleBody = Http.get(HOMEPAGE + "/" + bundle);
        String token = ampWebPlayToken(bundleBody);
        if (token == null) {
            throw new IOException("apple bundle 未找到 AMPWebPlay token（页面结构可能变了）");
        }
        return token;
    }

    /**
     * 从 bundle 文本里挑出网页播放器目录 token。
     *
     * <p>现在 bundle 里有三枚同构 JWT（Apple 自家的几个 kid），payload 的 iss 各不相同；
     * 不解码 JSON，只对 payload 段做 base64url 前缀匹配（{@link #AMP_WEB_PLAY_ISS}）——
     * {@code {"iss":"AMPWebPlay"} 的 base64url 编码在每枚 token 里都是同一个字面前缀，
     * 纯字符串比对，不引 JSON 解析器。</p>
     */
    static String ampWebPlayToken(String bundleBody) {
        if (bundleBody == null) return null;
        java.util.regex.Matcher matcher = TOKEN_PATTERN.matcher(bundleBody);
        String fallback = null;
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!TOKEN_SHAPE.matcher(candidate).matches()) continue;
            String[] parts = candidate.split("\\.");
            if (parts.length != 3) continue;
            if (parts[1].startsWith(AMP_WEB_PLAY_ISS)) return candidate;
            fallback = candidate; // 形状对的先记着：iss 前缀改版时至少还能试一发。
        }
        return fallback;
    }

    private static String base64UrlEncode(String text) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String firstGroup(Matcher matcher) {
        return matcher.find() ? matcher.group(1) : null;
    }

    // ── 磁盘缓存 ─────────────────────────────────────────────────────────────

    private static volatile long lastFetchMillis;

    private static boolean fresh(String token, long ttl) {
        return lastFetchMillis > 0L && System.currentTimeMillis() - lastFetchMillis < ttl;
    }

    private static java.io.File diskFile() {
        return new java.io.File(com.muoniumplayer.core.settings.ConfigPaths.APPLE_MUSIC_DIR, "web_token.txt");
    }

    private static String readDisk() {
        try {
            java.io.File file = diskFile();
            if (!file.isFile()) return "";
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(file.toPath()).toMillis();
            if (age < 0 || age > DISK_TTL_MILLIS) return "";
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return TOKEN_SHAPE.matcher(raw).matches() ? raw : "";        } catch (Throwable throwable) {
            return "";
        }
    }

    private static void writeDisk(String token) {
        try {
            java.io.File file = diskFile();
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) return;
            Files.write(file.toPath(), token.getBytes(StandardCharsets.UTF_8));
        } catch (IOException throwable) {
            System.err.println("[Music/Apple] token 缓存写入失败: " + throwable);
        }
    }

    /** 极小的 HTTP GET 工具：只服务本类与 AppleApiClient 的共享头与代理逻辑。 */
    static final class Http {
        private Http() {
        }

        static String get(String url) throws IOException {
            return get(url, null, 15_000);
        }

        static String get(String url, Map<String, String> headers, int timeoutMillis) throws IOException {
            Map<String, String> all = headers == null ? new LinkedHashMap<>() : headers;
            all.putIfAbsent("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36");
            java.net.Proxy proxy = AppleProxySupport.currentProxyOrNull();
            java.net.HttpURLConnection connection;
            if (proxy != null) {
                connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection(proxy);
            } else {
                connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            }
            connection.setConnectTimeout(Math.min(10_000, timeoutMillis));
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            for (Map.Entry<String, String> entry : all.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            int code = connection.getResponseCode();
            java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readStream(stream);
            connection.disconnect();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " from " + hostOf(url));
            }
            return body;
        }

        private static String hostOf(String url) {
            try {
                return new java.net.URL(url).getHost();
            } catch (IOException ignored) {
                return url;
            }
        }

        private static String readStream(java.io.InputStream raw) throws IOException {
            if (raw == null) return "";
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(raw, StandardCharsets.UTF_8))) {
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    out.append(buffer, 0, read);
                }
            } finally {
                try {
                    raw.close();
                } catch (IOException ignored) {
                }
            }
            return out.toString();
        }
    }
}
