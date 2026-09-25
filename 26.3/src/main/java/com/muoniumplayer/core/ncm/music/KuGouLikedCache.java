package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.kugou.KuGouDevice;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 酷狗「我喜欢」的本地索引。
 *
 * <h3>为什么需要一份本地索引</h3>
 * <p>网易云有一个全量 id 表（{@code CloudMusic.likeList}），画收藏图标时直接查。
 * 酷狗没有对应的轻量接口：判断一首歌是否已在「我喜欢」里<b>只能</b>把整张歌单拉下来比对，
 * 而收藏图标是每一行都要画的，为它逐行触发网络请求显然不行。</p>
 *
 * <h3>冷启动问题的来龙去脉</h3>
 * <p>本类最初只有会话内的乐观状态：点了就记，取消就删。代价是<b>重启后所有已收藏的歌都显示成
 * 未收藏</b>，用户再点一次就是重复收藏。现在启动时先 {@link #restoreFromDisk()} 读上一次落盘的
 * 索引（不发请求，离线也对），登录或需要刷新时由 {@link #replaceWithServerIndex} 用服务端全量
 * 结果覆盖。落盘文件是<b>可再生缓存</b>：删掉只会退化成「图标初始不准」，不丢用户数据，
 * 所以放 {@link ConfigPaths#KUGOU_LIKED}（cache 目录）而不是配置目录。</p>
 *
 * <h3>按账号分键</h3>
 * <p>索引里记的是 {@code Music.getStableKey()}，而它不含账号信息。换账号时如果继续用旧文件，
 * 新账号会看到上一个账号的红心，所以文件里带 {@code account} 字段，读的时候对不上就当没有。</p>
 *
 * <h3>键为什么是稳定键而不是 hash</h3>
 * <p>UI 那侧的判定（{@code MusicWidget}）用的是 {@code contains(music.getStableKey())}，
 * 这个签名从建立起就没变过、也不能变。服务端全量索引只有 hash，
 * 所以 {@link #replaceWithServerIndex} 用 {@link Music#kuGouStableKey(String)} 换算一次。</p>
 */
public final class KuGouLikedCache {

    /**
     * 索引容量上限。
     *
     * <p>重到 {@code replaceWithServerIndex} 之后它不再只是「最近使用集合」，而是真·全量收藏，
     * 所以上限要能装下一个重度用户的收藏夹（五千首是常见量级）。超限时<b>只拒绝新增</b>，
     * 不再像早期版本那样整体清空——整体清空会把刚从服务端拉到的全量索引一起丢掉。</p>
     */
    private static final int MAX_ENTRIES = 20000;

    private static final Object LOCK = new Object();

    private static final Set<String> LIKED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 是否已经拿到过一份可信的全量索引（服务端或磁盘）。没有它时 {@link #contains} 只做乐观判定。 */
    private static volatile boolean indexed;
    /** 上一次读/写文件时对应的账号，用于换账号时自动失配。 */
    private static volatile String account = "";
    private static volatile boolean loadedFromDisk;

    private KuGouLikedCache() {
    }

    public static boolean contains(String stableKey) {
        if (stableKey == null) return false;
        if (!loadedFromDisk) restoreFromDisk();
        return LIKED.contains(stableKey);
    }

    public static void add(String stableKey) {
        if (stableKey == null) return;
        if (!ensureRoom()) return;
        LIKED.add(stableKey);
        persist();
    }

    public static void remove(String stableKey) {
        if (stableKey == null) return;
        if (!LIKED.remove(stableKey)) return;
        persist();
    }

    /** 索引是否已经建立过。{@code false} 意味着红心状态不可信，调用方不该据此显示「收藏失败」。 */
    public static boolean hasPersistedIndex() {
        return indexed;
    }

    /**
     * 用一份「完整」的已收藏集合整体替换当前状态。
     *
     * <p>只有在确实拿到了整张「我喜欢」歌单时才该调用——否则会把用户刚点的那几首抹掉。
     * 需要传 hash 的调用方用 {@link #replaceWithServerIndex}。</p>
     */
    public static void replace(Collection<String> stableKeys) {
        LIKED.clear();
        if (stableKeys != null) {
            for (String key : stableKeys) {
                if (key != null && LIKED.size() < MAX_ENTRIES) LIKED.add(key);
            }
        }
        indexed = true;
        persist();
    }

    /**
     * 用服务端全量收藏（一组 hash）重建索引。
     *
     * @param hashes {@code get_list_all_file_v3} 里那些条目的 hash，大小写都收
     */
    public static void replaceWithServerIndex(Collection<String> hashes) {
        Set<String> keys = new LinkedHashSet<String>();
        if (hashes != null) {
            for (String hash : hashes) {
                String key = Music.kuGouStableKey(hash);
                if (!key.isEmpty()) keys.add(key);
                if (keys.size() >= MAX_ENTRIES) break;
            }
        }
        replace(keys);
    }

    public static void clear() {
        synchronized (LOCK) {
            LIKED.clear();
            indexed = false;
            account = "";
            loadedFromDisk = true;
        }
        try {
            File file = ConfigPaths.KUGOU_LIKED;
            if (file.isFile()) file.delete();
        } catch (Throwable ignored) {
            // 删不掉不影响本次会话：内存状态已经清了。
        }
    }

    /**
     * 从磁盘恢复索引（不发请求）。
     *
     * <p>账号对不上时按「没有索引」处理：宁可红心初始不准，也不能把上一个账号的收藏画给这个账号。
     * 多次调用是幂等的，第二次之后直接返回。</p>
     */
    public static synchronized void restoreFromDisk() {
        loadedFromDisk = true;
        String currentAccount = KuGouDevice.userId();
        if (indexed && currentAccount.equals(account)) return;
        try {
            File file = ConfigPaths.KUGOU_LIKED;
            if (!file.isFile()) return;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(text);
            if (parsed == null || !parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();
            String stored = root.has("account") && !root.get("account").isJsonNull()
                    ? root.get("account").getAsString() : "";
            if (!stored.equals(currentAccount)) return;
            JsonArray keys = root.has("keys") && root.get("keys").isJsonArray()
                    ? root.get("keys").getAsJsonArray() : null;
            if (keys == null) return;
            LIKED.clear();
            for (JsonElement element : keys) {
                if (element == null || !element.isJsonPrimitive()) continue;
                String key = element.getAsString();
                if (key != null && !key.trim().isEmpty() && LIKED.size() < MAX_ENTRIES) LIKED.add(key.trim());
            }
            account = stored;
            indexed = root.has("indexed") && !root.get("indexed").isJsonNull()
                    && root.get("indexed").getAsBoolean();
        } catch (Throwable throwable) {
            System.err.println("[Music/KuGou] 收藏索引读取失败，按未建立处理: " + throwable.getMessage());
        }
    }

    /** 上限保护：满了就拒绝再装，并让调用方知道（返回 false）。 */
    private static boolean ensureRoom() {
        if (LIKED.size() < MAX_ENTRIES) return true;
        System.err.println("[Music/KuGou] 收藏索引已达 " + MAX_ENTRIES
                + " 条上限，本次乐观记录被丢弃（不影响服务端写入）");
        return false;
    }

    /** 把当前索引落盘。失败只影响下次启动的初始状态，不影响本次会话。 */
    private static void persist() {
        synchronized (LOCK) {
            try {
                File file = ConfigPaths.KUGOU_LIKED;
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    return;
                }
                JsonObject root = new JsonObject();
                account = KuGouDevice.userId();
                root.addProperty("account", account);
                root.addProperty("indexed", indexed);
                JsonArray keys = new JsonArray();
                for (String key : LIKED) keys.add(key);
                root.add("keys", keys);
                Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable throwable) {
                System.err.println("[Music/KuGou] 收藏索引写入失败: " + throwable.getMessage());
            }
        }
    }
}
