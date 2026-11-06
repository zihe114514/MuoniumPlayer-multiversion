package com.muoniumplayer.core.preset;

import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.preset.ui.PresetPanelSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面板会话：播放器里那张（组件树）和 H 键设置页里那张（手画）共用的内容与行为。
 * 两边长相不同，但点了什么、状态行说什么必须一样 —— 这里钉住的就是那一份。
 */
class PresetPanelSessionTest {

    @TempDir
    Path temp;

    private FakeDomain hud;
    private FakeDomain audio;
    private PresetService service;
    private FakeClipboard clipboard;
    private RecordingNavigator navigator;
    private PresetPanelSession session;

    private static final class FakeClipboard implements PresetPanelSession.Clipboard {
        String content = "";

        @Override
        public String get() {
            return content;
        }

        @Override
        public void set(String text) {
            content = text;
        }
    }

    private static final class RecordingNavigator implements PresetPanelSession.Navigator {
        final List<String> calls = new ArrayList<String>();
        StylePreset lastApply;

        @Override
        public void showMain(boolean backward) {
            calls.add(backward ? "main:back" : "main");
        }

        @Override
        public void showApply(StylePreset preset) {
            calls.add("apply:" + preset.meta().name());
            lastApply = preset;
        }
    }

    @BeforeEach
    void setUp() {
        hud = new FakeDomain("hud", KeyPolicy.builder()
                .facet(Facet.LYRICS, "glow", "color")
                .facet(Facet.VISIBILITY, "lyricsShown")
                .facet(Facet.LAYOUT, "lyricX")
                .build(), PresetEngineTest.json("{\"glow\":0.5,\"color\":\"white\",\"lyricsShown\":true,\"lyricX\":0.5}"));
        audio = new FakeDomain("acoustics", KeyPolicy.builder()
                .facet(Facet.ACOUSTICS, "enabled", "strength")
                .build(), PresetEngineTest.json("{\"enabled\":false,\"strength\":100}"));
        PresetRepository repository = new PresetRepository(temp.resolve("presets").toFile(),
                temp.resolve("presets/backups").toFile(), name -> null);
        service = new PresetService(new PresetEngine(Arrays.asList(hud, audio)), repository);
        clipboard = new FakeClipboard();
        navigator = new RecordingNavigator();
        session = new PresetPanelSession(service, clipboard);
        session.setNavigator(navigator);
        session.reset();
    }

    /** 作者那边调好后存一份，再把本机状态拨回出厂，模拟「另一个人」。 */
    private StylePreset authored() throws Exception {
        hud.state = PresetEngineTest.json("{\"glow\":0.9,\"color\":\"white\",\"lyricsShown\":true,\"lyricX\":0.2}");
        StylePreset preset = service.saveCurrent("夜间",
                EnumSet.of(Facet.LYRICS, Facet.VISIBILITY, Facet.LAYOUT, Facet.ACOUSTICS));
        hud.state = hud.defaults();
        return preset;
    }

    @Test
    void 进套用页默认不勾布局_预检数字跟着勾选变() throws Exception {
        StylePreset preset = authored();
        session.enterApply(preset);
        assertEquals(PresetPanelSession.Page.APPLY, session.page());
        assertTrue(session.isSelected(Facet.LYRICS));
        assertFalse(session.isSelected(Facet.LAYOUT), "布局位置要主动勾");
        assertEquals(1, session.previewChanges(), "只有发光强度不同");
        assertTrue(session.previewStatus().startsWith("将改动 1 项设置"), session.previewStatus());
        assertEquals(PresetPanelSession.Tone.OK, session.previewTone());
        assertEquals("套用 1 项", session.applyLabel());

        session.selectAll();
        assertTrue(session.isSelected(Facet.LAYOUT));
        assertEquals(2, session.previewChanges(), "全选后连位置一起算");

        session.selectAudioOnly();
        assertEquals(0, session.previewChanges());
        assertEquals("所选部分和你现在的配置一样，套用不会有变化", session.previewStatus());
        assertEquals(PresetPanelSession.Tone.INFO, session.previewTone());
        assertEquals("套用", session.applyLabel());

        session.toggle(Facet.ACOUSTICS);
        assertEquals("先勾选要套用的部分", session.previewStatus());
        assertEquals(PresetPanelSession.Tone.WARN, session.previewTone());

        session.selectAppearanceOnly();
        assertTrue(session.isSelected(Facet.LYRICS) && !session.isSelected(Facet.LAYOUT)
                && !session.isSelected(Facet.ACOUSTICS), "只选外观也不带需要主动勾的分面");
    }

