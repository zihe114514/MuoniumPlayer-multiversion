package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.utils.network.HttpUtils;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 酷狗接口的请求封装：注入设备参数、算签名、发请求、解 JSON。
 *
 * <p>拆成独立一层而不是塞进 API 类里，是因为酷狗有<b>四套</b>签名、<b>多个</b>网关，
 * 每个端点选哪一套是端点自己的属性。把这段逻辑集中在这里，{@link KuGouMusicApi} 里每个方法
 * 就只剩「填参数 + 读字段」。</p>
 *
 * <h3>网关分工（都是实测确认的）</h3>
 * <ul>
 *   <li>{@link #GATEWAY}（{@code gateway.kugou.com}）：主力。搜索、歌单、榜单、推荐、FM、
 *       取流都走它，靠 {@code x-router} 请求头把请求转给对应的后端服务。</li>
 *   <li>{@link #OPENAPI}（{@code openapi.kugou.com}）：歌手单曲等少数端点直连这个域名。</li>
 *   <li>{@link #MOBILE_CDN}（{@code mobilecdn.kugou.com}）：老移动端，免签。现在只剩
 *       「歌单详情」一类端点还可靠，搜索/榜单都已经改走网关。</li>
 *   <li>{@link #VIP}（{@code kugouvip.kugou.com}）：会员状态查询。</li>
 * </ul>
 *
 * <h3>设备身份</h3>
 * <p>{@code mid} 由一个持久化的 GUID 推出（见 {@link KuGouSign#mid}）。它必须在一次安装里保持稳定：
 * 每次请求换一个新 mid 会被上游当成大量新设备，风控会开始要求二次验证（{@code ssa-code}），
 * 表现是接口突然全部返回 {@code error_code} 非 0。{@code dfid} 缺失时用 {@code "-"}，
 * <b>实测 {@code "-"} 对搜索、榜单、取流都可用</b>，所以设备注册只是锦上添花、不是前置条件。</p>
 *
 * <p><b>并发</b>：本类的静态方法无共享可变状态，可以从多个线程同时调用。</p>
 */
public final class KuGouHttp {

    /** 主网关，需要 Android 签名。 */
    public static final String GATEWAY = "https://gateway.kugou.com";

    /** openapi 域名，少数端点（歌手单曲）直连它。 */
    public static final String OPENAPI = "https://openapi.kugou.com";

    /** 老 trackercdn，只作最后兜底。 */
    public static final String TRACKER_CDN = "https://trackercdn.kugou.com";

    /**
     * {@code /v6/priv_url} 的域名。
     *
     * <p>它与 {@link #GATEWAY} 不是一回事：{@code /v5/url} 是「打到 gateway、用 {@code x-router}
     * 转给 trackercdn」，而 {@code /v6/priv_url} 在参考实现里<b>直连 tracker 且不带任何
     * {@code x-router}</b>。协议是 http 不是 https（参考实现原样），照搬不改。</p>
     */
    public static final String TRACKER = "http://tracker.kugou.com";

    /** 老移动端网关，免签。 */
    public static final String MOBILE_CDN = "http://mobilecdn.kugou.com";

    /** Web 版端点（歌单详情等）。 */
    public static final String MOBILE_WEB = "http://m.kugou.com";

    public static final String LOGIN_USER = "https://login-user.kugou.com";

    public static final String LYRICS = "https://lyrics.kugou.com";

    /** VIP 信息。 */
    public static final String VIP = "https://kugouvip.kugou.com";

    /**
     * 动态封面 CDN。
     *
     * <p><b>这个域名对请求头极敏感</b>：带上 {@code kg-rc}/{@code kg-thash} 那套默认头会随机
     * 触发 sign error，必须用 {@link #getBareHeaders} 走「一个默认头都不带」的路径。</p>
     */
    public static final String KMR_CDN = "https://kmrcdn.service.kugou.com";

    /** CSCC 上报域名（听歌时长）。同样要裸头。 */
    public static final String D_KUGOU = "http://d.kugou.com";

    public static final String APP_ID = "1005";
    public static final String CLIENT_VER = "20489";
    public static final String SRC_APP_ID = "2919";

    /**
     * 当前身份的 {@code appid}。
     *
     * <p>写请求体里的 {@code appid} 字段、算 {@code key} 时都要用它，<b>不要</b>直接引用
     * {@link #APP_ID}——那个常量是标准版的固定值，只有「与平台无关」的签名（动态封面）才该用它。</p>
     */
    public static String appId() {
        return KuGouProfile.active().appId();
    }

    /** 当前身份的 {@code clientver}（取流端点另有固定的 {@code 11430}，见 {@code STREAM_CLIENT_VER}）。 */
    public static String clientVer() {
        return KuGouProfile.active().clientVer();
    }

    /**
     * 搜索接口要求的「平台版本」参数。
     *
     * <p>{@code plat} 是仅有两三个合法取值的接口级开关，不是可选的装饰：不带它时
     * {@code /v3/search/song} 稳定返回 {@code error_code=152 Parameter Error}，
     * 换任何其它参数组合都救不回来（逐个二分验证过）。{@code 2} = Android。</p>
     */
    public static final String PLAT_ANDROID = "2";

    private static final String USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";

    private KuGouHttp() {
    }

    /**
     * 该端点用哪一套签名。
     *
     * <p>{@code REGISTER}（设备注册签名）保留了实现但当前没有端点使用：设备注册链路
     * 在本项目里不可用，见 {@link KuGouDevice} 的类注释。留着是因为它不是错的，只是用不上。</p>
     */
    public enum Sign {
        /** 不加签名（老 mobilecdn / m.kugou.com / lyrics.kugou.com 端点）。 */
        NONE,
        ANDROID,
        WEB,
        REGISTER
    }

    /** 一次请求的参数集合。用 LinkedHashMap 保持可读的书写顺序；签名自己会排序。 */
    public static Map<String, String> params() {
        return new LinkedHashMap<String, String>();
    }

    /**
     * 注入每个签名接口都要带的设备与版本参数。
     *
     * <p>登录态存在时<b>同时</b>把 {@code token}/{@code userid} 放进查询串和请求体：取流与
     * 云歌单服务都从查询串读它们，少一处就会静默降级成匿名请求（表现为「VIP 曲拿不到地址」
     * 而不是报错）。</p>
     */
    public static Map<String, String> deviceParams() {
        KuGouProfile profile = KuGouProfile.active();
        Map<String, String> params = params();
        params.put("dfid", KuGouDevice.dfid());
        params.put("mid", KuGouDevice.mid());
        params.put("uuid", "-");
        params.put("appid", profile.appId());
        params.put("clientver", profile.clientVer());
        params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000L));
        if (KuGouDevice.isLoggedIn()) {
            params.put("token", KuGouDevice.token());
            params.put("userid", KuGouDevice.userId());
        }
        return params;
    }

    /** 显式把登录凭证注入到一组已有参数里（榜单等端点要求 {@code userid} 存在，即使是 "0"）。 */
    public static Map<String, String> withAuth(Map<String, String> params, boolean alwaysUserId) {
        if (params == null) params = params();
        if (KuGouDevice.isLoggedIn()) {
            params.put("token", KuGouDevice.token());
            params.put("userid", KuGouDevice.userId());
        } else if (alwaysUserId) {
            params.put("userid", "0");
            params.put("token", "");
        }
        return params;
    }

    public static JsonObject get(String baseUrl, String path, Map<String, String> params, Sign sign) {
        return get(baseUrl, path, params, sign, null);
    }

    /**
     * @param extraHeaders 额外请求头，例如新网关的 {@code x-router} / {@code kg-tid}
     * @return 解析后的 JSON 对象；请求或解析失败时返回 {@code null}（调用方按「没数据」处理，
     *         不要吞成空对象）
     */
    public static JsonObject get(String baseUrl, String path, Map<String, String> params, Sign sign,
                                 Map<String, String> extraHeaders) {
        Map<String, String> signed = signIfNeeded(params, sign, null);
        String url = baseUrl + path + "?" + query(signed);
        try {
            InputStream stream = HttpUtils.request(url, null, headers(signed, extraHeaders), "GET");
            return parse(stream);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] GET " + path + " 失败: " + describe(throwable));
            return null;
        }
    }

    /**
     * POST JSON。签名要把请求体也算进去，所以 body 必须在算签名前就定下来。
     *
     * <p>返回 {@code null} 表示请求或解析失败，<b>不代表业务失败</b>：酷狗的业务失败是
     * HTTP 200 + {@code status=0}，字段检查交给调用方。</p>
     */
    public static JsonObject postJson(String baseUrl, String path, Map<String, String> params, String body,
                                      Sign sign, Map<String, String> extraHeaders) {
        Map<String, String> signed = signIfNeeded(params, sign, body);
        String url = baseUrl + path + "?" + query(signed);
        Map<String, String> headers = headers(signed, extraHeaders);
        headers.put("Content-Type", "application/json");
        try {
            InputStream stream = HttpUtils.request(url, body, headers, "POST", "application/json");
            return parse(stream);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] POST " + path + " 失败: " + describe(throwable));
            return null;
        }
    }

    /**
     * POST 原始字符串体（云歌单服务的 AES 负载是 base64，但签名按原文算）。
     *
     * @param contentType 默认 {@code application/json}
     */
    public static byte[] postRaw(String baseUrl, String path, Map<String, String> params, String body,
                                 Sign sign, Map<String, String> extraHeaders, String contentType) {
        Map<String, String> signed = signIfNeeded(params, sign, body);
        String url = baseUrl + path + "?" + query(signed);
        Map<String, String> headers = headers(signed, extraHeaders);
        headers.put("Content-Type", contentType == null ? "application/json" : contentType);
        try {
            InputStream stream = HttpUtils.request(url, body, headers, "POST", contentType);
            return readAll(stream);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] POST(raw) " + path + " 失败: " + describe(throwable));
            return null;
        }
    }

    /**
     * 发一个「只有指定头、没有任何默认头」的请求。
     *
     * <p>给 {@link #KMR_CDN} 与 {@link #D_KUGOU} 用。这两个域名对默认的 {@code kg-*} 系列头
     * 过敏，多带一个就会随机失败，所以走这条<b>完全绕开</b> {@link #headers} 的路径——
     * 不是在 {@link #headers} 上加开关，因为「默认头有哪些」这件事在两个调用点上是完全不同的。</p>
     *
     * @param body        {@code null} 表示无请求体
     * @param contentType {@code null} 时用 {@code application/octet-stream}
     */
    public static byte[] requestBare(String method, String url, Map<String, String> bareHeaders,
                                     String body, String contentType) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (bareHeaders != null) headers.putAll(bareHeaders);
        if (body != null) {
            headers.put("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        }
        try {
            InputStream stream = HttpUtils.request(url, body, headers, method,
                    body == null ? null : headers.get("Content-Type"));
            return readAll(stream);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] " + method + " " + url + " 失败: " + describe(throwable));
            return null;
        }
    }

    /**
     * 动态封面 CDN 要求的最小头集。
     *
     * <p>{@code mid} 传的是<b>字面量字符串 {@code "undefined"}</b>，不是真实 mid——这是上游
     * 参考实现里既有的行为，实测可用，别「顺手修正」成真 mid。</p>
     */
    public static Map<String, String> kmrCdnHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("dfid", "-");
        headers.put("mid", "undefined");
        headers.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000L));
        return headers;
    }

    private static Map<String, String> signIfNeeded(Map<String, String> params, Sign sign, String body) {
        Map<String, String> copy = params == null ? params() : new LinkedHashMap<String, String>(params);
        if (sign == Sign.ANDROID) {
            // 跟随当前身份：概念版是另一把盐值，与 appid 必须成套。
            copy.put("signature", KuGouSign.android(copy, body, KuGouProfile.active()));
        } else if (sign == Sign.WEB) {
            copy.put("signature", KuGouSign.web(copy, body));
        } else if (sign == Sign.REGISTER) {
            copy.put("signature", KuGouSign.register(copy));
        }
        return copy;
    }

    private static Map<String, String> headers(Map<String, String> params, Map<String, String> extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("dfid", KuGouDevice.dfid());
        headers.put("mid", KuGouDevice.mid());
        headers.put("kg-rc", "1");
        headers.put("kg-thash", "5d816a0");
        headers.put("kg-rec", "1");
        headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
        String clientTime = params == null ? null : params.get("clienttime");
        headers.put("clienttime", clientTime == null
                ? String.valueOf(System.currentTimeMillis() / 1000L) : clientTime);
        if (extraHeaders != null) headers.putAll(extraHeaders);
        return headers;
    }

    private static String query(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        if (params == null) return "";
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) builder.append('&');
            builder.append(KuGouSign.urlEncode(entry.getKey()))
                    .append('=')
                    .append(KuGouSign.urlEncode(entry.getValue()));
        }
        return builder.toString();
    }

    private static JsonObject parse(InputStream stream) {
        if (stream == null) return null;
        String text = HttpUtils.readString(stream);
        return parseText(text);
    }

    /** 把一段可能的 JSON 文本解析成对象；不是对象（HTML 错误页等）时返回 {@code null}。 */
    public static JsonObject parseText(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            JsonElement element = new JsonParser().parse(text.trim());
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] readAll(InputStream stream) {
        if (stream == null) return null;
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return buffer.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) return "unknown";
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }
}
