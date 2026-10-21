package com.muoniumplayer.render.skija;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QPlayerFluidShaderStaticTest {
    @Test
    @DisplayName("全屏歌词只加载 QPlayer 三种流体算法及其切歌淡化 shader")
    void qplayerFluidShadersExposeAnimationAndAudioInputs() throws IOException {
        assertShader("pixi_renderer.sksl", "uniform float bend;", "uniform float4 angles;");
        assertShader("pixi_renderer_fade.sksl", "uniform shader coverPrev;", "uniform float fade;");
        assertShader("classic.sksl", "uniform float2 resolution;", "uniform float time;");
        assertShader("classic_fade.sksl", "uniform shader coverPrev;", "uniform float fade;");
        assertShader("meshgradient.sksl", "uniform float alpha;", "uniform shader cover;");
    }

    @Test
    @DisplayName("folia 移植风格（Latent/Sora/Nomand）声明宿主要推的全部 uniform")
    void foliaPortShadersDeclareHostUniforms() throws IOException {
        assertFoliaShader("latent.sksl", "uniform shader cover;", "uniform float audioLow;");
        assertFoliaShader("nomand.sksl", "uniform shader cover;", "uniform float effect;");
        // Sora 没有封面子着色器与分段频段：颜色走 particleColor/Accent，
        // 律动全部折进宿主积分过的 time。
        assertFoliaShader("sora.sksl", "uniform float3 particleColor;", "uniform float3 particleAccent;");
    }

    @Test
    @DisplayName("folia 三个层化 shader 都声明层开关与层不透明度 uniform")
    void foliaShadersExposeLayerCompositingInputs() throws IOException {
        String[] shaders = {"latent.sksl", "sora.sksl", "nomand.sksl"};
        for (String name : shaders) {
            try (InputStream input = SkijaFluidBackground.class.getResourceAsStream(
                    "/shaders/fluid/" + name)) {
                assertTrue(input != null, "missing folia shader " + name);
                String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(source.contains("uniform float layer;"),
                        name + " lost the layer selector");
                assertTrue(source.contains("uniform float alpha;"),
                        name + " lost the layer alpha input");
            }
        }
        // Latent 的拆层语义钉死：0=仅网格，1=仅抖动（带自身 alpha），2=融合经典形态。
        try (InputStream input = SkijaFluidBackground.class.getResourceAsStream(
                "/shaders/fluid/latent.sksl")) {
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(source.contains("if (layer < 0.5)") && source.contains("if (layer < 1.5)"),
                    "latent.sksl layer branches drifted");
        }
    }

    private static void assertShader(String name, String first, String second) throws IOException {
        try (InputStream input = SkijaFluidBackground.class.getResourceAsStream(
                "/shaders/fluid/" + name)) {
            assertTrue(input != null, "missing QPlayer shader " + name);
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(source.contains("uniform float time;"), name + " needs a time input");
            assertTrue(source.contains("uniform float audio;"), name + " needs an audio-response input");
            assertTrue(source.contains(first), name + " lost " + first);
            assertTrue(source.contains(second), name + " lost " + second);
        }
    }

    private static void assertFoliaShader(String name, String first, String second) throws IOException {
        try (InputStream input = SkijaFluidBackground.class.getResourceAsStream(
                "/shaders/fluid/" + name)) {
            assertTrue(input != null, "missing folia shader " + name);
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(source.contains("uniform float2 resolution;"),
                    name + " needs a resolution input");
            assertTrue(source.contains("uniform float time;"), name + " needs a time input");
            assertTrue(source.contains("uniform float audio;"), name + " needs an audio-response input");
            assertTrue(source.contains(first), name + " lost " + first);
            assertTrue(source.contains(second), name + " lost " + second);
        }
    }
}
