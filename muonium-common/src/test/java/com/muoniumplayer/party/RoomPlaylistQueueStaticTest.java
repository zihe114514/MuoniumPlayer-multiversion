package com.muoniumplayer.party;

import com.google.gson.JsonObject;
import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「播放歌单」在客户端这一侧的<b>接线</b>：一整张单子怎么进房间、那颗按钮什么时候出现、
 * 界面上怎么把「这批是自动排的」说出来。
 *
 * <p>三档优先级本身（插队 &gt; 点歌 &gt; 歌单）由<b>服务端</b>实现并有 {@code core-test} 的
 * {@code RoomPlaylistBatchTest} 真测；客户端不参与排序 —— 这是刻意的：权限判定归服务端，
 * 否则版本 skew 会让同一房间里不同客户端给出不同顺序。所以这一组只能静态扫源码，钉住那些
 * 「塌了不报错、只是行为不对」的分界：</p>
 *
 * <ol>
 *   <li><b>灌单只发元数据，不在本机逐首取流。</b>一次点歌顺手把自己解得出的直链带上是省事；
 *       一次灌 100 首再这么做，就是拿自己的账号（或房间里那几个会员的账号）按着一张单猛抽一遍，
 *       而其中大部分要等几十分钟才轮到，那时链接早过期了。写成 {@code upload()} 一样的调用
 *       在编译上完全正常，症状却是灌完单卡半小时 —— 只能靠断言。</li>
 *   <li><b>灌单不许碰播放头</b>：这条路径里出现 {@code play_now} 就是「按下去把全房正在听的
 *       那首切了」，而需求里那句「已经播放的不算」说的正是不要打断。</li>
 *   <li><b>按钮的出现条件与可点条件是两件事</b>。出现只看这台服务端认不认这个 op（老服务端不发
 *       能力位 → 按钮不出现，而不是出现后按出一句 error）；可点才看权限。写反了会让普通成员以为
 *       房间坏了。</li>
 *   <li><b>歌单名这一个字符串在发送、显示、撤回三处必须是同一个来源</b>，否则长名单子会出现
 *       「界面上写着已排 30 首，点撤回一句都撤不动」。</li>
 * </ol>
 */
class RoomPlaylistQueueStaticTest {

    private static final Path PARTY = ProjectPaths.versionRoot()
            .getParent().resolve(Paths.get("mpparty-common", "src", "main", "java", "com", "muoniumplayer", "party"));

    // ═══════════════════════ 发送侧 ═══════════════════════

    @Test
    @DisplayName("整张歌单只发元数据：不在本机逐首解析直链")
    void batchCarriesMetadataOnly() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String body = region(client, "private static void sendBatch(",
                "房间是否正在接管播放");

