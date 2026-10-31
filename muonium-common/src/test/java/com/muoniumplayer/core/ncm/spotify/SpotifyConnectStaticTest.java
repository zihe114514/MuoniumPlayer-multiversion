package com.muoniumplayer.core.ncm.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spotify Connect（客户端控制）的静测：解析形状、命令请求形状、scope 判定与退避节奏。
 * 可编程假 transport，零网络零磁盘——所有「2026 迁移后 Spotify 会不会改字段」类 paranoia
 * 都钉在这里，改坏一次就红一次。
 */
class SpotifyConnectStaticTest {

    /** 可编程 transport：记录每次收到的请求，按序弹脚本响应；脚本耗尽时一律回 204。 */
    private static final class FakeTransport implements SpotifyWebApiClient.Transport {
        final List<SpotifyWebApiClient.Request> seen =
                Collections.synchronizedList(new ArrayList<>());
        final List<SpotifyWebApiClient.Response> script = new ArrayList<>();

        @Override
        public SpotifyWebApiClient.Response send(SpotifyWebApiClient.Request request) {
            seen.add(request);
            synchronized (script) {
                if (script.isEmpty()) return new SpotifyWebApiClient.Response(204, -1, "");
                return script.remove(0);
            }
        }

        void enqueue(int code, String body) {
            script.add(new SpotifyWebApiClient.Response(code, -1, body));
        }

        List<SpotifyWebApiClient.Request> apiCalls() {
            List<SpotifyWebApiClient.Request> out = new ArrayList<>();
            for (SpotifyWebApiClient.Request request : seen) {
                if (request.url.contains("/api/token")) continue;
                out.add(request);
            }
            return out;
        }
    }

    @AfterEach
    void tearDown() {
        SpotifyWebApiClient.resetTransportForTest();
        SpotifyAuthStore.clearForTest();
        SpotifyConfigStore.clearForTest();
    }

    private void login() {
        SpotifyConfigStore.putForTest("test-client-id", "US");
        SpotifyAuthStore.putForTest("fresh-access", "rt-original",
                System.currentTimeMillis() + 60 * 60 * 1000L);
    }

    /** 走 saveTokenResponse 灌一份带 scope 的登录态（putForTest 不覆盖 scope 字段）。 */
    private void loginWithScope(String scope) {
        SpotifyConfigStore.putForTest("test-client-id", "US");
        SpotifyAuthStore.putForTest("", "seed", 0L);
        JsonObject payload = new JsonObject();
        payload.addProperty("access_token", "fresh-access");
        payload.addProperty("refresh_token", "rt-original");
        payload.addProperty("expires_in", 3600L);
        payload.addProperty("scope", scope);
        SpotifyAuthStore.saveTokenResponse(payload);
    }

    // ── 播放状态解析 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("parsePlayback：device/progress/is_playing/item 全量解析，外推从快照位置起算")
    void parsePlaybackFullShape() {
        JsonObject payload = JsonParser.parseString(
                "{\"device\":{\"id\":\"d1\",\"name\":\"我的电脑\",\"type\":\"Computer\","
                        + "\"volume_percent\":62,\"supports_volume\":true},"
                        + "\"progress_ms\":84000,\"is_playing\":true,"
                        + "\"item\":{\"type\":\"track\",\"id\":\"abc\",\"name\":\"Song\","
                        + "\"duration_ms\":200000,"
                        + "\"artists\":[{\"name\":\"A1\"},{\"name\":\"A2\"}],"
                        + "\"album\":{\"name\":\"Album\",\"id\":\"al1\","
                        + "\"images\":[{\"url\":\"http://cover\"}]}}}").getAsJsonObject();

        SpotifyConnectService.PlaybackState state = SpotifyConnectService.parsePlayback(payload);

        assertNotNull(state);
        assertEquals("d1", state.deviceId);
        assertEquals("我的电脑", state.deviceName);
        assertEquals("Computer", state.deviceType);
        assertEquals(62, state.volumePercent);
        assertTrue(state.supportsVolume);
        assertTrue(state.playing);
        assertNotNull(state.track);
        assertEquals("abc", state.track.id);
        assertEquals("Song", state.track.name);
        assertEquals("A1 / A2", state.track.artist);
        assertEquals(200000L, state.track.durationMillis);
        assertEquals("http://cover", state.track.coverUrl);
        assertEquals(200000L, state.durationMs);
        assertEquals("spotify:abc", state.trackKey());
        // 外推：锚点那一拍就是快照位置，向前走就是「快照位置 + 已流逝」。
        assertEquals(84000L, state.positionMsAt(state.capturedAtMillis));
        long later = state.capturedAtMillis + 2_000L;
        assertEquals(86000L, state.positionMsAt(later));
        assertTrue(state.positionMsNow() >= 84000L);
    }

