package com.muoniumplayer.core.screens.ncm.foliabg;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Point;
import io.github.humbleui.types.Rect;

import java.util.Random;

/**
 * 「通用 / Common」背景：封面色流体 + 几何氛围层 + 暗角。
 *
 * <p>从下到上分层：</p>
 * <ol>
 *   <li><b>封面色流体</b>（仅 {@code commonUseCoverColorBg} 且拿到封面子着色器时）：
 *       上游的模糊封面（40px × 1.5 = 60px 等效）、
 *       {@code to bottom right} 主题渐变（overlay 混合、alpha .4）、封面 scale 1.5。
 *       流体下面先垫一层 {@code theme.backgroundColor}、opacity .8（原版用来盖住封面解码前的空白）。</li>
 *   <li><b>纯色遮罩</b>：整屏 {@code theme.backgroundColor}，alpha =
 *       {@code commonUseCoverColorBg ? commonCoverOpacity : 1}（原版默认 .75）。</li>
 *   <li><b>几何氛围层</b>：15 个形状 + 20 颗上升光点，见 {@link #drawShapes} /
 *       {@link #drawParticles}；{@code staticMode} 时整层（含暗角）不画。</li>
 *   <li><b>暗角</b>：{@code radial-gradient(circle, transparent 40%, rgba(0,0,0,.6) 100%)}，
 *       {@code circle} 默认 farthest-corner（半径 = 中心到最远角），40% 半径以内完全透明；
 *       {@code commonDisableVignette} 时只跳过这一层，形状与光点不受影响。</li>
 * </ol>
 *
 * <p><b>随机数的差异（刻意偏离，见规格 §4.4.1 与 §7）</b>：folia 用 {@code Math.random()}
 * 生成形状与粒子 —— {@code useMemo} 的依赖虽然是 seed，内部却没有用它，所以同一个 seed 在不同
 * 会话里布局也不同，且无法逐像素复现。这里换成固定种子的 {@link Random}，<b>但每次抽取的顺序与
 * 原版完全一致</b>（iconSlot 判定 → iconSlot 值 → type → initialX → initialY → size → duration →
 * delay → opacity → reverse → filled → rotation），所以参数分布、平均形状占比都与参考实现同构，
 * 只是可复现。以后不要把这里「修」回非确定性。</p>
 *
 * <p><b>图标槽</b>：原版最多 6 个形状会被 {@code theme.lyricsIcons} 的某个图标顶替，并改用
 * 20..40 s 的时长与 0→peak→0 的脉冲（{@code --geometric-peak = opacity*3}，同时用 vocal 频段
 * 缩放）。本移植没有主题图标表，等价于原版 {@code theme.lyricsIcons} 为空：{@code getShapeIcon}
 * 返回 null、形状面照常显示。因此这里仍然按原顺序抽取
 * iconSlot（让后续随机数序列一致），只把 duration 换成图标档 20..40 s，不画图标与脉冲。</p>
 *
 * <p><b>音频</b>：形状类型固定绑定频段 —— circle=bass、square=lowMid、triangle=mid、
 * cross=treble（上游 {@code getShapeScaleKey}）；vocal 只给图标槽用，这里无图标所以不参与。
 * 原版用 spring（stiffness 300 / damping 30，ζ≈.866）把 10..200 映射到 .95..1.45，上升沿约
 * 0.1 s、0.5 s 内稳定并带轻微过冲；这里用时间常数 0.115 s 的一阶平滑近似（同样的跟随速度、
 * 无过冲），再走同一映射。粒子、流体与暗角都不响应音频。</p>
 *
 * <p>Java 8 语法（项目源码按 Java 8 兼容书写，构建目标仍是 Java 25）。</p>
 */
public final class FoliaCommonBackground {

    /** 形状数量（上游 15 个）。 */
    public static final int SHAPE_COUNT = 15;
    /** 光点数量（上游 20 颗）。 */
    public static final int PARTICLE_COUNT = 20;
    /** 最多多少个形状带图标槽（上游 iconSlots < 6）。 */
    private static final int MAX_ICON_SLOTS = 6;

