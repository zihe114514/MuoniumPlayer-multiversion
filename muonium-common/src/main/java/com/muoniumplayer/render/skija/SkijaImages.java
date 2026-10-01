package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.BackendTexture;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.GLTextureInfo;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.util.HashMap;
import java.util.Map;

/**
 * GL 纹理到 Skia {@link Image} 的桥。
 *
 * <h2>为什么不重做纹理上传</h2>
 * 「把 PNG 解码、把像素传到显存」不是 UI 渲染，是资源加载。这条链路（{@code TextureManager} /
 * {@code DynamicTexture} / {@code AnimatedTexture} / 封面下载）本身没有旧固定管线的包袱，
 * 重写它只会凭空引入一批新 bug。所以纹理仍旧由现有代码上传成 GL 纹理，
 * 这里用 {@link Image#borrowTextureFrom} 把已经存在的 GL 纹理<b>借</b>给 Skia 采样。
 *
 * <p>{@code borrow} 而不是 {@code adopt}：所有权仍在原来的纹理对象手上，
 * Skia 不会去删这个纹理。这一点很关键——{@code adopt} 会让 Skia 在回收 Image 时
 * 删掉 Minecraft 或本 mod 还在用的纹理。
 *
 * <p>借来的 Image 只是一个指向同一个 GL 纹理对象的视图，不做像素快照。
 * 因此纹理内容被重新上传（封面换了、动图播下一帧）后，下次绘制自然就是新内容，
 * 不需要任何失效通知。<b>只有</b>纹理被删除或者尺寸变了才需要 {@link #invalidate(int)}。
 *
 * <h2>纹理尺寸从哪来</h2>
 * 不用 {@code glGetTexLevelParameteriv} 去问 GL。原因是那要先把纹理绑到当前单元，
 * 而 Skia 对 GL 状态有自己的缓存，在一帧中间插入裸 GL 调用正是 S1 阶段踩过的坑
 * （画面随机错乱）。尺寸一律由调用方给出——旧代码的
 * {@code drawModalRectWithCustomSizedTexture(..., textureWidth, textureHeight)}
 * 本来就带着这两个值。
 */
public final class SkijaImages {

    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_RGBA8 = 0x8058;

    /** key = glId &lt;&lt; 32 | width &lt;&lt; 16 | height，尺寸变了就是另一个条目。 */
    private static final Map<Long, Image> CACHE = new HashMap<>();

    /**
     * 纹理 id → {宽, 高}。由纹理分配处登记（{@code TextureUtil.allocateTextureImpl} /
     * {@code DynamicTexture.allocateTextureImpl}）。
     *
     * <p>有一批绘制入口只给了「画多大」，没给「纹理多大」——{@code roundedRectTextured}
     * 就是典型：调用方先绑纹理，再说要画进哪个圆角矩形。旧实现靠 GL 的
     * {@code sampler2D} 自动用归一化 uv，不需要知道尺寸；Skia 要用像素坐标建源矩形，
     * 就必须知道。所以在纹理分配的那一刻把尺寸记下来。
     */
    private static final Map<Integer, int[]> SIZES = new HashMap<>();

    private SkijaImages() {
    }

    private static long key(int glId, int width, int height) {
        return ((long) glId << 32) | ((long) (width & 0xFFFF) << 16) | (height & 0xFFFF);
    }

    /**
     * 取（或建）一个指向给定 GL 纹理的 Skia Image。返回的对象由本类持有，<b>调用方不要 close</b>。
     *
     * @return 上下文不可用或包装失败时返回 null
     */
    public static Image wrap(int glId, int width, int height) {
        if (glId <= 0 || width <= 0 || height <= 0) {
            return null;
        }
        DirectContext context = SkijaHost.context();
        if (context == null) {
            return null;
        }
        long k = key(glId, width, height);
        Image cached = CACHE.get(k);
        if (cached != null) {
            return cached;
        }
        try {
            BackendTexture backend = BackendTexture.makeGL(width, height, false,
                    new GLTextureInfo(GL_TEXTURE_2D, glId, GL_RGBA8));
            // TOP_LEFT：Minecraft 的 NativeImage 第 0 行就是图片顶边，
            // 旧着色器也是按 v=0 对应顶边采样的（ShaderProgram.drawQuad 未翻转）。
            //
            // UNPREMUL：Minecraft 的纹理存的是直通 alpha，不是预乘。
            // 写成 PREMUL 会让半透明像素偏亮（尤其是白色描边的图标）。
            Image image = Image.borrowTextureFrom(context, backend, SurfaceOrigin.TOP_LEFT,
                    ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.getSRGB(), null);
            if (image != null) {
                CACHE.put(k, image);
            }
            return image;
        } catch (Throwable t) {
            System.out.println("[MuoniumPlayer/SkijaImages] 包装 GL 纹理失败 id=" + glId
                    + " " + width + "x" + height + ": " + t);
            return null;
        }
    }

