package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「上次播放到哪」的会话快照：重启游戏后队列、当前歌、播放模式、歌单上下文与播放位置都能
 * 就位（但不出声——续播由用户点播放触发），落在 {@link ConfigPaths#PLAYBACK_RESUME}。
 *
 * <h2>这份文件在信任模型里是「不可信输入」</h2>
 * 它由播放线程按事件 + 心跳写、进程可能随时被强杀（GameRestart / 崩溃 / 拔电源），
 * 所以<b>读回来的每一样东西都要重新校验</b>：version 不认识、队列空、index 越界、
 * 单条曲目反序列化失败，全部走「丢弃这一份/这一条」，绝不抛给调用方——
 * 记忆功能损坏的代价只是「这次启动不恢复」，绝不能挡住播放器打开或播放。
 *
 * <h2>写侧纪律</h2>
 * <ul>
 *   <li><b>只在播放边界与心跳写</b>：切歌、暂停、seek（去抖）、播放中的 15 秒心跳、关闭钩子。
 *       任何写入都 catch(Throwable)，写盘在 {@link JsonConfigStorage} 的原子写（临时文件 +
 *       ATOMIC_MOVE）之上，强杀最多丢最后一次心跳，文件本体不会半截。</li>
 *   <li><b>FM 会话拒写</b>：FM 队列是一次性的接口批次，把它记下来下次恢复等于复活一个
 *       用户没要求保存的会话；FM 期间跳过，保住的永远是上一个正常队列的记忆。</li>
 *   <li><b>一起听房间拒写</b>：房里的队列与推进由服务端控制，本地快照描述不了它。</li>
 *   <li><b>队列上限</b>：超出 {@link #MAX_QUEUE} 截断，index 一并夹回有效范围。</li>
 * </ul>
 *
 * <h2>恢复语义（与 CloudMusic 的分工）</h2>
 * 这里只负责「文件 ↔ 快照对象」这一层：读文件、校验、反序列化。往 {@code CloudMusic} 的
 * 静态字段里摆、续播偏移的消费，全部在 {@code CloudMusic.restoreLastSession()}——那边才是
 * 知道线程纪律的地方。本类不创建线程、不碰音频、不发网络请求。
 */
public final class PlaybackResumeStore {

    /** 快照格式版本。读到更高版本（降级安装）时不认识就不恢复，绝不猜。 */
    static final int VERSION = 1;

    /** 队列快照上限。超过这个规模的队列多半是异常状态，截断比原样收下安全。 */
    static final int MAX_QUEUE = 1_000;

    /** 播放心跳间隔：播放中每这么久把 position 刷新一次，强杀时最多丢这一段。 */
    static final long HEARTBEAT_MILLIS = 15_000L;

    /** 终态事件（切歌/暂停/seek）之外的最小落盘间隔；seek 用它去抖。 */
    static final long MIN_INTERVAL_MILLIS = 5_000L;

    private static final Object LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static boolean loaded;
    /** 心跳/去抖共用的「上次落盘」墙钟；0 表示还没有过落盘。 */
    private static long lastSavedAtMillis;

    private PlaybackResumeStore() {
    }

    /** 一次可恢复的会话快照。字段语义见各捕获点；{@code context} 为 null 表示队列不来自歌单。 */
    public static final class Snapshot {
        public final List<Music> queue;
        public final int index;
        public final long positionMillis;
        public final CloudMusic.PlayMode playMode;
        public final PlayList context;

        Snapshot(List<Music> queue, int index, long positionMillis,
                 CloudMusic.PlayMode playMode, PlayList context) {
            this.queue = queue;
            this.index = index;
            this.positionMillis = positionMillis;
            this.playMode = playMode;
            this.context = context;
        }
    }

    // ─────────────────────────── 写 ───────────────────────────

    /**
     * 播放边界事件：换歌、暂停/恢复、seek。无条件落盘（仍然全 catch）。
     * {@code positionMillis} 传调用方读到的当前位置；负数按 0 处理。
     */
    public static void noteTrackEvent(List<Music> queue, int index, long positionMillis,
                                      CloudMusic.PlayMode playMode, PlayList context) {
        save(queue, index, positionMillis, playMode, context);
    }

    /**
     * seek 去抖入口：拖进度条会连发很多次，只有距上次落盘超过 {@link #MIN_INTERVAL_MILLIS}
     * 才真的写。终态事件（切歌/暂停）走 {@link #noteTrackEvent}，不受去抖限制。
     */
    public static void noteSeekThrottled(List<Music> queue, int index, long positionMillis,
                                         CloudMusic.PlayMode playMode, PlayList context) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (lastSavedAtMillis > 0L && now - lastSavedAtMillis < MIN_INTERVAL_MILLIS) return;
            save(queue, index, positionMillis, playMode, context);
        }
    }

    /**
     * 播放中的心跳。距上次落盘不足 {@link #HEARTBEAT_MILLIS} 就跳过——它要跟播线的
     * 10ms tick 共处，大部分 tick 都必须是纯内存判断后立刻返回。
     */
    public static void noteHeartbeat(List<Music> queue, int index, long positionMillis,
                                     CloudMusic.PlayMode playMode, PlayList context) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (lastSavedAtMillis > 0L && now - lastSavedAtMillis < HEARTBEAT_MILLIS) return;
            save(queue, index, positionMillis, playMode, context);
        }
    }

    /**
     * 用户清空队列 / 停止 / 登出时把快照一起删掉：这些动作的语义就是「我不在听了」，
     * 下次启动复活一条用户亲手清掉的队列是惊吓而不是便利。文件不存在就算了。
     */
    public static void clear() {
        synchronized (LOCK) {
            lastSavedAtMillis = 0L;
            try {
                java.nio.file.Files.deleteIfExists(ConfigPaths.PLAYBACK_RESUME.toPath());
            } catch (Throwable ignored) {
                // 删不掉顶多下次启动多恢复一次；写路径全 catch 的地方不需要这里抛。
            }
        }
    }

    private static void save(List<Music> queue, int index, long positionMillis,
                             CloudMusic.PlayMode playMode, PlayList context) {
        // FM / 一起听房间不是可恢复的会话：这两道闸放在 save 最里面而不是每个调用点，
        // 漏掉一个调用点也不会把不可恢复的队列写下去。
        if (PlaybackResumeStore.personalFmActive() || MpPartyClient.inRoom()) return;
        if (playMode == null) playMode = CloudMusic.PlayMode.Sequential;
        long position = Math.max(0L, positionMillis);
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", VERSION);
            root.addProperty("savedAt", System.currentTimeMillis());
            root.addProperty("index", Math.max(0, index));
            root.addProperty("positionMillis", position);
            root.addProperty("playMode", playMode.name());
            JsonArray tracks = new JsonArray();
            List<Music> snapshot = queue == null ? null : new ArrayList<>(queue);
            int count = 0;
            if (snapshot != null) {
                for (Music music : snapshot) {
                    if (music == null) continue;
                    if (count >= MAX_QUEUE) break;
                    tracks.add(LocalPlaylistStore.serializeTrack(music));
                    count++;
                }
            }
            root.add("queue", tracks);
            if (context != null) {
                JsonObject ctx = new JsonObject();
                ctx.addProperty("id", context.getId());
                ctx.addProperty("name", context.getName() == null ? "" : context.getName());
                ctx.addProperty("count", context.getCount());
                MusicPlatform platform = context.getPlatform();
                ctx.addProperty("platform", platform == null ? "" : platform.name());
                String platformPlaylistId = context.getPlatformPlaylistId();
                if (platformPlaylistId != null && !platformPlaylistId.isEmpty()) {
                    ctx.addProperty("platformPlaylistId", platformPlaylistId);
                }
                ctx.addProperty("local", context.isLocalPlaylist());
                root.add("context", ctx);
            }
            JsonConfigStorage.writeObject(ConfigPaths.PLAYBACK_RESUME, GSON, root);
            lastSavedAtMillis = System.currentTimeMillis();
        } catch (Throwable ignored) {
            // 快照写失败不影响播放；下次事件/心跳会再试。
        }
    }

    /** FM 判定收口在这里，save 的两道闸才能与 CloudMusic 的语义同步更新。 */
    private static boolean personalFmActive() {
        try {
            return CloudMusic.isPersonalFmActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 读快照。文件缺失、损坏、版本不认识、队列校验不过——一律返回 {@code null}，
     * 由调用方安静地当作「没有可恢复的会话」。
     */
    public static Snapshot read() {
        try {
            File file = ConfigPaths.PLAYBACK_RESUME;
            if (!file.isFile()) return null;
            JsonObject root = JsonConfigStorage.readObject(file, GSON);
            if (root == null) return null;
            if (!root.has("version") || root.get("version").isJsonNull()) return null;
            int version;
            try {
                version = root.get("version").getAsInt();
            } catch (Throwable malformed) {
                return null;
            }
            if (version != VERSION) return null;

            List<Music> queue = new ArrayList<>();
            if (root.has("queue") && root.get("queue").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("queue")) {
                    if (queue.size() >= MAX_QUEUE) break;
                    Music music = LocalPlaylistStore.deserializeTrack(element);
                    if (music != null) queue.add(music);
                }
            }
            if (queue.isEmpty()) return null;

            int index = readIntClamped(root, "index", 0, 0, queue.size() - 1);
            long position = Math.max(0L, readLong(root, "positionMillis", 0L));

            CloudMusic.PlayMode playMode = CloudMusic.PlayMode.Sequential;
            String modeName = string(root, "playMode");
            if (modeName != null) {
                try {
                    playMode = CloudMusic.PlayMode.valueOf(modeName);
                } catch (Throwable unknown) {
                    // 不认识的模式回落顺序播放：比拒掉整份快照好。
                }
            }

            return new Snapshot(queue, index, position, playMode, readContext(root));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 歌单上下文壳：只带身份四元组（platform / id / platformPlaylistId / 名字与数量），
     * 不带曲目——曲目由 UI 已有的懒加载链路自己拉（这与「打开一张歌单」完全同构）。
     * 任何字段读不出来就整体放弃上下文（恢复成「无来源队列」），不猜。
     */
    private static PlayList readContext(JsonObject root) {
        try {
            if (!root.has("context") || !root.get("context").isJsonObject()) return null;
            JsonObject ctx = root.getAsJsonObject("context");
            long id = readLong(ctx, "id", -1L);
            String platformName = string(ctx, "platform");
            if (id < 0L || platformName == null || platformName.isEmpty()) return null;
            MusicPlatform platform;
            try {
                platform = MusicPlatform.valueOf(platformName);
            } catch (Throwable unknown) {
                return null;
            }
            PlayList playlist = new PlayList(id, string(ctx, "name"), "", readIntClamped(ctx, "count", 0, 0, Integer.MAX_VALUE), 0L, null, "", 0L);
            playlist.setPlatform(platform);
            String platformPlaylistId = string(ctx, "platformPlaylistId");
            if (platformPlaylistId != null && !platformPlaylistId.isEmpty()) {
                playlist.setPlatformPlaylistId(platformPlaylistId);
            }
            if (ctx.has("local") && !ctx.get("local").isJsonNull() && ctx.get("local").getAsBoolean()) {
                playlist.setLocalPlaylist(true);
            }
            return playlist;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private static int readIntClamped(JsonObject root, String key, int fallback, int min, int max) {
        try {
            if (!root.has(key) || root.get(key).isJsonNull()) return fallback;
            return Math.max(min, Math.min(max, root.get(key).getAsInt()));
        } catch (Throwable malformed) {
            return fallback;
        }
    }

    private static long readLong(JsonObject root, String key, long fallback) {
        try {
            if (!root.has(key) || root.get(key).isJsonNull()) return fallback;
            return root.get(key).getAsLong();
        } catch (Throwable malformed) {
            return fallback;
        }
    }

    private static String string(JsonObject root, String key) {
        try {
            if (!root.has(key) || root.get(key).isJsonNull()) return null;
            String value = root.get(key).getAsString();
            return value.isEmpty() ? null : value;
        } catch (Throwable malformed) {
            return null;
        }
    }
}
