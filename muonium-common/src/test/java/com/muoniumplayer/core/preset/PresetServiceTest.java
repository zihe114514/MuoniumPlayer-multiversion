package com.muoniumplayer.core.preset;

import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.preset.model.StylePreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 服务门面 + 文件仓库：备份 / 撤销、存为预设 → 分享码 → 导入的全链路，全在临时目录里跑。 */
class PresetServiceTest {

    @TempDir
    Path temp;

    private FakeDomain hud;
    private FakeDomain audio;
    private PresetService service;
    private PresetRepository repository;

    @BeforeEach
    void setUp() {
        hud = new FakeDomain("hud", KeyPolicy.builder()
                .facet(Facet.LYRICS, "glow", "color")
                .facet(Facet.VISIBILITY, "lyricsShown")
                .build(), PresetEngineTest.json("{\"glow\":0.5,\"color\":\"white\",\"lyricsShown\":true}"));
        audio = new FakeDomain("acoustics", KeyPolicy.builder()
                .facet(Facet.ACOUSTICS, "enabled", "strength")
                .build(), PresetEngineTest.json("{\"enabled\":false,\"strength\":100}"));
        repository = new PresetRepository(temp.resolve("presets").toFile(),
                temp.resolve("presets/backups").toFile(), name -> null);
        service = new PresetService(new PresetEngine(java.util.Arrays.asList(hud, audio)), repository);
    }

    @Test
    void 套用前自动备份_撤销逐级回退() {
        StylePreset first = savedFromState("{\"glow\":0.9,\"color\":\"red\",\"lyricsShown\":true}");
        StylePreset second = savedFromState("{\"glow\":0.1,\"color\":\"blue\",\"lyricsShown\":true}");
        hud.state = PresetEngineTest.json("{\"glow\":0.5,\"color\":\"white\",\"lyricsShown\":true}");

        assertFalse(service.canUndo());
        service.apply(first, EnumSet.of(Facet.LYRICS));
        service.apply(second, EnumSet.of(Facet.LYRICS));
        assertEquals("blue", hud.state.get("color").getAsString());
        assertEquals(2, repository.backupCount());

        assertNotNull(service.undo());
        assertEquals("red", hud.state.get("color").getAsString(), "退回第一次套用之后");
        assertNotNull(service.undo());
        assertEquals("white", hud.state.get("color").getAsString(), "退回最初");
        assertEquals(0.5, hud.state.get("glow").getAsDouble(), 1e-6);
        assertFalse(service.canUndo());
        assertNull(service.undo());
    }

    @Test
    void 只分享音效_导入后只动音效() throws Exception {
        audio.state = PresetEngineTest.json("{\"enabled\":true,\"strength\":60}");
        hud.state = PresetEngineTest.json("{\"glow\":0.9,\"color\":\"red\",\"lyricsShown\":true}");
        StylePreset mine = service.saveCurrent("我的音效", Facet.ofGroup(Facet.Group.AUDIO));
        assertEquals(EnumSet.of(Facet.VIPER, Facet.ACOUSTICS), mine.facets());
        assertFalse(mine.domains().containsKey("hud"), "只分享音效时外观一个键都不带");
        String share = service.shareText(mine);

        // 另一位玩家：状态各不相同。
        audio.state = PresetEngineTest.json("{\"enabled\":false,\"strength\":100}");
        hud.state = PresetEngineTest.json("{\"glow\":0.2,\"color\":\"green\",\"lyricsShown\":false}");
        StylePreset imported = service.importText("群里：" + share + " 好听");
        assertTrue(imported.meta().id().startsWith("user:"));
        assertNotEquals(mine.meta().id(), imported.meta().id(), "导入的一律换新 id");

        CompatReport report = service.apply(imported, service.defaultSelection(imported));
        assertFalse(report.hasFailures());
        assertTrue(audio.state.get("enabled").getAsBoolean());
        assertEquals(60, audio.state.get("strength").getAsInt());
        assertEquals("green", hud.state.get("color").getAsString(), "外观原样不动");
        assertFalse(hud.state.get("lyricsShown").getAsBoolean());
    }

