package com.muoniumplayer.core.rendering.font;

import com.muoniumplayer.render.skija.SkijaGfx;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;

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
     * 辉光层的 sigma，按字号取比例。只作用于<b>晕形</b>（见 {@link #drawOn} 的
     * 两层合成），必须从大到小排——{@link #padding} 直接拿第 0 项算留白。
     */
    private static final double[] SIGMA_RATIO = {0.246, 0.080, 0.0246};

    /** 辉光层各自的不透明度上限，还要再乘发光强度。越靠外越淡。 */
    private static final double[] LAYER_ALPHA = {0.20, 0.34, 0.42};

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
        Canvas target = SkijaGfx.canvas();
        if (target == null) {
            return;
        }
        drawOn(target, font, text, x, y, rgb, alpha, strength, spread);
    }

    /**
     * 画到<b>指定的</b>画布 —— 3D 世界歌词的纹理烘焙用：光晕与文字一起烘进一张离屏位图。
     * 语义与 {@link #draw} 完全一致（同样的三层 sigma/alpha、加色混合），只是画布由调用方给。
     */
    public static void drawOn(Canvas canvas, CFontRenderer font, String text, double x, double y,
                              int rgb, double alpha, double strength, double spread) {
        if (canvas == null || font == null || text == null || text.isEmpty()) {
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
            // 辉光的观感关键：晕要「薄」。过去三层把文字实体重画三遍，紧贴笔画
            // 处三层 alpha 加色叠出近不透明的实色壳 —— 用户反馈「像包了一层
            // 颜色」。改法：两层都把整体 alpha 压低、只保留大 σ 的柔晕与中 σ
            // 的过渡（小 σ 层紧贴笔画、与墨色本体几乎重合，视觉上只会形成
            // 壳，删掉由墨色本体自己提供「亮边」）。晕形仍然是文字形状的
            // blur 副本，但因为 alpha 低、无小 σ 层，笔画边缘由墨色抗锯齿
            // 灰阶直接过渡到柔晕，不再是「色壳包字」。
            for (int layer = 0; layer < 2; layer++) {
                double layerAlpha = clampedAlpha * clampedStrength * LAYER_ALPHA[layer];
                if (layerAlpha <= .003) {
                    continue;
                }
                font.drawGlyphsOn(canvas, text, x, y, withAlpha(rgb, layerAlpha), 255,
                        (float) (scale * SIGMA_RATIO[layer]), null);
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