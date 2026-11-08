package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PresetUiWiringStaticTest {
    private static final String HUD = "com/muoniumplayer/core/screens/hud/";
    private static final List<String> VERSIONS = Arrays.asList("1.20.1 recode", "1.21.1", "1.21.10", "1.21.11 recode", "26.1.2", "26.2", "26.3");

    private String source(String file) throws IOException {
        return Files.readString(ProjectPaths.sourceFile(HUD + file));
    }

    @Test
    void allSevenModelsHaveTheSameIndependentPresetCategoryAndActions() throws IOException {
        String commonBlock = null;
        for (String version : VERSIONS) {
            Path file = ProjectPaths.repoRoot().resolve(version).resolve("src/main/java/" + HUD + "HudSettingsModel.java");
            String text = Files.readString(file);
            int start = text.indexOf("new Category(\"presets\", \"模板预设\"");
            assertTrue(start >= 0, version);
            int end = text.indexOf("new Category(\"缓存与公告\"", start);
            assertTrue(end > start, version);
            String block = text.substring(start, end).trim();
            if (commonBlock == null) commonBlock = block;
            else assertEquals(commonBlock, block, version);
            for (String action : Arrays.asList("管理模板预设", "保存当前为模板", "撤销上次套用", "导入模板"))
                assertTrue(block.contains("action(\"" + action + "\""), version + "/" + action);
            assertTrue(text.contains("public final String id;"), version);
            assertTrue(text.contains("public static void openPresetSettings(PresetSettingsActions.Mode mode)"), version);
            assertTrue(text.contains("new GuiPresetSettings(() -> client."), version);
            // Opening APIs really differ at 26.2, not at the 26.x lane boundary.
            assertTrue(text.contains(version.equals("26.2") || version.equals("26.3")
                    ? "Screen parent = client.gui.screen();" : "Screen parent = client.screen;"), version);
        }
    }

    @Test
    void rootPlayerHasNoPresetEntryAndIndependentUiDoesNotUseItsInstance() throws IOException {
        String panel = source("PresetSettingsPanel.java");
        String host = source("GuiPresetSettings.java");
        String actions = source("PresetSettingsActions.java");
        for (String text : Arrays.asList(panel, host, actions)) {
            assertFalse(text.contains("NCMScreen.getInstance"));
            assertFalse(text.contains("openConfirmation"));
            assertFalse(text.contains("PresetPanelSession.standard"));
            assertFalse(text.contains("net.fabricmc"));
            assertFalse(text.contains("net.neoforged"));
            assertFalse(text.contains("net.minecraftforge"));
        }
        for (String file : Arrays.asList("com/muoniumplayer/core/screens/ncm/NCMScreen.java",
                "com/muoniumplayer/core/screens/ncm/panels/NavigateBar.java")) {
            String text = Files.readString(ProjectPaths.sourceFile(file));
            assertFalse(text.contains("PresetOverlay"), file);
            assertFalse(text.contains("openPreset"), file);
            assertFalse(text.contains("PresetSettingsActions"), file);
        }
        assertTrue(host.contains("extends GuiScreen"));
        assertTrue(host.contains("new PresetPanelSession(PresetService.get()"));
        assertTrue(host.contains("session.setHost("));
        assertFalse(host.contains("net.minecraft."));
    }

    @Test
    void hostMapsMouseAndRenderTogetherAndKeepsNativeCallbacksOffClosedPages() throws IOException {
        String host = source("GuiPresetSettings.java");
        assertTrue(host.contains("RenderSystem.refreshResolution()"));
        assertTrue(host.contains("WindowAdaptiveScale.beginForced()"));
        assertTrue(host.contains("SkijaGfx.scale(sx, sy)"));
        assertTrue(host.contains("panel.renderWidget(mouseX / sx, mouseY / sy, 0)"));
        assertTrue(host.contains("panel.onMouseClickReceived(mouseX / sx, mouseY / sy, mouseButton)"));
        assertTrue(host.contains("panel.onDWheelReceived(mouseX / sx, mouseY / sy, wheel)"));
        assertTrue(host.contains("panel.onTickReceived()"));
        assertTrue(host.contains("MultiThreadingUtil.runAsync"));
        assertTrue(host.contains("MultiThreadingUtil.runOnMainThread"));
        assertTrue(host.contains("panel.acceptsCallback(ticket)"));
        assertTrue(host.contains("closed = true"));
        assertTrue(host.contains("returnToParent.run()"));
        for (String vanilla : Arrays.asList("drawCenteredString", "drawRect(", "GuiButton", "fontRendererObj"))
            assertFalse(host.contains(vanilla), vanilla);
        assertTrue(source("PresetSettingsActions.java").contains("MultiThreadingUtil.runOnMainThread"));
    }

    @Test
    void everyInputAndActionCanBeReachedWithKeyboardAndScroll() throws IOException {
        String panel = source("PresetSettingsPanel.java");
        for (String key : Arrays.asList("KEY_TAB", "KEY_RETURN", "KEY_NUMPADENTER", "KEY_ESCAPE", "KEY_HOME", "KEY_END", "KEY_UP", "KEY_DOWN"))
            assertTrue(panel.contains(key), key);
        assertTrue(panel.contains("revealFocus()"));
        assertTrue(panel.contains("scrollToOffsetImmediately"));
        assertTrue(panel.contains("content = feed(body(dialog"));
        assertTrue(panel.contains("nameInput = input("));
        assertTrue(panel.contains("importInput = input("));
        assertTrue(panel.contains("session.save(nameText(), false)"));
        assertTrue(panel.contains("View.DELETE"));
        assertTrue(panel.contains("确认删除"));
        assertTrue(panel.contains("PresetSettingsLayout.footerButton("));
        assertTrue(panel.contains("card.setTransformations(null)"));
        assertTrue(panel.contains("rowHeights.entrySet()"), "Off-screen rows must be measured before the ScrollPanel culls them");
        assertTrue(panel.contains("while (row.getParent()"), "Keyboard navigation uses the owning row, including unrendered buttons");
        assertFalse(panel.contains("reveal) *"), "Entrance must not move card hit boxes");
        int measurement = panel.indexOf("rowHeights.entrySet()");
        assertTrue(panel.indexOf("super.onRender(mouseX, mouseY)", measurement) > measurement);
        assertFalse(panel.contains("void onRender(double mouseX, double mouseY) {\n        refreshCatalog"), "Catalog reads belong to actions, not frames");
    }

    @Test
    void fontWarningRespectsColdPolicyAndOptInFacetsRemainExplicit() throws IOException {
        String panel = source("PresetSettingsPanel.java");
        assertTrue(panel.contains("FONT_NOTE"));
        assertTrue(panel.contains("冷字体策略"));
        assertTrue(panel.contains("需重启游戏"));
        assertFalse(panel.contains("UiFontLibrary.select"));
        assertFalse(panel.contains("applyPresetId"));
        String session = Files.readString(ProjectPaths.sourceFile("com/muoniumplayer/core/preset/ui/PresetPanelSession.java"));
        assertTrue(session.contains("service.defaultSelection(preset)"));
        String facet = Files.readString(ProjectPaths.sourceFile("com/muoniumplayer/core/preset/model/Facet.java"));
        assertTrue(facet.contains("\"布局位置\", Group.APPEARANCE, true"));
        assertTrue(facet.contains("\"画质性能\", Group.APPEARANCE, true"));
    }

    @Test
    void keyboardCanRevealEveryWrappedButtonEvenIn640x360Window() {
        double width = PresetSettingsLayout.sheetWidth(320);
        double viewport = PresetSettingsLayout.body(320, 180).height;
        double scroll = 0;
        for (int index : new int[]{0, 1, 2, 3, 4, 5, 0}) {
            PresetSettingsLayout.Box button = PresetSettingsLayout.footerButton(width, index, 6);
            double top = 400 + button.y;
            scroll = PresetSettingsLayout.revealOffset(scroll, top, button.height, viewport);
            assertTrue(top >= scroll);
            assertTrue(top + button.height <= scroll + viewport);
        }
        assertEquals(20, PresetSettingsLayout.revealOffset(20, 30, 20, 80));
    }

    @Test
    void footerWrapsWithoutOverlapFor1600_960_640Windows() {
        for (double physicalWidth : new double[]{1600, 960, 640}) {
            double width = PresetSettingsLayout.sheetWidth(physicalWidth / 2);
            for (int count : new int[]{2, 4, 6}) {
                assertTrue(PresetSettingsLayout.footerColumns(width, count) <= 3);
                double height = PresetSettingsLayout.footerHeight(width, count);
                for (int i = 0; i < count; i++) {
                    PresetSettingsLayout.Box box = PresetSettingsLayout.footerButton(width, i, count);
                    assertTrue(box.x >= PresetSettingsLayout.INSET);
                    assertTrue(box.x + box.width <= width - PresetSettingsLayout.INSET + .001);
                    assertTrue(box.y + box.height <= height);
                    assertTrue(box.width >= 108, "Buttons must not reduce to clipped labels at " + physicalWidth);
                    assertTrue(box.contains(box.x + box.width / 2, box.y + box.height / 2));
                    for (int j = 0; j < i; j++) {
                        PresetSettingsLayout.Box other = PresetSettingsLayout.footerButton(width, j, count);
                        assertTrue(box.x >= other.x + other.width || other.x >= box.x + box.width
                                || box.y >= other.y + other.height || other.y >= box.y + box.height);
                    }
                }
            }
            // Inputs and wrapped footer are in this scroll viewport, not a squeezed fixed footer.
            PresetSettingsLayout.Box body = PresetSettingsLayout.body(physicalWidth / 2, physicalWidth * 9 / 32);
            assertTrue(body.height >= 50, "Even the 640x360 window needs a usable scroll body");
        }
    }
}
