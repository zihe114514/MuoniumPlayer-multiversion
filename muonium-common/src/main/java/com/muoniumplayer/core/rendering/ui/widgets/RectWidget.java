package com.muoniumplayer.core.rendering.ui.widgets;

import com.muoniumplayer.core.rendering.Rect;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/7/8 20:28
 */
public class RectWidget extends AbstractWidget<RectWidget> {

    public RectWidget(double x, double y, double width, double height) {
        this.setBounds(x, y, width, height);
    }

    public RectWidget() {
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        // 与 RoundedRectWidget 同一套底板画法，只是圆角给 0（方形玻璃块）。
        boolean frosted = this.glassPlateBase(this.getX(), this.getY(),
                this.getWidth(), this.getHeight(), 0);
        Rect.draw(this.getX(), this.getY(), this.getWidth(), this.getHeight(), this.getHexColor());
        this.glassPlateEdge(frosted, this.getX(), this.getY(),
                this.getWidth(), this.getHeight(), 0);
    }

}