    /**
     * 纹理分配 / 重新分配时登记尺寸。
     *
     * <p>顺手把这个 id 的旧视图作废：GL 允许对同一个纹理名重新 {@code glTexImage2D}
     * 成另一个尺寸，旧的 Skia 视图仍按老尺寸采样，画出来就是错位或拉伸。
     */
    public static void rememberSize(int glId, int width, int height) {
        if (glId <= 0 || width <= 0 || height <= 0) {
            return;
        }
        int[] previous = SIZES.get(glId);
        if (previous != null && previous[0] == width && previous[1] == height) {
            return;
        }
        SIZES.put(glId, new int[]{width, height});
        invalidate(glId);
    }

    /**
     * 弱登记：只在还没有记录时填入，<b>不</b>作废已有视图。
     *
     * <p>给「绘制时顺手带了纹理尺寸」的入口用（{@code Image.drawModalRectWithCustomSizedTexture}
     * 这一族）。这些入口给的尺寸来自调用方的约定而不是真实分配，偶尔会不一致；
     * 如果让它们像 {@link #rememberSize} 那样反复作废缓存，每帧都会重建纹理视图。
     */
    public static void hintSize(int glId, int width, int height) {
        if (glId <= 0 || width <= 0 || height <= 0) {
            return;
        }
        SIZES.putIfAbsent(glId, new int[]{width, height});
    }

    /** 登记过的纹理尺寸，没登记返回 null。 */
    public static int[] sizeOf(int glId) {
        return SIZES.get(glId);
    }

    /** 用登记过的尺寸包装纹理。没登记过则返回 null。 */
    public static Image wrap(int glId) {
        int[] size = SIZES.get(glId);
        return size == null ? null : wrap(glId, size[0], size[1]);
    }

    /** 纹理被删除或重新分配时调用，丢掉所有指向这个 id 的视图。 */
    public static void invalidate(int glId) {
        if (CACHE.isEmpty()) {
            return;
        }
        long lo = (long) glId << 32;
        long hi = lo + 0xFFFFFFFFL;
        CACHE.entrySet().removeIf(e -> {
            long k = e.getKey();
            if (k >= lo && k <= hi) {
                e.getValue().close();
                return true;
            }
            return false;
        });
    }

    /** 渲染层整体关闭 / GL 上下文重建时清空。 */
    public static void clear() {
        for (Image image : CACHE.values()) {
            image.close();
        }
        CACHE.clear();
        SIZES.clear();
    }

    /**
     * 画「当前绑定的纹理」。
     *
     * <p>这条入口对应旧代码里「先 bindTexture、再画一个矩形」的写法：纹理是隐式的。
     * 纹理 id 由 {@link SkijaGfx#boundTexture()} 记着，尺寸从 {@link #SIZES} 查。
     *
     * @return 纹理未知（没绑、或者尺寸没登记过）时返回 false，调用方可以据此跳过
     */
    public static boolean drawBoundTextured(double x, double y, double width, double height,
                                            double u0, double v0, double u1, double v1,
                                            int tintArgb) {
        int glId = SkijaGfx.boundTexture();
        int[] size = SIZES.get(glId);
        if (size == null) {
            return false;
        }
        drawTextured(x, y, width, height, u0, v0, u1, v1,
                glId, size[0], size[1], tintArgb, SkijaGfx.linearFiltering());
        return true;
    }

