package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 酷狗音乐的接口客户端。
 *
 * <h3>网关分工</h3>
 * <p>绝大多数端点在 {@code gateway.kugou.com} 上，靠 {@code x-router} 头选出后端服务。
 * 只有三类例外：</p>
 * <ul>
 *   <li><b>免签端点</b>（{@code mobilecdn.kugou.com}、{@code m.kugou.com}、{@code lyrics.kugou.com}）：
 *       歌单详情、歌词。它们不需要签名，也就不会因为签名算法变动而失效。</li>
 *   <li><b>openapi</b>（歌手单曲等少数端点直接连 {@code openapi.kugou.com}）。</li>
 *   <li><b>Web 签名</b>（{@code login-user.kugou.com} 的扫码登录）。</li>
 * </ul>
 *
 * <h3>「参数齐全」比「参数正确」更容易踩</h3>
 * <p>酷狗的失败表现高度同质：HTTP 200 + {@code status:0} + 一个 {@code error_code}。
 * 同一个 {@code 20006} 既可能表示「少了必填参数」，也可能表示「参数值不被接受」。
 * 所以下面每个方法都注释了它真正必需的参数，改动时不要凭直觉删。</p>
 *
 * <h3>失败约定</h3>
 * <p>所有方法失败返回空列表 / {@code null}，不抛异常：一次网络抖动不该把播放器整页打空。
 * 唯一例外是写操作，它们返回布尔值让调用方知道有没有真的写成功。</p>
 */
public final class KuGouMusicApi {

    /** 单次列表请求的上限，防止把一个坏参数放大成几千条的请求。 */
    private static final int MAX_PAGE_SIZE = 300;

    /**
     * 取流接口要求的 {@code clientver}，同时也是 {@code version} 参数。
     *
     * <p>这不是默认的 20489：{@code /v5/url} 只在 tracker 协议版本 11430 下认这套参数，
     * 传 20489 会稳定失败。<b>整个项目里只有这一个端点要改 clientver</b>，
     * 所以它是局部常量而不是 {@link KuGouHttp#CLIENT_VER} 的替换。
     * 标准版与概念版<b>共用</b>这一个值（对照 {@code module/song_url.js}：
     * {@code version} 与 {@code clientver} 都不随 lite 分支变）。</p>
     */
    private static final String STREAM_CLIENT_VER = "11430";

    /** 包级只读出口：auth 取流链路（{@link KuGouAuth}）的 {@code version} 参数用同一个值。 */
    static String streamClientVer() {
        return STREAM_CLIENT_VER;
    }

    /**
     * auth 两个端点（{@code /v1/authorization} 与 {@code /tracker/v5/url}）要求的 {@code clientver}。
     *
     * <p>对照 {@code song_auth.js}/{@code song_url_auth.js}：写死 {@code 11561}，不随身份变。
     * 常量本体与语义说明在 {@link KuGouAuth#AUTH_CLIENT_VER}，这里只是一个别名，
     * 让「取流要用哪套 clientver」的三种取值（11430 / 11561 / 身份默认）在同文件里能对齐着看。</p>
     */
    public static final String AUTH_CLIENT_VER = KuGouAuth.AUTH_CLIENT_VER;

    /**
     * 老 {@code /i/v2/} 兜底链路的 {@code pid}。
     *
     * <p>与 {@link KuGouProfile#streamPid()} 不是一回事：那是现代 {@code /v5/url} 上随身份变的值，
     * 这个是老协议里恒为 2 的固定字段，两种客户端都用它。</p>
     */
    private static final String LEGACY_PID = "2";

    /**
     * 交付校验上线时的酷狗音频缓存世代标记。
     *
     * <p>校验上线之前落盘的酷狗音频缓存可能装着<b>另一首歌</b>：上游对取不到的 hash 会静默交付别人的
     * 文件，而缓存名只由「曲目 + 音质」决定，于是错文件会被一直命中，光修取流并不生效。
     * 播放侧把这个标记拼进缓存名，旧文件因此自然失配，之后由容量兜底回收。</p>
     *
     * <p><b>只有酷狗需要它</b>：其它音源没有这种静默换歌的行为，不该跟着丢缓存。
     * 改动取流判据本身不必动这个值，只有在又发现一批历史缓存不可信时才抬一档。</p>
     */
    public static final String AUDIO_CACHE_EPOCH = "kg2";

    /** {@code everyday_song_recommend} 的 {@code x-router}。 */
    private static final String ROUTER_EVERYDAY = "everydayrec.service.kugou.com";

    private KuGouMusicApi() {
    }

    // ── 数据模型 ────────────────────────────────────────────────────────────

    /**
     * 一首酷狗曲目。
     *
     * <p>三个 hash 分别对应标准 / 320 / 无损，取流时<b>必须配同档的音质参数</b>：
     * 拿无损的 hash 去要 128 会拿到一个奇怪的结果，拿 128 的 hash 去要无损则直接失败。
     * 更高的档位（高品无损、臻品系列、母带）不在那三个字段里，走
     * {@link #hashesByQuality} 与 {@link #privilegeLite(List)}。</p>
     */
    public static final class Track {
        public String hash = "";
        public String hash320 = "";
        public String hashLossless = "";
        public String name = "";
        public String artist = "";
        public String album = "";
        public String albumId = "";
        public String albumAudioId = "";
        public String audioId = "";
        public String mixSongId = "";
        /** 云歌单里的行标识，删曲要用它。列表端点不一定给。 */
        public String fileId = "";
        public long durationMillis;
        /** 带 {@code {size}} 占位符的封面模板，取用时替换成具体尺寸。 */
        public String coverTemplate = "";
        /** 上游 {@code privilege}：0/8 免费，10 需要会员。 */
        public int privilege;
        /** {@code privilege == 10} 的便捷判定。 */
        public boolean vip;
        /**
         * 按档位记下的 hash（{@link KuGouQuality#key()} → hash）。
         *
         * <p>只有三个来源会往里写：搜索响应里的 {@code Res.Hash}（母带）、云歌单的
         * {@code relate_goods[]}（各档 bitrate + hash），以及 {@link #privilegeLite(List)}
         * 的探测结果。列表端点给不到的档位<b>不在这里</b>，此时 {@link #hashFor} 返回空串、
         * 阶梯自动跳过它——而不是拿一个低档的 hash 去冒充高档，那正是 {@code status=2} 的成因。</p>
         */
        public final Map<String, String> hashesByQuality = new LinkedHashMap<String, String>();

        /**
         * {@link KuGouQuality#key()} → 该档是否需要会员（来自 {@code privilegeLite} 的
         * {@code relate_goods} 解析，见 {@link Privilege#vipLockedByQuality}）。
         *
         * <p>非会员账号上为 true 的档会被 {@link #songUrl(Track, KuGouQuality)} 的阶梯跳过：
         * 反正上游必拒，先跳过能省一次注定 {@code status=2} 的请求。</p>
         */
        public final Map<String, Boolean> vipLockedByQuality = new LinkedHashMap<String, Boolean>();

        /**
         * 取流时该传的 {@code album_audio_id}。
         *
         * <p>只认 {@code MixSongID} 那一套 ID 空间，取不到就返回空串、由调用方传 {@code 0}。
         * 实测传 {@code 0} 会让服务端回落到按 {@code hash} 解析，交付的正是点的那首歌；而传一个
         * <b>不属于这套空间</b>的 ID（例如搜索端点的 {@code Audioid}，其实是老的 {@code audio_id}）
         * 会让它按那个 ID 去解析文件，交回<b>另一首歌</b>的完整文件。</p>
         */
        public String requestAlbumAudioId() {
            String mix = KuGouSign.nonNull(mixSongId).trim();
            return mix.isEmpty() ? KuGouSign.nonNull(albumAudioId).trim() : mix;
        }
    }

    /**
     * 一个酷狗歌单 / 榜单 / 专辑 / 歌手条目。
     *
     * <p>{@link #id} 是「取曲目时该用哪个端点」的主键，{@link #kind} 说明它是什么。
     * 两者一起决定 {@code KuGouMusicService} 怎么拼 {@code platformPlaylistId}。</p>
     */
    public static final class Playlist {
        /** 主键。歌单是 gid 或 specialid，榜单是 rankid，专辑是 albumid，歌手是 authorid。 */
        public String id = "";
        public String name = "";
        public String coverTemplate = "";
        public String description = "";
        public int trackCount;
        /** 兼容既有的「这是榜单」判定，等价于 {@code "rank".equals(kind)}。 */
        public boolean rank;
        /** {@code playlist} / {@code rank} / {@code album} / {@code artist}。 */
        public String kind = KIND_PLAYLIST;
        /** 用户歌单的数字 {@code listid}，只有账号歌单有；增删曲与删除歌单要它。 */
        public String listId = "";
        /** 云端歌单主键 {@code global_collection_id}，没有时为空。 */
        public String collectionId = "";
        /** 歌单发布者昵称，用于副标题。 */
        public String creator = "";
    }

    public static final String KIND_PLAYLIST = "playlist";
    public static final String KIND_RANK = "rank";
    public static final String KIND_ALBUM = "album";
    public static final String KIND_ARTIST = "artist";

    /** 取流结果。 */
    public static final class Stream {
        public final String url;
        public final String format;

        Stream(String url, String format) {
            this.url = url;
            this.format = format;
        }
    }

    /** 会员状态。{@code vipType} 非 0 才有高音质授权。 */
    public static final class VipStatus {
        public final int vipType;
        public final boolean vip;
        /** 到期时间原文（形如 {@code 2026-10-06 08:31:40}）；不保证可解析，只用于展示。 */
        public final String expiresAt;

        VipStatus(int vipType, boolean vip, String expiresAt) {
            this.vipType = vipType;
            this.vip = vip;
            this.expiresAt = expiresAt == null ? "" : expiresAt;
        }
    }

    /** 扫码登录的一次轮询结果。 */
    public static final class QrStatus {
        /** 上游原始码：0 过期、1 等待扫码、2 待确认、4 登录成功。保持不透明，不改名成语义值。 */
        public final int code;
        public final String token;
        public final String userId;
        public final String nickname;
        public final String avatarUrl;

        QrStatus(int code, String token, String userId, String nickname, String avatarUrl) {
            this.code = code;
            this.token = token;
            this.userId = userId;
            this.nickname = nickname;
            this.avatarUrl = avatarUrl;
        }
    }

    /** 扫码登录的会话：轮询用的 key、可直接渲染的二维码图（上游给的 base64 PNG），以及签发身份。 */
    public static final class QrSession {
        public final String key;
        /** 形如 {@code data:image/png;base64,...}；上游没给图时为空串。 */
        public final String imageDataUrl;
        /**
         * 这张二维码是给哪个客户端签发的。
         *
         * <p>必须跟着会话走而不是「到时候读当前身份」：一张概念版的码换成标准版身份落盘，
         * 之后每次取流都会以错的盐值签名，表现是登录成功但什么都放不出来。</p>
         */
        public final KuGouProfile profile;

        QrSession(String key, String imageDataUrl) {
            this(key, imageDataUrl, KuGouProfile.STANDARD);
        }

        QrSession(String key, String imageDataUrl, KuGouProfile profile) {
            this.key = key == null ? "" : key;
            this.imageDataUrl = imageDataUrl == null ? "" : imageDataUrl;
            this.profile = profile == null ? KuGouProfile.STANDARD : profile;
        }
    }

    // ── 搜索 ────────────────────────────────────────────────────────────────

    /**
     * 搜索单曲。
     *
     * <p><b>{@code plat=2} 是必需参数，不是可选项。</b>不带它 {@code /v3/search/song} 稳定返回
     * {@code error_code=152 Parameter Error}，而错误信息不会告诉你少了哪个参数。</p>
     */
    public static List<Track> search(String keyword, int limit) {
        return search(keyword, limit, 1);
    }

