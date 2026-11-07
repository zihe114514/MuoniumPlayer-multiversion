package com.muoniumplayer.core.screens.hud;

/** Geometry shared by preset controls and their hit boxes; all dimensions are layout units. */
public final class PresetSettingsLayout {
    public static final double SHEET_W = 456.0;
    public static final double BODY_TOP = 90.0;
    public static final double INSET = 23.0;
    public static final double BUTTON_H = 30.0;
    private static final double GAP = 8.0;

    private PresetSettingsLayout() { }

    public static double sheetWidth(double viewportWidth) {
        return Math.max(1.0, Math.min(SHEET_W, viewportWidth - 24.0));
    }

    public static double sheetHeight(double viewportHeight) {
        return Math.max(1.0, Math.min(502.0, viewportHeight - 24.0));
    }

    public static Box body(double viewportWidth, double viewportHeight) {
        return new Box(0, BODY_TOP, sheetWidth(viewportWidth),
                Math.max(1.0, sheetHeight(viewportHeight) - BODY_TOP - 8.0));
    }

    public static int footerColumns(double width, int count) {
        double span = Math.max(1.0, width - INSET * 2.0);
        int cap = count <= 4 ? 2 : 3;
        return Math.max(1, Math.min(Math.min(cap, count), (int) ((span + GAP) / (108.0 + GAP))));
    }

    public static double footerHeight(double width, int count) {
        int columns = footerColumns(width, count);
        int rows = (count + columns - 1) / columns;
        return 12.0 + rows * (BUTTON_H + GAP) - GAP + 12.0;
    }

    public static Box footerButton(double width, int index, int count) {
        if (count < 1 || index < 0 || index >= count) throw new IllegalArgumentException("footer index/count");
        int columns = footerColumns(width, count);
        double span = Math.max(1.0, width - INSET * 2.0);
        double buttonWidth = Math.max(1.0, (span - GAP * (columns - 1)) / columns);
        return new Box(INSET + (index % columns) * (buttonWidth + GAP),
                12.0 + (index / columns) * (BUTTON_H + GAP), buttonWidth, BUTTON_H);
    }

    /** Minimal scroll needed to make a focused control reachable, including controls in a tall wrapped action row. */
    public static double revealOffset(double scroll, double top, double height, double viewportHeight) {
        if (top < scroll) return Math.max(0, top);
        if (top + height > scroll + viewportHeight) return Math.max(0, top + height - viewportHeight);
        return scroll;
    }

    public static final class Box {
        public final double x, y, width, height;
        public Box(double x, double y, double width, double height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }
}
