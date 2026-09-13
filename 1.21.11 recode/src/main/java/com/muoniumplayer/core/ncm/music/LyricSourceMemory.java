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
 * 每首歌的「歌词来源」记忆。
 *
 * <h3>为什么需要它</h3>
 * <p>自动匹配再准也会有挑错的时候：同名不同版本、翻唱与原唱同名、本地元数据缺歌手/专辑、上游歌词库
 * 里那一版时间轴本身就是错的。这些情况下「重新匹配」是<b>没有用</b>的——它把同一条确定性链路原样
 * 再跑一遍，输入没变，结果必然一模一样。用户看着一份错歌词，点多少次都逃不掉。</p>
 *
 * <p>真正能脱困的手段是<b>换一个源</b>，所以这里把「这首歌用哪个源」也做成和
 * {@link LyricOffsetMemory} 一样的按歌记忆：切歌、重启之后仍然有效，不必每听一遍重挑一次。</p>
 *
 * <h3>语义</h3>
 * <p>{@link #AUTO} 是默认值，写入时直接删条目——「没设置过」和「设回自动」是同一件事。</p>
 * <p>指定某个源是<b>偏好而非硬绑定</b>：那个源这次没有逐字结果时，加载链会打一行日志然后落回
 * 自动链路，而不是把歌词区留空。用户宁可看到「不是我要的源」也不要看到一片空白。</p>
 *
 * <p>存法与项目约定一致：小的用户设置放 {@code config/muonium/} 下的独立文件，读失败就当没设置过，
 * 不该有机会影响播放器主配置。</p>
 */
public final class LyricSourceMemory {

    /** 自动：AMLL → QQ QRC → 酷狗 KRC → 汽水 → 平台兜底，取第一个逐字结果。 */
    public static final int AUTO = 0;
    /** AMLL 词库（人工核对过的 TTML）。 */
    public static final int AMLL = 1;
    /** QQ 音乐 QRC。 */
    public static final int QQ = 2;
    /** 酷狗 KRC（含跨源检索）。 */
    public static final int KUGOU = 3;
    /** 汽水音乐。 */
    public static final int QISHUI = 4;
    /** 平台官方歌词：跳过全部逐字源，直接走云盘 / 内嵌 / 统一提供方 / 网易云官方。 */
    public static final int PLATFORM = 5;

    /** 可选项个数，{@link #next(int)} / {@link #previous(int)} 绕它取模。 */
    private static final int COUNT = 6;

    /** 上限：防止长年累积把文件撑大。按写入顺序淘汰最旧的一首。 */
    private static final int MAX_ENTRIES = 1000;

    private static final Object LOCK = new Object();
    private static Map<String, Integer> cache;

    private LyricSourceMemory() {
    }

    /** 这首歌选择的来源；从没设置过返回 {@link #AUTO}。 */
    public static int read(String stableKey) {
        String key = normalizeKey(stableKey);
        if (key == null) return AUTO;
        synchronized (LOCK) {
            Integer value = hydrate().get(key);
            return value == null ? AUTO : value;
        }
    }

    /** 记住（{@link #AUTO} 表示忘掉）这首歌的来源。没有稳定键时什么都不做。 */
    public static void write(String stableKey, int source) {
        String key = normalizeKey(stableKey);
        if (key == null) return;

        int normalized = clamp(source);
        synchronized (LOCK) {
            Map<String, Integer> map = hydrate();
            // 先删再放：最新写入的排到末尾，淘汰时丢的才是最久没动过的。
            map.remove(key);
            if (normalized != AUTO) map.put(key, normalized);
            while (map.size() > MAX_ENTRIES) {
                String oldest = map.keySet().iterator().next();
                map.remove(oldest);
            }
            persist(map);
        }
    }

    /** 未知取值一律折回 {@link #AUTO}，避免配置文件被手改坏之后整条链走进无人分支。 */
    public static int clamp(int source) {
        return source < 0 || source >= COUNT ? AUTO : source;
    }

    public static int next(int source) {
        return (clamp(source) + 1) % COUNT;
    }

    public static int previous(int source) {
        return (clamp(source) + COUNT - 1) % COUNT;
    }

    /** 界面上显示的名字。 */
    public static String displayName(int source) {
        switch (clamp(source)) {
            case AMLL:
                return "AMLL";
            case QQ:
                return "QQ 音乐";
            case KUGOU:
                return "酷狗";
            case QISHUI:
                return "汽水";
            case PLATFORM:
                return "平台官方";
            default:
                return "自动";
        }
    }

    private static String normalizeKey(String stableKey) {
        if (stableKey == null) return null;
        String trimmed = stableKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Integer> hydrate() {
        if (cache != null) return cache;
        cache = new LinkedHashMap<>();
        try {
            File source = ConfigPaths.LYRIC_SOURCES;
            if (!source.isFile()) return cache;
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(text);
            if (parsed == null || !parsed.isJsonObject()) return cache;
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) continue;
                try {
                    int id = clamp(value.getAsInt());
                    if (id != AUTO) cache.put(entry.getKey(), id);
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

    private static void persist(Map<String, Integer> map) {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                root.addProperty(entry.getKey(), entry.getValue());
            }
            File target = ConfigPaths.LYRIC_SOURCES;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            Files.write(target.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            // 写不进去就只留内存值,静默跳过持久化。
            System.err.println("[Music/LyricSource] 保存失败: " + failure.getMessage());
        }
    }
}
