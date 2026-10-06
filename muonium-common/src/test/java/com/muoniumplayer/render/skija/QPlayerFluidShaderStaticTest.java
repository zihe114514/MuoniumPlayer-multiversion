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
}
