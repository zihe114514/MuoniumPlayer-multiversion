package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 概念版的 auth 取流链路：{@code /v1/user_verify} → {@code /v1/authorization} → {@code /tracker/v5/url}。
 *
 * <h3>为什么概念版需要另一条取流通路</h3>
 * <p>概念版 VIP（畅听 VIP）的权益<b>不在老的 {@code /v5/url} 上核销</b>。概念版 token 打到
 * {@code /v5/url} 时上游按非会员对待：面板查会员查得到，取流每档都被
 * {@code fail_process=["vip"]}/{@code ["pkg","buy"]} 拒掉。参考实现 26-08 起「新版概念版部分请求
 * 开始使用」这条 auth 链路（对照 {@code module/user_verify.js}、{@code song_auth.js}、
 * {@code song_url_auth.js}），它是概念版客户端现在实际走的取流路径。</p>
 *
 * <h3>三个端点的分工</h3>
 * <ol>
 *   <li>{@code /v1/user_verify}：登录态换<b>用户级</b> {@code auth}（形如 {@code xxx:xxx}），
 *       一次登录取一次即可，缓存与 {@code (userid, token)} 绑定——换账号自动失效，不用手动清；</li>
 *   <li>{@code /v1/authorization}：用户 {@code auth} + hash 换<b>单曲</b> {@code auth} 与
 *       {@code open_time}，按 hash 缓存；</li>
 *   <li>{@code /tracker/v5/url}：带单曲 auth 取流。注意这条<b>不走 {@code x-router}</b>，
 *       鉴权靠 {@code key}（与 {@code /v5/url} 同一把身份盐）+ 单曲 {@code auth} + Android 签名
 *       （上游 {@code song_url_auth.js} 里的 {@code notSign} 是死参数，签名照算）。</li>
 * </ol>
 *
 * <p>标准版身份<b>不使用本类</b>：那条链路对标准版没有实测依据，标准版继续走
 * {@link KuGouMusicApi} 的 {@code /v5/url} 阶梯，行为与之前完全一致。</p>
 *
 * <p><b>ogg 交付</b>：{@code need_ogg=1} 是概念版客户端的原生行为（概念版 128/320 播的就是 ogg），
 * 但本仓播放链路不支持 ogg 容器，所以 auth 链路上交付的 ogg 档按「这一档不可用」处理——
 * 调用方会沿阶梯降级（flac 及以上没有 ogg 版本，会员用户实际落点在无损）。</p>
 */
public final class KuGouAuth {

    /**
     * auth 链路两个端点要求的 {@code clientver}。
     *
     * <p>对照 {@code song_auth.js}/{@code song_url_auth.js}：写死 {@code 11561}，<b>不随身份变</b>
     * （参考实现里就是字面量）。它与 {@code /v5/url} 的 11430、身份默认的 20489/11440 是三个不同的值，
     * 各管各的端点。</p>
     */
    public static final String AUTH_CLIENT_VER = "11561";

    /** {@code module_id} 在 auth 三个端点上都是这个值，来自概念版客户端抓包。 */
    static final String MODULE_ID = "51";

    private static final Object LOCK = new Object();
    private static final int SONG_CACHE_LIMIT = 256;
    private static final String ROUTER_TRACKER_GZ = "trackercdngz.kugou.com";

    /** 用户级 auth 缓存：键是 {@code userid|token}，值是 {@code auth} 串。 */
    private static String userAuth = "";
    private static String userAuthKey = "";
    /** 用户自检结论：true 表示 user_verify 成功且 valid=1。 */
    private static boolean userVerified;

