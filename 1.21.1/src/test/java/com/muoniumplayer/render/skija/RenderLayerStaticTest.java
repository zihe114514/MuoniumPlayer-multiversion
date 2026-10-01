package com.muoniumplayer.render.skija;

import com.muoniumplayer.testsupport.ProjectPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染层重写的结构性不变量。纯文本扫描，不需要 GL、不需要 Skija 原生库。
 *
 * <p>取代已删除的 {@code ShaderPortProbeTest}。旧测试钉的是「那批 GLSL 有没有正确移植到
 * core profile」；GLSL 与固定管线模拟层整体删除之后，需要钉的东西换成了三件：
 * <ol>
 *   <li><b>旧实现真的没了</b>，不会有人顺手把 {@code Framebuffer} / 自写着色器加回来
 *       ——那会让两套渲染路径并存，正是这次重写要消灭的状态。</li>
 *   <li><b>只有 {@link SkijaHost} 认识 Minecraft</b>。这是「换游戏版本只改一个文件」这条
 *       收益的全部来源，一旦别的渲染层文件 import 了 net.minecraft，收益立刻归零，
 *       而且不会有任何编译错误来提醒。</li>
 *   <li><b>四角渐变着色器的 uniform 布局和 Java 侧写入的字节数吻合</b>。
 *       SkSL 侧改一个 uniform、Java 侧忘了改偏移量，画面只是「颜色不对」，不报错。</li>
 * </ol>
 */
class RenderLayerStaticTest {

    private static final Path MAIN_JAVA = ProjectPaths.sharedJava();
    private static final Path MAIN_RESOURCES = ProjectPaths.sharedResources();
    private static final Path SKIJA_PACKAGE =
            MAIN_JAVA.resolve(Paths.get("com", "muoniumplayer", "render", "skija"));

    /** 这些文件是旧渲染实现的核心；重写之后不允许再出现。 */
    private static final String[] REMOVED_SOURCES = {
            "com/muoniumplayer/core/rendering/Framebuffer.java",
            "com/muoniumplayer/core/rendering/OpenGlHelper.java",
            "com/muoniumplayer/core/rendering/shader/Shaders.java",
            "com/muoniumplayer/core/rendering/shader/ShaderProgram.java",
            "com/muoniumplayer/core/rendering/font/TextureAtlas.java",
            "com/muoniumplayer/core/rendering/font/GlyphGenerator.java",
            "com/muoniumplayer/compat/gl/internal/FixedFunction.java",
            "com/muoniumplayer/compat/gl/GL20.java",
            "com/muoniumplayer/compat/lwjgl2/glu/GLU.java",
    };

    /** 旧实现在业务代码里留下的调用形态；只要出现就说明有人把旧路径接回去了。 */
    private static final String[] FORBIDDEN_USAGES = {
            "Shaders.",
            "OpenGlHelper.",
            "FixedFunction.",
            "glBegin(",
    };

    @Test
    @DisplayName("旧 OpenGL 渲染实现的源文件与 GLSL 资源都已删除")
    void 旧实现已彻底移除() {
        for (String removed : REMOVED_SOURCES) {
            Path path = MAIN_JAVA.resolve(removed);
            assertFalse(Files.exists(path), "旧渲染实现又回来了: " + removed);
        }
        assertFalse(Files.exists(MAIN_RESOURCES.resolve(Paths.get("muonium", "shaders"))),
                "muonium/shaders 目录又出现了；渲染层允许的着色器只放在 SkijaEffects / SkijaLatentEffects 的 SkSL 中");
    }

    @Test
    @DisplayName("业务代码里不再引用任何旧渲染门面")
    void 业务代码不再调用旧门面() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : ProjectPaths.allJavaFiles()) {
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            for (String forbidden : FORBIDDEN_USAGES) {
                if (code.contains(forbidden)) {
                    offenders.add(file + " -> " + forbidden);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "仍在使用旧渲染门面:\n" + String.join("\n", offenders));
    }

    @Test
    @DisplayName("渲染层里只有 SkijaHost 认识 Minecraft")
    void 只有宿主层耦合Minecraft() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : ProjectPaths.javaFilesIn("com/muoniumplayer/render/skija")) {
            String name = file.getFileName().toString();
            if (name.equals("SkijaHost.java")) {
                continue;
            }
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (code.contains("net.minecraft") || code.contains("com.mojang")) {
                offenders.add(name);
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些渲染层文件绕过宿主层直接耦合了 Minecraft，移植时会漏改: " + offenders);
    }

    @Test
    @DisplayName("四角渐变着色器的 uniform 布局是 6 个 float4，与 Java 侧写入的 96 字节一致")
    void 四角渐变uniform布局与Java侧一致() {
        String sksl = SkijaEffects.cornerGradientSource();
        long uniforms = Stream.of(sksl.split("\\R"))
                .map(String::trim)
                .filter(line -> line.startsWith("uniform "))
                .count();
        assertEquals(6, uniforms,
                "uniform 数量变了，SkijaEffects.cornerGradient 里的 ByteBuffer 偏移量必须同步修改");
        assertFalse(sksl.contains("uniform float2") || sksl.contains("uniform float3"),
                "全部 uniform 必须声明为 float4：SkSL 对 float2/float3 的对齐规则不是 16 字节，"
                        + "混用会让 Java 侧算错偏移量");
        assertEquals(96, uniforms * 16, "6 个 float4 应当正好是 96 字节");
    }

    /**
     * 去掉行注释与块注释。注释里提到旧实现的名字是<b>刻意保留的移植说明</b>，
     * 不去掉的话这些说明反而会让测试红。
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlock = false;
        for (String line : source.split("\\R", -1)) {
            int i = 0;
            while (i < line.length()) {
                if (inBlock) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) {
                        break;
                    }
                    inBlock = false;
                    i = end + 2;
                    continue;
                }
                int block = line.indexOf("/*", i);
                int lineComment = line.indexOf("//", i);
                if (lineComment >= 0 && (block < 0 || lineComment < block)) {
                    out.append(line, i, lineComment);
                    break;
                }
                if (block >= 0) {
                    out.append(line, i, block);
                    inBlock = true;
                    i = block + 2;
                    continue;
                }
                out.append(line, i, line.length());
                break;
            }
            out.append('\n');
        }
        return out.toString();
    }
}
