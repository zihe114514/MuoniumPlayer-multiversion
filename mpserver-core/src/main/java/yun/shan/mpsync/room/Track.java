package yun.shan.mpsync.room;

import com.google.gson.JsonObject;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.util.SourceGuard;

/**
 * 一首由客户端上传的歌：元数据 + 点歌人用自己会话解析出来的取流直链。服务端原样保存，
 * 从不自己去取音频，也不关心是哪个平台 —— 全房共用点歌人那一条链接，这就是它能同时支持
 * 网易云 / QQ / 酷狗 / GD 的原因。
 *
 * <p>直链是有签名有效期的，所以有 {@link #urlExpiresAt}：队列里的歌轮到播放时如果链接已过期，
 * 房间会发 {@link Proto#S_URL_NEEDED} 找客户端重新解析，而不是把一条死链推出去。能应答的只有
 * 装了对应平台并已登录的成员 —— 这是共享直链模型的固有边界。
 *
 * <p>身份是 {@link #key()}（{@code source:songId}）而不是单独的 {@code songId}：跨平台之后
 * 不同平台完全可能出现相同的数字 id，只按 songId 比会让两首歌撞成一首，去重把第二首拒了，
 * 更糟的是 {@code url_supply} 会把 A 平台的链接塞进 B 平台的曲目里。
 */
public final class Track {

    /** 平台令牌，见 {@link Proto#isValidSource}。 */
    public final String source;
    public final String songId;
    public String name;
    public String artist;
    public String album;
    public String cover;
    public long durationMs;
    public String url;
    /** Epoch ms after which {@link #url} must be re-resolved; 0 = unknown (assume short). */
    public long urlExpiresAt;
    /** Length of the audio {@link #url} serves, as reported by the resolving client;
     *  0 when unknown. Shorter than {@link #durationMs} means a VIP trial clip. */
    public long urlDurationMs;

    public Track(String source, String songId) {
        this.source = source;
        this.songId = songId;
    }

    /** 跨平台唯一的曲目身份。 */
    public String key() {
        return source + ":" + songId;
    }

    /**
     * Validate + adopt a client-supplied track object.
     *
     * @throws IllegalArgumentException 带一句面向用户的中文原因，说明这份负载为什么不能入队。
     */
    /** 客户端自报时长的可信上限（毫秒）。正常曲目最长不过二十来分钟；超过它的负载
     *  只可能是点歌人（或换源替身）想用一首「歌」占住整间房。 */
    public static final long MAX_PLAUSIBLE_DURATION_MS = 30 * 60 * 1000L;

    public static Track fromJson(JsonObject o, MpConfig cfg) {
        if (o == null) throw new IllegalArgumentException("缺少歌曲信息");
        // 老客户端不发 source，按网易云处理。
        String source = Proto.str(o, "source", Proto.SOURCE_NETEASE).trim().toLowerCase(java.util.Locale.ROOT);
        if (!Proto.isValidSource(source)) throw new IllegalArgumentException("无效的播放源标识");
        if (!cfg.isSourceAllowed(source)) {
            throw new IllegalArgumentException("服务器未开放该播放源：" + source);
        }
        String songId = Proto.str(o, "songId", "").trim();
        if (!SourceGuard.isValidSongId(songId)) throw new IllegalArgumentException("无效的歌曲 ID");

        Track t = new Track(source, songId);
        t.name = clip(Proto.str(o, "name", "未知歌曲"), 120);
        t.artist = clip(Proto.str(o, "artist", ""), 120);
        t.album = clip(Proto.str(o, "album", ""), 120);
        String cover = Proto.str(o, "cover", "");
        t.cover = SourceGuard.isAllowedUrl(cover, cfg.allowedHosts) ? cover : "";
        t.durationMs = Math.max(0L, Proto.num(o, "duration", 0L));
        if (t.durationMs > MAX_PLAUSIBLE_DURATION_MS) {
            // 没有时长判不出交接点会让房间卡死（enqueue 已挡 0），时长离谱则反过来——
            // 房间被一首「歌」合法占住半小时起步，频道里只能靠投票解。两个方向都封死。
            throw new IllegalArgumentException("曲目时长超出可信范围，已拒绝");
        }
        String url = Proto.str(o, "url", "");
        long served = Proto.num(o, "urlDuration", 0L);
        // 点歌人自己上传的那条链同样可能是「算出来的」长度（酷狗 / LX 一路），判据必须与
        // 代解析那一路一致，否则同一台机器解析出的链接，走两条路会得到两种结论。
        boolean estimated = Proto.bool(o, "urlDurationEstimated", false);
        if (!url.isBlank() && isTrialClip(served, t.durationMs, cfg, estimated)) {
            // One resolved link is shared with the whole room, so a trial clip resolved by
            // a non-VIP account would truncate the song for everyone.
            throw new IllegalArgumentException("该歌曲解析到的只是试听片段（需要 VIP），已拒绝");
        }
        t.adoptUrl(url, Proto.num(o, "urlExpiresAt", 0L), served, cfg, estimated);
        return t;
    }