    /** 单曲 auth 缓存：hash → {@code auth|open_time}。 */
    private static final Map<String, String> SONG_AUTH = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > SONG_CACHE_LIMIT;
        }
    };

    private KuGouAuth() {
    }

    /** 清空全部缓存。换账号 / 登出后由 {@code KuGouMusicService.afterAccountChanged()} 调用。 */
    public static void invalidate() {
        synchronized (LOCK) {
            userAuth = "";
            userAuthKey = "";
            userVerified = false;
            SONG_AUTH.clear();
        }
    }

    /**
     * 最近一次用户自检是否通过。
     *
     * <p>{@code false} 不是「凭证一定无效」：网络失败也会落在这里。账号面板把它当成
     * 「建议用概念版 App 重新扫码」的提示依据，而不是硬性判定。</p>
     */
    public static boolean isUserVerified() {
        synchronized (LOCK) {
            return userVerified;
        }
    }

    /**
     * 登录自检 + 取用户级 auth。
     *
     * <p>缓存键是 {@code userid|token}：已缓存且键没变就直接返回，所以登录后可以放心反复调。
     * 失败返回空串并清掉自检标记；<b>不抛异常</b>，调用方按「拿不到 auth」处理。</p>
     */
    public static String userVerify() {
        if (!KuGouDevice.isLoggedIn()) return "";
        String cacheKey = KuGouDevice.userId() + "|" + KuGouDevice.token();
        synchronized (LOCK) {
            if (userVerified && userAuthKey.equals(cacheKey)) return userAuth;
        }
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("module_id", MODULE_ID);
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/v1/user_verify", params,
                KuGouHttp.Sign.ANDROID, router(ROUTER_TRACKER_GZ));
        JsonObject data = root == null ? null : firstObject(root, "data");
        String auth = data == null ? "" : KuGouSign.nonNull(trim(data, "auth")).trim();
        boolean valid = data != null && intOf(data, "valid") == 1;
        synchronized (LOCK) {
            if (!auth.isEmpty() && valid) {
                userAuth = auth;
                userAuthKey = cacheKey;
                userVerified = true;
            } else {
                userAuth = "";
                userAuthKey = "";
                userVerified = false;
            }
        }
        if (!auth.isEmpty() && valid) {
            System.out.println("[Music/KuGou][auth] 概念版凭证自检通过（用户 auth 已缓存）");
        } else {
            System.err.println("[Music/KuGou][auth] 概念版凭证自检未通过：确认是用概念版 App 扫的码（valid="
                    + (data == null ? "无响应" : String.valueOf(intOf(data, "valid"))) + "）");
        }
        return userVerified ? userAuth : "";
    }

    /**
     * 取一首歌的 auth 与 {@code open_time}，形如 {@code auth|open_time}。
     *
     * <p>失败返回空串并清掉该 hash 的缓存（下次重打）。{@code albumAudioId} 允许为 0：
     * 参考实现里这个参数本来就是 {@code Number(… ?? 0)}。</p>
     */
    public static String songAuth(String hash, String albumAudioId) {
        String cleanHash = KuGouSign.lower(KuGouSign.nonNull(hash).trim());
        if (cleanHash.isEmpty()) return "";
        synchronized (LOCK) {
            String cached = SONG_AUTH.get(cleanHash);
            if (cached != null) return cached;
        }
        String userAuthorization = userVerify();
        if (userAuthorization.isEmpty()) return "";

        KuGouProfile profile = KuGouProfile.active();
        Map<String, String> params = KuGouHttp.deviceParams();
        // 这条端点只认 11561：覆盖身份默认的 clientver（对照 song_auth.js 的字面量）。
        params.put("clientver", AUTH_CLIENT_VER);
        params.put("authorization", userAuthorization);
        params.put("module_id", MODULE_ID);
        params.put("album_audio_id", KuGouSign.isBlank(albumAudioId) ? "0" : albumAudioId.trim());
        params.put("hash", cleanHash);
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/v1/authorization", params,
                KuGouHttp.Sign.ANDROID, router(ROUTER_TRACKER_GZ));
        JsonObject data = root == null ? null : firstObject(root, "data");
        String auth = data == null ? "" : KuGouSign.nonNull(trim(data, "auth")).trim();
        long openTime = data == null ? 0L : longOf(data, "open_time");
        if (auth.isEmpty() || openTime <= 0L) {
            synchronized (LOCK) {
                SONG_AUTH.remove(cleanHash);
            }
            System.err.println("[Music/KuGou][auth] 拿单曲 auth 失败 " + shortHash(cleanHash)
                    + "（" + profile.label() + "）");
            return "";
        }
        String value = auth + "|" + openTime;
        synchronized (LOCK) {
            SONG_AUTH.put(cleanHash, value);
        }
        return value;
    }

    /**
     * 概念版 auth 取流。
     *
     * <p>失败返回 {@code null}，调用方（{@link KuGouMusicApi} 的阶梯）回退 {@code /v5/url}。
     * 成功但交付 ogg 时也返回 {@code null}：播放链路不支持 ogg 容器，这一档按不可用处理，
     * 原因写进日志。</p>
     *
     * @param track   曲目元数据（album_id / album_audio_id 用于请求与交付校验）
     * @param hash    与 {@code quality} <b>成对</b>的 hash（与 {@code /v5/url} 同一约束）
     * @param quality 酷狗音质字面量（{@code 128}/{@code flac}/…）
     */
    public static KuGouMusicApi.Stream resolveViaAuth(KuGouMusicApi.Track track, String hash, String quality) {
        if (track == null || KuGouSign.isBlank(hash) || KuGouSign.isBlank(quality)) return null;
        String cleanHash = KuGouSign.lower(hash.trim());
        String songAuthorization = songAuth(cleanHash, track.requestAlbumAudioId());
        if (songAuthorization.isEmpty()) return null;
        int separator = songAuthorization.indexOf('|');
        if (separator <= 0) return null;
        String auth = songAuthorization.substring(0, separator);
        String openTime = songAuthorization.substring(separator + 1);

        KuGouProfile profile = KuGouProfile.active();
        Map<String, String> params = KuGouHttp.params();
        params.put("dfid", KuGouDevice.dfid());
        params.put("mid", KuGouDevice.mid());
        params.put("uuid", "-");
        params.put("appid", profile.appId());
        params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000L));
        if (KuGouDevice.isLoggedIn()) {
            params.put("token", KuGouDevice.token());
            params.put("userid", KuGouDevice.userId());
        } else {
            params.put("userid", "0");
        }
        params.put("album_id", KuGouSign.isBlank(track.albumId) ? "0" : track.albumId);
        params.put("area_code", "1");
        params.put("module", "");
        params.put("hash", cleanHash);
        params.put("need_m", "0");
        params.put("ssa_flag", "is_fromtrack");
        params.put("version", KuGouMusicApi.streamClientVer());
        params.put("open_time", openTime);
        params.put("ptype", "0");
        params.put("need_ogg", "1");
        params.put("page_id", profile.streamPageId());
        params.put("auth", auth);
        params.put("mtype", "0");
        params.put("quality", quality);
        params.put("album_audio_id", track.requestAlbumAudioId().isEmpty() ? "0" : track.requestAlbumAudioId());
        params.put("behavior", "play");
        params.put("pid", profile.streamPid());
        params.put("module_id", MODULE_ID);
        params.put("cmd", "26");
        params.put("ppage_id", profile.streamPageIdChain());
        params.put("clientver", AUTH_CLIENT_VER);
        params.put("pidversion", "3001");
        params.put("cdnBackup", "1");
        params.put("key", KuGouSign.urlKey(cleanHash, KuGouDevice.mid(), KuGouDevice.userId(), profile));

        // 带 Android 签名、不带 x-router（对照 song_url_auth.js：它的 notSign 标志在上游
        // request.js 里是死参数，签名照算——与 /v5/url 的行为一致；x-router 则真的没有）。
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/tracker/v5/url", params, KuGouHttp.Sign.ANDROID, null);
        if (root == null || intOf(root, "status") != 1) {
            System.err.println("[Music/KuGou][auth] 取流被拒 " + track.name + " (" + quality + ")："
                    + describeFailure(root));
            return null;
        }
        String url = firstUrlOf(root);
        if (KuGouSign.isBlank(url)) return null;
        String format = KuGouSign.nonNull(trim(root, "extName")).trim();
        if (KuGouSign.isBlank(format)) format = inferFormatOf(url);
        if (isOggFormat(format, url)) {
            System.err.println("[Music/KuGou][auth] " + track.name + " (" + quality
                    + ") 交付 ogg，播放链路不支持，降级下一档");
            return null;
        }
        // 交付身份校验与 /v5/url 同判据：请求的 hash 取不到文件时上游不报错，而是交回别人的歌。
        String delivered = deliveredHashOf(root, url);
        if (!delivered.isEmpty() && !delivered.equals(cleanHash)) {
            System.err.println("[Music/KuGou][auth] 上游换了文件，弃用这一档 " + track.name + " (" + quality
                    + ")：请求 " + cleanHash + "，实得 " + delivered);
            return null;
        }
        return new KuGouMusicApi.Stream(url, format);
    }

    // ── 解析辅助（与 KuGouMusicApi 的私有辅助同判据，独立一份避免改可见性） ─────────

    private static Map<String, String> router(String name) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (!KuGouSign.isBlank(name)) headers.put("x-router", name);
        return headers;
    }

    private static JsonObject firstObject(JsonObject parent, String name) {
        if (parent == null) return null;
        com.google.gson.JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String trim(JsonObject parent, String name) {
        if (parent == null) return "";
        com.google.gson.JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        String value = element.getAsString();
        return KuGouSign.isBlank(value) ? "" : value.trim();
    }

    private static int intOf(JsonObject parent, String name) {
        if (parent == null) return 0;
        com.google.gson.JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return 0;
        try {
            return element.getAsInt();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long longOf(JsonObject parent, String name) {
        if (parent == null) return 0L;
        com.google.gson.JsonElement element = parent.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return 0L;
        try {
            return element.getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 从响应里取第一条可用地址，字段优先级与 {@code /v5/url} 路径一致：{@code url[]} →
     * {@code backupdownurl}。两个名字都兼容数组与标量两种形态——{@code cdnBackup=1} 的响应里
     * 备份地址通常是数组（对照 {@code KuGouMusicApi} 侧 {@code array(root, "url", "backupdownurl")}），
     * 只按标量读会在 url[] 为空时把本可成功的取流误判为失败。
     */
    private static String firstUrlOf(JsonObject root) {
        for (String name : new String[]{"url", "backupdownurl"}) {
            com.google.gson.JsonElement element = root.get(name);
            if (element == null || element.isJsonNull()) continue;
            if (element.isJsonArray()) {
                for (com.google.gson.JsonElement item : element.getAsJsonArray()) {
                    if (item == null || !item.isJsonPrimitive()) continue;
                    String candidate = item.getAsString();
                    if (!KuGouSign.isBlank(candidate)) return candidate.trim();
                }
            } else if (element.isJsonPrimitive()) {
                String candidate = element.getAsString();
                if (!KuGouSign.isBlank(candidate)) return candidate.trim();
            }
        }
        return "";
    }

    private static String inferFormatOf(String url) {
        String clean = KuGouSign.lower(KuGouSign.nonNull(url));
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int dot = clean.lastIndexOf('.');
        return dot >= 0 && dot < clean.length() - 1 ? clean.substring(dot + 1) : "mp3";
    }

    /**
     * 交付的是不是 ogg 容器。
     *
     * <p>同时看 {@code extName} 与地址后缀：有的响应 {@code extName} 按请求档位填
     * （请求 flac 回 ogg 文件时 extName 仍是 flac），地址段才是可靠的。</p>
     */
    static boolean isOggFormat(String format, String url) {
        if (!KuGouSign.isBlank(format) && KuGouSign.lower(format.trim()).contains("ogg")) return true;
        String clean = KuGouSign.lower(KuGouSign.nonNull(url));
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        return clean.endsWith(".ogg");
    }

    private static String deliveredHashOf(JsonObject root, String url) {
        String reported = KuGouSign.lower(KuGouSign.nonNull(trim(root, "hash")));
        if (!reported.isEmpty()) return reported;
        String address = KuGouSign.lower(KuGouSign.nonNull(url));
        int marker = address.indexOf("/v3/");
        if (marker < 0) return "";
        int start = marker + 4;
        int end = address.length();
        for (int index = start; index < address.length(); index++) {
            char current = address.charAt(index);
            if (current == '/' || current == '?' || current == '&') {
                end = index;
                break;
            }
        }
        return address.substring(start, end);
    }

    private static String describeFailure(JsonObject root) {
        if (root == null) return "无响应";
        int status = intOf(root, "status");
        int errorCode = intOf(root, "error_code");
        String raw = root.toString();
        if (raw.length() > 240) raw = raw.substring(0, 240) + "…";
        return "status=" + status + " error_code=" + errorCode + " " + raw;
    }

    private static String shortHash(String hash) {
        return KuGouSign.nonNull(hash).length() <= 8 ? hash : hash.substring(0, 8) + "…";
    }
}