    /** 圆角版本的「画当前绑定的纹理」。 */
    public static boolean drawBoundRoundedTextured(double x, double y, double width, double height,
                                                    double radius,
                                                    double u0, double v0, double u1, double v1,
                                                    float alpha) {
        int glId = SkijaGfx.boundTexture();
        int[] size = SIZES.get(glId);
        if (size == null) {
            return false;
        }
        drawRoundedTextured(x, y, width, height, radius, u0, v0, u1, v1,
                glId, size[0], size[1], alpha, SkijaGfx.linearFiltering());
        return true;
    }

    public static int cacheSize() {
        return CACHE.size();
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /**
     * 把纹理的一块画到屏幕的一块上。uv 用<b>归一化</b>坐标，与旧着色器一致。
     *
     * @param tintArgb 相当于旧固定管线的 {@code glColor4f} 调制。
     *                 {@code 0xFFFFFFFF} 表示原样。
     */
    public static void drawTextured(double x, double y, double width, double height,
                                    double u0, double v0, double u1, double v1,
                                    int glId, int texWidth, int texHeight,
                                    int tintArgb, boolean linear) {
        if (SkijaClip.recording()) {
            SkijaClip.record(x, y, width, height, 0);
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        Image image = wrap(glId, texWidth, texHeight);
        if (image == null) {
            return;
        }
        Source src = source(u0, v0, u1, v1, texWidth, texHeight);
        Rect dst = normalize(x, y, width, height);
        Paint paint = tintPaint(tintArgb);
        drawSource(canvas, image, src, dst, linear, paint);
        clearTint(paint);
    }

    /**
     * 圆角裁剪的贴图绘制，对应旧的 {@code RQTShader}。
     *
     * <p>一处已知的细微差异：旧的 {@code rqt.frag} 输出的是
     * {@code vec4(textureColor.rgb, u_alpha * edge)}——它<b>丢掉了纹理自身的 alpha</b>，
     * 只用 {@code a == 0} 时 discard 来抠掉全透明像素。也就是说纹理里 alpha 介于 0 和 1 之间的
     * 像素会被画成完全按 {@code u_alpha} 的不透明度。这里改成正常的 alpha 混合：
     * 全透明和全不透明两种像素结果完全一致，只有「半透明」像素不同，
     * 而这条路径的用途是封面、头像、模糊背景，都是不透明位图，所见完全一样；
     * 万一有半透明素材，正常混合才是对的。
     */
    public static void drawRoundedTextured(double x, double y, double width, double height,
                                           double radius,
                                           double u0, double v0, double u1, double v1,
                                           int glId, int texWidth, int texHeight,
                                           float alpha, boolean linear) {
        if (SkijaClip.recording()) {
            // 圆角图片控件正是旧实现里「再开一层模板」的那类调用方，必须支持。
            SkijaClip.record(x, y, width, height, radius);
            return;
        }
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        Image image = wrap(glId, texWidth, texHeight);
        if (image == null) {
            return;
        }
        RRect rrect = SkijaShapes.rrect(x, y, width, height, radius);
        Source src = source(u0, v0, u1, v1, texWidth, texHeight);
        Rect dst = Rect.makeXYWH((float) rrect.getLeft(), (float) rrect.getTop(),
                (float) rrect.getWidth(), (float) rrect.getHeight());
        canvas.save();
        try {
            canvas.clipRRect(rrect, true);
            Paint paint = tintPaint(SkijaGfx.argb(1f, 1f, 1f, alpha));
            drawSource(canvas, image, src, dst, linear, paint);
            clearTint(paint);
        } finally {
            canvas.restore();
        }
    }

    /**
     * 旋转 90 度贴图。
     *
     * <p>旧实现是直接把四个顶点的 uv 换位（{@code ...Rotate90R} / {@code ...Rotate90L}）。
     * 换位后的映射不是「源矩形到目标矩形」的仿射缩放，没法用一个 src/dst 矩形表达，
     * 所以改成旋转画布：局部尺寸取 {@code (height, width)}，旋转 90 度后在屏幕上正好是
     * {@code (width, height)}。
     *
     * @param degrees 顺时针为正。旧实现的 R 是顺时针 90、L 是逆时针 90
     */
    public static void drawTexturedRotated(double x, double y, double width, double height,
                                           double u0, double v0, double u1, double v1,
                                           int glId, int texWidth, int texHeight,
                                           int tintArgb, boolean linear, float degrees) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        if (SkijaClip.recording()) {
            SkijaClip.record(x, y, width, height, 0);
            return;
        }
        Image image = wrap(glId, texWidth, texHeight);
        if (image == null) {
            return;
        }
        canvas.save();
        try {
            canvas.translate((float) (x + width * 0.5), (float) (y + height * 0.5));
            canvas.rotate(degrees);
            Source src = source(u0, v0, u1, v1, texWidth, texHeight);
            Rect dst = normalize(-height * 0.5, -width * 0.5, height, width);
            Paint paint = tintPaint(tintArgb);
            drawSource(canvas, image, src, dst, linear, paint);
            clearTint(paint);
        } finally {
            canvas.restore();
        }
    }

    /**
     * 贴图，同时给上下两端不同的透明度。
     *
     * <p>旧实现靠顶点色插值实现（顶边一个 alpha、底边另一个），用于歌词与列表的边缘淡出。
     * Skia 没有顶点色，改成「先把图画进一个图层，再用竖向 alpha 渐变以 {@code DST_IN}
     * 把图层的 alpha 乘上去」。结果与顶点色插值一致，而且不受图像本身有没有 alpha 影响。
     */
    public static void drawTexturedVerticalFade(double x, double y, double width, double height,
                                                double u0, double v0, double u1, double v1,
                                                int glId, int texWidth, int texHeight,
                                                boolean linear, float topAlpha, float bottomAlpha) {
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) {
            return;
        }
        if (SkijaClip.recording()) {
            SkijaClip.record(x, y, width, height, 0);
            return;
        }
        Image image = wrap(glId, texWidth, texHeight);
        if (image == null) {
            return;
        }
        Rect bounds = normalize(x, y, width, height);
        int saveCount = canvas.saveLayer(bounds, null);
        try {
            Source src = source(u0, v0, u1, v1, texWidth, texHeight);
            Paint paint = SkijaGfx.fill(0xFFFFFFFF);
            drawSource(canvas, image, src, bounds, linear, paint);

            int top = (Math.round(clamp01(topAlpha) * 255f) << 24) | 0xFFFFFF;
            int bottom = (Math.round(clamp01(bottomAlpha) * 255f) << 24) | 0xFFFFFF;
            try (io.github.humbleui.skija.Shader gradient =
                         io.github.humbleui.skija.Shader.makeLinearGradient(
                                 (float) x, (float) y, (float) x, (float) (y + height),
                                 new int[]{top, bottom})) {
                Paint mask = SkijaGfx.fillShader(gradient);
                mask.setBlendMode(io.github.humbleui.skija.BlendMode.DST_IN);
                canvas.drawRect(bounds, mask);
                mask.setShader(null);
                mask.setBlendMode(SkijaGfx.blendMode());
            }
        } finally {
            canvas.restoreToCount(saveCount);
        }
    }

