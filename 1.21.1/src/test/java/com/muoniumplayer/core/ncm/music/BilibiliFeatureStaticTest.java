package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B 站音源「登录 / 歌词 / 合集分P / 收藏夹」四期的离线回归测试。
 *
 * <p>分两层。<b>纯逻辑</b>钉住那些一旦写错就没有局部症状的字符串与状态变换：WBI 的 mixin key
 * 打乱（错了是所有签名接口集体 -403）、扫码业务码到 {@code QrLoginState} 的映射（错了是用户盯着
 * 一张永不会动的二维码）、Cookie 拼接（错了是风控）、字幕时间戳与 LRC 的往返（错了是整屏歌词不动）。
 * <b>接线</b>静态扫源码，钉住三条容易被后续改动拆掉的不变量：播放链路永不返回 FLAC、
 * 界面每帧调用的状态查询不发请求、B 站凭据既不落 {@code music_auth.json} 也不显示在界面上。</p>
 *
 * <p>不启动 Minecraft、不联网、不读写真实配置。</p>
 */
class BilibiliFeatureStaticTest {

    /** bilibili-API-collect 公开文档里的 WBI 示例键对与它的 mixin key。 */
    private static final String DOC_IMG_KEY = "7cd084941338484aae1ad9425b84077c";
    private static final String DOC_SUB_KEY = "4932caff0ff746eab6f01bf08b70ac45";
    private static final String DOC_MIXIN_KEY = "ea1db124af3c7062474693fa704f4ff8";

    private static final Path MUSIC_DIR = Path.of("com", "muoniumplayer", "core", "ncm", "music");
    private static final Path SCREENS_DIR = Path.of("com", "muoniumplayer", "core", "screens", "ncm");
    private static final Path PANELS_DIR = SCREENS_DIR.resolve("panels");

    private static String read(Path relativeDir, String fileName) throws IOException {
        Path file = ProjectPaths.sharedJava().resolve(relativeDir).resolve(fileName);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    // ════════════════════════════ WBI 签名（纯逻辑） ════════════════════════════

    @Test
    @DisplayName("mixin key 打乱与官方文档示例逐字符一致")
    void mixinKeyMatchesPublishedVector() {
        assertEquals(DOC_MIXIN_KEY, BilibiliMusicService.mixinKey(DOC_IMG_KEY + DOC_SUB_KEY));
        // 只取前 32 位：打乱表有 64 项，raw 更长时尾部必须被丢掉。
        assertEquals(32, BilibiliMusicService.mixinKey(DOC_IMG_KEY + DOC_SUB_KEY + "tail").length());
    }

    @Test
    @DisplayName("canonical query 按键排序、丢掉 !'()* 并做 URL 编码")
    void canonicalQueryIsSortedAndSanitized() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("zab", "514");
        params.put("foo", "114");
        params.put("bar", "191919");
        assertEquals("bar=191919&foo=114&zab=514", BilibiliMusicService.canonicalQuery(params));

        TreeMap<String, String> hostile = new TreeMap<>();
        hostile.put("keyword", "a!b'c(d)e*f");
        assertEquals("keyword=abcdef", BilibiliMusicService.canonicalQuery(hostile),
                "B 站签名前会把这几个字符从参数值里剔除，两边算的不是同一份串就对不上");

        TreeMap<String, String> spaced = new TreeMap<>();
        spaced.put("keyword", "周杰伦 晴天");
        assertTrue(BilibiliMusicService.canonicalQuery(spaced).contains("%E5%91%A8"),
                "中文必须按 UTF-8 百分号编码");
    }

    @Test
    @DisplayName("md5 输出小写十六进制，可直接当 w_rid")
    void md5IsLowercaseHex() throws Exception {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", BilibiliMusicService.md5Hex("abc"));
    }

    @Test
    @DisplayName("从 wbi_img 的 URL 里剥出 key")
    void fileNameWithoutExtension() {
        assertEquals(DOC_IMG_KEY, BilibiliMusicService.fileNameWithoutExtension(
                "https://i0.hdslb.com/bfs/wbi/" + DOC_IMG_KEY + ".png"));
    }

    // ════════════════════════════ 扫码登录（纯逻辑） ════════════════════════════

