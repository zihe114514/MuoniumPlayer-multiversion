package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 歌词时间轴的落盘缓存与播放预取。
 *
 * <p>四个逐字歌词源（AMLL / QQ QRC / 酷狗 KRC / 汽水）各自在服务内部有内存缓存，但进程一重启就全部
 * 清空，于是每次开播放器听同一首歌都要重走一遍「搜索 → 打分 → 下载 → 解密 → 解析」。弱网下这段
 * 链路要几秒，而歌词区在这几秒里是空的。</p>
 *
 * <h3>为什么缓存的是时间轴而不是原始文本</h3>
 * <p>Folia 缓存的是提供商返回的原始歌词文本，因为它每一路都拿得到文本。本项目不一样：AMLL / QQ /
 * 酷狗 / 汽水的逐字结果都是各服务的私有解析产物，返回给上层的只有 {@code List<LyricLine>}，
 * 回不到原始文本。这里把最终时间轴序列化成 JSON 落盘，是唯一能覆盖全部四路的做法。</p>
 *
 * <p>格式带 {@code version}，解析失败、字段缺失、版本不认识都当缓存未命中处理——缓存永远不能成为
 * 一个能把歌词卡死的新故障点。</p>
 *
 * <h3>预取</h3>
 * <p>切歌时顺便把队列里下一首的歌词解析好落盘。走的是与正常加载<b>完全相同</b>的那条链路，
 * 所以预取结果与真实加载结果一致，不存在"预取的歌词和实际用的不一样"。</p>
 */
final class LyricCacheStore {

    /** 缓存格式版本。任何字段语义变化都要 +1，旧版本会被当成未命中。 */
    private static final int FORMAT_VERSION = 1;

    /** 缓存文件上限。一首歌大约 5~15 KB，2000 首约 20 MB。 */
    private static final int MAX_ENTRIES = 2000;

    /** 每写这么多次才整理一次目录，避免每首歌都 listFiles。 */
    private static final int PRUNE_INTERVAL = 128;

    private static final AtomicInteger WRITES_SINCE_PRUNE = new AtomicInteger();

    /** 预取去重：同一首已经在预取中就不再起第二个线程。 */
    private static final Object PREFETCH_LOCK = new Object();
    private static String prefetchingKey = "";

    private LyricCacheStore() {
    }

    // ── 读 / 写 ─────────────────────────────────────────────────────────────

