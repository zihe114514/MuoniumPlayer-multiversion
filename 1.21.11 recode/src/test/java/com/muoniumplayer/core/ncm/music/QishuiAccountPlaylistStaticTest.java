package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汽水「我的账号歌单」两条链路的离线回归测试：快照要把自建 + 收藏都翻到底，
 * 取曲目要按身份分派到带 Cookie 的 PC 详情接口。
 *
 * <p>钉住的都是实测出来的上游行为，改回去就会复现用户报的两个症状：</p>
 * <ul>
 *   <li>收藏的歌单压根不显示 —— {@code /me/playlist} 只有自建的，收藏在
 *       {@code /me/collection/mixed}；</li>
 *   <li>歌单里的歌数量不全 —— 匿名接口少给（同一份歌单 116 首 vs 带 Cookie 238 首），
 *       而客户端以前还额外掐了 500 首 / 12 页。</li>
 * </ul>
 *
 * <p>不启动 Minecraft、不联网、不写真实配置文件。</p>
 */
class QishuiAccountPlaylistStaticTest {

    private static String source() throws IOException {
        Path dir = ProjectPaths.sharedJava()
                .resolve(Paths.get("com", "muoniumplayer", "core", "ncm", "music"));
        return new String(Files.readAllBytes(dir.resolve("QishuiMusicService.java")),
                StandardCharsets.UTF_8);
    }

    /** 取 [from, to) 之间的一段源码，用它把断言限制在某个方法体内。 */
    private static String slice(String source, String label, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到" + label + "：" + from);
        int end = to == null ? source.length() : source.indexOf(to, start);
        assertTrue(end > start, label + " 之后找不到结尾标记：" + to);
        return source.substring(start, end);
    }

    // ───────────────────── 快照：自建 + 收藏，翻到底，去重 ─────────────────────

    @Test
    @DisplayName("账号歌单快照并上收藏那一条，两条接口都按游标翻到底")
    void snapshotMergesSelfBuiltAndCollected() throws IOException {
        String service = source();
        String fetch = slice(service, "快照读取",
                "private static List<PlayList> fetchUserPlaylists(int limit)",
                "private static void collectPlaylistCards");
        assertTrue(fetch.contains("fetchAllPcPages(buildPcMePlaylistsUrl(), \"playlists\""),
                "自建歌单没按游标翻：只读一页就会漏掉后半段");
        assertTrue(fetch.contains("collectionPlaylists("),
                "收藏的歌单没并进快照 —— 用户报的「收藏不全部展示」就是这一条");
        assertTrue(fetch.contains("putIfAbsent"), "两条接口之间没按平台 ID 去重");

        String collection = slice(service, "收藏读取",
                "public static List<PlayList> collectionPlaylists(int limit)",
                "public static List<Music> collectionTracks");
        assertTrue(collection.contains("fetchAllPcPages(buildPcMeCollectionUrl(), \"mixed_collections\""),
                "收藏那条又退回只读一页");
        // 收藏是混合列表：专辑卡片字段叫 album、标题在 name 里，跟歌单不同形，本轮按约定不显示。
        String cards = slice(service, "卡片归并",
                "private static void collectPlaylistCards",
                "private static JsonArray fetchAllPcPages");
        assertTrue(cards.contains("\"playlist\".equals(kind)"), "收藏里的专辑条目没有跳过");
        assertTrue(cards.contains("ACCOUNT_PLAYLIST_PREFIX +"),
                "账号歌单的 ID 没打前缀：取曲目时会走回匿名接口");
    }

