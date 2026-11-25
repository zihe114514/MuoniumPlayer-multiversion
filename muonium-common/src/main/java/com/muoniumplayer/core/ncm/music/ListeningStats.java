package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 本机收听统计：主页「本周时长 / 新增收藏 / 连续收听」三个数和「反复聆听」那一栏的数据源。
 *
 * <p>计时挂在 {@link CloudMusic} 的播放会话上，口径与 {@link NeteasePlaybackHistoryReporter} 相同：
 * 会话从音频真正起播开始，每次采样只累计未暂停的那段，单步最多 1.5 秒（客户端卡住的一帧不能算成
 * 收听）。一首歌在一次会话里听满 30 秒或一半时长（取小者）才算播放了一次。</p>
 *
 * <p>只落在本机 {@link ConfigPaths#LISTENING_STATS}，不上传。写盘在后台线程，内容是锁内拍的快照；
 * 日期只留最近 {@link #MAX_DAYS} 天，曲目只留 {@link #MAX_TRACKS} 首（按次数、最近播放排序后截断）。</p>
 */
public final class ListeningStats {

    private static final long MAXIMUM_OBSERVED_STEP_NANOS = 1_500_000_000L;
    private static final long COUNT_THRESHOLD_MILLIS = 30_000L;
    /** 「连续收听」里一天至少要听这么多秒才算这一天听过。 */
    private static final long STREAK_DAY_SECONDS = 60L;
    private static final int MAX_DAYS = 120;
    private static final int MAX_TRACKS = 300;

    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final File FILE = ConfigPaths.LISTENING_STATS;

    /** 磁盘上的记录已经读进内存。volatile：主页读数先在锁外看它，没读完就不进锁（不等磁盘）。 */
    private static volatile boolean loaded;
    private static volatile boolean loading;
    /** 日期（ISO）→ 当天实际收听秒数。 */
    private static final TreeMap<String, Long> LISTEN_SECONDS = new TreeMap<>();
    /** 日期（ISO）→ 当天新增收藏数。 */
    private static final TreeMap<String, Integer> NEW_LIKES = new TreeMap<>();
    /** 曲目稳定键 → 播放记录。 */
    private static final LinkedHashMap<String, TrackRecord> TRACKS = new LinkedHashMap<>();
    private static Session session;

    private ListeningStats() {
    }

    /** 一首歌的播放次数与最后播放时间。 */
    public static final class TrackRecord {
        private final Music music;
        private int count;
        private long lastPlayed;

        private TrackRecord(Music music, int count, long lastPlayed) {
            this.music = music;
            this.count = count;
            this.lastPlayed = lastPlayed;
        }

        public Music getMusic() {
            return this.music;
        }

        public int getCount() {
            return this.count;
        }
    }

    private static final class Session {
        private final Music song;
        private final AudioPlayer player;
        private long lastObservedNanos;
        private long playedNanos;

        private Session(Music song, AudioPlayer player, long now) {
            this.song = song;
            this.player = player;
            this.lastObservedNanos = now;
        }
    }

    // ------------------------------------------------------------------
    // 播放会话（由 CloudMusic 调用）
    // ------------------------------------------------------------------

    /** 音频真正起播后开始一次会话；上一首若没有正常收尾，先按「被替换」结掉。 */
    public static void start(Music song, AudioPlayer player) {
        if (song == null || player == null) return;
        synchronized (LOCK) {
            finishLocked(null);
            session = new Session(song, player, System.nanoTime());
        }
    }

    /** 采样正在播放的会话；暂停期间不累计。 */
    public static void observe(AudioPlayer player) {
        if (player == null) return;
        synchronized (LOCK) {
            if (session != null && session.player == player) sampleLocked(session, System.nanoTime());
        }
    }

    /** 结束会话：把这次的收听时长记到今天，够阈值就给这首歌记一次播放。 */
    public static void finish(AudioPlayer player) {
        synchronized (LOCK) {
            finishLocked(player);
        }
    }

    /** 收藏了一首歌（只记数量，不记是哪首）。在客户端线程被调用，所以读盘与记账都放到后台。 */
    public static void noteLiked() {
        final String day = today();
        MultiThreadingUtil.runAsync(() -> {
            synchronized (LOCK) {
                loadLocked();
                NEW_LIKES.merge(day, 1, Integer::sum);
                scheduleSaveLocked();
            }
        });
    }

    /**
     * 主页读数的前置：记录还没读进内存就在后台读一次，这一帧先给空读数。
     * 主页是在渲染线程上每帧读的，不能在那里等磁盘。
     *
     * @return 记录是否已经可读
     */
    private static boolean readyForReading() {
        if (loaded) return true;
        if (!loading) {
            loading = true;
            MultiThreadingUtil.runAsync(() -> {
                try {
                    synchronized (LOCK) {
                        loadLocked();
                    }
                } finally {
                    loading = false;
                }
            });
        }
        return false;
    }

    private static void sampleLocked(Session active, long now) {
        long elapsed = Math.max(0L, now - active.lastObservedNanos);
        active.lastObservedNanos = now;
        if (!active.player.isPausing()) {
            active.playedNanos += Math.min(elapsed, MAXIMUM_OBSERVED_STEP_NANOS);
        }
    }

    private static void finishLocked(AudioPlayer player) {
        Session active = session;
        if (active == null || (player != null && active.player != player)) return;
        sampleLocked(active, System.nanoTime());
        session = null;
        long playedMillis = active.playedNanos / 1_000_000L;
        if (playedMillis < 1_000L) return;
        loadLocked();
        LISTEN_SECONDS.merge(today(), playedMillis / 1_000L, Long::sum);
        long duration = active.song.getDuration();
        long threshold = duration > 0 ? Math.min(COUNT_THRESHOLD_MILLIS, duration / 2) : COUNT_THRESHOLD_MILLIS;
        if (playedMillis >= threshold) {
            String key = active.song.getStableKey();
            if (key != null && !key.isEmpty()) {
                TrackRecord record = TRACKS.remove(key);
                if (record == null) record = new TrackRecord(active.song, 0, 0L);
                record.count++;
                record.lastPlayed = System.currentTimeMillis();
                // 重新插到末尾：LinkedHashMap 的顺序就是「最近一次播放」的顺序，截断时用得上。
                TRACKS.put(key, record);
            }
        }
        scheduleSaveLocked();
    }

    // ------------------------------------------------------------------
    // 读数（主页）
    // ------------------------------------------------------------------

    /** 本周（周一起）实际收听小时数。 */
    public static double weekListenHours() {
        if (!readyForReading()) return 0.0;
        synchronized (LOCK) {
            String monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            long seconds = 0L;
            for (Map.Entry<String, Long> day : LISTEN_SECONDS.tailMap(monday, true).entrySet()) {
                seconds += day.getValue();
            }
            Session active = session;
            if (active != null) seconds += active.playedNanos / 1_000_000_000L;
            return seconds / 3600.0;
        }
    }

    /** 本周（周一起）新增收藏数。 */
    public static int weekNewLikes() {
        if (!readyForReading()) return 0;
        synchronized (LOCK) {
            String monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            int likes = 0;
            for (Map.Entry<String, Integer> day : NEW_LIKES.tailMap(monday, true).entrySet()) {
                likes += day.getValue();
            }
            return likes;
        }
    }

    /** 截至今天（今天还没听就从昨天算起）连续收听的天数。 */
    public static int streakDays() {
        if (!readyForReading()) return 0;
        synchronized (LOCK) {
            LocalDate day = LocalDate.now();
            if (secondsOn(day) < STREAK_DAY_SECONDS) day = day.minusDays(1);
            int streak = 0;
            while (secondsOn(day) >= STREAK_DAY_SECONDS && streak < MAX_DAYS) {
                streak++;
                day = day.minusDays(1);
            }
            return streak;
        }
    }

    /** 播放次数最多的几首（次数相同按最近播放）。 */
    public static List<TrackRecord> topTracks(int limit) {
        if (!readyForReading()) return new ArrayList<>();
        synchronized (LOCK) {
            List<TrackRecord> records = new ArrayList<>(TRACKS.values());
            records.sort(Comparator.comparingInt((TrackRecord record) -> record.count).reversed()
                    .thenComparing(Comparator.comparingLong((TrackRecord record) -> record.lastPlayed).reversed()));
            return records.size() <= limit ? records : new ArrayList<>(records.subList(0, Math.max(0, limit)));
        }
    }

    private static long secondsOn(LocalDate day) {
        Long seconds = LISTEN_SECONDS.get(day.toString());
        long value = seconds == null ? 0L : seconds;
        if (day.equals(LocalDate.now()) && session != null) value += session.playedNanos / 1_000_000_000L;
        return value;
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    private static void loadLocked() {
        if (loaded) return;
        try {
            readFileLocked();
        } finally {
            // 读完（或读坏）之后才放行锁外的读数：它们看到 true 时内存里已经是完整记录。
            loaded = true;
        }
    }

    private static void readFileLocked() {
        if (!FILE.exists()) return;
        try {
            JsonObject root = JsonConfigStorage.readObject(FILE, GSON);
            if (root == null) return;
            if (root.has("listen") && root.get("listen").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("listen").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) LISTEN_SECONDS.put(entry.getKey(), entry.getValue().getAsLong());
                }
            }
            if (root.has("likes") && root.get("likes").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("likes").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) NEW_LIKES.put(entry.getKey(), entry.getValue().getAsInt());
                }
            }
            if (root.has("tracks") && root.get("tracks").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("tracks")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject item = element.getAsJsonObject();
                    Music music = LocalPlaylistStore.deserializeTrack(item.get("music"));
                    if (music == null || music.getStableKey() == null || music.getStableKey().isEmpty()) continue;
                    int count = item.has("count") ? item.get("count").getAsInt() : 0;
                    long last = item.has("last") ? item.get("last").getAsLong() : 0L;
                    TRACKS.put(music.getStableKey(), new TrackRecord(music, count, last));
                }
            }
        } catch (Exception malformed) {
            // 统计文件坏了不影响播放：从空记录重新开始，并在日志里留下原因。旧文件下次保存时被覆盖。
            System.err.println("[MuoniumPlayer] 收听统计读取失败，将从空记录开始: " + malformed);
            LISTEN_SECONDS.clear();
            NEW_LIKES.clear();
            TRACKS.clear();
        }
    }

    private static void scheduleSaveLocked() {
        trimLocked();
        final JsonObject snapshot = snapshotLocked();
        MultiThreadingUtil.runAsync(() -> {
            synchronized (FILE) {
                try {
                    JsonConfigStorage.writeObject(FILE, GSON, snapshot);
                } catch (Exception failure) {
                    System.err.println("[MuoniumPlayer] 收听统计保存失败: " + failure);
                }
            }
        });
    }

    private static void trimLocked() {
        while (LISTEN_SECONDS.size() > MAX_DAYS) LISTEN_SECONDS.pollFirstEntry();
        while (NEW_LIKES.size() > MAX_DAYS) NEW_LIKES.pollFirstEntry();
        if (TRACKS.size() <= MAX_TRACKS) return;
        List<Map.Entry<String, TrackRecord>> entries = new ArrayList<>(TRACKS.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, TrackRecord> entry) -> entry.getValue().count)
                .thenComparingLong(entry -> entry.getValue().lastPlayed));
        for (int index = 0; index < entries.size() - MAX_TRACKS; index++) {
            TRACKS.remove(entries.get(index).getKey());
        }
    }

    private static JsonObject snapshotLocked() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject listen = new JsonObject();
        for (Map.Entry<String, Long> entry : LISTEN_SECONDS.entrySet()) listen.addProperty(entry.getKey(), entry.getValue());
        root.add("listen", listen);
        JsonObject likes = new JsonObject();
        for (Map.Entry<String, Integer> entry : NEW_LIKES.entrySet()) likes.addProperty(entry.getKey(), entry.getValue());
        root.add("likes", likes);
        JsonArray tracks = new JsonArray();
        for (TrackRecord record : TRACKS.values()) {
            JsonObject item = new JsonObject();
            item.add("music", LocalPlaylistStore.serializeTrack(record.music));
            item.addProperty("count", record.count);
            item.addProperty("last", record.lastPlayed);
            tracks.add(item);
        }
        root.add("tracks", tracks);
        return root;
    }

    /** 测试用：清空内存状态，下一次读数重新从磁盘加载。 */
    static void resetForTest() {
        synchronized (LOCK) {
            loaded = false;
            session = null;
            LISTEN_SECONDS.clear();
            NEW_LIKES.clear();
            TRACKS.clear();
        }
    }

    /** 测试用：不经过播放器直接记一次收听。 */
    static void recordForTest(Music song, long playedMillis, String day) {
        synchronized (LOCK) {
            loaded = true;
            LISTEN_SECONDS.merge(day, playedMillis / 1_000L, Long::sum);
            TrackRecord record = TRACKS.remove(song.getStableKey());
            if (record == null) record = new TrackRecord(song, 0, 0L);
            record.count++;
            record.lastPlayed = System.currentTimeMillis();
            TRACKS.put(song.getStableKey(), record);
        }
    }

    static List<String> trackKeysForTest() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(TRACKS.keySet()));
        }
    }
}