    /**
     * 竖向渐隐贴图，尺寸从登记表里查（见 {@link #rememberSize}）。
     * 尺寸未知时什么都不画并返回 false，调用方可以据此跳过。
     */
    public static boolean drawTexturedVerticalFade(double x, double y, double width, double height,
                                                   double u0, double v0, double u1, double v1,
                                                   int glId, boolean linear,
                                                   float topAlpha, float bottomAlpha) {
        int[] size = SIZES.get(glId);
        if (size == null) {
            return false;
        }
        drawTexturedVerticalFade(x, y, width, height, u0, v0, u1, v1,
                glId, size[0], size[1], linear, topAlpha, bottomAlpha);
        return true;
    }

    // ------------------------------------------------------------------
    // 源矩形 / 目标矩形的规范化
    // ------------------------------------------------------------------

    /**
     * 源矩形，外加「这一轴上 uv 区间是反向的」这个信息。
     *
     * <p>见 {@link #source} 的说明。
     */
    static final class Source {
        final Rect rect;
        final boolean flipX;
        final boolean flipY;

        Source(Rect rect, boolean flipX, boolean flipY) {
            this.rect = rect;
            this.flipX = flipX;
            this.flipY = flipY;
        }
    }

    /**
     * 由归一化 uv 求源像素矩形，并把「uv 区间反向」翻译成一次镜像。
     *
     * <p><b>为什么需要这一步</b>：旧实现把 uv 交给顶点/着色器插值，
     * {@code uv = offset + texCoord * scale}。scale 为负是完全合法的，
     * 结果就是沿该轴<b>翻转采样</b>。业务代码里确实存在这种调用——
     * 例如 {@code CappellaLyricRenderer.drawSheetCell} 传的 u/v 与
     * {@code RQTShader.draw} 的 {@code scale = u - texX} 相减后可能为负。
     *
     * <p>而 Skia 的 {@link Rect#makeLTRB} 断言 {@code l <= r && t <= b}，
     * 传进去直接抛 {@code IllegalArgumentException}——实测就是这样崩在
     * {@code Rect::makeLTRB expected t <= b, got 97.0 > 94.0}。
     *
     * <p>所以这里把区间取绝对值得到合法的源矩形，同时记下哪一轴反了；
     * {@link #drawSource} 再在目标矩形上做对应的镜像。两步合起来与旧的负 scale
     * 行为完全一致，包括它那些看起来奇怪的调用。<b>刻意不去"修正"业务侧的取值</b>：
     * 本次重写只换渲染实现，不改 UI 行为。
     */
    static Source source(double u0, double v0, double u1, double v1,
                         int texWidth, int texHeight) {
        float left = (float) (u0 * texWidth);
        float right = (float) (u1 * texWidth);
        float top = (float) (v0 * texHeight);
        float bottom = (float) (v1 * texHeight);
        boolean flipX = right < left;
        boolean flipY = bottom < top;
        if (flipX) {
            float swap = left;
            left = right;
            right = swap;
        }
        if (flipY) {
            float swap = top;
            top = bottom;
            bottom = swap;
        }
        return new Source(Rect.makeLTRB(left, top, right, bottom), flipX, flipY);
    }

