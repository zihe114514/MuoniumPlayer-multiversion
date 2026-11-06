package com.muoniumplayer.core.cine;

import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaShapes;
import io.github.humbleui.skija.Canvas;

/**
 * 卡点运镜的转场遮罩：切镜瞬间把画面盖住再揭开（J-cut 的视觉半程）。
 *
 * <h3>特效（v1 五种，位掩码在 {@link CineSettings}）</h3>
 * <ol>
 *   <li>黑淡入出（经典 cut-to-black）；</li>
 *   <li>白淡入出（闪白，副歌 drop 感）；</li>
 *   <li>横擦（wipe，左→右遮出、左→右揭入）；</li>
 *   <li>竖擦；</li>
 *   <li>光圈 iris（圆，从画面中心收拢/张开）。</li>
 * </ol>
 *
 * <h3>渲染路径</h3>
 * <p>照 {@code AutomixTransitionAnimation} 的帧末合成路子：树侧 HUD 回调
 * （{@code MuoniumPlayerEventHandler.onRender2D}）里调 {@link #render()}，
 * 经 {@code SkijaHost.deferPostCompositeOverlay} 推迟到 Skija 离屏合成时画——
 * 盖在世界之上、GUI 之下，与灵动岛同一条链。</p>
 *
 * <p>遮罩时钟全在 {@link CineDirector}（单消费者），本类只做无状态绘制。</p>
 */
public final class CineTransition {

    private CineTransition() {
    }

    /** 树侧 HUD 回调每帧调：有转场就画，无转场一次布尔判断。 */
    public static void render() {
        CineDirector.TransitionFrame frame = CineDirector.currentTransition();
        if (frame == null) return;
        if (!CineDirector.isActive()) return;
        Canvas canvas = SkijaGfx.canvas();
        if (!SkijaGfx.active() || canvas == null) return;
        double width = RenderSystem.getLayoutWidth();
        double height = RenderSystem.getLayoutHeight();
        if (width <= 0d || height <= 0d) return;

        double t = CineShots.smootherstep(frame.progress());
        // 遮出（covering=true）：覆盖从 0→1；揭入：覆盖从 1→0。
        double coverage = frame.covering() ? t : 1d - t;
        // 与 AutomixTransitionAnimation 同款纪律:画刷/混合等绘制状态进出配对,
        // 不渗给同帧的其它 Skija 调用方(灵动岛/播放器界面)。
        SkijaGfx.pushAttrib();
        try {
            switch (frame.effect()) {
                case 1 -> drawFade(canvas, width, height, coverage, false);
                case 2 -> drawFade(canvas, width, height, coverage, true);
                case 3 -> drawWipe(canvas, width, height, coverage, true);
                case 4 -> drawWipe(canvas, width, height, coverage, false);
                case 5 -> drawIris(canvas, width, height, coverage);
                default -> {
                }
            }
        } finally {
            SkijaGfx.popAttrib();
        }
    }

    private static void drawFade(Canvas canvas, double width, double height,
                                 double coverage, boolean white) {
        int alpha = (int) Math.round(Math.max(0d, Math.min(1d, coverage)) * 255d);
        if (alpha <= 0) return;
        int color = (alpha << 24) | (white ? 0xFFFFFF : 0x000000);
        SkijaShapes.rect(0d, 0d, width, height, color);
    }

    private static void drawWipe(Canvas canvas, double width, double height,
                                 double coverage, boolean horizontal) {
        if (coverage <= 0d) return;
        if (horizontal) {
            double edge = width * coverage;
            SkijaShapes.rect(0d, 0d, edge, height, 0xFF000000);
        } else {
            double edge = height * coverage;
            SkijaShapes.rect(0d, 0d, width, edge, 0xFF000000);
        }
    }

    /** 光圈 iris：遮出时圆孔收缩到 0（四周黑幕涨进来）；揭入反向。 */
    private static void drawIris(Canvas canvas, double width, double height, double coverage) {
        double centerX = width / 2d;
        double centerY = height / 2d;
        double maxRadius = Math.hypot(centerX, centerY);
        double holeRadius = maxRadius * (1d - Math.max(0d, Math.min(1d, coverage)));
        if (holeRadius >= maxRadius) return;
        // 内圈透明（画面透出）、外圈纯黑的径向环 —— SkijaShapes.radialRing。
        SkijaShapes.radialRing(centerX, centerY, Math.max(0d, holeRadius), maxRadius * 1.05d,
                0x00000000, 0xFF000000);
    }
}
