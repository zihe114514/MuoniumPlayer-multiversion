package yun.shan.mpsync.command;

import com.google.gson.JsonObject;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.ban.BanEntry;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.presence.ClientProfile;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.room.IdlePlaylist;
import yun.shan.mpsync.room.Member;
import yun.shan.mpsync.room.QueueEntry;
import yun.shan.mpsync.room.Room;
import yun.shan.mpsync.room.RoomManager;
import yun.shan.mpsync.room.VoteSession;
import yun.shan.mpsync.util.Durations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /mp ...} implemented once, as plain string arguments.
 *
 * <p>Brigadier (Fabric) and Bukkit commands (Paper) differ in every detail except the
 * words the player types, so both adapters register a thin wrapper that forwards the raw
 * argument array here. Every mutating subcommand builds the same JSON frame the client
 * GUI would send and pushes it through {@link RoomManager#handle}, so permissions,
 * validation and broadcasts exist in exactly one place; only the read-only views
 * ({@code info}, {@code queue}, {@code list}) read room state directly.
 *
 * @return feedback lines for the caller; room-wide effects are announced by the engine
 */
public final class MpTextCommands {

    private MpTextCommands() {}

    /** Subcommands offered for tab completion to everyone. */
    public static final List<String> SUBCOMMANDS = List.of(
            "create", "join", "leave", "disband", "invite", "admin", "unadmin", "kick",
            "transfer", "pause", "resume", "skip", "prev", "clear", "seek", "repeat",
            "play", "info", "queue", "channel", "public", "agree", "deny", "vote",
            "who", "autojoin", "help");

    /** 只对服务器管理员补全的子命令（适配层用 {@link #suggest(String[], List, boolean)} 传入身份时生效）。 */
    public static final List<String> ADMIN_SUBCOMMANDS = List.of("status", "idle", "ban", "unban", "bans", "list");

    private static final List<String> IDLE_ACTIONS =
            List.of("save", "add", "on", "off", "shuffle", "list", "clear");

    /**
     * Completion candidates for {@code /mp <args...>}，按普通玩家补全。
     *
     * <p>保留这个签名：各加载器的适配层都在调它，而在补全时拿权限的 API 各版本变过好几次，
     * 能轻松拿到的平台（Paper）改调下面那个重载即可。</p>
     */
    public static List<String> suggest(String[] args, List<String> onlineNames) {
        return suggest(args, onlineNames, false);
    }

    /** @param operator 调用者是不是服务器管理员；是就把管理员子命令也补出来 */
    public static List<String> suggest(String[] args, List<String> onlineNames, boolean operator) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : SUBCOMMANDS) if (s.startsWith(prefix)) out.add(s);
            if (operator) for (String s : ADMIN_SUBCOMMANDS) if (s.startsWith(prefix)) out.add(s);
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            boolean onOff = (sub.equals("autojoin") && operator && args[1].equalsIgnoreCase("server"))
                    || (sub.equals("idle") && operator && args[1].equalsIgnoreCase("shuffle"));
            return onOff ? startingWith(List.of("on", "off"), prefix) : List.of();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("autojoin")) {
                return startingWith(operator ? List.of("on", "off", "server") : List.of("on", "off"), prefix);
            }
            if (sub.equals("idle")) return operator ? startingWith(IDLE_ACTIONS, prefix) : List.of();
            if (sub.equals("status")) return operator ? startingWith(List.of("list"), prefix) : List.of();
            switch (sub) {
                case "invite": case "admin": case "unadmin": case "kick": case "transfer": {
                    List<String> out = new ArrayList<>();
                    if (onlineNames != null) {
                        for (String n : onlineNames) {
                            if (n.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(n);
                        }
                    }
                    return out;
                }
                case "repeat": return List.of("off", "one", "all");
                case "public": return List.of("on", "off");
                case "vote": return List.of("skip", "kick");
                case "ban": case "unban": {
                    List<String> out = new ArrayList<>();
                    if (onlineNames != null) {
                        for (String n : onlineNames) {
                            if (n.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(n);
                        }
                    }
                    return out;
                }
                default: return List.of();
            }
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String s : options) if (s.startsWith(prefix)) out.add(s);
        return out;
    }

    public static List<String> run(UUID actor, boolean operator, String[] args) {
        RoomManager manager = MpParty.manager();
        if (manager == null) return List.of("一起听服务端未就绪");
        // 每次调用都记一笔真实的 operator 标志 —— 这是 core 唯一能拿到平台权限信息的地方，
        // 频道的管理权和 GUI 上的管理按钮都靠它。理由见 RoomManager.operators 的注释。
        manager.noteOperator(actor, operator);
        if (args.length == 0) return help();

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create": {
                JsonObject m = Proto.msg(Proto.C_CREATE);
                if (args.length > 1) m.addProperty("name", join(args, 1));
                return op(manager, actor, m);
            }
            case "join": {
                if (args.length < 2) return List.of("用法：/mp join <邀请码>");
                JsonObject m = Proto.msg(Proto.C_JOIN);
                m.addProperty("code", args[1]);
                return op(manager, actor, m);
            }
            case "leave":   return op(manager, actor, Proto.msg(Proto.C_LEAVE));
            case "disband": return op(manager, actor, Proto.msg(Proto.C_DISBAND));
            case "pause":   return op(manager, actor, Proto.msg(Proto.C_PAUSE));
            case "resume":  return op(manager, actor, Proto.msg(Proto.C_RESUME));
            case "skip":    return op(manager, actor, Proto.msg(Proto.C_NEXT));
            case "prev":    return op(manager, actor, Proto.msg(Proto.C_PREV));
            case "clear":   return op(manager, actor, Proto.msg(Proto.C_CLEAR));
            case "invite":  return player(manager, actor, args, Proto.C_INVITE, null);
            case "admin":   return player(manager, actor, args, Proto.C_SET_ROLE, "admin");
            case "unadmin": return player(manager, actor, args, Proto.C_SET_ROLE, "member");
            case "kick":    return player(manager, actor, args, Proto.C_KICK, null);
            case "transfer":return player(manager, actor, args, Proto.C_TRANSFER, null);
            case "seek": {
                if (args.length < 2) return List.of("用法：/mp seek <秒>");
                long seconds = parseLong(args[1], -1);
                if (seconds < 0) return List.of("秒数必须是不小于 0 的整数");
                JsonObject m = Proto.msg(Proto.C_SEEK);
                m.addProperty("pos", seconds * 1000L);
                return op(manager, actor, m);
            }
            case "repeat": {
                if (args.length < 2) return List.of("用法：/mp repeat <off|one|all>");
                JsonObject m = Proto.msg(Proto.C_REPEAT);
                m.addProperty("mode", args[1]);
                return op(manager, actor, m);
            }
            case "play": {
                if (args.length < 2) return List.of("用法：/mp play <歌单序号>");
                long index = parseLong(args[1], 0);
                if (index < 1) return List.of("序号从 1 开始");
                JsonObject m = Proto.msg(Proto.C_PLAY_INDEX);
                m.addProperty("index", index - 1);
                return op(manager, actor, m);
            }
            case "info":  return info(manager, actor);
            case "queue": return queue(manager, actor);
            case "who":   return who(manager);
            // ── v2 ──
            case "channel": return op(manager, actor, Proto.msg(Proto.C_CHANNEL_JOIN));
            case "public": {
                JsonObject m = Proto.msg(Proto.C_SET_PUBLIC);
                if (args.length > 1) {
                    String v = args[1].toLowerCase(Locale.ROOT);
                    m.addProperty("public", v.equals("on") || v.equals("true") || v.equals("是"));
                }
                return op(manager, actor, m);
            }
            case "agree": case "同意": return vote(manager, actor, true);
            case "deny":  case "反对": return vote(manager, actor, false);
            case "vote": {
                if (args.length < 2) return List.of("用法：/mp vote skip | /mp vote kick <玩家>");
                JsonObject m = Proto.msg(Proto.C_VOTE_START);
                boolean kick = args[1].equalsIgnoreCase("kick");
                m.addProperty("kind", kick ? "kick" : "skip");
                if (kick) {
                    if (args.length < 3) return List.of("用法：/mp vote kick <玩家>");
                    m.addProperty("target", args[2]);
                }
                return op(manager, actor, m);
            }
            case "ban":
                if (!operator) return List.of("只有服务器管理员可以封禁一起听功能");
                return ban(manager, actor, args);
            case "unban":
                if (!operator) return List.of("只有服务器管理员可以解封");
                if (args.length < 2) return List.of("用法：/mp unban <玩家>");
                return List.of(manager.bans().unban(args[1])
                        ? "已解封 " + args[1]
                        : args[1] + " 并没有被封禁");
            case "bans":
                if (!operator) return List.of("只有服务器管理员可以查看封禁名单");
                return bans(manager);
            case "list":
                if (!operator) return List.of("只有管理员可以查看全部房间");
                return list(manager);
            case "status":
                if (!operator) return List.of("只有服务器管理员可以查看连接状态");
                return args.length > 1 && args[1].equalsIgnoreCase("list") ? statusList(manager) : status(manager);
            case "idle":
                if (!operator) return List.of("只有服务器管理员可以管理频道空闲歌单");
                return idle(manager, actor, args);
            case "autojoin":
                return autojoin(manager, actor, operator, args);
            case "help": default: return help();
        }
    }

    private static List<String> op(RoomManager manager, UUID actor, JsonObject msg) {
        manager.handle(actor, msg);
        return List.of();
    }

    private static List<String> player(RoomManager manager, UUID actor, String[] args,
                                       String protoOp, String role) {
        if (args.length < 2) return List.of("用法：/mp " + args[0] + " <玩家>");
        JsonObject m = Proto.msg(protoOp);
        m.addProperty("target", args[1]);
        if (role != null) m.addProperty("role", role);
        return op(manager, actor, m);
    }

    /** 聊天栏参与投票。不带 voteId，投给当前那一个进行中的投票。 */
    private static List<String> vote(RoomManager manager, UUID actor, boolean agree) {
        Room room = manager.roomOf(actor);
        if (room == null) return List.of("你还没有加入一起听房间或频道");
        VoteSession v = room.latestOpenVote();
        if (v == null) return List.of("现在没有进行中的投票");
        JsonObject m = Proto.msg(Proto.C_VOTE_CAST);
        m.addProperty("voteId", v.id);
        m.addProperty("agree", agree);
        return op(manager, actor, m);
    }

    /**
     * {@code /mp ban <玩家> <原因> <时长>}。
     *
     * <p>时长可以写在原因前面也可以写在后面 —— 管理员是在聊天栏里现敲的，强求语序只会让命令
     * 用起来别扭。判据是「这一段能不能被解析成时长」（见 {@link Durations}），所以
     * {@code /mp ban Steve 刷歌 30m} 和 {@code /mp ban Steve 30m 刷歌} 都成立。没写时长
     * 就是永久。
     */
    private static List<String> ban(RoomManager manager, UUID actor, String[] args) {
        if (args.length < 2) {
            return List.of("用法：/mp ban <玩家> <原因> <时长>",
                    "时长可写 30m / 2h / 7d / 永久；省略即永久");
        }
        String targetName = args[1];
        long duration = Durations.PERMANENT;
        List<String> reasonParts = new ArrayList<>();
        boolean tookDuration = false;
        // 从后往前找第一个像时长的词：原因里出现「3天」这种字样时，末尾那个才是真正的时长。
        for (int i = args.length - 1; i >= 2; i--) {
            if (!tookDuration && Durations.looksLikeDuration(args[i])) {
                duration = Durations.parseMs(args[i]);
                tookDuration = true;
                continue;
            }
            reasonParts.add(0, args[i]);
        }
        String reason = String.join(" ", reasonParts).trim();
        if (reason.isEmpty()) reason = "未说明原因";

        UUID target = Net.uuidByName(targetName);
        long until = duration == Durations.PERMANENT ? 0L : System.currentTimeMillis() + duration;
        BanEntry entry = manager.bans().ban(target, targetName, reason, until, Net.nameOf(actor));
        if (target != null) {
            // 封了就得请出去，否则他还在房间里继续听。
            manager.enforceBan(target);
            Net.error(target, "你的一起听功能已被封禁（" + reason + "，"
                    + entry.remainingLabel() + "）");
        }
        return List.of("已封禁 " + targetName + " 的一起听功能：" + reason
                + "（" + entry.remainingLabel() + "）"
                + (target == null ? "  — 该玩家当前离线，按玩家名生效" : ""));
    }

    private static List<String> bans(RoomManager manager) {
        List<BanEntry> all = manager.bans().all();
        if (all.isEmpty()) return List.of("当前没有一起听封禁");
        List<String> out = new ArrayList<>();
        out.add("一起听封禁名单（" + all.size() + "）：");
        for (BanEntry e : all) {
            out.add("· " + e.name + "  " + e.reason + "  剩余 " + e.remainingLabel()
                    + (e.by.isBlank() ? "" : "  by " + e.by));
        }
        return out;
    }

    /**
     * {@code /mp status}：全服连接状况的几行汇总（仅管理员）。
     *
     * <p>「装了客户端」的判据是「服务端手里有他的档案」：客户端进服握手（hello）时建档，下线即清，
     * 所以它精确地等于「装了 mod 且和服务端接上了」。装了 mod 但通道没通（比如代理没转发）的人，
     * 服务端从头到尾收不到他的任何一帧，只能和没装的人一起算进「未检测到客户端」。</p>
     *
     * <p>和 {@code /mp who} 的分工：who 是逐人一行的明细（百人服会刷满一屏），这里只给汇总。</p>
     */
    private static List<String> status(RoomManager manager) {
        var platform = MpParty.platform();
        if (platform == null) return List.of("一起听服务端未就绪");
        int online = 0, withClient = 0, member = 0, contributeVip = 0, contributeLx = 0;
        java.util.Map<String, Integer> clients = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> versions = new java.util.LinkedHashMap<>();
        for (UUID uuid : platform.onlinePlayers()) {
            online++;
            ClientProfile p = manager.presence().get(uuid);
            if (p == null) continue;
            withClient++;
            String client = p.displayName();
            clients.merge(client, 1, Integer::sum);
            String ver = p.modVersion == null || p.modVersion.isBlank() ? "未知版本" : p.modVersion;
            versions.merge(client + " " + ver, 1, Integer::sum);
            if (!p.memberSources.isEmpty()) member++;
            if (contributesVipAnywhere(p)) contributeVip++;
            if (p.contributeLx && p.lxChannelCount() > 0) contributeLx++;
        }
        List<String> out = new ArrayList<>();
        out.add("在线 " + online + " 人：已装客户端并完成握手 " + withClient + " 人"
                + (clients.isEmpty() ? "" : "（" + joinCounts(clients, " ", 0) + "）")
                + "，未检测到客户端 " + (online - withClient) + " 人");
        if (!versions.isEmpty()) out.add("客户端版本：" + joinCounts(versions, " ×", 8));

        Room channel = null;
        int roomCount = 0, roomPeople = 0;
        for (Room r : manager.rooms()) {
            if (r.channel()) { channel = r; continue; }
            roomCount++;
            for (Member m : r.members()) if (m.online) roomPeople++;
        }
        String ch;
        if (!manager.config().channelEnabled) {
            ch = "服务器频道未开启";
        } else if (channel == null) {
            ch = "服务器频道 0 人";
        } else {
            int inChannel = 0;
            for (Member m : channel.members()) if (m.online) inChannel++;
            ch = channel.name + " " + inChannel + " 人（在听 " + channel.listenerCount() + "）";
        }
        out.add("房间：" + ch + " · 私人房间 " + roomCount + " 个共 " + roomPeople + " 人"
                + " · 自动加入" + (manager.config().channelAutoJoin ? "开" : "关"));
        out.add("解析能力：有会员 " + member + " 人 · 贡献会员解析 " + contributeVip
                + " 人 · 贡献 LX " + contributeLx + " 人"
                + (manager.resolver() != null && manager.resolver().anyEnabled() ? " · 服主 Token 已配置" : ""));
        out.add("/mp status list 列出未检测到客户端的玩家；/mp who 看每个人在听什么");
        return out;
    }

    /** {@code /mp status list}：未检测到客户端的在线玩家，每行 10 个，方便排查谁没装或没连上。 */
    private static List<String> statusList(RoomManager manager) {
        var platform = MpParty.platform();
        if (platform == null) return List.of("一起听服务端未就绪");
        List<String> names = new ArrayList<>();
        for (UUID uuid : platform.onlinePlayers()) {
            if (manager.presence().get(uuid) == null) names.add(Net.nameOf(uuid));
        }
        if (names.isEmpty()) return List.of("所有在线玩家都已装客户端并完成握手");
        names.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> out = new ArrayList<>();
        out.add("未检测到客户端（" + names.size() + " 人，没装 mod 或通道没连上）：");
        for (int i = 0; i < names.size(); i += 10) {
            out.add(String.join("、", names.subList(i, Math.min(names.size(), i + 10))));
        }
        return out;
    }

    /** 愿意并且有能力替房间解析至少一个平台的完整版（与分层解析用的是同一个判据）。 */
    private static boolean contributesVipAnywhere(ClientProfile p) {
        if (!p.contributeVip) return false;
        java.util.Set<String> all = new java.util.LinkedHashSet<>(p.vipSources);
        all.addAll(p.memberSources);
        all.addAll(p.resolvableSources);
        for (String s : all) if (p.contributesVip(s)) return true;
        return false;
    }

    /** 「A 3 · B 1」。按人数从多到少；limit > 0 时只列前几项，其余合成「其他」。 */
    private static String joinCounts(java.util.Map<String, Integer> counts, String sep, int limit) {
        List<java.util.Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        StringBuilder sb = new StringBuilder();
        int rest = 0;
        for (int i = 0; i < list.size(); i++) {
            if (limit > 0 && i >= limit) { rest += list.get(i).getValue(); continue; }
            if (sb.length() > 0) sb.append(" · ");
            sb.append(list.get(i).getKey()).append(sep).append(list.get(i).getValue());
        }
        if (rest > 0) sb.append(" · 其他").append(sep).append(rest);
        return sb.toString();
    }

    /** 在线玩家和他们正在听什么 —— GUI 里那一页的命令行版本。 */
    private static List<String> who(RoomManager manager) {
        var platform = MpParty.platform();
        if (platform == null) return List.of("一起听服务端未就绪");
        List<String> out = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (UUID uuid : platform.onlinePlayers()) {
            ClientProfile p = manager.presence().get(uuid);
            if (p == null) continue;
            Room room = manager.roomOf(uuid);
            String listening = p.listeningLabel();
            lines.add("· " + Net.nameOf(uuid) + " [" + p.badge() + "]"
                    + (p.hasVip() ? " VIP" : "")
                    + (room == null ? "" : " @" + room.name)
                    + "  " + (listening.isEmpty() ? "（未在听）" : listening));
        }
        if (lines.isEmpty()) return List.of("当前没有安装播放器客户端的在线玩家");
        out.add("在线（装了播放器的 " + lines.size() + " 人）：");
        out.addAll(lines);
        return out;
    }

    private static List<String> info(RoomManager manager, UUID actor) {
        Room room = manager.roomOf(actor);
        if (room == null) {
            return List.of("你还没有加入一起听房间，使用 /mp create 创建或 /mp join <邀请码> 加入");
        }
        List<String> out = new ArrayList<>();
        out.add((room.channel() ? "频道：" : "房间：") + room.name
                + "  邀请码：" + room.code
                + "  循环：" + room.repeat().id()
                + (room.channel() ? "" : (room.open ? "  [公开]" : "  [私密]")));
        for (VoteSession v : room.votes()) {
            out.add("投票中：" + v.headline() + "  " + v.agrees() + "/"
                    + v.needed(room.voteEligible()) + " 票，剩 "
                    + (v.remainingMs() / 1000L) + " 秒 —— /mp agree 同意");
        }
        QueueEntry cur = room.current();
        out.add("正在播放：" + (cur == null ? "（无）"
                : cur.track.display() + "  " + fmt(room.positionMs()) + "/" + fmt(cur.track.durationMs)
                        + (room.isPlaying() ? " ▶" : " ⏸")));
        StringBuilder sb = new StringBuilder("成员（").append(room.members().size())
                .append("，收听中 ").append(room.listenerCount()).append("）：");
        boolean first = true;
        for (Member m : room.members()) {
            if (!first) sb.append("，");
            first = false;
            ClientProfile prof = manager.presence().get(m.uuid);
            sb.append(m.name).append('[').append(m.role.id())
                    .append(m.online ? "" : ",离线")
                    .append(m.selfPaused ? ",个人暂停" : "")
                    .append(prof == null ? "" : "," + prof.badge())
                    .append(prof != null && prof.hasVip() ? ",VIP" : "")
                    .append(']');
        }
        out.add(sb.toString());
        return out;
    }

    private static List<String> queue(RoomManager manager, UUID actor) {
        Room room = manager.roomOf(actor);
        if (room == null) return List.of("你还没有加入一起听房间");
        if (room.queue().isEmpty()) return List.of("一起听歌单为空");
        List<String> out = new ArrayList<>();
        out.add("一起听歌单（" + room.queue().size() + "）：");
        int i = 1;
        for (QueueEntry e : room.queue()) {
            out.add(i++ + ". " + e.track.display() + "  — " + e.requesterName);
        }
        return out;
    }

    private static List<String> list(RoomManager manager) {
        if (manager.rooms().isEmpty()) return List.of("当前没有一起听房间");
        List<String> out = new ArrayList<>();
        out.add("一起听房间：");
        for (Room r : manager.rooms()) {
            out.add(r.code + "  " + r.name + "  成员 " + r.members().size()
                    + "  队列 " + r.queue().size()
                    + "  " + (r.current() == null ? "空闲" : r.current().track.display()));
        }
        return out;
    }

    /**
     * {@code /mp idle ...}：频道空闲歌单。直接调 manager 而不是构造协议帧 —— 这是只有管理员在聊天栏
     * 用的服务端设施，客户端没有对应的界面，也就不该为它扩线协议（同 {@code /mp ban}）。
     */
    private static List<String> idle(RoomManager manager, UUID actor, String[] args) {
        IdlePlaylist idle = manager.idle();
        String sub = args.length < 2 ? "info" : args[1].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "save": case "add": {
                    boolean append = sub.equals("add");
                    Room src = manager.roomOf(actor);
                    String from = src == null ? "服务器频道" : src.name;
                    int n = manager.saveIdleFrom(actor, append ? null : (args.length > 2 ? join(args, 2) : null), append);
                    List<String> out = new ArrayList<>();
                    out.add(append
                            ? "已从「" + from + "」追加 " + n + " 首（重复的已跳过），空闲歌单现有 " + idle.size() + " 首"
                            : "已把「" + from + "」里的 " + n + " 首歌存为空闲歌单「" + idle.name() + "」"
                                    + (idle.enabled() ? "" : "，输入 /mp idle on 开启"));
                    if (src != null && !src.channel()) {
                        out.add("这个房间可以 /mp disband 解散了，再 /mp channel 回到频道");
                    }
                    return out;
                }
                case "on":
                    if (idle.size() == 0) {
                        return List.of("空闲歌单是空的：先 /mp create 建房、用「播放歌单」灌进去，再 /mp idle save");
                    }
                    manager.setIdleEnabled(true);
                    return List.of("空闲歌单已开启：频道没人点歌时会自动播放「" + idle.name() + "」");
                case "off":
                    manager.setIdleEnabled(false);
                    return List.of("空闲歌单已关闭（正在放的这首会放完）");
                case "shuffle": {
                    if (args.length < 3) return List.of("用法：/mp idle shuffle <on|off>");
                    boolean on = isOn(args[2]);
                    idle.setShuffle(on);
                    return List.of("空闲歌单已切换为" + (on ? "随机" : "顺序") + "播放");
                }
                case "clear":
                    manager.clearIdle();
                    return List.of("空闲歌单已清空并关闭");
                case "list": {
                    if (idle.size() == 0) return List.of("空闲歌单是空的");
                    List<String> out = new ArrayList<>();
                    out.add("空闲歌单「" + idle.name() + "」接下来（共 " + idle.size() + " 首）：");
                    int i = 1;
                    for (String s : idle.upcoming(20)) out.add(i++ + ". " + s);
                    return out;
                }
                case "info": default: {
                    List<String> out = new ArrayList<>();
                    long paused = idle.pausedRemainingMs();
                    out.add("空闲歌单「" + idle.name() + "」：" + (idle.enabled() ? "开启" : "关闭")
                            + (idle.enabled() && paused > 0 ? "（连续解析失败，暂停中，还剩 "
                                    + Math.max(1L, paused / 60_000L) + " 分钟）" : "")
                            + "，" + idle.size() + " 首，" + (idle.shuffle() ? "随机" : "顺序") + "播放");
                    out.add("频道自动加入：" + (manager.config().channelAutoJoin ? "开启" : "关闭"));
                    out.add("/mp idle save [名字] | add | on | off | shuffle <on|off> | list | clear");
                    return out;
                }
            }
        } catch (IllegalStateException e) {
            return List.of(e.getMessage());
        }
    }

    /**
     * {@code /mp autojoin ...}：
     * <ul>
     *   <li>{@code /mp autojoin} —— 看自己和全服的状态；</li>
     *   <li>{@code /mp autojoin on|off} —— 玩家自己的开关，人人可用；</li>
     *   <li>{@code /mp autojoin server on|off} —— 全服开关，仅管理员，写回 mpsync.json。</li>
     * </ul>
     */
    private static List<String> autojoin(RoomManager manager, UUID actor, boolean operator, String[] args) {
        MpConfig cfg = manager.config();
        boolean serverOn = cfg.channelAutoJoin && cfg.channelEnabled;
        if (args.length < 2) {
            List<String> out = new ArrayList<>();
            out.add("服务器频道自动加入：" + (serverOn ? "开启" : "关闭")
                    + "；你的设置：" + (manager.optedOutOfAutoJoin(actor) ? "关闭" : "开启"));
            out.add("/mp autojoin on|off  开关你自己的进服自动加入"
                    + (operator ? "；/mp autojoin server on|off  全服开关" : ""));
            return out;
        }
        if (args[1].equalsIgnoreCase("server")) {
            if (!operator) return List.of("只有服务器管理员可以设置全服自动加入");
            return autojoinServer(manager, args);
        }
        boolean on = isOn(args[1]);
        boolean joined = manager.setPersonalAutoJoin(actor, on);
        if (!on) return List.of("已关闭你的进服自动加入频道（当前在频道里的话用 /mp leave 离开）");
        if (!serverOn) return List.of("已打开你的进服自动加入，不过服务器当前没有开启这个功能");
        return List.of("已打开你的进服自动加入频道" + (joined ? "，已把你放进频道" : ""));
    }

    /** {@code /mp autojoin server on|off}：切换全服自动加入并写回 mpsync.json。 */
    private static List<String> autojoinServer(RoomManager manager, String[] args) {
        MpConfig cfg = manager.config();
        if (args.length < 3) {
            return List.of("全服自动加入：" + (cfg.channelAutoJoin ? "开启" : "关闭") + "  —— /mp autojoin server <on|off>");
        }
        boolean on = isOn(args[2]);
        cfg.channelAutoJoin = on;
        boolean saved = MpParty.saveConfig();
        List<String> out = new ArrayList<>();
        out.add("全服频道自动加入已" + (on ? "开启" : "关闭")
                + (saved ? "（已写入 mpsync.json）" : "（本次运行有效，配置文件未写入）"));
        if (on && !cfg.channelEnabled) {
            out.add("注意：服务器频道当前是关闭的（channelEnabled=false），自动加入不会生效");
        } else if (on) {
            int n = manager.autoJoinOnlineNow();
            if (n > 0) out.add("已把当前在线的 " + n + " 位客户端玩家放进频道");
        }
        return out;
    }

    private static boolean isOn(String s) {
        String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        return v.equals("on") || v.equals("true") || v.equals("1") || v.equals("开") || v.equals("是");
    }

    private static List<String> help() {
        return List.of(
                "一起听指令：",
                "/mp create [房名]         创建房间",
                "/mp join <邀请码>         加入房间",
                "/mp channel               加入服务器频道（全服公开大房间）",
                "/mp public [on|off]       把自己的房间设为公开 / 私密",
                "/mp invite <玩家>         邀请玩家（对方在一起听界面里处理）",
                "/mp leave | /mp disband   离开 | 解散（房主）",
                "/mp admin <玩家> | /mp unadmin <玩家>   授予/撤销管理员",
                "/mp transfer <玩家> | /mp kick <玩家>   转让房主 | 移出成员",
                "/mp pause | resume | skip | prev | clear",
                "/mp play <序号> | /mp seek <秒> | /mp repeat <off|one|all>",
                "/mp vote skip | /mp vote kick <玩家>    频道内发起投票",
                "/mp agree | /mp deny      对进行中的投票表态",
                "/mp info | /mp queue | /mp who         房间信息 | 歌单 | 在线在听",
                "/mp autojoin [on|off]     你自己的进服自动加入频道开关",
                "管理员：/mp ban <玩家> <原因> <时长> | /mp unban <玩家> | /mp bans | /mp list",
                "管理员：/mp status [list]   全服连接状况汇总（多少人装了客户端并连上）",
                "管理员：/mp idle [save|add|on|off|shuffle|list|clear]   频道空闲歌单",
                "管理员：/mp autojoin server <on|off>   全服：装了客户端的玩家进服自动加入频道",
                "点歌与切歌请在音乐播放器界面操作（需安装支持一起听的客户端 mod）");
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Throwable ignored) { return def; }
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "0:00";
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