    /**
     * 默认布局种子。
     *
     * <p>folia 每次挂载都重新 {@code Math.random()}，没有可复现的布局；这里给一个固定值，
     * 让「同一首歌同一套形状」成为默认行为。</p>
     */
    public static final long DEFAULT_SEED = 0x5EEDC0DEL;

    /** 频段映射端点（folia {@code useTransform(spring, [10, 200], [.95, 1.45])}）。 */
    private static final float BAND_MIN = 10f;
    private static final float BAND_MAX = 200f;
    private static final float SCALE_MIN = 0.95f;
    private static final float SCALE_MAX = 1.45f;

    /**
     * 一阶平滑的时间常数。
     *
     * <p>spring 300/30 的 63% 上升时间约 115 ms（ζ=30/(2√300)=.866）；用同时间常数的一阶
     * 低通复现同样的跟随速度，省掉微分方程与过冲。</p>
     */
    private static final float BAND_TAU_SECONDS = 0.115f;

    /** 封面层下的主题底色 alpha（上游取 .8）。 */
    private static final float COVER_BASE_ALPHA = 0.8f;
    /** 模糊半径：原版在 320 宽缓冲上按 40*1.5*320/cssWidth 烘，画布即元素时等价 60px。 */
    private static final float COVER_BLUR_PX = 60f;
    /** 对角渐变 overlay 的 alpha（上游取 .4）。 */
    private static final float COVER_GRADIENT_OPACITY = 0.4f;
    /** 封面 object-cover 之后再放大的倍数（上游取 1.5）。 */
    private static final float COVER_SCALE = 1.5f;

    private final Shape[] shapes = new Shape[SHAPE_COUNT];
    private final Particle[] particles = new Particle[PARTICLE_COUNT];
    /** 五路频段（bass/lowMid/mid/vocal/treble）的平滑值，0..255。 */
    private final float[] smoothedBands = new float[5];
    private double lastClock = Double.NaN;

    /** 一个形状的全部生成参数（folia {@code BackgroundShape} 的字段，顺序相同）。 */
    private static final class Shape {
        int type;
        /** 图标槽份额 0..1；{@code <0} 表示没有图标槽（上游的 null）。 */
        float iconSlot;
        float initialX;
        float initialY;
        float size;
        float duration;
        float delay;
        float opacity;
        boolean reverse;
        boolean filled;
        float rotation;
    }

    /** 一颗上升光点的全部生成参数。 */
    private static final class Particle {
        float size;
        float left;
        float top;
        float opacity;
        float duration;
        float delay;
    }

    /** 默认布局（固定 {@link #DEFAULT_SEED}）。 */
    public FoliaCommonBackground() {
        this(DEFAULT_SEED);
    }

    /**
     * 指定布局种子。相同 seed 的实例在同一 clock 上给出逐像素相同的画面。
     *
     * <p>种子只影响 15 个形状与 20 颗光点的参数，不影响流体/遮罩/暗角。</p>
     */
    public FoliaCommonBackground(long seed) {
        generate(seed);
    }

