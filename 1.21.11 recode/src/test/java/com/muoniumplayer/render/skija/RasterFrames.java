package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Surface;

import java.lang.reflect.Field;

/**
 * 测试用的「一帧纯 CPU 画布」。
 *
 * <p>生产路径的帧由 {@link SkijaHost#beginFrame()} 开，它要 Minecraft 的窗口、GL 上下文与
 * 离屏目标。校准 Folia 设置面板的排版时这些一样都不想要：需要的是「改一个常量 → 立刻出图」，
 * 而启动一次客户端要十几秒。这里把同一套帧内状态压到一张 raster {@link Surface} 上，
 * 于是 {@link SkijaGfx#canvas()} 及整条绘制链（形状、渐变、模糊、裁剪、文字）都照常工作，
 * 生产代码一行都不用为测试让路。
 *
 * <p> {@link SkijaHost} 的那几个字段是私有的，所以用反射写。它们全是静态可变状态，
 * 单测是单线程的，不存在竞态；用完必须 {@link #end(Surface)}，否则下一帧看见的
 * {@code inFrame} 还是 true，宿主层的 {@code beginFrame} 会直接返回 null。
 */
public final class RasterFrames {

    private RasterFrames() {
    }

    /**
     * 开一帧。
     *
     * <p>两个缩放因子是<b>分开</b>给的，因为生产路径上它们本来就不相等：画布的基准变换
     * 是「编辑器单位 → 设备像素」（Folia 那套设计空间里等于 {@code foliaUiScale}），
     * 而 {@link SkijaHost#guiScale()} 报的是 Minecraft 自己的 GUI 缩放。渲染层里
     * 发丝线宽与模糊 sigma 是按后者换算的，只给一个值就会把这两样一起改掉，
     * 出图对不上游戏里的样子。
     *
     * @param pixelWidth    输出图的实际像素宽
     * @param guiWidth      业务代码看到的逻辑宽（Folia 那套设计空间的单位数）
     * @param guiHeight     业务代码看到的逻辑高
     * @param hostGuiScale  游戏里 {@code pixelWidth / window.getGuiScaledWidth()} 的那个值
     */
    public static Surface begin(int pixelWidth, int guiWidth, int guiHeight, double hostGuiScale) {
        if (!SkijaNatives.ensureLoaded()) {
            throw new IllegalStateException("Skija 原生库不可用: " + SkijaNatives.failure());
        }
        int pixelHeight = (int) Math.round(pixelWidth * (guiHeight / (double) guiWidth));
        Surface surface = Surface.makeRasterN32Premul(pixelWidth, pixelHeight);
        Canvas canvas = surface.getCanvas();
        canvas.clear(0xFF09090B);
        // 与 beginFrame 同一件事：一次 save + 一个「逻辑 → 设备」的缩放，之后上层只见逻辑坐标。
        canvas.save();
        float scale = (float) pixelWidth / (float) guiWidth;
        canvas.scale(scale, scale);
        set("canvas", canvas);
        set("inFrame", Boolean.TRUE);
        set("frameGuiWidth", guiWidth);
        set("frameGuiHeight", guiHeight);
        set("frameGuiScale", hostGuiScale);
        SkijaGfx.beginFrame();
        SkijaClip.resetFrame();
        return surface;
    }

    /** 收帧并关掉 Surface。*/
    public static void end(Surface surface) {
        SkijaGfx.restoreDepthTo(0);
        SkijaGfx.endFrame();
        SkijaClip.resetFrame();
        Canvas canvas = SkijaGfx.canvas();
        if (canvas != null) {
            canvas.restore();
        }
        set("canvas", null);
        set("inFrame", Boolean.FALSE);
        if (surface != null) {
            surface.close();
        }
    }

    private static void set(String name, Object value) {
        try {
            Field field = SkijaHost.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("写 SkijaHost." + name + " 失败", e);
        }
    }
}
