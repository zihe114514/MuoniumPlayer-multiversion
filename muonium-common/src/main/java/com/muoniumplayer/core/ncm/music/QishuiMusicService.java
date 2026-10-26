package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.muoniumplayer.core.utils.Location;
import top.fpsmaster.music.QrCode;
import top.fpsmaster.music.QrLoginState;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

/**
 * 汽水音乐（Luna）内置音源。
 *
 * <p>搜索沿用 music-lib 的 Android 客户端请求形状；匿名播放与歌词走 qishui-api 已验证的
 * H5 {@code seo_track} 接口。登录使用官方 Passport 二维码，仅接受抖音 App 扫码，确认后把
 * Cookie 存在宿主自己的配置文件中。登录后优先请求带 Cookie 的 PC/播放信息接口，并继续只
 * 选择 AAC/M4A；加密样本在进入现有 AAC 解码器前于本机解密。</p>
 */
public final class QishuiMusicService {

    private static final String SEARCH_ENDPOINT = "https://api.qishui.com/luna/search/track";
    private static final String TRACK_ENDPOINT = "https://beta-luna.douyin.com/luna/h5/seo_track";
    private static final String PC_TRACK_PATH = "/luna/pc/track_v2";
    /** PC 接口的容灾域名，顺序与参考实现一致。 */
    private static final String[] PC_API_BASES = {
            "https://api5-lq.qishui.com", "https://api.qishui.com"
    };
    private static final String PC_SEARCH_PLAYLIST_ENDPOINT = "https://api.qishui.com/luna/pc/search/playlist";
    private static final String PC_SEARCH_MIXED_ENDPOINT = "https://api.qishui.com/luna/pc/search/mixed";
    private static final String PC_ME_PLAYLISTS_ENDPOINT = "https://api.qishui.com/luna/pc/me/playlist";
    private static final String PC_ME_COLLECTION_ENDPOINT = "https://api.qishui.com/luna/pc/me/collection/mixed";
    /** 带 Cookie 的歌单详情：账号歌单只有这条接口给得出完整曲目，见 {@link #accountPlaylistTracks}。 */
    private static final String PC_PLAYLIST_DETAIL_ENDPOINT = "https://api.qishui.com/luna/pc/playlist/detail";
    private static final String LUNA_DISCOVER_ENDPOINT = "https://beta-luna.douyin.com/luna/discover";
    private static final String LUNA_DISCOVER_MIX_ENDPOINT = "https://beta-luna.douyin.com/luna/discover/mix";
    private static final String LUNA_PLAYLIST_DETAIL_ENDPOINT = "https://beta-luna.douyin.com/luna/playlist/detail";
    private static final String LUNA_RADIO_TRACKS_ENDPOINT = "https://beta-luna.douyin.com/luna/feed/radio/tracks";
    private static final String ACCOUNT_ENDPOINT = "https://api.qishui.com/luna/pc/me";
    private static final String COVER_ROOT = "https://p3-luna.douyinpic.com/img/";
    private static final String ANDROID_USER_AGENT =
            "com.luna.music/100198030 (Linux; U; Android 15; zh_CN_#Hans; ABR-AL80; "
                    + "Build/V417IR;tt-ok/3.12.13.19)";
    private static final String WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120 Safari/537.36";
    private static final String PC_USER_AGENT = "LunaPC/3.3.0(359450208)";
    private static final int REQUEST_TIMEOUT_MS = 15_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Map<String, Location> AVATAR_LOCATIONS = new ConcurrentHashMap<>();

