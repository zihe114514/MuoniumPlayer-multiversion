package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每首歌的「歌词时间轴偏移」记忆。对应 Folia 的 {@code lyricOffsetMemory}。
 *
 * <p>曲子与歌词版本对不齐是常态（现场版、重制版、第三方歌词源），自动匹配再准也会有需要手动微调的
 * 时候。用户调过之后切歌、重启，那个补偿值本该还在——原来每次切歌都把它重置成 0，等于让用户
 * 每听一遍重调一遍。</p>
 *
 * <p>只做一件事：持久化 {@code 歌曲稳定键 → 偏移毫秒}。不碰任何解析、时间轴或渲染逻辑——
 * 偏移在 {@code CloudMusic.findCurrentLyric} 里统一加一次。</p>
 *
 * <p>存法是直白的 JSON 映射，遵循项目约定：小的用户设置放 {@code config/muonium/} 下的独立文件，
 * 读失败只是「回到没有偏移」，不该有机会影响播放器主配置。</p>
 */
final class LyricOffsetMemory {

    /** 上限：防止长年累积把文件撑大。按写入顺序淘汰最旧的一首。 */
    private static final int MAX_ENTRIES = 1000;

    /** 单首歌的偏移范围，±10 秒。再大就不是"对不齐"而是"换错歌"了。 */
    private static final long MAX_OFFSET_MILLIS = 10_000L;

    private static final Object LOCK = new Object();
    private static Map<String, Long> cache;

    private LyricOffsetMemory() {
    }

    /** 这首歌上次用过的偏移（毫秒）；从没设置过返回 0。 */
    static long read(String stableKey) {
        String key = normalizeKey(stableKey);
        if (key == null) return 0L;
        synchronized (LOCK) {
            Long value = hydrate().get(key);
            return value == null ? 0L : value;
        }
    }

    /** 记住（{@code 0} 表示忘掉）这首歌的偏移。没有稳定键时什么都不做。 */
    static void write(String stableKey, long offsetMillis) {
        String key = normalizeKey(stableKey);
        if (key == null) return;

        long clamped = clamp(offsetMillis);
        synchronized (LOCK) {
            Map<String, Long> map = hydrate();
            // 先删再放：最新写入的排到末尾，淘汰时丢的才是最久没动过的。
            map.remove(key);
            if (clamped != 0L) map.put(key, clamped);
            while (map.size() > MAX_ENTRIES) {
                String oldest = map.keySet().iterator().next();
                map.remove(oldest);
            }
            persist(map);
        }
    }

    static long clamp(long offsetMillis) {
        if (offsetMillis > MAX_OFFSET_MILLIS) return MAX_OFFSET_MILLIS;
        if (offsetMillis < -MAX_OFFSET_MILLIS) return -MAX_OFFSET_MILLIS;
        return offsetMillis;
    }

    private static String normalizeKey(String stableKey) {
        if (stableKey == null) return null;
        String trimmed = stableKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Long> hydrate() {
        if (cache != null) return cache;
        cache = new LinkedHashMap<>();
        try {
            File source = ConfigPaths.LYRIC_OFFSETS;
            if (!source.isFile()) return cache;
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(text);
            if (parsed == null || !parsed.isJsonObject()) return cache;
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) continue;
                try {
                    long offset = value.getAsLong();
                    if (offset != 0L) cache.put(entry.getKey(), clamp(offset));
                } catch (RuntimeException ignored) {
                    // 单个条目坏掉不影响其余。
                }
            }
        } catch (Throwable failure) {
            // 文件损坏或被占用：从空表开始。绝不把异常抛进播放路径。
            cache = new LinkedHashMap<>();
        }
        return cache;
    }

    private static void persist(Map<String, Long> map) {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Long> entry : map.entrySet()) {
                root.addProperty(entry.getKey(), entry.getValue());
            }
            File target = ConfigPaths.LYRIC_OFFSETS;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            Files.write(target.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            // 写不进去就只留内存值,静默跳过持久化。
            System.err.println("[Music/LyricOffset] 保存失败: " + failure.getMessage());
        }
    }
}