    @Test
    @DisplayName("parsePlayback：暂停时不外推；播客条目不映射成曲目行")
    void parsePlaybackPausedAndNonTrack() {
        SpotifyConnectService.PlaybackState paused = SpotifyConnectService.parsePlayback(
                JsonParser.parseString("{\"device\":{\"id\":\"d1\",\"name\":\"PC\"},"
                        + "\"progress_ms\":5000,\"is_playing\":false,"
                        + "\"item\":{\"type\":\"track\",\"id\":\"t\",\"name\":\"S\","
                        + "\"duration_ms\":10000}}").getAsJsonObject());
        assertNotNull(paused);
        assertEquals(5000L, paused.positionMsAt(paused.capturedAtMillis + 60_000L),
                "暂停时位置必须钉死在快照值上");

        SpotifyConnectService.PlaybackState podcast = SpotifyConnectService.parsePlayback(
                JsonParser.parseString("{\"device\":{\"id\":\"d1\",\"name\":\"PC\"},"
                        + "\"progress_ms\":1000,\"is_playing\":true,"
                        + "\"item\":{\"type\":\"episode\",\"id\":\"e1\",\"name\":\"播客\","
                        + "\"duration_ms\":900000}}").getAsJsonObject());
        assertNotNull(podcast);
        assertNull(podcast.track, "episode 不是曲目：镜像不换「正在播放」行");
        assertEquals(0L, podcast.durationMs);
        assertEquals("", podcast.trackKey());
    }

    @Test
    @DisplayName("parsePlayback：204 的空体与缺 device 的形状都归为 null（不是错误）")
    void parsePlaybackNothingPlaying() {
        assertNull(SpotifyConnectService.parsePlayback(new JsonObject()));
        assertNull(SpotifyConnectService.parsePlayback(null));
    }

    @Test
    @DisplayName("parseDevices：设备列表解析，跳过无 id 的行")
    void parseDevices() {
        List<SpotifyConnectService.Device> devices = SpotifyConnectService.parseDevices(
                JsonParser.parseString("{\"devices\":["
                        + "{\"id\":\"d1\",\"name\":\"PC\",\"type\":\"Computer\","
                        + "\"volume_percent\":40,\"supports_volume\":true,\"is_active\":true},"
                        + "{\"id\":\"\",\"name\":\"幽灵\"},"
                        + "{\"id\":\"d2\",\"name\":\"音箱\",\"type\":\"Speaker\","
                        + "\"supports_volume\":false,\"is_active\":false}]}").getAsJsonObject());
        assertEquals(2, devices.size());
        assertEquals("PC", devices.get(0).name);
        assertTrue(devices.get(0).active);
        assertTrue(devices.get(0).supportsVolume);
        assertFalse(devices.get(1).active);
        assertFalse(devices.get(1).supportsVolume);
        assertEquals(-1, devices.get(1).volumePercent, "没报的音量是「未知」，不是 0");
    }

    // ── 命令请求形状 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("命令形状：transfer 的 PUT + JSON 体、next 的 POST、seek/volume 的 query")
    void commandRequestShapes() throws IOException {
        login();
        FakeTransport transport = new FakeTransport();
        SpotifyWebApiClient.setTransportForTest(transport);

        SpotifyConnectService.transfer("dev1", true);
        SpotifyConnectService.resume("dev1");
        SpotifyConnectService.pause("dev1");
        SpotifyConnectService.next("");
        SpotifyConnectService.previous("");
        SpotifyConnectService.seek(42000L, "");
        SpotifyConnectService.setVolume(50, "dev1");
        SpotifyConnectService.playTracks("dev1",
                java.util.Arrays.asList("spotify:track:a", "spotify:track:b"));

        List<SpotifyWebApiClient.Request> calls = transport.apiCalls();
        assertEquals(8, calls.size());

        SpotifyWebApiClient.Request transfer = calls.get(0);
        assertEquals("PUT", transfer.method);
        assertEquals("https://api.spotify.com/v1/me/player", transfer.url);
        JsonObject body = JsonParser.parseString(transfer.jsonBody).getAsJsonObject();
        assertEquals("dev1", body.getAsJsonArray("device_ids").get(0).getAsString());
        assertTrue(body.get("play").getAsBoolean());

        assertEquals("PUT", calls.get(1).method);
        assertEquals("https://api.spotify.com/v1/me/player/play?device_id=dev1", calls.get(1).url);
        assertNull(calls.get(1).body(), "resume 不需要请求体（空体 PUT，Content-Length: 0）");

        assertEquals("PUT", calls.get(2).method);
        assertEquals("https://api.spotify.com/v1/me/player/pause?device_id=dev1", calls.get(2).url);

        assertEquals("POST", calls.get(3).method);
        assertEquals("https://api.spotify.com/v1/me/player/next", calls.get(3).url,
                "不带 device_id 时让 Spotify 自己路由到活动设备");
        assertEquals("POST", calls.get(4).method);
        assertEquals("https://api.spotify.com/v1/me/player/previous", calls.get(4).url);

        assertEquals("PUT", calls.get(5).method);
        assertEquals("https://api.spotify.com/v1/me/player/seek?position_ms=42000", calls.get(5).url);

        assertEquals("PUT", calls.get(6).method);
        assertEquals("https://api.spotify.com/v1/me/player/volume?device_id=dev1&volume_percent=50",
                calls.get(6).url);

        assertEquals("PUT", calls.get(7).method);
        assertEquals("https://api.spotify.com/v1/me/player/play?device_id=dev1", calls.get(7).url);
        JsonObject uris = JsonParser.parseString(calls.get(7).jsonBody).getAsJsonObject();
        assertEquals("spotify:track:a", uris.getAsJsonArray("uris").get(0).getAsString());
    }

