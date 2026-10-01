package com.muoniumplayer.core.ncm.music;

/**
 * 最近一次成功解码封面的代表色。
 *
 * <p>封面服务在后台线程写入，UI 渲染线程只读取不可变快照；version 用来让主题层在换歌时
 * 只生成一次新色板，而不是每帧重复计算。</p>
 */
public final class CoverThemePalette {

    private static volatile int[] colors = new int[0];
    private static volatile int version;

    private CoverThemePalette() {
    }

    public static void set(int[] palette) {
        if (palette == null || palette.length == 0) return;
        colors = palette.clone();
        version++;
    }

    public static int[] snapshot() {
        return colors.clone();
    }

    public static int version() {
        return version;
    }
}
