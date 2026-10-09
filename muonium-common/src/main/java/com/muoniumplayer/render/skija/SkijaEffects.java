package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.SaveLayerRec;
import io.github.humbleui.skija.SamplingMode;
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
     * 与 {@link #backdropBlur} 同一件事，只是裁剪形状换成圆角矩形。
     *
     * <p>桌面歌词的 Blossom 面板要的是一张毛玻璃卡片。按直角矩形裁的话，四个圆角外面
     * 那四小块背景照样被模糊，卡片边缘就变成了方角的模糊残影，圆角形状根本看不出来。
     * {@code clipRRect} 带抗锯齿，模糊结果的边缘因此与随后压上去的圆角底色严丝合缝。</p>
     */
    public static void backdropBlurRounded(double x, double y, double width, double height,
                                           double radius, float sigma, float alpha) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || alpha <= 0.001f || width <= 0 || height <= 0 || sigma <= 0) {
            return;
        }
        Rect bounds = Rect.makeXYWH((float) x, (float) y, (float) width, (float) height);
        RRect rounded = RRect.makeXYWH((float) x, (float) y, (float) width, (float) height,
                (float) Math.max(0.0, radius));
        int depth = canvas.save();
        try {
            canvas.clipRRect(rounded, true);
            try (ImageFilter blur = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP);
                 Paint paint = new Paint().setAlphaf(Math.min(1f, alpha))) {
                canvas.restoreToCount(canvas.saveLayer(new SaveLayerRec(bounds, paint, blur)));
            }
        } finally {
            canvas.restoreToCount(depth);
        }
    }

    /**
     * 真毛玻璃的底色：把 {@link GlassBackdrop} 每帧从游戏画面采下来那张图<b>糊过</b>再铺进圆角矩形。
     *
     * <h2>它和 {@link #backdropBlurRounded} 的差别就是这个方法存在的理由</h2>
     * 我们的 Skia 画在自己的透明离屏上，{@code saveLayer} 的 backdrop 只糊得到 Skia 这一层
     * 已经画过的像素——对一块刚开画、底下一个像素都没有的面板来说，等于什么都没糊。
     * 这里吃的则是 GPU 上那份真实的画面拷贝。所以四处调用点的写法统一是
     * 「先试这里，返回 {@code false} 再退回原来那套渐变高光」：采集停用、Skia 不可用、
     * 主题没选琉璃这三种情况下，界面必须还是玩家熟悉的那张脸，而不是凭空少一层底。
     *
     * <h2>为什么源和目的都要往外扩一圈</h2>
     * 磨砂玻璃在边缘处本来就要吃进面板<em>外面</em>的像素。只按面板矩形取源再糊，
     * {@link FilterTileMode#CLAMP} 会让圆角那一圈拿"自己的边"去补，糊出一圈比画面更淡的壳，
     * 看着像玻璃上又贴了张磨砂膜。于是两侧都按 3σ 外扩，再由 {@code clipRRect} 裁回来。
     *
     * <h2>坐标空间：问画布，不问调用方</h2>
     * x/y/width/height 是<b>画布局部</b>坐标，背景图是设备像素，两者之间隔着当前矩阵。
     * 这个映射以前由每个调用点各自报一档倍率，而报错了的症状是「面板透出屏幕别处的画面」——
     * 底图整块糊着，截图上根本读不出来，于是错了也没人知道（灵动岛在尺寸档位不是 1 的时候
     * 就是这么错的，而它的 {@code scaleAtPos} 带轴心，纯倍率压根表达不出来）。现在直接读
     * {@link GlassBackdrop#mapping(io.github.humbleui.skija.Canvas)}：Skia 把这块矩形画到
     * 屏幕上用的就是那个矩阵，玻璃采的也就是同一块。矩阵带旋转/错切时映射给不出轴对齐的源
     * 矩形，这里返回 {@code false}，调用点照旧画它那套假玻璃。
     *
     * <p>开销：默认走的是「一帧一次预糊」——整张背景在每次采集后被糊一遍（采集本身压在 ≤30Hz），
     * 这里每次调用就退化成一块贴图，所以<em>逐元素</em>铺玻璃（曲目行、按钮、二级菜单）
     * 与铺一整块面板是一个量级的价钱。退回自己挂滤镜那条路时才真的是「一块一次」：
     * Skia 要为每次 saveLayer 分配一块临时纹理并跑两趟可分离高斯，那种画法只适合面板级。</p>
     *
     * @param sigma 模糊强度，单位是<b>画布局部</b>坐标（GUI 单位）。Skia 会把它乘上当前矩阵的
     *        缩放再落到设备像素上，所以同一数值在不同 guiScale 下糊出来的相对宽度一致。
     * @param opacity 这一层背景的出场浓度，用于主题切换时的淡入淡出。
     * @return 真的铺了一层背景；{@code false} = 没有可用背景，调用方该走原来的假玻璃
     */
    public static boolean frostedBackdrop(double x, double y, double width, double height,
                                          double radius, float sigma, float opacity) {
        return frostedBackdrop(x, y, width, height, radius, sigma, opacity, false);
    }

    /**
     * 转场专用的预糊贴图路径：优先吃已经存在的<b>新鲜</b>背景档（同代或落后一代，免糊），
     * 档太旧（上一次开界面留下的）时由预糊层按本代数重糊一遍——那是全开状态本来就在付的
     * 价钱，且受每次采集的重建预算约束。没有可用预糊档时仍会贴当前原始背景，
     * 但绝不会为了补模糊而在本面板上触发 saveLayer + blur。
     */
    public static boolean frostedBackdropCached(double x, double y, double width, double height,
                                                double radius, float sigma, float opacity) {
        return frostedBackdrop(x, y, width, height, radius, sigma, opacity, true);
    }

    private static boolean frostedBackdrop(double x, double y, double width, double height,
                                           double radius, float sigma, float opacity,
                                           boolean cachedOnly) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null || width <= 0.0 || height <= 0.0 || opacity <= 0.004f) {
            return false;
        }
        // 优先吃「一帧一次预糊」那张：它已经把整张背景糊好了，这里贴一块就是纯贴图。
        // 拿不到（预糊目标建不起来、或还没采到过）就退回自己挂 blur 滤镜的旧画法——
        // 预糊是优化，不是依赖，退回去只是贵一点，玻璃本身不受影响。
        Image backdrop = cachedOnly
                ? GlassBackdrop.blurredCached(sigma)
                : GlassBackdrop.blurred(sigma);
        boolean preblurred = backdrop != null;
        if (!preblurred) {
            backdrop = GlassBackdrop.image();
            // 转场路径没有可借用的预糊档时只贴原图，绝不现场挂高斯滤镜。
            if (cachedOnly) {
                preblurred = true;
            }
        }
        if (backdrop == null) {
            return false;
        }
        GlassBackdrop.Mapping map = GlassBackdrop.mapping(canvas);
        if (map == null) {
            return false;
        }
        // 只有「自己糊」这一条路需要外扩一圈（见类注释）：预糊是在整张图上做的，
        // 面板这一块取的是已经糊好的内部像素，边缘没有可补的洞。
        float pad = preblurred || sigma <= 0f ? 0f : sigma * 3f;
        RRect rounded = RRect.makeXYWH((float) x, (float) y, (float) width, (float) height,
                (float) Math.max(0.0, radius));
        double left = x - pad, top = y - pad;
        double right = x + width + pad, bottom = y + height + pad;
        float imageWidth = backdrop.getWidth();
        float imageHeight = backdrop.getHeight();
        // 越界的一边夹到图上：面板贴屏幕边时这一侧会有极轻微的比例失真，
        // 换来的是「永远不会取到图外的空像素」。
        Rect source = Rect.makeLTRB(
                clampF(map.textureX((float) left), 0f, imageWidth),
                clampF(map.textureY((float) top), 0f, imageHeight),
                clampF(map.textureX((float) right), 0f, imageWidth),
                clampF(map.textureY((float) bottom), 0f, imageHeight));
        if (source.getWidth() <= 0f || source.getHeight() <= 0f) {
            return false;
        }
        Rect bounds = Rect.makeLTRB((float) left, (float) top, (float) right, (float) bottom);
        int depth = canvas.save();
        try {
            canvas.clipRRect(rounded, true);
            try (ImageFilter blur = !preblurred && sigma > 0f
                         ? ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP) : null;
                 Paint paint = new Paint().setAlphaf(Math.min(1f, opacity))) {
                if (blur != null) {
                    paint.setImageFilter(blur);
                }
                // strict=false：外扩与夹边让源、目的两个矩形比例未必完全相等，
                // 这里要的是「铺满面板」，不是「不许形变」。
                canvas.drawImageRect(backdrop, source, bounds, SamplingMode.LINEAR, paint, false);
            }
            paintBloom(canvas, source, bounds, opacity, cachedOnly);
        } finally {
            canvas.restoreToCount(depth);
        }
        return true;
    }

    /**
     * 亮部溢出那一层：贴在磨砂底<em>之上</em>、面板自己的底色与文字之下，用相加混出来一圈亮晕。
     *
     * <p>为什么画在 frosted 里而不是各调用点自己贴：这层的意思就是「玻璃后面那团亮的东西透过来」，
     * 它必须与底用同一块剪形、同一份源矩形，两处各算一遍就会在面板贴屏幕边时错位半像素——
     * 而那个位置正好是 bloom 最亮的地方，错半像素就是一条白边。</p>
     *
     * <p>强度为 0 时 {@link GlassBackdrop#bloom()} 给 null，这里<em>一次绘制都不发</em>：
     * 新开销默认必须是零，不然「默认关着」只是话术。</p>
     */
    private static void paintBloom(Canvas canvas, Rect source, Rect bounds, float opacity, boolean cachedOnly) {
        Image bloom = cachedOnly ? GlassBackdrop.bloomCached() : GlassBackdrop.bloom();
        if (bloom == null) {
            return;
        }
        float alpha = GlassBackdrop.bloomStrength() * Math.min(1f, opacity);
        if (alpha <= 0.004f) {
            return;
        }
        try (Paint paint = new Paint().setBlendMode(BlendMode.PLUS).setAlphaf(alpha)) {
            canvas.drawImageRect(bloom, source, bounds, SamplingMode.LINEAR, paint, false);
        }
    }

    /**
     * 全表面液态玻璃：不是把一张模糊图贴进圆角，而是让背景在整块玻璃内部
     * 按 squircle 距离场发生非线性折射。失败时返回 false，让调用点走旧的假玻璃。
     */
    public static boolean liquidBackdrop(double x, double y, double width, double height,
                                         double radius, float sigma, float opacity) {
        if (opacity <= 0.004f || width <= 0.0 || height <= 0.0) {
            return false;
        }
        return GlassLiquidSurface.draw(x, y, width, height, radius,
                1f, Math.min(1f, Math.max(0f, opacity)), sigma);
    }

    /** 液态玻璃的弹窗版本：同一套整面折射，再压一层与弹窗背景匹配的暗纱。 */
    public static boolean dimmedLiquidBackdrop(double x, double y, double width, double height,
                                                double radius, float sigma, float opacity) {
        if (!liquidBackdrop(x, y, width, height, radius, sigma, opacity)) {
            return false;
        }
        int veil = Math.round(SHEET_VEIL * Math.max(0f, Math.min(1f, opacity)) * 255f);
        SkijaShapes.roundedRect(x, y, width, height, radius, (veil & 0xFF) << 24);
        return true;
    }
    /**
     * 弹窗那一档的「压暗过的真背景」：先按 {@link #frostedBackdrop} 贴一块糊过的真画面，
     * 再在同一条圆角里压一层黑纱。
     *
     * <h2>为什么弹窗不能直接吃真背景</h2>
     * 一块贴在面板上的行或按钮，背后就是这一帧采进来的那块画面，贴上去自然对得上。弹窗不是：
     * 它背后是我们自己的面板加一层 52% 黑遮罩，而这两样都不在采集里。直接贴真背景，等于
     * 在一张压暗的桌面上开一扇透着大晴天的窗——<b>弹窗会比它周围更亮</b>，那读起来不是玻璃，
     * 是贴图穿帮。2026-09-30 玩家反馈「二级菜单没有液态玻璃」，当时的做法是干脆只描一道边，
     * 那是把这一档整个跳过，不是解决。
     *
     * <h2>那层黑纱补的就是遮罩的暗度</h2>
     * {@link #SHEET_VEIL} 跟的是遮罩那一档，于是透出去的是「压暗过的真画面」：形状还在
     * （那是玻璃），亮度已经落回遮罩下面那一层。它仍然比周围那块被遮罩压住的面板略亮——
     * 这正对：iOS 那张抽屉板从来就比它底下的暗背景亮一档，读作「一块干净的玻璃盖在上面」。
     *
     * <p>两层用同一个圆角、同一个 {@code opacity}，所以弹窗淡入时是一起淡入的，
     * 不会出现「先看见一块亮玻璃，黑纱才跟上」。</p>
     *
     * @return 真铺上了；{@code false} = 这一帧没有可用背景，调用方该画它自己那套假玻璃
     */
    public static boolean dimmedBackdrop(double x, double y, double width, double height,
                                         double radius, float sigma, float opacity) {
        if (!frostedBackdrop(x, y, width, height, radius, sigma, opacity)) {
            return false;
        }
        int veil = Math.round(SHEET_VEIL * Math.max(0f, Math.min(1f, opacity)) * 255f);
        SkijaShapes.roundedRect(x, y, width, height, radius, (veil & 0xFF) << 24);
        return true;
    }

    /** 弹窗那层黑纱的浓度，跟的是 {@code NcmSheet} 遮罩那一档（0.52）略低一点。 */
    private static final float SHEET_VEIL = 0.46f;

    /** 换算全程走 double，只在最后落进 Skia 的 float 矩形时收一次口。 */
    private static float clampF(double value, float min, float max) {
        float clamped = (float) value;
        return clamped < min ? min : (clamped > max ? max : clamped);
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