    @Test
    void 默认勾选去掉需要主动勾选的分面() {
        StylePreset preset = new StylePreset(PresetEngineTest.meta(), StylePreset.Base.DEFAULTS,
                EnumSet.of(Facet.LYRICS, Facet.LAYOUT, Facet.ACOUSTICS), null, Collections.emptyMap(),
                StylePreset.Source.USER, null);
        assertEquals(EnumSet.of(Facet.LYRICS, Facet.ACOUSTICS), service.defaultSelection(preset),
                "布局默认不勾；这组假域里没有的分面也不出现");
    }

    @Test
    void 存为预设重名不覆盖且只删目录内的文件() throws Exception {
        StylePreset a = service.saveCurrent("同名", EnumSet.of(Facet.LYRICS));
        StylePreset b = service.saveCurrent("同名", EnumSet.of(Facet.LYRICS));
        assertNotEquals(a.file(), b.file());
        assertEquals(2, service.userPresets().size());
        assertTrue(service.delete(a));
        assertEquals(1, service.userPresets().size());

        File outside = temp.resolve("outside" + PresetRepository.EXTENSION).toFile();
        Files.write(outside.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        StylePreset forged = b.located(StylePreset.Source.USER, outside);
        assertFalse(service.delete(forged), "目录外的文件不许删");
        assertTrue(outside.exists());
        assertFalse(service.delete(b.located(StylePreset.Source.BUILTIN, b.file())), "内置不许删");
    }

    @Test
    void 备份只留最近十份() throws Exception {
        StylePreset preset = savedFromState("{\"glow\":0.9,\"color\":\"red\",\"lyricsShown\":true}");
        for (int i = 0; i < PresetRepository.BACKUP_KEEP + 3; i++) {
            service.apply(preset, EnumSet.of(Facet.LYRICS));
        }
        assertEquals(PresetRepository.BACKUP_KEEP, repository.backupCount());
    }

    @Test
    void 坏文件与超大文件跳过不拖垮列表() throws Exception {
        service.saveCurrent("好的", EnumSet.of(Facet.LYRICS));
        Files.write(temp.resolve("presets/坏的" + PresetRepository.EXTENSION),
                "{oops".getBytes(StandardCharsets.UTF_8));
        List<StylePreset> list = service.userPresets();
        assertEquals(1, list.size());
        assertEquals("好的", list.get(0).meta().name());
    }

    @Test
    void 导入垃圾给出原因() {
        assertThrows(PresetFormatException.class, () -> service.importText("hello"));
        assertThrows(PresetFormatException.class, () -> service.importText("{\"format\":\"nope\"}"));
    }

    @Test
    void 内置预设按索引顺序读取() {
        PresetRepository withBuiltins = new PresetRepository(temp.toFile(), temp.resolve("b").toFile(), name -> {
            String text;
            if ("index.json".equals(name)) {
                text = "[\"a.mpreset.json\",\"missing.mpreset.json\",\"b.mpreset.json\"]";
            } else if ("a.mpreset.json".equals(name)) {
                text = "{\"format\":\"muonium.preset\",\"meta\":{\"name\":\"甲\"}}";
            } else if ("b.mpreset.json".equals(name)) {
                text = "{\"format\":\"muonium.preset\",\"meta\":{\"name\":\"乙\"}}";
            } else {
                return null;
            }
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        });
        List<StylePreset> builtins = withBuiltins.builtins();
        assertEquals(2, builtins.size(), "缺失的那一份跳过");
        assertEquals("甲", builtins.get(0).meta().name());
        assertEquals(StylePreset.Source.BUILTIN, builtins.get(1).source());
    }

    @Test
    void 文件名去掉非法字符() {
        assertEquals("a_b_c", PresetRepository.slug("a/b\\c"));
        assertEquals("预设", PresetRepository.slug(" ... "));
        assertEquals("流光玻璃", PresetRepository.slug("流光玻璃"));
    }

    private StylePreset savedFromState(String state) {
        String before = hud.state.toString();
        hud.state = PresetEngineTest.json(state);
        try {
            return service.saveCurrent("快照", EnumSet.of(Facet.LYRICS));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            hud.state = PresetEngineTest.json(before);
        }
    }
}
