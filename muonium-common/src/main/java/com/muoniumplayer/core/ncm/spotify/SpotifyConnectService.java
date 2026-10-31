package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spotify Connect（方案一「客户端控制」）的 Web API 封装：{@code /me/player} 一族端点、
 * 播放状态/设备列表的解析，以及两处使用方（设备面板与 {@link SpotifyConnectMirror}）共享的
 * 最近一次快照缓存。
 *
 * <p><b>端点形状（2026-10 核对官方参考；2026-02 迁移指南确认 Player 一族无改动）</b>：
 * 读是 {@code GET /me/player}（什么都没播时回 {@code 204 No Content}，不是错误）与
 * {@code GET /me/player/devices}；写是 {@code PUT /me/player}（转移设备，JSON 体
 * {@code device_ids + play}）、{@code PUT /me/player/play|pause|seek|volume}、
 * {@code POST /me/player/next|previous}，目标设备一律走 {@code device_id} query 参数。</p>
 *
 * <p><b>权限与 Premium</b>：读要 {@code user-read-playback-state}，写要
 * {@code user-modify-playback-state}（两者已进 {@link SpotifyConfigStore#SCOPES}）。
 * 写端点仅对 <b>Premium</b> 账号生效——{@code /me} 已不再暴露 {@code product} 字段
 * （2026-02 迁移移除），没法预先判断，只能对 403 反应式处理：见 {@link #userMessage}。</p>
 *
 * <p>本类<b>没有线程</b>：所有方法都是「调用即请求」的同步形态，由调用方决定跑在哪个
 * 后台线程上（镜像的轮询线程、面板的 {@code MultiThreadingUtil.runAsync}）。</p>
 */
public final class SpotifyConnectService {

    // ── 模型 ────────────────────────────────────────────────────────────────

    /**
     * 一次 {@code /me/player} 读取的不可变切片。
     *
     * <p>位置的外推在这里做：轮询是 3 秒一拍，直接把 {@code progressMs} 画到进度条上会
     * 三秒一跳；{@link #positionMsNow()} 用「快照位置 + 正在播期间的墙钟」补出逐帧连续的
     * 读数，与 {@code CloudMusic.smoothPositionMs} 的锚定外推恰好衔接。</p>
     */
    public static final class PlaybackState {
        public final String deviceId;
        public final String deviceName;
        public final String deviceType;
        /** 设备音量（0..100）。设备没报时为 -1，别拿它当「静音」。 */
        public final int volumePercent;
        public final boolean supportsVolume;
        public final boolean playing;
        /** 响应里的 {@code progress_ms}；未报为 0。 */
        public final long progressMs;
        /** 收到这份响应那一刻的墙钟（epoch 毫秒），外推的锚点。 */
        public final long capturedAtMillis;
        /** 正在放的曲目；播客/电台等非曲目内容为 null（此时不换「正在播放」）。 */
        public final SpotifyMusicService.Track track;
        /** 曲目总时长；无曲目为 0。 */
        public final long durationMs;

        PlaybackState(String deviceId, String deviceName, String deviceType, int volumePercent,
                      boolean supportsVolume, boolean playing, long progressMs,
                      long capturedAtMillis, SpotifyMusicService.Track track, long durationMs) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.deviceType = deviceType;
            this.volumePercent = volumePercent;
            this.supportsVolume = supportsVolume;
            this.playing = playing;
            this.progressMs = progressMs;
            this.capturedAtMillis = capturedAtMillis;
            this.track = track;
            this.durationMs = durationMs;
        }

        /** 此刻该显示的位置（{@code now = System.currentTimeMillis()}）。 */
        public long positionMsNow() {
            return positionMsAt(System.currentTimeMillis());
        }

        public long positionMsAt(long nowMillis) {
            if (!playing) return progressMs;
            return progressMs + Math.max(0L, nowMillis - capturedAtMillis);
        }

        /** 曲目身份键（与 {@code Music.getStableKey()} 的 id 域一致：spotify track id 本身）。 */
        public String trackKey() {
            return track == null ? "" : "spotify:" + track.id;
        }
    }

    /** {@code /me/player/devices} 里的一台设备。 */
    public static final class Device {
        public final String id;
        public final String name;
        public final String type;
        public final int volumePercent;
        public final boolean supportsVolume;
        public final boolean active;

        Device(String id, String name, String type, int volumePercent,
               boolean supportsVolume, boolean active) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.volumePercent = volumePercent;
            this.supportsVolume = supportsVolume;
            this.active = active;
        }
    }

    // ── 读取 ────────────────────────────────────────────────────────────────

    /**
     * 读当前播放状态。什么都没播（204 或缺 device）返回 null——这是「无状态」，不是错误。
     * 401/429/403 由 {@link SpotifyWebApiClient} 的管线如实抛出。
     */
    public static PlaybackState fetchPlayback() throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("additional_types", "track");
        return parsePlayback(SpotifyWebApiClient.getJson("/me/player", query));
    }

    /** 读设备列表。空列表 = 没有任何 Spotify 设备在线。 */
    public static List<Device> fetchDevices() throws IOException {
        return parseDevices(SpotifyWebApiClient.getJson("/me/player/devices", null));
    }

    /** {@code /me/player} 响应 → {@link PlaybackState}。204 的空体与缺 device 的形状都归为 null。 */
    static PlaybackState parsePlayback(JsonObject payload) {
        JsonObject device = object(payload, "device");
        if (device == null) return null;
        long capturedAt = System.currentTimeMillis();
        JsonObject item = object(payload, "item");
        SpotifyMusicService.Track track = null;
        long durationMs = 0L;
        // 播客/电台的 item 不是 track 形状（没有 artists/album），readTrack 会映射出残行 ——
        // 直接不映射：镜像继续遥控传输，但「正在播放」不换行。
        if (item != null && "track".equals(text(item, "type"))) {
            track = SpotifyMusicService.readTrack(item);
        }
        if (track != null) durationMs = track.durationMillis;
        return new PlaybackState(
                text(device, "id"), text(device, "name"), text(device, "type"),
                intOr(device, "volume_percent", -1),
                bool(device, "supports_volume"),
                bool(payload, "is_playing"),
                intOr(payload, "progress_ms", 0),
                capturedAt, track, durationMs);
    }

    /** {@code /me/player/devices} 响应 → 设备列表。 */
    static List<Device> parseDevices(JsonObject payload) {
        if (payload == null) return Collections.emptyList();
        JsonArray devices = payload.getAsJsonArray("devices");
        if (devices == null) return Collections.emptyList();
        List<Device> out = new ArrayList<>();
        for (JsonElement element : devices) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject device = element.getAsJsonObject();
            String id = text(device, "id");
            if (id.isEmpty()) continue;
            out.add(new Device(id, text(device, "name"), text(device, "type"),
                    intOr(device, "volume_percent", -1), bool(device, "supports_volume"),
                    bool(device, "is_active")));
        }
        return out;
    }

    // ── 命令（全部 Premium 账号才生效；失败如实抛出） ────────────────────────

    /** 把播放转移到某台设备。{@code play} 决定转移过去之后是否接着放。 */
    public static void transfer(String deviceId, boolean play) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add(deviceId == null ? "" : deviceId.trim());
        body.add("device_ids", ids);
        body.addProperty("play", play);
        SpotifyWebApiClient.putJson("/me/player", null, body);
    }

    /** 恢复当前上下文的播放（不指定曲目，Spotify 自己接着上次的地方放）。 */
    public static void resume(String deviceId) throws IOException {
        SpotifyWebApiClient.put("/me/player/play", deviceQuery(deviceId));
    }

    public static void pause(String deviceId) throws IOException {
        SpotifyWebApiClient.put("/me/player/pause", deviceQuery(deviceId));
    }

    public static void next(String deviceId) throws IOException {
        SpotifyWebApiClient.post("/me/player/next", deviceQuery(deviceId));
    }

    public static void previous(String deviceId) throws IOException {
        SpotifyWebApiClient.post("/me/player/previous", deviceQuery(deviceId));
    }

    public static void seek(long positionMs, String deviceId) throws IOException {
        Map<String, String> query = deviceQuery(deviceId);
        query.put("position_ms", String.valueOf(Math.max(0L, positionMs)));
        SpotifyWebApiClient.put("/me/player/seek", query);
    }

    public static void setVolume(int percent, String deviceId) throws IOException {
        Map<String, String> query = deviceQuery(deviceId);
        query.put("volume_percent", String.valueOf(Math.max(0, Math.min(100, percent))));
        SpotifyWebApiClient.put("/me/player/volume", query);
    }

    /** 把一串曲目塞到设备上播放（按传入顺序，Spotify 默认不自动续下一首之外的排队）。 */
    public static void playTracks(String deviceId, List<String> trackUris) throws IOException {
        if (trackUris == null || trackUris.isEmpty()) return;
        JsonObject body = new JsonObject();
        JsonArray uris = new JsonArray();
        for (String uri : trackUris) {
            if (uri != null && !uri.trim().isEmpty()) uris.add(uri.trim());
        }
        body.add("uris", uris);
        SpotifyWebApiClient.putJson("/me/player/play", deviceQuery(deviceId), body);
    }

    private static Map<String, String> deviceQuery(String deviceId) {
        Map<String, String> query = new LinkedHashMap<>();
        if (deviceId != null && !deviceId.trim().isEmpty()) query.put("device_id", deviceId.trim());
        return query;
    }

    // ── 两处使用方共享的最近快照 ──────────────────────────────────────────────

    private static final Object DEVICES_LOCK = new Object();
    private static volatile PlaybackState lastPlayback;
    private static List<Device> lastDevices = Collections.emptyList();
    private static volatile long lastDevicesAtMillis;
    /** 设备列表的缓存期：设备上下线不频繁，轮询不必每拍都问一遍。 */
    private static final long DEVICES_CACHE_MS = 10_000L;
    private static final AtomicBoolean REFRESH_IN_FLIGHT = new AtomicBoolean(false);

    /** 最近一次读到的播放状态（镜像轮询与面板刷新共用一条缓存）。可为 null。 */
    public static PlaybackState lastKnownPlayback() {
        return lastPlayback;
    }

    /** 镜像轮询线程读到一拍后落进共享缓存（与 {@link #refreshAsync} 的写入同一条）。 */
    static void recordPlayback(PlaybackState state) {
        lastPlayback = state;
    }

    public static List<Device> lastKnownDevices() {
        synchronized (DEVICES_LOCK) {
            return new ArrayList<>(lastDevices);
        }
    }

    /**
     * 面板驱动的轻刷新：在飞去重，成功才更新缓存（失败保留旧值）。
     * 回调在后台线程执行，UI 调用方要自己切回主线程。
     */
    public static void refreshAsync(Runnable callback) {
        if (!SpotifyMusicService.isLoggedIn()) {
            lastPlayback = null;
            synchronized (DEVICES_LOCK) {
                lastDevices = Collections.emptyList();
            }
            if (callback != null) callback.run();
            return;
        }
        if (!REFRESH_IN_FLIGHT.compareAndSet(false, true)) {
            if (callback != null) callback.run();
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                PlaybackState state = fetchPlayback();
                if (state != null) lastPlayback = state;
                long now = System.currentTimeMillis();
                if (now - lastDevicesAtMillis >= DEVICES_CACHE_MS) {
                    try {
                        List<Device> devices = fetchDevices();
                        synchronized (DEVICES_LOCK) {
                            lastDevices = devices;
                        }
                        lastDevicesAtMillis = now;
                    } catch (IOException | SpotifyWebApiClient.SpotifyApiException ignored) {
                        // 设备列表读失败不动旧值：面板还能显示上一次的列表。
                    }
                }
            } catch (IOException | SpotifyWebApiClient.SpotifyApiException throwable) {
                System.err.println("[Music/Spotify] 播放状态刷新失败: " + throwable.getMessage());
            } finally {
                REFRESH_IN_FLIGHT.set(false);
                if (callback != null) {
                    try {
                        callback.run();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "Spotify Connect Refresh");
        thread.setDaemon(true);
        thread.start();
    }

    // ── 错误的用户话术（面板状态行与镜像通知共用） ────────────────────────────

    /** 把一次失败翻成给玩家看的一句话。空串表示「不值得打扰玩家」。 */
    public static String userMessage(Throwable throwable) {
        if (throwable instanceof SpotifyWebApiClient.SpotifyApiException) {
            SpotifyWebApiClient.SpotifyApiException api =
                    (SpotifyWebApiClient.SpotifyApiException) throwable;
            if (api.reauthRequired) return "Spotify 授权已失效，请重新授权";
            if (api.statusCode == 403) {
                String reason = api.reason == null ? "" : api.reason;
                if (reason.toLowerCase().contains("premium")) {
                    return "需要 Spotify Premium 才能远程控制播放";
                }
                if (reason.toLowerCase().contains("device does not support")
                        || reason.toLowerCase().contains("restrict")) {
                    return "这台设备不支持这条控制命令";
                }
                return "Spotify 拒绝了控制命令（缺播放权限时请在登录页重新授权）：" + reason;
            }
            if (api.statusCode == 404) return "没有活动的 Spotify 设备可接管";
            return "Spotify 命令失败：" + api.getMessage();
        }
        return "";
    }

    // ── 解析小工具（防御性：Spotify 的字段说没就没，2026-02 就是先例） ─────────

    private static JsonObject object(JsonObject payload, String member) {
        if (payload == null || !payload.has(member) || !payload.get(member).isJsonObject()) return null;
        return payload.getAsJsonObject(member);
    }

    private static String text(JsonObject object, String member) {
        try {
            if (object == null || !object.has(member) || object.get(member).isJsonNull()) return "";
            return object.get(member).getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean bool(JsonObject object, String member) {
        try {
            return object != null && object.has(member) && object.get(member).isJsonPrimitive()
                    && object.get(member).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int intOr(JsonObject object, String member, int fallback) {
        try {
            if (object == null || !object.has(member) || !object.get(member).isJsonPrimitive()) {
                return fallback;
            }
            return object.get(member).getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
