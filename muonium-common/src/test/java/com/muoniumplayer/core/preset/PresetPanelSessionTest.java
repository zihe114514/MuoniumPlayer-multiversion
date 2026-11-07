package com.muoniumplayer.core.preset;

import com.muoniumplayer.core.preset.model.*;
import com.muoniumplayer.core.preset.ui.PresetPanelSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PresetPanelSessionTest {
    @TempDir Path temp;
    private FakeDomain hud, audio;
    private PresetService service;
    private FakeClipboard clipboard;
    private RecordingNavigator navigator;
    private PresetPanelSession session;
    private static final class FakeClipboard implements PresetPanelSession.Clipboard {
        String content = ""; public String get() { return content; } public void set(String text) { content = text; }
    }
    private static final class RecordingNavigator implements PresetPanelSession.Navigator {
        final List<String> calls = new ArrayList<>(); StylePreset lastApply;
        public void showMain(boolean backward) { calls.add(backward ? "main:back" : "main"); }
        public void showApply(StylePreset preset) { calls.add("apply:" + preset.meta().name()); lastApply = preset; }
    }
    @BeforeEach void setUp() {
        hud = new FakeDomain("hud", KeyPolicy.builder().facet(Facet.LYRICS, "glow", "color").facet(Facet.VISIBILITY, "lyricsShown")
                .facet(Facet.LAYOUT, "lyricX").build(), PresetEngineTest.json("{\"glow\":0.5,\"color\":\"white\",\"lyricsShown\":true,\"lyricX\":0.5}"));
        audio = new FakeDomain("acoustics", KeyPolicy.builder().facet(Facet.ACOUSTICS, "enabled", "strength").build(), PresetEngineTest.json("{\"enabled\":false,\"strength\":100}"));
        service = new PresetService(new PresetEngine(Arrays.asList(hud, audio)), new PresetRepository(temp.resolve("presets").toFile(), temp.resolve("presets/backups").toFile(), name -> null));
        clipboard = new FakeClipboard(); navigator = new RecordingNavigator(); session = new PresetPanelSession(service, clipboard); session.setNavigator(navigator); session.reset();
    }
    private StylePreset authored() throws Exception {
        hud.state.addProperty("glow", .9); hud.state.addProperty("lyricX", .2);
        StylePreset preset = service.saveCurrent("夜间", EnumSet.of(Facet.LYRICS, Facet.VISIBILITY, Facet.LAYOUT, Facet.ACOUSTICS)); hud.state = hud.defaults(); return preset;
    }
    @Test void 进套用页默认不勾布局_预检数字跟着勾选变() throws Exception {
        session.enterApply(authored()); assertEquals(PresetPanelSession.Page.APPLY, session.page()); assertTrue(session.isSelected(Facet.LYRICS)); assertFalse(session.isSelected(Facet.LAYOUT));
        assertEquals(1, session.previewChanges()); assertTrue(session.previewStatus().startsWith("将改动 1 项设置")); assertEquals(PresetPanelSession.Tone.OK, session.previewTone()); assertEquals("套用 1 项", session.applyLabel());
        session.selectAll(); assertEquals(2, session.previewChanges()); session.selectAudioOnly(); assertEquals(0, session.previewChanges()); assertEquals("所选部分和你现在的配置一样，套用不会有变化", session.previewStatus());
        session.toggle(Facet.ACOUSTICS); assertEquals("先勾选要套用的部分", session.previewStatus()); assertEquals(PresetPanelSession.Tone.WARN, session.previewTone());
        session.selectAppearanceOnly(); assertTrue(session.isSelected(Facet.LYRICS)); assertFalse(session.isSelected(Facet.LAYOUT)); assertFalse(session.isSelected(Facet.ACOUSTICS));
    }
    @Test void 套用后回主页_撤销逐级回退() throws Exception {
        session.enterApply(authored()); session.applyTarget(); assertEquals(Arrays.asList("main:back"), navigator.calls); assertEquals(PresetPanelSession.Tone.OK, session.statusTone());
        assertTrue(session.statusText().startsWith("已套用「夜间」· 改动 1 项")); assertEquals(.9, hud.state.get("glow").getAsDouble()); assertEquals(.5, hud.state.get("lyricX").getAsDouble());
        assertTrue(session.canUndo()); assertEquals(PresetPanelSession.UNDO_TIP_READY, session.undoTooltip()); session.undo(); assertEquals("已撤销上一次套用", session.statusText());
        session.undo(); assertEquals("还没有可以撤销的套用", session.statusText());
    }
    @Test void 存为预设_先起名再勾选_保存并复制() {
        session.enterSave(); assertFalse(session.isSaveSelected(Facet.LAYOUT)); session.save("  ", false); assertEquals("先起个名字", session.saveHint());
        for (Facet facet : Facet.values()) if (session.isSaveSelected(facet)) session.toggleSave(facet);
        session.save("直播用", false); assertEquals("至少勾选一个部分", session.saveHint()); assertTrue(navigator.calls.isEmpty());
        session.saveSelectAppearanceOnly(); assertFalse(session.saveHintBad()); hud.state.addProperty("glow", .7); int old = session.revision(); session.save("直播用", true);
        assertTrue(clipboard.content.contains("MPP2:")); assertTrue(session.statusText().startsWith("已保存「直播用」并复制分享码")); assertEquals(old + 1, session.revision()); assertEquals(1, session.mine().size());
    }
    @Test void 粘贴导入_垃圾给原因_分享码直接进套用页() throws Exception {
        clipboard.content = "今天天气不错"; session.importFromClipboard(); assertEquals(PresetPanelSession.Tone.BAD, session.statusTone()); assertTrue(navigator.calls.isEmpty());
        clipboard.content = service.shareText(authored()); int before = session.revision(); session.importFromClipboard();
        assertEquals(Arrays.asList("apply:夜间"), navigator.calls); assertEquals(before + 1, session.revision()); assertTrue(navigator.lastApply.meta().id().startsWith("user:"));
    }
    @Test void 复制分享码与删除() throws Exception {
        StylePreset preset = authored(); session.copyShareCode(preset); assertTrue(clipboard.content.contains("MPP2:")); assertTrue(session.statusText().startsWith("已复制「夜间」的分享码"));
        int before = session.revision(); session.delete(preset); assertEquals("已删除「夜间」", session.statusText()); assertEquals(before + 1, session.revision()); assertTrue(session.mine().isEmpty()); assertTrue(PresetPanelSession.deleteMessage(preset).contains("「夜间」"));
    }
    @Test void 提示行超过上限并成一句() { assertEquals("", session.noteLine(0)); assertEquals("我的预设", session.mineSectionTitle(0)); assertEquals("我的预设（3）", session.mineSectionTitle(3)); }
    @Test void 未注入宿主不启动文件框或播放器() {
        session.browseForFile(); assertFalse(session.picking()); assertEquals(PresetPanelSession.Tone.BAD, session.statusTone());
        assertNotNull(session.stateJson()); assertEquals("main", session.stateJson().get("page").getAsString());
    }
}