    /** 按参考实现的抽取顺序生成两套布局（见类注释「随机数的差异」）。 */
    private void generate(long seed) {
        Random random = new Random(seed);
        int iconSlots = 0;
        for (int index = 0; index < SHAPE_COUNT; index++) {
            Shape shape = new Shape();
            // 抽取式：iconSlot = iconSlots < 6 && Math.random() > 0.7 ? Math.random() : null
            // —— 判定每次都抽，命中时再抽一次份额，所以下面所有参数的下标都要照抄这个顺序。
            if (iconSlots < MAX_ICON_SLOTS && random.nextDouble() > 0.7) {
                shape.iconSlot = (float) random.nextDouble();
                iconSlots++;
            } else {
                shape.iconSlot = -1f;
            }
            // Math.random() 返回 double，所以这里每个字段正好一次 nextDouble()：
            // 抽样次数与参考实现逐次对应，只有「种子来源」不同。
            shape.type = (int) (random.nextDouble() * 4.0);
            shape.initialX = (float) (random.nextDouble() * 100.0);
            shape.initialY = (float) (random.nextDouble() * 100.0);
            shape.size = (float) (40.0 + random.nextDouble() * 100.0);
            shape.duration = shape.iconSlot >= 0f
                    ? (float) (20.0 + random.nextDouble() * 20.0)
                    : (float) (30.0 + random.nextDouble() * 30.0);
            shape.delay = (float) (random.nextDouble() * 5.0);
            shape.opacity = (float) (0.11 + random.nextDouble() * 0.08);
            shape.reverse = random.nextDouble() > 0.5;
            shape.filled = random.nextDouble() < 0.3;
            shape.rotation = (float) (random.nextDouble() * 360.0);
            shapes[index] = shape;
        }
        for (int index = 0; index < PARTICLE_COUNT; index++) {
            Particle particle = new Particle();
            particle.size = (float) (random.nextDouble() * 4.0 + 1.0);
            particle.left = (float) (random.nextDouble() * 100.0);
            particle.top = (float) (random.nextDouble() * 100.0);
            particle.opacity = (float) (random.nextDouble() * 0.3);
            particle.duration = (float) (15.0 + random.nextDouble() * 20.0);
            particle.delay = (float) (random.nextDouble() * 10.0);
            particles[index] = particle;
        }
    }

    /**
     * 画一帧。
     *
     * @param clock       已累加的秒数；{@code staticMode} 时由调用方停住
     * @param bands       五路频段 0..255（bass/lowMid/mid/vocal/treble），可为 null（视为静音）
     * @param coverShader 封面子着色器，可为 null（此时不画流体层）
     */
    public void render(Canvas canvas, float width, float height, FoliaTheme theme, float alpha,
                       double clock, float[] bands, Shader coverShader) {
        if (canvas == null || theme == null || width < 1f || height < 1f || alpha <= .004f) return;
        updateBands(bands, clock);
        float opacity = FoliaTheme.clamp(alpha, 0f, 1f);
        if (opacity >= 0.999f) {
            drawFrame(canvas, width, height, theme, clock, coverShader);
            return;
        }
        // CSS 的 opacity 是组透明度（先合成整层再乘 alpha），不是每个图元各乘一次 ——
        // 封面层 + 遮罩叠在一起时这两者结果不同，所以这里用 saveLayerAlpha 整层收口。
        int layer = canvas.saveLayerAlpha(Rect.makeXYWH(0f, 0f, width, height),
                Math.round(opacity * 255f));
        try {
            drawFrame(canvas, width, height, theme, clock, coverShader);
        } finally {
            canvas.restoreToCount(layer);
        }
    }

    /** 画不透明的整层；整体透明度由 {@link #render} 的图层统一施加。 */
    private void drawFrame(Canvas canvas, float width, float height, FoliaTheme theme,
                           double clock, Shader coverShader) {
        boolean useCover = FoliaBackgroundConfig.commonUseCoverColorBg && coverShader != null;
        if (useCover) {
            // 封面层下面先垫一层 theme.backgroundColor、opacity .8，盖住解码前的空白。
            fill(canvas, width, height, theme.background, COVER_BASE_ALPHA);
            drawFluid(canvas, width, height, theme, coverShader);
        }
        // 封面层之上的纯色遮罩：启用封面时用 common.opacity，否则整屏不透明底色。
        fill(canvas, width, height, theme.background,
                useCover ? FoliaBackgroundConfig.commonCoverOpacity : 1f);

        if (!FoliaBackgroundConfig.staticMode) {
            drawShapes(canvas, width, height, theme, clock);
            drawParticles(canvas, width, height, theme, clock);
            if (!FoliaBackgroundConfig.commonDisableVignette) {
                drawVignette(canvas, width, height);
            }
        }
    }

