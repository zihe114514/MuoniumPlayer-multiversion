package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.kugou.KuGouMusicApi;
import com.muoniumplayer.core.ncm.kugou.KrcLyrics;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.muoniumplayer.core.utils.json.JsonUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 手动歌词搜索：四源检索候选 + 按候选精确取词。
 *
 * <p>界面层（{@code LyricSearchOverlay}）只认这一个类。四家的接口差异、打分、翻页、限流全在这里
 * 收住，产出的 {@link LyricSearchHit} 是一份「够画一张候选卡片、也够把这条歌词原样取回来」的中性
 * 结构，界面不需要知道酷狗的 {@code accesskey} 是什么。</p>
 *
 * <h3>四个标签页各自搜什么</h3>
 * <p>照 本项目 的 {@code lyricMatchSources.ts} 一字不差：</p>
 * <ul>
 *   <li><b>网易云音乐</b>：{@code /api/cloudsearch/pc} 单曲 50 条，只排序不筛分；</li>
 *   <li><b>AMLLDB</b>：词库本身不接受自由检索词，所以这一档是「拿用户输入去网易云 + QQ 各搜 5 条
 *       → 合并去重 → 只留判得准的（{@code score >= 72} 且时长没否掉）→ 逐个回词库探有没有 TTML
 *       → 探过的上限 4 个 → 留下有货的前 5 条」。这一档给的是<b>逐字</b>歌词，所以它宁可空着；</li>
 *   <li><b>QQ 音乐</b>：{@code search_for_qq_cp}，检索词是「标题 - 歌手 - 专辑」三段式（它的 {@code w}
 *       是整串关键词匹配）；</li>
 *   <li><b>酷狗音乐</b>：走<b>歌曲</b>搜索而不是歌词搜索。歌词接口那些候选只有 {@code id + accesskey}
 *       和时长，摊给人看认不出是哪一首；歌搜能给出专辑与封面，而 {@code hash} 本身就精确锁定某一个
 *       录音版本，取词时按 hash 走，等价于「钉住了这一条」。</li>
 * </ul>
 *
 * <p><b>除了 AMLLDB，其余三档都不按分数过滤。</b>这是有意的：自动链路必须「宁可没有歌词也不给错的」，
 * 所以 {@link LyricMatchScore#MIN_ACCEPT_SCORE} 那道 75 分的门槛在那边是硬规矩；而这里<b>人是判断
 * 者</b>，分数只是卡片右上角那个参考值。把 74 分的候选藏起来，用户想救的那首冷门歌就永远救不回来。</p>
 *
 * <p>所有方法都要在<b>后台线程</b>上调用（界面那一侧统一走 {@code MultiThreadingUtil.runAsync}）：
 * 这里每一个方法都会打网络请求。方法本身不抛异常，失败就是空列表，线程被中断时立刻返回。</p>
 */
public final class LyricSearchService {

    /** 各档的候选条数，与 folia 的 {@code searchSongs(query, 50, 0)} / {@code searchQQLyrics(query)} 对齐。 */
    private static final int NETEASE_LIMIT = 50;
    private static final int QQ_LIMIT = 20;
    private static final int KUGOU_LIMIT = 50;

    private static final int AMLL_SOURCE_LIMIT = 5;
    private static final int AMLL_MAX_PROBES = 4;
    private static final int AMLL_MAX_RESULTS = 5;
    /** AMLL 档的探测门槛，比自动链路的 75 略松：这里探中一条就有逐字歌词，值得多问两次。 */
    private static final int AMLL_MIN_PROBE_SCORE = 72;

    private static final String AMLL_PLATFORM_NETEASE = "ncm";
    private static final String AMLL_PLATFORM_QQ = "qq";
    private static final String AMLL_PARAM_NETEASE = "ncmMusicId";
    private static final String AMLL_PARAM_QQ = "qqMusicId";

    /** 候选卡片与封面占位用的尺寸；网易云的图床按 {@code ?param=} 裁图，QQ 的固定 300。 */
    private static final int COVER_SIZE = 300;

    /** 时长乘数达到这一档才算「基本可以认定是同一个版本」（差 ≤3s，见 {@link LyricMatchScore}）。 */
    private static final double DURATION_MATCHED_MULTIPLIER = 0.95;
    /** 未知时长的乘数档。它不是「时长不符」，AMLL 档的闸门要放它过去。 */
    private static final double DURATION_UNKNOWN_MULTIPLIER = 0.9;

    private LyricSearchService() {
    }

    // ── 目标曲与检索词 ────────────────────────────────────────────────────────

    /** 当前正在放的歌；没在放返回 {@code null}，界面据此决定要不要开这一页。 */
    public static Music currentTrack() {
        return CloudMusic.currentlyPlaying;
    }

    /**
     * 打开弹窗时填进搜索框的默认检索词：{@code 标题 - 歌手 - 专辑}。
     *
     * <p>用户可以在框里改，改完就是他的判断；这一档默认值只是为了「进来就能直接回车」。</p>
     */
    public static String defaultQuery(Music music) {
        if (music == null) return "";
        LyricMatchScore.Target target = targetOf(music);
        return LyricMatchText.buildStructuredQuery(target.title, target.artist, target.album);
    }

    /** 由一首歌得出打分用的那一侧。 */
    static LyricMatchScore.Target targetOf(Music music) {
        if (music == null) return new LyricMatchScore.Target("", "", "", 0L);
        return new LyricMatchScore.Target(safe(music.getName()), artistsOf(music), albumOf(music),
                music.getDuration());
    }

    // ── 检索 ──────────────────────────────────────────────────────────────────

    /**
     * 以<b>当前播放的歌</b>为目标搜一批候选。
     *
     * <p>{@code null} 目标的快捷写法，等价于把 {@link CloudMusic#currentlyPlaying} 交给下面那个重载。
     * 弹窗自己走的是带目标的版本——从歌单某一行打开时，那一行才是目标。</p>
     */
    public static List<LyricSearchHit> search(LyricSearchSource source, String query) {
        return search(source, query, targetOf(currentTrack()));
    }

    /**
     * 以<b>指定的那一首</b>为目标搜一批候选。
     *
     * <p>给「从歌曲列表某一行打开选词弹窗」用：那一行未必是正在放的歌，而候选是不是同一版本
     * 全靠时长与专辑比对打分，目标拿错了整列排序就是错的。传 {@code null} 退回当前播放。</p>
     */
    public static List<LyricSearchHit> search(LyricSearchSource source, String query, Music music) {
        return search(source, query, targetOf(music == null ? currentTrack() : music));
    }

    static List<LyricSearchHit> search(LyricSearchSource source, String query,
                                       LyricMatchScore.Target target) {
        String keyword = safe(query).trim();
        if (source == null || interrupted()) {
            return Collections.emptyList();
        }
        LyricMatchScore.Target goal = target == null ? new LyricMatchScore.Target("", "", "", 0L) : target;
        // AMLL 那一档用的是当前这首歌自己拼出来的检索词，与框里写了什么无关（见下面分支的注释），
        // 所以它不能和其余三档共用「框里空的就不搜」这道闸。
        if (source != LyricSearchSource.AMLL && keyword.isEmpty()) {
            return Collections.emptyList();
        }

        List<LyricSearchHit> hits;
        switch (source) {
            case QQ:
                hits = searchQQ(keyword, goal);
                break;
            case KUGOU:
                hits = searchKuGou(keyword, goal);
                break;
            case AMLL:
                hits = searchAmll(goal);
                break;
            case NETEASE:
            default:
                hits = searchNetease(keyword, goal);
                break;
        }
        sortByScoreDesc(hits);
        return hits;
    }

    /** 网易云：云搜索的候选对象里就带着取词要的一切（{@code id} 直接喂 {@code /api/song/lyric/v1}）。 */
    private static List<LyricSearchHit> searchNetease(String keyword, LyricMatchScore.Target target) {
        List<LyricSearchHit> hits = new ArrayList<LyricSearchHit>();
        JsonArray songs = neteaseSongs(keyword);
        if (songs == null) return hits;

        for (int i = 0; i < songs.size() && i < NETEASE_LIMIT; i++) {
            JsonElement element = songs.get(i);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();

            long id = longOr(item, "id");
            if (id <= 0L) continue;

            String title = text(item, "name");
            List<String> artists = artistNames(item);
            JsonObject album = object(item, "al");
            if (album == null) album = object(item, "album");
            String albumName = text(album, "name");
            long duration = longOr(item, "dt");
            if (duration <= 0L) duration = longOr(item, "duration");

            hits.add(new LyricSearchHit(LyricSearchSource.NETEASE, String.valueOf(id), "",
                    title, join(artists, ", "), albumName, duration, coverOf(album),
                    scoreOf(target, title, artists, albumName, duration),
                    durationMatched(target, duration), item));
        }
        return hits;
    }

    /** QQ：候选带 {@code albummid}，封面按图床模板拼出来。 */
    private static List<LyricSearchHit> searchQQ(String keyword, LyricMatchScore.Target target) {
        List<LyricSearchHit> hits = new ArrayList<LyricSearchHit>();
        JsonArray items = QQQrcLyricService.manualCandidates(keyword, QQ_LIMIT);
        if (items == null) return hits;

        for (int i = 0; i < items.size(); i++) {
            JsonElement element = items.get(i);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();

            long songId = longOr(item, "songid");
            if (songId <= 0L) continue;

            String title = text(item, "songname");
            List<String> artists = qqArtistNames(item);
            String albumName = text(item, "albumname");
            // {@code interval} 是秒。QQ 的候选没给秒以外的时候，缺字段就是 0，按未知时长处理。
            long duration = longOr(item, "interval") * 1000L;
            String albumMid = text(item, "albummid");
            String cover = albumMid.isEmpty() ? ""
                    : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg";

            hits.add(new LyricSearchHit(LyricSearchSource.QQ, String.valueOf(songId), "",
                    title, join(artists, ", "), albumName, duration, cover,
                    scoreOf(target, title, artists, albumName, duration),
                    durationMatched(target, duration), item));
        }
        return hits;
    }

    /** 酷狗：歌搜候选 → {@code hash} 就是这条录音的坐标。 */
    private static List<LyricSearchHit> searchKuGou(String keyword, LyricMatchScore.Target target) {
        List<LyricSearchHit> hits = new ArrayList<LyricSearchHit>();
        List<KuGouMusicApi.Track> tracks;
        try {
            tracks = KuGouMusicApi.search(keyword, KUGOU_LIMIT);
        } catch (Throwable failure) {
            return hits;
        }
        if (tracks == null) return hits;

        for (KuGouMusicApi.Track track : tracks) {
            if (track == null) continue;
            String hash = safe(track.hash).trim().toLowerCase(Locale.ROOT);
            if (hash.isEmpty()) continue;

            List<String> artists = new ArrayList<String>(1);
            artists.add(safe(track.artist));
            String albumName = safe(track.album);

            hits.add(new LyricSearchHit(LyricSearchSource.KUGOU, hash, "", safe(track.name),
                    safe(track.artist), albumName, track.durationMillis,
                    KuGouMusicApi.cover(track.coverTemplate, COVER_SIZE),
                    scoreOf(target, safe(track.name), artists, albumName, track.durationMillis),
                    durationMatched(target, track.durationMillis), payloadOf(track, hash)));
        }
        return hits;
    }

    /**
     * AMLLDB：借网易云与 QQ 的搜索结果当候选，再逐个回词库探有没有条目。
     *
     * <p>探的意思是真的发一次「按平台 id 精确取」的请求，只留下有 TTML 的那几条——这一档给的就是
     * 逐字歌词，探空的条目摆在列表里只会让用户点了以后看到「无可用歌词」。上限 4 次探测，
     * 加上两个上游各 5 条，整档最多 14 次请求，都在词库 25ms 的最小间隔约束下。</p>
     */
    private static List<LyricSearchHit> searchAmll(LyricMatchScore.Target target) {
        List<LyricSearchHit> hits = new ArrayList<LyricSearchHit>();
        if (target == null || LyricMatchText.isBlank(target.title)) return hits;

        List<AmllCandidate> pool = new ArrayList<AmllCandidate>();
        String query = LyricMatchText.buildStructuredQuery(target.title, target.artist, target.album);
        pool.addAll(neteaseAmllCandidates(query, target));
        pool.addAll(qqAmllCandidates(query, target));
        Collections.sort(pool, new Comparator<AmllCandidate>() {
            @Override
            public int compare(AmllCandidate left, AmllCandidate right) {
                return Integer.compare(right.score, left.score);
            }
        });

        Set<String> seen = new HashSet<String>();
        int probes = 0;
        for (AmllCandidate candidate : pool) {
            if (hits.size() >= AMLL_MAX_RESULTS || interrupted()) break;
            if (probes >= AMLL_MAX_PROBES) break;
            if (!seen.add(candidate.key())) continue;
            if (!shouldProbe(candidate, target)) continue;

            probes++;
            JsonObject entry = AmllTtmlLyricService.manualProbe(candidate.param, candidate.trackId);
            if (entry == null) continue;

            String entryId = text(entry, "id");
            hits.add(new LyricSearchHit(LyricSearchSource.AMLL,
                    entryId.isEmpty() ? candidate.trackId : entryId,
                    candidate.platform, candidate.title, candidate.artists, candidate.album,
                    candidate.durationMillis, candidate.coverUrl, candidate.score,
                    candidate.durationMatched(), amllPayload(entry, candidate)));
        }
        return hits;
    }

    /**
     * 值不值得为这条候选发一次探测请求。
     *
     * <p>闸门照 folia 的 {@code shouldProbeAmllDbCandidate}：曲名必须对上，歌手或专辑至少有一个
     * <b>明确</b>对上（缺数据不算对上），分数过线，且时长没有否掉它。差这四条中的一条就省一次请求。</p>
     */
    private static boolean shouldProbe(AmllCandidate candidate, LyricMatchScore.Target target) {
        if (!candidate.titleMatched) return false;
        if (!candidate.artistMatched && !Boolean.TRUE.equals(candidate.albumMatched)) return false;
        return candidate.score >= AMLL_MIN_PROBE_SCORE && candidate.durationMultiplier >= DURATION_UNKNOWN_MULTIPLIER;
    }

    private static List<AmllCandidate> neteaseAmllCandidates(String query, LyricMatchScore.Target target) {
        List<AmllCandidate> result = new ArrayList<AmllCandidate>();
        JsonArray songs = neteaseSongs(query);
        if (songs == null) return result;
        for (int i = 0; i < songs.size() && i < AMLL_SOURCE_LIMIT; i++) {
            JsonElement element = songs.get(i);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            long id = longOr(item, "id");
            if (id <= 0L) continue;

            String title = text(item, "name");
            List<String> artists = artistNames(item);
            JsonObject album = object(item, "al");
            if (album == null) album = object(item, "album");
            long duration = longOr(item, "dt");
            if (duration <= 0L) duration = longOr(item, "duration");

            result.add(new AmllCandidate(AMLL_PLATFORM_NETEASE, AMLL_PARAM_NETEASE, String.valueOf(id),
                    title, join(artists, ", "), text(album, "name"), duration, coverOf(album),
                    evaluate(target, title, artists, text(album, "name"), duration)));
        }
        return result;
    }

    private static List<AmllCandidate> qqAmllCandidates(String query, LyricMatchScore.Target target) {
        List<AmllCandidate> result = new ArrayList<AmllCandidate>();
        JsonArray items = QQQrcLyricService.manualCandidates(query, AMLL_SOURCE_LIMIT);
        if (items == null) return result;
        for (int i = 0; i < items.size() && i < AMLL_SOURCE_LIMIT; i++) {
            JsonElement element = items.get(i);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            long songId = longOr(item, "songid");
            if (songId <= 0L) continue;

            String title = text(item, "songname");
            List<String> artists = qqArtistNames(item);
            String albumName = text(item, "albumname");
            long duration = longOr(item, "interval") * 1000L;
            String albumMid = text(item, "albummid");

            result.add(new AmllCandidate(AMLL_PLATFORM_QQ, AMLL_PARAM_QQ, String.valueOf(songId),
                    title, join(artists, ", "), albumName, duration,
                    albumMid.isEmpty() ? "" : "https://y.gtimg.cn/music/photo_new/T002R300x300M000"
                            + albumMid + ".jpg",
                    evaluate(target, title, artists, albumName, duration)));
        }
        return result;
    }

    // ── 取词 ──────────────────────────────────────────────────────────────────

    /**
     * 取用户挑中这一条的歌词。界面在选中一条后拉预览、「保存」时再拉一次都走它。
     *
     * @return 永不为 {@code null}；取不到返回空列表，由界面显示「无可用歌词 / 重新加载」
     */
    public static List<LyricLine> fetchLyrics(LyricSearchHit hit) {
        if (hit == null) return Collections.emptyList();
        return fetch(hit.source, hit.trackId, hit.platform, hit.payload, hit.durationMillis,
                hit.title, hit.artists);
    }

    /**
     * 按钉住的那一条精确重取。加载链的第 0 级用它。
     *
     * <p>这里<b>不重新搜索</b>：钉住的语义就是「上次用户点的那一条」，重新搜一遍等于把选择权又交回
     * 给排序算法，而排序正是他一开始不满意的东西。</p>
     */
    public static List<LyricLine> fetchLyrics(LyricManualMemory.Pin pin) {
        if (pin == null) return Collections.emptyList();
        return fetch(pin.source, pin.trackId, pin.platform, pin.candidateJson(), pin.durationMillis,
                pin.title, pin.artists);
    }

    private static List<LyricLine> fetch(LyricSearchSource source, String trackId, String platform,
                                         JsonObject payload, long durationMillis, String title,
                                         String artists) {
        if (source == null || interrupted()) return Collections.emptyList();
        try {
            switch (source) {
                case QQ:
                    return QQQrcLyricService.manualResolve(payload);
                case KUGOU:
                    return fetchKuGou(trackId, durationMillis, title, artists);
                case AMLL:
                    return fetchAmll(trackId, payload);
                case NETEASE:
                default:
                    return fetchNetease(trackId);
            }
        } catch (Throwable failure) {
            System.err.println("[Music/LyricSearch] " + source.id + " 取词失败 " + trackId + ": "
                    + failure.getMessage());
            return Collections.emptyList();
        }
    }

    /** 网易云官方歌词接口：{@code yrc}（逐字）与 {@code lrc} 都在同一份响应里，一次请求拿全。 */
    private static List<LyricLine> fetchNetease(String trackId) {
        long id = parseLong(trackId);
        if (id <= 0L) return Collections.emptyList();

        String response = CloudMusicApi.lyricNew(id).toString();
        // 上游偶尔夹 U+2000..U+200A 这类宽空格，解析器会把它当成正文的一部分；
        // 与 LyricLoadService 里同一处清洗保持一致，否则手动钉的那一条会比自动那条多带几个怪字符。
        response = response.replaceAll("[\\u2000-\\u200a]", " ");
        JsonObject json = JsonUtils.toJsonObject(response);
        if (json == null) return Collections.emptyList();
        List<LyricLine> parsed = LyricParser.parse(json);
        return parsed == null ? Collections.<LyricLine>emptyList() : parsed;
    }

    /**
     * 酷狗：先按 hash 取 KRC（逐字），解不出音节时退回这份曲目的行级 LRC。
     *
     * <p>{@code hash} 定位的是某一个录音文件，所以"按 hash 拿歌词"和"钉住用户挑的那一条"是同一件事：
     * 中间那步候选排序不影响结果，也不会像 {@code accesskey} 那样隔天就换。</p>
     */
    private static List<LyricLine> fetchKuGou(String hash, long durationMillis, String title, String artists) {
        String trimmed = safe(hash).trim();
        if (trimmed.isEmpty()) return Collections.emptyList();
        String keyword = LyricMatchText.isBlank(artists) ? title : artists.trim() + " - " + title;

        KrcLyrics.Decoded decoded = KuGouMusicApi.lyric(trimmed, durationMillis, keyword);
        List<LyricLine> lines = new ArrayList<LyricLine>();
        if (decoded != null && !decoded.yrc.isEmpty()) {
            LyricParser.parseYrc(decoded.yrc, lines);
            if (!decoded.translation.isEmpty()) {
                LyricParser.applyQrcSidecar(decoded.translation, lines, true);
            }
            if (!lines.isEmpty()) return lines;
        }

        // KRC 那一版根本没有逐字轨（伴奏、纯音乐、老数据）：行级 LRC 也是用户点的那首歌的词。
        String lrc = KuGouMusicApi.lyricLrc(trimmed, durationMillis, keyword);
        if (lrc.isEmpty()) return Collections.emptyList();
        List<LyricLine> fallback = LyricParser.parseLyricText(lrc, "", "");
        return fallback == null ? Collections.<LyricLine>emptyList() : fallback;
    }

    /**
     * AMLLDB：按条目 id 精确取；id 已经不在词库里（词条被合并或被撤）时，退回当初那条平台 id 再探一次。
     */
    private static List<LyricLine> fetchAmll(String entryId, JsonObject payload) {
        List<LyricLine> lines = AmllTtmlLyricService.manualResolve(entryId);
        if (!lines.isEmpty()) return lines;
        if (payload == null) return Collections.emptyList();

        String param = text(payload, "probeParam");
        String probeId = text(payload, "probeId");
        return AmllTtmlLyricService.manualResolveByPlatformId(param, probeId);
    }

    // ── 网易云 / QQ 的响应摘取 ────────────────────────────────────────────────

    private static JsonArray neteaseSongs(String keyword) {
        if (interrupted()) return null;
        try {
            JsonObject response = CloudMusicApi.cloudSearch(keyword, CloudMusicApi.SearchType.Single)
                    .toJsonObject();
            return array(object(response, "result"), "songs");
        } catch (Throwable failure) {
            System.err.println("[Music/LyricSearch] 网易云检索失败: " + failure.getMessage());
            return null;
        }
    }

    /** 云搜索的单曲对象里歌手是 {@code ar}，个别端点给 {@code artists}，两种都认。 */
    private static List<String> artistNames(JsonObject item) {
        JsonArray array = array(item, "ar");
        if (array == null) array = array(item, "artists");
        return namesIn(array);
    }

    private static List<String> qqArtistNames(JsonObject item) {
        return namesIn(array(item, "singer"));
    }

    private static List<String> namesIn(JsonArray array) {
        List<String> names = new ArrayList<String>();
        if (array == null) return names;
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            String name = text(element.getAsJsonObject(), "name");
            if (!name.trim().isEmpty()) names.add(name.trim());
        }
        return names;
    }

    private static String coverOf(JsonObject album) {
        String url = text(album, "picUrl");
        return url.isEmpty() ? text(album, "blurPicUrl") : url;
    }

    /** 钉给 {@link LyricManualMemory} 的那份坐标：酷狗只要 hash 就够精确重取。 */
    private static JsonObject payloadOf(KuGouMusicApi.Track track, String hash) {
        JsonObject payload = new JsonObject();
        payload.addProperty("hash", hash);
        payload.addProperty("name", safe(track.name));
        payload.addProperty("singer", safe(track.artist));
        payload.addProperty("album", safe(track.album));
        if (track.durationMillis > 0L) payload.addProperty("duration", track.durationMillis);
        return payload;
    }

    /**
     * AMLL 档的 payload：词库条目 id + 当初是从哪条平台 id 探进来的。
     *
     * <p>两份坐标都存是有原因的：词库条目 id 会随着词库合并改动，而平台 id 不会。取词时先按条目 id
     * 精确拿，拿不到再按平台 id 重探一次，钉住的那一条就不至于因为上游挪了个号而整个失效。</p>
     */
    private static JsonObject amllPayload(JsonObject entry, AmllCandidate candidate) {
        JsonObject payload = new JsonObject();
        String entryId = text(entry, "id");
        if (!entryId.isEmpty()) payload.addProperty("amllId", entryId);
        payload.addProperty("probeParam", candidate.param);
        payload.addProperty("probeId", candidate.trackId);
        String filename = text(entry, "filename");
        if (!filename.isEmpty()) payload.addProperty("filename", filename);
        // 故意不带 entry.lyrics：一份 TTML 能有几十 KB，钉词配置文件装不下也不该装。
        return payload;
    }

    // ── 打分 ──────────────────────────────────────────────────────────────────

    private static LyricMatchScore.Result evaluate(LyricMatchScore.Target target, String title,
                                                   List<String> artists, String album, long durationMillis) {
        return LyricMatchScore.evaluate(target, LyricMatchScore.Candidate.of(title, artists, album,
                durationMillis));
    }

    private static int scoreOf(LyricMatchScore.Target target, String title, List<String> artists,
                               String album, long durationMillis) {
        return evaluate(target, title, artists, album, durationMillis).score;
    }

    private static boolean durationMatched(LyricMatchScore.Target target, long candidateMillis) {
        return durationMultiplierOf(target, candidateMillis) >= DURATION_MATCHED_MULTIPLIER;
    }

    private static double durationMultiplierOf(LyricMatchScore.Target target, long candidateMillis) {
        // 未知时长（0.9）与明确对上的那两档（1.0 / 0.95）之外，都算「时长没对上」。
        if (target == null || target.durationMillis <= 0L || candidateMillis <= 0L) {
            return DURATION_UNKNOWN_MULTIPLIER;
        }
        long difference = Math.abs(target.durationMillis - candidateMillis);
        if (difference <= 1_000L) return 1.00;
        if (difference <= 3_000L) return 0.95;
        if (difference <= 5_000L) return 0.75;
        if (difference <= 10_000L) return 0.35;
        return 0.10;
    }

    private static void sortByScoreDesc(List<LyricSearchHit> hits) {
        Collections.sort(hits, new Comparator<LyricSearchHit>() {
            @Override
            public int compare(LyricSearchHit left, LyricSearchHit right) {
                return Integer.compare(right.score, left.score);
            }
        });
    }

    // ── 小工具 ────────────────────────────────────────────────────────────────

    private static boolean interrupted() {
        return Thread.currentThread().isInterrupted();
    }

    private static String artistsOf(Music music) {
        try {
            String artists = safe(music.getArtistsName());
            // 缺元数据时 Music 给的是占位串 "Unknown"，拿它去比对会把正确候选全否掉。
            return artists.equalsIgnoreCase("Unknown") ? "" : artists;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String albumOf(Music music) {
        try {
            return music.getAlbum() == null ? "" : safe(music.getAlbum().getName());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private static JsonObject object(JsonObject parent, String property) {
        try {
            if (parent == null || !parent.has(property) || !parent.get(property).isJsonObject()) return null;
            return parent.getAsJsonObject(property);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonArray array(JsonObject parent, String property) {
        try {
            if (parent == null || !parent.has(property) || !parent.get(property).isJsonArray()) return null;
            return parent.getAsJsonArray(property);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String text(JsonObject object, String property) {
        try {
            if (object == null || !object.has(property) || object.get(property).isJsonNull()) return "";
            return object.get(property).getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long longOr(JsonObject object, String property) {
        try {
            if (object == null || !object.has(property) || object.get(property).isJsonNull()) return 0L;
            return (long) Math.floor(object.get(property).getAsDouble());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long parseLong(String value) {
        String trimmed = safe(value).trim();
        if (trimmed.isEmpty() || trimmed.length() > 18) return 0L;
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) return 0L;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * AMLL 档的中间结构：一条来自网易云或 QQ 的候选，附带它当初的打分分量。
     *
     * <p>之所以不复用 {@link LyricSearchHit}：探测之前这条候选还<b>不是</b>一条 AMLL 结果，
     * 源、id、payload 三样都要等探到了才定得下来。</p>
     */
    private static final class AmllCandidate {
        private final String platform;
        private final String param;
        private final String trackId;
        private final String title;
        private final String artists;
        private final String album;
        private final long durationMillis;
        private final String coverUrl;
        private final int score;
        private final boolean titleMatched;
        private final boolean artistMatched;
        private final Boolean albumMatched;
        private final double durationMultiplier;

        private AmllCandidate(String platform, String param, String trackId, String title, String artists,
                              String album, long durationMillis, String coverUrl,
                              LyricMatchScore.Result result) {
            this.platform = platform;
            this.param = param;
            this.trackId = trackId;
            this.title = title;
            this.artists = artists;
            this.album = album;
            this.durationMillis = durationMillis;
            this.coverUrl = coverUrl;
            this.score = result.score;
            this.titleMatched = result.titleMatched;
            this.artistMatched = result.artistMatched;
            this.albumMatched = result.albumMatched;
            this.durationMultiplier = result.durationMultiplier;
        }

        private boolean durationMatched() {
            return durationMultiplier >= DURATION_MATCHED_MULTIPLIER;
        }

        /** 去重键：同一个平台里同一个 id 只探一次。 */
        private String key() {
            return platform + ':' + trackId;
        }
    }
}
