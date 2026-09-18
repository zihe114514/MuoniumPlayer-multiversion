package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.rendering.entities.impl.ScrollText;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 针对错误报告 d2c713e3 中客户端崩溃的回归探针。
 *
 * 过渡中的 ControlsBar 布局产生了负的 LabelWidget 宽度，
 * 并传到 ScrollText 和 Skia 的 Rect::makeXYWH。这些探针不需要 GL/Skija
 * 帧：守卫必须在构造任何本地矩形之前拒绝无效输入。
 */
class ScrollTextSafetyTest {

    @Test
    void nonPositiveScrollWidthIsRejectedBeforeSkia() {
        ScrollText scrollText = new ScrollText();
        scrollText.render(null, "transient layout", 0.0, 0.0, -9.700003, 0xFFFFFFFF);
        assertFalse(scrollText.isScrolling);
    }

    @Test
    void labelWidthSentinelAndTransientNegativeValuesAreNormalized() {
        LabelWidget label = new LabelWidget("transient layout");
        assertEquals(-1.0, label.setMaxWidth(-1.0).getMaxWidth(), 0.0);
        assertEquals(0.0, label.setMaxWidth(-9.700003).getMaxWidth(), 0.0);
        assertEquals(0.0, label.setMaxWidth(Double.NaN).getMaxWidth(), 0.0);
    }
}
