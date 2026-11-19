package com.muoniumplayer.core.screens.ncm.effects;

/** 音效工作台自己的布局轴；绘制、命中与滚动共用这份几何。 */
public final class EffectsLayout {

    public record Box(double x, double y, double width, double height) {
        public double right() { return x + width; }
        public double bottom() { return y + height; }
        public double centerX() { return x + width * .5; }
        public double centerY() { return y + height * .5; }
        public boolean contains(double px, double py) {
            return width > 0 && height > 0 && px >= x && py >= y && px < right() && py < bottom();
        }
        public Box inset(double amount) {
            double dx = Math.min(Math.max(0, amount), width * .5);
            double dy = Math.min(Math.max(0, amount), height * .5);
            return new Box(x + dx, y + dy, Math.max(0, width - dx * 2), Math.max(0, height - dy * 2));
        }
        public Box offset(double dx, double dy) { return new Box(x + dx, y + dy, width, height); }
        public Box intersect(Box other) {
            double left = Math.max(x, other.x), top = Math.max(y, other.y);
            return new Box(left, top, Math.max(0, Math.min(right(), other.right()) - left),
                    Math.max(0, Math.min(bottom(), other.bottom()) - top));
        }
    }

    /** 宿主保持播放器布局轴，工作台内部使用设计轴；两者之间只做一次等比换算。 */
    public record Viewport(double hostWidth, double hostHeight, double unitsPerLayout) {
        public Viewport {
            if (!Double.isFinite(hostWidth) || !Double.isFinite(hostHeight)
                    || !Double.isFinite(unitsPerLayout) || unitsPerLayout <= 0) {
                throw new IllegalArgumentException("工作台坐标倍率和尺寸必须有效");
            }
            hostWidth = Math.max(0, hostWidth);
            hostHeight = Math.max(0, hostHeight);
        }
        public double width() { return hostWidth * unitsPerLayout; }
        public double height() { return hostHeight * unitsPerLayout; }
        public double renderScale() { return 1.0 / unitsPerLayout; }
        public double toDesign(double coordinate) { return coordinate * unitsPerLayout; }
        public double toLayout(double coordinate) { return coordinate / unitsPerLayout; }
        public Box toLayout(Box box) {
            return new Box(toLayout(box.x), toLayout(box.y), toLayout(box.width), toLayout(box.height));
        }
    }

    public static final double SURFACE_WIDTH_RATIO = .74;
    public static final double SURFACE_HEIGHT_RATIO = .78;
    public static final double NAVIGATION_WIDTH = 210;

    public final Box surface;
    public final Box navigation;
    public final Box toolbar;
    public final Box viewport;
    public final Box status;
    public final boolean rail;
    public final boolean usable;

    private EffectsLayout(Box surface, Box navigation, Box toolbar, Box viewport, Box status,
                          boolean rail, boolean usable) {
        this.surface = surface;
        this.navigation = navigation;
        this.toolbar = toolbar;
        this.viewport = viewport;
        this.status = status;
        this.rail = rail;
        this.usable = usable;
    }

    public static EffectsLayout calculate(double width, double height, double entranceOffset) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(entranceOffset)) {
            throw new IllegalArgumentException("工作台尺寸必须是有限数值");
        }
        width = Math.max(0, width);
        height = Math.max(0, height);
        double margin = Math.min(20, Math.min(width, height) * .03);
        double w = width * SURFACE_WIDTH_RATIO;
        double h = height * SURFACE_HEIGHT_RATIO;
        double y = Math.max(margin, Math.min(height - h - margin,
                (height - h) * .5 + entranceOffset));
        Box surface = new Box((width - w) * .5, y, w, h);
        boolean rail = w >= 780 && h >= 430;
        double navWidth = rail ? NAVIGATION_WIDTH : 0;
        double header = Math.min(68, h);
        double navHeight = rail ? 0 : Math.min(48, Math.max(0, h - header));
        Box navigation = rail
                ? new Box(surface.x + 10, surface.y + header + 12, navWidth - 20, Math.max(0, h - header - 58))
                : new Box(surface.x + 12, surface.y + header, Math.max(0, w - 24), navHeight);
        double contentX = surface.x + navWidth + 22;
        double contentWidth = Math.max(0, w - navWidth - 44);
        double top = surface.y + header + navHeight;
        double footerHeight = Math.min(44, Math.max(0, surface.bottom() - top));
        double toolbarHeight = Math.min(100, Math.max(0, surface.bottom() - footerHeight - top));
        Box toolbar = new Box(contentX, top, contentWidth, toolbarHeight);
        Box viewport = new Box(contentX, toolbar.bottom(), contentWidth,
                Math.max(0, surface.bottom() - footerHeight - 8 - toolbar.bottom()));
        Box status = new Box(surface.x + navWidth + 22, surface.bottom() - footerHeight,
                contentWidth, footerHeight);
        return new EffectsLayout(surface, navigation, toolbar, viewport, status, rail, w >= 280 && h >= 260);
    }

    public static Box chart(Box card) {
        return new Box(card.x + 40, card.y + 40, Math.max(1, card.width - 58), Math.max(1, card.height - 76));
    }

    public static double frequencyX(double frequency, Box plot) {
        if (!Double.isFinite(frequency) || frequency <= 0) throw new IllegalArgumentException("非法频率");
        return plot.x + clamp(Math.log(frequency / 20.0) / Math.log(1000.0)) * plot.width;
    }

    public static double gainY(double gain, Box plot) {
        if (!Double.isFinite(gain)) throw new IllegalArgumentException("非法增益");
        return plot.y + (1 - clamp((gain + 12) / 24)) * plot.height;
    }

    public static double gainAtY(double y, Box plot) {
        if (!Double.isFinite(y)) throw new IllegalArgumentException("非法坐标");
        return 12 - clamp((y - plot.y) / Math.max(1, plot.height)) * 24;
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}
