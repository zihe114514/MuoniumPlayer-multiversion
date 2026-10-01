package com.muoniumplayer.core.ncm.music.dto;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import top.fpsmaster.music.Track;
import com.muoniumplayer.core.ncm.RequestUtil;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.NeteaseMusicService;
import com.muoniumplayer.core.ncm.music.GdStudioMusicService;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.GdStudioSourceSettings;
import com.muoniumplayer.core.ncm.music.MusicPlatform;
import com.muoniumplayer.core.ncm.music.Quality;
import com.muoniumplayer.core.ncm.music.BilibiliMusicService;
import com.muoniumplayer.core.ncm.music.QishuiMusicService;
import com.muoniumplayer.core.ncm.music.YouTubeMusicService;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.Tuple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Data
public class Music {

    private static final long PLAY_URL_RESOLVE_TIMEOUT_SECONDS = 10L;
    // GD Studio 代理解析一个有效第三方 CDN 链接可能比官方 API 慢。 
    private static final long GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS = 35L;
    private static final AtomicInteger URL_RESOLVER_THREAD_ID = new AtomicInteger();
    private static final ExecutorService URL_RESOLVER = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "Music URL Resolver-" + URL_RESOLVER_THREAD_ID.incrementAndGet());
            // 底层网络库即使忽略 interrupt，也不能阻止游戏/IDE 退出。
            thread.setDaemon(true);
            return thread;
        }
    });

    static final int STEREO = 8192;
    static final int INSTRUMENTAL = 131072;
    static final int DOLBY_ATMOS = 262144;
    static final int DIRTY = 1048576;
    static final long HIRES = 17179869184L;

    @SerializedName("name")
    private final String name;

    @SerializedName("mainTitle")
    private final String mainTitle;

    @SerializedName("additionalTitle")
    private final String additionalTitle;

    @SerializedName("id")
    private final long id;

    @SerializedName("ar")
    private final List<Artist> artists;

    @SerializedName("alia")
    private final List<String> aliasName;

    @SerializedName("al")
    private final Album album;

    @SerializedName("dt")
    private final long duration;

    /**
     * 从解码流测得的长度。GD Studio 搜索响应不带时长，因此
     * 没有它，列表、Coverflow 和进度读数对这些曲目会停在 00:00。
     * {@code transient} 把它排除在 Lombok 相等性和序列化播放列表数据之外。
     */
    private transient volatile long decodedDurationMillis;

    @SerializedName("mark")
    private final long featureFlag;

    @SerializedName("publishTime")
    private final long publishTime;

    @SerializedName("tns")
    private final List<String> translatedName;

    /**
     * 网易云歌曲详情特权字段。它们与解析出的
     * 音频质量保持区分：会员/专辑限制是授权元数据。
     */
    @SerializedName("fee")
    private int fee;

    @SerializedName("payed")
    private int payed;

    private transient String artistsName, translatedNames;

    /**
     * 一起听房间下发的取流链接：非空时优先于本机所有解析链路。
     *
     * <p>整房共用同一条链接是刻意的 —— 会员成员解析出的完整音频，非会员成员听到的也是它，
     * 而不是各自解析成「有人能放、有人只有 30 秒试听」。{@code transient} 让它既不进
     * Gson 序列化的歌单缓存，也不参与 Lombok 的 equals。
     */
    private transient String partyStreamUrl;

    /** 一起听链接的容器类型；留空则由 URL 后缀推断。 */
    private transient String partyStreamType;

    /** 跨平台字段。旧网易云 JSON 不包含这些字段时会自然回退到 NETEASE + 数字 id。 */
    private transient MusicPlatform source = MusicPlatform.NETEASE;
    private transient String sourceId;
    private transient String sourceMid;
    private transient String externalCoverUrl;
    /**
     * 自定义源（LX 脚本）补出来的封面。{@code null} 是「还没问过」，空串是「问过了，它也没有」。
     * 问一次要 20 秒起步，不能每帧都问。
     */
    private transient volatile String lxCoverUrl;
    /**
     * 封面是不是「已经是一条成品链接」。网易云那条链路会给裸链接追加 {@code ?param=NyN}
     * 做尺寸缩放，而自定义源给的封面是别人家的直链，追加过去多半就是 404。
     */
    private transient boolean foreignCover;
    /** 当前封面提取出的代表色，供「隐现」歌词背景实时取用；不进入歌单序列化。 */
    private transient volatile int[] coverPalette;
    /** 自由选择的 GD 曲目的 GD Studio 聚合源 key（netease/joox/...）。 */
    private transient String gdPlatform;
    private transient String gdPicId;
    private transient String gdLyricId;
    /**
     * 酷狗的曲目主键。
     *
     * <p>酷狗不像网易云/QQ 那样有一个贯穿全站的歌曲 id：取流、取歌词都按<b>文件 hash</b> 定位，
     * 而同一首歌的三个音质是三个不同的 hash。所以这三个都要留着，取流时按音质挑一个再逐档回退。</p>
     */
    private transient String kugouHash = "";
    private transient String kugouHash320 = "";
    private transient String kugouHashLossless = "";
    private transient String kugouAlbumId = "";
    private transient String kugouAlbumAudioId = "";
    /** 带 {@code {size}} 占位符的酷狗封面模板。 */
    private transient String kugouCoverTemplate = "";
    /**
     * 解灰状态。两个字段都是 {@code transient}：它们描述的是「这一次点播发生了什么」，
     * 不该进歌单落盘，也不该参与 Lombok 的相等性判断。
     *
     * <p>{@code greyDetected} 由 api-enhanced 的「音乐是否可用」判定（见
     * {@code CloudMusicApi#isMusicPlayable}），只在官方链路已经取不到流之后问一次；
     * {@code unblockedSourceLabel} 非空表示这一首现在放的是第三方源的流。</p>
     */
    private transient volatile boolean greyDetected;
    private transient volatile String unblockedSourceLabel = "";
    /**
     * 这一行的声音是<b>跨平台换源</b>换来的（而不是本平台自己解出来的第三方流）。
     *
     * <p>界面上要分两种词说：换源换来的说「已换源 · 网易云」，本平台的解灰 / 脚本流说
     * 「解灰 · 野花·wy」。{@code unblockedSourceLabel} 只说「声音出自哪里」，分不出这两件事，
     * 而玩家看徽标要判断的正是「我这首原平台的歌是被换掉了，还是只是换了条取流地址」。</p>
     *
     * <p>只在 {@code CrossSourceSwitcher.propagateUnblockLabel} 的录取点置位，同样
     * {@code transient}：描述这一次点播，不落盘、不参与相等性。</p>
     */
    private transient volatile boolean crossSourceSwitched;
    private transient boolean vip;
    /** 仅当网易云载荷显式把本曲目标记为云盘曲目时为 true。 */
    private transient boolean cloudSong;
    /** 歌曲/特权元数据宣称的最高提供方档位；空表示未提供。 */
    private transient String highestQualityLabel = "";
    private transient Track cadenceTrack;

    /** 为当前播放请求解析出的实际流质量。 */
    private transient volatile PlaybackQuality playbackQuality = PlaybackQuality.UNKNOWN;

    /** 汽水登录流返回的一次性 play_auth；只在解析到下载完成之间使用，不写配置。 */
    private transient volatile String qishuiPlayAuth = "";

    /**
     * Gson 在解码不可变网易云响应模型时，可能不运行字段初始化器
     * 就分配此 DTO。保持面向渲染的状态非空，
     * 即使对这样反序列化的实例。
     */
    public PlaybackQuality getPlaybackQuality() {
        PlaybackQuality current = playbackQuality;
        if (current == null) {
            playbackQuality = PlaybackQuality.UNKNOWN;
            return PlaybackQuality.UNKNOWN;
        }
        return current;
    }

    public String getQishuiPlayAuth() {
        String current = qishuiPlayAuth;
        return current == null ? "" : current;
    }

    public void setQishuiPlayAuth(String value) {
        qishuiPlayAuth = nonNull(value);
    }

    public void clearQishuiPlayAuth() {
        qishuiPlayAuth = "";
    }

    public enum PlaybackQuality {
        UNKNOWN(""),
        STANDARD(""),
        HQ("HQ"),
        LOSSLESS("无损");

        private final String badge;

        PlaybackQuality(String badge) {
            this.badge = badge;
        }

        public String getBadge() {
            return badge;
        }

        public boolean hasBadge() {
            return !badge.isEmpty();
        }

        /** 用于运行时状态通知的、人可读的解析流档位。 */
        public String getDisplayName() {
            switch (this) {
                case LOSSLESS:
                    return "无损";
                case HQ:
                    return "HQ";
                case STANDARD:
                    return "标准";
                case UNKNOWN:
                default:
                    return "未知";
            }
        }
    }

    /** 应用可选的网易云歌曲/特权字段，而不假定它们对 QQ/Cadence 曲目存在。 */
    public void applyNeteaseMetadata(JsonObject song, JsonObject privilege) {
        if (!isNetease()) return;
        cloudSong = cloudSong || isCloudMarked(song) || isCloudMarked(privilege);
        if (privilege != null) {
            if (privilege.has("fee") && !privilege.get("fee").isJsonNull()) {
                fee = safeInt(privilege, "fee", fee);
            }
            if (privilege.has("payed") && !privilege.get("payed").isJsonNull()) {
                payed = safeInt(privilege, "payed", payed);
            }
            considerHighestQuality(readString(privilege, "maxBrLevel"), readLong(privilege, "maxbr"));
            considerHighestQuality(readString(privilege, "playMaxBrLevel"), readLong(privilege, "playMaxBr"));
            considerHighestQuality(readString(privilege, "downloadMaxBrLevel"), readLong(privilege, "downloadMaxBr"));
            considerHighestQuality(readString(privilege, "plLevel"), readLong(privilege, "br"));
            considerHighestQuality(readString(privilege, "dlLevel"), 0L);
        }
        if (song != null) {
            considerHighestQuality(readString(song, "maxBrLevel"), readLong(song, "maxbr"));
        }
        if ((highestQualityLabel == null || highestQualityLabel.isEmpty()) && isHiRes()) {
            highestQualityLabel = "Hi-Res";
        }
    }

    public boolean isCloudSong() {
        return cloudSong || (isNetease() && CloudMusic.isUserCloudSong(id));
    }

    public String getHighestQualityLabel() {
        if (highestQualityLabel == null || highestQualityLabel.trim().isEmpty()) {
            return isHiRes() ? "Hi-Res" : "";
        }
        return highestQualityLabel;
    }

    private void considerHighestQuality(String level, long bitrate) {
        String candidate = toQualityLabel(level, bitrate);
        if (qualityRank(candidate) > qualityRank(highestQualityLabel)) {
            highestQualityLabel = candidate;
        }
    }

    private static String toQualityLabel(String level, long bitrate) {
        String normalized = nonNull(level).trim().toLowerCase(Locale.ROOT);
        if ("jymaster".equals(normalized) || "hires".equals(normalized)) return "Hi-Res";
        if ("lossless".equals(normalized) || "jyeffect".equals(normalized) || bitrate >= 900000L) return "无损";
        if ("higher".equals(normalized) || "exhigh".equals(normalized) || "sky".equals(normalized)
                || bitrate >= 256000L) return "HQ";
        if ("standard".equals(normalized) || bitrate > 0L) return "标准";
        return "";
    }

    private static int qualityRank(String label) {
        if ("Hi-Res".equals(label)) return 4;
        if ("无损".equals(label)) return 3;
        if ("HQ".equals(label)) return 2;
        if ("标准".equals(label)) return 1;
        return 0;
    }

    private static boolean isCloudMarked(JsonObject object) {
        if (object == null) return false;
        return isTruthy(object, "cloudSong") || isTruthy(object, "cloudsong")
                || isTruthy(object, "isCloudSong") || isTruthy(object, "cloudStatus")
                || isTruthy(object, "cloudSongId");
    }

    private static boolean isTruthy(JsonObject object, String property) {
        if (!object.has(property) || object.get(property).isJsonNull()) return false;
        try {
            if (object.get(property).isJsonObject()) return true;
            if (object.get(property).getAsJsonPrimitive().isBoolean()) return object.get(property).getAsBoolean();
            if (object.get(property).getAsJsonPrimitive().isNumber()) return object.get(property).getAsLong() != 0L;
            String value = object.get(property).getAsString();
            return "true".equalsIgnoreCase(value) || (!"0".equals(value) && !value.trim().isEmpty());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int safeInt(JsonObject object, String property, int fallback) {
        try {
            return object.get(property).getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }
    public static Music fromCadenceTrack(Track track) {
        MusicPlatform platform = MusicPlatform.fromCadence(track.getSource());
        String id = nonNull(track.getId());
        long stableId = platform == MusicPlatform.NETEASE
                ? parseNeteaseId(id, track.getMid())
                : stableLong(platform.name() + ':' + id + ':' + nonNull(track.getMid()));

        List<Artist> artistList = new ArrayList<>();
        String artists = nonNull(track.getArtists());
        if (!artists.trim().isEmpty()) {
            String[] names = artists.split("\\s*/\\s*|\\s*,\\s*");
            for (String artistName : names) {
                if (!artistName.trim().isEmpty()) {
                    artistList.add(new Artist(stableLong(platform.name() + ":artist:" + artistName),
                            artistName.trim(), Collections.emptyList(), Collections.emptyList()));
                }
            }
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        String albumName = nonNull(track.getAlbum());
        Album album = new Album(stableLong(platform.name() + ":album:" + albumName), albumName,
                nonNull(track.getCoverUrl()), Collections.emptyList());

        Music music = new Music(nonNull(track.getName()), nonNull(track.getName()), "", stableId,
                artistList, Collections.emptyList(), album, track.getDurationMs(), 0L, 0L,
                Collections.emptyList());
        music.source = platform;
        music.sourceId = id;
        music.sourceMid = track.getMid();
        music.externalCoverUrl = track.getCoverUrl();
        music.vip = track.getVip();
        music.cadenceTrack = track;
        return music;
    }

    /** 构建一条 YouTube 搜索结果。YouTube 视频 id 稳定，并成为源 key。 */
    public static Music fromYouTubeMusicTrack(YouTubeMusicService.Track track) {
        if (track == null) return null;
        String videoId = nonNull(track.videoId);
        long stableId = stableLong("youtube:" + videoId);

        List<Artist> artistList = new ArrayList<>();
        String artist = nonNull(track.artist);
        if (!artist.trim().isEmpty()) {
            artistList.add(new Artist(stableLong("youtube:artist:" + artist), artist.trim(),
                    Collections.emptyList(), Collections.emptyList()));
        } else {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        String cover = nonNull(track.coverUrl);
        // 紧凑的 YouTube 搜索结果不带专辑元数据。保持它为空，而非
        //在全屏曲目详情中把频道名当作假专辑呈现。
        Album album = new Album(stableLong("youtube:album:" + videoId), "", cover,
                Collections.emptyList());
        Music music = new Music(nonNull(track.title), nonNull(track.title), "", stableId,
                artistList, Collections.emptyList(), album, track.durationMillis, 0L, 0L,
                Collections.emptyList());
        music.source = MusicPlatform.YOUTUBE;
        music.sourceId = videoId;
        music.sourceMid = videoId;
        music.externalCoverUrl = cover;
        return music;
    }

    /** 构建一条汽水音乐搜索结果。汽水曲目 id 全局稳定。 */
    public static Music fromQishuiTrack(QishuiMusicService.Track track) {
        if (track == null) return null;
        String trackId = nonNull(track.trackId);
        long stableId = stableLong("qishui:" + trackId);

        List<Artist> artistList = new ArrayList<>();
        String artist = nonNull(track.artist);
        if (!artist.trim().isEmpty()) {
            for (String artistName : artist.split("\\s*/\\s*")) {
                if (artistName.trim().isEmpty()) continue;
                artistList.add(new Artist(stableLong("qishui:artist:" + artistName), artistName.trim(),
                        Collections.emptyList(), Collections.emptyList()));
            }
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        String albumName = nonNull(track.album);
        String cover = nonNull(track.coverUrl);
        Album album = new Album(stableLong("qishui:album:" + albumName), albumName, cover,
                Collections.emptyList());
        Music music = new Music(nonNull(track.title), nonNull(track.title), "", stableId,
                artistList, Collections.emptyList(), album, track.durationMillis, 0L, 0L,
                Collections.emptyList());
        music.source = MusicPlatform.QISHUI;
        music.sourceId = trackId;
        music.sourceMid = trackId;
        music.externalCoverUrl = cover;
        music.vip = track.vip;
        return music;
    }

    /** 构建一条 Bilibili 搜索结果。BVID 加页码是稳定的源 key。 */
    public static Music fromBilibiliTrack(BilibiliMusicService.Track track) {
        if (track == null) return null;
        String bvid = nonNull(track.bvid);
        int page = Math.max(1, track.page);
        String sourceKey = BilibiliMusicService.sourceKey(bvid, page);
        long stableId = stableLong("bilibili:" + sourceKey);

        List<Artist> artistList = new ArrayList<>();
        String artist = nonNull(track.artist);
        if (!artist.trim().isEmpty()) {
            artistList.add(new Artist(stableLong("bilibili:artist:" + artist), artist.trim(),
                    Collections.emptyList(), Collections.emptyList()));
        } else {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        String cover = nonNull(track.coverUrl);
        Album album = new Album(stableLong("bilibili:album:" + sourceKey), "", cover,
                Collections.emptyList());
        Music music = new Music(nonNull(track.title), nonNull(track.title), "", stableId,
                artistList, Collections.emptyList(), album, track.durationMillis, 0L, 0L,
                Collections.emptyList());
        music.source = MusicPlatform.BILIBILI;
        music.sourceId = sourceKey;
        music.sourceMid = bvid;
        music.externalCoverUrl = cover;
        return music;
    }

    /**
     * 由酷狗曲目构造一首 {@link Music}。
     *
     * <p>酷狗没有全站歌曲 id，所以 {@code id} 用 hash 派生的稳定哈希 —— 它要稳定，因为封面缓存、
     * 下载缓存、播放历史全都按 {@link #getStableKey()} 落盘，每次搜索换一个 id 会让缓存永远打不中。</p>
     */
    public static Music fromKuGouTrack(com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.Track track) {
        if (track == null) return null;
        long stableId = stableLong("kugou:" + track.hash);

        List<Artist> artistList = new ArrayList<>();
        String artists = nonNull(track.artist);
        if (!artists.trim().isEmpty()) {
            for (String artistName : artists.split("\\s*[、,/]\\s*")) {
                if (artistName.trim().isEmpty()) continue;
                artistList.add(new Artist(stableLong("kugou:artist:" + artistName), artistName.trim(),
                        Collections.emptyList(), Collections.emptyList()));
            }
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        String albumName = nonNull(track.album);
        String cover = com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.cover(track.coverTemplate, 300);
        Album album = new Album(stableLong("kugou:album:" + albumName), albumName, cover,
                Collections.emptyList());

        Music music = new Music(nonNull(track.name), nonNull(track.name), "", stableId,
                artistList, Collections.emptyList(), album, track.durationMillis, 0L, 0L,
                Collections.emptyList());
        music.source = MusicPlatform.KUGOU;
        music.sourceId = nonNull(track.hash);
        music.sourceMid = nonNull(track.albumAudioId);
        music.kugouHash = nonNull(track.hash);
        music.kugouHash320 = nonNull(track.hash320);
        music.kugouHashLossless = nonNull(track.hashLossless);
        music.kugouAlbumId = nonNull(track.albumId);
        music.kugouAlbumAudioId = nonNull(track.albumAudioId);
        music.kugouCoverTemplate = nonNull(track.coverTemplate);
        music.externalCoverUrl = cover;
        music.vip = track.vip;
        return music;
    }

    /** 还原成酷狗 API 的曲目模型，供取流与取歌词使用。 */
    public com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.Track toKuGouTrack() {
        com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.Track track =
                new com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.Track();
        track.hash = nonNull(kugouHash).isEmpty() ? getSourceId() : kugouHash;
        track.hash320 = nonNull(kugouHash320);
        track.hashLossless = nonNull(kugouHashLossless);
        track.albumId = nonNull(kugouAlbumId);
        track.albumAudioId = nonNull(kugouAlbumAudioId);
        track.name = nonNull(name);
        track.artist = getArtistsName();
        track.album = album == null ? "" : nonNull(album.getName());
        track.durationMillis = getDuration();
        track.coverTemplate = nonNull(kugouCoverTemplate);
        track.vip = vip;
        return track;
    }

    /** 从一条 GD API 搜索结果构建一条可自由选择的 GD Studio 曲目。 */
    public static Music fromGdTrack(GdStudioMusicService.GdTrack track, String platformKey) {
        if (track == null) return null;
        String id = nonNull(track.id);
        String returnedPlatform = nonNull(track.source).trim().toLowerCase(Locale.ROOT);
        String configuredPlatform = platformKey == null ? "" : platformKey.trim().toLowerCase(Locale.ROOT);
        String platform = GdStudioMusicService.isKnownPlatform(returnedPlatform) ? returnedPlatform : configuredPlatform;
        long stableId = stableLong("gd:" + platform + ':' + id);

        List<Artist> artistList = new ArrayList<>();
        String artists = nonNull(track.artist);
        if (!artists.trim().isEmpty()) {
            String[] names = artists.split("\\s*[、,/]\\s*");
            for (String artistName : names) {
                if (!artistName.trim().isEmpty()) {
                    artistList.add(new Artist(stableLong("gd:artist:" + artistName), artistName.trim(),
                            Collections.emptyList(), Collections.emptyList()));
                }
            }
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        String albumName = nonNull(track.album);
        Album album = new Album(stableLong("gd:" + platform + ":album:" + albumName), albumName, "",
                Collections.emptyList());

        Music music = new Music(nonNull(track.name), nonNull(track.name), "", stableId,
                artistList, Collections.emptyList(), album, 0L, 0L, 0L, Collections.emptyList());
        music.source = MusicPlatform.GD;
        music.sourceId = id;
        music.gdPlatform = platform;
        music.gdPicId = nonNull(track.picId);
        music.gdLyricId = nonNull(track.lyricId);
        music.externalCoverUrl = "";
        return music;
    }

    /** 源元数据时长，回退到解码时测得的长度。 */
    public long getDuration() {
        return duration > 0L ? duration : decodedDurationMillis;
    }

    /** 为一个未携带时长元数据的源记录解码器报告的长度。 */
    public void applyDecodedDuration(long millis) {
        if (millis > 0L && duration <= 0L) {
            decodedDurationMillis = millis;
        }
    }

    public Track toCadenceTrack() {
        if (cadenceTrack != null) return cadenceTrack;
        // 必须走 getSource() 而不是直接读字段：Music 声明了显式构造器，Lombok 的 @Data 因此
        // 不再补无参构造器，Gson 只能用 Unsafe.allocateInstance 建实例 —— 那条路径不执行任何
        // 字段初始化式，所以 `source = MusicPlatform.NETEASE` 这个默认值对所有 JSON 解析出来的
        // 歌曲都不生效，字段实际是 null（歌单、私人 FM、一起听下发的曲目全都如此）。
        // 直接读字段会在这里抛 NPE，把整条歌词链路吞掉（日志里的
        // "[Music/Netease] Lyric failed ... this.source is null" 就是它）。
        cadenceTrack = new Track(getSource().toCadenceSource(), getSourceId(), sourceMid, nonNull(name),
                getArtistsName(), album == null ? "" : nonNull(album.getName()), duration,
                getBaseCoverUrl(), vip);
        return cadenceTrack;
    }

    public String getSourceId() {
        return sourceId == null || sourceId.trim().isEmpty() ? String.valueOf(id) : sourceId;
    }

    public MusicPlatform getSource() {
        return source == null ? MusicPlatform.NETEASE : source;
    }

    public boolean isQQ() {
        return getSource() == MusicPlatform.QQ;
    }

    public boolean isNetease() {
        return getSource() == MusicPlatform.NETEASE;
    }

    public boolean isGd() {
        return getSource() == MusicPlatform.GD;
    }

    public boolean isYouTube() {
        return getSource() == MusicPlatform.YOUTUBE;
    }

    public boolean isBilibili() {
        return getSource() == MusicPlatform.BILIBILI;
    }

    public boolean isQishui() {
        return getSource() == MusicPlatform.QISHUI;
    }

    public boolean isKuGou() {
        return getSource() == MusicPlatform.KUGOU;
    }

    /** 酷我渠道：只为 LX 自定义源而存在，没有本家取流链路。 */
    public boolean isKuwo() {
        return getSource() == MusicPlatform.KUWO;
    }

    /** 咪咕渠道：同 {@link #isKuwo()}，脚本声明 mg 渠道之外不可播。 */
    public boolean isMigu() {
        return getSource() == MusicPlatform.MIGU;
    }

    /** 酷我 / 咪咕：宿主只内置了它们的搜索（供换源匹配），取流完全依赖自定义源。 */
    public boolean isLxOnlyChannel() {
        return isKuwo() || isMigu();
    }

    /**
     * 返回提供方是否把本曲目标记为会员或付费内容受限。
     * Cadence 为 QQ 曲目提供该标志；网易云 fee 从其特权载荷复制。
     */
    public boolean hasVipRestriction() {
        return vip || (isNetease() && fee != 0);
    }

    /** 仅对网易云数字专辑购买曲目为 true，而非普通专辑元数据。 */
    public boolean isDigitalAlbumTrack() {
        return isNetease() && fee == 4;
    }

    public String getStableKey() {
        return getSource().name().toLowerCase() + '_' + Long.toUnsignedString(id);
    }

    /**
     * 由酷狗 hash 反推它的稳定键。
     *
     * <p>存在的唯一理由：酷狗「我喜欢」的收藏索引里只有 hash（那是它的全局主键），
     * 而收藏图标查的是 {@link #getStableKey()}。没有这个换算，冷启动索引就没法在
     * <b>曲目还没被加载出来</b>的时候提前建好——那样等于仍然要逐行发请求。</p>
     *
     * <p>算法必须与 {@code fromKuGouTrack} 里那句 {@code stableLong("kugou:" + track.hash)}
     * 完全一致，所以它复用同一个私有函数，而不是另写一份 FNV。</p>
     */
    public static String kuGouStableKey(String hash) {
        // 统一转小写：Track.hash 是解析时 lowercase 过的，而上游不同端点给的大小写不一致
        // （收藏列表那个端点给大写），不统一的话同一个 hash 会算出两个稳定键。
        String value = nonNull(hash).trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        return "kugou_" + Long.toUnsignedString(stableLong("kugou:" + value));
    }

    public final Location getCoverLocation() {
        return Location.of("muonium/textures/music/" + getStableKey() + "/cover.png");
    }

    public final Location getBlurredCoverLocation() {
        return Location.of("muonium/textures/music/" + getStableKey() + "/cover_blurred.png");
    }

    /** 网易云提供受支持的动态图时为动画封面纹理；否则不存在。 */
    public final Location getDynamicCoverLocation() {
        return Location.of("muonium/textures/music/" + getStableKey() + "/cover_dynamic.png");
    }
    public final Location getSmallCoverLocation() {
        return Location.of("muonium/textures/music/" + getStableKey() + "/cover_small.png");
    }

    public String getArtistsName() {
        if (this.artistsName == null) {
            this.artistsName = this.buildArtistsNames();
            if (this.artistsName.isEmpty()) this.artistsName = "Unknown";
        }
        return this.artistsName;
    }

    public String getTranslatedNames() {
        if (this.translatedNames == null) this.translatedNames = this.buildTranslatedNames();
        return this.translatedNames;
    }

    private String buildTranslatedNames() {
        if (this.translatedName == null || this.translatedName.isEmpty()) return "";
        return String.join(", ", this.translatedName);
    }

    private String buildArtistsNames() {
        if (this.artists == null || this.artists.isEmpty()) return "";
        return this.artists.stream().filter(Objects::nonNull).map(Artist::getName)
                .filter(Objects::nonNull).collect(Collectors.joining(", "));
    }

    private String getBaseCoverUrl() {
        if (externalCoverUrl != null && !externalCoverUrl.trim().isEmpty()) return externalCoverUrl;
        return album == null ? "" : nonNull(album.getPicUrl());
    }

    public String getCoverUrl(int size) {
        String base = getBaseCoverUrl();
        if (base.isEmpty()) return "";
        if (isKuGou()) {
            // 酷狗封面是带 {size} 占位符的模板，尺寸要替换进去而不是拼查询参数。
            String template = kugouCoverTemplate == null || kugouCoverTemplate.trim().isEmpty()
                    ? base : kugouCoverTemplate;
            String resolved = com.muoniumplayer.core.ncm.kugou.KuGouMusicApi.cover(template, size);
            return (resolved.isEmpty() ? base : resolved).replace("http://", "https://");
        }
        if (isQQ() || isGd() || isYouTube() || isBilibili() || isQishui() || foreignCover) {
            // 这些平台的封面 URL 已带尺寸或模板（自定义源给的是别人家的直链，同理），
            // 避免追加网易云专用 param 导致 404。
            return base.replace("http://", "https://");
        }
        return base + "?param=" + size + "y" + size;
    }

    /**
     * 解析 GD Studio 专辑封面 URL（网络调用）。仅可安全从后台
     * 封面加载线程调用；渲染路径使用缓存的 {@link #getCoverUrl(int)}。
     */
    public String resolveGdCoverUrl(int size) {
        if (!isGd()) return getCoverUrl(size);
        String cached = externalCoverUrl;
        if (cached != null && !cached.trim().isEmpty()) {
            return cached.replace("http://", "https://");
        }
        String resolved = "";
        try {
            resolved = GdStudioMusicService.getPicUrl(getGdPlatform(), gdPicId, size);
        } catch (Throwable ignored) {
        }
        if (!resolved.isEmpty()) externalCoverUrl = resolved;
        return resolved.replace("http://", "https://");
    }

    /** 更新歌曲播放次数；跨平台曲目不调用网易云 scrobble。 */
    @Deprecated
    public void updPlayCount(PlayList pl, float sec) {
        if (!isNetease()) return;
    }

    /**
     * 获得歌曲播放 URL。每一次点歌都会重新解析，不缓存失败结果。
     *
     * 默认配置是无损优先：先请求无损；曲目没有无损、账号没有 VIP、接口返回空链接、
     * 解析异常或超时时，再明确降级到标准音质。网易云在主解析器失败后继续切换
     * 备用解析器；QQ 仅能使用 Cadence，但同样执行无损到标准的降级。
     */
    public Tuple<String, String> getPlayUrl() {
        // 绝不显示从上一次失败解析带过来的质量徽标。
        this.playbackQuality = PlaybackQuality.UNKNOWN;

        // 一起听房间正在放的这一首，用房间共用的那条链接，不再各自解析：本机重新解析会让
        // 非会员退到试听片段或第三方源，整房的进度条随即失去意义。
        Tuple<String, String> shared = partyPlayUrl();
        if (shared != null) return shared;

        // 自定义源（LX Music 脚本）排在整条内置链路之前：用户导入并启用它就是为了用它取流。
        // 它拿不出链接时返回 null，下面的内置链路照旧跑 —— 不会因为一份坏脚本而没歌可听。
        Tuple<String, String> custom = resolveLxSource();
        if (custom != null) return custom;

        // 酷我 / 咪咕渠道没有内置取流链路：上面那一跳（自定义源）就是它们唯一的活路。
        // 绝不能继续往下掉 —— 那会把这些平台的 id 当网易云 id 喂给内置解析，放出别的歌。
        if (isLxOnlyChannel()) return null;

        if (isYouTube()) {
            return resolveYouTubePlayUrl();
        }
        if (isBilibili()) {
            return resolveBilibiliPlayUrl();
        }
        if (isQishui()) {
            return resolveQishuiPlayUrl();
        }
        if (isGd()) {
            return resolveGdPlayUrl();
        }
        if (isKuGou()) {
            return resolveKuGouPlayUrl();
        }
        if (isQQ()) {
            return resolveCadenceWithStandardFallback();
        }

        // Cadence 搜索得到的网易云歌曲仍优先保持原来源，且该来源内部先尝试无损。
        if (cadenceTrack != null) {
            Tuple<String, String> result = resolveCadenceWithStandardFallback();
            if (result != null) return result;
            result = resolveWithTimeout(new Callable<Tuple<String, String>>() {
                @Override
                public Tuple<String, String> call() {
                    return resolveWithBuiltInNeteaseApi();
                }
            }, "built-in NetEase API");
            if (result != null) return result;
            return resolveNeteaseUnblock();
        }

        Tuple<String, String> result = resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveWithBuiltInNeteaseApi();
            }
        }, "built-in NetEase API");
        if (result != null) return result;
        result = resolveCadenceWithStandardFallback();
        if (result != null) return result;
        return resolveNeteaseUnblock();
    }

    /** 一起听房间下发的链接，没有则返回 null（照常走本机解析链路）。 */
    private Tuple<String, String> partyPlayUrl() {
        String url = partyStreamUrl;
        if (url == null || url.trim().isEmpty()) return null;
        // 类型留空交给 normalizePlayUrl 按 URL 后缀推断：房间协议只传链接，不传容器。
        return normalizePlayUrl(new Tuple<>(url, partyStreamType == null ? "" : partyStreamType));
    }

    /**
     * 组装交给脚本的 {@code musicInfo}。
     *
     * @return null 表示这个平台在 LX 里没有对应的源（joox / bilibili / tidal / spotify 等）
     */
    private com.muoniumplayer.core.ncm.lxsource.LxTrack lxTrack() {
        String key = com.muoniumplayer.core.ncm.lxsource.LxSourceService
                .sourceKeyFor(getSource().name(), gdPlatform);
        if (key == null) return null;

        com.muoniumplayer.core.ncm.lxsource.LxTrack track =
                new com.muoniumplayer.core.ncm.lxsource.LxTrack()
                        .source(key)
                        .name(nonNull(name))
                        .singer(getArtistsName())
                        .albumName(album == null ? "" : nonNull(album.getName()))
                        .img(getCoverUrl(500))
                        .durationMillis(getDuration());

        if ("kg".equals(key)) {
            // 酷狗的脚本按文件 hash 取流，songmid 对它没用；三个音质三个 hash，
            // 这里只给基准那一个，高音质由脚本自己去查。
            track.songmid(getSourceId()).hash(nonNull(kugouHash).isEmpty() ? getSourceId() : kugouHash);
        } else if ("tx".equals(key)) {
            // QQ 的主键是 mid 字符串（例：003aAYrm3GE0Ac）而不是数字 id；
            // 上游的 songmid 放 mid、songId 放数字 id，albumMid 宓主没有就给空。
            String mid = nonNull(sourceMid).isEmpty() ? getSourceId() : nonNull(sourceMid);
            track.songmid(mid).qq(mid, "", getSourceId());
        } else if ("mg".equals(key)) {
            // 咪咕的脚本按 copyrightId 取流（上游 mg 的 getId 只读它）；songmid 位放 songId，
            // 两个键都给上，按新结构写的脚本也读得到。copyrightId 缺失时退回主键本身。
            track.songmid(getSourceId())
                    .copyrightId(nonNull(sourceMid).isEmpty() ? getSourceId() : nonNull(sourceMid));
        } else {
            // wy 与 kw 的脚本都读 musicInfo.songmid。
            track.songmid(getSourceId());
        }
        return track;
    }

    /**
     * 让已启用的自定义源先试一次。
     *
     * <p>走 {@link #resolveWithTimeout} 而不是直接调：脚本自己有 20 秒请求超时、首次还要等
     * 最多 8 秒初始化，而这条调用链上的那一头是游戏主线程。</p>
     */
    private Tuple<String, String> resolveLxSource() {
        if (!com.muoniumplayer.core.ncm.lxsource.LxSourceService.isEnabled()) return null;
        final com.muoniumplayer.core.ncm.lxsource.LxTrack track = lxTrack();
        if (track == null) return null;
        // 上一首的徒标不能沿用到这一首；失败时下面的内置链路也依赖这个字段是空的。
        this.unblockedSourceLabel = "";
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                com.muoniumplayer.core.ncm.lxsource.LxSourceService.Resolved resolved =
                        com.muoniumplayer.core.ncm.lxsource.LxSourceService
                                .resolveMusicUrl(track, requestedQuality.name());
                if (resolved == null) return null;
                Tuple<String, String> normalized = normalizePlayUrl(new Tuple<>(resolved.url, resolved.format));
                if (normalized == null) return null;
                // 复用解灰的那枚徒标：用户需要知道「这一首不是官方流」，而这与解灰是同一件事。
                // 末尾带上本次用的渠道键（「野花·kw」）—— 一份脚本五个渠道，只写脚本名读不出落点，
                // 而酷我 / 咪咕这类 LX-only 渠道没有本家流，这一位就是它的全部身份信息。
                unblockedSourceLabel = com.muoniumplayer.core.ncm.lxsource.LxSourceService
                        .badgeLabel(resolved.sourceLabel, track.getSource());
                playbackQuality = lxPlaybackQuality(resolved.quality);
                considerHighestQuality(
                        com.muoniumplayer.core.ncm.lxsource.LxQuality.muLevelFor(resolved.quality), 0L);
                return normalized;
            }
        }, "LX custom source", GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    /**
     * 换源流水线用它验证「自定义源能不能解出这一首」。
     *
     * <p>播放时 {@link #getPlayUrl()} 把自定义源排在整条内置链路之前，验证如果只看官方链路，
     * 「脚本明明能播」的候选会全部死在验证关 —— 尤其网易云/QQ/酷狗上非会员够不着的 VIP 曲。
     * 语义与 getPlayUrl 里那一跳完全一致：脚本没启用、源不覆盖本平台或解不出链接都返回 null。</p>
     */
    public Tuple<String, String> resolveLxPlayUrl() {
        return resolveLxSource();
    }

    /** 自定义源的音质徒标按脚本实际给出的档位判定，而不是按容器猜。 */
    private static PlaybackQuality lxPlaybackQuality(String lxQuality) {
        if ("flac".equals(lxQuality) || "flac24bit".equals(lxQuality)) return PlaybackQuality.LOSSLESS;
        return "320k".equals(lxQuality) ? PlaybackQuality.HQ : PlaybackQuality.STANDARD;
    }

    /**
     * 问自定义源要歌词（宿主扩展，默认关，见
     * {@link com.muoniumplayer.core.ncm.lxsource.LxSourceService#isExtraActionsEnabled()}）。
     *
     * <p><b>只能在后台线程上调</b>：脚本自己有 20 秒请求超时，首次还要等最多 8 秒初始化。
     * {@code LyricLoadService.resolve} 正好跑在 {@code MultiThreadingUtil.runAsync} 里。</p>
     *
     * <p>与取流相反，歌词是<b>兜底</b>而不是优先：平台自己那份（含翻译）几乎总是更准，
     * 只有内置链路一条都没给出歌词时才轮到脚本。拿不到返回 null，调用方照旧往下走。</p>
     */
    public com.muoniumplayer.core.ncm.lxsource.LxSourceService.Lyrics resolveLxLyrics() {
        if (!com.muoniumplayer.core.ncm.lxsource.LxSourceService.isEnabled()) return null;
        if (!com.muoniumplayer.core.ncm.lxsource.LxSourceService.isExtraActionsEnabled()) return null;
        com.muoniumplayer.core.ncm.lxsource.LxTrack track = lxTrack();
        if (track == null) return null;
        return com.muoniumplayer.core.ncm.lxsource.LxSourceService.resolveLyric(track);
    }

    /**
     * 问自定义源要一张封面（宿主扩展，同上）。<b>只能在后台封面线程上调。</b>
     *
     * <p>只在内置封面为空时才该问它：平台自己那张永远更对，而且问一次是秒级的网络往返。
     * 结果缓存在 {@link #lxCoverUrl} 里，问过一次就不再问。</p>
     *
     * @return 空串表示拿不到（调用方保持原样）
     */
    public String resolveLxCoverUrl() {
        String cached = lxCoverUrl;
        if (cached != null) return cached;
        String resolved = "";
        if (com.muoniumplayer.core.ncm.lxsource.LxSourceService.isEnabled()
                && com.muoniumplayer.core.ncm.lxsource.LxSourceService.isExtraActionsEnabled()) {
            com.muoniumplayer.core.ncm.lxsource.LxTrack track = lxTrack();
            if (track != null) {
                String url = com.muoniumplayer.core.ncm.lxsource.LxSourceService.resolvePic(track);
                if (url != null && !url.trim().isEmpty()) resolved = url.trim().replace("http://", "https://");
            }
        }
        lxCoverUrl = resolved;
        return resolved;
    }

    /**
     * 把自定义源搜到的一条结果拼成曲目。
     *
     * <p>主键是纯数字时直接当 {@code id} 用 —— 网易云的内置解析读的是数字 id，编成哈希会让
     * 「脚本挂了退回官方源」这条路断掉；不是纯数字才退回 {@code stableLong}。</p>
     */
    public static Music fromLxSearchHit(com.muoniumplayer.core.ncm.lxsource.LxSourceService.SearchHit hit) {
        if (hit == null || !hit.isUsable()) return null;
        String platformName = com.muoniumplayer.core.ncm.lxsource.LxSourceService
                .platformForSourceKey(hit.source);
        if (platformName == null) return null;
        MusicPlatform platform;
        try {
            platform = MusicPlatform.valueOf(platformName);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        String primaryKey = hit.primaryKey();
        long stableId;
        try {
            stableId = Long.parseLong(primaryKey);
        } catch (NumberFormatException notNumeric) {
            stableId = stableLong("lx:" + hit.source + ":" + primaryKey);
        }

        List<Artist> artistList = new ArrayList<>();
        for (String artistName : hit.singer.split("\\s*[、,/]\\s*")) {
            if (artistName.trim().isEmpty()) continue;
            artistList.add(new Artist(stableLong("lx:artist:" + artistName), artistName.trim(),
                    Collections.emptyList(), Collections.emptyList()));
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown", Collections.emptyList(), Collections.emptyList()));
        }

        Album album = new Album(stableLong("lx:album:" + hit.albumName), hit.albumName,
                hit.img, Collections.emptyList());
        Music music = new Music(hit.name, hit.name, "", stableId, artistList,
                Collections.emptyList(), album, parseInterval(hit.interval), 0L, 0L,
                Collections.emptyList());
        music.source = platform;
        music.sourceId = primaryKey;
        // QQ 的 mid、咪咕的 copyrightId 都存在 sourceMid 里（两条取流链路各认这一个键）。
        music.sourceMid = !hit.strMediaMid.isEmpty() ? hit.strMediaMid : hit.copyrightId;
        if (!hit.hash.isEmpty()) music.kugouHash = hit.hash;
        if (!hit.img.isEmpty()) {
            music.externalCoverUrl = hit.img;
            // 脚本给的封面是别人家的直链，不能再走网易云那套 ?param= 缩放。
            music.foreignCover = true;
        }
        return music;
    }

    /** 脚本给的时长是 "mm:ss"（内置 musicSearch 的口径）；解析不出来就留 0，解码后会回填。 */
    private static long parseInterval(String interval) {
        String text = nonNull(interval).trim();
        int colon = text.indexOf(':');
        if (colon <= 0) return 0L;
        try {
            long minutes = Long.parseLong(text.substring(0, colon).trim());
            long seconds = Long.parseLong(text.substring(colon + 1).trim());
            if (minutes < 0L || seconds < 0L) return 0L;
            return (minutes * 60L + seconds) * 1000L;
        } catch (NumberFormatException broken) {
            return 0L;
        }
    }

    /**
     * 最后一道网易云兜底：官方两条链路都没有流之后，按 UnblockNeteaseMusic 的流程去第三方源
     * 重新匹配一条可播链接。
     *
     * <p>刻意放在整条链路的<b>最后</b>，而且只对当前这一首生效 —— 主动对整张歌单探测可用性会
     * 瞬间打爆网易云与 GD 音乐台的额度。解灰失败时仍然返回 {@code null}，交回既有的
     * 「没有可用音频源 → 停下等用户操作」流程，不会自动跳下一曲。</p>
     */
    private Tuple<String, String> resolveNeteaseUnblock() {
        if (!isNetease()) return null;
        this.unblockedSourceLabel = "";
        if (!com.muoniumplayer.core.ncm.music.NeteaseUnblockSettings.isEnabled()) return null;

        // 先问一次「音乐是否可用」，把真正的灰色歌和一次网络抖动区分开。判定只影响歌曲行上的
        // 「灰」标，不影响是否尝试解灰 —— 官方流已经拿不到了，无论原因都该去找替代源。
        this.greyDetected = !com.muoniumplayer.core.ncm.music.NeteaseGreyUnblocker.isPlayableOnOfficial(this);

        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                com.muoniumplayer.core.ncm.music.NeteaseGreyUnblocker.Result unblocked =
                        com.muoniumplayer.core.ncm.music.NeteaseGreyUnblocker.unblock(Music.this, requestedQuality);
                if (unblocked == null) return null;
                Tuple<String, String> normalized = normalizePlayUrl(new Tuple<>(unblocked.url, unblocked.format));
                if (normalized == null) return null;
                unblockedSourceLabel = unblocked.sourceLabel;
                playbackQuality = detectCadencePlaybackQuality(normalized.getB(), requestedQuality);
                considerHighestQuality(normalized.getB(), 0L);
                return normalized;
            }
        }, "NetEase grey unblock", GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    /** 非空表示这一首正在播第三方源解出来的流；值就是徽标上显示的源名。 */
    public String getUnblockedSourceLabel() {
        String label = unblockedSourceLabel;
        return label == null ? "" : label;
    }

    /**
     * 换源录取时把替身的「第三方流」标记回写到被替换掉的行对象上。
     *
     * <p>播放队列是面板列表的浅拷贝（{@code new ArrayList<>(songs)}），行 widget 绑定的还是
     * 老对象；徽标若只写在替身上，界面永远看不见。写入方只在换源录取点调用，正常播放链路
     * 依旧靠 {@code resolveLxSource} / {@code resolveNeteaseUnblock} 自己维护这个字段。</p>
     */
    public void setUnblockedSourceLabel(String label) {
        this.unblockedSourceLabel = label == null ? "" : label;
    }

    /**
     * 标记「这一首是跨平台换源换来的」，徽标因此念作「已换源 · 落点」而不是「解灰 · 源名」。
     *
     * <p>只有 {@code CrossSourceSwitcher.propagateUnblockLabel} 会在录取点调用它，行对象与替身
     * 对象两个都标 —— 面板那一行的 widget 绑老引用，重建后的行与全屏页读的是替身自己。</p>
     */
    public void markCrossSourceSwitched() {
        this.crossSourceSwitched = true;
    }

    /** 这一行的声音来自另一平台被换进来的曲目（而不是本平台的解灰 / 脚本流）。 */
    public boolean isCrossSourceSwitched() {
        return crossSourceSwitched;
    }

    /** 官方链路取不到流，且「音乐是否可用」也判定为不可用。 */
    public boolean isGreyDetected() {
        return greyDetected;
    }

    /**
     * 解析一条 YouTube 音频流。YouTube 在此只暴露有损音频；因此解析出的
     * 质量徽标刻意保持 standard/HQ，而非声称无损。
     */
    private Tuple<String, String> resolveYouTubePlayUrl() {
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                YouTubeMusicService.ResolveResult result =
                        YouTubeMusicService.getSongUrl(getSourceId(), requestedQuality);
                if (result == null) return null;
                playbackQuality = detectCadencePlaybackQuality(result.format, requestedQuality);
                considerHighestQuality(result.format, 0L);
                return new Tuple<>(result.url, result.format);
            }
        }, "YouTube Music", GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    /**
     * 解析 Bilibili 的纯音频 DASH 流。服务偏好 AAC-LC，因为
     * 内置 JAAD 解码器可靠处理它；分片 MP4 随后流经
     * 既有的 AAC 解码缓存管线。
     */
    private Tuple<String, String> resolveQishuiPlayUrl() {
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.STANDARD : CloudMusic.quality;
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                QishuiMusicService.ResolveResult result =
                        QishuiMusicService.getSongUrl(getSourceId(), requestedQuality);
                if (result == null) return null;
                setQishuiPlayAuth(result.playAuth);
                playbackQuality = detectCadencePlaybackQuality(result.format, requestedQuality);
                considerHighestQuality(result.format, result.bitrate);
                return new Tuple<>(result.url, result.format);
            }
        }, "Qishui Music", GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    private Tuple<String, String> resolveBilibiliPlayUrl() {
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                BilibiliMusicService.ResolveResult result =
                        BilibiliMusicService.getSongUrl(getSourceId(), requestedQuality);
                if (result == null) return null;
                playbackQuality = detectCadencePlaybackQuality(result.format, requestedQuality);
                considerHighestQuality(result.format, 0L);
                return new Tuple<>(result.url, result.format);
            }
        }, "Bilibili", GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    /**
     * 解析一条可自由选择的 GD Studio 曲目。当首选平台解析或
     * 播放失败时，它向搜索返回的确切平台/id 发送文档记载的 URL 请求。
     */
    private Tuple<String, String> resolveGdPlayUrl() {
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        final String platform = getGdPlatform().isEmpty() ? GdStudioSourceSettings.getPlatform() : getGdPlatform();
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() throws Exception {
                DownloadDynamicIsland.showGdSourceResolving("GD音乐台 · " + platform.toUpperCase(Locale.ROOT));
                // 受保护解析：先走选中源上文档记载的码率阶梯，再
                //按标题/艺术家在一个健康源重新匹配。只有整条链完全失败才上报失败。
                GdStudioMusicService.ResolveResult result = GdStudioMusicService.resolveTrackWithFallback(
                        platform, getSourceId(), getName(), getArtistsName(), requestedQuality);
                if (result == null) {
                    String reason = GdStudioMusicService.getHealth(platform).lastError;
                    DownloadDynamicIsland.showGdSourceFailure("GD音乐台",
                            reason.isEmpty() ? "当前平台未返回可播放链接" : reason);
                    return null;
                }
                DownloadDynamicIsland.showGdSourceResolved(result.isFallback()
                        ? "已回退 " + GdStudioMusicService.displayName(result.platform)
                        : "GD音乐台 · " + result.platform.toUpperCase(Locale.ROOT), result.format);
                playbackQuality = detectCadencePlaybackQuality(result.format, requestedQuality);
                considerHighestQuality(result.format, 0L);
                return new Tuple<>(result.url, result.format);
            }
        }, "GD Studio", GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    /**
     * 在字节级检查拒绝选中源给出的流后，在另一个健康源上重新解析该 GD Studio 曲目
     * （DASH 视频、HTML 错误页、截断载荷）。
     */
    public Tuple<String, String> getGdCrossSourceFallbackPlayUrl() {
        if (!isGd()) return null;
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        final String platform = getGdPlatform().isEmpty() ? GdStudioSourceSettings.getPlatform() : getGdPlatform();
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() throws Exception {
                GdStudioMusicService.ResolveResult result = GdStudioMusicService.resolveCrossSource(
                        platform, getName(), getArtistsName(), requestedQuality);
                if (result == null) return null;
                DownloadDynamicIsland.showGdSourceResolved("已回退 "
                        + GdStudioMusicService.displayName(result.platform), result.format);
                playbackQuality = detectCadencePlaybackQuality(result.format, requestedQuality);
                considerHighestQuality(result.format, 0L);
                return new Tuple<>(result.url, result.format);
            }
        }, "GD cross-source fallback", GD_PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    /**
     * 在字节级检查拒绝最初返回的载荷后，把一首网易云歌重新解析为
     * 已知可播放的标准 MP3 流。
     */
    public Tuple<String, String> getStandardMp3PlayUrl() {
        if (!isNetease()) {
            return null;
        }
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveNeteasePlayUrl(CloudMusicApi.songUrlStandardMp3(id),
                        "post-download standard MP3 fallback");
            }
        }, "post-download standard MP3 fallback");
    }
    /**
     * 酷狗取流。
     *
     * <p>音质降级由 {@code KuGouMusicApi.songUrl} 自己在三个 hash 之间做，所以这里只需要一次调用：
     * 上游对每个音质是独立授权的，把降级放在能同时看到三个 hash 的那一层，比在这里重复三次请求更省。</p>
     */
    private Tuple<String, String> resolveKuGouPlayUrl() {
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                Tuple<String, String> resolved = normalizePlayUrl(
                        com.muoniumplayer.core.ncm.music.KuGouMusicService.getSongUrl(Music.this, requestedQuality));
                if (resolved != null) {
                    playbackQuality = detectCadencePlaybackQuality(resolved.getB(), requestedQuality);
                    considerHighestQuality(resolved.getB(), 0L);
                }
                return resolved;
            }
        }, "KuGou " + requestedQuality.getQuality());
    }

    /** 先按配置档位试一次，再试一条已知可播放的标准 Cadence 流。 */
    private Tuple<String, String> resolveCadenceWithStandardFallback() {
        final Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        Tuple<String, String> result = resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveWithCadence(requestedQuality);
            }
        }, "Cadence " + requestedQuality.getQuality());
        if (result != null || requestedQuality == Quality.STANDARD) return result;

        return resolveWithTimeout(new Callable<Tuple<String, String>>() {
            @Override
            public Tuple<String, String> call() {
                return resolveWithCadence(Quality.STANDARD);
            }
        }, "Cadence standard fallback");
    }

    private Tuple<String, String> resolveWithCadence(Quality requestedQuality) {
        Tuple<String, String> resolved = normalizePlayUrl(NeteaseMusicService.getSongUrl(this, requestedQuality));
        if (resolved != null) {
            this.playbackQuality = detectCadencePlaybackQuality(resolved.getB(), requestedQuality);
            considerHighestQuality(resolved.getB(), 0L);
        }
        return resolved;
    }

    private Tuple<String, String> resolveWithBuiltInNeteaseApi() {
        // 默认配置先请求无损。若用户刻意
        //在模块设置中选了另一个档位，则遵从该显式偏好；任何
        //不可用/VIP 受限/失败的主请求仍回退到标准 MP3。
        Quality requestedQuality = CloudMusic.quality == null ? Quality.LOSSLESS : CloudMusic.quality;
        Tuple<String, String> preferred = resolveNeteasePlayUrl(
                CloudMusicApi.songUrlV1(this.id, requestedQuality.getQuality().toLowerCase(Locale.ROOT)),
                "requested quality");
        if (preferred != null) return preferred;

        return resolveNeteasePlayUrl(CloudMusicApi.songUrlStandardMp3(this.id), "standard MP3 fallback");
    }

    private Tuple<String, String> resolveNeteasePlayUrl(RequestUtil.RequestAnswer answer, String requestDescription) {
        try {
            if (answer == null || answer.getStatus() != 200) return null;

            JsonObject result = answer.toJsonObject();
            if (result == null || !result.has("data") || !result.get("data").isJsonArray()
                    || result.getAsJsonArray("data").size() == 0) return null;

            JsonObject music = result.getAsJsonArray("data").get(0).getAsJsonObject();
            if ((music.has("code") && !music.get("code").isJsonNull() && music.get("code").getAsInt() != 200)
                    || !music.has("url") || music.get("url").isJsonNull()) return null;

            String url = music.get("url").getAsString();
            if (url == null || url.trim().isEmpty()) return null;

            String type = "mp3";
            if (music.has("type") && !music.get("type").isJsonNull()) {
                String responseType = music.get("type").getAsString();
                if (responseType != null && !responseType.trim().isEmpty()) type = responseType;
            }
            Tuple<String, String> normalized = normalizePlayUrl(new Tuple<>(url, type));
            if (normalized != null) {
                this.playbackQuality = detectNeteasePlaybackQuality(music, normalized.getB());
                considerHighestQuality(readString(music, "level"), readLong(music, "br"));
            }
            return normalized;
        } catch (Throwable throwable) {
            System.err.println("[Music/NetEase] " + requestDescription + " URL resolver failed for "
                    + getStableKey() + ": " + throwable.getMessage());
            return null;
        }
    }
    private Tuple<String, String> resolveWithTimeout(Callable<Tuple<String, String>> resolver, String resolverName) {
        return resolveWithTimeout(resolver, resolverName, PLAY_URL_RESOLVE_TIMEOUT_SECONDS);
    }

    private Tuple<String, String> resolveWithTimeout(Callable<Tuple<String, String>> resolver, String resolverName,
                                                      long timeoutSeconds) {
        Future<Tuple<String, String>> future = URL_RESOLVER.submit(resolver);
        try {
            return normalizePlayUrl(future.get(Math.max(1L, timeoutSeconds), TimeUnit.SECONDS));
        } catch (TimeoutException timeout) {
            future.cancel(true);
            if ("GD Studio".equals(resolverName)) {
                DownloadDynamicIsland.showGdSourceFailure("GD音乐台", "获取播放链接超时");
            }
            System.err.println("[Music] " + resolverName + " URL resolver timed out for " + getStableKey());
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            if ("GD Studio".equals(resolverName)) {
                DownloadDynamicIsland.showGdSourceFailure("GD音乐台", "获取播放链接被中断");
            }
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            future.cancel(true);
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if ("GD Studio".equals(resolverName)) {
                DownloadDynamicIsland.showGdSourceFailure("GD音乐台",
                        cause.getMessage() == null || cause.getMessage().trim().isEmpty()
                                ? "获取播放链接失败" : cause.getMessage());
            }
            System.err.println("[Music] " + resolverName + " URL resolver failed for " + getStableKey()
                    + ": " + cause.getMessage());
        }
        return null;
    }

    private PlaybackQuality detectNeteasePlaybackQuality(JsonObject response, String format) {
        if (isLosslessFormat(format)) {
            return PlaybackQuality.LOSSLESS;
        }

        String level = readString(response, "level").toLowerCase(Locale.ROOT);
        if ("lossless".equals(level) || "hires".equals(level)
                || "jyeffect".equals(level) || "jymaster".equals(level)) {
            return PlaybackQuality.LOSSLESS;
        }

        long bitrate = readLong(response, "br");
        if (bitrate >= 256000L || "higher".equals(level) || "exhigh".equals(level)
                || "sky".equals(level)) {
            return PlaybackQuality.HQ;
        }
        return PlaybackQuality.STANDARD;
    }

    private PlaybackQuality detectCadencePlaybackQuality(String format, Quality requestedQuality) {
        if (isLosslessFormat(format)) {
            return PlaybackQuality.LOSSLESS;
        }
        // Cadence 不为每个提供方提供码率。一个无损请求
        //返回有损容器的，被当作真正的标准兜底。
        return (requestedQuality == Quality.HIGHER
                || requestedQuality == Quality.EXHIGH || requestedQuality == Quality.SKY)
                ? PlaybackQuality.HQ : PlaybackQuality.STANDARD;
    }

    private static boolean isLosslessFormat(String format) {
        String normalized = nonNull(format).trim().toLowerCase(Locale.ROOT);
        return "flac".equals(normalized) || "wav".equals(normalized);
    }

    private static String readString(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return "";
        try {
            return nonNull(object.get(property).getAsString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long readLong(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return 0L;
        try {
            return object.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static Tuple<String, String> normalizePlayUrl(Tuple<String, String> result) {
        if (result == null || result.getA() == null || result.getA().trim().isEmpty()) return null;

        String url = result.getA().trim();
        String type = result.getB() == null ? "" : result.getB().trim().toLowerCase(Locale.ROOT);
        int separator = type.indexOf(';');
        if (separator >= 0) type = type.substring(0, separator).trim();
        if (type.startsWith("audio/")) type = type.substring("audio/".length());
        if ("mpeg".equals(type) || "mpeg3".equals(type) || "x-mp3".equals(type)) type = "mp3";
        if ("x-flac".equals(type)) type = "flac";
        if ("x-wav".equals(type) || "wave".equals(type)) type = "wav";
        if ("adts".equals(type) || "x-aac".equals(type)) type = "aac";
        if ("mp4a".equals(type) || "m4a".equals(type)) type = "m4a";

        if (!isSupportedFormat(type)) {
            type = inferFormat(url);
        }
        return isSupportedFormat(type) ? new Tuple<>(url, type) : null;
    }

    private static boolean isSupportedFormat(String type) {
        // 下载字节在播放前仍由 CloudMusic 校验。AAC/M4A 到达
        //既有的解码器；通用 MP4 到达文件头拒绝/通知路径。
        return "mp3".equals(type) || "flac".equals(type) || "wav".equals(type)
                || "aac".equals(type) || "m4a".equals(type) || "mp4".equals(type);
    }

    private static String inferFormat(String url) {
        String clean = nonNull(url).toLowerCase(Locale.ROOT);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int fragment = clean.indexOf('#');
        if (fragment >= 0) clean = clean.substring(0, fragment);
        int dot = clean.lastIndexOf('.');
        return dot >= 0 && dot < clean.length() - 1 ? clean.substring(dot + 1) : "";
    }

    /**
     * 把收藏状态写到平台账号上。
     *
     * <p>酷狗走自己的适配层：它的「我喜欢」是一个普通歌单，加曲与删曲要的是数字 {@code listid}
     * 和 {@code fileid}（后者还得先按 hash 去查），与网易云那句 {@code /api/like} 完全不是一回事。</p>
     */
    public void setLike(boolean like) {
        if (isNetease()) CloudMusicApi.like(this.id, like);
        else if (isKuGou()) {
            com.muoniumplayer.core.ncm.music.KuGouMusicService.setLike(this, like);
        }
    }

    public boolean isInstrumental() { return (this.featureFlag & INSTRUMENTAL) != 0; }
    public boolean isDolbyAtmos() { return (this.featureFlag & DOLBY_ATMOS) != 0; }
    public boolean isDirty() { return (this.featureFlag & DIRTY) != 0; }
    public boolean isHiRes() { return (this.featureFlag & HIRES) != 0; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Music)) return false;
        Music music = (Music) o;
        return getSource() == music.getSource() && getSourceId().equals(music.getSourceId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSource(), getSourceId());
    }

    private static long parseNeteaseId(String id, String mid) {
        try {
            return Long.parseLong(nonNull(id));
        } catch (NumberFormatException ignored) {
            return stableLong(MusicPlatform.NETEASE.name() + ':' + nonNull(id) + ':' + nonNull(mid));
        }
    }

    private static long stableLong(String value) {
        byte[] bytes = nonNull(value).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
