package com.muoniumplayer.core.ncm.music;

/**
 * 当前播放封面代表色的活动快照（歌词渐变消费）。
 *
 * <p>与 {@link CoverThemePalette} 同形：后台提取线程写入，渲染线程只读不可变克隆；
 * version 单调递增，消费方比对 version 在换封面时只重算一次派生数据。</p>
 */
public final class CoverGradientColors {

    private static volatile int[] colors = new int[0];
    private static volatile int version;

    private CoverGradientColors() {
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
