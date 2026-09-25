package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * 1.8.9 {@code net.minecraft.client.gui.ScaledResolution} 的兼容替身。
 *
 * <p>1.20.1 起这三个值挪到了 {@code Minecraft#getWindow()} 上，且不再需要每帧
 * new 一个对象。为了不改 {@code RenderSystem#refreshResolution()} /
 * {@code WindowResolutionImpl} / {@code GuiHudEditor*} 的调用形态，这里保留
 * "构造时快照" 的语义：构造那一刻读一次窗口尺寸，之后 getter 返回同一批值。
 *
 * <p>构造参数保留 1.8.9 的 {@code Minecraft} 位置但类型放宽为 Object 并忽略，
 * 因为窗口只能从单例拿；调用点写 {@code new ScaledResolution(mc)} 仍可编译。
 */
public final class ScaledResolution {

    private final int scaleFactor;
    private final int scaledWidth;
    private final int scaledHeight;

    public ScaledResolution() {
        this(null);
    }

    public ScaledResolution(Object ignoredMinecraft) {
        Minecraft client = Minecraft.getInstance();
        Window window = client == null ? null : client.getWindow();
        if (window == null) {
            this.scaleFactor = 1;
            this.scaledWidth = 0;
            this.scaledHeight = 0;
        } else {
            // 26.2 的 getGuiScale() 已经是 int（Yarn 的 getScaleFactor() 曾是 double）。
            // 仍然兜一层 max(1,...)：0 会让后面所有按缩放因子做的整数除法炸掉。
            this.scaleFactor = Math.max(1, window.getGuiScale());
            this.scaledWidth = window.getGuiScaledWidth();
            this.scaledHeight = window.getGuiScaledHeight();
        }
    }

    public int getScaleFactor() {
        return scaleFactor;
    }

    public int getScaledWidth() {
        return scaledWidth;
    }

    public int getScaledHeight() {
        return scaledHeight;
    }
}
