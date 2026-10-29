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
    /** 当前封面提取出的代表色，供歌词页主题与流体背景实时取用；不进入歌单序列化。 */
    private transient volatile int[] coverPalette;
    /**
     * 电台 / 播客节目标记。节目名是"第 N 期 · xxx"这类串行标题，拿去别的平台按歌名匹配
     * 只会换来一首先人后鬼的替身，所以这一位一旦置上，自动换源救援对该条目全程短路
     * （放不出来就报失败，绝不悄悄换成别的平台搜出来的同名歌）。
     */
    private transient boolean djProgram;
    /**
     * Apple 官方 30 秒试听地址（{@link #fromAppleTrack} 填入）。
     *
     * <p>试听兜底播放时省一次单曲详情请求；缓存到行上而不是每次现查，是因为兜底只发生
     * 在「换源与 gamdl 都失败」的链路末端，再来一次网络往返只会让失败体感更差。</p>
     */
    private transient String applePreviewUrl;
    /**
     * 本行正在走「30 秒 preview 兜底」。与汽水的 {@code qishuiTrialResolve} 同族的 transient
     * 运行态：true 时缓存键带 {@code pv_} 标记（试听文件不冒充完整版），试听片段检测对它
     * 放行（这是设计终点不是故障）。gamdl 完整链路每次尝试前都会复位为 false。
     */
    private transient volatile boolean applePreviewOnly;
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
     * 换源配对键：本格被换源改写前后的两条身份（原曲与替身）各持同一把。
     *
     * <p>播放队列是面板列表的浅拷贝，自动换源只 set 进替身、行 widget 还绑着原曲对象；
     * 高亮与待播角标按 {@code stableKey} 相等匹配，替身换了平台与 id 就永远对不上，
     * 表现为「换源成功的那一行丢了正在播放的焦点」。录取点把两边都标成同一把钥匙，
     * 显示层在 stableKey 不等时再看这一位，焦点就跟着声音走。</p>
     */
    private transient volatile String crossSourcePeerKey = "";
    /**
     * 换源<b>替身侧</b>的落点平台；被替换的原曲侧恒为 null。
     *
     * <p>只由 {@code CrossSourceSwitcher.propagateUnblockLabel} 写给替身：替身的
     * {@code source} 就是落点平台，但重播路径上脚本/解灰链路开头会清掉徽标、官方链路
     * 成功又不写 —— {@code getPlayUrl} 的徽标恢复要靠这一位自证「本次官方流确实来自
     * 录取时的落点」，防止把旧落点徽标恢复到一个本次走自己平台官方链的对象上。
     * {@code transient}：描述这一次点播，不落盘、不参与相等性。</p>
     */
    private transient volatile MusicPlatform crossSourceLandedPlatform;
    /**
     * 这一行的声音是<b>跨平台换源</b>换来的（而不是本平台自己解出来的第三方流）。
     *
     * <p>界面上要分两种词说：换源换来的说「已换源 · 网易云」，本平台的解灰 / 脚本流说
     * 「解灰 · lx/网易云」。{@code unblockedSourceLabel} 只说「声音出自哪里」，分不出这两件事，
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
     * 本次实际取到的那条流属于哪一档；上游没给可辨认的档位名时为 null。
     *
     * <p>描述的是「这一次点播发生了什么」，所以是 transient：不落盘、不参与相等性。
     * 界面拿它与用户选的档位拼成「选的 → 实得」，取代从前那串码率列表。</p>
     */
    private transient volatile Quality qishuiActualTier;
    /**
     * 本次解析到的汽水流是否试听片段（取流层 {@code ResolveResult#full} 的镜像）。
     *
     * <p>播放层判到「只播了半首」时用它如实归因：是后端未就绪/未登录的临时降级，
     * 还是账号权益真的不够——按时长差瞎猜会把前者说成「当前账号拿不到完整版」。
     * 描述本次点播，与 {@link #qishuiActualTier} 同为 transient。</p>
     */
    private transient volatile boolean qishuiTrialResolve;

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

    /** 本次实际取到的汽水档位；null = 上游没给可辨认的档位名。 */
    public Quality getQishuiActualTier() {
        return qishuiActualTier;
    }

    public void setQishuiActualTier(Quality tier) {
        qishuiActualTier = tier;
    }

    /** 本次解析到的汽水流是否试听；非汽水源恒 false。 */
    public boolean isQishuiTrialResolve() {
        return qishuiTrialResolve;
    }

    public void setQishuiTrialResolve(boolean trial) {
        qishuiTrialResolve = trial;
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

    /**
     * 由一条 Spotify 曲目元数据构造 {@link Music} 行。
     *
     * <p>这条行<b>没有本家音频</b>（官方 Web API 无音频出口），它存在的意义是：搜索结果里
     * 出现真实的 Spotify 曲目（封面、时长、多歌手），点播时 {@code #getPlayUrl()} 返回空、
     * 由跨平台换源在其余平台找同名同歌手的替身。{@code id} 用 spotify track id 的稳定哈希，
     * 与酷狗同理由：封面 / 下载缓存、播放历史全按 {@link #getStableKey()} 落盘。</p>
     *
     * <p>{@code vip} 恒为 false：Spotify 的元数据没有「会员才能看」的概念，替身的会员
     * 限制由替身自己那行在播放链路上如实呈现。</p>
     */
    public static Music fromSpotifyTrack(
            com.muoniumplayer.core.ncm.spotify.SpotifyMusicService.Track track) {
        if (track == null) return null;
        // 本地文件行与空 id 行没有可跨平台匹配的规范化元数据，映射出来只会是一行永远放不出声的废行。
        if (track.local) return null;
        String trackId = nonNull(track.id).trim();
        if (trackId.isEmpty()) return null;
        long stableId = stableLong("spotify:" + trackId);

        List<Artist> artistList = new ArrayList<>();
        String artists = nonNull(track.artist);
        if (!artists.trim().isEmpty()) {
            for (String artistName : artists.split("\\s*/\\s*")) {
                if (artistName.trim().isEmpty()) continue;
                artistList.add(new Artist(stableLong("spotify:artist:" + artistName), artistName.trim(),
                        Collections.emptyList(), Collections.emptyList()));
            }
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown Artist", Collections.emptyList(), Collections.emptyList()));
        }

        String albumName = nonNull(track.album);
        String cover = nonNull(track.coverUrl);
        Album album = new Album(stableLong("spotify:album:" + nonNull(track.albumId)), albumName, cover,
                Collections.emptyList());
        Music music = new Music(nonNull(track.name), nonNull(track.name), "", stableId,
                artistList, Collections.emptyList(), album, track.durationMillis, 0L, 0L,
                Collections.emptyList());
        music.source = MusicPlatform.SPOTIFY;
        music.sourceId = trackId;
        music.sourceMid = trackId;
        music.externalCoverUrl = cover;
        music.foreignCover = !cover.isEmpty();
        return music;
    }

    /**
     * 由一条 Apple Music 曲目元数据构造 {@link Music} 行。
     *
     * <p>与 {@link #fromSpotifyTrack} 同族但不同命：Apple 行<b>不是纯元数据行</b>——官方有
     * 30 秒 preview，且 gamdl 伴生后端可用时能拉到完整版。所以 {@link #getPlayUrl()} 对它
     * 走三段：伪 URL（{@code muonium-apple://}，由伴生链路兑现成完整版本地文件）→
     * 返回空落换源替身 → 试听兜底（{@code CloudMusic} 的 Apple 特例）。{@code id} 用
     * Apple catalog 数字 id 的稳定哈希，封面 / 缓存按 {@link #getStableKey()} 落盘。</p>
     *
     * <p>{@code previewUrl} 暂存在行上：试听兜底时省一次单曲详情请求。</p>
     */
    public static Music fromAppleTrack(
            com.muoniumplayer.core.ncm.apple.AppleMusicService.Track track) {
        if (track == null) return null;
        String trackId = nonNull(track.id).trim();
        if (trackId.isEmpty()) return null;
        long stableId = stableLong("apple:" + trackId);

        List<Artist> artistList = new ArrayList<>();
        String artists = nonNull(track.artist);
        if (!artists.trim().isEmpty()) {
            for (String artistName : artists.split("\\s*/\\s*")) {
                if (artistName.trim().isEmpty()) continue;
                artistList.add(new Artist(stableLong("apple:artist:" + artistName), artistName.trim(),
                        Collections.emptyList(), Collections.emptyList()));
            }
        }
        if (artistList.isEmpty()) {
            artistList.add(new Artist(0L, "Unknown Artist", Collections.emptyList(), Collections.emptyList()));
        }

        String albumName = nonNull(track.album);
        String cover = nonNull(track.coverUrl);
        Album album = new Album(stableLong("apple:album:" + nonNull(track.albumId)), albumName, cover,
                Collections.emptyList());
        Music music = new Music(nonNull(track.name), nonNull(track.name), "", stableId,
                artistList, Collections.emptyList(), album, track.durationMillis, 0L, 0L,
                Collections.emptyList());
        music.source = MusicPlatform.APPLE;
        music.sourceId = trackId;
        music.sourceMid = trackId;
        music.externalCoverUrl = cover;
        music.foreignCover = !cover.isEmpty();
        music.applePreviewUrl = nonNull(track.previewUrl);
        return music;
    }

    /**
     * 为「同一首歌在队列里占第二格」复制一份<b>独立的队列身份</b>。
     *
     * <p>{@code playNext} 若把同一对象引用插进队列，之后换源录取点写在对象上的徽标与
     * 换源配对键会同时落到两格上：没被换源的那格行也挂「已换源」徽标、也亮「正在播放」
     * 高亮，而显示层（行 widget 只认对象）无法区分它们。让第二格拿一份浅拷贝，
     * 徽标 / 配对键 / 播放态这些「这一格发生过什么」的 transient 运行态就互相隔离开了。</p>
     *
     * <p>拷贝体带上<b>取流与显示必需</b>的静态身份（12 个 final 字段 + source/sourceId/
     * sourceMid/GD 与酷狗主键 + cadenceTrack 缓存 + 会员标记 + 封面链接），{@code equals}
     * （platform+id）与账本、高亮、队列去重的判据完全不变。刻意<b>不带</b>：解灰 / 换源
     * 徽标、crossSourceSwitched、配对键、解码时长、播放质量、汽水一次性 play_auth ——
     * 这些描述的是「原来那一格的这一次点播」，第二格还没播过，留默认才是对的。</p>
     *
     * <p>覆盖 {@code artistsName}/{@code translatedNames} 这两个惰性缓存：拷贝体第一次
     * 渲染就能直接用，不必重拼一次。</p>
     */
    public Music queueIdentityCopy() {
        Music copy = new Music(name, mainTitle, additionalTitle, id, artists, aliasName,
                album, duration, featureFlag, publishTime, translatedName);
        copy.source = source;
        copy.sourceId = sourceId;
        copy.sourceMid = sourceMid;
        copy.gdPlatform = gdPlatform;
        copy.gdPicId = gdPicId;
        copy.gdLyricId = gdLyricId;
        copy.kugouHash = kugouHash;
        copy.kugouHash320 = kugouHash320;
        copy.kugouHashLossless = kugouHashLossless;
        copy.kugouAlbumId = kugouAlbumId;
        copy.kugouAlbumAudioId = kugouAlbumAudioId;
        copy.kugouCoverTemplate = kugouCoverTemplate;
        copy.cadenceTrack = cadenceTrack;
        copy.vip = vip;
        copy.cloudSong = cloudSong;
        copy.externalCoverUrl = externalCoverUrl;
        copy.foreignCover = foreignCover;
        copy.artistsName = artistsName;
        copy.translatedNames = translatedNames;
        return copy;
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

    /**
     * 这首的声明时长是否来自解码器（搜索时上游没给时长）。
     *
     * <p>GD 落点的试听判定依赖它：搜索响应不带时长 → 解码后 {@link #applyDecodedDuration}
     * 把 30 秒试听回填成「声明时长」→ {@code isIncompleteStream} 的「声明 ≥90 秒」第一道闸
     * 把残曲当完整曲目放行。播放链路拿这一位旁路判定（解码出的绝对时长过短且无上游参照
     * = 不可信完整）。</p>
     */
    public boolean durationCameFromDecoder() {
        return duration <= 0L && decodedDurationMillis > 0L;
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

    /**
     * Spotify 行：只有元数据（官方 Web API 无音频出口），取流由跨平台换源接管。
     * 见 {@link #fromSpotifyTrack} 与 {@code SpotifyMusicService} 的类注释。
     */
    public boolean isSpotify() {
        return getSource() == MusicPlatform.SPOTIFY;
    }

    /**
     * Apple Music 行：目录元数据公开、完整音轨 DRM 保护。取流顺序
     * gamdl 伴生 → 跨平台换源 → 30 秒 preview 兜底，见 {@code AppleMusicService} 类注释。
     */
    public boolean isApple() {
        return getSource() == MusicPlatform.APPLE;
    }

    /** Apple 官方 30 秒试听地址（{@link #fromAppleTrack} 填入）；空串 = 尚未取到。 */
    public String getApplePreviewUrl() {
        return applePreviewUrl == null ? "" : applePreviewUrl;
    }

    public boolean isApplePreviewOnly() {
        return applePreviewOnly;
    }

    public void setApplePreviewOnly(boolean previewOnly) {
        applePreviewOnly = previewOnly;
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

    /** 测试与换源候选取用时写入「会员/付费受限」标记；正常解析链路只读它。 */
    public void setVipRestricted(boolean restricted) {
        this.vip = restricted;
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
     * <p>默认配置是无损优先：先请求无损；曲目没有无损、账号没有 VIP、接口返回空链接、
     * 解析异常或超时时，再明确降级到标准音质。网易云在主解析器失败后继续切换
     * 备用解析器；QQ 仅能使用 Cadence，但同样执行无损到标准的降级。</p>
     *
     * <p>外面这层还负责换源替身重播的徽标兜底：解析链（{@link #resolveLxSource} /
     * {@link #resolveNeteaseUnblock}）都先清掉旧标再干活，走脚本 / 解灰成功会写一枚新的，
     * 走官方链路却没人写 —— automix 预备替身、手动重播替身时，录取点写好的
     * 「已换源 · 落点」就会凭空消失。恢复只认<b>替身自己</b>（录取点给它记过落点平台），
     * 且本次成功链路的平台就是那个落点 —— 被替换的原曲侧没有落点记录，用户重播它时
     * 走的是它自己平台的官方链路，恢复旧落点只会让徽标归因到本次根本没参与供流的平台。</p>
     */
    public Tuple<String, String> getPlayUrl() {
        String switchedBadge = crossSourceSwitched ? getUnblockedSourceLabel() : "";
        MusicPlatform landed = crossSourceLandedPlatform;
        Tuple<String, String> playUrl = resolvePlayUrl();
        if (playUrl != null && !switchedBadge.trim().isEmpty() && crossSourceSwitched
                && landed == getSource()
                && getUnblockedSourceLabel().trim().isEmpty()) {
            this.unblockedSourceLabel = switchedBadge;
        }
        return playUrl;
    }

    private Tuple<String, String> resolvePlayUrl() {
        // 绝不显示从上一次失败解析带过来的质量徽标。
        this.playbackQuality = PlaybackQuality.UNKNOWN;

        // 同理，旧徽标也绝不沿用。第三方链（脚本/解灰）成功会写一枚真实的新徽标；
        // 官方链路成功则什么都不写 —— 留着上一轮「已换源 · X」就是在把本次自己平台
        // 的官方流错误归因到 X 上。替身侧若需要保住录取徽标，由 getPlayUrl 外层的
        // 恢复闸（落点平台自证）负责，不受这一次清空影响。
        this.unblockedSourceLabel = "";

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

        // Spotify 是元数据源：官方 Web API 没有任何音频出口（preview_url 已下线），返回 null
        // 让点播链路落进 CrossSourceSwitcher 按同名同歌手找替身。同样绝不能继续往下掉 ——
        // spotify id 会被 Cadence 当网易云 id 解析，放出完全不相干的歌。
        if (isSpotify()) return null;

        // Apple Music：gamdl 伴生后端可用时给一条伪 URL（cachedAudioFile 里兑换成完整版
        // 本地文件）；伴生不可用/未启用返回 null 落换源替身；都失败还有试听兜底（CloudMusic
        // 的 Apple 特例）。与 Spotify 同理绝不能继续往下掉 —— apple 的数字 id 会被 Cadence
        // 当网易云 id 解析，放出完全不相干的歌。
        if (isApple()) {
            return resolveApplePlayUrl();
        }

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

    /**
     * Apple 的 gamdl 伴生伪 URL。
     *
     * <p>第二元素给 {@code m4a}：伴生链路最终交付的就是 AAC 256 的 m4a 本地文件，
     * 缓存键与容器探测都按这个语义走。伴生不可用（未安装 / 未启用 / 非 Windows 且未配
     * 手动路径）返回 null —— 点播链路随即落进换源替身，用户无感。</p>
     */
    private Tuple<String, String> resolveApplePlayUrl() {
        // 完整版尝试先复位试听标记：上一轮兜底留下的 pv_ 键位不能延续到这一轮，
        // 否则 gamdl 就绪后点歌还在命中试听缓存键位（键位由 audioCachePrefix 生成）。
        this.applePreviewOnly = false;
        String pseudo = com.muoniumplayer.core.ncm.apple.ApplePlaybackResolver
                .resolvePseudoUrl(getSourceId());
        if (pseudo == null) return null;
        playbackQuality = PlaybackQuality.HQ;
        return new Tuple<>(pseudo, "m4a");
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
                // 写成「lx/渠道显示名」（例：lx/酷我）—— 一份脚本五个渠道，只写 lx 读不出落点，
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
                // 解灰换的是「这一条取流地址」不是「这一首歌」：录取点留下的换源标记若还在
                // （本曲曾当过兄弟格/替身），这次成功后前缀会念成「已换源 · …」——本次并没有
                // 换曲目，复位让它回到「解灰 · …」的本义。
                crossSourceSwitched = false;
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

    /** 录取点写入/更新换源配对键；非录取方一律不碰这一位。 */
    public void setCrossSourcePeerKey(String key) {
        this.crossSourcePeerKey = key == null ? "" : key;
    }

    /** 本格换源配对键（原曲与替身同持一把）；从未参与换源时为空串。 */
    public String getCrossSourcePeerKey() {
        String key = crossSourcePeerKey;
        return key == null ? "" : key;
    }

    /**
     * 两条曲目是否站在同一格换源的两侧（原曲 ↔ 替身）。
     *
     * <p>给高亮与「正在播放」判定用：面板行 widget 绑的是原曲老对象，正在出的声却是替身的，
     * stableKey 已经对不上（换了平台与 id）。两侧在录取点都被标了同一把
     * {@code crossSourcePeerKey}，这一位相等即视为同一格。</p>
     */
    public boolean isCrossSourcePeerOf(Music other) {
        if (this == other) return true;
        if (other == null) return false;
        String mine = crossSourcePeerKey;
        return mine != null && !mine.isEmpty() && mine.equals(other.crossSourcePeerKey);
    }

    /**
     * 录取点写入替身的落点平台；{@code replaced} 侧传 null 表示「我不是替身」。
     *
     * <p>重播链路凭这一位自证身份（见字段注释），只有 {@code getPlayUrl} 的徽标恢复读它。</p>
     */
    public void setCrossSourceLandedPlatform(MusicPlatform platform) {
        this.crossSourceLandedPlatform = platform;
    }

    /** 这一对象是否真是换源录取时的<b>替身</b>（而不是被替换的原曲侧）；替身侧返回落点平台。 */
    public MusicPlatform getCrossSourceLandedPlatform() {
        return crossSourceLandedPlatform;
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
                setQishuiActualTier(result.actualTier);
                setQishuiTrialResolve(!result.full);
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
        else if (isQishui()) {
            com.muoniumplayer.core.ncm.music.QishuiMusicService.setTrackLiked(getSourceId(), like);
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