    /**
     * 按 {@code name()} 比对：{@code QrLoginState} 来自内置的第三方库，测试不该跟着它的
     * 导入路径走，常量名反而是两边都不会随便改的契约。
     */
    @Test
    @DisplayName("B 站业务码映射到平台无关的扫码状态，未知码不咽进 WAITING")
    void qrCodeMapping() {
        assertEquals("CONFIRMED", BilibiliLogin.mapToState(0).name());
        assertEquals("WAITING", BilibiliLogin.mapToState(86101).name());
        assertEquals("SCANNED", BilibiliLogin.mapToState(86090).name());
        assertEquals("EXPIRED", BilibiliLogin.mapToState(86038).name());
        // 新出现的码必须被看见：把它当「等待扫码」会让用户盯着一张永远不会动的二维码。
        assertEquals("ERROR", BilibiliLogin.mapToState(-352).name());
    }

    @Test
    @DisplayName("Set-Cookie 解析丢掉属性段与空值，保留登录必需的三个")
    void setCookieParsing() {
        Map<String, String> cookies = BilibiliLogin.parseSetCookies(List.of(
                "SESSDATA=abc%2Cdef; Path=/; HttpOnly",
                "bili_jct=deadbeef; Max-Age=0",
                "buvid3=XYZ;",
                "DELETE_ME=; Path=/",
                "=novalue",
                ""));
        assertEquals("abc%2Cdef", cookies.get("SESSDATA"));
        assertEquals("deadbeef", cookies.get("bili_jct"));
        assertEquals("XYZ", cookies.get("buvid3"));
        assertFalse(cookies.containsKey("DELETE_ME"), "B 站用空值删 cookie，收下来只会污染拼接串");
        assertFalse(cookies.containsKey(""), "没有名字的段不该进表");
        assertEquals(3, cookies.size());
    }

    @Test
    @DisplayName("Cookie 拼接按名字排序，与下发顺序无关")
    void cookieJoinIsOrderIndependent() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("SESSDATA", "s");
        first.put("buvid3", "b");
        first.put("bili_jct", "j");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("bili_jct", "j");
        second.put("buvid3", "b");
        second.put("SESSDATA", "s");
        String expected = "SESSDATA=s; bili_jct=j; buvid3=b";
        assertEquals(expected, BilibiliSessionStore.joinCookies(first));
        assertEquals(expected, BilibiliSessionStore.joinCookies(second),
                "generate 与 poll 两跳先下发哪几个不固定，拼接结果必须可复现");

