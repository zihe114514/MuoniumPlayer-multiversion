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
 * <p>酷狗有五类「可以取出一串曲目」的对象，取曲目的端点各不相同：精选歌单用 {@code specialid}、
 * 榜单用 {@code rankid}、云端歌单（含用户歌单）用 {@code global_collection_id}、
 * 专辑用 {@code album_id}、歌手用 {@code author_id}。{@link PlayList} 只有一个
 * {@code platformPlaylistId} 字段，所以在它前面加前缀区分（{@code rank:} / {@code collection:} /
 * {@code album:} / {@code artist:} / 无前缀=specialid），而不是给 DTO 再加一批只有酷狗用得上的字段。</p>
 *
 * <h3>「我的歌单」为什么额外带一个数字 id</h3>
 * <p>账号歌单有两个主键：云端主键 {@code global_collection_id}（取曲目用）与数字 {@code listid}
 * （增删曲、删歌单用）。云端主键里其实就嵌着 {@code listid}（{@code collection_3_<uid>_<listid>_0}），
 * 但那个格式是上游的内部约定、会随版本变，所以直接从接口拿到的 {@code listid} 才可靠——
 * 它随 {@code platformPlaylistId} 一起编码，形如 {@code collection:<gid>#<listid>}。</p>
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
    private static final String ALBUM_PREFIX = "album:";
    private static final String ARTIST_PREFIX = "artist:";

    /** 数字 listid 与云端主键之间的分隔符；见类注释。 */
    private static final String LIST_ID_SEPARATOR = "#";

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

    /** 搜索公开酷狗歌单。 */
    public static List<PlayList> searchPlaylists(String keyword, int limit) {
        return adaptPlaylists(KuGouMusicApi.searchPlaylists(keyword, limit));
    }

    /** 首页推荐歌单。 */
    public static List<PlayList> recommendPlaylists(int limit) {
        return adaptPlaylists(KuGouMusicApi.recommendPlaylists(limit));
    }

    /** 榜单列表，用于「排行榜」入口的卡片墙。 */
    public static List<PlayList> rankPlaylists() {
        return adaptPlaylists(KuGouMusicApi.rankList());
    }

    /** 热搜词。匿名可用，用于发现页。 */
    public static List<String> hotSearch() {
        return KuGouMusicApi.hotSearch();
    }

    /** 搜索联想词。 */
    public static List<String> searchSuggest(String keyword) {
        return KuGouMusicApi.searchSuggest(keyword);
    }

    /** 每日推荐。匿名可用。 */
    public static List<Music> dailyRecommend() {
        return adaptTracks(KuGouMusicApi.dailyRecommend(null));
    }

    /** 私人 FM（需要登录）。{@code excludeHash} 用来避免连续推同一首。 */
    public static List<Music> personalFm(int limit, String excludeHash) {
        return adaptTracks(KuGouMusicApi.personalFm(limit, excludeHash));
    }

    /** 最近播放（需要登录）。 */
    public static List<Music> recentSongs(int limit) {
        return adaptTracks(KuGouMusicApi.historySongs(limit));
    }

    /** 歌手单曲。 */
    public static List<Music> artistSongs(String authorId, int limit) {
        return adaptTracks(KuGouMusicApi.artistSongs(authorId, limit));
    }

    /** 专辑曲目。 */
    public static List<Music> albumSongs(String albumId, int limit) {
        return adaptTracks(KuGouMusicApi.albumSongs(albumId, limit));
    }

    /**
     * 读取一个酷狗歌单/榜单/专辑/歌手的曲目。
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
            tracks = KuGouMusicApi.collectionTracks(collectionIdOf(id), PLAYLIST_TRACK_LIMIT);
        } else if (id.startsWith(ALBUM_PREFIX)) {
            tracks = KuGouMusicApi.albumSongs(id.substring(ALBUM_PREFIX.length()), PLAYLIST_TRACK_LIMIT);
        } else if (id.startsWith(ARTIST_PREFIX)) {
            tracks = KuGouMusicApi.artistSongs(id.substring(ARTIST_PREFIX.length()), PLAYLIST_TRACK_LIMIT);
        } else {
            tracks = KuGouMusicApi.playlistTracks(id, PLAYLIST_TRACK_LIMIT);
        }
        if (tracks.isEmpty()) {
            throw new IllegalStateException("酷狗歌单 " + id + " 没有返回曲目");
        }
        return adaptTracks(tracks);
    }

    /** 账号歌单的数字 {@code listid}，用于增删曲与删除歌单；非账号歌单返回空串。 */
    public static String listIdOf(String platformPlaylistId) {
        String id = nonNull(platformPlaylistId);
        if (!id.startsWith(COLLECTION_PREFIX)) return "";
        int separator = id.indexOf(LIST_ID_SEPARATOR);
        return separator < 0 ? "" : id.substring(separator + 1);
    }

    /** 去掉 {@code #listid} 尾巴，只要云端主键——取曲目时它才是主键。 */
    private static String collectionIdOf(String platformPlaylistId) {
        String id = nonNull(platformPlaylistId).substring(COLLECTION_PREFIX.length());
        int separator = id.indexOf(LIST_ID_SEPARATOR);
        return separator < 0 ? id : id.substring(0, separator);
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

    /**
     * 这首歌是否「因为账号不是会员而无法播放」。
     *
     * <p>取流链路用它把失败原因说清楚：同样拿不到地址，会员曲该提示开通，免费曲该提示重试。</p>
     */
    public static boolean needsVip(Music music) {
        if (music == null || !music.isKuGou()) return false;
        return KuGouMusicApi.needsVip(music.toKuGouTrack());
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

    public static boolean isVip() {
        return KuGouDevice.isVip();
    }

    /** 会员等级文案，用在账号面板。非会员返回空串。 */
    public static String getVipLabel() {
        if (!KuGouDevice.isVip()) return "";
        String expires = KuGouDevice.vipExpiresAt();
        // 只取日期部分：上游给的是 "2026-10-06 08:31:40"，面板上一行放不下也没必要精确到秒。
        int space = expires.indexOf(' ');
        if (space > 0) expires = expires.substring(0, space);
        return expires.isEmpty() ? "会员" : "会员 · " + expires + " 到期";
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
        // 收藏状态与上报会话都绑在账号上：留着会让下一个账号看到上一个账号的收藏图标，
        // 也会拿旧 token 的去发上报。
        KuGouLikedCache.clear();
        KuGouPlaybackReporter.abandon();
    }

    /**
     * 刷新会员状态。
     *
     * <p>登录成功后调用一次即可：取流时不查它，只有账号面板与「这首歌需要会员」的提示会读。
     * 放在登录回调里而不是每次取流，是因为它对播放行为没有影响，多打一次请求只是浪费。</p>
     */
    public static void refreshVipStatus() {
        if (!isLoggedIn()) return;
        try {
            KuGouMusicApi.VipStatus status = KuGouMusicApi.vipInfo();
            if (status != null) KuGouDevice.saveVip(status.vipType, status.expiresAt);
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] 会员状态查询失败: " + throwable.getMessage());
        }
    }

    /** 申请扫码会话；返回 {@code null} 表示这次没拿到。 */
    public static KuGouMusicApi.QrSession createQrSession() {
        return KuGouMusicApi.createQrSession();
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
            // 会员状态决定高音质能不能拿到，登录后立刻补一次。
            refreshVipStatus();
            refreshUserPlaylists(true);
        }
        return status.code;
    }

    // ── 动态封面 ────────────────────────────────────────────────────────────

    /**
     * 取一首酷狗曲目的动态封面视频地址。
     *
     * <p>{@code album_audio_id} 是这条链路的必填主键（动态封面 CDN 只认它），
     * {@code album_id} 可选。两样都由 {@link Music} 的酷狗字段带过来。</p>
     *
     * <p>这个 URL 带过期时间戳，<b>每次都要重新取</b>，不能缓存后复用。</p>
     *
     * @return h264 MP4 地址；没有动态封面或失败时返回空串
     */
    public static String dynamicCoverUrl(Music music) {
        if (music == null || !music.isKuGou()) return "";
        String audioId = nonNull(music.getKugouAlbumAudioId());
        if (audioId.isEmpty()) audioId = nonNull(music.getSourceMid());
        if (audioId.isEmpty()) return "";
        return KuGouMusicApi.dynamicCoverUrl(audioId, nonNull(music.getKugouAlbumId()));
    }

    // ── 听歌上报 ────────────────────────────────────────────────────────────

    /**
     * 提交一条听歌历史。与 CSCC 时长上报相互独立：这条只记「听过」。
     *
     * @param playCount 累计播放次数，至少 1
     */
    public static boolean uploadPlayHistory(Music music, int playCount) {
        if (music == null || !music.isKuGou() || !isLoggedIn()) return false;
        String audioId = nonNull(music.getKugouAlbumAudioId());
        if (audioId.isEmpty()) audioId = nonNull(music.getSourceMid());
        if (audioId.isEmpty()) return false;
        return KuGouMusicApi.uploadPlayHistory(audioId, playCount);
    }

    /**
     * 发一次 CSCC「开始播放」事件。
     *
     * <p>与 {@link NeteasePlaybackHistoryReporter} 不同，酷狗这边要求 start/end 成对：
     * 只发 end 的话时长不会计入成长值。</p>
     */
    public static void reportPlaybackStart(Music music) {
        String mixSongId = csccSongId(music);
        if (mixSongId.isEmpty()) return;
        com.muoniumplayer.core.ncm.kugou.KuGouCsccReporter.start(mixSongId);
    }

    /**
     * 发一次 CSCC「结束播放」事件，时长挂在 {@code duration} 上。
     *
     * @param playedMillis 实际播放毫秒；非正数会被忽略
     */
    public static void reportPlaybackEnd(Music music, long playedMillis) {
        String mixSongId = csccSongId(music);
        if (mixSongId.isEmpty()) return;
        com.muoniumplayer.core.ncm.kugou.KuGouCsccReporter.finish(mixSongId, playedMillis);
    }

    /** CSCC 只认数字 id，所以优先取 {@code album_audio_id}，退化到 {@code sourceMid}。 */
    private static String csccSongId(Music music) {
        if (music == null || !music.isKuGou() || !isLoggedIn()) return "";
        String audioId = nonNull(music.getKugouAlbumAudioId()).trim();
        if (!audioId.isEmpty()) return audioId;
        return nonNull(music.getSourceMid()).trim();
    }

    // ── 歌单写操作 ──────────────────────────────────────────────────────────

    /** 收藏一首歌到「我喜欢」。 */
    public static boolean likeTrack(Music music) {
        if (music == null || !music.isKuGou()) return false;
        KuGouMusicApi.Playlist liked = KuGouMusicApi.likedPlaylist();
        if (liked == null || nonNull(liked.listId).trim().isEmpty()) return false;
        boolean added = KuGouMusicApi.addTrackToPlaylist(liked.listId, music.toKuGouTrack());
        if (added) KuGouLikedCache.add(music.getStableKey());
        return added;
    }

    /**
     * 从「我喜欢」里移除一首歌。
     *
     * <p>和 {@link #removeTrackFromPlaylist} 同一条链路：删除接口只认 {@code fileid}，
     * 而它不在曲目列表里，得先按 hash 去 {@code get_list_all_file_v3} 的结果里找。</p>
     */
    public static boolean unlikeTrack(Music music) {
        if (music == null || !music.isKuGou()) return false;
        KuGouMusicApi.Playlist liked = KuGouMusicApi.likedPlaylist();
        if (liked == null || nonNull(liked.listId).trim().isEmpty()) return false;
        boolean removed = removeTrackFromPlaylist(liked.listId, music);
        if (removed) KuGouLikedCache.remove(music.getStableKey());
        return removed;
    }

    /** 新建歌单。成功后刷新缓存。 */
    public static boolean createPlaylist(String name) {
        if (!isLoggedIn() || name == null || name.trim().isEmpty()) return false;
        boolean created = KuGouMusicApi.createPlaylist(name);
        if (created) refreshUserPlaylists(true);
        return created;
    }

    /** 取消收藏 / 删除歌单。成功后刷新缓存。 */
    public static boolean deletePlaylist(String platformPlaylistId) {
        String listId = listIdOf(platformPlaylistId);
        if (listId.isEmpty()) return false;
        boolean deleted = KuGouMusicApi.deletePlaylist(listId);
        if (deleted) refreshUserPlaylists(true);
        return deleted;
    }

    /**
     * 收藏一个别人的歌单到自己的账号。
     *
     * <p>酷狗的「收藏」是<b>在本地新建一份副本</b>，不做引用：{@code add_list} 用
     * {@code type=1} 建出一个新歌单，然后把原歌单的主键三元组记在新歌单上。所以收藏成功后
     * 歌单列表里会多出一条全新条目，而不是原条目被标记成已收藏——这与网易云的行为不同。</p>
     *
     * <p>按上游文档，收藏后还要再调一次「加歌」才能把原歌单的曲目带过来；这里刻意不做：
     * 那是一次与歌单大小成正比的批量请求，应该在用户明确要求时触发，而不是点一下「收藏」
     * 就悄悄发起几百次写入。</p>
     */
    public static boolean subscribePlaylist(PlayList playlist) {
        if (playlist == null || !isLoggedIn()) return false;
        String name = nonNull(playlist.getName()).trim();
        if (name.isEmpty()) return false;

        String globalId = collectionIdOf(playlist.getPlatformPlaylistId());
        String creatorListId = collectionCreatorListId(globalId);
        if (creatorListId.isEmpty()) return false;
        boolean created = KuGouMusicApi.collectPlaylist(name, collectionCreatorUserId(globalId),
                creatorListId, globalId);
        if (created) refreshUserPlaylists(true);
        return created;
    }

    /**
     * 从 {@code collection_3_<userid>_<listid>_<?>} 里取出创建者 userid。
     *
     * <p>收藏接口要求原歌单的「主键三元组」，但 {@link PlayList} 这个通用模型上没有对应的字段
     * （它只有音源无关的名字/封面/作者昵称）。好在 global collection id 本身就编码了这两个数字，
     * 直接解出来比为一个接口往通用模型上加两个酷狗专用字段更干净。</p>
     *
     * @return 创建者 userid；格式不符时返回空串
     */
    private static String collectionCreatorUserId(String globalCollectionId) {
        return collectionSegment(globalCollectionId, 1);
    }

    /** 从 global collection id 里取原歌单在创建者账号下的 {@code listid}。 */
    private static String collectionCreatorListId(String globalCollectionId) {
        return collectionSegment(globalCollectionId, 2);
    }

    /** {@code collection_<type>_<userid>_<listid>_<...>} 的第 {@code index} 段（1 起，跳过 collection 与 type）。 */
    private static String collectionSegment(String globalCollectionId, int index) {
        String id = nonNull(globalCollectionId).trim();
        if (id.isEmpty()) return "";
        String[] parts = id.split("_");
        // parts[0]="collection", parts[1]=type, parts[2]=userid, parts[3]=listid
        int target = index + 1;
        if (parts.length <= target) return "";
        return nonNull(parts[target]).trim();
    }

    /** 取消收藏 / 删除一个已收藏的歌单。与 {@link #deletePlaylist} 是同一件事。 */
    public static boolean unsubscribePlaylist(PlayList playlist) {
        if (playlist == null) return false;
        return deletePlaylist(playlist.getPlatformPlaylistId());
    }

    /**
     * 往一个账号歌单里加一首歌。
     *
     * <p>与 {@link #removeTrackFromPlaylist} 对称：入参是 UI 手上的
     * {@code platformPlaylistId} 与 {@link Music}，两者都必须是酷狗侧的。</p>
     */
    public static boolean addTrackToPlaylist(String platformPlaylistId, Music music) {
        String listId = listIdOf(platformPlaylistId);
        if (listId.isEmpty() || music == null || !music.isKuGou()) return false;
        boolean added = KuGouMusicApi.addTrackToPlaylist(listId, music.toKuGouTrack());
        // 曲目数是从账号歌单列表里带出来的，加歌之后那个数字就旧了。
        // 传 true 强制重拉；它只替换引用、不先清空，所以弹窗里正在列的歌单不会闪一下。
        if (added) refreshUserPlaylists(true);
        return added;
    }

    /**
     * 从歌单里移除一首歌。
     *
     * <p>{@code fileid} 不在曲目列表里（那个端点不给它），所以要先按 hash 在
     * {@link KuGouMusicApi#userPlaylistFiles} 的结果里找一次。查找是必要的：
     * 删除接口只认 {@code fileid}，而 UI 手上只有 {@link Music}。</p>
     */
    public static boolean removeTrackFromPlaylist(String platformPlaylistId, Music music) {
        String listId = listIdOf(platformPlaylistId);
        if (listId.isEmpty() || music == null) return false;
        String hash = nonNull(music.getSourceId());
        if (hash.isEmpty()) return false;
        try {
            com.google.gson.JsonArray files = KuGouMusicApi.userPlaylistFiles(listId, 1000);
            if (files == null) return false;
            for (com.google.gson.JsonElement element : files) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String itemHash = text(item, "hash");
                if (!itemHash.equalsIgnoreCase(hash)) continue;
                String fileId = text(item, "fileid");
                return !fileId.isEmpty() && KuGouMusicApi.removeTrackFromPlaylist(listId, fileId);
            }
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] 移除歌曲失败: " + throwable.getMessage());
        }
        return false;
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
        synchronized (PLAYLIST_LOCK) {
            userPlaylistsLastAttempt = System.currentTimeMillis();
        }
        try {
            List<PlayList> loaded = adaptPlaylists(KuGouMusicApi.userPlaylists(100));
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

    private static List<PlayList> adaptPlaylists(List<KuGouMusicApi.Playlist> playlists) {
        LinkedHashMap<String, PlayList> unique = new LinkedHashMap<String, PlayList>();
        if (playlists == null) return new ArrayList<PlayList>();
        for (KuGouMusicApi.Playlist playlist : playlists) {
            if (playlist == null || playlist.id == null || playlist.id.trim().isEmpty()) continue;
            String key = platformId(playlist);
            if (unique.containsKey(key)) continue;
            unique.put(key, createPlaylist(key, playlist));
        }
        return new ArrayList<PlayList>(unique.values());
    }

    /** 见类注释：不同种类的歌单取曲目的端点不同，用前缀把这个信息带进 platformPlaylistId。 */
    private static String platformId(KuGouMusicApi.Playlist playlist) {
        String id = playlist.id.trim();
        if (KuGouMusicApi.KIND_RANK.equals(playlist.kind)) return RANK_PREFIX + id;
        if (KuGouMusicApi.KIND_ALBUM.equals(playlist.kind)) return ALBUM_PREFIX + id;
        if (KuGouMusicApi.KIND_ARTIST.equals(playlist.kind)) return ARTIST_PREFIX + id;
        // 云端歌单（含用户歌单与搜索结果里的公开歌单）用非数字的 global_collection_id，
        // 老 specialid 端点认不出它。
        if (!id.matches("\\d+")) {
            String listId = nonNull(playlist.listId).trim();
            return COLLECTION_PREFIX + id + (listId.isEmpty() ? "" : LIST_ID_SEPARATOR + listId);
        }
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
