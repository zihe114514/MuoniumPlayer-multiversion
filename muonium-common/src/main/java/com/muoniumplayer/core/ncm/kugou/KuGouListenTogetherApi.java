package com.muoniumplayer.core.ncm.kugou;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 酷狗官方「一起听」（众乐房 / 自习室）的协议层。
 *
 * <p>端点全部对照 {@code MakcRe/KuGouMusicApi} 的 {@code module/listen_together_*.js}
 * 本类只认识酷狗的 JSON，不认识播放器。宿主侧的转换在
 * {@code core.ncm.music.KuGouListenTogetherService}。</p>
 *
 * <h3>网关分工</h3>
 * <ul>
 *   <li>{@code gateway.kugou.com} 上的<b>两批路径</b>：{@code /rmservice/v1/*} 是房间与聊天本身
 *       （建房、加入、心跳、成员、消息），{@code /youth/v1|v2/genting/*} 是众乐房的<b>播放</b>侧
 *       （当前曲目、进度、房主换曲、房间歌单、点歌）。</li>
 *   <li>{@code youth.kugou.com}：发现页与自习室一族的 {@code /v1/*}、{@code /v3/*} 端点。
 *       这个域名在本仓是新的，{@link KuGouHttp} 里没有对应常量，所以常量放在下面 {@link #YOUTH}。</li>
 *   <li>{@code concepts.kugou.com}：只有一个推荐位 {@code /v1/genting/recommend}，
 *       它的 {@code room_biz} 是 {@code 1006}，<b>不是</b>房间的 {@code 1009}，别当成同一条。</li>
 * </ul>
 *
 * <p><b>这批端点全都不带 {@code x-router}</b>：网关按路径前缀自己分流。{@link KuGouMusicApi} 里
 * 那些必须显式给 {@code x-router} 的端点是例外而不是通则，照抄那边的写法会让这里全部 404。</p>
 *
 * <h3>签名与身份</h3>
 * <p>参考实现里这一族统一 {@code encryptType: 'android'}，所以这里一律
 * {@link KuGouHttp.Sign#ANDROID}；{@code youth.kugou.com} 也签，它并不是免签域名。</p>
 * <p>一起听是<b>概念版</b>的功能：参考实现的注释通篇写「概念版客户端」，
 * {@code music_reqcmd} 带的 {@code ppage_id=356753938} 正是 {@link KuGouProfile#CONCEPT}
 * 那条页面链的首项。请求参数按 {@link KuGouHttp#deviceParams()} 跟随<b>凭证自己的身份</b>，
 * 标准版凭证能否建众乐房未实测（<b>待实测</b>）；失败的表现是 {@code error_code} 非 0，
 * 而不是抛异常。</p>
 *
 * <h3>房号有两副面孔</h3>
 * <p>{@code rmservice} 叫 {@code groupid}，{@code genting} 叫 {@code roomid}，值是同一个；
 * 参考实现在每个端点上都是 {@code p.groupid || p.room_id} 这样互为兜底地读。所以本类对外的参数
 * 一律叫 {@code roomId}，按端点决定塞进哪个字段名，<b>不要</b>顺手把两个字段名统一掉。</p>
 *
 * <h3>进度单位是秒</h3>
 * <p>参考实现与概念版客户端在 {@code progress_info.progress} 与 {@code music_player_opr} 的
 * {@code progress} 上都是<b>秒</b>（对照前端 {@code currentTime} 直接取整、且解析侧对
 * {@code > 10000} 的值才除以 1000）。本类对外的方法收毫秒、在边界上整除换算一次，
 * 传毫秒进去会得到一个荒谬的进度。</p>
 *
 * <h3>响应里的 JSON 常常还是字符串</h3>
 * <p>参考实现的 {@code parseJsonValue} 会先把字符串试解析成 JSON 再用：{@code pass_through_data}、
 * {@code switch}、{@code message}、{@code audios} 都可能以字符串形式到达。所以这里读对象/数组一律走
 * {@link #asObject} 与 {@link #asArray}，直接 {@code getAsJsonArray()} 在真机上会抛
 * {@code IllegalStateException}。</p>
 *
 * <h3>响应字段名的齐全度</h3>
 * <p>请求侧的字面量是照抄参考实现的，可信；<b>响应</b>侧只有一部分字段名能从参考实现的前端里读到。
 * 所以读取一律走候选名列表（{@link #listUnder}、{@link #deepText}），取不到就是空值而不是异常。
 * 待实测：{@code msg_history} 与 {@code get_musicroom_member} 的列表键名、
 * {@code music_reqcmd} 的地址键名、{@code group/info} 除了 {@code room_state} 之外到底带不带房间名。</p>
 *
 * <h3>失败约定</h3>
 * <p>与 {@link KuGouMusicApi} 一致：读操作失败返回空列表 / {@code null} / 空串，写操作返回布尔值。
 * 一次网络抖动不该把房间面板整页打空。</p>
 */
public final class KuGouListenTogetherApi {

    /**
     * 一起听的「青春版/概念版」域名。
     *
     * <p>{@link KuGouHttp} 里没有它：那批常量是取流与内容端点的属性，而这个域名只服务房间一族，
     * 放在这里而不是往 {@link KuGouHttp} 里加常量，是为了不把房间协议渗进通用请求层。</p>
     */
    private static final String YOUTH = "https://youth.kugou.com";

    /** 概念版推荐位域名，全类只有 {@link #recommendedRoomsFromConcepts} 用。 */
    private static final String CONCEPTS = "https://concepts.kugou.com";

    /**
     * 房间业务线。{@code 1000} = 自习室（学习模式），{@code 1009} = 众乐房（一起听）。
     *
     * <p>这是每条 rmservice 请求都要带的 {@code biz}，取错的表现是「建房成功但 genting 那一半
     * 完全不认这个房号」，因为 {@code /youth/v1/genting/…} 只服务 1009。</p>
     */
    public static final String BIZ_SELF_STUDY = "1000";
    public static final String BIZ_MUSIC_ROOM = "1009";

    /**
     * 概念版推荐位那条端点上的业务线，<b>不是</b>众乐房的 1009。
     *
     * <p>照抄参考实现的默认值。它返回的是「可以一起听的内容房」这一大类，与自建众乐房不是同一批；
     * 之所以还留着这条端点，是因为它给的房间数量比广场多，界面做「逛一逛」时比 squareRooms 好用。</p>
     */
    public static final String BIZ_RECOMMEND = "1006";

    /**
     * 建房必带的背景图。
     *
     * <p>参考实现把它当默认值写死（{@code DEFAULT_MUSIC_ROOM_BG}）。留空串上游不接受，
     * 所以这是一个<b>必须存在的字面量</b>，不是装饰。</p>
     */
    private static final String DEFAULT_ROOM_BG =
            "https://youthimgbssdl.kugou.com/6e9cdcef8d163d06225d8cbeaa2f1ece.JPEG";

    /** 聊天消息类型。801 = 文本；其余（2001~/3001~/4001~/5100）都是上游生成的系统事件。 */
    public static final int MSG_TYPE_TEXT = 801;

    /**
     * 单次提交的曲目上限。
     *
     * <p>参考实现 {@code musicRoomAudios} 里就是一个 {@code slice(0, 50)}。超出去的部分会被静默丢掉，
     * 所以这里同样截断并在截断时打一行日志，而不是让调用方以为整表都上去了。</p>
     */
    private static final int MAX_AUDIOS_PER_REQUEST = 50;

    /**
     * {@code music_reqcmd} 的页面链路。
     *
     * <p>参考实现里这两个值是<b>硬编码常量</b>并留了注释：概念版是从房间播放页发起这个请求的，
     * gateway 依据页面链路来路由 {@code music_reqcmd}，缺了它上游直接 404。
     * 不要「顺手换成」{@link KuGouProfile#streamPageId()}——那是取流页面的链路，不是房间的。</p>
     */
    private static final String REQCMD_PAGE_ID = "147780134";
    private static final String REQCMD_PARENT_PAGE_ID = "356753938";

    /** 自习室一族的页面链路（参考实现 {@code listen_together_study.js} 顶部的 PAGE_ID 常量）。 */
    private static final String STUDY_PAGE_ID = "711586122";
    private static final String STUDY_PARENT_PAGE_ID = "356753938";
    private static final String STUDY_LIST_PAGE_ID = "191708212";

    /** 播放模式：1 列表循环 / 2 单曲循环 / 3 随机，与概念版 {@code play_mode} 同值。 */
    public static final int PLAY_MODE_LIST = 1;
    public static final int PLAY_MODE_SINGLE = 2;
    public static final int PLAY_MODE_RANDOM = 3;

    private KuGouListenTogetherApi() {
    }

    // ── 数据模型 ────────────────────────────────────────────────────────────

    /**
     * 一个房间。既用来表达「建房/会话恢复」的结果，也用来表达 {@code group/info}、
     * {@code get_musicroom_info}、{@code music_sync_player} 的快照。
     *
     * <p>字段风格跟 {@link KuGouMusicApi.Track}：公有可变字段、由 parse 逐步回填、没有 getter。
     * 各端点回的字段齐全度差别很大，取不到的就是一开始那个空值，调用方按「不知道」处理。</p>
     */
    public static final class Room {
        public String id = "";
        /** 房间名。众乐房建房时它写进 {@code introduction}。 */
        public String name = "";
        public String biz = BIZ_MUSIC_ROOM;
        public String ownerId = "";
        public String ownerName = "";
        public String ownerAvatar = "";
        public String notice = "";
        /** 云端频道主键 {@code global_collection_id}；公开房才有。 */
        public String collectionId = "";
        /** 房间歌单版本，换曲/加歌要回带；上游用它做并发校验。 */
        public String listVersion = "";
        public int memberCount;
        public int memberLimit;
        public boolean allowChat = true;
        /** 房间已解散或被隐藏。{@code group/info} 的判据是 {@code room_state}。 */
        public boolean closed;
        public String closeReason = "";
        /** 只有 {@link #accountSession} 会填：{@code user/get_status} 的 {@code is_owner}。 */
        public boolean selfIsOwner;

        /** 当前曲目快照（{@code sync_player} / 房间详情里带）。 */
        public String currentHash = "";
        public String currentMixSongId = "";
        public String currentSongName = "";
        public String currentArtistName = "";
        /** 已经归一化到毫秒：上游是秒，只有异常大的值才是毫秒。 */
        public long currentProgressMillis;
        public boolean currentPlaying = true;

        public boolean valid() {
            return !KuGouSign.isBlank(id);
        }
    }

    /**
     * 房间里的一个成员/听众。
     *
     * <p>{@link #userId} 用 {@code long} 与 {@code NeteaseListenTogetherService.Member} 对齐，
     * 这样界面同一套成员列表渲染能同时服务两个音源。</p>
     */
    public static final class Member {
        public final long userId;
        public final String nickname;
        public final String avatarUrl;
        /** 自习室成员列表带的累计学习时长（秒）；众乐房没有，恒为 0。 */
        public final long studySeconds;

        Member(long userId, String nickname, String avatarUrl, long studySeconds) {
            this.userId = userId;
            this.nickname = nickname == null ? "" : nickname;
            this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
            this.studySeconds = studySeconds;
        }
    }

    /**
     * 一条房间消息。
     *
     * <p>{@link #type} 保持上游原值不翻译成语义枚举：系统事件类型一直在加
     * （2001~2010 学习、3001~3005 播放、4001~4005 成员、5100 点歌、810/820/821/830 会话），
     * 猜错一个归类不如把原文 {@code alert} 交出去。</p>
     */
    public static final class Message {
        public final String id;
        public final long userId;
        public final String nickname;
        public final String avatarUrl;
        public final int type;
        public final String text;
        public final long sentAtMillis;
        /** {@code type != 801}：系统事件，界面按次要样式排。 */
        public final boolean system;

        Message(String id, long userId, String nickname, String avatarUrl, int type, String text,
                long sentAtMillis, boolean system) {
            this.id = id == null ? "" : id;
            this.userId = userId;
            this.nickname = nickname == null ? "" : nickname;
            this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
            this.type = type;
            this.text = text == null ? "" : text;
            this.sentAtMillis = sentAtMillis;
            this.system = system;
        }
    }

    /**
     * 房间进度快照，对应上游的 {@code progress_info} 对象。
     *
     * <p>{@code init_musicroom} 与 {@code music_add} 都接受它，用它把「房主现在放到哪」一次性
     * 告诉服务端，省掉一次随后的 {@code music_player_opr}。</p>
     */
    public static final class Progress {
        public String hash = "";
        public String albumAudioId = "";
        public long progressMillis;
        public boolean playing = true;
        public int playMode = PLAY_MODE_LIST;

        public Progress() {
        }

        public Progress(String hash, String albumAudioId, long progressMillis, boolean playing, int playMode) {
            this.hash = KuGouSign.nonNull(hash);
            this.albumAudioId = KuGouSign.nonNull(albumAudioId);
            this.progressMillis = Math.max(0L, progressMillis);
            this.playing = playing;
            this.playMode = playMode <= 0 ? PLAY_MODE_LIST : playMode;
        }
    }

    // ── 房间：rmservice ─────────────────────────────────────────────────────

    /**
     * 建房。
     *
     * <p>参数与参考实现 {@code listen_together_room.create} 逐字对齐，其中三条是不能「优化」的：</p>
     * <ul>
     *   <li>{@code cp_notice} 恒为 1：它决定房间里要不要出现进房提示；</li>
     *   <li>{@code room_bg_content} 是一个 <b>JSON 字符串</b>而不是对象（上游 {@code JSON.stringify}
     *       之后才塞进 {@code pass_through_data}），给对象会被读成空背景；</li>
     *   <li>{@code room_privacy} 为 1 时带 {@code global_collection_id}（公开房挂音乐频道），
     *       否则带 {@code capacity}（小房人数）。两者互斥，同时给上游会走错分支。</li>
     * </ul>
     *
     * @param biz               {@link #BIZ_MUSIC_ROOM} 或 {@link #BIZ_SELF_STUDY}
     * @param roomPrivacy       0 表示用默认值（众乐房 1 / 自习室 3）
     * @param capacity          仅非公开房生效，0 表示用上游默认 5
     * @param backgroundUrl     空表示用 {@link #DEFAULT_ROOM_BG}
     * @param globalCollectionId 仅公开房生效，空串表示让上游自建频道
     * @return 新房间号；失败返回空串
     */
    public static String createRoom(String biz, String introduction, int roomPrivacy, int capacity,
                                    String backgroundUrl, String globalCollectionId) {
        String resolvedBiz = KuGouSign.isBlank(biz) ? BIZ_MUSIC_ROOM : biz.trim();
        boolean music = BIZ_MUSIC_ROOM.equals(resolvedBiz);
        int privacy = roomPrivacy <= 0 ? (music ? 1 : 3) : roomPrivacy;

        JsonObject passThrough = new JsonObject();
        passThrough.addProperty("room_privacy", privacy);
        passThrough.addProperty("cp_notice", 1);

        JsonObject body = authBody();
        body.addProperty("biz", resolvedBiz);
        if (music) {
            body.addProperty("introduction", KuGouSign.nonNull(introduction).trim());
            JsonObject background = new JsonObject();
            background.addProperty("bg_img",
                    KuGouSign.isBlank(backgroundUrl) ? DEFAULT_ROOM_BG : backgroundUrl.trim());
            // room_bg_type 上游发的是字符串（String(p.room_bg_type || 2)），不要发数字。
            background.addProperty("room_bg_type", "2");
            passThrough.addProperty("room_bg_content", background.toString());
            if (privacy == 1) {
                passThrough.addProperty("global_collection_id",
                        KuGouSign.nonNull(globalCollectionId).trim());
            } else {
                body.addProperty("capacity", capacity <= 0 ? 5 : capacity);
            }
        } else {
            // 自习室分支带的是 biz_defined_data，参考实现在调用方没给时兜底成「开歌词」。
            JsonArray defined = new JsonArray();
            JsonObject lyricSwitch = new JsonObject();
            lyricSwitch.addProperty("key", "lyric_switch");
            lyricSwitch.addProperty("value", 1);
            defined.add(lyricSwitch);
            body.add("biz_defined_data", defined);
        }
        body.add("pass_through_data", passThrough);

        JsonObject root = post(KuGouHttp.GATEWAY, "/rmservice/v1/group/create",
                KuGouHttp.deviceParams(), body);
        String roomId = roomIdOf(root);
        if (roomId.isEmpty()) {
            System.err.println("[Music/KuGou] 建房未返回 groupid: " + errorText(root));
            return "";
        }
        return roomId;
    }

    /** 加入房间。{@code pass_through_data} 在这里只带 {@code cp_notice}，与建房不同。 */
    public static boolean joinRoom(String biz, String roomId) {
        if (KuGouSign.isBlank(roomId)) return false;
        JsonObject passThrough = new JsonObject();
        passThrough.addProperty("cp_notice", 1);
        JsonObject body = groupBody(biz, roomId);
        body.add("pass_through_data", passThrough);
        return ok(post(KuGouHttp.GATEWAY, "/rmservice/v1/group/join", KuGouHttp.deviceParams(), body),
                "加入房间");
    }

    /** 退出房间（成员身份）。房主走 {@link #dismissRoom} 才是关房。 */
    public static boolean leaveRoom(String biz, String roomId) {
        return groupPost("/rmservice/v1/group/leave", biz, roomId, "退出房间");
    }

    /** 解散房间（房主）。网易云那边的对偶是 {@code listen_together_end}。 */
    public static boolean dismissRoom(String biz, String roomId) {
        return groupPost("/rmservice/v1/group/dismiss", biz, roomId, "解散房间");
    }

    /**
     * 心跳。
     *
     * <p>它<b>不</b>带播放状态——网易云的心跳接口里塞了 songId/progress，酷狗把这两件事拆到了
     * {@code genting} 那一族。所以断心跳只是掉出成员列表，不会把房间进度带歪。</p>
     */
    public static boolean heartbeat(String biz, String roomId) {
        return groupPost("/rmservice/v1/group/heartbeat", biz, roomId, null);
    }

    /**
     * 房间状态快照。
     *
     * <p>注意 {@code biz} 在<b>查询串</b>而 {@code groupid} 在<b>请求体</b>，且这条请求体里
     * <b>没有</b> {@code userid}/{@code token}（参考实现 {@code state} 就没带 authBody）——
     * 凭证只在查询串上。照抄别处写法会多一个字段而被拒。</p>
     *
     * <p>请求失败时返回的 {@link Room} 只带房号、{@code closed} 为 {@code false}：
     * <b>「读不到」不等于「房间没了」</b>。判散要拿到一个成功响应且 {@code room_state} 不为 1，
     * 否则一次超时就把人踢出自己的房间。</p>
     */
    public static Room roomState(String biz, String roomId) {
        Room room = new Room();
        if (KuGouSign.isBlank(roomId)) return room;
        room.biz = KuGouSign.isBlank(biz) ? BIZ_MUSIC_ROOM : biz.trim();
        room.id = roomId.trim();
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("biz", room.biz);
        JsonObject body = new JsonObject();
        body.addProperty("groupid", room.id);
        JsonObject root = post(KuGouHttp.GATEWAY, "/rmservice/v1/group/info", params, body);
        if (root == null) return room;
        // room_state 是「房间还活着」的唯一判据：参考实现在它不为 1 时按「群组已解散」处理，
        // 而 HTTP 仍是 200、status 仍是 1，所以 isOk 过得了、房间其实已经没了。
        room.closed = !deepFlag(root, "room_state");
        mergeInto(room, parseRoom(unwrapData(root)));
        return room;
    }

    /**
     * 把「读到了才算数」的字段并进来，空值不覆盖已有值。
     *
     * <p>{@code group/info} 只负责给活性与少量身份信息，完整内容要靠 {@code get_musicroom_info}；
     * 两边合成的意义是：某一条失败时界面上的房间不至于整个变空。</p>
     */
    private static void mergeInto(Room target, Room source) {
        if (source == null) return;
        if (KuGouSign.isBlank(target.id)) target.id = source.id;
        if (KuGouSign.isBlank(target.name)) target.name = source.name;
        if (KuGouSign.isBlank(target.ownerId)) target.ownerId = source.ownerId;
        if (KuGouSign.isBlank(target.ownerName)) target.ownerName = source.ownerName;
        if (KuGouSign.isBlank(target.ownerAvatar)) target.ownerAvatar = source.ownerAvatar;
        if (KuGouSign.isBlank(target.notice)) target.notice = source.notice;
        if (KuGouSign.isBlank(target.collectionId)) target.collectionId = source.collectionId;
        if (KuGouSign.isBlank(target.listVersion)) target.listVersion = source.listVersion;
        if (target.memberCount == 0) target.memberCount = source.memberCount;
        if (target.memberLimit == 0) target.memberLimit = source.memberLimit;
        if (KuGouSign.isBlank(target.currentHash)) target.currentHash = source.currentHash;
        if (KuGouSign.isBlank(target.currentMixSongId)) target.currentMixSongId = source.currentMixSongId;
        if (KuGouSign.isBlank(target.currentSongName)) target.currentSongName = source.currentSongName;
        if (KuGouSign.isBlank(target.currentArtistName)) target.currentArtistName = source.currentArtistName;
        if (target.currentProgressMillis == 0L) target.currentProgressMillis = source.currentProgressMillis;
        target.closed = target.closed || source.closed;
    }

    /**
     * 当前账号在这个业务线上的会话。
     *
     * <p>用途有两个，都很重要：</p>
     * <ul>
     *   <li>进程重启后本地房号丢了，用 {@code groupid=""} 查回来（这就是参考实现创建前的预检）；</li>
     *   <li>建房/加入被 {@code error_code=20006}（「账号已有未结束的会话」）拒掉时，
     *       真正的房间其实在这里，不该把它报成失败。</li>
     * </ul>
     */
    public static Room accountSession(String biz) {
        JsonObject body = groupBody(biz, "");
        JsonObject root = post(KuGouHttp.GATEWAY, "/rmservice/v1/user/get_status",
                KuGouHttp.deviceParams(), body);
        if (root == null) return null;
        Room room = parseRoom(unwrapData(root));
        if (!room.valid()) room.id = roomIdOf(root);
        room.selfIsOwner = deepFlag(root, "is_owner");
        room.biz = KuGouSign.isBlank(biz) ? BIZ_MUSIC_ROOM : biz.trim();
        return room.valid() ? room : null;
    }

    /**
     * 开关房间聊天。
     *
     * <p>{@code switch.chat} 的取值是反直觉的：{@code 1} 开、{@code 2} 关 —— <b>没有 0</b>。
     * 参考实现里那句 {@code Number(p.chat) === 1 ? 1 : 2} 就是这个意思，别改成 0/1。
     * 这条端点还固定按众乐房（{@code biz=1009}）发，与调用方当前房间无关。</p>
     */
    public static boolean updateChatEnabled(String roomId, boolean enabled) {
        if (KuGouSign.isBlank(roomId)) return false;
        JsonObject chatSwitch = new JsonObject();
        chatSwitch.addProperty("chat", enabled ? 1 : 2);
        JsonObject body = new JsonObject();
        body.addProperty("groupid", roomId.trim());
        body.addProperty("biz", BIZ_MUSIC_ROOM);
        // "switch" 是 Java 关键字，但作为 JSON 字段名原样存在——用字符串字面量取键，不改名。
        body.add("switch", chatSwitch);
        return ok(post(KuGouHttp.GATEWAY, "/rmservice/v1/group/update_info",
                KuGouHttp.deviceParams(), body), "更新聊天开关");
    }

    // ── 聊天 ────────────────────────────────────────────────────────────────

    /**
     * 发一条文本消息。
     *
     * <p>正文放在 {@code message.alert} 而不是 {@code message.content}：参考实现
     * {@code alert: p.alert || p.message} 就是这条路径，而解析侧也把 {@code alert} 排在最前。
     * 昵称与头像是发送方自己填的（服务端不回填），所以传空会让对方看到「房间成员」。</p>
     */
    public static boolean sendChat(String biz, String roomId, String text, String nickname, String avatarUrl) {
        if (KuGouSign.isBlank(roomId) || KuGouSign.isBlank(text)) return false;
        JsonObject message = new JsonObject();
        message.addProperty("msgtype", MSG_TYPE_TEXT);
        message.addProperty("nickname", KuGouSign.nonNull(nickname).trim());
        message.addProperty("img", KuGouSign.nonNull(avatarUrl).trim());
        message.addProperty("alert", text.trim());
        JsonObject body = groupBody(biz, roomId);
        body.add("message", message);
        return ok(post(KuGouHttp.GATEWAY, "/rmservice/v1/group/chat", KuGouHttp.deviceParams(), body),
                "发送消息");
    }

    /**
     * 拉房间消息（含系统事件）。
     *
     * <p>{@code maxid} 与 {@code pagesize} 在这里是<b>字符串</b>——参考实现写的就是
     * {@code p.maxid || '0'} 与 {@code p.pagesize || '20'}，签名算的是序列化后的体，改类型会让签名变。</p>
     * <p>翻页游标语义未实测（<b>待实测</b>）：参考实现与前端客户端每次都用 {@code maxid='0'}
     * 拉最新一页、再在前端按 {@code msgid} 去重，这里保持同样用法。</p>
     *
     * @param maxId    空表示从最新一页开始
     * @param pageSize 上游默认 20，客户端用 50
     */
    public static List<Message> chatHistory(String biz, String roomId, String maxId, int pageSize) {
        List<Message> result = new ArrayList<Message>();
        if (KuGouSign.isBlank(roomId)) return result;
        JsonObject body = groupBody(biz, roomId);
        body.addProperty("maxid", KuGouSign.isBlank(maxId) ? "0" : maxId.trim());
        body.addProperty("pagesize", String.valueOf(clampPageSize(pageSize)));
        JsonObject root = post(KuGouHttp.GATEWAY, "/rmservice/v1/group/msg_history",
                KuGouHttp.deviceParams(), body);
        JsonArray items = listUnder(root, "list", "msgs", "messages", "msg_list", "items", "info");
        if (items == null) return result;
        for (JsonElement element : items) {
            Message message = parseMessage(element);
            if (message != null) result.add(message);
        }
        return result;
    }

    // ── 众乐房：播放侧 ──────────────────────────────────────────────────────

    /**
     * 房主初始化房间：铺歌单 + 一次性交代当前进度。
     *
     * <p>建房之后<b>必须</b>调它，否则房间在成员列表里存在、但 {@code genting} 那一半不认这个房号，
     * 表现是别人进得来却看不到歌单。参考实现 {@code initialize} 之后才 {@code hydrateSession}。</p>
     *
     * @param progress 可为 {@code null}（只铺队列、不带进度）
     */
    public static boolean initMusicRoom(String roomId, List<KuGouMusicApi.Track> tracks, Progress progress) {
        if (KuGouSign.isBlank(roomId)) return false;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("roomid", roomId.trim());
        JsonObject body = new JsonObject();
        // sendall=1：把这条队列广播给房内所有人，不是「只更新本地视图」。
        body.addProperty("sendall", 1);
        body.add("audios", audioArray(tracks));
        if (progress != null) body.add("progress_info", progressInfo(progress));
        return ok(post(KuGouHttp.GATEWAY, "/youth/v1/genting/init_musicroom", params, body),
                "初始化房间歌单");
    }

    /**
     * 房主换曲。
     *
     * <p>{@code is_auto} 是字符串 {@code "1"/"0"}（参考实现 {@code Number(p.is_auto||0) ? '1' : '0'}），
     * 表示「这是自动连播而不是人点的」，房间里的提示文案跟着它变。</p>
     *
     * @param listVersion 上一次拿到的房间歌单版本，空表示不带
     */
    public static boolean switchRoomSong(String roomId, KuGouMusicApi.Track track, String listVersion,
                                         boolean auto) {
        if (KuGouSign.isBlank(roomId) || track == null || KuGouSign.isBlank(track.hash)) return false;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("roomid", roomId.trim());
        JsonObject body = new JsonObject();
        // act_type=1 是「切到指定曲」；参考实现只用到这一种取值。
        body.addProperty("act_type", 1);
        body.addProperty("list_version", KuGouSign.nonNull(listVersion).trim());
        body.addProperty("is_auto", auto ? "1" : "0");
        body.add("audio", audioObject(track.hash, track.requestAlbumAudioId()));
        return ok(post(KuGouHttp.GATEWAY, "/youth/v1/genting/music_sw", params, body), "房间换曲");
    }

    /**
     * 上报播放进度（{@code action=2}）。
     *
     * <p>单位是秒且<b>向下取整</b>，与参考实现 {@code Math.floor} 一致；进度不能为负。</p>
     */
    public static boolean reportProgress(String roomId, long progressMillis) {
        Map<String, String> params = roomParams(roomId);
        if (params == null) return false;
        JsonObject body = new JsonObject();
        body.addProperty("action", 2);
        body.addProperty("progress", Math.max(0L, progressMillis / 1000L));
        return ok(post(KuGouHttp.GATEWAY, "/youth/v1/genting/music_player_opr", params, body), null);
    }

    /**
     * 上报暂停/继续（{@code action=3}）。
     *
     * <p>{@code pause} 是字符串，而且取值是 {@code 1=在播}、{@code 2=已暂停}——
     * 与字段名的直觉正好相反（参考实现 {@code Number(p.pause||2) === 1 ? '1' : '2'}）。
     * 写反的结果是全房跟着你反过来。</p>
     */
    public static boolean reportPlayState(String roomId, boolean playing) {
        Map<String, String> params = roomParams(roomId);
        if (params == null) return false;
        JsonObject body = new JsonObject();
        body.addProperty("action", 3);
        body.addProperty("pause", playing ? "1" : "2");
        return ok(post(KuGouHttp.GATEWAY, "/youth/v1/genting/music_player_opr", params, body), null);
    }

    /** 上报循环模式（{@code action=1}）：{@code play_mode} 是数字，取值见 {@code PLAY_MODE_*}。 */
    public static boolean reportPlayMode(String roomId, int playMode) {
        Map<String, String> params = roomParams(roomId);
        if (params == null) return false;
        JsonObject body = new JsonObject();
        body.addProperty("action", 1);
        body.addProperty("play_mode", playMode <= 0 ? PLAY_MODE_LIST : playMode);
        return ok(post(KuGouHttp.GATEWAY, "/youth/v1/genting/music_player_opr", params, body), null);
    }

    /**
     * 拉房间当前的播放快照。
     *
     * <p>这是客机唯一的「主机在放什么」来源，参考实现里它就是客机轮询的主接口。
     * 请求体是<b>空对象</b> {@code {}}（签名算的是 {@code "{}"}），{@code frm=2} 表示「来自手机端」，
     * 上游按它决定广播范围。</p>
     */
    public static Room syncPlayer(String roomId) {
        if (KuGouSign.isBlank(roomId)) return null;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("roomid", roomId.trim());
        params.put("frm", "2");
        JsonObject root = post(KuGouHttp.GATEWAY, "/youth/v1/genting/music_sync_player", params,
                new JsonObject());
        if (root == null) return null;
        Room room = parseRoom(unwrapData(root));
        if (!room.valid()) {
            room.id = roomId.trim();
        }
        room.biz = BIZ_MUSIC_ROOM;
        return room;
    }

    /**
     * 房间歌单。
     *
     * @param cursor 上一页最后一条曲目，上游按它做游标翻页；可为 {@code null}
     */
    public static List<KuGouMusicApi.Track> roomPlaylist(String roomId, KuGouMusicApi.Track cursor,
                                                         int pageSize) {
        List<KuGouMusicApi.Track> result = new ArrayList<KuGouMusicApi.Track>();
        Map<String, String> params = roomParams(roomId);
        if (params == null) return result;
        JsonObject body = new JsonObject();
        body.addProperty("pagesize", clampPageSize(pageSize));
        if (cursor != null && !KuGouSign.isBlank(cursor.hash)) {
            body.add("audio", audioObject(cursor.hash, cursor.requestAlbumAudioId()));
        }
        JsonObject root = post(KuGouHttp.GATEWAY, "/youth/v1/genting/music_fetch_list", params, body);
        collectTracks(root, result);
        return result;
    }

    /**
     * 服务端维护的「近期播放队列」。
     *
     * <p>参考实现留了注释：概念版客户端为这个端点签名并发送<b>空请求体</b>，不是 JSON 对象。
     * 所以这里传空串——签名里参与拼接的体就是空串，换成 {@code "{}"} 是另一个签名值。</p>
     * <p>客机不需要翻完整房主歌单时读这条，返回的就是界面上看到的那段队列。</p>
     */
    public static List<KuGouMusicApi.Track> recentPlaylist(String roomId) {
        List<KuGouMusicApi.Track> result = new ArrayList<KuGouMusicApi.Track>();
        Map<String, String> params = roomParams(roomId);
        if (params == null) return result;
        JsonObject root = KuGouHttp.postJson(KuGouHttp.GATEWAY, "/youth/v1/genting/music_recent_list",
                params, "", KuGouHttp.Sign.ANDROID, null);
        collectTracks(root, result);
        return result;
    }

    /**
     * 往房间歌单加歌（房主加歌 / 处理点歌）。
     *
     * @param action      1 = 房主自己加，4 = 代人加点歌
     * @param orderUserId 代人加时的点歌人 ID，非空时查询串会多带 {@code order_userid} 与 {@code source=1}
     */
    public static boolean addRoomSongs(String roomId, List<KuGouMusicApi.Track> tracks, int action,
                                       String listVersion, String orderUserId, Progress progress) {
        Map<String, String> params = roomParams(roomId);
        if (params == null) return false;
        String requester = KuGouSign.nonNull(orderUserId).trim();
        if (!requester.isEmpty()) {
            params.put("order_userid", requester);
            params.put("source", "1");
        }
        JsonObject body = new JsonObject();
        body.addProperty("action", action <= 0 ? 4 : action);
        body.addProperty("list_version", KuGouSign.nonNull(listVersion).trim());
        body.addProperty("sendall", 1);
        body.add("audios", audioArray(tracks));
        if (progress != null) body.add("progress_info", progressInfo(progress));
        return ok(post(KuGouHttp.GATEWAY, "/youth/v1/genting/music_add", params, body), "房间加歌");
    }

    /** 点歌（成员向房间提一首）。 */
    public static boolean orderSong(String roomId, KuGouMusicApi.Track track) {
        if (track == null || KuGouSign.isBlank(track.hash)) return false;
        Map<String, String> params = roomParams(roomId);
        if (params == null) return false;
        JsonObject body = new JsonObject();
        body.addProperty("mixsongid", track.requestAlbumAudioId());
        body.addProperty("hash", KuGouSign.lower(track.hash));
        return ok(post(KuGouHttp.GATEWAY, "/youth/v1/genting/order_song", params, body), "点歌");
    }

    /**
     * 点歌列表（房主要处理的那张队列）。
     *
     * <p>点歌人（条目里的 {@code user_info}）没有建模进返回类型：界面上「谁点的」这一行
     * 用 {@link #chatHistory} 里的 5100 事件更准，那条本来就带昵称与时间。</p>
     */
    public static List<KuGouMusicApi.Track> songOrderList(String roomId) {
        List<KuGouMusicApi.Track> result = new ArrayList<KuGouMusicApi.Track>();
        if (KuGouSign.isBlank(roomId)) return result;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("roomid", roomId.trim());
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/youth/v1/genting/song_order_list",
                params, KuGouHttp.Sign.ANDROID);
        collectTracks(root, result);
        return result;
    }

    /** 撤掉某个人的点歌。{@code order_userid} 在这里是字符串。 */
    public static boolean removeSongOrder(String roomId, KuGouMusicApi.Track track, String orderUserId) {
        if (track == null || KuGouSign.isBlank(track.hash)) return false;
        Map<String, String> params = roomParams(roomId);
        if (params == null) return false;
        JsonObject body = new JsonObject();
        body.addProperty("mixsongid", track.requestAlbumAudioId());
        body.addProperty("hash", KuGouSign.lower(track.hash));
        body.addProperty("order_userid", KuGouSign.nonNull(orderUserId).trim());
        return ok(post(KuGouHttp.GATEWAY, "/youth/v1/genting/remove_song", params, body), "撤销点歌");
    }

    /**
     * 房间授权的取流地址。
     *
     * <p>参考实现强调这条端点<b>必须</b>带 {@code page_id}/{@code ppage_id} 那对固定页面链路，
     * 缺了网关直接 404；{@code roomid} 在请求体里而不是查询串（和同族其它端点不一样）。</p>
     *
     * <p>本仓平时用 {@link KuGouMusicApi#songUrl} 取流，这条只在「房间给了一个本机
     * 解析不出来的 hash」时兜底用。返回的地址带过期参数，<b>必须即取即用</b>。</p>
     *
     * @return 播放地址；失败返回空串
     */
    public static String roomPlaybackUrl(String roomId, String hash, String mixSongId) {
        if (KuGouSign.isBlank(roomId) || KuGouSign.isBlank(hash)) return "";
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("page_id", REQCMD_PAGE_ID);
        params.put("ppage_id", REQCMD_PARENT_PAGE_ID);
        JsonObject body = authBody();
        body.addProperty("roomid", roomId.trim());
        body.add("audio", audioObject(hash, mixSongId));
        JsonObject root = post(KuGouHttp.GATEWAY, "/youth/v1/genting/music_reqcmd", params, body);
        if (root == null) return "";
        // 参考实现的解析侧对「播放地址既可能是字符串也可能是字符串数组」留了后手，这里两种都读。
        String url = deepText(root, "play_url", "url", "audio_url");
        if (url.isEmpty()) {
            JsonArray urls = deepArray(root, "url", "play_url");
            url = firstUrl(urls);
        }
        return url;
    }

    /** 众乐房详情（{@code genting} 侧，比 {@code group/info} 给的字段全）。 */
    public static Room musicRoomDetail(String roomId) {
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("roomid", KuGouSign.nonNull(roomId).trim());
        params.put("biz", BIZ_MUSIC_ROOM);
        return fetchRoom(KuGouHttp.GATEWAY, "/youth/v1/genting/get_musicroom_info", params, roomId,
                BIZ_MUSIC_ROOM);
    }

    /**
     * 房间听众列表。
     *
     * <p><b>不要</b>改用 {@code rmservice} 那一族的成员端点：参考实现留了注释说明它返回的是
     * <b>连麦席位</b>，普通进房听歌的人根本不在里面。这条才是界面上那个「谁在听」。
     * {@code apiver} 固定为字符串 {@code '3'}。</p>
     */
    public static List<Member> musicRoomMembers(String roomId, int page, int pageSize) {
        List<Member> result = new ArrayList<Member>();
        if (KuGouSign.isBlank(roomId)) return result;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("roomid", roomId.trim());
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("pagesize", String.valueOf(clampPageSize(pageSize)));
        params.put("apiver", "3");
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/youth/v1/genting/get_musicroom_member",
                params, KuGouHttp.Sign.ANDROID);
        collectMembers(root, result);
        return result;
    }

    /** 跟听历史（房间自己那条「最近在这个房间听过」）。{@code last_id} 是游标，0 起。 */
    public static List<KuGouMusicApi.Track> roomPlayHistory(long lastId) {
        List<KuGouMusicApi.Track> result = new ArrayList<KuGouMusicApi.Track>();
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("last_id", String.valueOf(Math.max(0L, lastId)));
        JsonObject root = KuGouHttp.get(KuGouHttp.GATEWAY, "/youth/v1/genting/history", params,
                KuGouHttp.Sign.ANDROID);
        collectTracks(root, result);
        return result;
    }

    // ── 发现页 / 推荐房间 ───────────────────────────────────────────────────

    /**
     * 推荐房间（{@code /youth/v2/genting/recommend}）。
     *
     * <p>分页是<b>零起</b>的：参考实现写的是 {@code page: max(0, Number(p.page||1) - 1)}，
     * 也就是对外说「第 1 页」要发 {@code page=0}。这条最容易在重构时被「顺手统一」掉，
     * 然后第 2 页开始全部空一页。{@code room_biz} 固定 {@code 1009}（众乐房），
     * {@code loop_pick} 是「循环取」开关，取 0。</p>
     *
     * @param tags     标签筛选（空表示不限）
     * @param behavior 参考实现里 {@code mixsongid} 存在时才带，是「以某首歌为种子推荐」；空表示不带。
     *                 请求体 {@code {data: [...]}} 装的是客户端的行为流（听过哪些，用来去重），
     *                 本仓没有房间侧的埋点，固定发空数组。
     */
    public static List<Room> recommendRooms(int page, int pageSize, String tags, String behavior) {
        List<Room> result = new ArrayList<Room>();
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("page", String.valueOf(Math.max(0, page - 1)));
        params.put("pagesize", String.valueOf(clampPageSize(pageSize)));
        params.put("loop_pick", "0");
        params.put("tags", KuGouSign.nonNull(tags).trim());
        params.put("room_biz", BIZ_MUSIC_ROOM);
        if (!KuGouSign.isBlank(behavior)) params.put("mixsongid", behavior.trim());
        JsonObject body = new JsonObject();
        body.add("data", new JsonArray());
        JsonObject root = post(KuGouHttp.GATEWAY, "/youth/v2/genting/recommend", params, body);
        collectRooms(root, result, BIZ_MUSIC_ROOM);
        return result;
    }

    /**
     * 发现页房间广场（{@code youth.kugou.com/v1/genting/square}）。
     *
     * <p>{@code order_type} 上游默认 1（热度）；这里保持默认，不猜其它取值的含义。</p>
     */
    public static List<Room> squareRooms(int page, int pageSize, int orderType) {
        List<Room> result = new ArrayList<Room>();
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("pagesize", String.valueOf(clampPageSize(pageSize)));
        params.put("order_type", String.valueOf(orderType <= 0 ? 1 : orderType));
        JsonObject root = KuGouHttp.get(YOUTH, "/v1/genting/square", params, KuGouHttp.Sign.ANDROID);
        collectRooms(root, result, BIZ_MUSIC_ROOM);
        return result;
    }

    /**
     * 概念版推荐位。
     *
     * <p>和 {@link #recommendRooms} 不是一条端点：域名不同、而且 {@code room_biz} 默认是
     * {@code 1006}。参考实现照抄客户端就是这个值，<b>不要</b>改成 1009，两者返回的不是同一批房间。</p>
     */
    public static List<Room> recommendedRoomsFromConcepts(int page, int pageSize) {
        List<Room> result = new ArrayList<Room>();
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("pagesize", String.valueOf(clampPageSize(pageSize)));
        params.put("room_biz", "1006");
        // 参考实现没给 config.data，但方法是 POST，于是发一个空对象。
        JsonObject root = post(CONCEPTS, "/v1/genting/recommend", params, new JsonObject());
        collectRooms(root, result, BIZ_RECOMMEND);
        return result;
    }

    /** 最近去过的房间动态（重进上一次的房间用）。免参数。 */
    public static List<Room> recentRoomDynamic() {
        List<Room> result = new ArrayList<Room>();
        JsonObject root = KuGouHttp.get(YOUTH, "/v3/user/recent_room_dynamic",
                KuGouHttp.deviceParams(), KuGouHttp.Sign.ANDROID);
        collectRooms(root, result, BIZ_MUSIC_ROOM);
        return result;
    }

    // ── 学习模式（自习室，biz=1000）──────────────────────────────────────────
    //
    // 参考实现把这一族完整列出了，但锁定的那份前端已经把它下线（进房前直接抛
    // 「自习室已下线，目前仅支持众乐房」），所以这里只做到「能列、能看、能进」的程度，
    // 不参与网易云那套主机的播放同步。真正的建房仍然走 createRoom(BIZ_SELF_STUDY, …)。
    //
    // 另外参考实现有一条 delete_created_room 是 **DELETE** 方法
    // （gateway /youth/v1/room/delete_room，查询串带 global_collection_id 与 roomid）。
    // KuGouHttp 没有 DELETE 的封装，绕过去要手工签名 + 手工拼头，而这条既没有网易云对偶、
    // 又是破坏性操作，故未实现。
    // 待实测：自习室端点在标准版凭证下的实际可用性。

    /** 自习室列表（按标签）。页面链路两个值是参考实现里的固定值。 */
    public static List<Room> studyRooms(int page, int pageSize, int sort, String tagId) {
        List<Room> result = new ArrayList<Room>();
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("pagesize", String.valueOf(clampPageSize(pageSize)));
        params.put("sort", String.valueOf(Math.max(0, sort)));
        params.put("page_id", STUDY_LIST_PAGE_ID);
        params.put("ppage_id", STUDY_PARENT_PAGE_ID);
        params.put("tag_id", KuGouSign.nonNull(tagId).trim());
        JsonObject root = KuGouHttp.get(YOUTH, "/v1/room/get_room_list_by_tag", params,
                KuGouHttp.Sign.ANDROID);
        collectRooms(root, result, BIZ_SELF_STUDY);
        return result;
    }

    /** 自习室详情。房号在查询串里叫 {@code room_id}（众乐房那条叫 {@code roomid}）。 */
    public static Room studyRoomDetail(String roomId) {
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("room_id", KuGouSign.nonNull(roomId).trim());
        return fetchRoom(YOUTH, "/v1/room/get_room_detail", params, roomId, BIZ_SELF_STUDY);
    }

    /** 自习室成员列表。{@code member_type}：1 普通成员（上游默认），2 未实测含义。 */
    public static List<Member> studyMembers(String roomId, int page, int pageSize, int memberType) {
        List<Member> result = new ArrayList<Member>();
        if (KuGouSign.isBlank(roomId)) return result;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("room_id", roomId.trim());
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("pagesize", String.valueOf(clampPageSize(pageSize)));
        params.put("member_type", String.valueOf(memberType <= 0 ? 1 : memberType));
        JsonObject root = KuGouHttp.get(YOUTH, "/v1/room/get_member_list", params,
                KuGouHttp.Sign.ANDROID);
        collectMembers(root, result);
        return result;
    }

    /** 自己创建过的自习室。免参数。 */
    public static List<Room> createdStudyRooms() {
        List<Room> result = new ArrayList<Room>();
        JsonObject root = KuGouHttp.get(YOUTH, "/v1/study/user_create_room_list",
                KuGouHttp.deviceParams(), KuGouHttp.Sign.ANDROID);
        collectRooms(root, result, BIZ_SELF_STUDY);
        return result;
    }

    /**
     * 配置自习室（房主改名/公告/曲风）。
     *
     * <p>查询串里的 {@code page_id} 由 {@code music_type} 决定（参考实现：1→711586122、
     * 2→971343961、3→711357575 那一组），这里只暴露 {@code music_type} 并按同表映射。
     * {@code music_type==3} 走白噪音分支（带 {@code white_noise_type}、不带曲风与曲目）。</p>
     */
    public static boolean configureStudyRoom(String roomId, String roomName, String notice,
                                             String globalCollectionId, boolean allowChat,
                                             String roomTag, int musicType, String musicStyle,
                                             List<KuGouMusicApi.Track> tracks) {
        int type = musicType <= 0 ? 1 : musicType;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("page_id", String.valueOf(studyPageIdFor(type)));
        params.put("ppage_id", STUDY_PARENT_PAGE_ID);
        params.put("type", "1");

        JsonObject body = new JsonObject();
        body.addProperty("room_id", KuGouSign.nonNull(roomId).trim());
        body.addProperty("room_name", KuGouSign.nonNull(roomName).trim());
        body.addProperty("global_collection_id", KuGouSign.nonNull(globalCollectionId).trim());
        body.addProperty("room_notice", KuGouSign.nonNull(notice).trim());
        body.addProperty("allow_chat", allowChat ? 1 : 0);
        body.addProperty("room_tag", KuGouSign.isBlank(roomTag) ? "2" : roomTag.trim());
        body.addProperty("music_type", type);
        if (type == 3) {
            body.addProperty("white_noise_type", 1);
        } else {
            if (!KuGouSign.isBlank(musicStyle)) body.addProperty("music_style", musicStyle.trim());
            JsonArray audios = audioArray(tracks);
            if (audios.size() > 0) body.add("audios", audios);
        }
        return ok(post(YOUTH, "/v1/user/make_room", params, body), "配置自习室");
    }

    /** 自习室的播放快照。注意 {@code roomid}/{@code frm} 在<b>请求体</b>里，页面链路在查询串里。 */
    public static Room studySyncPlayer(String roomId) {
        if (KuGouSign.isBlank(roomId)) return null;
        Map<String, String> params = studyPageParams();
        JsonObject body = authBody();
        body.addProperty("roomid", roomId.trim());
        body.addProperty("frm", 2);
        JsonObject root = post(YOUTH, "/v1/music/sync_player", params, body);
        if (root == null) return null;
        Room room = parseRoom(unwrapData(root));
        if (!room.valid()) room.id = roomId.trim();
        room.biz = BIZ_SELF_STUDY;
        return room;
    }

    /** 自习室歌单。 */
    public static List<KuGouMusicApi.Track> studyPlaylist(String roomId, int pageSize) {
        List<KuGouMusicApi.Track> result = new ArrayList<KuGouMusicApi.Track>();
        if (KuGouSign.isBlank(roomId)) return result;
        Map<String, String> params = studyPageParams();
        JsonObject body = authBody();
        body.addProperty("roomid", roomId.trim());
        body.addProperty("frm", 2);
        body.addProperty("pagesize", clampPageSize(pageSize));
        JsonObject root = post(YOUTH, "/v1/music/fetch_list", params, body);
        collectTracks(root, result);
        return result;
    }

    // ── 请求与解析辅助 ──────────────────────────────────────────────────────

    /**
     * 「GET 一条详情端点 → {@link Room}」的公共形状。
     *
     * <p>房号在查询串里的字段名（{@code roomid} / {@code room_id}）与业务线由调用点决定，
     * 这两个差异是上游真实的，不是可以统一掉的历史包袱。</p>
     */
    private static Room fetchRoom(String baseUrl, String path, Map<String, String> params,
                                  String roomId, String biz) {
        if (KuGouSign.isBlank(roomId)) return null;
        JsonObject root = KuGouHttp.get(baseUrl, path, params, KuGouHttp.Sign.ANDROID);
        if (root == null) return null;
        Room room = parseRoom(unwrapData(root));
        room.biz = biz;
        if (!room.valid()) room.id = roomId.trim();
        return room.valid() ? room : null;
    }

    private static Map<String, String> studyPageParams() {
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("page_id", STUDY_PAGE_ID);
        params.put("ppage_id", STUDY_PARENT_PAGE_ID);
        return params;
    }

    /** 参考实现 {@code configure} 里那张 page_id 表。 */
    private static long studyPageIdFor(int musicType) {
        if (musicType == 2) return 971343961L;
        if (musicType == 3) return 711357575L;
        return Long.parseLong(STUDY_PAGE_ID);
    }

    private static boolean groupPost(String path, String biz, String roomId, String failureHint) {
        if (KuGouSign.isBlank(roomId)) return false;
        return ok(post(KuGouHttp.GATEWAY, path, KuGouHttp.deviceParams(), groupBody(biz, roomId)),
                failureHint);
    }

    /** rmservice 一族通用的请求体：authBody + biz + groupid。 */
    private static JsonObject groupBody(String biz, String roomId) {
        JsonObject body = authBody();
        body.addProperty("biz", KuGouSign.isBlank(biz) ? BIZ_MUSIC_ROOM : biz.trim());
        body.addProperty("groupid", KuGouSign.nonNull(roomId).trim());
        return body;
    }

    /**
     * {@code authBody}：{@code userid} 必须是<b>数字</b>。
     *
     * <p>参考实现写的是 {@code Number(params?.userid || 0)}。查询串上它仍是字符串（
     * {@link KuGouHttp#deviceParams()} 那套），但请求体里给字符串 rmservice 会当成缺参。</p>
     */
    private static JsonObject authBody() {
        JsonObject body = new JsonObject();
        body.addProperty("userid", selfUserId());
        body.addProperty("token", KuGouDevice.isLoggedIn() ? KuGouDevice.token() : "");
        return body;
    }

    private static long selfUserId() {
        try {
            return Long.parseLong(KuGouDevice.userId().trim());
        } catch (Throwable malformed) {
            return 0L;
        }
    }

    private static Map<String, String> roomParams(String roomId) {
        if (KuGouSign.isBlank(roomId)) return null;
        Map<String, String> params = KuGouHttp.deviceParams();
        params.put("roomid", roomId.trim());
        return params;
    }

    private static JsonObject audioObject(String hash, String mixSongId) {
        JsonObject audio = new JsonObject();
        audio.addProperty("hash", KuGouSign.lower(KuGouSign.nonNull(hash)));
        audio.addProperty("mixsongid", KuGouSign.nonNull(mixSongId).trim());
        return audio;
    }

    /**
     * 参考实现的 {@code musicRoomAudios}：截到 50 条、每条 {@code {hash, mixsongid, fid}}、
     * 丢掉没有 hash 的。
     *
     * <p>{@code fid} 是云歌单的行号，房间队列不认，参考实现兜底成 0 —— 这里同样恒发 0，
     * 少一个字段和多发 0 在上游是两个分支，别省。</p>
     */
    private static JsonArray audioArray(List<KuGouMusicApi.Track> tracks) {
        JsonArray audios = new JsonArray();
        if (tracks == null) return audios;
        int dropped = 0;
        for (KuGouMusicApi.Track track : tracks) {
            if (track == null || KuGouSign.isBlank(track.hash)) continue;
            if (audios.size() >= MAX_AUDIOS_PER_REQUEST) {
                dropped++;
                continue;
            }
            JsonObject audio = new JsonObject();
            audio.addProperty("hash", KuGouSign.lower(track.hash));
            audio.addProperty("mixsongid", track.requestAlbumAudioId());
            audio.addProperty("fid", 0);
            audios.add(audio);
        }
        if (dropped > 0) {
            System.err.println("[Music/KuGou] 房间歌单单次上限 " + MAX_AUDIOS_PER_REQUEST
                    + " 首，丢掉了 " + dropped + " 首。");
        }
        return audios;
    }

    /** {@code progress_info}：{@code pause} 同样是 1=在播、2=暂停；{@code play_mode} 是字符串。 */
    private static JsonObject progressInfo(Progress progress) {
        JsonObject info = new JsonObject();
        info.addProperty("hash", KuGouSign.lower(progress.hash));
        info.addProperty("album_audio_id", progress.albumAudioId);
        info.addProperty("progress", Math.max(0L, progress.progressMillis / 1000L));
        info.addProperty("pause", progress.playing ? 1 : 2);
        info.addProperty("play_mode", String.valueOf(progress.playMode));
        return info;
    }

    private static JsonObject post(String baseUrl, String path, Map<String, String> params,
                                   JsonObject body) {
        return KuGouHttp.postJson(baseUrl, path, params, body.toString(), KuGouHttp.Sign.ANDROID, null);
    }

    /** 写操作统一收口：失败时留一行日志（{@code failureHint} 为 null 表示静默，给高频轮询用）。 */
    private static boolean ok(JsonObject root, String failureHint) {
        if (isOk(root)) return true;
        if (failureHint != null) {
            System.err.println("[Music/KuGou] " + failureHint + "失败: " + errorText(root));
        }
        return false;
    }

    /** 与 {@code KuGouMusicApi.isOk} 同一套判据（那边的是私有的，这里复制一份最小的）。 */
    private static boolean isOk(JsonObject root) {
        if (root == null) return false;
        JsonElement status = root.get("status");
        if (status != null && !status.isJsonNull() && integer(root, "status") != 1) return false;
        JsonElement errorCode = root.get("error_code");
        if (errorCode != null && !errorCode.isJsonNull() && integer(root, "error_code") != 0) return false;
        JsonElement errcode = root.get("errcode");
        if (errcode != null && !errcode.isJsonNull() && integer(root, "errcode") != 0) return false;
        return true;
    }

    /**
     * 上游给的失败原因。
     *
     * <p>{@code error_msg} 优先，它是唯一对人可读的一句（参考实现的前端也只用它）；
     * 都没有时退回 {@code error_code=NNN}，让日志里至少能查到那条码表。</p>
     */
    private static String errorText(JsonObject root) {
        if (root == null) return "无响应";
        String message = deepText(root, "error_msg", "error", "msg", "status_msg", "message");
        if (!message.isEmpty()) return message;
        int code = integer(root, "error_code", "errcode", "status");
        return code == 0 ? "无错误信息" : "error_code=" + code;
    }

    private static void collectRooms(JsonObject root, List<Room> out, String biz) {
        JsonArray items = listUnder(root, "list", "rooms", "room_list", "items", "info", "data");
        if (items == null) return;
        for (JsonElement element : items) {
            JsonObject item = asObject(element);
            if (item == null) continue;
            Room room = parseRoom(item);
            if (!room.valid()) continue;
            if (room.closed) continue;
            if (KuGouSign.isBlank(room.biz)) room.biz = biz;
            out.add(room);
        }
    }

    private static void collectMembers(JsonObject root, List<Member> out) {
        JsonArray items = listUnder(root, "list", "members", "member_list", "user_list",
                "audiences", "items", "info");
        if (items == null) return;
        for (JsonElement element : items) {
            Member member = parseMember(element);
            if (member != null) out.add(member);
        }
    }

    private static void collectTracks(JsonObject root, List<KuGouMusicApi.Track> out) {
        JsonArray items = listUnder(root, "list", "songs", "audios", "audio_list", "music_list",
                "items", "info");
        if (items == null) return;
        for (JsonElement element : items) {
            JsonObject item = asObject(element);
            if (item == null) continue;
            KuGouMusicApi.Track track = parseRoomTrack(item);
            if (track != null && !containsHash(out, track.hash)) out.add(track);
        }
    }

    private static boolean containsHash(List<KuGouMusicApi.Track> tracks, String hash) {
        String lower = KuGouSign.lower(hash);
        for (KuGouMusicApi.Track track : tracks) {
            if (lower.equals(KuGouSign.lower(track.hash))) return true;
        }
        return false;
    }

    /**
     * 房间成员条目。
     *
     * <p>昵称与头像既可能在条目本身、也可能嵌在 {@code user_info}/{@code member_info} 里，
     * 参考实现是把三层浅合并成一层再读的，这里做同一件事。</p>
     */
    private static Member parseMember(JsonElement value) {
        JsonObject source = asObject(value);
        if (source == null) return null;
        JsonObject merged = merge(source, "user_info", "member_info");
        long userId = longValue(merged, "userid", "user_id", "uid", "id");
        String nickname = firstText(merged, "nick_name", "nickname", "username", "user_name", "name");
        String avatar = firstText(merged, "user_pic", "avatar", "headimg", "head_img", "img", "pic");
        if (userId <= 0L && nickname.isEmpty()) return null;
        return new Member(userId, nickname, avatar, longValue(merged, "study_time"));
    }

    /**
     * 一条房间消息。
     *
     * <p>列表元素本身可能就是 JSON 字符串；正文又可能嵌在 {@code message}/{@code msg}/{@code content}
     * 里再套一层字符串。参考实现正是这两次 {@code parseJsonValue}，这里用 {@link #asObject} 覆盖同一形状。</p>
     * <p>系统事件（{@code type != 801}）的文案优先取上游给的 {@code alert}，取不到才按类型补一句
     * 中文——上游对部分事件已经给了人话，自己编一遍会和它不一致。表里没有的类型返回空串、
     * 这条消息随之被丢掉：宁可不显示，也不显示一句猜出来的文案。</p>
     */
    private static Message parseMessage(JsonElement value) {
        JsonObject outer = asObject(value);
        if (outer == null) return null;
        JsonObject inner = asObject(firstElement(outer, "message", "msg", "content"));
        JsonObject source = inner == null ? outer : inner;
        int type = integer(source, "msgtype", "msg_type", "type");
        if (type == 0) type = integer(outer, "msgtype");
        long userId = longValue(source, "userid", "uid");
        if (userId == 0L) userId = longValue(outer, "uid", "userid");
        String nickname = firstText(source, "nickname", "nick_name", "username", "user_name", "name");
        String text = type == MSG_TYPE_TEXT
                ? firstText(source, "alert", "message", "content", "text", "msg")
                : firstText(source, "alert", "content", "text", "title", "prompt");
        if (text.isEmpty()) text = systemText(source, type, nickname);
        if (text.isEmpty()) return null;
        long rawTime = longValue(outer, "addtime", "timestamp", "time");
        long sentAt = rawTime > 0L && rawTime < 10_000_000_000L ? rawTime * 1000L : rawTime;
        String id = firstText(outer, "msgid", "id");
        if (id.isEmpty()) id = userId + ":" + sentAt + ":" + text;
        return new Message(id, userId, nickname, firstText(source, "img", "avatar", "user_pic", "pic"),
                type, text, sentAt, type != MSG_TYPE_TEXT);
    }

    /**
     * 系统事件的兜底文案。
     *
     * <p>这张表来自参考实现前端里的同一份映射，只留与「一起听（众乐房）」相关的那些；
     * 学习模式专用的 2001~2010 也留着，因为自习室仍然可列可看。</p>
     */
    private static String systemText(JsonObject source, int type, String nickname) {
        String who = nickname.isEmpty() ? "房间成员" : nickname;
        String songName = firstText(source, "songname", "song_name");
        switch (type) {
            case 810:
                return integer(source, "action", "event") == 2 ? who + " 离开了房间" : who + " 进入了房间";
            case 820:
            case 3002:
                return "房主已结束一起听";
            case 821:
                return "房间信息已更新";
            case 830:
                return who + " 更新了房间成员设置";
            case 3001:
                return "房主开始了一起听";
            case 3003:
            case 2009:
                return "房间歌单已更新";
            case 3004:
                return songName.isEmpty() ? "房主切换了歌曲" : "已切换至《" + songName + "》";
            case 3005:
                return "房间播放状态已更新";
            case 4001:
                return who + " 已加入一起听";
            case 4002:
            case 2005:
                return who + " 离开了房间";
            case 4004:
                return songName.isEmpty() ? who + " 收藏了歌曲" : who + " 收藏了《" + songName + "》";
            case 4005:
                return who + " 关注了房主";
            case 5100:
                return songName.isEmpty() ? who + " 发起了点歌请求" : who + " 点播了《" + songName + "》";
            case 2004:
                return who + " 进入了房间";
            default:
                return "";
        }
    }

    /**
     * 一个房间条目 / 快照。
     *
     * <p>字段名在各端点之间不统一（{@code room_id}/{@code roomid}/{@code groupid}/{@code id}，
     * {@code room_name}/{@code room_theme}/{@code name}），所以每一组都按参考实现的候选顺序读，
     * 顺序本身有意义：越靠前的是这条端点真正在用的那个名字。</p>
     */
    private static Room parseRoom(JsonObject source) {
        Room room = new Room();
        if (source == null) return room;
        // extra_room_info / room_info / base_info 是三层可选的浅包装，展平进同一层再读。
        JsonObject record = merge(source, "extra_room_info", "room_info", "base_info");
        room.id = firstText(record, "room_id", "roomid", "groupid", "id");
        room.name = firstText(record, "room_name", "room_theme", "name", "introduction");
        JsonObject owner = merge(source, "user_info", "owner_info");
        room.ownerId = firstText(owner, "userid", "owner_id", "uid");
        if (room.ownerId.isEmpty()) room.ownerId = firstText(record, "userid", "owner_id", "uid");
        room.ownerName = firstText(owner, "nick_name", "nickname", "owner_name");
        if (room.ownerName.isEmpty()) room.ownerName = firstText(record, "nick_name", "owner_name");
        room.ownerAvatar = firstText(owner, "user_pic", "avatar", "img");
        room.notice = firstText(record, "room_notice", "notice");
        room.collectionId = firstText(record, "global_collection_id", "channel_id");
        room.listVersion = firstText(record, "list_version");
        room.memberCount = integer(record, "member_count", "online_count", "online_user_count",
                "all_user_count", "member_num");
        room.memberLimit = integer(record, "member_limit", "capacity");
        // closed 只在字段真的存在时才下结论：两个键都缺时 integer 会给 0，
        // 于是「没表态」会被读成「房间已结束」，发现页就会整页空白。
        if (record.has("is_hide") || record.has("room_status")) {
            room.closed = record.has("is_hide")
                    ? integer(record, "is_hide") == 1
                    : integer(record, "room_status") == 0;
        }
        room.closeReason = firstText(record, "show_msg", "close_reason");
        // 聊天开关同理：没有任何表态时保持默认的「可聊天」。
        int chatSwitch = integer(merge(record, "switch"), "chat");
        if (chatSwitch == 1 || chatSwitch == 2) {
            room.allowChat = chatSwitch == 1;
        } else if (record.has("allow_chat") || record.has("chat")) {
            room.allowChat = integer(record, "allow_chat", "chat") != 0;
        }

        JsonObject song = merge(source, "current_audio", "audio", "song_info");
        room.currentHash = KuGouSign.lower(firstText(song, "hash", "file_hash", "audio_hash"));
        if (room.currentHash.isEmpty()) {
            room.currentHash = KuGouSign.lower(firstText(record, "cur_song", "hash"));
        }
        room.currentMixSongId = firstText(song, "mixsongid", "mixSongId", "mix_song_id",
                "album_audio_id", "audio_id");
        room.currentSongName = firstText(song, "song_name", "songname", "audio_name");
        if (room.currentSongName.isEmpty()) {
            room.currentSongName = firstText(record, "songname", "audio_name");
        }
        room.currentArtistName = firstText(song, "author_name", "singername", "singer_name");
        if (room.currentArtistName.isEmpty()) {
            room.currentArtistName = firstText(record, "author_name", "singername");
        }
        room.currentProgressMillis = normalizedProgressMillis(song, record);
        // pause：1=在播、2=暂停；play_status 那一族才是文字。两种都读，谁在就用谁。
        int pause = integer(song, "pause");
        if (pause != 0) {
            room.currentPlaying = pause == 1;
        } else {
            String state = firstText(record, "play_status", "playstate", "state", "status");
            room.currentPlaying = !("pause".equalsIgnoreCase(state) || "paused".equalsIgnoreCase(state)
                    || "0".equals(state));
        }
        return room;
    }

    /**
     * 进度归一化到毫秒。
     *
     * <p>参考实现在解析侧对「大于 10000 的值」按毫秒处理、其余按秒——因为不同端点两种都给。
     * 这个阈值是它的实测结论，照搬。</p>
     */
    private static long normalizedProgressMillis(JsonObject song, JsonObject record) {
        long raw = longValue(song, "progress", "play_progress", "current_time", "position", "play_pos");
        if (raw == 0L) {
            raw = longValue(record, "progress", "play_progress", "current_time", "position", "play_pos");
        }
        if (raw <= 0L) return 0L;
        return raw > 10_000L ? raw : raw * 1000L;
    }

    /**
     * 房间曲目。
     *
     * <p>这是 {@code KuGouMusicApi.parseFlatTrack} 的房间版：字段基本同源，差别在两处——</p>
     * <ul>
     *   <li>曲目可能整体嵌在 {@code song_info}/{@code audio_info}/{@code audio}/{@code base} 里，
     *       且每一层都可能是 JSON 字符串；</li>
     *   <li>{@code mixsongid} 的候选名字更多（房间侧一会儿叫 {@code mix_song_id}、
     *       一会儿叫 {@code albumAudioId}）。</li>
     * </ul>
     *
     * <p><b>待实测</b>：房间队列里的 {@code hash} 可能是 {@code genting_hash}（OGG 专用游标）而不是
     * 标准版权 hash，参考实现的前端要靠一次元数据接口把两者对齐。这里直接取 {@code hash}
     * 交给 {@code KuGouMusicApi.songUrl}，若某些房间取不到流就是这条差异，需要加一次反查。</p>
     */
    private static KuGouMusicApi.Track parseRoomTrack(JsonObject item) {
        if (item == null) return null;
        JsonObject record = merge(item, "song_info", "audio_info", "audio", "base",
                "album_info", "trans_param");
        KuGouMusicApi.Track track = new KuGouMusicApi.Track();
        track.hash = KuGouSign.lower(firstText(record, "hash", "file_hash", "audio_hash", "hash_128"));
        if (KuGouSign.isBlank(track.hash)) return null;
        track.hash320 = KuGouSign.lower(firstText(record, "hash_320", "320hash"));
        track.hashLossless = KuGouSign.lower(firstText(record, "hash_flac", "hash_super", "sqhash"));
        track.name = firstText(record, "songname", "song_name", "audio_name", "ori_audio_name", "name");
        track.artist = firstText(record, "author_name", "singername", "singer_name");
        if (KuGouSign.isBlank(track.artist)) track.artist = singerNames(record);
        track.album = firstText(record, "album_name", "album");
        track.albumId = firstText(record, "album_id", "albumid");
        track.mixSongId = firstText(record, "mixsongid", "mixSongId", "mix_song_id",
                "album_audio_id", "albumAudioId", "audio_id");
        track.albumAudioId = firstText(record, "album_audio_id", "albumAudioId", "mixsongid");
        track.audioId = firstText(record, "audio_id", "mixsongid");
        track.durationMillis = normalizedDuration(record);
        track.coverTemplate = firstText(record, "sizable_cover", "cover", "cover_url", "album_sizable_cover",
                "union_cover", "img");
        track.privilege = integer(record, "privilege", "media_pay_type");
        track.vip = track.privilege == 10;
        if (KuGouSign.isBlank(track.name)) {
            String fileName = firstText(record, "filename", "filename_128");
            int split = fileName.indexOf(" - ");
            if (split > 0) {
                track.artist = KuGouSign.isBlank(track.artist) ? fileName.substring(0, split).trim() : track.artist;
                track.name = fileName.substring(split + 3).trim();
            }
        }
        return KuGouSign.isBlank(track.name) && KuGouSign.isBlank(track.artist) ? null : track;
    }

    /** 房号：{@code data} 有时直接就是房号字符串（参考实现对这一形状留了后手）。 */
    private static String roomIdOf(JsonObject root) {
        if (root == null) return "";
        JsonElement data = root.get("data");
        if (data != null && data.isJsonPrimitive()) {
            String direct = data.getAsString().trim();
            if (!direct.isEmpty() && !"0".equals(direct)) return direct;
        }
        return deepText(root, "groupid", "group_id", "room_id", "roomid");
    }

    /**
     * 深度优先找同名字段（限深 6 层）。
     *
     * <p>这一族的嵌套深度在各端点完全不统一（{@code data.group_info.groupid} 与
     * {@code data.groupid} 都出现过），按路径硬取总有一条错。限深是因为 {@code join_time}
     * 之类的时间戳也在对象里，翻太深会把无关键名当成想要的。</p>
     */
    private static String deepText(JsonObject root, String... names) {
        if (root == null) return "";
        for (String name : names) {
            JsonElement found = deepFind(root, name, 0);
            if (found == null || !found.isJsonPrimitive()) continue;
            String value = found.getAsString();
            if (!KuGouSign.isBlank(value)) return value.trim();
        }
        return "";
    }

    private static boolean deepFlag(JsonObject root, String name) {
        JsonElement found = deepFind(root, name, 0);
        if (found == null || !found.isJsonPrimitive()) return false;
        try {
            if (found.getAsJsonPrimitive().isBoolean()) return found.getAsBoolean();
            return found.getAsLong() == 1L;
        } catch (Throwable notNumeric) {
            return "true".equalsIgnoreCase(found.getAsString());
        }
    }

    private static JsonArray deepArray(JsonObject root, String... names) {
        if (root == null) return null;
        for (String name : names) {
            JsonElement found = deepFind(root, name, 0);
            JsonArray array = asArray(found);
            if (array != null) return array;
        }
        return null;
    }

    private static JsonElement deepFind(JsonObject root, String name, int depth) {
        if (root == null || depth > 6) return null;
        JsonElement own = root.get(name);
        if (own != null && !own.isJsonNull()) return own;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            JsonElement value = entry.getValue();
            JsonObject object = asObject(value);
            if (object != null) {
                JsonElement nested = deepFind(object, name, depth + 1);
                if (nested != null) return nested;
                continue;
            }
            JsonArray array = asArray(value);
            if (array == null) continue;
            for (JsonElement item : array) {
                JsonObject nested = asObject(item);
                if (nested == null) continue;
                JsonElement found = deepFind(nested, name, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 「要么已经是对象、要么是一段 JSON 字符串」。
     *
     * <p>参考实现到处在用的 {@code parseJsonValue}，是这一族响应的形状特征，不是某个端点的手误。</p>
     */
    private static JsonObject asObject(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) return element.getAsJsonObject();
        if (!element.isJsonPrimitive()) return null;
        try {
            String text = element.getAsString().trim();
            if (text.length() < 2 || text.charAt(0) != '{') return null;
            JsonElement parsed = KuGouHttp.parseText(text);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable malformed) {
            return null;
        }
    }

    private static JsonArray asArray(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonArray()) return element.getAsJsonArray();
        if (!element.isJsonPrimitive()) return null;
        try {
            String text = element.getAsString().trim();
            if (text.length() < 2 || text.charAt(0) != '[') return null;
            JsonElement parsed = KuGouHttp.parseText(text);
            return parsed != null && parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        } catch (Throwable malformed) {
            return null;
        }
    }

    /** 浅合并几层可选包装：后来的不覆盖已有非空值，与参考实现的展开顺序一致。 */
    private static JsonObject merge(JsonObject source, String... nestedNames) {
        JsonObject merged = new JsonObject();
        if (source == null) return merged;
        for (String name : nestedNames) {
            JsonObject nested = asObject(source.get(name));
            if (nested == null) continue;
            for (Map.Entry<String, JsonElement> entry : nested.entrySet()) {
                if (!merged.has(entry.getKey())) merged.add(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            merged.add(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    /**
     * 取「data 里第一个像列表的字段」。
     *
     * <p>{@code data} 本身有时就是数组；有时在 {@code data.data} 里再套一层；
     * 有时字段名在 {@code list}/{@code info}/{@code songs} 之间换。参考实现的前端也是这一串候选，
     * 少一个就会在某条端点上稳定读到空。</p>
     */
    private static JsonArray listUnder(JsonObject root, String... names) {
        if (root == null) return null;
        JsonArray direct = asArray(root.get("data"));
        if (direct != null) return direct;
        JsonObject data = asObject(root.get("data"));
        JsonObject scope = data == null ? root : data;
        for (String name : names) {
            JsonArray array = asArray(scope.get(name));
            if (array != null) return array;
        }
        // 再往下退一层：{data:{list:{info:[]}}} 这类形状在 rmservice 里出现过。
        for (String name : names) {
            JsonObject nested = asObject(scope.get(name));
            if (nested == null) continue;
            for (String inner : names) {
                JsonArray array = asArray(nested.get(inner));
                if (array != null) return array;
            }
        }
        return null;
    }

    private static JsonElement firstElement(JsonObject parent, String... names) {
        if (parent == null) return null;
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element != null && !element.isJsonNull()) return element;
        }
        return null;
    }

    /** 房间列表里的歌手信息可能只在 {@code authors} 数组里（{@code {author_name}} 或 {@code {name}}）。 */
    private static String singerNames(JsonObject item) {
        JsonArray authors = asArray(item.get("authors"));
        if (authors == null) return "";
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : authors) {
            JsonObject author = asObject(element);
            String name = author == null
                    ? (element.isJsonPrimitive() ? element.getAsString() : "")
                    : firstText(author, "author_name", "name");
            if (KuGouSign.isBlank(name)) continue;
            if (builder.length() > 0) builder.append('、');
            builder.append(name.trim());
        }
        return builder.toString();
    }

    /** 时长：房间侧同样一会儿秒、一会儿毫秒，用与进度相同的阈值。 */
    private static long normalizedDuration(JsonObject item) {
        long raw = longValue(item, "timelength", "duration", "timelen", "time_length");
        if (raw <= 0L) return 0L;
        return raw > 10_000L ? raw : raw * 1000L;
    }

    private static int clampPageSize(int limit) {
        return Math.max(1, Math.min(100, limit <= 0 ? 20 : limit));
    }

    private static String firstUrl(JsonArray urls) {
        if (urls == null) return "";
        for (JsonElement element : urls) {
            if (element == null || !element.isJsonPrimitive()) continue;
            String url = element.getAsString();
            if (!KuGouSign.isBlank(url)) return url.trim();
        }
        return "";
    }

    /**
     * 「有 {@code data} 就进 {@code data}，没有就用响应本身」，最多剥五层。
     *
     * <p>这一族的包装深度不固定：{@code {data:{...}}}、{@code {data:"{...}"}} 与
     * {@code {status:1, ...}} 平铺都出现过。剥到底比按端点记路径省事，也不会因为某条改了包装而取空。
     * 限层是防御性的：万一某个字段自引用，不加限就会转不完。</p>
     */
    private static JsonObject unwrapData(JsonObject root) {
        JsonObject current = root;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            JsonElement data = current.get("data");
            JsonObject nested = asObject(data);
            if (nested == null) return current;
            current = nested;
        }
        return current;
    }

    private static String firstText(JsonObject parent, String... names) {
        if (parent == null) return "";
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            String value = element.getAsString();
            if (!KuGouSign.isBlank(value)) return value.trim();
        }
        return "";
    }

    private static int integer(JsonObject parent, String... names) {
        return (int) longValue(parent, names);
    }

    private static long longValue(JsonObject parent, String... names) {
        if (parent == null) return 0L;
        for (String name : names) {
            JsonElement element = parent.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            try {
                return element.getAsJsonPrimitive().isNumber()
                        ? element.getAsLong()
                        : Long.parseLong(element.getAsString().trim());
            } catch (Throwable ignored) {
                // 同名不是数字（例如 status 是个词）就继续找下一个候选。
            }
        }
        return 0L;
    }
}