    /** 写入一首歌的时间轴。失败一律静默——缓存写不进去不影响这次播放。 */
    static void store(String stableKey, List<LyricLine> lines) {
        if (stableKey == null || stableKey.trim().isEmpty() || lines == null || lines.isEmpty()) return;
        try {
            File directory = ConfigPaths.LYRIC_CACHE;
            if (!directory.isDirectory() && !directory.mkdirs()) return;

            JsonObject root = serialize(stableKey, lines);
            File target = fileFor(stableKey);
            // 先写临时文件再原子替换：写到一半被杀不会留下一个半截 JSON 被当成缓存命中。
            File temporary = new File(directory, target.getName() + ".tmp");
            Files.write(temporary.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            if (WRITES_SINCE_PRUNE.incrementAndGet() >= PRUNE_INTERVAL) {
                WRITES_SINCE_PRUNE.set(0);
                prune();
            }
        } catch (Throwable failure) {
            System.err.println("[Music/LyricCache] 写入失败 " + stableKey + ": " + failure.getMessage());
        }
    }

    /**
     * 读一首歌的缓存。
     *
     * @return 未命中、版本不符、内容损坏时返回空列表；调用方据此回退到常规加载链
     */
    static List<LyricLine> load(String stableKey) {
        if (stableKey == null || stableKey.trim().isEmpty()) return Collections.emptyList();
        File source = fileFor(stableKey);
        if (!source.isFile()) return Collections.emptyList();

        try {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(text);
            if (parsed == null || !parsed.isJsonObject()) return Collections.emptyList();
            JsonObject root = parsed.getAsJsonObject();
            if (intOf(root, "version", -1) != FORMAT_VERSION) return Collections.emptyList();
            // 哈希撞了不要紧,但绝不能把别的歌的歌词交出去。
            if (!stableKey.equals(stringOf(root, "key"))) return Collections.emptyList();
            return deserialize(root);
        } catch (Throwable failure) {
            // 读坏了就当没有,顺手删掉,免得每次加载都踩一次。
            invalidate(stableKey);
            return Collections.emptyList();
        }
    }

    /** 删掉一首歌的缓存。手动「重取歌词」用它把上一轮结果清干净。 */
    static void invalidate(String stableKey) {
        if (stableKey == null || stableKey.trim().isEmpty()) return;
        File source = fileFor(stableKey);
        if (source.isFile() && !source.delete()) {
            System.err.println("[Music/LyricCache] 删除失败 " + source.getName());
        }
    }

    // ── 预取 ────────────────────────────────────────────────────────────────

    /**
     * 预取播放队列里当前曲目的下一首。
     *
     * <p>只在后台线程调用，自身会再起一条低优先级守护线程，所以调用方（歌词加载线程）不会被拖住。</p>
     *
     * @param queue        播放队列，可以为 null
     * @param currentIndex 当前曲目在队列里的下标；越界或已在队尾时什么都不做
     */
    static void prefetchNext(List<Music> queue, int currentIndex) {
        if (queue == null || currentIndex < 0 || currentIndex + 1 >= queue.size()) return;
        Music next = queue.get(currentIndex + 1);
        if (next == null) return;

        final String key = next.getStableKey();
        if (key == null || key.isEmpty()) return;
        if (fileFor(key).isFile()) return;

        synchronized (PREFETCH_LOCK) {
            if (key.equals(prefetchingKey)) return;
            prefetchingKey = key;
        }

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 与正常加载完全同一条链路:预取到的歌词和真正轮到这首歌时用的是同一份。
                    LyricLoadService.LyricLoadResult result =
                            LyricLoadService.load(next, next.getId(), 0L, null);
                    if (result != null && result.lines != null && !result.lines.isEmpty()) {
                        store(key, result.lines);
                    }
                } catch (Throwable failure) {
                    System.err.println("[Music/LyricCache] 预取失败 " + key + ": " + failure.getMessage());
                } finally {
                    synchronized (PREFETCH_LOCK) {
                        if (key.equals(prefetchingKey)) prefetchingKey = "";
                    }
                }
            }
        }, "Muonium Lyric Prefetch");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    // ── 序列化 ──────────────────────────────────────────────────────────────

    private static JsonObject serialize(String stableKey, List<LyricLine> lines) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("key", stableKey);

        JsonArray array = new JsonArray();
        for (LyricLine line : lines) {
            if (line == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("t", line.timestamp);
            item.addProperty("d", line.duration);
            item.addProperty("x", line.lyric == null ? "" : line.lyric);
            if (line.translationText != null) item.addProperty("tr", line.translationText);
            if (line.romanizationText != null) item.addProperty("ro", line.romanizationText);
            if (line.isBreakLine) item.addProperty("br", true);
            if (line.isChorus) {
                item.addProperty("ch", true);
                item.addProperty("ci", line.chorusIndex);
                item.addProperty("ce", line.chorusEffect);
            }
            if (!line.words.isEmpty()) {
                JsonArray words = new JsonArray();
                for (LyricLine.Word word : line.words) {
                    if (word == null) continue;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("x", word.word);
                    entry.addProperty("t", word.timestamp);
                    entry.addProperty("d", word.duration);
                    if (word.background) entry.addProperty("bg", true);
                    words.add(entry);
                }
                item.add("w", words);
            }
            array.add(item);
        }
        root.add("lines", array);
        return root;
    }

    private static List<LyricLine> deserialize(JsonObject root) {
        List<LyricLine> lines = new ArrayList<>();
        JsonElement element = root.get("lines");
        if (element == null || !element.isJsonArray()) return lines;

        for (JsonElement raw : element.getAsJsonArray()) {
            if (raw == null || !raw.isJsonObject()) continue;
            JsonObject item = raw.getAsJsonObject();
            String text = stringOf(item, "x");
            if (text.isEmpty()) continue;

            LyricLine line = new LyricLine(longOf(item, "t", 0L), text);
            line.duration = longOf(item, "d", 0L);
            if (item.has("tr")) line.translationText = stringOf(item, "tr");
            if (item.has("ro")) line.romanizationText = stringOf(item, "ro");
            line.isBreakLine = item.has("br") && item.get("br").getAsBoolean();
            if (item.has("ch") && item.get("ch").getAsBoolean()) {
                line.isChorus = true;
                line.chorusIndex = intOf(item, "ci", -1);
                line.chorusEffect = intOf(item, "ce", -1);
            }

            JsonElement wordsElement = item.get("w");
            if (wordsElement != null && wordsElement.isJsonArray()) {
                for (JsonElement rawWord : wordsElement.getAsJsonArray()) {
                    if (rawWord == null || !rawWord.isJsonObject()) continue;
                    JsonObject word = rawWord.getAsJsonObject();
                    String wordText = stringOf(word, "x");
                    if (wordText.isEmpty()) continue;
                    LyricLine.Word added = new LyricLine.Word(wordText,
                            Math.max(0L, longOf(word, "t", 0L)), Math.max(0L, longOf(word, "d", 0L)));
                    added.background = word.has("bg") && word.get("bg").getAsBoolean();
                    line.words.add(added);
                }
            }
            lines.add(line);
        }
        lines.sort(Comparator.comparingLong(LyricLine::getTimestamp));
        return lines;
    }

    // ── 目录管理 ────────────────────────────────────────────────────────────

    /** 超出上限时按最后修改时间删掉最旧的一批。 */
    private static void prune() {
        File directory = ConfigPaths.LYRIC_CACHE;
        File[] files = directory.listFiles();
        if (files == null || files.length <= MAX_ENTRIES) return;

        List<File> candidates = new ArrayList<>(Arrays.asList(files));
        candidates.sort(new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(left.lastModified(), right.lastModified());
            }
        });
        int remove = candidates.size() - MAX_ENTRIES;
        for (int index = 0; index < remove; index++) {
            File file = candidates.get(index);
            // 只删自己写出来的 .json；临时文件留给下一次原子替换覆盖。
            if (file.isFile() && file.getName().endsWith(".json") && !file.delete()) {
                System.err.println("[Music/LyricCache] 清理失败 " + file.getName());
            }
        }
    }

    /**
     * 由歌曲稳定键推一个文件名。
     *
     * <p>不能用 {@code hashCode()}：它跨进程不稳定，而缓存要跨进程复用。用 FNV-1a 得到一个
     * 定长十六进制名，键里形如 {@code netease:12345} 的冒号也就不会跑进文件名。</p>
     */
    private static File fileFor(String stableKey) {
        byte[] bytes = ("lyric:" + stableKey).getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return new File(ConfigPaths.LYRIC_CACHE, Long.toHexString(hash) + ".json");
    }

    private static String stringOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive()
                ? "" : element.getAsString();
    }

    private static long longOf(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !(element instanceof JsonPrimitive)) return fallback;
        try {
            return element.getAsLong();
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    private static int intOf(JsonObject object, String key, int fallback) {
        long value = longOf(object, key, fallback);
        return value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? fallback : (int) value;
    }
}
