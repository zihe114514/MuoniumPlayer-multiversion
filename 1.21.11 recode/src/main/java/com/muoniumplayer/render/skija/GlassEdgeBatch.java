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
import io.github.humbleui.types.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 一组玻璃的<b>边缘批次</b>：把「这一组里有哪几块玻璃」攒起来，最后一趟着色器画完整张距离场。
 *
 * <h2>为什么要攒</h2>
 * 一块挨一块的小玻璃（播放器页脚那一排按钮、分段控件的那几格）最像苹果那张图的地方，
 * 不是它们各自糊得多透，而是<b>挨得近的两块会在中间鼓出一道颈</b>——像两滴水银碰到一起。
 * 逐矩形各画各的永远画不出那道颈：每一块都只知道自己。所以这里改成
 * {@code opSmoothUnion}，同一个思路）。
 *
 * <h2>为什么是<em>一组</em>而不是一帧</h2>
 * 没人管；我们的边缘必须压在<em>自己那块底色之上、别人那块底色之下</em>。攒到帧末统一画，
 * 后画的那块弹窗就会被先画的按钮边缘光打穿。所以这里的窗口由调用点自己括：
 * {@code beginFusion → 连续画完这一组 → endFusion}，组与组之间该盖的还是盖。
 * 代价是要记得收尾，所以 {@code SkijaHost.endFrame} 兜一道：帧末还有开着的组就地画掉，
 * 症状从「这一组边缘永远不出现」变成「晚一帧出现」，那才是能忍的。
 *
 * <h2>组内换了矩阵怎么办</h2>
 * 每条<em>登记</em>都做坐标转换：开组时记下画布矩阵，登记时再读一次当前矩阵，把这一块从
 * 「它自己那套局部坐标」烘回「开组那一刻那套」，收尾时再把画布矩阵按回开组那一套画出去。
 * 于是组里各块控件可以带着自己的进场位移与缩放（{@code PanelItemEntrance} 正是每行一次
 * pushMatrix），不需要调用点保证它们共用一个矩阵。
 *
 * <p>两套矩阵之间只要有旋转、错切或透视，这条<em>不入组</em>（{@link #add} 返回
 * {@code false}）：轴对齐的盒子在旋转之下像是一个斜四边形，距离场表达不了，硬烘出来的症状
 * 是「这一块的边折的是画面别处」——糊着的底上判不出来。</p>
 *
 * <p>取样平移则只有<em>一组一份</em>（{@code uSample} 在 {@link #begin} 那一刻烘下来）：
 * 一块玻璃采背景的哪一块，问的是<b>画块时</b>画布的当前矩阵（见 {@code GlassBackdrop#mapping}），
 * 而组内各块矩阵通常只差几个像素的位移与百分之几的缩放，取样点差的那一两列画面糊在一起
 * 分不出来。这一条是刻意的取舍：逐条目各算一份取样就要给每个条目挂一套矩阵，
 * 而 uniform 数组已经占到 800 字节。</p>
 *
 * <h2>装得下多少</h2>
 * {@link #MAX_EDGES} 条。为什么是 24：一屏歌曲行在 GUI 缩放 1~2 的窗口里最多十几行，
 * 加上进出视口那两半行与容器里别的玻璃，16 会在大窗上刚好不够——而<em>不够的症状很难看</em>：
 * 前 16 行连成一块，第 17 行开始各自套一圈，正好像整页玻璃缺了一角。
 * 溢出时 {@link #add} 返回 {@code false}，调用点立刻退回逐矩形那条路画
 * 这一块（不是丢掉不画）：<b>少一道颈</b>可以接受，<b>少一圈边缘光</b>是 bug。
 */
public final class GlassEdgeBatch {

    /** uniform 数组的长度 = 一次能攒的边数；与 {@code uRects[24]} 那一行必须同值。 */
    static final int MAX_EDGES = 24;

    /**
     * SkSL：与 {@code GlassRefraction.SKSL} 同一套折射数学，差别只在场是<b>多个</b>圆角盒的
     * soft-min。两段 {@code edgeOffset} 必须逐字一致，这一点由 {@code GlassEdgeFusionStaticTest} 钉。
     */
    private static final String SKSL = """
            uniform shader uBackdrop;
            uniform float4 uCount;      // x = 条目数, y = 融合半径 k（局部单位）, zw 备用
            uniform float4 uRects[24];  // xy = 左上角, zw = 宽高
            uniform float4 uShapes[24]; // x = 圆角半径, y = 折射厚度, z = 折射率, w = 强度
            uniform float4 uMisc[24];   // x = 色散, y = 相位, z = 菲涅尔, w = 整体 alpha
            uniform float4 uSample;     // xy = 取样平移（画布局部单位），zw 备用

            float sdRoundBox(float2 p, float2 center, float2 halfSize, float r) {
                float2 q = abs(p - center) - (halfSize - float2(r));
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            float kSoftMin(float a, float b, float k) {
                if (k <= 0.0001) {
                    return min(a, b);
                }
                float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
                return mix(b, a, h) - k * h * (1.0 - h);
            }

            // 场：登记过的每一块圆角盒合成一张。k = 0 时它就是普通的 min（各块互不相干）。
            float field(float2 p, int n, float k) {
                float d = 1e6;
                for (int i = 0; i < 24; i++) {
                    if (i >= n) {
                        break;
                    }
                    float4 rc = uRects[i];
                    float m = k + 96.0;
                    if (p.x < rc.x - m || p.y < rc.y - m
                            || p.x > rc.x + rc.z + m || p.y > rc.y + rc.w + m) {
                        continue;   // 每条目一次廉价剪形：这一像素离它远，不参与
                    }
                    float2 center = rc.xy + rc.zw * 0.5;
                    float2 halfSize = max(rc.zw * 0.5, float2(0.5));
                    float r = min(uShapes[i].x, min(halfSize.x, halfSize.y));
                    d = kSoftMin(d, sdRoundBox(p, center, halfSize, r), k);
                }
                return d;
            }

            float2 fieldNormal(float2 p, int n, float k) {
                // 法线取整张场的梯度，不取「最近那一块」的解析法线：融合处要的正是那道颈的弯，
                // 逐块法线在两块之间会跳，跳变在边缘那一圈上就是一道可见的折痕。
                float e = 0.75;
                float dx = field(p + float2(e, 0.0), n, k) - field(p - float2(e, 0.0), n, k);
                float dy = field(p + float2(0.0, e), n, k) - field(p - float2(0.0, e), n, k);
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
                int n = int(uCount.x + 0.5);
                float k = max(uCount.y, 0.0);
                if (n <= 0) {
                    return half4(0.0);
                }
                // 一次循环同时拿到两样东西：整张场的距离（soft-min，融合就发生在这一项上）
                // 与「离这一像素最近的那一块」自己的参数（厚度/折射率/强度/alpha）。
                // 为什么不在循环外按索引取：SkSL 里 uniform 数组的下标必须是常量，
                // 记下标为变量那种写法编译期就报 "index expression must be constant"，
                // 而这是 runtime effect 的硬规矩，不是某一版驱动的脾气。
                float dField = 1e6;
                float nearestDistance = 1e6;
                float4 shape = float4(0.0);
                float4 misc = float4(1.0);
                for (int i = 0; i < 24; i++) {
                    if (i >= n) {
                        break;
                    }
                    float4 rc = uRects[i];
                    float m = k + 96.0;
                    if (coord.x < rc.x - m || coord.y < rc.y - m
                            || coord.x > rc.x + rc.z + m || coord.y > rc.y + rc.w + m) {
                        continue;
                    }
                    float2 center = rc.xy + rc.zw * 0.5;
                    float2 halfSize = max(rc.zw * 0.5, float2(0.5));
                    float r = min(uShapes[i].x, min(halfSize.x, halfSize.y));
                    float d = sdRoundBox(coord, center, halfSize, r);
                    if (d < nearestDistance) {
                        nearestDistance = d;
                        shape = uShapes[i];
                        misc = uMisc[i];
                    }
                    dField = kSoftMin(dField, d, k);
                }
                if (nearestDistance > 1e5) {
                    return half4(0.0);   // 剪形把这像素挡在了所有玻璃之外
                }
                float depth = -dField;
                if (depth <= 0.0 || shape.w <= 0.002) {
                    return half4(0.0);   // 在所有玻璃之外：一个像素都不写
                }
                float thickness = max(shape.y, 1.0);
                float band = 1.0 - smoothstep(0.0, thickness, depth);
                if (band <= 0.002) {
                    return half4(0.0);   // 中央那片「只糊不折」：这里必须真的透明，否则会盖住已画的字
                }
                float2 normal = fieldNormal(coord, n, k);
                float off = edgeOffset(depth, thickness, shape.z);
                float disp = misc.x;
                float2 shifted = coord + uSample.xy;
                float2 base = shifted + normal * off;
                float red = uBackdrop.eval(shifted + normal * (off * (1.0 - disp))).r;
                float green = uBackdrop.eval(base).g;
                float blue = uBackdrop.eval(shifted + normal * (off * (1.0 + disp))).b;
                float breathe = 0.92 + 0.08 * sin(misc.y + coord.x * 0.008);
                float fresnel = pow(band, 2.5) * misc.z * breathe;
                float3 rgb = float3(red, green, blue) + float3(0.86, 0.93, 1.0) * fresnel;
                float a = clamp(misc.w * band, 0.0, 1.0);
                return half4(half3(clamp(rgb, float3(0.0), float3(1.0)) * half(a)), half(a));
            }
            """;

    private static final float[] rects = new float[MAX_EDGES * 4];
    private static final float[] shapes = new float[MAX_EDGES * 4];
    private static final float[] misc = new float[MAX_EDGES * 4];

    private static int count;
    private static boolean open;
    private static float smoothing;
    private static float sampleX;
    private static float sampleY;
    /** 开组那一刻的画布矩阵（局部→设备，行主序 9 位）；null = 这一组不做坐标烘培。 */
    private static float[] groupMatrix;
    /** 这一组里最重的一档模糊（见 {@link #add}）。 */
    private static float maxSigma;
    /** 这一帧登记过、但因为没有背景而白登的条数；只给探针看。 */
    private static int dropped;
    /** 帧末没收尾被丢掉的组数。 */
    private static int abandoned;
    private static final String TAG = "[MuoniumPlayer/GlassEdgeBatch] ";

    private static RuntimeEffect effect;
    private static boolean unavailable;
    private static Shader backdropShader;
    private static Image backdropShaderImage;
    private static float backdropShaderScaleX;
    private static float backdropShaderScaleY;

    private GlassEdgeBatch() {
    }

    /** 现在有没有开着的组。 */
    public static boolean isOpen() {
        return open;
    }

    static int size() {
        return count;
    }

    /**
     * 开一组。已经开着的一组先画掉——嵌套调用是调用点的 bug，但这里的选择必须是
     * 「少一道颈」而不是「整组消失」。
     *
     * @param smoothing 融合半径，画布局部单位：两块玻璃的间隙小于它才会鼓出那道颈。0 = 各画各的。
     * @param offsetX 取样平移（局部单位），在开组这一刻烘下来，见类注释「组内不许换矩阵」。
     */
    public static void begin(float smoothing, float offsetX, float offsetY) {
        begin(smoothing, offsetX, offsetY, null);
    }

    /**
     * 同上，再多给一份「开组这一刻的画布矩阵」。
     *
     * @param matrix 行主序 9 位（{@code Canvas.getLocalToDeviceAsMatrix33().getMat()}）；
     *               组内每条登记都烘回这一套坐标系。传 {@code null} = 不烘，只给测试与
     *               「确定整组不换矩阵」的调用点用
     */
    public static void begin(float smoothing, float offsetX, float offsetY, float[] matrix) {
        if (open) {
            flush();
        }
        GlassEdgeBatch.smoothing = Math.max(0f, smoothing);
        GlassEdgeBatch.sampleX = offsetX;
        GlassEdgeBatch.sampleY = offsetY;
        GlassEdgeBatch.groupMatrix = matrix == null || matrix.length < 9 ? null : matrix.clone();
        GlassEdgeBatch.count = 0;
        GlassEdgeBatch.maxSigma = 0f;
        GlassEdgeBatch.open = true;
    }

    /**
     * 登记一块玻璃的边缘。
     *
     * @return 收下了；{@code false} = 这一条没进组，调用点应当<em>立刻</em>自己逐矩形画这一块。
     *         两种原因：这组已经装满（{@link #MAX_EDGES} 条），或者这块与开组那一刻之间
     *         隔着旋转/退化矩阵，烘不进那一套坐标系
     */
    static boolean add(double x, double y, double width, double height, double radius,
                       double thickness, float strength, float dispersion, float phase,
                       float fresnel, float alpha, float sigma) {
        if (!open) {
            return false;
        }
        if (count >= MAX_EDGES) {
            dropped++;   // 溢出：调用点会立刻自己逐矩形画这一块，这里只记一笔给探针
            return false;
        }
        // 把这一条从它自己的画布坐标系烘回开组那一刻那一套（见类注释「组内换了矩阵怎么办」）。
        // 烘不了就不入组：调用点紧接着逐矩形画这一块，画的那一屏跟原来一模一样。
        float bx = (float) x, by = (float) y, bw = (float) width, bh = (float) height;
        float br = (float) Math.max(0.0, radius);
        float bt = (float) Math.max(1.0, thickness);
        if (groupMatrix != null) {
            float[] baked = bakeIntoGroup(groupMatrix, currentMatrix(), x, y, width, height, br, bt);
            if (baked == null) {
                dropped++;   // 与溢出同一笔账：都是「这一条退回逐矩形」，探针只需知道退回了几条
                return false;
            }
            bx = baked[0];
            by = baked[1];
            bw = baked[2];
            bh = baked[3];
            br = baked[4];
            bt = baked[5];
        }
        int at = count * 4;
        rects[at] = bx;
        rects[at + 1] = by;
        rects[at + 2] = bw;
        rects[at + 3] = bh;
        shapes[at] = Math.max(0f, br);
        shapes[at + 1] = Math.max(1f, bt);
        shapes[at + 2] = GlassRefraction.refractiveIndex();
        shapes[at + 3] = strength;
        misc[at] = dispersion;
        misc[at + 1] = phase;
        misc[at + 2] = fresnel;
        misc[at + 3] = alpha;
        // 一组里出现不同模糊档（面板与元素各一档倍率）时，取最重的那一档当取样档：
        // 边那一圈薄，档偏一点读不出来，而两组各画一次就散了那道颈。
        if (sigma > maxSigma) {
            maxSigma = sigma;
        }
        count++;
        return true;
    }

    /**
     * 把一块圆角盒从 {@code current} 那套局部坐标烘进 {@code group} 那套。
     *
     * <p><b>纯函数，要在 JVM 里钉</b>：真机上矩阵只能从活画布读，而这里错了的代价是
     * 「相邻两块的边各画在各的地方，融合处出现一道错位亮边」。行主序 {@code [sx, kx, tx,
     * ky, sy, ty, p0, p1, p2]}（Skia 的 Matrix33 就是这个布局）。</p>
     *
     * <p>圆角半径与折射厚度乘的是两轴比例的<em>均值</em>：本模块允许 x/y 系数不等
     * （{@code WindowAdaptiveScale}），严格讲非等比缩放会把圆角变成椭圆，但列表行那种
     * 每行一次 {@code scale(eased, eased)} 是等比的，面板与控件之间也不会差出 10%；
     * 取均值换来的是「一套 4 个数的数组表达一条边」，不必给每块再挂一组矩阵。</p>
     *
     * @return {@code {x, y, w, h, radius, thickness}} 全部落在 group 坐标系里；
     *         {@code null} = 任一侧带旋转/错切/透视/退化，这条边不能入组
     */
    static float[] bakeIntoGroup(float[] group, float[] current,
                                 double x, double y, double width, double height,
                                 double radius, double thickness) {
        if (!isAxisAligned(group) || !isAxisAligned(current)) {
            return null;
        }
        float rx = current[0] / group[0];
        float ry = current[4] / group[4];
        float rs = (rx + ry) * .5f;
        return new float[]{
                (float) ((current[0] * x + current[2] - group[2]) / group[0]),
                (float) ((current[4] * y + current[5] - group[5]) / group[4]),
                (float) (width * rx),
                (float) (height * ry),
                (float) (radius * rs),
                (float) (thickness * rs)};
    }

    /** 轴对齐且不退化才谈得上「把盒子烘成盒子」。 */
    private static boolean isAxisAligned(float[] m) {
        return m != null && m.length >= 9
                && Math.abs(m[0]) > 1e-4f && Math.abs(m[4]) > 1e-4f
                && Math.abs(m[1]) < 1e-3f && Math.abs(m[3]) < 1e-3f
                && Math.abs(m[6]) < 1e-3f && Math.abs(m[7]) < 1e-3f
                && Math.abs(m[8] - 1f) < 1e-3f;
    }

    /** 当前画布矩阵；没有画布（单元测试、上下文已丢）或读不出都返回 {@code null}。 */
    private static float[] currentMatrix() {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return null;
        }
        try {
            return canvas.getLocalToDeviceAsMatrix33().getMat();
        } catch (Throwable failure) {
            return null;
        }
    }

    /** 收尾并画。没开组、或一组里空着时什么也不做。 */
    public static int flush() {
        if (!open) {
            return 0;
        }
        int drawn = count;
        open = false;
        int n = count;
        count = 0;
        if (n <= 0) {
            return 0;
        }
        Canvas canvas = SkijaGfx.canvas();
        Image backdrop = GlassBackdrop.blurred(maxSigma > 0f ? maxSigma : GlassBackdrop.blurSigma());
        if (backdrop == null) {
            backdrop = GlassBackdrop.image();
        }
        if (canvas == null || backdrop == null) {
            return 0;
        }
        // 条目坐标活在开组那一刻那套坐标系里（add 时逐条烘过），取样档就得按<em>那套</em>矩阵算，
        // 而不是按收尾这一刻画布正好站在哪儿。烘不进组的矩阵（旋转）早就在 add 里退回逐矩形了，
        // 所以 groupMatrix 非空时它必然是轴对齐的，这里拿不到只是没有背景。
        float[] atFlush = currentMatrix();
        GlassBackdrop.Mapping map = groupMatrix == null ? GlassBackdrop.mapping(canvas)
                : GlassBackdrop.mapping(groupMatrix);
        if (map == null) {
            map = GlassBackdrop.mapping(canvas);
        }
        if (map == null) {
            return 0;
        }
        RuntimeEffect shader = effect();
        Shader source = backdropShader(backdrop, map);
        if (shader == null || source == null) {
            return 0;
        }
        // 一趟画完，所以矩形只覆盖这批的外接框（外加一圈融合半径 + 最厚的边）。
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE,
                bottom = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int at = i * 4;
            left = Math.min(left, rects[at]);
            top = Math.min(top, rects[at + 1]);
            right = Math.max(right, rects[at] + rects[at + 2]);
            bottom = Math.max(bottom, rects[at + 1] + rects[at + 3]);
        }
        float margin = smoothing + 32f;
        left -= margin;
        top -= margin;
        right += margin;
        bottom += margin;
        try (Data uniforms = uniforms(n)) {
            if (uniforms == null) {
                return 0;
            }
            try (Shader fused = shader.makeShader(uniforms, new Shader[]{source})) {
                if (fused == null) {
                    return 0;
                }
                try (Paint paint = new Paint().setShader(fused)) {
                    // 组里最后那块控件可能把自己的 pushMatrix 还回去了，也可能还没（同一批兄弟
                    // 共用容器那一层）。这里按回开组那一刻的矩阵画，条目坐标与坐标系才是一对。
                    // 矩阵本来就一样时不做 save/setMatrix：一次 save 要还配平记账，能不碰就不碰。
                    boolean pin = groupMatrix != null && !matches(groupMatrix, atFlush);
                    int depth = pin ? canvas.save() : -1;
                    try {
                        if (pin) {
                            canvas.setMatrix(new Matrix33(groupMatrix[0], groupMatrix[1],
                                    groupMatrix[2], groupMatrix[3], groupMatrix[4], groupMatrix[5],
                                    groupMatrix[6], groupMatrix[7], groupMatrix[8]));
                        }
                        canvas.drawRect(Rect.makeLTRB(left, top, right, bottom), paint);
                    } finally {
                        if (pin) {
                            canvas.restoreToCount(depth);
                        }
                    }
                }
            }
        }
        return drawn;
    }

    /** 两份矩阵是否等价到不必再按回去（1/1000 像素以内）。 */
    private static boolean matches(float[] a, float[] b) {
        if (a == null || b == null || a.length < 9 || b.length < 9) {
            return false;
        }
        for (int i = 0; i < 9; i++) {
            if (Math.abs(a[i] - b[i]) > 0.001f) {
                return false;
            }
        }
        return true;
    }

    /**
     * 帧末还开着组 = 调用点漏了 {@code endFusion}（异常路径、或界面被半路关掉）。
     *
     * <p>这里选择<b>丢掉</b>而不是「顺手画掉」：坐标系能靠 {@link #groupMatrix} 按回去，
     * <em>裁剪</em>按不回去——那一组本来要画在它那层滚动视口里，帧末视口的 stencil 早就
     * 收掉了，照原样画会让一整排边缘盖到列表外的面板上。丢掉的症状是「这一帧这组没有
     * 边缘光」，一帧之后调用点自己就纠正了；画错的症状是永久性的越界亮边。</p>
     */
    static void abandonIfOpen() {
        if (!open) {
            return;
        }
        abandoned++;
        if (abandoned <= 3 || abandoned % 120 == 0) {
            System.out.println(TAG + "第 " + abandoned + " 次遇到没收尾的融合组（漏了 endFusion？），"
                    + "这一组的 " + count + " 条边缘已丢弃");
        }
        clear();
    }

    /** 丢掉的组数，只给探针看。 */
    static int abandonedCount() {
        return abandoned;
    }

    /** 组内还剩没画的东西时丢掉（界面被关掉、采集停用）。不画，也不报错。 */
    public static void clear() {
        open = false;
        count = 0;
        groupMatrix = null;
    }

    private static RuntimeEffect effect() {
        if (effect == null && !unavailable) {
            try {
                effect = RuntimeEffect.makeForShader(SKSL);
            } catch (Throwable failure) {
                unavailable = true;
                System.out.println("[MuoniumPlayer/GlassEdgeBatch] 融合边缘着色器编译失败，"
                        + "玻璃退回逐矩形各画各的: " + failure);
            }
        }
        return effect;
    }

    /**
     * 打包 uniform：一个 float4 一组，顺序必须与 SkSL 里那五行<em>逐字同序</em>。
     * 写错顺序不会报错，只会「边缘折的是画面别处」——那种 bug 在糊着的底上读不出来。
     */
    private static Data uniforms(int n) {
        RuntimeEffect shader = effect();
        if (shader == null) {
            return null;
        }
        int bytes = shader.getUniformSize();
        ByteBuffer buf = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat(n).putFloat(smoothing).putFloat(0f).putFloat(0f);
        put(buf, rects);
        put(buf, shapes);
        put(buf, misc);
        buf.putFloat(sampleX).putFloat(sampleY).putFloat(0f).putFloat(0f);
        if (buf.position() != bytes) {
            unavailable = true;
            System.out.println("[MuoniumPlayer/GlassEdgeBatch] uniform 字节数不匹配: SkSL 要 "
                    + bytes + "，Java 侧写了 " + buf.position() + "，融合边缘停用");
            return null;
        }
        return Data.makeFromBytes(buf.array());
    }

    private static void put(ByteBuffer buf, float[] values) {
        for (float value : values) {
            buf.putFloat(value);
        }
    }

    private static Shader backdropShader(Image backdrop, GlassBackdrop.Mapping map) {
        // 与 GlassRefraction 同一套「认对象 + 认缩放」的缓存；融合这一路整组共用一份平移，
        // 所以矩阵里只留缩放，平移进 uSample。
        if (backdropShader != null && backdropShaderImage == backdrop
                && Math.abs(backdropShaderScaleX - map.scaleX) < 0.0001f
                && Math.abs(backdropShaderScaleY - map.scaleY) < 0.0001f) {
            return backdropShader;
        }
        if (backdropShader != null) {
            backdropShader.close();
            backdropShader = null;
            backdropShaderImage = null;
            backdropShaderScaleX = 0f;
            backdropShaderScaleY = 0f;
        }
        try {
            backdropShader = backdrop.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
                    SamplingMode.LINEAR, Matrix33.makeScale(1f / map.scaleX, 1f / map.scaleY));
            backdropShaderImage = backdrop;
            backdropShaderScaleX = map.scaleX;
            backdropShaderScaleY = map.scaleY;
            return backdropShader;
        } catch (Throwable failure) {
            unavailable = true;
            System.out.println("[MuoniumPlayer/GlassEdgeBatch] 背景包不成 shader，融合边缘停用: "
                    + failure);
            return null;
        }
    }

    /** 给探针：现在这帧装了几条、溢出丢了几条。 */
    static int droppedCount() {
        return dropped;
    }

    /** SkSL 源码，只给静态测试用（钉 uniform 行数与两段折射数学不许分叉）。 */
    static String source() {
        return SKSL;
    }

    /** 编译自检。 */
    static boolean compiles() {
        return effect() != null;
    }
}
