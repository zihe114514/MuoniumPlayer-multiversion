package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.TimeUnit;

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
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int REQUEST_TIMEOUT_MS = 25_000;

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

        ResolveResult(String url, String format, long bitrate) {
            this.url = url;
            this.format = format;
            this.bitrate = bitrate;
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

        try {
            JsonObject response = post("player", body);
            JsonObject playability = object(response, "playabilityStatus");
            String status = string(playability, "status");
            if (!"OK".equals(status)) {
                String reason = string(playability, "reason");
                throw new IllegalStateException(reason.isEmpty() ? "YouTube 返回 " + status : reason);
            }

            JsonObject streamingData = object(response, "streamingData");
            JsonArray formats = array(streamingData, "adaptiveFormats");
            if (formats == null) formats = array(streamingData, "formats");
            if (formats == null) return null;

            boolean preferHigherQuality = requestedQuality != Quality.STANDARD;
            JsonObject selected = null;
            long selectedBitrate = preferHigherQuality ? Long.MIN_VALUE : Long.MAX_VALUE;
            for (JsonElement element : formats) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject format = element.getAsJsonObject();
                String mimeType = string(format, "mimeType").toLowerCase(Locale.ROOT);
                String url = string(format, "url");
                if (!mimeType.startsWith("audio/mp4") || url.trim().isEmpty()) continue;

                long bitrate = longValue(format, "bitrate");
                if (preferHigherQuality ? bitrate >= selectedBitrate : bitrate <= selectedBitrate) {
                    selected = format;
                    selectedBitrate = bitrate;
                }
            }
            if (selected == null) return null;
            return new ResolveResult(string(selected, "url").trim(), "m4a", selectedBitrate);
        } catch (Throwable throwable) {
            System.err.println("[Music/YouTube] Song URL failed for " + videoId + ": "
                    + describe(throwable));
            return null;
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_ENDPOINT + endpoint + "?prettyPrint=false"))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toString().getBytes(StandardCharsets.UTF_8)))
                .build();

        HttpResponse<InputStream> response = httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        String text = new String(readLimited(response.body()), StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("YouTube HTTP " + response.statusCode()
                    + (text.isEmpty() ? "" : ": " + text));
        }

        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) throw new IOException("YouTube 返回了非 JSON 对象");
        return parsed.getAsJsonObject();
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

    private static byte[] readLimited(InputStream input) throws IOException {
        if (input == null) return new byte[0];
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > MAX_BYTES) {
                    throw new IOException("YouTube 响应过大");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
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
