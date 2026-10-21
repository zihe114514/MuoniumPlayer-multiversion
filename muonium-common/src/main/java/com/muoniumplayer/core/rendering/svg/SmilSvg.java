package com.muoniumplayer.core.rendering.svg;

import com.muoniumplayer.core.rendering.svg.SmilXml.Element;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.GradientStyle;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.PathEffect;
import io.github.humbleui.skija.PathFillMode;
import io.github.humbleui.skija.PathMeasure;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一份<b>带动画</b>的 SVG（SMIL）在 Skia 上的实时渲染器。
 *
 * <p>素材是 Jitter 那类导出器写的 {@code <animate>} / {@code <animateTransform>} /
 * {@code <animateMotion>} 文件。Skia 自带的 SVG 模块（{@code SVGDOM}）<b>只出静帧</b>，
 * 而 Skija 那层连「改一个属性」的口子都没开（{@code SVGNode} 只有 {@code getTag()}、
 * {@code SVGContainer} 连子节点都不给遍历），所以每帧改 DOM 重画这条路在 Skija 上走不通；
 * 剩下的选择要么把帧烘成贴图序列、要么自己解释 SMIL。这里选后者。</p>
 *
 * <h2>为什么不烘帧</h2>
 *
 * <p>烘帧要把「解析后的 SVG 文本 → {@code SVGDOM} → 光栅」重复 N 遍。二维码那一枚按 340
 * 逻辑像素显示，GUI Scale 3 下就是 1020×1020，40 帧约 160 MB 显存；而且分辨率一换就得重烘。
 * 实时解释只有一份几何，代价全在每帧的矩阵和不透明度上 —— 五枚素材在浏览器里真正要画的
 * 叶子节点只有 4～48 个（{@code <defs>} 里那一大堆是导出器留下的模板副本，规范里
 * {@code defs} 的内容不画，除非被 {@code url(#…)} 引用），一帧几百次矩阵复合，量级和
 * 现在那 12 段转子差不多。</p>
 *
 * <h2>支持的口子（就是这五枚素材用到的全部）</h2>
 *
 * <ul>
 *   <li>几何：{@code path/@d}、{@code rect}（含 {@code rx/ry}）、{@code ellipse}、{@code circle}；</li>
 *   <li>绘制：{@code fill}（颜色 / {@code none} / {@code url(#线性渐变)}）、{@code stroke}、
 *       {@code stroke-width}、{@code stroke-linecap}、{@code stroke-linejoin}、
 *       {@code stroke-dasharray}、{@code stroke-dashoffset}、{@code fill-rule}，
 *       以及从父节点继承的这些属性；</li>
 *   <li>变换：{@code translate} / {@code scale} / {@code rotate} / {@code matrix} 的函数列表；</li>
 *   <li>裁剪：{@code clip-path="url(#id)"}，被引用的 {@code clipPath} 画在 {@code defs} 里；</li>
 *   <li>动画：{@code attributeName} 为 {@code transform}（{@code type=translate|scale|rotate|matrix}）、
 *       {@code opacity}、{@code display}、{@code stroke-dasharray}、{@code stroke-dashoffset}，
 *       以及沿 {@code path} 运动的 {@code animateMotion}（配 {@code keyPoints}）；
 *       {@code calcMode} 认 {@code spline}（配 {@code keySplines}）、{@code linear}、
 *       {@code discrete}、{@code paced}。</li>
 * </ul>
 *
 * <p>没实现的一律<b>忽略而不是猜</b>：滤镜、遮罩、文字、{@code use}、径向渐变都不在这批素材里，
 * 真遇上了就是少画一点，不该由一个图标拖垮界面。</p>
 *
 * <h2>组不透明度：乘进子树，不开图层</h2>
 *
 * <p>SVG 的 {@code opacity} 语义要先把子树离屏合成、再整体降透明；这里把 alpha 直接乘到每个叶子上。
 * 差别只在「同一组里两块墨互相叠」的那种地方会偏亮。之所以这么选：Skia 的
 * {@code saveLayer} 在 GL 目标上要按图层尺寸分配离屏缓冲，而这些图标每帧都变，
 * 拿一个图标的半透明层级去换每帧一块显存不划算。素材本身是扁平描线风格的图标，
 * 互叠的部分极少，肉眼分不出来。</p>
 */
public final class SmilSvg {

    /** 资源路径 → 解析好的那份。 */
    private static final Map<String, SmilSvg> CACHE = new ConcurrentHashMap<>();

    /**
     * 读不到或解析失败过的路径。
     *
     * <p>单独一个集合而不是往 {@link #CACHE} 里塞 null：{@code ConcurrentHashMap} 不收 null 值，
     * 塞进去会当场抛 {@link NullPointerException} —— 那正好是「一份坏素材把界面弄崩」，
     * 而这里要的是「画不出来，退回静态图标，并且别每帧再读一遍磁盘」。</p>
     */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    /** 测量墨迹盒时沿时间轴采的点数。8 个够把「从 0 缩放出来」的那些元素量全。 */
    private static final int MEASURE_SAMPLES = 8;

    /**
     * 所有实例共享的一支 Paint。渲染只在主线程上发生（Skija canvas 本身就是主线程物件），
     * 每帧 new Paint() + close() 是纯 JNI 开销；paintLeaf 画完即清 effect/shader，
     * render 入口再兜一次底，字段间不会串。
     */
    private static final Paint SHARED_PAINT = new Paint();

    private final List<Node> roots = new ArrayList<>();
    private final Map<String, Path> clips = new HashMap<>();
    private final Map<String, Gradient> gradients = new HashMap<>();

    private final double viewBoxX, viewBoxY, viewBoxW, viewBoxH;
    /** 量出来的墨迹盒（文档坐标系）。用它才能把图标撑满目标框：素材里 padding 多得很。 */
    private final double contentX, contentY, contentW, contentH;
    private final double durationSeconds;

    private SmilSvg(Element svg) {
        double[] vb = parseFloats(svg.attr("viewBox", "0 0 100 100"));
        if (vb.length >= 4 && vb[2] > 0 && vb[3] > 0) {
            viewBoxX = vb[0];
            viewBoxY = vb[1];
            viewBoxW = vb[2];
            viewBoxH = vb[3];
        } else {
            viewBoxX = 0;
            viewBoxY = 0;
            viewBoxW = 100;
            viewBoxH = 100;
        }

        // 根元素自己的 presentation 属性也参与继承：这五份素材都写的是 <svg fill="none">，
        // 少了这一步，只写 stroke 不写 fill 的笔画会被按 SVG 默认值填成实心黑块。
        State initial = new State().inherit(svg);
        double maxEnd = 0.0;
        for (Element child : svg.children) {
            String tag = child.localTag();
            if ("defs".equals(tag) || "title".equals(tag) || "desc".equals(tag) || "style".equals(tag)) {
                // defs 里只有被 url(#) 引用的东西才画：clipPath 与渐变收进资源表，
                // 其余那一大堆模板副本照规范就是不显示的 —— 跟着画会和浏览器对不上。
                harvest(child);
                continue;
            }
            Node node = build(child, initial);
            if (node != null) {
                roots.add(node);
            }
        }
        for (Node root : roots) {
            maxEnd = Math.max(maxEnd, root.lastEnd);
        }
        durationSeconds = maxEnd > 0 ? maxEnd : 1.0;

        double[] box = measureContent();
        contentX = box[0];
        contentY = box[1];
        contentW = box[2];
        contentH = box[3];
    }

    // ==================================================================
    // 加载
    // ==================================================================

    /**
     * 读一份 classpath 上的 SVG，比如 {@code /muonium/svg/like.svg}。
     *
     * @return 解析成功的那份；资源缺失、XML 坏了、没有可画内容都返回 {@code null}，
     * 由调用方退回静态图标。同名资源整个过程只读一次。
     */
    public static SmilSvg load(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            return null;
        }
        String key = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        if (FAILED.contains(key)) {
            return null;   // 失败过就别再读一遍磁盘，但也别把 null 塞进 CACHE（见 FAILED 的注释）。
        }
        SmilSvg cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        SmilSvg parsed = null;
        try {
            String text = readResource(key);
            if (text != null && !text.isEmpty()) {
                Element root = SmilXml.parse(text);
                if ("svg".equals(root.localTag())) {
                    SmilSvg svg = new SmilSvg(root);
                    parsed = svg.roots.isEmpty() ? null : svg;
                }
            }
        } catch (Throwable ignored) {
            parsed = null;   // 一份坏素材只意味着「这枚画不出来」，不能让界面开不出来。
        }
        if (parsed == null) {
            FAILED.add(key);
        } else {
            CACHE.put(key, parsed);
        }
        return parsed;
    }

    /**
     * 只给测试：从一段 SVG 文本造一份，走的还是 {@link #load} 里那条解析路径。
     *
     * <p>为的是钉住「规范写得清、但这批素材一条都没用到」的规则——{@code fill="freeze"} 与
     * {@code fill="remove"} 的分别、没写 {@code repeatCount} 该算演一遍。为这两条造两份真素材
     * 不划算：素材要进 jar，还要跟着五棵树同步。</p>
     */
    static SmilSvg parseForTest(String text) {
        try {
            Element root = SmilXml.parse(text);
            if (!"svg".equals(root.localTag())) {
                return null;
            }
            SmilSvg svg = new SmilSvg(root);
            return svg.roots.isEmpty() ? null : svg;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readResource(String path) {
        try (InputStream input = SmilSvg.class.getResourceAsStream(path)) {
            if (input == null) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 一整轮动画的时长（秒）。所有通道同时结束，取最晚的那个。 */
    public double duration() {
        return durationSeconds;
    }

    /**
     * 量出来的墨迹盒 {@code [x, y, 宽, 高]}（文档坐标系）。只给测试与离线出图用：
     * 素材留白多少、有没有被画外的东西带偏，全在这四个数上，光看像素很难反推。
     */
    double[] contentBoxForTest() {
        return new double[]{contentX, contentY, contentW, contentH};
    }

    /** 画布 {@code viewBox} 的 {@code [x, y, 宽, 高]}，用来核对上面那四个数有没有超出画布。 */
    double[] viewBoxForTest() {
        return new double[]{viewBoxX, viewBoxY, viewBoxW, viewBoxH};
    }

    /**
     * 这份素材<b>结构上</b>要画的叶子数：不看 {@code display} 与 {@code opacity} 门控，
     * 只数「在渲染树里、带几何」的节点。
     *
     * <p>和浏览器里 {@code svg.querySelectorAll('path,rect,…')} 再剔掉 {@code defs} 后代
     * 数是同一个口径 —— 拿它当钉子，钉的是「我们照规范只画 defs 之外的东西」这件事。
     * 按帧过滤的那个口径见 {@link #leafBoxesForTest(double)}，两者不一样：星光那枚
     * 结构上 9 笔，但同时亮着的最多 5 笔，剩下的靠动画轮班。</p>
     */
    int drawnLeafCountForTest() {
        int total = 0;
        for (Node root : roots) {
            total += countLeaves(root);
        }
        return total;
    }

    private static int countLeaves(Node node) {
        int total = node.geometry != null ? 1 : 0;
        for (Node child : node.children) {
            total += countLeaves(child);
        }
        return total;
    }

    /**
     * 某一帧里<b>真的会被画到</b>的那些叶子的文档坐标包围盒，每条形如
     * {@code [x, y, 宽, 高, alpha]}；不可见的（{@code display:none}、全透明）不在表里。
     *
     * <p>只给测试与离线出图用。要判断「动画有没有在动、有没有画到画外」，看这四个数
     * 比看像素可靠得多 —— 而且能直接和浏览器里 {@code getBoundingClientRect()} 的结果对。</p>
     */
    List<double[]> leafBoxesForTest(double time) {
        List<double[]> boxes = new ArrayList<>();
        for (Node root : roots) {
            collectLeaves(root, Math.max(0.0, time), Matrix33.IDENTITY, 1f, boxes);
        }
        return boxes;
    }

    private void collectLeaves(Node node, double time, Matrix33 parent, float inherited,
                               List<double[]> boxes) {
        Matrix33 matrix = node.matrix;
        double opacity = node.opacity;
        boolean inline = node.inline;
        for (Channel channel : node.channels) {
            if (!channel.evaluate(time)) {
                continue;
            }
            switch (channel.target) {
                case Channel.TRANSFORM:
                    matrix = channel.matrix();
                    break;
                case Channel.MOTION:
                    matrix = matrix.makeConcat(channel.motion());
                    break;
                case Channel.OPACITY:
                    opacity = channel.out[0];
                    break;
                case Channel.DISPLAY:
                    inline = !"none".equals(channel.outString);
                    break;
                default:
                    break;
            }
        }
        if (!inline || opacity <= 0.004) {
            return;
        }
        float alpha = (float) (inherited * clamp01(opacity));
        Matrix33 absolute = parent.makeConcat(matrix);
        if (node.geometry != null) {
            Rect bounds = node.geometry.getBounds();
            float[] m = absolute.getMat();
            double left = bounds.getLeft();
            double top = bounds.getTop();
            double right = left + bounds.getWidth();
            double bottom = top + bounds.getHeight();
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
                double x = (corner & 1) == 0 ? left : right;
                double y = (corner & 2) == 0 ? top : bottom;
                minX = Math.min(minX, m[0] * x + m[1] * y + m[2]);
                minY = Math.min(minY, m[3] * x + m[4] * y + m[5]);
                maxX = Math.max(maxX, m[0] * x + m[1] * y + m[2]);
                maxY = Math.max(maxY, m[3] * x + m[4] * y + m[5]);
            }
            boxes.add(new double[]{minX, minY, maxX - minX, maxY - minY, alpha});
        }
        for (Node child : node.children) {
            collectLeaves(child, time, absolute, alpha, boxes);
        }
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    /**
     * 把这一帧画进给定矩形。
     *
     * <p>墨迹盒等比 contain + 居中，绝不拉伸 —— 和 {@code BrandVectorIcons} 同一套规矩。</p>
     *
     * @param timeSeconds 时间轴位置；调用方 own 这个时钟（循环与播完一次都在外面算）。
     *                    负数按 0 处理，超出时长就停住不画最后一帧以外的东西。
     * @param tintColor   非 0 时把<b>所有</b>填充与描边染成这一个 ARGB 色。
     *                    {@code search.svg} 的笔画是写死的黑色，要跟主题色走就得靠它。
     * @return 真的画了才 true。不在 Skija 帧内、尺寸非正、素材没解析出来都 false。
     */
    public boolean drawIn(double left, double top, double width, double height,
                          double timeSeconds, float alpha, int tintColor) {
        if (!(width > 0) || !(height > 0) || !(alpha > 0.004f) || !SkijaGfx.active()) {
            return false;
        }
        return render(SkijaGfx.canvas(), timeSeconds, left, top, width, height, alpha, tintColor);
    }

    /** 以 {@code (centerX, centerY)} 为中心画进边长 {@code box} 的方格。 */
    public boolean drawCentered(double centerX, double centerY, double box,
                                double timeSeconds, float alpha, int tintColor) {
        return drawIn(centerX - box * .5, centerY - box * .5, box, box, timeSeconds, alpha, tintColor);
    }

    /**
     * 真正干活的一帧。 canvas 由调用方给，测试里传一张离屏表面就能在<b>游戏外</b>核对画面。
     *
     * <p>这里的 save/restore 全程自己配平，不借 {@code SkijaGfx.pushMatrix()}：那套要记深度，
     * 而本类一次帧内会有成百上千次进出，用 {@code restoreToCount} 一把兜底更稳 ——
     * 中途抛异常也不会把整帧的坐标系弹坏。</p>
     */
    boolean render(Canvas canvas, double timeSeconds, double left, double top,
                   double width, double height, float alpha, int tintColor) {
        if (canvas == null) {
            return false;
        }
        double scale = Math.min(width / contentW, height / contentH);
        if (!(scale > 0)) {
            return false;
        }
        double drawnW = contentW * scale;
        double drawnH = contentH * scale;
        int base = canvas.getSaveCount();
        // 渲染只在主线程发生（见调用方），复用一支共享 Paint 省掉每帧的 JNI 分配 +
        // close；paintLeaf 每次画完都会把 effect/shader 清干净，无状态外泄。
        Paint paint = SHARED_PAINT;
        try {
            paint.setAntiAlias(true);
            paint.setPathEffect(null);
            paint.setShader(null);
            // save() 必须在改矩阵之前：concat / translate / scale 都只动当前矩阵，
            // 不会自己加一层保存点 —— 少了这一步，末尾那句 restoreToCount 就是空转，
            // 本类的平移缩放会留在宿主画布上，把这一帧后面所有界面都带偏。
            canvas.save();
            canvas.translate((float) (left + (width - drawnW) * .5), (float) (top + (height - drawnH) * .5));
            canvas.scale((float) scale, (float) scale);
            canvas.translate((float) -contentX, (float) -contentY);
            double time = Math.max(0.0, timeSeconds);
            // 染色必须先把 alpha 抬成不透明：主题色常常只带 RGB（网易云主题的 ACCENT 就是
            // 0x00RRGGBB），而乘 alpha 走的是「乘掉颜色自带的 alpha」那一条 —— 不这么补的话
            // 染出来的每一笔 alpha 都是 0，整枚图标画了等于没画。
            int tint = tintColor == 0 ? 0 : tintColor | 0xFF000000;
            for (Node node : roots) {
                draw(canvas, node, time, (float) Math.min(1.0, alpha), paint, tint);
            }
        } catch (Throwable ignored) {
            return false;
        } finally {
            canvas.restoreToCount(base);
        }
        return true;
    }

    private void draw(Canvas canvas, Node node, double time, float inherited,
                      Paint paint, int tint) {
        Matrix33 matrix = node.matrix;
        double opacity = node.opacity;
        boolean inline = node.inline;
        String dashArray = node.dashArray;
        String dashOffset = node.dashOffset;
        double strokeWidth = node.strokeWidth;
        int fill = node.fill;
        int stroke = node.stroke;
        Gradient gradient = node.fillGradient;
        Gradient strokeGradient = node.strokeGradient;
        for (Channel channel : node.channels) {
            if (!channel.evaluate(time)) {
                continue;
            }
            switch (channel.target) {
                case Channel.TRANSFORM:
                    matrix = channel.matrix();
                    break;
                case Channel.MOTION:
                    // 运动变换挂在元素自身变换<b>之后</b>（更贴近孩子），与 SVG 的补充变换一致。
                    matrix = matrix.makeConcat(channel.motion());
                    break;
                case Channel.OPACITY:
                    opacity = channel.out[0];
                    break;
                case Channel.DISPLAY:
                    inline = !"none".equals(channel.outString);
                    break;
                case Channel.DASH_ARRAY:
                    dashArray = channel.outString;
                    break;
                case Channel.DASH_OFFSET:
                    dashOffset = channel.outString;
                    break;
                case Channel.STROKE_WIDTH:
                    strokeWidth = channel.out[0];
                    break;
                case Channel.FILL:
                    // 动画把颜色写死了，它原先引用的渐变同时作废。
                    fill = channel.color;
                    gradient = null;
                    break;
                case Channel.STROKE:
                    stroke = channel.color;
                    strokeGradient = null;   // 与 FILL 同理：动画写死颜色之后引用的渐变作废
                    break;
                default:
                    break;
            }
        }
        float alpha = (float) (inherited * clamp01(opacity));
        if (!inline || alpha <= 0.004f) {
            return;   // 全透明就不必再往下走，但父节点的矩阵也不该带给孩子 —— 这里直接砍掉整枝。
        }

        int save = canvas.getSaveCount();
        canvas.save();
        canvas.concat(matrix);
        Path clip = node.clip;
        if (clip != null) {
            canvas.clipPath(clip, ClipMode.INTERSECT, true);
        }
        if (node.geometry != null) {
            paintLeaf(canvas, node, paint, alpha, strokeWidth, dashArray, dashOffset,
                    fill, stroke, tint, gradient, strokeGradient);
        }
        for (Node child : node.children) {
            draw(canvas, child, time, alpha, paint, tint);
        }
        canvas.restoreToCount(save);
    }

    private void paintLeaf(Canvas canvas, Node node, Paint paint, float alpha, double strokeWidth,
                           String dashArray, String dashOffset, int fill, int stroke, int tint,
                           Gradient gradient, Gradient strokeGradient) {
        boolean tinted = tint != 0;
        Path geometry = node.geometry;
        if (fill != 0 || gradient != null) {
            // 染色模式（tint != 0）下渐变也一并丢掉：要的是「整枚图标跟主题色走」。
            Shader shader = tinted ? null : shaderOf(gradient, geometry, alpha);
            paint.setMode(PaintMode.FILL);
            paint.setShader(shader);
            paint.setColor(shader != null ? 0xFFFFFFFF
                    : withAlpha(tinted ? tint : fill, alpha));
            canvas.drawPath(geometry, paint);
            if (shader != null) {
                shader.close();
            }
        }
        if ((stroke != 0 || strokeGradient != null) && strokeWidth > 0) {
            // 描边也要能挂渐变。原来这里写死 setShader(null)，于是 stroke="url(#…)"
            // 的素材整条笔画都不出现——彩虹 hello 正是这一类。
            Shader shader = tinted ? null : shaderOf(strokeGradient, geometry, alpha);
            paint.setMode(PaintMode.STROKE);
            paint.setShader(shader);
            paint.setColor(shader != null ? 0xFFFFFFFF
                    : withAlpha(tinted ? tint : stroke, alpha));
            paint.setStrokeWidth((float) strokeWidth);
            paint.setStrokeCap(node.lineCap);
            paint.setStrokeJoin(node.lineJoin);
            if (node.dashIntervals != null && dashArray == node.dashArray && dashOffset == node.dashOffset) {
                // 无 dash 动画通道覆盖本帧（局部值仍是加载期的同一引用）：走预解析结果，不碰字符串。
                applyStaticDash(paint, node.dashIntervals, node.dashPhase, dashUnit(node));
            } else {
                applyDash(paint, dashArray, dashOffset, dashUnit(node));
            }
            canvas.drawPath(geometry, paint);
            paint.setPathEffect(null);
            paint.setShader(null);
        }
    }

    /**
     * dash 的单位换算：{@code pathLength} 声明了多少，就把「实测全长 / 声明全长」乘进
     * dasharray 与 dashoffset。没写 pathLength 的素材恒为 1，行为与改动前逐字节一致。
     */
    private static float dashUnit(Node node) {
        if (node.dashUnit != null) {
            return node.dashUnit;
        }
        float unit = 1f;
        if (node.pathLength > 0 && node.geometry != null) {
            float length = totalLength(node.geometry);
            if (length > 0) {
                unit = length / (float) node.pathLength;
            }
        }
        node.dashUnit = unit;
        return unit;
    }

    /** 路径全长（所有轮廓相加）。{@link PathMeasure#getLength()} 只给第一段，多段路径必须自己走一遍。 */
    private static float totalLength(Path path) {
        try (PathMeasure measure = new PathMeasure(path, false)) {
            float total = 0;
            do {
                total += measure.getLength();
            } while (measure.nextContour());
            return total;
        } catch (Throwable unavailable) {
            return 0f;
        }
    }

    /** 渐变只在<b>填充</b>上用；坐标写在该元素自己的用户空间里，所以跟着画布走就行。 */
    private Shader shaderOf(Gradient gradient, Path geometry, float alpha) {
        if (gradient == null) {
            return null;
        }
        float x0 = gradient.x0;
        float y0 = gradient.y0;
        float x1 = gradient.x1;
        float y1 = gradient.y1;
        if (!gradient.userSpace) {
            Rect bounds = geometry.getBounds();
            float w = bounds.getWidth();
            float h = bounds.getHeight();
            x0 = bounds.getLeft() + x0 * w;
            x1 = bounds.getLeft() + x1 * w;
            y0 = bounds.getTop() + y0 * h;
            y1 = bounds.getTop() + y1 * h;
        }
        if (x0 == x1 && y0 == y1) {
            return null;   // 零长度渐变：Skia 会直接不出图，交给纯色。
        }
        // 组不透明度乘进每一档的颜色里 —— 设了 shader 之后 Paint.color 是不参与调色的。
        int[] colors = new int[gradient.colors.length];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = multiplyAlpha(gradient.colors[i], alpha);
        }
        return Shader.makeLinearGradient(x0, y0, x1, y1, colors, gradient.stops,
                GradientStyle.DEFAULT.withTileMode(FilterTileMode.CLAMP));
    }

    private void applyDash(Paint paint, String dashArray, String dashOffset, float unit) {
        double[] intervals = parseFloats(dashArray);
        if (intervals.length == 0) {
            return;
        }
        // SVG 规定奇数个数要把整串重复一遍（"5" 等于 "5 5"）；Skia 只收偶数长度，
        // 少这一步的话 makeDash 直接抛出来，虚线就整条消失。
        int length = intervals.length % 2 == 0 ? intervals.length : intervals.length * 2;
        float[] dashes = new float[length];
        double total = 0;
        for (int i = 0; i < length; i++) {
            dashes[i] = (float) Math.max(0, intervals[i % intervals.length] * unit);
            total += dashes[i];
        }
        if (total <= 0) {
            return;
        }
        double[] offsets = parseFloats(dashOffset);
        // 偏移同样按 pathLength 计份，否则描字动画会整体错位。
        float offset = offsets.length > 0 ? (float) (offsets[0] * unit) : 0f;
        PathEffect effect = PathEffect.makeDash(dashes, offset);
        paint.setPathEffect(effect);
        effect.close();
    }

    /**
     * dash 的快捷路径：预解析好的间隔（加载期算好，奇数已补偶）× 单位。
     *
     * <p>没有 dash 动画通道的叶子每帧都走这里——原来每一帧都要重新 parseFloats 两串
     * 字符串再建一个原生 PathEffect，动画素材常驻期间按帧率持续付费。</p>
     */
    private static void applyStaticDash(Paint paint, float[] intervals, float phase, float unit) {
        if (intervals == null || intervals.length == 0) {
            return;
        }
        float[] dashes = new float[intervals.length];
        double total = 0;
        for (int i = 0; i < intervals.length; i++) {
            dashes[i] = (float) Math.max(0, intervals[i] * unit);
            total += dashes[i];
        }
        if (total <= 0) {
            return;
        }
        PathEffect effect = PathEffect.makeDash(dashes, phase * unit);
        paint.setPathEffect(effect);
        effect.close();
    }

    /** 加载期解析 dasharray：奇数个数补成偶数（applyDash 同一语义），解析不出整串返回 null。 */
    private static float[] staticDashIntervals(String dashArray) {
        double[] intervals = parseFloats(dashArray);
        if (intervals.length == 0) {
            return null;
        }
        int length = intervals.length % 2 == 0 ? intervals.length : intervals.length * 2;
        float[] dashes = new float[length];
        for (int i = 0; i < length; i++) {
            dashes[i] = (float) Math.max(0, intervals[i % intervals.length]);
        }
        return dashes;
    }

    /** 加载期解析 dashoffset 的首个值；没有按 0。 */
    private static float staticDashPhase(String dashOffset) {
        double[] offsets = parseFloats(dashOffset);
        return offsets.length > 0 ? (float) offsets[0] : 0f;
    }

    // ==================================================================
    // 建树
    // ==================================================================

    /** 往资源表里收 clipPath 与线性渐变；defs 里其余的东西照规范不画。 */
    private void harvest(Element defs) {
        for (Element child : defs.children) {
            String tag = child.localTag();
            String id = child.attr("id");
            if (id == null) {
                continue;
            }
            if ("clipPath".equals(tag)) {
                Path path = buildGroupGeometry(child);
                if (path != null) {
                    clips.put(id, path);
                }
            } else if ("linearGradient".equals(tag)) {
                Gradient gradient = buildGradient(child);
                if (gradient != null) {
                    gradients.put(id, gradient);
                }
            } else if ("g".equals(tag) || "symbol".equals(tag)) {
                // 嵌套 defs 里还可能藏着 clipPath。
                harvest(child);
            }
        }
    }

    private Gradient buildGradient(Element element) {
        List<Stop> stops = new ArrayList<>();
        for (Element stop : element.children) {
            if (!"stop".equals(stop.localTag())) {
                continue;
            }
            int color = parseColor(stop.attr("stop-color", "#000000"));
            double opacity = parseNumber(stop.attr("stop-opacity", "1"), 1);
            double offset = parseOffset(stop.attr("offset", "0"));
            stops.add(new Stop(offset, withAlpha(color, (float) clamp01(opacity))));
        }
        if (stops.isEmpty()) {
            return null;
        }
        Gradient gradient = new Gradient();
        gradient.userSpace = "userSpaceOnUse".equals(element.attr("gradientUnits", "objectBoundingBox"));
        gradient.x0 = (float) parseNumber(element.attr("x1", "0"), 0);
        gradient.y0 = (float) parseNumber(element.attr("y1", "0"), 0);
        gradient.x1 = (float) parseNumber(element.attr("x2", "100%"), 0);
        gradient.y1 = (float) parseNumber(element.attr("y2", "0"), 0);
        if (gradient.x1 == 0 && gradient.y1 == 0 && stops.size() > 1) {
            gradient.x1 = 1;   // 没写 x2 的按 SVG 默认 100% 处理（objectBoundingBox 下就是 1）。
        }
        gradient.colors = new int[stops.size()];
        gradient.stops = new float[stops.size()];
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            gradient.stops[i] = (float) clamp01(stop.offset);
            gradient.colors[i] = stop.color;
        }
        return gradient;
    }

    private static double parseOffset(String value) {
        if (value == null) {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("%")) {
            return parseNumber(trimmed.substring(0, trimmed.length() - 1), 0) / 100.0;
        }
        return parseNumber(trimmed, 0);
    }

    /** clipPath 里可能有好几个图形，按非零缠绕并成一块。 */
    private Path buildGroupGeometry(Element container) {
        PathBuilder builder = new PathBuilder();
        Path union = null;
        try {
            for (Element child : container.children) {
                Path path = geometryOf(child);
                if (path == null) {
                    continue;
                }
                builder.addPath(path);
                path.close();
            }
            union = builder.isEmpty() ? null : builder.snapshot();
        } catch (Throwable ignored) {
            return null;
        } finally {
            builder.close();
        }
        return union;
    }

    private Node build(Element element, State parent) {
        String tag = element.localTag();
        if ("animate".equals(tag) || "animateTransform".equals(tag) || "animateMotion".equals(tag)) {
            return null;
        }
        Node node = new Node();
        State state = parent.inherit(element);
        node.opacity = parseNumber(element.attr("opacity", "1"), 1);
        node.inline = !"none".equals(element.attr("display", "inline"));
        node.matrix = parseTransform(element.attr("transform"));
        node.clip = resolveRef(element.attr("clip-path"), clips);
        node.geometry = geometryOf(element);
        // SVG 规范：写了 pathLength 之后，stroke-dasharray / dashoffset 的单位换成
        // 「作者声明的全长」的份数，而不是用户单位。描字动画全靠这条成立 ——
        // hello.svg 用 pathLength="100" 把「已画出多少」直接写成百分比，
        // 不认的话 0.877/99.123 会被当成 0.877 个用户单位，画出来的是一串锯齿而不是笔画。
        node.pathLength = parseNumber(element.attr("pathLength", "0"), 0);
        String gradientId = referenceId(state.fillRaw);
        node.fillGradient = gradientId == null ? null : gradients.get(gradientId);
        node.fill = node.fillGradient != null ? 0 : state.fill;
        node.stroke = state.stroke;
        // 描边渐变与填充渐变同一套判据：看原文是不是 url(#…)，因为 parseColor 会把引用读成 0。
        String strokeRef = referenceId(state.strokeRaw);
        node.strokeGradient = strokeRef == null ? null : gradients.get(strokeRef);
        if (node.strokeGradient != null) {
            node.stroke = 0;   // 颜色由渐变负责，留着 0 才不会被当成「另一个要画的实心描边」
        }
        node.strokeWidth = state.strokeWidth;
        node.lineCap = state.lineCap;
        node.lineJoin = state.lineJoin;
        node.dashArray = state.dashArray;
        node.dashOffset = state.dashOffset;
        node.dashIntervals = staticDashIntervals(state.dashArray);
        node.dashPhase = staticDashPhase(state.dashOffset);
        node.fillMode = state.evenOdd ? PathFillMode.EVEN_ODD : PathFillMode.WINDING;
        if (node.geometry != null && node.fillMode != node.geometry.getFillMode()) {
            Path retuned = node.geometry.makeWithFillMode(node.fillMode);
            node.geometry.close();
            node.geometry = retuned;
        }
        if (node.geometry == null && node.clip == null && element.children.isEmpty()) {
            return null;   // 既没内容又没子节点的组，画了也是白画。
        }

        for (Element child : element.children) {
            String childTag = child.localTag();
            if ("animate".equals(childTag) || "animateTransform".equals(childTag)
                    || "animateMotion".equals(childTag)) {
                Channel channel = Channel.of(childTag, child.attributes);
                if (channel != null) {
                    node.channels.add(channel);
                    node.lastEnd = Math.max(node.lastEnd, channel.end());
                }
                continue;
            }
            Node sub = build(child, state);
            if (sub != null) {
                node.children.add(sub);
            }
            node.lastEnd = Math.max(node.lastEnd, sub == null ? 0 : sub.lastEnd);
        }
        return node;
    }

    private static Path geometryOf(Element element) {
        String tag = element.localTag();
        try {
            if ("path".equals(tag)) {
                String d = element.attr("d");
                if (d == null || d.isEmpty()) {
                    return null;
                }
                Path path = Path.makeFromSVGString(d);
                return path == null || path.isEmpty() ? null : path;
            }
            if ("rect".equals(tag)) {
                double x = parseNumber(element.attr("x", "0"), 0);
                double y = parseNumber(element.attr("y", "0"), 0);
                double w = parseNumber(element.attr("width", "0"), 0);
                double h = parseNumber(element.attr("height", "0"), 0);
                if (w <= 0 || h <= 0) {
                    return null;
                }
                double rx = parseLength(element.attr("rx"), w);
                double ry = parseLength(element.attr("ry"), h);
                if (rx <= 0 && ry <= 0) {
                    return Path.makeRect(Rect.makeLTRB((float) x, (float) y, (float) (x + w), (float) (y + h)));
                }
                double radius = rx > 0 && ry > 0 ? Math.min(rx, ry) : Math.max(rx, ry);
                radius = Math.min(radius, Math.min(w, h) * .5);
                return Path.makeRRect(RRect.makeXYWH(
                        (float) x, (float) y, (float) w, (float) h, (float) radius));
            }
            if ("ellipse".equals(tag) || "circle".equals(tag)) {
                double cx = parseNumber(element.attr("cx", "0"), 0);
                double cy = parseNumber(element.attr("cy", "0"), 0);
                double rx = "circle".equals(tag)
                        ? parseNumber(element.attr("r", "0"), 0) : parseNumber(element.attr("rx", "0"), 0);
                double ry = "circle".equals(tag)
                        ? parseNumber(element.attr("r", "0"), 0) : parseNumber(element.attr("ry", "0"), 0);
                if (rx <= 0 || ry <= 0) {
                    return null;
                }
                return Path.makeOval(Rect.makeXYWH((float) (cx - rx), (float) (cy - ry),
                        (float) (rx * 2), (float) (ry * 2)));
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static double parseLength(String value, double fallbackAxis) {
        if (value == null) {
            return 0;
        }
        if (value.endsWith("%")) {
            return parseNumber(value.substring(0, value.length() - 1), 0) / 100.0 * fallbackAxis;
        }
        return parseNumber(value, 0);
    }

    // ==================================================================
    // 墨迹盒
    // ==================================================================

    /**
     * 沿时间轴采几帧，把所有叶子的墨迹并起来，再和 viewBox 求交。
     *
     * <p>为什么要这个：这批素材的画布留白极多（{@code like.svg} 的心只占 512 里的 120），
     * 照 viewBox 映射进 20 逻辑像素的按钮，爱心就只剩 5 像素。求交那一步是为了躲开
     * 导出器放在画外的东西 —— {@code login-safe.svg} 有一个分量停在 y≈-1411，
     * 不排除的话整体会被压成一根条。</p>
     */
    private double[] measureContent() {
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int sample = 0; sample <= MEASURE_SAMPLES; sample++) {
            double time = durationSeconds * sample / MEASURE_SAMPLES;
            for (Node root : roots) {
                measureWalk(root, time, Matrix33.IDENTITY, box);
            }
        }
        double minX = Math.max(box[0], viewBoxX);
        double minY = Math.max(box[1], viewBoxY);
        double maxX = Math.min(box[2], viewBoxX + viewBoxW);
        double maxY = Math.min(box[3], viewBoxY + viewBoxH);
        if (!(maxX > minX) || !(maxY > minY)) {
            return new double[]{viewBoxX, viewBoxY, viewBoxW, viewBoxH};
        }
        return new double[]{minX, minY, maxX - minX, maxY - minY};
    }

    private void measureWalk(Node node, double time, Matrix33 parent, double[] box) {
        Matrix33 matrix = node.matrix;
        boolean inline = node.inline;
        double opacity = node.opacity;
        for (Channel channel : node.channels) {
            if (!channel.evaluate(time)) {
                continue;
            }
            switch (channel.target) {
                case Channel.TRANSFORM:
                    matrix = channel.matrix();
                    break;
                case Channel.MOTION:
                    matrix = matrix.makeConcat(channel.motion());
                    break;
                case Channel.OPACITY:
                    opacity = channel.out[0];
                    break;
                case Channel.DISPLAY:
                    inline = !"none".equals(channel.outString);
                    break;
                default:
                    break;
            }
        }
        if (!inline || opacity <= 0.004) {
            return;
        }
        Matrix33 absolute = parent.makeConcat(matrix);
        if (node.geometry != null) {
            Rect bounds = node.geometry.getBounds();
            accumulate(box, absolute, bounds);
        }
        for (Node child : node.children) {
            measureWalk(child, time, absolute, box);
        }
    }

    private static void accumulate(double[] box, Matrix33 matrix, Rect bounds) {
        float[] m = matrix.getMat();
        double left = bounds.getLeft();
        double top = bounds.getTop();
        double right = left + bounds.getWidth();
        double bottom = top + bounds.getHeight();
        for (int corner = 0; corner < 4; corner++) {
            double x = (corner & 1) == 0 ? left : right;
            double y = (corner & 2) == 0 ? top : bottom;
            double tx = m[0] * x + m[1] * y + m[2];
            double ty = m[3] * x + m[4] * y + m[5];
            box[0] = Math.min(box[0], tx);
            box[1] = Math.min(box[1], ty);
            box[2] = Math.max(box[2], tx);
            box[3] = Math.max(box[3], ty);
        }
    }

    // ==================================================================
    // 解析小工具
    // ==================================================================

    private static Path resolveRef(String value, Map<String, Path> table) {
        String id = referenceId(value);
        if (id == null) {
            return null;
        }
        return table.get(id);
    }

    /** {@code url(#i0)} → {@code i0}；不是 url() 就 null。 */
    static String referenceId(String value) {
        if (value == null) {
            return null;
        }
        int open = value.indexOf('(');
        int close = value.indexOf(')', open + 1);
        if (open < 0 || close < 0 || !value.startsWith("url")) {
            return null;
        }
        String inner = value.substring(open + 1, close).trim();
        if (inner.startsWith("#")) {
            inner = inner.substring(1);
        } else if (inner.startsWith("\"#")) {
            inner = inner.substring(2, Math.max(2, inner.length() - 1));
        }
        return inner.isEmpty() ? null : inner;
    }

    /**
     * 读一串数：{@code "5.05 5.038"}、{@code "0 0 1 1; 0.333 0 0.667 1"}、{@code "-48"}。
     *
     * <p>分隔符认逗号、分号、空白；除此之外还按 SVG 路径的规矩认「紧跟在数字后面的负号」
     * 和「一个小数点之后又出现的第二个小数点」为新数开始 —— 导出器写的
     * {@code "196.73 0; 354.58 0"} 里负号不多见，但 {@code "-2,-2"} 这种连写很常见。</p>
     */
    static double[] parseFloats(String value) {
        if (value == null || value.isEmpty()) {
            return new double[0];
        }
        List<Double> out = new ArrayList<>(8);
        StringBuilder token = new StringBuilder();
        boolean hasDot = false;
        char previous = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == ';' || Character.isWhitespace(c)) {
                flushToken(out, token);
                hasDot = false;
                previous = 0;
                continue;
            }
            boolean digit = c >= '0' && c <= '9';
            boolean sign = c == '-' || c == '+';
            boolean dot = c == '.';
            boolean exponent = c == 'e' || c == 'E';
            boolean afterExponent = previous == 'e' || previous == 'E';
            if (sign && token.length() > 0 && !afterExponent) {
                flushToken(out, token);
                hasDot = false;
            } else if (dot && hasDot) {
                flushToken(out, token);
                hasDot = false;
            } else if (!digit && !sign && !dot && !exponent) {
                // 撞上不相关的字母（比如残留的函数名）就当分隔符用，别把整串废掉。
                flushToken(out, token);
                hasDot = false;
                previous = 0;
                continue;
            }
            if (dot) {
                hasDot = true;
            }
            token.append(c);
            previous = c;
        }
        flushToken(out, token);
        double[] result = new double[out.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = out.get(index);
        }
        return result;
    }

    private static void flushToken(List<Double> out, StringBuilder token) {
        if (token.length() == 0) {
            return;
        }
        try {
            out.add(Double.parseDouble(token.toString()));
        } catch (NumberFormatException ignored) {
            // 只有一截符号或指数没有尾数这类残片：丢掉它，前面已经读出来的数照用。
        }
        token.setLength(0);
    }

    static double parseNumber(String value, double fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            String trimmed = value.trim();
            if (trimmed.endsWith("%")) {
                return Double.parseDouble(trimmed.substring(0, trimmed.length() - 1).trim()) / 100.0;
            }
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 时长写法：{@code 5s} / {@code 500ms} / 裸数字（按秒）。 */
    static double parseTime(String value, double fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.endsWith("ms")) {
                return Double.parseDouble(trimmed.substring(0, trimmed.length() - 2).trim()) / 1000.0;
            }
            if (trimmed.endsWith("s")) {
                return Double.parseDouble(trimmed.substring(0, trimmed.length() - 1).trim());
            }
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * 颜色。这批素材只出现 {@code #rgb} / {@code #rrggbb} / {@code none}，
     * 但 {@code rgb()} 和几个常用名字顺手一起认，免得下次换素材就来加一行。
     */
    static int parseColor(String value) {
        if (value == null) {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "none".equals(trimmed)) {
            return 0;
        }
        if (trimmed.startsWith("#")) {
            String hex = trimmed.substring(1);
            try {
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
                    int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                    int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
                    return 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                if (hex.length() == 6) {
                    return 0xFF000000 | Integer.parseInt(hex, 16);
                }
                if (hex.length() == 8) {
                    long packed = Long.parseLong(hex, 16);
                    // SVG 的 8 位写法是 RRGGBBAA，Skia 要的是 AARRGGBB。
                    int alpha = (int) (packed & 0xFF);
                    return (alpha << 24) | ((int) (packed >> 8) & 0xFFFFFF);
                }
            } catch (NumberFormatException ignored) {
                return 0;
            }
            return 0;
        }
        if (trimmed.startsWith("rgb(") && trimmed.endsWith(")")) {
            double[] parts = parseFloats(trimmed.substring(4, trimmed.length() - 1));
            if (parts.length >= 3) {
                int alpha = parts.length >= 4 ? (int) (clamp01(parts[3]) * 255) : 255;
                return (alpha << 24) | (clamp255(parts[0]) << 16) | (clamp255(parts[1]) << 8) | clamp255(parts[2]);
            }
            return 0;
        }
        switch (trimmed.toLowerCase()) {
            case "black":
                return 0xFF000000;
            case "white":
                return 0xFFFFFFFF;
            case "red":
                return 0xFFFF0000;
            case "green":
                return 0xFF008000;
            case "blue":
                return 0xFF0000FF;
            case "yellow":
                return 0xFFFFFF00;
            case "transparent":
                return 0;
            default:
                return 0;
        }
    }

    static Matrix33 parseTransform(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Matrix33.IDENTITY;
        }
        Matrix33 result = Matrix33.IDENTITY;
        String text = value;
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('(', i);
            if (open < 0) {
                break;
            }
            int close = text.indexOf(')', open + 1);
            if (close < 0) {
                break;
            }
            String name = text.substring(i, open).trim();
            double[] args = parseFloats(text.substring(open + 1, close));
            result = result.makeConcat(transformOf(name, args));
            i = close + 1;
        }
        return result;
    }

    /** 一个变换函数 → 矩阵。动画插值出来的那组数也走这里，两边就不会分叉。 */
    static Matrix33 transformOf(String name, double[] args) {
        switch (name) {
            case "translate": {
                double x = args.length > 0 ? args[0] : 0;
                double y = args.length > 1 ? args[1] : 0;
                return Matrix33.makeTranslate((float) x, (float) y);
            }
            case "scale": {
                double x = args.length > 0 ? args[0] : 1;
                double y = args.length > 1 ? args[1] : x;
                return Matrix33.makeScale((float) x, (float) y);
            }
            case "rotate": {
                double degrees = args.length > 0 ? args[0] : 0;
                if (args.length >= 3) {
                    return Matrix33.makeRotate((float) degrees, (float) args[1], (float) args[2]);
                }
                return Matrix33.makeRotate((float) degrees);
            }
            case "skewX":
                return new Matrix33(new float[]{1, (float) Math.tan(Math.toRadians(arg(args, 0))), 0,
                        0, 1, 0, 0, 0, 1});
            case "skewY":
                return new Matrix33(new float[]{1, 0, 0,
                        (float) Math.tan(Math.toRadians(arg(args, 0))), 1, 0, 0, 0, 1});
            case "matrix": {
                if (args.length < 6) {
                    return Matrix33.IDENTITY;
                }
                // SVG 的 matrix(a b c d e f) 按列写，Skia 的 Matrix33 按行写。
                return new Matrix33(new float[]{(float) args[0], (float) args[2], (float) args[4],
                        (float) args[1], (float) args[3], (float) args[5], 0, 0, 1});
            }
            default:
                return Matrix33.IDENTITY;
        }
    }

    private static double arg(double[] args, int index) {
        return args.length > index ? args[index] : 0;
    }

    static double clamp01(double value) {
        return value < 0 ? 0 : Math.min(value, 1);
    }

    private static int clamp255(double value) {
        if (value <= 0) {
            return 0;
        }
        return Math.min(255, (int) Math.round(value));
    }

    static int withAlpha(int argb, float alpha) {
        return multiplyAlpha(argb, (float) clamp01(alpha));
    }

    static int multiplyAlpha(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * clamp01(alpha));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    /** 一层节点：静态几何 + 它自己那几个动画通道。 */
    private static final class Node {
        Path geometry;
        Path clip;
        Gradient fillGradient;
        /** {@code stroke="url(#…)"} 解出来的渐变；彩虹描边靠它。 */
        Gradient strokeGradient;
        int fill;
        int stroke;
        double strokeWidth;
        PaintStrokeCap lineCap = PaintStrokeCap.BUTT;
        PaintStrokeJoin lineJoin = PaintStrokeJoin.MITER;
        String dashArray;
        String dashOffset;
        /**
         * dash 参数的加载期解析结果（奇数已补偶、用户单位值）。
         *
         * <p>没有 DASH_ARRAY/DASH_OFFSET 动画通道时它是常量——每帧重新 parseFloats +
         * makeDash 是白付一次 JNI 原生内存。挂了 dash 动画的节点保持 {@code null}，走每帧解析。</p>
         */
        float[] dashIntervals;
        float dashPhase;
        /**
         * {@code pathLength}；0 表示没写，dash 按用户单位算。
         *
         * <p>只在 Node 上而不在 State 上：规范里 pathLength 是几何属性，不继承。</p>
         */
        double pathLength;
        /**
         * dash 单位换算系数 = 实测全长 / pathLength，量一次存住。
         *
         * <p>和 {@code Channel.measure} 同一个道理：每帧新建原生测量器去量同一条路，
         * 是白付一次 JNI 与一块原生内存。</p>
         */
        Float dashUnit;
        PathFillMode fillMode = PathFillMode.WINDING;
        Matrix33 matrix = Matrix33.IDENTITY;
        double opacity = 1;
        boolean inline = true;
        final List<Node> children = new ArrayList<>();
        final List<Channel> channels = new ArrayList<>();
        /** 这一枝上最晚结束的动画，用来定整份素材的时长。 */
        double lastEnd;
    }

    /**  presentation 属性的继承值。 */
    private static final class State {
        /** SVG 的 fill 初值就是黑，不是「不填」；不写 fill 的图形默认是实心黑。 */
        int fill = 0xFF000000;
        /**
         * {@code fill} 的<b>原文</b>。判「是不是引用渐变」必须看这一份：
         * {@code fill="url(#i0)"} 过 {@link #parseColor} 会得到 0，和 {@code fill="none"} 撞车。
         */
        String fillRaw = "#000000";
        int stroke;
        /**
         * {@code stroke} 的<b>原文</b>，理由与 {@link #fillRaw} 完全一样：
         * {@code stroke="url(#i0)"} 过 {@link #parseColor} 得到 0，于是整条笔画直接不画。
         * 作者那份 hello.svg 的彩虹就是挂在 stroke 上的。
         */
        String strokeRaw;
        double strokeWidth = 1;
        PaintStrokeCap lineCap = PaintStrokeCap.BUTT;
        PaintStrokeJoin lineJoin = PaintStrokeJoin.MITER;
        String dashArray;
        String dashOffset;
        boolean evenOdd;

        State inherit(Element element) {
            State next = new State();
            next.fill = fill;
            next.fillRaw = fillRaw;
            next.stroke = stroke;
            next.strokeRaw = strokeRaw;
            next.strokeWidth = strokeWidth;
            next.lineCap = lineCap;
            next.lineJoin = lineJoin;
            next.dashArray = dashArray;
            next.dashOffset = dashOffset;
            next.evenOdd = evenOdd;
            String value;
            if ((value = element.attr("fill")) != null) {
                next.fill = parseColor(value);
                next.fillRaw = value;
            }
            if ((value = element.attr("stroke")) != null) {
                next.stroke = parseColor(value);
                next.strokeRaw = value;
            }
            if ((value = element.attr("stroke-width")) != null) {
                next.strokeWidth = parseNumber(value, strokeWidth);
            }
            if ((value = element.attr("stroke-linecap")) != null) {
                next.lineCap = cap(value);
            }
            if ((value = element.attr("stroke-linejoin")) != null) {
                next.lineJoin = join(value);
            }
            if ((value = element.attr("stroke-dasharray")) != null && !"none".equals(value)) {
                next.dashArray = value;
            }
            if ((value = element.attr("stroke-dashoffset")) != null) {
                next.dashOffset = value;
            }
            if ((value = element.attr("fill-rule")) != null) {
                next.evenOdd = value.trim().startsWith("evenodd");
            }
            return next;
        }

        private static PaintStrokeCap cap(String value) {
            switch (value) {
                case "round":
                    return PaintStrokeCap.ROUND;
                case "square":
                    return PaintStrokeCap.SQUARE;
                default:
                    return PaintStrokeCap.BUTT;
            }
        }

        private static PaintStrokeJoin join(String value) {
            switch (value) {
                case "round":
                    return PaintStrokeJoin.ROUND;
                case "bevel":
                    return PaintStrokeJoin.BEVEL;
                default:
                    return PaintStrokeJoin.MITER;
            }
        }
    }

    private static final class Gradient {
        boolean userSpace;
        float x0, y0, x1, y1;
        int[] colors;
        float[] stops;
    }

    private static final class Stop {
        final double offset;
        final int color;

        Stop(double offset, int color) {
            this.offset = offset;
            this.color = color;
        }
    }

    private static final class Channel {
        static final int TRANSFORM = 0;
        static final int MOTION = 1;
        static final int OPACITY = 2;
        static final int DISPLAY = 3;
        static final int DASH_ARRAY = 4;
        static final int DASH_OFFSET = 5;
        static final int STROKE_WIDTH = 6;
        static final int FILL = 7;
        static final int STROKE = 8;

        int target;
        double begin;
        double dur = 1;
        boolean indefinite = true;
        /**
         * {@code fill="freeze"}：一次性动画走完之后停在末帧。
         *
         * <p>SMIL 里 {@code fill} 这个属性在 {@code <animate>} 上<b>不是</b>颜色，而是
         * 「演完之后怎么办」——缺省 {@code remove}（回落基底值），{@code freeze} 才是冻在末帧。
         * 元素上的 presentation {@code fill="…"}（颜色）走的是 {@link #FILL} 那条通道，两者不冲突，
         * 因为这里读的是动画元素自己的属性。</p>
         */
        boolean freeze;
        boolean discrete;
        boolean spline;
        double[] keyTimes;
        double[][] keys;
        String[] strings;
        double[] splines;
        String transformType = "translate";
        /**
         * animateMotion 的采样器。路径长度与 {@link PathMeasure} 都在加载时算好一次 ——
         * 每帧新建一个原生测量器去量同一条路，是白付一次 JNI 与一块原生内存。
         *
         * <p>它跟着这份素材活到进程结束，所以<b>不关</b>；和 {@code BrandVectorIcons}
         * 缓存那些 Path 是同一个道理。</p>
         */
        PathMeasure measure;
        double motionLength;

        final double[] out = new double[6];
        int outCount = 1;
        String outString;
        int color;

        static Channel of(String tag, Map<String, String> attributes) {
            Channel channel = new Channel();
            String attributeName = attributes.get("attributeName");
            channel.begin = parseTime(attributes.get("begin"), 0);
            channel.dur = parseTime(attributes.get("dur"), 0);
            String repeat = attributes.get("repeatCount");
            // 规范里「没写 repeatCount」＝只演一遍，不是无限循环。以前缺省当成 indefinite，
            // 于是任何一张没写 repeatCount 的一次性动画会被永远重播——本仓这六张素材都显式
            // 写了 indefinite，所以改默认值对它们没有影响，只是不再把一次性动画播成循环。
            channel.indefinite = repeat != null
                    && ("indefinite".equals(repeat.trim()) || parseNumber(repeat, 1) > 1_000_000);
            channel.freeze = "freeze".equals(attributes.get("fill"));
            String calcMode = attributes.get("calcMode");
            channel.discrete = "discrete".equals(calcMode);
            channel.spline = "spline".equals(calcMode);

            String values = attributes.get("values");
            if (values == null || values.isEmpty()) {
                // animateMotion 不写 values，进度全在 keyPoints 上（也是分号隔开的），
                // 按 SMIL 的读法把它当关键帧序列就行。
                values = "animateMotion".equals(tag) ? attributes.get("keyPoints") : null;
                if (values == null || values.isEmpty()) {
                    return null;   // from/to 那种写法这批素材里没有，不猜。
                }
            }
            String[] frames = values.split(";");
            List<String> kept = new ArrayList<>(frames.length);
            for (String frame : frames) {
                kept.add(frame.trim());
            }
            channel.strings = kept.toArray(new String[0]);
            channel.keys = new double[kept.size()][];
            boolean numeric = !kept.isEmpty();
            int width = -1;
            for (int i = 0; i < kept.size(); i++) {
                double[] parsed = parseFloats(kept.get(i));
                if (parsed.length == 0) {
                    numeric = false;
                } else if (width >= 0 && parsed.length != width) {
                    numeric = false;   // 各帧分量个数不一致就别插值，退成 discrete 挑一帧。
                } else {
                    width = parsed.length;
                }
                channel.keys[i] = parsed;
            }
            if (!numeric) {
                channel.keys = null;
                width = 0;
            }

            // keyTimes 的配对对象是「关键帧个数」，不是数值 keys —— display 这种字符串通道
            // 一个数都没有，照样要按 keyTimes 切段。这里写成看 keys != null 的话，
            // display 通道会永远停在第 0 帧，而第 0 帧通常是 none，整枝就再没画出来过。
            int frameCount = channel.strings.length;
            String times = attributes.get("keyTimes");
            if (times != null && !times.isEmpty()) {
                double[] parsed = parseFloats(times);
                if (parsed.length == frameCount) {
                    channel.keyTimes = parsed;
                }
            }
            if (channel.keyTimes == null) {
                channel.keyTimes = uniformTimes(frameCount);
            }
            String splineText = attributes.get("keySplines");
            if (channel.spline && splineText != null) {
                double[] parsed = parseFloats(splineText);
                channel.splines = parsed.length >= 4 ? parsed : null;
                channel.spline = channel.splines != null;
            }
            if ("animateMotion".equals(tag)) {
                channel.target = MOTION;
                String path = attributes.get("path");
                if (path == null || path.isEmpty()) {
                    return null;
                }
                Path motion = Path.makeFromSVGString(path);
                if (motion == null) {
                    return null;
                }
                try {
                    channel.measure = new PathMeasure(motion, false);
                    channel.motionLength = channel.measure.getLength();
                } catch (Throwable ignored) {
                    channel.measure = null;
                }
                motion.close();
                if (channel.measure == null || channel.motionLength <= 0) {
                    return null;
                }
                return channel;
            }
            if ("animateTransform".equals(tag)) {
                channel.target = TRANSFORM;
                String type = attributes.get("type");
                channel.transformType = type == null ? "translate" : type.trim();
                return channel;
            }
            channel.target = targetOf(attributeName);
            if (channel.target == UNSUPPORTED) {
                // 不认识的属性就整条丢掉。回退成某个能动的属性等于「猜」：
                // 作者那份 hello.svg 里有一条 animate attributeName="d"（笔画末端的微变形），
                // 猜成 OPACITY 的话路径字符串会被 parseFloats 抠出一串数字当不透明度用，
                // 字没变形，倒是整条笔画跟着乱闪。
                return null;
            }
            return channel;
        }

        /** 不在这批口子内的动画目标。丢整条，不参与求值。 */
        private static final int UNSUPPORTED = -1;

        private static int targetOf(String attributeName) {
            if (attributeName == null) {
                return OPACITY;
            }
            switch (attributeName.trim()) {
                case "display":
                    return DISPLAY;
                case "opacity":
                    return OPACITY;
                case "stroke-dasharray":
                    return DASH_ARRAY;
                case "stroke-dashoffset":
                    return DASH_OFFSET;
                case "stroke-width":
                    return STROKE_WIDTH;
                case "fill":
                    return FILL;
                case "stroke":
                    return STROKE;
                default:
                    return UNSUPPORTED;
            }
        }

        private static double[] uniformTimes(int count) {
            double[] times = new double[Math.max(count, 1)];
            for (int i = 0; i < times.length; i++) {
                times[i] = count <= 1 ? 1 : i / (double) (count - 1);
            }
            return times;
        }

        double end() {
            // 素材总时长取「最后一轮的末尾」：无限循环的按一轮算，这跟浏览器里时间轴的
            // 重复周期一致，也是 SvgPlayback 拿去把相位换算成秒数的依据。
            return begin + dur;
        }

        /** 求 t 时刻的值，写进自己的 out/outString/color。t 早于 begin 返回 false（用基底值）。 */
        boolean evaluate(double t) {
            double local = t - begin;
            if (local < 0) {
                return false;
            }
            if (dur <= 0) {
                return pick(keys == null || keys.length == 0 ? 0 : keys.length - 1);
            }
            double progress = local / dur;
            if (indefinite) {
                // 无限循环按规范是回卷：一轮结束回到该轮起点。这也是素材写
                // repeatCount="indefinite" + fill="freeze" 时 freeze 不生效的原因——动画永不「结束」。
                progress = progress - Math.floor(progress);
            } else if (progress > 1) {
                if (!freeze) {
                    // fill="remove"（缺省）：演完就把值还给元素自己，不能赖在末帧上。
                    return false;
                }
                progress = 1;   // fill="freeze"：停在末帧。
            }
            int index = segmentFor(progress);
            if (discrete || strings != null && keys == null) {
                return pick(index);
            }
            double from = keyTimes[index];
            double to = keyTimes[Math.min(index + 1, keyTimes.length - 1)];
            double span = to - from;
            double u = span <= 0 ? 1 : clamp01((progress - from) / span);
            if (spline && index * 4 + 3 < splines.length) {
                u = cubic(splines[index * 4], splines[index * 4 + 1],
                        splines[index * 4 + 2], splines[index * 4 + 3], u);
            }
            if (keys == null || index + 1 >= keys.length) {
                return pick(index);
            }
            double[] a = keys[index];
            double[] b = keys[index + 1];
            for (int c = 0; c < out.length && c < a.length && c < b.length; c++) {
                out[c] = a[c] + (b[c] - a[c]) * u;
            }
            outCount = Math.min(a.length, b.length);
            if (target == FILL || target == STROKE) {
                // 颜色通道不插值：末帧色直接挑，避免在两种品牌色之间冒出一团灰。
                color = parseColor(strings[u < .5 ? index : index + 1]);
            } else if (target == DASH_ARRAY || target == DASH_OFFSET || target == STROKE_WIDTH) {
                outString = format(out, target == DASH_ARRAY ? outCount : 1);
            }
            return true;
        }

        private boolean pick(int index) {
            index = Math.max(0, Math.min(index, keyCount() - 1));
            if (keys != null && keys[index] != null) {
                for (int c = 0; c < out.length && c < keys[index].length; c++) {
                    out[c] = keys[index][c];
                }
                outCount = keys[index].length;
            }
            outString = strings == null ? null : strings[index];
            if (target == FILL || target == STROKE) {
                color = parseColor(outString);
            }
            return true;
        }

        private int keyCount() {
            if (keys != null) {
                return keys.length;
            }
            return strings == null ? 1 : strings.length;
        }

        private int segmentFor(double progress) {
            if (keyTimes == null) {
                return 0;
            }
            int index = 0;
            for (int i = 0; i < keyTimes.length; i++) {
                if (keyTimes[i] <= progress) {
                    index = i;
                }
            }
            return Math.max(0, Math.min(index, keyCount() - 2 < 0 ? 0 : keyCount() - 2));
        }

        private static String format(double[] values, int count) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < count && i < values.length; i++) {
                if (i > 0) {
                    out.append(' ');
                }
                out.append(trimmed(values[i]));
            }
            return out.toString();
        }

        private static String trimmed(double value) {
            if (value == Math.floor(value) && !Double.isInfinite(value)) {
                return Long.toString((long) value);
            }
            return Double.toString(Math.round(value * 100000.0) / 100000.0);
        }

        Matrix33 matrix() {
            return transformOf(transformType, out);
        }

        /**
         * animateMotion：把这一时刻的 keyPoint 换算成路上的落点。
         *
         * <p><b>keyPoints 这里按「占整条路的比例」读</b>。按 SMIL 原文写，不带 {@code %} 的
         * 数字是路径坐标系里的里程；但这批导出器给的区间恰好是 0..1，而那条路实测约 10 个单位
         * —— 照字面读的话扫描线一生只走一成路，明显不是作者的意思。Jitter 一类的预览也是
         * 按整条路走的，所以这里乘上 {@link #motionLength}。</p>
         */
        Matrix33 motion() {
            if (measure == null || motionLength <= 0) {
                return Matrix33.IDENTITY;
            }
            try {
                Matrix33 at = measure.getMatrix((float) (clamp01(out[0]) * motionLength), true, false);
                return at == null ? Matrix33.IDENTITY : at;
            } catch (Throwable ignored) {
                return Matrix33.IDENTITY;
            }
        }

        /** 三次贝塞尔缓动：给定 x 求 y，用二分收。keySplines 就是这四个控制点。 */
        static double cubic(double x1, double y1, double x2, double y2, double x) {
            if (x <= 0) {
                return 0;
            }
            if (x >= 1) {
                return 1;
            }
            double low = 0;
            double high = 1;
            double t = x;
            for (int i = 0; i < 24; i++) {
                double estimate = bezier(x1, x2, t);
                if (Math.abs(estimate - x) < 1e-4) {
                    break;
                }
                if (estimate < x) {
                    low = t;
                } else {
                    high = t;
                }
                t = (low + high) * .5;
            }
            return bezier(y1, y2, t);
        }

        private static double bezier(double c1, double c2, double t) {
            double inv = 1 - t;
            return 3 * inv * inv * t * c1 + 3 * inv * t * t * c2 + t * t * t;
        }
    }
}