    @Test
    @DisplayName("分页的收尾只看 has_more 与 next_cursor，不看这一页空不空")
    void pagingDoesNotStopOnEmptyPage() throws IOException {
        String service = source();
        String pages = slice(service, "分页",
                "private static JsonArray fetchAllPcPages", "// ── 我的歌单");
        assertTrue(pages.contains("if (!truthy(response, \"has_more\")) break;"),
                "没有按 has_more 收尾");
        assertTrue(pages.contains("next.isEmpty() || next.equals(cursor) || !cursors.add(next)"),
                "没有防住不前进或重复的游标");
        // 实测 /me/playlist 在 count=2 时中间页回 0 条却仍 has_more=true：
        // 以「这页是空的」收尾会把账号后半段的歌单整段丢掉。
        assertFalse(pages.contains("size() == 0) break") || pages.contains("isEmpty()) break"),
                "分页又拿「这一页是空的」当终止条件了");
        assertTrue(pages.contains("\"count\", String.valueOf(PAGE_SIZE)"),
                "分页请求没带 count —— 上游不带 count 时回的是短页");
    }

    // ───────────────────── 取曲目：账号带 Cookie，导入保持匿名 ─────────────────────

    @Test
    @DisplayName("playlistTracks 按 acct: 前缀分派两条接口")
    void dispatcherRoutesByPrefix() throws IOException {
        String service = source();
        String router = slice(service, "分派",
                "public static List<Music> playlistTracks(String playlistId, int limit)",
                "private static List<Music> accountPlaylistTracks");
        assertTrue(router.contains("startsWith(ACCOUNT_PLAYLIST_PREFIX)"), "没按账号前缀分派");
        assertTrue(router.contains("accountPlaylistTracks(") && router.contains("anonymousPlaylistTracks("),
                "两条路少了一条");
    }

    @Test
    @DisplayName("账号取曲目带 Cookie 走 PC 详情接口，并翻到 has_more 收尾")
    void accountPathUsesCookieAndPagesToTheEnd() throws IOException {
        String service = source();
        String account = slice(service, "账号取曲目",
                "private static List<Music> accountPlaylistTracks",
                "private static List<Music> anonymousPlaylistTracks");
        assertTrue(account.contains("buildPcPlaylistDetailUrl(")
                        && service.contains("PC_PLAYLIST_DETAIL_ENDPOINT = \"https://api.qishui.com/luna/pc/playlist/detail\""),
                "账号歌单没走 PC 的 playlist/detail：匿名那条给不出完整列表");
        assertTrue(account.contains("QishuiSessionStore.getCookieHeader()"), "账号那条没带 Cookie");
        assertTrue(account.contains("if (!truthy(response, \"has_more\")) break;"),
                "账号那条没按 has_more 翻到底");
        assertTrue(account.contains("int target = MAX_PAGES * PAGE_SIZE;"),
                "账号那条又拿 count_tracks 当上限：那个字段实测会偏小");
        // 凭据失效或这条接口挂了要能退回匿名那条，别把面板画成空白。
        assertTrue(account.contains("return anonymousPlaylistTracks(playlistId, limit);"),
                "账号那条失败后没有兜底");
    }

    @Test
    @DisplayName("导入那条一路不带凭据，也不再掐 500 首 / 12 页")
    void importPathStaysAnonymousAndUnclamped() throws IOException {
        String service = source();
        String anon = slice(service, "匿名取曲目",
                "private static List<Music> anonymousPlaylistTracks",
                "private static final Pattern PLAYLIST_ID_PATH");
        assertFalse(anon.contains("getCookieHeader"), "匿名路径带上登录 Cookie 了");
        assertFalse(anon.contains("pcHeaders"), "匿名路径带上了 PC 鉴权头");
        assertFalse(anon.contains("Math.min(500"), "匿名路径还掐着 500 首");
        assertFalse(anon.contains("page < 12"), "匿名路径还掐着 12 页");
        assertTrue(anon.contains("Collections.emptyMap()") && anon.contains("ANDROID_USER_AGENT"),
                "匿名那条的接口形状变了：请对照实测重新确认无鉴权请求");
    }

    @Test
    @DisplayName("快照刷新按合并后的上限拉取，不再是 100")
    void snapshotRefreshCapFollowsMerge() throws IOException {
        String service = source();
        assertTrue(service.contains("fetchUserPlaylists(ACCOUNT_PLAYLIST_CAP)"),
                "快照还按 100 条掐：自建 + 收藏合并后会被削掉");
    }
}
