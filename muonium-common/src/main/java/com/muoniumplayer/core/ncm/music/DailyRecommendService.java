package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.json.JsonUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 主页「每日推荐」大卡的数据：按当前音源取今天的推荐曲目，一天一份、按音源与账号缓存。
 *
 * <p>只用各家已有的每日推荐入口：网易云 {@code /api/v3/discovery/recommend/songs}（需登录）、
 * 酷狗 {@link KuGouMusicService#dailyRecommend()}、QQ {@link NeteaseMusicService#getQQDailySongs()}、
 * 汽水 {@link QishuiMusicService#feedDailyTracks(int)}。其余音源没有对应接口，{@link #peek} 恒为
 * {@code null}，主页就不出这张卡 —— 不拿别的东西冒充「每日推荐」。</p>
 *
 * <p>网络请求只在 {@link #ensureLoaded} 里异步发；主页每帧只调 {@link #peek}，是纯内存读。</p>
 */
public final class DailyRecommendService {

    private static final int QISHUI_LIMIT = 30;

    private static volatile Mix cached;
    private static volatile String loadingKey;

    private DailyRecommendService() {
    }

    /** 一份每日推荐：大卡的标题、副标题与曲目。 */
    public static final class Mix {
        private final String key;
        private final List<Music> tracks;

        private Mix(String key, List<Music> tracks) {
            this.key = key;
            this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
        }

        public List<Music> getTracks() {
            return this.tracks;
        }

        /** 「从「第一首」听起」。 */
        public String title() {
            String first = this.tracks.isEmpty() ? "" : this.tracks.get(0).getName();
            return first == null || first.isEmpty() ? "今天的推荐" : "从「" + first + "」听起";
        }

        public String subtitle() {
            return "根据你的口味精选 · " + this.tracks.size() + " 首";
        }

        /** 大卡封面用第一首的封面。 */
        public Music coverTrack() {
            return this.tracks.isEmpty() ? null : this.tracks.get(0);
        }
    }

    /** 这个音源有没有每日推荐入口（与登录态无关）。 */
    public static boolean supports(MusicPlatform platform) {
        return platform == MusicPlatform.NETEASE || platform == MusicPlatform.KUGOU
                || platform == MusicPlatform.QQ || platform == MusicPlatform.QISHUI;
    }

    /** 今天、当前音源、当前账号已经取到的那一份；还没取到或已过期返回 {@code null}。 */
    public static Mix peek(MusicPlatform platform) {
        Mix mix = cached;
        return mix != null && mix.key.equals(keyFor(platform)) ? mix : null;
    }

    /**
     * 还没有今天的这一份就在后台取一次；取到后在客户端线程回调 {@code onReady}。
     * 同一个键同时只有一次请求在路上。
     */
    public static void ensureLoaded(MusicPlatform platform, Runnable onReady) {
        if (!supports(platform) || !NeteaseMusicService.isLoggedIn(platform)) return;
        final String key = keyFor(platform);
        if (peek(platform) != null || key.equals(loadingKey)) return;
        loadingKey = key;
        MultiThreadingUtil.runAsync(() -> {
            List<Music> tracks = Collections.emptyList();
            try {
                tracks = fetch(platform);
            } catch (Exception failure) {
                System.err.println("[MuoniumPlayer] 每日推荐获取失败（" + platform + "）: " + failure);
            } finally {
                if (key.equals(loadingKey)) loadingKey = null;
            }
            if (tracks == null || tracks.isEmpty()) return;
            cached = new Mix(key, tracks);
            if (onReady != null) MultiThreadingUtil.runOnMainThread(onReady);
        });
    }

    private static String keyFor(MusicPlatform platform) {
        String account = NeteaseMusicService.getAccountName(platform);
        return platform.name() + '|' + (account == null ? "" : account) + '|' + LocalDate.now();
    }

    private static List<Music> fetch(MusicPlatform platform) {
        switch (platform) {
            case NETEASE: return fetchNetease();
            case KUGOU: return KuGouMusicService.dailyRecommend();
            case QQ: return NeteaseMusicService.getQQDailySongs();
            case QISHUI: return QishuiMusicService.feedDailyTracks(QISHUI_LIMIT);
            default: return Collections.emptyList();
        }
    }

    /** 网易云每日推荐：{@code data.dailySongs}，每一项是完整的歌曲对象。 */
    private static List<Music> fetchNetease() {
        JsonObject root = CloudMusicApi.recommendSongs().toJsonObject();
        if (root == null || !root.has("data") || !root.get("data").isJsonObject()) return Collections.emptyList();
        JsonObject data = root.getAsJsonObject("data");
        if (!data.has("dailySongs") || !data.get("dailySongs").isJsonArray()) return Collections.emptyList();
        JsonArray songs = data.getAsJsonArray("dailySongs");
        List<Music> result = new ArrayList<>(songs.size());
        for (JsonElement element : songs) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject song = element.getAsJsonObject();
            Music music = JsonUtils.parse(song, Music.class);
            if (music == null || music.getId() <= 0L) continue;
            music.applyNeteaseMetadata(song, null);
            result.add(music);
        }
        return result;
    }
}