    /**
     * 发现页推荐歌单的采样参数。这一条接口不分页（见 {@link #recommendPlaylists}），只能靠
     * 反复抽样去重凑量，所以「几路并发、抽几轮、要够多少」都是这里定的。
     *
     * <p>实测（2026-10，单次 0.3~0.5 秒）：8 并发 × 5 轮 ≈ 3 秒收到 140 张以上；池子上限
     * 约 150~160。所以并发给 8 路，轮数给 6 轮留一点余量，并把池子上限写死避免无谓地抽。</p>
     */
    private static final int RECOMMEND_PARALLELISM = 8;
    private static final int RECOMMEND_ROUNDS = 6;
    /**
     * 一轮采样的等待上限。
     *
     * <p><b>必须大于 {@link #REQUEST_TIMEOUT_MS}</b>：单轮预算比单个请求的超时还短时，
     * 慢网络下请求永远不可能在预算内返回，这一轮必然被判「空手」，连续几轮就把主页判成
     * 失败了 —— 2026-10-11 真机踩过：预算 8 秒、请求超时 15 秒，采样直接放弃、主页全空。
     * 留一点余量让「慢但能通」的请求有机会落地。</p>
     */
    private static final long RECOMMEND_ROUND_TIMEOUT_MS = REQUEST_TIMEOUT_MS + 4_000L;
    /**
     * 整次采样（所有轮加起来）的上限。
     *
     * <p>拦的是「后端半死」那一档：单轮虽然有自己的预算，但六轮串起来最坏仍是几十秒，
     * 而调用方是在给首屏铺内容。到点就带着已经拿到的部分返回（渐进发布已经把首屏发出去了）。</p>
     *
     * <p>只对「已经拿到东西」之后生效 —— 一张都没拿到时不受它约束，否则慢网络会被直接
     * 判成失败（见 {@link #recommendPlaylists(int, Consumer)}）。</p>
     */
    private static final long RECOMMEND_TOTAL_BUDGET_MS = 30_000L;
    /** 池子实测上限约 150~160：目标再高也只是多抽几轮空手回来，直接夹到这里。 */
    private static final int RECOMMEND_POOL_LIMIT = 160;
    /** 电台池子实测只有 24 个左右，抽两轮就见底。 */
    private static final int RADIO_ROUNDS = 3;
    /** 采样用的短命线程池：守护线程，空闲即回收，不占住游戏进程退出。 */
    private static final ExecutorService RECOMMEND_EXECUTOR = Executors.newFixedThreadPool(
            RECOMMEND_PARALLELISM, runnable -> {
                Thread thread = new Thread(runnable, "qishui-discover-sample");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 分页页长。PC 与匿名 Android 两条列表/详情接口都照数给：实测 {@code count=100} 就是满
     * 100 条，而<b>不带 count 时上游回的是短页</b>（{@code /me/playlist} 只回 5 条），
     * 看着像到底了 —— 所以每个分页请求都必须显式带上它。
     */
    private static final int PAGE_SIZE = 100;
    /**
     * 翻页闸门。按 {@link #PAGE_SIZE} 算是两万条，正常账号一辈子用不到；留着的唯一目的是
     * 上游哪天把 {@code next_cursor} 写成不前进的值时，别把客户端熬成死循环。
     */
    private static final int MAX_PAGES = 200;
    /** 侧栏「我的账号歌单」的条数上限：自建 + 收藏合并后再掐，实际账号远到不了这个数。 */
    private static final int ACCOUNT_PLAYLIST_CAP = 500;
    /**
     * 账号歌单 ID 的前缀，决定取曲目走哪条接口（见 {@link #playlistTracks(String, int)}）。
     *
     * <p>同一份歌单在两条接口上给的不是同一个列表：实测一份 {@code count_tracks} 标 186 首的
     * 收藏歌单，匿名 Android 接口逐页翻完只有 116 首，带 Cookie 的 PC 接口给到 238 首。
     * 侧栏里那条既然来自账号，就必须按账号的身份去取。</p>
     */
    private static final String ACCOUNT_PLAYLIST_PREFIX = "acct:";
    /**
     * 「每日推荐」虚拟歌单前缀（见 {@link #playlistTracks(String, int)}）：曲目来自
     * {@code /luna/pc/feed/song-tab}，内容每天变，所以做成虚拟单而不是真歌单。
     */
    private static final String DAILY_PLAYLIST_PREFIX = "qsdaily:";
    /** 每日推荐（发现页歌曲流）与歌单写操作的 PC 端路径，全部走后端签名代理。 */
    private static final String PC_FEED_SONG_TAB_PATH = "/luna/pc/feed/song-tab";

    private static final Pattern SODA_WORD = Pattern.compile("<(\\d+),(\\d+),(\\d+)>([^<]*)");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private QishuiMusicService() {
    }

    public static final class Track {
        public final String trackId;
        public final String title;
        public final String artist;
        public final String album;
        public final long durationMillis;
        public final String coverUrl;
        public final boolean vip;

        Track(String trackId, String title, String artist, String album, long durationMillis,
              String coverUrl, boolean vip) {
            this.trackId = trackId;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMillis = durationMillis;
            this.coverUrl = coverUrl;
            this.vip = vip;
        }
    }

    public static final class ResolveResult {
        public final String url;
        public final String format;
        public final long bitrate;
        public final String playAuth;
        /** 这一条流实际属于哪一档；上游没给可辨认的档位名时为 null。 */
        public final Quality actualTier;
        /** 选中流是否完整版（时长达到声明 95%）；false = 试听片段。播放层归因要用。 */
        public final boolean full;

        ResolveResult(String url, String format, long bitrate, String playAuth, Quality actualTier,
                      boolean full) {
            this.url = url;
            this.format = format;
            this.bitrate = bitrate;
            this.playAuth = playAuth;
            this.actualTier = actualTier;
            this.full = full;
        }
    }

    public static List<Track> search(String query, int limit) {
        if (query == null || query.trim().isEmpty()) return List.of();
        int effectiveLimit = Math.max(1, Math.min(50, limit));

        try {
            JsonObject response = getJson(buildSearchUrl(query.trim(), effectiveLimit),
                    ANDROID_USER_AGENT, "", Collections.emptyMap());
            JsonArray groups = array(response, "result_groups");
            if (groups == null) return List.of();

            List<Track> tracks = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement groupElement : groups) {
                if (!groupElement.isJsonObject()) continue;
                JsonArray data = array(groupElement.getAsJsonObject(), "data");
                if (data == null) continue;
                for (JsonElement itemElement : data) {
                    if (!itemElement.isJsonObject()) continue;
                    JsonObject entity = object(itemElement.getAsJsonObject(), "entity");
                    JsonObject rawTrack = object(entity, "track");
                    String trackId = string(rawTrack, "id");
                    if (trackId.isEmpty() || !seen.add(trackId)) continue;
                    Track track = parseTrack(rawTrack);
                    if (track != null) tracks.add(track);
                    if (tracks.size() >= effectiveLimit) return tracks;
                }
            }
            return tracks;
        } catch (Throwable throwable) {
            System.err.println("[Music/Qishui] Search failed for " + query.trim() + ": "
                    + describe(throwable));
            return List.of();
        }
    }

    public static ResolveResult getSongUrl(String trackId, Quality requestedQuality) {
        String id = nonBlank(trackId);
        if (id.isEmpty()) return null;

        try {
            boolean authenticated = QishuiSessionStore.isLoggedIn();
            List<Stream> candidates = new ArrayList<>();
            long declared = 0L;
            if (authenticated) {
                JsonObject pc = fetchAuthenticatedTrack(id);
                if (pc != null) {
                    candidates.addAll(collectCandidates(pc));
                    declared = Math.max(declared, declaredTrackDuration(pc));
                }
            }
            try {
                JsonObject web = fetchTrack(id, authenticated);
                candidates.addAll(collectCandidates(web));
                declared = Math.max(declared, declaredTrackDuration(web));
            } catch (Throwable webFailure) {
                if (candidates.isEmpty()) throw webFailure;
            }
            return selectAac(candidates, requestedQuality, declared);
        } catch (Throwable throwable) {
            System.err.println("[Music/Qishui] Song URL failed for " + id + ": " + describe(throwable));
            return null;
        }
    }

    /** 汽水 H5 歌词已是逐字时间轴；转换为项目现有 QRC 解析器能识别的文本。 */
    public static List<LyricLine> getWordByWordLyrics(Music music) {
        if (music == null || !music.isQishui()) return Collections.emptyList();
        String trackId = nonBlank(music.getSourceId());
        if (trackId.isEmpty()) return Collections.emptyList();

        try {
            String content = lyricContent(fetchTrack(trackId, QishuiSessionStore.isLoggedIn()));
            if (content.isEmpty()) return Collections.emptyList();
            String qrc = sodaWordsToQrc(content);
            List<LyricLine> lines = new ArrayList<>();
            LyricParser.parseQrc(qrc, lines);
            return lines;
        } catch (Throwable throwable) {
            System.err.println("[Music/Qishui] Lyric failed for " + trackId + ": " + describe(throwable));
            return Collections.emptyList();
        }
    }
    /**
     * 申请一次扫码登录的二维码。
     *
     * <p>码不是模组自己取的：Passport 那一步要 {@code a_bogus} / {@code msToken}，只有官方安全
     * 组件在浏览器渲染进程里算得出来，Java 侧复现不了。这里只负责把外部取码工具拉起来，拿它
     * 给的扫码链接（{@link QrCode#getQrContent()}），二维码仍由界面自己画。</p>
     *
     * <p><b>工具不在就直接说缺什么，不回落。</b>此前内置的那条直连请求没有签名，上游必然拒绝，
     * 现象是「码出来了但永远扫不上」——比直接告诉用户该把 exe 放哪难查得多。上一版 sidecar
     * 就是因为静默回落才当夜回滚的。</p>
     */
    public static QrCode createQrCode() {
        // 登录只走外部取码工具(QishuiCookieTool):passport 的扫码确认会触发 2046
        // 风控二次验证,闭环需要官方验证组件在浏览器环境里运行——只有这个工具有。
        // 后端(qs-backend)管的是取流/账号数据的签名,与登录无关,两条链路别混。
        if (!QishuiLoginBridge.isAvailable()) {
            throw new IllegalStateException("汽水扫码需要外部取码工具：把 QishuiCookieTool.exe 放到 "
                    + QishuiLoginBridge.requiredPath());
        }
        try {
            return QishuiLoginBridge.start();
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("获取汽水登录二维码失败: " + describe(error), error);
        }
    }

    /**
     * 读一次外部取码工具的状态。<b>必须在后台线程调用。</b>
     *
     * <p>确认之后走的是与「Cookie 登录」<b>同一条</b>收尾路径：工具给的是一份完整的 cookie 文本，
     * 交给 {@link #importCookie} 真校验一遍才落盘。扫码与粘贴不该有第二种登录结果，也不该有
     * 第二个写会话的地方。</p>
     *
     * <p>{@code MFA}（上游要求二次验证）映射到 {@link QrLoginState#WAITING} 而不是
     * {@link QrLoginState#ERROR}：那一刻工具已经把验证窗口弹出来了，人是<b>在游戏外</b>解决它的，
     * 游戏里改成「等待」才对得上；界面要照抄 {@link #getQrStatusHint()}，别自己编一句「等待扫码」。</p>
     */
    public static QrLoginState checkQrCode(QrCode qrCode) {
        if (qrCode == null) return QrLoginState.ERROR;
        QishuiLoginBridge.State state = QishuiLoginBridge.poll();
        if (state == null) return QrLoginState.ERROR;
        if (!state.isAlive()) {
            System.err.println("[Music/Qishui] 外部取码工具停止刷新状态，按失败处理");
            return QrLoginState.ERROR;
        }
        switch (state.phase) {
            case STARTING:
            case WAITING:
            case MFA:
                return QrLoginState.WAITING;
            case SCANNED:
                return QrLoginState.SCANNED;
            case EXPIRED:
                return QrLoginState.EXPIRED;
            case CONFIRMED:
                CookieImport imported = importCookie(state.cookie);
                if (!imported.success) {
                    System.err.println("[Music/Qishui] 扫码拿到的 Cookie 没通过校验: " + imported.message);
                    return QrLoginState.ERROR;
                }
                return QrLoginState.CONFIRMED;
            default:
                return QrLoginState.ERROR;
        }
    }

    /**
     * 外部取码工具最近一次给人看的话（「请用抖音 App 扫码」「请在弹出的窗口里完成验证」等）。
     * 风控弹窗那一步只能用得上它：工具此刻正卡在等待用户操作上，模组这边什么都没发生。
     */
    public static String getQrStatusHint() {
        return QishuiLoginBridge.statusHint();
    }

    /**
     * 结束这一次扫码：让外部工具转入空闲等复用，<b>不杀进程</b>。
     *
     * <p>不管人是登上了、过期了还是翻页走了都会走到这里。以前这里是直接把进程 kill 掉，
     * 于是每点开一次汽水账号页都要重等一遍 88 MB 自解压；更要命的是风控那一次——人正在
     * 外部窗口里收验证码，工具被判「停止刷新」后我们反手把它 kill 了，人验完回到游戏
     * 只剩一句登录失败。工具空闲时会自己倒计时退出，孤儿进程由它自己负责。</p>
     */
    public static void cancelQrLogin() {
        QishuiLoginBridge.releaseSession();
    }

    public static boolean isLoggedIn() {
        return QishuiSessionStore.isLoggedIn();
    }

    /**
     * 本地留着凭据、但上游已经拒绝它。与「未登录」是两种状态：这种时候界面要说的是
     * 「Cookie 过期了，去重扫或重新导入」，而不是假装账号从来没登录过、把昵称头像全抹掉。
     */
    public static boolean isReauthRequired() {
        return QishuiSessionStore.isLoggedIn() && QishuiSessionStore.isReauthRequired();
    }

    public static String getAccountName() {
        QishuiSessionStore.Session session = QishuiSessionStore.snapshot();
        if (!session.loggedIn) return "未登录";
        return displayName(session.nickname);
    }

    public static String getAccountId() {
        return QishuiSessionStore.snapshot().userId;
    }

    public static String getDouyinId() {
        return QishuiSessionStore.snapshot().douyinId;
    }

    public static String getAvatarUrl() {
        return QishuiSessionStore.snapshot().avatarUrl;
    }

    public static boolean isVipAccount() {
        return QishuiSessionStore.snapshot().vip;
    }

    /**
     * VIP 受限曲目此刻是否必然只拿得到试听：后端未就绪、未登录、账号非会员，命中其一即成立。
     *
     * <p>这是播放前的<b>判定</b>而不是猜测——完整版要「登录 + VIP 账号 + 本机签名后端」三样
     * 同时在位（track_v2 要后端签名，权益要账号），缺哪样上游都只给试听片段；反过来三者齐了
     * VIP 曲目就是完整版。免费曲目不经此判定：匿名也能拿到全曲。列表行的「试听」标据此点亮，
     * 与真实播放结果不符时（权益内却仍拿到试听，如地区限制）由播放层点亮的
     * {@code Music.isQishuiTrialResolve()} 兜底。三个读取全是 volatile 快照，每帧调用安全。</p>
     */
    public static boolean vipContentRestrictedToTrial() {
        return !QishuiBackend.peekAvailable()
                || !QishuiSessionStore.isLoggedIn()
                || !isVipAccount();
    }

    /**
     * 当前账号的会员短标：{@code "SVIP"} / {@code "VIP"}，非会员与未登录返回 {@code null}。
     *
     * <p>给账号行右端的会员徽标用。vip_stage 非空不等于会员（免费账号是 {@code "free"}），
     * 判定仍以 {@code vip} 位为准，stage 只用来分 SVIP 与 VIP 两档文案。</p>
     */
    public static String membershipBadgeText() {
        QishuiSessionStore.Session session = QishuiSessionStore.snapshot();
        if (!session.loggedIn || !session.vip) return null;
        String stage = session.vipStage;
        return stage != null && stage.toLowerCase(Locale.ROOT).contains("svip") ? "SVIP" : "VIP";
    }

    /**
     * 汽水音频缓存的权益世代：登录态 + 账号 VIP + 后端可用性，任一翻转都要换一代。
     *
     * <p>音频缓存键原本只有「曲目 + 音质」。后端未就绪（或未登录）窗口里解析出的试听
     * 流会以这个键落盘，之后哪怕后端已就绪、账号权益足够，点歌依然命中那份试听文件，
     * 完整流 URL 被直接丢弃——用户「充了 VIP 也只放半首」。把这三个状态位折进键里
     * （与酷狗 {@code AUDIO_CACHE_EPOCH} 同款先例），世代一翻旧键自然不命中，
     * 试听文件成为孤儿交给既有 LRU 淘汰，不需要显式失效钩子。</p>
     *
     * <p>三个读取全部是 volatile 快照（{@link QishuiBackend#peekAvailable()} 绝不发探测），
     * 缓存键计算虽不在渲染路径，也保持零阻塞。</p>
     */
    static int audioCacheEpoch() {
        int epoch = 0;
        if (QishuiSessionStore.isLoggedIn()) epoch |= 1;
        if (isVipAccount()) epoch |= 2;
        if (QishuiBackend.peekAvailable()) epoch |= 4;
        return epoch;
    }

    /**
     * 「仅试听」常驻标识的文案：按当下状态说清为什么只有半首。
     *
     * <p>给播放条与全屏播放页的 WARN 徽标用；与 {@code trialFailureReason}（播放失败
     * 归因）同一套判据但措辞面向「正在播的这是什么」——这里是状态说明，不是报错。
     * 三个读取全是 volatile 快照，渲染路径每帧调用安全。</p>
     */
    public static String trialBadgeLabel() {
        if (!QishuiBackend.peekAvailable()) {
            return "仅试听 · 后端未就绪";
        }
        if (!QishuiSessionStore.isLoggedIn()) {
            return "仅试听 · 未登录";
        }
        return "仅试听 · 账号权益不足";
    }

    /** 账号资料的稳定显示名：过滤格式/控制字符，绝不把不可绘制昵称交给 UI。 */
    public static String displayName(String value) {
        if (value == null) return "汽水音乐账号";
        StringBuilder clean = new StringBuilder();
        boolean skipFormatCode = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (skipFormatCode) {
                skipFormatCode = false;
                continue;
            }
            if (codePoint == '\u00a7') {
                skipFormatCode = true;
                continue;
            }
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.UNASSIGNED) continue;
            if (!Character.isWhitespace(codePoint)) clean.appendCodePoint(codePoint);
            else if (clean.length() > 0 && clean.charAt(clean.length() - 1) != ' ') clean.append(' ');
        }
        String result = clean.toString().trim();
        boolean hasIdentityCharacter = result.codePoints().anyMatch(codePoint ->
                Character.isLetterOrDigit(codePoint) || Character.isIdeographic(codePoint));
        return hasIdentityCharacter ? result : "汽水音乐账号";
    }

    /** 头像位置随账号身份和签名 URL 变化，避免复用上个账号的固定贴图。 */
    public static Location avatarLocation(String userId, String avatarUrl) {
        if (avatarUrl == null || avatarUrl.trim().isEmpty()) return null;
        String identity = (userId == null ? "" : userId.trim()) + "|" + avatarUrl.trim();
        return AVATAR_LOCATIONS.computeIfAbsent(identity, key -> {
            StringBuilder hex = new StringBuilder();
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(key.getBytes(StandardCharsets.UTF_8));
                for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
            } catch (Throwable ignored) {
                hex.append(Integer.toHexString(key.hashCode()));
            }
            return Location.of("muonium/textures/account/qishui/" + hex + ".png");
        });
    }

    /**
     * 从 {@code /luna/pc/me} 的响应里定位账号信息本体。
     *
     * <p>实测当前上游把 {@code my_info} 放在<b>顶层</b>，而参考实现的历史 fixture 里它在
     * {@code data} 下。层级是上游会改的东西，所以两种形状都认：先顶层、再 {@code data}，
     * 都没有就退回整包（保留改造前「拿 envelope 兜底」的行为）。</p>
     */
    static JsonObject locateAccountInfo(JsonObject response) {
        JsonObject data = object(response, "data");
        JsonObject[] candidates = {
                object(response, "my_info"), object(response, "user"),
                object(data, "my_info"), object(data, "user"),
        };
        for (JsonObject candidate : candidates) {
            if (candidate.size() > 0) return candidate;
        }
        return data.size() > 0 ? data : response;
    }

    /**
     * 剥掉 PC 接口的响应信封。{@code track_v2}、歌单、收藏这一类把业务数据放在 {@code data}
     * 里，而 H5 的 {@code seo_track} 直接放在顶层，所以 {@code data} 优先、顶层回退。
     * 之前只在顶层找 {@code track_player}，等于登录后每次播放都白读一次。
     */
    static JsonObject envelope(JsonObject response) {
        JsonObject data = object(response, "data");
        return data.size() == 0 ? response : data;
    }

    /**
     * 账号头像地址。
     *
     * <p>字段名是实测出来的：{@code /luna/pc/me} 返回的是 {@code my_info.medium_avatar_url} 与
     * {@code larger_avatar_url}，形状 {@code {"uri":…,"urls":[…,…,…]}}，而 <b>没有</b>
     * {@code avatar_url}、也 <b>没有</b> {@code avatar_thumb}。之前只找后两个名字，于是头像
     * 永远存成空串，账号面板只能退回昵称首字。</p>
     *
     * <p>这些地址带 {@code x-signature} 与 {@code x-expires}，实测约两天后失效，所以它只是一份
     * 会过期的缓存，不是可以永久落盘的 URL：{@link #refreshProfile()} 必须每次启动都跑一次。</p>
     */
    static String resolveAvatarUrl(JsonObject info) {
        String avatar = firstUrl(info.get("medium_avatar_url"));
        if (avatar.isEmpty()) avatar = firstUrl(info.get("larger_avatar_url"));
        if (avatar.isEmpty()) avatar = firstNonBlank(string(info, "avatar_url"), string(info, "avatar"));
        if (avatar.isEmpty()) avatar = firstUrl(info.get("url_avatar"));
        if (avatar.isEmpty()) avatar = firstUrl(info.get("avatar_thumb"));
        if (avatar.isEmpty()) avatar = firstUrl(info.get("avatar_uri"));
        return avatar;
    }

    /**
     * 是否会员。
     *
     * <p>不能再把「vip_stage 非空」当会员：实测<b>免费</b>账号也带这个字段，值是 {@code "free"}。
     * 那一条让每个登录成功的用户都被标成会员，界面上挂一枚假会员标，而用户看到它之后自然会
     * 以为「完整版取不出来」是取流环节的错。</p>
     *
     * <p>也不能反过来只排除 free/none/0 这几个已知非会员值：{@code vip_stage} 的取值域我们没测全，
     * 排除法等于把任何陌生值（包括数字档位）都当成会员。这里用白名单——只有正面证据才挂标，
     * 因为这个徽标只用于展示，漏标少张贴纸，假标则会把排查引到错的地方。</p>
     */
    static boolean resolveVip(JsonObject info) {
        if (truthy(info, "is_vip") || truthy(info, "isVip")) return true;
        String stage = string(info, "vip_stage").toLowerCase(Locale.ROOT);
        return stage.contains("vip") || stage.contains("会员");
    }

    public static boolean refreshProfile() {
        String cookie = QishuiSessionStore.getCookieHeader();
        if (cookie.isEmpty()) return false;
        try {
            JsonObject response = requirePcStatus(
                    getJson(buildAccountUrl(), PC_USER_AGENT, cookie, pcHeaders()), "账号接口");
            JsonObject info = locateAccountInfo(response);
            String nickname = firstNonBlank(string(info, "nickname"), string(info, "name"));
            String userId = firstNonBlank(string(info, "id"), string(info, "user_id"));
            String douyinId = string(info, "douyin_id");
            String avatar = resolveAvatarUrl(info);
            boolean vip = resolveVip(info);
            QishuiSessionStore.saveProfile(userId, nickname, douyinId, avatar, vip,
                    string(info, "vip_stage"));
            if (QishuiSessionStore.isLoggedIn()) {
                QishuiAccountProfiles.saveCurrent();
            }
            QishuiSessionStore.markVerified();
            return true;
        } catch (Throwable error) {
            // 只有上游明确拒绝凭据时才标过期；网络抖动、超时、解析失败都不算，
            // 否则一次断网就会把好好登录着的账号踢成「需要重新登录」。
            if (looksLikeAuthRejection(error)) handleAuthRejection();
            System.err.println("[Music/Qishui] Profile refresh failed: " + describe(error));
            return false;
        }
    }

    /**
     * 手动导入 Cookie 的结果。失败时也带一句能直接显示给用户的话，
     * 界面不需要自己去猜是哪一步崩的。
     */
    public static final class CookieImport {
        public final boolean success;
        public final String message;
        public final String nickname;
        public final boolean vip;

        private CookieImport(boolean success, String message, String nickname, boolean vip) {
            this.success = success;
            this.message = message;
            this.nickname = nickname;
            this.vip = vip;
        }

        private static CookieImport fail(String message) {
            return new CookieImport(false, message, "", false);
        }

        static CookieImport failure(String message) {
            return fail(message);
        }
    }

    /**
     * 用一份用户手上的 Cookie 文本换成本地会话。<b>必须在后台线程调用。</b>
     *
     * <p>顺序是刻意的：先解析、再判凭据名、然后拿它真打一次账号接口，三步全过才落盘。
     * 任何一步失败都原样返回，本地已有的登录态一个字都不会被动过——导入失败的代价
     * 不该是「把原来能用的账号弄没了」。
     */
    public static CookieImport importCookie(String rawInput) {
        Map<String, String> parsed = parseCookieInput(rawInput);
        if (parsed.isEmpty()) {
            return CookieImport.fail("没认出任何 Cookie 字段。可以粘完整的 Cookie 字符串，"
                    + "也可以只粘 sessionid=xxx");
        }
        if (!hasLoginCredential(parsed)) {
            return CookieImport.fail("里面没有登录凭据（sessionid / sid_tt / uid_tt / sid_guard 至少一个）。"
                    + "多半是只复制了匿名 cookie。");
        }
        String cookieHeader = joinCookieMap(parsed);
        try {
            JsonResponse response = getJsonResponse(buildAccountUrl(), PC_USER_AGENT,
                    cookieHeader, pcHeaders());
            requirePcStatus(response.body, "账号接口");
            JsonObject info = locateAccountInfo(response.body);
            String nickname = firstNonBlank(string(info, "nickname"), string(info, "name"));
            if (nickname.isEmpty()) {
                return CookieImport.fail("账号接口没返回昵称，这份 Cookie 大概已经失效了。");
            }
            String userId = firstNonBlank(string(info, "id"), string(info, "user_id"));
            String douyinId = string(info, "douyin_id");
            String avatar = resolveAvatarUrl(info);
            boolean vip = resolveVip(info);

            // 上游可能在响应里轮换 sid_guard / sessionid，带回来就一并收下，
            // 不然存下去的那份下一秒就过期了。
            Map<String, String> merged = new LinkedHashMap<>(parsed);
            for (Map.Entry<String, String> entry
                    : parseCookieInput(response.cookieHeader).entrySet()) {
                merged.put(entry.getKey(), entry.getValue());
            }
            boolean synced = false;
            if (QishuiBackend.probeAlive()) {
                try {
                    synced = QishuiBackend.pushCookie(joinCookieMap(merged), true);
                } catch (Throwable syncError) {
                    System.err.println("[Music/Qishui] 导入前推送 Cookie 给后端失败: " + describe(syncError));
                }
                if (!synced) {
                    return CookieImport.fail("本机签名后端未接受新账号，当前账号保持不变。请稍后重试。");
                }
            }
            QishuiSessionStore.replaceCookies(merged);
            QishuiSessionStore.saveProfile(userId, nickname, douyinId, avatar, vip,
                    string(info, "vip_stage"));
            QishuiSessionStore.markVerified();
            QishuiAccountProfiles.saveCurrent();
            clearUserPlaylistsCache();
            return new CookieImport(true,
                    synced ? "Cookie 登录成功，已同步本机签名后端" : "Cookie 登录成功",
                    displayName(nickname), vip);
        } catch (Throwable error) {
            if (looksLikeAuthRejection(error)) {
                return CookieImport.fail("这份 Cookie 已被上游拒绝（过期、被顶下线或触发验证）。");
            }
            return CookieImport.fail("校验请求没打通：" + describe(error));
        }
    }

    /**
     * 容错解析。用户会从三个地方复制：浏览器 devtools 的 Cookie header 值、
     * 「Copy as cURL」里 -b 的那串、或者只盯着 sessionid 抄一条。参考实现
     * 的 normalizeQishuiCookieInput 也是同样思路（它还吃 JSON 数组形态），
     * 所以这里三种都要认。
     */
    static Map<String, String> parseCookieInput(String rawInput) {
        Map<String, String> out = new LinkedHashMap<>();
        String text = nonBlank(rawInput).replace("\uFEFF", "");
        if (text.isEmpty()) return out;

        int colon = text.indexOf(':');
        if (text.regionMatches(true, 0, "Cookie", 0, 6) && colon > 0 && colon < 12) {
            text = text.substring(colon + 1);
        }
        int eq = text.toLowerCase(Locale.ROOT).indexOf("document.cookie");
        if (eq == 0) {
            int assign = text.indexOf('=');
            if (assign > 0) text = text.substring(assign + 1);
        }
        text = text.trim();

        // devtools / 插件导出的数组形态：[{"name":"sessionid","value":"..."}]
        if (text.startsWith("[")) {
            try {
                JsonElement parsedJson = JsonParser.parseString(text);
                if (parsedJson.isJsonArray()) {
                    for (JsonElement element : parsedJson.getAsJsonArray()) {
                        if (element == null || !element.isJsonObject()) continue;
                        JsonObject item = element.getAsJsonObject();
                        String name = nonBlank(string(item, "name"));
                        String value = nonBlank(string(item, "value"));
                        putCookie(out, name, value);
                    }
                }
            } catch (Throwable ignored) {
                // 当普通文本继续往下走，不因为一次猜错就报错。
            }
            if (!out.isEmpty()) return out;
        }

        for (String chunk : text.split("[;\r\n]+")) {
            String pair = chunk.trim();
            if (pair.isEmpty()) continue;
            int separator = pair.indexOf('=');
            if (separator <= 0) continue;
            putCookie(out, pair.substring(0, separator).trim(),
                    unquote(pair.substring(separator + 1).trim()));
        }
        return out;
    }

    /** 只收真正像 cookie 的键值对：属性名、空值、超长输入一律丢掉。 */
    private static void putCookie(Map<String, String> target, String name, String value) {
        if (target.size() >= 64) return;
        if (name.isEmpty() || value.isEmpty()) return;
        String lower = name.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "domain":
            case "path":
            case "expires":
            case "max-age":
            case "httponly":
            case "secure":
            case "samesite":
            case "partitioned":
                return;
            default:
                break;
        }
        if (value.length() > 4096) return;
        target.put(name, value);
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** 判定「这份 cookie 算不算登录态」，与参考实现的 qishuiCookieHasLogin 同一组名字。 */
    static boolean hasLoginCredential(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return false;
        String[] names = {"sessionid", "sessionid_ss", "sid_tt", "uid_tt", "uid_tt_ss", "sid_guard"};
        for (String name : names) {
            if (!nonBlank(cookies.get(name)).isEmpty()) return true;
        }
        return false;
    }

    private static String joinCookieMap(Map<String, String> cookies) {
        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (header.length() > 0) header.append("; ");
            header.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return header.length() == 0 ? "" : header.append(';').toString();
    }

    /** 上游拒绝凭据的两种表现：HTTP 401/403，或 200 里带 1000016。 */
    private static boolean looksLikeAuthRejection(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            String message = nonBlank(cursor.getMessage());
            if (message.isEmpty()) continue;
            if (message.contains("HTTP 401") || message.contains("HTTP 403")
                    || message.contains("1000016")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 凭据被上游拒绝的统一处置：先试一次「后端缺 Cookie」自愈，救不回来才标过期。
     *
     * <p>{@code 1000016} 有两种成因——后端手里没有 Cookie（启动竞态漏推、后端重装、
     * 手动清了后端 config），或本地 Cookie 真过期/被顶下线。前者与本地凭据无关，
     * 强推一次本地会话就能恢复；不区分就会把好好登录着的账号标成「需要重新验证」，
     * 界面让用户去重扫——他们重登完，下一次还是同样报错。所有
     * {@code looksLikeAuthRejection → markReauthRequired} 的调用点都改走这里，
     * 不要再绕过它直接标。</p>
     *
     * <p>调用方都在后台线程（业务请求链路），自愈里的一次推送/探活开销可接受。</p>
     */
    static void handleAuthRejection() {
        if (QishuiBackend.healMissingCookie()) {
            QishuiSessionStore.markVerified();
            return;
        }
        QishuiSessionStore.markReauthRequired();
    }

    public static void logout() {
        QishuiSessionStore.clear();
        clearUserPlaylistsCache();
    }

    public static void decryptDownloadedAudio(File file, String playAuth) throws IOException {
        if (file == null || playAuth == null || playAuth.trim().isEmpty()) return;
        QishuiAudioDecryptor.decryptInPlace(file.toPath(), playAuth);
    }

    // ── 写操作(全部经后端签名代理;凭据在 qs-backend,模组不持有签名能力) ──

    /** 红心集合的本地缓存:LikeButton 每帧只读它,绝不发请求。 */
    private static volatile Set<String> likedTrackIds = Collections.emptySet();
    private static volatile boolean likedTrackIdsLoadAttempted;


    /** 喜欢/取消喜欢:POST /luna/pc/me/collection/media(/delete)。 */
    public static boolean setTrackLiked(String trackId, boolean liked) {
        String id = nonBlank(trackId);
        if (id.isEmpty() || !QishuiSessionStore.isLoggedIn()) return false;
        try {
            JsonObject payload = new JsonObject();
            JsonArray media = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("type", "track");
            item.addProperty("id", id);
            media.add(item);
            payload.add("media", media);
            payload.addProperty("scene", "");
            String path = liked ? "/luna/pc/me/collection/media"
                    : "/luna/pc/me/collection/media/delete";
            requirePcStatus(QishuiBackend.signedPc(path, Collections.emptyMap(), payload.toString()),
                    liked ? "红心" : "取消红心");
            noteTrackLikedLocal(id, liked);
            return true;
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Set track liked failed for " + id + ": "
                    + describe(error));
            if (looksLikeAuthRejection(error)) handleAuthRejection();
            return false;
        }
    }

    /** 收藏/取消收藏歌单;账号歌单(acct: 前缀)本就在账号里,直接成功不做请求。 */
    public static boolean setPlaylistCollected(String playlistId, boolean collected) {
        String id = nonBlank(playlistId);
        if (id.isEmpty()) return false;
        if (id.startsWith(ACCOUNT_PLAYLIST_PREFIX)) return true;
        if (!QishuiSessionStore.isLoggedIn()) return false;
        try {
            JsonObject payload = new JsonObject();
            JsonArray ids = new JsonArray();
            ids.add(id);
            payload.add("playlist_ids", ids);
            String path = collected ? "/luna/pc/me/collection/playlist"
                    : "/luna/pc/me/collection/playlist/delete";
            requirePcStatus(QishuiBackend.signedPc(path, Collections.emptyMap(), payload.toString()),
                    collected ? "收藏歌单" : "取消收藏歌单");
            return true;
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Set playlist collected failed for " + id + ": "
                    + describe(error));
            if (looksLikeAuthRejection(error)) handleAuthRejection();
            return false;
        }
    }

    /** 把歌加进自建歌单:POST /luna/pc/me/playlist/media/append。 */
    public static boolean addSongToPlaylist(String playlistId, String trackId) {
        String pid = nonBlank(playlistId);
        String id = nonBlank(trackId);
        if (pid.isEmpty() || id.isEmpty() || !QishuiSessionStore.isLoggedIn()) return false;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("playlist_id", pid.startsWith(ACCOUNT_PLAYLIST_PREFIX)
                    ? pid.substring(ACCOUNT_PLAYLIST_PREFIX.length()) : pid);
            JsonArray media = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("type", "track");
            media.add(item);
            payload.add("media", media);
            requirePcStatus(QishuiBackend.signedPc("/luna/pc/me/playlist/media/append",
                    Collections.emptyMap(), payload.toString()), "加歌进歌单");
            return true;
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Add song to playlist failed: " + describe(error));
            if (looksLikeAuthRejection(error)) handleAuthRejection();
            return false;
        }
    }

    // ── 云歌单管理(2026-10-11 逐条实测过:建/改名/删单/删歌四个端点都按下面的形状回) ──

    /**
     * 新建云歌单:POST /luna/pc/me/playlist(与「获取账号歌单」同路径不同方法)。
     *
     * @return 新建歌单的卡片(已带 acct: 前缀),失败返回 null;成功后由调用方刷新侧栏快照
     */
    public static PlayList createCloudPlaylist(String name) {
        String title = nonBlank(name);
        if (title.isEmpty() || !QishuiSessionStore.isLoggedIn()) return null;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("name", title);
            payload.addProperty("is_private", false);
            JsonObject response = requirePcStatus(QishuiBackend.signedPc(
                    pcPathOf(PC_ME_PLAYLISTS_ENDPOINT), Collections.emptyMap(), payload.toString()),
                    "新建歌单");
            PlayList created = parsePlaylistCard(object(response, "playlist"), new JsonObject());
            if (created != null) {
                created.setPlatformPlaylistId(ACCOUNT_PLAYLIST_PREFIX + created.getPlatformPlaylistId());
                clearUserPlaylistsCache();
                return created;
            }
            System.err.println("[Music/Qishui] 新建歌单成功但响应里没有 playlist 对象");
            return null;
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Create cloud playlist failed: " + describe(error));
            if (looksLikeAuthRejection(error)) handleAuthRejection();
            return null;
        }
    }

    /** 改名/改简介:POST /luna/pc/me/playlist/update。两个字段都可独立改,传空即不动。 */
    public static boolean updateCloudPlaylist(String playlistId, String newName, String newDescription) {
        String pid = stripAccountPrefix(playlistId);
        String name = nonBlank(newName);
        String description = newDescription == null ? "" : newDescription.trim();
        if (pid.isEmpty() || (name.isEmpty() && description.isEmpty())
                || !QishuiSessionStore.isLoggedIn()) return false;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("playlist_id", pid);
            if (!name.isEmpty()) payload.addProperty("name", name);
            if (!description.isEmpty()) payload.addProperty("description", description);
            requirePcStatus(QishuiBackend.signedPc("/luna/pc/me/playlist/update",
                    Collections.emptyMap(), payload.toString()), "改歌单信息");
            clearUserPlaylistsCache();
            return true;
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Update cloud playlist failed for " + pid + ": "
                    + describe(error));
            if (looksLikeAuthRejection(error)) handleAuthRejection();
            return false;
        }
    }

    /** 删除云歌单:POST /luna/pc/me/playlist/delete,请求体是 id 数组(实测可批量,这里只删一张)。 */
    public static boolean deleteCloudPlaylist(String playlistId) {
        String pid = stripAccountPrefix(playlistId);
        if (pid.isEmpty() || !QishuiSessionStore.isLoggedIn()) return false;
        try {
            JsonObject payload = new JsonObject();
            JsonArray ids = new JsonArray();
            ids.add(pid);
            payload.add("playlist_ids", ids);
            requirePcStatus(QishuiBackend.signedPc("/luna/pc/me/playlist/delete",
                    Collections.emptyMap(), payload.toString()), "删除歌单");
            clearUserPlaylistsCache();
            return true;
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Delete cloud playlist failed for " + pid + ": "
                    + describe(error));
            if (looksLikeAuthRejection(error)) handleAuthRejection();
            return false;
        }
    }

    /** 从自建歌单删歌:POST /luna/pc/me/playlist/media/delete,media 形状与加歌对称。
     *  2026-10-11 实测可用;曲目行的 ✕ 按钮目前只对本地单显示,这条留着按需接 UI。 */
    public static boolean removeSongFromCloudPlaylist(String playlistId, String trackId) {
        String pid = stripAccountPrefix(playlistId);
        String id = nonBlank(trackId);
        if (pid.isEmpty() || id.isEmpty() || !QishuiSessionStore.isLoggedIn()) return false;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("playlist_id", pid);
            JsonArray media = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("type", "track");
            media.add(item);
            payload.add("media", media);
            requirePcStatus(QishuiBackend.signedPc("/luna/pc/me/playlist/media/delete",
                    Collections.emptyMap(), payload.toString()), "从歌单移除");
            return true;
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Remove song from cloud playlist failed: " + describe(error));
            if (looksLikeAuthRejection(error)) handleAuthRejection();
            return false;
        }
    }

    /** 账号歌单 id 去掉 acct: 前缀;没带前缀原样返回。 */
    private static String stripAccountPrefix(String playlistId) {
        String id = nonBlank(playlistId);
        return id.startsWith(ACCOUNT_PLAYLIST_PREFIX)
                ? id.substring(ACCOUNT_PLAYLIST_PREFIX.length()) : id;
    }

    // ── 发现流(私人 FM 语义;内容个性化,做成 qsdaily: 虚拟歌单的曲目源 + FM 取数引擎) ──

    /**
     * 发现流取数:POST /luna/pc/feed/song-tab。
     *
     * <p><b>私人 FM 语义（2026-10-11 三波实测零重叠）</b>：把听过的歌以 {@code played_media}
     * 回传，下一波就是全新的推荐，{@code has_more} 恒真——它是无限流，不是固定榜单。
     * 汽水首页的「发现」就是抖音式滑动切歌，数据引擎与私人 FM 同构，所以模组把它接进
     * {@link PersonalFmManager}：一次取一首、上报已听、无限续。</p>
     *
     * <p>翻页不靠游标（响应里没有 cursor 字段），靠的就是回传 {@code played_media}；
     * 一次取一页（30 条）整块展示的场景（每日推荐卡）传空 played 即可。</p>
     *
     * @param playedTracks 已听过的歌（新→旧任意顺序，内部取最近 20 条上报），null/空表示首次请求
     * @param mode         听歌模式；null 或默认「推荐」不带 feed_preference，场景模式按官方
     *                     官方 feedmode 的映射只传 {@code {scene_mode_id}}（实测确实改变推荐内容）
     */
    public static List<Music> discoverFeedTracks(int count, List<Music> playedTracks, FeedMode mode) {
        int target = Math.max(1, Math.min(30, count));
        boolean firstRequest = playedTracks == null || playedTracks.isEmpty();
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("count", target);
            payload.addProperty("is_first_request", firstRequest);
            if (!firstRequest) {
                payload.addProperty("is_did_first_request", true);
                JsonArray played = new JsonArray();
                int reported = 0;
                for (Music song : playedTracks) {
                    if (reported >= 20) break;
                    if (song == null || nonBlank(song.getSourceId()).isEmpty()) continue;
                    JsonObject item = new JsonObject();
                    item.addProperty("type", "track");
                    item.addProperty("media_id", song.getSourceId());
                    item.addProperty("played_mills", 30_000L);
                    item.addProperty("duration", Math.max(1_000L, song.getDuration()));
                    played.add(item);
                    reported++;
                }
                payload.add("played_media", played);
            }
            if (mode != null && !mode.isDefaultMode()) {
                // 官方 feedmode 的映射:场景模式只传 scene_mode_id;偏好模式(非默认的
                // 话)传 preference_mode。实测 scene_mode_id=6/8 给出的推荐明显不同。
                JsonObject preference = new JsonObject();
                if (mode.sceneModeId > 0) {
                    preference.addProperty("scene_mode_id", mode.sceneModeId);
                } else {
                    preference.addProperty("preference_mode", mode.key);
                }
                payload.add("feed_preference", preference);
                JsonObject counts = new JsonObject();
                counts.addProperty("mix_session_count", 1);
                payload.add("feed_counts", counts);
            }
            payload.addProperty("full_media", true);
            JsonObject response = requirePcStatus(QishuiBackend.signedPc(PC_FEED_SONG_TAB_PATH,
                    Collections.emptyMap(), payload.toString()), "发现流");
            LinkedHashMap<String, Music> unique = new LinkedHashMap<>();
            collectFeedTracks(array(response, "items"), unique, target);
            return new ArrayList<>(unique.values());
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Discover feed failed: " + describe(error));
            if (looksLikeAuthRejection(error)) handleAuthRejection();
            return Collections.emptyList();
        }
    }

    /** 两参形态：不带模式（默认「推荐」），每日推荐卡等整页场景用。 */
    public static List<Music> discoverFeedTracks(int count, List<Music> playedTracks) {
        return discoverFeedTracks(count, playedTracks, null);
    }

    // ── 听歌模式(场景模式)——清单由 /luna/pc/feed/mode 下发,断网时回落实测快照 ──

    /** 一个听歌模式:「推荐」是偏好模式({@code feed_preference_mode})，其余是场景模式。 */
    public static final class FeedMode {
        /** 稳定键:场景模式用 sub_queue_type(scene_mode_chill),偏好模式用 default。 */
        public final String key;
        /** UI 文案,与官方客户端一致(如「专注模式」「深夜 EMO」)。 */
        public final String text;
        /** 场景模式的上游 id(传给 feed_preference);偏好模式为 -1。 */
        public final int sceneModeId;

        public FeedMode(String key, String text, int sceneModeId) {
            this.key = key;
            this.text = text;
            this.sceneModeId = sceneModeId;
        }

        public boolean isDefaultMode() {
            return sceneModeId <= 0 && "default".equals(key);
        }
    }

    /** 模式清单缓存;getFeedModes() 保证非空(拉不到就用实测快照)。 */
    private static volatile List<FeedMode> feedModesCache;
    /** 「正在后台拉真实清单」标记:同一个时刻只有一趟,拉回来只覆盖快照那一份缓存。 */
    private static final java.util.concurrent.atomic.AtomicBoolean FEED_MODES_FETCHING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 顶部偏好条第一枚永远叫「推荐」;上游下不来时它是唯一的兜底。 */
    public static final FeedMode DEFAULT_FEED_MODE = new FeedMode("default", "推荐", -1);

    /**
     * 模组静态注册的偏好模式:familiar/explore(熟悉/新鲜)。上游偏好条(/luna/pc/feed/mode
     * 的 feed_mode_bar)只下发「推荐」一枚,但 song-tab 的 feed_preference 实测接受这两个值
     * 且真实改变推荐池——它们是汽水对应「熟悉/探索」的参数。面板的「常用」行固定显示
     * 推荐/熟悉模式/新鲜模式三枚(用户指定),取显示对象走这里。
     */
    public static FeedMode staticFeedMode(String key) {
        for (FeedMode mode : STATIC_FEED_MODES) {
            if (mode.key.equals(key)) return mode;
        }
        return DEFAULT_FEED_MODE;
    }

    public static final FeedMode[] STATIC_FEED_MODES = {
            DEFAULT_FEED_MODE,
            new FeedMode("familiar", "熟悉模式", -1),
            new FeedMode("explore", "新鲜模式", -1),
    };

    /**
     * 真实听歌模式清单:GET /luna/pc/feed/mode。偏好条在前面(「推荐」),场景音乐块按
     * 上游顺序排在后面;同一 sub_queue_type 去重。拉不到时用 2026-10-11 实测快照
     * (45 个场景模式,真实 id/键/文案),所以<b>永远</b>返回非空列表。
     *
     * <p><b>这个方法绝不在调用线程上发请求</b>:调用它的地方是面板装配与渲染回调(渲染线程),
     * 一次签名请求的往返会把帧卡住。清单先给快照,真实清单在后台拉回来替换缓存,下一次装配
     * 用上——顺序与内容都是真实的,只是晚一趟。</p>
     */
    public static List<FeedMode> getFeedModes() {
        List<FeedMode> cached = feedModesCache;
        if (cached != null) return cached;
        refreshFeedModesAsync();
        return FEED_MODE_SNAPSHOT;
    }

    /** 后台拉一次真实清单;已经有缓存就不重复。失败不写缓存,但 30 秒内不重试(见下)。 */
    public static void refreshFeedModesAsync() {
        if (feedModesCache != null) return;
        if (System.nanoTime() < FEED_MODES_RETRY_AFTER) return;
        if (!FEED_MODES_FETCHING.compareAndSet(false, true)) return;
        MultiThreadingUtil.runAsync(() -> {
            try {
                JsonObject response = requirePcStatus(QishuiBackend.signedPc("/luna/pc/feed/mode",
                        Collections.emptyMap(), null), "听歌模式");
                LinkedHashMap<String, FeedMode> unique = new LinkedHashMap<>();
                unique.put(DEFAULT_FEED_MODE.key, DEFAULT_FEED_MODE);
                JsonObject bar = object(response, "feed_mode_bar");
                for (JsonElement element : array(bar, "feed_mode")) {
                    collectFeedMode(element, unique);
                }
                for (JsonElement blockElement : array(response, "feed_mode_block")) {
                    if (!blockElement.isJsonObject()) continue;
                    for (JsonElement element : array(blockElement.getAsJsonObject(), "feed_mode")) {
                        collectFeedMode(element, unique);
                    }
                }
                // 「推荐」必须存在且排第一:偏好条拉丢了也轮不到场景模式冒名。
                List<FeedMode> modes = new ArrayList<>(unique.values());
                if (!modes.isEmpty() && !modes.get(0).isDefaultMode()) {
                    modes.remove(DEFAULT_FEED_MODE);
                    modes.add(0, DEFAULT_FEED_MODE);
                }
                feedModesCache = Collections.unmodifiableList(modes);
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Feed modes failed, keeping snapshot: " + describe(error));
                // 失败<b>不</b>把快照写进缓存:后端稍后起来了还得能换成真实清单。代价是这一段时间
                // 里调用方拿到的都是快照,所以给一个 30 秒的冷却,别让每次装配都去撞一次网络。
                FEED_MODES_RETRY_AFTER = System.nanoTime() + 30_000_000_000L;
            } finally {
                FEED_MODES_FETCHING.set(false);
            }
        });
    }

    /** 上一次清单拉取失败后的冷却截止时刻(nanoTime)。 */
    private static volatile long FEED_MODES_RETRY_AFTER;

    /** 连接预热是否已经排过：只做一次，够覆盖整个会话。 */
    private static final java.util.concurrent.atomic.AtomicBoolean WARMED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 预热发现页那条链路的连接。
     *
     * <p>实测（2026-10）：<b>冷</b>启动第一次采样要 1.9 秒才回来，而同一进程里后续几次只要
     * 0.33 秒 —— 差的 1.6 秒全在建连（DNS + TCP + TLS，{@code beta-luna.douyin.com}）。
     * 主页首个回调因此从「一秒出头」被拖成「两秒」。这里是把这个建连提前：玩家在本源面板
     * 点下汽水的那一刻（或面板装配时）就发一次最小请求，等真正去拉推荐歌单时连接已经在池子里。</p>
     *
     * <p>只发一次、失败静默 —— 它买的只是建连那点时间，拿不到也还有正路。</p>
     */
    public static void warmDiscoverConnectionAsync() {
        if (!WARMED.compareAndSet(false, true)) return;
        MultiThreadingUtil.runAsync(() -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("count", 1);
                postJson(LUNA_DISCOVER_MIX_ENDPOINT, payload.toString(),
                        ANDROID_USER_AGENT, "", Collections.emptyMap());
            } catch (Throwable error) {
                System.out.println("[Music/Qishui] 发现页连接预热未成功（不影响功能）: " + describe(error));
            }
        });
    }

    private static void collectFeedMode(JsonElement element, Map<String, FeedMode> output) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject mode = element.getAsJsonObject();
        String text = nonBlank(string(mode, "text"));
        if (text.isEmpty()) return;
        JsonObject scene = object(object(mode, "entity"), "feed_scene_mode");
        int sceneModeId = (int) longValue(scene, "scene_mode_id");
        if (sceneModeId > 0) {
            String subQueueType = nonBlank(string(scene, "sub_queue_type"));
            String key = subQueueType.isEmpty() ? "scene_mode_" + sceneModeId : subQueueType;
            output.putIfAbsent(key, new FeedMode(key, text, sceneModeId));
            return;
        }
        // 偏好条:目前只有「推荐」一枚;上游哪天下发别的偏好模式也照单全收。
        String preferenceKey = nonBlank(string(object(object(mode, "entity"), "feed_preference_mode"), "mode"));
        output.putIfAbsent(preferenceKey.isEmpty() ? "default" : preferenceKey,
                new FeedMode(preferenceKey.isEmpty() ? "default" : preferenceKey, text, -1));
    }

    /**
     * 实测快照(2026-10-11,来自 /luna/pc/feed/mode 真实响应):断网或端点异常时的兜底。
     * 上游随时可能增删——所以只要在线就优先用下发的清单,这里只是最后防线。
     */
    private static final List<FeedMode> FEED_MODE_SNAPSHOT = Collections.unmodifiableList(Arrays.asList(
            DEFAULT_FEED_MODE,
            new FeedMode("scene_mode_chill", "Chill 放松", 3),
            new FeedMode("scene_mode_happy", "快乐时光", 5),
            new FeedMode("scene_mode_emo", "深夜 EMO", 4),
            new FeedMode("scene_mode_dj", "DJ模式", 7),
            new FeedMode("scene_mode_bedtime", "助眠模式", 8),
            new FeedMode("scene_mode_sport", "动感健身", 2),
            new FeedMode("scene_mode_douyin_roam", "抖音漫游", 9),
            new FeedMode("scene_mode_bath", "洗澡", 41),
            new FeedMode("scene_mode_electronic", "电音", 11),
            new FeedMode("scene_mode_lucky", "好运", 45),
            new FeedMode("scene_mode_library", "图书馆", 48),
            new FeedMode("scene_mode_cantonese", "粤语", 10),
            new FeedMode("scene_mode_commute", "通勤必听", 1),
            new FeedMode("scene_mode_breakup", "失恋必听", 13),
            new FeedMode("scene_mode_lying_flat", "躺平", 44),
            new FeedMode("scene_mode_english", "欧美", 15),
            new FeedMode("scene_mode_clean_up", "打扫", 47),
            new FeedMode("scene_mode_chinese_style", "国风", 12),
            new FeedMode("scene_mode_game", "打游戏", 37),
            new FeedMode("scene_mode_car_mode", "驾车", 33),
            new FeedMode("scene_mode_focus", "专注模式", 6),
            new FeedMode("scene_mode_rap", "说唱", 24),
            new FeedMode("scene_mode_slow_motion", "沉浸0.8x", 14),
            new FeedMode("scene_mode_night_time", "夜晚", 40),
            new FeedMode("scene_mode_heal", "治愈", 21),
            new FeedMode("scene_mode_non_vocal", "轻音乐", 25),
            new FeedMode("scene_mode_drunk", "小酒馆", 18),
            new FeedMode("scene_mode_ktv", "KTV必点", 31),
            new FeedMode("scene_mode_get_up", "起床", 38),
            new FeedMode("scene_mode_love_song", "浪漫情歌", 19),
            new FeedMode("scene_mode_rock", "摇滚", 28),
            new FeedMode("scene_mode_rnb", "R&B", 23),
            new FeedMode("scene_mode_calm", "佛系时间", 20),
            new FeedMode("scene_mode_nostalgic", "怀旧老歌", 16),
            new FeedMode("scene_mode_folk", "民谣", 29),
            new FeedMode("scene_mode_sweet_girl", "甜美女声", 17),
            new FeedMode("scene_mode_kpop", "K-pop", 22),
            new FeedMode("scene_mode_jpop", "日语", 30),
            new FeedMode("scene_mode_travel", "旅行", 36),
            new FeedMode("scene_mode_fish", "摸鱼", 34),
            new FeedMode("scene_mode_child", "儿歌", 26),
            new FeedMode("scene_mode_rain", "雨天", 35),
            new FeedMode("scene_mode_beach", "海边", 39),
            new FeedMode("scene_mode_country", "乡村", 27),
            new FeedMode("scene_mode_classic", "古典", 32)));

    /** 每日推荐虚拟歌单的曲目源：首次请求形态（不带 played_media），内容每天变。 */
    public static List<Music> feedDailyTracks(int limit) {
        return discoverFeedTracks(limit, Collections.emptyList());
    }

    // ── 搜索联想 + 最近播放(2026-10-11 逐条实测:签名代理与免签移动端两条路都通) ──

    /**
     * 搜索联想：GET /luna/pc/sug（后端签名优先），免签移动端 /luna/sug 兜底——两条路
     * 实测给同一份 {@code sugs}（suggestion 文本 + content_type 标签），兜底不带 Cookie 也能出。
     *
     * @return 联想项（text 点下去真正拿去搜的词，kind 是右侧类型标签）
     */
    public static List<NeteaseSearchSuggestService.Suggestion> searchSuggest(String keyword) {
        String q = nonBlank(keyword);
        if (q.isEmpty()) return Collections.emptyList();
        try {
            JsonObject response;
            if (QishuiBackend.isAvailable()) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("q", q);
                params.put("sug_scene", "");
                params.put("sug_search_id", "");
                response = requirePcStatus(QishuiBackend.signedPc("/luna/pc/sug", params, null),
                        "搜索联想(后端签名)");
            } else {
                StringBuilder url = new StringBuilder("https://api.qishui.com/luna/sug?");
                appendMobileCommonParams(url);
                append(url, "q", q);
                append(url, "sug_scene", "");
                append(url, "sug_search_id", "");
                response = getJson(url.toString(), ANDROID_USER_AGENT, "", Collections.emptyMap());
            }
            List<NeteaseSearchSuggestService.Suggestion> result = new ArrayList<>();
            JsonArray sugs = array(response, "sugs");
            if (sugs == null) return result;
            Set<String> seen = new LinkedHashSet<>();
            for (JsonElement element : sugs) {
                if (result.size() >= NeteaseSearchSuggestService.MAX_SUGGESTIONS) break;
                if (!element.isJsonObject()) continue;
                JsonObject sug = element.getAsJsonObject();
                String text = nonBlank(string(sug, "suggestion"));
                if (text.isEmpty() || !seen.add(text)) continue;
                result.add(new NeteaseSearchSuggestService.Suggestion(text,
                        suggestKindLabel(string(sug, "content_type")), ""));
            }
            return result;
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Search suggest failed for " + q + ": " + describe(error));
            return Collections.emptyList();
        }
    }

    private static String suggestKindLabel(String contentType) {
        switch (nonBlank(contentType)) {
            case "artist": return "歌手";
            case "playlist": return "歌单";
            case "track": return "歌曲";
            case "album": return "专辑";
            case "mv": return "MV";
            default: return "";
        }
    }

    /**
     * 最近播放：GET /luna/me/recently-played-media 按游标翻页。条目与发现流同形
     * （{@code entity.track}，{@link #parseFeedTrack} 的回退链正好覆盖），去重取最新一次。
     *
     * <p>后端签名代理优先；不在时走免签移动端 + Cookie（实测同给 media/next_cursor/has_more）。
     * 两边都不在或未登录返回空列表，调用方按「暂无播放记录」处理。</p>
     */
    public static List<Music> recentlyPlayedTracks(int limit) {
        if (!QishuiSessionStore.isLoggedIn()) return Collections.emptyList();
        int target = Math.max(1, Math.min(500, limit));
        LinkedHashMap<String, Music> unique = new LinkedHashMap<>();
        String cursor = "0";
        Set<String> cursors = new HashSet<>();
        for (int page = 0; page < MAX_PAGES && unique.size() < target; page++) {
            try {
                JsonObject response;
                if (QishuiBackend.isAvailable()) {
                    Map<String, String> params = new LinkedHashMap<>();
                    params.put("cursor", cursor);
                    params.put("count", String.valueOf(Math.min(PAGE_SIZE, target - unique.size())));
                    params.put("media_type", "track");
                    params.put("scene", "");
                    response = requirePcStatus(QishuiBackend.signedPc(
                            "/luna/pc/me/recently-played-media", params, null), "最近播放(后端签名)");
                } else {
                    StringBuilder url = new StringBuilder("https://api.qishui.com/luna/me/recently-played-media?");
                    appendMobileCommonParams(url);
                    append(url, "cursor", cursor);
                    append(url, "count", String.valueOf(Math.min(PAGE_SIZE, target - unique.size())));
                    append(url, "media_type", "track");
                    append(url, "scene", "");
                    response = getJson(url.toString(), ANDROID_USER_AGENT,
                            QishuiSessionStore.getCookieHeader(), Collections.emptyMap());
                }
                collectFeedTracks(array(response, "media"), unique, target);
                if (!truthy(response, "has_more")) break;
                String next = firstNonBlank(string(response, "next_cursor"), "");
                if (next.isEmpty() || next.equals(cursor) || !cursors.add(next)) break;
                cursor = next;
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Recently played failed: " + describe(error));
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 「垃圾桶」：POST /luna/track/dislike（免签名移动端面，与搜索同一套公共参数）。
     *
     * <p>实测两个硬约束：{@code artist_ids} <b>必填</b>（缺了 1000004），艺人 id 只在
     * feed 的响应里有——所以 {@link #collectFeedTracks} 解析时顺手把 trackId → 第一位
     * 艺人 id 记进侧表，跳过时从这里查。查不到（比如这首不是发现流推的）就返回 false，
     * 跳过本身不受影响：下一波请求照样会把这首以短时长计入 played_media。</p>
     */
    public static boolean dislikeTrack(String trackId) {
        String id = nonBlank(trackId);
        if (id.isEmpty() || !QishuiSessionStore.isLoggedIn()) return false;
        long artistId = feedArtistIdOf(id);
        if (artistId <= 0L) {
            System.err.println("[Music/Qishui] Dislike skipped: no artist id seen for " + id);
            return false;
        }
        try {
            StringBuilder url = new StringBuilder("https://api.qishui.com/luna/track/dislike?");
            appendMobileCommonParams(url);
            JsonObject payload = new JsonObject();
            payload.addProperty("track_id", id);
            JsonArray artistIds = new JsonArray();
            artistIds.add(artistId);
            payload.add("artist_ids", artistIds);
            JsonObject response = postJson(url.toString(), payload.toString(),
                    ANDROID_USER_AGENT, QishuiSessionStore.getCookieHeader(), Collections.emptyMap());
            int status = response.has("status_code") ? response.get("status_code").getAsInt() : -1;
            if (status != 0) {
                throw new IllegalStateException("dislike 业务码 " + status);
            }
            return true;
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Dislike failed for " + id + ": " + describe(error));
            return false;
        }
    }

    /** feed 里见过的曲目 → 第一位艺人的上游 id（dislike 必填）；超出上限淘汰最旧的。 */
    private static final Map<String, Long> FEED_ARTIST_IDS = new LinkedHashMap<>();
    private static final int FEED_ARTIST_ID_LIMIT = 512;

    private static void rememberFeedArtistId(String trackId, long artistId) {
        if (nonBlank(trackId).isEmpty() || artistId <= 0L) return;
        synchronized (FEED_ARTIST_IDS) {
            FEED_ARTIST_IDS.put(trackId, artistId);
            while (FEED_ARTIST_IDS.size() > FEED_ARTIST_ID_LIMIT) {
                String oldest = FEED_ARTIST_IDS.keySet().iterator().next();
                FEED_ARTIST_IDS.remove(oldest);
            }
        }
    }

    private static long feedArtistIdOf(String trackId) {
        synchronized (FEED_ARTIST_IDS) {
            Long value = FEED_ARTIST_IDS.get(trackId);
            return value == null ? 0L : value;
        }
    }

    /** 播放上报:POST /luna/pc/me/recently-played-media(最近播放)。 */
    public static boolean reportRecentlyPlayed(String trackId) {
        String id = nonBlank(trackId);
        if (id.isEmpty() || !QishuiSessionStore.isLoggedIn()) return false;
        try {
            JsonObject payload = new JsonObject();
            JsonArray media = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("type", "track");
            item.addProperty("id", id);
            media.add(item);
            payload.add("media", media);
            requirePcStatus(QishuiBackend.signedPc("/luna/pc/me/recently-played-media",
                    Collections.emptyMap(), payload.toString()), "播放上报");
            return true;
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Recently-played report failed for " + id + ": "
                    + describe(error));
            return false;
        }
    }

    /** 登录后异步拉一次「我喜欢的音乐」歌单,把红心集合装进本地缓存。 */
    public static void ensureLikedTrackIdsLoaded() {
        if (likedTrackIdsLoadAttempted || !QishuiSessionStore.isLoggedIn()) return;
        likedTrackIdsLoadAttempted = true;
        MultiThreadingUtil.runAsync(() -> {
            try {
                for (PlayList playlist : getUserPlaylistsSnapshot()) {
                    String name = playlist.getName();
                    String pid = playlist.getPlatformPlaylistId();
                    if (name == null || pid == null || !name.contains("我喜欢")
                            || !pid.startsWith(ACCOUNT_PLAYLIST_PREFIX)) {
                        continue;
                    }
                    List<Music> tracks = accountPlaylistTracks(
                            pid.substring(ACCOUNT_PLAYLIST_PREFIX.length()), 500);
                    Set<String> ids = new HashSet<>();
                    for (Music music : tracks) ids.add(music.getSourceId());
                    likedTrackIds = ids;
                    System.out.println("[Music/Qishui] 红心集合已加载: " + ids.size() + " 首");
                    return;
                }
                System.out.println("[Music/Qishui] 账号歌单里未找到『我喜欢的音乐』,红心状态不可用");
            } catch (Throwable error) {
                likedTrackIdsLoadAttempted = false; // 这次失败,下次再试
                System.err.println("[Music/Qishui] 红心集合加载失败: " + describe(error));
            }
        });
    }

    /** LikeButton 的只读本地判定;集合没加载完时按未红心处理。 */
    public static boolean isTrackLikedLocal(String trackId) {
        return likedTrackIds.contains(nonBlank(trackId));
    }

    public static void noteTrackLikedLocal(String trackId, boolean liked) {
        String id = nonBlank(trackId);
        if (id.isEmpty()) return;
        Set<String> updated = new HashSet<>(likedTrackIds);
        if (liked) updated.add(id);
        else updated.remove(id);
        likedTrackIds = updated;
    }
    /**
     * 汽水发现页推荐歌单。接口为公开 App 流，返回卡片所需的最小元数据。
     *
     * <p><b>这一条接口不分页。</b>它每次都从推荐池里随机取一小批（实测 4~12 张，与
     * {@code count} 无关：传 50、100、200 回来的是同一个量级），响应里 {@code has_more}
     * 恒为真却<b>没有</b> {@code next_cursor}（试过 {@code cursor}/{@code offset}/{@code page}
     * 也都被忽略）。所以「跟着游标翻页」那套在这里必然只取到第一批 —— 原来就是这样，主页
     * 永远只有 8~10 张卡。</p>
     *
     * <p>要拿更多只有一条路：<b>反复采样同一接口，按歌单 id 去重累积</b>。池子上限实测约
     * 150~160 张，边际递减明显（第 1 轮 +65、第 5 轮只剩 +8）。并发不受限流（6~8 个并发
     * 全部 200），所以按批并发去压时间：8 并发 × 5 轮约 3 秒能收到 140 张以上。</p>
     *
     * <p>截断策略按「够用就停」：凑够 target 就收手，而且连续两轮零新增就认定池子已经
     * 见底，提前结束 —— 免得为了最后几张把用户晾在转圈上。</p>
     */
    public static List<PlayList> recommendPlaylists(int limit) {
        return recommendPlaylists(limit, null);
    }

    /**
     * 与 {@link #recommendPlaylists(int)} 相同，但每一轮采完就把当前的累积结果交给
     * {@code progress}（可以为 null）。
     *
     * <p>给主页做渐进式首屏用：第一轮（8 个并发）就能拿到五六十张，先铺出去，
     * 后面几轮在后台补到一百多张再追加。回调在<b>采样线程</b>上跑，实现方自己负责切到主线程；
     * 传进去的列表是当轮的不可变快照，可以安全地跨线程持有。</p>
     */
    public static List<PlayList> recommendPlaylists(int limit, Consumer<List<PlayList>> progress) {
        int target = Math.max(1, Math.min(RECOMMEND_POOL_LIMIT, limit));
        ConcurrentHashMap<String, PlayList> unique = new ConcurrentHashMap<>();
        int barrenRounds = 0;
        long budgetDeadline = System.nanoTime() + RECOMMEND_TOTAL_BUDGET_MS * 1_000_000L;
        for (int round = 0; round < RECOMMEND_ROUNDS && unique.size() < target; round++) {
            // 整次预算从第二轮起生效：第一轮永远跑满。一张都没拿到就收手等于把所有慢网络
            // 都判成失败（见 RECOMMEND_ROUND_TIMEOUT_MS 的说明）。
            if (round > 0 && System.nanoTime() > budgetDeadline) break;
            int before = unique.size();
            sampleDiscoverMixRound(unique, target);
            if (unique.isEmpty()) {
                // 一张都没拿到：网络慢或上游抖动。继续下一轮再试，<b>不要</b>在这里收手 ——
                // 单轮空手是常态（实测请求 0.3~0.5 秒，但慢网络下 8 路全超时也出现过），
                // 交给轮数与整次预算收尾。
                continue;
            }
            boolean grew = unique.size() > before;
            if (!grew) {
                if (++barrenRounds >= 2) break;
            } else {
                barrenRounds = 0;
                if (progress != null) {
                    // 有进展才回调：空手的一轮不该让界面重排一次。
                    progress.accept(Collections.unmodifiableList(new ArrayList<>(unique.values())));
                }
            }
        }
        if (unique.isEmpty()) {
            System.err.println("[Music/Qishui] Recommend playlists failed: "
                    + RECOMMEND_ROUNDS + " 轮采样都没拿到歌单（网络不可达或上游风控）");
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 一轮并发采样：{@value #RECOMMEND_PARALLELISM} 个请求同时发，回来的卡片按 id 并进
     * {@code unique}。单个请求失败只丢它自己那一批，不牵连同一轮的其它请求。
     *
     * @return 这一轮新解析出的歌单张数（含重复的，用来判「这一轮有没有东西回来」）
     */
    private static int sampleDiscoverMixRound(ConcurrentHashMap<String, PlayList> unique, int target) {
        List<CompletableFuture<Integer>> futures = new ArrayList<>(RECOMMEND_PARALLELISM);
        for (int index = 0; index < RECOMMEND_PARALLELISM; index++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    JsonObject payload = new JsonObject();
                    // 每次带一点随机量:上游对同样的载荷常常回同一批,随机 count 能让两次
                    // 采样错开一点,重叠率更低。
                    payload.addProperty("count", 20 + ThreadLocalRandom.current().nextInt(31));
                    payload.addProperty("first_request", true);
                    JsonObject response = postJson(LUNA_DISCOVER_MIX_ENDPOINT, payload.toString(),
                            ANDROID_USER_AGENT, "", Collections.emptyMap());
                    Map<String, PlayList> parsed = new LinkedHashMap<>();
                    collectPlaylistResources(array(response, "inner_block"), parsed, target);
                    if (parsed.isEmpty()) {
                        collectPlaylistResources(array(response, "blocks"), parsed, target);
                    }
                    for (Map.Entry<String, PlayList> entry : parsed.entrySet()) {
                        unique.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                    return parsed.size();
                } catch (Throwable error) {
                    System.err.println("[Music/Qishui] Recommend playlist sample failed: " + describe(error));
                    return 0;
                }
            }, RECOMMEND_EXECUTOR));
        }
        int collected = 0;
        // 整轮一份预算，而不是每一路各给一份：八路并发同时起飞，慢的那几路不该把这一轮的
        // 等待累加成「8 × 超时」。给到整轮上限就收手，剩下的路自己跑完——结果仍会并进
        // unique，只是这一轮不等它们了。
        long deadline = System.nanoTime() + RECOMMEND_ROUND_TIMEOUT_MS * 1_000_000L;
        for (CompletableFuture<Integer> future : futures) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0L) break;
            try {
                collected += future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                // 超时/中断只丢这一路,其余几路照常统计。
            }
        }
        return collected;
    }

    /**
     * 汽水发现页电台。当前上游有时把电台放在 blocks，有时放在 discover/mix 的 inner_block。
     *
     * <p>电台和推荐歌单是同一套采样语义：接口一次给一批、无游标可跟。实测连抽 12 次，
     * 第 1、2 次各出 12 个不重复的之后就一直空手 —— 池子只有 24 个左右。所以这里同样并发
     * 采两轮就够了，不必像歌单那样铺 6 轮。</p>
     */
    public static List<PlayList> recommendRadios(int limit) {
        int target = Math.max(1, Math.min(80, limit));
        ConcurrentHashMap<String, PlayList> unique = new ConcurrentHashMap<>();
        for (int round = 0; round < RADIO_ROUNDS && unique.size() < target; round++) {
            int before = unique.size();
            sampleRadioRound(unique, target);
            if (unique.size() == before) break;
        }
        return new ArrayList<>(unique.values());
    }

    /** 电台采样的一轮：与歌单同一形状，只是端点与收集器不同。 */
    private static void sampleRadioRound(ConcurrentHashMap<String, PlayList> unique, int target) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(RECOMMEND_PARALLELISM);
        for (int index = 0; index < RECOMMEND_PARALLELISM; index++) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("first_request", true);
                    JsonObject response = postJson(LUNA_DISCOVER_ENDPOINT, payload.toString(),
                            ANDROID_USER_AGENT, "", Collections.emptyMap());
                    Map<String, PlayList> parsed = new LinkedHashMap<>();
                    collectRadioResources(array(response, "blocks"), parsed, target);
                    if (parsed.isEmpty()) {
                        collectRadioResources(array(response, "inner_block"), parsed, target);
                    }
                    for (Map.Entry<String, PlayList> entry : parsed.entrySet()) {
                        unique.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                } catch (Throwable error) {
                    System.err.println("[Music/Qishui] Recommend radio sample failed: " + describe(error));
                }
            }, RECOMMEND_EXECUTOR));
        }
        // 与歌单那一轮同一预算口径：整轮一份上限，不按每路累加。
        long deadline = System.nanoTime() + RECOMMEND_ROUND_TIMEOUT_MS * 1_000_000L;
        for (CompletableFuture<Void> future : futures) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0L) break;
            try {
                future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
            }
        }
    }

    /** PC 歌单搜索，失败时回退到混合搜索中的歌单分组。 */
    public static List<PlayList> searchPlaylists(String query, int limit) {
        String keyword = nonBlank(query);
        if (keyword.isEmpty()) return Collections.emptyList();
        int target = Math.max(1, Math.min(100, limit));
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<>();
        try {
            JsonObject response;
            if (QishuiBackend.isAvailable()) {
                response = requirePcStatus(QishuiBackend.signedPc(
                        pcPathOf(PC_SEARCH_PLAYLIST_ENDPOINT), pcSearchParams(keyword, 0), null),
                        "歌单搜索(后端签名)");
            } else {
                response = requirePcStatus(getJson(buildPcSearchUrl(keyword, 0),
                        PC_USER_AGENT, QishuiSessionStore.getCookieHeader(), pcHeaders()), "歌单搜索");
            }
            collectPlaylistsFromSearch(response, unique, target);
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] Playlist search failed: " + describe(error));
        }
        if (unique.isEmpty()) {
            try {
                JsonObject response;
                if (QishuiBackend.isAvailable()) {
                    response = requirePcStatus(QishuiBackend.signedPc(
                            pcPathOf(PC_SEARCH_MIXED_ENDPOINT), pcSearchParams(keyword, 0), null),
                            "混合搜索(后端签名)");
                } else {
                    response = requirePcStatus(getJson(buildPcMixedSearchUrl(keyword, 0),
                            PC_USER_AGENT, QishuiSessionStore.getCookieHeader(), pcHeaders()), "混合搜索");
                }
                collectPlaylistsFromSearch(response, unique, target);
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Mixed playlist search failed: " + describe(error));
            }
        }
        return new ArrayList<>(unique.values());
    }

    /** PC 搜索的业务参数(设备参数由后端统一补齐)。 */
    private static Map<String, String> pcSearchParams(String keyword, int cursor) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", keyword);
        params.put("cursor", String.valueOf(Math.max(0, cursor)));
        params.put("search_id", "");
        params.put("search_method", "input");
        params.put("debug_params", "");
        params.put("from_search_id", "");
        params.put("search_scene", "");
        return params;
    }

    /** 歌单详情。只保留 feed 中的曲目实体，视频/播客条目会被忽略。 */
    public static List<Music> playlistTracks(String playlistId, int limit) {
        String id = nonBlank(playlistId);
        if (id.isEmpty()) return Collections.emptyList();
        if (id.startsWith(ACCOUNT_PLAYLIST_PREFIX)) {
            return accountPlaylistTracks(id.substring(ACCOUNT_PLAYLIST_PREFIX.length()), limit);
        }
        if (id.startsWith(DAILY_PLAYLIST_PREFIX)) {
            return feedDailyTracks(limit);
        }
        return anonymousPlaylistTracks(id, limit);
    }

    /** 每日推荐虚拟歌单的卡片（首页墙第一张）。日期后缀让用户一眼知道内容会换。 */
    public static PlayList dailyFeedCard() {
        PlayList card = new PlayList(stableLong("qishui:daily:" + java.time.LocalDate.now()),
                "每日推荐 · " + java.time.LocalDate.now().getMonthValue() + "月"
                        + java.time.LocalDate.now().getDayOfMonth() + "日",
                "", 30, 0L, null, "汽水发现页每天为你挑的新歌", 0L);
        card.setPlatform(MusicPlatform.QISHUI);
        card.setPlatformPlaylistId(DAILY_PLAYLIST_PREFIX + "today");
        return card;
    }

    /**
     * 账号歌单取曲目：带 Cookie 走 {@code GET /luna/pc/playlist/detail}，按游标翻到底。
     *
     * <p>终止条件只有 {@code has_more} 不为真、或 {@code next_cursor} 缺失 / 不前进 / 重复，
     * <b>不能以「这一页是空的」收尾</b>：实测上游会在中间页回 0 条却仍然 {@code has_more=true}
     * （不可见的条目被跳过了），空页收尾会把后半段整段丢掉。请求的条数也不看
     * {@code count_tracks}：那个字段实测会偏小（标 186 首、实际 238 首），游标才是准的。</p>
     *
     * <p>凭据失效或这条接口出错时退回 {@link #anonymousPlaylistTracks}：宁可少几首，
     * 也别把已经点开的面板变成一片空白。</p>
     */
    private static List<Music> accountPlaylistTracks(String playlistId, int limit) {
        // 调用方给的 limit 来自 count_tracks，而这个字段实测会偏小（标 186 首、游标翻完 238 首）：
        // 拿它当上限，「歌单里歌曲数量不全」就还在。这里只把 MAX_PAGES 当闸门，收尾交给 has_more。
        int target = MAX_PAGES * PAGE_SIZE;
        LinkedHashMap<String, Music> unique = new LinkedHashMap<>();
        String cursor = "";
        Set<String> cursors = new HashSet<>();
        for (int page = 0; page < MAX_PAGES && unique.size() < target; page++) {
            try {
                JsonObject response;
                if (QishuiBackend.isAvailable()) {
                    Map<String, String> params = new LinkedHashMap<>();
                    params.put("playlist_id", playlistId);
                    params.put("count", String.valueOf(PAGE_SIZE));
                    params.put("cursor", cursor);
                    response = requirePcStatus(QishuiBackend.signedPc(
                            pcPathOf(PC_PLAYLIST_DETAIL_ENDPOINT), params, null), "歌单详情(后端签名)");
                } else {
                    response = requirePcStatus(getJson(
                            buildPcPlaylistDetailUrl(playlistId, cursor), PC_USER_AGENT,
                            QishuiSessionStore.getCookieHeader(), pcHeaders()), "歌单详情");
                }
                collectFeedTracks(array(response, "media_resources"), unique, target);
                if (!truthy(response, "has_more")) break;
                String next = string(response, "next_cursor");
                if (next.isEmpty() || next.equals(cursor) || !cursors.add(next)) break;
                cursor = next;
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Account playlist detail failed for "
                        + playlistId + ": " + describe(error));
                if (looksLikeAuthRejection(error)) handleAuthRejection();
                break;
            }
        }
        if (!unique.isEmpty()) return new ArrayList<>(unique.values());
        return anonymousPlaylistTracks(playlistId, limit);
    }

    /**
     * 匿名取曲目：{@code POST beta-luna.douyin.com/luna/playlist/detail}，<b>一处凭据都不带</b>。
     *
     * <p>导入的歌单走这一条。带上 Cookie 反而会被这条接口拒掉（实测返回业务码 {@code 1000006}），
     * 而且导入本身要求与登录、账号解耦 —— 见 {@link #importPlaylist(String)}。</p>
     */
    private static List<Music> anonymousPlaylistTracks(String playlistId, int limit) {
        String id = nonBlank(playlistId);
        if (id.isEmpty()) return Collections.emptyList();
        int target = Math.max(1, limit);
        LinkedHashMap<String, Music> unique = new LinkedHashMap<>();
        String cursor = "";
        String sessionId = "";
        for (int page = 0; page < MAX_PAGES && unique.size() < target; page++) {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("playlist_id", id);
                payload.addProperty("playlist_type", 0);
                payload.addProperty("count", Math.min(PAGE_SIZE, target - unique.size()));
                if (!cursor.isEmpty()) payload.addProperty("cursor", cursor);
                if (!sessionId.isEmpty()) payload.addProperty("session_id", sessionId);
                JsonObject response = postJson(LUNA_PLAYLIST_DETAIL_ENDPOINT, payload.toString(),
                        ANDROID_USER_AGENT, "", Collections.emptyMap());
                collectFeedTracks(array(response, "media_resources"), unique, target);
                String nextCursor = string(response, "next_cursor");
                String nextSession = string(response, "session_id");
                if (nextCursor.isEmpty()
                        || (nextCursor.equals(cursor) && nextSession.equals(sessionId))) break;
                cursor = nextCursor;
                sessionId = nextSession;
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Playlist detail failed for " + id + ": " + describe(error));
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static final Pattern PLAYLIST_ID_PATH = Pattern.compile("playlist/(\\d{6,})");
    private static final Pattern PLAYLIST_ID_PARAM = Pattern.compile("[?&](?:playlist_id|id)=(\\d{6,})");
    private static final Pattern EMBEDDED_LONG_ID = Pattern.compile("(\\d{10,})");
    private static final Pattern ANY_URL = Pattern.compile("https?://[^\\s\"'<>，。]+");

    /** 只用于跟随抖音短链的 302：不自动重定向，逐跳读 Location，命中歌单 ID 即返回。 */
    private static final HttpClient REDIRECT_NEVER_HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 从用户粘贴的文本里提取汽水歌单 ID。兼容：纯数字 ID、{@code .../playlist/123}、
     * {@code ?playlist_id=123}、{@code ?id=123}、分享口令里内嵌的一串长数字；对
     * 抖音短链（{@code v.douyin.com}、{@code douyin.com/s/}、{@code qishui.douyin.com}）
     * 会跟随一次 302 再用最终 URL 复提取。
     *
     * @return 歌单 ID；文本里找不到可用 ID 时返回 {@code null}
     */
    public static String extractPlaylistId(String input) {
        String text = nonBlank(input);
        if (text.isEmpty()) return null;

        String id = firstGroupId(PLAYLIST_ID_PATH, text);
        if (id != null) return id;
        id = firstGroupId(PLAYLIST_ID_PARAM, text);
        if (id != null) return id;
        if (text.matches("\\d{6,}")) return text;
        id = firstGroupId(EMBEDDED_LONG_ID, text);
        if (id != null) return id;

        Matcher urlMatcher = ANY_URL.matcher(text);
        while (urlMatcher.find()) {
            String url = urlMatcher.group();
            if (!isQishuiShortLink(url)) continue;
            String resolved = resolveRedirectPlaylistId(url);
            if (resolved != null) return resolved;
        }
        return null;
    }

    private static boolean isQishuiShortLink(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("douyin.com") || lower.contains("qishui");
    }

    private static String resolveRedirectPlaylistId(String url) {
        String current = url;
        for (int hop = 0; hop < 5 && current != null; hop++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(current))
                        .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                        .header("User-Agent", WEB_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,*/*")
                        .GET().build();
                HttpResponse<Void> response = REDIRECT_NEVER_HTTP.send(request,
                        HttpResponse.BodyHandlers.discarding());
                String location = response.headers().firstValue("location").orElse("");
                if (location.isEmpty()) return null;
                current = URI.create(current).resolve(location).toString();
                String id = firstGroupId(PLAYLIST_ID_PATH, current);
                if (id == null) id = firstGroupId(PLAYLIST_ID_PARAM, current);
                if (id != null) return id;
                if (!isQishuiShortLink(current)) return null;
            } catch (Throwable error) {
                return null;
            }
        }
        return null;
    }

    private static String firstGroupId(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 匿名导入一个汽水歌单：解析 ID 后请求一次歌单详情（不带 Cookie）拿标题/封面/曲目数，
     * 构造 platform=QISHUI 的 {@link PlayList} 引用返回。曲目列表不在此落地——UI 每次点开
     * 时由 {@link #playlistTracks(String, int)} 匿名向上游拉取，因此导入与登录、账号完全解耦。
     *
     * @throws IllegalArgumentException 文本里解析不到有效歌单 ID
     */
    public static PlayList importPlaylist(String input) {
        String id = extractPlaylistId(input);
        if (id == null) {
            throw new IllegalArgumentException(
                    "没识别出汽水歌单 ID，请粘贴歌单分享链接或直接输入数字 ID");
        }

        String title = "";
        String cover = "";
        int count = 0;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("playlist_id", id);
            payload.addProperty("playlist_type", 0);
            payload.addProperty("count", 1);
            JsonObject response = postJson(LUNA_PLAYLIST_DETAIL_ENDPOINT, payload.toString(),
                    ANDROID_USER_AGENT, "", Collections.emptyMap());
            JsonObject meta = object(response, "playlist");
            if (meta.size() == 0) meta = object(object(response, "data"), "playlist");
            title = firstNonBlank(string(meta, "title"), string(meta, "name"));
            cover = coverUrl(object(meta, "url_cover"));
            if (cover.isEmpty()) cover = firstUrl(meta.get("cover_url"));
            long declared = longValue(meta, "count_tracks");
            if (declared <= 0) declared = longValue(meta, "song_count");
            count = checkedInt(declared, "playlist count");
        } catch (Throwable error) {
            // 元数据拿不到不阻断导入：曲目会在点开时匿名再拉，只是标题先兜底成 ID。
            System.err.println("[Music/Qishui] Import metadata failed for " + id + ": "
                    + describe(error));
        }

        PlayList playlist = new PlayList(stableLong("qishui:playlist:" + id),
                title.isEmpty() ? "汽水歌单 " + id : title, cover, Math.max(0, count),
                0L, null, "", 0L);
        playlist.setPlatform(MusicPlatform.QISHUI);
        playlist.setPlatformPlaylistId(id);
        return playlist;
    }

    /** 电台歌曲。radio id 可以直接传入，也可使用 UI 的 radio: 前缀。 */
    public static List<Music> radioTracks(String radioId, int limit) {
        String id = nonBlank(radioId);
        if (id.startsWith("radio:")) id = id.substring("radio:".length()).trim();
        if (id.isEmpty()) return Collections.emptyList();
        int target = Math.max(1, Math.min(500, limit));
        LinkedHashMap<String, Music> unique = new LinkedHashMap<>();
        String cursor = "";
        for (int page = 0; page < 12 && unique.size() < target; page++) {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("radio_id", id);
                payload.addProperty("count", Math.min(50, target - unique.size()));
                if (!cursor.isEmpty()) payload.addProperty("cursor", cursor);
                // 后端在就优先走 PC 签名代理（与官方 PC 客户端同一入口，稳）；不在再走
                // 匿名 Android 流——两条路实测给的是同一份 items 形状。
                JsonObject response;
                if (QishuiBackend.isAvailable()) {
                    response = requirePcStatus(QishuiBackend.signedPc("/luna/pc/feed/radio/tracks",
                            Collections.emptyMap(), payload.toString()), "电台曲目(后端签名)");
                } else {
                    response = postJson(LUNA_RADIO_TRACKS_ENDPOINT, payload.toString(),
                            ANDROID_USER_AGENT, "", Collections.emptyMap());
                }
                collectFeedTracks(array(response, "items"), unique, target);
                String nextCursor = firstNonBlank(string(response, "next_cursor"), string(response, "cursor"));
                if (nextCursor.isEmpty() || nextCursor.equals(cursor)) break;
                cursor = nextCursor;
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Radio tracks failed for " + id + ": " + describe(error));
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 拉账号侧栏要的歌单：<b>自建 + 收藏</b>，各自按游标翻到底，再按平台 ID 去重（带 Cookie）。
     *
     * <p>为什么要合两条接口：{@code /me/playlist} 只有<b>自己建的</b>歌单，收藏别人的歌单在
     * {@code /me/collection/mixed} 里，两边实测无交集（本账号 5 + 6 首 playlists）。以前侧栏
     * 只读前者且只读一页，所以「收藏的歌单不显示」。</p>
     *
     * <p>收藏那条是<b>混合</b>列表，条目按 {@code item_type} 分成 playlist 与 album；本轮按
     * 约定只显示歌单，专辑条目跳过（专辑的卡片字段是 {@code album}，标题在 {@code name} 里，
     * 和歌单的 {@code title} 不同形，硬接进来会画出一堆无名条目）。</p>
     *
     * <p>自建那条<b>失败会抛</b>：快照刷新要靠异常与否区分「账号真的没有歌单」与「这一次
     * 请求失败」，不能把两者都折叠成空列表。收藏那条挂了则只记日志 —— 半张桌子好过一张空桌。</p>
     */
    private static List<PlayList> fetchUserPlaylists(int limit) throws Exception {
        if (!QishuiSessionStore.isLoggedIn()) return Collections.emptyList();
        int cap = Math.max(1, limit);
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<>();
        collectPlaylistCards(fetchAllPcPages(buildPcMePlaylistsUrl(), "playlists", "自建歌单"),
                "", unique, cap);
        // 收藏那条挂了只会在自己里面记日志：半张桌子好过一张空桌。
        for (PlayList playlist : collectionPlaylists(cap)) {
            if (unique.size() >= cap) break;
            unique.putIfAbsent(playlist.getPlatformPlaylistId(), playlist);
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 把一页页捞回来的条目打成 {@link PlayList}，键与 ID 都带上 {@link #ACCOUNT_PLAYLIST_PREFIX}，
     * 让取曲目时能分派到带 Cookie 的 PC 详情接口。
     *
     * @param cardKey 歌单卡片在条目里的字段名：自建接口每条<b>本身就是</b>歌单卡片（传空串），
     *                收藏接口每条要先按 {@code item_type} 钻一层
     */
    private static void collectPlaylistCards(JsonArray items, String cardKey,
                                             LinkedHashMap<String, PlayList> output, int limit) {
        if (items == null) return;
        int cap = Math.max(1, limit);
        for (JsonElement element : items) {
            if (output.size() >= cap) break;
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonObject raw;
            if (cardKey.isEmpty()) {
                raw = item;
            } else {
                // 只认得出来的非歌单条目（专辑）才跳过：字段哪天不上来时，宁可按歌单试一次。
                String kind = string(item, "item_type");
                if (!kind.isEmpty() && !"playlist".equals(kind)) continue;
                raw = object(item, cardKey);
                if (raw.size() == 0) raw = object(object(item, "media"), cardKey);
            }
            PlayList playlist = parsePlaylistCard(raw, object(item, "style"));
            if (playlist == null || playlist.getPlatformPlaylistId().isEmpty()) continue;
            String keyed = ACCOUNT_PLAYLIST_PREFIX + playlist.getPlatformPlaylistId();
            playlist.setPlatformPlaylistId(keyed);
            output.put(keyed, playlist);
        }
    }

    /**
     * 按游标把一个 PC 列表接口翻到底，把每页的数组原样接起来。
     *
     * <p>两个实测过、不看一眼就会踩的坑：</p>
     * <ul>
     *   <li><b>空页不等于到底。</b>{@code /me/playlist} 在 {@code count=2} 时第二页回 0 条，
     *       但 {@code has_more} 仍是 true、{@code next_cursor} 照旧往后走。</li>
     *   <li><b>最后一页不带 {@code has_more} 字段</b>（不是 false，是压根没有），
     *       所以下面用 {@code !truthy(...)} 收尾，而不是比较它等于 false。</li>
     * </ul>
     */
    private static JsonArray fetchAllPcPages(String url, String arrayKey, String what)
            throws Exception {
        JsonArray collected = new JsonArray();
        String cursor = "";
        Set<String> cursors = new HashSet<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonObject response;
            if (QishuiBackend.isAvailable()) {
                // 后端在:签名由官方 bdms.node 经 qs-backend 完成,URL/头序由后端拼装。
                Map<String, String> params = new LinkedHashMap<>();
                params.put("count", String.valueOf(PAGE_SIZE));
                params.put("cursor", cursor);
                response = requirePcStatus(QishuiBackend.signedPc(
                        pcPathOf(url), params, null), what);
            } else {
                StringBuilder paged = new StringBuilder(url);
                append(paged, "count", String.valueOf(PAGE_SIZE));
                append(paged, "cursor", cursor);
                response = requirePcStatus(getJson(paged.toString(), PC_USER_AGENT,
                        QishuiSessionStore.getCookieHeader(), pcHeaders()), what);
            }
            JsonArray items = array(response, arrayKey);
            if (items != null) {
                for (JsonElement item : items) collected.add(item);
            }
            if (!truthy(response, "has_more")) break;
            String next = string(response, "next_cursor");
            if (next.isEmpty() || next.equals(cursor) || !cursors.add(next)) break;
            cursor = next;
        }
        return collected;
    }

    /** {@code https://api.qishui.com/luna/pc/xxx} → {@code /luna/pc/xxx}，后端白名单用。 */
    private static String pcPathOf(String endpoint) {
        return endpoint.startsWith("https://api.qishui.com")
                ? endpoint.substring("https://api.qishui.com".length())
                : endpoint;
    }

    // ── 我的歌单（快照 + 异步，与 QQ / 酷狗侧栏同一套加载形态） ──────────────
    //
    // 侧栏要的是「进页面先画缓存、后台拉完 markDirty 再画一次」，不能像二级页那样
    // 打开时才同步打网络。状态机照抄酷狗那份：loading 闸门、失败后 15 秒内不重打。

    private static final Object USER_PLAYLIST_LOCK = new Object();
    private static final long USER_PLAYLIST_RETRY_DELAY_MS = 15_000L;

    private static volatile List<PlayList> userPlaylistsSnapshot = Collections.emptyList();
    private static volatile boolean userPlaylistsLoaded;
    private static volatile boolean userPlaylistsLoading;
    private static volatile long userPlaylistsLastAttempt;

    public static List<PlayList> getUserPlaylistsSnapshot() {
        return new ArrayList<>(userPlaylistsSnapshot);
    }

    public static boolean areUserPlaylistsLoaded() {
        return userPlaylistsLoaded;
    }

    public static boolean areUserPlaylistsLoading() {
        return userPlaylistsLoading;
    }

    /** 回调运行在后台线程；UI 调用方要自己切回主线程。 */
    public static void ensureUserPlaylistsAsync(final Runnable callback) {
        if (!QishuiSessionStore.isLoggedIn()) {
            clearUserPlaylistsCache();
            if (callback != null) callback.run();
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (USER_PLAYLIST_LOCK) {
            if (userPlaylistsLoading) return;
            if (userPlaylistsLoaded) {
                if (callback != null) callback.run();
                return;
            }
            // 失败后短时间内不重试：凭据已经死了的时候，每次重绘侧栏都重打一次毫无意义。
            if (now - userPlaylistsLastAttempt < USER_PLAYLIST_RETRY_DELAY_MS) return;
            userPlaylistsLoading = true;
            userPlaylistsLastAttempt = now;
        }

        Thread thread = new Thread(() -> {
            try {
                if (!userPlaylistsLoaded) refreshUserPlaylistsSnapshot();
            } finally {
                synchronized (USER_PLAYLIST_LOCK) {
                    userPlaylistsLoading = false;
                }
                if (callback != null) {
                    try {
                        callback.run();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "Qishui Account");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 侧栏刷新按钮：无视「已加载」强制重拉账号歌单。
     *
     * <p>ensure 那条路一局只会拉一次，没有这个入口侧栏快照就再也不更新。结果在
     * <b>后台线程</b>回调（UI 调用方自己切主线程）；刷新已在路上时被 loading 标志挡住
     * 并立刻回报失败，由调用方决定怎么提示。{@link #refreshUserPlaylistsSnapshot()} 内部
     * 吞异常且失败保留旧快照，成败最终看 {@link #areUserPlaylistsLoaded()}。</p>
     */
    public static void forceRefreshUserPlaylistsAsync(Consumer<PlaylistRefreshResult> callback) {
        long startedAt = System.currentTimeMillis();
        if (!isLoggedIn()) {
            if (callback != null) callback.accept(PlaylistRefreshResult.failure(
                    System.currentTimeMillis() - startedAt, "未登录汽水音乐账号"));
            return;
        }
        synchronized (USER_PLAYLIST_LOCK) {
            if (userPlaylistsLoading) {
                if (callback != null) callback.accept(PlaylistRefreshResult.failure(
                        System.currentTimeMillis() - startedAt, "汽水歌单正在刷新中"));
                return;
            }
            userPlaylistsLoading = true;
            userPlaylistsLastAttempt = System.currentTimeMillis();
        }

        Thread thread = new Thread(() -> {
            PlaylistRefreshResult outcome;
            try {
                refreshUserPlaylistsSnapshot();
                outcome = userPlaylistsLoaded
                        ? PlaylistRefreshResult.success(System.currentTimeMillis() - startedAt, userPlaylistsSnapshot.size())
                        : PlaylistRefreshResult.failure(System.currentTimeMillis() - startedAt, "汽水歌单刷新失败");
            } catch (Throwable throwable) {
                outcome = PlaylistRefreshResult.failure(System.currentTimeMillis() - startedAt, throwable.getMessage());
            } finally {
                synchronized (USER_PLAYLIST_LOCK) {
                    userPlaylistsLoading = false;
                }
            }
            if (callback != null) {
                try {
                    callback.accept(outcome);
                } catch (Throwable ignored) {
                }
            }
        }, "Qishui Account Refresh");
        thread.setDaemon(true);
        thread.start();
    }

    private static void refreshUserPlaylistsSnapshot() {
        if (!QishuiSessionStore.isLoggedIn()) {
            clearUserPlaylistsCache();
            return;
        }
        try {
            List<PlayList> loaded = fetchUserPlaylists(ACCOUNT_PLAYLIST_CAP);
            userPlaylistsSnapshot = Collections.unmodifiableList(new ArrayList<>(loaded));
            userPlaylistsLoaded = true;
            System.out.println("[Music/Qishui] 已加载 " + loaded.size() + " 个汽水账号歌单。");
        } catch (Throwable error) {
            userPlaylistsLoaded = false;
            System.err.println("[Music/Qishui] 账号歌单加载失败: " + describe(error));
        }
    }

    /**
     * 凭据一换，快照必须作废：Cookie 导入可能登进来的是<b>另一个</b>抖音账号，
     * 留着上一位的歌单会让侧栏指着拿不到的东西。退出登录同理。
     */
    public static void clearUserPlaylistsCache() {
        userPlaylistsSnapshot = Collections.emptyList();
        userPlaylistsLoaded = false;
        userPlaylistsLoading = false;
        userPlaylistsLastAttempt = 0L;
        likedTrackIds = Collections.emptySet();
        likedTrackIdsLoadAttempted = false;
    }

    /**
     * 收藏列表里的<b>歌单</b>（专辑条目跳过），按游标翻到底。
     *
     * <p>侧栏快照并不直接调这里 —— {@link #fetchUserPlaylists(int)} 会把这条和自建并起来；
     * 这一条留给只要收藏那一半的调用方。</p>
     */
    public static List<PlayList> collectionPlaylists(int limit) {
        if (!QishuiSessionStore.isLoggedIn()) return Collections.emptyList();
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<>();
        try {
            collectPlaylistCards(fetchAllPcPages(buildPcMeCollectionUrl(), "mixed_collections", "收藏"),
                    "playlist", unique, Math.max(1, Math.min(ACCOUNT_PLAYLIST_CAP, limit)));
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] User collection failed: " + describe(error));
        }
        return new ArrayList<>(unique.values());
    }

    public static List<Music> collectionTracks(int limit) {
        if (!QishuiSessionStore.isLoggedIn()) return Collections.emptyList();
        JsonObject response = fetchUserCollection();
        int target = Math.max(1, Math.min(500, limit));
        LinkedHashMap<String, Music> unique = new LinkedHashMap<>();
        JsonArray collections = array(response, "mixed_collections");
        if (collections != null) {
            for (JsonElement element : collections) {
                if (!element.isJsonObject()) continue;
                Music music = parseFeedTrack(element);
                if (music != null) unique.put(music.getSourceId(), music);
                if (unique.size() >= target) break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static JsonObject fetchUserCollection() {
        if (!QishuiSessionStore.isLoggedIn()) return new JsonObject();
        try {
            return requirePcStatus(getJson(buildPcMeCollectionUrl(), PC_USER_AGENT,
                    QishuiSessionStore.getCookieHeader(), pcHeaders()), "收藏");
        } catch (Throwable error) {
            System.err.println("[Music/Qishui] User collection failed: " + describe(error));
            return new JsonObject();
        }
    }

    private static void collectPlaylistResources(JsonArray blocks, Map<String, PlayList> output, int limit) {
        if (blocks == null) return;
        for (JsonElement blockElement : blocks) {
            if (!blockElement.isJsonObject() || output.size() >= limit) return;
            JsonObject block = blockElement.getAsJsonObject();
            JsonArray resources = array(block, "resources");
            if (resources == null) continue;
            for (JsonElement resourceElement : resources) {
                if (!resourceElement.isJsonObject() || output.size() >= limit) return;
                JsonObject resource = resourceElement.getAsJsonObject();
                JsonObject entity = object(resource, "entity");
                JsonObject raw = object(entity, "playlist");
                if (raw.size() == 0) raw = object(resource, "playlist");
                PlayList playlist = parsePlaylistCard(raw, object(resource, "style"));
                if (playlist != null) output.put(playlist.getPlatformPlaylistId(), playlist);
            }
        }
    }

    private static void collectRadioResources(JsonArray blocks, Map<String, PlayList> output, int limit) {
        if (blocks == null) return;
        for (JsonElement blockElement : blocks) {
            if (!blockElement.isJsonObject() || output.size() >= limit) return;
            JsonObject block = blockElement.getAsJsonObject();
            JsonArray resources = array(block, "resources");
            if (resources != null) {
                for (JsonElement resourceElement : resources) {
                    if (!resourceElement.isJsonObject() || output.size() >= limit) return;
                    JsonObject resource = resourceElement.getAsJsonObject();
                    JsonObject style = object(resource, "style");
                    JsonObject radio = object(object(resource, "entity"), "radio");
                    String type = string(resource, "type");
                    if (!"radio".equals(type) && radio.size() == 0) continue;
                    String id = firstNonBlank(string(resource, "resource_id"), string(radio, "radio_id"));
                    if (id.isEmpty()) continue;
                    String title = firstNonBlank(string(style, "title"), string(radio, "radio_name"),
                            string(resource, "title"));
                    if (title.isEmpty()) title = "汽水电台";
                    String cover = firstUrl(style.get("cover_url_list"));
                    if (cover.isEmpty()) cover = firstUrl(style.get("cover_url"));
                    String description = firstNonBlank(string(style, "desc"), string(radio, "description"));
                    PlayList playlist = new PlayList(stableLong("qishui:radio:" + id), title, cover,
                            0, 0L, null, description, 0L);
                    playlist.setPlatform(MusicPlatform.QISHUI);
                    playlist.setPlatformPlaylistId("radio:" + id);
                    output.put(id, playlist);
                }
            }
            collectRadioResources(array(block, "inner_block"), output, limit);
        }
    }

    private static void collectPlaylistsFromSearch(JsonObject response, Map<String, PlayList> output, int limit) {
        JsonArray groups = array(response, "result_groups");
        if (groups == null) return;
        for (JsonElement groupElement : groups) {
            if (!groupElement.isJsonObject() || output.size() >= limit) return;
            JsonObject group = groupElement.getAsJsonObject();
            JsonArray data = array(group, "data");
            if (data == null) continue;
            for (JsonElement itemElement : data) {
                if (!itemElement.isJsonObject() || output.size() >= limit) return;
                JsonObject item = itemElement.getAsJsonObject();
                JsonObject entity = object(item, "entity");
                JsonObject raw = object(entity, "playlist");
                if (raw.size() == 0) raw = object(item, "playlist");
                PlayList playlist = parsePlaylistCard(raw, object(item, "style"));
                if (playlist != null) output.put(playlist.getPlatformPlaylistId(), playlist);
            }
        }
    }

    private static PlayList parsePlaylistCard(JsonObject raw, JsonObject style) {
        if (raw == null || raw.size() == 0) return null;
        String id = firstNonBlank(string(raw, "id"), string(raw, "playlist_id"));
        String title = firstNonBlank(string(raw, "title"), string(raw, "name"), string(style, "title"));
        if (id.isEmpty() || title.isEmpty()) return null;
        String cover = coverUrl(object(raw, "url_cover"));
        if (cover.isEmpty()) cover = firstUrl(raw.get("cover_url"));
        if (cover.isEmpty()) cover = firstUrl(style.get("cover_url"));
        if (cover.isEmpty()) cover = firstUrl(style.get("cover_url_list"));
        int count = checkedInt(longValue(raw, "count_tracks"), "playlist count");
        if (count <= 0) count = checkedInt(longValue(raw, "track_count"), "playlist count");
        if (count <= 0) count = checkedInt(longValue(raw, "song_count"), "playlist count");
        String description = firstNonBlank(string(raw, "desc"), string(raw, "description"),
                string(style, "desc"));
        PlayList playlist = new PlayList(stableLong("qishui:playlist:" + id), title, cover,
                Math.max(0, count), 0L, null, description, 0L);
        playlist.setPlatform(MusicPlatform.QISHUI);
        playlist.setPlatformPlaylistId(id);
        return playlist;
    }

    private static void collectFeedTracks(JsonArray items, Map<String, Music> output, int limit) {
        if (items == null) return;
        for (JsonElement item : items) {
            if (output.size() >= limit) return;
            Music music = parseFeedTrack(item);
            if (music != null && !music.getSourceId().isEmpty()) output.put(music.getSourceId(), music);
        }
    }

    private static Music parseFeedTrack(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject item = element.getAsJsonObject();
        JsonObject entity = object(item, "entity");
        JsonObject wrapper = object(entity, "track_wrapper");
        JsonObject raw = object(wrapper, "track");
        if (raw.size() == 0) raw = object(entity, "track");
        if (raw.size() == 0) raw = object(item, "track");
        if (raw.size() == 0) raw = object(object(item, "media"), "track");
        if (raw.size() == 0) raw = item;
        Track track = parseTrack(raw);
        Music music = track == null ? null : Music.fromQishuiTrack(track);
        // 顺手记下第一位艺人的上游 id:/luna/track/dislike 必填 artist_ids,而这个 id
        // 只在 feed 响应里有。FM 跳过时要查这张侧表。
        if (music != null) {
            JsonArray artists = array(raw, "artists");
            if (artists != null && !artists.isEmpty() && artists.get(0).isJsonObject()) {
                rememberFeedArtistId(music.getSourceId(), longValue(artists.get(0).getAsJsonObject(), "id"));
            }
        }
        return music;
    }

    private static int checkedInt(long value, String name) {
        if (value <= 0L) return 0;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static long stableLong(String value) {
        String safe = value == null ? "" : value;
        try {
            long parsed = Long.parseLong(safe);
            if (parsed > 0L) return parsed;
        } catch (NumberFormatException ignored) {
        }
        long hash = 1125899906842597L;
        for (int index = 0; index < safe.length(); index++) {
            hash = 31L * hash + safe.charAt(index);
        }
        return hash & Long.MAX_VALUE;
    }
    private static Track parseTrack(JsonObject rawTrack) {
        String trackId = string(rawTrack, "id");
        String title = string(rawTrack, "name");
        if (trackId.isEmpty() || title.isEmpty()) return null;

        String artist = joinArtists(array(rawTrack, "artists"));
        JsonObject album = object(rawTrack, "album");
        String albumName = string(album, "name");
        String cover = coverUrl(object(album, "url_cover"));
        long duration = longValue(rawTrack, "duration");
        if (duration > 0L && duration < 1000L) duration *= 1000L;
        JsonObject label = object(rawTrack, "label_info");
        boolean vip = truthy(label, "only_vip_playable");
        return new Track(trackId, title, artist, albumName, duration, cover, vip);
    }

    private static JsonObject fetchTrack(String trackId, boolean authenticated)
            throws IOException, InterruptedException {
        return getJson(TRACK_ENDPOINT + "?track_id=" + urlEncode(trackId) + "&device_platform=web",
                WEB_USER_AGENT, authenticated ? QishuiSessionStore.getCookieHeader() : "",
                Collections.emptyMap());
    }

    /**
     * 登录态的 {@code pc/track_v2}——参考实现里唯一能给完整流与权益字段的那条接口。
     *
     * <p>2026-09-20 实测：这条路由对我们的请求恒返回 <b>HTTP 200 + 0 字节</b>，而它的 H5 姊妹路由
     * {@code /luna/h5/track_v2} 会明说 {@code status_code=1000062 应用版本有风险}。换域名、换
     * 版本号的 6 种组合、换 app_name/device_platform/channel、带不带 cookie、伪造与真实设备标识、
     * UA 与 curl 两套 TLS 栈全都试过了，一律空响应；同期 {@code /luna/pc/me}、{@code me/playlist}、
     * {@code me/collection/mixed}、{@code pc/search/playlist} 都正常出数据，所以不是凭据问题，
     * 而是上游对播放分发接口加了客户端风控。参考实现自己也留了「汽水接口暂时返回空响应」这条
     * 错误分支，说明它同样撞得上。</p>
     *
     * <p>因此这里失败是<b>预期内</b>的：返回 null，由 {@link #getSongUrl} 继续用 H5 那条线，
     * 免费版曲目能拿到完整流，受限曲目只有试听。</p>
     */
    private static JsonObject fetchAuthenticatedTrack(String trackId) {
        if (!QishuiSessionStore.isLoggedIn()) return null;
        String cookie = QishuiSessionStore.getCookieHeader();
        JsonObject payload = new JsonObject();
        payload.addProperty("track_id", trackId);
        payload.addProperty("media_type", "track");
        payload.addProperty("queue_type", "favorite_track_playlist");
        payload.addProperty("scene_name", "library");
        String body = payload.toString();
        // 首选本地签名后端：x-helios/x-medusa 只有官方 bdms.node 算得出，Java 侧复现不了。
        // 后端签名覆盖 URL+全部请求头，必须由最终发请求的后端完成，模组不能签完自发。
        if (QishuiBackend.isAvailable()) {
            try {
                return requirePcStatus(QishuiBackend.signedPc(PC_TRACK_PATH,
                        Collections.emptyMap(), body), "取流(后端签名)");
            } catch (Throwable backendError) {
                System.err.println("[Music/Qishui] Backend-signed track request failed: "
                        + describe(backendError));
                if (looksLikeAuthRejection(backendError)) {
                    handleAuthRejection();
                    return null;
                }
            }
        }
        Throwable last = null;
        for (String base : PC_API_BASES) {
            try {
                return requirePcStatus(postJson(buildPcTrackUrl(base, ""), body, PC_USER_AGENT,
                        cookie, pcHeaders()), "取流");
            } catch (Throwable posted) {
                last = posted;
                // 凭据被拒不换域名重试——参考实现同样在 401/403 时立刻停手，
                // 连着打只会让风控更快盯上这个会话。这里只标记，不清凭据。
                if (looksLikeAuthRejection(posted)) {
                    handleAuthRejection();
                    break;
                }
            }
            try {
                return requirePcStatus(getJson(buildPcTrackUrl(base, trackId), PC_USER_AGENT,
                        cookie, pcHeaders()), "取流");
            } catch (Throwable fallback) {
                last = fallback;
                if (looksLikeAuthRejection(fallback)) {
                    handleAuthRejection();
                    break;
                }
            }
        }
        System.err.println("[Music/Qishui] Authenticated track request failed: " + describe(last));
        return null;
    }

    private static JsonObject fetchPlayerInfo(String url) throws IOException, InterruptedException {
        return getJson(url, WEB_USER_AGENT, QishuiSessionStore.getCookieHeader(),
                Collections.emptyMap());
    }

    static List<Stream> collectCandidates(JsonObject response) {
        List<Stream> candidates = new ArrayList<>();
        if (response == null) return candidates;
        JsonObject body = envelope(response);
        // 一次遍历收全四处流来源：track.audio_info.play_info_list、track_player.video_model、
        // bit_rates 兜底，以及 seo_track 顶层那套。它们的字段大小写不一致，所以统一交给
        // audioUrl()/durationMillis()/playAuth() 各自认名字。
        collectStreams(body, candidates, 0, 0L, "");
        JsonObject player = locateTrackPlayer(body);
        String playerInfoUrl = firstNonBlank(string(player, "url_player_info"),
                string(player, "urlPlayerInfo"), string(player, "URLPlayerInfo"));
        if (!playerInfoUrl.isEmpty()) {
            try {
                // 二跳的播放信息接口是 PascalCase 的 Result.Data.PlayInfoList，同样一次遍历即可。
                collectStreams(envelope(fetchPlayerInfo(playerInfoUrl)), candidates, 0, 0L, "");
            } catch (Throwable error) {
                System.err.println("[Music/Qishui] Player info request failed: " + describe(error));
            }
        }
        return candidates;
    }

    /** 曲目本体：{@code data.track}、顶层 {@code track}、{@code seo_track.track} 都见过。 */
    static JsonObject locateTrack(JsonObject body) {
        JsonObject[] candidates = {
                object(body, "track"), object(body, "track_info"), object(body, "trackInfo"),
                object(object(body, "seo_track"), "track"),
        };
        for (JsonObject candidate : candidates) {
            if (candidate.size() > 0) return candidate;
        }
        return body;
    }

    /** 播放信息本体：{@code track_player} 有时挂在信封上，有时挂在曲目里，命名还有大小写差异。 */
    static JsonObject locateTrackPlayer(JsonObject body) {
        JsonObject track = locateTrack(body);
        JsonObject[] candidates = {
                object(body, "track_player"), object(body, "trackPlayer"),
                object(track, "track_player"), object(track, "trackPlayer"),
        };
        for (JsonObject candidate : candidates) {
            if (candidate.size() > 0) return candidate;
        }
        return new JsonObject();
    }

    /**
     * 遍历响应树，把带播放地址的节点收成候选流。
     *
     * <p>{@code inheritedDurationMillis} 与 {@code inheritedAuth} 是父节点上的
     * {@code video_duration} 和 {@code play_auth}：实测 {@code video_model} 的时长在
     * <b>父节点</b>上，而带 URL 的只有 {@code video_list[i]}，不继承的话候选时长恒为 0，
     * 「时长不足声明值九成就算试听」这条判据在实战里一次都没生效过。</p>
     */
    private static void collectStreams(JsonElement element, List<Stream> output, int depth,
                                       long inheritedDurationMillis, String inheritedAuth) {
        if (element == null || element.isJsonNull() || depth > 8) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectStreams(child, output, depth + 1, inheritedDurationMillis, inheritedAuth);
            }
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject node = element.getAsJsonObject();
        long duration = durationMillis(node);
        if (duration <= 0L) duration = inheritedDurationMillis;
        String auth = playAuth(node);
        if (auth.isEmpty()) auth = nonBlank(inheritedAuth);
        if (!audioUrl(node).isEmpty()) output.add(new Stream(node, duration, auth));
        for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
            // video_model 是「字符串再包一层 JSON」，边走边剥，不然整棵子树都看不见。
            collectStreams(parseEmbeddedJson(entry.getValue()), output, depth + 1, duration, auth);
        }
    }

    static ResolveResult selectAac(List<Stream> candidates, Quality requestedQuality,
                                   long declaredDurationMillis) {
        long targetBitrate = targetBitrateFor(requestedQuality);
        Stream selected = null;
        long selectedScore = -1L;
        long selectedDuration = 0L;
        for (Stream candidate : candidates) {
            String url = audioUrl(candidate.node);
            if (url.isEmpty()) continue;
            JsonObject meta = object(candidate.node, "video_meta");
            String codec = firstNonBlank(string(candidate.node, "codec_type"),
                    string(candidate.node, "Codec"), string(meta, "codec_type")).toLowerCase(Locale.ROOT);
            String format = firstNonBlank(string(candidate.node, "format"), string(candidate.node, "vtype"),
                    string(candidate.node, "Format"), string(meta, "format"),
                    string(meta, "vtype")).toLowerCase(Locale.ROOT);
            String mime = firstNonBlank(string(candidate.node, "mime_type"),
                    string(candidate.node, "MimeType"), string(meta, "mime_type")).toLowerCase(Locale.ROOT);
            boolean aac = codec.contains("aac") || format.contains("m4a") || format.contains("aac")
                    || mime.contains("audio/mp4") || url.toLowerCase(Locale.ROOT).contains("audio_mp4");
            if (!aac || format.contains("flac") || format.contains("wav")) continue;

            long bitrate = streamBitrate(candidate.node);
            long duration = candidate.durationMillis;
            int rank = streamCompletenessRank(duration, declaredDurationMillis);
            int selectedRank = selected == null ? Integer.MIN_VALUE
                    : streamCompletenessRank(selectedDuration, declaredDurationMillis);
            // 参考实现的排序键是「时长优先，差一秒以上才算压制」，之后才比音质与码率。
            // 完整/未知/试听先分档：时长未知只是不作判据，不该压过一条已确认的完整流。
            boolean better;
            if (selected == null) better = true;
            else if (rank != selectedRank) better = rank > selectedRank;
            else if (Math.abs(duration - selectedDuration) > 1_000L) better = duration > selectedDuration;
            else better = bitrateBetter(bitrate, selectedScore, targetBitrate);
            if (better) {
                selected = candidate;
                selectedScore = bitrate;
                selectedDuration = duration;
            }
        }
        if (selected == null) return null;

        String url = audioUrl(selected.node);
        String format = firstNonBlank(string(selected.node, "format"), string(selected.node, "Format"),
                string(selected.node, "vtype")).toLowerCase(Locale.ROOT);
        String normalized = format.contains("aac") && !format.contains("m4a") && !url.contains(".m4a")
                ? "aac" : "m4a";
        if (!isFullStream(selectedDuration, declaredDurationMillis)) {
            System.err.println("[Music/Qishui] 上游只给到试听片段：" + (selectedDuration / 1000L)
                    + "s / 声明 " + (declaredDurationMillis / 1000L) + "s，完整流需要上游放开的权益");
        }
        // 完整度结论必须随流上传：播放层判到「只播了半首」时，要靠它区分「后端未就绪/
        // 未登录的临时降级」与「账号权益真的不够」，不能让上层只按时长差瞎归因到账号。
        boolean full = isFullStream(selectedDuration, declaredDurationMillis);
        return new ResolveResult(url, normalized, streamBitrate(selected.node), selected.playAuth,
                tierOf(selected.node), full);
    }

    /**
     * 这一条流在上游侧属于哪一档。
     *
     * <p>取的是上游自己的 {@code quality}/{@code Quality} 字段（实测取值 {@code medium} /
     * {@code higher} / {@code highest} / {@code lossless} / {@code hi_res} / {@code spatial}），
     * 而不是靠码率猜：汽水的 {@code spatial}（324k）与 {@code highest}（324k）码率几乎一样，
     * 只按码率分不出「高清环绕」与「极高」。码率只在字段缺席时兜底。</p>
     *
     * <p>为什么不能让界面继续显示「本次有几档、各多少码率」：那是实现细节，用户看到
     * {@code 324k/321k/320k/260k/258k/132k/130k/68k/66k} 只会困惑，他只关心「我选了什么、
     * 最后听到的是什么」。所以档位名在这里收成 Quality 枚举，界面再翻译成短名。</p>
     */
    static Quality tierOf(JsonObject candidate) {
        String raw = firstNonBlank(string(candidate, "quality"), string(candidate, "Quality"),
                string(object(candidate, "video_meta"), "quality")).toLowerCase(Locale.ROOT);
        if (raw.contains("spatial") || raw.contains("atmos")) return Quality.JYEFFECT;
        if (raw.contains("jymaster") || raw.contains("master")) return Quality.JYMASTER;
        if (raw.contains("sky") || raw.contains("immers")) return Quality.SKY;
        if (raw.contains("hi_res") || raw.contains("hires") || raw.contains("hi-res")) return Quality.HIRES;
        if (raw.contains("lossless") || raw.contains("flac") || raw.contains("sq")) return Quality.LOSSLESS;
        if (raw.contains("highest") || raw.contains("exhigh")) return Quality.EXHIGH;
        if (raw.contains("higher")) return Quality.HIGHER;
        if (raw.contains("medium") || raw.contains("standard") || raw.contains("normal")) {
            return Quality.STANDARD;
        }
        // 上游没给可辨认的档位名：返回 null 让界面只说「实得」那一半，而不是瞎猜一个档。
        return null;
    }

    /**
     * 候选流的完整度分档：2 完整、1 时长未知、0 试听。
     *
     * <p>{@link #isFullStream} 对时长未知是 fail-open 的（宁可放出声也不静默），但排序时
     * 不能让「未知」和「已确认完整」同分——否则父节点时长没继承到的候选会凭 fail-open
     * 永远压过真正的完整流，这正是审计里那条判据实战失效的原因。</p>
     */
    static int streamCompletenessRank(long durationMillis, long declaredMillis) {
        if (durationMillis <= 0L) return 1;
        return isFullStream(durationMillis, declaredMillis) ? 2 : 0;
    }

    /**
     * 目标码率：把全局音质偏好换算成汽水码率阶梯（实测 68k/132k/260k/324k）上的选档策略。
     *
     * <p>返回值语义：{@code 0} = 取最低档（标准音质省流量）；{@code Long.MAX_VALUE} = 取最高档；
     * 其余 = 取「≥目标的最小档」，一档都没达标就取最高——宁可多给一点，不偷偷降档。</p>
     */
    static long targetBitrateFor(Quality quality) {
        if (quality == null) return 0L;
        switch (quality) {
            case STANDARD: return 0L;
            case HIGHER: return 192_000L;
            case EXHIGH: return 320_000L;
            // LOSSLESS 及以上：汽水 AAC 阶梯里 324k 就是顶，直接取最高。
            default: return Long.MAX_VALUE;
        }
    }

    /** 同完整度、同时长的两条流按选档策略比码率；{@code currentBest < 0} 表示还没有选中者。 */
    static boolean bitrateBetter(long candidate, long currentBest, long target) {
        if (currentBest < 0L) return true;
        if (target == Long.MAX_VALUE) return candidate > currentBest;
        if (target == 0L) return candidate < currentBest;
        if (candidate >= target && currentBest >= target) return candidate < currentBest;
        if (candidate >= target) return true;
        if (currentBest >= target) return false;
        return candidate > currentBest;
    }

    /** 流码率：显式字段优先，其次按档位的代表码率兜底。字段大小写两套都见过。 */
    private static long streamBitrate(JsonObject candidate) {
        long bitrate = longValue(candidate, "bitrate");
        if (bitrate <= 0L) bitrate = longValue(candidate, "Bitrate");
        if (bitrate <= 0L) bitrate = longValue(candidate, "real_bitrate");
        if (bitrate <= 0L) bitrate = longValue(candidate, "br");
        if (bitrate <= 0L) bitrate = longValue(object(candidate, "video_meta"), "bitrate");
        if (bitrate <= 0L) bitrate = qualityScore(string(candidate, "quality"));
        return bitrate;
    }

    private static String audioUrl(JsonObject candidate) {
        String url = firstNonBlank(string(candidate, "main_url"), string(candidate, "backup_url"),
                string(candidate, "main_play_url"), string(candidate, "backup_play_url"),
                string(candidate, "MainPlayUrl"), string(candidate, "BackupPlayUrl"),
                string(candidate, "play_url"), string(candidate, "PlayURL"));
        if (!url.isEmpty()) return url;
        JsonArray urls = array(candidate, "url_list");
        if (urls != null) {
            for (JsonElement item : urls) if (item.isJsonPrimitive()) return item.getAsString();
        }
        return "";
    }

    private static String playAuth(JsonObject candidate) {
        String auth = firstNonBlank(string(candidate, "play_auth"), string(candidate, "PlayAuth"),
                string(candidate, "spade_a"), string(candidate, "SpadeA"));
        if (!auth.isEmpty()) return auth;
        JsonObject encrypt = object(candidate, "encrypt_info");
        return firstNonBlank(string(encrypt, "spade_a"), string(encrypt, "SpadeA"),
                string(encrypt, "play_auth"), string(encrypt, "PlayAuth"));
    }

    private static long qualityScore(String quality) {
        String value = nonBlank(quality).toLowerCase(Locale.ROOT);
        if (value.contains("hires") || value.contains("hi_res")) return 2000L;
        if (value.contains("lossless") || value.contains("flac") || value.contains("sq")) return 1500L;
        if (value.contains("higher") || value.contains("exhigh") || value.contains("highest")) return 320L;
        if (value.contains("standard") || value.contains("normal")) return 128L;
        return 0L;
    }

    private static JsonElement parseEmbeddedJson(JsonElement value) {
        JsonElement current = value;
        for (int depth = 0; depth < 3 && current != null && current.isJsonPrimitive()
                && current.getAsJsonPrimitive().isString(); depth++) {
            String text = current.getAsString();
            try {
                current = JsonParser.parseString(text);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return current;
    }

    private static String lyricContent(JsonObject response) {
        JsonObject seo = object(response, "seo_track");
        String content = string(object(seo, "lyric"), "content");
        if (!content.isEmpty()) return content;
        return string(object(response, "lyric"), "content");
    }

    private static String sodaWordsToQrc(String raw) {
        StringBuilder converted = new StringBuilder(raw.length() + 32);
        String[] lines = raw.replace("\r", "").split("\n");
        for (String line : lines) {
            int firstTag = line.indexOf('<');
            if (firstTag > 0) converted.append(line, 0, firstTag);
            Matcher matcher = SODA_WORD.matcher(line);
            boolean appended = false;
            while (matcher.find()) {
                String text = matcher.group(4);
                if (text.isEmpty()) continue;
                converted.append(text).append('(').append(matcher.group(1)).append(',')
                        .append(matcher.group(2)).append(')');
                appended = true;
            }
            if (appended) converted.append('\n');
        }
        return converted.toString();
    }
    private static String buildSearchUrl(String query, int limit) {
        StringBuilder url = new StringBuilder(SEARCH_ENDPOINT).append('?');
        appendMobileCommonParams(url);
        append(url, "q", query);
        append(url, "cursor", "0");
        append(url, "count", String.valueOf(limit));
        return url.toString();
    }

    /**
     * 匿名移动端公共参数（与官方 App 同形）。搜索、免签名写操作（如 /luna/track/dislike）
     * 共用这一套——2026-10-11 实测这些移动端面端点不要求应用级签名，带 cookie + 公共参数即达。
     */
    private static void appendMobileCommonParams(StringBuilder url) {
        append(url, "device_platform", "android");
        append(url, "os", "android");
        append(url, "ssmix", "a");
        append(url, "cdid", "46556f98-1720-4248-83da-62b74b60b46a");
        append(url, "channel", "xiaomi_8478_64");
        append(url, "aid", "386088");
        append(url, "app_name", "luna");
        append(url, "version_code", "100198030");
        append(url, "version_name", "19.8.0");
        append(url, "manifest_version_code", "100198030");
        append(url, "update_version_code", "100198030");
        append(url, "resolution", "1080*1920");
        append(url, "dpi", "480");
        append(url, "device_type", "ABR-AL80");
        append(url, "device_brand", "HUAWEI");
        append(url, "language", "zh");
        append(url, "os_api", "35");
        append(url, "os_version", "15");
        append(url, "ac", "wifi");
        append(url, "device_model", "ABR-AL80");
        append(url, "save_power", "0");
        append(url, "font_size", "1.00");
        append(url, "luna_first_launch_apk_type", "normal_apk");
        append(url, "diversion_channel_name", "xiaomi_8478_64");
        append(url, "is_car_play", "0");
        append(url, "battery", "0.99");
        append(url, "network_speed", "10156");
        append(url, "hybrid_version_code", "100198030");
        append(url, "tz_name", "Asia/Shanghai");
        append(url, "tz_offset", "28800");
        append(url, "luna_register_time", "1784311292");
        append(url, "package", "com.luna.music");
        append(url, "charge", "0");
        append(url, "luna_apk_type", "normal_apk");
        append(url, "output_device_type", "Phone");
        append(url, "volume", "1.00");
        append(url, "brightness", "0.08");
        append(url, "need_personal_recommend", "1");
        append(url, "is_teen_mode", "0");
        append(url, "sim_region", "cn");
        append(url, "android_device_type", "default");
        append(url, "iid", "2204957404569386");
        append(url, "device_id", "2204957404565290");
        append(url, "_rticket", String.valueOf(System.currentTimeMillis()));
    }

    /** {@code trackId} 为空时是 POST 用的 URL（曲目 id 只在 body 里），非空时是 GET 回落用的。 */
    private static String buildPcTrackUrl(String base, String trackId) {
        StringBuilder url = buildPcUrl(base + PC_TRACK_PATH);
        if (!nonBlank(trackId).isEmpty()) {
            append(url, "track_id", trackId);
            append(url, "media_type", "track");
        }
        return url.toString();
    }

    private static String buildPcSearchUrl(String keyword, int cursor) {
        StringBuilder url = buildPcUrl(PC_SEARCH_PLAYLIST_ENDPOINT);
        appendPcSearchParams(url, keyword, cursor);
        return url.toString();
    }

    private static String buildPcMixedSearchUrl(String keyword, int cursor) {
        StringBuilder url = buildPcUrl(PC_SEARCH_MIXED_ENDPOINT);
        appendPcSearchParams(url, keyword, cursor);
        return url.toString();
    }

    private static String buildPcMePlaylistsUrl() {
        return buildPcUrl(PC_ME_PLAYLISTS_ENDPOINT).toString();
    }

    /**
     * 带 Cookie 的歌单详情 URL。这条接口按<b>纯偏移</b>走游标（实测 {@code cursor} 依次是
     * 100 / 200 / 300），也不需要 Android 那条的 {@code playlist_type} 与 {@code session_id}。
     */
    private static String buildPcPlaylistDetailUrl(String playlistId, String cursor) {
        StringBuilder url = buildPcUrl(PC_PLAYLIST_DETAIL_ENDPOINT);
        append(url, "playlist_id", playlistId);
        append(url, "count", String.valueOf(PAGE_SIZE));
        append(url, "cursor", nonBlank(cursor));
        return url.toString();
    }

    private static String buildPcMeCollectionUrl() {
        return buildPcUrl(PC_ME_COLLECTION_ENDPOINT).toString();
    }

    private static StringBuilder buildPcUrl(String endpoint) {
        long now = System.currentTimeMillis();
        String deviceId = String.valueOf(now);
        String iid = String.valueOf(now + 1L);
        StringBuilder url = new StringBuilder(endpoint).append('?');
        append(url, "aid", "386088");
        append(url, "app_name", "luna_pc");
        append(url, "region", "cn");
        append(url, "geo_region", "cn");
        append(url, "os_region", "cn");
        append(url, "sim_region", "");
        append(url, "device_id", deviceId);
        append(url, "cdid", "");
        append(url, "iid", iid);
        append(url, "version_name", "3.3.0");
        append(url, "version_code", "30030000");
        append(url, "channel", "official");
        append(url, "build_mode", "master");
        append(url, "network_carrier", "");
        append(url, "ac", "wifi");
        append(url, "tz_name", "Asia/Shanghai");
        append(url, "resolution", "");
        append(url, "device_platform", "windows");
        append(url, "device_type", "Windows");
        append(url, "os_version", "Windows 11");
        append(url, "fp", deviceId);
        return url;
    }

    private static void appendPcSearchParams(StringBuilder url, String keyword, int cursor) {
        append(url, "q", keyword);
        append(url, "cursor", String.valueOf(Math.max(0, cursor)));
        append(url, "search_id", "");
        append(url, "search_method", "input");
        append(url, "debug_params", "");
        append(url, "from_search_id", "");
        append(url, "search_scene", "");
    }

    private static String buildAccountUrl() {
        return ACCOUNT_ENDPOINT + "?aid=386088";
    }

    /** PC 接口除 Content-Type 外还要那三个 Luna 自报家门的头，参考实现同样带 Referer。 */
    private static Map<String, String> pcHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Referer", "https://www.qishui.com/");
        headers.put("x-luna-background-type", "foreground");
        headers.put("x-luna-is-background-req", "0");
        headers.put("x-luna-is-local-user", "1");
        return headers;
    }

    private static void append(StringBuilder url, String key, String value) {
        if (url.charAt(url.length() - 1) != '?') url.append('&');
        url.append(urlEncode(key)).append('=').append(urlEncode(value));
    }
    private static JsonObject getJson(String url, String userAgent, String cookie,
                                      Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        return getJsonResponse(url, userAgent, cookie, extraHeaders).body;
    }

    private static JsonResponse getJsonResponse(String url, String userAgent, String cookie,
                                                Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        return execute(request(url, userAgent, cookie, extraHeaders).GET().build());
    }

    private static JsonObject postJson(String url, String body, String userAgent, String cookie,
                                       Map<String, String> extraHeaders)
            throws IOException, InterruptedException {
        HttpRequest request = request(url, userAgent, cookie, extraHeaders)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return execute(request).body;
    }

    private static JsonResponse postFormResponse(String url, Map<String, String> form,
                                                 String cookie, String userAgent)
            throws IOException, InterruptedException {
        HttpRequest request = request(url, userAgent, cookie, Collections.emptyMap())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form), StandardCharsets.UTF_8))
                .build();
        return execute(request);
    }

    private static HttpRequest.Builder request(String url, String userAgent, String cookie,
                                               Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", userAgent);
        if (cookie != null && !cookie.trim().isEmpty()) builder.header("Cookie", cookie);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    builder.header(header.getKey(), header.getValue());
                }
            }
        }
        return builder;
    }

    private static JsonResponse execute(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String text = new String(readLimited(response.body()), StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("汽水音乐 HTTP " + response.statusCode()
                    + (text.isEmpty() ? "" : ": " + text));
        }
        if (text.trim().isEmpty()) throw new IOException("汽水音乐返回了空响应");
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) throw new IOException("汽水音乐返回了非 JSON 对象");
        return new JsonResponse(parsed.getAsJsonObject(), cookieHeaderFromSetCookie(response));
    }

    /**
     * PC 接口用「HTTP 200 + status_code」表达失败，只看 HTTP 状态码会把一条失败响应读成成功。
     *
     * <p>实测过的码：{@code 1000016} 凭据失效、{@code 1000062} 客户端风控（应用版本有风险）、
     * {@code 1000004} 参数非法、{@code 1000005} 资源不存在。不校验的话 {@code 1000016}
     * 会一路走到 {@code markVerified()}，把失效凭据标成「已验证」，而 {@code looksLikeAuthRejection}
     * 因为压根没收到异常而永不触发。抛出去的消息里带上码值，凭据失效才能被认出来。
     */
    static JsonObject requirePcStatus(JsonObject response, String what) throws IOException {
        if (response == null) return null;
        long code = longValue(response, "status_code");
        if (code == 0L) code = longValue(response, "error_code");
        if (code == 0L) return response;
        JsonObject info = object(response, "status_info");
        String reason = info.size() == 0 ? "" : nonBlank(string(info, "status_msg"));
        throw new IOException("汽水音乐 " + what + " 返回业务码 " + code
                + (reason.isEmpty() ? "" : "：" + reason));
    }

    private static String cookieHeaderFromSetCookie(HttpResponse<?> response) {
        StringBuilder header = new StringBuilder();
        for (String raw : response.headers().allValues("set-cookie")) {
            if (raw == null) continue;
            int separator = raw.indexOf(';');
            String pair = (separator < 0 ? raw : raw.substring(0, separator)).trim();
            if (pair.isEmpty()) continue;
            if (header.length() > 0) header.append("; ");
            header.append(pair);
        }
        return header.toString();
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder encoded = new StringBuilder();
        if (form != null) {
            for (Map.Entry<String, String> entry : form.entrySet()) {
                if (encoded.length() > 0) encoded.append('&');
                encoded.append(urlEncode(entry.getKey())).append('=')
                        .append(urlEncode(entry.getValue()));
            }
        }
        return encoded.toString();
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        if (input == null) return new byte[0];
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > MAX_BYTES) throw new IOException("汽水音乐响应过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
    private static String joinArtists(JsonArray artists) {
        if (artists == null) return "";
        List<String> names = new ArrayList<>();
        for (JsonElement element : artists) {
            if (element == null || !element.isJsonObject()) continue;
            String name = string(element.getAsJsonObject(), "name");
            if (!name.isEmpty()) names.add(name);
        }
        return String.join(" / ", names);
    }

    private static String coverUrl(JsonObject image) {
        if (image == null) return "";
        String uri = string(image, "uri");
        String templatePrefix = string(image, "template_prefix");
        if (!uri.isEmpty()) {
            // 上游偶尔不给 template_prefix，但 uri 本身是完整的对象键。这时沿用那个
            // 已知可用的模板前缀 —— 实测它对每个存储桶都成立（tos-cn-i-b829550vbb、
            // tos-cn-v-2774c002…）。原来这种情形会回落到裸 url（没有 ~tplv-…-resize:
            // 后缀），那是个 **HTTP 400**：真机上一屏封面全指向它，HttpUtils 还会对 400
            // 重试十次，几十条线程一起猛打网络，把带宽从真正要用的请求那里抢走。
            String prefix = templatePrefix.isEmpty() ? COVER_TEMPLATE_PREFIX : templatePrefix;
            return COVER_ROOT + uri + "~" + prefix + "-resize:" + COVER_PIXELS + ":" + COVER_PIXELS + ".png";
        }
        JsonArray urls = array(image, "urls");
        if (urls == null || urls.size() == 0) return "";
        String base = urls.get(0).isJsonNull() ? "" : urls.get(0).getAsString();
        if (!uri.isEmpty() && !base.contains(uri)) base += uri;
        return base;
    }

    /**
     * 取封面时的边长（像素）。
     *
     * <p>原来写死 960：一张 960×960 的 ARGB 解码出来是 3.7 MB 的 int 数组，而它服务的
     * 位置最大也只有详情页 hero 那不到 200 像素、列表里更是 40~100 像素。降到这里：
     * 像素量约为原来的 1/7，解码内存与主线程贴图上传的开销同比降，观感在两种用途上都
     * 足够（封面是照片，缩小后仍锐）。要提回去只动这一个数。</p>
     */
    private static final int COVER_PIXELS = 360;
    /**
     * 封面模板前缀兜底。上游给的 {@code template_prefix} 优先；它缺失时用这个 ——
     * 实测对该服务的每个存储桶都成立（见 {@link #coverUrl(JsonObject)}）。
     */
    private static final String COVER_TEMPLATE_PREFIX = "tplv-b829550vbb";

    private static String firstUrl(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) {
            String value = nonBlank(element.getAsString());
            return value.startsWith("http://") || value.startsWith("https://") ? value : "";
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String value = firstUrl(child);
                if (!value.isEmpty()) return value;
            }
            return "";
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String uri = string(object, "uri");
            JsonArray urls = array(object, "urls");
            if (urls != null && urls.size() > 0) {
                String base = firstUrl(urls.get(0));
                if (!base.isEmpty()) return uri.isEmpty() || base.contains(uri) ? base : base + uri;
            }
            for (String key : List.of("url_list", "urls", "url", "uri", "cover_url_list")) {
                String value = firstUrl(object.get(key));
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    private static boolean truthy(JsonObject object, String property) {
        if (object == null || !object.has(property) || object.get(property).isJsonNull()) return false;
        try {
            return object.get(property).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static JsonObject object(JsonObject parent, String property) {
        if (parent == null || !parent.has(property) || !parent.get(property).isJsonObject()) {
            return new JsonObject();
        }
        return parent.getAsJsonObject(property);
    }

    private static JsonArray array(JsonObject parent, String property) {
        if (parent == null || !parent.has(property) || !parent.get(property).isJsonArray()) return null;
        return parent.getAsJsonArray(property);
    }

    private static String string(JsonObject parent, String property) {
        if (parent == null || !parent.has(property) || parent.get(property).isJsonNull()) return "";
        try {
            return nonBlank(parent.get(property).getAsString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long longValue(JsonObject parent, String property) {
        if (parent == null || !parent.has(property) || parent.get(property).isJsonNull()) return 0L;
        try {
            // 走上游实测存在的浮点写法（"Duration": 29.002、"Bitrate": 129895.0）。
            // getAsLong() 对带小数点的 JSON 数字直接抛异常，以前这些字段一律静默读成 0。
            return Math.round(parent.get(property).getAsDouble());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static double numberValue(JsonObject parent, String property) {
        if (parent == null || !parent.has(property) || parent.get(property).isJsonNull()) return 0D;
        try {
            return parent.get(property).getAsDouble();
        } catch (Throwable ignored) {
            return 0D;
        }
    }

    private static double firstPositiveNumber(JsonObject parent, String... properties) {
        if (parent == null) return 0D;
        for (String property : properties) {
            double value = numberValue(parent, property);
            if (value > 0D) return value;
        }
        return 0D;
    }

    /**
     * 一条候选流的时长，单位毫秒；拿不到时返回 0。
     *
     * <p>两条上游的形状完全不同：{@code video_model} 里是小写整数字段，而
     * {@code url_player_info} 返回的 PlayInfoList 用的是 <b>PascalCase 的浮点秒</b>
     * （实测 {@code "Duration": 29.002} / {@code 279.918}）。原先只读小写名，播放信息接口的
     * 时长就恒为 0，完整流与试听片段再也分不开。</p>
     */
    private static long durationMillis(JsonObject candidate) {
        if (candidate == null) return 0L;
        double raw = firstPositiveNumber(candidate, "duration", "Duration", "video_duration",
                "videoDuration", "play_time");
        if (raw <= 0D) raw = firstPositiveNumber(object(candidate, "video_meta"), "duration", "Duration");
        if (raw <= 0D) return 0L;
        // 小于 10000 的按秒解释：没有歌长过一万秒（近三小时），也没有试听短于十秒。
        return Math.round(raw < 10_000D ? raw * 1000D : raw);
    }

    /**
     * 这条候选是不是完整长度，而不是试听片段。
     *
     * <p>判据优先用曲目自己声明的总时长：流不足声明值的 95% 就是试听。之所以不能像参考实现
     * 那样按「恰好 30 或 60 秒」判，是因为实测的试听是 <b>29.002 秒</b>，固定值匹配会漏掉它。</p>
     *
     * <p>声明时长未知时才退回固定试听规格；两者都不知道就当作完整，让它按码率正常竞争——
     * 宁可放一段试听，也不该因为判不出长度而放不出声音。</p>
     */
    private static boolean isFullStream(long durationMillis, long declaredMillis) {
        if (declaredMillis > 0L) {
            return durationMillis <= 0L || durationMillis * 100L >= declaredMillis * 95L;
        }
        if (durationMillis <= 0L) return true;
        return durationMillis != 30_000L && durationMillis != 60_000L;
    }

    /**
     * 曲目声明的总时长（毫秒），用来把试听片段和完整流分开。
     * 搜索响应、seo_track 与 pc/track_v2 里的挂载位置各不相同，所以逐个探后取最大值，
     * 防止某一处给的是预览时长。注意不看 {@code preview.duration}——那正是要识别的东西。
     */
    private static long declaredTrackDuration(JsonObject response) {
        if (response == null) return 0L;
        JsonObject body = envelope(response);
        JsonObject seo = object(body, "seo_track");
        double best = 0D;
        for (JsonObject container : new JsonObject[]{body, seo, locateTrack(body),
                object(seo, "track"), object(body, "media_resource"),
                object(object(body, "track"), "audio_info")}) {
            double value = firstPositiveNumber(container, "duration", "duration_ms", "Duration",
                    "video_duration");
            if (value > best) best = value;
        }
        if (best <= 0D) return 0L;
        return Math.round(best < 10_000D ? best * 1000D : best);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(nonBlank(value), StandardCharsets.UTF_8);
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String describe(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null
                ? String.valueOf(throwable) : throwable.getMessage();
    }

    /**
     * 一条候选流：节点本体，加上从父节点继承来的时长与解密凭据。
     * 上游把 {@code video_duration} 与 {@code play_auth} 放在 {@code video_list} 的父节点上，
     * 只带节点本身就没法判断它是完整流还是试听片段。
     */
    static final class Stream {
        final JsonObject node;
        final long durationMillis;
        final String playAuth;

        Stream(JsonObject node, long durationMillis, String playAuth) {
            this.node = node;
            this.durationMillis = durationMillis;
            this.playAuth = playAuth == null ? "" : playAuth;
        }
    }

    private static final class JsonResponse {
        final JsonObject body;
        final String cookieHeader;

        JsonResponse(JsonObject body, String cookieHeader) {
            this.body = body;
            this.cookieHeader = cookieHeader == null ? "" : cookieHeader;
        }
    }

}