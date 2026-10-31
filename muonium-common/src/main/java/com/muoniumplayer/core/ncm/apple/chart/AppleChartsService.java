package com.muoniumplayer.core.ncm.apple.chart;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.apple.AppleMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.network.HttpUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apple Music 排行榜：营销工具 RSS feed(公开、无需登录)。
 *
 * <p>端点 {@code https://rss.marketingtools.apple.com/api/v2/{sf}/music/most-played/100/songs.json}
 * (备用域名 rss.applemarketingtools.com),返回前 100 首的 id/名称/歌手/封面。
 * itunes.apple.com/lookup 批量补全时长与试听地址(每批 ≤100 id)。</p>
 *
 * <p>与 {@link AppleMusicService} 的关系:榜单只出元数据行,播放仍走该服务的
 * 试听兜底与本地引擎;榜单条目带 {@code chart:<storefront>} 虚拟歌单 id,
 * 曲目懒加载由本类承接(经 AppleMusicService#playlistTracks 分派)。</p>
 */
public final class AppleChartsService {

    public static final String CHART_PLAYLIST_PREFIX = "chart:";

    /** 内置可切换的商店(与 RSS 端点支持的 storefront 对齐)。 */
    public static final List<String> STOREFRONTS = List.of(
            "cn", "us", "jp", "kr", "hk", "tw", "gb", "sg");

    private static final List<String> FEED_HOSTS = List.of(
            "https://rss.marketingtools.apple.com",
            "https://rss.applemarketingtools.com");

    /** 单页榜单条目上限(feed 上限 100)。 */
    public static final int CHART_LIMIT = 100;

    private AppleChartsService() {
    }

    /** 一条榜单行:id/标题/歌手/封面(取 feed 原生字段,补全在 lookup 步)。 */
    public static final class ChartEntry {
        public String id = "";
        public String name = "";
        public String artist = "";
        public String coverUrl = "";
        public String storeUrl = "";
        public int rank;
    }

    /**
     * 拉 storefront 的 Top 100。双域名各试一次,网络失败返回空表(调用方按「无数据」画)。
     */
    public static List<ChartEntry> topChart(String storefront) {
        String sf = normalize(storefront);
        for (String host : FEED_HOSTS) {
            String url = host + "/api/v2/" + sf + "/music/most-played/" + CHART_LIMIT + "/songs.json";
            try {
                String body = HttpUtils.getString(url, null);
                if (body == null || body.isEmpty()) continue;
                return parseFeed(body);
            } catch (IOException | RuntimeException ignored) {
                // 换备用域名重试
            }
        }
        return Collections.emptyList();
    }

    /** itunes lookup 的补全结果:试听地址 + 时长(feed 不带时长,列表里的 00:00 靠它填)。 */
    public static final class LookupInfo {
        public final String previewUrl;
        public final long durationMillis;
        LookupInfo(String previewUrl, long durationMillis) {
            this.previewUrl = previewUrl;
            this.durationMillis = durationMillis;
        }
    }

    /** itunes lookup 批量补全:返回 id → 试听地址与时长;查不到的 id 不在表里。 */
    public static Map<String, LookupInfo> lookupEntries(List<ChartEntry> entries) {
        if (entries.isEmpty()) return Collections.emptyMap();
        StringBuilder ids = new StringBuilder();
        for (ChartEntry entry : entries) {
            if (ids.length() > 0) ids.append(',');
            ids.append(entry.id);
        }
        Map<String, LookupInfo> out = new LinkedHashMap<>();
        try {
            String body = HttpUtils.getString("https://itunes.apple.com/lookup?id=" + ids, null);
            if (body == null || body.isEmpty()) return out;
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray results = root.getAsJsonArray("results");
            if (results == null) return out;
            for (JsonElement element : results) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject o = element.getAsJsonObject();
                String id = text(o, "trackId");
                if (id.isEmpty()) continue;
                long duration = 0L;
                if (o.has("trackTimeMillis") && o.get("trackTimeMillis").isJsonPrimitive()) {
                    duration = o.get("trackTimeMillis").getAsLong();
                }
                out.put(id, new LookupInfo(text(o, "previewUrl"), duration));
            }
        } catch (IOException | RuntimeException ignored) {
            // 试听地址与时长缺失不影响榜单展示,由播放链路再兜底
        }
        return out;
    }

    /** feed 缓存:榜单建卡与曲目懒加载共用同一次拉取,TTL 内不重打。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, CachedFeed> FEED_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FEED_TTL_MILLIS = 10 * 60_000L;

    private static final class CachedFeed {
        final List<ChartEntry> entries;
        final long at;
        CachedFeed(List<ChartEntry> entries, long at) {
            this.entries = entries;
            this.at = at;
        }
    }

    /** 带缓存的 feed 拉取;空结果不缓存(瞬时失败不该把整页钉在空白上)。 */
    public static List<ChartEntry> topChartCached(String storefront) {
        String sf = normalize(storefront);
        CachedFeed cached = FEED_CACHE.get(sf);
        if (cached != null && System.currentTimeMillis() - cached.at < FEED_TTL_MILLIS) {
            return cached.entries;
        }
        List<ChartEntry> entries = topChart(sf);
        if (!entries.isEmpty()) {
            FEED_CACHE.put(sf, new CachedFeed(entries, System.currentTimeMillis()));
        }
        return entries;
    }

    /** feed 条目 → 播放器 Music 行(榜单名次进不了缓存键,只进展示;行 id 用 catalog id 稳定键)。 */
    public static List<Music> toMusicRows(String storefront, List<ChartEntry> entries) {
        List<Music> rows = new ArrayList<>(entries.size());
        Map<String, LookupInfo> lookups = lookupEntries(entries);
        for (ChartEntry entry : entries) {
            LookupInfo info = lookups.get(entry.id);
            Music row = Music.fromAppleTrack(toTrack(entry, info));
            if (row != null) rows.add(row);
        }
        return rows;
    }

    /** 虚拟歌单卡片标题(Top 100 · 地区)。 */
    public static String chartTitle(String storefront) {
        return "Top 100 · " + normalize(storefront).toUpperCase(Locale.ROOT);
    }

    /** 曲目懒加载入口:AppleMusicService#playlistTracks 的 chart: 分派落这里。 */
    public static List<Music> chartTracks(String storefront) {
        return toMusicRows(storefront, topChartCached(storefront));
    }

    private static com.muoniumplayer.core.ncm.apple.AppleMusicService.Track toTrack(
            ChartEntry entry, LookupInfo info) {
        com.muoniumplayer.core.ncm.apple.AppleMusicService.Track track =
                new com.muoniumplayer.core.ncm.apple.AppleMusicService.Track();
        track.id = entry.id;
        track.name = entry.name;
        track.artist = entry.artist;
        track.album = "";
        track.albumId = "";
        track.coverUrl = entry.coverUrl;
        track.previewUrl = info == null ? "" : info.previewUrl;
        track.durationMillis = info == null ? 0L : info.durationMillis;
        return track;
    }

    private static List<ChartEntry> parseFeed(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject feed = root.getAsJsonObject("feed");
        if (feed == null) return Collections.emptyList();
        JsonArray results = feed.getAsJsonArray("results");
        if (results == null) return Collections.emptyList();
        List<ChartEntry> out = new ArrayList<>(results.size());
        int rank = 1;
        for (JsonElement element : results) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject o = element.getAsJsonObject();
            ChartEntry entry = new ChartEntry();
            entry.id = text(o, "id");
            entry.name = text(o, "name");
            entry.artist = text(o, "artistName");
            entry.coverUrl = text(o, "artworkUrl100").replace("100x100", "600x600");
            entry.storeUrl = text(o, "url");
            entry.rank = rank++;
            if (!entry.id.isEmpty() && !entry.name.isEmpty()) out.add(entry);
        }
        return out;
    }

    private static String normalize(String storefront) {
        String sf = storefront == null ? "" : storefront.trim().toLowerCase(Locale.ROOT);
        if (sf.length() != 2) return "us";
        boolean known = false;
        for (String known_ : STOREFRONTS) {
            if (known_.equals(sf)) {
                known = true;
                break;
            }
        }
        return known ? sf : "us";
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
