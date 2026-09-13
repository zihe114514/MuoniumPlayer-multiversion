package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.kugou.KrcLyrics;
import com.muoniumplayer.core.ncm.kugou.KuGouDevice;
import com.muoniumplayer.core.ncm.kugou.KuGouMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.screens.ncm.LyricParser;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.Tuple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 酷狗音乐的宿主适配层。
 *
 * <p>与 {@link NeteaseMusicService} 平级：{@code core.ncm.kugou} 里的东西只认识酷狗自己的模型，
 * 这里负责把它翻译成播放器用的 {@link Music}/{@link PlayList}，并持有登录态与歌单缓存。
 * UI 与播放链路不直接接触 {@code core.ncm.kugou}。</p>
 *
 * <h3>歌单主键的编码</h3>
 * <p>酷狗有三种歌单，取曲目的端点各不相同：精选歌单用 {@code specialid}、榜单用 {@code rankid}、
 * 用户自己的歌单用 {@code global_collection_id}。{@link PlayList} 只有一个
 * {@code platformPlaylistId} 字段，所以在它前面加前缀区分（{@code rank:} / {@code collection:} /
 * 无前缀=specialid），而不是给 DTO 再加一个只有酷狗用得上的字段。</p>
 */
public final class KuGouMusicService {

    /** 歌单曲目单次最多取多少首。酷狗单页上限比这个大，但一屏几百首已经够用且更快。 */
    private static final int PLAYLIST_TRACK_LIMIT = 300;

    /**
     * 跨源检索时最多给多少个候选打分。
     *
     * <p>上游一次通常只回 10~20 条，打分是纯计算（字符串相似度 + 一次哈希查找），所以这个上限
     * 主要是防御性的：万一上游回几百条，也不至于在歌词加载线程上做几万次字符串比较。</p>
     */
    private static final int MAX_CROSS_SOURCE_CANDIDATES = 24;

    private static final String RANK_PREFIX = "rank:";
    private static final String COLLECTION_PREFIX = "collection:";

    private static final Object PLAYLIST_LOCK = new Object();
    private static final long PLAYLIST_RETRY_DELAY_MS = 15_000L;

    private static volatile List<PlayList> userPlaylists = Collections.emptyList();
    private static volatile boolean userPlaylistsLoaded;
    private static volatile boolean userPlaylistsLoading;
    private static volatile long userPlaylistsLastAttempt;

    private KuGouMusicService() {
    }

    // ── 搜索 / 内容 ─────────────────────────────────────────────────────────

    public static List<Music> search(String keyword, int limit) {
        return adaptTracks(KuGouMusicApi.search(keyword, limit));
    }

    public static List<PlayList> searchPlaylists(String keyword, int limit) {
        return adaptPlaylists(KuGouMusicApi.searchPlaylists(keyword, limit), false);
    }

    /** 首页推荐歌单。 */
    public static List<PlayList> recommendPlaylists(int limit) {
        return adaptPlaylists(KuGouMusicApi.recommendPlaylists(limit), false);
    }

    /** 榜单列表，用于「排行榜」入口的卡片墙。 */
    public static List<PlayList> rankPlaylists() {
        return adaptPlaylists(KuGouMusicApi.rankList(), false);
    }

    /**
     * 读取一个酷狗歌单的曲目。
     *
     * @throws IllegalStateException 上游明确失败时抛出，让 {@link PlayList} 保持"未加载"以便下次重试；
     *                               返回空列表会被当成"这个歌单真的是空的"
     */
    public static List<Music> playlistTracks(String platformPlaylistId) {
        String id = nonNull(platformPlaylistId).trim();
        if (id.isEmpty()) return Collections.emptyList();
        List<KuGouMusicApi.Track> tracks;
        if (id.startsWith(RANK_PREFIX)) {
            tracks = KuGouMusicApi.rankSongs(id.substring(RANK_PREFIX.length()), PLAYLIST_TRACK_LIMIT);
        } else if (id.startsWith(COLLECTION_PREFIX)) {
            tracks = KuGouMusicApi.collectionTracks(id.substring(COLLECTION_PREFIX.length()), PLAYLIST_TRACK_LIMIT);
        } else {
            tracks = KuGouMusicApi.playlistTracks(id, PLAYLIST_TRACK_LIMIT);
        }
        if (tracks.isEmpty()) {
            throw new IllegalStateException("酷狗歌单 " + id + " 没有返回曲目");
        }
        return adaptTracks(tracks);
    }

