package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 哔哩哔哩的收藏夹链路：读取我的/订阅的收藏夹、取其中内容，以及把一条稿件收藏/取消收藏。
 *
 * <p><b>整条链路都要登录。</b>{@code folder/created/list-all} 查别人的公开收藏夹时匿名可用，
 * 但 {@code resource/list} 与 {@code resource/deal} 一律要 {@code SESSDATA}，写操作还要
 * {@code bili_jct} 当 csrf。未登录时这里一律给空结果，界面据此提示去登录。</p>
 *
 * <p><b>mlid 而不是 fid。</b>收藏夹对外有两种 id：{@code fid} 是原始 id，{@code mlid} 是
 * 「原始 id + 创建者 mid 尾号两位」拼出来的完整 id。{@code resource/list} 与 {@code resource/deal}
 * 认的是 mlid，所以本地一律带 mlid。</p>
 *
 * <p><b>只收视频稿件。</b>{@code medias[]} 里除了稿件（{@code type=2}）还可能出现音频（12）与
 * 视频合集（21）。播放器这一侧只有稿件这条链路能取到可播的流，所以非稿件项被跳过 ——
 * 与其显示一行点开就报错的条目，不如不显示。</p>
 */
public final class BilibiliFavoriteService {

    /** 收藏夹歌单的 platformPlaylistId 前缀：{@code bili-fav:{mlid}}。 */
    public static final String FAV_PREFIX = "bili-fav:";

    /** 收藏夹内容接口的单页上限，超过会被 B 站夹到 20。 */
    private static final int RESOURCE_PAGE_SIZE = 20;
    private static final int RESOURCE_MAX_PAGES = 25;
    /** attr 的第 1 位：0 是默认收藏夹，1 是用户自建的其他收藏夹。 */
    private static final int ATTR_NOT_DEFAULT = 2;
    private static final int TYPE_VIDEO = 2;
    private static final String FAV_API_ROOT = BilibiliMusicService.FAV_API_ROOT;

    private static volatile List<PlayList> folderCache = Collections.emptyList();
    /** 我创建的收藏夹 mlid，按接口给出的顺序；收藏写入只在里面挑。 */
    private static volatile List<String> ownFolderIds = Collections.emptyList();
    private static volatile String folderCacheOwner = "";
    private static volatile boolean foldersLoading;
    /** mlid → {@code attr} 位掩码。只有我创建的收藏夹才有这一项。 */
    private static final Map<String, Long> folderAttributes = new ConcurrentHashMap<>();

    /**
     * 「这条稿件我收过没有」的本地记忆。
     *
     * <p>播放列表每一行都要问一次，而 {@code fav/video/favoured} 是一条一次请求的接口；
     * 不做成内存表的话，翻一页收藏列表就是几十次串行请求。收藏动作成功后会就地更新，
     * 所以界面不会在点完之后还亮着旧状态。</p>
     */
    private static final Map<String, Boolean> favouredCache = new ConcurrentHashMap<>();

    private BilibiliFavoriteService() {
    }

    // ════════════════════════════ 收藏夹列表 ════════════════════════════

    /** 侧栏用：命中缓存直接返回，不触发网络。 */
    public static List<PlayList> getUserPlaylistsSnapshot() {
        return folderCache;
    }

    public static boolean areUserPlaylistsLoaded() {
        return !folderCache.isEmpty();
    }

    public static boolean areUserPlaylistsLoading() {
        return foldersLoading;
    }

