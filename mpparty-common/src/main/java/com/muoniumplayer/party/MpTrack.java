package com.muoniumplayer.party;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Artist;
import com.muoniumplayer.core.ncm.music.dto.Music;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一起听通道上流动的一首歌：元数据，以及（仅正在播放的那一首）取流链接。
 *
 * <p>房间共享的是「点歌人自己解析出来的直链」，服务端不解析也不取流，所以一起听<b>不限平台</b>：
 * 只要点歌人本机能解析出直链，其他成员照这条链接下载就能放 —— 播放本身只是「下载 + 按字节头认
 * 容器」，与来源平台无关。
 *
 * <p>身份是 {@link #source} + {@link #songId} 两段。不能只用 songId：QQ 的 12345 和网易云的
 * 12345 是两首不同的歌，只按 id 比会让入队去重误判，更糟的是续签直链时会把 A 平台的链接塞给
 * B 平台的曲目。
 *
 * <p>{@link #songId} 用字符串：网易云是数字，酷狗按文件 hash 定位，服务端也是按字符串校验的。
 */
public final class MpTrack {

    /** 平台令牌（netease / qq / kugou / gd / …）；老服务端不发时按 netease 处理。 */
    public final String source;
    public final String songId;
    public final String name;
    public final String artist;
    public final String album;
    public final String cover;
    public final long durationMs;
    /** 只有当前正在播放的那一首会带链接。 */
    public final String url;
    public final long urlExpiresAt;
    /** 链接实际提供的音频长度；比歌曲短说明是 VIP 试听片段，0 表示未知。 */
    public final long urlDurationMs;
    /** 歌单条目句柄；不是队列条目时为 0。 */
    public final long entryId;
    public final String requesterName;
    /**
     * 这一首来自哪张被整张灌进来的歌单；不是灌单的就是空串。
     *
     * <p>界面靠它把「歌单待播」那一档标出来。没有这个标记的话，一张 100 首的单子灌进来之后
     * 列表里全是「点歌 张三」，谁也不知道后面那几十首是自动排的 —— 更关键的是不知道自己刚点的那首
     * <b>其实插在那批歌前面</b>，看起来就像歌单没生效。</p>
     *
     * <p>老服务端不发这个键，取到 {@code ""}，界面就不画那段副标题，行为和改造前一致。</p>
     */
    public final String playlist;
    /**
     * 这一首的交接计划是不是已经算好了。
     *
     * <p>只对队首那一首有意义 —— 计划描述的是「从正在播的这一首怎么进入它」。歌单列表据此
     * 给这一行加一个标记，否则「分析」这件事在整个界面上完全不可见，用户只能从日志里猜。
     * 老服务端不发这个字段，取到的就是 {@code false}（不显示标记），行为不变。</p>
     */
    public final boolean planReady;

    private MpTrack(String source, String songId, String name, String artist, String album,
                    String cover, long durationMs, String url, long urlExpiresAt,
                    long urlDurationMs, long entryId, String requesterName, boolean planReady,
                    String playlist) {
        this.source = source;
        this.songId = songId;
        this.name = name;
        this.artist = artist;
        this.album = album;
        this.cover = cover;
        this.durationMs = durationMs;
        this.url = url;
        this.urlExpiresAt = urlExpiresAt;
        this.urlDurationMs = urlDurationMs;
        this.entryId = entryId;
        this.requesterName = requesterName;
        this.planReady = planReady;
        this.playlist = playlist == null ? "" : playlist;
    }

    /** 这一首是整张歌单灌进来的吗。 */
    public boolean fromPlaylist() {
        return !playlist.isEmpty();
    }

    public static MpTrack fromJson(JsonObject o) {
        if (o == null) return null;
        String id = MpProto.str(o, "songId", "");
        if (id.isEmpty()) return null;
        return new MpTrack(MpProto.sourceOf(MpProto.str(o, "source", MpProto.SOURCE_NETEASE)), id,
                MpProto.str(o, "name", "未知歌曲"),
                MpProto.str(o, "artist", ""),
                MpProto.str(o, "album", ""),
                MpProto.str(o, "cover", ""),
                MpProto.num(o, "duration", 0L),
                MpProto.str(o, "url", ""),
                MpProto.num(o, "urlExpiresAt", 0L),
                MpProto.num(o, "urlDuration", 0L),
                MpProto.num(o, "entryId", 0L),
                MpProto.str(o, "requesterName", ""),
                MpProto.bool(o, "planReady", false),
                MpProto.str(o, "playlist", ""));
    }

    /**
     * 拼出点歌 / 切歌请求里的 {@code track} 对象。
     *
     * <p>{@code servedDurationMs} 是链接实际提供的音频长度。整个房间共用这一条解析好的 CDN 链接，
     * 所以服务端要靠它识别「非会员解析到的试听片段」。只有网易云会在同一次响应里给出这个值，
     * 其他平台传 0（未知，服务端放行）。
     *
     * <p>{@code songId} 用 {@link Music#getSourceId()} 而不是 {@code getId()}：后者是本机为了缓存
     * 与相等性算出来的哈希，别的客户端拿它既查不到歌也对不上号；前者才是该平台真正的主键
     * （网易云数字 id、酷狗文件 hash、GD 的聚合 id）。
     */
    public static JsonObject toJson(Music song, String streamUrl, long urlExpiresAt, long servedDurationMs) {
        JsonObject o = new JsonObject();
        o.addProperty("source", token(song.getSource()));
        o.addProperty("songId", song.getSourceId());
        o.addProperty("name", song.getName() == null ? "未知歌曲" : song.getName());
        o.addProperty("artist", song.getArtistsName());
        o.addProperty("album", song.getAlbum() == null || song.getAlbum().getName() == null
                ? "" : song.getAlbum().getName());
        o.addProperty("cover", song.getCoverUrl(300));
        o.addProperty("duration", song.getDuration());
        if (streamUrl != null && !streamUrl.trim().isEmpty()) {
            o.addProperty("url", streamUrl);
            o.addProperty("urlExpiresAt", urlExpiresAt);
            if (servedDurationMs > 0) o.addProperty("urlDuration", servedDurationMs);
        }
        return o;
    }

    public long numericId() {
        try {
            return Long.parseLong(songId);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 曲目身份键，必须和服务端 {@code Track.key()} 一致。 */
    public String key() {
        return MpProto.trackKey(source, songId);
    }

    /** 平台令牌 → 本机平台枚举；本机没有这个平台时返回 null。 */
    public MusicPlatform platform() {
        return platformOf(source);
    }

    /** 本机平台枚举 → 协议令牌。枚举名恰好就是小写令牌，所以直接转小写。 */
    public static String token(MusicPlatform platform) {
        return platform == null
                ? MpProto.SOURCE_NETEASE
                : platform.name().toLowerCase(Locale.ROOT);
    }

    public static MusicPlatform platformOf(String source) {
        if (source == null) return null;
        try {
            return MusicPlatform.valueOf(source.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return null;      // 对方装了本机没有的播放源
        }
    }

    /**
     * 用房间下发的元数据构造一首 {@link Music}，<b>不回平台重新拉取</b>。
     *
     * <p>这一点是跨平台的关键：原来的做法是拿 songId 去网易云重查一遍，于是非数字 id（酷狗
     * hash）会被静默丢掉，而 QQ 的数字 id 更糟 —— 会查到一首完全不相干的网易云歌然后照播。
     * 房间已经把歌名/歌手/专辑/封面/时长全带过来了，直接用就行；取流用的是下发的直链
     * （{@code setPartyStreamUrl}），本机根本不需要这个平台的会话。</p>
     *
     * <p>代价：封面缓存/播放历史按 {@code getStableKey()} 落盘，而这里的 id 只能按平台约定重算，
     * 网易云能和本机搜索结果对上，其他平台不一定 —— 那只会让缓存打不中，不影响播放。</p>
     */
    public Music toMusic() {
        MusicPlatform platform = platform();
        String artistNames = artist == null ? "" : artist;
        List<Artist> artistList = new ArrayList<Artist>();
        for (String one : artistNames.split("\\s*[、,/]\\s*")) {
            if (one.trim().isEmpty()) continue;
            artistList.add(new Artist(stableLong("party:artist:" + one.trim()), one.trim(),
                    Collections.<String>emptyList(), Collections.<String>emptyList()));
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown",
                    Collections.<String>emptyList(), Collections.<String>emptyList()));
        }

        String albumName = album == null ? "" : album;
        String coverUrl = cover == null ? "" : cover;
        Album albumObj = new Album(stableLong("party:album:" + albumName), albumName, coverUrl,
                Collections.<String>emptyList());

        Music music = new Music(name, name, "", stableId(platform), artistList,
                Collections.<String>emptyList(), albumObj, durationMs, 0L, 0L,
                Collections.<String>emptyList());
        // 本机没有这个平台时按网易云安放：Music 没有「其他」这一档，而播放走的是下发的直链，
        // 平台字段此时只影响歌词/封面这类附加信息的查询路径。
        music.setSource(platform == null ? MusicPlatform.NETEASE : platform);
        music.setSourceId(songId);
        music.setExternalCoverUrl(coverUrl);
        if (platform == MusicPlatform.KUGOU) {
            // 酷狗取歌词按文件 hash 定位，而 songId 就是 hash。
            music.setKugouHash(songId);
        }
        return music;
    }

    /**
     * 按各平台既有的约定算出 {@code Music.id}。
     *
     * <p>网易云直接用数字 id，这样同一首歌从本机搜索进来和从房间下发进来能得到同一个 id；
     * 其他平台用和 {@code Music} 内部同一套 FNV-1a，尽量对齐（QQ 少了 mid 那一段，对不上，
     * 只影响缓存命中）。</p>
     */
    private long stableId(MusicPlatform platform) {
        if (platform == null || platform == MusicPlatform.NETEASE) {
            try {
                return Long.parseLong(songId);
            } catch (Throwable ignored) {
                return stableLong(MusicPlatform.NETEASE.name() + ':' + songId + ':');
            }
        }
        if (platform == MusicPlatform.KUGOU) return stableLong("kugou:" + songId);
        if (platform == MusicPlatform.GD) return stableLong("gd:" + songId);
        return stableLong(platform.name() + ':' + songId + ':');
    }

    /** 与 {@code Music.stableLong} 同一套 FNV-1a 64 位，取正。 */
    private static long stableLong(String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }

    public String display() {
        return artist == null || artist.trim().isEmpty() ? name : name + " - " + artist;
    }
}
