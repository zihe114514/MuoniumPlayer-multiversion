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

/** Versioned .mpreset.json envelope. Unknown domains/facets survive local round-trips but never authorize changes. */
public final class PresetJson {
    public static final String FORMAT = "muonium.preset";
    public static final int FORMAT_VERSION = 1;
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    private PresetJson() {}
    public static StylePreset parse(String text, StylePreset.Source source, File file) throws PresetFormatException {
        if (text != null && text.length() > PresetLimits.MAX_JSON_CHARS)
            throw new PresetFormatException("预设内容过大，已拒绝读取");
        String trimmed = text == null ? "" : stripBom(text).trim();
        if (trimmed.isEmpty()) throw new PresetFormatException("预设内容是空的");
        if (PresetLimits.nestingDepth(trimmed) > PresetLimits.MAX_DEPTH)
            throw new PresetFormatException("预设结构嵌套过深，已拒绝读取");
        JsonElement parsed;
        try { parsed = JsonParser.parseString(trimmed); }
        catch (RuntimeException failure) { throw new PresetFormatException("预设不是合法的 JSON", failure); }
        if (parsed == null || !parsed.isJsonObject()) throw new PresetFormatException("预设的最外层必须是一个对象");
        JsonObject root = parsed.getAsJsonObject();
        if (!FORMAT.equals(string(root, "format"))) throw new PresetFormatException("这不是 MuoniumPlayer 的模板预设");
        int version = integer(root, "formatVersion", 1);
        if (version < 1) throw new PresetFormatException("预设格式版本无效");
        if (version > FORMAT_VERSION) throw new PresetFormatException("这份预设来自更新版本的 MuoniumPlayer（格式 v" + version + "），请先更新再导入");
        JsonObject metaJson = object(root, "meta"), generator = object(metaJson, "generator");
        String name = clean(string(metaJson, "name"), PresetLimits.MAX_NAME_CHARS);
        PresetMeta meta = new PresetMeta(clean(string(metaJson, "id"), PresetLimits.MAX_ID_CHARS),
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
            if (array.size() > PresetLimits.MAX_FACETS) throw new PresetFormatException("预设声明的分面过多，已拒绝读取");
            for (JsonElement element : array) {
                if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
                String id = element.getAsString();
                Facet facet = Facet.byId(id);
                if (facet != null) facets.add(facet);
                else if (isSimpleId(id) && !unknownFacets.contains(id)) unknownFacets.add(id);
            }
        }
        Map<String, DomainFragment> domains = new LinkedHashMap<String, DomainFragment>();
        JsonObject domainsJson = object(root, "domains");
        if (domainsJson != null) {
            if (domainsJson.size() > PresetLimits.MAX_DOMAINS) throw new PresetFormatException("预设里的配置域过多，已拒绝读取");
            for (Map.Entry<String, JsonElement> entry : domainsJson.entrySet()) {
                if (!isSimpleId(entry.getKey()) || entry.getValue() == null || !entry.getValue().isJsonObject()) continue;
                JsonObject domain = entry.getValue().getAsJsonObject(), values = object(domain, "values");
                if (values == null) continue;
                checkValues(values, new int[1]);
                domains.put(entry.getKey(), new DomainFragment(integer(domain, "v", 1), values));
            }
        }
        return new StylePreset(meta, StylePreset.Base.byId(string(root, "base")), facets, unknownFacets, domains, source, file);
    }
    public static String write(StylePreset preset, boolean pretty) {
        return (pretty ? PRETTY : COMPACT).toJson(toJson(preset)) + (pretty ? "\n" : "");
    }
    /** Plain data projection, excluding local paths/source. */
    public static JsonObject toJson(StylePreset preset) {
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
        for (Facet facet : Facet.values()) if (preset.facets().contains(facet)) facets.add(facet.id());
        for (String unknown : preset.unknownFacets()) facets.add(unknown);
        root.add("facets", facets);
        JsonObject domains = new JsonObject();
        for (Map.Entry<String, DomainFragment> entry : preset.domains().entrySet()) {
            JsonObject domain = new JsonObject();
            domain.addProperty("v", entry.getValue().version());
            domain.add("values", entry.getValue().values());
            domains.add(entry.getKey(), domain);
        }
        root.add("domains", domains);
        return root;
    }
    private static void checkValues(JsonElement element, int[] leaves) throws PresetFormatException {
        if (element != null && element.isJsonObject()) {
            if (element.getAsJsonObject().size() > PresetLimits.MAX_KEYS_PER_DOMAIN) throw new PresetFormatException("预设里的设置项过多");
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().length() > PresetLimits.MAX_SHORT_TEXT_CHARS * 4) throw new PresetFormatException("预设里有过长的设置名，已拒绝读取");
                checkValues(entry.getValue(), leaves);
            }
        } else if (element != null && element.isJsonArray()) {
            if (element.getAsJsonArray().size() > PresetLimits.MAX_KEYS_PER_DOMAIN) throw new PresetFormatException("预设里的数组过大");
            for (JsonElement item : element.getAsJsonArray()) checkValues(item, leaves);
        } else {
            if (++leaves[0] > PresetLimits.MAX_KEYS_PER_DOMAIN) throw new PresetFormatException("预设里的设置项过多，已拒绝读取");
            if (element != null && element.isJsonPrimitive()) {
                if (element.getAsJsonPrimitive().isString() && element.getAsString().length() > PresetLimits.MAX_VALUE_STRING_CHARS)
                    throw new PresetFormatException("预设里有过长的文字内容，已拒绝读取");
                if (element.getAsJsonPrimitive().isNumber() && !Double.isFinite(element.getAsDouble()))
                    throw new PresetFormatException("预设里有无效数值，已拒绝读取");
            }
        }
    }
    static boolean isSimpleId(String id) {
        if (id == null || id.isEmpty() || id.length() > 32) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) return false;
        }
        return true;
    }
    public static String clean(String value, int maxChars) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(Math.min(value.length(), maxChars));
        int count = 0;
        for (int i = 0; i < value.length() && count < maxChars;) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                if (codePoint == '\n' || codePoint == '\t') { out.append(' '); count++; }
                continue;
            }
            out.appendCodePoint(codePoint); count++;
        }
        return out.toString().trim();
    }
    private static void putIfPresent(JsonObject target, String key, String value) {
        if (value != null && !value.isEmpty()) target.addProperty(key, value);
    }
    private static String stripBom(String text) { return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text; }
    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
    private static String string(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
    private static int integer(JsonObject parent, String key, int fallback) {
        JsonElement value = parent == null ? null : parent.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return fallback;
        try {
            long parsed = value.getAsBigDecimal().longValueExact();
            return parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE ? (int) parsed : fallback;
        } catch (RuntimeException failure) { return fallback; }
    }
}