    /** 异步拉一次收藏夹列表；已有缓存或已有请求在路上时直接返回。 */
    public static void ensureUserPlaylistsAsync(Runnable onComplete) {
        String mid = BilibiliMusicService.getAccountId();
        if (mid.isEmpty()) {
            // 未登录（或刚退出）没有可展示的东西：把回调照样发出去，界面才不至于停在「正在加载…」。
            if (onComplete != null) MultiThreadingUtil.runOnMainThread(onComplete);
            return;
        }
        if (mid.equals(folderCacheOwner) && !folderCache.isEmpty()) {
            if (onComplete != null) MultiThreadingUtil.runOnMainThread(onComplete);
            return;
        }
        if (!mid.equals(folderCacheOwner)) {
            // 换了账号必须先作废，否则新账号看到的是上一个人的收藏夹。
            folderCache = Collections.emptyList();
            ownFolderIds = Collections.emptyList();
            folderAttributes.clear();
            favouredCache.clear();
        }
        if (foldersLoading) {
            if (onComplete != null) MultiThreadingUtil.runOnMainThread(onComplete);
            return;
        }
        foldersLoading = true;
        MultiThreadingUtil.runAsync(() -> {
            try {
                refreshFolders(mid);
            } finally {
                foldersLoading = false;
                if (onComplete != null) MultiThreadingUtil.runOnMainThread(onComplete);
            }
        });
    }

    /**
     * 侧栏刷新按钮：无视已有缓存强制重拉收藏夹列表。
     *
     * <p>ensure 那条路命中缓存就返回，没有这个入口侧栏快照就再也不更新。结果在
     * <b>主线程</b>回调（与 ensure 的回调同一条规则）；刷新已在路上时被 loading 标志
     * 挡住并立刻回报失败，由调用方决定怎么提示。{@link #refreshFolders(String)} 抛异常
     * 时不碰 {@code folderCache}，旧数据原样保留。</p>
     */
    public static void forceRefreshUserPlaylistsAsync(Consumer<PlaylistRefreshResult> callback) {
        long startedAt = System.currentTimeMillis();
        String mid = BilibiliMusicService.getAccountId();
        if (mid.isEmpty()) {
            if (callback != null) MultiThreadingUtil.runOnMainThread(() ->
                    callback.accept(PlaylistRefreshResult.failure(
                            System.currentTimeMillis() - startedAt, "未登录哔哩哔哩账号")));
            return;
        }
        if (foldersLoading) {
            if (callback != null) MultiThreadingUtil.runOnMainThread(() ->
                    callback.accept(PlaylistRefreshResult.failure(
                            System.currentTimeMillis() - startedAt, "B站收藏夹正在刷新中")));
            return;
        }
        foldersLoading = true;
        MultiThreadingUtil.runAsync(() -> {
            PlaylistRefreshResult outcome;
            try {
                List<PlayList> folders = refreshFolders(mid);
                outcome = PlaylistRefreshResult.success(System.currentTimeMillis() - startedAt, folders.size());
            } catch (Throwable throwable) {
                String detail = throwable.getMessage();
                outcome = PlaylistRefreshResult.failure(System.currentTimeMillis() - startedAt,
                        detail == null || detail.trim().isEmpty() ? "网络请求失败" : detail);
            } finally {
                foldersLoading = false;
            }
            if (callback != null) {
                // try 与 catch 各赋值一次，outcome 因此不是 effectively final，
                // lambda 捕获需要一份只赋值一次的拷贝。
                final PlaylistRefreshResult result = outcome;
                MultiThreadingUtil.runOnMainThread(() -> callback.accept(result));
            }
        });
    }

    /** 我创建的 + 我订阅的收藏夹，按 mlid 去重后合并。同步版本，调用方自己开线程。 */
    public static List<PlayList> userPlaylists() {
        String mid = BilibiliMusicService.getAccountId();
        if (mid.isEmpty()) return List.of();
        return refreshFolders(mid);
    }

    private static synchronized List<PlayList> refreshFolders(String mid) {
        Map<String, PlayList> unique = new LinkedHashMap<>();
        Set<String> own = new LinkedHashSet<>();
        collectFolders(unique, own, favApi("folder/created/list-all?up_mid=" + mid + "&type=2"), true);
        collectFolders(unique, own, favApi("folder/collected/list?up_mid=" + mid
                + "&pn=1&ps=20&type=2&platform=web"), false);
        List<PlayList> folders = new ArrayList<>(unique.values());
        folderCache = folders;
        ownFolderIds = new ArrayList<>(own);
        folderCacheOwner = mid;
        return folders;
    }