    // ------------------------------------------------------------------
    // 封面层
    // ------------------------------------------------------------------

    private void drawFluid(Canvas canvas, float width, float height, FoliaTheme theme,
                           Shader coverShader) {
        RuntimeEffect effect = FoliaSkSL.effect(FoliaSkSL.FLUID);
        if (effect == null) return;
        Shader shaded = null;
        try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(effect)) {
            builder.setUniform("resolution", width, height);
            builder.setUniform("alpha", 1f);
            builder.setUniform("blurPx", COVER_BLUR_PX);
            builder.setUniform("gradientOpacity", COVER_GRADIENT_OPACITY);
            builder.setUniform("primaryColor",
                    FoliaTheme.r(theme.primary), FoliaTheme.g(theme.primary), FoliaTheme.b(theme.primary));
            builder.setUniform("secondaryColor",
                    FoliaTheme.r(theme.secondary), FoliaTheme.g(theme.secondary), FoliaTheme.b(theme.secondary));
            builder.setUniform("backgroundColor",
                    FoliaTheme.r(theme.background), FoliaTheme.g(theme.background), FoliaTheme.b(theme.background));
            builder.setUniform("hasCover", 1f);
            builder.setUniform("coverScale", COVER_SCALE);
            builder.setChild("cover", coverShader);
            shaded = builder.makeShader();
            try (Paint paint = new Paint()) {
                paint.setShader(shaded);
                canvas.drawRect(Rect.makeXYWH(0f, 0f, width, height), paint);
            }
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/FoliaBg] Common 流体绘制失败: " + failure);
        } finally {
            if (shaded != null) shaded.close();
        }
    }

    // ------------------------------------------------------------------
    // 几何层
    // ------------------------------------------------------------------

    private void drawShapes(Canvas canvas, float width, float height, FoliaTheme theme,
                            double clock) {
        if (FoliaBackgroundConfig.commonDisableGeometric) return;
        // 一帧 15 个形状共用一支 Paint（每帧都 new 会给渲染线程添无谓的 GC 压力）。
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            for (int index = 0; index < SHAPE_COUNT; index++) {
                Shape shape = shapes[index];
                float phase = phaseAt(clock, shape.delay, shape.duration);
                float direction = shape.reverse ? -1f : 1f;
                // CSS geometric-drift：0% (-15,30) → 50% (15,-30) → 100% (-15,30)，linear。
                float travel = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
                float driftX = (travel * 30f - 15f) * direction;
                float driftY = (30f - travel * 60f) * direction;
                // 上游的盒子 left/top 是百分比、宽高是 px；盒的左上角落在 (initialX%, initialY%)，
                // 没有居中偏移，所以贴边的形状会被裁掉一部分（规格 §6.4）。
                float left = shape.initialX / 100f * width + driftX;
                float top = shape.initialY / 100f * height + driftY;
                float angle = shape.rotation + phase * 360f;
                float scale = bandScale(shape.type);

                paint.setMode(PaintMode.FILL);
                paint.setStrokeWidth(1f);
                paint.setColor(FoliaTheme.argb(theme.secondary, shape.opacity));

                int save = canvas.save();
                try {
                    // 漂移容器的 transform-origin 与面的 scale 中心都是盒中心（CSS 默认 50% 50%），
                    // 所以先移到盒中心、再旋转、再缩放，形状按基础尺寸画。
                    canvas.translate(left + shape.size * 0.5f, top + shape.size * 0.5f);
                    canvas.rotate(angle);
                    canvas.scale(scale, scale);
                    drawShapeFace(canvas, paint, shape, shape.size);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        }
    }

    /**
     * 形状面（folia {@code getShapeFaceStyle}）：
     * circle/square 未填充时是 1px {@code secondary} 描边，填充时实心；
     * triangle/cross 不论 {@code filled} 取值都实心，并用 clip-path 多边形裁形。
     */
    private static void drawShapeFace(Canvas canvas, Paint paint, Shape shape, float size) {
        // CSS 的 border 画在盒内侧（Tailwind preflight 的 border-box），所以描边圆/方要内缩半个线宽，
        // 否则 Skia 的描边会有一半落到盒外，整体比原版大 1px。
        float inset = size * 0.5f - 0.5f;
        if (shape.type == 0) {
            if (shape.filled) {
                canvas.drawCircle(0f, 0f, size * 0.5f, paint);
            } else {
                paint.setMode(PaintMode.STROKE);
                paint.setStrokeWidth(1f);
                canvas.drawCircle(0f, 0f, inset, paint);
            }
            return;
        }
        if (shape.type == 1) {
            if (shape.filled) {
                canvas.drawRect(Rect.makeXYWH(-size * 0.5f, -size * 0.5f, size, size), paint);
            } else {
                paint.setMode(PaintMode.STROKE);
                paint.setStrokeWidth(1f);
                canvas.drawRect(Rect.makeXYWH(-inset, -inset, inset * 2f, inset * 2f), paint);
            }
            return;
        }
        if (shape.type == 2) {
            // CSS clip-path: polygon(50% 0%, 0% 100%, 100% 100%)。
            Path path = Path.makePolygon(new Point[]{
                    new Point(0f, -size * 0.5f),
                    new Point(-size * 0.5f, size * 0.5f),
                    new Point(size * 0.5f, size * 0.5f)}, true);
            canvas.drawPath(path, paint);
            path.close();
            return;
        }
        // cross：上游的 12 点星形 clip-path，逐点按百分比换算到形状盒。
        float[] points = {
                0.20f, 0.00f, 0.00f, 0.20f, 0.30f, 0.50f, 0.00f, 0.80f,
                0.20f, 1.00f, 0.50f, 0.70f, 0.80f, 1.00f, 1.00f, 0.80f,
                0.70f, 0.50f, 1.00f, 0.20f, 0.80f, 0.00f, 0.50f, 0.30f};
        Point[] polygon = new Point[12];
        for (int i = 0; i < 12; i++) {
            polygon[i] = new Point((points[i * 2] - 0.5f) * size,
                    (points[i * 2 + 1] - 0.5f) * size);
        }
        Path path = Path.makePolygon(polygon, true);
        canvas.drawPath(path, paint);
        path.close();
    }

    private void drawParticles(Canvas canvas, float width, float height, FoliaTheme theme,
                               double clock) {
        if (FoliaBackgroundConfig.commonDisableGeometric) return;
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            for (int index = 0; index < PARTICLE_COUNT; index++) {
                Particle particle = particles[index];
                float phase = phaseAt(clock, particle.delay, particle.duration);
                // CSS geometric-rise：translateY 0 → -100px；透明度 0 → peak → 0（两端为 0）。
                // 原版关键帧是线性的三角波，这里用同端点、同峰位的 sin 包络，淡入淡出更贴近
                // 「上升时渐亮、到顶再渐隐」的观感（任务书指定的 sin fade）。
                float rise = phase * 100f;
                float fade = (float) Math.sin(Math.PI * phase) * particle.opacity;
                if (fade <= 0.002f) continue;
                paint.setColor(FoliaTheme.argb(theme.accent, fade));
                // CSS: left/top 是盒的左上角，圆点直径 = size，所以圆心要加半个 size。
                canvas.drawCircle(particle.left / 100f * width + particle.size * 0.5f,
                        particle.top / 100f * height + particle.size * 0.5f - rise,
                        particle.size * 0.5f, paint);
            }
        }
    }

    private void drawVignette(Canvas canvas, float width, float height) {
        // radial-gradient(circle, transparent 40%, rgba(0,0,0,.6) 100%)：circle 的默认尺寸是
        // farthest-corner，所以半径 = 中心到最远角 = hypot(w,h)/2；0..40% 之间第一档是纯透明。
        float radius = (float) Math.hypot(width, height) * 0.5f;
        try (Shader shader = Shader.makeRadialGradient(width * 0.5f, height * 0.5f, radius,
                new int[]{0x00000000, FoliaTheme.argb(0x000000, 0.6f)},
                new float[]{0.4f, 1f})) {
            try (Paint paint = new Paint()) {
                paint.setShader(shader);
                canvas.drawRect(Rect.makeXYWH(0f, 0f, width, height), paint);
            }
        }
    }

    // ------------------------------------------------------------------
    // 相位 / 音频 / 小工具
    // ------------------------------------------------------------------

    /**
     * CSS 关键帧相位：正 delay 期间保持 0% 帧（{@code animation-fill-mode: backwards}），
     * 之后按 duration 循环。
     */
    private static float phaseAt(double clock, float delay, float duration) {
        double elapsed = clock - delay;
        if (elapsed <= 0.0) return 0f;
        double cycles = elapsed / Math.max(1.0, duration);
        return (float) (cycles - Math.floor(cycles));
    }

    /**
     * 频段一阶平滑。第一帧直接取输入值（对应 spring 的初始值 = 当前值），
     * 之后按 dt 换算的指数系数逼近；clock 不前进（staticMode）时完全冻结。
     */
    private void updateBands(float[] bands, double clock) {
        if (Double.isNaN(lastClock)) {
            lastClock = clock;
            for (int index = 0; index < 5; index++) {
                smoothedBands[index] = bandAt(bands, index);
            }
            return;
        }
        double elapsed = clock - lastClock;
        lastClock = clock;
        if (elapsed <= 0.0) return;
        float delta = (float) Math.min(0.05, elapsed);
        float amount = (float) (1.0 - Math.exp(-delta / BAND_TAU_SECONDS));
        for (int index = 0; index < 5; index++) {
            smoothedBands[index] += (bandAt(bands, index) - smoothedBands[index]) * amount;
        }
    }

    private static float bandAt(float[] bands, int index) {
        if (bands == null || bands.length <= index) return 0f;
        return FoliaTheme.clamp(bands[index], 0f, 255f);
    }

    /** circle=bass、square=lowMid、triangle=mid、cross=treble（folia getShapeScaleKey）。 */
    private float bandScale(int type) {
        int band = type == 0 ? 0 : (type == 1 ? 1 : (type == 2 ? 2 : 4));
        float amount = FoliaTheme.clamp(
                (smoothedBands[band] - BAND_MIN) / (BAND_MAX - BAND_MIN), 0f, 1f);
        return SCALE_MIN + amount * (SCALE_MAX - SCALE_MIN);
    }

    private static void fill(Canvas canvas, float width, float height, int rgb, float alpha) {
        if (alpha <= .004f) return;
        try (Paint paint = new Paint()) {
            paint.setColor(FoliaTheme.argb(rgb, alpha));
            canvas.drawRect(Rect.makeXYWH(0f, 0f, width, height), paint);
        }
    }

    // ------------------------------------------------------------------
    // 测试探针（包内可见，不扩大公开 API）
    // ------------------------------------------------------------------

    /**
     * 第 {@code index} 个形状的
     * {type, iconSlot, initialX, initialY, size, duration, delay, opacity, rotation}。
     * {@code iconSlot} 为 -1 表示没有图标槽。
     */
    float[] shapeParameters(int index) {
        Shape shape = shapes[index];
        return new float[]{shape.type, shape.iconSlot, shape.initialX, shape.initialY,
                shape.size, shape.duration, shape.delay, shape.opacity, shape.rotation};
    }

    /** 第 {@code index} 颗光点的 {size, left, top, opacity, duration, delay}。 */
    float[] particleParameters(int index) {
        Particle particle = particles[index];
        return new float[]{particle.size, particle.left, particle.top,
                particle.opacity, particle.duration, particle.delay};
    }
}

