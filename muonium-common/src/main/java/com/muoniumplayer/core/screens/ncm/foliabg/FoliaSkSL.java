package com.muoniumplayer.core.screens.ncm.foliabg;

import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * folia 背景的 SkSL 资源与编译缓存。
 *
 * <p>三支着色器（{@code latent} / {@code nomand} / {@code fluid}）各是一个 RuntimeEffect，
 * 编译一次全程复用。编译失败按「负缓存」记住，之后直接走调用方的兜底底色 —— 旧版每帧重试
 * 一次 SkSL 解析，失败时能把渲染线程拖死。</p>
 *
 * <p>封面统一缩到 256×256（{@link #COVER_TEX}）再交给着色器：folia 的抖动格与网格锚点需要真实
 * 像素，而 QPlayer 那套 32px 工作纹理糊得看不出结构。子着色器的采样域固定为 0..32，所以这里用
 * {@code 32/256 = .125} 的缩放矩阵把 256 贴图铺进 0..32 —— 与旧版
 * {@code SkijaFluidBackground.makeFoliaShader} 同一个约定。</p>
 */
final class FoliaSkSL {

    static final int COVER_TEX = 256;
    private static final String ROOT = "/shaders/folia/";

    static final String LATENT = "latent.sksl";
    static final String NOMAND = "nomand.sksl";
    static final String FLUID = "fluid.sksl";

    private static final Map<String, RuntimeEffect> EFFECTS = new HashMap<String, RuntimeEffect>();
    private static final Map<String, Boolean> FAILED = new HashMap<String, Boolean>();

    private FoliaSkSL() {
    }

    /** 取（或编译）一支着色器；失败返回 {@code null}，调用方画兜底。 */
    static RuntimeEffect effect(String name) {
        if (FAILED.containsKey(name)) return null;
        RuntimeEffect cached = EFFECTS.get(name);
        if (cached != null) return cached;
        try {
            String source = read(name);
            RuntimeEffect effect = RuntimeEffect.makeForShader(source);
            EFFECTS.put(name, effect);
            return effect;
        } catch (Throwable failure) {
            FAILED.put(name, Boolean.TRUE);
            System.out.println("[MuoniumPlayer/FoliaBg] 着色器 " + name + " 编译失败，走兜底底色: "
                    + failure);
            return null;
        }
    }

    /** 供测试与热重载用：清掉编译缓存（失败标记也清）。 */
    static synchronized void invalidate() {
        for (RuntimeEffect effect : EFFECTS.values()) {
            try {
                effect.close();
            } catch (Throwable ignored) {
            }
        }
        EFFECTS.clear();
        FAILED.clear();
    }

    private static String read(String name) throws IOException {
        try (InputStream in = FoliaSkSL.class.getResourceAsStream(ROOT + name)) {
            if (in == null) throw new IOException("resource not found: " + ROOT + name);
            StringBuilder source = new StringBuilder(8192);
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                source.append(buffer, 0, count);
            }
            return source.toString();
        }
    }

    /**
     * 把一个 {@link Image} 包成「0..32 采样域」的子着色器。
     *
     * <p>Skija 的 {@code makeShader} 的 localMatrix 是「纹理坐标 → 着色器坐标」，
     * 也就是 {@code shader = M · texel}，所以 0..32 域要用 {@code 32/width} 的缩放。</p>
     */
    static Shader childShader(Image image) {
        if (image == null) return null;
        float width = Math.max(1, image.getWidth());
        float height = Math.max(1, image.getHeight());
        io.github.humbleui.skija.Matrix33 matrix = new io.github.humbleui.skija.Matrix33(
                32f / width, 0f, 0f,
                0f, 32f / height, 0f,
                0f, 0f, 1f);
        return image.makeShader(io.github.humbleui.skija.FilterTileMode.CLAMP,
                io.github.humbleui.skija.FilterTileMode.CLAMP,
                io.github.humbleui.skija.SamplingMode.LINEAR, matrix);
    }

    /** 全屏矩形（局部坐标）。 */
    static Rect fullRect(float width, float height) {
        return Rect.makeXYWH(0f, 0f, Math.max(1f, width), Math.max(1f, height));
    }

    /** 当前帧的 canvas；不在帧内返回 null。 */
    static Canvas canvas() {
        return SkijaGfx.canvas();
    }
}




