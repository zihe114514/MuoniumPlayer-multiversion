package com.muoniumplayer.render.skija;

import com.muoniumplayer.testsupport.ProjectPaths;
import io.github.humbleui.skija.RuntimeEffect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 亮部溢出（bloom）这一层的三条不变量。
 *
 * <p>它新加的是一整条 GPU 链路（一张纹理 + 一个着色器 + 一趟全屏高斯），而这类东西最典型的
 * 失败不是「画错了」而是「默认也在花钱」。所以这里钉的三件事都在钱上：</p>
 * <ol>
 *   <li>强度为 0 时必须在<em>任何</em> GL / Skia 调用之前就返回 null——顺序错了的话，
 *       玩家没开这一档也会每帧多付一趟高斯，而那笔钱没有任何人能看见；</li>
 *   <li>它必须贴在磨砂底之内（同一个 clip、同一份源矩形），不然面板贴屏幕边时会错半像素，
 *       症状是亮边外面套一条白边；</li>
 *   <li>SkSL 必须真能在 Skia 里编译，且 uniform 就是 Java 侧写的那 16 字节。
 *       这一条是<em>真跑</em>的（{@link RuntimeEffect#makeForShader}），因为 SkSL 写错时
 *       症状是「这一层悄悄停用」，只有编译自检能把它抓住。</li>
 * </ol>
 */
class GlassBloomStaticTest {

    private static String read(String... parts) throws Exception {
        Path path = ProjectPaths.sharedJava().resolve(Paths.get("com", "muoniumplayer", "render",
                "skija", parts[parts.length - 1]));
        assertTrue(Files.exists(path), "文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    @DisplayName("强度为 0 是第一道闸：在建任何东西之前就返回 null")
    void zeroStrengthShortCircuitsEverything() throws Exception {
        String source = read("GlassBackdrop.java");
        int at = source.indexOf("public static Image bloom() {");
        assertTrue(at > 0, "bloom() 入口没了");
        String head = source.substring(at, at + 400);
        int guard = head.indexOf("bloomStrength() <= 0f");
        int build = head.indexOf("rebuildBloom(");
        assertTrue(guard >= 0 && build > guard,
                "强度闸必须排在重建之前，否则关掉这一档照样每帧付一趟全屏高斯：" + head);
    }

    @Test
    @DisplayName("bloom 走 frostedBackdrop 那一条：与磨砂底同一个剪形、同一份源矩形")
    void bloomDrawnInsideFrost() throws Exception {
        String source = read("SkijaEffects.java");
        int at = source.indexOf("public static boolean frostedBackdrop(");
        int end = source.indexOf("private static void paintBloom(", at);
        assertTrue(at > 0 && end > at, "frostedBackdrop 与 paintBloom 的相对位置变了");
        String frost = source.substring(at, end);
        assertTrue(frost.contains("paintBloom(canvas, source, bounds, opacity"),
                "bloom 必须画在 frostedBackdrop 的 clipRRect 之内：两处各算源矩形会错半像素");
        assertTrue(frost.contains("canvas.clipRRect(rounded, true)"), "剪形丢了");
        String body = source.substring(end);
        assertTrue(body.contains("GlassBackdrop.bloom()"), "强度为 0 时靠 bloom() 返回 null 兜底");
        assertTrue(body.contains("BlendMode.PLUS"), "亮部溢出是相加，不是覆盖");
        assertTrue(body.contains("if (bloom == null)"), "没有 bloom 时必须一次绘制都不发");
    }

    @Test
    @DisplayName("亮部提取的 SkSL 真能在 Skia 里编译，uniform 就是 16 字节")
    void bloomShaderCompilesWithExpectedUniforms() {
        String sksl = GlassBackdrop.bloomSource();
        assertTrue(sksl.contains("uniform shader uRaw;"), "子 shader 名换了要连同 Java 侧的 children 一起改");
        long declared = sksl.lines().map(String::trim).filter(line -> line.startsWith("uniform ")).count();
        assertEquals(2L, declared, "bloom 的 SkSL 只该有 1 个 shader + 1 个 float4");
        try (RuntimeEffect effect = RuntimeEffect.makeForShader(sksl)) {
            assertNotNull(effect, "SkSL 编译失败：这一层会静默停用，玻璃少一圈亮晕而没人报");
            assertEquals(16, effect.getUniformSize(),
                    "Java 侧 bloomUniforms 写的是 4 个 float = 16 字节，SkSL 改了这里就要一起改");
        }
    }

    @Test
    @DisplayName("阈值窗口自洽：threshold + window 覆盖到全亮，不会算出负窗口")
    void thresholdWindowIsSane() {
        // 这两个常量的意义全在「0.55 之上的亮度按到 1.0 的窗口平方衰减」，
        // 窗口写反（大于 1 - 阈值很多）时亮部提取会连中间调一起吃进来，整块玻璃发灰。
        float threshold = GlassBackdrop.BLOOM_THRESHOLD;
        float window = GlassBackdrop.BLOOM_WINDOW;
        assertTrue(threshold > 0f && threshold < 1f, "阈值必须落在 0..1 之间");
        assertEquals(1f, threshold + window, 0.011f,
                "窗口宽 = 1 - 阈值，否则最亮处也达不到满衰减，亮晕永远比预期暗");
    }
}
