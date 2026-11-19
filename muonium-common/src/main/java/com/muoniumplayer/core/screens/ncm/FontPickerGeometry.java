package com.muoniumplayer.core.screens.ncm;

/** 字体页的设计像素布局；宿主坐标变换仍由 NcmExpressiveLayout 统一拥有。 */
final class FontPickerGeometry {
    static final double WIDTH = 980;
    static final double HEIGHT = 740;
    static final double ROW_HEIGHT = 76;
    static final double ROW_GAP = 6;

    final double width, height, inset, header, searchY, tabsY, summaryY, contentY;
    final double contentWidth, listWidth, listY, listHeight, previewX, previewY, previewWidth, previewHeight;
    final double footerY, actionHeight;
    final boolean columns, shortWindow;

    FontPickerGeometry(double width, double height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        inset = this.width < 480 ? 16 : 24;
        contentWidth = Math.max(1, this.width - inset * 2);
        shortWindow = this.height < 540;
        header = shortWindow ? 58 : 90;
        searchY = header;
        tabsY = searchY + 60;
        summaryY = tabsY + 62;
        contentY = summaryY + 22;
        actionHeight = shortWindow ? 42 : 48;
        footerY = this.height - inset - actionHeight;
        columns = this.width >= 800 && this.height >= 640;
        previewWidth = columns ? Math.min(340, contentWidth * .37) : contentWidth;
        listWidth = columns ? Math.max(1, contentWidth - previewWidth - 20) : contentWidth;
        previewX = columns ? inset + listWidth + 20 : inset;
        previewY = contentY;
        previewHeight = columns ? Math.max(1, footerY - 42 - contentY)
                : this.height < 620 ? 0 : 116;
        listY = columns ? contentY : contentY + previewHeight + (previewHeight > 0 ? 12 : 0);
        listHeight = Math.max(1, footerY - 54 - listY);
    }

    double actionWidth(int index) {
        return index == 0 ? (contentWidth - 16) * .44 : (contentWidth - 16) * .28;
    }

    double actionX(int index) {
        if (index == 0) return inset;
        if (index == 1) return inset + actionWidth(0) + 8;
        return inset + actionWidth(0) + actionWidth(1) + 16;
    }
}
