package yun.shan.mpsync.resolve;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * 服务端用服主的网易云 Cookie 解析一条取流直链。
 *
 * <p><b>为什么服务端也要有这条路。</b>共享直链模型的固有边界是「房间里必须恰好有个会员在线」。
 * 服主配了自己的 {@code MUSIC_U} 之后，服务端可以自己签出完整音频的地址，非会员玩家点会员曲
 * 也能听 —— 而且不需要任何玩家把账号交出来。
 *
 * <p><b>只做一件事</b>：{@code song/enhance/player/url/v1}。这是 weapi 里最稳定的一个接口，
 * 而且返回里同时带 {@code time}（这条链接实际能取到多长的音频），所以「是不是试听片段」不用
 * 额外请求就能判断 —— 上层照旧用它拒收试听片段。
 *
 * <p>加密照抄客户端那份（双层 AES-CBC + RSA），两边必须完全一致，否则接口返回 {@code 250}。
 */
public final class NeteaseTokenApi {

    private NeteaseTokenApi() {}

    private static final String BASE = "https://music.163.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Safari/537.36";

    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final String RSA_PUB = "010001";
    private static final String RSA_MOD =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725"
          + "152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312"
          + "ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424"
          + "d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
    private static final char[] BASE62 =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    /** 解析结果：链接 + 这条链接实际能取到的音频长度（毫秒，0 = 未知）。 */
    public record Stream(String url, long servedMs) {
        public boolean ok() { return url != null && !url.isBlank(); }
    }

    /**
     * @param token   服主配置的凭据；可以只是 {@code MUSIC_U} 的值，也可以是整段 cookie 串
     * @param songId  网易云数字歌曲 id
     * @return 永不为 null；解析失败时 {@link Stream#ok()} 为 false
     */
    public static Stream songUrl(String token, String songId, long timeoutMs) {
        if (token == null || token.isBlank() || songId == null || songId.isBlank()) {
            return new Stream("", 0L);
        }
        try {
            String cookie = cookieHeader(token);
            String body = "{\"ids\":\"[" + Long.parseLong(songId.trim())
                    + "]\",\"level\":\"standard\",\"encodeType\":\"mp3\",\"csrf_token\":\""
                    + csrfOf(cookie) + "\"}";
            String[] enc = weapi(body);
            String form = "params=" + enc(enc[0]) + "&encSecKey=" + enc(enc[1]);
            String resp = post(BASE + "/weapi/song/enhance/player/url/v1", form, cookie, timeoutMs);
            JsonElement el = JsonParser.parseString(resp);
            if (el == null || !el.isJsonObject()) return new Stream("", 0L);
            JsonArray data = el.getAsJsonObject().getAsJsonArray("data");
            if (data == null || data.isEmpty() || !data.get(0).isJsonObject()) return new Stream("", 0L);
            JsonObject first = data.get(0).getAsJsonObject();
            String url = first.has("url") && !first.get("url").isJsonNull()
                    ? first.get("url").getAsString() : "";
            long served = first.has("time") && !first.get("time").isJsonNull()
                    ? first.get("time").getAsLong() : 0L;
            return new Stream(url == null ? "" : url, Math.max(0L, served));
        } catch (Throwable ignored) {
            // 任何失败都退化成「解析不出来」，上层会照旧去问客户端。
            return new Stream("", 0L);
        }
    }

    /** 把配置里的凭据补成一条完整的 Cookie 头。 */
    private static String cookieHeader(String token) {
        String t = token.trim();
        String base = t.contains("=") ? t : "MUSIC_U=" + t;
        if (!base.endsWith(";")) base = base + ";";
        // os/appver 这几项是网易云风控要看的最小设备指纹，缺了会更容易撞 8821。
        return base + " os=pc; appver=2.10.7; channel=netease;";
    }

    private static String csrfOf(String cookie) {
        int i = cookie.indexOf("__csrf=");
        if (i < 0) return "";
        int end = cookie.indexOf(';', i);
        return cookie.substring(i + 7, end < 0 ? cookie.length() : end).trim();
    }

    private static String post(String urlStr, String form, String cookie, long timeoutMs)
            throws Exception {
        int t = (int) Math.max(3_000L, Math.min(60_000L, timeoutMs));
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(t);
            conn.setReadTimeout(t);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", BASE);
            conn.setRequestProperty("Origin", BASE);
            conn.setRequestProperty("Cookie", cookie);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return readAll(is);
        } finally {
            conn.disconnect();
        }
    }

    static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            // 上限 2MB：解析接口的响应只有几百字节，再多说明拿到的不是我们要的东西。
            while ((n = in.read(buf)) > 0 && out.size() < 2 * 1024 * 1024) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** @return {@code [params, encSecKey]} */
    private static String[] weapi(String json) throws Exception {
        String secret = randomString(16);
        String first = aesCbc(json, PRESET_KEY);
        String params = aesCbc(first, secret);
        return new String[]{params, rsa(secret)};
    }

    private static String aesCbc(String data, String key) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
        return Base64.getEncoder().encodeToString(c.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String rsa(String secret) {
        String rev = new StringBuilder(secret).reverse().toString();
        BigInteger t = new BigInteger(1, rev.getBytes(StandardCharsets.UTF_8));
        BigInteger e = new BigInteger(RSA_PUB, 16);
        BigInteger m = new BigInteger(RSA_MOD, 16);
        String hex = t.modPow(e, m).toString(16);
        if (hex.length() >= 256) return hex;
        StringBuilder sb = new StringBuilder(256);
        for (int i = hex.length(); i < 256; i++) sb.append('0');
        return sb.append(hex).toString();
    }

    private static String randomString(int len) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) out[i] = BASE62[RNG.nextInt(BASE62.length)];
        return new String(out);
    }

    /** 供通用模板解析器复用：GET 一个地址并原样返回正文。 */
    static String get(String urlStr, long timeoutMs) throws Exception {
        int t = (int) Math.max(3_000L, Math.min(60_000L, timeoutMs));
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(t);
            conn.setReadTimeout(t);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return readAll(is);
        } finally {
            conn.disconnect();
        }
    }

    static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}