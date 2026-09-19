package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import top.fpsmaster.music.QrCode;
import top.fpsmaster.music.QrLoginState;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bilibili 的网页扫码登录。
 *
 * <p>链路只有两跳，但状态机比网易云那条直白：B 站把「等待扫码 / 已扫码待确认 / 已过期」
 * 全塞在 {@code data.code} 这一个业务码里，而不是靠 HTTP 状态或字段组合推断。</p>
 *
 * <pre>
 *   GET passport.bilibili.com/x/passport-login/web/qrcode/generate
 *       → data.url         二维码内容（用哔哩哔哩 App 扫这个）
 *       → data.qrcode_key  轮询凭证
 *   GET passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=…
 *       → data.code = 0 成功 / 86101 未扫描 / 86090 已扫码 / 86038 已过期
 *       → 成功那一刻，登录凭据（SESSDATA、bili_jct、buvid3…）出现在响应的 Set-Cookie 里
 * </pre>
 *
 * <p>凭据从响应头收集后交给 {@link BilibiliSessionStore} 落盘，本类不持有 Cookie。
 * 只有 {@code qrcode_key} 与它的过期时刻留在内存（{@link #PENDING}）：那是一次性会话状态，
 * 落盘既没意义也会把扫码痕迹留在磁盘上。</p>
 */
public final class BilibiliLogin {

    private static final String QR_GENERATE_API =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";
    private static final String QR_POLL_API =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll";

    /** B 站官方二维码有效期就是 180 秒。 */
    private static final long QR_TTL_MILLIS = 180_000L;

    // 上游业务码。写成常量是为了让 mapToState 的 default 分支不至于吞掉一个改名。
    private static final int CODE_SUCCESS = 0;
    private static final int CODE_EXPIRED = 86038;
    private static final int CODE_SCANNED = 86090;
    private static final int CODE_WAITING = 86101;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<String, PendingQr> PENDING = new ConcurrentHashMap<>();

    private BilibiliLogin() {
    }

    private static final class PendingQr {
        private final long expiresAt;
        private final String cookieHeader;

        private PendingQr(long expiresAt, String cookieHeader) {
            this.expiresAt = expiresAt;
            this.cookieHeader = cookieHeader;
        }
    }

    /**
     * 申请一张登录二维码。
     *
     * <p>{@link QrCode#getQrContent()} 返回的是 B 站给的原始 URL，账号面板那条通用渲染路径
     * （{@code QRCodeGenerator.generateQRCode(content, …)}）会把它画成码；不是 data URL，
     * 所以不会误进 QQ/汽水那套「base64 直接解码」的分支。</p>
     */
    public static QrCode createQrCode() {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(QR_GENERATE_API))
                            .timeout(Duration.ofSeconds(20))
                            .header("User-Agent", BilibiliMusicService.USER_AGENT)
                            .header("Referer", BilibiliMusicService.REFERER)
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            JsonObject body = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
            BilibiliMusicService.requireSuccessful(body, "qrcode/generate");
            JsonObject data = BilibiliMusicService.object(body, "data");
            String content = BilibiliMusicService.string(data, "url");
            String key = BilibiliMusicService.string(data, "qrcode_key");
            if (content.isEmpty() || key.isEmpty()) {
                throw new IllegalStateException("哔哩哔哩未返回登录二维码");
            }
            // generate 这一跳也会下发 buvid 之类的匿名 cookie，先收下：poll 成功时再和
            // 登录 cookie 合并，缺一半都会让风控概率上升。
            PENDING.put(key, new PendingQr(System.currentTimeMillis() + QR_TTL_MILLIS,
                    cookieHeaderFrom(response.headers().allValues("set-cookie"))));
            return new QrCode(key, content);
        } catch (Throwable throwable) {
            throw new IllegalStateException("获取哔哩哔哩登录二维码失败: "
                    + BilibiliMusicService.describe(throwable), throwable);
        }
    }

    /**
     * 轮询一次扫码状态。
     *
     * <p>成功分支必须<b>先落盘凭据、再返回 {@link QrLoginState#CONFIRMED}</b>：账号面板看到
     * CONFIRMED 就会去读登录态，顺序反了会显示成「登录成功但仍是未登录」。</p>
     */
    public static QrLoginState checkQrCode(QrCode qrCode) {
        if (qrCode == null) return QrLoginState.ERROR;
        String key = BilibiliMusicService.nonBlank(qrCode.getKey());
        if (key.isEmpty()) return QrLoginState.ERROR;

        PendingQr pending = PENDING.get(key);
        if (pending != null && System.currentTimeMillis() > pending.expiresAt) {
            PENDING.remove(key);
            return QrLoginState.EXPIRED;
        }

        try {
            String url = QR_POLL_API + "?qrcode_key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", BilibiliMusicService.USER_AGENT)
                    .header("Referer", BilibiliMusicService.REFERER)
                    .header("Accept", "application/json");
            if (pending != null && !pending.cookieHeader.isEmpty()) {
                builder.header("Cookie", pending.cookieHeader);
            }
            HttpResponse<String> response = HTTP.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return QrLoginState.ERROR;

            JsonObject body = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject data = BilibiliMusicService.object(body, "data");
            // 外层 code 非 0 时 data 常常是 null：那是接口级错误（风控、限流），不是扫码状态。
            if (data == null) return QrLoginState.ERROR;
            int code = (int) BilibiliMusicService.longValue(data, "code");

            if (code == CODE_SUCCESS) {
                Map<String, String> cookies = parseSetCookies(response.headers()
                        .allValues("set-cookie"));
                if (cookies.isEmpty()) {
                    System.err.println("[Music/Bilibili] QR poll reported success without cookies");
                    return QrLoginState.ERROR;
                }
                BilibiliSessionStore.saveCookies(cookies);
                PENDING.remove(key);
                BilibiliMusicService.onLoginConfirmed();
                return QrLoginState.CONFIRMED;
            }

            QrLoginState state = mapToState(code);
            if (state == QrLoginState.EXPIRED) PENDING.remove(key);
            return state;
        } catch (Throwable throwable) {
            // 轮询里的一次网络抖动不该终止整个会话：保持 WAITING，让账号面板的
            // transientErrors 计数去决定何时放弃（它有 3 次的容忍度）。
            System.err.println("[Music/Bilibili] QR poll failed: "
                    + BilibiliMusicService.describe(throwable));
            return QrLoginState.WAITING;
        }
    }

    /**
     * B 站业务码 → 平台无关的扫码状态。
     *
     * <p>未知码一律 {@link QrLoginState#ERROR} 而不是 WAITING：新出现的码值得被看见，
     * 把它咽进「等待扫码」会让用户盯着一张永远不会动的二维码。</p>
     */
    static QrLoginState mapToState(int code) {
        switch (code) {
            case CODE_SUCCESS:
                return QrLoginState.CONFIRMED;
            case CODE_WAITING:
                return QrLoginState.WAITING;
            case CODE_SCANNED:
                return QrLoginState.SCANNED;
            case CODE_EXPIRED:
                return QrLoginState.EXPIRED;
            default:
                return QrLoginState.ERROR;
        }
    }

    /** 从一批 {@code Set-Cookie} 头里取出 name→value，丢掉属性段。 */
    static Map<String, String> parseSetCookies(List<String> headers) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (headers == null) return cookies;
        for (String header : headers) {
            String pair = BilibiliMusicService.nonBlank(header);
            if (pair.isEmpty()) continue;
            int semicolon = pair.indexOf(';');
            if (semicolon >= 0) pair = pair.substring(0, semicolon);
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            // B 站会用空值显式删除某个 cookie；收下来只会污染拼接串，跳过。
            if (!name.isEmpty() && !value.isEmpty()) cookies.put(name, value);
        }
        return cookies;
    }

    private static String cookieHeaderFrom(List<String> setCookieHeaders) {
        return BilibiliSessionStore.joinCookies(parseSetCookies(setCookieHeaders));
    }
}
