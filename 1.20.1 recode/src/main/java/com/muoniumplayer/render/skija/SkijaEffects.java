package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.SaveLayerRec;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 四角双线性渐变。整个渲染层里<b>唯一</b>的自定义着色器。
 *
 * <h2>为什么非要写着色器</h2>
 * 旧渲染层的 {@code rqg.frag} / {@code rogq.frag} 对矩形四个角的颜色做<b>双线性</b>插值：
 * <pre>{@code mix(mix(c1, c2, v), mix(c3, c4, v), u)}</pre>
 * Skia 的 {@code Shader.makeLinearGradient} 只能沿一个方向插值。把横向和竖向两个线性渐变
 * 叠加起来<b>不等价</b>：叠加是相加，双线性是相乘式的插值，对角方向会多出一条错误的亮带。
 * 这正是 {@code SharedRenderingConstants.evenAdd} 的注释里记下的那类问题，
 * 所以这里用 {@link RuntimeEffect} 把原来的插值逻辑逐字搬过来。
 *
 * <h2>坐标系</h2>
 * 旧实现用 {@code ShaderProgram.drawQuadFlipped} 画四边形，纹理坐标是<b>竖向翻转</b>的：
 * 矩形顶边 v=1、底边 v=0。所以 {@code c1..c4} 的语义是
 * 左下 / 左上 / 右下 / 右上，与 {@code RQGShader.draw} 的形参名一致。
 * 本类内部把屏幕坐标换算成同样的 (u, v)，保证颜色朝向和原来一模一样。
 *
 * <h2>抖动</h2>
 * 原着色器给 rgb 加了 ±0.5/255 的哈希抖动，用来消除大面积平缓渐变上的色带。
 * 这里一并保留，包括「只抖 rgb 不抖 alpha」这个细节——给 alpha 加噪点会让
 * 大面积低透明度的图层长出颗粒。
 */
public final class SkijaEffects {

    /**
     * 注意：SkSL 的 uniform 打包规则里 {@code float2} 与 {@code float4} 的对齐要求不同，
     * 混用容易在 Java 侧算错偏移量而画出诡异结果。这里<b>全部声明成 float4</b>，
     * 于是布局就是简单的 16 字节一格，Java 侧照着顺序写就行。
     */
    private static final String CORNER_GRADIENT_SKSL = """
            uniform float4 uRect;   // xy = 矩形左上角, zw = 矩形尺寸
            uniform float4 c1;      // u=0 v=0  左下
            uniform float4 c2;      // u=0 v=1  左上
            uniform float4 c3;      // u=1 v=0  右下
            uniform float4 c4;      // u=1 v=1  右上
            uniform float4 uMisc;   // x = 全局 alpha

            half4 main(float2 coord) {
                float2 size = max(uRect.zw, float2(0.0001));
                float2 st = clamp((coord - uRect.xy) / size, 0.0, 1.0);
                // 旧实现的四边形纹理坐标是竖向翻转的，这里对齐它
                st.y = 1.0 - st.y;

                float4 c = mix(mix(c1, c2, st.y), mix(c3, c4, st.y), st.x);

                // 抖动只加在颜色上，不动 alpha
                float n = fract(sin(dot(st, float2(12.9898, 78.233))) * 43758.5453);
                c.rgb += mix(0.5 / 255.0, -0.5 / 255.0, n);

                float a = clamp(c.a * uMisc.x, 0.0, 1.0);
                // Skia 的 runtime shader 必须返回预乘 alpha 的颜色
                return half4(half3(clamp(c.rgb, 0.0, 1.0) * a), half(a));
            }
            """;

    private static RuntimeEffect cornerGradientEffect;
    /** 编译失败后置位：不再重试，退化到单方向渐变。 */
    private static boolean cornerGradientUnavailable;

    private SkijaEffects() {
    }

    private static RuntimeEffect cornerGradientEffect() {
        if (cornerGradientEffect == null && !cornerGradientUnavailable) {
            try {
                cornerGradientEffect = RuntimeEffect.makeForShader(CORNER_GRADIENT_SKSL);
            } catch (Throwable t) {
                cornerGradientUnavailable = true;
                System.out.println("[MuoniumPlayer/SkijaEffects] 四角渐变着色器编译失败，"
                        + "退化为对角线性渐变: " + t);
            }
        }
        return cornerGradientEffect;
    }

