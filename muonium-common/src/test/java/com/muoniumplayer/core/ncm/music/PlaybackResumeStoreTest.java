package com.muoniumplayer.core.ncm.music;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「上次播放到哪」快照的行为测试：round-trip、损坏容错、边界 clamp。
 *
 * <p>这里的铁律是「文件是不可信输入」：任何一种坏法都只能换来 null 或逐条丢弃，
 * 绝不能抛出。恢复语义（往 CloudMusic 摆状态）不在这里测——那属于 CloudMusic
 * 的线程纪律，静态接线测试管「接没接」，这里管「存取对不对」。</p>
 */
class PlaybackResumeStoreTest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    @TempDir
    Path temp;

    @AfterEach
    void resetCaches() throws Exception {
        PlaybackResumeStore.flushPending();
        // 本类与 ConfigPaths 都按真实文件路径工作；测试用反射把 PLAYBACK_RESUME 指到临时目录，
        // 用完复位，别把临时路径漏进同 JVM 里后跑的其它测试。
        setFinalFile(ConfigPaths.class, "PLAYBACK_RESUME",
                new java.io.File(System.getProperty("java.io.tmpdir"), "playback_resume_absent.json"));
    }

    private static void setFinalFile(Class<?> owner, String name, java.io.File value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        // final static File 常量：Field.set 对 record/隐藏类之外的 static final 可通过 modifiers
        // 层直接写（JDK17+ 需要先把 modifiers 清掉）。
        sun.misc.Unsafe unsafe;
        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        } catch (Throwable unavailable) {
            throw new IllegalStateException("测试需要 sun.misc.Unsafe 来替换 ConfigPaths 常量", unavailable);
        }
        Object base = unsafe.staticFieldBase(field);
        long offset = unsafe.staticFieldOffset(field);
        unsafe.putObject(base, offset, value);
    }

    private void pointStoreAt(java.io.File file) throws Exception {
        setFinalFile(ConfigPaths.class, "PLAYBACK_RESUME", file);
    }

    // ─────────────────────────── round-trip ───────────────────────────

    @Test
    @DisplayName("写入后读回：队列、index、position、playMode 全部一致")
    void roundTrip() throws Exception {
        java.io.File file = temp.resolve("resume.json").toFile();
        pointStoreAt(file);

        List<Music> queue = new ArrayList<>();
        queue.add(makeMusic("NETEASE", 111L, "歌一"));
        queue.add(makeMusic("KUGOU", 222L, "歌二"));
        PlaybackResumeStore.noteTrackEvent(queue, 1, 83_000L, CloudMusic.PlayMode.Random, null);

        PlaybackResumeStore.Snapshot snapshot = PlaybackResumeStore.read();
        assertNotNull(snapshot, "刚写完必须读得回来");
        assertEquals(2, snapshot.queue.size());
        assertEquals("kugou_222", snapshot.queue.get(1).getStableKey());
        assertEquals(1, snapshot.index);
        assertEquals(83_000L, snapshot.positionMillis);
        assertEquals(CloudMusic.PlayMode.Random, snapshot.playMode);
        assertNull(snapshot.context);
    }

    @Test
    @DisplayName("FM/房间期间拒写：文件保持原样（没有 FM 可造，用房间外的空场景验证写入路径本身通）")
    void writeCreatesFile() throws Exception {
        java.io.File file = temp.resolve("resume.json").toFile();
        pointStoreAt(file);
        assertFalse(file.isFile(), "测试前提：文件还不存在");

        PlaybackResumeStore.noteTrackEvent(
                List.of(makeMusic("NETEASE", 1L, "x")), 0, 0L, CloudMusic.PlayMode.Sequential, null);
        // 注：本机没进一起听房间、也没开 FM，save 的两道闸放行，文件应当出现。
        assertTrue(file.isFile(), "普通场景写入必须落盘");
    }

    @Test
    @DisplayName("clear 删除快照文件；再读返回 null")
    void clearDeletes() throws Exception {
        java.io.File file = temp.resolve("resume.json").toFile();
        pointStoreAt(file);
        PlaybackResumeStore.noteTrackEvent(
                List.of(makeMusic("NETEASE", 1L, "x")), 0, 5_000L, CloudMusic.PlayMode.Sequential, null);
        assertTrue(file.isFile());

        PlaybackResumeStore.clear();
        assertFalse(file.exists(), "clear 必须真删文件");
        assertNull(PlaybackResumeStore.read());
    }

    // ─────────────────────────── 坏文件容错 ───────────────────────────

    @Test
    @DisplayName("JSON 损坏 / 版本不认识 / 空队列 → 一律 null，绝不抛出")
    void corruptInputsReturnNull() throws Exception {
        String[] broken = {
                "这不是 JSON{{{",
                "{}",
                "{\"version\":999,\"queue\":[]}",
                "{\"version\":1}",
                "{\"version\":1,\"queue\":[]}",
                "",
        };
        for (String content : broken) {
            java.io.File file = temp.resolve("broken.json").toFile();
            Files.writeString(file.toPath(), content);
            pointStoreAt(file);
            assertNull(PlaybackResumeStore.read(), "坏输入必须安静地当 null：\n" + content);
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    @DisplayName("index 越界/负值 clamp 回有效区间；position 负值按 0")
    void outOfRangeClamped() throws Exception {
        java.io.File file = temp.resolve("clamp.json").toFile();
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        com.google.gson.JsonArray tracks = new com.google.gson.JsonArray();
        tracks.add(LocalPlaylistStore.serializeTrack(makeMusic("NETEASE", 7L, "唯一的歌")));
        tracks.add(LocalPlaylistStore.serializeTrack(makeMusic("NETEASE", 8L, "第二首")));
        root.add("queue", tracks);
        root.addProperty("index", 99);
        root.addProperty("positionMillis", -50);
        root.addProperty("playMode", "Sequential");
        JsonConfigStorage.writeObject(file, GSON, root);
        pointStoreAt(file);

        PlaybackResumeStore.Snapshot snapshot = PlaybackResumeStore.read();
        assertNotNull(snapshot);
        assertEquals(1, snapshot.index, "index 99 必须被夹回最后一格");
        assertEquals(0L, snapshot.positionMillis);
    }

    @Test
    @DisplayName("队列逐条容错：中间坏一条，其余照常恢复")
    void perTrackTolerance() throws Exception {
        java.io.File file = temp.resolve("tolerant.json").toFile();
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        com.google.gson.JsonArray tracks = new com.google.gson.JsonArray();
        tracks.add(LocalPlaylistStore.serializeTrack(makeMusic("NETEASE", 1L, "好歌")));
        tracks.add(GSON.toJsonTree("一个字符串，不是歌"));
        tracks.add(LocalPlaylistStore.serializeTrack(makeMusic("NETEASE", 2L, "另一首好歌")));
        root.add("queue", tracks);
        root.addProperty("index", 1);
        JsonConfigStorage.writeObject(file, GSON, root);
        pointStoreAt(file);

        PlaybackResumeStore.Snapshot snapshot = PlaybackResumeStore.read();
        assertNotNull(snapshot, "坏一条不能否掉整份快照");
        assertEquals(2, snapshot.queue.size(), "坏条目被丢掉，好的两条留下");
        assertEquals(1, snapshot.index, "index 1 在 clamp 后仍指向第二条好歌");
        assertEquals("netease_2", snapshot.queue.get(1).getStableKey());
    }

    @Test
    @DisplayName("队列超上限截断到 MAX_QUEUE")
    void queueCapped() throws Exception {
        java.io.File file = temp.resolve("capped.json").toFile();
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        com.google.gson.JsonArray tracks = new com.google.gson.JsonArray();
        List<Music> big = new ArrayList<>();
        for (long id = 1; id <= PlaybackResumeStore.MAX_QUEUE + 50; id++) {
            Music music = makeMusic("NETEASE", id, "歌" + id);
            big.add(music);
            tracks.add(LocalPlaylistStore.serializeTrack(music));
        }
        root.add("queue", tracks);
        root.addProperty("index", 0);
        JsonConfigStorage.writeObject(file, GSON, root);
        pointStoreAt(file);

        PlaybackResumeStore.Snapshot snapshot = PlaybackResumeStore.read();
        assertNotNull(snapshot);
        assertEquals(PlaybackResumeStore.MAX_QUEUE, snapshot.queue.size(), "读回侧按上限截断");
    }

    @Test
    void queuedWritesKeepTheLatestIntentAndClearWinsOverEarlierSaves() throws Exception {
        java.io.File file = temp.resolve("queued.json").toFile();
        pointStoreAt(file);
        List<Music> queue = new ArrayList<>();
        queue.add(makeMusic("NETEASE", 42L, "latest"));
        PlaybackResumeStore.requestSave(queue, 0, 1_000L, CloudMusic.PlayMode.Sequential, null, 0L);
        PlaybackResumeStore.requestSave(queue, 0, 9_000L, CloudMusic.PlayMode.Random, null, 0L);
        queue.clear();
        PlaybackResumeStore.flushPending();
        PlaybackResumeStore.Snapshot snapshot = PlaybackResumeStore.read();
        assertNotNull(snapshot);
        assertEquals(1, snapshot.queue.size(), "提交后修改源列表不能改变已捕获队列");
        assertEquals(9_000L, snapshot.positionMillis);
        assertEquals(CloudMusic.PlayMode.Random, snapshot.playMode);

        PlaybackResumeStore.requestSave(snapshot.queue, 0, 12_000L, snapshot.playMode, null, 0L);
        PlaybackResumeStore.requestClear();
        PlaybackResumeStore.flushPending();
        assertFalse(file.exists(), "清空不能被更早的排队保存复活");

        PlaybackResumeStore.requestSave(snapshot.queue, 0, 25_000L, snapshot.playMode, null, 0L);
        PlaybackResumeStore.flushPending();
        assertEquals(25_000L, PlaybackResumeStore.read().positionMillis,
                "清空之后的新播放必须能重新保存");
    }

    @Test
    void throttledQueuedSaveDoesNotOverwriteTheLatestTrackEvent() throws Exception {
        pointStoreAt(temp.resolve("throttled.json").toFile());
        List<Music> queue = List.of(makeMusic("NETEASE", 7L, "track"));
        PlaybackResumeStore.requestSave(queue, 0, 3_000L, CloudMusic.PlayMode.Sequential, null, 0L);
        PlaybackResumeStore.requestSave(queue, 0, 1_000L, CloudMusic.PlayMode.Sequential,
                null, PlaybackResumeStore.MIN_INTERVAL_MILLIS);
        PlaybackResumeStore.flushPending();
        assertEquals(3_000L, PlaybackResumeStore.read().positionMillis);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static Music makeMusic(String source, long id, String name) {
        Music music = new Music(name, name, "", id, java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), null, 200_000L, 0L, 0L, java.util.Collections.emptyList());
        music.setSource(MusicPlatform.valueOf(source));
        return music;
    }
}
