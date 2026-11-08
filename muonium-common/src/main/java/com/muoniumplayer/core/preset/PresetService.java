package com.muoniumplayer.core.preset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.codec.PresetJson;
import com.muoniumplayer.core.preset.codec.PresetLimits;
import com.muoniumplayer.core.preset.codec.ShareCode;
import com.muoniumplayer.core.preset.domain.PresetDomains;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.DomainFragment;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.PresetMeta;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.update.UpdateChecker;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/** Main-thread preset facade. Pure JSON methods are the stable boundary for any host UI/Profiles adapter. */
public final class PresetService {
    public static final int PROTOCOL_VERSION = 1;
    private static PresetService instance;
    private final PresetEngine engine;
    private final PresetRepository repository;
    private final CompactShareCode shareCode;
    public PresetService(PresetEngine engine, PresetRepository repository) {
        this.engine = engine; this.repository = repository; this.shareCode = new CompactShareCode(engine.domains());
    }
    public static synchronized PresetService get() {
        if (instance == null) instance = new PresetService(new PresetEngine(PresetDomains.all()), PresetRepository.standard());
        return instance;
    }
    public PresetEngine engine() { return engine; }
    public PresetRepository repository() { return repository; }
    public List<StylePreset> builtins() { return repository.builtins(); }
    public List<StylePreset> userPresets() { return repository.userPresets(); }
    public Set<Facet> defaultSelection(StylePreset preset) {
        Set<Facet> available = EnumSet.noneOf(Facet.class);
        for (Facet facet : preset.facets()) if (engine.availableFacets().contains(facet)) available.add(facet);
        return Facet.withoutOptIn(available);
    }
    public CompatReport preview(StylePreset preset, Set<Facet> selected) { return engine.plan(preset, selected); }
    public CompatReport apply(StylePreset preset, Set<Facet> selected) {
        Set<Facet> effective = EnumSet.noneOf(Facet.class);
        if (selected != null) for (Facet facet : selected) if (preset.facets().contains(facet)) effective.add(facet);
        CompatReport preflight = engine.plan(preset, effective);
        if (preflight.hasFailures() || preflight.changedKeys() == 0) return preflight;
        StylePreset backup;
        try {
            Map<String, DomainFragment> fragments = engine.capture(effective);
            PresetMeta meta = new PresetMeta("backup:" + UUID.randomUUID(), "套用「" + preset.meta().name() + "」之前", "", "", now(),
                    MuoniumPlayerExtension.VERSION, UpdateChecker.minecraftVersion());
            backup = repository.saveBackup(new StylePreset(meta, StylePreset.Base.CURRENT, effective, null, fragments,
                    StylePreset.Source.BACKUP, null).withLocalState(engine.captureState(effective)));
        } catch (Throwable failure) {
            preflight.failure("备份（未改动任何配置）", failure);
            return preflight;
        }
        CompatReport report = engine.apply(preset, effective);
        if (report.hasFailures() && report.wasRolledBack() && !report.rollbackFailed()) repository.removeBackup(backup);
        return report;
    }
    public boolean canUndo() { return repository.backupCount() > 0; }
    public CompatReport undo() {
        StylePreset backup = repository.latestBackup();
        if (backup == null) return null;
        JsonObject local = backup.localState();
        CompatReport report = local == null ? engine.apply(backup, backup.facets()) : engine.restoreState(local);
        if (!report.hasFailures()) repository.removeBackup(backup);
        return report;
    }
    public StylePreset saveCurrent(String name, Set<Facet> facets) throws IOException { return repository.saveUser(snapshot(name, facets)); }
    public StylePreset snapshot(String name, Set<Facet> facets) {
        String cleanName = PresetJson.clean(name, PresetLimits.MAX_NAME_CHARS);
        PresetMeta meta = new PresetMeta("user:" + UUID.randomUUID(), cleanName.isEmpty() ? "我的预设" : cleanName, "", "", now(),
                MuoniumPlayerExtension.VERSION, UpdateChecker.minecraftVersion());
        return new StylePreset(meta, StylePreset.Base.DEFAULTS, facets, null, engine.capture(facets), StylePreset.Source.USER, null);
    }
    public String shareText(StylePreset preset) { return shareCode.shareLine(preset); }
    /** Bounded parse only, no disk write, suitable for imported-text preview. */
    public StylePreset parseText(String text) throws PresetFormatException {
        if (text != null && text.length() > PresetLimits.MAX_SHARE_TEXT_CHARS) throw new PresetFormatException("预设内容过长");
        String trimmed = text == null ? "" : text.trim();
        if (!trimmed.isEmpty() && trimmed.charAt(0) == '\uFEFF') trimmed = trimmed.substring(1).trim();
        if (trimmed.startsWith("{")) return PresetJson.parse(trimmed, StylePreset.Source.USER, null);
        if (CompactShareCode.contains(text)) return shareCode.decode(text);
        return PresetJson.parse(ShareCode.decode(text), StylePreset.Source.USER, null);
    }
    public StylePreset importText(String text) throws PresetFormatException, IOException {
        StylePreset parsed = parseText(text);
        return repository.saveUser(parsed.withMeta(parsed.meta().withId("user:" + UUID.randomUUID())));
    }
    public StylePreset importFile(File file) throws PresetFormatException, IOException {
        if (file == null || !file.isFile()) throw new PresetFormatException("文件不存在");
        String text;
        try (InputStream in = java.nio.file.Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            text = new String(PresetRepository.readAll(in, PresetLimits.MAX_JSON_CHARS * 4L), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new PresetFormatException("文件读不出来或过大：" + failure.getMessage(), failure); }
        return importText(text);
    }
    public boolean delete(StylePreset preset) { return repository.delete(preset); }
    /** Trusted complete LOCAL settings. Excluded privacy fields stay local; never pass this to share/import. */
    public JsonObject captureState() { return engine.captureState(); }
    public CompatReport restoreState(JsonObject state) { return engine.restoreState(state); }

    public StylePreset find(String id) {
        if (id == null) return null;
        for (StylePreset preset : builtins()) if (id.equals(preset.meta().id())) return preset;
        for (StylePreset preset : userPresets()) if (id.equals(preset.meta().id())) return preset;
        return null;
    }
    /** Directory metadata/details without any absolute local path or binary resource data. */
    public JsonObject catalogJson() {
        JsonObject out = new JsonObject();
        out.addProperty("protocolVersion", PROTOCOL_VERSION);
        out.addProperty("canUndo", canUndo());
        out.add("facets", facetsJson(engine.availableFacets()));
        JsonArray builtins = new JsonArray(), users = new JsonArray();
        for (StylePreset preset : builtins()) builtins.add(detailJson(preset));
        for (StylePreset preset : userPresets()) users.add(detailJson(preset));
        out.add("builtins", builtins); out.add("users", users);
        return out;
    }
    public JsonObject detailJson(String id) { StylePreset preset = find(id); return preset == null ? null : detailJson(preset); }
    private JsonObject detailJson(StylePreset preset) {
        JsonObject out = new JsonObject();
        out.addProperty("id", preset.meta().id()); out.addProperty("name", preset.meta().name());
        out.addProperty("description", preset.meta().description()); out.addProperty("author", preset.meta().author());
        out.addProperty("source", preset.source().name().toLowerCase(Locale.ROOT));
        out.addProperty("deletable", preset.source() == StylePreset.Source.USER);
        out.addProperty("base", preset.base().id());
        out.add("facets", facetIds(preset.facets()));
        out.add("defaultSelection", facetIds(defaultSelection(preset)));
        return out;
    }
    /** Stable action protocol. Apply/undo/save/delete run synchronously on the main thread; no lifecycle binding. */
    public JsonObject actionJson(JsonObject request) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        try {
            if (request == null) throw new IllegalArgumentException("动作为空");
            String action = text(request, "action");
            if ("catalog".equals(action)) return catalogJson();
            if ("undo".equals(action)) {
                CompatReport report = undo();
                result.add("report", report == null ? new CompatReport().toJson() : report.toJson());
                result.addProperty("ok", report != null && !report.hasFailures());
                return result;
            }
            if ("import".equals(action)) {
                result.add("preset", detailJson(importText(text(request, "text"))));
            } else if ("save".equals(action)) {
                result.add("preset", detailJson(saveCurrent(text(request, "name"), requestedFacets(request, engine.availableFacets()))));
            } else {
                StylePreset preset = find(text(request, "id"));
                if (preset == null) throw new IllegalArgumentException("预设不存在，请刷新目录");
                if ("detail".equals(action)) result.add("preset", detailJson(preset));
                else if ("preview".equals(action) || "apply".equals(action)) {
                    Set<Facet> facets = requestedFacets(request, defaultSelection(preset));
                    CompatReport report = "preview".equals(action) ? preview(preset, facets) : apply(preset, facets);
                    result.add("report", report.toJson()); result.addProperty("ok", !report.hasFailures()); return result;
                } else if ("share".equals(action)) result.addProperty("text", shareText(preset));
                else if ("delete".equals(action)) {
                    if (!delete(preset)) throw new IllegalStateException("预设删除失败");
                } else throw new IllegalArgumentException("未知动作");
            }
            result.addProperty("ok", true);
        } catch (Throwable failure) {
            result.addProperty("ok", false);
            result.addProperty("error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
        return result;
    }
    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key); return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
    private static Set<Facet> requestedFacets(JsonObject request, Set<Facet> fallback) {
        if (!request.has("facets")) return fallback;
        Set<Facet> out = EnumSet.noneOf(Facet.class);
        if (request.get("facets").isJsonArray()) for (JsonElement value : request.getAsJsonArray("facets")) {
            Facet facet = value.isJsonPrimitive() ? Facet.byId(value.getAsString()) : null;
            if (facet != null) out.add(facet);
        }
        return out;
    }
    private static JsonArray facetIds(Set<Facet> facets) {
        JsonArray out = new JsonArray(); for (Facet facet : Facet.values()) if (facets.contains(facet)) out.add(facet.id()); return out;
    }
    private static JsonArray facetsJson(Set<Facet> facets) {
        JsonArray out = new JsonArray();
        for (Facet facet : Facet.values()) if (facets.contains(facet)) {
            JsonObject value = new JsonObject(); value.addProperty("id", facet.id()); value.addProperty("name", facet.displayName());
            value.addProperty("description", facet.description()); value.addProperty("group", facet.group().name().toLowerCase(Locale.ROOT));
            value.addProperty("optIn", facet.optIn()); out.add(value);
        }
        return out;
    }
    private static String now() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC")); return format.format(new Date());
    }
}