    /**
     * 规范化目标矩形：负的宽 / 高换算成起点平移 + 正的尺寸。
     *
     * <p>旧的着色器入口（{@code RQTShader.draw} 等）开头就有一段
     * {@code if (x > x + width) swap} 做同样的事，这里照搬。
     */
    static Rect normalize(double x, double y, double width, double height) {
        if (width < 0) {
            x += width;
            width = -width;
        }
        if (height < 0) {
            y += height;
            height = -height;
        }
        return Rect.makeXYWH((float) x, (float) y, (float) width, (float) height);
    }

    /** 按 {@link Source} 记下的镜像信息把图画到目标矩形上。 */
    private static void drawSource(Canvas canvas, Image image, Source src, Rect dst,
                                   boolean linear, Paint paint) {
        SamplingMode mode = linear ? SamplingMode.LINEAR : SamplingMode.DEFAULT;
        if (!src.flipX && !src.flipY) {
            canvas.drawImageRect(image, src.rect, dst, mode, paint, true);
            return;
        }
        // 绕目标矩形中心镜像：等价于旧实现里 uv 的 scale 为负。
        float cx = dst.getLeft() + dst.getWidth() * 0.5f;
        float cy = dst.getTop() + dst.getHeight() * 0.5f;
        canvas.save();
        try {
            canvas.translate(cx, cy);
            canvas.scale(src.flipX ? -1f : 1f, src.flipY ? -1f : 1f);
            canvas.translate(-cx, -cy);
            canvas.drawImageRect(image, src.rect, dst, mode, paint, true);
        } finally {
            canvas.restore();
        }
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /**
     * 贴图用的 Paint。
     *
     * <p>旧固定管线在 {@code GL_MODULATE} 下的行为是「纹理颜色逐通道乘当前颜色」，
     * 在 Skia 里的精确等价物就是 {@code BlendMode.MODULATE} 的
     * {@link ColorFilter#makeBlend}。纯白调制是恒等变换，这种常见情形直接省掉 filter。
     */
    private static Paint tintPaint(int tintArgb) {
        Paint paint = SkijaGfx.fill(0xFFFFFFFF);
        if (tintArgb != 0xFFFFFFFF) {
            paint.setColorFilter(ColorFilter.makeBlend(tintArgb, io.github.humbleui.skija.BlendMode.MODULATE));
        }
        return paint;
    }

    private static void clearTint(Paint paint) {
        paint.setColorFilter(null);
    }
}
