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

    /**
     * 3D 世界歌词<b>独立晕格</b>的 sigma 链（{@link #drawGlowOn} 专用口径）。
     * 晕格只烘高斯光形、以加色混合画在墨格之下，能量口径与 {@link #drawOn}
     * 的「薄晕」完全不同：外层 σ 大而弥散（光圈主体），内层 σ 小、贴字边形成
     * 亮环，接通「墨 → 光」的过渡。0.20×字号×spread 在默认 spread=1.3 时晕的
     * 可见半径约等于一个字高，拉满 3.0 时约 1.8 倍字高的弥散光。
     */
    private static final double[] GLOW_SIGMA_RATIO = {0.20, 0.068};

    /** 晕格层的不透明度上限（乘发光强度）。加色混合下叠加即增亮，可给到全量程。 */
    private static final double[] GLOW_LAYER_ALPHA = {0.40, 0.85};

    /** 发光质量档（用户可选）：单层宽晕（最省）/ HQ（默认，最平衡）/ Ultra（最华丽）。 */
    public static final int GLOW_TIER_STANDARD = 0;
    public static final int GLOW_TIER_HIGH = 1;
    public static final int GLOW_TIER_ULTRA = 2;
    public static final int GLOW_TIER_DEFAULT = GLOW_TIER_HIGH;

    /** 各档的层参数：σ 比例（×字号×spread）× 层 α（×强度）。探针实测定格。 */
    private static final double[][] TIER_SIGMAS = {
            {0.26},                                              // Standard 单层宽晕
            {0.26, 0.055},                                       // HQ 外柔晕 + 内亮环
            {0.55, 0.24, 0.05, 0.02},                            // Ultra 三尺度 Bloom + 白芯
    };
    private static final double[][] TIER_ALPHAS = {
            {0.50},
            {0.34, 0.68},
            {0.14, 0.34, 0.80, 0.22},
    };

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
     * 3D 世界歌词<b>独立晕格</b>的烘焙口：只画高斯光形、不画墨迹。晕格与墨格
     * 在渲染层以「晕在下（加色）、墨在上（正常）」叠加 —— 这是唯一能同时得到
     * 「字锐利 + 光真发光」的合成：加色只增不减，亮背景上光晕融入、暗背景上
     * 浮出，永远不会像 alpha 混合那样把背景压成灰雾（旧「烘进同一张格」的观感
     * 问题根源，见 docs/方案-3D歌词真实发光.md）。
     *
     * <p>与 {@link #drawOn} 同样是加色（PLUS）画进烘焙位图；只是 σ 链与能量
     * 口径换用 {@link #GLOW_SIGMA_RATIO}/{@link #GLOW_LAYER_ALPHA}。调用方
     * 用 {@link #glowPadding} 计算留白。
     *
     * @param rgb      光的颜色，低 24 位有效（跟随字色模式由调用方解析后传入）
     * @param strength 发光强度（0~1），0 表示整条链路跳过
     * @param spread   扩散倍率，1.0 是默认口径
     */
    public static void drawGlowOn(Canvas canvas, CFontRenderer font, String text, double x, double y,
                                  int rgb, double strength, double spread) {
        drawGlowOn(canvas, font, text, x, y, rgb, 0, strength, spread);
    }

    /**
     * 带墨色回收的晕格绘制：内层 σ（贴字亮环）的光色向墨色回收 —— 墨→光的
     * 色相过渡由内层接通，自定义光色（尤其白光）不再以亮环形式包住彩色墨字
     * （「色壳」割裂感的来源）；外层 σ 保留纯光色弥散。ink = 本字最终墨色，
     * 0 = 无墨色可回收（两层都用光色，退回旧口径）。
     */
    public static void drawGlowOn(Canvas canvas, CFontRenderer font, String text, double x, double y,
                                  int rgb, int ink, double strength, double spread) {
        drawGlowOn(canvas, font, text, x, y, rgb, ink, strength, spread, GLOW_TIER_DEFAULT);
    }

    /**
     * 指定质量档的晕格绘制。分层语义（探针定格，见 _scratch/glowprobe）：
     * <ul>
     *   <li><b>Standard</b>：单层宽晕 —— Emission + Outer Glow，最省。</li>
     *   <li><b>High</b>（默认）：外层柔晕 + 双层 Halo（背景融合）+ 内层贴字
     *       亮环（光色向墨回收 55%，墨→光过渡无色壳）。</li>
     *   <li><b>Ultra</b>：HQ 全部 + 第三层超宽弥散（多尺度 Bloom）+ 极窄白芯
     *       （能量过曝趋白，真实发光体质感）。</li>
     * </ul>
     * ink = 本字最终墨色（0 = 无墨可回收，内层退纯光色）。
     */
    public static void drawGlowOn(Canvas canvas, CFontRenderer font, String text, double x, double y,
                                  int rgb, int ink, double strength, double spread, int tier) {
        if (canvas == null || font == null || text == null || text.isEmpty()) {
            return;
        }
        double clampedStrength = clamp01(strength);
        if (clampedStrength <= .004) {
            return;
        }
        double scale = font.sizePx * Math.max(.05, spread);
        BlendMode previousBlend = SkijaGfx.blendMode();
        SkijaGfx.setBlendMode(BlendMode.PLUS);
        try {
            int t = Math.max(GLOW_TIER_STANDARD, Math.min(GLOW_TIER_ULTRA, tier));
            double[] sigmas = TIER_SIGMAS[t];
            double[] alphas = TIER_ALPHAS[t];
            int light = rgb & 0xFFFFFF;
            int inkC = ink & 0xFFFFFF;
            for (int layer = 0; layer < sigmas.length; layer++) {
                double layerAlpha = clampedStrength * alphas[layer];
                if (layerAlpha <= .003) {
                    continue;
                }
                int layerRgb = light;
                if (t == GLOW_TIER_HIGH && layer == 1 && inkC != 0) {
                    layerRgb = mixRgb(light, inkC, 0.55f);   // 内亮环：墨回收
                }
                if (t == GLOW_TIER_ULTRA && layer == 2 && inkC != 0) {
                    int recovered = mixRgb(light, inkC, 0.55f);
                    layerRgb = mixRgb(recovered, 0xFFFFFF, 0.35f);   // 亮环 + 白芯
                }
                if (t == GLOW_TIER_ULTRA && layer == 3) {
                    layerRgb = 0xFFFFFF;                          // 极窄白芯
                }
                font.drawGlyphsOn(canvas, text, x, y, withAlpha(layerRgb, layerAlpha), 255,
                        (float) (scale * sigmas[layer]), null);
            }
        } finally {
            SkijaGfx.setBlendMode(previousBlend);
        }
    }

    /** 按通道线性混色。 */
    private static int mixRgb(int a, int b, double t) {
        int r = (int) (((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t) + 0.5);
        int g = (int) (((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t) + 0.5);
        int b2 = (int) (((a & 255) + ((b & 255) - (a & 255)) * t) + 0.5);
        return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b2);
    }

    /**
     * 独立晕格向外扩出去多少烘焙像素。取 3σ₀：高斯尾巴在那之外已不可见。
     * 墨格与晕格的 quad 以同一 (x,y) 锚点对齐（晕格 pad ≥ 墨格 pad），晕格
     * 必须按自己的 pad 留白，否则大扩散档光晕被格边切成方块。
     */
    public static int glowPadding(CFontRenderer font, double spread) {
        return glowPadding(font, spread, GLOW_TIER_DEFAULT);
    }

    /** 指定档的晕格留白：3×最宽层 σ。Ultra 的 0.55 层比旧口径宽得多，pad 必须跟档走。 */
    public static int glowPadding(CFontRenderer font, double spread, int tier) {
        if (font == null) {
            return 0;
        }
        int t = Math.max(GLOW_TIER_STANDARD, Math.min(GLOW_TIER_ULTRA, tier));
        double widest = TIER_SIGMAS[t][0];
        return (int) Math.ceil(3.0 * font.sizePx * Math.max(.05, spread) * widest);
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