    @Test
    @DisplayName("setVolume 夹到 0..100；seek 的负位置夹到 0")
    void commandClamping() throws IOException {
        login();
        FakeTransport transport = new FakeTransport();
        SpotifyWebApiClient.setTransportForTest(transport);

        SpotifyConnectService.setVolume(250, "d");
        SpotifyConnectService.setVolume(-10, "d");
        SpotifyConnectService.seek(-5L, "d");

        List<SpotifyWebApiClient.Request> calls = transport.apiCalls();
        assertTrue(calls.get(0).url.endsWith("volume_percent=100"));
        assertTrue(calls.get(1).url.endsWith("volume_percent=0"));
        assertTrue(calls.get(2).url.endsWith("position_ms=0"));
    }

    // ── scope 判定 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("hasPlaybackScopes：两条播放 scope 都在才算；空 scope 不算")
    void playbackScopeDetection() {
        loginWithScope("user-read-private user-read-playback-state user-modify-playback-state");
        assertTrue(SpotifyAuthStore.snapshot().hasPlaybackScopes());

        loginWithScope("user-read-private user-read-playback-state");
        assertFalse(SpotifyAuthStore.snapshot().hasPlaybackScopes(),
                "旧授权缺 modify：登录着但差权限，不该伪装成能控制");

        loginWithScope("");
        assertFalse(SpotifyAuthStore.snapshot().hasPlaybackScopes());
    }

    // ── 错误话术与退避 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("userMessage：Premium 403、缺权限 403、重新授权、404 各有一句话")
    void userMessages() {
        assertTrue(SpotifyConnectService.userMessage(
                new SpotifyWebApiClient.SpotifyApiException(403, "Premium required.", false))
                .contains("Premium"));
        String permission = SpotifyConnectService.userMessage(
                new SpotifyWebApiClient.SpotifyApiException(403, "Requires user-modify-playback-state", false));
        assertTrue(permission.contains("重新授权"), "缺权限要说清去重新授权：" + permission);
        assertTrue(SpotifyConnectService.userMessage(
                new SpotifyWebApiClient.SpotifyApiException(0, "REAUTH_REQUIRED", true))
                .contains("重新授权"));
        assertFalse(SpotifyConnectService.userMessage(
                new SpotifyWebApiClient.SpotifyApiException(404, "no active device", false)).isEmpty());
        assertEquals("", SpotifyConnectService.userMessage(new IOException("网络断了")),
                "网络类失败不值得专门弹一句话");
    }

    @Test
    @DisplayName("轮询退避：3s 起步每次翻倍，封顶 30s")
    void pollBackoff() {
        assertEquals(6_000L, SpotifyConnectMirror.nextInterval(3_000L));
        assertEquals(12_000L, SpotifyConnectMirror.nextInterval(6_000L));
        assertEquals(30_000L, SpotifyConnectMirror.nextInterval(30_000L));
        assertEquals(30_000L, SpotifyConnectMirror.nextInterval(60_000L));
        assertEquals(3_000L, SpotifyConnectMirror.nextInterval(0L), "异常的当前值按基准起步");
    }

    // ── 2026 迁移的曲库形状（搜索分页与 /items 端点） ─────────────────────────