    public static List<Track> search(String keyword, int limit, int page) {
        List<Track> result = new ArrayList<Track>();
        if (KuGouSign.isBlank(keyword)) return result;

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("keyword", keyword.trim());
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("plat", KuGouHttp.PLAT_ANDROID);
        params.put("platform", "AndroidFilter");
        params.put("albumhide", "0");
        params.put("iscorrection", "1");
        params.put("nocollect", "0");
        params.put("showtype", "1");
        params.put("userid", KuGouDevice.isLoggedIn() ? KuGouDevice.userId() : "0");

        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/v3/search/song", params,
                KuGouHttp.Sign.ANDROID, router("complexsearch.kugou.com"));
        JsonArray lists = array(object(root, "data"), "lists");
        if (lists == null) return result;
        for (JsonElement element : lists) {
            if (element == null || !element.isJsonObject()) continue;
            Track track = parseSearchTrack(element.getAsJsonObject());
            if (track != null) result.add(track);
        }
        return result;
    }

    /** 搜索公开歌单。返回项的 {@code gid} 是云端主键，取曲目要走 {@link #collectionTracks}。 */
    public static List<Playlist> searchPlaylists(String keyword, int limit) {
        List<Playlist> result = new ArrayList<Playlist>();
        if (KuGouSign.isBlank(keyword)) return result;

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("keyword", keyword.trim());
        params.put("page", "1");
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("plat", KuGouHttp.PLAT_ANDROID);
        params.put("platform", "AndroidFilter");
        params.put("albumhide", "0");
        params.put("iscorrection", "1");
        params.put("nocollect", "0");

        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/v1/search/special", params,
                KuGouHttp.Sign.ANDROID, router("complexsearch.kugou.com"));
        JsonArray lists = array(object(root, "data"), "lists");
        if (lists == null) return result;
        for (JsonElement element : lists) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Playlist playlist = new Playlist();
            playlist.collectionId = text(item, "gid");
            playlist.id = KuGouSign.isBlank(playlist.collectionId) ? text(item, "specialid") : playlist.collectionId;
            playlist.name = firstText(item, "specialname", "name");
            playlist.coverTemplate = firstText(item, "img", "imgurl", "pic", "cover");
            playlist.description = firstText(item, "intro", "nickname");
            playlist.creator = text(item, "nickname");
            playlist.trackCount = integer(item, "song_count", "songcount", "count", "musiccount");
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    /**
     * 酷狗搜索的内容类型。
     *
     * <p>取值与路径来自参考实现的 {@code SearchType}（{@code interface.d.ts}：
     * {@code song | special | lyric | album | author | mv}）。<b>只有单曲走 {@code /v3}</b>，
     * 其余都是 {@code /v1/search/<类型>}，而且都挂在同一个 {@code x-router} 上。</p>
     *
     * <p>没有「用户」这一类：酷狗的搜索端点不提供按用户搜（参考实现里也没有），
     * 所以宿主那几个「搜用户」的入口在酷狗下应当隐藏而不是给空列表。</p>
     */
    public enum SearchType {
        /** 单曲。走 {@code /v3}，是本类里唯一必须带 {@code plat=2} 的类型。 */
        SONG("song", "/v3/search/song", "歌曲"),
        PLAYLIST("special", "/v1/search/special", "歌单"),
        ALBUM("album", "/v1/search/album", "专辑"),
        ARTIST("author", "/v1/search/author", "歌手"),
        /** 按歌词搜：返回的条目结构与单曲一致，可以直接取来播。 */
        LYRIC("lyric", "/v1/search/lyric", "歌词"),
        MV("mv", "/v1/search/mv", "MV");

        private final String key;
        private final String path;
        private final String label;

        SearchType(String key, String path, String label) {
            this.key = key;
            this.path = path;
            this.label = label;
        }

        /** 拼进 {@code /v1/search/<类型>} 的字面量。 */
        public String key() {
            return key;
        }

        public String path() {
            return path;
        }

        public String label() {
            return label;
        }
    }

    /** 搜索专辑。返回项的 {@code kind} 是 {@link #KIND_ALBUM}，取曲目走 {@link #albumSongs}。 */
    public static List<Playlist> searchAlbums(String keyword, int limit) {
        return searchPlaylists(SearchType.ALBUM, keyword, limit);
    }

    /** 搜索歌手。返回项的 {@code kind} 是 {@link #KIND_ARTIST}，取曲目走 {@link #artistSongs}。 */
    public static List<Playlist> searchArtists(String keyword, int limit) {
        return searchPlaylists(SearchType.ARTIST, keyword, limit);
    }

    /** 按歌词搜歌：条目结构与单曲一致，直接能拿去播。 */
    public static List<Track> searchLyricSongs(String keyword, int limit) {
        return searchSongs(SearchType.LYRIC, keyword, limit);
    }

    /**
     * 按类型搜歌单 / 专辑 / 歌手。
     *
     * <p>三类响应的条目结构基本一致（{@code lists[]} + 名字 + 封面 + 数量），所以共用一个解析器。
     * {@link SearchType#SONG} 与 {@link SearchType#LYRIC} 的条目是曲目而不是歌单，
     * 它们走 {@link #searchSongs(SearchType, String, int)}。</p>
     */
    public static List<Playlist> searchPlaylists(SearchType type, String keyword, int limit) {
        List<Playlist> result = new ArrayList<Playlist>();
        if (type == null || KuGouSign.isBlank(keyword)) return result;

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("keyword", keyword.trim());
        params.put("page", "1");
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("plat", KuGouHttp.PLAT_ANDROID);
        params.put("platform", "AndroidFilter");
        params.put("albumhide", "0");
        params.put("iscorrection", "1");
        params.put("nocollect", "0");
        if (type == SearchType.ALBUM) {
            // 专辑搜索比别的类型多三个开关：category=1 是全部分类，sorttype=0 按相关性，
            // searchsong=0 表示「不要连带搜歌」。缺任一个上游会少回一半结果，且不报错。
            params.put("category", "1");
            params.put("sorttype", "0");
            params.put("searchsong", "0");
        }

        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, type.path(), params,
                KuGouHttp.Sign.ANDROID, router("complexsearch.kugou.com"));
        JsonArray lists = array(object(root, "data"), "lists", "info");
        if (lists == null) return result;
        for (JsonElement element : lists) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Playlist playlist = new Playlist();
            if (type == SearchType.ALBUM) {
                playlist.id = firstText(item, "albumid", "album_id", "Albid", "id");
                playlist.kind = KIND_ALBUM;
                playlist.name = firstText(item, "albumname", "album_name", "name");
                playlist.coverTemplate = firstText(item, "imgurl", "cover", "sizable_cover", "img");
                playlist.creator = firstText(item, "singername", "author_name", "artist_name");
                playlist.trackCount = integer(item, "songcount", "song_count", "total");
                playlist.description = firstText(item, "intro", "desc");
            } else if (type == SearchType.ARTIST) {
                playlist.id = firstText(item, "authorid", "author_id", "singerid", "id");
                playlist.kind = KIND_ARTIST;
                playlist.name = firstText(item, "authorname", "author_name", "singername", "name");
                playlist.coverTemplate = firstText(item, "authorpic", "img", "sizable_avatar", "avatar", "cover");
                playlist.trackCount = integer(item, "album_count", "song_count", "fansnums");
                playlist.description = firstText(item, "intro", "desc");
            } else {
                playlist.collectionId = text(item, "gid");
                playlist.id = KuGouSign.isBlank(playlist.collectionId)
                        ? firstText(item, "specialid", "id") : playlist.collectionId;
                playlist.kind = KIND_PLAYLIST;
                playlist.name = firstText(item, "specialname", "name");
                playlist.coverTemplate = firstText(item, "img", "imgurl", "pic", "cover");
                playlist.description = firstText(item, "intro", "nickname");
                playlist.creator = text(item, "nickname");
                playlist.trackCount = integer(item, "song_count", "songcount", "count", "musiccount");
            }
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    /** 按歌词搜歌：条目结构与单曲一致，直接能拿去播。 */
    public static List<Track> searchSongs(SearchType type, String keyword, int limit) {
        List<Track> result = new ArrayList<Track>();
        if (type == null || KuGouSign.isBlank(keyword)) return result;

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("keyword", keyword.trim());
        params.put("page", "1");
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("plat", KuGouHttp.PLAT_ANDROID);
        params.put("platform", "AndroidFilter");
        params.put("albumhide", "0");
        params.put("iscorrection", "1");
        params.put("nocollect", "0");
        params.put("showtype", "1");

        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, type.path(), params,
                KuGouHttp.Sign.ANDROID, router("complexsearch.kugou.com"));
        JsonArray lists = array(object(root, "data"), "lists", "info");
        if (lists == null) return result;
        for (JsonElement element : lists) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            // 歌词搜索的条目是老的小写命名（hash/songname/singername），与 /v3 那套
            // PascalCase 不同，所以两套解析器都试一遍，先命中的算数。
            Track track = parseSearchTrack(item);
            if (track == null) track = parseFlatTrack(item);
            if (track == null) track = parseLayeredTrack(item);
            if (track != null) result.add(track);
        }
        return result;
    }

    /**
     * 歌手榜单（按类型/性别筛）。
     *
     * <p>端点在 {@code mobilecdn} 的 {@code /ocean/v6/singer/list} 上，条目数组叫
     * {@code data.info}。它回答的是「有哪些歌手」，与按关键词搜歌手是两条链路。</p>
     *
     * @param type 0 全部、1 男、2 女、3 组合
     * @param sexType 上游的性别细分开关，0 表示不额外筛
     */
    public static List<Playlist> singerList(int type, int sexType, int limit) {
        List<Playlist> result = new ArrayList<Playlist>();
        Map<String, String> params = KuGouHttp.params();
        params.put("hotsize", String.valueOf(clampPageSize(limit <= 0 ? 50 : limit)));
        params.put("musician", "0");
        params.put("sextype", String.valueOf(Math.max(0, sexType)));
        params.put("showtype", "2");
        params.put("type", String.valueOf(Math.max(0, type)));
        JsonObject root = KuGouHttp.get(KuGouHttp.MOBILE_CDN, "/ocean/v6/singer/list", params,
                KuGouHttp.Sign.NONE);
        JsonArray info = array(object(root, "data"), "info", "lists");
        if (info == null) return result;
        for (JsonElement element : info) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Playlist playlist = new Playlist();
            playlist.kind = KIND_ARTIST;
            playlist.id = firstText(item, "authorid", "author_id", "id");
            playlist.name = firstText(item, "authorname", "singername", "name");
            playlist.coverTemplate = firstText(item, "headpic", "img", "avatar", "cover");
            playlist.trackCount = integer(item, "album_count", "songcount");
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    /**
     * 新歌专辑榜（发现页用）。
     *
     * <p>{@code POST /musicadservice/v1/mobile_newalbum_sp}，条目在 {@code data.info.list}，
     * 与别的专辑端点又差一层。{@code withpriv=1} 要带上：不带时响应里没有权限信息，
     * 会员专集会照常显示但点了放不出。</p>
     */
    public static List<Playlist> topAlbums(int page, int limit) {
        List<Playlist> result = new ArrayList<Playlist>();
        JsonObject body = new JsonObject();
        body.addProperty("apiver", 20);
        body.addProperty("page", Math.max(1, page));
        body.addProperty("pagesize", clampPageSize(limit));
        body.addProperty("withpriv", 1);
        if (KuGouDevice.isLoggedIn()) {
            body.addProperty("token", KuGouDevice.token());
            body.addProperty("userid", KuGouDevice.userId());
        } else {
            body.addProperty("token", "");
        }
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/musicadservice/v1/mobile_newalbum_sp",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID, null);
        JsonArray list = array(object(object(root, "data"), "info"), "list", "songs");
        if (list == null) return result;
        for (JsonElement element : list) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Playlist playlist = new Playlist();
            playlist.kind = KIND_ALBUM;
            playlist.id = firstText(item, "album_id", "albumid", "id");
            playlist.name = firstText(item, "album_name", "albumname", "name");
            playlist.coverTemplate = firstText(item, "sizable_cover", "cover", "imgurl");
            playlist.creator = firstText(item, "author_name", "singername");
            playlist.description = firstText(item, "intro", "desc");
            playlist.trackCount = integer(item, "song_count", "songcount");
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    /**
     * 新歌榜（按榜型）。
     *
     * <p>{@code rank_id} 与 {@code /api/v3/rank/list} 那套 {@code rankid} 不是同一套编号：
     * 这条端点用的是「新歌发布容器」的 {@code rank_id}（默认 {@code 21608}），
     * 传榜单列表里的 id 会拿到空结果而不是报错。</p>
     */
    public static List<Track> newSongs(String rankId, int page, int limit) {
        List<Track> result = new ArrayList<Track>();
        JsonObject body = new JsonObject();
        body.addProperty("rank_id", number(KuGouSign.isBlank(rankId) ? "21608" : rankId.trim()));
        body.addProperty("page", Math.max(1, page));
        body.addProperty("pagesize", clampPageSize(limit));
        if (KuGouDevice.isLoggedIn()) body.addProperty("userid", KuGouDevice.userId());
        body.add("tags", new JsonArray());
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY,
                "/musicadservice/container/v1/newsong_publish", KuGouHttp.deviceParams(),
                body.toString(), KuGouHttp.Sign.ANDROID, null);
        JsonArray list = array(object(object(root, "data"), "recommend"), "list");
        if (list == null) list = array(object(root, "data"), "list", "info");
        if (list == null) return result;
        for (JsonElement element : list) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Track track = parseFlatTrack(item);
            if (track == null) track = parseAudioInfoTrack(item);
            if (track == null) track = parseLayeredTrack(item);
            if (track != null) result.add(track);
        }
        return result;
    }

    /**
     * 热搜榜。
     *
     * <p>{@code navid=1} 是「热搜榜」这个导航项，其它取值对应别的榜单（例如歌手榜）。
     * 匿名可用。</p>
     */
    public static List<String> hotSearch() {
        List<String> result = new ArrayList<String>();
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("navid", "1");
        params.put("plat", KuGouHttp.PLAT_ANDROID);
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/api/v3/search/hot_tab", params,
                KuGouHttp.Sign.ANDROID, router("msearch.kugou.com"));
        JsonObject data = object(root, "data");
        JsonArray lists = array(data, "list");
        if (lists == null) return result;
        for (JsonElement element : lists) {
            if (element == null || !element.isJsonObject()) continue;
            JsonArray keywords = array(element.getAsJsonObject(), "keywords");
            if (keywords == null) continue;
            for (JsonElement keyword : keywords) {
                if (keyword == null || !keyword.isJsonObject()) continue;
                String word = text(keyword.getAsJsonObject(), "keyword");
                // 热搜里会混入「占位词」——上游用它做运营位，直接搜是空结果。
                if (!KuGouSign.isBlank(word)) result.add(word.trim());
            }
        }
        return result;
    }

    /** 搜索联想词。 */
    public static List<String> searchSuggest(String keyword) {
        List<String> result = new ArrayList<String>();
        if (KuGouSign.isBlank(keyword)) return result;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("keyword", keyword.trim());
        params.put("AlbumTipCount", "10");
        params.put("CorrectTipCount", "10");
        params.put("MVTipCount", "10");
        params.put("MusicTipCount", "10");
        params.put("radiotip", "1");
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/v2/getSearchTip", params,
                KuGouHttp.Sign.ANDROID, router("searchtip.kugou.com"));
        JsonArray list = pickArray(root, "data");
        if (list == null) return result;
        for (JsonElement element : list) {
            if (element == null || !element.isJsonObject()) continue;
            JsonArray records = array(element.getAsJsonObject(), "RecordDatas");
            if (records == null) continue;
            for (JsonElement record : records) {
                if (record == null || !record.isJsonObject()) continue;
                String hint = text(record.getAsJsonObject(), "HintInfo");
                if (!KuGouSign.isBlank(hint)) result.add(hint.trim());
            }
        }
        return result;
    }

    // ── 歌单 / 榜单 ─────────────────────────────────────────────────────────

    /**
     * 首页推荐歌单。
     *
     * <p>走 {@code special_recommend}：body 里 {@code special_recommend} 必须是
     * <b>JSON 对象</b>而不是字符串（传字符串会得到 nginx 500），同时 {@code key} 要按 body 里的
     * 参数算——两者不匹配会得到 {@code 200104}。</p>
     */
    public static List<Playlist> recommendPlaylists(int limit) {
        List<Playlist> result = new ArrayList<Playlist>();
        int pageSize = clampPageSize(limit <= 0 ? 12 : limit);
        long clientTime = System.currentTimeMillis() / 1000L;

        JsonObject specialRecommend = new JsonObject();
        specialRecommend.addProperty("withtag", 1);
        specialRecommend.addProperty("withsong", 1);
        specialRecommend.addProperty("sort", 1);
        specialRecommend.addProperty("ugc", 1);
        specialRecommend.addProperty("is_selected", 0);
        specialRecommend.addProperty("withrecommend", 1);
        specialRecommend.addProperty("area_code", 1);
        specialRecommend.addProperty("categoryid", 0);

        JsonObject body = new JsonObject();
        body.addProperty("appid", number(KuGouHttp.APP_ID));
        body.addProperty("mid", KuGouDevice.mid());
        body.addProperty("clientver", number(KuGouHttp.CLIENT_VER));
        body.addProperty("platform", "android");
        body.addProperty("clienttime", clientTime);
        body.addProperty("userid", 0);
        body.addProperty("module_id", 1);
        body.addProperty("page", 1);
        body.addProperty("pagesize", pageSize);
        body.addProperty("key", KuGouSign.paramsKey(String.valueOf(clientTime)));
        body.add("special_recommend", specialRecommend);
        body.addProperty("req_multi", 1);
        body.addProperty("retrun_min", 5);
        body.addProperty("return_special_falg", 1);

        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/v2/special_recommend",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID,
                router("specialrec.service.kugou.com"));
        if (!isOk(root)) return result;

        JsonArray specials = array(object(root, "data"), "special_list");
        if (specials == null) return result;
        for (JsonElement element : specials) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Playlist playlist = new Playlist();
            playlist.id = firstText(item, "global_collection_id", "specialid");
            playlist.collectionId = text(item, "global_collection_id");
            playlist.name = firstText(item, "specialname", "name");
            playlist.coverTemplate = firstText(item, "flexible_cover", "imgurl", "img", "pic");
            playlist.description = firstText(item, "intro", "nickname");
            playlist.creator = text(item, "nickname");
            playlist.trackCount = integer(item, "song_count", "songcount", "count", "percount");
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    /**
     * 榜单列表。
     *
     * <p>走免签的 mobilecdn：它一次给全 57 个榜单且字段稳定，网关那版还要处理
     * {@code withsong} 的结构差异，没有收益。</p>
     */
    public static List<Playlist> rankList() {
        List<Playlist> result = new ArrayList<Playlist>();
        Map<String, String> params = KuGouHttp.params();
        params.put("version", "9108");
        params.put("plat", KuGouHttp.PLAT_ANDROID);
        params.put("showtype", "2");
        params.put("parentid", "0");
        params.put("apiver", "6");
        params.put("area_code", "1");
        params.put("withsong", "1");
        JsonObject root = KuGouHttp.get(KuGouHttp.MOBILE_CDN, "/api/v3/rank/list", params, KuGouHttp.Sign.NONE);
        JsonArray info = array(object(root, "data"), "info");
        if (info == null) return result;
        for (JsonElement element : info) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Playlist playlist = new Playlist();
            playlist.id = text(item, "rankid");
            playlist.name = text(item, "rankname");
            playlist.coverTemplate = firstText(item, "img_9", "img_cover", "album_img_9", "imgurl", "banner_9");
            playlist.description = firstText(item, "update_frequency", "intro");
            playlist.trackCount = integer(item, "songcount", "count");
            playlist.rank = true;
            playlist.kind = KIND_RANK;
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    /**
     * 榜单曲目。
     *
     * <p>这条端点把歌曲信息全部塞进 {@code audio_info}（{@code hash_128/hash_320/hash_flac}），
     * <b>没有</b>搜索接口那种扁平 {@code hash} 字段；歌名也在 {@code audio_name}。按搜索的字段名解析会得到一堆空对象。</p>
     */
    public static List<Track> rankSongs(String rankId, int limit) {
        return rankSongs(rankId, limit, 1);
    }

    public static List<Track> rankSongs(String rankId, int limit, int page) {
        List<Track> result = new ArrayList<Track>();
        if (KuGouSign.isBlank(rankId)) return result;

        JsonObject body = new JsonObject();
        body.addProperty("show_portrait_mv", 1);
        body.addProperty("show_type_total", 1);
        body.addProperty("filter_original_remarks", 1);
        body.addProperty("area_code", 1);
        body.addProperty("pagesize", clampPageSize(limit));
        body.addProperty("rank_cid", 0);
        body.addProperty("type", 1);
        body.addProperty("page", Math.max(1, page));
        body.addProperty("rank_id", rankId.trim());

        Map<String, String> headers = router(null);
        // 这个端点不接受默认的 kg-tid，必须显式给 369，否则参数校验过不去。
        headers.put("kg-tid", "369");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/openapi/kmr/v2/rank/audio",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID, headers);

        JsonArray songList = array(object(root, "data"), "songlist");
        if (songList == null) return result;
        for (JsonElement element : songList) {
            if (element == null || !element.isJsonObject()) continue;
            Track track = parseAudioInfoTrack(element.getAsJsonObject());
            if (track != null) result.add(track);
        }
        return result;
    }

    /** 精选歌单曲目（{@code specialid}），走免签的老端点。 */
    public static List<Track> playlistTracks(String specialId, int limit) {
        List<Track> result = new ArrayList<Track>();
        if (KuGouSign.isBlank(specialId)) return result;
        Map<String, String> params = KuGouHttp.params();
        params.put("specialid", specialId.trim());
        params.put("page", "1");
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("version", "9108");
        params.put("with_res_tag", "0");
        JsonObject root = KuGouHttp.get(KuGouHttp.MOBILE_CDN, "/api/v3/special/song", params, KuGouHttp.Sign.NONE);
        JsonArray info = array(object(root, "data"), "info");
        if (info == null) return result;
        for (JsonElement element : info) {
            if (element == null || !element.isJsonObject()) continue;
            Track track = parseLegacyTrack(element.getAsJsonObject());
            if (track != null) result.add(track);
        }
        return result;
    }

    /**
     * 云端歌单曲目（{@code global_collection_id}）。
     *
     * <p>公开歌单、用户歌单、搜索结果里的歌单都用这个主键。曲目字段比老端点全，
     * 但音质档位藏在 {@code relate_goods} 数组里而不是扁平的 {@code 320hash}/{@code sqhash}。</p>
     */
    public static List<Track> collectionTracks(String globalCollectionId, int limit) {
        return collectionTracks(globalCollectionId, limit, 1);
    }

    public static List<Track> collectionTracks(String globalCollectionId, int limit, int page) {
        List<Track> result = new ArrayList<Track>();
        if (KuGouSign.isBlank(globalCollectionId)) return result;

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("area_code", "1");
        params.put("begin_idx", String.valueOf((Math.max(1, page) - 1) * clampPageSize(limit)));
        params.put("plat", "1");
        params.put("type", "1");
        params.put("mode", "1");
        params.put("personal_switch", "1");
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("global_collection_id", globalCollectionId.trim());

        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY,
                "/pubsongs/v2/get_other_list_file_nofilt", params, KuGouHttp.Sign.ANDROID);
        JsonArray songs = array(object(root, "data"), "songs", "info", "list");
        if (songs == null) return result;
        for (JsonElement element : songs) {
            if (element == null || !element.isJsonObject()) continue;
            Track track = parseCollectionTrack(element.getAsJsonObject());
            if (track != null) result.add(track);
        }
        return result;
    }

    // ── 推荐 / 每日 / 私人 FM ───────────────────────────────────────────────

    /**
     * 每日推荐。
     *
     * <p>不需要登录，匿名也回 30 首。{@code platform=ios} 不是笔误：android 分支返回的是另一套
     * 结构（卡片流而不是 {@code song_list}），解析成本更高收益更低。</p>
     */
    public static List<Track> dailyRecommend(String date) {
        List<Track> result = new ArrayList<Track>();
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("platform", "ios");
        if (!KuGouSign.isBlank(date)) params.put("date", date.trim());
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/everyday_song_recommend", params,
                KuGouHttp.Sign.ANDROID, router(ROUTER_EVERYDAY));
        if (!isOk(root)) return result;
        JsonArray songList = array(object(root, "data"), "song_list");
        if (songList == null) return result;
        for (JsonElement element : songList) {
            if (element == null || !element.isJsonObject()) continue;
            Track track = parseFlatTrack(element.getAsJsonObject());
            if (track != null) result.add(track);
        }
        return result;
    }

    /**
     * 私人 FM（手机端的「猜你喜欢」）。<b>需要登录</b>：匿名会得到 {@code error_code=200101}。
     *
     * <p>这是 {@link #requestFm(FmRequest)} 的便捷入口，行为与旧版一致：默认模式、默认歌曲池。</p>
     *
     * @param excludeHash 上一条的 hash，用来避免连续推同一首；可为空
     */
    public static List<Track> personalFm(int limit, String excludeHash) {
        FmRequest request = new FmRequest();
        request.limit = limit;
        request.excludeHash = excludeHash;
        return requestFm(request).tracks;
    }

    /** FM 推荐的一次完整结果：曲目之外还要带回游标与剩余量。 */
    public static final class FmResult {
        public final List<Track> tracks;
        /**
         * 上游给的推荐游标。
         *
         * <p>做「不感兴趣 / 红心」这类反馈上报时<b>必须原样带回去</b>，它标识的是「刚才推的哪一批」。
         * 不带也能发出去，但反馈会打到错误的条目上——这类错是静默的。</p>
         */
        public final String curMark;
        /** 已标记「不感兴趣」的曲目数，用于在 UI 上解释为什么推荐变稀。 */
        public final int garbageCount;
        /** 本次这批还剩多少没推完。 */
        public final int remainCount;

        FmResult(List<Track> tracks, String curMark, int garbageCount, int remainCount) {
            this.tracks = tracks == null ? new ArrayList<Track>() : tracks;
            this.curMark = KuGouSign.nonNull(curMark);
            this.garbageCount = garbageCount;
            this.remainCount = remainCount;
        }
    }

    /**
     * FM 模式。
     *
     * <p>取值来自参考实现的 {@code FmMode} 类型（{@code interface.d.ts}）：只有这三个，
     * <b>传别的值不会报错</b>，上游会静默按 {@code normal} 处理，所以宿主的多种 FM 模式要在这里
     * 显式收敛（映射在 {@code KuGouMusicService}）。</p>
     */
    public enum FmMode {
        /** 常规推荐（默认）。 */
        NORMAL("normal", "推荐"),
        /** 小众 / 冷门。 */
        SMALL("small", "小众"),
        /** 30 秒高潮片段。 */
        PEAK("peak", "高潮片段");

        private final String key;
        private final String label;

        FmMode(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public static FmMode fromKey(String value) {
            String key = KuGouSign.nonNull(value).trim().toLowerCase(Locale.ROOT);
            for (FmMode mode : values()) {
                if (mode.key.equals(key)) return mode;
            }
            return NORMAL;
        }
    }

    /** 一次 FM 请求的全部可选维度。字段默认值就是「与旧版行为一致」。 */
    public static final class FmRequest {
        /** 推荐模式。 */
        public FmMode mode = FmMode.NORMAL;
        /** 歌曲池：0/1/2 对应上游的 Alpha/Beta/Gamma，换池等于换一批推荐来源。 */
        public int songPoolId;
        /** 上一条的 hash，避免连续推同一首。 */
        public String excludeHash = "";
        /** 上一条的 {@code album_audio_id}，与 {@link #excludeHash} 配对上报。 */
        public String excludeAlbumAudioId = "";
        /** 上一条实际收听了多少毫秒；上游用它判断你是真听完还是切得快。 */
        public long lastPlayMillis;
        /** 是否允许重复推荐（{@code is_overplay}）。听完一批要重听时打开。 */
        public boolean allowRepeat;
        public int limit;
        /** 上一次 {@link FmResult#curMark} 带回来的游标；做反馈上报时要回传。 */
        public String curMark = "";
    }

    /**
     * 请求一批 FM 推荐。
     *
     * <p>这是「猜你喜欢」唯一的写+读混合端点：它会把上一条的收听情况（{@code hash}/{@code songid}/
     * {@code playtime}）一起记进推荐模型，所以<b>推进下一批时要把上一条的信息带全</b>，
     * 否则推荐会停滞在第一批。</p>
     *
     * <p>{@code key} 是 {@code paramsKey(毫秒时间戳)}，与 body 里的 {@code clienttime} 必须同源；
     * 两者不一致会得到 {@code 200104}（与 {@code special_recommend} 同一类错误）。
     * {@code fakem} 是「假机器码」，固定值即可，随机值会让推荐变得不稳定。</p>
     */
    public static FmResult requestFm(FmRequest request) {
        List<Track> tracks = new ArrayList<Track>();
        FmRequest used = request == null ? new FmRequest() : request;
        if (!KuGouDevice.isLoggedIn()) return new FmResult(tracks, "", 0, 0);
        long clientTime = System.currentTimeMillis();

        JsonObject body = new JsonObject();
        body.addProperty("appid", number(KuGouHttp.appId()));
        body.addProperty("clienttime", clientTime);
        body.addProperty("mid", KuGouDevice.mid());
        // action=play 是「正常要推荐」；负反馈走 personalFmAction，不复用这条便捷入口。
        body.addProperty("action", "play");
        body.addProperty("recommend_source_locked", 0);
        body.addProperty("song_pool_id", Math.max(0, used.songPoolId));
        body.addProperty("callerid", 0);
        body.addProperty("m_type", 1);
        body.addProperty("platform", "ios");
        body.addProperty("area_code", 1);
        body.addProperty("remain_songcnt", 0);
        body.addProperty("clientver", number(KuGouHttp.clientVer()));
        body.addProperty("is_overplay", used.allowRepeat ? 1 : 0);
        body.addProperty("mode", (used.mode == null ? FmMode.NORMAL : used.mode).key());
        body.addProperty("fakem", "ca981cfc583a4c37f28d2d49000013c16a0a");
        body.addProperty("key", KuGouSign.paramsKey(String.valueOf(clientTime), KuGouProfile.active()));
        body.addProperty("userid", KuGouDevice.userId());
        body.addProperty("kguid", KuGouDevice.userId());
        body.addProperty("token", KuGouDevice.token());
        // vip_type 决定这一批里能不能含会员曲；不传时上游按非会员推，
        // 表现为「明明是会员却一直推免费歌」。
        body.addProperty("vip_type", KuGouDevice.vipType());
        String hash = KuGouSign.lower(KuGouSign.nonNull(used.excludeHash).trim());
        if (!hash.isEmpty()) body.addProperty("hash", hash);
        String songId = KuGouSign.nonNull(used.excludeAlbumAudioId).trim();
        if (!songId.isEmpty()) body.addProperty("songid", number(songId));
        if (used.lastPlayMillis > 0L) body.addProperty("playtime", used.lastPlayMillis);
        if (!KuGouSign.isBlank(used.curMark)) body.addProperty("cur_mark", used.curMark.trim());
        if (used.limit > 0) body.addProperty("song_cnt", clampPageSize(used.limit));

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("platform", "ios");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/v2/personal_recommend", params,
                body.toString(), KuGouHttp.Sign.ANDROID, router("persnfm.service.kugou.com"));
        if (!isOk(root)) return new FmResult(tracks, "", 0, 0);

        JsonObject data = object(root, "data");
        JsonArray songList = array(data, "song_list", "songs");
        if (songList != null) {
            for (JsonElement element : songList) {
                if (element == null || !element.isJsonObject()) continue;
                Track track = parseFlatTrack(element.getAsJsonObject());
                if (track != null) tracks.add(track);
            }
        }
        // 游标与计数挂在 data.info 下，与曲目同级；缺失时按空串/0 处理，调用方不区分「没有」与「为 0」。
        JsonObject info = object(data, "info");
        String curMark = firstText(info, "cur_mark", "curmark");
        int garbage = integer(info, "garbage_num", "garbage_count");
        int remain = integer(info, "remain_songcnt", "remain");
        return new FmResult(tracks, curMark, garbage, remain);
    }

    /**
     * FM 负反馈 / 正反馈。
     *
     * <p>动作取值见参考实现的 {@code FmAction} 与其 body 注释：</p>
     * <ul>
     *   <li>{@code garbage} —— 不感兴趣（拉黑这一首），{@code cancel_garbage} 撤销；</li>
     *   <li>{@code click_red} —— 红心（进「我喜欢」），{@code cancel_red} 撤销；</li>
     *   <li>同一批里还有 {@code black_singer} / {@code cancel_black_singer}（拉黑歌手）、
     *       {@code update_recommend_source} / {@code change_song_pool}（换推荐来源与歌曲池）。</li>
     * </ul>
     *
     * <p>与 {@code personalFm} 是<b>同一个端点</b>、不同 {@code action}，所以签名与 key 的规则完全一致。
     * {@code curMark} 要传最近一次推荐带回的那个值，否则反馈会打到别批推荐上（不报错）。</p>
     *
     * @return 上游是否接受这次反馈
     */
    public static boolean personalFmAction(String action, Track track, String curMark) {
        if (!KuGouDevice.isLoggedIn() || KuGouSign.isBlank(action) || track == null) return false;
        long clientTime = System.currentTimeMillis();

        JsonObject body = new JsonObject();
        body.addProperty("appid", number(KuGouHttp.appId()));
        body.addProperty("clienttime", clientTime);
        body.addProperty("mid", KuGouDevice.mid());
        body.addProperty("action", action.trim());
        body.addProperty("recommend_source_locked", 0);
        body.addProperty("song_pool_id", 0);
        body.addProperty("callerid", 0);
        body.addProperty("m_type", 1);
        body.addProperty("platform", "ios");
        body.addProperty("area_code", 1);
        body.addProperty("remain_songcnt", 0);
        body.addProperty("clientver", number(KuGouHttp.clientVer()));
        body.addProperty("is_overplay", 0);
        body.addProperty("mode", FmMode.NORMAL.key());
        body.addProperty("fakem", "ca981cfc583a4c37f28d2d49000013c16a0a");
        body.addProperty("key", KuGouSign.paramsKey(String.valueOf(clientTime), KuGouProfile.active()));
        body.addProperty("userid", KuGouDevice.userId());
        body.addProperty("kguid", KuGouDevice.userId());
        body.addProperty("token", KuGouDevice.token());
        body.addProperty("vip_type", KuGouDevice.vipType());
        if (!KuGouSign.isBlank(track.hash)) body.addProperty("hash", KuGouSign.lower(track.hash));
        String songId = track.requestAlbumAudioId();
        if (!songId.isEmpty()) body.addProperty("songid", number(songId));
        if (!KuGouSign.isBlank(curMark)) body.addProperty("cur_mark", curMark.trim());

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("platform", "ios");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/v2/personal_recommend", params,
                body.toString(), KuGouHttp.Sign.ANDROID, router("persnfm.service.kugou.com"));
        return isOk(root);
    }

    /** 「不感兴趣」：让这一首从后续推荐里消失。 */
    public static boolean personalFmTrash(Track track, String curMark) {
        return personalFmAction("garbage", track, curMark);
    }

    /** 撤销「不感兴趣」。 */
    public static boolean personalFmUndoTrash(Track track, String curMark) {
        return personalFmAction("cancel_garbage", track, curMark);
    }

    /** FM 里点红心。与歌单加曲是两条链路：这条只影响推荐模型。 */
    public static boolean personalFmRed(Track track, String curMark) {
        return personalFmAction("click_red", track, curMark);
    }

    /** 撤销 FM 里的红心。 */
    public static boolean personalFmUndoRed(Track track, String curMark) {
        return personalFmAction("cancel_red", track, curMark);
    }

    /**
     * 拉黑 / 取消拉黑某位歌手。
     *
     * <p>酷狗的歌手黑名单是账号级持久的，撤销要记着歌手名——{@link Track#artist} 就是它比对用的值。</p>
     */
    public static boolean personalFmBlackSinger(Track track, boolean cancel) {
        if (track == null || KuGouSign.isBlank(track.artist)) return false;
        return personalFmAction(cancel ? "cancel_black_singer" : "black_singer", track, "");
    }

    /**
     * 最近播放（听歌历史）。需要登录。
     *
     * <p>返回结构比其它端点还要再包一层：真正的歌曲信息在 {@code songs[].info} 里，
     * 外层只有 {@code mxid/pc/ot} 这些播放计数。</p>
     */
    public static List<Track> historySongs(int limit) {
        List<Track> result = new ArrayList<Track>();
        if (!KuGouDevice.isLoggedIn()) return result;

        JsonObject body = new JsonObject();
        body.addProperty("token", KuGouDevice.token());
        body.addProperty("userid", KuGouDevice.userId());
        body.addProperty("source_classify", "app");
        body.addProperty("to_subdivide_sr", 1);

        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/playhistory/v1/get_songs",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID, null);
        if (!isOk(root)) return result;
        JsonArray songs = array(object(root, "data"), "songs");
        if (songs == null) return result;

        for (JsonElement element : songs) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject info = object(element.getAsJsonObject(), "info");
            if (info == null) continue;
            Track track = parseFlatTrack(info);
            if (track != null) result.add(track);
            if (result.size() >= clampPageSize(limit)) break;
        }
        return result;
    }

    // ── 专辑 / 歌手 ─────────────────────────────────────────────────────────

    /**
     * 专辑曲目。
     *
     * <p>响应分三层：{@code base}（歌名/歌手）、{@code audio_info}（三个 hash 与时长）、
     * {@code album_info}（专辑名与封面）。<b>没有任何一层是扁平的</b>，直接按搜索的字段名读会全部落空。</p>
     */
    public static List<Track> albumSongs(String albumId, int limit) {
        List<Track> result = new ArrayList<Track>();
        if (KuGouSign.isBlank(albumId)) return result;

        JsonObject body = new JsonObject();
        body.addProperty("album_id", albumId.trim());
        body.addProperty("is_buy", "");
        body.addProperty("page", 1);
        body.addProperty("pagesize", clampPageSize(limit));

        Map<String, String> headers = router("openapi.kugou.com");
        headers.put("kg-tid", "255");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/v1/album_audio/lite",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID, headers);
        if (!isOk(root)) return result;

        JsonArray songs = array(object(root, "data"), "songs");
        if (songs == null) return result;
        for (JsonElement element : songs) {
            if (element == null || !element.isJsonObject()) continue;
            Track track = parseLayeredTrack(element.getAsJsonObject());
            if (track != null) result.add(track);
        }
        return result;
    }

    /** 专辑信息（免签）。 */
    public static Playlist albumInfo(String albumId) {
        if (KuGouSign.isBlank(albumId)) return null;
        Map<String, String> params = KuGouHttp.params();
        params.put("albumid", albumId.trim());
        params.put("version", "9108");
        params.put("with_res_tag", "0");
        JsonObject root = KuGouHttp.get(KuGouHttp.MOBILE_CDN, "/api/v3/album/info", params, KuGouHttp.Sign.NONE);
        JsonObject data = object(root, "data");
        if (data == null) return null;
        Playlist playlist = new Playlist();
        playlist.id = albumId.trim();
        playlist.kind = KIND_ALBUM;
        playlist.name = text(data, "albumname");
        playlist.coverTemplate = firstText(data, "imgurl", "img");
        playlist.description = firstText(data, "intro", "singername");
        playlist.creator = text(data, "singername");
        playlist.trackCount = integer(data, "songcount", "song_count");
        return KuGouSign.isBlank(playlist.name) ? null : playlist;
    }

    /** 歌手单曲。这个端点直接连 {@code openapi.kugou.com}，不经过主网关。 */
    public static List<Track> artistSongs(String authorId, int limit) {
        List<Track> result = new ArrayList<Track>();
        if (KuGouSign.isBlank(authorId)) return result;

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("author_id", authorId.trim());
        params.put("pagesize", String.valueOf(clampPageSize(limit)));
        params.put("page", "1");
        // sort=2 是按热度，比默认的时间序更符合「歌手页」的期待。
        params.put("sort", "2");
        params.put("area_code", "all");
        params.put("key", KuGouSign.paramsKey(String.valueOf(System.currentTimeMillis() / 1000L)));

        Map<String, String> headers = router("openapi.kugou.com");
        headers.put("kg-tid", "220");
        JsonObject root = KuGouHttp.get(KuGouHttp.OPENAPI, "/kmr/v1/audio_group/author", params,
                KuGouHttp.Sign.ANDROID, headers);
        JsonArray data = pickArray(root, "data", "info", "list", "audios");
        if (data == null) return result;
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            Track track = parseOpenApiTrack(element.getAsJsonObject());
            if (track != null) result.add(track);
        }
        return result;
    }

    /**
     * 歌手信息。
     *
     * <p>必须用 POST 且带 JSON body：用 GET 语义（参数放查询串）会得到 502，
     * 因为上游这个路由只注册了 POST。</p>
     */
    public static Playlist artistInfo(String authorId) {
        if (KuGouSign.isBlank(authorId)) return null;
        JsonObject body = new JsonObject();
        body.addProperty("author_id", authorId.trim());
        Map<String, String> headers = router("openapi.kugou.com");
        headers.put("kg-tid", "36");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/kmr/v3/author",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID, headers);
        if (!isOk(root)) return null;
        JsonObject data = object(root, "data");
        if (data == null) data = root;
        Playlist playlist = new Playlist();
        playlist.id = authorId.trim();
        playlist.kind = KIND_ARTIST;
        playlist.name = text(data, "author_name");
        playlist.coverTemplate = text(data, "sizable_avatar");
        playlist.trackCount = integer(data, "audio_count", "song_count");
        playlist.description = artistIntro(data);
        return KuGouSign.isBlank(playlist.name) ? null : playlist;
    }

    /** 歌手专辑列表。 */
    public static List<Playlist> artistAlbums(String authorId, int limit) {
        List<Playlist> result = new ArrayList<Playlist>();
        if (KuGouSign.isBlank(authorId)) return result;

        JsonObject body = new JsonObject();
        body.addProperty("author_id", authorId.trim());
        body.addProperty("pagesize", clampPageSize(limit));
        body.addProperty("page", 1);
        body.addProperty("sort", 1);
        body.addProperty("category", 1);
        body.addProperty("area_code", "all");

        Map<String, String> headers = router("openapi.kugou.com");
        headers.put("kg-tid", "36");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/kmr/v1/author/albums",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID, headers);
        JsonArray data = array(root, "data", null);
        if (data == null) return result;
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Playlist playlist = new Playlist();
            playlist.id = firstText(item, "album_id", "albumid", "id");
            playlist.kind = KIND_ALBUM;
            playlist.name = firstText(item, "album_name", "albumname", "name");
            playlist.coverTemplate = firstText(item, "sizable_cover", "cover", "imgurl");
            playlist.creator = text(item, "author_name");
            playlist.trackCount = integer(item, "song_count", "songcount", "audio_count");
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    // ── 取流 ────────────────────────────────────────────────────────────────

    /**
     * 解析播放地址。
     *
     * <p>按音质从高到低依次尝试：上游对每个音质是<b>独立授权</b>的，无损拿不到不代表 320 也拿不到。</p>
     *
     * <p>关键约束：<b>每一档必须用同档的 hash</b>。传 128 的 hash 却要 {@code quality=320} 会直接
     * 返回 {@code status=2}，不会自动升档。所以这里把 (hash, quality) 成对地试。</p>
     *
     * @param tier 0 标准、1 高品（320）、2 无损
     */
    public static Stream songUrl(Track track, int tier) {
        if (track == null) return null;
        for (QualityTier candidate : tiersFor(track, tier)) {
            Stream stream = resolveViaGateway(track, candidate.hash, candidate.quality);
            if (stream != null) return stream;
        }
        // 现代接口连一首免费曲都拿不到时（例如上游临时故障），退回老 trackercdn。
        for (QualityTier candidate : tiersFor(track, tier)) {
            Stream stream = resolveViaLegacyCdn(track, candidate.hash, candidate.legacyBr);
            if (stream != null) return stream;
        }
        return null;
    }

    /** 一档音质：hash 与它对应的 {@code quality} 取值必须成对使用。 */
    private static final class QualityTier {
        final String hash;
        final String quality;
        final String legacyBr;

        QualityTier(String hash, String quality, String legacyBr) {
            this.hash = hash;
            this.quality = quality;
            this.legacyBr = legacyBr;
        }
    }

    private static List<QualityTier> tiersFor(Track track, int tier) {
        List<QualityTier> tiers = new ArrayList<QualityTier>();
        if (tier >= 2) addTier(tiers, track.hashLossless, "flac", "flac");
        if (tier >= 1) addTier(tiers, track.hash320, "320", "hq");
        addTier(tiers, track.hash, "128", "128");
        // 高档位失败时再补一次 320：tier=2 上面只加了无损与标准，中间那一档同样值得试。
        addTier(tiers, track.hash320, "320", "hq");
        return tiers;
    }

    private static void addTier(List<QualityTier> tiers, String hash, String quality, String legacyBr) {
        if (KuGouSign.isBlank(hash)) return;
        String lower = KuGouSign.lower(hash);
        for (QualityTier tier : tiers) {
            if (tier.hash.equals(lower)) return;
        }
        tiers.add(new QualityTier(lower, quality, legacyBr));
    }

    /**
     * 按酷狗自己的音质档位取流。
     *
     * <p>与 {@link #songUrl(Track, int)} 的区别只有一件：那一版把宿主的八档折叠成三档，
     * 这一版按 {@link KuGouQuality} 的阶梯逐档尝试，所以臻品母带 / 全景声这些确实存在的档位
     * 能拿到地址，不存在时会自动降回无损、320、128。</p>
     *
     * <p>两条不变的条件照旧：{@code (hash, quality)} <b>必须成对</b>，且交付的 hash 要和请求的一致
     * （校验在 {@link #resolveViaGateway} 里，两条链路共用）。</p>
     *
     * @param requested 起点档位；{@code null} 按最高档试
     * @return 拿不到任何档位时返回 {@code null}
     */
    public static Stream songUrl(Track track, KuGouQuality requested) {
        if (track == null) return null;
        boolean authFirst = KuGouProfile.active() == KuGouProfile.CONCEPT
                && com.muoniumplayer.core.screens.ncm.NCMPlayerConfig.isKuGouAuthStreamEnabled();
        for (KuGouQuality quality : KuGouQuality.ladderFrom(requested)) {
            String hash = hashFor(track, quality);
            if (KuGouSign.isBlank(hash)) continue;
            if (isVipLockedForCurrentAccount(track, quality)) continue;
            String lower = KuGouSign.lower(hash);
            if (authFirst) {
                Stream stream = KuGouAuth.resolveViaAuth(track, lower, quality.key());
                if (stream != null) return stream;
            }
            Stream stream = resolveViaGateway(track, lower, quality.key());
            if (stream != null) return stream;
        }
        // 逐档都拿不到时，最后试一次标准档：它一定有 hash，且是唯一匿名也可用的档位。
        String standard = hashFor(track, KuGouQuality.STANDARD);
        if (KuGouSign.isBlank(standard)) return null;
        String lower = KuGouSign.lower(standard);
        if (authFirst) {
            Stream stream = KuGouAuth.resolveViaAuth(track, lower, KuGouQuality.STANDARD.key());
            if (stream != null) return stream;
        }
        return resolveViaGateway(track, lower, "128");
    }

    /**
     * 这一档对当前账号是不是「需要会员且账号没有」——是的话上游必拒，阶梯直接跳过它。
     *
     * <p>判据完全来自 {@code privilegeLite} 探到的 {@code relate_goods}（见
     * {@link Track#vipLockedByQuality}），不发请求。<b>只有非会员账号才跳</b>：会员账号
     * （含概念版 VIP）对会员档有授权，跳过它们等于把这条链路的主场景（付费歌核销会员权益）
     * 整个关死。探测没覆盖到的档不在表里，照旧打请求让上游裁决。</p>
     */
    private static boolean isVipLockedForCurrentAccount(Track track, KuGouQuality quality) {
        Boolean locked = track.vipLockedByQuality.get(quality.key());
        if (locked == null || !locked || KuGouDevice.isVip()) return false;
        System.err.println("[Music/KuGou] 跳过 " + quality.label() + "（" + quality.key()
                + "）：探测到该档需要会员，当前账号非会员");
        return true;
    }

    /**
     * 某一档音质对应的 hash。
     *
     * <p>优先用 {@link #privilegeLite(List)} 探到的那份（它是唯一会明确回答「这首有哪些档」的端点），
     * 其次才是列表端点顺手带回来的 {@code hash_320} / {@code SQ.Hash} 之类。</p>
     */
    public static String hashFor(Track track, KuGouQuality quality) {
        if (track == null || quality == null) return "";
        String probed = track.hashesByQuality.get(quality.key());
        if (!KuGouSign.isBlank(probed)) return probed;
        if (quality == KuGouQuality.STANDARD) return track.hash;
        if (quality == KuGouQuality.HIGH) return track.hash320;
        if (quality == KuGouQuality.FLAC) return track.hashLossless;
        // 更高档位一律要求「确实探到过这一档的 hash」。拿低档 hash 冒充只会换来一次
        // status=2，白打一个请求还多一条误导性的「取流被拒」日志。
        return "";
    }

    /**
     * 批量查权限与音质可用性：{@code POST /v2/get_res_privilege/lite}。
     *
     * <p>这条端点做两件事，都是本类原来没有的能力：</p>
     * <ol>
     *   <li><b>回答「这首有哪些档、每档多大、hash 是什么」</b>。列表端点只会顺手给到无损那一档，
     *       臻品/母带档必须问它，否则 {@link #songUrl(Track, KuGouQuality)} 的阶梯没有起点依据；</li>
     *   <li><b>按 hash 反查曲目</b>。响应带 {@code audio_info} 全套元数据，所以手里只有一个 hash
     *       （分享链接、云盘、其它音源解灰）时不必再靠「歌手 - 歌名」去搜。</li>
     * </ol>
     *
     * <p>{@code qualities} 数组要一次给全（见 {@link KuGouQuality#privilegeProbeKeys()}）：
     * 少给一项，响应里就没有那一项，看起来像「这首没有这一档」。{@code resource} 与请求顺序一致。</p>
     *
     * @param hashes 要查的 hash，会去重并裁到 {@link #MAX_PAGE_SIZE}
     * @return 与请求同序的结果；失败返回空列表
     */
    public static List<Privilege> privilegeLite(List<String> hashes) {
        List<Privilege> result = new ArrayList<Privilege>();
        List<String> unique = new ArrayList<String>();
        if (hashes != null) {
            for (String value : hashes) {
                String hash = KuGouSign.lower(KuGouSign.nonNull(value).trim());
                if (hash.isEmpty() || unique.contains(hash)) continue;
                unique.add(hash);
                if (unique.size() >= MAX_PAGE_SIZE) break;
            }
        }
        if (unique.isEmpty()) return result;

        JsonArray resource = new JsonArray();
        for (String hash : unique) {
            JsonObject item = new JsonObject();
            item.addProperty("type", "audio");
            item.addProperty("page_id", 0);
            item.addProperty("hash", hash);
            item.addProperty("album_id", 0);
            resource.add(item);
        }

        JsonObject body = new JsonObject();
        body.addProperty("appid", number(KuGouHttp.appId()));
        body.addProperty("area_code", 1);
        body.addProperty("behavior", "play");
        body.addProperty("clientver", number(KuGouHttp.clientVer()));
        body.addProperty("need_hash_offset", 1);
        body.addProperty("relate", 1);
        body.addProperty("support_verify", 1);
        body.add("resource", resource);
        JsonArray qualities = new JsonArray();
        for (String key : KuGouQuality.privilegeProbeKeys()) qualities.add(key);
        body.add("qualities", qualities);

        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/v2/get_res_privilege/lite",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID,
                router("media.store.kugou.com"));
        if (!isOk(root)) return result;

        // 上游这条端点的 data 是「每个 hash 一项」的数组；万一改成对象包一层，也要能从常见
        // 几个字段名里把数组捞出来。不走 array(root, "data", null) 那种写法：那个 null 名字
        // 只在 data 不是数组时才会被读到，届时是一次谁也没预料到的空键查找。
        JsonElement dataNode = root.get("data");
        JsonArray data = dataNode != null && dataNode.isJsonArray()
                ? dataNode.getAsJsonArray()
                : array(object(root, "data"), "audio", "list", "privilege");
        if (data == null) return result;
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) continue;
            Privilege privilege = parsePrivilege(element.getAsJsonObject());
            if (privilege != null) result.add(privilege);
        }
        return result;
    }

    /**
     * 一条权限结果。
     *
     * <p>响应里同一份信息会按 {@code audio_info} / {@code privilege_info} / {@code related}
     * 分散出现，字段名又随端点变，所以这里不逐个映射字段，而是<b>递归扫整棵子树</b>按规则收集：
     * 「值是 32 位十六进制、且字段名里含某个档位名」的当作该档 hash，「字段名含 filesize/size」的
     * 当作该档字节数。这样上游改字段名只会少收一项，不会像逐字段映射那样整体落空。</p>
     */
    public static final class Privilege {
        public String hash = "";
        public String name = "";
        public String artist = "";
        public String album = "";
        public String albumId = "";
        public String albumAudioId = "";
        public String coverTemplate = "";
        public long durationMillis;
        /** 上游 {@code privilege}：0/8 免费，10 需要会员。 */
        public int privilege;
        public boolean vip;
        /** {@link KuGouQuality#key()} → 该档 hash。只包含确认存在的档位。 */
        public final Map<String, String> hashesByQuality = new LinkedHashMap<String, String>();
        /** {@link KuGouQuality#key()} → 该档文件字节数。 */
        public final Map<String, Long> bytesByQuality = new LinkedHashMap<String, Long>();
        /**
         * {@link KuGouQuality#key()} → 该档是否「需要会员/付费」。
         *
         * <p>来源是 {@code relate_goods[]} 的 {@code privilege} 字段（0 免费，非 0 需会员）。
         * 只在探测到该档存在时才有值：调用方用它跳过「非会员对会员档」的注定失败请求，
         * 不在这里替代上游判定——取流仍以上游为准，这里只是省请求。</p>
         */
        public final Map<String, Boolean> vipLockedByQuality = new LinkedHashMap<String, Boolean>();

        /** 这份结果里最高的可用档位；一个 hash 都没探到时返回 {@link KuGouQuality#STANDARD}。 */
        public KuGouQuality bestQuality() {
            KuGouQuality best = KuGouQuality.STANDARD;
            for (KuGouQuality quality : KuGouQuality.values()) {
                if (hashesByQuality.containsKey(quality.key()) && quality.rank() > best.rank()) best = quality;
            }
            return best;
        }

        public boolean has(KuGouQuality quality) {
            return quality != null && hashesByQuality.containsKey(quality.key());
        }

        /** 探测结果里所有确认存在的档位，从高到低。 */
        public List<KuGouQuality> availableQualities() {
            List<KuGouQuality> list = new ArrayList<KuGouQuality>();
            for (KuGouQuality quality : KuGouQuality.values()) {
                if (hashesByQuality.containsKey(quality.key())) list.add(quality);
            }
            java.util.Collections.sort(list, new java.util.Comparator<KuGouQuality>() {
                @Override
                public int compare(KuGouQuality left, KuGouQuality right) {
                    return right.rank() - left.rank();
                }
            });
            if (list.isEmpty()) list.add(KuGouQuality.STANDARD);
            return list;
        }
    }

    private static Privilege parsePrivilege(JsonObject node) {
        Privilege privilege = new Privilege();
        JsonObject audioInfo = object(node, "audio_info");
        JsonObject source = audioInfo == null ? node : audioInfo;
        privilege.hash = KuGouSign.lower(firstText(source, "hash", "file_hash"));
        privilege.name = firstText(source, "audio_name", "songname", "song_name", "name");
        privilege.artist = firstText(source, "author_name", "singername", "singer_name");
        if (KuGouSign.isBlank(privilege.artist)) privilege.artist = authorNames(source);
        privilege.album = firstText(object(node, "album_info"), "album_name", "albumname");
        if (KuGouSign.isBlank(privilege.album)) privilege.album = firstText(source, "album_name", "albumname");
        privilege.albumId = firstText(object(node, "album_info"), "album_id", "albumid");
        if (KuGouSign.isBlank(privilege.albumId)) privilege.albumId = firstText(source, "album_id", "albumid");
        privilege.albumAudioId = firstText(source, "album_audio_id", "mixsongid", "aoid");
        privilege.coverTemplate = firstText(object(node, "album_info"), "sizable_cover", "cover", "imgurl");
        if (KuGouSign.isBlank(privilege.coverTemplate)) {
            privilege.coverTemplate = firstText(source, "sizable_cover", "imgurl", "cover");
        }
        privilege.durationMillis = normalizedDuration(source, "duration", "timelength", "interval");
        privilege.privilege = privilegeFromDownload(node);
        privilege.vip = privilege.privilege == 10;
        parseRelateGoods(node, privilege);
        collectQualityFields(node, privilege, 0);
        if (privilege.hash.isEmpty() && privilege.name.isEmpty()) return null;
        return privilege;
    }

    /** 递归深度上限。响应最深也就三四层，设上限是为了防止结构变化时转不出来。 */
    private static final int PRIVILEGE_WALK_DEPTH = 5;

    /**
     * 读 {@code relate_goods[]}，把「每档要不要会员」记进 {@link Privilege#vipLockedByQuality}。
     *
     * <p>{@code relate_goods} 是上游按音质逐条给权益的数组，每条的 {@code privilege}
     * 0 表示免费、非 0 表示需要会员/付费（实测付费歌为 10 且 {@code pay_type=3}）。字段名大小写
     * 与档位字面量都照抄上游，匹配 {@link KuGouQuality#fromKey} 时不认识档位就跳过——
     * 上游扩档位不会把解析打崩，只是那一档没进判定表。</p>
     *
     * <p>包级可见只为了单测：黄金用例直接喂 JSON 断言归档结果。</p>
     */
    static void parseRelateGoods(JsonObject node, Privilege privilege) {
        if (node == null || privilege == null) return;
        JsonArray goods = array(node, "relate_goods");
        if (goods == null) return;
        for (JsonElement element : goods) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            KuGouQuality quality = KuGouQuality.fromKey(text(item, "quality"));
            if (quality == null) continue;
            privilege.vipLockedByQuality.put(quality.key(), integer(item, "privilege") != 0);
        }
    }

    private static void collectQualityFields(JsonObject node, Privilege privilege, int depth) {
        if (node == null || depth > PRIVILEGE_WALK_DEPTH) return;
        for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
            String field = KuGouSign.lower(KuGouSign.nonNull(entry.getKey()));
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonObject()) {
                collectQualityFields(value.getAsJsonObject(), privilege, depth + 1);
                continue;
            }
            if (value.isJsonArray()) {
                for (JsonElement child : value.getAsJsonArray()) {
                    if (child != null && child.isJsonObject()) {
                        collectQualityFields(child.getAsJsonObject(), privilege, depth + 1);
                    }
                }
                continue;
            }
            KuGouQuality quality = qualityOfFieldName(field);
            if (quality == null) continue;
            if (field.contains("filesize") || field.contains("size") || field.contains("bytes")) {
                long bytes = longValue(node, entry.getKey());
                if (bytes > 0L) privilege.bytesByQuality.put(quality.key(), bytes);
                continue;
            }
            String text = text(node, entry.getKey());
            if (isHashValue(text)) privilege.hashesByQuality.put(quality.key(), KuGouSign.lower(text));
        }
    }

    /** 字段名里出现的档位字面量。{@code hash_128} 与 {@code filesize_viper_tape} 都靠它归类。 */
    private static KuGouQuality qualityOfFieldName(String field) {
        for (KuGouQuality quality : KuGouQuality.values()) {
            if (field.contains(quality.key())) return quality;
        }
        return null;
    }

    /** 是不是一个酷狗 hash（32 位十六进制）。用这条硬判据防止把时长、体积当成 hash。 */
    private static boolean isHashValue(String value) {
        String text = KuGouSign.nonNull(value).trim();
        if (text.length() != 32) return false;
        for (int index = 0; index < text.length(); index++) {
            char ch = Character.toLowerCase(text.charAt(index));
            boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    /**
     * 这首歌是不是「因为账号没有会员才取不到」。
     *
     * <p>用于把「播放失败」翻译成用户能行动的信息：会员曲在非会员账号上必然失败，
     * 提示「需要会员」才有意义；而同样的失败如果出现在免费曲上，那是上游故障，提示开会员是误导。</p>
     *
     * <p>不需要发请求就能判断，所以 UI 在渲染歌曲列表时也可以直接用。</p>
     */
    public static boolean needsVip(Track track) {
        return track != null && track.vip && !KuGouDevice.isVip();
    }

    /**
     * 新网关取流：{@code /v5/url}。
     *
     * <p>这是<b>唯一</b>实测可用的取流路径。两个必须同时满足的条件：</p>
     * <ol>
     *   <li>外层 {@code clientver} 必须是 11430（tracker 协议版本），不是身份默认的 20489 / 11440。
     *       参考实现里 {@code version} 与 {@code clientver} 两个字段在<b>两种身份下都是 11430</b>，
     *       所以它不随 {@link KuGouProfile} 变。</li>
     *   <li>{@code key}、{@code appid}、{@code page_id}、{@code ppage_id}、{@code pid}
     *       <b>五个值全部取自同一个身份</b>。标准版是 {@code 57ae12eb… + 1005 + 151369488 +
     *       463467626… + 2}，概念版是 {@code 185672dd… + 3116 + 967177915 + 356753938… + 411}；
     *       只换其中一部分会得到「签名算得出来、上游什么都不给」的结果。</li>
     * </ol>
     *
     * <p>会员音质完全取决于账号：VIP 账号上 128/320/flac 都能拿到真实地址，
     * 匿名对会员曲会得到 {@code status=2} + {@code fail_process=["pkg","buy"]}。</p>
     */
    private static Stream resolveViaGateway(Track track, String hash, String quality) {
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
        params.put("hash", hash);
        params.put("ssa_flag", "is_fromtrack");
        params.put("version", STREAM_CLIENT_VER);
        params.put("page_id", profile.streamPageId());
        params.put("quality", quality);
        params.put("album_audio_id", albumAudioIdForRequest(track));
        params.put("behavior", "play");
        params.put("pid", profile.streamPid());
        params.put("cmd", "26");
        params.put("pidversion", "3001");
        params.put("IsFreePart", "0");
        params.put("ppage_id", profile.streamPageIdChain());
        params.put("cdnBackup", "1");
        params.put("module", "");
        params.put("clientver", STREAM_CLIENT_VER);
        params.put("key", KuGouSign.urlKey(hash, KuGouDevice.mid(), KuGouDevice.userId(), profile));

        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/v5/url", params,
                KuGouHttp.Sign.ANDROID, router("trackercdn.kugou.com"));
        if (root == null || integer(root, "status") != 1) {
            // 失败的两种原因需要区分：一档音质没授权（换下一档就好），还是整首曲子都要会员。
            // 后者重试其它档位只是白费请求，早一点把原因写进日志能省掉大量排查时间。
            String reason = failProcessReason(root);
            if (!reason.isEmpty()) {
                System.err.println("[Music/KuGou] 取流被拒 " + track.name + " (" + quality + "): " + reason
                        + (needsVip(track) ? "；当前账号非会员" : ""));
            }
            return null;
        }
        String url = firstUrl(array(root, "url", "backupdownurl"));
        if (KuGouSign.isBlank(url)) return null;
        // 「拿到 url」不等于「拿到这首歌」：请求的 hash 取不到文件时，上游不报错，而是把另一首歌
        // 完整的文件交回来（status 仍是 1、fail_process 为空、extName 仍按请求的档位填）。
        // 不核对交付身份的话，错文件会被当成成功采用，并一路写进缓存。
        String delivered = deliveredHash(root, url);
        if (!delivered.isEmpty() && !delivered.equals(KuGouSign.lower(hash))) {
            System.err.println("[Music/KuGou] 上游换了文件，弃用这一档 " + track.name + " (" + quality
                    + ")：请求 " + KuGouSign.lower(hash) + "，实得 " + delivered);
            return null;
        }
        String format = text(root, "extName");
        return new Stream(url, KuGouSign.isBlank(format) ? inferFormat(url) : format);
    }

    /**
     * 上游实际交付的文件 hash。
     *
     * <p>响应顶层 {@code hash} 就是交付文件的 hash，实测它与 CDN 地址里的 {@code /v3/<hash>/} 段恒等；
     * 上游偶尔不给这个字段，那时退而从地址段里取。两者都取不到时返回空串 —— 此时<b>保守放行</b>：
     * 不能因为上游少给一个字段，就把一首本来能播的歌判成不可播。</p>
     *
     * <p><b>不要改用 {@code std_hash} 当判据。</b>实测正常交付时它也可能不等于请求的 hash
     * （「晴天」flac 档请求 {@code 5e3dd2d1…}、交付内容核对无误、{@code std_hash} 却是
     * {@code e53255bd…}），拿它比对会把好档位一起毙掉。</p>
     */
    private static String deliveredHash(JsonObject root, String url) {
        String reported = KuGouSign.lower(text(root, "hash"));
        if (!reported.isEmpty()) return reported;
        String address = KuGouSign.lower(url);
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

    /**
     * 把 {@code fail_process} 里的失败环节翻成一句人话。
     *
     * <p>常见的取值：{@code ["pkg","buy"]} 表示需要购买/会员，{@code ["vip"]} 表示需要 VIP，
     * 空数组表示上游没给原因。返回空串表示「这次不是 fail_process 拦的」。</p>
     */
    private static String failProcessReason(JsonObject root) {
        if (root == null) return "";
        JsonArray process = array(root, "fail_process");
        if (process == null || process.size() == 0) return "";
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : process) {
            if (element == null || !element.isJsonPrimitive()) continue;
            if (builder.length() > 0) builder.append('+');
            builder.append(element.getAsString());
        }
        return builder.toString();
    }

    /**
     * 两个取流端点共用的 {@code album_audio_id} 取值。
     *
     * <p>走 {@link Track#requestAlbumAudioId()}，缺失时传字面量 {@code "0"}。传 0 时服务端回落到
     * 按 {@code hash} 解析，实测交付正确；<b>传一个不属于这套 ID 空间的值会换成别人的歌</b>，
     * 所以这里宁可传 0，也不拿别的 id 顶上。</p>
     */
    private static String albumAudioIdForRequest(Track track) {
        String id = track.requestAlbumAudioId();
        return id.isEmpty() ? "0" : id;
    }

    // ── 备选取流：/v6/priv_url ──────────────────────────────────────────────

    /**
     * {@code /v6/priv_url} 的 {@code qualities} 数组，照抄 {@code song_url_new.js:17}。
     *
     * <p>顺序与内容都<b>参与签名摘要</b>（body 整段进 md5），所以不能按本仓的档位枚举重排；
     * 里面的 {@code multitrack} 本仓没有对应音质，也照样留着。</p>
     */
    private static final String[] PRIV_URL_QUALITIES = {
            "128", "320", "flac", "high", "multitrack", "viper_atmos", "viper_tape", "viper_clear", "super"};

    /**
     * 备选取流：{@code POST http://tracker.kugou.com/v6/priv_url}（参考实现的 {@code song_url_new}）。
     *
     * <p><b>这一条不是默认路径，而且默认关着</b>：只有 {@code /v5/url} 阶梯与老 trackercdn 都拿不到
     * 地址、并且宿主开了 {@code NCMPlayerConfig.isKuGouPrivUrlFallbackEnabled()} 才会打它。
     * 两条都没被推翻的理由：</p>
     * <ul>
     *   <li>本仓实测它恒回 {@code error_code=20010 param error, unmarshal failed} —— 但那一次是在
     *       <b>标准版身份</b>下测的，概念版从没测过。所以这里既不当它是万能解，也不当它已经死了。</li>
     *   <li>参考实现自己的接口说明写着「该接口存在音频加密（目前无法解码），请谨慎使用」，
     *       还写着要先调 {@code /register/dev} 换 {@code dfid}，否则提示「本次请求需要验证」。
     *       本仓的 {@code dfid} 是本地随机值（实测对 {@code /v5/url} 够用），这正是它最可能卡住的地方。</li>
     * </ul>
     *
     * <p><b>参数一律照 {@code module/song_url_new.js} 的字面量，一处都不自作聪明</b>，
     * 因为这条链路没有实测数据可以指导改动：</p>
     * <ul>
     *   <li>{@code tracker_param.key} 的盐值是参考实现里<b>写死</b>的那把
     *       {@code 185672dd44712f60bb1736df5a377e82}（概念版取流盐），它<b>不</b>随 lite 变；
     *       同一段里的 {@code appid} 却<b>是</b>当前身份的。这个「概念版盐 + 当前 appid」的组合
     *       在标准版下就是混合身份 —— 与本仓当初测出 20010 的形态一致，所以本条实际有意义的场景
     *       是<b>概念版凭证</b>。这里照抄不修正，理由写在下面这条注释里，别当成笔误改掉：</li>
     *   <li>{@code pid='411'}、{@code priv_vip_type='6'}、{@code collect_list_id='3'}、
     *       {@code page_id=1} 也全是写死值，参考实现没有给它们任何 lite 条件。</li>
     * </ul>
     *
     * <p>响应字段名参考实现只是原样转发（{@code server.js} 不解析），所以本仓<b>没有</b>可靠的字段表：
     * 这里按「递归找 http 地址、能认出档位就先认档位、能核对交付 hash 就先核对」的方式取，
     * 交付身份判据与 {@code /v5/url} 完全一致（见 {@code docs/实现约定-酷狗取流交付校验.md}）。</p>
     *
     * @return 拿到地址返回 {@link Stream}，否则 {@code null}（调用方按「这一条也没用」处理）
     */
    public static Stream privUrl(Track track, KuGouQuality quality) {
        if (track == null) return null;
        KuGouQuality wanted = quality == null ? KuGouQuality.STANDARD : quality;
        String hash = KuGouSign.lower(hashFor(track, wanted));
        if (hash.isEmpty()) hash = KuGouSign.lower(track.hash);
        if (hash.isEmpty()) return null;

        KuGouProfile profile = KuGouProfile.active();
        // 参考实现把 userid 先 Number() 再拼进 key（"0" 而不是空串），本仓的 number() 同效。
        String userId = KuGouDevice.isLoggedIn() ? KuGouDevice.userId() : "0";
        String numericUserId = String.valueOf(number(userId));
        long clientTime = System.currentTimeMillis();

        JsonObject resource = new JsonObject();
        resource.addProperty("album_audio_id", albumAudioIdForRequest(track));
        resource.addProperty("collect_list_id", "3");
        resource.addProperty("collect_time", clientTime);
        resource.addProperty("hash", hash);
        resource.addProperty("id", 0);
        resource.addProperty("page_id", 1);
        resource.addProperty("type", "audio");

        JsonArray qualities = new JsonArray();
        for (String key : PRIV_URL_QUALITIES) qualities.add(key);

        JsonObject trackerParam = new JsonObject();
        trackerParam.addProperty("all_m", 1);
        trackerParam.addProperty("auth", "");
        trackerParam.addProperty("is_free_part", 0);
        trackerParam.addProperty("key", KuGouSign.md5(hash + KuGouProfile.CONCEPT.streamKeySalt()
                + profile.appId() + KuGouDevice.mid() + numericUserId));
        trackerParam.addProperty("module_id", 0);
        trackerParam.addProperty("need_climax", 1);
        trackerParam.addProperty("need_xcdn", 1);
        trackerParam.addProperty("open_time", "");
        trackerParam.addProperty("pid", "411");
        trackerParam.addProperty("pidversion", "3001");
        trackerParam.addProperty("priv_vip_type", "6");
        trackerParam.addProperty("viptoken", "");

        // 字段顺序 = 签名摘要的字节序列，必须与参考实现的 dataMap 一致。
        JsonObject body = new JsonObject();
        body.addProperty("area_code", "1");
        body.addProperty("behavior", "play");
        body.add("qualities", qualities);
        body.add("resource", resource);
        body.addProperty("token", KuGouDevice.isLoggedIn() ? KuGouDevice.token() : "");
        body.add("tracker_param", trackerParam);
        body.addProperty("userid", numericUserId);
        body.addProperty("vip", KuGouDevice.vipType());

        Map<String, String> params = KuGouHttp.deviceParams();
        // 与 /v5/url 不同：这条是直连 tracker，不带 x-router。
        JsonObject root = KuGouHttp.postJson(KuGouHttp.TRACKER, "/v6/priv_url", params,
                body.toString(), KuGouHttp.Sign.ANDROID, null);
        if (root == null) return null;
        int errorCode = integer(root, "error_code");
        if (errorCode != 0 || (root.has("status") && integer(root, "status") == 0)) {
            System.err.println("[Music/KuGou][v6] 上游拒绝（身份 " + profile.label() + "，"
                    + wanted.key() + "）：" + describePrivFailure(root));
            return null;
        }
        Stream stream = pickPrivStream(root, hash, wanted.key());
        if (stream == null) {
            // 「回了 200 但没给能用的地址」是这条链路最常见的失败形态（未登录/非会员就是空），
            // 把原始响应留一小段在日志里，才判断得出是参数错还是权限不够。
            System.err.println("[Music/KuGou][v6] 响应里没有可用地址（身份 " + profile.label() + "，"
                    + wanted.key() + "）：" + describePrivFailure(root));
        }
        return stream;
    }

    /** 一次递归找到的候选地址：节点、地址与「这一条大概是哪一档」的标签。 */
    private static final class PrivCandidate {
        private final JsonObject node;
        private final String url;
        private final String tag;

        private PrivCandidate(JsonObject node, String url, String tag) {
            this.node = node;
            this.url = url;
            this.tag = tag;
        }
    }

    /**
     * 从 {@code /v6/priv_url} 的响应里挑一条地址。
     *
     * <p>优先级：档位标签对得上 → 交付 hash 核对得过 → 任意一条。最后一档是<b>保守放行</b>，
     * 与 {@code /v5/url} 的策略一致：不能因为上游少给一个 hash 字段，就把一首本来能播的歌判成不可播。</p>
     */
    private static Stream pickPrivStream(JsonObject root, String hash, String qualityKey) {
        List<PrivCandidate> candidates = new ArrayList<PrivCandidate>();
        collectPrivCandidates(root, candidates, 0);
        PrivCandidate byQuality = null;
        PrivCandidate verified = null;
        for (PrivCandidate candidate : candidates) {
            if (byQuality == null && !qualityKey.isEmpty() && candidate.tag.contains(qualityKey)) {
                byQuality = candidate;
            }
            String delivered = deliveredHash(candidate.node, candidate.url);
            if (!delivered.isEmpty() && delivered.equals(hash) && verified == null) verified = candidate;
        }
        PrivCandidate chosen = byQuality != null ? byQuality : verified;
        if (chosen == null) chosen = candidates.isEmpty() ? null : candidates.get(0);
        if (chosen == null) return null;
        String delivered = deliveredHash(chosen.node, chosen.url);
        if (!delivered.isEmpty() && !delivered.equals(hash)) {
            System.err.println("[Music/KuGou][v6] 上游换了文件，弃用：" + chosen.url.substring(0,
                    Math.min(60, chosen.url.length())) + " 交付 " + delivered + "，请求 " + hash);
            return null;
        }
        return new Stream(chosen.url, inferFormat(chosen.url));
    }

    /**
     * 递归收集「字段名里带 url、值是一个 http 地址」的节点。
     *
     * <p>为什么只能递归：参考实现不解析这个响应，本仓也就没有字段表可依据，
     * 而它一次回九档，字段名极可能带档位前缀。深度限死 {@value #PRIV_WALK_DEPTH} 层，
     * 避免上游哪天回一个自引用结构把栈打满。</p>
     */
    private static void collectPrivCandidates(JsonObject node, List<PrivCandidate> out, int depth) {
        if (node == null || depth > PRIV_WALK_DEPTH || out.size() >= 64) return;
        for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive()) continue;
            String name = KuGouSign.lower(entry.getKey());
            if (!name.contains("url")) continue;
            String text = KuGouSign.nonNull(value.getAsString());
            if (!text.startsWith("http")) continue;
            out.add(new PrivCandidate(node, text, qualityTag(node)));
        }
        for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null) continue;
            if (value.isJsonObject()) {
                collectPrivCandidates(value.getAsJsonObject(), out, depth + 1);
            } else if (value.isJsonArray()) {
                for (JsonElement element : value.getAsJsonArray()) {
                    if (element != null && element.isJsonObject()) {
                        collectPrivCandidates(element.getAsJsonObject(), out, depth + 1);
                    }
                }
            }
        }
    }

    /** 节点里最像「这一条是哪一档」的几个字段，拼成一个小写串供档位匹配。 */
    private static String qualityTag(JsonObject node) {
        StringBuilder builder = new StringBuilder();
        for (String name : new String[]{"quality", "type", "name", "format", "extname", "level", "br"}) {
            JsonElement element = node.get(name);
            if (element == null || !element.isJsonPrimitive()) continue;
            if (builder.length() > 0) builder.append('|');
            builder.append(KuGouSign.lower(KuGouSign.nonNull(element.getAsString())));
        }
        return builder.toString();
    }

    /** 递归找地址时允许的深度；与 {@code privilege_lite} 那棵树同一量级。 */
    private static final int PRIV_WALK_DEPTH = 6;

    /** 失败时给日志用的一小段原文：状态码 + 截断后的响应，够判断「参数错」还是「权限不够」。 */
    private static String describePrivFailure(JsonObject root) {
        if (root == null) return "无响应";
        String raw = root.toString();
        if (raw.length() > 300) raw = raw.substring(0, 300) + "…";
        return "error_code=" + integer(root, "error_code") + " status=" + integer(root, "status") + " " + raw;
    }

    /**
     * 老 trackercdn 兜底：部分免费曲仍能由 {@code /i/v2/} 直接返回。
     *
     * <p><b>这条链路刻意不跟随 {@link KuGouProfile}</b>：它免签、{@code key} 用的是
     * {@link KuGouSign#playKey}（{@code kgcloudv2} 那把，与身份无关），而 {@code pid}/{@code appid}
     * 是老协议里的固定值。参考实现没有为概念版改这几样，改成动态的只会让这条兜底也一起失效。</p>
     */
    private static Stream resolveViaLegacyCdn(Track track, String hash, String br) {
        Map<String, String> params = KuGouHttp.params();
        params.put("key", KuGouSign.playKey(hash));
        params.put("hash", hash);
        params.put("br", br);
        params.put("appid", KuGouHttp.APP_ID);
        params.put("pid", LEGACY_PID);
        params.put("cmd", "25");
        params.put("behavior", "play");
        params.put("album_id", KuGouSign.nonNull(track.albumId));
        params.put("album_audio_id", albumAudioIdForRequest(track));
        JsonObject root = KuGouHttp.get(KuGouHttp.TRACKER_CDN, "/i/v2/", params, KuGouHttp.Sign.NONE);
        if (root == null || integer(root, "status") != 1) return null;
        String url = firstUrl(array(root, "url", "backupdownurl"));
        if (KuGouSign.isBlank(url)) return null;
        String format = firstText(root, "extName");
        return new Stream(url, KuGouSign.isBlank(format) ? inferFormat(url) : format);
    }

    /**
     * 会员状态。未登录或请求失败返回 {@code null}。
     *
     * <p>用于账号面板显示，也用于区分「这首会员曲取不到」是账号问题还是曲目问题——
     * 没有这个信息时，用户只会看到「播放失败」，无从判断该不该去开会员。</p>
     */
    public static VipStatus vipInfo() {
        if (!KuGouDevice.isLoggedIn()) return null;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("busi_type", "concept");
        JsonObject root = KuGouHttp.get(KuGouHttp.VIP, "/v1/get_union_vip", params, KuGouHttp.Sign.ANDROID);
        JsonObject data = object(root, "data");
        if (data == null) return null;
        int vipType = integer(data, "vip_type");
        return new VipStatus(vipType, integer(data, "is_vip") == 1 || vipType != 0,
                firstText(data, "vip_end_time", "m_reset_time"));
    }

    // ── 账号歌单 ────────────────────────────────────────────────────────────

    /**
     * 登录用户自己的歌单（创建 + 收藏）。需要 token；未登录返回空列表。
     *
     * <p>{@code listid}（数字）与 {@code global_collection_id} 都要保留：前者用于增删曲与删除歌单，
     * 后者用于取曲目。</p>
     */
    public static List<Playlist> userPlaylists(int limit) {
        List<Playlist> result = new ArrayList<Playlist>();
        if (!KuGouDevice.isLoggedIn()) return result;

        JsonObject body = new JsonObject();
        body.addProperty("userid", KuGouDevice.userId());
        body.addProperty("token", KuGouDevice.token());
        body.addProperty("total_ver", 979);
        // type=2 表示「全部歌单」；type=0 只回默认的两个。
        body.addProperty("type", 2);
        body.addProperty("page", 1);
        body.addProperty("pagesize", clampPageSize(limit));

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("plat", "1");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/v7/get_all_list", params,
                body.toString(), KuGouHttp.Sign.ANDROID, router("cloudlist.service.kugou.com"));
        if (!isOk(root)) return result;

        JsonArray info = array(object(root, "data"), "info");
        if (info == null) return result;
        for (JsonElement element : info) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            Playlist playlist = new Playlist();
            playlist.listId = text(item, "listid");
            playlist.collectionId = text(item, "global_collection_id");
            playlist.id = KuGouSign.isBlank(playlist.collectionId) ? playlist.listId : playlist.collectionId;
            playlist.name = firstText(item, "name", "listname", "specialname");
            playlist.coverTemplate = firstText(item, "pic", "imgurl", "cover", "create_user_pic");
            playlist.description = text(item, "intro");
            playlist.creator = text(item, "list_create_username");
            playlist.trackCount = integer(item, "count", "per_count", "songcount", "musiccount");
            if (KuGouSign.isBlank(playlist.id) || KuGouSign.isBlank(playlist.name)) continue;
            result.add(playlist);
        }
        return result;
    }

    /**
     * 「我喜欢」歌单。{@code listid} 固定是 2，但云端主键要从账号歌单列表里取。
     *
     * @return 找不到时返回 {@code null}
     */
    public static Playlist likedPlaylist() {
        for (Playlist playlist : userPlaylists(60)) {
            if ("2".equals(playlist.listId)) return playlist;
        }
        return null;
    }

    /**
     * 歌单的「可删除」视图：带 {@code fileid} 的曲目。
     *
     * <p>删曲必须有 {@code fileid}，而枚举曲目的那个端点<b>不返回</b>它。
     * 所以删除链路要走这条 {@code get_list_all_file_v3}，它是 {@code info} 数组而不是 {@code songs}。</p>
     */
    public static JsonArray userPlaylistFiles(String listId, int limit) {
        return userPlaylistFiles(listId, 1, limit);
    }

    /**
     * 歌单曲目（带 {@code fileid} 的那份），分页版。
     *
     * <p>单页上限是 300（{@link #MAX_PAGE_SIZE}），而「我喜欢」几百上千首是常态，
     * 所以要分页拉。总数字段在 {@code data.total}。</p>
     */
    public static JsonArray userPlaylistFiles(String listId, int page, int limit) {
        if (!KuGouDevice.isLoggedIn() || KuGouSign.isBlank(listId)) return null;
        JsonObject body = new JsonObject();
        body.addProperty("listid", listId.trim());
        body.addProperty("userid", KuGouDevice.userId());
        body.addProperty("type", 0);
        body.addProperty("page", Math.max(1, page));
        body.addProperty("pagesize", clampPageSize(limit));
        body.addProperty("area_code", 1);
        body.addProperty("allplatform", 1);
        body.addProperty("show_cover", 1);
        body.addProperty("token", KuGouDevice.token());
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/v4/get_list_all_file_v3",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID,
                router("cloudlist.service.kugou.com"));
        return array(object(root, "data"), "info");
    }

    /** {@code get_list_all_file_v3} 响应里的曲目总数；拿不到时返回 0。 */
    public static int userPlaylistFileCount(String listId) {
        if (!KuGouDevice.isLoggedIn() || KuGouSign.isBlank(listId)) return 0;
        JsonObject body = new JsonObject();
        body.addProperty("listid", listId.trim());
        body.addProperty("userid", KuGouDevice.userId());
        body.addProperty("type", 0);
        body.addProperty("page", 1);
        body.addProperty("pagesize", 1);
        body.addProperty("area_code", 1);
        body.addProperty("allplatform", 1);
        body.addProperty("token", KuGouDevice.token());
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/v4/get_list_all_file_v3",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID,
                router("cloudlist.service.kugou.com"));
        return integer(object(root, "data"), "total", "count", "percount");
    }

    /**
     * 「我喜欢」里全部曲目的 hash。
     *
     * <p>红心状态需要一个真值来源：本仓原来的做法是「本次会话里点过红心的记下来」，
     * 于是重启后所有已收藏的歌都显示成未收藏，再点一次会重复收藏。这条一次拉全，
     * 供 {@code KuGouLikedCache} 落盘做冷启动。</p>
     *
     * <p>用标准档 hash 作为标识：它与 {@link Track#hash} 同一套，其它音源解灰过来的酷狗曲目
     * 也带这个值。</p>
     *
     * @param maxCount 最多拉多少首；酷狗单页 300，超出部分按页取。传 0 表示拉到上游给的总数为止
     * @return 失败或未登录返回空列表（调用方要能区分「没收藏」与「没拉到」，所以别把空列表当结论）
     */
    public static List<String> likedHashes(int maxCount) {
        List<String> result = new ArrayList<String>();
        if (!KuGouDevice.isLoggedIn()) return result;
        Playlist liked = likedPlaylist();
        if (liked == null || KuGouSign.isBlank(liked.listId)) return result;
        int ceiling = maxCount <= 0 ? Integer.MAX_VALUE : maxCount;
        int page = 1;
        while (result.size() < ceiling) {
            JsonArray files = userPlaylistFiles(liked.listId, page, MAX_PAGE_SIZE);
            if (files == null || files.size() == 0) break;
            for (JsonElement element : files) {
                if (element == null || !element.isJsonObject()) continue;
                String hash = KuGouSign.lower(text(element.getAsJsonObject(), "hash"));
                if (!hash.isEmpty() && !result.contains(hash)) result.add(hash);
                if (result.size() >= ceiling) break;
            }
            // 上游单页封顶，返回少于一页就是最后一页；再多要一页只会拿到空数组。
            if (files.size() < MAX_PAGE_SIZE) break;
            page++;
            if (page > MAX_LIKED_PAGES) {
                System.err.println("[Music/KuGou] 收藏列表超过 " + MAX_LIKED_PAGES
                        + " 页，剩下的不再拉，冷启动索引按已拉到的部分建立");
                break;
            }
        }
        return result;
    }

    /** 收藏列表分页的上限页数，防止一个异常的总数把冷启动变成几十次串行请求。 */
    private static final int MAX_LIKED_PAGES = 20;

    /**
     * 取一首歌的动态封面视频地址（h264 MP4）。
     *
     * <p>端点 {@code kmrcdn.service.kugou.com/v2/album/audio} 与其它接口有几处不同，都踩过：</p>
     * <ul>
     *   <li>{@code data} 参数是<b>数组</b>，且要先 {@code JSON.stringify} 再 URL 编码；</li>
     *   <li>{@code query} 必须是字面量 {@code audioPlay}。实测 {@code audioInfo}/{@code audio}/
     *       {@code detail}/{@code all} 都只返回静态封面，没有 {@code dycover} 字段；</li>
     *   <li>签名与平台无关，固定标准版盐值；</li>
     *   <li><b>请求头必须最小化</b>：只发 {@code dfid}/{@code mid}/{@code clienttime}，
     *       多带项目默认的 {@code kg-*} 系列头会随机触发 sign error。所以这里绕开
     *       {@link KuGouHttp#get}，直接走 {@link KuGouHttp#requestBare}。</li>
     * </ul>
     *
     * <p>返回的 URL 带过期参数（{@code dis_t}），<b>必须即取即用</b>，不要缓存。
     * 绝大多数曲目没有动态封面，这时返回空串——这是正常情况，不是故障。</p>
     *
     * @return h264 MP4 地址；没有或失败时返回空串
     */
    public static String dynamicCoverUrl(String albumAudioId, String albumId) {
        if (KuGouSign.isBlank(albumAudioId)) return "";
        String id = albumAudioId.trim();

        JsonObject entry = new JsonObject();
        entry.addProperty("album_audio_id", number(id));
        // album_id 可以省；带上能让上游少一次反查。只有它是正数时才塞。
        String album = KuGouSign.nonNull(albumId).trim();
        if (!album.isEmpty() && !"0".equals(album)) {
            try {
                if (Long.parseLong(album) > 0L) entry.addProperty("album_id", Long.parseLong(album));
            } catch (NumberFormatException notNumeric) {
                // album_id 不是数字就当没有，不因为一个可选字段整体失败。
            }
        }
        JsonArray data = new JsonArray();
        data.add(entry);

        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put("appid", KuGouHttp.APP_ID);
        raw.put("clientver", KuGouHttp.CLIENT_VER);
        raw.put("data", data.toString());
        raw.put("isCdn", "1");
        raw.put("query", "audioPlay");
        // 签名按「按 key 排序后的 k=v（对象先 JSON.stringify）」算，与 Android 签名同一套规则。
        Map<String, String> signed = new LinkedHashMap<String, String>(raw);
        signed.put("signature", KuGouSign.android(raw, null));

        byte[] response = KuGouHttp.requestBare("GET",
                KuGouHttp.KMR_CDN + "/v2/album/audio?" + encodeQuery(signed),
                KuGouHttp.kmrCdnHeaders(), null, null);
        if (response == null) return "";
        JsonObject root = KuGouHttp.parseText(new String(response, java.nio.charset.StandardCharsets.UTF_8));
        if (root == null) return "";

        JsonArray items = array(root, "data");
        if (items == null || items.size() == 0 || !items.get(0).isJsonObject()) return "";
        JsonObject item = items.get(0).getAsJsonObject();
        if (!item.has("dycover") || !item.get("dycover").isJsonObject()) return "";
        JsonObject cover = item.getAsJsonObject("dycover");

        // 优先 h264：h265 的解码器支持面窄，ffmpeg 有没有对应解码器取决于具体构建。
        String url = firstText(cover, "h264_url");
        if (KuGouSign.isBlank(url)) {
            JsonArray backup = array(cover, "h264_backup_url");
            if (backup != null && backup.size() > 0 && backup.get(0).isJsonPrimitive()) {
                url = firstText(cover, "h264_backup_url");
            }
        }
        return KuGouSign.nonNull(url).trim();
    }

    /**
     * 提交一条听歌历史（让其它设备能看到）。
     *
     * <p>和 CSCC 时长上报是两件事：这条只记「听过」，不记「听了多久」。{@code ot} 必须是
     * <b>秒</b>，传毫秒会被上游拒绝。</p>
     *
     * @param mixSongId {@code album_audio_id}
     */
    public static boolean uploadPlayHistory(String mixSongId, int playCount) {
        if (!KuGouDevice.isLoggedIn() || KuGouSign.isBlank(mixSongId)) return false;

        JsonObject song = new JsonObject();
        song.addProperty("mxid", number(mixSongId.trim()));
        song.addProperty("op", 1);
        song.addProperty("ot", System.currentTimeMillis() / 1000L);
        song.addProperty("pc", Math.max(1, playCount));
        JsonArray songs = new JsonArray();
        songs.add(song);

        JsonObject body = new JsonObject();
        body.add("songs", songs);
        body.addProperty("token", KuGouDevice.token());
        body.addProperty("userid", KuGouDevice.userId());

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("plat", "3");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/playhistory/v1/upload_songs",
                params, body.toString(), KuGouHttp.Sign.ANDROID, null);
        return isOk(root);
    }

    /** 把参数表拼成查询串。{@link KuGouHttp} 的那个是私有的，这里复制一份小的。 */
    private static String encodeQuery(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (builder.length() > 0) builder.append('&');
            builder.append(KuGouSign.urlEncode(entry.getKey()))
                    .append('=')
                    .append(KuGouSign.urlEncode(entry.getValue()));
        }
        return builder.toString();
    }

    // ── 歌单写操作 ──────────────────────────────────────────────────────────
    /**
     * 往歌单加一首歌。
     *
     * <p>{@code slow_upload} 与 {@code scene} 不能省：少了它们上游会走另一条校验分支，
     * 表现为加歌成功但歌单里看不到这首歌。</p>
     *
     * @return 是否成功
     */
    public static boolean addTrackToPlaylist(String listId, Track track) {
        if (!KuGouDevice.isLoggedIn() || KuGouSign.isBlank(listId) || track == null) return false;
        if (KuGouSign.isBlank(track.hash)) return false;
        long clientTime = System.currentTimeMillis() / 1000L;

        JsonObject entry = new JsonObject();
        entry.addProperty("number", 1);
        entry.addProperty("name", KuGouSign.nonNull(track.artist) + " - " + KuGouSign.nonNull(track.name));
        entry.addProperty("hash", KuGouSign.lower(track.hash));
        entry.addProperty("size", 0);
        entry.addProperty("sort", 0);
        entry.addProperty("timelen", 0);
        entry.addProperty("bitrate", 0);
        entry.addProperty("album_id", number(track.albumId));
        entry.addProperty("mixsongid", number(firstNonBlank(track.mixSongId, track.albumAudioId)));
        JsonArray data = new JsonArray();
        data.add(entry);

        JsonObject body = new JsonObject();
        body.addProperty("userid", KuGouDevice.userId());
        body.addProperty("token", KuGouDevice.token());
        body.addProperty("listid", number(listId));
        body.addProperty("list_ver", 0);
        body.addProperty("type", 0);
        body.addProperty("slow_upload", 1);
        body.addProperty("scene", "false;null");
        body.add("data", data);

        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("last_time", String.valueOf(clientTime));
        params.put("last_area", "gztx");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/cloudlist.service/v6/add_song",
                params, body.toString(), KuGouHttp.Sign.ANDROID, null);
        return isOk(root);
    }

    /** 从歌单删除一首歌。{@code fileId} 来自 {@link #userPlaylistFiles}。 */
    public static boolean removeTrackFromPlaylist(String listId, String fileId) {
        if (!KuGouDevice.isLoggedIn() || KuGouSign.isBlank(listId) || KuGouSign.isBlank(fileId)) return false;

        JsonObject entry = new JsonObject();
        entry.addProperty("fileid", number(fileId));
        JsonArray data = new JsonArray();
        data.add(entry);

        JsonObject body = new JsonObject();
        body.addProperty("listid", number(listId));
        body.addProperty("userid", KuGouDevice.userId());
        body.add("data", data);
        body.addProperty("type", 0);
        body.addProperty("token", KuGouDevice.token());
        body.addProperty("list_ver", 0);

        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/v4/delete_songs",
                KuGouHttp.deviceParams(), body.toString(), KuGouHttp.Sign.ANDROID,
                router("cloudlist.service.kugou.com"));
        return isOk(root);
    }

    /**
     * 新建 / 收藏歌单。
     *
     * <p><b>不能带 {@code x-router}。</b>这是云歌单服务里唯一一个走裸网关的端点：
     * 带上 {@code x-router: cloudlist.service.kugou.com} 会被 openresty 直接 404，
     * 不带才进得了后端。同族的 {@code /v7/get_all_list}、
     * {@code /v4/get_list_all_file_v3} 则**必须**带 —— 方向正好相反，别照着抄。</p>
     */
    public static boolean createPlaylist(String name) {
        if (!KuGouDevice.isLoggedIn() || KuGouSign.isBlank(name)) return false;
        long clientTime = System.currentTimeMillis() / 1000L;

        JsonObject body = new JsonObject();
        body.addProperty("userid", KuGouDevice.userId());
        body.addProperty("token", KuGouDevice.token());
        body.addProperty("total_ver", 0);
        body.addProperty("name", name.trim());
        body.addProperty("type", 0);
        body.addProperty("source", 1);
        body.addProperty("is_pri", 0);
        body.addProperty("list_create_userid", KuGouDevice.userId());
        body.addProperty("list_create_listid", 0);
        body.addProperty("list_create_gid", "");
        body.addProperty("from_shupinmv", 0);

        return submitAddList(body, clientTime);
    }

    /**
     * 收藏别人的歌单。
     *
     * <p>与 {@link #createPlaylist} 同一个端点，差别只在 {@code type=1} 以及必须带上原歌单的
     * 三个主键：{@code list_create_userid} / {@code list_create_listid} / {@code list_create_gid}。
     * 少任何一个上游都会安静地建出一个空壳歌单，而不是报错。{@code source} 在收藏时也传 1；
     * {@code is_pri} 只在新建（{@code type=0}）时被上游读取，这里保持 0。</p>
     *
     * <p><b>服务端会忽略 {@code name}</b>：收藏成功后列表里显示的是<b>原歌单的名字</b>，
     * 并且 {@code count} 会同步成原歌单的曲目数。所以传进来的 name 只用于失败时的日志，
     * 不要指望它能改名。</p>
     *
     * @param creatorUserId 原歌单创建者的 userid，从 {@code global_collection_id} 第 3 段解析
     * @param creatorListId 原歌单在创建者账号下的 listid，从同一 id 的第 4 段解析
     * @param globalId      原歌单的 {@code collection_...} 主键，原样回传
     */
    public static boolean collectPlaylist(String name, String creatorUserId, String creatorListId,
                                          String globalId) {
        if (!KuGouDevice.isLoggedIn()) return false;
        if (KuGouSign.isBlank(creatorListId) || KuGouSign.isBlank(globalId)) return false;
        long clientTime = System.currentTimeMillis() / 1000L;

        JsonObject body = new JsonObject();
        body.addProperty("userid", KuGouDevice.userId());
        body.addProperty("token", KuGouDevice.token());
        body.addProperty("total_ver", 0);
        // 服务端会用自己的名字覆盖它；留着只是为了请求体形状与原客户端一致。
        body.addProperty("name", KuGouSign.nonNull(name).trim());
        body.addProperty("type", 1);
        body.addProperty("source", 1);
        body.addProperty("is_pri", 0);
        body.addProperty("list_create_userid", KuGouSign.nonNull(creatorUserId).trim());
        body.addProperty("list_create_listid", number(creatorListId));
        body.addProperty("list_create_gid", KuGouSign.nonNull(globalId).trim());
        body.addProperty("from_shupinmv", 0);

        return submitAddList(body, clientTime);
    }

    /**
     * 提交 {@code add_list}。新建与收藏共用，只有请求体不同。
     *
     * <p>注意最后那个 {@code null} —— 这里刻意<b>不</b>传 {@code x-router}，原因见
     * {@link #createPlaylist}。</p>
     */
    private static boolean submitAddList(JsonObject body, long clientTime) {
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("last_time", String.valueOf(clientTime));
        params.put("last_area", "gztx");
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/cloudlist.service/v5/add_list",
                params, body.toString(), KuGouHttp.Sign.ANDROID, null);
        return isOk(root);
    }

    /**
     * 删除 / 取消收藏歌单。
     *
     * <p><b>警告：这条链路当前未获证实可用，调用方不要把它当成能成功。</b>
     * 上游参考实现用的是 {@code /v2/delete_list} + AES/RSA 信封，代码照抄无误，
     * 但实测该端点对任何输入都返回 {@code {"status":0,"error_code":20006}}
     * （参数错误），且与参数无关：</p>
     *
     * <ul>
     *   <li>逐字节重放参考实现的请求 → 20006；</li>
     *   <li>换概念版 RSA 公钥、补 {@code userid}/{@code token}/{@code plat}、
     *       发明文 body、去掉 {@code p} → 全部 20006；</li>
     *   <li>带 {@code x-router} 才不是 404，但仍然是 20006；</li>
     *   <li><b>决定性证据</b>：完整信封与「连 {@code p} 都不带」的请求返回<b>完全相同</b>的
     *       42 字节响应 —— 说明服务端在解析 body/{@code p} 之前就拒绝了，
     *       它根本没看加密内容。</li>
     * </ul>
     *
     * <p>也就是说问题不在加密（AES/RSA 都自洽、且 {@code add_song} 用同一套
     * 签名能成功），而在该端点本身。已试过的替代路径
     * （{@code /cloudlist.service/v5/del_list}、{@code /v5/del_list}、
     * {@code /cloudlist.service/v2/del_list}）全部 404。</p>
     *
     * <p>保留实现是为了：一旦找到正确参数集可以直接改；同时避免后来者以为
     * 「没人实现过」而重新踩一遍。返回 {@code false} 时上层应给出明确提示，
     * 不要静默当作成功。</p>
     */
    public static boolean deletePlaylist(String listId) {
        if (!KuGouDevice.isLoggedIn() || KuGouSign.isBlank(listId)) return false;
        long clientTime = System.currentTimeMillis() / 1000L;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("listid", number(listId));
            payload.addProperty("total_ver", 0);
            payload.addProperty("type", 1);

            KuGouCrypto.AesPayload aes = KuGouCrypto.playlistAesEncrypt(payload.toString());
            String token = KuGouDevice.token();
            String userId = KuGouDevice.userId();
            JsonObject rsaPayload = new JsonObject();
            rsaPayload.addProperty("aes", aes.key);
            rsaPayload.addProperty("uid", number(userId));
            rsaPayload.addProperty("token", token);
            String p = KuGouCrypto.rsaEncrypt(rsaPayload.toString()).toUpperCase(Locale.ROOT);

            Map<String, String> params = KuGouHttp.deviceParams();
            params.put("clienttime", String.valueOf(clientTime));
            params.put("key", KuGouSign.paramsKey(String.valueOf(clientTime)));
            params.put("last_area", "gztx");
            params.put("last_time", String.valueOf(clientTime));
            params.put("p", p);

            byte[] response = KuGouHttp.postRaw(KuGouHttp.GATEWAY, "/v2/delete_list", params,
                    aes.str, KuGouHttp.Sign.ANDROID, router("cloudlist.service.kugou.com"), "application/json");
            if (response == null) return false;
            String decrypted = KuGouCrypto.playlistAesDecrypt(
                    new String(response, java.nio.charset.StandardCharsets.UTF_8), aes.key);
            return isOk(KuGouHttp.parseText(decrypted));
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] 删除歌单失败: " + throwable.getMessage());
            return false;
        }
    }

    // ── 歌词 ────────────────────────────────────────────────────────────────

    /**
     * 取 KRC 逐字歌词。
     *
     * @param hash       曲目 hash，上游按它精确匹配，命中率最高
     * @param durationMs 时长，用于在多个候选里挑最接近的一份；{@code <= 0} 时不参与打分
     * @param keyword    "歌手 - 歌名"，hash 匹配不到时的兜底检索词
     * @return 永不为 {@code null}；没有逐字内容时 {@code yrc} 为空串
     */
    public static KrcLyrics.Decoded lyric(String hash, long durationMs, String keyword) {
        String content = lyricContent(hash, durationMs, keyword, "krc");
        if (KuGouSign.isBlank(content)) return new KrcLyrics.Decoded("", "");
        return KrcLyrics.decode(content);
    }

    /**
     * 歌词候选列表（原始 JSON），交给调用方自己打分。
     *
     * <p>给「非酷狗曲目跨源取 KRC」用：那种情况没有 hash 可精确匹配，只能按关键词搜出一批候选，
     * 而<b>挑哪一个不能只看时长</b>——同名不同版本的时长可能只差几秒，必须由调用方用曲名/歌手/
     * 专辑一起判断。这里因此只做检索，不做选择。</p>
     *
     * @param hash    曲目 hash；非酷狗曲目传空串
     * @param keyword "歌手 - 歌名"
     * @return 永不为 {@code null}；没有候选时为空列表
     */
    public static List<JsonObject> lyricCandidates(String hash, String keyword) {
        List<JsonObject> result = new ArrayList<JsonObject>();
        JsonArray candidates = searchLyric(hash, keyword);
        if (candidates == null) return result;
        for (JsonElement element : candidates) {
            if (element != null && element.isJsonObject()) result.add(element.getAsJsonObject());
        }
        return result;
    }

    /**
     * 按指定候选下载并解密 KRC。
     *
     * @return 永不为 {@code null}；候选无效或下载失败时 {@code yrc} 为空串
     */
    public static KrcLyrics.Decoded lyric(JsonObject candidate) {
        String content = lyricContent(candidate, "krc");
        if (KuGouSign.isBlank(content)) return new KrcLyrics.Decoded("", "");
        return KrcLyrics.decode(content);
    }

    /**
     * 取行级 LRC 歌词，作为 KRC 解不出逐字时的兜底。
     *
     * <p>单独一条而不是从 KRC 降级得来：行级 KRC 会丢掉翻译，而 {@code fmt=lrc} 的那一份自带
     * 上游合并好的译文，作为普通歌词更完整。</p>
     *
     * @return 明文 LRC；拿不到时为空串
     */
    public static String lyricLrc(String hash, long durationMs, String keyword) {
        String content = lyricContent(hash, durationMs, keyword, "lrc");
        if (KuGouSign.isBlank(content)) return "";
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(content.trim());
            if (decoded == null || decoded.length == 0) return "";
            String text = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (Throwable throwable) {
            return "";
        }
    }

    /** 检索候选 → 下载，返回上游 {@code content} 原文（两种 fmt 都是 base64）。 */
    private static String lyricContent(String hash, long durationMs, String keyword, String format) {
        return lyricContent(bestLyricCandidate(hash, durationMs, keyword), format);
    }

    /** 下载指定候选的歌词正文（base64）。歌词接口免签，不需要设备参数。 */
    private static String lyricContent(JsonObject candidate, String format) {
        if (candidate == null) return "";
        String id = text(candidate, "id");
        String accessKey = firstText(candidate, "accesskey", "accessKey");
        if (KuGouSign.isBlank(id) || KuGouSign.isBlank(accessKey)) return "";

        Map<String, String> params = KuGouHttp.params();
        params.put("ver", "1");
        params.put("client", "pc");
        params.put("id", id);
        params.put("accesskey", accessKey);
        params.put("fmt", format);
        params.put("charset", "utf8");
        JsonObject root = KuGouHttp.get(KuGouHttp.LYRICS, "/download", params, KuGouHttp.Sign.NONE);
        return text(root, "content");
    }

    /** 按 hash 检索候选，失败再按关键词；时长最接近的胜出。 */
    private static JsonObject bestLyricCandidate(String hash, long durationMs, String keyword) {
        JsonArray candidates = searchLyric(hash, null);
        if (candidates == null || candidates.size() == 0) candidates = searchLyric(null, keyword);
        if (candidates == null || candidates.size() == 0) return null;

        JsonObject best = null;
        long bestDelta = Long.MAX_VALUE;
        for (JsonElement element : candidates) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (durationMs <= 0L) return item;
            long candidateDuration = lyricCandidateDurationMillis(item);
            long delta = candidateDuration <= 0L ? Long.MAX_VALUE : Math.abs(candidateDuration - durationMs);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = item;
            }
        }
        return best;
    }

    /**
     * 歌词候选的时长统一到毫秒。
     *
     * <p>酷狗歌词库里新老条目并存：老条目按<b>秒</b>给（{@code 314}），新条目按<b>毫秒</b>给
     * （{@code 255582}）。拿 {@code 314} 直接和毫秒比，差值永远是几十万，于是「最近的候选」
     * 实际上退化成「最短的候选」——关键词兜底那一路上就会挑错版本。</p>
     */
    private static long lyricCandidateDurationMillis(JsonObject candidate) {
        long raw = longValue(candidate, "duration");
        if (raw <= 0L) return 0L;
        return raw < 10_000L ? raw * 1000L : raw;
    }

    private static JsonArray searchLyric(String hash, String keyword) {
        if (KuGouSign.isBlank(hash) && KuGouSign.isBlank(keyword)) return null;
        Map<String, String> params = KuGouHttp.params();
        params.put("ver", "1");
        params.put("man", "yes");
        params.put("client", "pc");
        if (!KuGouSign.isBlank(hash)) params.put("hash", KuGouSign.lower(hash));
        if (!KuGouSign.isBlank(keyword)) params.put("keyword", keyword.trim());
        JsonObject root = KuGouHttp.get(KuGouHttp.LYRICS, "/search", params, KuGouHttp.Sign.NONE);
        return root == null ? null : array(root, "candidates");
    }

    // ── 扫码登录 ────────────────────────────────────────────────────────────

    /**
     * 申请一个扫码会话。
     *
     * <p>上游同时给了轮询用的 {@code qrcode} key 和一张 base64 PNG。用上游的图比本地生成可靠：
     * 二维码里除了 key 还有酷狗自己的跳转参数，本地拼错了手机端扫不出。</p>
     *
     * @return 失败返回 {@code null}
     */
    public static QrSession createQrSession() {
        return createQrSession(KuGouProfile.active());
    }

    /**
     * 按指定身份申请扫码会话。
     *
     * <p>两个 {@code appid} 不是一回事，混用会得到「扫码成功但会员状态与取流权限对不上」的凭证：</p>
     * <ul>
     *   <li>请求参数 {@code appid} 固定 {@code 1001}（登录服务自己的标识）；</li>
     *   <li>二维码内容里嵌的 {@code appid} 才是<b>要登录的客户端</b>——标准版 {@code 1005}、
     *       概念版 {@code 3116}（对照 {@code module/login_qr_key.js} 里那个随 lite 开关变的
     *       {@code appid} 变量）。</li>
     * </ul>
     *
     * <p>签名用 {@link KuGouHttp.Sign#WEB}：登录服务只有 Web 一套盐值，与客户端身份无关。</p>
     */
    public static QrSession createQrSession(KuGouProfile profile) {
        KuGouProfile used = profile == null ? KuGouProfile.STANDARD : profile;
        Map<String, String> params = KuGouHttp.deviceParams();
        // appid 在这个端点上必须是客户端 appid（1001），不是内容接口用的 1005/3116。
        params.put("appid", KuGouProfile.QR_REQUEST_APP_ID);
        params.put("type", "1");
        params.put("plat", "4");
        params.put("qrcode_txt", "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid="
                + used.qrTextAppId() + "&");
        params.put("srcappid", KuGouHttp.SRC_APP_ID);
        JsonObject root = KuGouHttp.get(KuGouHttp.LOGIN_USER, "/v2/qrcode", params, KuGouHttp.Sign.WEB);
        if (!isOk(root)) return null;
        JsonObject data = object(root, "data");
        if (data == null) return null;
        String key = text(data, "qrcode");
        if (KuGouSign.isBlank(key)) return null;
        return new QrSession(key, toImageDataUrl(text(data, "qrcode_img")), used);
    }

    /** 申请一个扫码 key；失败返回空串。二维码内容是 {@link #qrContent(String)}。 */
    public static String createQrKey() {
        QrSession session = createQrSession();
        return session == null ? "" : session.key;
    }

    /** 手机酷狗扫这个 URL 即可确认登录（标准版；概念版见 {@link #qrContent(String, KuGouProfile)}）。 */
    public static String qrContent(String key) {
        return "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=" + KuGouSign.nonNull(key);
    }

    /**
     * 按身份拼二维码内容。
     *
     * <p><b>标准版这一条刻意保持原样</b>（不带 {@code appid}）：它是实测能扫通的那条 URL，
     * 而「不带」并不影响 h5 页面判断要拉起哪个客户端。概念版没有这个实测结论，所以照
     * {@code module/login_qr_key.js} 的 {@code qrcode_txt} 带上 {@code appid=3116}——
     * 参考实现里那个变量就是随 lite 开关变的，二维码页要靠它决定授权给哪个客户端。</p>
     *
     * <p>即便如此，UI 仍应<b>优先用上游回的 {@code qrcode_img} 图</b>
     * （见 {@link QrSession#imageDataUrl}）：概念版从没测过，上游给什么就是什么，
     * 本地拼串只是它没给图时的兜底。</p>
     */
    public static String qrContent(String key, KuGouProfile profile) {
        String base = "https://h5.kugou.com/apps/loginQRCode/html/index.html";
        if (profile == null || profile == KuGouProfile.STANDARD) return qrContent(key);
        return base + "?appid=" + profile.qrTextAppId() + "&qrcode=" + KuGouSign.nonNull(key);
    }

    /** 轮询一次扫码状态（按当前身份）。返回 {@code null} 表示这次请求本身失败（网络/协议），调用方应重试而不是判失效。 */
    public static QrStatus checkQr(String key) {
        return checkQr(key, KuGouProfile.active());
    }

    /**
     * 轮询一次扫码状态。
     *
     * <p>{@code profile} 要传 {@link QrSession#profile}（也就是当初申请二维码时那个身份）：
     * 参考实现的 {@code login_qr_check} 把客户端 {@code appid} 一起带上，而它是随 lite 开关变的。
     * 用错身份时上游可能照样回 {@code status=4} 并给一张对不上号的凭证。</p>
     */
    public static QrStatus checkQr(String key, KuGouProfile profile) {
        if (KuGouSign.isBlank(key)) return null;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("plat", "4");
        params.put("srcappid", KuGouHttp.SRC_APP_ID);
        params.put("appid", (profile == null ? KuGouProfile.STANDARD : profile).appId());
        params.put("qrcode", key.trim());
        params.put("dev", "");
        JsonObject root = KuGouHttp.get(KuGouHttp.LOGIN_USER, "/v2/get_userinfo_qrcode", params, KuGouHttp.Sign.WEB);
        if (root == null) return null;
        JsonObject data = object(root, "data");
        if (data == null) return null;
        return new QrStatus(integer(data, "status"), text(data, "token"),
                firstText(data, "userid", "user_id"),
                firstText(data, "nickname", "username", "nickName"),
                firstText(data, "pic", "avatar", "img"));
    }

    /**
     * 用现成的 {@code token} + {@code userid} 登录（「粘贴凭证」入口）。
     *
     * <p><b>必须是 App 侧的 token。</b>浏览器 Cookie 里那个 {@code token} 是 Web 会话凭证
     * （{@code a_id=1014}），在网关上会稳定报 {@code 20006}/{@code 20017}——这不是参数问题，
     * 是两种凭证本来就不同源。所以入口文案要写清楚「从酷狗 App 抓包或扫码得到」，
     * 否则用户会拿着浏览器 Cookie 反复试。</p>
     *
     * <p>校验方式是「先落盘、再打一个必须要登录态的只读请求」：酷狗没有独立的
     * {@code check_token} 端点，而 {@code get_all_list} 与 {@code get_union_vip} 都只在凭证有效时给数据。
     * 两者都拿不到内容就判定失败并回滚到原来的登录态。</p>
     *
     * @param profile 这份凭证所属的客户端身份；概念版抓到的 token 要用 {@link KuGouProfile#CONCEPT}
     * @return 凭证可用时返回 {@code true}
     */
    public static boolean loginWithToken(String token, String userId, KuGouProfile profile) {
        String cleanToken = KuGouSign.nonNull(token).trim();
        String cleanUserId = KuGouSign.nonNull(userId).trim();
        if (cleanToken.isEmpty() || cleanUserId.isEmpty() || "0".equals(cleanUserId)) return false;

        // 回滚用的旧状态：粘贴错了凭证不该把已经登录的账号顶掉。
        String previousToken = KuGouDevice.token();
        String previousUserId = KuGouDevice.userId();
        KuGouProfile previousProfile = KuGouProfile.active();

        KuGouDevice.saveLogin(cleanToken, cleanUserId, "", "", profile);
        boolean works = verifyCurrentCredential();
        if (!works) {
            KuGouDevice.saveLogin(previousToken, previousUserId, "", "", previousProfile);
        }
        return works;
    }

    /**
     * 当前凭证是否可用。
     *
     * <p>两条独立证据取<b>或</b>：会员查询与账号歌单在风控或临时故障时会各挂一次，
     * 要求两条同时成功会把有效凭证判成无效。两条都空才失败。</p>
     */
    public static boolean verifyCurrentCredential() {
        if (!KuGouDevice.isLoggedIn()) return false;
        try {
            if (vipInfo() != null) return true;
        } catch (Throwable ignored) {
            // 落到下一条证据
        }
        try {
            return !userPlaylists(5).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── 解析：各端点的字段形态 ──────────────────────────────────────────────

    /**
     * {@code /v3/search/song} 的 PascalCase 条目。
     *
     * <p>三个音质各是一个子对象（{@code HQ}/{@code SQ}/{@code Res}），里面才有 hash，
     * 而顶层那个 {@code FileHash} 只是标准档。</p>
     */
    private static Track parseSearchTrack(JsonObject item) {
        if (item == null) return null;
        Track track = new Track();
        track.hash = KuGouSign.lower(text(item, "FileHash"));
        if (KuGouSign.isBlank(track.hash)) return null;
        track.hash320 = KuGouSign.lower(hashOf(item, "HQ"));
        track.hashLossless = KuGouSign.lower(hashOf(item, "SQ"));
        // 母带与高品无损是**另外的 hash**，不是无损那份：拿 SQ 的 hash 去要 quality=super
        // 只会换来一次 status=2。搜索愿意给就顺手存进档位表，取流的阶梯才走得到这两档。
        String master = KuGouSign.lower(hashOf(item, "Res"));
        if (!master.isEmpty()) track.hashesByQuality.put(KuGouQuality.SUPER.key(), master);
        String hiRes = KuGouSign.lower(hashOf(item, "HI"));
        if (!hiRes.isEmpty()) track.hashesByQuality.put(KuGouQuality.HI_RES.key(), hiRes);
        track.name = firstText(item, "SongName", "OriSongName");
        track.artist = firstText(item, "SingerName");
        if (KuGouSign.isBlank(track.artist)) track.artist = singerNames(item);
        track.album = firstText(item, "AlbumName", "OriAlbumName");
        track.albumId = firstText(item, "AlbumID", "AlbumId");
        // `Audioid` 是老的 `audio_id`（Scid），**不是** `album_audio_id`。它和 `MixSongID` 是两套
        // 不同的 ID 空间：把这个值当 `album_audio_id` 传到取流端点，服务端会按那个 ID 去解析文件，
        // 交回**另一首歌**的完整文件，而 `status` 仍是 1、`extName` 仍按请求的档位填 —— 只有响应
        // 顶层 `hash` 露馅。所以 `album_audio_id` 只能来自 `MixSongID`，拿不到就留空（取流时传 0）。
        track.audioId = firstText(item, "Audioid", "AudioID");
        track.mixSongId = firstText(item, "MixSongID", "MixSongId");
        track.albumAudioId = track.mixSongId;
        track.durationMillis = normalizedDuration(item, "Duration", "TimeLength");
        track.coverTemplate = firstText(item, "Image", "AlbumImage", "sizable_cover");
        track.privilege = firstPrivilege(item, "Privilege");
        track.vip = track.privilege == 10;
        applyCoverFromTransParam(item, track);
        return KuGouSign.isBlank(track.name) ? null : track;
    }

    /** {@code /openapi/kmr/v2/rank/audio} 的条目：歌曲信息全在 {@code audio_info} 里。 */
    private static Track parseAudioInfoTrack(JsonObject item) {
        if (item == null) return null;
        Track track = new Track();
        track.name = firstText(item, "audio_name", "songname", "name");
        track.artist = firstText(item, "author_name");
        if (KuGouSign.isBlank(track.artist)) track.artist = authorNames(item);
        track.albumId = firstText(item, "album_id");
        track.albumAudioId = firstText(item, "album_audio_id");
        track.audioId = firstText(item, "audio_id");
        track.mixSongId = firstText(item, "mixsongid", "mixsong_id");

        JsonObject audio = object(item, "audio_info");
        if (audio != null) {
            track.hash = KuGouSign.lower(firstText(audio, "hash", "hash_128"));
            track.hash320 = KuGouSign.lower(text(audio, "hash_320"));
            track.hashLossless = KuGouSign.lower(firstText(audio, "hash_flac", "hash_super"));
            track.durationMillis = normalizedDuration(audio, "duration", "duration_128");
        }
        JsonObject album = object(item, "album_info");
        if (album != null) {
            track.album = text(album, "album_name");
            track.coverTemplate = firstText(album, "sizable_cover", "cover");
        }
        track.privilege = privilegeFromDownload(item);
        track.vip = track.privilege == 10;
        if (KuGouSign.isBlank(track.hash)) return null;
        return KuGouSign.isBlank(track.name) ? null : track;
    }

    /** {@code /v1/album_audio/lite} 的三层结构：{@code base} + {@code audio_info} + {@code album_info}。 */
    private static Track parseLayeredTrack(JsonObject item) {
        if (item == null) return null;
        Track track = new Track();
        JsonObject base = object(item, "base");
        if (base != null) {
            track.name = firstText(base, "audio_name", "songname");
            track.artist = text(base, "author_name");
            track.albumId = text(base, "album_id");
            track.albumAudioId = text(base, "album_audio_id");
            track.audioId = text(base, "audio_id");
        }
        JsonObject audio = object(item, "audio_info");
        if (audio != null) {
            track.hash = KuGouSign.lower(firstText(audio, "hash", "hash_128"));
            track.hash320 = KuGouSign.lower(text(audio, "hash_320"));
            track.hashLossless = KuGouSign.lower(text(audio, "hash_flac"));
            track.durationMillis = normalizedDuration(audio, "duration", "duration_128");
        }
        JsonObject album = object(item, "album_info");
        if (album != null) {
            track.album = text(album, "album_name");
            track.coverTemplate = firstText(album, "sizable_cover", "cover");
        }
        if (KuGouSign.isBlank(track.artist)) track.artist = authorNames(item);
        if (KuGouSign.isBlank(track.name)) track.name = firstText(item, "audio_name", "songname");
        track.privilege = privilegeFromDownload(item);
        track.vip = track.privilege == 10;
        if (KuGouSign.isBlank(track.hash)) return null;
        return KuGouSign.isBlank(track.name) ? null : track;
    }

    /** {@code openapi} 歌手单曲：字段是扁平的 {@code hash/hash_320/hash_flac}。 */
    private static Track parseOpenApiTrack(JsonObject item) {
        if (item == null) return null;
        Track track = new Track();
        track.hash = KuGouSign.lower(firstText(item, "hash", "hash_128"));
        if (KuGouSign.isBlank(track.hash)) return null;
        track.hash320 = KuGouSign.lower(text(item, "hash_320"));
        track.hashLossless = KuGouSign.lower(firstText(item, "hash_flac", "hash_super"));
        track.name = firstText(item, "audio_name", "songname", "name");
        track.artist = firstText(item, "author_name");
        track.album = firstText(item, "album_name");
        track.albumId = text(item, "album_id");
        track.albumAudioId = text(item, "album_audio_id");
        track.audioId = text(item, "audio_id");
        track.mixSongId = firstText(item, "mixsongid", "mixsong_id");
        track.durationMillis = normalizedDuration(item, "timelength", "duration");
        track.coverTemplate = firstText(item, "sizable_cover", "cover");
        track.privilege = firstPrivilege(item, "privilege");
        track.vip = track.privilege == 10;
        applyCoverFromTransParam(item, track);
        return KuGouSign.isBlank(track.name) ? null : track;
    }

    /**
     * 云歌单曲目。音质档位在 {@code relate_goods} 数组里，不在扁平字段上。
     */
    private static Track parseCollectionTrack(JsonObject item) {
        if (item == null) return null;
        Track track = new Track();
        track.hash = KuGouSign.lower(text(item, "hash"));
        if (KuGouSign.isBlank(track.hash)) return null;
        track.hash320 = KuGouSign.lower(text(item, "hash_320"));
        track.hashLossless = KuGouSign.lower(text(item, "hash_flac"));
        applyRelatedQualityHashes(item, track);
        track.fileId = text(item, "fileid");
        track.albumId = firstText(item, "album_id", "albumid");
        track.albumAudioId = firstText(item, "album_audio_id", "mixsongid");
        track.audioId = firstText(item, "audio_id", "mixsongid");
        track.mixSongId = firstText(item, "mixsongid", "mixsong_id");
        track.durationMillis = normalizedDuration(item, "timelen", "duration", "timelength");
        track.privilege = firstPrivilege(item, "privilege", "media_pay_type");
        track.vip = track.privilege == 10;

        // 云歌单的歌名常是「歌手 - 歌名.mp3」，而 singerinfo 里有干净的两段。
        track.artist = singerNames(item);
        track.name = firstText(item, "songname", "name", "filename");
        JsonObject album = object(item, "albuminfo");
        if (album != null) {
            track.album = firstText(album, "album_name", "name");
            track.coverTemplate = firstText(album, "sizable_cover", "cover");
        }
        if (KuGouSign.isBlank(track.album)) track.album = text(item, "album_name");
        track.coverTemplate = KuGouSign.isBlank(track.coverTemplate)
                ? firstText(item, "cover", "img") : track.coverTemplate;
        applyCoverFromTransParam(item, track);
        splitArtistAndName(track, firstText(item, "filename", "name"));
        return KuGouSign.isBlank(track.name) ? null : track;
    }

    /** 每日推荐 / FM / 历史共用的扁平结构（{@code hash / hash_320 / hash_flac}）。 */
    private static Track parseFlatTrack(JsonObject item) {
        if (item == null) return null;
        Track track = new Track();
        track.hash = KuGouSign.lower(firstText(item, "hash", "hash_128"));
        if (KuGouSign.isBlank(track.hash)) return null;
        track.hash320 = KuGouSign.lower(text(item, "hash_320"));
        track.hashLossless = KuGouSign.lower(firstText(item, "hash_flac", "hash_super"));
        applyRelatedQualityHashes(item, track);
        track.name = firstText(item, "songname", "song_name", "audio_name", "name", "ori_audio_name");
        track.artist = firstText(item, "author_name", "singername");
        if (KuGouSign.isBlank(track.artist)) track.artist = singerNames(item);
        track.album = firstText(item, "album_name");
        track.albumId = firstText(item, "album_id", "albumid");
        track.albumAudioId = firstText(item, "album_audio_id", "mixsongid");
        track.audioId = firstText(item, "audio_id", "mixsongid");
        track.mixSongId = firstText(item, "mixsongid", "mixsong_id");
        track.durationMillis = normalizedDuration(item, "timelength", "timelen", "time_length", "duration");
        track.coverTemplate = firstText(item, "sizable_cover", "cover", "img");
        track.privilege = firstPrivilege(item, "privilege");
        track.vip = track.privilege == 10;
        applyCoverFromTransParam(item, track);
        // 歌名兜底：有些端点只给 "歌手 - 歌名" 的 filename。
        if (KuGouSign.isBlank(track.name)) {
            splitArtistAndName(track, firstText(item, "filename", "filename_128"));
        }
        return KuGouSign.isBlank(track.name) ? null : track;
    }

    /** 老 mobilecdn 端点（特别歌单曲目等）的条目。 */
    private static Track parseLegacyTrack(JsonObject item) {
        if (item == null) return null;
        Track track = new Track();
        track.hash = KuGouSign.lower(firstText(item, "hash", "320hash"));
        if (KuGouSign.isBlank(track.hash)) return null;
        track.hash320 = KuGouSign.lower(text(item, "320hash"));
        track.hashLossless = KuGouSign.lower(text(item, "sqhash"));
        track.albumId = firstText(item, "album_id");
        // 同 parseSearchTrack：`audio_id` 与 `album_audio_id` 不是一套 ID，不能当兜底，
        // 宁可留空让取流传 0 —— 传错 ID 会拿到别人的文件。
        track.albumAudioId = firstText(item, "album_audio_id", "mixsongid");
        track.audioId = text(item, "audio_id");
        track.durationMillis = normalizedDuration(item, "duration", "timelength");
        track.name = firstText(item, "songname", "filename");
        track.artist = firstText(item, "singername");
        track.album = firstText(item, "album_name");
        track.coverTemplate = firstText(item, "img", "imgurl", "album_sizable_cover");
        track.privilege = firstPrivilege(item, "privilege");
        track.vip = track.privilege == 10;
        applyCoverFromTransParam(item, track);
        if (KuGouSign.isBlank(track.artist)) splitArtistAndName(track, text(item, "filename"));
        return KuGouSign.isBlank(track.name) ? null : track;
    }

    /**
     * 把「歌手 - 歌名」拆开填进 {@code artist}/{@code name}。
     *
     * <p>只在该字段确实含分隔符时生效，且<b>不覆盖</b>已有的非空值：云歌单的
     * {@code singerinfo} 比 filename 里的更准确（多人合唱的分隔符不统一）。</p>
     */
    private static void splitArtistAndName(Track track, String fileName) {
        if (KuGouSign.isBlank(fileName)) return;
        int split = fileName.indexOf(" - ");
        if (split <= 0) return;
        String artist = fileName.substring(0, split).trim();
        String name = fileName.substring(split + 3).trim();
        if (KuGouSign.isBlank(track.artist) && !artist.isEmpty()) track.artist = artist;
        if (KuGouSign.isBlank(track.name) && !name.isEmpty()) track.name = stripExtension(name);
    }

    private static String stripExtension(String name) {
        if (name.length() > 4) {
            String tail = name.substring(name.length() - 4).toLowerCase(Locale.ROOT);
            if (tail.equals(".mp3") || tail.equals(".flac") || tail.equals(".m4a") || tail.equals(".ape")) {
                return name.substring(0, name.length() - 4);
            }
        }
        return name;
    }

    /**
     * 云歌单用 {@code relate_goods} 给音质档位：每个元素是 {@code {bitrate, hash}}。
     *
     * <p>阈值取 700/300 kbps：无损普遍 700+，320 档在 320 附近。用区间而不是等值判断，
     * 是因为上游给的是实际码率（320 档常写成 321）。</p>
     */
    private static void applyRelatedQualityHashes(JsonObject item, Track track) {
        JsonArray related = array(item, "relate_goods");
        if (related == null) return;
        for (JsonElement element : related) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject quality = element.getAsJsonObject();
            int bitrate = integer(quality, "bitrate");
            String hash = KuGouSign.lower(firstText(quality, "hash"));
            if (KuGouSign.isBlank(hash)) continue;
            if (bitrate >= 700) {
                if (KuGouSign.isBlank(track.hashLossless)) track.hashLossless = hash;
            } else if (bitrate >= 300) {
                if (KuGouSign.isBlank(track.hash320)) track.hash320 = hash;
            }
            // 上游偶尔直接标档位名（flac / high / viper_tape…）。认出来就按档位存，
            // 这比按码率猜准：母带与高品无损都是 700+，只看看不出区别。
            KuGouQuality named = KuGouQuality.fromKey(firstText(quality, "quality", "type", "quality_key"));
            if (named != null) track.hashesByQuality.put(named.key(), hash);
        }
    }

    /** 封面的备用来源：{@code trans_param.union_cover}（云歌单常只有这个）。 */
    private static void applyCoverFromTransParam(JsonObject item, Track track) {
        if (!KuGouSign.isBlank(track.coverTemplate)) return;
        JsonObject transParam = object(item, "trans_param");
        if (transParam != null) track.coverTemplate = text(transParam, "union_cover");
    }

    /** {@code HQ}/{@code SQ}/{@code Res} 子对象里的 {@code Hash}。 */
    private static String hashOf(JsonObject item, String name) {
        JsonObject quality = object(item, name);
        return quality == null ? "" : firstText(quality, "Hash", "hash");
    }

    /**
     * 榜单的 {@code privilege_download} 里的 {@code privilege}。
     *
     * <p>这一档单独处理：它是个对象而不是数字，直接读 {@code privilege} 会拿到 0，
     * 于是所有会员曲都会被当成免费曲，UI 上不会显示 VIP 标记。</p>
     */
    private static int privilegeFromDownload(JsonObject item) {
        JsonObject download = object(item, "privilege_download");
        if (download != null) {
            int value = integer(download, "privilege");
            if (value != 0) return value;
        }
        return firstPrivilege(item, "privilege");
    }

    private static int firstPrivilege(JsonObject item, String... names) {
        if (item == null) return 0;
        for (String name : names) {
            JsonElement element = item.get(name);
            if (element == null || element.isJsonNull()) continue;
            try {
                if (element.isJsonObject()) {
                    // cloud 歌单的 privilege 偶尔是 {privilege: 10} 这样的包装。
                    return firstPrivilege(element.getAsJsonObject(), "privilege", "value");
                }
                return element.getAsInt();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /**
     * 时长统一到毫秒。
     *
     * <p>不同端点分别用秒（搜索的 {@code Duration}）和毫秒（歌单的 {@code timelen}）。
     * 用「小于 10 小时就当秒」判定：没有一首正常的歌时长会超过 10 小时，而毫秒值动辄六位数。</p>
     */
    private static long normalizedDuration(JsonObject item, String... names) {
        long raw = longValue(item, names);
        if (raw <= 0L) return 0L;
        return raw < 36_000L ? raw * 1000L : raw;
    }

    private static String singerNames(JsonObject item) {
        JsonArray singers = array(item, "singerinfo");
        if (singers == null) return "";
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : singers) {
            if (element == null || !element.isJsonObject()) continue;
            String name = firstText(element.getAsJsonObject(), "name", "author_name");
            if (KuGouSign.isBlank(name)) continue;
            if (builder.length() > 0) builder.append("、");
            builder.append(name);
        }
        return builder.toString();
    }

    private static String authorNames(JsonObject item) {
        JsonArray authors = array(item, "authors");
        if (authors == null) return "";
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : authors) {
            if (!element.isJsonObject()) continue;
            String name = firstText(element.getAsJsonObject(), "author_name", "name");
            if (KuGouSign.isBlank(name)) continue;
            if (builder.length() > 0) builder.append("、");
            builder.append(name);
        }
        return builder.toString();
    }

    /** 歌手简介：{@code long_intro} 是个字符串数组。 */
    private static String artistIntro(JsonObject data) {
        JsonArray intro = array(data, "long_intro");
        if (intro == null) return text(data, "intro");
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : intro) {
            if (element == null || !element.isJsonPrimitive()) continue;
            String line = element.getAsString();
            if (KuGouSign.isBlank(line)) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(line.trim());
        }
        return builder.toString();
    }

    /** 上游给的是裸 base64，转成浏览器/UI 能直接用的 data URL。 */
    private static String toImageDataUrl(String base64) {
        if (KuGouSign.isBlank(base64)) return "";
        String value = base64.trim();
        return value.startsWith("data:") ? value : "data:image/png;base64," + value;
    }

    private static Map<String, String> router(String name) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (!KuGouSign.isBlank(name)) headers.put("x-router", name);
        return headers;
    }

    // ── 解析辅助 ────────────────────────────────────────────────────────────

    private static boolean isOk(JsonObject root) {
        if (root == null) return false;
        JsonElement status = root.get("status");
        if (status != null && !status.isJsonNull() && integer(root, "status") != 1) return false;
        JsonElement errorCode = root.get("error_code");
        if (errorCode != null && !errorCode.isJsonNull() && integer(root, "error_code") != 0) return false;
        JsonElement errcode = root.get("errcode");
        if (errcode != null && !errcode.isJsonNull() && integer(root, "errcode") != 0) return false;
        return true;
    }

    private static int clampPageSize(int limit) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, limit <= 0 ? 30 : limit));
    }

    private static String firstUrl(JsonArray urls) {
        if (urls == null) return "";
        for (JsonElement element : urls) {
            if (element == null || !element.isJsonPrimitive()) continue;
            String url = element.getAsString();
            if (!KuGouSign.isBlank(url)) return url.trim();
        }
        return "";
    }

    private static String inferFormat(String url) {
        String clean = KuGouSign.lower(url);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int dot = clean.lastIndexOf('.');
        return dot >= 0 && dot < clean.length() - 1 ? clean.substring(dot + 1) : "mp3";
    }

    private static String firstNonBlank(String first, String second) {
        return KuGouSign.isBlank(first) ? KuGouSign.nonNull(second) : first;
    }

    private static long number(String value) {
        if (KuGouSign.isBlank(value)) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null) return null;
        if (name == null) return parent;
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /**
     * 取父节点下第一个存在的数组字段。
     *
     * <p>不同端点的「列表」叫法不统一（{@code lists}/{@code info}/{@code songs}），
     * 而且有的端点把数组直接放在顶层。分两个方法而不是给一个方法加「传 null 表示父节点自己就是数组」
     * 的约定——那种约定会让调用点写成 {@code array(x, null)}，既读不懂也容易误传。</p>
     */
    private static JsonArray array(JsonObject parent, String... names) {
        if (parent == null) return null;
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element != null && element.isJsonArray()) return element.getAsJsonArray();
        }
        return null;
    }

    /**
     * 从 {@code root} 或其子节点里取第一个数组。
     *
     * <p>用于「{@code data} 有时是对象、有时直接是数组」的端点：{@code getSearchTip} 与
     * {@code audio_group/author} 都属于这一类。先看 {@code root} 本身，再看 {@code root.data}。</p>
     */
    private static JsonArray pickArray(JsonObject root, String... innerNames) {
        if (root == null) return null;
        for (String name : innerNames) {
            JsonElement element = root.get(name);
            if (element != null && element.isJsonArray()) return element.getAsJsonArray();
        }
        JsonObject data = object(root, "data");
        if (data != null) {
            for (String name : innerNames) {
                JsonElement element = data.get(name);
                if (element != null && element.isJsonArray()) return element.getAsJsonArray();
            }
        }
        return null;
    }

    private static String text(JsonObject parent, String name) {
        return firstText(parent, name);
    }

    private static String firstText(JsonObject parent, String... names) {
        if (parent == null) return "";
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            String value = element.getAsString();
            if (!KuGouSign.isBlank(value)) return value.trim();
        }
        return "";
    }

    private static int integer(JsonObject parent, String... names) {
        if (parent == null) return 0;
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            try {
                return element.getAsInt();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static long longValue(JsonObject parent, String... names) {
        if (parent == null) return 0L;
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            try {
                return element.getAsLong();
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    /** 把带 {@code {size}} 的封面模板替换成具体尺寸。 */
    public static String cover(String template, int size) {
        if (KuGouSign.isBlank(template)) return "";
        return template.replace("{size}", String.valueOf(Math.max(64, size)))
                .replace("%7Bsize%7D", String.valueOf(Math.max(64, size)));
    }

    static String lowerLocale(String value) {
        return KuGouSign.nonNull(value).toLowerCase(Locale.ROOT);
    }
}
