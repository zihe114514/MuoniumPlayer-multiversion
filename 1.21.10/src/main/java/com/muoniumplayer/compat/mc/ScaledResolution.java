package com.muoniumplayer.compat.mc;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.Window;

/**
 * 1.8.9 {@code net.minecraft.client.gui.ScaledResolution} 的兼容替身。
 *
 * <p>1.20.1 把这三个值挪到了 {@code Minecraft#getWindow()} 上，且不再需要每帧
 * new 一个对象。为了不改 {@code RenderSystem#refreshResolution()} /
 * {@code WindowResolutionImpl} / {@code GuiHudEditor*} 的调用形态，这里保留
 * "构造时快照" 的语义：构造那一刻读一次窗口尺寸，之后 getter 返回同一批值。
 *
 * <p>构造参数保留 1.8.9 的 {@code Minecraft} 位置但类型放宽为 Object 并忽略，
 * 因为 1.20.1 的窗口只能从单例拿；调用点写 {@code new ScaledResolution(mc)} 仍可编译。
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
            // 1.8.9 的 scaleFactor 是 int；1.20.1 的 getScaleFactor() 是 double
            // （GUI Scale = Auto 且 HiDPI 时可能出现 1.5 之类）。向下取整并至少为 1，
            // 与 1.8.9 "整数倍缩放" 的语义一致，避免 glScissor 的整数运算被小数污染。
            this.scaleFactor = Math.max(1, (int) window.getGuiScale());
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
