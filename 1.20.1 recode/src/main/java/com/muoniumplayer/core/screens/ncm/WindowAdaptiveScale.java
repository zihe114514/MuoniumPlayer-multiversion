package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;

/**
 * 播放器与播放器设置页共用的窗口自适应坐标层。
 *
 * <p>开启时沿用播放器现有的“固定逻辑坐标”：逻辑宽高由物理窗口推导，
 * 渲染前按当前 Minecraft GUI Scale 做一次连续校正。这样 1x / 2x / 3x GUI
 * 不会改变播放器的物理占屏比例；关闭时则直接使用 Minecraft 的 GUI 逻辑宽高。</p>
 */
public final class WindowAdaptiveScale {

    private WindowAdaptiveScale() {
    }

    public static boolean isEnabled() {
        return NCMPlayerConfig.isWindowAdaptiveScaleEnabled();
    }

    /** 必须在 RenderSystem#refreshResolution() 之后调用。 */
    public static boolean begin() {
        boolean active = isEnabled();
        RenderSystem.FIXED_SCALE = active;
        return active;
    }

    public static void end() {
        RenderSystem.FIXED_SCALE = false;
    }

    public static boolean isTransforming() {
        return RenderSystem.FIXED_SCALE;
    }

    public static double correctionX() {
        double layoutWidth = RenderSystem.getWidth();
        return layoutWidth <= 0.0 ? 1.0 : RenderSystem.getWidthNotScaled() / layoutWidth;
    }

    public static double correctionY() {
        double layoutHeight = RenderSystem.getHeight();
        return layoutHeight <= 0.0 ? 1.0 : RenderSystem.getHeightNotScaled() / layoutHeight;
    }

    public static int layoutWidth() {
        return (int) Math.round(RenderSystem.getWidth());
    }

    public static int layoutHeight() {
        return (int) Math.round(RenderSystem.getHeight());
    }

    public static int toLayoutX(int x) {
        return (int) Math.round(toLayoutX((double) x));
    }

    public static int toLayoutY(int y) {
        return (int) Math.round(toLayoutY((double) y));
    }

    /**
     * 把一个 Minecraft GUI 坐标转换为播放器窗口自适应布局。
     *
     * <p>在 {@link #begin()} 之外调用也安全：它临时启用
     * 布局，仅长到足以取得修正因子。过渡代码从全局 HUD
     * 坐标系捕获锚点，而该坐标系不总在播放器渲染作用域内。</p>
     */
    public static double toLayoutX(double x) {
        if (!isEnabled()) return x;
        boolean ownedLayout = beginLayoutIfNeeded();
        try {
            double scale = correctionX();
            return scale <= 0.0 ? x : x / scale;
        } finally {
            if (ownedLayout) end();
        }
    }

    public static double toLayoutY(double y) {
        if (!isEnabled()) return y;
        boolean ownedLayout = beginLayoutIfNeeded();
        try {
            double scale = correctionY();
            return scale <= 0.0 ? y : y / scale;
        } finally {
            if (ownedLayout) end();
        }
    }

    public static double toLayoutWidth(double width) {
        if (!isEnabled()) return width;
        boolean ownedLayout = beginLayoutIfNeeded();
        try {
            double scale = correctionX();
            return scale <= 0.0 ? width : width / scale;
        } finally {
            if (ownedLayout) end();
        }
    }

    public static double toLayoutHeight(double height) {
        if (!isEnabled()) return height;
        boolean ownedLayout = beginLayoutIfNeeded();
        try {
            double scale = correctionY();
            return scale <= 0.0 ? height : height / scale;
        } finally {
            if (ownedLayout) end();
        }
    }

    public static double toLayoutRadius(double radius) {
        if (!isEnabled()) return radius;
        boolean ownedLayout = beginLayoutIfNeeded();
        try {
            double scaleX = correctionX();
            double scaleY = correctionY();
            if (scaleX <= 0.0 || scaleY <= 0.0) return radius;
            return radius / ((scaleX + scaleY) * .5);
        } finally {
            if (ownedLayout) end();
        }
    }

    private static boolean beginLayoutIfNeeded() {
        if (isTransforming()) return false;
        begin();
        return true;
    }
}
