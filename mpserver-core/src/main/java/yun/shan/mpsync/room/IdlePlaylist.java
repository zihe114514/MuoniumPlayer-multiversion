package yun.shan.mpsync.room;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.net.Net;
import yun.shan.mpsync.net.Proto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 服务器频道的空闲歌单：没人点歌时频道自动放这张单。落盘在 {@code mpsync-idle.json}。
 *
 * <h2>为什么只存元数据</h2>
 * 直链几分钟就过期，存下来也是死链。这里只存 {@code source/songId/名字/时长…}，轮到哪首，房间就
 * 走现有的分层求助（服主 Token → 会员 → LX → 能解析的成员 → 所有人）去要一条新的 —— 和成员
 * 「灌歌单」进来的曲目完全同一条路。所以歌单里每首都得有在线的人（或服主 Token）解析得出来。
 *
 * <h2>歌单从哪来</h2>
 * 管理员先用客户端的「播放歌单」把整张单灌进频道，再 {@code /mp idle save} 把频道当前的队列存下来
 * （见 {@code RoomManager#saveIdleFromChannel}）。时长等元数据都是客户端给的，是完整的；手写这个
 * 文件不现实 —— 没有时长的曲目会被拒，否则房间会卡在那一首上。
 *
 * <h2>失败处理</h2>
 * 解析不出来的歌冷却 {@link MpConfig#idleFailCooldownMs} 不再选；连续
 * {@link MpConfig#idleMaxConsecutiveFails} 首都失败就整体暂停 {@link MpConfig#idlePauseMs}，
 * 免得在一张谁都解析不了的单子上空转、反复跳歌。
 *
 * <p>线程：只在引擎线程上被调用（和 Room 一样），不加锁。</p>
 */
public final class IdlePlaylist implements IdleSource {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String DEFAULT_NAME = "服务器歌单";

    private final MpConfig cfg;
    private Path file;

    private boolean enabled;
    private boolean shuffle;
    private String name = DEFAULT_NAME;
    private final List<JsonObject> tracks = new ArrayList<>();

    /** 播放顺序（tracks 的下标排列）；顺序模式下就是 0..n-1。 */
    private int[] order = new int[0];
    /** 下一首在 {@link #order} 里的位置。 */
    private int cursor;

    private final Map<String, Long> coolUntil = new HashMap<>();
    private int consecutiveFails;
    private long pausedUntil;
    private final Random rng = new Random();

    public IdlePlaylist(MpConfig cfg) {
        this.cfg = cfg;
    }

    // ── 持久化 ───────────────────────────────────────────────────────────────

    public void load(Path path) {
        this.file = path;
        tracks.clear();
        try {
            if (path != null && Files.exists(path)) {
                JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                enabled = Proto.bool(o, "enabled", false);
                shuffle = Proto.bool(o, "shuffle", false);
                String n = Proto.str(o, "name", DEFAULT_NAME);
                name = n == null || n.isBlank() ? DEFAULT_NAME : n.trim();
                JsonArray arr = Proto.arr(o, "tracks");
                if (arr != null) {
                    Set<String> seen = new LinkedHashSet<>();
                    for (JsonElement e : arr) {
                        if (e == null || !e.isJsonObject()) continue;
                        JsonObject t = e.getAsJsonObject();
                        if (seen.add(keyOf(t))) tracks.add(t);
                    }
                }
                cursor = (int) Math.max(0L, Proto.num(o, "cursor", 0L));
            }
        } catch (Throwable t) {
            // 坏文件不该拖住开服：当成空歌单，下一次 save 会重写成合法 JSON。
            Net.warn("[mpsync] 空闲歌单文件读取失败，按空歌单处理：" + path, t);
            tracks.clear();
        }
        rebuildOrder();
        if (cursor >= order.length) cursor = 0;
    }

    private void save() {
        Path path = file;
        if (path == null) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("shuffle", shuffle);
            o.addProperty("name", name);
            o.addProperty("cursor", cursor);
            JsonArray arr = new JsonArray();
            for (JsonObject t : tracks) arr.add(t);
            o.add("tracks", arr);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 写不进去也不能让指令抛到管理员脸上；内存里的歌单仍然生效。
            Net.warn("[mpsync] 空闲歌单写盘失败：" + path, t);
        }
    }

    // ── 管理（/mp idle） ─────────────────────────────────────────────────────

    public boolean enabled() { return enabled; }

    public boolean shuffle() { return shuffle; }

    public String name() { return name; }

    public int size() { return tracks.size(); }

    /** 因连续失败而暂停的剩余毫秒；0 = 没暂停。 */
    public long pausedRemainingMs() {
        return Math.max(0L, pausedUntil - System.currentTimeMillis());
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            // 重新打开是管理员的明确意愿：之前的暂停与失败计数都作废，给这张单一次干净的机会。
            pausedUntil = 0L;
            consecutiveFails = 0;
        }
        save();
    }

    public void setShuffle(boolean on) {
        if (shuffle == on) return;
        shuffle = on;
        rebuildOrder();
        cursor = 0;
        save();
    }

    /** 整张替换。返回实际存下的首数（已去重、已剔除没有时长的）。 */
    public int replace(String newName, List<Track> list) {
        tracks.clear();
        coolUntil.clear();
        consecutiveFails = 0;
        pausedUntil = 0L;
        if (newName != null && !newName.isBlank()) name = clip(newName.trim(), 40);
        int added = addAll(list);
        rebuildOrder();
        cursor = 0;
        save();
        return added;
    }

    /** 追加到末尾（跳过已有的）。返回新增首数。 */
    public int append(List<Track> list) {
        int added = addAll(list);
        if (added > 0) {
            rebuildOrder();
            if (cursor >= order.length) cursor = 0;
            save();
        }
        return added;
    }

    public void clear() {
        tracks.clear();
        coolUntil.clear();
        enabled = false;
        rebuildOrder();
        cursor = 0;
        save();
    }

    /** 接下来会放的几首（只读，不移动游标）。冷却中的也列出来，由调用方决定怎么显示。 */
    public List<String> upcoming(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < order.length && out.size() < n; i++) {
            JsonObject t = tracks.get(order[(cursor + i) % order.length]);
            out.add(display(t));
        }
        return out;
    }

    private int addAll(List<Track> list) {
        if (list == null) return 0;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonObject t : tracks) seen.add(keyOf(t));
        int added = 0;
        for (Track t : list) {
            if (t == null || t.durationMs <= 0L) continue;
            if (!seen.add(t.key())) continue;
            tracks.add(t.toJson(false));
            added++;
        }
        return added;
    }

    // ── IdleSource ───────────────────────────────────────────────────────────

    @Override
    public boolean active() {
        return enabled && !tracks.isEmpty() && System.currentTimeMillis() >= pausedUntil;
    }

    @Override
    public Track next(Set<String> exclude) {
        int n = order.length;
        long now = System.currentTimeMillis();
        for (int tries = 0; tries < n; tries++) {
            JsonObject o = tracks.get(order[cursor]);
            advance();
            String key = keyOf(o);
            if (exclude != null && exclude.contains(key)) continue;
            Long until = coolUntil.get(key);
            if (until != null) {
                if (until > now) continue;
                coolUntil.remove(key);
            }
            try {
                // 每次都重新走一遍校验：服主可能后来收紧了 allowedSources / allowedHosts。
                Track t = Track.fromJson(o, cfg);
                if (t.durationMs <= 0L) continue;
                save();   // 游标落盘：重启后接着往下放，而不是每次都从第一首开始
                return t;
            } catch (Throwable ignored) {
                // 平台已不开放 / ID 不合法：跳过这一首，不算解析失败。
            }
        }
        return null;
    }

    @Override
    public void failed(String key) {
        long now = System.currentTimeMillis();
        coolUntil.put(key, now + cfg.idleFailCooldownMs);
        if (++consecutiveFails >= cfg.idleMaxConsecutiveFails) {
            consecutiveFails = 0;
            pausedUntil = now + cfg.idlePauseMs;
            Net.warn("[mpsync] 空闲歌单连续 " + cfg.idleMaxConsecutiveFails
                    + " 首都没能解析出播放地址，暂停 " + (cfg.idlePauseMs / 60_000L)
                    + " 分钟（频道里可能没有能解析这些平台的成员，考虑配置 serverTokens）", null);
        }
    }

    @Override
    public void played(String key) {
        consecutiveFails = 0;
    }

    @Override
    public String label() { return name; }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private void advance() {
        cursor++;
        if (cursor >= order.length) {
            cursor = 0;
            // 随机模式每轮重新洗一次，免得每轮都是同一个「随机」顺序。
            if (shuffle) shuffleOrder();
        }
    }

    private void rebuildOrder() {
        order = new int[tracks.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        if (shuffle) shuffleOrder();
    }

    private void shuffleOrder() {
        for (int i = order.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
    }

    static String keyOf(JsonObject t) {
        String source = Proto.str(t, "source", Proto.SOURCE_NETEASE).trim().toLowerCase(Locale.ROOT);
        return source + ":" + Proto.str(t, "songId", "").trim();
    }

    private static String display(JsonObject t) {
        String n = Proto.str(t, "name", "未知歌曲");
        String a = Proto.str(t, "artist", "");
        return a == null || a.isBlank() ? n : n + " - " + a;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
