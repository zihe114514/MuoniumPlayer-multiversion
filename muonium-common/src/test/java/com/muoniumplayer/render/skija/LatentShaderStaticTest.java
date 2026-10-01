package com.muoniumplayer.render.skija;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LatentShaderStaticTest {

    @Test
    @DisplayName("隐现 shader 只使用 float4 uniform，且布局与 Java 侧缓冲区一致")
    void latentShaderUniformLayout() {
        assertUniformLayout(SkijaLatentEffects.meshShaderSource(), 8);
        assertUniformLayout(SkijaLatentEffects.ditherShaderSource(), 4);
    }

    private static void assertUniformLayout(String source, int expected) {
        long uniforms = Stream.of(source.split("\\R"))
                .map(String::trim)
                .filter(line -> line.startsWith("uniform "))
                .count();
        assertEquals(expected, uniforms, "uniform 数量变化时必须同步修改 Java 侧 ByteBuffer");
        assertFalse(source.contains("uniform float2") || source.contains("uniform float3"),
                "隐现 shader 的 uniform 必须全部使用 float4，避免 SkSL 对齐差异");
        assertEquals(expected * 16, uniforms * 16);
    }
}
