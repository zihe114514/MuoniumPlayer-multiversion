package com.muoniumplayer.core.preset.codec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.preset.model.DomainFragment;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.PresetMeta;
import com.muoniumplayer.core.preset.model.StylePreset;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预设文件格式 v1（{@code *.mpreset.json}）的读写。
 *
 * <pre>{@code
 * {
 *   "format": "muonium.preset",
 *   "formatVersion": 1,
 *   "base": "defaults",
 *   "meta": { "id": "user:…", "name": "…", "author": "…", "description": "…",
 *             "created": "2026-11-06T03:52:00Z", "generator": { "mod": "2.4.1", "mc": "26.3" } },
 *   "facets": ["lyrics", "theme"],
 *   "domains": { "hud": { "v": 1, "values": { … } }, "theme": { "v": 1, "values": { … } } }
 * }
 * }</pre>
 *
 * <p><b>读旧写新</b>：{@link #FORMAT_VERSION} 只在外层信封变化时 +1；配置域里键的变化走各域自己的片段版本
 * {@code v}。本版本认不出的分面与配置域原样保留（转存、再分享都不丢），只是不套用。</p>
 */
public final class PresetJson {

    public static final String FORMAT = "muonium.preset";
    public static final int FORMAT_VERSION = 1;

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private PresetJson() {
    }

    /**
     * 解析一份预设文本。顺序固定：量尺寸 → 量嵌套深度 → 解析 → 校验，任何一步不过都在动手之前拒掉。
     *
     * @throws PresetFormatException 带一句给玩家看的原因
     */
    public static StylePreset parse(String text, StylePreset.Source source, File file) throws PresetFormatException {
        String trimmed = text == null ? "" : stripBom(text).trim();
        if (trimmed.isEmpty()) {
            throw new PresetFormatException("预设内容是空的");
        }
        if (trimmed.length() > PresetLimits.MAX_JSON_CHARS) {
            throw new PresetFormatException("预设内容过大，已拒绝读取");
        }
        if (PresetLimits.nestingDepth(trimmed) > PresetLimits.MAX_DEPTH) {
            throw new PresetFormatException("预设结构嵌套过深，已拒绝读取");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(trimmed);
        } catch (RuntimeException failure) {
            throw new PresetFormatException("预设不是合法的 JSON", failure);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new PresetFormatException("预设的最外层必须是一个对象");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!FORMAT.equals(string(root, "format"))) {
            throw new PresetFormatException("这不是 MuoniumPlayer 的模板预设");
        }
        int version = integer(root, "formatVersion", 1);
        if (version < 1) {
            throw new PresetFormatException("预设格式版本无效");
        }
        if (version > FORMAT_VERSION) {
            throw new PresetFormatException("这份预设来自更新版本的 MuoniumPlayer（格式 v" + version
                    + "），请先更新再导入");
        }

        JsonObject metaJson = object(root, "meta");
        JsonObject generator = object(metaJson, "generator");
        String name = clean(string(metaJson, "name"), PresetLimits.MAX_NAME_CHARS);
        PresetMeta meta = new PresetMeta(
                clean(string(metaJson, "id"), PresetLimits.MAX_ID_CHARS),
                name.isEmpty() ? "未命名预设" : name,
                clean(string(metaJson, "author"), PresetLimits.MAX_AUTHOR_CHARS),
                clean(string(metaJson, "description"), PresetLimits.MAX_DESCRIPTION_CHARS),
                clean(string(metaJson, "created"), PresetLimits.MAX_SHORT_TEXT_CHARS),
                clean(string(generator, "mod"), PresetLimits.MAX_SHORT_TEXT_CHARS),
                clean(string(generator, "mc"), PresetLimits.MAX_SHORT_TEXT_CHARS));

        Set<Facet> facets = EnumSet.noneOf(Facet.class);
        List<String> unknownFacets = new ArrayList<String>();
        JsonElement facetsJson = root.get("facets");
        if (facetsJson != null && facetsJson.isJsonArray()) {
            JsonArray array = facetsJson.getAsJsonArray();
            if (array.size() > PresetLimits.MAX_FACETS) {
                throw new PresetFormatException("预设声明的分面过多，已拒绝读取");
            }
            for (JsonElement element : array) {
                if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    continue;
                }
                String id = element.getAsString();
                Facet facet = Facet.byId(id);
                if (facet != null) {
                    facets.add(facet);
                } else if (isSimpleId(id) && !unknownFacets.contains(id)) {
                    unknownFacets.add(id);
                }
            }
        }

        Map<String, DomainFragment> domains = new LinkedHashMap<String, DomainFragment>();
        JsonObject domainsJson = object(root, "domains");
        if (domainsJson != null) {
            if (domainsJson.size() > PresetLimits.MAX_DOMAINS) {
                throw new PresetFormatException("预设里的配置域过多，已拒绝读取");
            }
            for (Map.Entry<String, JsonElement> entry : domainsJson.entrySet()) {
                if (!isSimpleId(entry.getKey()) || entry.getValue() == null || !entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject domain = entry.getValue().getAsJsonObject();
                JsonObject values = object(domain, "values");
                if (values == null) {
                    continue;
                }
                int[] leaves = new int[1];
                checkValues(values, leaves);
                if (leaves[0] > PresetLimits.MAX_KEYS_PER_DOMAIN) {
                    throw new PresetFormatException("配置域「" + entry.getKey() + "」的设置项过多，已拒绝读取");
                }
                domains.put(entry.getKey(), new DomainFragment(integer(domain, "v", 1), values));
            }
        }

        StylePreset.Base base = StylePreset.Base.byId(string(root, "base"));
        return new StylePreset(meta, base, facets, unknownFacets, domains, source, file);
    }

    /** 写出预设文本。{@code pretty} 给文件用，紧凑格式给分享码用。 */
    public static String write(StylePreset preset, boolean pretty) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("base", preset.base().id());

        PresetMeta source = preset.meta();
        JsonObject meta = new JsonObject();
        putIfPresent(meta, "id", source.id());
        meta.addProperty("name", source.name());
        putIfPresent(meta, "author", source.author());
        putIfPresent(meta, "description", source.description());
        putIfPresent(meta, "created", source.created());
        if (!source.generatorMod().isEmpty() || !source.generatorMc().isEmpty()) {
            JsonObject generator = new JsonObject();
            putIfPresent(generator, "mod", source.generatorMod());
            putIfPresent(generator, "mc", source.generatorMc());
            meta.add("generator", generator);
        }
        root.add("meta", meta);

        JsonArray facets = new JsonArray();
        for (Facet facet : Facet.values()) {
            if (preset.facets().contains(facet)) {
                facets.add(facet.id());
            }
        }
        for (String unknown : preset.unknownFacets()) {
            facets.add(unknown);
        }
        root.add("facets", facets);

        JsonObject domains = new JsonObject();
        for (Map.Entry<String, DomainFragment> entry : preset.domains().entrySet()) {
            JsonObject domain = new JsonObject();
            domain.addProperty("v", entry.getValue().version());
            domain.add("values", entry.getValue().values());
            domains.add(entry.getKey(), domain);
        }
        root.add("domains", domains);
        return (pretty ? PRETTY : COMPACT).toJson(root) + (pretty ? "\n" : "");
    }

    // ==================== 校验与清洗 ====================

    /** 逐个叶子数数、量字符串长度。深度已经在文本层量过，这里的递归是有界的。 */
    private static void checkValues(JsonElement element, int[] leaves) throws PresetFormatException {
        if (element == null || element.isJsonNull()) {
            leaves[0]++;
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().length() > PresetLimits.MAX_SHORT_TEXT_CHARS * 4) {
                    throw new PresetFormatException("预设里有过长的设置名，已拒绝读取");
                }
                checkValues(entry.getValue(), leaves);
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                checkValues(item, leaves);
            }
            return;
        }
        leaves[0]++;
        if (element.getAsJsonPrimitive().isString()
                && element.getAsString().length() > PresetLimits.MAX_VALUE_STRING_CHARS) {
            throw new PresetFormatException("预设里有过长的文字内容，已拒绝读取");
        }
    }

    /** 配置域 / 分面 id：只许小写字母数字与下划线、短横线。 */
    static boolean isSimpleId(String id) {
        if (id == null || id.isEmpty() || id.length() > 32) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 去掉控制字符与首尾空白，按码点截断到上限（不会把一个 emoji 劈成两半）。 */
    public static String clean(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), maxChars));
        int count = 0;
        for (int i = 0; i < value.length() && count < maxChars; ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                // 换行、制表、以及 RLO 之类的方向控制符：名字里不该出现，后者还能把显示顺序整个倒过来。
                if (codePoint == '\n' || codePoint == '\t') {
                    out.append(' ');
                    count++;
                }
                continue;
            }
            out.appendCodePoint(codePoint);
            count++;
        }
        return out.toString().trim();
    }

    private static void putIfPresent(JsonObject target, String key, String value) {
        if (value != null && !value.isEmpty()) {
            target.addProperty(key, value);
        }
    }

    private static String stripBom(String text) {
        return text.length() > 0 && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static int integer(JsonObject parent, String key, int fallback) {
        if (parent == null) {
            return fallback;
        }
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException failure) {
            return fallback;
        }
    }
}
