package yun.shan.mpsync.presence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;
import yun.shan.mpsync.platform.MpPlatform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全服在线态：谁在线、用什么客户端、有没有 VIP、正在听什么。
 *
 * <p>这一层刻意和房间分开。需求要的「在线玩家和正在听的音乐名」不限于同一个房间 —— 你得先能
 * 看到谁在听什么，才能决定邀请谁。所以档案按玩家存活，进出房间都不影响；房间快照只是在成员
 * 条目上把这份档案<b>拼</b>进去。
 *
 * <p>只在内存里：档案跟着连接生灭，玩家下线就没有意义了（他既不在听歌，也不能替谁解析）。
 */
public final class PresenceRegistry {

    private final Map<UUID, ClientProfile> profiles = new HashMap<>();

    public ClientProfile get(UUID uuid) {
        return uuid == null ? null : profiles.get(uuid);
    }

    /** 拿到（必要时新建）这个玩家的档案。 */
    public ClientProfile of(UUID uuid) {
        if (uuid == null) return new ClientProfile();
        return profiles.computeIfAbsent(uuid, k -> new ClientProfile());
    }

    /** 吸收一帧 profile / hello。 */
    public ClientProfile update(UUID uuid, JsonObject msg) {
        ClientProfile p = of(uuid);
        p.apply(msg);
        return p;
    }

    public void forget(UUID uuid) {
        if (uuid != null) profiles.remove(uuid);
    }

    public void clear() {
        profiles.clear();
    }

    /** 这个玩家愿不愿意、并且有能力替房间解析这个平台的曲子（VIP 与 LX 两档的并集）。 */
    public boolean contributes(UUID uuid, String source) {
        ClientProfile p = get(uuid);
        return p != null && p.contributes(source);
    }

    /** 「贡献 VIP」档：真会员或不经 LX 就有完整版路。取流分层里排在「贡献 LX」之前。 */
    public boolean contributesVip(UUID uuid, String source) {
        ClientProfile p = get(uuid);
        return p != null && p.contributesVip(source);
    }

    /** 「贡献 LX」档：愿意且只能靠本机 LX 自定义源替房间解该平台。 */
    public boolean contributesLx(UUID uuid, String source) {
        ClientProfile p = get(uuid);
        return p != null && p.contributesLx(source);
    }

    /** 这台机器的脚本覆盖几个渠道；没报过档案就是 0。用于给「贡献 LX」那一层内部排序。 */
    public int lxChannelCount(UUID uuid) {
        ClientProfile p = get(uuid);
        return p == null ? 0 : p.lxChannelCount();
    }

    /**
     * 这个平台上是<b>真会员</b>（客户端报的 {@code member} 数组里有）。
     *
     * <p>取流分层里第 1 层只问这一档。它和 {@link #contributesVip} 的差别就是这次分层的全部意义：
     * 后者还包含「这个平台本来不卖会员」的那类，那一路同样能给完整版，但不该占着第一层 ——
     * 会员答案的过期时间与稳定性都更好，先问它。</p>
     */
    public boolean isMember(UUID uuid, String source) {
        ClientProfile p = get(uuid);
        return p != null && p.isMember(source);
    }

    /** 有能力解析（不管愿不愿意贡献）——排在贡献者后面问。 */
    public boolean canResolve(UUID uuid, String source) {
        ClientProfile p = get(uuid);
        return p != null && p.canResolve(source);
    }

    /**
     * 这台客户端有没有跨平台换源那套流水线（{@code switchRescue}）。
     *
     * <p>没有的人收不到 {@code switch_rescue}：那一句要它跑一条压根不存在的流水线，
     * 而服务端还得为它把总闸多留一个救援窗 —— 白等 12 秒换不来任何一次可播的机会。</p>
     */
    /** 这台机器能出完整版的平台数，用于跨平台匹配的派单排序。 */
    public int resolvableCount(UUID uuid) {
        ClientProfile p = get(uuid);
        return p == null ? 0 : p.resolvableCount();
    }

    public boolean canSwitchRescue(UUID uuid) {
        ClientProfile p = get(uuid);
        return p != null && p.switchRescue;
    }

    /**
     * 全服在线态快照。
     *
     * @param roomLabel 玩家所在房间的名字（不在房间里给空串）——由调用方从 RoomManager 查，
     *                  避免这个类反过来依赖房间层
     */
    public JsonObject snapshot(MpPlatform platform, RoomLabels roomLabel) {
        JsonArray players = new JsonArray();
        Collection<UUID> online = platform == null ? List.of() : safeOnline(platform);
        List<UUID> sorted = new ArrayList<>(online);
        // 有客户端的排前面，其余按名字：一屏 GUI 里，能一起听的人才是有用信息。
        sorted.sort((a, b) -> {
            ClientProfile pa = get(a);
            ClientProfile pb = get(b);
            boolean ma = pa != null && !pa.mod.isBlank();
            boolean mb = pb != null && !pb.mod.isBlank();
            if (ma != mb) return ma ? -1 : 1;
            return Net.nameOf(a).compareToIgnoreCase(Net.nameOf(b));
        });
        for (UUID uuid : sorted) {
            ClientProfile p = get(uuid);
            JsonObject o = (p == null ? new ClientProfile() : p).toJson(uuid, Net.nameOf(uuid));
            String room = roomLabel == null ? "" : roomLabel.labelOf(uuid);
            o.addProperty("room", room == null ? "" : room);
            o.addProperty("hasClient", p != null && !p.mod.isBlank());
            players.add(o);
        }
        JsonObject msg = Proto.msg(Proto.S_PRESENCE);
        msg.add("players", players);
        msg.addProperty("online", players.size());
        return msg;
    }

    private static Collection<UUID> safeOnline(MpPlatform platform) {
        try {
            Collection<UUID> c = platform.onlinePlayers();
            return c == null ? List.of() : c;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /** 「这个玩家在哪个房间」的回调，由房间层提供。 */
    @FunctionalInterface
    public interface RoomLabels {
        String labelOf(UUID uuid);
    }
}