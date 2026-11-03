package yun.shan.mpsync.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import yun.shan.mpsync.presence.ClientProfile;

import java.util.Collection;
import java.util.UUID;

/** One participant of a {@link Room}. Mutated on the server thread only. */
public final class Member {

    public final UUID uuid;
    public String name;
    public Role role;
    /** Online here means "has an active play connection", not "currently playing audio". */
    public boolean online = true;
    /** Last client heartbeat ({@code progress}) — epoch ms. */
    public long lastSeen = System.currentTimeMillis();
    /** Whether the client reports audio actually playing (drives the listener count). */
    public boolean listening;
    /** Last position the client reported, for drift diagnostics via {@code /mp info}. */
    public long reportedPos;
    /**
     * 个人暂停：这个成员自己按了暂停，房间时钟照走。
     *
     * <p>为什么必须让服务端知道，而不是客户端自己静音了算：一，收听人数不该把他算进去；
     * 二，别人能在成员列表里看到「他暂停了」而不是以为他掉线；三，他恢复的时候要由服务端
     * 立刻推一次权威位置过去，否则他会从暂停那一刻的位置继续放，越听越偏。
     */
    public boolean selfPaused;

    public Member(UUID uuid, String name, Role role) {
        this.uuid = uuid;
        this.name = name;
        this.role = role;
    }

    /** 真的在听：在线、客户端报了在放、而且没有个人暂停。 */
    public boolean actuallyListening() {
        return online && listening && !selfPaused;
    }

    public JsonObject toJson() {
        return toJson(null);
    }

    /**
     * 成员条目。
     *
     * @param profile 这个玩家的客户端自报档案（Mod 名、VIP 平台、在听曲目）；null 表示
     *                他没上报过 —— 那就把 {@code mod} 留空，客户端会显示「未知」，
     *                而不是替他编一个
     */
    public JsonObject toJson(ClientProfile profile) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name);
        o.addProperty("role", role.id());
        o.addProperty("online", online);
        o.addProperty("listening", listening);
        o.addProperty("selfPaused", selfPaused);
        // v2：客户端身份与 VIP。老客户端读不到这些字段，行为不变。
        o.addProperty("mod", profile == null ? "" : profile.mod);
        o.addProperty("modName", profile == null ? "" : profile.displayName());
        o.addProperty("modBadge", profile == null ? "" : profile.badge());
        o.addProperty("modVersion", profile == null ? "" : profile.modVersion);
        o.addProperty("vip", profile != null && profile.hasVip());
        o.addProperty("contributeVip", profile != null && profile.contributeVip);
        o.add("vipSources", sourcesJson(profile == null ? null : profile.vipSources));
        // 能力位原样透出（member / resolvable / viaLx 是客户端自报的三个集合，automix* 是
        // 分析能力）——两套客户端拿它们画成员行上的 chip。服务端不解读、不加工：这些集合
        // 的语义只有报上来它们的客户端自己说得清，这里过一道只会引入第三种口径。
        o.addProperty("automixCapable", profile != null && profile.automixCapable);
        o.addProperty("automixEngine", profile == null || profile.automixEngine == null
                ? "" : profile.automixEngine);
        o.add("member", sourcesJson(profile == null ? null : profile.memberSources));
        o.add("resolvable", sourcesJson(profile == null ? null : profile.resolvableSources));
        o.add("viaLx", sourcesJson(profile == null ? null : profile.viaLxSources));
        // 「正在听的音乐名」：在房间里时它通常就是房间当前曲目，但个人暂停或还没开起来时
        // 会不一样，所以照客户端自己报的来。
        o.addProperty("nowPlaying", profile == null ? "" : profile.listeningLabel());
        return o;
    }

    private static JsonArray sourcesJson(Collection<String> set) {
        JsonArray a = new JsonArray();
        if (set != null) for (String s : set) a.add(s);
        return a;
    }
}