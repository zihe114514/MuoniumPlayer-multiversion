package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 本地保存 YouTube 的浏览器 Cookie 与账号展示资料。
 *
 * <p><b>只做 Cookie 这一种登录</b>。另一条常见的路是 OAuth 设备码，但参考实现
 * （全仓 grep {@code googleusercontent} / {@code GOCSPX} 零命中），
 * {@code setup_oauth(client_id, client_secret, …)} 是必填参数，
 * 文档 {@code docs/source/setup/oauth.rst} 明写要先有自己的 Google Cloud 项目。
 * 实测端点：不给 client 是 400 invalid_request，给错 client 是 401 invalid_client
 * —— 流程本身通，缺的是凭据。对一个闭源分发的 mod 来说，内置一对公开 client
 * 等于把「整体被吊销」的风险转嫁给所有用户，而让用户自建 GCP 又不现实，所以不做。</p>
 *
 * <p>Cookie 路线要的东西很少：从导出的 Cookie 里取 {@value #REQUIRED_COOKIE}，
 * 按 {@code sha1(<时间戳> + " " + <sapisid> + " " + <origin>)} 算出
 * {@code SAPISIDHASH <时间戳>_<摘要>} 当 authorization 头，并给请求串补上 API key。
 *
 * <p>与汽水那份 {@link QishuiSessionStore} 保持同样的语义：导入是整体替换而不是按名合并
 * （合并会拼出一个两个账号字段混装的畸形会话）；上游判定失效时<b>只打标记不清凭据</b>，
 * 因为那 Cookie 也许只是临时被风控，删了用户就得重导一次。</p>
 */
public final class YouTubeSessionStore {

    /** Google 用来算 SAPISIDHASH 的那枚 Cookie。缺它就不是有效的登录态。 */
    static final String REQUIRED_COOKIE = "__Secure-3PAPISID";

    /** 参与 SAPISIDHASH 的 origin，必须与请求里的 Origin 头逐字一致。 */
    static final String ORIGIN = "https://music.youtube.com";

    static final String INNERTUBE_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";

    public static final class Session {
        public final boolean loggedIn;
        public final String cookieHeader;
        public final String sapisid;
        public final String accountName;
        public final String channelHandle;
        public final String avatarUrl;
        /** 上一次成功打过账号接口的时刻；0 表示从没验证过。 */
        public final long verifiedAt;
        /** 凭据还在本地，但上游已判定过期——界面据此提示重导，而不是静默当未登录。 */
        public final boolean reauthRequired;

        private Session(Data source) {
            Map<String, String> cookies = source.cookies == null
                    ? Collections.<String, String>emptyMap() : source.cookies;
            this.sapisid = nonBlank(cookies.get(REQUIRED_COOKIE));
            this.loggedIn = !this.sapisid.isEmpty();
            this.cookieHeader = joinCookies(cookies);
            this.accountName = nonBlank(source.accountName);
            this.channelHandle = nonBlank(source.channelHandle);
            this.avatarUrl = nonBlank(source.avatarUrl);
            this.verifiedAt = Math.max(0L, source.verifiedAt);
            this.reauthRequired = source.reauthRequired;
        }
    }

    /** 粘贴板里常见的零宽 BOM。写成常量而不是字面量，是为了别让源码里出现一个看不见的字符。 */
    private static final String BOM = String.valueOf((char) 0xFEFF);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static Data data;
    private static boolean loaded;

    private YouTubeSessionStore() {
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

    /** 拼好的 Cookie 头；未登录时返回空串，调用方据此决定要不要带认证头。 */
    public static String cookieHeader() {
        Session session = snapshot();
        return session.loggedIn ? session.cookieHeader : "";
    }

    /**
     * Android 系客户端要用这个 origin 参与摘要。
     *
     * <p>实测出来的：{@code ANDROID_MUSIC} 配 {@code SAPISIDHASH}（按 music 站算的那份）
     * 两跳全回 {@code 400 Request contains an invalid argument}，把摘要里的 origin 换成
     * {@code https://www.youtube.com} 立刻 200。摘要值本身没错，错在「Android 客户端的
     * 认证是按 www 站签的」这件事上 —— 请求仍然发给 music.youtube.com，只有摘要的第三段变。</p>
     */
    public static final String MOBILE_ORIGIN = "https://www.youtube.com";

    /**
     * 算 authorization 头：{@code SAPISIDHASH <unix 秒>_<sha1(时间戳 空格 sapisid 空格 origin)>}。
     *
     * @return 未登录或算法不可用时返回空串
     */
    public static String authorization() {
        return authorization(ORIGIN);
    }

    /** 按客户端选 origin：网页音乐端用 music 站，Android 系用 {@link #MOBILE_ORIGIN}。 */
    public static String authorization(String origin) {
        Session session = snapshot();
        if (!session.loggedIn) return "";
        return sapisidHash(session.sapisid, origin, System.currentTimeMillis() / 1000L);
    }

    /** 纯函数形式，供静态回归测试直接断言（不碰磁盘也不碰系统时间）。 */
    static String sapisidHash(String sapisid, String origin, long unixSeconds) {
        if (isBlank(sapisid) || isBlank(origin)) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(
                    (unixSeconds + " " + sapisid + " " + origin).getBytes(StandardCharsets.UTF_8));
            return "SAPISIDHASH " + unixSeconds + "_" + toHex(hash);
        } catch (Exception unavailable) {
            System.err.println("[Music/YouTube] SAPISIDHASH 计算失败: " + unavailable);
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /**
     * 用一段用户粘贴的 Cookie 登录。
     *
     * <p>容错程度对齐汽水那份：接受 {@code Cookie: } 前缀、{@code document.cookie = "..."}、
     * DevTools/插件导出的 {@code [{"name":..,"value":..}]} 数组，以及裸的 {@code a=1; b=2}。
     * 只有解析出 {@value #REQUIRED_COOKIE} 才算成功——否则等于没登录，
     * 存一份残缺 Cookie 只会让后面的每个请求都拿到看不懂的降级响应。</p>
     *
     * @return 成功写入返回 true
     */
    public static boolean importCookie(String rawInput) {
        Map<String, String> cookies = parseCookieInput(rawInput);
        // 残缺 Cookie 一律拒收：少了必需那枚就等于没登录，存下来只会让后面每个请求
        // 拿到看不懂的降级响应。
        if (nonBlank(cookies.get(REQUIRED_COOKIE)).isEmpty()) return false;
        synchronized (LOCK) {
            ensureLoaded();
            data.cookies = cookies;
            // 换账号了，上一个账号的展示资料必须一起清掉，否则界面会显示错人的昵称头像。
            data.accountName = "";
            data.channelHandle = "";
            data.avatarUrl = "";
            data.updatedAt = System.currentTimeMillis();
            data.verifiedAt = 0L;
            data.reauthRequired = false;
            persistLocked();
        }
        return true;
    }

    /** 把用户粘贴的任意形态 Cookie 解析成键值表。解析不出任何一项时返回空表。 */
    public static Map<String, String> parseCookieInput(String rawInput) {
        Map<String, String> out = new LinkedHashMap<>();
        String text = nonBlank(rawInput).replace(BOM, "");
        if (text.isEmpty()) return out;

        int colon = text.indexOf(':');
        if (text.regionMatches(true, 0, "Cookie", 0, 6) && colon > 0 && colon < 12) {
            text = text.substring(colon + 1);
        }
        if (text.toLowerCase(Locale.ROOT).startsWith("document.cookie")) {
            int assign = text.indexOf('=');
            if (assign > 0) text = text.substring(assign + 1);
        }
        text = text.trim();
        // 控制台里贴过来的常常是整条赋值语句：document.cookie = "NID=q; SID=…; …"
        // 上面已经把左半边剥掉了，剩下的两端引号要先去掉，否则最后一枚 Cookie 的尾巴上
        // 会挂着一个引号，算出来的 SAPISIDHASH 永远不对。
        text = stripWrappingQuotes(text);

        // DevTools / 插件导出的数组形态：[{"name":"__Secure-3PAPISID","value":"..."}]
        if (text.startsWith("[")) {
            try {
                JsonElement parsed = JsonParser.parseString(text);
                if (parsed.isJsonArray()) {
                    for (JsonElement element : parsed.getAsJsonArray()) {
                        if (element == null || !element.isJsonObject()) continue;
                        JsonObject item = element.getAsJsonObject();
                        putCookie(out, readMember(item, "name"), readMember(item, "value"));
                    }
                }
            } catch (Throwable ignored) {
                return out;
            }
            return out;
        }

        // 对象形态：{"name":"value", ...}
        if (text.startsWith("{")) {
            try {
                JsonElement parsed = JsonParser.parseString(text);
                if (parsed.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                        putCookie(out, entry.getKey(), readText(entry.getValue()));
                    }
                }
            } catch (Throwable ignored) {
                return out;
            }
            return out;
        }

        for (String pair : text.split(";")) {
            int assign = pair.indexOf('=');
            if (assign <= 0) continue;
            putCookie(out, pair.substring(0, assign), pair.substring(assign + 1));
        }
        return out;
    }

    private static String readMember(JsonObject object, String property) {
        return object != null && object.has(property) ? readText(object.get(property)) : "";
    }

    private static String readText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        try {
            return value.getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 键值两端去空白、值去包裹引号；名字不合法的整条丢掉。 */
    private static void putCookie(Map<String, String> target, String rawName, String rawValue) {
        String name = nonBlank(rawName);
        if (name.isEmpty()) return;
        String value = stripWrappingQuotes(nonBlank(rawValue));
        if (value.isEmpty()) return;
        target.put(name, value);
    }

    /** 去掉成对包裹的首尾引号。只有一侧有引号时不去，避免把值里合法的引号吃掉。 */
    private static String stripWrappingQuotes(String value) {
        String text = value == null ? "" : value.trim();
        while (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                text = text.substring(1, text.length() - 1).trim();
                continue;
            }
            break;
        }
        return text;
    }

    private static String joinCookies(Map<String, String> cookies) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    /** 账号接口确认成功时记录时刻，并撤销之前的过期标记。 */
    public static void markVerified(String accountName, String channelHandle, String avatarUrl) {
        synchronized (LOCK) {
            ensureLoaded();
            if (!snapshotLocked().loggedIn) return;
            if (!isBlank(accountName)) data.accountName = accountName.trim();
            if (!isBlank(channelHandle)) data.channelHandle = channelHandle.trim();
            if (!isBlank(avatarUrl)) data.avatarUrl = avatarUrl.trim();
            data.verifiedAt = System.currentTimeMillis();
            data.reauthRequired = false;
            data.updatedAt = System.currentTimeMillis();
            persistLocked();
        }
    }

    /** 上游判定凭据失效：只打标记，绝不清空凭据。 */
    public static void markReauthRequired() {
        synchronized (LOCK) {
            ensureLoaded();
            if (!data.reauthRequired) {
                data.reauthRequired = true;
                persistLocked();
            }
        }
    }

    public static boolean isReauthRequired() {
        synchronized (LOCK) {
            ensureLoaded();
            return data.reauthRequired;
        }
    }

    /** 退出登录：清内存与磁盘上的 Cookie。与「上游判定过期」不同，这是用户主动要删。 */
    public static void clear() {
        synchronized (LOCK) {
            data = new Data();
            loaded = true;
            try {
                Files.deleteIfExists(ConfigPaths.YOUTUBE_AUTH.toPath());
            } catch (IOException ignored) {
                // 退出登录以内存状态为准；文件删不掉时下次保存也会覆盖它。
            }
        }
    }

    /** 给界面用的一行状态。 */
    public static String describe() {
        Session session = snapshot();
        if (!session.loggedIn) return "未登录（匿名）";
        String who = session.accountName.isEmpty() ? "已导入 Cookie" : session.accountName;
        if (session.reauthRequired) return who + " · Cookie 已失效，请重新导入";
        if (session.verifiedAt <= 0L) return who + " · 尚未验证";
        return who;
    }

    private static Session snapshotLocked() {
        return new Session(data);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        data = readFromDisk();
        loaded = true;
    }

    private static Data readFromDisk() {
        try {
            Path path = ConfigPaths.YOUTUBE_AUTH.toPath();
            if (!Files.exists(path)) return new Data();
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Data parsed = GSON.fromJson(json, Data.class);
            if (parsed == null) return new Data();
            if (parsed.cookies == null) parsed.cookies = new LinkedHashMap<>();
            return parsed;
        } catch (Throwable failure) {
            System.err.println("[Music/YouTube] Cookie 读取失败: " + failure);
            return new Data();
        }
    }

    private static void persistLocked() {
        try {
            Path path = ConfigPaths.YOUTUBE_AUTH.toPath();
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            Files.write(temp, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomic) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable failure) {
            System.err.println("[Music/YouTube] Cookie 保存失败: " + failure);
        }
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Gson 落盘的形状。字段名进了文件格式，不要再改名。 */
    private static final class Data {
        Map<String, String> cookies = new LinkedHashMap<>();
        String accountName = "";
        String channelHandle = "";
        String avatarUrl = "";
        long verifiedAt;
        boolean reauthRequired;
        long updatedAt;
    }
}
