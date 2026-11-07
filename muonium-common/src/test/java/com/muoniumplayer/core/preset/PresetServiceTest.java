package com.muoniumplayer.core.preset;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PresetServiceTest {
    @TempDir Path temp;
    private FakeDomain hud, audio;
    private PresetService service;
    private PresetRepository repository;
    @BeforeEach void setUp() {
        hud = new FakeDomain("hud", KeyPolicy.builder().facet(Facet.LYRICS, "glow", "color").facet(Facet.VISIBILITY, "lyricsShown").build(),
                PresetEngineTest.json("{\"glow\":0.5,\"color\":\"white\",\"lyricsShown\":true}"));
        audio = new FakeDomain("acoustics", KeyPolicy.builder().facet(Facet.ACOUSTICS, "enabled", "strength").build(), PresetEngineTest.json("{\"enabled\":false,\"strength\":100}"));
        repository = new PresetRepository(temp.resolve("presets").toFile(), temp.resolve("presets/backups").toFile(), name -> null);
        service = new PresetService(new PresetEngine(Arrays.asList(hud, audio)), repository);
    }
    @Test void 套用前自动备份_撤销逐级回退() {
        StylePreset first = savedFromState("{\"glow\":0.9,\"color\":\"red\",\"lyricsShown\":true}"), second = savedFromState("{\"glow\":0.1,\"color\":\"blue\",\"lyricsShown\":true}");
        assertFalse(service.canUndo()); service.apply(first, EnumSet.of(Facet.LYRICS)); service.apply(second, EnumSet.of(Facet.LYRICS));
        assertEquals("blue", hud.state.get("color").getAsString()); assertEquals(2, repository.backupCount());
        assertNotNull(service.undo()); assertEquals("red", hud.state.get("color").getAsString());
        assertNotNull(service.undo()); assertEquals("white", hud.state.get("color").getAsString()); assertFalse(service.canUndo()); assertNull(service.undo());
    }
    @Test void 只分享音效_导入后只动音效() throws Exception {
        audio.state = PresetEngineTest.json("{\"enabled\":true,\"strength\":60}");
        StylePreset mine = service.saveCurrent("我的音效", Facet.ofGroup(Facet.Group.AUDIO)); assertFalse(mine.domains().containsKey("hud"));
        String share = service.shareText(mine); audio.state = audio.defaults(); hud.state.addProperty("color", "green");
        StylePreset imported = service.importText("群里：" + share + " 好听"); assertTrue(imported.meta().id().startsWith("user:")); assertNotEquals(mine.meta().id(), imported.meta().id());
        assertFalse(service.apply(imported, service.defaultSelection(imported)).hasFailures()); assertTrue(audio.state.get("enabled").getAsBoolean()); assertEquals(60, audio.state.get("strength").getAsInt()); assertEquals("green", hud.state.get("color").getAsString());
    }
    @Test void 默认勾选去掉需要主动勾选的分面() {
        StylePreset preset = new StylePreset(PresetEngineTest.meta(), StylePreset.Base.DEFAULTS, EnumSet.of(Facet.LYRICS, Facet.LAYOUT, Facet.ACOUSTICS), null, Collections.emptyMap(), StylePreset.Source.USER, null);
        assertEquals(EnumSet.of(Facet.LYRICS, Facet.ACOUSTICS), service.defaultSelection(preset));
    }
    @Test void 存为预设重名不覆盖且只删目录内的文件() throws Exception {
        StylePreset a = service.saveCurrent("同名", EnumSet.of(Facet.LYRICS)), b = service.saveCurrent("同名", EnumSet.of(Facet.LYRICS));
        assertNotEquals(a.file(), b.file()); assertEquals(2, service.userPresets().size()); assertTrue(service.delete(a));
        File outside = temp.resolve("outside" + PresetRepository.EXTENSION).toFile(); Files.writeString(outside.toPath(), "{}");
        assertFalse(service.delete(b.located(StylePreset.Source.USER, outside))); assertTrue(outside.exists());
        assertFalse(service.delete(b.located(StylePreset.Source.BUILTIN, b.file())));
    }
    @Test void 备份只留最近十份() {
        StylePreset preset = savedFromState("{\"glow\":0.9,\"color\":\"red\",\"lyricsShown\":true}");
        for (int i = 0; i < PresetRepository.BACKUP_KEEP + 3; i++) { hud.state = hud.defaults(); service.apply(preset, EnumSet.of(Facet.LYRICS)); }
        assertEquals(PresetRepository.BACKUP_KEEP, repository.backupCount());
    }
    @Test void 坏文件与超大文件跳过不拖垮列表() throws Exception {
        service.saveCurrent("好的", EnumSet.of(Facet.LYRICS)); Files.writeString(temp.resolve("presets/坏的" + PresetRepository.EXTENSION), "{oops");
        Files.writeString(temp.resolve("presets/过大" + PresetRepository.EXTENSION), "x".repeat(com.muoniumplayer.core.preset.codec.PresetLimits.MAX_JSON_CHARS + 1));
        assertEquals(1, service.userPresets().size());
    }
    @Test void 导入垃圾给出原因() { assertThrows(PresetFormatException.class, () -> service.importText("hello")); assertThrows(PresetFormatException.class, () -> service.importText("{\"format\":\"nope\"}")); }
    @Test void 内置预设按索引顺序读取() {
        PresetRepository source = new PresetRepository(temp.toFile(), temp.resolve("b").toFile(), name -> {
            String text = "index.json".equals(name) ? "[\"a.mpreset.json\",\"missing.mpreset.json\",\"b.mpreset.json\"]"
                    : "a.mpreset.json".equals(name) ? "{\"format\":\"muonium.preset\",\"meta\":{\"name\":\"甲\"}}"
                    : "b.mpreset.json".equals(name) ? "{\"format\":\"muonium.preset\",\"meta\":{\"name\":\"乙\"}}" : null;
            return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        });
        assertEquals(2, source.builtins().size()); assertEquals("甲", source.builtins().get(0).meta().name()); assertEquals("乙", source.builtins().get(1).meta().name());
    }
    @Test void 文件名去掉非法字符() {
        assertEquals("a_b_c", PresetRepository.slug("a/b\\c")); assertEquals("预设", PresetRepository.slug(" ... ")); assertEquals("流光玻璃", PresetRepository.slug("流光玻璃")); assertEquals("_CON", PresetRepository.slug("CON"));
    }
    @Test void 自动备份失败时不应用() throws Exception {
        Files.createDirectories(temp.resolve("presets")); Files.writeString(temp.resolve("presets/backups"), "not a directory");
        StylePreset preset = savedFromState("{\"glow\":0.9,\"color\":\"red\",\"lyricsShown\":true}");
        CompatReport report = service.apply(preset, EnumSet.of(Facet.LYRICS)); assertTrue(report.hasFailures()); assertEquals(0, hud.applyCount); assertEquals(hud.defaults(), hud.state);
    }
    @Test void 撤销失败保留备份且回滚现有状态() {
        StylePreset preset = savedFromState("{\"glow\":0.9,\"color\":\"red\",\"lyricsShown\":true}"); service.apply(preset, EnumSet.of(Facet.LYRICS));
        JsonObject before = hud.snapshot(); hud.failOnRestore = new IllegalStateException("fail"); hud.mutateBeforeFailure = true;
        CompatReport report = service.undo();
        assertTrue(report.hasFailures()); assertTrue(report.wasRolledBack()); assertFalse(report.rollbackFailed());
        assertEquals(before, hud.state); assertEquals(1, repository.backupCount());
        assertFalse(service.undo().hasFailures()); assertEquals(hud.defaults(), hud.state); assertFalse(service.canUndo());
    }
    @Test void 没有变化不消耗备份() { StylePreset preset = service.snapshot("相同", EnumSet.of(Facet.LYRICS)); service.apply(preset, preset.facets()); assertFalse(service.canUndo()); }
    @Test void atomic写失败不截断旧文件并清理临时文件() throws Exception {
        Path target = temp.resolve("atomic.json"); PresetRepository.writeAtomically(target.toFile(), "old");
        assertThrows(java.io.IOException.class, () -> PresetRepository.writeAtomically(target.toFile(), "x".repeat(600000)));
        assertEquals("old", Files.readString(target));
        try (var files = Files.list(temp)) { assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".tmp"))); }
    }
    @Test void JSON目录动作协议不泄露本地路径且支持完整本地恢复() throws Exception {
        StylePreset saved = service.saveCurrent("协议", EnumSet.of(Facet.LYRICS));
        JsonObject request = PresetEngineTest.json("{\"action\":\"detail\",\"id\":\"" + saved.meta().id() + "\"}");
        assertTrue(service.actionJson(request).get("ok").getAsBoolean()); assertFalse(service.catalogJson().toString().contains(temp.toString()));
        JsonObject state = service.captureState(); hud.state.addProperty("color", "red"); assertFalse(service.restoreState(state).hasFailures()); assertEquals("white", hud.state.get("color").getAsString());
    }
    private StylePreset savedFromState(String state) {
        JsonObject old = hud.snapshot(); hud.state = PresetEngineTest.json(state);
        try { return service.saveCurrent("快照", EnumSet.of(Facet.LYRICS)); } catch (Exception failure) { throw new AssertionError(failure); } finally { hud.state = old; }
    }
}
