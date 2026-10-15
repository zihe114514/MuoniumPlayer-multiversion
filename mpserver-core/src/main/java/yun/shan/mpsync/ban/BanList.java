package yun.shan.mpsync.ban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 一起听封禁名单（{@code /mp ban}），落盘在 {@code mpsync-bans.json}。
 *
 * <p>为什么要持久化：封禁是管理动作，重启就失效等于没有封 —— 这一点和房间刻意不落盘正好相反
 * （房间里的直链几分钟就过期，恢复出来全是死链，而封禁没有这个问题）。
 *
 * <p>同时按 UUID 和玩家名匹配：封禁时玩家在线就记 UUID（改名也逃不掉），离线只能按名字封，
 * 于是两个条件是「或」的关系。
 *
 * <p>只封「一起听功能」，不动玩家进服。被封的人照样能玩，只是建不了房、进不了房、点不了歌 ——
 * 这是这个封禁的全部含义，不要拿它当踢人工具。
 */
public final class BanList {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<BanEntry> entries = new ArrayList<>();
    private Path file;

    public void load(Path path) {
        this.file = path;
        entries.clear();
        try {
            if (path != null && Files.exists(path)) {
                List<BanEntry> read = GSON.fromJson(
                        Files.readString(path, StandardCharsets.UTF_8),
                        new TypeToken<List<BanEntry>>() {}.getType());
                if (read != null) {
                    for (BanEntry e : read) if (e != null) entries.add(e);
                }
            }
        } catch (Throwable ignored) {
            // 坏文件不该拖住开服；当成空名单，下一次 save 会把它重写成合法 JSON。
        }
        purge();
    }

    /** 丢掉已到期的条目。返回是否有变化。 */
    public boolean purge() {
        long now = System.currentTimeMillis();
        boolean changed = entries.removeIf(e -> e.expired(now));
        if (changed) save();
        return changed;
    }

    public List<BanEntry> all() {
        purge();
        return new ArrayList<>(entries);
    }

    /** 生效中的封禁，没有就返回 null。 */
    public BanEntry find(UUID uuid, String name) {
        long now = System.currentTimeMillis();
        for (BanEntry e : entries) {
            if (e.expired(now)) continue;
            if (e.matches(uuid, name)) return e;
        }
        return null;
    }

    public boolean isBanned(UUID uuid, String name) {
        return find(uuid, name) != null;
    }

    /** 覆盖式封禁：同一个人重复封会替换掉旧记录，而不是叠两条。 */
    public BanEntry ban(UUID uuid, String name, String reason, long until, String by) {
        remove(uuid, name);
        BanEntry e = new BanEntry(uuid, name, reason, until, by);
        entries.add(e);
        save();
        return e;
    }

    public boolean unban(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) return false;
        String key = nameOrUuid.trim();
        UUID uuid = null;
        try { uuid = UUID.fromString(key); } catch (Throwable ignored) { }
        boolean removed = remove(uuid, key);
        if (removed) save();
        return removed;
    }

    private boolean remove(UUID uuid, String name) {
        String lower = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return entries.removeIf(e ->
                (uuid != null && !e.uuid.isBlank() && e.uuid.equalsIgnoreCase(uuid.toString()))
                        || (!lower.isEmpty() && e.name.equalsIgnoreCase(lower)));
    }

    private void save() {
        Path path = file;
        if (path == null) return;
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(entries), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 写不进去（只读目录 / 磁盘满）也不能让封禁命令抛到玩家脸上；内存里的名单仍然生效。
        }
    }
}