        assertTrue(body.contains("MpProto.C_ENQUEUE_BATCH"), "走的是批量那一帧，不是逐首 enqueue");
        assertTrue(body.contains("MpTrack.toJson(usable.get(i), \"\", 0L, 0L)"),
                "url 传空串：链接由服务端按前瞻窗逐首向房间要，不是点歌人一次性备齐");
        assertFalse(body.contains("upload("),
                "批量路径不许复用 upload()：那条会当场在本机解析每一首的音频");
        assertFalse(body.contains("resolveByPlayer("),
                "同上，逐首取流的另一条路也不能出现在这里");
        assertTrue(body.contains("m.addProperty(\"playlist\", label)"),
                "歌单名要发出去：界面上「这批是自动排的」与「撤回这张单」都靠它");
        assertTrue(body.contains("m.add(\"tracks\", tracks)"), "曲目是数组");
        assertTrue(body.contains("from += BATCH_CHUNK"),
                "要分帧：一张 500 首的单子拼成一帧会撞单帧上限");
    }

    @Test
    @DisplayName("灌单这条路上没有切歌：正在播的那一首不会被一张单子打断")
    void batchNeverInterruptsTheCurrentTrack() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String body = region(client, "private static void sendBatch(",
                "房间是否正在接管播放");
        String entry = region(client, "public static boolean requestPlaylist(PlayList playlist)",
                "private static String playlistTitle(PlayList playlist)");
        assertTrue(entry.contains("loadMusicsWithCallback"),
                "必须先等曲目表加载完再发：直接读 getMusics() 会把还在加载中的单子灌成前几十首");

        for (String where : new String[]{body, entry}) {
            assertFalse(where.contains("C_PLAY_NOW"), "play_now 一出现就是「按下去立刻换歌」");
            assertFalse(where.contains("playNow("), "同上，那是同一个动作的方法名写法");
            assertFalse(where.contains("MpProto.C_NEXT"), "也不许顺手替房间切到下一首");
        }
    }

    @Test
    @DisplayName("能看见的按钮 ≠ 能按的按钮：出现只看能力，可点才看权限")
    void buttonAppearanceIsCapabilityWhileEnabledIsPermission() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));

        String supported = region(client, "public static boolean playlistSupported()",
                "把一张歌单排进房间");
        assertTrue(supported.contains("playlistBatchLimit > 0"),
                "老服务端不发这个能力位，取到 0 就该整颗不出现，而不是按出一句 error");
        assertTrue(supported.contains("room == null") || supported.contains("room != null"),
                "不在房间里时没有任何一起听入口该出现");
        assertTrue(supported.contains("!inChannel()"),
                "频道是全服共用的，那里不该让一个人倒进一百首");

        String can = region(client, "public static boolean canPlaylist()", "这颗按钮该不该出现");
        assertTrue(can.contains("playlistSupported()"), "可点必须建立在出现之上，否则两套判据会分叉");
        assertTrue(can.contains("playlistAdminOnly") && can.contains("hasControl()"),
                "服务端把灌单收给管理组时，普通成员看到的是置灰而不是没有");
    }

    @Test
    @DisplayName("歌单名一个来源：发送、显示、撤回比的是同一个串")
    void playlistLabelHasOneImplementation() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String titles = region(client, "private static String playlistTitle(PlayList playlist)",
                "分帧把这批歌排进房间");

        assertTrue(titles.contains("MpRoomPlaylists.labelOf(playlist)"),
                "标签只在 MpRoomPlaylists 一处算（含那套 80 字裁剪），否则撤回会比对另一个串");

        String lists = read(PARTY.resolve("MpRoomPlaylists.java"));
        String label = region(lists, "public static String labelOf(PlayList playlist)",
                "现在能选到的歌单");
        assertTrue(label.contains("substring(0, 80)") || label.contains("> 80"),
                "服务端把队列条目上的名字截到 80 字符，客户端不跟着截就出现「看得见、撤不动」");
    }

    @Test
    @DisplayName("撤回走的是「只撤这张单」，不是整条队列")
    void withdrawalTargetsOnePlaylist() throws IOException {
        String client = read(PARTY.resolve("MpPartyClient.java"));
        String body = region(client, "public static void clearPlaylist(String playlist)",
                "队列里现在挂着哪几张歌单");

        assertTrue(body.contains("MpProto.C_CLEAR_PLAYLIST"),
                "必须走服务端那一帧：逐首 removeEntry 撤一百首就是一百次全量广播");
        assertTrue(body.contains("m.addProperty(\"playlist\", playlist)"), "撤的是名字，不是下标");
        assertFalse(body.contains("clearQueue()"), "顺手调 clearQueue 会把别人排的歌一起清掉");
    }

    // ═══════════════════════ 界面侧 ═══════════════════════

    @Test
    @DisplayName("歌单页底部那一排按按钮数量平分宽度，四颗不会挤爆也不会留空")
    void queuePaneButtonRowSplitsByCount() throws IOException {
        String overlay = read(PARTY.resolve("ui").resolve("MpPartyOverlay.java"));
        String pane = region(overlay, "private void buildQueuePane(final Panel host)",
                "歌单里的一行：封面 + 歌名 + 副行");

        assertTrue(pane.contains("MpPartyClient.playlistSupported()"),
                "「播放歌单」那颗只在服务端支持时加入这一排");
        assertTrue(pane.contains("enabled.add(MpPartyClient::canPlaylist)"),
                "加入之后仍然按权限置灰，不是按能力决定显不显示");
        assertTrue(pane.contains("(total - 8 * (count - 1)) / count"),
                "宽度按这一排实际有几颗平分：写死三份坐标的话，三颗与四颗都会有一边留下空位");
    }

    @Test
    @DisplayName("队列行上歌单歌不写「点歌 某人」")
    void playlistEntriesAreLabelledAsPlaylist() throws IOException {
        String overlay = read(PARTY.resolve("ui").resolve("MpPartyOverlay.java"));
        String row = region(overlay, "private void buildQueueRow(final ListView list, final int slot)",
                "歌单第");

        int playlist = row.indexOf("track.fromPlaylist()");
        int requester = row.indexOf("点歌 ");
        assertTrue(playlist >= 0 && requester > playlist,
                "歌单条目要先判并显示成「歌单 《名》」，否则一行行「点歌 张三」让人以为排序坏了");
        assertTrue(row.contains("\"歌单 \"") || row.contains("歌单 "), "副行里要有这个字样");
    }

    @Test
    @DisplayName("选单页在房间里、且只在本机选得到单子时才铺列表")
    void pickerPageUsesTheSharedSourceList() throws IOException {
        String overlay = read(PARTY.resolve("ui").resolve("MpPartyOverlay.java"));
        String page = region(overlay, "private void showPlaylistPage()",
                "选单页的一行：分组图标");

        assertTrue(page.contains("MpRoomPlaylists.collect()"),
                "选单来源与撤回比对共用一份枚举，不能界面自己再拼一次");
        assertTrue(page.contains("Page.PLAYLIST"), "要有自己的页码，返回键才回得到总览");
        assertTrue(overlay.contains("private void buildPlaylistRow"), "行是固定槽位的 ListView 槽");
        assertTrue(page.contains("MpRoomPlaylists.Item"),
                "列表项带着「来自哪一组」，用户才知道账号单为什么没出现");
    }

    @Test
    @DisplayName("歌单页那颗「排进房间」只在房间里出现")
    void playlistPanelEntryIsRoomGated() throws IOException {
        String panel = read(ProjectPaths.sourceFile(Paths.get("com", "muoniumplayer",
                "core", "screens", "ncm", "panels", "PlaylistPanel.java")));

        String body = region(panel, "RoundedButtonWidget btnParty",
                "RoundedButtonWidget btnCoverflow");
        assertTrue(body.contains("MpPartyClient.playlistSupported()"),
                "不在房间里（或老服务端）这颗按钮整个不出现，而不是按下去没反应");
        assertTrue(body.contains("MpPartyClient.requestPlaylist(playList)"),
                "按下去交给房间那一条批量路径，不在界面里自己拼帧");

        String coverflow = region(panel, "RoundedButtonWidget btnCoverflow",
                "btnCoverflow.setOnClickCallback");
        assertTrue(coverflow.contains("MpPartyClient.playlistSupported()"),
                "链式定位：那一颗收起来时后面那排要自己顶回原位，不然界面留一块空");
    }

    // ═══════════════════════ 协议读回 ═══════════════════════

    @Test
    @DisplayName("歌单名从快照里读得回来；老服务端不发就是空串")
    void playlistFieldParsesBackFromTheSnapshot() {
        JsonObject with = new JsonObject();
        with.addProperty("songId", "123");
        with.addProperty("name", "一首歌");
        with.addProperty("playlist", "深夜电台");
        MpTrack tagged = MpTrack.fromJson(with);
        assertTrue(tagged.fromPlaylist(), "界面要靠它把第三档标出来");
        assertEquals("深夜电台", tagged.playlist);

        JsonObject plain = new JsonObject();
        plain.addProperty("songId", "123");
        plain.addProperty("name", "另一首");
        assertFalse(MpTrack.fromJson(plain).fromPlaylist(),
                "逐首点来的歌不能显示成「歌单」，那是把别人的排队说成自动排的");
    }

    @Test
    @DisplayName("批量与撤回两个 op 的字符串与服务端同名")
    void wireOpNamesMatchTheServer() throws IOException {
        String proto = read(PARTY.resolve("MpProto.java"));
        assertTrue(proto.contains("C_ENQUEUE_BATCH = \"enqueue_batch\""),
                "这是线上协议的字面量，改名等于两个客户端各说各话");
        assertTrue(proto.contains("C_CLEAR_PLAYLIST = \"clear_playlist\""));
    }

    // ════════════════════════ 共用工具 ════════════════════════

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 取两个锚点之间那一段源码；锚点找不到就直接失败，免得断言在空串上假绿。 */
    private static String region(String source, String from, String until) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "源码里找不到起点锚：" + from);
        int end = source.indexOf(until, start + from.length());
        assertTrue(end > start, "源码里找不到终点锚：" + until);
        return source.substring(start, end);
    }
}
