package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.utils.Tuple;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GD Studio 聚合音乐 API（https://music-api.gdstudio.xyz）的内置客户端。
 *
 * <p>仅使用文档记载的四种请求类型：{@code types=search}、{@code types=url}、
 * {@code types=pic} 和 {@code types=lyric}。每个请求都携带前一次搜索响应返回的平台和标识，
 * 与文档要求完全一致。</p>
 *
 * <h3>兜底保护</h3>
 * <ul>
 *   <li>一个滚动窗口限流器把流量限制在文档记载的 50 请求 / 5 分钟，并保留
 *       余量，使封面/歌词的装饰性流量永远不会挤占搜索和播放。</li>
 *   <li>按请求类型的超时防止缓慢的封面提供方阻塞播放解析。</li>
 *   <li>按源的健康追踪（断路器）记录上游错误，冷却失败的
 *       源，并在一个请求成功后自动恢复。</li>
 *   <li>播放解析走文档记载的码率阶梯，当选中源仍
 *       无返回时，在一个健康源上重新匹配该曲目，而非直接失败。</li>
 *   <li>封面/歌词未命中被负缓存，使死掉的提供方不会每帧都被轮询。</li>
 * </ul>
 */
public final class GdStudioMusicService {

    public static final String API_ENDPOINT = "https://music-api.gdstudio.xyz/api.php";

    private static final String AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SEARCH_COUNT = 20;

    private static final long REQUEST_WINDOW_MILLIS = 5L * 60L * 1000L;
    private static final int MAX_REQUESTS_PER_WINDOW = 50;
    /** 为搜索/播放保留的槽位；封面和歌词在低于此余量时停止请求。 */
    private static final int COSMETIC_BUDGET_RESERVE = 12;
    /** 码率降级重试仅在此预算仍有空闲时运行。 */
    private static final int LADDER_MIN_BUDGET = 6;
    /** 跨源救援花费一次搜索加一次 url 请求。 */
    private static final int CROSS_SOURCE_MIN_BUDGET = 10;
    private static final int CROSS_SOURCE_LIMIT = 1;
    /** 歌词救援是装饰性的；它仅在播放预算充裕时运行。 */
    private static final int LYRIC_FALLBACK_MIN_BUDGET = 20;
    /** 墙上时钟守护，使缓慢的提供方无法串联三次完整读取超时。 */
    private static final long LADDER_DEADLINE_MILLIS = 18_000L;
    private static final long CROSS_SOURCE_DEADLINE_MILLIS = 22_000L;

    private static final long SEARCH_CACHE_MILLIS = 15_000L;
    private static final long COVER_CACHE_MILLIS = 6L * 60L * 60L * 1000L;
    private static final long LYRIC_CACHE_MILLIS = 6L * 60L * 60L * 1000L;
    /** 空的封面/歌词回答被短暂记住，而非重复请求。 */
    private static final long NEGATIVE_CACHE_MILLIS = 10L * 60L * 1000L;

    private static final long UNSUPPORTED_COOLDOWN_MILLIS = 30L * 60L * 1000L;
    private static final long EMPTY_PLAYBACK_COOLDOWN_MILLIS = 60L * 1000L;
    private static final long[] FAILURE_COOLDOWN_MILLIS = {10_000L, 45_000L, 180_000L, 600_000L};

    private static final Object REQUEST_LOCK = new Object();
    private static final Deque<Long> REQUEST_TIMESTAMPS = new ArrayDeque<>();
    private static final Object CACHE_LOCK = new Object();
    private static final Map<String, CacheEntry<List<GdTrack>>> SEARCH_CACHE = new LinkedHashMap<>();
    private static final Map<String, CacheEntry<String>> COVER_CACHE = new LinkedHashMap<>();
    private static final Map<String, CacheEntry<Tuple<String, String>>> LYRIC_CACHE = new LinkedHashMap<>();
    private static final Map<String, CacheEntry<Tuple<String, String>>> LYRIC_FALLBACK_CACHE = new LinkedHashMap<>();
    private static final Object HEALTH_LOCK = new Object();
    private static final Map<String, SourceHealth> HEALTH = new LinkedHashMap<>();
    private static final List<Platform> PLATFORMS = buildPlatforms();
    private static final Map<String, Platform> PLATFORM_BY_KEY = indexPlatforms();

    private GdStudioMusicService() {
    }

    /** 源菜单中显示的运行时提示；若上游恢复，条目仍可选。 */
    public enum PlatformStatus {
        RECOMMENDED("稳定 · 搜索、封面、播放、歌词", 0x75D8A0),
        PLAYABLE("可播放 · 歌词可能为空", 0x82C7FF),
        EXPERIMENTAL("实验性 · 播放链接可能为空", 0xE7C26B),
        UNAVAILABLE("暂未开放 · 接口恢复后可直接重试", 0xAEB5C4);

        public final String description;
        public final int color;

        PlatformStatus(String description, int color) {
            this.description = description;
            this.color = color;
        }
    }

    /** GD Studio API 暴露的一个可选平台。 */
    public static final class Platform {
        public final String key;
        public final String displayName;
        public final PlatformStatus status;

        private Platform(String key, String displayName, PlatformStatus status) {
            this.key = key;
            this.displayName = displayName;
            this.status = status == null ? PlatformStatus.UNAVAILABLE : status;
        }
    }

