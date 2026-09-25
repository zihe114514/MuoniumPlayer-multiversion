package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 汽水那条导入链（ID 解析 + 匿名详情）的离线回归测试。
 *
 * <p>全渠道导入的接线、存储与识别规则在 {@link PlaylistImportStaticTest}；这里只钉汽水自己
 * 那段最容易被上游字段变化波及的逻辑。</p>
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

    // ──────────────────────────── 匿名与账号解耦（源码扫描） ────────────────────────────

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
    @DisplayName("账号歌单直接摊在侧栏「我的账号歌单」下，不再是点开才拉的二级页")
    void qishuiUserPlaylistsRenderInlineInSidebar() throws IOException {
        String sidebar = read(ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "screens", "ncm", "panels")),
                "NavigateBar.java");
        assertTrue(sidebar.contains("QishuiMusicService.getUserPlaylistsSnapshot()"),
                "侧栏没走快照：账号歌单大概又回到了点开才同步拉取的老路");
        assertTrue(sidebar.contains("QishuiMusicService.ensureUserPlaylistsAsync"),
                "侧栏未接线异步加载");
        assertFalse(sidebar.contains("我的汽水歌单"), "跳二级页的旧入口还留在侧栏");
        assertFalse(sidebar.contains("loadQishuiUserPlaylists"), "二级页加载方法没删干净");

        String service = read(ProjectPaths.sharedJava()
                .resolve(Path.of("com", "muoniumplayer", "core", "ncm", "music")),
                "QishuiMusicService.java");
        assertTrue(service.contains("public static void ensureUserPlaylistsAsync"), "服务侧缺少异步快照入口");
        assertTrue(service.contains("new Thread") && service.contains("setDaemon(true)"),
                "快照拉取必须离开主线程：Minecraft 主线程打网络会卡死整个游戏");
        // 凭据一变（导入新 Cookie / 退出登录），旧账号的歌单快照必须作废。
        int logout = service.indexOf("public static void logout()");
        assertTrue(service.indexOf("clearUserPlaylistsCache()", logout) > 0
                && service.indexOf("clearUserPlaylistsCache()", logout) < logout + 240,
                "logout 没有作废歌单快照");
        int cookie = service.indexOf("public static CookieImport importCookie");
        int cookieEnd = service.indexOf("/**", cookie + 10);
        String cookieBody = service.substring(cookie, cookieEnd > cookie ? cookieEnd : service.length());
        assertTrue(cookieBody.contains("clearUserPlaylistsCache()"), "Cookie 登录后没有作废歌单快照");
    }
}
