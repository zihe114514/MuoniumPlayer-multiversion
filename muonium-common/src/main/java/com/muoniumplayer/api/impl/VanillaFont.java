package com.muoniumplayer.api.impl;

import com.muoniumplayer.compat.mc.FontRenderer;
import com.muoniumplayer.api.interfaces.render.Font;

/**
 * 原版字体的 Font 适配器。1.8.9 直接包 {@code net.minecraft.client.gui.FontRenderer}，
 * 现在包移植层的 {@link FontRenderer}（内部是原版 Font + 当前帧 GuiGraphicsExtractor）。
 * 语义不变：getWidth/getHeight 随时可查，drawString 只在渲染帧内有效。
 */
public class VanillaFont implements Font {

    private final FontRenderer renderer;

    public VanillaFont(FontRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public int getWidth(String text) {
        return renderer.getStringWidth(text);
    }

    @Override
    public int getHeight() {
        return renderer.FONT_HEIGHT;
    }

    @Override
    public void drawString(String text, double x, double y, int color) {
        renderer.drawString(text, (int) x, (int) y, color);
    }
}