    /** 由 GD Studio 搜索响应适配来的搜索结果。 */
    public static final class GdTrack {
        public final String id;
        public final String name;
        public final String artist;
        public final String album;
        public final String picId;
        public final String lyricId;
        public final String source;

        private GdTrack(String id, String name, String artist, String album, String picId, String lyricId, String source) {
            this.id = id;
            this.name = name;
            this.artist = artist;
            this.album = album;
            this.picId = picId;
            this.lyricId = lyricId;
            this.source = source;
        }
    }

    /** 携带文档记载 {@code detail} 消息的上游错误。 */
    public static final class GdApiException extends RuntimeException {
        public final int httpStatus;
        public final boolean unsupportedSource;

        private GdApiException(String message, int httpStatus, boolean unsupportedSource) {
            super(message);
            this.httpStatus = httpStatus;
            this.unsupportedSource = unsupportedSource;
        }
    }

    /** 一次 GD Studio 播放解析的结果，包括实际服务它的源。 */
    public static final class ResolveResult {
        public final String url;
        public final String format;
        public final String platform;
        public final String requestedPlatform;
        public final int bitrate;

        private ResolveResult(String url, String format, String platform, String requestedPlatform, int bitrate) {
            this.url = url;
            this.format = format;
            this.platform = platform;
            this.requestedPlatform = requestedPlatform;
            this.bitrate = bitrate;
        }

        /** 选中源失败、另一个健康源救回播放时为 true。 */
        public boolean isFallback() {
            return !platform.isEmpty() && !requestedPlatform.isEmpty() && !platform.equals(requestedPlatform);
        }
    }

    /** 搜索结果加上实际应答的源，使 UI 能解释一次兜底。 */
    public static final class SearchOutcome {
        public final List<GdTrack> tracks;
        public final String source;
        public final String requestedSource;
        public final String failureReason;
        /** 选中源报错时为 true；它只是没有匹配时为 false。 */
        public final boolean requestedSourceFailed;

        private SearchOutcome(List<GdTrack> tracks, String source, String requestedSource,
                              String failureReason, boolean requestedSourceFailed) {
            this.tracks = tracks == null ? Collections.<GdTrack>emptyList() : tracks;
            this.source = safe(source);
            this.requestedSource = safe(requestedSource);
            this.failureReason = safe(failureReason);
            this.requestedSourceFailed = requestedSourceFailed;
        }

        public boolean isFallback() {
            return !source.isEmpty() && !requestedSource.isEmpty() && !source.equals(requestedSource);
        }

        public boolean isEmpty() {
            return tracks.isEmpty();
        }
    }

    /** 一个源的运行时健康，由源菜单和兜底选择器使用。 */
    public static final class Health {
        public final boolean cooling;
        public final boolean unsupported;
        public final long cooldownRemainingMillis;
        public final String lastError;

        private Health(boolean cooling, boolean unsupported, long cooldownRemainingMillis, String lastError) {
            this.cooling = cooling;
            this.unsupported = unsupported;
            this.cooldownRemainingMillis = cooldownRemainingMillis;
            this.lastError = safe(lastError);
        }
    }

    public static List<Platform> getPlatforms() {
        return PLATFORMS;
    }

    public static String displayName(String key) {
        Platform platform = PLATFORM_BY_KEY.get(safe(key).toLowerCase(Locale.ROOT));
        return platform == null ? safe(key).toUpperCase(Locale.ROOT) : platform.displayName;
    }

    public static boolean isKnownPlatform(String key) {
        return PLATFORM_BY_KEY.containsKey(safe(key).toLowerCase(Locale.ROOT));
    }

    /** 在文档记载的五分钟窗口内仍可用的请求数。 */
    public static int remainingRequestBudget() {
        synchronized (REQUEST_LOCK) {
            pruneTimestampsLocked(System.currentTimeMillis());
            return Math.max(0, MAX_REQUESTS_PER_WINDOW - REQUEST_TIMESTAMPS.size());
        }
    }

    /** 运行时健康快照；永不为 null。 */
    public static Health getHealth(String key) {
        String platform = safe(key).toLowerCase(Locale.ROOT);
        synchronized (HEALTH_LOCK) {
            SourceHealth health = HEALTH.get(platform);
            if (health == null) return new Health(false, false, 0L, "");
            long now = System.currentTimeMillis();
            long httpRemaining = Math.max(0L, health.cooldownUntil - now);
            long playbackRemaining = Math.max(0L, health.playbackCooldownUntil - now);
            long remaining = Math.max(httpRemaining, playbackRemaining);
            String lastError = httpRemaining >= playbackRemaining && !health.lastError.isEmpty()
                    ? health.lastError
                    : (health.playbackError.isEmpty() ? health.lastError : health.playbackError);
            return new Health(remaining > 0L, health.unsupported && httpRemaining > 0L, remaining, lastError);
        }
    }

    /** 源菜单的简短状态后缀，源看起来健康时为空。 */
    public static String statusLabel(String key) {
        Health health = getHealth(key);
        if (!health.cooling) return "";
        long seconds = (health.cooldownRemainingMillis + 999L) / 1000L;
        String window = seconds >= 60L ? (seconds / 60L) + " 分钟" : seconds + " 秒";
        return (health.unsupported ? "接口未开放 · " : "暂时故障 · ") + window + "后重试";
    }