        Map<String, String> hostile = new LinkedHashMap<>();
        hostile.put("x", "a;b");
        hostile.put("y", "c");
        assertEquals("y=c", BilibiliSessionStore.joinCookies(hostile),
                "含 ; 的值会被丢掉：那是在往请求头里注入字段");
        assertEquals("", BilibiliSessionStore.joinCookies(null));
    }

    // ════════════════════════════ 合集 / 分P / 收藏夹 id ════════════════════════════

    @Test
    @DisplayName("三种 B 站歌单形态靠前缀分派，id 往返稳定")
    void playlistIdRoundTrip() {
        String pages = BilibiliPlaylistService.pagesPlaylistId("BV1xx411c7mD");
        String season = BilibiliPlaylistService.seasonPlaylistId(123L, 456L, "BV1xx411c7mD");
        String folder = BilibiliFavoriteService.favPlaylistId("99");
        assertEquals("bili-bvid:BV1xx411c7mD", pages);
        assertEquals("bili-season:123:456:BV1xx411c7mD", season);
        assertEquals("bili-fav:99", folder);
        assertTrue(BilibiliPlaylistService.supports(pages));
        assertTrue(BilibiliPlaylistService.supports(season));
        assertTrue(BilibiliFavoriteService.supports(folder));
        assertEquals("99", BilibiliFavoriteService.mlidOf(folder));
        assertEquals("", BilibiliFavoriteService.mlidOf(pages), "不是收藏夹的 id 不该被切成 mlid");
        assertFalse(BilibiliFavoriteService.supports(pages));
    }

    @Test
    @DisplayName("派生的 long 主键稳定、非负且互不相同")
    void stablePlaylistIdsAreDeterministicAndPositive() {
        String pages = BilibiliPlaylistService.pagesPlaylistId("BV1xx411c7mD");
        assertEquals(BilibiliPlaylistService.stableId(pages), BilibiliPlaylistService.stableId(pages));
        assertTrue(BilibiliPlaylistService.stableId(pages) > 0L, "负数 id 会被当成临时歌单");
        assertNotEquals(BilibiliPlaylistService.stableId(pages),
                BilibiliPlaylistService.stableId(BilibiliPlaylistService.pagesPlaylistId("BV1yy411c7mE")));
        // 收藏夹与分P不能撞主键，否则侧栏点开会串成同一张表。
        assertNotEquals(BilibiliPlaylistService.stableId(pages),
                BilibiliPlaylistService.stableId(BilibiliFavoriteService.favPlaylistId("1")));
    }

    @Test
    @DisplayName("分P 的 sourceKey 只在第二页起带页码")
    void sourceKeyOnlyAppendsPageWhenNeeded() {
        assertEquals("BV1xx411c7mD", BilibiliMusicService.sourceKey("BV1xx411c7mD", 1));
        assertEquals("BV1xx411c7mD:3", BilibiliMusicService.sourceKey("BV1xx411c7mD", 3));
    }

    // ════════════════════════════ 字幕 → LRC ════════════════════════════

    @Test
    @DisplayName("时间戳往返一致，超过一小时仍按两段分钟写")
    void timestampRoundTrip() {
        assertEquals("[00:12.340]", BilibiliSubtitleService.formatTimestamp(12.34D));
        assertEquals(12.34D, BilibiliSubtitleService.parseTimestamp(
                BilibiliSubtitleService.formatTimestamp(12.34D)), 0.001D);

        String overAnHour = BilibiliSubtitleService.formatTimestamp(4512.34D);
        assertEquals("[75:12.340]", overAnHour);
        // LRC 规范与现有 LyricParser 只认 mm:ss.SSS；写成 hh:mm:ss 会让整行解析失败。
        assertEquals(2, overAnHour.split(":").length);
        assertEquals(4512.34D, BilibiliSubtitleService.parseTimestamp(overAnHour), 0.001D);
    }

    @Test
    @DisplayName("非法时间戳不炸，落成 0 或 -1 由调用方兜底")
    void timestampEdges() {
        assertEquals("[00:00.000]", BilibiliSubtitleService.formatTimestamp(-5D));
        assertEquals("[00:00.000]", BilibiliSubtitleService.formatTimestamp(Double.NaN));
        assertEquals("[00:00.000]", BilibiliSubtitleService.formatTimestamp(Double.POSITIVE_INFINITY));
        assertEquals(-1D, BilibiliSubtitleService.parseTimestamp("12:34"));
        assertEquals(-1D, BilibiliSubtitleService.parseTimestamp(""));
    }

    @Test
    @DisplayName("字幕正文解析：跳过缺字段、倒挂区间与空文本")
    void subtitleBodyParsing() {
        List<BilibiliSubtitleService.SubtitleLine> lines = BilibiliSubtitleService.parseSubtitleBody(
                "{\"body\":[{\"from\":1.0,\"to\":2.0,\"content\":\"第一句\"},"
                        + "{\"from\":5.0,\"to\":3.0,\"content\":\"区间倒挂\"},"
                        + "{\"from\":6.0,\"to\":7.0,\"content\":\"\"},"
                        + "{\"from\":8.0,\"to\":9.0},"
                        + "{\"from\":9.0,\"to\":10.0,\"content\":\"第二句\"}]}");
        assertEquals(2, lines.size());
        assertEquals("第一句", lines.get(0).content);
        assertEquals("第二句", lines.get(1).content);
        assertTrue(BilibiliSubtitleService.parseSubtitleBody("not json").isEmpty());
        assertTrue(BilibiliSubtitleService.parseSubtitleBody("").isEmpty());
    }

    @Test
    @DisplayName("字幕清单优先中文，没有中文退回第一条，空清单返回空串")
    void subtitleLanguagePreference() {
        JsonArray subtitles = JsonParser.parseString(
                "[{\"lan\":\"en-US\",\"subtitle_url\":\"//aisub/hk/1.json\"},"
                        + "{\"lan\":\"ai-zh\",\"subtitle_url\":\"//aisub/zh/2.json\"}]").getAsJsonArray();
        assertEquals("https://aisub/zh/2.json", BilibiliSubtitleService.pickSubtitleUrl(subtitles),
                "清单里 en 在前，必须跳过它选中文那一条");

        JsonArray englishOnly = JsonParser.parseString(
                "[{\"lan\":\"en-US\",\"subtitle_url\":\"//aisub/hk/1.json\"}]").getAsJsonArray();
        assertEquals("https://aisub/hk/1.json", BilibiliSubtitleService.pickSubtitleUrl(englishOnly),
                "一条英文字幕对中文用户也比没有强");

        assertTrue(BilibiliSubtitleService.pickSubtitleUrl(null).isEmpty());
        // 未登录时 B 站回的正是空数组，这一条是整个歌词链路的开关。
        assertTrue(BilibiliSubtitleService.pickSubtitleUrl(new JsonArray()).isEmpty());
    }

    // ════════════════════════════ 接线与不变量（源码扫描） ════════════════════════════

    @Test
    @DisplayName("播放链路永不返回 FLAC/杜比，高解析只在下载路径放开")
    void playbackNeverGetsHiRes() throws IOException {
        String source = read(MUSIC_DIR, "BilibiliMusicService.java");
        assertTrue(body(source, "public static ResolveResult getSongUrl")
                        .contains("resolve(sourceId, requestedQuality, false)"),
                "播放必须走 allowHires=false：AacAudioDecoder 只认 AAC-LC 分片");
        assertTrue(body(source, "public static ResolveResult getDownloadUrl")
                        .contains("Quality.LOSSLESS, true"),
                "下载才有放开高解析的入口");
        assertTrue(body(source, "private static ResolveResult resolve")
                        .contains("allowHires && queryVipAccount()"),
                "高解析还要叠一个大会员判定");
    }

    @Test
    @DisplayName("界面每帧调用的状态查询不发请求")
    void uiStateAccessorsStayOffline() throws IOException {
        String service = read(MUSIC_DIR, "BilibiliMusicService.java");
        assertNoNetwork(service, "public static boolean isVipAccount()",
                "账号面板的 veil 回调每帧问一次会员");
        assertNoNetwork(service, "public static boolean isLoggedIn()",
                "登录态只看本地 SESSDATA");
        assertNoNetwork(service, "public static String getAccountId()",
                "UID 走本地快照，收藏/字幕接口用它当参数");

        String favorites = read(MUSIC_DIR, "BilibiliFavoriteService.java");
        assertNoNetwork(favorites, "public static boolean isFavouredLocally(Music music)",
                "红心状态每行每帧都要问，挂上去就是翻一页发几十次请求");
    }

    @Test
    @DisplayName("B 站歌单不再静默走网易云解析")
    void playlistRoutesToBilibili() throws IOException {
        String source = read(MUSIC_DIR.resolve("dto"), "PlayList.java");
        // 结束标记不能写 "playlistTrackAll"：这一支的注释里正好在讲它，会把片段切短。
        String branch = between(source, "} else if (getPlatform() == MusicPlatform.BILIBILI)",
                "playlist song detail response does not contain songs");
        assertTrue(branch.contains("BilibiliPlaylistService.tracks(getPlatformPlaylistId())"),
                "缺这一支时 B 站歌单会掉进网易云分支，拿字符串 id 去要号段 → 静默空列表");
        assertTrue(branch.contains("loadedMusics == null"),
                "取失败要抛出来，不能和「这个歌单本来就是空的」混在一起");
    }

    @Test
    @DisplayName("凭据独立落盘，界面不外泄 Cookie")
    void credentialsStayLocalAndHidden() throws IOException {
        String config = read(Path.of("com", "muoniumplayer", "core", "settings"), "ConfigPaths.java");
        assertTrue(config.contains("bilibili_auth.json"), "B 站凭据必须单开一个文件");

        String store = read(MUSIC_DIR, "BilibiliSessionStore.java");
        assertFalse(store.contains("MUSIC_AUTH"),
                "music_auth.json 由 Cadence 的凭据存储整段重写，写进去的 B 站 Cookie 会被丢掉");
        assertTrue(store.contains("BILIBILI_AUTH"));

        for (String screen : List.of("AccountManagerOverlay.java", "MusicSourceOverlay.java")) {
            String source = read(SCREENS_DIR, screen);
            assertFalse(source.contains("getCookieHeader"), screen + " 不得读取 Cookie 内容");
            assertFalse(source.toUpperCase(Locale.ROOT).contains("SESSDATA"),
                    screen + " 不得出现 Cookie 字段名（面板承诺不显示 Cookie 内容）");
        }
    }

    @Test
    @DisplayName("四期功能各自在界面上有入口")
    void featuresAreWiredIntoUi() throws IOException {
        String overlay = read(SCREENS_DIR, "AccountManagerOverlay.java");
        assertTrue(overlay.contains("MusicPlatform.BILIBILI"), "账号面板没有 B 站这一行");
        assertTrue(overlay.contains("哔哩哔哩 App"), "扫码提示没写用哪个 App");

        String navigate = read(PANELS_DIR, "NavigateBar.java");
        assertTrue(navigate.contains("BilibiliPlaylistService.searchPlaylists"), "侧栏没有合集/分P搜索");
        assertTrue(navigate.contains("BilibiliFavoriteService.getUserPlaylistsSnapshot"), "侧栏没有收藏夹");
        // 首页文案承诺了「粘贴 BV号或视频链接」，这条入口必须在；否则 playlistsOfVideo 就是死代码。
        // 入口点开的是二级导入面板（可自由输入、也可粘贴），不是原来那个"点一下读剪贴板"的方法。
        assertTrue(navigate.contains("openBilibiliImport"), "侧栏没有导入稿件入口");
        assertFalse(navigate.contains("importBilibiliPlaylistFromClipboard"),
                "盲读剪贴板的一键导入要摘掉：粘错东西时用户连看到原文的机会都没有");

        String importSheet = read(SCREENS_DIR, "BilibiliImportOverlay.java");
        assertTrue(importSheet.contains("BilibiliPlaylistService.playlistsOfVideo"), "导入面板没接稿件解析");
        assertTrue(importSheet.contains("KeyboardUtils.getClipboardString"), "导入面板没有「粘贴」按钮");
        assertTrue(importSheet.contains("setEnabled(true).setFocused(true)"),
                "输入框没聚焦就是「打开还得先点一下才能打字」");
        assertNoNetwork(importSheet, "public void onInit()", "面板搭骨架时不许碰网络");
        assertTrue(between(importSheet, "private void submit()", "private void finish")
                        .contains("MultiThreadingUtil.runAsync"),
                "解析（含短链展开）必须在后台线程，放在点击回调里就是当帧卡住");

        String home = read(PANELS_DIR, "HomePanel.java");
        assertTrue(home.contains("BilibiliFavoriteService.userPlaylists"), "首页没有 B 站内容");

        String widget = read(PANELS_DIR, "MusicWidget.java");
        assertTrue(widget.contains("BilibiliFavoriteService.setFavourite"), "红心没接收藏写入");
        assertTrue(widget.contains("canAddToPlaylist"), "「加入歌单」没按源分开（B 站该隐藏它）");

        String cadence = read(MUSIC_DIR, "NeteaseMusicService.java");
        assertTrue(cadence.contains("BilibiliSubtitleService.getLyricText"), "歌词没接字幕链路");
        assertTrue(cadence.contains("BilibiliLogin.createQrCode"), "登录没接扫码");
    }

    // ════════════════════════════ 小工具 ════════════════════════════

    /** 方法体（不含签名与收尾大括号）。{@code signature} 必须是首次出现的那个方法。 */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "源码里找不到 " + signature);
        int open = source.indexOf('{', start);
        int close = source.indexOf("\n    }", open);
        assertTrue(open > start && close > open, signature + " 的方法体没找到结尾");
        return source.substring(open, close);
    }

    private static void assertNoNetwork(String source, String signature, String why) {
        String method = body(source, signature);
        for (String forbidden : List.of("getJson", "wbiGet", "postForm", "HTTP.send", "resolveTrack")) {
            assertFalse(method.contains(forbidden),
                    why + "：方法体里出现 " + forbidden + " 就是把渲染线程挂到网络上");
        }
    }

    /** 取 [startMarker, endMarker) 之间的源码片段；两个标记都必须存在。 */
    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "源码里找不到 " + startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(end > start, "找不到 " + startMarker + " 之后的 " + endMarker);
        return source.substring(start, end);
    }
}
