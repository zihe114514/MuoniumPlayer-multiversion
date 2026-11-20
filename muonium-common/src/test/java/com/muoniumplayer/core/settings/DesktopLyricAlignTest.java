package com.muoniumplayer.core.settings;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面歌词对齐设置的不变量。
 *
 * <p>只读源码 + 纯枚举映射，不初始化渲染线程、不落盘（{@link DesktopLyricSetting#set}
 * 会写文件，测试里刻意不碰它，免得在仓库根留下 {@code config/}）。</p>
 */
class DesktopLyricAlignTest {

    private static final Path SHARED_JAVA = ProjectPaths.sharedJava();

    @Test
    @DisplayName("对齐词汇就是渲染层比的 Left/Center/Right，坏值退回居中")
    void vocabularyMatchesRendererAndBadValuesFallBack() {
        assertEquals("Left", DesktopLyricSetting.Align.LEFT.mode());
        assertEquals("Center", DesktopLyricSetting.Align.CENTER.mode());
        assertEquals("Right", DesktopLyricSetting.Align.RIGHT.mode());

        assertEquals(DesktopLyricSetting.Align.LEFT, DesktopLyricSetting.Align.ofMode("left"));
        assertEquals(DesktopLyricSetting.Align.RIGHT, DesktopLyricSetting.Align.ofMode(" Right "));
        // 手改坏的配置只能退化成默认对齐，不能让桌面歌词整条消失。
        assertEquals(DesktopLyricSetting.Align.CENTER, DesktopLyricSetting.Align.ofMode("Diagonal"));
        assertEquals(DesktopLyricSetting.Align.CENTER, DesktopLyricSetting.Align.ofMode(null));

        assertEquals("左靠", DesktopLyricSetting.Align.LEFT.label());
        assertEquals("居中", DesktopLyricSetting.Align.CENTER.label());
        assertEquals("右靠", DesktopLyricSetting.Align.RIGHT.label());
        assertEquals(DesktopLyricSetting.Align.RIGHT,
                DesktopLyricSetting.Align.ofLabel("右靠"));
        assertNull(DesktopLyricSetting.Align.ofLabel("左"));
    }

    @Test
    @DisplayName("默认居中：升级上来的用户画面不变，且选项按左→中→右排列")
    void defaultsToCenterAndOffersThreeChoices() {
        assertEquals(DesktopLyricSetting.Align.CENTER, DesktopLyricSetting.get());
        assertEquals("Center", DesktopLyricSetting.modeName());
        String[] labels = DesktopLyricSetting.labels();
        assertEquals(3, labels.length);
        assertEquals("左靠", labels[0]);
        assertEquals("居中", labels[1]);
        assertEquals("右靠", labels[2]);
    }

    @Test
    @DisplayName("桌面歌词渲染读共享设置，而不再只认组件内部的 ModeValue")
    void renderingUsesSharedSetting() throws IOException {
        String widget = read(SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "widget", "impl", "MusicLyricsWidget.java")));

        assertTrue(widget.contains("duetAlignOverride != null ? duetAlignOverride : DesktopLyricSetting.modeName()"),
                "非对唱行必须按用户选的对齐方式落点");
        assertFalse(widget.contains("? duetAlignOverride : this.alignMode.getValue()"),
                "对齐的权威值不能还是组件内部的 ModeValue（设置页写的是共享配置）");
        assertTrue(widget.contains("alignMode.setValue(DesktopLyricSetting.modeName())"),
                "组件模块值必须镜像共享设置，避免旧消费者拿到过期值");

        // 默认样式其实是 Blossom 卡片（HudConfig.osdLyricStyle = 1），只改经典样式等于没改。
        String blossom = read(SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "widget", "impl", "BlossomLyricStyle.java")));
        assertTrue(blossom.contains("alignOffset(rowWidth, wrapW)"),
                "Blossom 的逐字行也必须按设置落点（折行后每行宽度不同，不能用整块偏移）");
        assertTrue(blossom.contains("alignOffset(font.getStringWidthD(row), wrapW)"),
                "Blossom 的行级歌词同样要对齐");
        assertTrue(blossom.contains("scaleAtPos(x + alignAnchor(wrapW)"),
                "缩放锚点要跟着对齐走，否则居中时当前行放大会往右漂");
    }

    @Test
    @DisplayName("设置页提供左靠/居中/右靠三档，并落到共享配置")
    void settingsPageExposesTheChoice() throws IOException {
        Path model = ProjectPaths.versionJava().resolve(Paths.get(
                "com", "muoniumplayer", "core", "screens", "hud", "HudSettingsModel.java"));
        assertTrue(Files.isRegularFile(model), "找不到本树的 HudSettingsModel：" + model);
        String source = read(model);

        assertTrue(source.contains("osdAlignChoice()"), "设置页必须挂上「桌面歌词对齐」这一项");
        assertTrue(source.contains("DesktopLyricSetting.labels()"), "三档选项要从共享配置里取，别手写一份");
        assertTrue(source.contains("DesktopLyricSetting.setLabel(label)"), "选中值必须写回共享配置");
        // 「面板与卡片」页的样式与大小组：宽高旁边就是它。
        int group = source.indexOf("new Group(\"样式与大小\"");
        int entry = source.indexOf("osdAlignChoice()");
        assertTrue(group >= 0 && entry > group, "这一项要放在「样式与大小」组里宽高后面");
    }

    @Test
    @DisplayName("对齐落在自己的 desktop_lyrics.json，不往七份 HudConfig 里塞字段")
    void persistsToItsOwnSharedFile() throws IOException {
        String paths = read(SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "settings", "ConfigPaths.java")));
        assertTrue(paths.contains("desktop_lyrics.json"), "共享配置文件名要登记在 ConfigPaths");

        String setting = read(SHARED_JAVA.resolve(Paths.get(
                "com", "muoniumplayer", "core", "settings", "DesktopLyricSetting.java")));
        assertTrue(setting.contains("ConfigPaths.DESKTOP_LYRIC"));
        assertTrue(setting.contains("o.addProperty(\"align\""));
    }

    private static String read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "找不到文件：" + file);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