    /**
     * Accept a (re-)resolved URL, dropping anything outside the allowlist and any trial
     * clip (a shorter-than-track stream, i.e. a VIP-gated preview).
     *
     * @return true when the URL was adopted
     */
    public boolean adoptUrl(String url, long expiresAt, long servedDurationMs, MpConfig cfg) {
        return adoptUrl(url, expiresAt, servedDurationMs, cfg, false);
    }

    /**
     * @param estimated {@code servedDurationMs} 是不是<b>算出来的</b>而不是测出来的。
     *                  酷狗 / QQ / LX 脚本这几路都不报「这条链接实际服务了多长」，客户端只能拿
     *                  HEAD 的字节数除以声明码率估一个数，走的是这一档，阈值放宽到
     *                  {@link MpConfig#estimatedStreamDurationRatio} ——
     *                  拿实测的标准去卡估算值，会把完整版误判成试听片段，代价是整房白等一轮
     *                  再跳过一首本来能放的歌。
     */
    public boolean adoptUrl(String url, long expiresAt, long servedDurationMs, MpConfig cfg,
                            boolean estimated) {
        boolean ok = url != null && !url.isBlank()
                && SourceGuard.isAllowedUrl(url, cfg.allowedHosts)
                && !isTrialClip(servedDurationMs, durationMs, cfg, estimated);
        if (ok) {
            this.url = url;
            this.urlDurationMs = servedDurationMs;
            this.urlExpiresAt = expiresAt > 0
                    ? expiresAt
                    : System.currentTimeMillis() + cfg.assumedUrlLifetimeMs;
        } else {
            this.url = null;
            this.urlDurationMs = 0L;
            this.urlExpiresAt = 0L;
        }
        return ok;
    }

    /**
     * Whether a resolved stream is only a preview.
     *
     * <p>Both lengths come from NetEase itself (track length vs. length of the served
     * audio), so this needs no extra request. Unknown values pass: an old client that does
     * not report {@code urlDuration} must still be able to 点歌.
     */
    public static boolean isTrialClip(long servedMs, long trackMs, MpConfig cfg) {
        return isTrialClip(servedMs, trackMs, cfg, false);
    }

    /**
     * 实测路（{@code estimated=false}）的绝对豁免线：这条流本身服务了这么久，就不再判试听。
     *
     * <p>为什么需要：servedMs 与 trackMs 来自<b>两个不同的接口</b> —— servedMs 是取流接口报的
     * 「这条链接实际服务多长」，trackMs 是点歌人上传的曲目元数据时长。同名不同版本（专辑版 /
     * 单曲版 / 重制版）两者可以差出 20% 以上，纯比例判据（0.8）会把一条 3 分 55 秒的完整版
     * 对着 5 分钟的元数据误判成试听。这种误判是<b>确定性的</b>：房间里每个会员解出的都是同一条
     * 流、算出同一个比例、在同一个判定上集体弃权，于是「全员 VIP」的房间照样全层落空，
     * 再被换源救援改成别的平台版本。而真实的试听片段固定是 30/60 秒量级 —— 一条超过 90 秒的流
     * 必然不是试听。90 秒这条线与客户端换源判据 {@code TRIAL_CHECK_MIN_DECLARED_MILLIS} 同源：
     * 短于此的曲目本来就可能就这么长，比例判定照旧兜着。
     *
     * <p>估算路（{@code estimated=true}，阈值更宽的 0.5）<b>不适用</b>这条豁免：估算值本身就
     * 可能偏差大，码率猜错时 90 秒的截断流能被估成三分钟，豁免会把真试听放进房间。
     */
    public static final long TRIAL_CLIP_SERVED_FLOOR_MS = 90_000L;

    public static boolean isTrialClip(long servedMs, long trackMs, MpConfig cfg, boolean estimated) {
        if (servedMs <= 0L || trackMs <= 0L) return false;
        // 实测的流时长超过豁免线：不可能是试听。比例判定对「元数据与实际流版本错位」无能为力，
        // 见方法注释 —— 这是「全员 VIP 房间集体弃权会员曲」的根因。
        if (!estimated && servedMs > TRIAL_CLIP_SERVED_FLOOR_MS) return false;
        double ratio = estimated ? cfg.estimatedStreamDurationRatio : cfg.minStreamDurationRatio;
        return servedMs < trackMs * ratio;
    }

    public boolean hasFreshUrl() {
        return url != null && !url.isBlank()
                && urlExpiresAt > System.currentTimeMillis() + 15_000L;
    }

    public String display() {
        return artist == null || artist.isBlank() ? name : name + " - " + artist;
    }

    public JsonObject toJson(boolean withUrl) {
        JsonObject o = new JsonObject();
        o.addProperty("source", source);
        o.addProperty("songId", songId);
        o.addProperty("name", name);
        o.addProperty("artist", artist);
        o.addProperty("album", album);
        o.addProperty("cover", cover);
        o.addProperty("duration", durationMs);
        if (withUrl && url != null) {
            o.addProperty("url", url);
            o.addProperty("urlExpiresAt", urlExpiresAt);
            if (urlDurationMs > 0) o.addProperty("urlDuration", urlDurationMs);
        }
        return o;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
