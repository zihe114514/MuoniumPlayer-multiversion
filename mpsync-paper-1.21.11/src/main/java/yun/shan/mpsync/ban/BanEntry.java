package yun.shan.mpsync.ban;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.UUID;

/** 一条一起听封禁记录。{@link #until} 为 0 表示永久。 */
public final class BanEntry {

    /** 封禁时在线才拿得到 UUID；按名字封离线玩家时为 null。 */
    public String uuid = "";
    /** 小写玩家名。UUID 缺失时靠它匹配，所以必须存。 */
    public String name = "";
    public String reason = "";
    public String by = "";
    public long at;
    /** 解封时间（epoch ms）；0 = 永久。 */
    public long until;

    public BanEntry() {}

    public BanEntry(UUID uuid, String name, String reason, long until, String by) {
        this.uuid = uuid == null ? "" : uuid.toString();
        this.name = name == null ? "" : name.toLowerCase(Locale.ROOT);
        this.reason = reason == null || reason.isBlank() ? "未说明原因" : reason;
        this.until = Math.max(0L, until);
        this.by = by == null ? "" : by;
        this.at = System.currentTimeMillis();
    }

    public boolean permanent() { return until <= 0L; }

    public boolean expired(long now) { return !permanent() && now >= until; }

    public boolean matches(UUID id, String playerName) {
        if (id != null && !uuid.isBlank() && uuid.equalsIgnoreCase(id.toString())) return true;
        return playerName != null && !name.isBlank()
                && name.equalsIgnoreCase(playerName.trim());
    }

    /** 剩余时长的中文短描述。 */
    public String remainingLabel() {
        if (permanent()) return "永久";
        long left = until - System.currentTimeMillis();
        if (left <= 0L) return "已到期";
        long m = left / 60_000L;
        if (m < 60L) return Math.max(1L, m) + " 分钟";
        long h = m / 60L;
        if (h < 48L) return h + " 小时";
        return (h / 24L) + " 天";
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("name", name);
        o.addProperty("reason", reason);
        o.addProperty("by", by);
        o.addProperty("at", at);
        o.addProperty("until", until);
        o.addProperty("remaining", remainingLabel());
        return o;
    }
}