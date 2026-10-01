package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 哔哩哔哩的「合集（ugc_season）/ 分 P（pages）」歌单链路。
 *
 * <p>B 站没有"歌单"这个对象。README 里 Bilibili 那一行的「歌单搜索 / 歌单歌曲」，实际拿的是
 * 稿件自带的两种结构：一条多 P 稿件的全部分 P，和 UP 主把一个稿件目录打包成的合集。
 * 收藏夹是另一条链路，见 {@link BilibiliFavoriteService}。</p>
 *
 * <p><b>id 形态。</b>{@code PlayList.id} 是 long，而 B 站的主键是字符串，所以真实主键放在
 * {@code platformPlaylistId} 里，用前缀区分三种形态（{@link #PAGES_PREFIX}、{@link #SEASON_PREFIX}、
 * {@link BilibiliFavoriteService#FAV_PREFIX}），{@link #tracks(String)} 按前缀分派。long id 由
 * FNV-1a 从主键推出 —— 必须跨进程稳定，因为歌单封面缓存按它落盘。</p>
 *
 * <p><b>字段兜底。</b>合集接口以缺字段著称：{@code episodes[]} 里的 title/cover/duration 经常是空的，
 * 得回到 {@code arc}/{@code page} 两层，还不够就用 {@code x/space/ugc/season} 拉的归档索引补。
 * 这一串四级兜底是照抄上游实现的现实妥协，不是防御性编程。</p>
 */
public final class BilibiliPlaylistService {

    /** 多 P 稿件：{@code bili-bvid:BV1xx411c7mD}。 */
    public static final String PAGES_PREFIX = "bili-bvid:";
    /** 合集：{@code bili-season:{seasonId}:{mid}:{bvid}}，bvid 用来直接读 view 里的 sections。 */
    public static final String SEASON_PREFIX = "bili-season:";

    private static final String SEASON_API_ROOT = "https://api.bilibili.com/x/space/ugc";
    private static final int SEASON_PAGE_SIZE = 30;
    /** 归档索引最多翻这么多页，防止 total 报得比实际大时死循环。 */
    private static final int SEASON_MAX_PAGES = 20;
    /**
     * 歌单搜索里逐条稿件要各发一次 {@code view} 才知道它有没有合集/多 P。
     * 上限压到 10 条：搜索一次二十次串行请求会明显卡住加载，而 B 站的排序里
     * 前 10 条之外几乎不会再出现带合集的稿件。
     */
    private static final int SEARCH_VIEW_LIMIT = 10;

    private BilibiliPlaylistService() {
    }

    // ════════════════════════════ 歌单列表 ════════════════════════════

    /**
     * 搜索"歌单"：把视频搜索结果逐条展开，凡是带合集或分 P 数 &gt; 1 的才输出成歌单。
     *
     * <p>单 P 稿件被刻意排除 —— 它和搜索结果里那条曲目是同一件事，列出来只会是重复条目。</p>
     */
    public static List<PlayList> searchPlaylists(String keyword, int limit) {
        String query = BilibiliMusicService.nonBlank(keyword);
        if (query.isEmpty()) return List.of();
        int cap = Math.max(1, Math.min(30, limit));

        try {
            List<PlayList> playlists = new ArrayList<>();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            JsonArray results = searchVideoResults(query);
            if (results == null) return List.of();
            int probed = 0;
            for (JsonElement element : results) {
                if (playlists.size() >= cap || probed >= SEARCH_VIEW_LIMIT) break;
                if (element == null || !element.isJsonObject()) continue;
                JsonObject result = element.getAsJsonObject();
                String bvid = BilibiliMusicService.string(result, "bvid");
                if (bvid.isEmpty()) continue;
                probed++;

                JsonObject data = viewData(bvid);
                if (data == null) continue;
                PlayList playlist = playlistOfVideo(bvid, data,
                        BilibiliMusicService.cleanText(BilibiliMusicService.string(result, "title")),
                        BilibiliMusicService.normalizeCover(BilibiliMusicService.string(result, "pic")));
                if (playlist == null || seen.put(playlist.getPlatformPlaylistId(), Boolean.TRUE) != null) {
                    continue;
                }
                playlists.add(playlist);
            }
            return playlists;
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Playlist search failed for " + query + ": "
                    + BilibiliMusicService.describe(throwable));
            return List.of();
        }
    }

    /**
     * 一条稿件能给出的所有歌单：合集优先，其次多 P 全集。
     *
     * <p>和 {@link #searchPlaylists(String, int)} 用同一个构造逻辑，区别只是这里面向
     * 「已知某条稿件」的场景（例如从正在播放的曲目跳回它的合集）。</p>
     */
    public static List<PlayList> playlistsOfVideo(String bvidOrLink) {
        List<PlayList> playlists = new ArrayList<>();
        try {
            // 先归一：粘贴进来的是分享口令或带一堆参数的链接时，直接把原文喂给 view 只会失败。
            String bvid = BilibiliMusicService.canonicalVideoId(bvidOrLink);
            if (bvid.isEmpty()) return playlists;
            JsonObject data = viewData(bvid);
            if (data == null) return playlists;
            String canonical = BilibiliMusicService.firstNonBlank(
                    BilibiliMusicService.string(data, "bvid"), bvid);
            PlayList playlist = playlistOfVideo(canonical, data,
                    BilibiliMusicService.cleanText(BilibiliMusicService.string(data, "title")),
                    BilibiliMusicService.normalizeCover(BilibiliMusicService.string(data, "pic")));
            if (playlist != null) playlists.add(playlist);
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Playlists of video failed for " + bvidOrLink + ": "
                    + BilibiliMusicService.describe(throwable));
        }
        return playlists;
    }

    /** view 响应里一条稿件对应的歌单：有合集就是合集，否则多 P 才算歌单。 */
    private static PlayList playlistOfVideo(String bvid, JsonObject data, String fallbackTitle, String cover) {
        String artist = BilibiliMusicService.cleanText(
                BilibiliMusicService.string(BilibiliMusicService.object(data, "owner"), "name"));
        JsonObject season = BilibiliMusicService.object(data, "ugc_season");
        if (season != null && !season.isJsonNull()) {
            long seasonId = BilibiliMusicService.longValue(season, "id");
            long mid = BilibiliMusicService.longValue(BilibiliMusicService.object(data, "owner"), "mid");
            String seasonTitle = BilibiliMusicService.firstNonBlank(
                    BilibiliMusicService.cleanText(BilibiliMusicService.string(season, "title")), fallbackTitle);
            String seasonCover = BilibiliMusicService.firstNonBlank(
                    BilibiliMusicService.string(season, "cover"), cover);
            int count = countEpisodes(BilibiliMusicService.array(season, "sections"));
            return createPlaylist(seasonPlaylistId(seasonId, mid, bvid), seasonTitle,
                    BilibiliMusicService.normalizeCover(seasonCover), count,
                    BilibiliMusicService.longValue(BilibiliMusicService.object(season, "stat"), "view"),
                    artist, BilibiliMusicService.cleanText(BilibiliMusicService.string(season, "intro")));
        }

        int pages = size(BilibiliMusicService.array(data, "pages"));
        if (pages <= 1) return null;
        return createPlaylist(pagesPlaylistId(bvid), fallbackTitle, cover, pages, 0L, artist, "");
    }

    private static PlayList createPlaylist(String platformId, String name, String cover, int count,
                                           long playCount, String creator, String description) {
        PlayList playlist = new PlayList(stableId(platformId),
                BilibiliMusicService.nonBlank(name), BilibiliMusicService.nonBlank(cover),
                Math.max(0, count), playCount, null, BilibiliMusicService.nonBlank(description), 0L);
        playlist.setPlatform(MusicPlatform.BILIBILI);
        playlist.setPlatformPlaylistId(platformId);
        return playlist;
    }

    // ════════════════════════════ 歌单曲目 ════════════════════════════

    /**
     * 按 {@code platformPlaylistId} 取曲目。
     *
     * <p>不认识的前缀返回 {@code null} 而不是空表：{@code PlayList.queryMusics()} 把
     * {@code null} 当失败处理，空表会被当成「这个歌单确实没有歌」，于是 id 写错时界面上一片
     * 安静，谁都不知道出了什么事。</p>
     */
    public static List<Music> tracks(String platformPlaylistId) {
        String platformId = BilibiliMusicService.nonBlank(platformPlaylistId);
        if (platformId.startsWith(SEASON_PREFIX)) {
            return seasonTracks(platformId.substring(SEASON_PREFIX.length()));
        }
        if (platformId.startsWith(PAGES_PREFIX)) {
            return pageTracks(platformId.substring(PAGES_PREFIX.length()));
        }
        if (platformId.startsWith(BilibiliFavoriteService.FAV_PREFIX)) {
            return BilibiliFavoriteService.tracks(platformId.substring(BilibiliFavoriteService.FAV_PREFIX.length()));
        }
        return null;
    }

    /** 分 P 全集：一次 {@code view} 拿到 pages[]，页码就是数组下标 + 1。 */
    private static List<Music> pageTracks(String bvid) {
        try {
            JsonObject data = viewData(bvid);
            if (data == null) return null;
            String rootTitle = BilibiliMusicService.cleanText(BilibiliMusicService.string(data, "title"));
            String artist = BilibiliMusicService.cleanText(
                    BilibiliMusicService.string(BilibiliMusicService.object(data, "owner"), "name"));
            String cover = BilibiliMusicService.normalizeCover(BilibiliMusicService.string(data, "pic"));
            String canonical = BilibiliMusicService.firstNonBlank(BilibiliMusicService.string(data, "bvid"), bvid);

            JsonArray pages = BilibiliMusicService.array(data, "pages");
            if (pages == null || pages.size() == 0) {
                // 付费/审核中的稿件偶尔会在 view 里省略 pages，pagelist 是独立的端点，还能再试一次。
                JsonObject pageList = BilibiliMusicService.getJson(BilibiliMusicService.PLAYER_API_ROOT
                        + "/pagelist?bvid=" + BilibiliMusicService.urlEncode(canonical), false);
                pages = BilibiliMusicService.array(pageList, "data");
            }
            if (pages == null || pages.size() == 0) return null;

            List<Music> tracks = new ArrayList<>(pages.size());
            int index = 1;
            for (JsonElement element : pages) {
                if (element == null || !element.isJsonObject()) {
                    index++;
                    continue;
                }
                JsonObject page = element.getAsJsonObject();
                tracks.add(toMusic(new BilibiliMusicService.Track(canonical, index,
                        displayTitle(rootTitle, BilibiliMusicService.cleanText(
                                BilibiliMusicService.string(page, "part"))),
                        artist, BilibiliMusicService.longValue(page, "duration") * 1000L,
                        cover, BilibiliMusicService.string(data, "aid"),
                        BilibiliMusicService.string(page, "cid"))));
                index++;
            }
            return tracks;
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Page tracks failed for " + bvid + ": "
                    + BilibiliMusicService.describe(throwable));
            return null;
        }
    }

    /**
     * 合集曲目：先读 view 里的 {@code ugc_season.sections[]}，一条都没有才退回
     * {@code x/space/ugc/season} 分页列表。
     *
     * <p>顺序和上游一致：sections 里带着 {@code ep.arc} 与 {@code ep.page} 两层兜底，
     * 比归档索引给的字段更全。</p>
     */
    private static List<Music> seasonTracks(String key) {
        String[] parts = key.split(":", -1);
        long seasonId = parseLong(parts.length > 0 ? parts[0] : "");
        long mid = parseLong(parts.length > 1 ? parts[1] : "");
        String bvid = parts.length > 2 ? parts[2] : "";
        if (seasonId <= 0 || mid <= 0) return null;

        try {
            Map<String, ArchiveMeta> archiveIndex = new LinkedHashMap<>();
            String seasonTitle = "";
            String seasonCover = "";
            List<Music> fromSections = new ArrayList<>();

            JsonObject data = bvid.isEmpty() ? null : viewData(bvid);
            String artist = data == null ? "" : BilibiliMusicService.cleanText(
                    BilibiliMusicService.string(BilibiliMusicService.object(data, "owner"), "name"));
            if (data != null) {
                JsonObject season = BilibiliMusicService.object(data, "ugc_season");
                if (season != null) {
                    seasonTitle = BilibiliMusicService.cleanText(BilibiliMusicService.string(season, "title"));
                    seasonCover = BilibiliMusicService.normalizeCover(BilibiliMusicService.string(season, "cover"));
                    collectSeasonArchives(seasonId, mid, archiveIndex);
                    fromSections.addAll(sectionTracks(season, seasonTitle, seasonCover, artist, archiveIndex));
                }
            }
            if (!fromSections.isEmpty()) return fromSections;

            return pagedSeasonTracks(seasonId, mid);
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Season tracks failed for " + key + ": "
                    + BilibiliMusicService.describe(throwable));
            return null;
        }
    }

    private static List<Music> sectionTracks(JsonObject season, String seasonTitle, String seasonCover,
                                             String artist, Map<String, ArchiveMeta> archiveIndex) {
        List<Music> tracks = new ArrayList<>();
        JsonArray sections = BilibiliMusicService.array(season, "sections");
        if (sections == null) return tracks;
        for (JsonElement sectionElement : sections) {
            if (sectionElement == null || !sectionElement.isJsonObject()) continue;
            JsonArray episodes = BilibiliMusicService.array(sectionElement.getAsJsonObject(), "episodes");
            if (episodes == null) continue;
            for (JsonElement episodeElement : episodes) {
                if (episodeElement == null || !episodeElement.isJsonObject()) continue;
                JsonObject episode = episodeElement.getAsJsonObject();
                String episodeBvid = BilibiliMusicService.string(episode, "bvid");
                if (episodeBvid.isEmpty()) continue;
                String cid = BilibiliMusicService.string(episode, "cid");
                ArchiveMeta meta = archiveIndex.get("bvid:" + episodeBvid);
                if (meta == null && !cid.isEmpty()) meta = archiveIndex.get("cid:" + cid);

                JsonObject arc = BilibiliMusicService.object(episode, "arc");
                JsonObject page = BilibiliMusicService.object(episode, "page");

                // 四级兜底：ep 自身 → ep.arc → ep.page → 归档索引。B 站按层随机缺字段。
                String title = BilibiliMusicService.firstNonBlank(
                        BilibiliMusicService.cleanText(BilibiliMusicService.string(episode, "title")),
                        BilibiliMusicService.cleanText(BilibiliMusicService.string(arc, "title")),
                        BilibiliMusicService.cleanText(BilibiliMusicService.string(page, "part")),
                        meta == null ? "" : meta.title);
                String cover = BilibiliMusicService.firstNonBlank(
                        BilibiliMusicService.string(episode, "cover"),
                        BilibiliMusicService.string(arc, "pic"),
                        seasonCover,
                        meta == null ? "" : meta.cover);
                long duration = BilibiliMusicService.longValue(episode, "duration");
                if (duration <= 0) duration = BilibiliMusicService.longValue(page, "duration");
                if (duration <= 0) duration = BilibiliMusicService.longValue(arc, "duration");
                if (duration <= 0 && meta != null) duration = meta.durationSeconds;

                tracks.add(toMusic(new BilibiliMusicService.Track(episodeBvid, 1,
                        title.isEmpty() ? seasonTitle : title,
                        artist.isEmpty() ? seasonTitle : artist,
                        duration * 1000L,
                        BilibiliMusicService.normalizeCover(cover), "", cid)));
            }
        }
        return tracks;
    }

    /** 合集的独立分页端点：view 里没有 sections、或只要列表时用。 */
    private static List<Music> pagedSeasonTracks(long seasonId, long mid) throws Exception {
        List<Music> tracks = new ArrayList<>();
        for (int pageNum = 1; pageNum <= SEASON_MAX_PAGES; pageNum++) {
            JsonObject response = BilibiliMusicService.getJson(SEASON_API_ROOT
                    + "/season?mid=" + mid + "&season_id=" + seasonId
                    + "&page_num=" + pageNum + "&page_size=" + SEASON_PAGE_SIZE, false);
            BilibiliMusicService.requireSuccessful(response, "space/ugc/season");
            JsonArray archives = BilibiliMusicService.array(BilibiliMusicService.object(response, "data"),
                    "archives");
            if (archives == null || archives.size() == 0) break;
            for (JsonElement element : archives) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject archive = element.getAsJsonObject();
                String bvid = BilibiliMusicService.string(archive, "bvid");
                if (bvid.isEmpty()) continue;
                tracks.add(toMusic(new BilibiliMusicService.Track(bvid, 1,
                        BilibiliMusicService.cleanText(BilibiliMusicService.string(archive, "title")),
                        "", BilibiliMusicService.longValue(archive, "duration") * 1000L,
                        BilibiliMusicService.normalizeCover(BilibiliMusicService.string(archive, "cover")),
                        BilibiliMusicService.string(archive, "aid"),
                        BilibiliMusicService.string(archive, "cid"))));
            }
            if (archives.size() < SEASON_PAGE_SIZE) break;
        }
        return tracks;
    }

    /**
     * 拉合集的归档索引，给 {@code sections[].episodes[]} 里缺字段的那几条补标题/封面/时长。
     *
     * <p>失败时返回已经拿到的部分而不是抛出去：合集列表本身已经在 view 里了，
     * 索引拉不动只是少一层兜底，不该让整个歌单打不开。</p>
     */
    private static void collectSeasonArchives(long seasonId, long mid, Map<String, ArchiveMeta> index) {
        try {
            for (int pageNum = 1; pageNum <= SEASON_MAX_PAGES; pageNum++) {
                JsonObject response = BilibiliMusicService.getJson(SEASON_API_ROOT
                        + "/season?mid=" + mid + "&season_id=" + seasonId
                        + "&page_num=" + pageNum + "&page_size=" + SEASON_PAGE_SIZE, false);
                JsonObject data = BilibiliMusicService.object(response, "data");
                JsonArray archives = BilibiliMusicService.array(data, "archives");
                if (archives == null || archives.size() == 0) return;
                for (JsonElement element : archives) {
                    if (element == null || !element.isJsonObject()) continue;
                    JsonObject archive = element.getAsJsonObject();
                    ArchiveMeta meta = new ArchiveMeta(
                            BilibiliMusicService.cleanText(BilibiliMusicService.string(archive, "title")),
                            BilibiliMusicService.normalizeCover(BilibiliMusicService.string(archive, "cover")),
                            BilibiliMusicService.longValue(archive, "duration"));
                    String bvid = BilibiliMusicService.string(archive, "bvid");
                    if (!bvid.isEmpty()) index.put("bvid:" + bvid, meta);
                    String cid = BilibiliMusicService.string(archive, "cid");
                    if (!cid.isEmpty() && !"0".equals(cid)) index.put("cid:" + cid, meta);
                }
                if (archives.size() < SEASON_PAGE_SIZE) return;
            }
        } catch (Throwable ignored) {
            // 见方法注释。
        }
    }

    // ════════════════════════════ id 编解码 ════════════════════════════

    /** 分 P 歌单的 platformPlaylistId。 */
    public static String pagesPlaylistId(String bvid) {
        return PAGES_PREFIX + BilibiliMusicService.nonBlank(bvid);
    }

    /** 合集歌单的 platformPlaylistId。 */
    public static String seasonPlaylistId(long seasonId, long mid, String bvid) {
        return SEASON_PREFIX + seasonId + ":" + mid + ":" + BilibiliMusicService.nonBlank(bvid);
    }

    /** 这条 platformPlaylistId 是不是本服务认得的形态。 */
    public static boolean supports(String platformPlaylistId) {
        String platformId = BilibiliMusicService.nonBlank(platformPlaylistId);
        return platformId.startsWith(PAGES_PREFIX) || platformId.startsWith(SEASON_PREFIX);
    }

    // ════════════════════════════ 零件 ════════════════════════════

    private static JsonArray searchVideoResults(String keyword) throws Exception {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", "1");
        params.put("page_size", Integer.toString(SEARCH_VIEW_LIMIT * 2));
        JsonObject response = BilibiliMusicService.wbiGet(BilibiliMusicService.API_ROOT,
                "/wbi/search/type", params, false);
        BilibiliMusicService.requireSuccessful(response, "search");
        return BilibiliMusicService.array(BilibiliMusicService.object(response, "data"), "result");
    }

    private static JsonObject viewData(String bvid) throws Exception {
        JsonObject response = BilibiliMusicService.getJson(BilibiliMusicService.API_ROOT + "/view?"
                + BilibiliMusicService.viewIdQuery(bvid), false);
        BilibiliMusicService.requireSuccessful(response, "view");
        return BilibiliMusicService.object(response, "data");
    }

    private static Music toMusic(BilibiliMusicService.Track track) {
        return Music.fromBilibiliTrack(track);
    }

    /** 分 P 的显示名：「视频标题 - P名」，和搜索结果里那条保持同一形态。 */
    private static String displayTitle(String rootTitle, String part) {
        if (part.isEmpty()) return rootTitle;
        if (part.equals(rootTitle)) return rootTitle;
        return rootTitle + " - " + part;
    }

    private static int countEpisodes(JsonArray sections) {
        if (sections == null) return 0;
        int count = 0;
        for (JsonElement element : sections) {
            if (element == null || !element.isJsonObject()) continue;
            count += size(BilibiliMusicService.array(element.getAsJsonObject(), "episodes"));
        }
        return count;
    }

    private static int size(JsonArray array) {
        return array == null ? 0 : array.size();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(BilibiliMusicService.nonBlank(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * 由平台主键推一个稳定的 long id。
     *
     * <p>用 FNV-1a 而不是 {@code hashCode()}，是因为它要跨进程稳定（歌单封面缓存按这个 id 落盘）。
     * 前缀写死 {@code bilibili:playlist:}，于是同一个主键在酷狗那边推出来的 id 不会撞车。</p>
     */
    static long stableId(String platformId) {
        byte[] bytes = ("bilibili:playlist:" + BilibiliMusicService.nonBlank(platformId))
                .getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }

    /** 归档索引里的一条兜底资料。 */
    private static final class ArchiveMeta {
        private final String title;
        private final String cover;
        private final long durationSeconds;

        private ArchiveMeta(String title, String cover, long durationSeconds) {
            this.title = title;
            this.cover = cover;
            this.durationSeconds = durationSeconds;
        }
    }
}
