package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 真玻璃的<b>边缘折射</b>：把采下来的背景沿圆角轮廓的法线掰一下再采样，三个通道各掰不同距离
 * （色散），外加一圈随入射角变化的亮边（菲涅尔）。
 *
 * <h2>为什么只画边缘那一圈</h2>
 * 一块磨砂玻璃的中间是<b> diffuse </b>的（P1 那层糊过的背景已经负责了），只有贴着轮廓那几像素
 * 才看得出「后面的画面被折了一下」。所以本层刻意在往里超过厚度之后直接返回透明：
 * 既省掉全屏三次采样的开销，也不会把已经糊好的中间层再盖一遍。
 *
 * Snell 近似那三行（{@code xR = 1 - d/thickness} → {@code thetaI = asin(xR²)} →
 * {@code thetaT = asin(sin(thetaI)/n)} → {@code edgeFactor = -tan(thetaT - thetaI)}）、
 * 三通道折射率（0.985 / 1.0 / 1.015）与菲涅尔的幂次，逐式搬自
 * <b>没有</b>搬它的两件事：一是全屏 post pass（它 hook {@code GameRenderer#renderBlur} 并
 * {@code ci.cancel()}，我们在自己的 Skia 图层上按矩形画），二是「读同一张纹理当输入再写回去」
 * ——那条路在我们的合成分层里会直接读到自己。它把每块面板的 SDF 上传进 UBO 在一个 pass 里画完，
 * 我们目前一层面板一个 shader 实例：面板数量是个位数，这个差别不值得为它引入 uniform buffer。
 *
 * <h2>坐标系</h2>
 * {@code coord} 是画布局部单位，而背景图是图像素，两边的对应关系每帧问一次画布
 * （{@link GlassBackdrop#mapping}）：缩放进 shader 的局部矩阵，平移进 {@code uSample}。
 * 调用点不再报倍率——报错了的症状是「边缘折的是画面别处」，而这一圈只有几像素、背后又糊着，
 * 肉眼根本判不出来（2026-09-29 复查时灵动岛在尺寸档位不是 1 就是这个状态）。
 * 矩阵带旋转/错切时映射给不出轴对齐的取样矩形，这里直接不画。
 *
 * <h2>失败就什么都没有</h2>
 * 编译失败、没有背景、Skia 停用，一律返回 false，调用点于是只剩 P1 那层糊背景——
 * 玻璃照样是玻璃，只是不折。
 */
public final class GlassRefraction {

    /**
     * uniform 一律 float4、16 字节一格：与 {@link SkijaEffects} 里四角渐变同一套约定，
     * 混用 float2/float4 会让 Java 侧的偏移量算错而<b>不报任何错</b>。
     */
    private static final String SKSL = """
            uniform shader uBackdrop;
            uniform float4 uRect;    // xy = 左上角, zw = 宽高（画布局部单位）
            uniform float4 uShape;   // x = 圆角半径, y = 折射厚度, z = 折射率, w = 强度
            uniform float4 uMisc;    // x = 色散, y = 相位, z = 菲涅尔, w = 整体 alpha
            uniform float4 uSample;  // xy = 取样平移（画布局部单位），zw 备用

            float sdRoundBox(float2 p, float2 center, float2 halfSize, float r) {
                float2 q = abs(p - center) - (halfSize - float2(r));
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            float2 sdNormal(float2 p, float2 center, float2 halfSize, float r) {
                // 中心差分而不是解析梯度：解析式在直边上是常量、在圆角上是径向，
                // 两段拼起来的地方法线会跳，跳变在边缘那一圈上就是一道可见的折痕。
                float e = 0.75;
                float dx = sdRoundBox(p + float2(e, 0.0), center, halfSize, r)
                         - sdRoundBox(p - float2(e, 0.0), center, halfSize, r);
                float dy = sdRoundBox(p + float2(0.0, e), center, halfSize, r)
                         - sdRoundBox(p - float2(0.0, e), center, halfSize, r);
                float len = max(sqrt(dx * dx + dy * dy), 0.0001);
                return float2(dx, dy) / len;
            }

            float edgeOffset(float depth, float thickness, float n) {
                float xR = clamp(1.0 - depth / max(thickness, 0.001), 0.0, 1.0);
                float thetaI = asin(clamp(xR * xR, 0.0, 1.0));
                float thetaT = asin(clamp(sin(thetaI) / max(n, 0.01), 0.0, 1.0));
                return -tan(thetaT - thetaI) * thickness;
            }

            half4 main(float2 coord) {
                float2 center = uRect.xy + uRect.zw * 0.5;
                float2 halfSize = max(uRect.zw * 0.5, float2(0.5));
                float r = min(uShape.x, min(halfSize.x, halfSize.y));
                float thickness = max(uShape.y, 1.0);
                float depth = -sdRoundBox(coord, center, halfSize, r);
                if (depth <= 0.0 || uShape.w <= 0.002) {
                    return half4(0.0);
                }
                float band = 1.0 - smoothstep(0.0, thickness, depth);
                if (band <= 0.002) {
                    return half4(0.0);
                }
                float2 normal = sdNormal(coord, center, halfSize, r);
                float off = edgeOffset(depth, thickness, uShape.z);
                float disp = uMisc.x;
                // 取样原点整体平移一档：背景图按「局部单位 → 图像素」的仿射映射取，其中平移
                // 那一项每帧都在变（灵动岛的轴心缩放、面板出场动画的位移），而 shader 的局部
                // 矩阵只按缩放缓存，所以平移走 uniform，矩阵不动。
                float2 shifted = coord + uSample.xy;
                // 三通道各用自己的折射率：同一块玻璃的边缘因此带一圈极细的彩虹，
                // 这正是「玻璃」和「半透明黑块」读起来不一样的地方。
                float2 base = shifted + normal * off;
                float red = uBackdrop.eval(shifted + normal * (off * (1.0 - disp))).r;
                float green = uBackdrop.eval(base).g;
                float blue = uBackdrop.eval(shifted + normal * (off * (1.0 + disp))).b;
                // 极慢的呼吸只改高光不改几何：文字和点击区一动不动，表面却不像贴图。
                float breathe = 0.92 + 0.08 * sin(uMisc.y + coord.x * 0.008);
                float fresnel = pow(band, 2.5) * uMisc.z * breathe;
                float3 rgb = float3(red, green, blue) + float3(0.86, 0.93, 1.0) * fresnel;
                float a = clamp(uMisc.w * band, 0.0, 1.0);
                // Skia 的 runtime shader 必须返回预乘 alpha
                return half4(half3(clamp(rgb, float3(0.0), float3(1.0))) * half(a), half(a));
            }
            """;

    /** 玻璃的折射率：1.45 是钠钙玻璃的量级，再大边缘就明显「凸透镜」了。 */
    private static final float REFRACTIVE_INDEX = 1.45f;
    /** 三通道折射率的相对偏差，决定那圈彩虹有多宽。 */
    private static final float DISPERSION = 0.055f;
    /** 菲涅尔亮边的强度，独立于折射：折射管「折多少」，这个管「亮边多亮」。 */
    private static final float FRESNEL = 0.34f;

    private static RuntimeEffect effect;
    private static boolean unavailable;

    /**
     * 背景图的 shader 视图。
     *
     * <p>缓存键用「图像句柄 + 该轴倍率」而不是每帧重建：Shader 是<b>视图</b>，纹理内容每帧变
     * 但尺寸与倍率不变，重建会让 Skia 每帧丢弃并重新绑定一个外部纹理（同
     * {@link GlassBackdrop} 里 Image 只建一次的取舍）。矩阵里的<em>平移</em>那一半不进缓存：
     * 灵动岛的轴心缩放与面板出场动画每帧都在挪它，挪进缓存就等于每帧重建，所以平移走
     * {@code uSample} 那个 uniform（本来每帧就要重写），缓存只留缩放。</p>
     */
    private static Shader backdropShader;
    private static Image backdropShaderImage;
    private static float backdropShaderScaleX;
    private static float backdropShaderScaleY;

    private GlassRefraction() {
    }

    private static RuntimeEffect effect() {
        if (effect == null && !unavailable) {
            try {
                effect = RuntimeEffect.makeForShader(SKSL);
            } catch (Throwable failure) {
                unavailable = true;
                System.out.println("[MuoniumPlayer/GlassRefraction] 折射着色器编译失败，"
                        + "玻璃退回只有模糊的那一层: " + failure);
            }
        }
        return effect;
    }

    /**
     * 在一块玻璃矩形上画边缘折射（面板级：整块面板那一圈）。
     *
     * @param strength 折射强度（0 = 不画）。设置里那一档「玻璃边缘光」同时喂这里与假玻璃高光。
     * @param alpha 这一层此刻的出场浓度，用于主题切换与界面淡入。
     * @param sigma <b>这一面</b>的模糊档（{@code GlassLook.blurSigma(Surface)}）：边缘要折的那张
     *        背景必须与铺底用的是同一档，否则会出现「面板糊成一片、边缘却折着清晰画面」。
     * @return 画了；false = 没有可用背景或着色器不可用
     */
    public static boolean drawEdge(double x, double y, double width, double height,
                                   double radius, float strength, float alpha, float sigma) {
        // 面板级：边够长，折射带可以宽到 28，中央留一大片「只糊不折」的平玻璃。
        float thickness = edgeThickness(width, height, 6.0, 28.0, 0.45);
        if (joined(x, y, width, height, radius, thickness, strength, alpha, sigma)) {
            return true;
        }
        return draw(x, y, width, height, radius, strength, alpha, sigma, 6.0, 28.0, 0.45);
    }

    /**
     * 元素级折射（一行曲目、一颗按钮、一片二级菜单）。
     *
     * <p>与面板级只差<em>那一圈有多宽</em>：面板按短边的 45% 取，是因为面板够大，45% 也才几十像素。
     * 同样比例放到 30 高的曲目行上就是 13.5，上下两圈在中间撞上，整行都在折——那不是「一块小玻璃」，
     * 是一整行果冻。元素这一档封顶 9，且按 34% 取，保证再小的按钮也留出一条不折的中央。</p>
     */
    public static boolean drawElementEdge(double x, double y, double width, double height,
                                          double radius, float strength, float alpha, float sigma) {
        float thickness = edgeThickness(width, height, 2.5, 9.0, 0.34);
        if (joined(x, y, width, height, radius, thickness, strength, alpha, sigma)) {
            return true;
        }
        return draw(x, y, width, height, radius, strength, alpha, sigma, 2.5, 9.0, 0.34);
    }

    /**
     * 这一条边有没有<em>入组</em>：正开着融合组（见 {@code GlassLook.beginFusion}）就登记，
     * 由那一组在收尾时一趟画完整张距离场；没开组、或组已满，返回 {@code false}，
     * 调用点紧接着逐矩形各画各的。
     *
     * <p>为什么在入组这一步判而不是在 {@code GlassLook}：厚度那一档（面板 28 / 元素 9）
     * 与逐矩形那条路必须同源，两处各写一份常量的结果是同一块玻璃的两条边一条宽一条窄。</p>
     */
    private static boolean joined(double x, double y, double width, double height, double radius,
                                  float thickness, float strength, float alpha, float sigma) {
        return GlassEdgeBatch.isOpen() && GlassEdgeBatch.add(x, y, width, height, radius, thickness,
                strength, DISPERSION, phase(), FRESNEL, alpha, sigma);
    }

    /** 呼吸相位：两条路共用一份，否则一组里有的边亮有的边暗。 */
    private static float phase() {
        return (float) ((System.nanoTime() / 1_000_000_000.0) * 0.9 % (Math.PI * 2));
    }

    /**
     * 折射那一圈有多宽。<b>纯函数</b>，单独拎出来是因为「上下两圈会不会在中间撞上」这件事
     * 只有在这里算得清，而撞上之后的样子（一整行果冻）在糊着的背景上是看不出来的。
     *
     * <p>不变量见 {@code GlassTintStaticTest.elementRefractionLeavesAFlatMiddle}。</p>
     */
    static float edgeThickness(double width, double height,
                               double minThickness, double maxThickness, double ratio) {
        return (float) Math.max(minThickness, Math.min(maxThickness, Math.min(width, height) * ratio));
    }

    private static boolean draw(double x, double y, double width, double height,
                                double radius, float strength, float alpha, float sigma,
                                double minThickness, double maxThickness, double ratio) {
        RuntimeEffect shader = effect();
        Canvas canvas = SkijaGfx.canvas();
        // 折射要折<em>糊过</em>的那张：面板里外都是磨砂画面，元素边缘却把清晰的世界折进来，
        // 读起来就是「这块贴了张别的图」。档由调用点给（这一面自己那一档），
        // 因为底板与边缘必须糊得一样，否则一半生效的怪相就在同一块玻璃上出现。
        // 预糊拿不到时才退回原图（那时整块玻璃也在退回旧画法）。
        Image backdrop = GlassBackdrop.blurred(sigma);
        if (backdrop == null) {
            backdrop = GlassBackdrop.image();
        }
        if (shader == null || canvas == null || backdrop == null
                || width <= 2.0 || height <= 2.0 || strength <= 0.002f || alpha <= 0.002f) {
            return false;
        }
        GlassBackdrop.Mapping map = GlassBackdrop.mapping(canvas);
        if (map == null) {
            return false;
        }
        Shader source = backdropShader(backdrop, map);
        if (source == null) {
            return false;
        }
        float thickness = edgeThickness(width, height, minThickness, maxThickness, ratio);
        // uniform 字节数必须**正好等于** SkSL 里那三个 float4：多一格 Skia 就直接返回 nullptr，
        // 而 Skija 把它变成一句没头没尾的 "Can't wrap nullptr"（2026-09-29 真机踩到，
        // 原因就是我照抄四角渐变时把「补一格对齐」的写法也抄了过来，那边 6 格是刚好用满）。
        int uniformBytes = shader.getUniformSize();
        ByteBuffer buf = ByteBuffer.allocate(uniformBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat((float) x).putFloat((float) y).putFloat((float) width).putFloat((float) height);
        buf.putFloat((float) Math.max(0.0, radius)).putFloat(thickness)
                .putFloat(REFRACTIVE_INDEX).putFloat(strength);
        buf.putFloat(DISPERSION)
                .putFloat(phase())
                .putFloat(FRESNEL).putFloat(Math.min(1f, alpha));
        // 平移量换算成局部单位：图像素上的 offset 除以该轴倍率。
        buf.putFloat(map.offsetX / map.scaleX).putFloat(map.offsetY / map.scaleY)
                .putFloat(0f).putFloat(0f);
        if (buf.position() != uniformBytes) {
            unavailable = true;
            System.out.println("[MuoniumPlayer/GlassRefraction] uniform 字节数不匹配: SkSL 要 "
                    + uniformBytes + "，Java 侧写了 " + buf.position() + "，折射层停用");
            return false;
        }

        RRect rounded = RRect.makeXYWH((float) x, (float) y, (float) width, (float) height,
                (float) Math.max(0.0, radius));
        try (Data data = Data.makeFromBytes(buf.array());
             Shader refracted = shader.makeShader(data, new Shader[]{source})) {
            Paint paint = SkijaGfx.fillShader(refracted);
            int depth = canvas.save();
            try {
                canvas.clipRRect(rounded, true);
                // 画整个矩形即可：effect 自己在往里超过厚度后返回透明，
                // 外面再套一层 clipRRect 兜住圆角外的像素。
                canvas.drawRect(Rect.makeXYWH((float) x, (float) y,
                        (float) width, (float) height), paint);
            } finally {
                canvas.restoreToCount(depth);
                paint.setShader(null);
            }
            return true;
        } catch (Throwable failure) {
            if (!unavailable) {
                unavailable = true;
                System.out.println("[MuoniumPlayer/GlassRefraction] 折射绘制失败，已停用这一层: "
                        + failure);
            }
            return false;
        }
    }

    /**
     * 丢掉缓存的背景 shader 视图。
     *
     * <p>Shader 绑在 DirectContext 上，上下文重建之后它就指向已经没了的纹理，
     * 所以 {@link SkijaHost#shutdown()} 里必须叫一次。这里只丢视图，不关 effect：
     * 编译好的程序对象与上下文无关，留着可以省掉重载后第一帧的编译。</p>
     */
    public static void release() {
        if (backdropShader != null) {
            try {
                backdropShader.close();
            } catch (Throwable ignored) {
                // 上下文已经没了的时候 close 会抛，丢了引用就行
            }
            backdropShader = null;
            backdropShaderImage = null;
            backdropShaderScaleX = 0f;
            backdropShaderScaleY = 0f;
        }
    }

    /* 三个折射常数给同包的 GlassEdgeBatch 用：两边各抄一份的话，
       「一块玻璃」与「一组玻璃」的边缘就会差一点点，而那种差别正好在最显眼的那圈上。 */
    static float refractiveIndex() {
        return REFRACTIVE_INDEX;
    }

    static float dispersion() {
        return DISPERSION;
    }

    static float fresnel() {
        return FRESNEL;
    }

    /** 编译自检，只给静态测试用。 */
    public static boolean compiles() {
        return effect() != null;
    }

    /** SkSL 源码，只给静态测试钉 uniform 数量用。 */
    public static String source() {
        return SKSL;
    }

    private static Shader backdropShader(Image backdrop, GlassBackdrop.Mapping map) {
        // 认对象而不是认句柄：GlassBackdrop 在尺寸变化之前一直返回同一个 Image，
        // 尺寸一变它就 close 掉旧的并新建一个，届时这里比不中，自然重建。
        if (backdropShader != null && backdropShaderImage == backdrop
                && Math.abs(backdropShaderScaleX - map.scaleX) < 0.0001f
                && Math.abs(backdropShaderScaleY - map.scaleY) < 0.0001f) {
            return backdropShader;
        }
        if (backdropShader != null) {
            backdropShader.close();
            backdropShader = null;
            backdropShaderImage = null;
        }
        try {
            // 局部矩阵是「局部 → 图像素」这个仿射的<b>逆</b>：Skia 的 shader 矩阵描述的是
            // 「shader 自己的空间怎么摆到画布上」，绘制时按逆把画布坐标取回来。所以这里传
            // 1/scale，eval(coord) 采到的就是 scale*coord 那个像素——与 frostedBackdrop 用
            // textureX() 直接乘 scale 是同一件事，只是那边走矩形、这边走矩阵。
            // 平移不在这里：它每帧都变，进缓存就等于每帧重建，所以交给 uSample。
            // x/y 分开，是因为播放器那一层允许两个轴系数不同（WindowAdaptiveScale）。
            backdropShader = backdrop.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                    SamplingMode.LINEAR, Matrix33.makeScale(1f / map.scaleX, 1f / map.scaleY));
            backdropShaderImage = backdrop;
            backdropShaderScaleX = map.scaleX;
            backdropShaderScaleY = map.scaleY;
            return backdropShader;
        } catch (Throwable failure) {
            unavailable = true;
            System.out.println("[MuoniumPlayer/GlassRefraction] 背景取不到 shader，折射停用: " + failure);
            return null;
        }
    }
}
