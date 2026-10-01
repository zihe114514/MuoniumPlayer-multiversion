package com.muoniumplayer.compat.mc;

import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;

/**
 * 1.8.9 {@code net.minecraft.client.gui.Gui} 里本项目唯一用到的静态方法 {@code drawRect}。
 *
 * <p>直接转发到项目自己的 {@link RenderSystem#drawRect(double, double, double, double, int)}：
 * 两者语义相同（left/top/right/bottom + ARGB，开混合、关贴图、关 alpha test），
 * 而且转发后 HUD 编辑器的矩形与播放器其余部分走同一条绘制路径，
 * 渲染后端重写时只需改一处。
 */
public final class Gui {

    private Gui() {
    }

    public static void drawRect(int left, int top, int right, int bottom, int color) {
        RenderSystem.drawRect(left, top, right, bottom, color);
    }
}
