package com.muoniumplayer.core.cine;

import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;
import io.github.humbleui.skija.Canvas;

/**
 * 卡点运镜的电影黑边：运镜激活时在画面上下各画一条黑带（宽高比造型）。
 *
 * <p>每侧高度 = 屏高 × {@code CineSettings.cinematicBarPercent()}；13% ≈ 2.39:1 @ 16:9。
 * 与转场遮罩同一条帧末 Skija 合成链（见 {@link CineTransition} 的渲染路径注释）。</p>
 */
public final class CineBars {

    private CineBars() {
    }

    /** 树侧 HUD 回调每帧调。 */
    public static void render() {
        float fraction = CineDirector.cinematicBarFraction();
        if (fraction <= 0f) return;
        Canvas canvas = SkijaGfx.canvas();
        if (!SkijaGfx.active() || canvas == null) return;
        double width = RenderSystem.getLayoutWidth();
        double height = RenderSystem.getLayoutHeight();
        if (width <= 0d || height <= 0d) return;
        double barHeight = height * Math.min(1d, fraction);
        if (barHeight <= 0.5d) return;
        SkijaShapes.rect(0d, 0d, width, barHeight, 0xFF000000);
        SkijaShapes.rect(0d, height - barHeight, width, height, 0xFF000000);
    }
}
