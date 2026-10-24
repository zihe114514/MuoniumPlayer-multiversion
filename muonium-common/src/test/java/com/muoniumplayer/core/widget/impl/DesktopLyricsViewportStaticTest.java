package com.muoniumplayer.core.widget.impl;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面歌词视口尺寸与自动换行的不变量。
 *
 * <p>只读源码，不触发 OpenAPI / 渲染线程初始化。</p>
 */
class DesktopLyricsViewportStaticTest {

    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();

    @Test
    @DisplayName("桌面歌词宽高持久化，并作为渲染与编辑器的唯一尺寸来源")
    void viewportSizeIsPersistedAndAuthoritative() throws IOException {
        String config = read(settings("HudConfig.java"));
        assertTrue(config.contains("public static float lyricWidth = 450.0f"));
        assertTrue(config.contains("public static float lyricHeight = 120.0f"));
        assertTrue(config.contains("getFloat(o, \"lyricWidth\", lyricWidth)"));
        assertTrue(config.contains("getFloat(o, \"lyricHeight\", lyricHeight)"));
        assertTrue(config.contains("o.addProperty(\"lyricWidth\", lyricWidth)"));
        assertTrue(config.contains("o.addProperty(\"lyricHeight\", lyricHeight)"));

        String setting = read(settings("HudSetting.java"));
        assertTrue(setting.contains("OSD_WIDTH(\"桌面歌词宽度\", 225.0f, 900.0f)"));
        assertTrue(setting.contains("OSD_HEIGHT(\"桌面歌词高度\", 60.0f, 480.0f)"));
        assertTrue(setting.contains("case OSD_WIDTH: return HudConfig.lyricWidth;"));
        assertTrue(setting.contains("case OSD_HEIGHT: return HudConfig.lyricHeight;"));
        assertTrue(setting.contains("case OSD_WIDTH: HudConfig.lyricWidth = clamped; break;"));
        assertTrue(setting.contains("case OSD_HEIGHT: HudConfig.lyricHeight = clamped; break;"));
    }

    @Test
    @DisplayName("渲染视口使用持久化宽高，原文与译文都按内容宽度换行")
    void renderingUsesViewportAndWrapsAllTextKinds() throws IOException {
        String widget = read(ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "widget", "impl", "MusicLyricsWidget.java")));
        assertTrue(widget.contains("float baseW = HudConfig.lyricWidth;"));
        assertTrue(widget.contains("float baseH = HudConfig.lyricHeight;"));

        int setWidth = widget.indexOf("wpsInterface.setWidth(baseW);");
        int setHeight = widget.indexOf("wpsInterface.setHeight(baseH);");
        int render = widget.indexOf("renderAllLyrics(");
        assertTrue(setWidth >= 0 && setHeight >= 0 && render > setWidth && render > setHeight,
                "必须先把换行视口写进 wrapper，再开始歌词排版");

        assertTrue(widget.contains("KaraokeLayoutBuilder.build(getFontRenderer(), line, getContentWidth())"),
                "逐字歌词必须按视口宽度切物理行");
        assertTrue(widget.contains("fitText(getFontRenderer(), line.getLyric())"),
                "普通原文必须自动换行");
        assertTrue(widget.contains("fitText(getSmallFontRenderer(), secondaryLyric)"),
                "译文/音译必须自动换行");
        assertTrue(widget.contains("wpsInterface.getWidth() - CONTENT_HORIZONTAL_PADDING * 2.0"),
                "换行宽度必须来自可自定义的视口宽度");
    }

    @Test
    @DisplayName("设置界面提供宽高，编辑器命中框使用同一尺寸")
    void bothSettingsSurfacesExposeViewportSize() throws IOException {
        String model = read(hud("HudSettingsModel.java"));
        assertTrue(model.contains("new Group(\"大小与换行\""));
        assertTrue(model.contains("slider(HudSetting.OSD_WIDTH"));
        assertTrue(model.contains("slider(HudSetting.OSD_HEIGHT"));
        assertTrue(model.contains("syncLyricViewportModuleValues()"));

        String editor = read(hud("GuiHudEditorPro.java"));
        assertTrue(editor.contains("HudConfig.lyricWidth * HudConfig.lyricScale"));
        assertTrue(editor.contains("HudConfig.lyricHeight * HudConfig.lyricScale"));
    }

    @Test
    @DisplayName("HUD 中心吸附降级为弱磁吸，不再 20px 内强制跳到中心")
    void centerSnappingIsSoftened() throws IOException {
        String stage = read(hud("HudOverlayStage.java"));
        assertTrue(stage.contains("SNAP_THRESHOLD = 8"));
        assertTrue(stage.contains("SNAP_STRENGTH = 0.35"));
        assertTrue(stage.contains("private static int softenSnap(int value, int target)"));
        assertTrue(stage.contains("nx = snapAxis(nx"));
        assertTrue(stage.contains("ny = snapAxis(ny"));
        assertFalse(stage.contains("if (snapX) nx = centerX;"));
        assertFalse(stage.contains("if (snapY) ny = centerY;"));
    }

    private static Path settings(String fileName) {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "settings", fileName));
    }

    private static Path hud(String fileName) {
        return ProjectPaths.sourceFile(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", fileName));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
