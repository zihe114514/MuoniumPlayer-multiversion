package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「下一首播放」抽屉房间模式的离线契约测试。
 *
 * <p>抽屉在服务器房间里显示的是<b>一起听歌单</b>（{@code MpPartyClient.queue()}），拖动/移除/清空
 * 落到房间协议上。这些类一加载就要建 GL 上下文，跑不起来，所以按本项目静态测试的惯例钉源码契约，
 * 防的是三类最容易悄悄回归的写法：</p>
 *
 * <ul>
 *   <li>拖动途中按格发消息——一次拖动几十条 C_MOVE 会把房间刷屏；</li>
 *   <li>中断的拖动不回滚——界面留下一份服务器从来没有过的顺序；</li>
 *   <li>权限判据各自为政——抽屉与房间面板对「谁能排/谁能删」说的不是同一句话。</li>
 * </ul>
 */
class PlayQueueRoomStaticTest {

    private static final Path PANEL = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "PlayQueuePanel.java"));
    private static final Path BRIDGE = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "PlayQueueRoomBridge.java"));
    private static final Path CONTROLS = ProjectPaths.sourceFile(Paths.get(
            "com", "muoniumplayer", "core", "screens", "ncm", "panels", "ControlsBar.java"));

    @Test
    @DisplayName("抽屉在房间模式下读写都走桥接层，本地路径原样保留")
    void panelRoutesRoomModeThroughTheBridge() throws IOException {
        String body = read(PANEL);
        String sync = between(body, "private void syncRows()", "private boolean matchesRows");
        assertTrue(sync.contains("this.roomMode ? this.room.snapshot() : CloudMusic.getQueuedNextSongs()"),
                "房间模式没有切数据源：抽屉在房间里还在显示永远为空的本地待播段");
        String move = between(body, "private boolean moveDisplayRow", "private void endDrag");
        assertTrue(move.contains("this.roomMode ? this.room.move(from, to) : CloudMusic.moveQueuedNext(from, to)"),
                "换位没有按模式分流：本地要立刻写进活队列，房间只能动暂存");
        String remove = between(body, "private void removeRow", "private void cancelDrag");
        assertTrue(remove.contains("this.room.remove(index)") && remove.contains("CloudMusic.removeQueuedNext(index)"),
                "移除没有按模式分流");
        String clear = between(body, "clearAll.setOnClickCallback", "LabelWidget clearAllText");
        assertTrue(clear.contains("this.room.clear()") && clear.contains("CloudMusic.clearQueuedNextSongs()"),
                "清空没有按模式分流");
        assertTrue(body.contains("this.roomMode = PlayQueueRoomBridge.roomActive();"),
                "房间模式没有每帧判定：进房/退房之后抽屉会停在旧模式上");
    }

    @Test
    @DisplayName("松手才提交一次 move，中断的拖动回滚暂存顺序")
    void dragCommitsOnceAndAbortedDragsRollBack() throws IOException {
        String body = read(PANEL);
        String drag = between(body, "private void updateDrag", "private boolean moveDisplayRow");
        assertTrue(drag.contains("boolean released = !Mouse.isButtonDown(0)"),
                "松手与中途夭折没有分开：要么丢了提交，要么把夭折当松手发出指令");
        assertTrue(drag.contains("this.endDrag(released)"), "松手没有走 endDrag 的提交路径");
        String end = between(body, "private void endDrag", "private void removeRow");
        assertTrue(end.contains("this.room.commitMove(row.music)") && end.contains("this.room.rollbackMove()"),
                "endDrag 没有按结局分流：提交与回滚必须是一对");
        String cancel = between(body, "private void cancelDrag", "public boolean consumeClick");
        assertTrue(cancel.contains("this.room.rollbackMove()"),
                "cancelDrag 在房间模式下不回滚：界面会留下一份服务器没有过的顺序");

        String bridge = read(BRIDGE);
        String moveBody = between(bridge, "boolean move(int from, int to)", "void commitMove");
        assertTrue(!moveBody.contains("MpPartyClient."),
                "拖动途中碰了协议：一次拖动会按格发几十条 move，全房广播刷屏");
        String commitBody = between(bridge, "void commitMove", "void rollbackMove");
        assertTrue(commitBody.contains("MpPartyClient.moveEntry(track.entryId, index)"),
                "提交没有走 moveEntry，或下标不是暂存里的最终位置");
        String rollbackBody = between(bridge, "void rollbackMove", "boolean remove");
        assertTrue(rollbackBody.contains("dirty && !committed"),
                "回滚不分青红皂白：已发出的移除/清空的乐观效果会被一起抹掉，刚删的行原地复活");
    }

    @Test
    @DisplayName("暂存顺序以服务器回包为准，过期提交回退到权威顺序")
    void stagingYieldsToAuthoritativeQueue() throws IOException {
        String bridge = read(BRIDGE);
        String snapshot = between(bridge, "List<Music> snapshot()", "boolean canReorder");
        assertTrue(snapshot.contains("serverQueue != serverSeen"),
                "没有按服务器队列实例变化重建：乐观顺序会一直压着权威顺序");
        assertTrue(snapshot.contains("COMMIT_EXPIRE_MILLIS"),
                "提交丢了没有过期回退：界面永远显示一份服务器不认的顺序");
        assertTrue(snapshot.contains("KuGouListenTogetherService.getRoomSongs()"),
                "酷狗房间没有退回只读歌单：客机在别处根本看不到房间歌单");
    }

    @Test
    @DisplayName("排列、移除、清空的权限判据与房间面板同一条线")
    void permissionsMatchTheRoomPanel() throws IOException {
        String bridge = read(BRIDGE);
        assertTrue(bridge.contains("canReorder() {\n        return interactive() && MpPartyClient.hasControl();"),
                "排列权限不是 hasControl：普通成员拖动只会被服务端一次次拒绝");
        assertTrue(bridge.contains("canClear() {\n        return interactive() && MpPartyClient.hasControl();"),
                "清空权限不是 hasControl");
        String canRemove = between(bridge, "boolean canRemove(int index)", "boolean canClear");
        assertTrue(canRemove.contains("MpPartyClient.hasControl()") && canRemove.contains("MpPartyClient.self()"),
                "移除权限没对齐房间面板：管理员全开、普通成员只开自己点的那首");

        String panel = read(PANEL);
        String begin = between(panel, "private void beginDrag", "private void updateDrag");
        assertTrue(begin.contains("!this.room.canReorder()"),
                "没有控制权的成员也能把行提起来：松手才发现整场白拖");
        String flow = between(panel, "flowSort.setBeforeRenderCallback", "flowSort.setOnClickCallback");
        assertTrue(flow.contains("this.roomMode"),
                "「排列」在房间模式下还出现：它排的是本地待播段，房间里那份永远是空的");
        String clearHidden = between(panel, "clearAll.setBeforeRenderCallback", "clearAll.setOnClickCallback");
        assertTrue(clearHidden.contains("!this.room.canClear()"),
                "没有控制权的成员看得到「清空」：一颗按了只会被拒的按钮");
        String rowFlags = between(panel, "private void applyRowLayout", "private void syncRows");
        assertTrue(rowFlags.contains("this.room.canRemove(row.slot)"),
                "行级移除按钮没有按房间权限收起");
    }

    @Test
    @DisplayName("入口按钮的角标与点亮条件跟抽屉数的是同一份队列")
    void controlsBarCountsTheSameQueueAsTheDrawer() throws IOException {
        String body = read(CONTROLS);
        assertTrue(body.contains("PlayQueueRoomBridge.displayQueuedCount()"),
                "入口按钮还在数本地待播：房间里队列非空时按钮不亮/角标为 0，与抽屉对不上");
        assertTrue(!body.contains("CloudMusic.getQueuedNextCount()"),
                "ControlsBar 里还残留直读本地待播数的写法");
    }

    private static String between(final String source, final String from, final String until) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "找不到起点 " + from);
        int end = source.indexOf(until, start);
        assertTrue(end > start, "找不到终点 " + until);
        return source.substring(start, end);
    }

    /** 读源码并<b>剥掉注释</b>：实现自己的注释里就写着被禁的写法，不剥会自己把自己钉红。 */
    private static String read(final Path path) throws IOException {
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\n]*", "");
    }
}