    @Test
    @DisplayName("搜索分页：单次请求 limit 恒 ≤10（2026-02 开发模式上限），offset 顺延补齐")
    void searchPaginatesWithSmallPages() throws IOException {
        login();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, tracksPage(10, 0));
        transport.enqueue(200, tracksPage(10, 10));
        SpotifyWebApiClient.setTransportForTest(transport);

        List<SpotifyMusicService.Track> tracks = SpotifyMusicService.search("关键词", 20);

        assertEquals(20, tracks.size());
        List<SpotifyWebApiClient.Request> calls = transport.apiCalls();
        assertEquals(2, calls.size());
        for (SpotifyWebApiClient.Request call : calls) {
            assertTrue(call.url.contains("limit=10"),
                    "任何一次 /search 都不能带 limit>10：" + call.url);
            assertTrue(call.url.contains("type=track"));
        }
        assertTrue(calls.get(0).url.contains("/search?"));
        assertFalse(calls.get(0).url.contains("offset="), "第一页不显式带 offset");
        assertTrue(calls.get(1).url.contains("offset=10"), "第二页从第一页的长度顺延");
    }

    @Test
    @DisplayName("歌单曲目走 /playlists/{id}/items；条目包装键改名 item 时也认得")
    void playlistTracksUsesItemsEndpointAndItemWrapper() throws IOException {
        login();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"items\":[{"
                + "\"item\":{\"type\":\"track\",\"id\":\"t1\",\"name\":\"S1\","
                + "\"duration_ms\":1000,\"artists\":[{\"name\":\"A\"}]}}]}");
        SpotifyWebApiClient.setTransportForTest(transport);

        List<SpotifyMusicService.Track> tracks = SpotifyMusicService.playlistTracks("pl1", 100);

        assertEquals(1, tracks.size());
        assertEquals("S1", tracks.get(0).name);
        assertEquals("/playlists/pl1/items",
                transport.apiCalls().get(0).url.replace("https://api.spotify.com/v1", "")
                        .split("\\?")[0],
                "2026-02 迁移后开发模式必须走 /items 端点");
    }

    @Test
    @DisplayName("歌单曲目：/items 404（扩展配额应用）时回落旧 /tracks 端点")
    void playlistTracksFallsBackToLegacyEndpoint() throws IOException {
        login();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(404, "{\"error\":{\"status\":404,\"message\":\"not found\"}}");
        transport.enqueue(200, "{\"items\":[{"
                + "\"track\":{\"type\":\"track\",\"id\":\"t1\",\"name\":\"旧端点\","
                + "\"duration_ms\":1000,\"artists\":[{\"name\":\"A\"}]}}]}");
        SpotifyWebApiClient.setTransportForTest(transport);

        List<SpotifyMusicService.Track> tracks = SpotifyMusicService.playlistTracks("pl1", 100);

        assertEquals(1, tracks.size());
        assertEquals("旧端点", tracks.get(0).name);
        List<SpotifyWebApiClient.Request> calls = transport.apiCalls();
        assertEquals(2, calls.size());
        assertTrue(calls.get(0).url.contains("/playlists/pl1/items"));
        assertTrue(calls.get(1).url.contains("/playlists/pl1/tracks"), "回落到旧端点");
    }

    @Test
    @DisplayName("歌单卡片的曲目数：tracks.total 与迁移后的 items.total 都认")
    void playlistCardTrackCountBothFieldNames() throws IOException {
        login();
        FakeTransport transport = new FakeTransport();
        transport.enqueue(200, "{\"playlists\":{\"items\":["
                + "{\"id\":\"p1\",\"name\":\"旧字段\",\"tracks\":{\"total\":7},\"images\":[]},"
                + "{\"id\":\"p2\",\"name\":\"新字段\",\"items\":{\"total\":9},\"images\":[]}]}}");
        SpotifyWebApiClient.setTransportForTest(transport);

        List<SpotifyMusicService.Playlist> playlists = SpotifyMusicService.searchPlaylists("词", 10);

        assertEquals(2, playlists.size());
        assertEquals(7, playlists.get(0).trackCount);
        assertEquals(9, playlists.get(1).trackCount, "2026-02 把 tracks 改名 items，两个都读");
    }

    private static String tracksPage(int count, int startIndex) {
        StringBuilder out = new StringBuilder("{\"tracks\":{\"items\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(',');
            out.append("{\"type\":\"track\",\"id\":\"t").append(startIndex + i)
                    .append("\",\"name\":\"S").append(startIndex + i)
                    .append("\",\"duration_ms\":1000,\"artists\":[{\"name\":\"A\"}]}");
        }
        return out.append("]}}").toString();
    }
}