    /** GET types=search。文档记载的默认页大小为 20；不要请求超过该数。 */
    public static List<GdTrack> search(String source, String keyword, int count, int page) throws Exception {
        String platform = requirePlatform(source);
        String name = safe(keyword);
        if (name.isEmpty()) return Collections.emptyList();

        int pageSize = Math.max(1, Math.min(MAX_SEARCH_COUNT, count <= 0 ? MAX_SEARCH_COUNT : count));
        int pageNumber = Math.max(1, page);
        String cacheKey = platform + '\n' + name.toLowerCase(Locale.ROOT) + '\n' + pageSize + '\n' + pageNumber;
        List<GdTrack> cached = cachedSearch(cacheKey);
        if (cached != null) return cached;

        StringBuilder query = new StringBuilder("types=search");
        append(query, "source", platform);
        append(query, "name", name);
        append(query, "count", String.valueOf(pageSize));
        append(query, "pages", String.valueOf(pageNumber));
        JsonElement root = json(query.toString(), RequestKind.SEARCH, platform);
        if (root == null) return Collections.emptyList();
        if (root.isJsonObject()) {
            String reason = firstError(root.getAsJsonObject());
            markFailure(platform, reason, false);
            throw new IllegalStateException(reason);
        }

        List<GdTrack> result = new ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                // 一些源会忽略请求的页大小（JOOX 对 count=5 返回 30），
                //因此在本地裁剪载荷，使列表渲染可预测。
                if (result.size() >= pageSize) break;
                JsonObject item = element.getAsJsonObject();
                String id = text(item, "id");
                if (id.isEmpty()) continue;
                String resultSource = normalizePlatform(text(item, "source"));
                result.add(new GdTrack(id, text(item, "name"), artists(item.get("artist")),
                        text(item, "album"), text(item, "pic_id"), text(item, "lyric_id"),
                        resultSource.isEmpty() ? platform : resultSource));
            }
        }
        List<GdTrack> immutable = Collections.unmodifiableList(result);
        // 绝不固定空回答：一些源间歇性返回空数组，而缓存的
        //未命中会让立即重试（或用户再次搜索）在 15 秒内变得无意义。
        if (!immutable.isEmpty()) {
            cacheSearch(cacheKey, immutable);
        }
        return new ArrayList<>(immutable);
    }

    /**
     * 带兜底保护的搜索：总是先试选中源，仅当真正失败
     * （或文档记载的"源不支持"回答）时才把查询交给一个健康
     * 源，使搜索界面仍显示结果而非空列表。
     */
    public static SearchOutcome searchWithFallback(String source, String keyword, int count, int page) {
        String requested = normalizePlatform(source);
        String name = safe(keyword);
        if (requested.isEmpty() || name.isEmpty()) {
            return new SearchOutcome(Collections.<GdTrack>emptyList(), "", requested,
                    requested.isEmpty() ? "未选择有效的 GD音乐台 平台" : "", false);
        }

        String reason = "";
        boolean failed = false;
        if (!isHardBlocked(requested)) {
            try {
                List<GdTrack> tracks = search(requested, name, count, page);
                if (tracks.isEmpty() && isFlakyEmptySource(requested)
                        && remainingRequestBudget() >= LADDER_MIN_BUDGET) {
                    // Bilibili 随机返回 HTTP 200 加空数组，因此用户
                    //实际选中的源在查询离开它之前再获一次机会。
                    tracks = search(requested, name, count, page);
                }
                if (!tracks.isEmpty()) {
                    return new SearchOutcome(tracks, requested, requested, "", false);
                }
                // 空数组是合法回答，而非故障：一些源也会间歇性
                //返回空数组，这就是为什么查询仍会在别处被提供。
                reason = "该音源没有匹配结果";
            } catch (Throwable throwable) {
                reason = describe(throwable);
                failed = true;
            }
        } else {
            reason = statusLabel(requested);
            if (reason.isEmpty()) reason = "该音源暂不可用";
            failed = true;
        }

        for (String candidate : fallbackCandidates(requested)) {
            if (isCancelled()) break;
            if (remainingRequestBudget() < LADDER_MIN_BUDGET) break;
            try {
                List<GdTrack> tracks = search(candidate, name, count, page);
                if (!tracks.isEmpty()) {
                    return new SearchOutcome(tracks, candidate, requested, reason, failed);
                }
            } catch (Throwable ignored) {
                // 保留主失败作为面向用户的原因。
            }
        }
        return new SearchOutcome(Collections.<GdTrack>emptyList(), "", requested, reason, failed);
    }

    /** GET types=url。请求的值只能是文档记载的码率之一。 */
    public static JsonObject requestUrl(String source, String trackId, int br) throws Exception {
        String platform = requirePlatform(source);
        String id = safe(trackId);
        if (id.isEmpty()) return null;
        StringBuilder query = new StringBuilder("types=url");
        append(query, "source", platform);
        append(query, "id", id);
        append(query, "br", String.valueOf(documentedBitrate(br)));
        JsonElement root = json(query.toString(), RequestKind.URL, platform);
        return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
    }

    /**
     * 仅用文档记载的 300/500 尺寸值的 GET types=pic。
     *
     * <p>封面是可选装饰：缺少预算、提供方失败或空回答
     * 都返回空字符串而非抛异常，未命中被负缓存，使封面
     * 加载器不会在每个列表帧上轮询死掉的提供方。</p>
     */
    public static String getPicUrl(String source, String picId, int size) {
        String platform = normalizePlatform(source);
        String id = safe(picId);
        if (platform.isEmpty() || id.isEmpty()) return "";
        // Bilibili 已在 pic_id 中返回可用的（协议相对的）封面 URL，
        //因此不花费请求再次解析它。
        String direct = directImageUrl(id);
        if (!direct.isEmpty()) return direct;

        int imageSize = size == 500 ? 500 : 300;
        String cacheKey = platform + ':' + id + ':' + imageSize;
        String cached = cached(COVER_CACHE, cacheKey);
        if (cached != null) return cached;
        if (!hasCosmeticBudget() || isHardBlocked(platform)) return "";

        String url = "";
        try {
            StringBuilder query = new StringBuilder("types=pic");
            append(query, "source", platform);
            append(query, "id", id);
            append(query, "size", String.valueOf(imageSize));
            JsonElement root = json(query.toString(), RequestKind.PIC, platform);
            url = root != null && root.isJsonObject() ? text(root.getAsJsonObject(), "url") : "";
        } catch (Throwable throwable) {
            cache(COVER_CACHE, cacheKey, "", NEGATIVE_CACHE_MILLIS);
            return "";
        }
        cache(COVER_CACHE, cacheKey, url, url.isEmpty() ? NEGATIVE_CACHE_MILLIS : COVER_CACHE_MILLIS);
        return url;
    }

    /**
     * GET types=lyric：原始 LRC 加可选翻译 LRC。
     *
     * <p>歌词也是可选的。空回答（Bilibili 从来没有）被短暂缓存，使歌词面板
     * 不重复请求，失败时返回 {@code null} 而非抛异常。</p>
     */
    public static Tuple<String, String> getLyric(String source, String lyricId) {
        String platform = normalizePlatform(source);
        String id = safe(lyricId);
        if (platform.isEmpty() || id.isEmpty()) return null;
        String cacheKey = platform + ':' + id;
        Tuple<String, String> cached = cached(LYRIC_CACHE, cacheKey);
        if (cached != null) return cached;
        if (!hasCosmeticBudget() || isHardBlocked(platform)) return null;

        try {
            StringBuilder query = new StringBuilder("types=lyric");
            append(query, "source", platform);
            append(query, "id", id);
            JsonElement root = json(query.toString(), RequestKind.LYRIC, platform);
            if (root == null || !root.isJsonObject()) return null;
            JsonObject object = root.getAsJsonObject();
            Tuple<String, String> lyric = new Tuple<>(text(object, "lyric"), text(object, "tlyric"));
            boolean empty = lyric.getA().isEmpty() && lyric.getB().isEmpty();
            cache(LYRIC_CACHE, cacheKey, lyric, empty ? NEGATIVE_CACHE_MILLIS : LYRIC_CACHE_MILLIS);
            return lyric;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * 带兜底保护的歌词。Bilibili 从不返回 LRC，其他源偶尔也没有，
     * 因此按标题/艺术家在一个健康源匹配一次，答案（命中或未命中）被缓存。
     * 救援仅在请求预算仍充裕时运行，因为它花费
     * 一次搜索加一次歌词请求，绝不能与播放竞争。
     */
    public static Tuple<String, String> getLyricWithFallback(String source, String lyricId,
                                                            String trackName, String artist) {
        Tuple<String, String> primary = getLyric(source, lyricId);
        if (primary != null && !safe(primary.getA()).isEmpty()) return primary;

        String requested = normalizePlatform(source);
        String title = safe(trackName);
        if (requested.isEmpty() || title.isEmpty()) return primary;

        String cacheKey = requested + ':' + safe(lyricId) + ':' + title.toLowerCase(Locale.ROOT);
        Tuple<String, String> cached = cached(LYRIC_FALLBACK_CACHE, cacheKey);
        if (cached != null) {
            return safe(cached.getA()).isEmpty() ? primary : cached;
        }
        if (remainingRequestBudget() < LYRIC_FALLBACK_MIN_BUDGET) return primary;

        int attempts = 0;
        for (String candidate : fallbackCandidates(requested)) {
            if (isCancelled()) break;
            if (attempts >= CROSS_SOURCE_LIMIT) break;
            attempts++;
            try {
                List<GdTrack> tracks = search(candidate, searchQuery(title, artist), 10, 1);
                GdTrack match = bestMatch(tracks, title, artist);
                if (match == null || safe(match.lyricId).isEmpty()) continue;
                Tuple<String, String> rescued = getLyric(candidate, match.lyricId);
                if (rescued != null && !safe(rescued.getA()).isEmpty()) {
                    cache(LYRIC_FALLBACK_CACHE, cacheKey, rescued, LYRIC_CACHE_MILLIS);
                    return rescued;
                }
            } catch (Throwable ignored) {
                // 歌词是装饰：失败的救援绝不能作为错误暴露。
            }
        }
        // 记住这次未命中，使歌词面板不会每次播放都重试。
        cache(LYRIC_FALLBACK_CACHE, cacheKey, new Tuple<>("", ""), NEGATIVE_CACHE_MILLIS);
        return primary;
    }

    /**
     * 清掉某首歌的歌词缓存（含跨源兜底的救援缓存）。
     *
     * <p>手动「重新匹配」要用它。这里的歌词缓存按小时计，而且<b>未命中也会被记住</b>——一次错误的
     * 兜底匹配或一次误判的「没有歌词」，在那之后无论刷新多少次都拿不到新结果，用户看到的就是
     * 「点了没反应」。</p>
     *
     * <p>与 {@link #getLyric} 用同一把 {@code CACHE_LOCK}：缓存是普通 {@code LinkedHashMap}，
     * 从别的线程直接 remove 会和请求线程打架。</p>
     */
    public static void invalidateLyric(String source, String lyricId, String trackName) {
        String platform = normalizePlatform(source);
        String id = safe(lyricId);
        if (platform.isEmpty() || id.isEmpty()) return;

        String title = safe(trackName);
        synchronized (CACHE_LOCK) {
            LYRIC_CACHE.remove(platform + ':' + id);
            if (!title.isEmpty()) {
                LYRIC_FALLBACK_CACHE.remove(platform + ':' + id + ':' + title.toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * 从搜索响应精确解析选中的源/id 对，当源对首选档位返回空 URL 时，
     * 走文档记载的码率阶梯。
     */
    public static ResolveResult resolveTrack(String source, String trackId, Quality quality) throws Exception {
        return resolveDirect(requirePlatform(source), safe(trackId), quality);
    }

    /**
     * 带完整兜底保护的播放解析：先走选中源上文档记载的码率阶梯，
     * 再按标题/艺术家在一个健康推荐源重新匹配。仅当
     * 每个受保护尝试都合法地没有产出流时返回 {@code null}。
     */
    public static ResolveResult resolveTrackWithFallback(String source, String trackId, String trackName,
                                                         String artist, Quality quality) throws Exception {
        return resolveProtected(source, trackId, trackName, artist, quality, false);
    }

    /**
     * 完全跳过选中源，只试一个健康的推荐源。用于
     * 选中源确实返回了 URL、但下载的字节结果不是可播放音频时。
     */
    public static ResolveResult resolveCrossSource(String source, String trackName, String artist,
                                                  Quality quality) throws Exception {
        return resolveProtected(source, "", trackName, artist, quality, true);
    }

    private static ResolveResult resolveProtected(String source, String trackId, String trackName,
                                                  String artist, Quality quality,
                                                  boolean skipSelectedSource) throws Exception {
        String requested = requirePlatform(source);
        String id = safe(trackId);
        long started = System.currentTimeMillis();
        Exception primaryError = null;
        if (!skipSelectedSource && !isHardBlocked(requested)) {
            try {
                ResolveResult direct = resolveDirect(requested, id, quality);
                if (direct != null) return direct;
            } catch (Exception error) {
                primaryError = error;
            }
        }

        String title = safe(trackName);
        if (!title.isEmpty()) {
            int attempts = 0;
            for (String candidate : fallbackCandidates(requested)) {
                if (isCancelled()) break;
                if (attempts >= CROSS_SOURCE_LIMIT) break;
                if (remainingRequestBudget() < CROSS_SOURCE_MIN_BUDGET) break;
                if (System.currentTimeMillis() - started > CROSS_SOURCE_DEADLINE_MILLIS) break;
                attempts++;
                try {
                    List<GdTrack> tracks = search(candidate, searchQuery(title, artist), 10, 1);
                    GdTrack match = bestMatch(tracks, title, artist);
                    if (match == null) continue;
                    ResolveResult rescued = resolveDirect(candidate, match.id, quality);
                    if (rescued != null) {
                        return new ResolveResult(rescued.url, rescued.format, candidate, requested, rescued.bitrate);
                    }
                } catch (Throwable ignored) {
                    // 失败的救援绝不能掩盖原始错误。
                }
            }
        }
        if (primaryError != null) throw primaryError;
        return null;
    }

    private static ResolveResult resolveDirect(String platform, String trackId, Quality quality) throws Exception {
        String id = safe(trackId);
        if (id.isEmpty()) return null;
        int[] ladder = bitrateLadder(quality);
        long ladderStarted = System.currentTimeMillis();
        Exception firstError = null;
        for (int index = 0; index < ladder.length; index++) {
            if (isCancelled()) break;
            if (index > 0 && remainingRequestBudget() < LADDER_MIN_BUDGET) break;
            if (index > 0 && System.currentTimeMillis() - ladderStarted > LADDER_DEADLINE_MILLIS) break;
            try {
                JsonObject object = requestUrl(platform, id, ladder[index]);
                String url = object == null ? "" : text(object, "url");
                if (!url.isEmpty()) {
                    markPlaybackSuccess(platform);
                    return new ResolveResult(url, inferFormat(url), platform, platform,
                            intValue(object, "br", ladder[index]));
                }
            } catch (GdApiException error) {
                // 上游服务未暴露的源不会应答另一个码率。
                if (error.unsupportedSource) throw error;
                firstError = error;
            } catch (Exception error) {
                firstError = error;
            }
        }
        if (firstError != null) throw firstError;
        // 被取消的尝试不说明源的问题，因此不得标记为损坏。
        ensureNotCancelled();
        markEmptyPlayback(platform);
        return null;
    }

    /** 可救回失败选择的、推荐的、当前健康的源。 */
    private static List<String> fallbackCandidates(String requested) {
        List<String> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(safe(requested).toLowerCase(Locale.ROOT));
        for (Platform platform : PLATFORMS) {
            if (platform.status != PlatformStatus.RECOMMENDED) continue;
            if (!seen.add(platform.key)) continue;
            if (isCooling(platform.key)) continue;
            candidates.add(platform.key);
        }
        return candidates;
    }

    /** 观察到即使对匹配查询也返回 HTTP 200 加空数组的源。 */
    private static boolean isFlakyEmptySource(String source) {
        return "bilibili".equals(safe(source).toLowerCase(Locale.ROOT));
    }

    private static String searchQuery(String title, String artist) {
        String name = safe(title);
        String performer = safe(artist);
        return performer.isEmpty() ? name : name + ' ' + performer;
    }

    /** 挑选最接近的搜索命中；简体/繁体拼写仍按字符重叠匹配。 */
    private static GdTrack bestMatch(List<GdTrack> tracks, String title, String artist) {
        if (tracks == null || tracks.isEmpty()) return null;
        GdTrack best = null;
        double bestScore = 0.0;
        boolean artistKnown = !safe(artist).isEmpty();
        for (GdTrack track : tracks) {
            if (track == null || safe(track.id).isEmpty()) continue;
            double nameScore = titleSimilarity(track.name, title);
            if (nameScore < .5) continue;
            double artistScore = similarity(track.artist, artist);
            // 没有艺术家守护，救援可能静默播放另一首仅仅
            //与请求标题共享字符的歌。
            if (artistKnown && !safe(track.artist).isEmpty() && artistScore < .34) continue;
            double score = nameScore * 2.0 + artistScore;
            if (score > bestScore) {
                bestScore = score;
                best = track;
            }
        }
        return best;
    }

    /** Bigram overlap: order-sensitive enough that "晴天" and "天晴" are no longer treated as equal. */
    private static double titleSimilarity(String left, String right) {
        String a = normalizeForMatch(left);
        String b = normalizeForMatch(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;
        if (a.length() < 2 || b.length() < 2) return similarity(a, b);
        Set<String> first = bigrams(a);
        Set<String> second = bigrams(b);
        int shared = 0;
        for (String value : first) {
            if (second.contains(value)) shared++;
        }
        int union = first.size() + second.size() - shared;
        return union <= 0 ? 0.0 : (double) shared / (double) union;
    }

    private static Set<String> bigrams(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 1 < value.length(); index++) {
            result.add(value.substring(index, index + 2));
        }
        return result;
    }

    private static String normalizeForMatch(String value) {
        StringBuilder builder = new StringBuilder();
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isLetterOrDigit(character)) builder.append(character);
        }
        return builder.toString();
    }

    private static double similarity(String left, String right) {
        Set<Character> a = characters(left);
        Set<Character> b = characters(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int shared = 0;
        for (Character value : a) {
            if (b.contains(value)) shared++;
        }
        int union = a.size() + b.size() - shared;
        return union <= 0 ? 0.0 : (double) shared / (double) union;
    }

    private static Set<Character> characters(String value) {
        Set<Character> result = new LinkedHashSet<>();
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isLetterOrDigit(character)) result.add(character);
        }
        return result;
    }

    private static String directImageUrl(String picId) {
        String value = safe(picId);
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("https://")) return value;
        if (value.startsWith("http://")) return "https://" + value.substring("http://".length());
        return "";
    }

    /** 文档记载的码率阶梯：先首选档位，再逐级更安全。 */
    private static int[] bitrateLadder(Quality quality) {
        Quality requested = quality == null ? Quality.LOSSLESS : quality;
        switch (requested) {
            case STANDARD:
                return new int[]{128, 320};
            case HIGHER:
                return new int[]{192, 320, 128};
            case EXHIGH:
                return new int[]{320, 192, 128};
            case LOSSLESS:
            default:
                return new int[]{999, 320, 128};
        }
    }

    private static int documentedBitrate(int bitrate) {
        switch (bitrate) {
            case 128:
            case 192:
            case 320:
            case 740:
            case 999:
                return bitrate;
            default:
                return 999;
        }
    }

    private static String inferFormat(String url) {
        String value = safe(url).toLowerCase(Locale.ROOT);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot < value.length() - 1) return value.substring(dot + 1);
        return "mp3";
    }

    private static String requirePlatform(String value) {
        String platform = normalizePlatform(value);
        if (platform.isEmpty()) throw new IllegalArgumentException("未选择有效的 GD音乐台 平台");
        return platform;
    }

    private static String normalizePlatform(String value) {
        String platform = safe(value).toLowerCase(Locale.ROOT);
        return isKnownPlatform(platform) ? platform : "";
    }

    private static List<GdTrack> cachedSearch(String key) {
        List<GdTrack> result = cached(SEARCH_CACHE, key);
        return result == null ? null : new ArrayList<>(result);
    }

    private static void cacheSearch(String key, List<GdTrack> value) {
        cache(SEARCH_CACHE, key, value, SEARCH_CACHE_MILLIS);
    }

    private static <T> T cached(Map<String, CacheEntry<T>> cache, String key) {
        synchronized (CACHE_LOCK) {
            CacheEntry<T> entry = cache.get(key);
            if (entry == null) return null;
            if (entry.expiresAt <= System.currentTimeMillis()) {
                cache.remove(key);
                return null;
            }
            return entry.value;
        }
    }

    private static <T> void cache(Map<String, CacheEntry<T>> cache, String key, T value, long lifetime) {
        if (value == null) return;
        synchronized (CACHE_LOCK) {
            if (cache.size() >= 128) cache.clear();
            cache.put(key, new CacheEntry<>(value, System.currentTimeMillis() + lifetime));
        }
    }

    /** 有各自超时的请求类别；封面/歌词绝不能拖延播放解析。 */
    private enum RequestKind {
        SEARCH(12_000, 25_000, false),
        URL(12_000, 30_000, false),
        PIC(6_000, 10_000, true),
        LYRIC(6_000, 10_000, true);

        private final int connectTimeout;
        private final int readTimeout;
        private final boolean cosmetic;

        RequestKind(int connectTimeout, int readTimeout, boolean cosmetic) {
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.cosmetic = cosmetic;
        }
    }

    private static void ensureNotCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("GD音乐台请求已取消");
        }
    }

    private static boolean isCancelled() {
        return Thread.currentThread().isInterrupted();
    }

    private static boolean hasCosmeticBudget() {
        return remainingRequestBudget() > COSMETIC_BUDGET_RESERVE;
    }

    private static void pruneTimestampsLocked(long now) {
        long cutoff = now - REQUEST_WINDOW_MILLIS;
        while (!REQUEST_TIMESTAMPS.isEmpty() && REQUEST_TIMESTAMPS.peekFirst() <= cutoff) {
            REQUEST_TIMESTAMPS.removeFirst();
        }
    }

    /** 在建立连接前强制 GD Studio 文档记载的滚动请求预算。 */
    private static void acquireRequestSlot(RequestKind kind) {
        synchronized (REQUEST_LOCK) {
            long now = System.currentTimeMillis();
            pruneTimestampsLocked(now);
            int limit = kind.cosmetic
                    ? MAX_REQUESTS_PER_WINDOW - COSMETIC_BUDGET_RESERVE
                    : MAX_REQUESTS_PER_WINDOW;
            if (REQUEST_TIMESTAMPS.size() >= limit) {
                long retryAfter = REQUEST_TIMESTAMPS.isEmpty() ? 1_000L
                        : Math.max(1L, REQUEST_TIMESTAMPS.peekFirst() + REQUEST_WINDOW_MILLIS - now);
                throw new IllegalStateException("GD音乐台请求已达 5 分钟 50 次上限，请在 "
                        + ((retryAfter + 999L) / 1000L) + " 秒后重试");
            }
            REQUEST_TIMESTAMPS.addLast(now);
        }
    }

    private static final class CacheEntry<T> {
        private final T value;
        private final long expiresAt;

        private CacheEntry(T value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    private static final class SourceHealth {
        private int consecutiveFailures;
        private long cooldownUntil;
        private boolean unsupported;
        private String lastError = "";
        // 一个源可能对搜索应答 HTTP 200 却从不给出流（酷我）。该状态
        //必须在一次成功搜索后保留，否则菜单会声称该源健康。
        private long playbackCooldownUntil;
        private String playbackError = "";
    }

    /** 仅对上游服务报告为不支持、且仍在冷却的源为 true。 */
    private static boolean isHardBlocked(String source) {
        Health health = getHealth(source);
        return health.unsupported && health.cooling;
    }

    private static boolean isCooling(String source) {
        return getHealth(source).cooling;
    }

    /** 清除传输层失败。已知的播放故障保留到一次流成功。 */
    private static void markSuccess(String source) {
        String platform = safe(source).toLowerCase(Locale.ROOT);
        if (platform.isEmpty()) return;
        synchronized (HEALTH_LOCK) {
            SourceHealth health = HEALTH.get(platform);
            if (health == null) return;
            health.consecutiveFailures = 0;
            health.cooldownUntil = 0L;
            health.unsupported = false;
            health.lastError = "";
            if (health.playbackCooldownUntil <= System.currentTimeMillis()) {
                HEALTH.remove(platform);
            }
        }
    }

    /** 真实流回来了：该源再次完全健康。 */
    private static void markPlaybackSuccess(String source) {
        String platform = safe(source).toLowerCase(Locale.ROOT);
        if (platform.isEmpty()) return;
        synchronized (HEALTH_LOCK) {
            HEALTH.remove(platform);
        }
    }

    private static void markFailure(String source, String reason, boolean unsupported) {
        String platform = safe(source).toLowerCase(Locale.ROOT);
        if (platform.isEmpty()) return;
        synchronized (HEALTH_LOCK) {
            SourceHealth health = HEALTH.get(platform);
            if (health == null) {
                health = new SourceHealth();
                HEALTH.put(platform, health);
            }
            health.lastError = safe(reason);
            health.unsupported = unsupported;
            if (unsupported) {
                health.consecutiveFailures = FAILURE_COOLDOWN_MILLIS.length;
                health.cooldownUntil = System.currentTimeMillis() + UNSUPPORTED_COOLDOWN_MILLIS;
                return;
            }
            health.consecutiveFailures = Math.min(FAILURE_COOLDOWN_MILLIS.length, health.consecutiveFailures + 1);
            health.cooldownUntil = System.currentTimeMillis()
                    + FAILURE_COOLDOWN_MILLIS[health.consecutiveFailures - 1];
        }
    }

    /** 文档记载的 200 回答带空 URL：源活着，但播放不可用。 */
    private static void markEmptyPlayback(String source) {
        String platform = safe(source).toLowerCase(Locale.ROOT);
        if (platform.isEmpty()) return;
        synchronized (HEALTH_LOCK) {
            SourceHealth health = HEALTH.get(platform);
            if (health == null) {
                health = new SourceHealth();
                HEALTH.put(platform, health);
            }
            health.playbackError = "该音源未返回可播放链接";
            health.playbackCooldownUntil = Math.max(health.playbackCooldownUntil,
                    System.currentTimeMillis() + EMPTY_PLAYBACK_COOLDOWN_MILLIS);
        }
    }

    private static JsonElement json(String query, RequestKind kind, String source) throws Exception {
        // 被调用方放弃的解析（超时用中断取消 future）绝不能
        //为没人会读的结果继续消耗文档记载的 50 请求 / 5 分钟。
        ensureNotCancelled();
        acquireRequestSlot(kind);
        HttpURLConnection connection = (HttpURLConnection) new URL(API_ENDPOINT + "?" + query).openConnection();
        connection.setConnectTimeout(kind.connectTimeout);
        connection.setReadTimeout(kind.readTimeout);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("User-Agent", AGENT);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String detail = errorDetail(connection);
                boolean unsupported = isUnsupportedSourceDetail(detail);
                String message = detail.isEmpty()
                        ? "GD音乐台返回 HTTP " + status
                        : "GD音乐台 " + status + "：" + detail;
                markFailure(source, unsupported ? "接口未开放该音源" : message, unsupported);
                throw new GdApiException(message, status, unsupported);
            }
            JsonElement parsed;
            try (InputStream input = connection.getInputStream()) {
                parsed = new JsonParser().parse(new String(readLimited(input), StandardCharsets.UTF_8));
            }
            markSuccess(source);
            return parsed;
        } catch (GdApiException error) {
            throw error;
        } catch (Exception error) {
            markFailure(source, describe(error), false);
            throw error;
        } finally {
            connection.disconnect();
        }
    }

    private static String errorDetail(HttpURLConnection connection) {
        try (InputStream stream = connection.getErrorStream()) {
            if (stream == null) return "";
            String body = new String(readLimited(stream), StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(body);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                String detail = text(object, "detail");
                if (detail.isEmpty()) detail = firstError(object);
                return detail;
            }
            return body.length() > 160 ? body.substring(0, 160) : body;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isUnsupportedSourceDetail(String detail) {
        String value = safe(detail).toLowerCase(Locale.ROOT);
        return value.contains("source") && value.contains("not supported");
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) return "未知错误";
        String message = safe(throwable.getMessage());
        if (!message.isEmpty()) return message;
        return throwable.getClass().getSimpleName();
    }

    private static byte[] readLimited(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > MAX_BYTES) throw new IllegalStateException("GD音乐台响应过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void append(StringBuilder builder, String key, String value) {
        if (safe(value).isEmpty()) return;
        if (builder.length() > 0) builder.append('&');
        try {
            builder.append(key).append('=').append(URLEncoder.encode(safe(value), "UTF-8"));
        } catch (Exception ignored) {
            builder.append(key).append('=');
        }
    }

    private static String artists(JsonElement element) {
        if (element == null || !element.isJsonArray()) return "";
        StringBuilder result = new StringBuilder();
        for (JsonElement value : element.getAsJsonArray()) {
            String name = value.isJsonPrimitive() ? value.getAsString() : "";
            if (name.trim().isEmpty()) continue;
            if (result.length() > 0) result.append('、');
            result.append(name.trim());
        }
        return result.toString();
    }

    private static String firstError(JsonObject object) {
        String message = text(object, "detail");
        if (message.isEmpty()) message = text(object, "msg");
        if (message.isEmpty()) message = text(object, "message");
        if (message.isEmpty()) message = text(object, "error");
        return message.isEmpty() ? "平台暂不可用" : message;
    }

    private static String text(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString().trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsInt() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<Platform> buildPlatforms() {
        List<Platform> platforms = new ArrayList<>();
        // 按当前端到端验证排序。所有文档记载参数都保留，使
        //上游服务恢复的源无需客户端更新即可再次可选。
        platforms.add(new Platform("netease", "网易云音乐", PlatformStatus.RECOMMENDED));
        platforms.add(new Platform("joox", "JOOX", PlatformStatus.RECOMMENDED));
        platforms.add(new Platform("bilibili", "哔哩哔哩", PlatformStatus.PLAYABLE));
        platforms.add(new Platform("kuwo", "酷我音乐", PlatformStatus.EXPERIMENTAL));
        platforms.add(new Platform("tencent", "QQ音乐", PlatformStatus.UNAVAILABLE));
        platforms.add(new Platform("tidal", "Tidal", PlatformStatus.UNAVAILABLE));
        platforms.add(new Platform("qobuz", "Qobuz", PlatformStatus.UNAVAILABLE));
        platforms.add(new Platform("apple", "Apple Music", PlatformStatus.UNAVAILABLE));
        platforms.add(new Platform("ytmusic", "YouTube Music", PlatformStatus.UNAVAILABLE));
        platforms.add(new Platform("spotify", "Spotify", PlatformStatus.UNAVAILABLE));
        return platforms;
    }

    private static Map<String, Platform> indexPlatforms() {
        Map<String, Platform> result = new LinkedHashMap<>();
        for (Platform platform : PLATFORMS) result.put(platform.key, platform);
        return result;
    }
}
