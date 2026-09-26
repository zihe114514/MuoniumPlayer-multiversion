package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全渠道「导入歌单」的离线回归测试：自动识别平台、只存引用的持久化、侧栏分区与导入面板接线。
 *
 * <p>钉住的三条不变量：<b>识别</b>——各平台分享文案必须落到唯一正确的渠道，裸数字 ID 的长度
 * 启发式不许漂（网易云 8-9 位 / 汽水 10-12 位 / QQ 13+ 位这三档区间重叠，是整套自动识别里
 * 唯一没有域名可依据的环节）；<b>存储</b>——引用往返后仍带平台与原 ID，且键必须含平台名，
 * 否则不同平台的同号歌单会互相覆盖；<b>接线</b>——侧栏「导入歌单」在「我的账号歌单」之上、
 * 全渠道都有「＋」入口，且整条导入链不读写任何账号凭据。</p>
 *
 * <p>不启动 Minecraft、不联网、不写真实配置文件。</p>
 */
class PlaylistImportStaticTest {

    private static String read(Path dir, String fileName) throws IOException {
        return new String(Files.readAllBytes(dir.resolve(fileName)), StandardCharsets.UTF_8);
    }

    private static Path musicDir() {
        return ProjectPaths.sharedJava().resolve(Path.of("com", "muoniumplayer", "core", "ncm", "music"));
    }

    // ──────────────────────────── 平台识别（纯逻辑） ────────────────────────────

    @Test
    @DisplayName("五个渠道的分享文案各自识别到唯一平台")
    void detectsEveryWiredPlatform() {
        assertEquals(MusicPlatform.NETEASE,
                PlaylistImportService.detect("看看这个歌单 https://music.163.com/playlist?id=88776655&csrc= 复制打开")
                        .platform);
        assertEquals(MusicPlatform.QQ,
                PlaylistImportService.detect("https://y.qq.com/n/ryqq/playlist/detail?id=7048155802")
                        .platform);
        assertEquals(MusicPlatform.KUGOU,
                PlaylistImportService.detect("https://www.kugou.com/yy/special/single/546903.html")
                        .platform);
        assertEquals(MusicPlatform.BILIBILI,
                PlaylistImportService.detect("【歌单合集】https://www.bilibili.com/video/BV1xx411c7mD/?p=1")
                        .platform);
        assertEquals(MusicPlatform.QISHUI,
                PlaylistImportService.detect("https://music.douyin.com/qishui/playlist/7398123456789012345")
                        .platform);
    }

