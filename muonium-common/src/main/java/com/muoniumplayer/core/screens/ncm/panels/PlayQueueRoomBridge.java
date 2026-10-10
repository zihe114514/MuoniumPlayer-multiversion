package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.KuGouListenTogetherService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.party.MpMember;
import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.party.MpTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「下一首播放」抽屉的<b>房间模式</b>数据源：把一起听的房间歌单接到抽屉里。
 *
 * <h3>为什么需要房间模式</h3>
 * <p>进了服务器房间之后，播放器的「下一首播放」已经被 {@code MpPartyClient.interceptPlayNext}
 * 改写成房间点歌（{@code queueNext}），本地待播账本在房间里根本不会被用到——可抽屉还在显示那份
 * 永远为空的本地队列，用户在房间里的每一次排队都像石沉大海。房间模式让抽屉显示<b>真正的
 * 一起听歌单</b>（{@link MpPartyClient#queue()}），拖动、移除、清空直接落到房间协议上，
 * 全房同步。</p>
 *
 * <h3>两种房间，两档能力</h3>
 * <ul>
 *   <li><b>服务器房间</b>（mpsync 服务端 mod）：协议里有 {@code move}/{@code remove}/{@code clear}，
 *       抽屉因此拥有与本地模式对等的全部手动排列能力。排列与清空只有房主/管理员可用
 *       （{@link MpPartyClient#hasControl()}），移除对自己的点歌也开放——与房间面板 QUEUE 页
 *       的权限判据逐字相同，服务端还会再校验一次。</li>
 *   <li><b>酷狗官方房间</b>：上游没有按曲调整顺序、也没有按曲删除的端点（见
 *       {@code KuGouListenTogetherService.syncRoomQueue} 的注释），所以这里只把
 *       {@link KuGouListenTogetherService#getRoomSongs()} 铺出来<b>只读展示</b>——酷狗客机本来
 *       没有任何能看到房间歌单的地方，抽屉里的这份只读列表是它唯一的入口。</li>
 *   <li><b>网易云官方房间不进房间模式</b>：房主的本地队列本身就是房间歌单
 *       （{@code NeteaseListenTogetherService} 每秒整表 REPLACE 上报），本地模式显示的就是它；
 *       客机侧则没有任何房间歌单数据可显示。</li>
 * </ul>
 *
 * <h3>乐观顺序与权威顺序</h3>
 * <p>本地的拖动是「每一格换位立刻写进 {@code playList}」；房间不行——{@code move} 是发给服务器的
 * 一条消息，按格发送会把一次拖动变成几十条 C_MOVE。所以这里维护一份<b>暂存顺序</b>：拖动途中只改
 * 暂存（界面立刻跟着动），松手才发<b>一条</b> {@code moveEntry}。服务器把新队列广播回来时
 * （{@code S_QUEUE} 每次都造一条新的 {@code MpRoom}），以实例变化为号放弃暂存、按权威顺序重建。
 * 提交后迟迟等不到回包（消息丢了）也按同一原则过期回退——显示一份服务器不认的顺序比短暂跳回
 * 真实顺序更糟。</p>
 *
 * <p>线程模型：暂存状态只有渲染线程碰；服务器队列经 {@link MpPartyClient#queue()} 读的是
 * volatile 房间快照里的不可变列表，网络线程只负责整条换掉它。两边没有共享的可变结构。</p>
 */
final class PlayQueueRoomBridge {

    /** {@code MpTrack.toMusic()} 的实例缓存上限，与房间面板的 musicCache 同一档。 */
    private static final int MUSIC_CACHE_LIMIT = 64;

    /** 提交移动后等服务器回包的期限，超时放弃乐观顺序。 */
    private static final long COMMIT_EXPIRE_MILLIS = 4_000L;

    /** 展示顺序上的曲目（暂存顺序）。与 {@link #stagedTracks} 一一对应。 */
    private List<Music> staged = Collections.emptyList();
    /** 展示顺序上的原始条目，带着 entryId / 点歌人。 */
    private List<MpTrack> stagedTracks = Collections.emptyList();
    /** 最近一次见到的服务器队列实例；实例变了就是服务器说了新话。 */
    private List<MpTrack> serverSeen = Collections.emptyList();
    /** 暂存相对服务器有未确认的本地改动。 */
    private boolean dirty;
    /** 松手那一次 moveEntry 已经发出。 */
    private boolean committed;
    private long committedAt;
    /** 发出提交那一刻的服务器实例；它没变而期限已过，说明回包丢了。 */
    private List<MpTrack> committedServer;

    /** 同一首歌的 {@link Music} 实例缓存：重复点同一首时两行共用一个实例，界面按身份复用行。 */
    private final Map<String, Music> musicCache = new HashMap<String, Music>();

    // ──────────────────────────── 模式判定 ────────────────────────────

    /** 抽屉这一帧要不要切进房间模式：服务器房间优先于酷狗官方房间。 */
    static boolean roomActive() {
        return MpPartyClient.inRoom() || KuGouListenTogetherService.isActive();
    }

    /** 房间模式是否有手动排列能力（只有服务器房间有）。 */
    static boolean interactive() {
        return MpPartyClient.inRoom();
    }

    /**
     * 角标该显示的数字：房间模式下数房间歌单，否则数本地待播。
     *
     * <p>给 {@code ControlsBar} 的入口按钮用——按钮点亮条件与角标数字必须和抽屉里显示的是同一份
     * 队列，否则「按钮亮着、抽屉却是空的」这种对不上的事又会出现。</p>
     */
    static int displayQueuedCount() {
        if (MpPartyClient.inRoom()) return MpPartyClient.queue().size();
        if (KuGouListenTogetherService.isActive()) return KuGouListenTogetherService.getRoomSongs().size();
        return CloudMusic.getQueuedNextCount();
    }

    // ──────────────────────────── 读 ────────────────────────────

    /** 抽屉这一帧要画的曲目，按展示顺序。 */
    List<Music> snapshot() {
        if (!interactive()) {
            // 酷狗房间：歌单本来就是服务端快照，直接给，没有暂存一说。
            return KuGouListenTogetherService.getRoomSongs();
        }
        List<MpTrack> serverQueue = MpPartyClient.queue();
        if (serverQueue != serverSeen) {
            rebuild(serverQueue);
        } else if (dirty && committed && serverQueue == committedServer
                && System.currentTimeMillis() - committedAt > COMMIT_EXPIRE_MILLIS) {
            // 提交既没被确认也没被反驳：消息大概率丢了，回到服务器最后一次说的顺序。
            rebuild(serverQueue);
        }
        return staged;
    }

    /** 拖动调整顺序当前放不放开：房间模式里只有房主/管理员。 */
    boolean canReorder() {
        return interactive() && MpPartyClient.hasControl();
    }

    /** 第 {@code index} 行的移除按钮该不该出现：管理员全开，普通成员只开自己点的那首。 */
    boolean canRemove(int index) {
        if (!interactive()) return false;
        if (index < 0 || index >= stagedTracks.size()) return false;
        if (MpPartyClient.hasControl()) return true;
        MpMember me = MpPartyClient.self();
        String requester = stagedTracks.get(index).requesterName;
        return me != null && me.name != null && me.name.equals(requester);
    }

    /** 清空按钮该不该出现：与房间面板 QUEUE 页的「清空歌单」同一个判据。 */
    boolean canClear() {
        return interactive() && MpPartyClient.hasControl();
    }

    /** 表头那句提示。 */
    String hint() {
        if (!interactive()) return "一起听歌单 · 顺序以房间为准";
        return canReorder() ? "拖动调整顺序 · 全房同步" : "一起听歌单 · 只有房主与管理员可以调整";
    }

    /** 队列为空时的那句话。 */
    String emptyHint() {
        if (!interactive()) return "房间歌单是空的 · 「同步一次」后这里显示房间曲目";
        return MpPartyClient.membersCanEnqueue()
                ? "一起听歌单是空的 · 点歌曲右侧的排队按钮即可点歌"
                : "一起听歌单是空的 · 这个房间只有房主与管理员可以点歌";
    }

    // ──────────────────────────── 写 ────────────────────────────

    /**
     * 拖动途中的每一次换位：只动暂存，不发消息。
     *
     * @return 展示顺序是否真的变了
     */
    boolean move(int from, int to) {
        if (!interactive() || from == to) return false;
        if (from < 0 || from >= staged.size() || to < 0 || to >= staged.size()) return false;
        staged = new ArrayList<Music>(staged);
        stagedTracks = new ArrayList<MpTrack>(stagedTracks);
        staged.add(to, staged.remove(from));
        stagedTracks.add(to, stagedTracks.remove(from));
        dirty = true;
        committed = false;
        return true;
    }

    /**
     * 松手：把被拖那一首的最终位置发给服务器，一次拖动只发一条消息。
     *
     * <p>目标下标按「抽出再插回」语义算——就是 {@code staged} 里它此刻所在的下标，与本地模式
     * {@code moveQueuedNext} 的 {@code remove/add} 同一套，也是房间面板「置顶」发 0 的同一套。</p>
     */
    void commitMove(Music dragged) {
        if (!interactive() || !dirty || dragged == null) return;
        int index = staged.indexOf(dragged);
        if (index < 0) return;
        MpTrack track = stagedTracks.get(index);
        if (track != null && track.entryId != 0L) {
            MpPartyClient.moveEntry(track.entryId, index);
            committed = true;
            committedAt = System.currentTimeMillis();
            committedServer = serverSeen;
        }
    }

    /**
     * 拖动中途夭折（抽屉被收起、行被对齐掉、又点了一次这颗行）：把只存在于本机的暂存顺序
     * 退回服务器最后一次说的样子。
     *
     * <p>只退<b>没发出去的</b>拖动。移除/清空的乐观改动已经发了消息、等的是回包，跟着一起回滚
     * 的话刚删掉的那行会在下一帧原样复活，直到回包到达才又消失——闪两次比不退更糟。</p>
     */
    void rollbackMove() {
        if (dirty && !committed) rebuild(serverSeen);
    }

    /**
     * 移除第 {@code index} 行：消息立刻发，暂存同步删掉，行当场淡出；
     * 服务器的回包到达后 {@link #snapshot()} 自然按权威顺序重建。
     *
     * @return 是否发出去并删掉了
     */
    boolean remove(int index) {
        if (!interactive() || index < 0 || index >= staged.size()) return false;
        MpTrack track = stagedTracks.get(index);
        if (track == null || track.entryId == 0L) return false;
        MpPartyClient.removeEntry(track.entryId);
        staged = new ArrayList<Music>(staged);
        stagedTracks = new ArrayList<MpTrack>(stagedTracks);
        staged.remove(index);
        stagedTracks.remove(index);
        dirty = true;
        committed = true;
        committedAt = System.currentTimeMillis();
        committedServer = serverSeen;
        return true;
    }

    /** 清空房间歌单。消息立刻发，暂存当场清空。 */
    void clear() {
        if (!interactive()) return;
        MpPartyClient.clearQueue();
        staged = Collections.emptyList();
        stagedTracks = Collections.emptyList();
        dirty = true;
        committed = true;
        committedAt = System.currentTimeMillis();
        committedServer = serverSeen;
    }

    // ──────────────────────────── 内部 ────────────────────────────

    /** 按服务器的权威顺序重建暂存：回包确认、远端改动、提交超时都走这里。 */
    private void rebuild(List<MpTrack> serverQueue) {
        serverSeen = serverQueue;
        dirty = false;
        committed = false;
        committedServer = null;
        if (serverQueue.isEmpty()) {
            staged = Collections.emptyList();
            stagedTracks = Collections.emptyList();
            return;
        }
        List<Music> songs = new ArrayList<Music>(serverQueue.size());
        List<MpTrack> tracks = new ArrayList<MpTrack>(serverQueue.size());
        for (MpTrack track : serverQueue) {
            if (track == null) continue;
            songs.add(musicOf(track));
            tracks.add(track);
        }
        staged = Collections.unmodifiableList(songs);
        stagedTracks = Collections.unmodifiableList(tracks);
    }

    /** 同一首歌跨快照共用同一个 {@link Music} 实例，界面才能按身份复用行、动画不被重建打断。 */
    private Music musicOf(MpTrack track) {
        String key = track.key();
        Music music = musicCache.get(key);
        if (music == null) {
            if (musicCache.size() > MUSIC_CACHE_LIMIT) musicCache.clear();
            music = track.toMusic();
            musicCache.put(key, music);
        }
        return music;
    }
}
