package com.muoniumplayer.core.lyrics3d;

import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;

/** Word-plane-only transitions; the Minecraft scene is never sampled or filtered. */
final class SonnetTextTransitions implements AutoCloseable {
    private static final String GLITCH = """
            uniform shader source;
            uniform float2 frameSize;
            uniform float amount;
            uniform float seed;
            float hash(float2 v) { return fract(sin(dot(v, float2(12.9898,78.233))) * 43758.5453); }
            half4 main(float2 p) {
                float y = p.y / max(frameSize.y, 1.0);
                float coarse = floor(y * 14.0), fine = floor(y * 58.0);
                float cn = hash(float2(coarse, seed));
                float fn = hash(float2(fine + 41.0, seed * 1.37));
                float cg = step(0.58, cn), fg = step(0.88, fn);
                float cs = (hash(float2(coarse + 17.0, seed)) * 2.0 - 1.0) * cg * amount * 0.1;
                float fs = (hash(float2(fine + 73.0, seed)) * 2.0 - 1.0) * fg * amount * 0.037;
                float2 coord = float2(clamp(p.x + (cs + fs) * frameSize.x, 0.5, frameSize.x - 0.5), p.y);
                half4 color = source.eval(coord);
                float tear = (cg * (cn - 0.58) + fg * 0.12) * amount;
                color.rgb = min(color.aaa, color.rgb * (1.0 + tear * 0.42));
                return color;
            }
            """;
    private RuntimeEffect glitch;
    private RuntimeEffectBuilder builder;

    ImageFilter filter(SonnetTextMotion.Transition transition, float width, float height) {
        if (transition.blur > 0.01) {
            return ImageFilter.makeBlur((float) transition.blur, (float) transition.blur, FilterTileMode.DECAL);
        }
        if (transition.glitch <= 0.001) return null;
        if (glitch == null) {
            glitch = RuntimeEffect.makeForShader(GLITCH);
            builder = new RuntimeEffectBuilder(glitch);
        }
        builder.setUniform("frameSize", width, height);
        builder.setUniform("amount", (float) transition.glitch);
        builder.setUniform("seed", (float) transition.seed);
        return ImageFilter.makeRuntimeShader(builder, width * 0.14f, "source", null);
    }

    @Override public void close() {
        if (builder != null) builder.close();
        if (glitch != null) glitch.close();
        builder = null;
        glitch = null;
    }
}