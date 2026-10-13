package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.utils.cursor.CursorUtils;

/**
 * 两个播放器界面共用的音量滑块。
 *
 * <p>该控件只拥有交互/动画状态。实际音量由
 * {@link CloudMusic} 持久化并应用，使全屏播放器、
 * 紧凑控件和可配置热键保持同步。</p>
 */
public final class VolumeControl implements SharedRenderingConstants {

    private static final double HIT_HEIGHT = 18.0;
    private static final double MIN_BAR_HEIGHT = 4.5;
    private static final double MAX_BAR_HEIGHT = 7.5;
    private static final double ENDPOINT_GAP = 6.0;

    private double renderedBarHeight = MIN_BAR_HEIGHT;
    private boolean dragging;

    /**
     * 渲染通用的全屏播放器音量呈现：低/高
     * 音量字形框住一个平滑动画的滑块。
     *
     * @param x 完整控件左缘（含两个字形）
     * @param width 完整控件宽度（含两个字形）
     */
    public boolean render(double mouseX, double mouseY, double x, double centerY,
                          double width, float alpha) {
        if (width <= 1.0 || alpha <= .01f) {
            dragging = false;
            return false;
        }

        double lowGlyphWidth = FontManager.music40.getStringWidthD("I");
        double highGlyphWidth = FontManager.music40.getStringWidthD("J");
        double sliderX = x + lowGlyphWidth + ENDPOINT_GAP;
        double sliderWidth = Math.max(1.0, width - lowGlyphWidth - highGlyphWidth - ENDPOINT_GAP * 2.0);

        boolean hovering = isHovered(mouseX, mouseY, sliderX, centerY - HIT_HEIGHT * .5,
                sliderWidth, HIT_HEIGHT);
        boolean leftDown = Mouse.isButtonDown(0);
        // 模态浮层（歌词选词、建单、导入等）压在界面上时，这一帧的按住不属于音量条：
        // 点一下遮罩就会把下面的音量拖到鼠标位置。全屏页与底栏共用这一个实例，闸门一处生效。
        if (NCMScreen.getInstance().isModalOverlayActive()) {
            hovering = false;
            dragging = false;
        } else if (!leftDown) {
            dragging = false;
        } else if (hovering) {
            dragging = true;
        }

        if (dragging) {
            double rawPercent = (mouseX - sliderX) / sliderWidth;
            CloudMusic.setVolume((float) rawPercent, true);
        }

        renderedBarHeight = Interpolations.interpolate(renderedBarHeight,
                (hovering || dragging) ? MAX_BAR_HEIGHT : MIN_BAR_HEIGHT, .28f);
        double radius = Math.min(renderedBarHeight * .5, 2.8);
        int trackColor = RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.BORDER),
                alpha * ((hovering || dragging) ? .92f : .68f));
        int fillColor = RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.ACCENT), alpha);
        int iconColor = RenderSystem.reAlpha(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                alpha * ((hovering || dragging) ? .95f : .64f));

        roundedRect(sliderX, centerY - renderedBarHeight * .5, sliderWidth, renderedBarHeight,
                radius, trackColor);

        // 显示走 displayVolume()：镜像时这根条画的是外部播放器在音量合成器里的那一格。
        double filledWidth = sliderWidth * CloudMusic.displayVolume();
        if (filledWidth > .05) {
            roundedRect(sliderX, centerY - renderedBarHeight * .5, filledWidth, renderedBarHeight,
                    Math.min(radius, filledWidth * .5), fillColor);
        }

        // 小拇指仅在交互时可见；静止状态保持紧凑，
        //与原全屏控件一样，而当前值仍易于瞄准。
        if (hovering || dragging) {
            double thumbX = sliderX + Math.max(0.0, Math.min(sliderWidth, filledWidth));
            roundedRect(thumbX - 1.7, centerY - 1.7, 3.4, 3.4, 1.7,
                    RenderSystem.reAlpha(0x00FFFFFF, alpha * .94f));
        }

        double iconY = centerY - FontManager.music40.getHeight() * .5 - .5;
        FontManager.music40.drawString("I", x, iconY, iconColor);
        FontManager.music40.drawString("J", x + width - highGlyphWidth, iconY, iconColor);

        if (hovering || dragging) {
            CursorUtils.setOverride(CursorUtils.HAND);
        }
        return hovering || dragging;
    }
}
