package com.muoniumplayer.core.rendering.font;

import com.muoniumplayer.render.skija.SkijaGfx;

import io.github.humbleui.skija.BlendMode;

/**
 * 文字的高斯光晕，全项目歌词发光的唯一实现。
 *
 * <p>项目里原先有四份各自为政的发光代码（经典全屏、Apple / Luminous / Tilt 共用的舞台层、
 * Cadenza、HUD 桌面歌词），做法都一样：把同一串文本按八个方向偏移各画一遍、逐层降透明度。
 * 那是 1.8.9 时代没有后处理管线的权宜之计，而且它<b>不是</b>高斯——八个副本之间填不满，
 * 出来的是方块状的灰雾，连笔画之间的空隙都糊上，字反而更难认。
 *
 * <p>现在渲染层是 Skia，{@code MaskFilter} 能直接对字形的覆盖遮罩做真高斯，一层一次绘制，
 * 不需要离屏图层。模糊后的字形遮罩会连同 sigma 一起进字形图集缓存，所以同一个字第二帧起
 * 就是一次贴图：实测一行七个字画两层，高斯 0.027ms，八方向偏移 0.205ms。
 *
 * <p>三层从外到内：最外层是散开的氛围光，中间层过渡，最里层紧贴笔画当亮边。三层都用
 * <b>加色</b>混合——光叠在一起该更亮，SRC_OVER 叠出来的是「更白的纸」而不是光。加色只在暗
 * 背景上成立，而歌词的底不管是压暗的模糊封面还是游戏画面，都足够暗。
 */
public final class TextGlow {

    /**
     * 三层的 sigma，按字号取比例；歌词字号 32.5 时依次是 8.0 / 2.6 / 0.8 GUI 像素。
     * 必须从大到小排——{@link #padding} 直接拿第 0 项算留白。
     */
    private static final double[] SIGMA_RATIO = {0.246, 0.080, 0.0246};

    /** 三层各自的不透明度上限，还要再乘发光强度。越靠外越淡。 */
    private static final double[] LAYER_ALPHA = {0.24, 0.50, 0.60};

    private TextGlow() {
    }

    public static void draw(CFontRenderer font, String text, double x, double y,
                            int rgb, double alpha, double strength) {
        draw(font, text, x, y, rgb, alpha, strength, 1.0);
    }

    /**
     * @param rgb      光的颜色，低 24 位有效；一般就是字本身的颜色
     * @param alpha    这段文字的基础不透明度（0~1），淡入淡出乘在这里
     * @param strength 发光强度（0~1），0 表示整条链路跳过
     * @param spread   扩散倍率，1.0 是歌词的默认口径；调大光晕更散更远
     */
    public static void draw(CFontRenderer font, String text, double x, double y,
                            int rgb, double alpha, double strength, double spread) {
        if (font == null || text == null || text.isEmpty()) {
            return;
        }
        double clampedStrength = clamp01(strength);
        double clampedAlpha = clamp01(alpha);
        if (clampedStrength <= .004 || clampedAlpha <= .004) {
            return;
        }
        double scale = font.sizePx * Math.max(.05, spread);
        BlendMode previousBlend = SkijaGfx.blendMode();
        SkijaGfx.setBlendMode(BlendMode.PLUS);
        try {
            for (int layer = 0; layer < SIGMA_RATIO.length; layer++) {
                double layerAlpha = clampedAlpha * clampedStrength * LAYER_ALPHA[layer];
                if (layerAlpha <= .003) {
                    continue;
                }
                font.drawStringGlow(text, x, y, withAlpha(rgb, layerAlpha),
                        scale * SIGMA_RATIO[layer]);
            }
        } finally {
            SkijaGfx.setBlendMode(previousBlend);
        }
    }

    /**
     * 光晕向外扩出去多少像素。往离屏图层里画发光时，图层四周至少要留这么宽的白，
     * 否则边界会把光晕切成方块——sigma 比原先那个偏移半径大得多，这个坑很容易踩。
     *
     * <p>取 3σ：高斯在那之外的尾巴已经看不见了。
     */
    public static int padding(CFontRenderer font, double spread) {
        if (font == null) {
            return 0;
        }
        return (int) Math.ceil(3.0 * font.sizePx * Math.max(.05, spread) * SIGMA_RATIO[0]);
    }

    private static int withAlpha(int rgb, double alpha) {
        int a = (int) Math.round(clamp01(alpha) * 255.0);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static double clamp01(double value) {
        return value < 0 ? 0 : (value > 1 ? 1 : value);
    }
}