    @Test
    void 套用后回主页_撤销逐级回退() throws Exception {
        StylePreset preset = authored();
        session.enterApply(preset);
        session.applyTarget();
        assertEquals(Arrays.asList("main:back"), navigator.calls, "套完回主页，按回退的方向翻");
        assertEquals(PresetPanelSession.Tone.OK, session.statusTone());
        assertTrue(session.statusText().startsWith("已套用「夜间」· 改动 1 项"), session.statusText());
        assertEquals(0.9, hud.state.get("glow").getAsDouble(), 1e-6);
        assertEquals(0.5, hud.state.get("lyricX").getAsDouble(), 1e-6, "没勾的布局不动");
        assertTrue(session.canUndo());
        assertEquals(PresetPanelSession.UNDO_TIP_READY, session.undoTooltip());

        session.undo();
        assertEquals("已撤销上一次套用", session.statusText());
        assertEquals(0.5, hud.state.get("glow").getAsDouble(), 1e-6);
        session.undo();
        assertEquals("还没有可以撤销的套用", session.statusText());
        assertEquals(PresetPanelSession.UNDO_TIP_EMPTY, session.undoTooltip());
    }

    @Test
    void 存为预设_先起名再勾选_保存并复制() {
        session.enterSave();
        assertFalse(session.isSaveSelected(Facet.LAYOUT), "存为预设也默认不带布局");
        assertTrue(session.isSaveSelected(Facet.LYRICS));

        session.save("   ", false);
        assertEquals("先起个名字", session.saveHint());
        assertTrue(session.saveHintBad());

        for (Facet facet : Facet.values()) {
            if (session.isSaveSelected(facet)) session.toggleSave(facet);
        }
        assertEquals("至少勾选一个部分", session.saveHint());
        session.save("直播用", false);
        assertEquals("至少勾选一个部分", session.saveHint());
        assertTrue(navigator.calls.isEmpty(), "没存成就不该翻页");

        session.saveSelectAppearanceOnly();
        assertFalse(session.saveHintBad());
        assertTrue(session.saveHint().startsWith("已选 "), session.saveHint());

        int before = session.revision();
        hud.state.addProperty("glow", 0.7);
        session.save("直播用", true);
        assertTrue(clipboard.content.contains("MPP2:"), "复制出去的是短码：" + clipboard.content);
        assertTrue(session.statusText().startsWith("已保存「直播用」并复制分享码"), session.statusText());
        assertEquals(before + 1, session.revision(), "列表变了，画法要重建主页");
        assertEquals(Arrays.asList("main:back"), navigator.calls);
        assertEquals(1, session.mine().size());
        assertEquals("直播用", session.mine().get(0).meta().name());
    }

    @Test
    void 粘贴导入_垃圾给原因_分享码直接进套用页() throws Exception {
        clipboard.content = "今天天气不错";
        session.importFromClipboard();
        assertEquals(PresetPanelSession.Tone.BAD, session.statusTone());
        assertTrue(navigator.calls.isEmpty());

        StylePreset preset = authored();
        clipboard.content = "快试试 " + service.shareText(preset) + " 好看吗";
        int before = session.revision();
        session.importFromClipboard();
        assertEquals(Arrays.asList("apply:夜间"), navigator.calls);
        assertEquals(PresetPanelSession.Tone.OK, session.statusTone());
        assertEquals(before + 1, session.revision());
        assertTrue(navigator.lastApply.meta().id().startsWith("user:"));
    }

    @Test
    void 复制分享码与删除() throws Exception {
        StylePreset preset = authored();
        session.copyShareCode(preset);
        assertTrue(clipboard.content.contains("MPP2:"));
        assertTrue(session.statusText().startsWith("已复制「夜间」的分享码"), session.statusText());

        int before = session.revision();
        session.delete(preset);
        assertEquals("已删除「夜间」", session.statusText());
        assertEquals(before + 1, session.revision());
        assertTrue(session.mine().isEmpty());
        assertTrue(PresetPanelSession.deleteMessage(preset).contains("「夜间」"));
    }

    @Test
    void 提示行超过上限并成一句() {
        assertEquals("", session.noteLine(0), "没有提示就是空行");
        assertEquals("我的预设", session.mineSectionTitle(0));
        assertEquals("我的预设（3）", session.mineSectionTitle(3));
    }
}
