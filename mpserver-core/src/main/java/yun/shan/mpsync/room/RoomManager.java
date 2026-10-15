package yun.shan.mpsync.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import yun.shan.mpsync.ban.BanEntry;
import yun.shan.mpsync.ban.BanList;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.platform.MpPlatform;
import yun.shan.mpsync.presence.PresenceRegistry;
import yun.shan.mpsync.resolve.ServerResolver;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every {@link Room} on this server and routes the client protocol into them.
 *
 * <p>Rooms are deliberately in-memory: a listening session is tied to the players who
 * are online, and NetEase stream URLs expire within minutes, so persisting rooms across
 * restarts would restore a queue whose links are all dead.
 *
 * <p>All entry points assume the server's main thread; the platform adapter hops there
 * before calling in (see {@code MpPlatform#execute}).
 */
public final class RoomManager {

    /** Ambiguous glyphs (0/O, 1/I) removed — codes get read aloud in voice chat. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    /** 服务器频道固定的 id / 邀请码，写死才好在命令和 GUI 里引用。 */
    public static final String CHANNEL_ID = "channel";
    public static final String CHANNEL_CODE = "CHANNEL";
    /** 待处理邀请的存活时间：过期的邀请留在列表里只会让人点进一个早就散了的房间。 */
    private static final long INVITE_TTL_MS = 10 * 60 * 1000L;

    private final MpConfig cfg;
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<String, String> byCode = new HashMap<>();
    private final Map<UUID, String> playerRoom = new HashMap<>();

    /** 客户端自报的身份 / VIP / 在听。跟着玩家活，进出房间都不影响。 */
    private final PresenceRegistry presence = new PresenceRegistry();
    /** 一起听封禁名单（落盘）。 */
    private final BanList bans = new BanList();
    /** 服务端代解析（服主 Token）；没配 Token 时它什么都不做。 */
    private ServerResolver resolver;

    /**
     * 已知的服务器管理员。
     *
     * <p><b>为什么是「已知」而不是「实时查」。</b>core 一行 Minecraft 代码都不碰，拿不到任何
     * 平台的权限系统；而每个加载器的权限 API 在 1.20.1 → 26.2 之间改了三次（整数等级 →
     * PermissionPredicate → PermissionSet），把它抽进 {@code MpPlatform} 就得在 7 个适配文件里
     * 各写一份，每一份都是一次跨版本编译风险。
     *
     * <p>取而代之：{@code /mp} 命令层<b>本来就</b>把真实的 operator 标志传进来（每个加载器都在
     * 它自己的命令注册处算好了），所以这里在每次 {@code /mp} 调用时记一笔。频道歌单管理这类
     * GUI 动作再回来查这个集合。含义因此是准确的 —— 集合里的人一定是真的以管理员身份跑过命令。
     * 代价是管理员进服后需要先敲一次任意 {@code /mp} 命令（比如 {@code /mp info}），GUI 上的
     * 管理按钮才会出现；下线即清除。
     */
    private final Set<UUID> operators = new HashSet<>();

    /** 待处理邀请：{@code 被邀请人 -> (roomId -> 邀请)}。 */
    private final Map<UUID, Map<String, PendingInvite>> invites = new HashMap<>();

    public RoomManager(MpConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * 开服时的一次性装配：封禁名单落盘位置 + 代解析线程池。
     *
     * <p>和构造器分开，是为了让纯 JVM 单元测试可以只 {@code new RoomManager(cfg)} 而不触发任何
     * 文件读写和线程创建。
     */
    public void bootstrap(Path banFile, MpPlatform platform) {
        bans.load(banFile);
        resolver = new ServerResolver(cfg);
        resolver.bind(this::onServerResolved,
                platform == null ? Runnable::run : platform::execute);
    }

    public MpConfig config() { return cfg; }

    public PresenceRegistry presence() { return presence; }

    public BanList bans() { return bans; }

    public ServerResolver resolver() { return resolver; }

    /** 服务端代解析回来的直链：喂给所有房间，只有 key 对得上的曲目会真的采纳。 */
    private void onServerResolved(String source, String songId, String url,
                                  long expiresAt, long servedMs) {
        for (Room room : new ArrayList<>(rooms.values())) {
            try {
                room.supplyUrl(source, songId, url, expiresAt, servedMs);
            } catch (Throwable t) {
                Net.warn("[mpsync] 代解析结果落地失败", t);
            }
        }
    }

    // ── 服务器管理员 ──────────────────────────────────────────────────────────

    /** 每次 {@code /mp} 命令调用时记一笔真实的 operator 标志。 */
    public void noteOperator(UUID uuid, boolean operator) {
        if (uuid == null) return;
        boolean changed = operator ? operators.add(uuid) : operators.remove(uuid);
        if (!changed) return;
        // 频道的控制权直接由它决定，状态变了要让客户端重画（管理按钮出现/消失）。
        Room room = roomOf(uuid);
        if (room != null && room.channel()) room.sendSnapshot(uuid);
    }

    public boolean isServerAdmin(UUID uuid) {
        return uuid != null && operators.contains(uuid);
    }

    // ── 封禁 ─────────────────────────────────────────────────────────────────

    /**
     * 被封禁就返回一句面向用户的原因，同时给客户端发一帧 {@link Proto#S_BANNED}
     * （让它把一起听整块置灰，而不是每次点击都弹一次错误）。
     */
    private String banReason(UUID uuid) {
        BanEntry e = bans.find(uuid, Net.nameOf(uuid));
        if (e == null) return null;
        JsonObject m = Proto.msg(Proto.S_BANNED);
        m.addProperty("reason", e.reason);
        m.addProperty("until", e.until);
        m.addProperty("remaining", e.remainingLabel());
        Net.send(uuid, m);
        return "你的一起听功能已被封禁（" + e.reason + "，剩余 " + e.remainingLabel() + "）";
    }

    /** 封禁生效时把人从房间里请出去 —— 否则封了他还在里面听。 */
    public void enforceBan(UUID uuid) {
        Room room = roomOf(uuid);
        if (room != null) kick(room, uuid);
        invites.remove(uuid);
    }

    // ── 服务器频道 ────────────────────────────────────────────────────────────

    /** 全服唯一的公开大房间。关闭时返回 null。 */
    public Room channel() {
        if (!cfg.channelEnabled) return null;
        Room existing = rooms.get(CHANNEL_ID);
        if (existing != null) return existing;
        Room ch = new Room(cfg, CHANNEL_ID, CHANNEL_CODE, cfg.channelName);
        wire(ch);
        rooms.put(CHANNEL_ID, ch);
        byCode.put(CHANNEL_CODE, CHANNEL_ID);
        return ch;
    }

    /** 加入频道。已经在别的房间里就先说清楚，而不是默默把他挪过去。 */
    public Room joinChannel(UUID uuid) {
        Room ch = channel();
        if (ch == null) throw new IllegalStateException("本服务器没有开启服务器频道");
        String banned = banReason(uuid);
        if (banned != null) throw new IllegalStateException(banned);
        Room existing = roomOf(uuid);
        if (existing != null && existing != ch) {
            throw new IllegalStateException("你已经在一个房间里了，请先离开");
        }
        if (ch.full() && !ch.has(uuid)) throw new IllegalStateException("频道已满");
        String name = Net.nameOf(uuid);
        boolean fresh = !ch.has(uuid);
        ch.join(uuid, name);
        playerRoom.put(uuid, ch.id);
        ch.broadcastState();
        if (fresh) ch.notice(name + " 加入了频道");
        else ch.sendSnapshot(uuid);
        return ch;
    }

    /** 每个新房间共同的装配：档案表、管理员判据、代解析钩子。 */
    private void wire(Room room) {
        room.setPresence(presence);
        room.setServerAdmin(this::isServerAdmin);
        ServerResolver r = resolver;
        if (r != null) room.setServerResolve(r::request);
    }

    // ── 公开房间 ──────────────────────────────────────────────────────────────

    /** 公开房间列表（含频道）。 */
    public JsonObject publicRoomsFrame() {
        JsonArray arr = new JsonArray();
        Room ch = cfg.channelEnabled ? rooms.get(CHANNEL_ID) : null;
        if (ch != null) arr.add(ch.publicJson());
        if (cfg.publicRoomsEnabled) {
            for (Room r : rooms.values()) {
                if (r.channel() || !r.open) continue;
                arr.add(r.publicJson());
            }
        }
        JsonObject m = Proto.msg(Proto.S_ROOMS);
        m.add("rooms", arr);
        m.addProperty("publicRoomsEnabled", cfg.publicRoomsEnabled);
        m.addProperty("channelEnabled", cfg.channelEnabled);
        m.addProperty("channelCode", CHANNEL_CODE);
        return m;
    }

    /** 把公开房间列表推给所有「不在房间里」的在线客户端。房间可见性变了要立刻反映。 */
    private void broadcastPublicRooms() {
        MpPlatform p = Net.platform();
        if (p == null) return;
        JsonObject frame = publicRoomsFrame();
        for (UUID uuid : p.onlinePlayers()) {
            if (roomOf(uuid) == null) Net.send(uuid, frame);
        }
    }

    // ── 待处理邀请 ────────────────────────────────────────────────────────────

    public void addInvite(UUID target, Room room, UUID from) {
        if (target == null || room == null) return;
        invites.computeIfAbsent(target, k -> new LinkedHashMap<>())
                .put(room.id, new PendingInvite(room.id, room.code, room.name, from, Net.nameOf(from)));
        Net.send(target, invitesFrame(target));
    }

    public JsonObject invitesFrame(UUID uuid) {
        Map<String, PendingInvite> mine = invites.get(uuid);
        JsonArray arr = new JsonArray();
        if (mine != null) {
            mine.entrySet().removeIf(e -> e.getValue().expired(INVITE_TTL_MS)
                    || !rooms.containsKey(e.getKey()));
            for (PendingInvite inv : mine.values()) arr.add(inv.toJson());
        }
        JsonObject m = Proto.msg(Proto.S_INVITES);
        m.add("invites", arr);
        return m;
    }

    private void acceptInvite(UUID uuid, String roomId) {
        Map<String, PendingInvite> mine = invites.get(uuid);
        PendingInvite inv = mine == null ? null : mine.get(roomId);
        if (inv == null) {
            // 没带 roomId（老客户端/命令行）时接受最新一条。
            inv = mine == null || mine.isEmpty() ? null
                    : new ArrayList<>(mine.values()).get(mine.size() - 1);
        }
        if (inv == null) { Net.error(uuid, "没有待处理的邀请"); return; }
        if (mine != null) mine.remove(inv.roomId);
        join(uuid, inv.roomId);
        Net.send(uuid, invitesFrame(uuid));
    }

    private void declineInvite(UUID uuid, String roomId) {
        Map<String, PendingInvite> mine = invites.get(uuid);
        if (mine != null) {
            if (roomId == null || roomId.isBlank()) mine.clear();
            else mine.remove(roomId);
        }
        Net.send(uuid, invitesFrame(uuid));
    }

    /** 这个玩家在哪个房间（给全服在线态用）。 */
    private String roomLabelOf(UUID uuid) {
        Room room = roomOf(uuid);
        return room == null ? "" : room.name;
    }

    public Collection<Room> rooms() { return rooms.values(); }

    public Room roomOf(UUID uuid) {
        String id = playerRoom.get(uuid);
        return id == null ? null : rooms.get(id);
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    /** @throws IllegalStateException with a user-facing reason */
    public Room create(UUID uuid, String name) {
        String banned = banReason(uuid);
        if (banned != null) throw new IllegalStateException(banned);
        if (roomOf(uuid) != null) throw new IllegalStateException("你已经在一个房间里了");
        if (cfg.maxRooms > 0 && rooms.size() >= cfg.maxRooms) {
            throw new IllegalStateException("服务器房间数量已达上限");
        }
        String id = newRoomId();
        String code = newCode();
        Room room = new Room(cfg, id, code, uuid, Net.nameOf(uuid), name);
        wire(room);
        rooms.put(id, room);
        byCode.put(code, id);
        playerRoom.put(uuid, id);
        room.sendSnapshot(uuid);
        Net.notice(uuid, "房间已创建，邀请码：" + code);
        return room;
    }

    /** @return the joined room; throws with a user-facing reason otherwise */
    public Room join(UUID uuid, String codeOrId) {
        String banned = banReason(uuid);
        if (banned != null) throw new IllegalStateException(banned);
        String name = Net.nameOf(uuid);
        Room existing = roomOf(uuid);
        if (existing != null) {
            if (existing.code.equalsIgnoreCase(codeOrId) || existing.id.equalsIgnoreCase(codeOrId)) {
                existing.join(uuid, name);
                existing.sendSnapshot(uuid);
                return existing;
            }
            throw new IllegalStateException("你已经在一个房间里了，请先离开");
        }
        Room room = resolve(codeOrId);
        if (room == null) {
            // Joining by invite alone is allowed (the client remembers the room id).
            room = rooms.values().stream().filter(r -> r.invited(uuid)).findFirst().orElse(null);
        }
        if (room == null) throw new IllegalStateException("邀请码无效或房间不存在");
        if (room.channel()) return joinChannel(uuid);
        if (room.full() && !room.has(uuid)) throw new IllegalStateException("房间已满");
        room.join(uuid, name);
        playerRoom.put(uuid, room.id);
        // 邀请已经用掉了，从待处理列表里划掉，否则 GUI 里那张卡还挂着。
        Map<String, PendingInvite> mine = invites.get(uuid);
        if (mine != null) {
            mine.remove(room.id);
            Net.send(uuid, invitesFrame(uuid));
        }
        room.broadcastState();
        room.notice(name + " 加入了房间");
        // 公开房间的人数/在放什么变了，列表里的人应该看到。
        if (room.open) broadcastPublicRooms();
        return room;
    }

    public Room resolve(String codeOrId) {
        if (codeOrId == null || codeOrId.isBlank()) return null;
        String key = codeOrId.trim();
        String id = byCode.get(key.toUpperCase(Locale.ROOT));
        Room room = id != null ? rooms.get(id) : null;
        return room != null ? room : rooms.get(key);
    }

    public void leave(UUID uuid) {
        Room room = roomOf(uuid);
        if (room == null) return;
        String name = Net.nameOf(uuid);
        boolean wasOwner = room.isOwner(uuid);
        playerRoom.remove(uuid);
        room.remove(uuid);
        Net.send(uuid, left(room.channel() ? "你已离开服务器频道" : "你已离开一起听房间"));
        // 频道是服务器设施：最后一个人走了也不能销毁，否则管理员维护的歌单跟着一起消失。
        if (room.members().isEmpty() && !room.channel()) {
            drop(room);
        } else {
            room.broadcastState();
            room.notice(name + (wasOwner ? " (房主) 离开了房间" : " 离开了房间"));
        }
        if (room.open) broadcastPublicRooms();
    }

    /** Owner-only teardown: everyone is dropped and the room id/code are released. */
    public void disband(Room room, String reason) {
        for (UUID uuid : new ArrayList<>(room.members().stream().map(m -> m.uuid).toList())) {
            playerRoom.remove(uuid);
            room.send(uuid, left(reason));
        }
        drop(room);
    }

    private void drop(Room room) {
        rooms.remove(room.id);
        byCode.remove(room.code);
        for (Member m : new ArrayList<>(room.members())) playerRoom.remove(m.uuid);
    }

    private static JsonObject left(String reason) {
        JsonObject m = Proto.msg(Proto.S_LEFT);
        m.addProperty("reason", reason == null ? "" : reason);
        return m;
    }

    public void onPlayerJoin(UUID uuid) {
        Room room = roomOf(uuid);
        if (room == null) return;
        // 断线期间可能被「按玩家名」封禁 —— 那一刻人不在线，UUID 查不到，enforceBan 没能触发。
        // 重连续座之前补一次判定：命中就请出房间，而不是把被封的人原样接回来继续听。
        if (banReason(uuid) != null) {
            enforceBan(uuid);
            return;
        }
        room.join(uuid, Net.nameOf(uuid));   // re-attach the seat kept across the disconnect
        room.sendSnapshot(uuid);
        room.broadcastState();
    }

    public void onPlayerQuit(UUID uuid) {
        Net.forget(uuid);
        // 档案和管理员标记都跟着连接走：下线的人既不在听歌，也不能替谁解析。
        presence.forget(uuid);
        operators.remove(uuid);
        Room room = roomOf(uuid);
        if (room == null) return;
        room.markOffline(uuid);
        room.broadcastState();
    }

    private int purgeCounter;

    public void tick() {
        // 每 5 分钟清一次过期封禁（20 tps × 300 s）。这件事跟房间无关，所以放在最前面，
        // 一个房间都没有的服务器也要清。
        if (++purgeCounter >= 6000) {
            purgeCounter = 0;
            bans.purge();
        }
        if (rooms.isEmpty()) return;
        for (Room room : new ArrayList<>(rooms.values())) {
            try {
                room.tick();
                // 投票通过要踢的人：Room 只登记，真正的移除必须走这里 —— playerRoom 索引在
                // 这一层，从 Room 里单方面删人会留下一条指向空房间的索引。
                UUID toKick = room.takePendingKick();
                if (toKick != null) kick(room, toKick);
            } catch (Throwable t) {
                // One bad room must not take the server tick down with it.
                Net.warn("[mpsync] room " + room.id + " tick failed", t);
            }
            if (room.dead()) disband(room, "房间已因长时间无人而关闭");
        }
    }

    public void shutdown() {
        rooms.clear();
        byCode.clear();
        playerRoom.clear();
        invites.clear();
        operators.clear();
        presence.clear();
        ServerResolver r = resolver;
        if (r != null) r.shutdown();
    }

    // ── protocol dispatch ────────────────────────────────────────────────────

    /** Handle one decoded C2S frame. Runs on the server thread. */
    public void handle(UUID uuid, JsonObject msg) {
        String op = Proto.str(msg, Proto.OP, "");
        if (op.isEmpty()) return;
        try {
            switch (op) {
                case Proto.C_HELLO -> {
                    JsonObject hello = Proto.msg(Proto.S_HELLO);
                    hello.addProperty("proto", Proto.VERSION);
                    hello.addProperty("server", "mpsync");
                    hello.addProperty("membersCanEnqueue", cfg.membersCanEnqueue);
                    hello.addProperty("maxQueuePerMember", cfg.maxQueuePerMember);
                    // 时钟纠偏阈值由服务端统一下发。这是让「同一房间里不同客户端口径一致」的关键：
                    // modernMP / MuoniumPlayer、1.20.1 到 26.2，全都用这一套数，
                    // 不会出现同一发心跳有人跳有人不跳、互相拉扯的局面。
                    // 老客户端读不到这几个字段，就用它自带的默认值（与这里的默认值相同）。
                    hello.addProperty("clockGraceMs", cfg.clockGraceMs);
                    hello.addProperty("softDriftMs", cfg.softDriftMs);
                    hello.addProperty("hardDriftMs", cfg.hardDriftMs);
                    hello.addProperty("resyncCooldownMs", cfg.resyncCooldownMs);
                    hello.addProperty("driftConfirmHits", cfg.driftConfirmHits);
                    // 心跳间隔也一并告知：客户端据此知道「多久没收到心跳算掉线」，
                    // 也能推出 driftConfirmHits 次确认大约要花多少时间。
                    hello.addProperty("timeBroadcastTicks", cfg.timeBroadcastTicks);
                    // 房间内无缝交接的提前量；0 = 关闭（默认）。客户端据此决定房间内要不要
                    // 提前下载下一首、换歌时交叠多长。老服务端不发这个字段，客户端按 0 处理，
                    // 也就退化成现在的硬切。
                    hello.addProperty("automixLeadMs", cfg.automixLeadMs);
                    // 分析者指派的策略与超时。客户端的用处只有一个：把「谁在算」显示出来，
                    // 以及知道自己该等多久。老客户端读不到，行为不变。
                    hello.addProperty("automixAssignTimeoutMs", cfg.automixAssignTimeoutMs);
                    hello.addProperty("automixAnnounceAnalyst", cfg.automixAnnounceAnalyst);
                    // ── v2：客户端要据此决定显示哪些页、哪些按钮 ──
                    hello.addProperty("channelEnabled", cfg.channelEnabled);
                    hello.addProperty("channelName", cfg.channelName);
                    hello.addProperty("channelCode", CHANNEL_CODE);
                    hello.addProperty("channelMaxPerMember", cfg.channelMaxPerMember);
                    hello.addProperty("publicRoomsEnabled", cfg.publicRoomsEnabled);
                    // 「播放歌单」这一档能力。客户端靠 playlistBatchLimit>0 判断这台服务端认不认识
                    // enqueue_batch —— 老服务端不发这个字段，客户端取到 0，那颗按钮就不出现，
                    // 不会出现「点了之后服务端一句 error 把整张单退回聊天栏」的界面。
                    // playlistAdminOnly 只影响按钮的 enabled 条件，不影响它显不显示。
                    hello.addProperty("playlistBatchLimit", cfg.playlistBatchLimit);
                    hello.addProperty("playlistAdminOnly", cfg.playlistAdminOnly);
                    hello.addProperty("voteWindowMs", cfg.voteWindowMs);
                    hello.addProperty("voteSkipRatio", cfg.voteSkipRatio);
                    hello.addProperty("voteKickRatio", cfg.voteKickRatio);
                    hello.addProperty("voteKickCooldownMs", cfg.voteKickCooldownMs);
                    // 服主配了 Token：没有 VIP 的玩家也能点会员曲，客户端应该把这件事说出来。
                    hello.addProperty("serverResolve", resolver != null && resolver.anyEnabled());
                    hello.addProperty("serverAdmin", isServerAdmin(uuid));
                    // hello 本身就带着客户端身份（client/modVersion/vip/contributeVip），先吸收再回应，
                    // 这样紧接着发出去的房间快照里已经有 Mod 与 VIP 信息。
                    presence.update(uuid, msg);
                    Net.send(uuid, hello);
                    // 被封禁的人也要收到 hello（否则客户端以为服务端没装），但要立刻告诉他为什么用不了。
                    banReason(uuid);
                    Room room = roomOf(uuid);
                    if (room != null) room.sendSnapshot(uuid);
                    // 一进来就把「待处理邀请」和「公开房间」补齐，GUI 不用再等一轮请求。
                    Net.send(uuid, invitesFrame(uuid));
                    Net.send(uuid, publicRoomsFrame());
                }
                case Proto.C_CREATE -> create(uuid, Proto.str(msg, "name", ""));
                case Proto.C_JOIN   -> join(uuid, Proto.str(msg, "code", Proto.str(msg, "room", "")));
                case Proto.C_LEAVE  -> leave(uuid);
                case Proto.C_SYNC   -> {
                    Room room = roomOf(uuid);
                    if (room != null) room.sendSnapshot(uuid);
                    else Net.send(uuid, left(""));
                    // 同步顺带把这两份也刷新：客户端打开面板时只发一个 sync 就够。
                    Net.send(uuid, invitesFrame(uuid));
                    Net.send(uuid, publicRoomsFrame());
                }
                // ── v2 顶层 op：这些在房间外也必须能用 ──
                case Proto.C_PROFILE -> {
                    presence.update(uuid, msg);
                    Room room = roomOf(uuid);
                    // 成员列表里的 Mod / VIP / 在听要跟着变，所以整房重画一次。
                    // 顺带让房间重挑一次分析者：客户端的能力位（能不能跑交接计划的分析）
                    // 很可能就是在这第一次 profile 里才到齐的，而换歌时的指派发生在它之前 ——
                    // 不补这一下，房间里明明有能算的机器也会被判成「没人能算」。
                    if (room != null) {
                        room.refreshAnalyst();
                        room.broadcastState();
                    }
                }
                case Proto.C_PROGRESS -> {
                    // 心跳在房间外也会来 —— 全服在线态（谁正在听什么）就靠它。所以它不走
                    // handleInRoom，否则房间外的心跳会换回一句「你还没有加入一起听房间」。
                    presence.update(uuid, msg);
                    Room room = roomOf(uuid);
                    if (room != null) {
                        room.progress(uuid, Proto.num(msg, "pos", 0L),
                                Proto.bool(msg, "playing", false));
                    }
                }
                case Proto.C_PRESENCE -> Net.send(uuid,
                        presence.snapshot(Net.platform(), this::roomLabelOf));
                case Proto.C_ROOMS    -> Net.send(uuid, publicRoomsFrame());
                case Proto.C_INVITES  -> Net.send(uuid, invitesFrame(uuid));
                case Proto.C_INVITE_ACCEPT  -> acceptInvite(uuid, Proto.str(msg, "roomId", ""));
                case Proto.C_INVITE_DECLINE -> declineInvite(uuid, Proto.str(msg, "roomId", ""));
                case Proto.C_CHANNEL_JOIN   -> joinChannel(uuid);
                default -> handleInRoom(uuid, op, msg);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            Net.error(uuid, e.getMessage());
        } catch (Throwable t) {
            Net.warn("[mpsync] op " + op + " from " + Net.nameOf(uuid) + " failed", t);
            Net.error(uuid, "操作失败");
        }
    }

    private void handleInRoom(UUID uuid, String op, JsonObject msg) {
        Room room = roomOf(uuid);
        if (room == null) {
            Net.error(uuid, "你还没有加入一起听房间");
            return;
        }
        boolean control = room.controls(uuid);

        switch (op) {
            case Proto.C_ENQUEUE -> {
                String banned = banReason(uuid);
                if (banned != null) { Net.error(uuid, banned); return; }
                // 频道天生开放点歌（那是它存在的意义），membersCanEnqueue 只约束普通房间。
                if (!control && !room.channel() && !cfg.membersCanEnqueue) {
                    Net.error(uuid, "本房间未开放点歌");
                    return;
                }
                Track track = Track.fromJson(Proto.obj(msg, "track"), cfg);
                String who = Net.nameOf(uuid);
                QueueEntry entry = room.enqueue(uuid, who, track, Proto.bool(msg, "next", false));
                // 插队与点歌在队列里落的位置不同，全房看到的这句也得不同：只写「点歌」的话，
                // 别人只看到有人点了一首、自己的排队却被挤后，像是服务端排序坏了。
                room.notice(who + (entry.jumped() ? " 插了一首：" : " 点歌：") + entry.track.display());
            }
            case Proto.C_ENQUEUE_BATCH -> {
                String banned = banReason(uuid);
                if (banned != null) { Net.error(uuid, banned); return; }
                // 频道里不给灌单：全服共用的房间，一个人倒进 100 首就把所有人的排队挡在后面了，
                // 而那里本来就已经有「人均 3 首」这一档在管着逐首点歌。关掉它比放宽它更好解释。
                if (room.channel()) { Net.error(uuid, "服务器频道不支持播放整张歌单，请一首一首点"); return; }
                if (cfg.playlistBatchLimit <= 0) { Net.error(uuid, "服务器未开启播放歌单功能"); return; }
                if (cfg.playlistAdminOnly && !control) {
                    Net.error(uuid, "本房间只有房主与管理员能播放整张歌单");
                    return;
                }
                if (!control && !cfg.membersCanEnqueue) { Net.error(uuid, "本房间未开放点歌"); return; }
                String label = Proto.str(msg, "playlist", "");
                Room.BatchResult result = room.enqueueBatch(uuid, Net.nameOf(uuid), label,
                        Proto.arr(msg, "tracks"));
                room.notice(Net.nameOf(uuid) + " " + result.describe());
            }
            case Proto.C_PLAY_NOW -> {
                if (requireControl(uuid, control)) return;
                room.playNow(uuid, Net.nameOf(uuid), Track.fromJson(Proto.obj(msg, "track"), cfg));
            }
            case Proto.C_PLAY_INDEX -> {
                if (requireControl(uuid, control)) return;
                long entryId = Proto.num(msg, "entryId", -1L);
                boolean ok = entryId > 0
                        ? room.playEntry(entryId)
                        : playByIndex(room, Proto.i32(msg, "index", -1));
                if (!ok) Net.error(uuid, "这首歌已不在队列中");
            }
            case Proto.C_REMOVE -> {
                long entryId = Proto.num(msg, "entryId", -1L);
                QueueEntry entry = room.findEntry(entryId);
                if (entry == null) { Net.error(uuid, "歌曲不在队列中"); return; }
                boolean own = cfg.requesterCanRemoveOwn && uuid.equals(entry.requester);
                if (!control && !own) { Net.error(uuid, "只能删除自己点的歌"); return; }
                room.removeEntry(entryId);
            }
            case Proto.C_MOVE -> {
                if (requireControl(uuid, control)) return;
                room.moveEntry(Proto.num(msg, "entryId", -1L), Proto.i32(msg, "to", 0));
            }
            case Proto.C_CLEAR -> {
                if (requireControl(uuid, control)) return;
                room.clearQueue();
            }
            case Proto.C_CLEAR_PLAYLIST -> {
                // 撤的是「某张歌单还没播的那批」，不是整条队列，所以普通成员也能用 —— 前提是他撤的
                // 正是自己灌进来的那批，这一点由 Room#removePlaylist 按 requester 再校验一次。
                String name = Proto.str(msg, "playlist", "");
                int removed = room.removePlaylist(uuid, name);
                if (removed <= 0) Net.error(uuid, "队列里没有《" + (name.isEmpty() ? "这张" : name) + "》待播的歌了");
                else room.notice(Net.nameOf(uuid) + " 撤回了歌单《" + name + "》剩下的 " + removed + " 首");
            }
            case Proto.C_PAUSE -> {
                if (requireControl(uuid, control)) return;
                room.pause();
            }
            case Proto.C_RESUME -> {
                if (requireControl(uuid, control)) return;
                room.resume();
            }
            case Proto.C_SEEK -> {
                if (requireControl(uuid, control)) return;
                room.seek(Proto.num(msg, "pos", 0L));
            }
            case Proto.C_NEXT -> {
                // 频道里切歌是集体决定：普通成员按下「下一曲」就等于发起一次投票，
                // 服务器管理员仍然可以直接切（需要有人能收拾场面）。
                if (room.channel() && !control) {
                    String err = room.startVote(VoteSession.Kind.SKIP, uuid, null);
                    if (err != null) Net.error(uuid, err);
                    return;
                }
                if (requireControl(uuid, control)) return;
                room.next(false);
            }
            case Proto.C_PREV -> {
                if (requireControl(uuid, control)) return;
                room.prev();
            }
            case Proto.C_REPEAT -> {
                if (requireControl(uuid, control)) return;
                room.setRepeat(RepeatMode.of(Proto.str(msg, "mode", "off")));
            }
            case Proto.C_INVITE -> {
                UUID target = targetOf(msg);
                if (target == null) { Net.error(uuid, "找不到该玩家"); return; }
                if (room.has(target)) { Net.error(uuid, "对方已在房间里"); return; }
                room.invite(target);
                String from = Net.nameOf(uuid);
                // v1 的单帧邀请仍然发：老客户端只认它。
                JsonObject inv = Proto.msg(Proto.S_INVITE);
                inv.addProperty("roomId", room.id);
                inv.addProperty("code", room.code);
                inv.addProperty("roomName", room.name);
                inv.addProperty("fromName", from);
                Net.send(target, inv);
                // v2：进「待处理邀请」列表，由客户端 GUI 呈现。
                //
                // 刻意不再往聊天框写那一行 —— 需求明确要求邀请不在聊天框展示。装了播放器的人
                // 会在一起听界面看到一张可接受/拒绝的卡片；没装播放器的人本来也没法一起听，
                // 给他一行提示只是噪音。
                addInvite(target, room, uuid);
                Net.notice(uuid, "已邀请 " + Net.nameOf(target));
            }
            case Proto.C_SET_ROLE -> {
                UUID targetUuid = targetOf(msg);
                if (targetUuid == null) { Net.error(uuid, "找不到该玩家"); return; }
                String err = room.setRole(uuid, targetUuid, Role.of(Proto.str(msg, "role", "member")));
                if (err != null) Net.error(uuid, err);
            }
            case Proto.C_KICK -> {
                UUID voteTarget = targetOf(msg);
                // 频道里没有房主，踢人只能靠投票（>80%），失败还要进冷却防止乱踢。
                if (room.channel() && !control) {
                    if (voteTarget == null) { Net.error(uuid, "找不到该玩家"); return; }
                    String err = room.startVote(VoteSession.Kind.KICK, uuid, voteTarget);
                    if (err != null) Net.error(uuid, err);
                    return;
                }
                if (requireControl(uuid, control)) return;
                UUID targetUuid = targetOf(msg);
                if (targetUuid == null || !room.has(targetUuid)) { Net.error(uuid, "对方不在房间里"); return; }
                if (room.isOwner(targetUuid)) { Net.error(uuid, "不能移出房主"); return; }
                if (room.roleOf(targetUuid) == Role.ADMIN && !room.isOwner(uuid)) {
                    Net.error(uuid, "只有房主可以移出管理员");
                    return;
                }
                kick(room, targetUuid);
            }
            case Proto.C_TRANSFER -> {
                UUID targetUuid = targetOf(msg);
                if (targetUuid == null) { Net.error(uuid, "找不到该玩家"); return; }
                String err = room.transfer(uuid, targetUuid);
                if (err != null) Net.error(uuid, err);
            }
            case Proto.C_DISBAND -> {
                if (!room.isOwner(uuid)) { Net.error(uuid, "只有房主可以解散房间"); return; }
                disband(room, "房主已解散房间");
            }
            // ── v2 房间内 op ──
            case Proto.C_SET_PUBLIC -> {
                if (room.channel()) { Net.error(uuid, "频道始终是公开的"); return; }
                if (requireControl(uuid, control)) return;
                if (!cfg.publicRoomsEnabled) { Net.error(uuid, "服务器未开放公开房间"); return; }
                room.open = Proto.bool(msg, "public", !room.open);
                room.broadcastState();
                room.notice(room.open ? "房间已设为公开，任何人都可以加入" : "房间已设为私密");
                broadcastPublicRooms();
            }
            case Proto.C_SELF_PAUSE -> {
                String err = room.setSelfPause(uuid, Proto.bool(msg, "paused", true));
                if (err != null) Net.error(uuid, err);
            }
            case Proto.C_VOTE_START -> {
                VoteSession.Kind kindOf = "kick".equalsIgnoreCase(Proto.str(msg, "kind", "skip"))
                        ? VoteSession.Kind.KICK : VoteSession.Kind.SKIP;
                String err = room.startVote(kindOf, uuid,
                        kindOf == VoteSession.Kind.KICK ? targetOf(msg) : null);
                if (err != null) Net.error(uuid, err);
            }
            case Proto.C_VOTE_CAST -> {
                String err = room.castVote(Proto.num(msg, "voteId", 0L), uuid,
                        Proto.bool(msg, "agree", true));
                if (err != null) Net.error(uuid, err);
            }
            // 直链应答：必须连带平台一起回传，否则跨平台房间里 songId 相同的两首歌会串链接。
            // 还要带上<b>是谁答的</b>与 entryId：分层采纳要记「这条链接出自第几层」，
            // 而迟到答案能不能覆盖先到的，全看那个层号 —— 不传就只能退回先答先赢。
            case Proto.C_URL_SUPPLY -> room.supplyUrl(uuid,
                    Proto.num(msg, "entryId", 0L),
                    Proto.str(msg, "source", Proto.SOURCE_NETEASE),
                    Proto.str(msg, "songId", ""),
                    Proto.str(msg, "url", ""),
                    Proto.num(msg, "urlExpiresAt", 0L),
                    Proto.num(msg, "urlDuration", 0L),
                    Proto.bool(msg, "urlDurationEstimated", false));
            // 弃权：这一层答不上，当场说一声，服务端收齐就立刻推进下一层。
            case Proto.C_URL_DECLINE -> room.declineUrl(uuid,
                    Proto.num(msg, "entryId", 0L),
                    (int) Proto.num(msg, "tier", -1L),
                    Proto.str(msg, "source", Proto.SOURCE_NETEASE),
                    Proto.str(msg, "songId", ""),
                    Proto.str(msg, "reason", ""));
            // 跨平台救援的答卷：把那一格换成另一平台的同一首歌。
            case Proto.C_TRACK_REPLACE -> room.replaceTrack(uuid,
                    Proto.num(msg, "entryId", 0L),
                    Proto.obj(msg, "track"));
            // 被派去换源的那台说「我这台换不到」：当场改派下一台，不必等满一个救援窗。
            case Proto.C_SWITCH_DECLINE -> room.switchDecline(uuid,
                    Proto.num(msg, "entryId", 0L), Proto.str(msg, "reason", ""));
            // 交接计划。服务端只做校验与广播 —— 分析需要音频，只能在客户端做；
            // 但交接点必须全房一致，所以由一台算出、服务端存下并转发。
            // 「哪一台」不再是房主：服务端按「点歌人 → 房主 → 房间里任何能算的人」指派
            // （见 Room#assignAnalyzer），被指派者用本帧交卷。认不出的人由 reportPlan 静默丢弃。
            case Proto.C_AUTOMIX_PLAN -> room.reportPlan(uuid,
                    Proto.num(msg, "entryId", 0L), Proto.obj(msg, "plan"));
            // 被指派人放弃：当场改派下一台，不必干等到超时。
            case Proto.C_ANALYZE_DECLINE -> room.declinePlan(uuid,
                    Proto.num(msg, "entryId", 0L));
            default -> Net.error(uuid, "不支持的操作：" + op);
        }
    }

    private boolean playByIndex(Room room, int index) {
        List<QueueEntry> queue = room.queue();
        if (index < 0 || index >= queue.size()) return false;
        return room.playEntry(queue.get(index).entryId);
    }

    public void kick(Room room, UUID targetUuid) {
        playerRoom.remove(targetUuid);
        room.send(targetUuid, left("你已被移出一起听房间"));
        String name = room.member(targetUuid) != null ? room.member(targetUuid).name : "玩家";
        room.remove(targetUuid);
        if (room.members().isEmpty() && !room.channel()) drop(room);
        else {
            room.broadcastState();
            room.notice(name + " 已被移出房间");
        }
    }

    private static boolean requireControl(UUID uuid, boolean control) {
        if (control) return false;
        Net.error(uuid, "只有房主或管理员可以执行此操作");
        return true;
    }

    /** Resolve the {@code target} field: online name first, then a raw UUID. */
    private static UUID targetOf(JsonObject msg) {
        String target = Proto.str(msg, "target", "");
        if (target.isBlank()) return null;
        UUID byName = Net.uuidByName(target);
        return byName != null ? byName : uuidOf(target);
    }

    private static UUID uuidOf(String s) {
        try { return UUID.fromString(s); } catch (Throwable ignored) { return null; }
    }

    private String newRoomId() {
        String id;
        do {
            id = Long.toHexString(RNG.nextLong() & 0xFFFFFFFFL);
        } while (rooms.containsKey(id));
        return id;
    }

    private String newCode() {
        String code;
        do {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) sb.append(CODE_ALPHABET[RNG.nextInt(CODE_ALPHABET.length)]);
            code = sb.toString();
        } while (byCode.containsKey(code));
        return code;
    }
}
