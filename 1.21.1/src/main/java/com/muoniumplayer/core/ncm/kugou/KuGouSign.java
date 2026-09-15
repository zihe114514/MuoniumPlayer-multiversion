package com.muoniumplayer.core.ncm.kugou;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 酷狗接口的请求签名。
 *
 * <p>这些盐值与拼接顺序是第三方逆向得到的协议事实（对照 {@code MakcRe/KuGouMusicApi} 的
 * {@code util/helper.js}），不是酷狗官方定义。改动前请先用真实请求验证，签名错的表现是
 * {@code error_code} 非 0 而 HTTP 仍然 200 —— 不会抛异常，只会一直取不到数据。</p>
 *
 * <h3>五种签名，区别只在盐值和参与拼接的内容</h3>
 * <ul>
 *   <li>{@link #android(Map, String)}：{@code MD5(salt + 排序后的 key=value + body + salt)}，
 *       网关（{@code gateway.kugou.com}）与 {@code trackercdn} 用这一套。请求体<b>参与</b>签名，
 *       所以 POST 必须在算签名前就把 body 定下来。</li>
 *   <li>{@link #web(Map, String)}：与 android 同形，盐值不同，且排序的是<b>拼好的
 *       {@code key=value} 串</b>而不是 key —— 两者在含数字 key 时结果不同，不能合并。
 *       {@code login-user.kugou.com} 的扫码接口用这一套。</li>
 *   <li>{@link #register(Map)}：只取<b>值</b>排序后夹 {@code "1014"}，设备注册接口用。</li>
 *   <li>{@link #paramsKey(String, String, String)}：{@code MD5(appid + salt + clientver + data)}，
 *       若干「概念版」接口的 {@code key} 参数用。</li>
 *   <li>{@link #urlKey} / {@link #cloudKey}：取流与云盘接口的专用 key。</li>
 * </ul>
 *
 * <p><b>排序的对象是 key，不是拼好的字符串</b>（{@link #web} 除外）。差别出现在「某个 key 是另一个
 * key 的前缀、且后者后续字符小于 {@code '='}」时，实测踩过就再也不会想当然。</p>
 */
public final class KuGouSign {

    /** Android 客户端签名盐值（标准版）。 */
    private static final String ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp";

    /** Web 端签名盐值。 */
    private static final String WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";

    /** 设备注册接口的盐值，作用等同于把值串夹在中间。 */
    private static final String REGISTER_SALT = "1014";

    /** {@code /v5/url} 取流的 key 盐值（标准版）。 */
    private static final String URL_KEY_SALT = "57ae12eb6890223e355ccfcb74edf70d";

    /** 云盘取流（{@code query_musicclound_url}）的 key 盐值。 */
    private static final String CLOUD_KEY_SALT = "ebd1ac3134c880bda6a2194537843caa0162e2e7";

    /** 老版 {@code trackercdn.kugou.com/i/v2/} 的 key 盐值。 */
    private static final String PLAY_KEY_SALT = "kgcloudv2";

    private KuGouSign() {
    }

    public static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                String hex = Integer.toHexString(item & 0xFF);
                if (hex.length() == 1) builder.append('0');
                builder.append(hex);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // 每个 JRE 都必须提供 MD5，这里走不到。
            throw new IllegalStateException("MD5 unavailable", impossible);
        }
    }

    /**
     * 对原始字节做 MD5。
     *
     * <p>请求体始终是 UTF-8 文本（JSON 或 base64），所以签名入口只接受 String；
     * 这条重载留给「需要对二进制文件本身取哈希」的场景（例如校验本地缓存的音频块）。</p>
     */
    public static String md5(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(data == null ? new byte[0] : data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                String hex = Integer.toHexString(item & 0xFF);
                if (hex.length() == 1) builder.append('0');
                builder.append(hex);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("MD5 unavailable", impossible);
        }
    }

    /**
     * 对「两段字节按序拼接」的结果做 MD5。
     *
     * <p>CSCC 上报的 {@code sign} 是 {@code MD5(前缀 ‖ 密文)}，而密文是任意二进制——先把它
     * 转成字符串再取哈希会因为编码往返而改变字节，签名必然对不上。所以这里按字节拼。</p>
     */
    public static String md5(byte[] first, byte[] second) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(first == null ? new byte[0] : first);
            byte[] hash = digest.digest(second == null ? new byte[0] : second);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                String hex = Integer.toHexString(item & 0xFF);
                if (hex.length() == 1) builder.append('0');
                builder.append(hex);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("MD5 unavailable", impossible);
        }
    }

    /** Android 版签名：按 key 排序后拼 {@code key=value}，前后夹盐值，请求体（若有）夹在末尾盐值之前。 */
    public static String android(Map<String, String> params, String body) {
        return md5(ANDROID_SALT + joinSortedByKey(params) + (body == null ? "" : body) + ANDROID_SALT);
    }

    /** Web 版签名：排序的是拼好的 {@code key=value} 串本身，这是上游的行为，不要「顺手改成」按 key 排。 */
    public static String web(Map<String, String> params) {
        return web(params, null);
    }

    /**
     * Web 版签名（带请求体）。
     *
     * <p>H5 的 {@code kGRequest} 对 POST 会把 JSON 请求体拼进签名；GET 传 {@code null} 即可。</p>
     */
    public static String web(Map<String, String> params, String body) {
        List<String> pairs = new ArrayList<String>();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                pairs.add(entry.getKey() + "=" + nonNull(entry.getValue()));
            }
        }
        Collections.sort(pairs);
        StringBuilder builder = new StringBuilder();
        for (String pair : pairs) builder.append(pair);
        return md5(WEB_SALT + builder + (body == null ? "" : body) + WEB_SALT);
    }

    /** 设备注册接口签名：只取值、排序、夹 {@code "1014"}。 */
    public static String register(Map<String, String> params) {
        List<String> values = new ArrayList<String>();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                values.add(nonNull(entry.getValue()));
            }
        }
        Collections.sort(values);
        StringBuilder builder = new StringBuilder();
        for (String value : values) builder.append(value);
        return md5(REGISTER_SALT + builder + REGISTER_SALT);
    }

    /**
     * 「参数密钥」签名：{@code MD5(appid + salt + clientver + data)}。
     *
     * <p>{@code song_climax}、{@code special_recommend}、{@code personal_recommend}、
     * {@code register_dev} 等接口的 {@code key} 参数用它，{@code data} 一般是毫秒时间戳。</p>
     */
    public static String paramsKey(String data) {
        return paramsKey(data, KuGouHttp.APP_ID, KuGouHttp.CLIENT_VER);
    }

    public static String paramsKey(String data, String appId, String clientVer) {
        return md5(nonNull(appId) + ANDROID_SALT + nonNull(clientVer) + nonNull(data));
    }

    /**
     * {@code /v5/url} 取流需要的 {@code key} 参数。
     *
     * <p>对应参考实现的 {@code signKey}，标准版（非 lite）取的就是这个盐值。注意概念版用的是
     * 另一个盐值 {@code 185672dd...}——<b>本项目只做标准版</b>，历史上曾经把那个概念版盐值
     * 当成「现代接口的盐值」用在标准版上，结果每次取流都拿不到地址。要切双平台时必须把
     * 盐值选择和 appid 一起按平台取，不能只换一个。</p>
     */
    public static String urlKey(String hash, String mid, String userId, String appId) {
        return md5(nonNull(hash) + URL_KEY_SALT + nonNull(appId) + nonNull(mid)
                + (isBlank(userId) ? "0" : userId));
    }

    /** 云盘取流 key：{@code MD5("musicclound" + hash + pid + salt)}。 */
    public static String cloudKey(String hash, String pid) {
        return md5("musicclound" + nonNull(hash) + nonNull(pid) + CLOUD_KEY_SALT);
    }

    /** 老版取流接口的 key：{@code MD5(hash + "kgcloudv2")}。 */
    public static String playKey(String hash) {
        return md5(lower(hash) + PLAY_KEY_SALT);
    }

    /**
     * 由设备 GUID 推出 mid。
     *
     * <p>上游把 MD5 的 32 位十六进制串<b>当成一个 16 进制大整数</b>再转十进制，而不是取它的数值哈希。
     * 直接 {@code Long.parseLong} 会溢出，必须用 {@link BigInteger}。</p>
     */
    public static String mid(String guid) {
        return new BigInteger(md5(guid), 16).toString();
    }

    /** 参数按 key 升序拼成 {@code key=value} 连续串（无分隔符），这是 android 签名的输入。 */
    private static String joinSortedByKey(Map<String, String> params) {
        List<String> keys = new ArrayList<String>();
        if (params != null) keys.addAll(params.keySet());
        Collections.sort(keys);
        StringBuilder builder = new StringBuilder();
        for (String key : keys) {
            builder.append(key).append('=').append(nonNull(params.get(key)));
        }
        return builder.toString();
    }

    static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(nonNull(value), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            return nonNull(value);
        }
    }

    static String lower(String value) {
        return nonNull(value).toLowerCase(java.util.Locale.ROOT);
    }

    static String nonNull(String value) {
        return value == null ? "" : value;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
