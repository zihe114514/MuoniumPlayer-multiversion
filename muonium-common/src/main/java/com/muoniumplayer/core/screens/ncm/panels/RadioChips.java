package com.muoniumplayer.core.screens.ncm.panels;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;

/**
 * 电台一族（{@code RadioHubPanel / DifmPanel / BroadcastPanel}）共用的 chip 排版与配色。
 *
 * <p>视觉口径与私人 FM 页的场景 chip 一致：高 17、圆角 6、玻璃薄底；选中 = 强调色实底。
 * 宽度按文案实测量（字体图集晚于首帧就绪时第一帧按估算宽摆位，下一帧自然校正），
 * 游标从左往右走，调用方自己决定换行/接续。</p>
 */
final class RadioChips {

    static final double CHIP_HEIGHT = 17.0;
    private static final double CHIP_GAP = 8.0;

    private RadioChips() {
    }

    /** 排版一枚 chip：宽度 = 文案宽 + 16 内边距，位置取游标并推进它。 */
    static void layout(RoundedButtonWidget chip, String label, double[] cursor, double y) {
        double textWidth = FontManager.pf12.getStringWidthD(label == null ? "" : label);
        chip.setBounds(Math.max(28.0, textWidth + 16.0), CHIP_HEIGHT);
        if (cursor != null) {
            chip.setPosition(cursor[0], y);
            cursor[0] += chip.getWidth() + CHIP_GAP;
        }
    }

    /**
     * 配色（SPlayer 的胶囊标签）：选中 = {@code bg-primary/16 text-primary}；未选中 =
     * {@code bg-on-surface/6}，悬停 /10。{@code hoverColor} 保留在签名里给调用方，现在悬停只抬透明度。
     */
    static void paint(RoundedButtonWidget chip, boolean selected, int accentColor, int hoverColor) {
        chip.setRadius(chip.getHeight() * .5);
        chip.setGlassRim(GlassLook.Surface.PLAYER);
        if (selected) {
            chip.setColor(accentColor);
            chip.setFillAlpha(chip.isHovering() ? .22f : .16f);
            chip.setTextColor(accentColor);
        } else {
            chip.setColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
            chip.setFillAlpha(chip.isHovering() ? .10f : .06f);
            chip.setTextColor(NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT));
        }
    }
}
