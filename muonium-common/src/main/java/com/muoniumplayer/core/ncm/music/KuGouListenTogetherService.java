package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.core.ncm.kugou.KuGouDevice;
import com.muoniumplayer.core.ncm.kugou.KuGouListenTogetherApi;
import com.muoniumplayer.core.ncm.kugou.KuGouMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 酷狗官方「一起听」（众乐房）会话。
 *
 * <p>与 {@link NeteaseListenTogetherService} 平级，公开形状刻意对齐，好让界面将来能用同一组调用
 * 同时提供两种音源的一起听。协议细节在 {@link KuGouListenTogetherApi}，本类只做三件事：
 * 酷狗曲目 ⇄ {@link Music} 的转换、当前会话的内存状态、以及把上游的失败翻译成人话。</p>
 *
 * <h3>与网易云那套的两处结构性差别</h3>
 * <ul>
 *   <li><b>这里全部是同步阻塞的</b>，不养后台线程：{@code createRoom}/{@code joinRoom} 直接返回布尔，
 *       而不是先置 {@code CONNECTING} 再等状态翻过来。调用方负责放到后台线程去
 *       （对照 {@code KuGouMusicService} 的账号歌单刷新）。一次网络慢只会让那次调用慢，
 *       不会有一个没人管的线程在背后重试。</li>
 *   <li><b>客机可以自动跟随</b>。网易云没有「拉主机指令」的 HTTP 端点，只能靠主机上报 + 客机手动
 *       「同步一次」；酷狗的 {@code music_sync_player} 本身就是房间权威快照，轮询它就等于收到指令。
 *       所以 {@link #followRoom()} 是给客机用的读取动作，而 {@link #syncOnce()} 只是它的同形包装
 *       （保持与网易云同名）。</li>
 * </ul>
 *
 * <h3>刻意不驱动播放器</h3>
 * <p>本类<b>不</b>碰 {@link Music} 的播放、也不碰 {@code NeteaseMusicService}：所有「让本机跟着放」
 * 的信息都以数据返回（{@link #getRoomSongs()} / {@link #getSyncStartIndex()} /
 * {@link #getSyncSeekMillis()}），由界面去调播放链路。这样房间坏了、上游超时都打断不了正在放的东西，
 * 也让这一层可以在没有播放器的场合（服务端侧、测试）直接用。</p>
 *
 * <h3>没有做的两件事</h3>
 * <ul>
 *   <li>网易云的「房里有非会员就跳过 VIP 曲」：酷狗<b>没有</b>查他人会员状态的公开端点
 *       （{@code user/get_status} 只给房间会话），猜错方向会让房主无故跳歌，所以整条逻辑不实现。</li>
 *   <li>学习模式（自习室）的主机同步：参考实现里那一族已经被它自己的前端下线了
 *       （「自习室已下线，目前仅支持众乐房」），这里只透出列表/详情/成员三个只读端点。</li>
 * </ul>
 *
 * <h3>身份</h3>
 * <p>一起听是<b>概念版</b>的功能，而 {@link KuGouDevice} 的凭证自带身份。
 * {@link #isAvailable()} 只要求「酷狗已登录」——标准版账号会不会被上游拒掉未实测（<b>待实测</b>），
 * 真被拒时表现是状态行一句上游原因，不是界面消失。</p>
 */
public final class KuGouListenTogetherService {

    /** 房间里的一个成员。字段名与 {@code NeteaseListenTogetherService.Member} 保持一致。 */
    public static final class Member {
        public final long userId;
        public final String nickname;
        public final String avatarUrl;
        /** 房主。酷狗的房间快照不直接标，用 {@code ownerId} 比出来的。 */
        public final boolean host;
        /** 是自己。界面把它排在最前，和官方客户端一致。 */
        public final boolean self;

        Member(long userId, String nickname, String avatarUrl, boolean host, boolean self) {
            this.userId = userId;
            this.nickname = nickname == null ? "" : nickname;
            this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
            this.host = host;
            this.self = self;
        }
    }

    /** 一条房间消息。{@link #system} 为真时是上游的系统事件（进房、换曲、点歌……）。 */
    public static final class Message {
        public final String id;
        public final long userId;
        public final String nickname;
        public final String avatarUrl;
        public final String text;
        public final long sentAtMillis;
        public final boolean system;

        Message(String id, long userId, String nickname, String avatarUrl, String text,
                long sentAtMillis, boolean system) {
            this.id = id == null ? "" : id;
            this.userId = userId;
            this.nickname = nickname == null ? "" : nickname;
            this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
            this.text = text == null ? "" : text;
            this.sentAtMillis = sentAtMillis;
            this.system = system;
        }
    }

    /** 发现页上的一个房间卡片。 */
    public static final class RoomSummary {
        public final String roomId;
        public final String roomName;
        public final String ownerId;
        public final String ownerName;
        public final int memberCount;
        public final String currentSongName;
        public final String currentArtistName;
        public final String notice;
        public final String biz;

        RoomSummary(KuGouListenTogetherApi.Room room) {
            this.roomId = safe(room.id);
            this.roomName = safe(room.name);
            this.ownerId = safe(room.ownerId);
            this.ownerName = safe(room.ownerName);
            this.memberCount = Math.max(0, room.memberCount);
            this.currentSongName = safe(room.currentSongName);
            this.currentArtistName = safe(room.currentArtistName);
            this.notice = safe(room.notice);
            this.biz = safe(room.biz);
        }
    }

    public enum Role {
        /** 没有会话。 */
        NONE,
        /** 自己建的房间：会把本地播放状态上报出去。 */
        HOST,
        /** 加入别人的房间：只读房间快照并跟随。 */
        GUEST
    }

    public enum Phase {
        IDLE,
        /**
         * 正在建房/加入。
         *
         * <p>本类是同步的，所以这个状态<b>不会</b>被观测到——调用返回时已经是 ACTIVE 或 FAILED。
         * 留着是为了让界面在同一套状态机上给两个音源共用分支，而不是「酷狗这边少一个状态要特判」。</p>
         */
        CONNECTING,
        ACTIVE,
        /** 终态失败，界面显示原因并允许重试。 */
        FAILED
    }

    /** 邀请码前缀。酷狗官方<b>没有</b>一起听的分享链接 scheme（参考实现里找不到），所以只有这个自造短码。 */
    private static final String INVITE_PREFIX = "kugou:";

    /** 内存里最多留多少条消息，与官方客户端的「往上翻不爆」策略同形。 */
    private static final int MAX_MESSAGES = 200;

    /** 单次拉取的房间歌单上限。协议层把 pagesize 夹在 100 以内，这里就取那一档。 */
    private static final int ROOM_PLAYLIST_LIMIT = 100;

    /**
     * 成员列表的单页大小。
     *
     * <p>参考实现在这条端点上默认就是 100，而它<b>没有</b>翻页游标可用（页码是从 1 起的普通分页，
     * 但官方客户端也只看第一页），所以取一页就等于「界面上能看到的这些人」。</p>
     */
    private static final int MEMBER_PAGE_SIZE = 100;

    private static final Object LOCK = new Object();

    private static volatile Role role = Role.NONE;
    private static volatile Phase phase = Phase.IDLE;
    private static volatile String roomId = "";
    private static volatile String biz = KuGouListenTogetherApi.BIZ_MUSIC_ROOM;
    private static volatile String roomName = "";
    private static volatile String ownerId = "";
    /** 房间歌单版本，换曲与加歌要回带；上游用它做并发校验。 */
    private static volatile String listVersion = "";
    private static volatile String statusText = "";
    private static volatile boolean statusIsError;
    private static volatile List<Member> members = Collections.emptyList();
    private static volatile List<Message> messages = Collections.emptyList();
    private static volatile List<Music> roomSongs = Collections.emptyList();

    /** 已收消息的 id 集合，用插入序保证「最旧的先被淘汰」。 */
    private static final LinkedHashMap<String, Message> SEEN = new LinkedHashMap<String, Message>();

    /** {@link #syncOnce()} / {@link #followRoom()} 解析出的起播位置，由界面消费一次。 */
    private static volatile int syncStartIndex;
    private static volatile long syncSeekMillis;

    private KuGouListenTogetherService() {
    }

    // ── 对外状态 ────────────────────────────────────────────────────────────

    /**
     * 酷狗一起听是否可用。
     *
     * <p>只 gate 在<b>酷狗已登录</b>上：房间凭证与取流凭证是同一套，
     * 匿名建房在参考实现里就没有这条路（{@code authBody} 的 {@code userid} 会是 0）。
     * 当前音源是不是酷狗由界面判断——本类不碰 {@code NeteaseMusicService}。</p>
     */
    public static boolean isAvailable() {
        return KuGouDevice.isLoggedIn();
    }

    public static Role getRole() {
        return role;
    }

    public static Phase getPhase() {
        return phase;
    }

    public static boolean isActive() {
        return phase == Phase.ACTIVE;
    }

    /** 同步实现下恒为 {@code false}；与网易云那侧同名，供界面共用分支。 */
    public static boolean isBusy() {
        return phase == Phase.CONNECTING;
    }

    public static boolean isHost() {
        return role == Role.HOST;
    }

    public static String getRoomId() {
        return roomId;
    }

    public static String getRoomName() {
        return roomName;
    }

    public static String getOwnerId() {
        return ownerId;
    }

    public static String getStatusText() {
        return statusText;
    }

    public static boolean isStatusError() {
        return statusIsError;
    }

    public static List<Member> getMembers() {
        return members;
    }

    public static List<Message> getMessages() {
        return messages;
    }

    /** 最近一次同步到的房间曲目。界面直接拿去铺列表。 */
    public static List<Music> getRoomSongs() {
        return roomSongs;
    }

    /** {@link #syncOnce()} / {@link #followRoom()} 得到的起播下标（房间没在放东西时为 0）。 */
    public static int getSyncStartIndex() {
        return syncStartIndex;
    }

    /** 与 {@link #getSyncStartIndex()} 配套的进度目标，毫秒。 */
    public static long getSyncSeekMillis() {
        return syncSeekMillis;
    }

    /**
     * 邀请码：{@code kugou:<biz>:<房间号>}。
     *
     * <p>带 {@code biz} 是因为酷狗的房间分两条业务线（众乐房 1009 / 自习室 1000），
     * 光给房号对方得猜。{@link #joinRoom(String)} 认这个格式，也认裸房号。</p>
     */
    public static String getInviteCode() {
        String room = roomId;
        return room.isEmpty() ? "" : INVITE_PREFIX + biz + ":" + room;
    }

    /**
     * 分享链接。
     *
     * <p>酷狗<b>没有</b>官方的一起听分享 URL（参考实现与它的前端里都只有房号），
     * 所以这里返回的就是邀请码原文，界面上的「复制链接」实际复制的是它。
     * 待实测：官方客户端的分享文案里那个 {@code kugou://} 类的 scheme，找到之后再换成真链接。</p>
     */
    public static String getShareLink() {
        return getInviteCode();
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    /** 用默认房名建房（房名取「<昵称>的房间」）。 */
    public static boolean createRoom(List<Music> songs) {
        return createRoom(KuGouDevice.nickname().trim() + " 的房间", songs);
    }

    /**
     * 建房并把初始队列铺进去。
     *
     * <p>顺序是「预检 → create → init」，与参考实现一致：预检不通过（账号在别的设备上已经有会话）
     * 就直接 adopt 那个会话并返回成功，因为此时<b>再建房必然被拒</b>。
     * 这一步省掉的表现是「用户点两次创建，第二次看到一个莫名其妙的失败」。</p>
     *
     * <p>建房时<b>不</b>带进度：本类不读播放器，猜一个 0 会让房间里的人从错误位置起播。
     * 界面起播后调一次 {@link #syncSong(Music)} 与 {@link #reportProgress(long)} 就对齐了。</p>
     *
     * @param songs 初始房间歌单；只有带酷狗 hash 的会被采纳
     * @return 是否进入房间（含「恢复到已有房间」）
     */
    public static boolean createRoom(String name, List<Music> songs) {
        if (!isAvailable()) return fail("需要先登录酷狗账号");
        List<KuGouMusicApi.Track> audios = toTracks(songs);
        if (audios.isEmpty()) return fail("这些曲目里没有酷狗能识别的（hash 为空）");
        if (!roomId.isEmpty()) leaveQuietly();

        KuGouListenTogetherApi.Room existing = recoverSessionQuietly();
        if (existing != null) return adopt(existing, roleOf(existing, Role.HOST), "已恢复到之前的房间");

        // room_privacy=1：公开房（挂在音乐频道上）；capacity 与 background 用上游默认，
        // 网易云那侧也没有这两个开关，界面上就没地方放。
        String created = KuGouListenTogetherApi.createRoom(KuGouListenTogetherApi.BIZ_MUSIC_ROOM,
                safe(name).trim(), 1, 0, "", "");
        if (created.isEmpty()) {
            // 建房失败最常见的原因是账号已有会话（上游 20006），而它只表现为一次布尔失败，
            // 所以再查一次会话来区分「真失败」和「其实建成了」。
            KuGouListenTogetherApi.Room raced = recoverSessionQuietly();
            if (raced != null) return adopt(raced, roleOf(raced, Role.HOST), "已恢复到之前的房间");
            return fail("创建房间失败，请稍后重试");
        }

        String token = created;
        boolean initialized = KuGouListenTogetherApi.initMusicRoom(token, audios, null);
        if (!initialized) {
            // 房间已经存在但不认识 genting 那一半，不关干净的话它会一直挂在账号会话里挡后续建房。
            KuGouListenTogetherApi.dismissRoom(KuGouListenTogetherApi.BIZ_MUSIC_ROOM, token);
            return fail("房间已创建但初始化歌单失败");
        }
        // 刚建出来的房间一定属于自己的，不需要再查一次 is_owner 来「确认」——
        // 那是给「本地没建房、只是重启」的路径用的（recoverSession）。
        adopt(newRoom(token, name), Role.HOST, "房间已就绪 · 把邀请码发给朋友即可加入");
        // 立刻把初始队列摆在本地：房间侧的列表要等 followRoom 的两次读才有，
        // 而用户刚点完创建就看到一个空歌单是很奇怪的事。
        synchronized (LOCK) {
            roomSongs = Collections.unmodifiableList(adaptTracks(audios));
        }
        refreshMembers();
        return true;
    }

    /**
     * 加入房间。
     *
     * @param invite 邀请码 {@code kugou:<biz>:<房号>}、裸房号，或含 {@code roomid=}/{@code groupid=} 的文本
     */
    public static boolean joinRoom(String invite) {
        if (!isAvailable()) return fail("需要先登录酷狗账号");
        String target = parseRoomId(invite);
        if (target.isEmpty()) return fail("认不出房间号：请粘贴邀请码，或直接填房号");
        String targetBiz = parseBiz(invite, KuGouListenTogetherApi.BIZ_MUSIC_ROOM);
        if (!roomId.isEmpty()) leaveQuietly();

        // 同建房：目标房间就是本机已有的会话时不能再 join，上游会以「已有会话」拒绝。
        KuGouListenTogetherApi.Room existing = recoverSessionQuietly();
        if (existing != null && target.equals(existing.id)) {
            return adopt(existing, roleOf(existing, Role.GUEST), "已在房间中");
        }

        if (!KuGouListenTogetherApi.joinRoom(targetBiz, target)) {
            KuGouListenTogetherApi.Room raced = recoverSessionQuietly();
            if (raced != null && target.equals(raced.id)) {
                return adopt(raced, roleOf(raced, Role.GUEST), "已在房间中");
            }
            return fail("加入房间失败：房间可能已结束");
        }
        KuGouListenTogetherApi.Room detail = KuGouListenTogetherApi.musicRoomDetail(target);
        if (detail == null) {
            detail = newRoom(target, "");
            detail.biz = targetBiz;
        }
        adopt(detail, Role.GUEST, "已加入房间 · 点「同步一次」对齐房间进度");
        refreshMembers();
        return true;
    }

    /**
     * 退出会话。
     *
     * <p>本地状态<b>先</b>清掉，再去通知上游：上游失败时没有可补救的动作（房间会因为断心跳自己过期），
     * 而把用户关在一个「退不出去」的界面里才是真正的坏体验。房主退出走 {@code dismiss}，等价于网易云
     * 那侧主机 {@code end}。</p>
     *
     * @return 上游是否确认
     */
    public static boolean leave() {
        Role leavingRole;
        String leavingRoom;
        String leavingBiz;
        synchronized (LOCK) {
            leavingRole = role;
            leavingRoom = roomId;
            leavingBiz = biz;
            resetState();
        }
        if (leavingRole == Role.NONE || leavingRoom.isEmpty()) return true;
        boolean confirmed;
        if (leavingRole == Role.HOST) {
            confirmed = KuGouListenTogetherApi.dismissRoom(leavingBiz, leavingRoom);
        } else {
            confirmed = KuGouListenTogetherApi.leaveRoom(leavingBiz, leavingRoom);
        }
        if (!confirmed) {
            // 已经退干净了，只是上游没确认：提示但不标成错误，避免用户以为还困在房间里。
            publish("已退出本地会话，但服务端未确认", false);
        }
        return confirmed;
    }

    /**
     * 恢复账号上未结束的会话（进程重启后本地房号丢了）。
     *
     * @return 是否恢复到某个房间
     */
    public static boolean recoverSession() {
        if (!isAvailable()) return fail("需要先登录酷狗账号");
        KuGouListenTogetherApi.Room room = recoverSessionQuietly();
        if (room == null) return fail("没有进行中的酷狗一起听房间");
        adopt(room, roleOf(room, Role.GUEST), "已回到房间");
        refreshMembers();
        return true;
    }

    /**
     * 会话恢复出来的那条记录带 {@code is_owner}，按它定角色，猜不出来才用调用方给的默认值。
     *
     * <p>不能只看 {@code ownerId == 我}：另一台设备建的房间，{@code get_status} 常常不回填
     * {@code ownerId}，于是房主会在自己建的房间里被判成客机、上报全部被拒。</p>
     */
    private static Role roleOf(KuGouListenTogetherApi.Room room, Role fallback) {
        if (room == null) return fallback;
        if (room.selfIsOwner) return Role.HOST;
        String owner = safe(room.ownerId);
        String self = safe(KuGouDevice.userId()).trim();
        if (!owner.isEmpty() && !self.isEmpty() && owner.equals(self)) return Role.HOST;
        return fallback;
    }

    /** 安静退出（不写状态行），给「先离开再进新房间」这类内部流程用。 */
    private static void leaveQuietly() {
        if (roomId.isEmpty()) return;
        try {
            if (role == Role.HOST) {
                KuGouListenTogetherApi.dismissRoom(biz, roomId);
            } else {
                KuGouListenTogetherApi.leaveRoom(biz, roomId);
            }
        } catch (Throwable throwable) {
            log("安静离房失败", throwable);
        } finally {
            synchronized (LOCK) {
                resetState();
            }
        }
    }

    // ── 房主：上报播放状态 ──────────────────────────────────────────────────

    /**
     * 房主换曲：让房间切到这首歌。
     *
     * <p>非房主调用直接返回 {@code false} —— 酷狗的上游也会拒，但先拒能给界面一句确定的原因，
     * 而不是一句「服务暂不可用」。</p>
     */
    public static boolean syncSong(Music song) {
        if (!requireHost("只有房主能切换房间曲目")) return false;
        KuGouMusicApi.Track track = toTrack(song);
        if (track == null) return false;
        boolean sent = KuGouListenTogetherApi.switchRoomSong(roomId, track, listVersion, false);
        if (!sent) publish("切换房间曲目失败", true);
        return sent;
    }

    /** 上报进度（毫秒，协议层会换算成秒）。 */
    public static boolean reportProgress(long progressMillis) {
        if (!requireHost("只有房主能同步播放进度")) return false;
        return KuGouListenTogetherApi.reportProgress(roomId, Math.max(0L, progressMillis));
    }

    /** 上报暂停/继续。 */
    public static boolean reportPlayState(boolean playing) {
        if (!requireHost("只有房主能同步播放状态")) return false;
        return KuGouListenTogetherApi.reportPlayState(roomId, playing);
    }

    /** 上报循环模式，取值见 {@link KuGouListenTogetherApi#PLAY_MODE_LIST} 等常量。 */
    public static boolean reportPlayMode(int playMode) {
        if (!requireHost("只有房主能同步播放模式")) return false;
        return KuGouListenTogetherApi.reportPlayMode(roomId, playMode);
    }

    /**
     * 房主把整条本地队列换成房间歌单。
     *
     * <p>网易云那侧是「REPLACE 整表」，酷狗没有对应端点，只能 {@code music_add} 增量加，
     * 所以这里是「把本地队列里房间还没有的部分加进去」。<b>不会</b>删掉房间里有而本地没有的歌 ——
     * 上游没有提供按曲删除的端点，误删别人想听的东西比多留几首更糟。</p>
     */
    public static boolean syncRoomQueue(List<Music> songs) {
        if (!requireHost("只有房主能同步房间歌单")) return false;
        List<KuGouMusicApi.Track> tracks = toTracks(songs);
        if (tracks.isEmpty()) return false;
        List<KuGouMusicApi.Track> missing = new ArrayList<KuGouMusicApi.Track>();
        List<KuGouMusicApi.Track> local = new ArrayList<KuGouMusicApi.Track>();
        for (Music music : roomSongs) {
            KuGouMusicApi.Track known = toTrack(music);
            if (known != null) local.add(known);
        }
        for (KuGouMusicApi.Track track : tracks) {
            if (!containsHash(local, track.hash)) missing.add(track);
        }
        if (missing.isEmpty()) return true;
        boolean sent = KuGouListenTogetherApi.addRoomSongs(roomId, missing, 1, listVersion, "", null);
        if (!sent) publish("同步房间歌单失败", true);
        return sent;
    }

    /**
     * 心跳。它只保活、<b>不</b>带播放状态（与网易云不同，那边的心跳里塞了 songId/progress）。
     *
     * <p>单次失败不改会话状态：上游偶发超时是常态，而「房间其实已经解散」由 {@link #refreshRoom()}
     * 判定 —— 它读的是 {@code room_state}，比「心跳丢了一次」准确得多。</p>
     */
    public static boolean heartbeat() {
        String room = roomId;
        if (room.isEmpty() || phase != Phase.ACTIVE) return false;
        return KuGouListenTogetherApi.heartbeat(biz, room);
    }

    // ── 客机 / 双方：读取房间 ───────────────────────────────────────────────

    /**
     * 按房间当前快照对齐一次：拉房间歌单、定位到正在放的那首、记下目标进度。
     *
     * <p>结果写在 {@link #getRoomSongs()} / {@link #getSyncStartIndex()} /
     * {@link #getSyncSeekMillis()} 三个 getter 里，<b>不</b>直接开播 —— 见类注释「刻意不驱动播放器」。</p>
     */
    public static boolean syncOnce() {
        return followRoom();
    }

    /**
     * 读一次房间快照（客机每几秒调它即可自动跟随）。
     *
     * <p>先取 {@code recent_playlist}（服务端维护的近期队列，短且一定在放），空了才退回
     * {@code room_playlist} 全表；两者都是读操作，失败返回空而不是抛。</p>
     */
    public static boolean followRoom() {
        String room = roomId;
        if (room.isEmpty() || phase != Phase.ACTIVE) return false;
        KuGouListenTogetherApi.Room snapshot = KuGouListenTogetherApi.syncPlayer(room);
        List<KuGouMusicApi.Track> tracks = KuGouListenTogetherApi.recentPlaylist(room);
        if (tracks.isEmpty()) {
            tracks = KuGouListenTogetherApi.roomPlaylist(room, null, ROOM_PLAYLIST_LIMIT);
        }
        if (snapshot == null && tracks.isEmpty()) {
            publish("房间快照读取失败", true);
            return false;
        }
        synchronized (LOCK) {
            if (!tracks.isEmpty()) {
                roomSongs = Collections.unmodifiableList(adaptTracks(tracks));
            }
            if (snapshot != null) {
                if (!safe(snapshot.listVersion).isEmpty()) listVersion = snapshot.listVersion;
                if (!safe(snapshot.name).isEmpty()) roomName = snapshot.name;
                if (!safe(snapshot.ownerId).isEmpty()) ownerId = snapshot.ownerId;
                syncSeekMillis = Math.max(0L, snapshot.currentProgressMillis);
                syncStartIndex = indexOfHash(roomSongs, snapshot.currentHash);
                if (snapshot.closed) {
                    // 房间已经没了：这不是错误，安静收摊，界面上回到空闲态。
                    resetState();
                    publish("房间已结束", false);
                    return false;
                }
            }
        }
        if (statusIsError) publish(defaultActiveStatus(), false);
        return true;
    }

    /**
     * 刷新房间身份（名称/房主/歌单版本），并在房间已经解散时把本地会话收掉。
     *
     * <p>判散只信 {@code group/info} 的 {@code room_state}：详情端点在读不到时也回 {@code null}，
     * 把「读不到」当「没了」会因为一次超时把人踢出自己的房间。</p>
    public static boolean refreshRoom() {
        String room = roomId;
        if (room.isEmpty() || phase != Phase.ACTIVE) return false;
        KuGouListenTogetherApi.Room state = KuGouListenTogetherApi.roomState(biz, room);
        if (state == null || state.closed) {
            resetState();
            publish("房间已结束", false);
            return false;
        }
        // 两条读操作各补各的空缺：group/info 一定给活性但不给歌单版本，
        // 详情反过来。任何一条失败都只是少补一点信息，不影响「还在不在」的结论。
        KuGouListenTogetherApi.Room detail = KuGouListenTogetherApi.musicRoomDetail(room);
        String name = safe(state.name);
        String owner = safe(state.ownerId);
        String version = safe(state.listVersion);
        if (detail != null) {
            if (name.isEmpty()) name = safe(detail.name);
            if (owner.isEmpty()) owner = safe(detail.ownerId);
            if (version.isEmpty()) version = safe(detail.listVersion);
        }
        synchronized (LOCK) {
            if (!name.isEmpty()) roomName = name;
            if (!owner.isEmpty()) ownerId = owner;
            if (!version.isEmpty()) listVersion = version;
        }
        return true;
    }

    /** 成员列表。上游这个端点没有分页游标，一次取一页。 */
    public static List<Member> refreshMembers() {
        String room = roomId;
        if (room.isEmpty()) return Collections.emptyList();
        List<Member> parsed = new ArrayList<Member>();
        try {
            long self = selfUserId();
            String host = ownerId;
            for (KuGouListenTogetherApi.Member source
                    : KuGouListenTogetherApi.musicRoomMembers(room, 1, MEMBER_PAGE_SIZE)) {
                parsed.add(new Member(source.userId, source.nickname, source.avatarUrl,
                        !host.isEmpty() && host.equals(String.valueOf(source.userId)),
                        self == source.userId && self != 0L));
            }
        } catch (Throwable throwable) {
            log("成员列表读取失败", throwable);
        }
        if (!parsed.isEmpty()) {
            members = Collections.unmodifiableList(parsed);
        }
        return members;
    }

    /** 拉一次消息（含系统事件），并按 msgid 去重累积。 */
    public static List<Message> pollMessages() {
        String room = roomId;
        if (room.isEmpty()) return messages;
        try {
            List<Message> incoming = new ArrayList<Message>();
            for (KuGouListenTogetherApi.Message source
                    : KuGouListenTogetherApi.chatHistory(biz, room, "0", 50)) {
                incoming.add(new Message(source.id, source.userId, source.nickname, source.avatarUrl,
                        source.text, source.sentAtMillis, source.system));
            }
            if (!incoming.isEmpty()) mergeMessages(incoming);
            // 轮询路径上的失败不标红：界面正在显示的这一屏消息是对的。
            if (statusIsError) publish(defaultActiveStatus(), false);
        } catch (Throwable throwable) {
            log("消息读取失败", throwable);
        }
        return messages;
    }

    /**
     * 发一条聊天消息。
     *
     * <p>房主关了聊天时上游会拒，而这条端点不给可读的原因字段，所以只能报「发送失败」；
     * 界面上「输入框禁用」的判断要自己用房间快照的 {@code allowChat}。</p>
    public static boolean sendMessage(String text) {
        String room = roomId;
        if (room.isEmpty() || text == null || text.trim().isEmpty()) return false;
        boolean sent = KuGouListenTogetherApi.sendChat(biz, room, text.trim(),
                KuGouDevice.nickname(), KuGouDevice.avatarUrl());
        if (!sent) publish("消息发送失败", true);
        return sent;
    }

    /** 房主开关聊天。 */
    public static boolean setChatEnabled(boolean enabled) {
        if (!requireHost("只有房主能开关聊天")) return false;
        return KuGouListenTogetherApi.updateChatEnabled(roomId, enabled);
    }

    // ── 发现页 ──────────────────────────────────────────────────────────────

    /**
     * 房间广场（发现页那一屏）。
     *
     * <p>界面上点一张卡片就用 {@link #joinRoom(String)} 传它的 {@code roomId} —— 邀请码格式只是
     * 「带业务线的简写」，裸房号同样认。</p>
     */
    public static List<RoomSummary> discoverRooms(int page, int limit) {
        return summarize(KuGouListenTogetherApi.squareRooms(page, limit, 1));
    }

    /** 推荐房间。界面给的是 1 起的页码；协议层会减一成零起（那条端点就是这么设计的），这里不再换算。 */
    public static List<RoomSummary> recommendedRooms(int page, int limit) {
        return summarize(KuGouListenTogetherApi.recommendRooms(page, limit, "", ""));
    }

    /**
     * 「最近去过」的房间。
     *
     * <p>与 {@link #recoverSession()} 不是一回事：这条列的是历史上进过的房间，可能早已解散
     * （协议层会把表态过 {@code closed} 的滤掉，没表态的留着）；{@code recoverSession} 查的是
     * 账号<b>当前</b>那条还没结束的会话。</p>
     */
    public static List<RoomSummary> recentRooms() {
        return summarize(KuGouListenTogetherApi.recentRoomDynamic());
    }

    /** 自习室列表。学习模式已经被官方前端下线，这条只够给「逛一逛」用。 */
    public static List<RoomSummary> studyRooms(int page, int limit, String tagId) {
        return summarize(KuGouListenTogetherApi.studyRooms(page, limit, 0, tagId));
    }

    /** 自己创建过的自习室。只读，用于「学习模式还在开着的那几个房间」的展示。 */
    public static List<RoomSummary> createdStudyRooms() {
        return summarize(KuGouListenTogetherApi.createdStudyRooms());
    }

    /** 点歌列表（房主待处理）。 */
    public static List<Music> roomSongOrders() {
        String room = roomId;
        if (room.isEmpty()) return Collections.emptyList();
        return adaptTracks(KuGouListenTogetherApi.songOrderList(room));
    }

    /** 成员向房间点一首歌。 */
    public static boolean requestSong(Music song) {
        String room = roomId;
        if (room.isEmpty()) return false;
        KuGouMusicApi.Track track = toTrack(song);
        if (track == null) return false;
        boolean sent = KuGouListenTogetherApi.orderSong(room, track);
        if (!sent) publish("点歌失败", true);
        return sent;
    }

    // ── 状态迁移 ────────────────────────────────────────────────────────────

    private static boolean adopt(KuGouListenTogetherApi.Room room, Role assigned, String text) {
        synchronized (LOCK) {
            roomId = safe(room.id);
            biz = safe(room.biz).isEmpty() ? KuGouListenTogetherApi.BIZ_MUSIC_ROOM : safe(room.biz);
            roomName = safe(room.name);
            ownerId = safe(room.ownerId);
            listVersion = safe(room.listVersion);
            role = assigned;
            phase = Phase.ACTIVE;
            statusText = text;
            statusIsError = false;
            members = Collections.emptyList();
            messages = Collections.emptyList();
            // 换房间必须连旧房间的歌单一起丢掉：留着的话新房间的「已同步」列表里会混进别人房间的歌。
            roomSongs = Collections.emptyList();
            SEEN.clear();
            syncStartIndex = 0;
            syncSeekMillis = 0L;
        }
        return true;
    }

    private static KuGouListenTogetherApi.Room newRoom(String id, String name) {
        KuGouListenTogetherApi.Room room = new KuGouListenTogetherApi.Room();
        room.id = safe(id);
        room.name = safe(name);
        room.biz = KuGouListenTogetherApi.BIZ_MUSIC_ROOM;
        room.ownerId = KuGouDevice.userId();
        room.ownerName = KuGouDevice.nickname();
        return room;
    }

    /**
     * 会话恢复。
     *
     * <p>读操作按约定失败返回 {@code null}，所以这里不区分「没有会话」与「查不到」，两种都是 null；
     * 调用方把它当成「没有」继续走建房/加入，最坏结果是收到一次布尔失败。</p>
     */
    private static KuGouListenTogetherApi.Room recoverSessionQuietly() {
        try {
            KuGouListenTogetherApi.Room room =
                    KuGouListenTogetherApi.accountSession(KuGouListenTogetherApi.BIZ_MUSIC_ROOM);
            if (room != null && room.closed) return null;
            return room;
        } catch (Throwable throwable) {
            log("会话恢复失败", throwable);
            return null;
        }
    }

    private static void resetState() {
        role = Role.NONE;
        phase = Phase.IDLE;
        roomId = "";
        biz = KuGouListenTogetherApi.BIZ_MUSIC_ROOM;
        roomName = "";
        ownerId = "";
        listVersion = "";
        members = Collections.emptyList();
        messages = Collections.emptyList();
        roomSongs = Collections.emptyList();
        SEEN.clear();
        syncStartIndex = 0;
        syncSeekMillis = 0L;
        statusText = "";
        statusIsError = false;
    }

    private static boolean fail(String text) {
        synchronized (LOCK) {
            // 先清干净再标失败：留着上一次的房号会让界面以为还在某个房间里，
            // 而「重试」按钮看起来是加入那个房间，其实不是。
            resetState();
            phase = Phase.FAILED;
            statusText = text;
            statusIsError = true;
        }
        System.err.println("[Music/KuGou] 一起听：" + text);
        return false;
    }

    private static void publish(String text, boolean error) {
        synchronized (LOCK) {
            statusText = text;
            statusIsError = error;
        }
    }

    private static String defaultActiveStatus() {
        return role == Role.HOST
                ? "房间进行中 · 你的播放状态正在同步给房间"
                : "已在房间中 · 房间进度正在自动跟随";
    }

    private static boolean requireHost(String reason) {
        if (roomId.isEmpty() || phase != Phase.ACTIVE) {
            // 不标红：这条多半是界面在会话结束后还按了一次按钮，不是一次故障。
            publish("还没有加入任何房间", false);
            return false;
        }
        if (role != Role.HOST) {
            publish(reason, true);
            return false;
        }
        return true;
    }

    private static void mergeMessages(List<Message> incoming) {
        synchronized (LOCK) {
            for (Message message : incoming) {
                if (message.id.isEmpty() || SEEN.containsKey(message.id)) continue;
                SEEN.put(message.id, message);
            }
            while (SEEN.size() > MAX_MESSAGES) {
                String oldest = SEEN.keySet().iterator().next();
                SEEN.remove(oldest);
            }
            List<Message> merged = new ArrayList<Message>(SEEN.values());
            // 上游给的是从新到旧的一页，按时间正序排完再交出去，界面就能直接自上而下画。
            Collections.sort(merged, new Comparator<Message>() {
                @Override
                public int compare(Message left, Message right) {
                    long delta = left.sentAtMillis - right.sentAtMillis;
                    return delta == 0L ? left.id.compareTo(right.id) : (delta < 0L ? -1 : 1);
                }
            });
            messages = Collections.unmodifiableList(merged);
        }
    }

    // ── 模型转换 ────────────────────────────────────────────────────────────

    /**
     * 酷狗曲目 → {@link Music}。
     *
     * <p>与 {@code KuGouMusicService.adaptTracks} 同一套（那条是私有的，这里只能自己走一遍）：
     * 单条转换失败就跳过，不让一首脏数据毁掉整屏。</p>
     */
    private static List<Music> adaptTracks(List<KuGouMusicApi.Track> tracks) {
        List<Music> result = new ArrayList<Music>();
        if (tracks == null) return result;
        int unnamed = 0;
        for (KuGouMusicApi.Track track : tracks) {
            if (track == null) continue;
            if (safe(track.name).trim().isEmpty()) {
                // 房间队列里确实存在「只有 hash、没有歌名」的条目（服务端自己补的那几首）。
                // 空标题在界面上看起来像渲染坏了，所以给一个认得出来源的占位名，
                // 而不是把这首歌丢掉——丢掉会让房间列表比实际短。
                unnamed++;
                track.name = "房间歌曲 " + unnamed;
            }
            try {
                Music music = Music.fromKuGouTrack(track);
                if (music != null) result.add(music);
            } catch (Throwable ignored) {
                // 一条曲目形状不对不值得把整个房间列表清空。
            }
        }
        return result;
    }

    /** {@link Music} → 酷狗曲目；不是酷狗源或没有 hash 的一律 {@code null}。 */
    private static KuGouMusicApi.Track toTrack(Music music) {
        if (music == null || !music.isKuGou()) return null;
        KuGouMusicApi.Track track = music.toKuGouTrack();
        return track == null || safe(track.hash).isEmpty() ? null : track;
    }

    /** 一条 {@code Music} 都不能变成酷狗曲目时返回空列表，让调用方给出一句确定的原因。 */
    private static List<KuGouMusicApi.Track> toTracks(List<Music> songs) {
        List<KuGouMusicApi.Track> result = new ArrayList<KuGouMusicApi.Track>();
        if (songs == null) return result;
        for (Music music : songs) {
            KuGouMusicApi.Track track = toTrack(music);
            if (track != null && !containsHash(result, track.hash)) result.add(track);
        }
        return result;
    }

    private static boolean containsHash(List<KuGouMusicApi.Track> tracks, String hash) {
        String lower = safe(hash).trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return false;
        for (KuGouMusicApi.Track track : tracks) {
            if (track != null && lower.equals(safe(track.hash).trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** 房间快照的 hash 在本地列表里的下标；找不到（含 hash 为空）都是 0。 */
    private static int indexOfHash(List<Music> songs, String hash) {
        String lower = safe(hash).trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return 0;
        for (int index = 0; index < songs.size(); index++) {
            Music music = songs.get(index);
            if (music == null) continue;
            String candidate = safe(music.getSourceId()).trim().toLowerCase(Locale.ROOT);
            if (lower.equals(candidate)) return index;
        }
        return 0;
    }

    private static List<RoomSummary> summarize(List<KuGouListenTogetherApi.Room> rooms) {
        List<RoomSummary> result = new ArrayList<RoomSummary>();
        if (rooms == null) return result;
        for (KuGouListenTogetherApi.Room room : rooms) {
            if (room == null || safe(room.id).isEmpty()) continue;
            result.add(new RoomSummary(room));
        }
        return result;
    }

    // ── 邀请码解析 ──────────────────────────────────────────────────────────

    /**
     * 从邀请码 / 分享文本 / 裸房号里取房间号。
     *
     * <p>酷狗房号是纯数字，所以带 {@code /} 的一定是链接残片而不是房号，直接判空比硬截一段安全。</p>
     */
    static String parseRoomId(String invite) {
        String value = safe(invite).trim();
        if (value.isEmpty()) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("roomid=");
        if (marker < 0) marker = lower.indexOf("groupid=");
        if (marker >= 0) {
            int start = value.indexOf('=', marker) + 1;
            int end = start;
            while (end < value.length() && " &#?\t\r\n".indexOf(value.charAt(end)) < 0) end++;
            return value.substring(start, end).trim();
        }
        if (lower.startsWith(INVITE_PREFIX)) {
            String[] parts = value.split(":");
            return parts.length >= 3 ? parts[2].trim() : "";
        }
        int hash = value.indexOf('#');
        if (hash > 0) return value.substring(0, hash).trim();
        return value.indexOf('/') >= 0 ? "" : value;
    }

    /** 邀请码里的业务线；裸房号按众乐房（1009）处理，因为自习室已经不是一起听的主路径。 */
    static String parseBiz(String invite, String fallback) {
        String value = safe(invite).trim();
        if (value.toLowerCase(Locale.ROOT).startsWith(INVITE_PREFIX)) {
            String[] parts = value.split(":");
            if (parts.length >= 3) {
                String candidate = parts[1].trim();
                if (KuGouListenTogetherApi.BIZ_SELF_STUDY.equals(candidate)) {
                    return KuGouListenTogetherApi.BIZ_SELF_STUDY;
                }
                return KuGouListenTogetherApi.BIZ_MUSIC_ROOM;
            }
        }
        return safe(fallback).isEmpty() ? KuGouListenTogetherApi.BIZ_MUSIC_ROOM : fallback.trim();
    }

    // ── 杂项 ────────────────────────────────────────────────────────────────

    private static long selfUserId() {
        try {
            return Long.parseLong(KuGouDevice.userId().trim());
        } catch (Throwable malformed) {
            return 0L;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** 网络异常一律吞在这里：一次抖动不该把界面整页打空。 */
    private static void log(String what, Throwable throwable) {
        System.err.println("[Music/KuGou] 一起听 " + what + ": "
                + (throwable == null ? "未知原因" : throwable.getMessage()));
    }
}
