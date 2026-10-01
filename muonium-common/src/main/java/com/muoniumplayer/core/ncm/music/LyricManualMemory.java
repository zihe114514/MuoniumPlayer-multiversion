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
 * 每首歌「手动搜出来并确认」的那一条歌词。
 *
 * <h3>为什么还要再来一层记忆</h3>
 * <p>{@link LyricSourceMemory} 能说的是「去 AMLL 拿」，但一个库里同名不同版本的东西一样多——
 * 原曲 / Live / Remix / 翻唱挂在同一个曲名下，自动打分照样可能挑到错的那一条，而这一次
 * <b>换源也救不了</b>：用户已经知道正确答案长什么样了，缺的是「就指定这一首」的手段。
 * 手动搜索弹窗给出的就是这个：候选由用户亲自点，机器不再参与选择。</p>
 *
 * <h3>钉的是候选本身，不是「源 + 一首歌」的抽象</h3>
 * <p>所以这里把上游返回的那一条候选 JSON 原样存下来（{@link Pin#payload}），重新加载时直接照它
 * 精确重取，不再搜一遍、也不再打分。理由很实在：搜索接口是模糊匹配，同一条 query 隔两周可能
 * 排出来完全不同的顺序；只存 id 的话，酷狗那种「id + 一次性的 accesskey」还会因为 token 过期而
 * 拿不到。整条候选存下来，最坏情况是取不到然后回落到自动链路。</p>
 *
 * <h3>与另外三条出口的优先关系</h3>
 * <p>这一档排在 {@code LyricLoadService.resolve} 的<b>最前面</b>，压过指定源与自动链路。
 * 「换歌词来源」（{@code CloudMusic.setLyricSource}）<b>不</b>动它——钉住是用户确认过的结果，
 * 比「去哪个库拿」这条偏好更具体，换库顺带走掉一份人工结果不合理；代价是钉住期间换源看不出效果，
 * 所以界面上那一行必须把「已手动选择 …」显示出来。</p>
 * <p>「重新匹配」（{@code CloudMusic.reloadLyrics}）<b>会</b>清掉它：那一行的意思就是
 * 「把上一份结果整个作废、按链路重跑」，留着钉住的话那个按钮永远白点。另外还有显式的
 * 「取消钉住」（{@code CloudMusic.clearPinnedLyric}）。</p>
 * <p>判定与清理都在 {@code LyricLoadService} / {@code CloudMusic} 那一侧做，这里只管读写。</p>
 *
 * <p>读写语义与 {@link LyricOffsetMemory} 完全一致：懒加载 + LRU 淘汰、原子写、失败只留内存值，
 * 并且<b>绝不把异常抛进播放路径</b>。</p>
 */
public final class LyricManualMemory {

    /** 上限：一条比 {@code lyric_sources.json} 的条目大得多（带整份候选 JSON），所以给得更紧。 */
    private static final int MAX_ENTRIES = 500;

    /** payload 的字节上限：上游偶尔会回一大坨带注释的 JSON，不该让它把配置文件撑爆。 */
    private static final int MAX_PAYLOAD_CHARS = 8 * 1024;

    private static final Object LOCK = new Object();
    private static Map<String, Pin> cache;

    private LyricManualMemory() {
    }

    /** 一条手动钉住的歌词。除 {@link #payload} 外的字段都只为了显示与日志，缺失不影响取词。 */
    public static final class Pin {
        public final LyricSearchSource source;
        /** 该源内定位这首歌的 id（网易云 songId、QQ songid、酷狗歌词 id、AMLL 词条 id）。 */
        public final String trackId;
        /** 仅 AMLL 用：这条 TTML 是按 ncm 还是 qq 的 id 存的。其它源为空串。 */
        public final String platform;
        /** 候选卡片上显示的歌名/歌手/专辑，用于「当前钉的是哪一首」这一行说明。 */
        public final String title;
        public final String artists;
        public final String album;
        public final long durationMillis;
        /** 上游返回的原始候选 JSON（compact）。酷狗靠它拿 id+accesskey，AMLL 靠它拿 musicNames。 */
        public final String payload;

        public Pin(LyricSearchSource source, String trackId, String platform, String title,
                   String artists, String album, long durationMillis, String payload) {
            this.source = source == null ? LyricSearchSource.NETEASE : source;
            this.trackId = safe(trackId);
            this.platform = safe(platform);
            this.title = safe(title);
            this.artists = safe(artists);
            this.album = safe(album);
            this.durationMillis = Math.max(0L, durationMillis);
            this.payload = safe(payload);
        }

        /** payload 解析不出来时返回 {@code null}，调用方按「只有 id」降级处理。 */
        public JsonObject candidateJson() {
            if (payload.isEmpty()) return null;
            try {
                JsonElement parsed = new JsonParser().parse(payload);
                return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        /** 状态行/日志用的一行说明：「歌手 - 歌名」。 */
        public String describe() {
            String name = title.isEmpty() ? trackId : title;
            return artists.isEmpty() ? name : artists + " - " + name;
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /** 这首歌手动钉住了哪一条；没钉返回 {@code null}。 */
    public static Pin read(String stableKey) {
        String key = normalizeKey(stableKey);
        if (key == null) return null;
        synchronized (LOCK) {
            return hydrate().get(key);
        }
    }

    /** 有没有钉过。界面上决定要不要显示那一行「已手动指定：xxx」。 */
    public static boolean has(String stableKey) {
        return read(stableKey) != null;
    }

    /** 记住用户挑的这一条。没有稳定键时什么都不做（本地未入库的歌钉不上）。 */
    public static void write(String stableKey, Pin pin) {
        String key = normalizeKey(stableKey);
        if (key == null || pin == null || pin.trackId.isEmpty() || pin.payload.length() > MAX_PAYLOAD_CHARS) {
            return;
        }
        synchronized (LOCK) {
            Map<String, Pin> map = hydrate();
            // 先删再放：最新写入的排到末尾，淘汰时丢的才是最久没动过的。
            map.remove(key);
            map.put(key, pin);
            while (map.size() > MAX_ENTRIES) {
                String oldest = map.keySet().iterator().next();
                map.remove(oldest);
            }
            persist(map);
        }
    }

    /** 忘掉这首歌的手动选择，回到自动链路。 */
    public static void clear(String stableKey) {
        String key = normalizeKey(stableKey);
        if (key == null) return;
        synchronized (LOCK) {
            if (hydrate().remove(key) != null) {
                persist(cache);
            }
        }
    }

    private static String normalizeKey(String stableKey) {
        if (stableKey == null) return null;
        String trimmed = stableKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Pin> hydrate() {
        if (cache != null) return cache;
        cache = new LinkedHashMap<String, Pin>();
        try {
            File source = ConfigPaths.LYRIC_MANUAL;
            if (!source.isFile()) return cache;
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            JsonElement parsed = new JsonParser().parse(text);
            if (parsed == null || !parsed.isJsonObject()) return cache;
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || !value.isJsonObject()) continue;
                Pin pin = readPin(value.getAsJsonObject());
                if (pin != null) cache.put(entry.getKey(), pin);
            }
        } catch (Throwable failure) {
            // 文件损坏或被占用：从空表开始。绝不把异常抛进播放路径。
            cache = new LinkedHashMap<String, Pin>();
        }
        return cache;
    }

    /** 单条读失败只丢那一条；缺 id 的条目直接当没有（没 id 就没法精确重取）。 */
    private static Pin readPin(JsonObject node) {
        try {
            String trackId = string(node, "trackId");
            if (trackId.isEmpty()) return null;
            JsonElement payload = node.get("payload");
            String raw;
            if (payload == null || payload.isJsonNull()) {
                raw = "";
            } else if (payload.isJsonObject()) {
                raw = payload.toString();
            } else {
                raw = payload.getAsString();
            }
            if (raw.length() > MAX_PAYLOAD_CHARS) return null;
            return new Pin(LyricSearchSource.fromId(string(node, "source")), trackId,
                    string(node, "platform"), string(node, "title"), string(node, "artists"),
                    string(node, "album"), longOr(node, "duration"), raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void persist(Map<String, Pin> map) {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Pin> entry : map.entrySet()) {
                Pin pin = entry.getValue();
                JsonObject node = new JsonObject();
                node.addProperty("source", pin.source.id);
                node.addProperty("trackId", pin.trackId);
                if (!pin.platform.isEmpty()) node.addProperty("platform", pin.platform);
                if (!pin.title.isEmpty()) node.addProperty("title", pin.title);
                if (!pin.artists.isEmpty()) node.addProperty("artists", pin.artists);
                if (!pin.album.isEmpty()) node.addProperty("album", pin.album);
                if (pin.durationMillis > 0L) node.addProperty("duration", pin.durationMillis);
                // 存成嵌套对象而不是字符串：手改配置文件时看得见、也好核对，读的时候两种都认。
                JsonElement candidate = pin.candidateJson() == null
                        ? null : pin.candidateJson();
                if (candidate != null) node.add("payload", candidate);
                else node.addProperty("payload", pin.payload);
                root.add(entry.getKey(), node);
            }
            File target = ConfigPaths.LYRIC_MANUAL;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            Files.write(target.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            // 写不进去就只留内存值。
            System.err.println("[Music/LyricManual] 保存失败: " + failure.getMessage());
        }
    }

    private static String string(JsonObject node, String property) {
        try {
            if (node == null || !node.has(property) || node.get(property).isJsonNull()) return "";
            return node.get(property).getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long longOr(JsonObject node, String property) {
        try {
            if (node == null || !node.has(property) || node.get(property).isJsonNull()) return 0L;
            return node.get(property).getAsLong();
        } catch (Throwable ignored) {
            return 0L;
        }
    }
}
