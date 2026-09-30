package com.muoniumplayer.core.rendering.ui.widgets;

import com.muoniumplayer.core.rendering.ui.AbstractWidget;

/**
 * @author MuoniumPlayer Contributors
 * Date: 2025/7/8 20:28
 */
public class RoundedRectWidget extends AbstractWidget<RoundedRectWidget> {

    private double radius = 0;

    public RoundedRectWidget(double x, double y, double width, double height) {
        this.setBounds(x, y, width, height);
    }

    public RoundedRectWidget() {
    }

    @Override
    public void onRender(double mouseX, double mouseY) {
        // 标了玻璃底板的控件走这三步：先贴预糊背景，再画自己那层底色，最后压边缘。
        // 顺序不能换 —— 边缘折射是「玻璃这个表面」的效应，画在底色之前会被底色盖掉。
        boolean frosted = this.glassPlateBase(this.getX(), this.getY(),
                this.getWidth(), this.getHeight(), this.radius);
        this.roundedRect(this.getX(), this.getY(), this.getWidth(), this.getHeight(), this.radius, this.getHexColor());
        this.glassPlateEdge(frosted, this.getX(), this.getY(),
                this.getWidth(), this.getHeight(), this.radius);
    }

    public RoundedRectWidget setRadius(double radius) {
        this.radius = radius;
        return this;
    }

    public double getRadius() {
        return radius;
    }
}
