package com.muoniumplayer.core.screens.ncm;

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
 * 「Mu本地歌单」这条链路的离线契约测试。
 *
 * <p>本地歌单的大部分故障都不崩，只是<b>悄悄做错事</b>：跨源的歌被当成网易云的歌提交（写进别人家的
 * 歌单）、删掉的单子还在往网易云发收藏请求（动了用户的真账号）、侧栏那一栏改了名字但「＋」还开着
 * 旧面板。这些都不可能靠跑起来发现，所以把接线本身钉住。</p>
 *
 * <p>曲目的存档往返另测，见 {@code LocalPlaylistStoreTest}：那份是纯数据，能在内存里真跑。
 * 这里只读源码，因为侧栏与曲目行一加载就要建 GL 上下文。</p>
 */
class LocalPlaylistStaticTest {

    private static final Path NCM = ProjectPaths.sharedJava().resolve(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm"));
    private static final Path SCREEN = ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "NCMScreen.java"));
    private static final Path OVERLAY = ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "AddToPlaylistOverlay.java"));
    private static final Path CREATE_SHEET = ProjectPaths.sourceFile(Paths.get("com/muoniumplayer/core/screens/ncm", "PlaylistCreateSheet.java"));
    private static final Path PANELS = NCM.resolve("panels");
    private static final Path NAVIGATE = PANELS.resolve("NavigateBar.java");
    private static final Path PLAYLIST_PANEL = PANELS.resolve("PlaylistPanel.java");
    private static final Path MUSIC_WIDGET = PANELS.resolve("MusicWidget.java");

    private static String read(final Path path) throws IOException {
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\n]*", "");
    }

    private static int indexOf(final String source, final String needle, final String where) {
        int at = source.indexOf(needle);
        assertTrue(at >= 0, where + " 里找不到「" + needle + "」");
        return at;
    }

    @Test
    @DisplayName("侧栏那一栏改名叫 Mu本地歌单，「＋」开的是建单页")
    void sidebarColumnIsTheLocalPlaylistColumn() throws IOException {
        String body = read(NAVIGATE);
        indexOf(body, "\"Mu本地歌单\"", "侧栏栏名");
        // 栏名与入口必须一起换：只改文字、点下去还是那份只能粘链接的导入面板，就是「名字承诺了做不到」。
        // 反向断言比量距离稳：侧栏不该再有任何一处直接打开导入面板。
        indexOf(body, "openPlaylistCreate()", "侧栏的「＋」");
        assertFalse(body.contains("openPlaylistImport()"),
                "侧栏里还有一处直接开导入面板：那一处的「＋」开出去的还是旧的那份，建不了单");
        // 自建单要有自己的删除路径：导入的单子断的是引用，本地单删的是曲目本身。
        indexOf(body, "removeLocalPlaylist(ready)", "本地单的右键移除");
        indexOf(body, "LocalPlaylistStore.remove(playlist)", "removeLocalPlaylist 的实现");
    }

    @Test
    @DisplayName("自建单排在导入引用之前，空栏提示指向建单页")
    void localRowsComeBeforeImportedRows() throws IOException {
        String body = read(NAVIGATE);
        int local = indexOf(body, "List<PlayList> localPlaylists", "本地单那一段");
        int imported = indexOf(body, "List<PlayList> importedPlaylists", "导入单那一段");
        assertTrue(local < imported, "侧栏里导入的引用排到了自建单前面：用户自己造的那几张应该最先看到");
        // 只有两张列表都空才提示；漏掉本地那一半的话，新建完第一张单子提示还挂在下面。
        assertTrue(body.contains("localPlaylists.isEmpty() && importedPlaylists.isEmpty()"),
                "空栏提示的条件没把本地单算进去：建完第一张单子它还会赖在列表里");
    }

    @Test
    @DisplayName("建单页在四路派发里全都接上了（画、键、鼠、模态）")
    void createSheetIsWiredIntoEveryDispatchPath() throws IOException {
        String body = read(SCREEN);
        int modal = indexOf(body, "boolean isModalOverlayActive()", "模态判定");
        assertTrue(body.indexOf("playlistCreateSheet != null", modal) > 0
                        && body.indexOf("playlistCreateSheet != null", modal) < modal + 900,
                "建单页没进 isModalOverlayActive()：在它下面滚轮会连带滚到播放器列表");
        // 汽水云建单页与本地建单页同层同形状。e90e748 把渲染与键盘接上了、漏了点击与模态——
        // 「点击穿透到下方主界面 + 表单按钮点不了」就是那次漏接，这里四个派发口一起钉住。
        assertTrue(body.indexOf("qishuiCloudPlaylistSheet != null", modal) > 0
                        && body.indexOf("qishuiCloudPlaylistSheet != null", modal) < modal + 900,
                "汽水云建单页没进 isModalOverlayActive()：滚轮与模态闸门对它失效");
        int qishuiClicked = indexOf(body, "public void mouseClicked(", "鼠标派发");
        assertTrue(body.indexOf("qishuiCloudPlaylistSheet.onMouseClickReceived", qishuiClicked) > 0,
                "鼠标点击没派发给汽水云建单页：点击全部穿透到下方组件，表单按钮点不了");

        int clicked = indexOf(body, "public void mouseClicked(", "鼠标派发");
        assertTrue(body.indexOf("playlistCreateSheet.onMouseClickReceived", clicked) > 0,
                "鼠标点击没派发给建单页：输入框和按钮都点不着");

        // ESC 必须抢在把按键下发给控件树之前：输入框聚焦时会把所有按键都吞掉，包括 ESC，
        // 那样这一层就再也关不掉了（导入面板那一条注释写的是同一件事）。
        int escape = indexOf(body, "playlistCreateSheet.handleEscape();", "键盘派发");
        int escTest = body.lastIndexOf("keyCode == Keyboard.KEY_ESCAPE", escape);
        assertTrue(escTest > 0 && escape - escTest < 200,
                "ESC 不是在这一层的按键派发里先处理的：聚焦输入框时按 ESC 会被打字框吞掉");
        int keyDown = body.indexOf("playlistCreateSheet.onKeyTypedReceived", escape);
        assertTrue(keyDown > 0 && keyDown - escape < 400,
                "其余按键没有下发给建单页：输入框打不了字、Ctrl+V 也不粘贴");

        assertTrue(body.indexOf("playlistCreateSheet.renderWidget") > 0, "没有绘制这一层");
    }

    @Test
    @DisplayName("加入歌单弹窗一定列出自建单，并且不给它套同源闸")
    void addToPlaylistAcceptsLocalPlaylistsAcrossSources() throws IOException {
        String body = read(OVERLAY);
        int list = indexOf(body, "private List<PlayList> playListsForSource()", "歌单来源");
        assertTrue(body.indexOf("LocalPlaylistStore.list()", list) < list + 1200,
                "弹窗没把自建单列进来：跨源的歌就没有任何地方可以加了");

        int submit = indexOf(body, "private void submitToPlaylist(", "提交分支");
        int localGate = body.indexOf("isLocalPlaylist()", submit);
        int sameSource = body.indexOf("boolean sameSource", submit);
        assertTrue(localGate > 0 && localGate < sameSource,
                "本地单走到了同源判断之后：那张闸是给「提交给某个平台云端」写的，本地单不该被它挡住");
        indexOf(body, "LocalPlaylistStore.addTrack(playlist, music)", "本地单分支");
    }

    @Test
    @DisplayName("本地单不会向任何平台写：收藏那一格对它不成立")
    void localPlaylistNeverTouchesUpstream() throws IOException {
        String body = read(PLAYLIST_PANEL);
        int guard = indexOf(body, "private boolean canToggleSubscription()", "收藏可行性");
        assertTrue(body.indexOf("isLocalPlaylist()", guard) < guard + 400
                        && body.indexOf("isLocalPlaylist()", guard)
                                < body.indexOf("getPlatform() == MusicPlatform.KUGOU", guard),
                "本地单的 platform 字段顶着 NETEASE，不做这道早退就会拿负数 id 去调网易云的订阅接口");
        // 那一格换成重命名之后，搜索框的锚点必须知道它在同一排。
        int anchor = indexOf(body, "double searchAnchorX", "搜索框锚点");
        assertTrue(body.indexOf("btnRename.getRelativeX()", anchor) < anchor + 700,
                "搜索框还按「收藏不画就等于没控件」算锚点：会压在重命名那颗上面");
    }

    @Test
    @DisplayName("曲目行按这张单是不是本地单，决定移除按钮和它占的位")
    void removeButtonTracksThePlaylistKind() throws IOException {
        String body = read(MUSIC_WIDGET);
        int row = indexOf(body, "playList.isLocalPlaylist()", "移除按钮的条件");
        assertTrue(body.indexOf("LocalPlaylistStore.removeTrack", row) < row + 1400,
                "这一格只是画了个叉，没接移除");
        int reserve = indexOf(body, "private double rightActionsReserve()", "右侧预留宽度");
        assertTrue(body.indexOf("isLocalPlaylist()", reserve) > 0 && body.indexOf("isLocalPlaylist()", reserve) < reserve + 700,
                "预留宽度没把第四枚图标算进去：长标题会滑到叉号底下");
        // 「加入歌单」那颗对任何源都该在（只要有本地单可放），否则跨源的歌根本进不来。
        int canAdd = indexOf(body, "private static boolean canAddToPlaylist(", "加入歌单可行性");
        assertTrue(body.indexOf("LocalPlaylistStore.hasAny()", canAdd) > 0
                        && body.indexOf("LocalPlaylistStore.hasAny()", canAdd) < canAdd + 400,
                "「加入歌单」仍然只认网易云/酷狗：B 站、GD 那些歌没有入口加进本地单");
    }

    @Test
    @DisplayName("改名换的是当前这一页，不在历史里多压一页旧名字")
    void renameReplacesTheCurrentPanel() throws IOException {
        String body = read(CREATE_SHEET);
        int rename = indexOf(body, "private void renameTo(String name)", "改名分支");
        indexOf(body, "LocalPlaylistStore.rename(renaming, name)", "改名落盘");
        assertTrue(body.indexOf("replaceCurrentPanel", rename) > 0
                        && body.indexOf("replaceCurrentPanel", rename) < rename + 1200,
                "改名后用 setCurrentPanel 压了一页：按返回会回到那张旧名字的单子");
        // 重命名模式下「从链接导入」没有意义，那一段排位要交回给「保存」。
        assertTrue(body.contains("renaming == null ? \"新建\" : \"保存\""), "确认按钮没按模式改文案");
    }
}