    @Test
    @DisplayName("YouTube 明确拒绝，而不是误判成别的平台")
    void youtubeIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PlaylistImportService.detect("https://music.youtube.com/playlist?list=RDMMabc"));
        assertTrue(error.getMessage().contains("YouTube"), "拒因要写清是 YouTube 不支持：" + error.getMessage());
    }

    @Test
    @DisplayName("认不出的链接与空文本给出可操作提示")
    void rejectsUnidentifiableInput() {
        assertThrows(IllegalArgumentException.class, () -> PlaylistImportService.detect(""));
        assertThrows(IllegalArgumentException.class,
                () -> PlaylistImportService.detect("https://example.com/playlist?id=7048155802"));
    }

    @Test
    @DisplayName("裸数字按长度分档：网易云 8-9 位、汽水 10-12 位、QQ 13 位以上")
    void bareDigitsAreBucketedByLength() {
        assertEquals(MusicPlatform.NETEASE, PlaylistImportService.detect("88776655").platform);
        assertEquals(MusicPlatform.QISHUI, PlaylistImportService.detect("739812345678").platform);
        assertEquals(MusicPlatform.QQ, PlaylistImportService.detect("7048155802123").platform);
    }

    @Test
    @DisplayName("猜错平台时按长度就近换源重试")
    void retryPicksTheNeighbourPlatform() {
        assertEquals(MusicPlatform.QISHUI,
                PlaylistImportService.retryPlatform(MusicPlatform.NETEASE, "739812345678"));
        assertEquals(MusicPlatform.QISHUI,
                PlaylistImportService.retryPlatform(MusicPlatform.QQ, "739812345678"));
        assertEquals(MusicPlatform.NETEASE,
                PlaylistImportService.retryPlatform(MusicPlatform.QISHUI, "739812345678"));
        assertEquals(MusicPlatform.NETEASE,
                PlaylistImportService.retryPlatform(MusicPlatform.QQ, "88776655"));
    }

    @Test
    @DisplayName("酷狗 songlist(gcid_) 链接仍归酷狗，不会被当成网易云数字")
    void kugouCloudListLinkRoutesToKugou() {
        assertEquals(MusicPlatform.KUGOU,
                PlaylistImportService.detect("https://www.kugou.com/songlist/gcid_zyIxYzJm/")
                        .platform);
    }

    // ──────────────────────────── 引用持久化 ────────────────────────────

    @Test
    @DisplayName("引用经 serialize→deserialize 往返后仍带着平台与原歌单 ID")
    void referenceRoundTripsWithPlatform() throws Exception {
        PlayList original = new PlayList(987654321L, "深夜华语 · 收藏向", "https://p3-luna.douyinpic.com/img/x.png",
                42, 0L, null, "", 0L);
        original.setPlatform(MusicPlatform.QISHUI);
        original.setPlatformPlaylistId("7398123456789012345");

        JsonObject json = serialize(original);
        assertTrue(json.has("playlistId"), "落盘必须带平台侧歌单 ID，才能匿名再拉");
        assertEquals("qishui", json.get("platform").getAsString(), "落盘必须带平台，否则无从路由");

        PlayList restored = deserialize(json);
        assertEquals(987654321L, restored.getId(), "派生 long id 必须原样回来，否则封面纹理路径漂移");
        assertEquals("深夜华语 · 收藏向", restored.getName());
        assertEquals(42, restored.getCount());
        assertEquals(MusicPlatform.QISHUI, restored.getPlatform());
        assertEquals("7398123456789012345", restored.getPlatformPlaylistId());
    }

    @Test
    @DisplayName("旧汽水存档缺 platform 字段时归到 QISHUI，迁移不丢历史导入")
    void legacyQishuiEntryDefaultsToQishui() throws Exception {
        JsonObject legacy = new JsonObject();
        legacy.addProperty("id", 1234567890L);
        legacy.addProperty("playlistId", "7398123456789012345");
        legacy.addProperty("name", "旧版导入的汽水歌单");
        legacy.addProperty("cover", "");
        legacy.addProperty("count", 7);

        PlayList restored = deserialize(legacy);
        assertEquals(MusicPlatform.QISHUI, restored.getPlatform());
        assertEquals("7398123456789012345", restored.getPlatformPlaylistId());
    }

    @Test
    @DisplayName("存储键含平台名：不同平台的同号歌单不会互相覆盖")
    void keyIncludesPlatform() {
        String shared = "7048155802123";
        PlayList qq = new PlayList(1L, "QQ 号", "", 0, 0L, null, "", 0L);
        qq.setPlatform(MusicPlatform.QQ);
        qq.setPlatformPlaylistId(shared);
        PlayList netease = new PlayList(2L, "网易云号", "", 0, 0L, null, "", 0L);
        netease.setPlatform(MusicPlatform.NETEASE);
        netease.setPlatformPlaylistId(shared);

        assertEquals("qq:" + shared, ImportedPlaylistStore.keyOf(qq));
        assertEquals("netease:" + shared, ImportedPlaylistStore.keyOf(netease));
        assertFalse(ImportedPlaylistStore.keyOf(qq).equals(ImportedPlaylistStore.keyOf(netease)),
                "两平台同号必须落成两条不同记录");
    }

    private static JsonObject serialize(PlayList playlist) throws Exception {
        Method method = ImportedPlaylistStore.class.getDeclaredMethod("serialize", PlayList.class);
        method.setAccessible(true);
        return (JsonObject) method.invoke(null, playlist);
    }

    private static PlayList deserialize(JsonObject item) throws Exception {
        Method method = ImportedPlaylistStore.class.getDeclaredMethod("deserialize", JsonObject.class);
        method.setAccessible(true);
        return (PlayList) method.invoke(null, item);
    }

    // ──────────────────────────── 侧栏分区与面板接线 ────────────────────────────

    @Test
    @DisplayName("侧栏：本地歌单栏在账号歌单之上，且账号栏已改名")
    void sidebarSectionsAreOrdered() throws IOException {
        String source = read(ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "screens", "ncm", "panels")),
                "NavigateBar.java");
        // 这一栏后来扩成了「Mu本地歌单」：上面几张是用户自建的（曲目存本机），下面几张才是粘贴链接
        // 带进来的引用。分区顺序没变，所以这一条仍然归在这里测（建单/移除那条链见 LocalPlaylistStaticTest）。
        int importLabel = source.indexOf("\"Mu本地歌单\"");
        int accountLabel = source.indexOf("\"我的账号歌单\"");
        int playlistSource = source.indexOf("List<PlayList> pl = qqMode");
        assertTrue(importLabel >= 0, "侧栏没有「Mu本地歌单」栏");
        assertTrue(accountLabel >= 0, "「我的歌单」没改成「我的账号歌单」");
        assertTrue(importLabel < accountLabel, "本地歌单栏没有排在账号歌单之上");
        assertTrue(accountLabel < playlistSource, "账号歌单标题与账号歌单列表被拆开了");
        assertFalse(source.contains("\"我的歌单\""), "还残留旧栏名");

        assertTrue(source.contains("ImportedPlaylistStore.list()"), "侧栏未渲染本地导入歌单");
        assertTrue(source.contains("setOnSecondaryClick"), "导入项未挂右键移除");
        assertTrue(source.contains("removeImportedPlaylist"), "缺少移除逻辑");
        assertTrue(source.contains("removeLocalPlaylist"), "自建单没有自己的移除逻辑（删的和导入的不是同一件事）");
        assertTrue(source.contains("RoundedRectWidget plusH") && source.contains("RoundedRectWidget plusV"),
                "「＋」又退回成一个文字字符（那不是图标）");
        assertTrue(source.contains("importHit.setOnClickCallback"), "「＋」没有接上点击导入");
        // 「＋」挂在导入歌单标题行，且不再按平台分支：所有渠道都有导入入口。
        int plusRow = source.indexOf("importHeader.addChild(importHit)");
        assertTrue(plusRow > 0, "「＋」没有并进「导入歌单」标题行");
        assertFalse(source.contains("\"加\""), "旧的整行「加」导入条目还留在侧栏里");

        // 标题文字与「＋」共用同一条墨迹中心线：一个按行盒顶部摆、一个按 em 方格居中摆的话，
        // 加号会明显沉在文字下方（用户实拍反馈过）。
        assertTrue(source.contains("NcmSheet.inkTop(lblImported, importHeader.getHeight() * .5)"),
                "「导入歌单」标题没有按墨迹居中，会和「＋」错开");
        assertTrue(source.contains("NcmSheet.inkTop(lblPlaylists, playlistsHeader.getHeight() * .5)"),
                "「我的账号歌单」标题没有按墨迹居中，会和上一栏标题错开");
        // 「＋」两条短条都以 (cx, cy) 为中心，cy 取行高中点即与上面那条基准重合；臂长按栏名墨迹量。
        assertTrue(source.contains("double cy = importHit.getHeight() * .5"),
                "「＋」没有居中到栏名那条中心线上");
        assertTrue(source.contains("NcmSheet.labelInkHeight(lblImported)"),
                "「＋」的臂长写死了，没跟着栏名的墨迹高度走");
        assertFalse(source.contains("PartyIcons.ADD"), "「＋」又换回了 15 像素的实心圆盘");
    }

    @Test
    @DisplayName("导入面板：走自动识别入口、可输入可粘贴，且不碰账号凭据")
    void importPanelIsAnonymousAndAutoDetecting() throws IOException {
        String source = read(ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "screens", "ncm")),
                "PlaylistImportOverlay.java");
        assertTrue(source.contains("PlaylistImportService.importPlaylist"), "面板没有走自动识别导入入口");
        assertTrue(source.contains("ImportedPlaylistStore.add"), "面板没有把引用存进本地库");
        assertTrue(source.contains("fillFromClipboard"), "缺少「粘贴」按钮逻辑");
        assertTrue(source.contains("Keyboard.KEY_RETURN"), "输入框回车没有接导入");
        assertTrue(source.contains("YouTube"), "面板里没写 YouTube 不支持");
        assertFalse(source.contains("getCookieHeader"), "面板不得携带登录 Cookie —— 需求是匿名、不进账号");
        assertFalse(source.contains("QISHUI_AUTH"), "面板不得触碰账号凭据文件");
    }

    @Test
    @DisplayName("除汽水外的四个渠道导入实现都不读写账号凭据")
    void otherChannelsImportAnonymously() throws IOException {
        String source = read(musicDir(), "PlaylistImportService.java");
        assertTrue(source.contains("fcg_ucc_getcdinfo_byids_cp"), "QQ 缺少匿名歌单详情端点");
        assertTrue(source.contains("special/song"), "酷狗缺少精选歌单详情端点");
        assertTrue(source.contains("playlistsOfVideo"), "B 站缺少复用稿件合集/分 P 的导入路径");
        assertTrue(source.contains("/api/v6/playlist/detail"), "网易云缺少匿名歌单详情端点");
        assertFalse(source.contains("getCookieHeader"), "导入服务不得携带登录 Cookie");
        assertFalse(source.contains("QISHUI_AUTH"), "导入服务不得触碰账号凭据文件");
    }

    @Test
    @DisplayName("ConfigPaths 注册了跨平台导入存档，并保留旧汽水存档做一次性迁移")
    void configPathsRegistered() throws IOException {
        String source = read(ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "settings")), "ConfigPaths.java");
        assertTrue(source.contains("IMPORTED_PLAYLISTS"), "未注册跨平台导入歌单路径常量");
        assertTrue(source.contains("imported_playlists.json"), "导入歌单文件名不符");
        assertTrue(source.contains("qishui_playlists.json"), "旧汽水存档常量被删掉了，历史导入会凭空消失");
    }
}