    // ── 取流 ────────────────────────────────────────────────────────────────

    /** @return {@code (url, format)}；解析不到返回 {@code null} */
    public static Tuple<String, String> getSongUrl(Music music, Quality requestedQuality) {
        if (music == null || !music.isKuGou()) return null;
        try {
            KuGouMusicApi.Stream stream = KuGouMusicApi.songUrl(music.toKuGouTrack(), tier(requestedQuality));
            return stream == null ? null : new Tuple<String, String>(stream.url, stream.format);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] 取流失败 " + music.getStableKey() + ": " + throwable.getMessage());
            return null;
        }
    }

    private static int tier(Quality quality) {
        if (quality == Quality.LOSSLESS || quality == Quality.HIRES
                || quality == Quality.JYEFFECT || quality == Quality.JYMASTER) {
            return 2;
        }
        if (quality == Quality.HIGHER || quality == Quality.EXHIGH || quality == Quality.SKY) {
            return 1;
        }
        return 0;
    }

    // ── 歌词 ────────────────────────────────────────────────────────────────

    /**
     * 酷狗 KRC 逐字歌词。
     *
     * <p><b>跨源检索。</b>KRC 是逐字第三顺位，但它原来的实现第一行就是
     * {@code if (!music.isKuGou()) return}，非酷狗曲目永远拿不到这一路 —— 第三顺位等于形同虚设。
     * 现在改成：酷狗曲目走原来的 hash 精确匹配（行为一字不变）；其它平台的曲目按
     * 「歌手 - 歌名」搜酷狗歌词候选，用曲名/歌手/专辑/时长一起打分，越过门槛才采用。</p>
     *
     * <p>打分这一步不能省。没有 hash 时上游返回的是一串同名候选（原曲、Live、Remix、翻唱），
     * 只按时长挑最近的会贴错版本，而逐字歌词贴错版本比没有歌词更难用。</p>
     *
     * @return 只有<b>真的带音节时间</b>时才返回非空；行级结果一律返回空列表，让上层的普通歌词兜底
     *         链路接手 —— 那条链路能给出带翻译的完整 LRC，用行级 KRC 替换它是降级
     */
    public static List<LyricLine> getWordByWordLyrics(Music music) {
        if (music == null) return Collections.emptyList();
        try {
            String keyword = keywordFor(music);
            if (music.isKuGou()) {
                return adapt(KuGouMusicApi.lyric(music.getSourceId(), music.getDuration(), keyword));
            }
            return adapt(fetchDecodedFromSearch(music, keyword));
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] KRC 歌词失败 " + music.getStableKey() + ": " + throwable.getMessage());
            return Collections.emptyList();
        }
    }

    /** KRC 解码结果 → 渲染模型。行级或解不出音节的一律返回空列表。 */
    private static List<LyricLine> adapt(KrcLyrics.Decoded decoded) {
        if (decoded == null || decoded.yrc.isEmpty()) return Collections.emptyList();

        List<LyricLine> parsed = new ArrayList<LyricLine>();
        LyricParser.parseYrc(decoded.yrc, parsed);
        if (!LyricParser.hasWordByWordTiming(parsed)) return Collections.emptyList();
        if (!decoded.translation.isEmpty()) {
            LyricParser.applyQrcSidecar(decoded.translation, parsed, true);
        }
        return parsed;
    }

    /**
     * 跨源：搜酷狗歌词候选 → 打分 → 取分最高的一份。
     *
     * <p>只有越过 {@link LyricMatchScore#MIN_ACCEPT_SCORE} 的候选才会被下载，所以「搜到一堆同名歌」
     * 的结果是「放弃这一路、交给下一个源」，而不是「随便贴一份逐字歌词」。</p>
     */
    private static KrcLyrics.Decoded fetchDecodedFromSearch(Music music, String keyword) {
        // Decoded 的构造器是包可见的（它在 kugou 包里），这里用公开的 decode("") 拿一个空结果：
        // 既不用为了「构造空值」去放宽 KrcLyrics 的可见性，也不会真的解密什么东西。
        if (keyword.trim().isEmpty()) return KrcLyrics.decode("");

        List<JsonObject> candidates = KuGouMusicApi.lyricCandidates("", keyword);
        if (candidates.isEmpty()) return KrcLyrics.decode("");

        LyricMatchScore.Target target = new LyricMatchScore.Target(
                music.getName(), artistsOf(music), albumName(music), music.getDuration());

        JsonObject best = null;
        LyricMatchScore.Result bestResult = null;
        int scanned = 0;
        for (int index = 0; index < candidates.size() && scanned < MAX_CROSS_SOURCE_CANDIDATES; index++) {
            JsonObject candidate = candidates.get(index);
            if (candidate == null) continue;
            scanned++;

            LyricMatchScore.Result result = LyricMatchScore.evaluate(target, candidateOf(candidate));
            if (!result.accepted()) continue;
            if (bestResult == null || result.score > bestResult.score) {
                best = candidate;
                bestResult = result;
            }
        }
        if (best == null || bestResult == null) return KrcLyrics.decode("");

        System.out.println("[Music/KuGou] 跨源命中 " + music.getName() + " → "
                + text(best, "song") + " / " + text(best, "singer") + " (" + bestResult.describe() + ")");
        return KuGouMusicApi.lyric(best);
    }

    /**
     * 酷狗歌词候选 → 打分输入。
     *
     * <h3>两个实测出来的坑</h3>
     * <p>酷狗歌词库里有<b>两套并存的条目</b>，字段语义不一致，直接按名字读会全军覆没：</p>
     * <ol>
     *   <li><b>{@code singer} / {@code song} 会互换。</b>现代条目是
     *       {@code singer=米津玄師, song=Lemon}（人名在前），而库里大量老条目是
     *       {@code singer=起风了, song=买辣椒也用券}（歌名在前）。这里<b>不猜哪个是哪个</b>，
     *       而是把两个字段都当成「可能是歌名、也可能是歌手」，让相似度自己选最高的那一头——
     *       两种写法都能命中，也不需要往上加一张"哪些 id 是旧格式"的表。</li>
     *   <li><b>{@code duration} 有秒和毫秒两种单位。</b>同一批候选里 {@code 314} 和
     *       {@code 255582} 会同时出现。小于 10 秒的值只可能是秒，据此归一化到毫秒；
     *       不归一化会让时长乘数把正确候选压到 0.1 倍。</li>
     * </ol>
     *
     * <p>两个字段相同时（{@code singer=晴天, song=晴天}）只当一个歌名用，不重复塞进歌手列表——
     * 否则「歌名恰好同名的另一个人」会把歌手相似度蒙对。</p>
     */
    private static LyricMatchScore.Candidate candidateOf(JsonObject candidate) {
        String singer = text(candidate, "singer");
        String song = text(candidate, "song");
        String album = text(candidate, "album");

        List<String> titles = new ArrayList<String>(2);
        List<String> artists = new ArrayList<String>(2);
        titles.add(song);
        titles.add(singer);
        if (!singer.equals(song)) {
            artists.add(singer);
            artists.add(song);
        }
        return new LyricMatchScore.Candidate(titles, artists, album, durationMillis(candidate));
    }

    /**
     * 候选时长归一化到毫秒。
     *
     * <p>小于 10 秒的值只可能是「秒」——没有任何一首带歌词的曲子短于 10 秒，而毫秒表示下
     * 真实曲目都在 10^5 量级。这个分界线两侧都留了足够余量。</p>
     */
    private static long durationMillis(JsonObject candidate) {
        long raw = longOf(candidate, "duration");
        if (raw <= 0L) return 0L;
        return raw < 10_000L ? raw * 1000L : raw;
    }

    private static String keywordFor(Music music) {
        String artist = artistsOf(music);
        String name = nonNull(music.getName()).trim();
        if (name.isEmpty()) return "";
        return artist.isEmpty() ? name : artist + " - " + name;
    }

    /** 歌手串。{@code "/"} 既是给人看的连接符，也正好在 {@code LyricMatchText} 的分隔符集合里，能原样拆回去。 */
    private static String artistsOf(Music music) {
        List<String> result = new ArrayList<String>();
        try {
            String artists = safe(music.getArtistsName());
            if (artists.isEmpty() || artists.equalsIgnoreCase("Unknown")) return "";
            for (String part : artists.split("\\s*[/、,&]\\s*")) {
                String trimmed = part == null ? "" : part.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
        } catch (Throwable ignored) {
            return "";
        }
        return String.join("/", result);
    }

    private static String albumName(Music music) {
        try {
            return music.getAlbum() == null ? "" : safe(music.getAlbum().getName()).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** JsonUtils 的取值方法在字段缺失时会抛异常，这里统一按「缺失即空」处理。 */
    private static String text(JsonObject object, String property) {
        try {
            if (object == null || !object.has(property) || object.get(property).isJsonNull()) return "";
            return safe(object.get(property).getAsString()).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long longOf(JsonObject object, String property) {
        try {
            if (object == null || !object.has(property) || object.get(property).isJsonNull()) return 0L;
            return object.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 酷狗行级 LRC，仅作普通歌词兜底。
     *
     * <p>逐字链路（AMLL / QQ QRC / 上面的 KRC）全部落空时才会用到它。返回原始 LRC 文本而不是
     * {@code LyricLine}，是为了让调用方走宿主既有的那条"文本 → 网易云歌词 JSON → LyricParser"
     * 通路，避免再多一份解析实现。</p>
     *
     * @return 明文 LRC；非酷狗曲目或拿不到时为空串
     */
    public static String getFallbackLrc(Music music) {
        if (music == null || !music.isKuGou()) return "";
        try {
            return KuGouMusicApi.lyricLrc(music.getSourceId(), music.getDuration(),
                    music.getArtistsName() + " - " + music.getName());
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] LRC 歌词失败 " + music.getStableKey() + ": " + throwable.getMessage());
            return "";
        }
    }

    // ── 账号 ────────────────────────────────────────────────────────────────

    public static boolean isLoggedIn() {
        return KuGouDevice.isLoggedIn();
    }

    public static String getAccountName() {
        String nickname = KuGouDevice.nickname();
        if (!nickname.trim().isEmpty()) return nickname;
        return isLoggedIn() ? "酷狗账号" : "未登录";
    }

    public static String getAvatarUrl() {
        return KuGouMusicApi.cover(KuGouDevice.avatarUrl(), 128);
    }

    public static Location getAvatarLocation() {
        return getAvatarUrl().isEmpty() ? null : Location.of("muonium/textures/account/kugou_avatar.png");
    }

    public static void logout() {
        KuGouDevice.logout();
        clearPlaylistCache();
    }

    /** 申请扫码 key；返回空串表示这次没拿到。 */
    public static String createQrKey() {
        return KuGouMusicApi.createQrKey();
    }

    public static String qrContent(String key) {
        return KuGouMusicApi.qrContent(key);
    }

    /**
     * 轮询一次扫码状态，成功时落盘凭证。
     *
     * @return 上游原始状态码：0 过期、1 等待扫码、2 待确认、4 成功；{@code -1} 表示这次请求失败
     *         （调用方应重试，不要当成失效）
     */
    public static int checkQr(String key) {
        KuGouMusicApi.QrStatus status = KuGouMusicApi.checkQr(key);
        if (status == null) return -1;
        if (status.code == 4) {
            if (status.token.isEmpty() || status.userId.isEmpty()) {
                System.err.println("[Music/KuGou] 扫码成功但上游没给 token/userid");
                return -1;
            }
            KuGouDevice.saveLogin(status.token, status.userId, status.nickname, status.avatarUrl);
            refreshUserPlaylists(true);
        }
        return status.code;
    }

    // ── 我的歌单（缓存 + 异步） ──────────────────────────────────────────────

    public static List<PlayList> getUserPlaylistsSnapshot() {
        return new ArrayList<PlayList>(userPlaylists);
    }

    public static boolean areUserPlaylistsLoaded() {
        return userPlaylistsLoaded;
    }

    public static boolean areUserPlaylistsLoading() {
        return userPlaylistsLoading;
    }

    /** 回调运行在后台线程；UI 调用方要自己切回主线程。 */
    public static void ensureUserPlaylistsAsync(final Runnable callback) {
        if (!isLoggedIn()) {
            clearPlaylistCache();
            if (callback != null) callback.run();
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (PLAYLIST_LOCK) {
            if (userPlaylistsLoading) return;
            if (userPlaylistsLoaded) {
                if (callback != null) callback.run();
                return;
            }
            // 失败后短时间内不重试：登录态失效时每次开面板都重打一次请求毫无意义。
            if (now - userPlaylistsLastAttempt < PLAYLIST_RETRY_DELAY_MS) return;
            userPlaylistsLoading = true;
            userPlaylistsLastAttempt = now;
        }

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshUserPlaylists(false);
                } finally {
                    synchronized (PLAYLIST_LOCK) {
                        userPlaylistsLoading = false;
                    }
                    if (callback != null) {
                        try {
                            callback.run();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "KuGou Account");
        thread.setDaemon(true);
        thread.start();
    }

    private static void refreshUserPlaylists(boolean force) {
        if (!isLoggedIn()) {
            clearPlaylistCache();
            return;
        }
        if (!force && userPlaylistsLoaded) return;
        try {
            List<PlayList> loaded = adaptPlaylists(KuGouMusicApi.userPlaylists(100), true);
            userPlaylists = Collections.unmodifiableList(new ArrayList<PlayList>(loaded));
            userPlaylistsLoaded = true;
            System.out.println("[Music/KuGou] 已加载 " + loaded.size() + " 个酷狗账号歌单。");
        } catch (Throwable throwable) {
            userPlaylistsLoaded = false;
            System.err.println("[Music/KuGou] 账号歌单加载失败: " + throwable.getMessage());
        }
    }

    private static void clearPlaylistCache() {
        userPlaylists = Collections.emptyList();
        userPlaylistsLoaded = false;
        userPlaylistsLoading = false;
        userPlaylistsLastAttempt = 0L;
    }

    // ── 模型转换 ────────────────────────────────────────────────────────────

    private static List<Music> adaptTracks(List<KuGouMusicApi.Track> tracks) {
        List<Music> result = new ArrayList<Music>();
        if (tracks == null) return result;
        for (KuGouMusicApi.Track track : tracks) {
            Music music = Music.fromKuGouTrack(track);
            if (music != null) result.add(music);
        }
        return result;
    }

    private static List<PlayList> adaptPlaylists(List<KuGouMusicApi.Playlist> playlists, boolean owned) {
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<String, PlayList>();
        if (playlists == null) return new ArrayList<PlayList>();
        for (KuGouMusicApi.Playlist playlist : playlists) {
            if (playlist == null || playlist.id == null || playlist.id.trim().isEmpty()) continue;
            String key = platformId(playlist, owned);
            if (unique.containsKey(key)) continue;
            unique.put(key, createPlaylist(key, playlist));
        }
        return new ArrayList<PlayList>(unique.values());
    }

    /** 见类注释：三种歌单的取曲目端点不同，用前缀把这个信息带进 platformPlaylistId。 */
    private static String platformId(KuGouMusicApi.Playlist playlist, boolean owned) {
        String id = playlist.id.trim();
        if (playlist.rank) return RANK_PREFIX + id;
        // Cloud playlists use a non-numeric global_collection_id, including public search
        // results. The legacy specialid endpoint cannot resolve that key.
        if (!id.matches("\\d+")) return COLLECTION_PREFIX + id;
        return id;
    }

    private static PlayList createPlaylist(String platformId, KuGouMusicApi.Playlist source) {
        PlayList playlist = new PlayList(stableId(platformId), nonNull(source.name),
                KuGouMusicApi.cover(source.coverTemplate, 300), Math.max(0, source.trackCount),
                0L, null, nonNull(source.description), 0L);
        playlist.setPlatform(MusicPlatform.KUGOU);
        playlist.setPlatformPlaylistId(platformId);
        return playlist;
    }

    /**
     * 由平台主键推一个稳定的 long id。
     *
     * <p>不能直接 {@code Long.parseLong}：{@code global_collection_id} 是
     * {@code collection_3_520033052_123_0} 这样的字符串。用 FNV-1a 而不是 {@code hashCode()}，
     * 是因为它要跨进程稳定（歌单封面缓存按这个 id 落盘）。</p>
     */
    private static long stableId(String platformId) {
        byte[] bytes = ("kugou:playlist:" + nonNull(platformId)).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash & Long.MAX_VALUE;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}