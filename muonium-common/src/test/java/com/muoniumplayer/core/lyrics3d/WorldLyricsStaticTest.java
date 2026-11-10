package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3D 世界歌词的源码规约。渲染期行为单元测不到，按仓库惯例钉源码。
 */
class WorldLyricsStaticTest {

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private static Path shared(String name) {
        return ProjectPaths.sharedJava().resolve(
                "com/muoniumplayer/core/lyrics3d").resolve(name);
    }

    @Test
    @DisplayName("3D 歌词的发光必须走 TextGlow 单一入口，不得自写模糊")
    void glowGoesThroughTextGlowOnly() throws IOException {
        String atlas = read(shared("LyricGlyphAtlas.java"));
        // 晕/墨分离后晕格烘焙走 drawGlowOn（独立晕格的 σ 链/能量口径）；
        // 墨格不再含光。两个入口都是 TextGlow 的实现，自写模糊仍然禁止。
        assertTrue(atlas.contains("TextGlow.drawGlowOn("),
                "晕格烘焙必须走 TextGlow.drawGlowOn（晕/墨分离的独立晕格口）");
        assertTrue(!atlas.contains("TextGlow.drawOn("),
                "墨格不得再烘光（晕/墨分离）——旧的烘进同一张格的 drawOn 路径应摘除");
        // MaskFilter / dropShadow 自写模糊是 docs/歌词发光-高斯重写.md 之前清掉的路子
        assertTrue(!atlas.contains("MaskFilter"),
                "不得在 3D 歌词里自建 MaskFilter 模糊——发光只能经 TextGlow");
    }

    @Test
    @DisplayName("发光强度只传一次：烘焙层不得再乘非量化系数")
    void glowStrengthIsNotMultipliedTwice() throws IOException {
        String atlas = read(shared("LyricGlyphAtlas.java"));
        // 缓存键里的量化乘法（glowStrength * 64f）是允许的；禁止的是对传入 drawGlowOn 的
        // 强度值再乘一个观感系数（Apple 样式的坑）。
        String body = atlas.substring(atlas.indexOf("private static GlyphCell bakeGlowLocked"));
        assertTrue(!body.matches("(?s).*glowStrength\\s*\\*\\s*[0-9].*"),
                "bakeGlowLocked 内不得对 glowStrength 做任何乘法——强度语义已含在 "
                        + "TextGlow.drawGlowOn 的参数里，二次乘是 Apple 样式踩过的坑"
                        + "（docs/歌词发光-高斯重写.md）");
        assertTrue(body.contains("glowStrength, glowSpread"),
                "glowStrength 必须原样传给 drawGlowOn");
    }

    @Test
    @DisplayName("HudSetting 的 3D 歌词条目必须接在枚举末尾")
    void hudSettingEntriesStayAtTail() throws IOException {
        Path setting = ProjectPaths.versionJava()
                .resolve("com/muoniumplayer/core/settings/HudSetting.java");
        String src = read(setting);
        int spectrum = src.indexOf("SPECTRUM_BAR_COUNT_CUSTOM");
        int world = src.indexOf("WORLD_LYRICS_DISTANCE");
        assertTrue(spectrum >= 0 && world > spectrum,
                "HudSetting 新条目必须追加在末尾：这套条目 id 有按序号落盘的历史，"
                        + "插中间会让老配置滑块错位");
        // 3D 条目之后不得再有任何普通条目（WORLD_LYRICS_GLOW_SPREAD 带分号收尾）
        int tail = src.indexOf("WORLD_LYRICS_GLOW_SPREAD");
        assertTrue(tail > world);
        String afterTail = src.substring(tail);
        assertTrue(afterTail.contains(";"),
                "WORLD_LYRICS_GLOW_SPREAD 必须是最后一个条目（分号收尾）");
        String enumBody = afterTail.substring(afterTail.indexOf(';') + 1,
                Math.max(afterTail.indexOf(';') + 1, afterTail.indexOf("private final String label")));
        assertTrue(!enumBody.matches("(?s)\\s*\\w+\\(\".*"),
                "分号之后枚举里不得再有条目——又插回去就破坏了序号落盘");
    }

    @Test
    @DisplayName("common 的 3D 歌词层不碰 net.minecraft")
    void commonLyrics3dPackageIsMcFree() throws IOException {
        Path dir = ProjectPaths.sharedJava().resolve("com/muoniumplayer/core/lyrics3d");
        assertTrue(Files.isDirectory(dir), "core/lyrics3d 包必须在共享层");
        for (Path root : java.util.Arrays.asList(ProjectPaths.sharedJava(), ProjectPaths.sharedKotlin())) {
            Path packageDir = root.resolve("com/muoniumplayer/core/lyrics3d");
            if (!Files.isDirectory(packageDir)) {
                continue;
            }
            try (var files = Files.walk(packageDir)) {
                files.filter(ProjectPaths::isSourceFile).forEach(p -> {
                    try {
                        String src = Files.readString(p);
                        assertTrue(!src.contains("net.minecraft"),
                                p.getFileName() + " 属于共享层，不得 import net.minecraft；"
                                        + "MC 触点只许在版本树的 compat/mc/ 里");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("渲染状态绝不跨播放复用：SplitLines 必须造新 LyricLine")
    void splitLinesCreatesFreshLines() throws IOException {
        String src = read(shared("SplitLines.java"));
        assertTrue(src.contains("new LyricLine("),
                "拆出的半句必须是全新 LyricLine（渲染对象带动画状态不能复用）");
    }

    @Test
    @DisplayName("图集页不得重复构造同一条辉光 RenderPipeline")
    void glowPipelineIsSharedAcrossPages() throws IOException {
        Path billboard = ProjectPaths.versionJava().resolve(
                "com/muoniumplayer/compat/mc/WorldBillboard.java");
        if (!Files.exists(billboard)) return;
        String src = read(billboard);
        int glowMethod = src.indexOf("glowType(");
        if (glowMethod < 0) return;

        int glowMap = src.indexOf("GLOW_TYPES", glowMethod);
        int pipeline = src.indexOf("LYRIC_GLOW_PIPELINE");
        assertTrue(glowMap > glowMethod && pipeline >= 0 && pipeline < glowMap,
                "自定义辉光版本必须先定义共享 LYRIC_GLOW_PIPELINE，再定义按页 GLOW_TYPES");

        int methodEnd = src.indexOf("\n    /**", glowMethod + 1);
        if (methodEnd < 0) methodEnd = src.length();
        String method = src.substring(glowMethod, methodEnd);
        assertTrue(method.contains("LYRIC_GLOW_PIPELINE"),
                "glowType 只能把共享管线交给页级 RenderType");
        assertTrue(!method.contains("RenderPipeline.builder"),
                "glowType 的按页工厂不得重新 builder；否则 PipelineCache 会按页阻塞编译");
    }
}