    /**
     * 建一个四角双线性渐变 shader。<b>调用方负责 close</b>。
     *
     * @return 编译不可用时返回一个等效的对角线性渐变；永远不返回 null
     */
    public static Shader cornerGradient(double x, double y, double width, double height,
                                       Color bottomLeft, Color topLeft,
                                       Color bottomRight, Color topRight,
                                       float alpha) {
        RuntimeEffect effect = cornerGradientEffect();
        if (effect == null) {
            return fallbackGradient(x, y, width, height, topLeft, bottomRight, alpha);
        }
        ByteBuffer buf = ByteBuffer.allocate(6 * 4 * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat((float) x).putFloat((float) y)
                .putFloat((float) width).putFloat((float) height);
        putColor(buf, bottomLeft);
        putColor(buf, topLeft);
        putColor(buf, bottomRight);
        putColor(buf, topRight);
        buf.putFloat(alpha).putFloat(0f).putFloat(0f).putFloat(0f);

        try (Data data = Data.makeFromBytes(buf.array())) {
            return effect.makeShader(data, new Shader[0]);
        }
    }

    /**
     * 退化路径：沿左上到右下的对角做线性渐变。
     * 不等价，但在着色器不可用时仍能保住「有渐变、方向对」这个观感。
     */
    private static Shader fallbackGradient(double x, double y, double width, double height,
                                           Color from, Color to, float alpha) {
        int a = SkijaGfx.mulAlpha(SkijaGfx.argb(from), alpha);
        int b = SkijaGfx.mulAlpha(SkijaGfx.argb(to), alpha);
        return Shader.makeLinearGradient((float) x, (float) y,
                (float) (x + width), (float) (y + height), new int[]{a, b});
    }

    private static void putColor(ByteBuffer buf, Color c) {
        buf.putFloat(c.getRed() / 255.0f)
                .putFloat(c.getGreen() / 255.0f)
                .putFloat(c.getBlue() / 255.0f)
                .putFloat(c.getAlpha() / 255.0f);
    }

    /** 用四角渐变填充一个圆角矩形。 */
    public static void fillCornerGradient(Canvas canvas, RRect rrect,
                                          double x, double y, double width, double height,
                                          Color bottomLeft, Color topLeft,
                                          Color bottomRight, Color topRight,
                                          float alpha) {
        try (Shader shader = cornerGradient(x, y, width, height,
                bottomLeft, topLeft, bottomRight, topRight, alpha)) {
            Paint paint = SkijaGfx.fillShader(shader);
            canvas.drawRRect(rrect, paint);
            // shader 只在本次绘制有效，画完立刻从复用的 Paint 上摘掉，
            // 否则会污染下一次本该是纯色的绘制。
            paint.setShader(null);
        }
    }

    /** 用四角渐变描边一个圆角矩形。 */
    public static void strokeCornerGradient(Canvas canvas, RRect rrect, double strokeWidth,
                                            double x, double y, double width, double height,
                                            Color bottomLeft, Color topLeft,
                                            Color bottomRight, Color topRight,
                                            float alpha) {
        try (Shader shader = cornerGradient(x, y, width, height,
                bottomLeft, topLeft, bottomRight, topRight, alpha)) {
            Paint paint = SkijaGfx.strokeShader(shader, strokeWidth);
            canvas.drawRRect(rrect, paint);
            paint.setShader(null);
        }
    }

    // ------------------------------------------------------------------
    // 高斯模糊：替代 GaussianBlurShader / BloomShader 两条离屏 + 自写卷积链路
    // ------------------------------------------------------------------

    /**
     * 歌词失焦模糊的等效 sigma，单位 GUI。
     *
     * <p>换算自旧实现：{@code GaussianKernel(5)} 的 {@code sigma = size / 2 = 2.5}（单位是
     * 采样步数），而 {@code blur.frag} 的步长 = {@code u_direction * u_texel_size}
     * = {@code 0.5 / RenderSystem.getWidth()}，也就是 0.5 个 GUI 单位。
     * 于是 sigma = 2.5 × 0.5 = 1.25 GUI 单位。
     */
    public static final float LYRIC_BLUR_SIGMA = 1.25f;

    /**
     * 泛光半径，单位<b>物理像素</b>。
     *
     * <p>换算自旧实现：{@code GaussianKernel(12)} 的 sigma = 6 步，而 {@code bloom.frag}
     * 的 {@code u_texel_size} 用的是 {@code Display} 尺寸、{@code u_direction} = 2，
     * 步长因此是 2 个物理像素 → sigma = 12 物理像素。
     * 用时要除以 {@link SkijaHost#guiScale()} 换成 GUI 单位。
     */
    private static final float BLOOM_SIGMA_DEVICE_PX = 12f;

    /**
     * 就地把矩形区域内<b>已经画好的内容</b>按 {@code alpha} 交叉淡化到它的模糊版本。
     *
     * <p>旧实现：把遮罩矩形画进离屏 FBO A，再以主 framebuffer 为输入做横竖两趟卷积到
     * FBO B，最后按 A 的 alpha 把 B 混合回主 framebuffer——即
     * {@code lerp(清晰, 模糊, 遮罩alpha)}。
     *
     * <p>Skia 实现：{@code clipRect} 限定范围 + 带 <b>backdrop</b> 的 {@code saveLayer}。
     * backdrop 会把「图层下方已有的像素」经模糊后作为图层初始内容；随即 restore，
     * 图层就以 paint 的 alpha 合成回去，语义与上面的 lerp 完全一致。
     * 中间不需要任何离屏 framebuffer，也不需要挂起裁剪。
     *
     * <p>用 {@link FilterTileMode#CLAMP} 而不是 DECAL：DECAL 会把区域外当成透明，
     * 导致模糊后的矩形四边出现一圈变淡的边（旧实现采样的是全屏纹理，边界外有真实像素）。
     */
    public static void backdropBlur(double x, double y, double width, double height,
                                    float sigma, float alpha) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || alpha <= 0.001f || width <= 0 || height <= 0 || sigma <= 0) {
            return;
        }
        Rect bounds = Rect.makeXYWH((float) x, (float) y, (float) width, (float) height);
        int depth = canvas.save();
        try {
            canvas.clipRect(bounds);
            try (ImageFilter blur = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP);
                 Paint paint = new Paint().setAlphaf(Math.min(1f, alpha))) {
                // 开层即关层：图层里不再追加任何绘制，需要的正是「模糊过的背景」本身。
                canvas.restoreToCount(canvas.saveLayer(new SaveLayerRec(bounds, paint, blur)));
            }
        } finally {
            canvas.restoreToCount(depth);
        }
    }

    /**
     * 泛光：把 {@code content} 画进一个挂了模糊滤镜的图层。
     *
     * <p>调用点画的是一个近黑色的圆角矩形，旧 {@code bloom.frag} 的竖向趟还会
     * {@code discard} 掉遮罩内部——净效果就是封面底下那圈柔和阴影。
     * 这里不复刻 discard：阴影内部随后会被不透明的封面完全盖住，看不见。
     *
     * <p>Skia 不可用时直接原样跑一遍 {@code content}——和旧实现「着色器编译失败就
     * 不走离屏路径」的降级策略一致：效果没了，但内容还在。
     */
    public static void bloom(Runnable content) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            content.run();
            return;
        }
        float sigma = (float) (BLOOM_SIGMA_DEVICE_PX / SkijaHost.guiScale());
        try (ImageFilter blur = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL);
             Paint paint = new Paint().setImageFilter(blur)) {
            int depth = canvas.saveLayer(new SaveLayerRec(null, paint));
            try {
                content.run();
            } finally {
                canvas.restoreToCount(depth);
            }
        }
    }

    /** 仅供静态测试用：确认着色器能编译。 */
    public static boolean cornerGradientCompiles() {
        return cornerGradientEffect() != null;
    }

    /** 仅供静态测试用：SkSL 源码。 */
    public static String cornerGradientSource() {
        return CORNER_GRADIENT_SKSL;
    }
}
