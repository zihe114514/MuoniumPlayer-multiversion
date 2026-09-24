package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 内置的 YouTube 源适配器。
 *
 * 不能直接嵌入 Minecraft Java 运行时。本类保持相同的 InnerTube
 * 请求形态，但对播放器所需的两个操作使用 YouTube 的 Android 客户端：
 * 搜索和音频流解析。</p>
 *
 * <p>播放刻意只选 {@code audio/mp4} 流。宿主音频管线已
 * 理解 AAC/M4A，而 YouTube 的 Opus 流到达 WebM 容器，该管线
 * 刻意拒绝。</p>
 */
public final class YouTubeMusicService {

    private static final String API_ENDPOINT = "https://www.youtube.com/youtubei/v1/";
    private static final String CLIENT_NAME = "ANDROID";
    private static final String CLIENT_VERSION = "20.10.38";
    private static final String USER_AGENT =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 15) gzip";
    private static final String SEARCH_VIDEO_PARAMS = "EgIQAQ%3D%3D";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    /**
     * 元数据响应的上限。{@link #MAX_BYTES} 是按「搜索 / 取流」那种小响应定的，
     * 但电台那一跳实测有 10 MB（Android 客户端的 {@code next} 把整个面板都带回来了），
     * 沿用 4 MB 会让它稳定抛「YouTube 响应过大」。
     */
    private static final int METADATA_MAX_BYTES = 24 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int REQUEST_TIMEOUT_MS = 25_000;
    /**
     * 一段音频的响应头超时。1 MiB 走代理可能要十几秒，比 JSON 那一档宽松。
     *
     * <p>注意这一档只管到**响应头到达**；整个交换（含响应体）的截止在
     * {@link #WINDOW_EXCHANGE_TIMEOUT_MS}，见 {@link #exchange}。</p>
     */
    private static final int STREAM_REQUEST_TIMEOUT_MS = 60_000;
    /**
     * 一次音频交换的截止：响应头 + 响应体读满都算在这一个数里。
     *
     * <p>原来用 {@code send()} + {@code ofInputStream()}，{@code HttpRequest.timeout} 只管到
     * 响应头，body 读满不受它约束 —— 代理中途 stall 就会把调用线程永久挂在 body 队列上
     * （实测换段那次 {@code player} 请求挂在 {@code HttpResponseInputStream.read} 上 388 秒不动），
     * 播放器那边是整条队列卡死：没有超时，也就走不到换源。120 秒够一段 1 MiB 慢慢走完。</p>
     */
    private static final int WINDOW_EXCHANGE_TIMEOUT_MS = 120_000;
    /**
     * 一次音频交换最多收多少字节。窗口本身 1 MiB，留到 32 MiB 是为了容纳
     * 「服务端不理 Range、直接回整首 200」那种情况；再大就宁可失败换源，
     * 也不要把整首之外的东西吸进堆里。
     */
    private static final int AUDIO_EXCHANGE_MAX_BYTES = 32 * 1024 * 1024;

    /**
     * music.youtube.com 的 InnerTube 入口，用于歌词 / 歌单详情 / 账号这三类元数据。
     *
     * <p>与上面那组 Android 常量分档使用，不是「哪档更好」的偏好问题，而是实测出来的分工：
     * 取流只有 ANDROID 匿名给得出 {@code audio/mp4}（同一首视频用 WEB_REMIX 匿名请求
     * 直接 {@code UNPLAYABLE}、0 条格式），而歌词 tab 与歌单曲目只有 music 站这一档才给。
     */
    private static final String MUSIC_API_ENDPOINT = "https://music.youtube.com/youtubei/v1/";
    private static final String MUSIC_CLIENT_NAME = "WEB_REMIX";
    private static final String MOBILE_MUSIC_CLIENT_NAME = "ANDROID_MUSIC";
    private static final String MOBILE_MUSIC_CLIENT_VERSION = "7.21.50";
    private static final String MUSIC_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0";
    /** 电台队列的 browseId 前缀：RDAMVM + videoId = 「以这首为种子的自动电台」。 */
    private static final String RADIO_PREFIX = "RDAMVM";
    private static final String PLAYLIST_PREFIX = "VL";

    /** {@link HttpClient} 构造后不能改代理，因此仅在设置变化时重建。 */
    private static final Object CLIENT_LOCK = new Object();
    private static volatile HttpClient cachedClient;
    private static volatile String cachedProxyEndpoint = "";

    private YouTubeMusicService() {
    }

    public static final class Track {
        public final String videoId;
        public final String title;
        public final String artist;
        public final long durationMillis;
        public final String coverUrl;

        Track(String videoId, String title, String artist, long durationMillis, String coverUrl) {
            this.videoId = videoId;
            this.title = title;
            this.artist = artist;
            this.durationMillis = durationMillis;
            this.coverUrl = coverUrl;
        }
    }

    public static final class ResolveResult {
        public final String url;
        public final String format;
        public final long bitrate;
        /** YouTube 自己的格式编号（140 = 128k AAC），分片重解析时要靠它对齐同一条流。 */
        public final int itag;
        /** 这条流的总字节数；服务端没给时为 0，此时按响应头里的 Content-Range 兜底。 */
        public final long contentLength;

        ResolveResult(String url, String format, long bitrate) {
            this(url, format, bitrate, 0, 0L);
        }

        ResolveResult(String url, String format, long bitrate, int itag, long contentLength) {
            this.url = url;
            this.format = format;
            this.bitrate = bitrate;
            this.itag = itag;
            this.contentLength = contentLength;
        }
    }

    public static List<Track> search(String query, int limit) {
        if (query == null || query.trim().isEmpty()) return List.of();
        int effectiveLimit = Math.max(1, Math.min(50, limit));

        JsonObject body = new JsonObject();
        body.addProperty("query", query.trim());
        body.addProperty("params", SEARCH_VIDEO_PARAMS);
        body.add("context", clientContext());

        try {
            JsonObject response = post("search", body);
            List<JsonObject> renderers = new ArrayList<>();
            collectRenderers(response, "compactVideoRenderer", renderers);
            collectRenderers(response, "videoRenderer", renderers);

            List<Track> tracks = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonObject renderer : renderers) {
                String videoId = string(renderer, "videoId");
                if (videoId.isEmpty() || !seen.add(videoId)) continue;
                Track track = parseTrack(renderer);
                if (track != null) tracks.add(track);
                if (tracks.size() >= effectiveLimit) break;
            }
            return tracks;
        } catch (Throwable throwable) {
            System.err.println("[Music/YouTube] Search failed for " + query.trim() + ": "
                    + describe(throwable));
            return List.of();
        }
    }

    public static ResolveResult getSongUrl(String videoId, Quality requestedQuality) {
        if (videoId == null || videoId.trim().isEmpty()) return null;

        try {
            JsonObject response = playerResponse(videoId);
            JsonObject streamingData = object(response, "streamingData");
            JsonArray formats = array(streamingData, "adaptiveFormats");
            if (formats == null) formats = array(streamingData, "formats");
            if (formats == null) return null;

            JsonObject selected = selectAudioFormat(formats, requestedQuality);
            if (selected == null) return null;
            return toResolveResult(selected);
        } catch (Throwable throwable) {
            System.err.println("[Music/YouTube] Song URL failed for " + videoId + ": "
                    + describe(throwable));
            return null;
        }
    }

    /** 一次 {@code player} 调用。播放状态不是 OK 时抛出，把服务端给的原因带出去。 */
    private static JsonObject playerResponse(String videoId) throws IOException, InterruptedException {
        JsonObject playbackContext = new JsonObject();
        JsonObject contentPlaybackContext = new JsonObject();
        contentPlaybackContext.addProperty("signatureTimestamp", signatureTimestamp());
        playbackContext.add("contentPlaybackContext", contentPlaybackContext);

        JsonObject body = new JsonObject();
        body.addProperty("videoId", videoId.trim());
        body.add("playbackContext", playbackContext);
        body.addProperty("contentCheckOk", true);
        body.addProperty("racyCheckOk", true);
        body.add("context", clientContext());

        JsonObject response = post("player", body, MAX_BYTES);
        JsonObject playability = object(response, "playabilityStatus");
        String status = string(playability, "status");
        if (!"OK".equals(status)) {
            String reason = string(playability, "reason");
            throw new IOException(reason.isEmpty() ? "YouTube 返回 " + status : reason);
        }
        return response;
    }

    private static ResolveResult toResolveResult(JsonObject format) {
        return new ResolveResult(string(format, "url").trim(), "m4a",
                longValue(format, "bitrate"), (int) longValue(format, "itag"),
                longValue(format, "contentLength"));
    }

    /** 按 itag 找一条**新解析**出来的同格式链接；找不到时返回空串。 */
    private static String refreshStreamUrl(String videoId, int itag) {
        try {
            JsonObject streamingData = object(playerResponse(videoId), "streamingData");
            JsonArray formats = array(streamingData, "adaptiveFormats");
            if (formats == null) formats = array(streamingData, "formats");
            if (formats == null) return "";
            for (JsonElement element : formats) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject format = element.getAsJsonObject();
                if (longValue(format, "itag") != itag) continue;
                String url = string(format, "url").trim();
                if (!url.isEmpty()) return url;
            }
        } catch (Throwable throwable) {
            System.err.println("[Music/YouTube] Stream refresh failed for " + videoId
                    + " itag " + itag + ": " + describe(throwable));
        }
        return "";
    }

    // ════════════════════ 分片读流：配额是一台出口 IP 的字节余额 ════════════════════

    /**
     * 一次请求最多要这么多字节。
     *
     * <p>这是「单发请求的上限」，不是「一定给这么多」：余额够时 1 MiB 整给满（206）、多一字节就
     * 回 403；余额不足时同一条链接要 1 MiB 也回 403，而 64 KiB、256 KiB 照样 206 —— 这一发是
     * 2026-09-23 在一个从没请求过的视频上量到的。所以真正要面对的是「余额」，光把整首切成
     * 1 MiB 一段并不总走得动，见 {@link #WINDOW_LADDER}。</p>
     */
    public static final int STREAM_WINDOW_BYTES = 1024 * 1024;

    /**
     * 被拒之后往下退的档位：一次要不满，就退而求其次要小的那截。
     *
     * <p>配额是按出口 IP 攒出来的（实测约每 60～70 秒攒够 1 MiB），刚点下一首时余额常常只有
     * 几百 KB；死磕 1 MiB 会连着吃几发 403、白等一分钟，而缩到 256 KiB / 64 KiB 就能立刻把
     * 已经攒下的那部分取走，进度条也就一直在动。拿到一段之后下一段重新从最大档试起。</p>
     */
    static final int[] WINDOW_LADDER = {1024 * 1024, 256 * 1024, 64 * 1024};

    /** 这一档该要多少字节；档位走完就停在最小档，不再往下缩。 */
    static int windowSpanFor(int step) {
        return WINDOW_LADDER[Math.min(Math.max(step, 0), WINDOW_LADDER.length - 1)];
    }

    /**
     * 这一段要到哪个字节为止（闭区间右端）：既不越过总长度，也不越过当前档位。
     *
     * <p>单独拆成纯函数是为了让「最后一段要短一截」「降档不能越过总长」这两件事能被静态测试
     * 直接断言 —— 这一处写错过一次，就会变成每段都多发一字节、然后每发都吃 403。</p>
     */
    static long windowHighFor(long offset, long total, int step) {
        long span = windowSpanFor(step);
        long end = total > 0 ? Math.min(offset + span, total) : offset + span;
        return Math.max(offset, end - 1L);
    }

    /**
     * 窗口被拒后隔多久再要下一段。
     *
     * <p>itag 140 = 130 kbps ≈ 16.3 KB/s，而每 60～70 秒放行 1 MiB —— 正好是「一秒给一秒的
     * 播放量」。所以等待步长取 20 秒，够余额挪一小截又不至于白等。</p>
     */
    static final long WINDOW_RETRY_DELAY_MS = 20_000L;
    /** 单段最多试几发（含降档那几发），乘上步长就是单段的等待上限。 */
    static final int MAX_WINDOW_WAITS = 6;
    /**
     * 整首的累计等待上限。超过它就不如让 {@code CrossSourceSwitcher} 换到别的源：
     * 用户已经点下播放，五分钟以上才出声音不算能用。
     */
    static final long MAX_STREAM_WAIT_MS = 4 * 60_000L;
    /**
     * 最多分多少段。挡住的是 {@code contentLength} 异常时的死循环，不是配额：
     * 降档之后一段可以只有 64 KiB，一首 5 MB 的歌就要八十来段，所以这个上限要放宽到几百段。
     */
    static final int MAX_STREAM_WINDOWS = 512;

    /**
     * 选流默认「秒开优先」：先要一段窗口就装得下的那条，装不下才按音质档节奏化取。
     *
     * <p>因为配额是按实时回血的，「选哪条流」在 YouTube 上不只在选音质，还在选等待：
     * 一首四分钟的歌，32 kbps 约 960 KB 一段拿完（点了就响），130 kbps 约 4 MB 要四段
     * （等将近四分钟才出声）。播放器里 YouTube 是备用源，先要能立刻听，再要音质。</p>
     *
     * <p>改成 false 就完全照 {@link Quality} 档选（高码率优先，接受等待）。选表本身以参数
     * 传给 {@code selectAudioFormat}，两条路都有静态测试钉着。</p>
     */
    static final boolean PREFER_INSTANT_STREAM = true;

    /**
     * 打开一条能一直读到文件尾的 YouTube 音频流。
     *
     * <p>播放器那边只交回一条链接（{@code Music#getPlayUrl} 的形状），所以这里从链接上把自己
     * 需要的两样东西读回来：{@code itag}（换链接时要对齐同一条流，混进别的格式字节就废了）与
     * {@code clen}（总字节数，用来算最后一段和停止条件）。</p>
     *
     * <p>每读完一段就重新调一次 {@code player} 换一条新链接：一条链接能给的量由那台出口 IP
     * 的余额决定（见 {@link #WINDOW_LADDER}），换链接不是为了扩容，是为了拿到一条还能读下去的
     * 地址。一首 4～5 MB 的 AAC 就是四五次解析加四五次传输，中间夹着等余额的时间。</p>
     */
    public static InputStream openStream(String videoId, String playUrl) throws IOException {
        String id = videoId == null ? "" : videoId.trim();
        String url = playUrl == null ? "" : playUrl.trim();
        if (id.isEmpty() || url.isEmpty()) throw new IOException("YouTube 取流参数为空");
        int itag = (int) longParam(queryParam(url, "itag"));
        long declared = longParam(queryParam(url, "clen"));
        return new ChunkedAudioStream(id, itag, url, declared);
    }

    /** 用已经解析好的结果开流，省掉一次从 URL 反解 itag/clen。 */
    public static InputStream openStream(String videoId, ResolveResult resolved) throws IOException {
        if (videoId == null || videoId.trim().isEmpty()) throw new IOException("YouTube videoId 为空");
        if (resolved == null || resolved.url == null || resolved.url.trim().isEmpty()) {
            throw new IOException("YouTube 没有解析出音频流");
        }
        int itag = resolved.itag > 0 ? resolved.itag
                : (int) longParam(queryParam(resolved.url, "itag"));
        long declared = resolved.contentLength > 0 ? resolved.contentLength
                : longParam(queryParam(resolved.url, "clen"));
        return new ChunkedAudioStream(videoId.trim(), itag, resolved.url.trim(), declared);
    }

    /** 顺序把一段段窗口接起来，对上层就是一整条流。 */
    private static final class ChunkedAudioStream extends InputStream {
        private final String videoId;
        private final int itag;
        private String url;
        private long total;
        private long offset;
        private long windowLeft;
        private int windows;
        private long waitedMillis;
        /** 当前在这一段的档位上（0 = 最大档），被拒就往下缩。 */
        private int windowStep;
        private InputStream current;

        ChunkedAudioStream(String videoId, int itag, String url, long total) {
            this.videoId = videoId;
            this.itag = itag;
            this.url = url;
            this.total = total;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] buffer, int off, int length) throws IOException {
            if (length == 0) return 0;
            while (true) {
                if (current == null || windowLeft <= 0) {
                    if (!nextWindow()) return -1;
                    continue;
                }
                int wanted = (int) Math.min((long) length, windowLeft);
                int read = current.read(buffer, off, wanted);
                if (read < 0) {
                    // 这一段还没读完服务端就先断了：换下一条链接从当前偏移续，不算失败。
                    closeWindow();
                    continue;
                }
                windowLeft -= read;
                offset += read;
                return read;
            }
        }

        @Override
        public int available() throws IOException {
            return availableTotal(total);
        }

        @Override
        public void close() throws IOException {
            closeWindow();
        }

        /** 开下一段窗口。返回 false 表示已经读到文件尾。 */
        private boolean nextWindow() throws IOException {
            closeWindow();
            if (total > 0 && offset >= total) return false;
            windows++;
            if (windows > MAX_STREAM_WINDOWS) {
                throw new IOException("YouTube 音频分片超过 " + MAX_STREAM_WINDOWS + " 段上限");
            }
            if (windows > 1) {
                String refreshed = refreshStreamUrl(videoId, itag);
                if (refreshed.isEmpty()) throw new IOException("YouTube 换段重新解析失败");
                url = refreshed;
            }
            long high = windowHighFor(offset, total, windowStep);

            IOException last = null;
            for (int attempt = 0; attempt <= MAX_WINDOW_WAITS; attempt++) {
                try {
                    openWindow(high);
                    // 这一段拿到了：下一段重新从最大档试起，反正余额又攒回来一点了。
                    windowStep = 0;
                    return true;
                } catch (IOException rejected) {
                    last = rejected;
                    if (attempt == MAX_WINDOW_WAITS) break;
                    if (windowStep < WINDOW_LADDER.length - 1) {
                        /*
                         * 403 的常见成因是「余额不足这一档」而不是「还没回血」——实测同一个视频上
                         * 1 MiB 被拒而 64 KiB 立刻 206。所以先降档、立刻再发一次，不白等那 20 秒：
                         * 已经攒下的那几百 KB 现在就能取走，进度条也就一直在动。
                         */
                        windowStep++;
                        high = windowHighFor(offset, total, windowStep);
                        continue;
                    }
                    // 已经缩到最小档还不给，那才是在等回血（实测约「一秒给一秒的播放量」）。
                    try {
                        Thread.sleep(WINDOW_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("YouTube 取流被中断", interrupted);
                    }
                    waitedMillis += WINDOW_RETRY_DELAY_MS;
                    if (waitedMillis > MAX_STREAM_WAIT_MS) {
                        throw new IOException("YouTube 字节窗口太慢（已等 " + waitedMillis / 1000
                                + " 秒，读到 " + offset + " 字节），交给跨源换轨", rejected);
                    }
                    String refreshed = refreshStreamUrl(videoId, itag);
                    if (refreshed.isEmpty()) throw rejected;
                    url = refreshed;
                }
            }
            throw last == null ? new IOException("YouTube 音频段打不开") : last;
        }

        private void openWindow(long high) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(STREAM_REQUEST_TIMEOUT_MS))
                    .header("Accept", "*/*")
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=" + offset + "-" + high)
                    .GET().build();
            /*
             * 一整段收进内存再交出去（一段最多 1 MiB）。不流式的原因见
             * WINDOW_EXCHANGE_TIMEOUT_MS 的注释：CDN 与代理会在中途把连接晾着，而这条流是
             * 在播放线程上读的，挂着就等于整首放不出来又不换源。收完再给，windowLeft 也就是
             * 实际拿到的字节数，不用信 Content-Range 说给了多少。
             */
            HttpResponse<byte[]> response = exchange(request, AUDIO_EXCHANGE_MAX_BYTES,
                    WINDOW_EXCHANGE_TIMEOUT_MS);
            int status = response.statusCode();
            if (status != 200 && status != 206) {
                throw new IOException("YouTube 音频段 HTTP " + status + " (offset " + offset + ")");
            }
            byte[] body = response.body() == null ? new byte[0] : response.body();
            String range = response.headers().firstValue("content-range").orElse("");
            long declared = contentRangeTotal(range);
            if (declared > 0) total = declared;
            if (status == 200 && total <= 0) total = offset + body.length;
            if (body.length == 0) throw new IOException("YouTube 音频段是空的 (offset " + offset + ")");
            current = new ByteArrayInputStream(body);
            windowLeft = body.length;
        }

        private void closeWindow() throws IOException {
            windowLeft = 0;
            if (current == null) return;
            InputStream stream = current;
            current = null;
            stream.close();
        }
    }

    /**
     * 进度分母：交回「这条流一共多少字节」，而不是「当前这段窗口缓冲了多少」。
     *
     * <p>{@code WrappedInputStream} 的百分比是 {@code 已读 / in.available()}，而 {@code HttpUtils}
     * 那条链路的 {@code available()} 早就是「响应声明的总长度」（它的注释里写着这个约定被进度
     * 和缓冲区容量依赖）。分片流要是照 {@code InputStream} 的通常语义回当前窗口的缓冲量，进度条
     * 第一口就会算出大于 1 的值并来回跳 —— 一首要等几分钟的歌，这条进度是用户唯一的反馈。</p>
     */
    static int availableTotal(long total) {
        if (total <= 0L) return 0;
        return (int) Math.min(total, (long) Integer.MAX_VALUE);
    }

    /** {@code Content-Range: bytes 0-1048575/4524128} 里的总长度；解析不出时为 0。 */
    static long contentRangeTotal(String value) {
        if (value == null) return 0L;
        int slash = value.lastIndexOf('/');
        if (slash < 0) return 0L;
        return longParam(value.substring(slash + 1).trim());
    }

    /** 同一个头里这一段实际有多少字节；解析不出时为 0。 */
    static long contentRangeLength(String value) {
        if (value == null) return 0L;
        int space = value.indexOf(' ');
        int dash = value.indexOf('-', space);
        int slash = value.lastIndexOf('/');
        if (dash < 0 || slash < dash) return 0L;
        long low = longParam(value.substring(space + 1, dash).trim());
        long high = longParam(value.substring(dash + 1, slash).trim());
        return high >= low ? high - low + 1 : 0L;
    }

    /** 取 URL 查询串里的一个参数；没有时返回空串。 */
    static String queryParam(String url, String key) {
        int question = url.indexOf('?');
        if (question < 0) return "";
        String tail = url.substring(question + 1);
        String needle = key + "=";
        for (String pair : tail.split("&")) {
            if (pair.startsWith(needle)) return pair.substring(needle.length());
        }
        return "";
    }

    /** 只认开头那段数字，别的一律 0 —— 用来读 itag / clen / Content-Range 里的长度。 */
    private static long longParam(String raw) {
        if (raw == null) return 0L;
        long value = 0L;
        boolean seen = false;
        for (int index = 0; index < raw.length(); index++) {
            char c = raw.charAt(index);
            if (c < '0' || c > '9') {
                if (seen) break;
                continue;
            }
            value = value * 10L + (c - '0');
            seen = true;
        }
        return seen ? value : 0L;
    }

    // ════════════════════ 歌单 / 电台 / 歌词 / 账号 ════════════════════

    /** 一次歌单解析的曲目上限：防住几千首的歌单把界面和音频缓存一起拖死。 */
    static final int MAX_TRACKS = 1000;
    /** 翻页保护。InnerTube 每页约 100 条，40 页足够覆盖上限，同时挡住自循环的 continuation。 */
    static final int MAX_PAGES = 40;

    public static final class Playlist {
        public final String id;
        public final String name;
        public final String coverUrl;
        /** 歌单页自己声明的曲目数；拿不到时为 0。 */
        public final int declaredCount;
        public final List<Track> tracks;

        Playlist(String id, String name, String coverUrl, int declaredCount, List<Track> tracks) {
            this.id = id;
            this.name = name;
            this.coverUrl = coverUrl;
            this.declaredCount = declaredCount;
            this.tracks = tracks;
        }
    }

    public static final class Lyrics {
        /** 带时间戳的 LRC 文本；没有逐字数据时为空串。 */
        public final String lrc;
        /** 整段纯文本歌词（没有时间戳那一档）。 */
        public final String plainText;
        /** 例如 "Source: LyricFind"，界面可以用来标注来源。 */
        public final String source;
        public final boolean timed;

        Lyrics(String lrc, String plainText, String source, boolean timed) {
            this.lrc = lrc;
            this.plainText = plainText;
            this.source = source;
            this.timed = timed;
        }

        public boolean isEmpty() {
            return lrc.isEmpty() && plainText.isEmpty();
        }
    }

    public static final class Account {
        public final String name;
        public final String channelHandle;
        public final String avatarUrl;

        Account(String name, String channelHandle, String avatarUrl) {
            this.name = name;
            this.channelHandle = channelHandle;
            this.avatarUrl = avatarUrl;
        }
    }

    public static final class LoginResult {
        public final boolean ok;
        public final String message;
        public final String accountName;

        private LoginResult(boolean ok, String message, String accountName) {
            this.ok = ok;
            this.message = message;
            this.accountName = accountName;
        }

        static LoginResult failure(String message) {
            return new LoginResult(false, message, "");
        }

        static LoginResult success(String accountName) {
            return new LoginResult(true, "", accountName);
        }
    }

    /**
     * 从任意一段分享文案里挑出 YouTube 歌单 id（不带 {@value #PLAYLIST_PREFIX} 前缀）。
     *
     * <p>接受 {@code music.youtube.com/playlist?list=…}、{@code youtube.com/watch?v=…&list=…}、
     * 以及裸的 {@code PL…}/{@code RD…}/{@code VL…} 之类前缀。挑不出返回空串。</p>
     */
    /** 从 "50 首歌曲"、"1,234" 这类文本里取第一个整数；一个数字都没有时返回 0。 */
    static int digitsOf(String value) {
        if (value == null) return 0;
        StringBuilder digits = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c >= '0' && c <= '9') digits.append(c);
            else if (c == ',' && digits.length() > 0) continue;
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException overflow) {
            return Integer.MAX_VALUE;
        }
    }

    static String normalizePlaylistId(String rawInput) {
        String text = rawInput == null ? "" : rawInput.trim();
        if (text.isEmpty()) return "";
        java.util.regex.Matcher query = java.util.regex.Pattern
                .compile("[?&#]list=([A-Za-z0-9_-]{8,})").matcher(text);
        if (query.find()) return stripListPrefix(query.group(1));
        java.util.regex.Matcher bare = java.util.regex.Pattern
                .compile("^(?:VL)?((?:PL|RD|OLAK|UMP|UPLA|FL|AM|VQ)[A-Za-z0-9_-]{6,})$").matcher(text);
        if (bare.find()) return bare.group(1);
        return "";
    }

    static String stripListPrefix(String value) {
        return value.startsWith(PLAYLIST_PREFIX) ? value.substring(PLAYLIST_PREFIX.length()) : value;
    }

    /**
     * 解析歌单详情，含 continuation 翻页。
     *
     * <p>匿名实测可以拿到 100 曲行 + 1 处分页，所以这里必须翻页，
     * 否则超过 100 首的歌单会静默缺尾。</p>
     */
    public static Playlist getPlaylist(String playlistIdOrUrl, int limit) {
        String id = normalizePlaylistId(playlistIdOrUrl);
        if (id.isEmpty()) return new Playlist("", "", "", 0, List.of());
        int cap = limit <= 0 ? MAX_TRACKS : Math.min(MAX_TRACKS, limit);

        List<Track> tracks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String name = "";
        String cover = "";
        int declared = 0;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("browseId", PLAYLIST_PREFIX + id);
            String continuation = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                JsonObject response = continuation == null
                        ? postMusic("browse", body, MUSIC_CLIENT_NAME)
                        : postMusic("browse", continuationBody(continuation), MUSIC_CLIENT_NAME);

                if (name.isEmpty()) {
                    List<JsonObject> headers = new ArrayList<>();
                    collectRenderers(response, "playlistHeaderRenderer", headers);
                    if (!headers.isEmpty()) {
                        JsonObject header = headers.get(0);
                        name = text(header, "title");
                        JsonArray headerThumbs = array(object(header, "thumbnails"), "thumbnails");
                        if (headerThumbs == null) headerThumbs = array(header, "thumbnails");
                        cover = largestThumbnail(headerThumbs);
                        // 歌单页会自己声明总曲目数（"50 首歌曲"）。拿不到就当 0，别拿一个
                        // 猜出来的数去撑界面——那会让翻页中途的真实条数看起来像缺页。
                        declared = digitsOf(text(header, "songsCount"));
                    }
                }

                List<JsonObject> rows = new ArrayList<>();
                collectRenderers(response, "musicResponsiveListItemRenderer", rows);
                for (JsonObject row : rows) {
                    Track track = parseListItem(row);
                    if (track == null || !seen.add(track.videoId)) continue;
                    tracks.add(track);
                    if (tracks.size() >= cap) {
                        return new Playlist(id, name, cover, declared, tracks);
                    }
                }

                continuation = findContinuationToken(response);
                if (continuation == null || continuation.isEmpty()) break;
            }
        } catch (Throwable throwable) {
            System.err.println("[Music/YouTube] Playlist failed for " + id + ": " + describe(throwable));
        }
        return new Playlist(id, name, cover, declared, tracks);
    }

    /**
     * 单首视频的元数据（标题 / 艺人 / 时长 / 封面）。
     *
     * <p>用 {@code player} 那一跳顺手取 {@code videoDetails}，比 {@code next} 小两个数量级
     * ——电台那一跳的响应实测有 10 MB，只为拿一个标题去拉它是浪费。</p>
     */
    public static Track getVideo(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) return null;
        JsonObject body = new JsonObject();
        body.addProperty("videoId", videoId.trim());
        body.add("context", clientContext());
        try {
            JsonObject response = post("player", body, METADATA_MAX_BYTES);
            JsonObject details = object(response, "videoDetails");
            String title = string(details, "title");
            if (title.isEmpty()) return null;
            String cover = largestThumbnail(array(object(details, "thumbnail"), "thumbnails"));
            if (cover.isEmpty()) cover = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
            long seconds = longValue(details, "lengthSeconds");
            return new Track(string(details, "videoId").isEmpty() ? videoId.trim() : string(details, "videoId"),
                    title, string(details, "author"), seconds * 1000L, cover);
        } catch (Throwable throwable) {
            System.err.println("[Music/YouTube] Video info failed for " + videoId + ": " + describe(throwable));
            return null;
        }
    }

    /** 从一个视频/歌单链接里挑出视频 id；挑不出返回空串。 */
    static String normalizeVideoId(String rawInput) {
        String text = rawInput == null ? "" : rawInput.trim();
        if (text.isEmpty()) return "";
        java.util.regex.Matcher query = java.util.regex.Pattern
                .compile("[?&#]v=([A-Za-z0-9_-]{11})").matcher(text);
        if (query.find()) return query.group(1);
        java.util.regex.Matcher shortLink = java.util.regex.Pattern
                .compile("youtu\\.be/([A-Za-z0-9_-]{11})").matcher(text);
        if (shortLink.find()) return shortLink.group(1);
        if (text.matches("[A-Za-z0-9_-]{11}")) return text;
        return "";
    }

    /**
     * 以这一首为种子的自动电台。
     *
     * <p>实测出的分档：这一跳必须用 www + ANDROID 客户端——同一请求换成 music 站那两档，
     * 面板里只有当前这 1 首；用 www + ANDROID 匿名就能拿到 50 首。响应可能到 10 MB，
     * 所以走 {@link #METADATA_MAX_BYTES} 那档上限。</p>
     */
    public static List<Track> getRadio(String videoId, int limit) {
        if (videoId == null || videoId.trim().isEmpty()) return List.of();
        int cap = limit <= 0 ? 50 : Math.min(100, limit);

        JsonObject body = new JsonObject();
        body.addProperty("videoId", videoId.trim());
        body.addProperty("playlistId", RADIO_PREFIX + videoId.trim());
        body.addProperty("enablePersistentPlaylistPanel", true);
        body.addProperty("isAudioOnly", true);
        body.addProperty("tunerSettingValue", "AUTOMIX_SETTING_NORMAL");
        JsonObject config = new JsonObject();
        JsonObject musicConfig = new JsonObject();
        musicConfig.addProperty("hasPersistentPlaylistPanel", true);
        musicConfig.addProperty("musicVideoType", "MUSIC_VIDEO_TYPE_ATV");
        config.add("watchEndpointMusicConfig", musicConfig);
        body.add("watchEndpointMusicSupportedConfigs", config);

        try {
            JsonObject response = post("next", body, METADATA_MAX_BYTES);
            List<JsonObject> rows = new ArrayList<>();
            collectRenderers(response, "playlistPanelVideoRenderer", rows);
            List<Track> tracks = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonObject row : rows) {
                Track track = parseQueueItem(row);
                if (track == null || !seen.add(track.videoId)) continue;
                tracks.add(track);
                if (tracks.size() >= cap) break;
            }
            return tracks;
        } catch (Throwable throwable) {
            System.err.println("[Music/YouTube] Radio failed for " + videoId + ": " + describe(throwable));
            return List.of();
        }
    }

    /**
     * 拿这一首的歌词：优先带毫秒时间戳的逐字歌词，退到整段纯文本。
     *
     * <p>两跳：先 {@code next} 要歌词面板的 browseId，再 {@code browse} 那个 id。
     * 所以两跳都用 {@value #MOBILE_MUSIC_CLIENT_NAME}。</p>
     *
     * <p><b>实测留了个没定性的口子</b>：匿名态下 browseId 每首都有，但面板一律回
     * 「没有歌词」——连参考实现自己测试用的那个 videoId 也一样。所以这条链路要带 Cookie
     * 才有内容可取，界面不要把它当「一定能出词」来许诺。</p>
     */
    public static Lyrics getLyrics(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) return new Lyrics("", "", "", false);
        try {
            JsonObject nextBody = new JsonObject();
            nextBody.addProperty("videoId", videoId.trim());
            nextBody.addProperty("playlistId", RADIO_PREFIX + videoId.trim());
            nextBody.addProperty("enablePersistentPlaylistPanel", true);
            nextBody.addProperty("isAudioOnly", true);
            nextBody.addProperty("tunerSettingValue", "AUTOMIX_SETTING_NORMAL");
            JsonObject next = postMusic("next", nextBody, MOBILE_MUSIC_CLIENT_NAME);

            String browseId = findBrowseIdByPageType(next, "MUSIC_PAGE_TYPE_TRACK_LYRICS");
            if (browseId.isEmpty()) return new Lyrics("", "", "", false);

            JsonObject body = new JsonObject();
            body.addProperty("browseId", browseId);
            JsonObject response = postMusic("browse", body, MOBILE_MUSIC_CLIENT_NAME);

            String lrc = buildLrc(response);
            if (!lrc.isEmpty()) {
                return new Lyrics(lrc, "", readSourceMessage(response), true);
            }

            List<JsonObject> shelves = new ArrayList<>();
            collectRenderers(response, "descriptionShelfRenderer", shelves);
            for (JsonObject shelf : shelves) {
                String plain = text(shelf, "description");
                if (plain.isEmpty()) plain = text(shelf, "text");
                if (!plain.isEmpty()) return new Lyrics("", plain.trim(), readSourceMessage(response), false);
            }
            return new Lyrics("", "", "", false);
        } catch (Throwable throwable) {
            System.err.println("[Music/YouTube] Lyrics failed for " + videoId + ": " + describe(throwable));
            return new Lyrics("", "", "", false);
        }
    }

    /**
     * 验证 Cookie 到底有没有用，并把账号资料落到本地。
     *
     * <p>顺序是「先存再看能不能问出账号」：账号接口没回话时保留 Cookie 并打上过期标记，
     * 因为那很可能只是临时风控，删了用户就得重导一次。</p>
     */
    public static LoginResult loginWithCookie(String rawCookie) {
        if (!YouTubeSessionStore.importCookie(rawCookie)) {
            return LoginResult.failure("没在粘贴的内容里找到登录必需的 __Secure-3PAPISID，"
                    + "请在已登录 music.youtube.com 的浏览器里导出完整 Cookie");
        }
        Account account = getAccountInfo();
        if (account == null || account.name.isEmpty()) {
            YouTubeSessionStore.markReauthRequired();
            return LoginResult.failure("Cookie 已保存，但账号接口没有回话——可能已失效，"
                    + "也可能是这个出口 IP 正在被 YouTube 验证");
        }
        YouTubeSessionStore.markVerified(account.name, account.channelHandle, account.avatarUrl);
        return LoginResult.success(account.name);
    }

    /** 登录态下问一次账号信息；未登录或拿不到时返回 null。 */
    public static Account getAccountInfo() {
        if (!YouTubeSessionStore.isLoggedIn()) return null;
        try {
            JsonObject response = postMusic("account/account_menu", new JsonObject(), MUSIC_CLIENT_NAME);
            List<JsonObject> headers = new ArrayList<>();
            collectRenderers(response, "activeAccountHeaderRenderer", headers);
            if (headers.isEmpty()) return null;
            JsonObject header = headers.get(0);
            String name = text(header, "accountName");
            String handle = text(header, "channelHandle");
            JsonArray photos = array(object(header, "accountPhoto"), "thumbnails");
            return new Account(name, handle, largestThumbnail(photos));
        } catch (Throwable throwable) {
            System.err.println("[Music/YouTube] Account info failed: " + describe(throwable));
            return null;
        }
    }

    // ──────────────────────────── 响应解析小工具 ────────────────────────────

    /**
     * 从一组格式里挑出要播的那条（策略取 {@link #PREFER_INSTANT_STREAM}）。
     *
     * @return 没有可用格式时返回 null
     */
    static JsonObject selectAudioFormat(JsonArray formats, Quality requestedQuality) {
        return selectAudioFormat(formats, requestedQuality, PREFER_INSTANT_STREAM);
    }

    /**
     * 从一组格式里挑出要播的那条。
     *
     * <p>只认 {@code audio/mp4}：宿主管线懂 AAC/M4A，而 YouTube 的 Opus 流装在 WebM 里，
     * 目前解不了（见类注释）。这里单独拆出来是为了能被静态测试直接喂一份
     * {@code adaptiveFormats} 进去断言档位——itag 599 那个缺陷就是这么抓到的。</p>
     *
     * <p>选表策略以参数传入，「秒开优先」与「按音质档选」两条都要能被静态测试钉死，
     * 写法照 {@code CrossSourceSwitcher.candidatePlatforms(platform, vipGate)}。</p>
     */
    static JsonObject selectAudioFormat(JsonArray formats, Quality requestedQuality, boolean instantFirst) {
        if (formats == null) return null;
        boolean preferHigherQuality = requestedQuality != Quality.STANDARD;
        JsonObject selected = null;
        long selectedBitrate = 0L;
        int selectedRank = Integer.MIN_VALUE;
        int selectedWindows = Integer.MIN_VALUE;
        for (JsonElement element : formats) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject format = element.getAsJsonObject();
            String mimeType = string(format, "mimeType").toLowerCase(Locale.ROOT);
            String url = string(format, "url");
            if (!mimeType.startsWith("audio/mp4") || url.trim().isEmpty()) continue;

            long bitrate = longValue(format, "bitrate");
            if (selected == null) {
                selected = format;
                selectedBitrate = bitrate;
                selectedRank = audioQualityRank(format);
                selectedWindows = streamWindows(longValue(format, "contentLength"));
                continue;
            }
            if (instantFirst) {
                /*
                 * 秒开优先：先比「这条流要几段窗口才取完」，段数一样再比码率。googlevideo 的配额
                 * 是按实时回血的（约每 64 秒放行 1 MiB），所以段数就是等待时间 —— 一首 4 分钟的歌，
                 * 32 kbps 那条 960 KB 一段装得下（不用等），130 kbps 那条 4.4 MB 要四段（等四分钟）。
                 * 短到一段就装得下的 130 kbps 会直接赢，这条规则并不牺牲这类流。
                 */
                int windows = streamWindows(longValue(format, "contentLength"));
                if (windows < selectedWindows || (windows == selectedWindows && bitrate > selectedBitrate)) {
                    selected = format;
                    selectedBitrate = bitrate;
                    selectedWindows = windows;
                }
                continue;
            }
            if (preferHigherQuality) {
                if (bitrate >= selectedBitrate) {
                    selected = format;
                    selectedBitrate = bitrate;
                }
                continue;
            }
            int rank = audioQualityRank(format);
            if (rank > selectedRank || (rank == selectedRank && bitrate <= selectedBitrate)) {
                selected = format;
                selectedBitrate = bitrate;
                selectedRank = rank;
            }
        }
        return selected;
    }

    /**
     * 这条流要几段窗口才取得完；一段窗口约等于一分多钟的等待。
     *
     * <p>长度未知时不判成 1 段，否则「没声明 clen」会被当成最省时间的选项插队。</p>
     */
    static int streamWindows(long contentLength) {
        if (contentLength <= 0L) return MAX_STREAM_WINDOWS;
        return (int) Math.min(MAX_STREAM_WINDOWS,
                (contentLength + STREAM_WINDOW_BYTES - 1L) / STREAM_WINDOW_BYTES);
    }

    /**
     * YouTube 自己标的音质档。
     *
     * <p>STANDARD 档以前直接取「audio/mp4 里码率最低的那条」，实测会落到
     * itag 599 = 32 kbps 的 {@code AUDIO_QUALITY_ULTRALOW}——那不是「标准」而是最低档。
     * 现在先按官方标签排，同一档里再取低码率省流量。</p>
     */
    static int audioQualityRank(JsonObject format) {
        String label = string(format, "audioQuality").toUpperCase(Locale.ROOT);
        // ULTRALOW 要在 LOW 之前判：它字面上也含 "LOW"。
        if (label.contains("ULTRAHIGH")) return 4;
        if (label.contains("ULTRALOW")) return 1;
        if (label.contains("MEDIUM")) return 3;
        if (label.contains("LOW")) return 2;
        long bitrate = longValue(format, "bitrate");
        if (bitrate >= 120_000L) return 3;
        if (bitrate >= 60_000L) return 2;
        return 1;
    }

    /** 翻页请求体。continuation token 原样回传即可，客户端档位与首跳保持一致。 */
    private static JsonObject continuationBody(String token) {
        JsonObject body = new JsonObject();
        body.addProperty("continuation", token);
        return body;
    }

    /** 在响应树里找一个可用的 continuation token。 */
    static String findContinuationToken(JsonObject response) {
        List<JsonObject> commands = new ArrayList<>();
        collectRenderers(response, "continuationCommand", commands);
        for (JsonObject command : commands) {
            String request = string(command, "request");
            if (!request.isEmpty() && !request.contains("BROWSE")) continue;
            String token = string(command, "token");
            if (!token.isEmpty()) return token;
        }
        return "";
    }

    /** 沿 browseEndpoint → …ContextMusicConfig → pageType 这条链找指定页面的 browseId。 */
    static String findBrowseIdByPageType(JsonObject root, String pageTypeMarker) {
        List<JsonObject> endpoints = new ArrayList<>();
        collectRenderers(root, "browseEndpoint", endpoints);
        for (JsonObject endpoint : endpoints) {
            List<JsonObject> configs = new ArrayList<>();
            collectRenderers(endpoint, "browseEndpointContextMusicConfig", configs);
            for (JsonObject config : configs) {
                if (!string(config, "pageType").contains(pageTypeMarker)) continue;
                String browseId = string(endpoint, "browseId");
                if (!browseId.isEmpty()) return browseId;
            }
        }
        return "";
    }

    /** 列表行的各列文本（标题 / 艺人 / 时长…），顺序与界面一致。 */
    static List<String> flexColumnTexts(JsonObject row) {
        List<String> out = new ArrayList<>();
        JsonArray columns = array(row, "flexColumns");
        if (columns == null) return out;
        for (JsonElement element : columns) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject renderer = object(element.getAsJsonObject(),
                    "musicResponsiveListItemFlexColumnRenderer");
            // 先只取这一列的第一个 run：InnerTube 会把「艺人 - 专辑」拆成好几个 run，
            // 而 string() 逐段 trim，拼起来就成了「绿洲乐队-主题」这种丢了分隔符的形状。
            String value = firstRunText(renderer);
            if (value.isEmpty()) value = text(renderer, "text");
            if (value.isEmpty()) value = text(renderer, "title");
            if (!value.isEmpty()) out.add(value.trim());
        }
        return out;
    }

    /** 一个 renderer 的 {@code text.runs[0].text}——参考实现里也是这条路径（navigation.RUN_TEXT）。 */
    private static String firstRunText(JsonObject renderer) {
        JsonArray runs = array(object(renderer, "text"), "runs");
        if (runs == null || runs.size() == 0) return "";
        JsonElement first = runs.get(0);
        return first != null && first.isJsonObject() ? string(first.getAsJsonObject(), "text") : "";
    }

    static Track parseListItem(JsonObject row) {
        String videoId = string(object(row, "playlistItemData"), "videoId");
        if (videoId.isEmpty()) {
            List<JsonObject> watch = new ArrayList<>();
            collectRenderers(row, "watchEndpoint", watch);
            for (JsonObject endpoint : watch) {
                videoId = string(endpoint, "videoId");
                if (!videoId.isEmpty()) break;
            }
        }
        if (videoId.isEmpty()) return null;

        List<String> columns = flexColumnTexts(row);
        String title = columns.isEmpty() ? "" : columns.get(0);
        if (title.isEmpty()) return null;

        String artist = "";
        long duration = 0L;
        for (int i = 1; i < columns.size(); i++) {
            String value = columns.get(i);
            long parsed = parseDuration(value);
            if (parsed > 0L && duration == 0L) {
                duration = parsed;
                continue;
            }
            if (artist.isEmpty()) {
                String cleaned = cleanByline(value);
                if (!cleaned.isEmpty() && !cleaned.equals(title)) artist = cleaned;
            }
        }

        List<JsonObject> thumbnailNodes = new ArrayList<>();
        collectRenderers(row, "musicThumbnailRenderer", thumbnailNodes);
        String cover = "";
        for (JsonObject node : thumbnailNodes) {
            cover = largestThumbnail(array(object(node, "thumbnail"), "thumbnails"));
            if (!cover.isEmpty()) break;
        }
        if (cover.isEmpty()) cover = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        return new Track(videoId, title, artist, duration, cover);
    }

    static Track parseQueueItem(JsonObject renderer) {
        String videoId = string(renderer, "videoId");
        String title = text(renderer, "title");
        if (videoId.isEmpty() || title.isEmpty()) return null;
        String artist = text(renderer, "bylineText");
        if (artist.isEmpty()) artist = text(renderer, "shortBylineText");
        long seconds = longValue(renderer, "lengthSeconds");
        long duration = seconds > 0L ? seconds * 1000L : parseDuration(text(renderer, "lengthText"));
        String cover = largestThumbnail(array(object(renderer, "thumbnail"), "thumbnails"));
        if (cover.isEmpty()) cover = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        return new Track(videoId, title, cleanByline(artist), duration, cover);
    }

    /** 酷狗/B 站那套「歌手 - 专辑」后缀在这里同样出现，YouTube 的自动频道还会挂「- 主题」。 */
    static String cleanByline(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return "";
        String[] parts = text.split(" - ");
        String first = parts[0].trim();
        if (first.equalsIgnoreCase("Topic") || first.equals("主题")) return "";
        return first;
    }

    /** 把 timedLyricsData 那一串带毫秒区间的行转成 LRC。 */
    static String buildLrc(JsonObject response) {
        List<JsonElement> blocks = new ArrayList<>();
        collectValues(response, "timedLyricsData", blocks);
        StringBuilder out = new StringBuilder();
        for (JsonElement block : blocks) {
            if (block == null || !block.isJsonArray()) continue;
            for (JsonElement element : block.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject line = element.getAsJsonObject();
                JsonObject cue = object(line, "cueRange");
                long start = longValue(cue, "startTimeMilliseconds");
                String text = string(line, "lyricLine");
                if (text.isEmpty()) continue;
                if (out.length() > 0) out.append('\n');
                out.append('[').append(timestamp(start)).append(']').append(text);
            }
        }
        return out.toString();
    }

    /** 毫秒 → {@code mm:ss.SSS}。YouTube 给的是绝对毫秒，可能超过一小时，分钟位照实进位。 */
    static String timestamp(long millis) {
        long clamped = Math.max(0L, millis);
        long minutes = clamped / 60_000L;
        long seconds = (clamped % 60_000L) / 1000L;
        long thousandths = clamped % 1000L;
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, thousandths);
    }

    private static String readSourceMessage(JsonObject response) {
        List<JsonElement> values = new ArrayList<>();
        collectValues(response, "sourceMessage", values);
        for (JsonElement value : values) {
            String text = readText(value);
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static String readText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) {
            try {
                return value.getAsString().trim();
            } catch (Throwable ignored) {
                return "";
            }
        }
        return "";
    }

    /** {@link #collectRenderers} 只收对象值；数组值（如 timedLyricsData）要用这个。 */
    private static void collectValues(JsonElement element, String key, List<JsonElement> output) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectValues(child, key, output);
            return;
        }
        if (!element.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (key.equals(entry.getKey())) output.add(entry.getValue());
            collectValues(entry.getValue(), key, output);
        }
    }

    private static Track parseTrack(JsonObject renderer) {
        String videoId = string(renderer, "videoId");
        String title = text(renderer, "title");
        if (videoId.isEmpty() || title.isEmpty()) return null;

        String artist = text(renderer, "shortBylineText");
        if (artist.isEmpty()) artist = text(renderer, "longBylineText");
        if (artist.isEmpty()) artist = text(renderer, "ownerText");

        JsonObject thumbnails = object(renderer, "thumbnail");
        JsonArray candidates = array(thumbnails, "thumbnails");
        String cover = largestThumbnail(candidates);
        if (cover.isEmpty()) cover = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

        return new Track(videoId, title, artist, parseDuration(text(renderer, "lengthText")), cover);
    }

    private static JsonObject clientContext() {
        JsonObject client = new JsonObject();
        client.addProperty("clientName", CLIENT_NAME);
        client.addProperty("clientVersion", CLIENT_VERSION);
        client.addProperty("hl", "zh-CN");
        client.addProperty("gl", "US");
        client.addProperty("androidSdkVersion", 35);

        JsonObject user = new JsonObject();
        JsonObject context = new JsonObject();
        context.add("client", client);
        context.add("user", user);

        return context;
    }

    /**
     * YouTube 的 player API 拒绝用旧签名时间戳创建的流。上游
     * Python 实现使用当前 UTC 日期减一，因此保留这条确切规则。
     */
    private static int signatureTimestamp() {
        return (int) TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis()) - 1;
    }

    private static JsonObject post(String endpoint, JsonObject body)
            throws IOException, InterruptedException {
        return post(endpoint, body, MAX_BYTES);
    }

    /** Android 客户端那一档：www 域、匿名、不带 API key。搜索与取流走这里。 */
    private static JsonObject post(String endpoint, JsonObject body, int maxBytes)
            throws IOException, InterruptedException {
        // 缺了 context 的 InnerTube 请求不会被「忽略」，而是直接 HTTP 400 failedPrecondition
        // （Precondition check failed），实测就是电台那一跳踩到的。搜索与取流本来就自带，
        // 所以这里只在没有时补上，不去改动已经跑通的那两类的请求字节。
        if (!body.has("context")) body.add("context", clientContext());
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_ENDPOINT + endpoint + "?prettyPrint=false"))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toString().getBytes(StandardCharsets.UTF_8)))
                .build();
        return send(request, maxBytes);
    }

    /**
     * music 站那一档：域名、客户端、UA、Origin 全部换成 music.youtube.com 的形态，
     * 并在已导入 Cookie 时带上认证三件套。
     *
     * {@code Authorization} 用按分钟算的 {@code SAPISIDHASH}。未登录时这些一概不加——
     * 实测匿名的 {@code browse} 也能拿到歌单详情，只是歌词面板会给空，
     * 所以「有没有 Cookie」只决定能拿到什么，不该决定请求能不能发出去。</p>
     *
     * <p>{@code SOCS=CAI} 那枚 Cookie 与登录无关，作用是跳过欧盟同意页；
     * 没有它时 music.youtube.com 会返回一张同意页而不是 JSON。用户导出的 Cookie 里
     * 通常已经自带一枚 SOCS，所以这里用 setHeader 而不是 header，避免同一个头出现两次。</p>
     */
    private static JsonObject postMusic(String endpoint, JsonObject body, String clientName)
            throws IOException, InterruptedException {
        String version = MOBILE_MUSIC_CLIENT_NAME.equals(clientName)
                ? MOBILE_MUSIC_CLIENT_VERSION : musicClientVersion(clientName);
        if (body.has("context")) body.remove("context");
        body.add("context", musicClientContext(clientName, version));

        String url = MUSIC_API_ENDPOINT + endpoint + "?prettyPrint=false&alt=json"
                + "&key=" + YouTubeSessionStore.INNERTUBE_API_KEY;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", MUSIC_USER_AGENT)
                .setHeader("Origin", YouTubeSessionStore.ORIGIN)
                .setHeader("Cookie", "SOCS=CAI")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toString().getBytes(StandardCharsets.UTF_8)));

        String cookie = YouTubeSessionStore.cookieHeader();
        if (!cookie.isEmpty()) {
            builder.setHeader("Cookie", "SOCS=CAI; " + cookie);
            // Android 系的客户端只认按 www 站签的 SAPISIDHASH：按 music 站算会两跳都 400
            // （INVALID_ARGUMENT，实测）。域名不变，只有摘要的第三段输入变。
            String authorization = YouTubeSessionStore.authorization(
                    MOBILE_MUSIC_CLIENT_NAME.equals(clientName)
                            ? YouTubeSessionStore.MOBILE_ORIGIN : YouTubeSessionStore.ORIGIN);
            if (!authorization.isEmpty()) {
                builder.setHeader("Authorization", authorization);
                builder.setHeader("X-Goog-AuthUser", "0");
            }
        }
        return send(builder.build(), METADATA_MAX_BYTES);
    }

    private static JsonObject send(HttpRequest request, int maxBytes)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = exchange(request, maxBytes, REQUEST_TIMEOUT_MS);
        byte[] raw = response.body() == null ? new byte[0] : response.body();
        String text = new String(raw, StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("YouTube HTTP " + response.statusCode()
                    + (text.isEmpty() ? "" : ": " + text));
        }

        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) throw new IOException("YouTube 返回了非 JSON 对象");
        return parsed.getAsJsonObject();
    }

    /**
     * 发一发请求，等的是**整个交换**完成：响应头与响应体都算在 {@code timeoutMillis} 里。
     *
     * <p>{@code HttpClient.send} 配 {@code BodyHandlers.ofInputStream()} 不是这个意思 ——
     * {@code HttpRequest.timeout} 只管到响应头到达，之后的每一口 body 读都没有期限，代理把连接
     * 晾在半路时调用线程就永久挂住（2026-09-23 实测：换段那次 {@code player} 请求在
     * {@code HttpResponseInputStream.read} 上停了 388 秒，jstack 才看出来）。改成异步发、
     * 整体收，超时就 {@code cancel(true)} 把交换摘掉，让上层的重试与换源逻辑拿到一个异常。</p>
     *
     * <p>响应体最大 {@code maxBytes}，超了直接失败而不是继续吃内存。</p>
     */
    private static HttpResponse<byte[]> exchange(HttpRequest request, int maxBytes, long timeoutMillis)
            throws IOException {
        CompletableFuture<HttpResponse<byte[]>> future =
                httpClient().sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            byte[] body = response.body();
            if (body != null && (long) body.length > maxBytes) {
                throw new IOException("YouTube 响应过大");
            }
            return response;
        } catch (TimeoutException timed) {
            future.cancel(true);
            throw new IOException("YouTube 请求超时 " + timeoutMillis / 1000 + " 秒（含响应体）", timed);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("YouTube 请求被中断", interrupted);
        } catch (ExecutionException failed) {
            future.cancel(true);
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw new IOException("YouTube 请求失败: " + cause, cause);
        }
    }

    private static String musicClientVersion(String clientName) {
        return "1." + new java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(new java.util.Date()) + ".01.00";
    }

    private static JsonObject musicClientContext(String clientName, String clientVersion) {
        JsonObject client = new JsonObject();
        client.addProperty("clientName", clientName);
        client.addProperty("clientVersion", clientVersion);
        client.addProperty("hl", "zh-CN");
        client.addProperty("gl", "US");
        if (MOBILE_MUSIC_CLIENT_NAME.equals(clientName)) client.addProperty("androidSdkVersion", 35);

        JsonObject context = new JsonObject();
        context.add("client", client);
        context.add("user", new JsonObject());
        return context;
    }

    private static HttpClient httpClient() {
        boolean useProxy = YouTubeProxySettings.isEnabled();
        String endpoint = useProxy ? YouTubeProxySettings.getEndpoint() : "";

        HttpClient current = cachedClient;
        if (current != null && endpoint.equals(cachedProxyEndpoint)) return current;

        synchronized (CLIENT_LOCK) {
            current = cachedClient;
            if (current != null && endpoint.equals(cachedProxyEndpoint)) return current;

            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (useProxy) {
                InetSocketAddress address = YouTubeProxySettings.createInetSocketAddress();
                if (address == null) throw new IllegalStateException("Invalid YouTube proxy endpoint");
                builder.proxy(ProxySelector.of(address));
            }
            current = builder.build();
            cachedClient = current;
            cachedProxyEndpoint = endpoint;
            return current;
        }
    }

    private static void collectRenderers(JsonElement element, String key, List<JsonObject> output) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectRenderers(child, key, output);
            return;
        }
        if (!element.isJsonObject()) return;

        JsonObject object = element.getAsJsonObject();
        JsonElement renderer = object.get(key);
        if (renderer != null && renderer.isJsonObject()) output.add(renderer.getAsJsonObject());
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) collectRenderers(entry.getValue(), key, output);
    }

    private static String text(JsonObject parent, String property) {
        JsonObject object = object(parent, property);
        JsonArray runs = array(object, "runs");
        if (runs != null) {
            StringBuilder result = new StringBuilder();
            for (JsonElement element : runs) {
                if (element == null || !element.isJsonObject()) continue;
                result.append(string(element.getAsJsonObject(), "text"));
            }
            return result.toString().trim();
        }
        return string(object, "simpleText");
    }

    private static String largestThumbnail(JsonArray thumbnails) {
        if (thumbnails == null) return "";
        String best = "";
        long bestArea = -1;
        for (JsonElement element : thumbnails) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject thumbnail = element.getAsJsonObject();
            long width = longValue(thumbnail, "width");
            long height = longValue(thumbnail, "height");
            long area = width * height;
            if (area > bestArea) {
                bestArea = area;
                best = string(thumbnail, "url");
            }
        }
        return best;
    }

    private static long parseDuration(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        String[] parts = value.trim().split(":");
        long result = 0L;
        try {
            for (String part : parts) result = result * 60L + Long.parseLong(part.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
        return result * 1000L;
    }

    private static JsonObject object(JsonObject parent, String property) {
        if (parent == null || !parent.has(property)) return new JsonObject();
        JsonElement value = parent.get(property);
        return value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String property) {
        if (parent == null || !parent.has(property)) return null;
        JsonElement value = parent.get(property);
        return value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String string(JsonObject parent, String property) {
        if (parent == null || !parent.has(property)) return "";
        try {
            JsonElement value = parent.get(property);
            return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long longValue(JsonObject parent, String property) {
        if (parent == null || !parent.has(property)) return 0L;
        try {
            return parent.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) return "未知错误";
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message.trim();
    }
}
