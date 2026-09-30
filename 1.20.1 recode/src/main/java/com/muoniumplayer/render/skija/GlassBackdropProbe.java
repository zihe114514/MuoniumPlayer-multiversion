package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

/**
 * 背景采集探针：{@code -Dmuonium.debug.glass=true} 时在屏幕左下角贴两块对比图。
 *
 * <p>它存在的唯一目的，是把「能不能每帧拿到本帧游戏最终画面并喂给 Skia」这件事变成一次
 * 真机截图就能判定的实验，而不是靠推理下结论。左块是采到的原图，右块是同一张图糊过之后的样子
 * ——那正是 P1 真毛玻璃要吃下的两个东西：<b>内容对不对</b>（有没有把我们的 HUD 抄进背景、
 * 上下有没有颠倒、窗口 resize 后尺寸对不对）和<b>糊得动不动</b>（日志里的采集耗时）。
 *
 * <p>两块都画不出来（一片空）说明采集根本没成；画出来是黑的说明读到的是还没写的帧缓冲；
 * 上下颠倒说明 {@code SurfaceOrigin} 选错；画面里带上了播放器界面说明采集点排到了合成之后。
 * 这四种症状各自指向一处，一次跑起来就能分辨，所以不必提前把每种可能都实现一遍。
 *
 * <p>探针不改任何既有绘制路径：只加一次调用，关掉属性就整段不执行。
 */
public final class GlassBackdropProbe {

    private static final String TAG = "[MuoniumPlayer/GlassProbe] ";

    /** 每块的宽，GUI 单位。 */
    private static final float PATCH_WIDTH = 200f;
    /** 每块的高，按 16:9 走；背景本身是窗口尺寸，比例不需要严格对齐。 */
    private static final float PATCH_HEIGHT = 112f;
    /** 两块之间的间隙。 */
    private static final float GAP = 8f;
    /** 距屏幕左下边的留白。 */
    private static final float MARGIN = 12f;

    /** 模糊 sigma，GUI 单位。真毛玻璃大概会落在 6~18 之间，这里先取一个能一眼看出差别的值。 */
    private static final float BLUR_SIGMA = probeSigma();

    private static long frames;

    /** Float 没有 getFloat(String,float) 这种现成解析，属性写错也不该让探针起不来。 */
    private static float probeSigma() {
        try {
            String raw = System.getProperty("muonium.glass.probeSigma");
            return raw == null || raw.isEmpty() ? 10f : Float.parseFloat(raw);
        } catch (Throwable ignored) {
            return 10f;
        }
    }

    private GlassBackdropProbe() {
    }

    /** 由 HUD 渲染回调调用；开关（JVM 属性或 {@code dev_glass_probe} 文件）没开时整段跳过。 */
    public static void renderIfDebugging() {
        // 问的是 debugging() 而不是 wanted()：后者现在也包含「主题选了琉璃」，
        // 用它的结果就是玩家一开真玻璃，屏幕左下角立刻长出两块调试块。
        if (!GlassBackdrop.debugging()) {
            return;
        }
        Canvas canvas = SkijaHost.canvas();
        if (canvas == null) {
            return;
        }
        Image backdrop = GlassBackdrop.image();
        int guiWidth = SkijaHost.guiWidth();
        int guiHeight = SkijaHost.guiHeight();
        if (backdrop == null || guiWidth <= 0 || guiHeight <= 0) {
            // 采集还没成功过一次。这条日志是「根本没采到」和「采到了但没画」的分界证据。
            if ((++frames) % 240L == 1L) {
                System.out.println(TAG + "背景图仍为 null，采集是否成功="
                        + (GlassBackdrop.captures() > 0)
                        + "（broken=" + GlassBackdrop.isBroken() + "）");
            }
            return;
        }

        float left = MARGIN;
        float top = guiHeight - PATCH_HEIGHT - MARGIN;
        // 源矩形用整张背景，目的矩形按 GUI 单位给——缩放交给 Skia 的线性采样。
        Rect source = Rect.makeXYWH(0f, 0f, backdrop.getWidth(), backdrop.getHeight());
        try (Paint plain = new Paint()) {
            canvas.drawImageRect(backdrop, source,
                    Rect.makeXYWH(left, top, PATCH_WIDTH, PATCH_HEIGHT),
                    SamplingMode.LINEAR, plain, true);
        }
        try (ImageFilter blur = ImageFilter.makeBlur(
                BLUR_SIGMA, BLUR_SIGMA, FilterTileMode.CLAMP);
             Paint paint = new Paint().setImageFilter(blur)) {
            canvas.drawImageRect(backdrop, source,
                    Rect.makeXYWH(left + PATCH_WIDTH + GAP, top, PATCH_WIDTH, PATCH_HEIGHT),
                    SamplingMode.LINEAR, paint, true);
        }

        if ((++frames) % 240L == 1L) {
            System.out.println(TAG + "采集 " + GlassBackdrop.captures() + " 帧，纹理 "
                    + GlassBackdrop.width() + "x" + GlassBackdrop.height()
                    + "，GUI " + guiWidth + "x" + guiHeight
                    + "，上次采集 " + (GlassBackdrop.lastCaptureNanos() / 1000L) + "µs"
                    + "，模糊 sigma " + BLUR_SIGMA);
        }
    }
}
