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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汽水「歌单导入（只存引用、匿名、不进账号）」的离线回归测试。
 *
 * <p>分三层。<b>纯逻辑</b>钉住 {@link QishuiMusicService#extractPlaylistId(String)}：分享链接、
 * query 参数、口令内嵌长数字、纯数字都能提出正确 ID，而口令里没有可用 ID 时必须返回 null（绝不能
 * 把手机号、时间戳之类误当歌单 ID，也不触发联网解析短链）。<b>持久化</b>用反射直接跑 store 的
 * {@code serialize}/{@code deserialize}，钉住一条引用往返后仍带着 platform=QISHUI 与原始歌单 ID ——
 * 这正是「点开时能被 {@code PlayList#queryMusics} 路由回 {@code playlistTracks} 匿名取曲目」的前提。
 * <b>接线</b>静态扫源码，钉住最容易在后续改动里被拆掉的两条不变量：{@code importPlaylist} 的详情
 * 请求走的是空 Cookie（匿名），以及侧栏确实在汽水模式下渲染本地导入歌单并给条目挂了右键移除。</p>
 *
 * <p>不启动 Minecraft、不联网、不写真实配置文件。</p>
 */
class QishuiLocalPlaylistStaticTest {

    private static final String SAMPLE_ID = "7398123456789012345";

    private static String read(Path dir, String fileName) throws IOException {
        return new String(Files.readAllBytes(dir.resolve(fileName)), StandardCharsets.UTF_8);
    }

    // ──────────────────────────── ID 解析（纯逻辑） ────────────────────────────

    @Test
    @DisplayName("纯数字 ID 直接返回，两端空白被裁掉")
    void plainIdWithWhitespace() {
        assertEquals(SAMPLE_ID, QishuiMusicService.extractPlaylistId(SAMPLE_ID));
        assertEquals(SAMPLE_ID, QishuiMusicService.extractPlaylistId("  " + SAMPLE_ID + "\n"));
    }

    @Test
    @DisplayName("路径形态与 query 形态都能提取")
    void urlForms() {
        assertEquals(SAMPLE_ID, QishuiMusicService.extractPlaylistId(
                "https://music.douyin.com/qishui/playlist/" + SAMPLE_ID + "?share_extra=abc"));
        assertEquals(SAMPLE_ID, QishuiMusicService.extractPlaylistId(
                "看看这个歌单 https://music.douyin.com/qishui/share/playlist?playlist_id="
                        + SAMPLE_ID + " 复制打开"));
        assertEquals(SAMPLE_ID, QishuiMusicService.extractPlaylistId(
                "https://music.douyin.com/some/page?id=" + SAMPLE_ID));
    }

    @Test
    @DisplayName("分享口令里内嵌的长数字被兜底提取")
    void embeddedLongDigits() {
        assertEquals(SAMPLE_ID, QishuiMusicService.extractPlaylistId(
                "复制打开抖音，看看【某某的歌单】" + SAMPLE_ID + " 长按此条消息"));
    }

    @Test
    @DisplayName("无有效 ID 的文本返回 null，不误判短数字")
    void rejectsJunk() {
        assertNull(QishuiMusicService.extractPlaylistId(null));
        assertNull(QishuiMusicService.extractPlaylistId(""));
        assertNull(QishuiMusicService.extractPlaylistId("hello world 123"));
        // 6 位以下太短，10 位以下不足以判定为歌单 ID；这里给一个 5 位数字，两种阈值都不满足。
        assertNull(QishuiMusicService.extractPlaylistId("order 12345 done"));
    }

    // ──────────────────────────── 持久化往返（引用） ────────────────────────────

    @Test
    @DisplayName("引用经 serialize→deserialize 往返后仍绑定 QISHUI 与原始歌单 ID")
    void referenceRoundTrips() throws Exception {
        PlayList original = new PlayList(987654321L, "深夜华语 · 收藏向", "https://p3-luna.douyinpic.com/img/x.png",
                42, 0L, null, "", 0L);
        original.setPlatform(MusicPlatform.QISHUI);
        original.setPlatformPlaylistId(SAMPLE_ID);

        JsonObject json = serialize(original);
        assertTrue(json.has("playlistId"), "落盘必须带汽水歌单 ID，才能匿名再拉");
        assertEquals(SAMPLE_ID, json.get("playlistId").getAsString());

        PlayList restored = deserialize(json);
        assertEquals(987654321L, restored.getId(), "派生 long id 必须原样回来，否则封面纹理路径漂移");
        assertEquals("深夜华语 · 收藏向", restored.getName());
        assertEquals("https://p3-luna.douyinpic.com/img/x.png", restored.getCoverUrl());
        assertEquals(42, restored.getCount());
        assertEquals(MusicPlatform.QISHUI, restored.getPlatform());
        assertEquals(SAMPLE_ID, restored.getPlatformPlaylistId());
    }

    private static JsonObject serialize(PlayList playlist) throws Exception {
        Method method = QishuiLocalPlaylistStore.class.getDeclaredMethod("serialize", PlayList.class);
        method.setAccessible(true);
        return (JsonObject) method.invoke(null, playlist);
    }

    private static PlayList deserialize(JsonObject item) throws Exception {
        Method method = QishuiLocalPlaylistStore.class.getDeclaredMethod("deserialize", JsonObject.class);
        method.setAccessible(true);
        return (PlayList) method.invoke(null, item);
    }

    // ──────────────────────────── 匿名 & 接线（源码扫描） ────────────────────────────

    @Test
    @DisplayName("importPlaylist 走空 Cookie（匿名），并把结果打成 QISHUI 引用")
    void importIsAnonymousAndTagged() throws IOException {
        String source = read(ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "ncm", "music")), "QishuiMusicService.java");
        int start = source.indexOf("public static PlayList importPlaylist");
        assertTrue(start >= 0, "importPlaylist 必须存在");
        int end = source.indexOf("public static List<Music> radioTracks", start);
        assertTrue(end > start, "importPlaylist 与 radioTracks 之间即导入方法体");
        String body = source.substring(start, end);

        assertFalse(body.contains("getCookieHeader"), "导入不得携带登录 Cookie —— 需求是匿名、不进账号");
        assertTrue(body.contains("Collections.emptyMap()"), "详情请求应保持无鉴权头");
        assertTrue(body.contains("ANDROID_USER_AGENT"), "沿用已验证的匿名 Android 端点");
        assertTrue(body.contains("setPlatform(MusicPlatform.QISHUI)"), "导入结果必须打成 QISHUI 平台");
        assertTrue(body.contains("setPlatformPlaylistId(id)"), "必须保留原始歌单 ID 供 playlistTracks 复用");
    }

    @Test
    @DisplayName("侧栏汽水模式渲染本地导入歌单并提供导入与右键移除")
    void sidebarWiresImportAndLocalList() throws IOException {
        String source = read(ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "screens", "ncm", "panels")),
                "NavigateBar.java");
        assertTrue(source.contains("openQishuiImport"), "缺少导入入口（应打开二级导入面板）");
        assertTrue(source.contains("QishuiLocalPlaylistStore.list()"), "侧栏未渲染本地导入歌单");
        assertTrue(source.contains("setOnSecondaryClick"), "导入项未挂右键移除");
        assertTrue(source.contains("removeImportedQishuiPlaylist"), "缺少移除逻辑");
    }

    @Test
    @DisplayName("导入面板：可输入可粘贴，落点仍是匿名导入 + 本地引用，不碰账号")
    void importPanelIsAnonymous() throws IOException {
        String source = read(ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "screens", "ncm")),
                "QishuiImportOverlay.java");
        assertTrue(source.contains("QishuiMusicService.importPlaylist"), "面板没有走匿名导入入口");
        assertTrue(source.contains("QishuiLocalPlaylistStore.add"), "面板没有把引用存进本地库");
        assertTrue(source.contains("fillFromClipboard"), "缺少「粘贴」按钮逻辑");
        assertTrue(source.contains("Keyboard.KEY_RETURN"), "输入框回车没有接导入");
        assertFalse(source.contains("getCookieHeader"), "面板不得携带登录 Cookie —— 需求是匿名、不进账号");
        assertFalse(source.contains("QISHUI_AUTH"), "面板不得触碰账号凭据文件");
    }

    @Test
    @DisplayName("ConfigPaths 注册了 qishui_playlists.json 落盘路径")
    void configPathRegistered() throws IOException {
        String source = read(ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "settings")), "ConfigPaths.java");
        assertTrue(source.contains("QISHUI_PLAYLISTS"), "未注册本地歌单路径常量");
        assertTrue(source.contains("qishui_playlists.json"), "本地歌单文件名不符");
    }
}