    private static void collectFolders(Map<String, PlayList> unique, Set<String> own,
                                       String url, boolean created) {
        try {
            JsonObject response = BilibiliMusicService.getJson(url, true);
            JsonObject data = BilibiliMusicService.object(response, "data");
            JsonArray list = BilibiliMusicService.array(data, "list");
            if (list == null) return;
            for (JsonElement element : list) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject folder = element.getAsJsonObject();
                String mlid = BilibiliMusicService.string(folder, "id");
                if (mlid.isEmpty() || "0".equals(mlid)) continue;
                String title = BilibiliMusicService.cleanText(BilibiliMusicService.string(folder, "title"));
                int count = (int) BilibiliMusicService.longValue(folder, "media_count");
                PlayList playlist = createPlaylist(mlid,
                        title.isEmpty() ? (created ? "未命名收藏夹" : "订阅的收藏夹") : title,
                        BilibiliMusicService.normalizeCover(BilibiliMusicService.string(folder, "cover")),
                        count,
                        BilibiliMusicService.cleanText(BilibiliMusicService.string(folder, "intro")),
                        BilibiliMusicService.cleanText(BilibiliMusicService.string(
                                BilibiliMusicService.object(folder, "upper"), "name")));
                if (unique.put(playlist.getPlatformPlaylistId(), playlist) == null && created) {
                    own.add(mlid);
                    folderAttributes.put(mlid, BilibiliMusicService.longValue(folder, "attr"));
                }
            }
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Favorite folder list failed: "
                    + BilibiliMusicService.describe(throwable));
        }
    }

    private static PlayList createPlaylist(String mlid, String name, String cover, int count,
                                           String description, String creator) {
        PlayList playlist = new PlayList(BilibiliPlaylistService.stableId(FAV_PREFIX + mlid), name,
                cover, Math.max(0, count), 0L, null, description, 0L);
        playlist.setPlatform(MusicPlatform.BILIBILI);
        playlist.setPlatformPlaylistId(favPlaylistId(mlid));
        return playlist;
    }

    /** 收藏夹歌单的 platformPlaylistId。 */
    public static String favPlaylistId(String mlid) {
        return FAV_PREFIX + BilibiliMusicService.nonBlank(mlid);
    }

    /** 这条 id 是不是收藏夹。 */
    public static boolean supports(String platformPlaylistId) {
        return BilibiliMusicService.nonBlank(platformPlaylistId).startsWith(FAV_PREFIX);
    }

    // ════════════════════════════ 收藏夹内容 ════════════════════════════

    /**
     * 取收藏夹内容（{@code platformPlaylistId} 去掉前缀之后的部分）。
     *
     * <p>返回 {@code null} 表示「一页都没取到」，也就是取失败；空收藏夹返回空表。
     * {@code PlayList.queryMusics()} 靠这一区分决定是报错还是显示「这个收藏夹是空的」。</p>
     */
    public static List<Music> tracks(String mlidOrId) {
        String mlid = BilibiliMusicService.nonBlank(mlidOrId);
        if (mlid.isEmpty()) return null;
        if (!BilibiliMusicService.isLoggedIn()) return List.of();

        List<Music> tracks = new ArrayList<>();
        boolean anyPage = false;
        // 打开的就是「点赞」按钮写入的那个收藏夹：能列出来的当然都收过了，就地给它播种，
        // 省得每行一次 favoured 查询（见 isFavouredLocally 的取舍）。
        boolean seedFavoured = isDefaultFolder(mlid);
        try {
            for (int page = 1; page <= RESOURCE_MAX_PAGES; page++) {
                JsonObject response = BilibiliMusicService.getJson(favApi("resource/list?media_id=" + mlid
                        + "&pn=" + page + "&ps=" + RESOURCE_PAGE_SIZE
                        + "&type=0&order=mtime&platform=web"), true);
                BilibiliMusicService.requireSuccessful(response, "fav/resource/list");
                JsonObject data = BilibiliMusicService.object(response, "data");
                if (data == null) break;
                anyPage = true;
                JsonArray medias = BilibiliMusicService.array(data, "medias");
                if (medias != null) {
                    for (JsonElement element : medias) {
                        Music music = toMusic(element);
                        if (music != null) {
                            tracks.add(music);
                            if (seedFavoured) rememberFavoured(music.getSourceId(), true);
                        }
                    }
                }
                if (!"true".equals(BilibiliMusicService.string(data, "has_more"))
                        || medias == null || medias.size() == 0) break;
            }
            return anyPage ? tracks : null;
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Favorite tracks failed for " + mlid + ": "
                    + BilibiliMusicService.describe(throwable));
            // 翻到后面才失败的话，把已经拿到的部分交回去比整单作废有用。
            return tracks.isEmpty() ? null : tracks;
        }
    }

    private static Music toMusic(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject media = element.getAsJsonObject();
        if (BilibiliMusicService.longValue(media, "type") != TYPE_VIDEO) return null;
        // attr 非 0 表示这条稿件已失效（9 是 UP 自删，1 是其他原因删除），点了也播不了。
        if (BilibiliMusicService.longValue(media, "attr") != 0) return null;
        String bvid = BilibiliMusicService.firstNonBlank(BilibiliMusicService.string(media, "bvid"),
                BilibiliMusicService.string(media, "bv_id"));
        if (bvid.isEmpty()) return null;

        return Music.fromBilibiliTrack(new BilibiliMusicService.Track(bvid,
                (int) Math.max(1L, BilibiliMusicService.longValue(media, "page")),
                BilibiliMusicService.cleanText(BilibiliMusicService.string(media, "title")),
                BilibiliMusicService.cleanText(BilibiliMusicService.string(
                        BilibiliMusicService.object(media, "upper"), "name")),
                BilibiliMusicService.longValue(media, "duration") * 1000L,
                BilibiliMusicService.normalizeCover(BilibiliMusicService.string(media, "cover")),
                BilibiliMusicService.string(media, "id"), ""));
    }

    // ════════════════════════════ 收藏 / 取消收藏 ════════════════════════════

    /**
     * 把这条稿件收进默认收藏夹，或从中取消。
     *
     * @return 上游确认成功才返回 {@code true}；失败时不会改动本地记忆，界面据此回滚图标
     */
    public static boolean setFavourite(Music music, boolean favourite) {
        if (music == null || !BilibiliMusicService.isLoggedIn()) return false;
        try {
            String mlid = defaultFolderId();
            if (mlid.isEmpty()) return false;
            String avid = avidOf(music.getSourceId());
            if (avid.isEmpty()) return false;

            Map<String, String> form = new LinkedHashMap<>();
            form.put("rid", avid);
            form.put("type", Integer.toString(TYPE_VIDEO));
            form.put(favourite ? "add_media_ids" : "del_media_ids", mlid);
            form.put("csrf", BilibiliSessionStore.getCsrfToken());
            form.put("platform", "web");
            JsonObject response = BilibiliMusicService.postForm(favApi("resource/deal"), form);
            BilibiliMusicService.requireSuccessful(response, "fav/resource/deal");
            rememberFavoured(music.getSourceId(), favourite);
            return true;
        } catch (Throwable throwable) {
            System.err.println("[Music/Bilibili] Favorite write failed for " + music.getStableKey()
                    + ": " + BilibiliMusicService.describe(throwable));
            return false;
        }
    }

    /**
     * 这条稿件在界面上该不该亮红心。<b>只读本地乐观记忆，绝不发请求。</b>
     *
     * <p>播放列表每一行、每一帧都要问一次，挂网络上去就是把渲染线程按行阻塞。语义和
     * {@link KuGouLikedCache} 一致：点过就记下来，失败回滚；打开默认收藏夹时由
     * {@link #tracks(String)} 批量播种。代价是在 B 站客户端里收的歌，这里要等列表被
     * 打开过一次才会亮 —— 换来的是翻一页收藏不用发几十次请求。</p>
     */
    public static boolean isFavouredLocally(Music music) {
        if (music == null || !BilibiliMusicService.isLoggedIn()) return false;
        Boolean known = favouredCache.get(BilibiliMusicService.nonBlank(music.getSourceId()));
        return known != null && known;
    }

    /**
     * 记录/撤销界面上的红心状态。点一下红心就立刻变色，靠的是这个方法而不是等接口回来；
     * 写入失败时界面再调用一次把它改回去。
     */
    public static void rememberFavoured(String sourceId, boolean favoured) {
        String id = BilibiliMusicService.nonBlank(sourceId);
        if (!id.isEmpty()) favouredCache.put(id, favoured);
    }

    /** 退出登录时清掉账号相关的缓存，避免新账号看到上一个人的收藏夹与红心状态。 */
    static void clearAccountCaches() {
        folderCache = Collections.emptyList();
        ownFolderIds = Collections.emptyList();
        folderAttributes.clear();
        favouredCache.clear();
        folderCacheOwner = "";
        foldersLoading = false;
    }

    /**
     * 默认收藏夹的 mlid；缓存里没有就同步拉一次。拿不到返回空串。
     *
     * <p>只在我<b>创建</b>的收藏夹里挑：订阅来的别人收藏夹没有写入权限，
     * 拿它当 {@code add_media_ids} 会被 B 站拒绝。</p>
     */
    private static String defaultFolderId() {
        List<String> own = ownFolderIds;
        if (own.isEmpty() && !BilibiliMusicService.getAccountId().isEmpty()) {
            userPlaylists();
            own = ownFolderIds;
        }
        if (own.isEmpty()) return "";
        for (String mlid : own) {
            Long attr = folderAttributes.get(mlid);
            if (attr != null && (attr & ATTR_NOT_DEFAULT) == 0L) return mlid;
        }
        // 没有标记成默认的那一个（老账号或被改过属性）就用列表第一个：新账号的第一个必是默认收藏夹。
        return own.get(0);
    }

    // ════════════════════════════ 零件 ════════════════════════════

    private static String favApi(String path) {
        return FAV_API_ROOT + "/" + path;
    }

    /** {@code bili-fav:123456789} → {@code 123456789}。 */
    static String mlidOf(String platformPlaylistId) {
        String id = BilibiliMusicService.nonBlank(platformPlaylistId);
        return id.startsWith(FAV_PREFIX) ? id.substring(FAV_PREFIX.length()) : "";
    }

    /**
     * 这个 mlid 是不是我那个「点赞会写进去」的默认收藏夹。
     *
     * <p>只看已经缓存的 {@code attr}，不发请求：它被 {@link #tracks(String)} 用来决定要不要给
     * 红心播种，而那个调用本身就在网络链路上，不该再套一层。attr 没落缓存时退回「我创建的
     * 第一个收藏夹」——新账号的第一个必是默认夹，和 {@link #defaultFolderId()} 的兜底一致。</p>
     */
    private static boolean isDefaultFolder(String mlid) {
        Long attr = folderAttributes.get(mlid);
        if (attr != null) return (attr & ATTR_NOT_DEFAULT) == 0L;
        List<String> own = ownFolderIds;
        return !own.isEmpty() && own.get(0).equals(mlid);
    }

    /**
     * 收藏写接口要的是 avid，而本地只存了 bvid。
     *
     * <p>用 {@code view} 换一次：这条链路本来就是「点一下红心」，多一个请求用户感知不到，
     * 但比把 avid 一路塞进 {@link Music} 干净 —— 后者会改变它的稳定 key，进而打掉音频缓存。</p>
     */
    private static String avidOf(String sourceId) {
        try {
            BilibiliMusicService.Track track = BilibiliMusicService.resolveTrack(sourceId);
            return track == null ? "" : BilibiliMusicService.nonBlank(track.aid);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
