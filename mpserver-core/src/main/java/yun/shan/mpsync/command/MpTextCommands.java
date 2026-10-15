package yun.shan.mpsync.command;

import com.google.gson.JsonObject;
import yun.shan.mpsync.MpParty;
import yun.shan.mpsync.ban.BanEntry;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.presence.ClientProfile;
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

    /** Subcommands offered for tab completion (excluding the op-only {@code list}). */
    public static final List<String> SUBCOMMANDS = List.of(
            "create", "join", "leave", "disband", "invite", "admin", "unadmin", "kick",
            "transfer", "pause", "resume", "skip", "prev", "clear", "seek", "repeat",
            "play", "info", "queue", "channel", "public", "agree", "deny", "vote",
            "who", "help");

    /** Completion candidates for {@code /mp <args...>}. */
    public static List<String> suggest(String[] args, List<String> onlineNames) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : SUBCOMMANDS) if (s.startsWith(prefix)) out.add(s);
            return out;
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "invite": case "admin": case "unadmin": case "kick": case "transfer": {
                    List<String> out = new ArrayList<>();
                    String prefix = args[1].toLowerCase(Locale.ROOT);
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
                    String prefix = args[1].toLowerCase(Locale.ROOT);
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
                "管理员：/mp ban <玩家> <原因> <时长> | /mp unban <玩家> | /mp bans | /mp list",
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
