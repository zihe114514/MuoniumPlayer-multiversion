package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;

import java.awt.Color;
/**
 * 播放器主界面 从 灵动岛 展开 / 收回 的整屏转场。
 *
 * <p>这条转场是时间驱动的：只有一个 {@code t ∈ [0,1]} 的时钟，几何和透明度都是
 * {@code t} 的纯函数。这样中途反向是连续的 —— 进场没播完就按 ESC，界面会从当前位置原路折回，
 * 不会因为退场换了一套曲线而跳一下。</p>
 *
 * <p>分层节奏：先变形表面，再让内容浮起，最后用边界光交代落定。收起时同一组曲线反序播放，
 * 因此“回到灵动岛”和“从灵动岛展开”是同一条路径的两个方向。</p>
 */
public final class PlayerIslandTransition implements SharedRenderingConstants, SharedConstants {

    /** 形状绘制是接口上的 default 方法，静态调用需要一个实例；除此之外这个对象没有状态。 */
    private static final PlayerIslandTransition GFX = new PlayerIslandTransition();

    private PlayerIslandTransition() {
    }

    private static final int IDLE = 0, OPENING = 1, OPEN = 2, CLOSING = 3;

    /**
     * 默认 3x 下约为 293/217ms。几何曲线本身已经有 emphasized 减速；
     * 收起末段的胶囊交接和撞击反馈另有更短的专用节奏。
     */
    private static final double OPEN_MS = 880.0;
    private static final double CLOSE_MS = 650.0;

    private static final TransitionCurveCache.CachedBezier MOTION = TransitionCurveCache.MOTION;
    private static final TransitionCurveCache.CachedBezier MOTION_LAGGING = TransitionCurveCache.MOTION_LAGGING;

    private static int state = IDLE;

    /** 1 = 完全展开。全部曲线都读它，一帧只在 {@link #tick()} 里推进一次。 */
    private static double t = 0.0;
    /** 平滑的灵动岛 → 胶囊交接；在关闭被中途逆转时保留。 */
    private static double capsuleHandoffMix = 0.0;

    /**
     * 落定材质交接窗，毫秒。
     *
     * <p>转场期间播放器表面贴的是「预糊背景贴图」（廉价、无折射），透镜液态玻璃整面关闭；
     * 动画一结束透镜立刻接管。两种材质对同一背景的亮度与边缘折射不同，瞬间切换读起来就是
     * 「落定那一下闪/顿」。这个窗口让表面落定后两种材质交叉淡变：贴图淡出、透镜按同一比例
     * 淡入（{@link #surfaceHandoff()}）。打开放完与关闭落定（CLOSING→IDLE 的撞击交给岛，
     * 表面消失）各自适用；中途反向不重置窗口，混合是双向连续的。</p>
     */
    private static final double SURFACE_HANDOFF_MS = 180.0;

    /**
     * 材质交接的进度 0..1：0 = 仍然按转场贴图画表面，1 = 已完全交给常规玻璃路径。
     * 只在 {@link #tick()} 推进（消费与 {@code tick} 相同的钳后 deltaMs），渲染侧只读。
     */
    private static double surfaceHandoff = 1.0;

    /** 上一次推进 {@link #surfaceHandoff} 的时刻；0 = 窗口已走完或尚未开始。 */
    private static long handoffLastNanos;
    /**
     * 过渡专用的单调时钟。共享的帧增量助手对长帧设上限，且也
     * 被 HUD/音乐渲染共享；当播放把帧率降到 25fps 以下时，旧的 40ms
     * 上限使该形变在墙上时钟上更久，即使其配置速度未变。
     */
    private static long lastTickNanos;

    /**
     * 单帧最多推进的动画时间，毫秒。
     *
     * <p>这条转场是用户盯着看的开场动画，{@code t} 一次跳太多读起来就是「跳过了动画」：
     * 旧上限 250ms 下，一次 200ms 的卡顿一帧吃掉 880ms 展开的 23%，连续几帧重活整个动画
     * 4 帧放完。50ms ≈ 20fps 的帧间距，正常掉帧（含一次 GC、一次区块加载）完全不触顶、
     * 保持实时步调；只有真正的长冻结（切标签页、调试器暂停）才会被按住，代价只是动画
     * 尾巴在墙上时钟上多走几十毫秒 —— 拉长永远好过跳过。持续低于 20fps 的机器动画会
     * 按比例慢放（15fps 约 1.32 倍、10fps 约 2 倍墙钟时长），这是刻意的：连续性比时长
     * 更重要，动画期间真正的降载是几何上的（内容 alpha 门槛、交接窗跳过整树），不是
     * 档位上的。</p>
     */
    private static final double MAX_STEP_MS = 50.0;

    private static float speed = 1.0f;

    private static double startX, startY, startWidth, startHeight, startRadius;
    /** 打开跟随常驻灵动岛；关闭最终必须落到紧凑胶囊。 */
    private static double capsuleX, capsuleY, capsuleWidth, capsuleHeight, capsuleRadius;
    private static double targetX, targetY, targetWidth, targetHeight, targetRadius;

    /** 本帧几何缓存。tick 后所有调用方复用同一份结果，避免重复解曲线和分配数组。 */
    private static final double[] surfaceBoundsCache = new double[5];
    private static double cachedContentAlpha = 0.0;
    private static double cachedContentScale = 1.0;
    private static double cachedSettlePulse = 0.0;

    /** 每个过渡帧使用的不可变颜色；逐帧分配这些只会增加 GC 压力。 */
    private static final Color SHADOW_STRONG = new Color(0, 0, 0, 48);
    private static final Color SHADOW_SOFT = new Color(0, 0, 0, 20);
    private static final Color SURFACE_EDGE = new Color(255, 255, 255, 38);
    private static final Color TRANSPARENT_WHITE = new Color(255, 255, 255, 0);
    private static final int COLOR_STEPS = 64;
    private static final Color[] WHITE_ALPHA_COLORS = new Color[256];
    private static final Color[] SURFACE_COLOR_CACHE = new Color[COLOR_STEPS + 1];
    private static final Color[] ACCENT_ALPHA_CACHE = new Color[256];
    private static int cachedIslandSurfaceColor = Integer.MIN_VALUE;
    private static int cachedPlayerSurfaceColor = Integer.MIN_VALUE;
    private static int cachedAccentColor = Integer.MIN_VALUE;

    static {
        for (int alpha = 0; alpha < WHITE_ALPHA_COLORS.length; alpha++) {
            WHITE_ALPHA_COLORS[alpha] = new Color(255, 255, 255, alpha);
        }
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 从当前灵动岛锚点开始一次展开。
     *
     * <p>如果上一轮收起还没播完就重新打开，状态会直接从 CLOSING 翻回 OPENING，
     * {@code t} 保留当前值 —— 也就是说界面会从当前位置继续展开，而不是跳回岛里重播。</p>
     */
    public static void open(float speedMultiplier) {
        speed = clampSpeed(speedMultiplier);
        // 只有全新打开才从岛开始。关闭期间重新打开必须保留当前
        //几何；重新捕获陈旧锚点会使表面横向跳变或改变大小。
        if (state == IDLE) {
            captureStart();
            captureCapsule();
            captureSurfacePalette();
            capsuleHandoffMix = 0.0;
            surfaceHandoff = 0.0;
            handoffLastNanos = 0L;
        }
        lastTickNanos = System.nanoTime();
        if (state == OPEN) {
            DownloadDynamicIsland.finishPlayerSurfaceTransition();
        } else {
            DownloadDynamicIsland.beginPlayerSurfaceTransition();
        }
        captureTarget();

        if (state == IDLE) {
            state = OPENING;
            t = 0.0;
        } else if (state == CLOSING) {
            state = OPENING;
        }
        refreshFrameCache();
    }

    /** 开始收回。已经完全打开时才有效；正在展开时按下 ESC 会立刻反向。 */
    public static void close() {
        if (state == OPENING || state == OPEN) {
            // 常驻岛可被展开，但 Apple 的交接落到紧凑胶囊。
            // 现在捕获该目标；tick() 淡入它而不改变当前帧。
            captureCapsule();
            captureSurfacePalette();
            lastTickNanos = System.nanoTime();
            DownloadDynamicIsland.preparePlayerAbsorb();
            state = CLOSING;
        }
    }

    /** 外部直接换屏时使用：立刻复位，并让真实灵动岛恢复显示。 */
    public static void abort() {
        // 正常关闭在 NCMScreen#onGuiClosed 被调用前到达 IDLE。此时
        //真实岛刚收到吸收脉冲，必须保留它；在此中止会
        //取消冲击，使表面看起来像戛然而止。
        if (state == IDLE && t == 0.0) {
            refreshFrameCache();
            return;
        }
        DownloadDynamicIsland.cancelPlayerSurfaceTransition();
        state = IDLE;
        t = 0.0;
        capsuleHandoffMix = 0.0;
        surfaceHandoff = 0.0;
        handoffLastNanos = 0L;
        lastTickNanos = 0L;
        refreshFrameCache();
    }

    /** 每帧推进一次时钟。必须在读取任何曲线之前调用。 */
    public static void tick() {
        captureTarget();

        long now = System.nanoTime();
        double deltaMs = lastTickNanos == 0L ? 0.0 : (now - lastTickNanos) / 1_000_000.0;
        lastTickNanos = now;
        // 在掉帧期间保持实时步调。上限压到 50ms：这条转场是用户盯着看的动画，
        // t 一帧跳 23% 就是「跳过了动画」；真冻结（切标签页）宁可尾巴拉长也不跳。
        // 音乐引起的正常卡顿（一次 GC、一次区块加载）远触不到这条线。
        deltaMs = Math.min(MAX_STEP_MS, Math.max(0.0, deltaMs));

        // 完全打开后，普通面板拥有渲染权。仅刷新四个缓存输出；
        // 这样可避免在每个空闲的玩家帧上重新求解两条贝塞尔曲线并重建脉冲。
        if (state == OPEN) {
            // 屏幕调整大小/初始化可能在播放器已打开时再次调用 open()。完成任何
            // 重新武装的表面过渡，使通知不会被无限期阻塞。
            DownloadDynamicIsland.finishPlayerSurfaceTransition();
            surfaceBoundsCache[0] = targetX;
            surfaceBoundsCache[1] = targetY;
            surfaceBoundsCache[2] = targetWidth;
            surfaceBoundsCache[3] = targetHeight;
            surfaceBoundsCache[4] = targetRadius;
            cachedContentAlpha = 1.0;
            cachedContentScale = 1.0;
            cachedSettlePulse = 0.0;
            // 落定后的材质交接窗在 OPEN 态继续走完（180ms）：透镜淡入的最后一段
            // 发生在面板完全展开之后，不能因为 OPEN 态提前 return 而冻结在半途。
            advanceSurfaceHandoff(deltaMs);
            return;
        }

        if (state == OPENING) {
            t = Math.min(1.0, t + deltaMs / openDuration());
            if (t >= 1.0) {
                t = 1.0;
                state = OPEN;
                DownloadDynamicIsland.finishPlayerSurfaceTransition();
            }
        } else if (state == CLOSING) {
            t = Math.max(0.0, t - deltaMs / closeDuration());
            if (t <= 0.0) {
                t = 0.0;
                state = IDLE;
                capsuleHandoffMix = 1.0;
                DownloadDynamicIsland.beginPlayerAbsorbImpact();
            }
        }

        // 材质交接窗：表面落定（展开到 1）后开始走，转场动画期间反向回卷到 0 ——
        // 正在形变的表面必须按贴图路径画，透镜只在停稳后逐帧淡入。
        advanceSurfaceHandoff(deltaMs);

        // 保持锚点变化基于时间。中途逆转关闭绝不能在一帧内把原点
        //从胶囊猛地拉回更大的常驻岛。
        if (state == OPENING || state == CLOSING) {
            // 在主折叠内启动紧凑胶囊交接，而非等待一个
            //单独的最终阶段。逆转仍平滑穿过这个保留的混合。
            double capsuleTarget = state == CLOSING ? MOTION.at(sub(1.0 - t, .32, .74)) : 0.0;
            capsuleHandoffMix += (capsuleTarget - capsuleHandoffMix) * Math.min(1.0, deltaMs / 45.0);
        }
        refreshFrameCache();
    }

    /**
     * 推进落定材质交接：0 = 转场贴图，1 = 常规玻璃路径完全接管。
     *
     * <p>窗口只在「表面已落定」（{@code t >= 1}）后向前走；转场重新开始（OPENING/CLOSING
     * 且 {@code t < 1}）时按同一时间常数回卷 —— 关闭被中途逆转、或者落定后立刻再关，
     * 表面都在移动，必须整体回到贴图路径，不允许带着半透明透镜叠画。</p>
     */
    private static void advanceSurfaceHandoff(double deltaMs) {
        boolean settled = t >= 1.0;
        if (settled) {
            if (surfaceHandoff < 1.0) {
                if (handoffLastNanos == 0L) {
                    handoffLastNanos = System.nanoTime();
                }
                double step = (System.nanoTime() - handoffLastNanos) / 1_000_000.0;
                handoffLastNanos = System.nanoTime();
                surfaceHandoff = Math.min(1.0,
                        surfaceHandoff + Math.min(step, MAX_STEP_MS) / SURFACE_HANDOFF_MS);
            } else {
                handoffLastNanos = 0L;
            }
        } else if (surfaceHandoff > 0.0) {
            surfaceHandoff = Math.max(0.0, surfaceHandoff - deltaMs / SURFACE_HANDOFF_MS);
            handoffLastNanos = 0L;
        }
    }

    /**
     * 本帧表面该按多大比例交给常规玻璃路径（0..1）。
     *
     * <p>0 = 仍按转场贴图画（形变中或刚落定）；1 = 常规透镜完全接管。消费方：
     * {@code GlassLook#paintPlayerTransitionSurface} 用它给预糊贴图乘淡出系数，
     * {@code NCMScreen} 的透镜表面控件用它给液态玻璃乘淡入系数 —— 两个系数相加为 1，
     * 材质切换在视觉上是一条连续的交叉淡化，而不是落定帧的一次跳变。</p>
     */
    public static double surfaceHandoff() {
        return surfaceHandoff;
    }

    public static boolean isRunning() {
        return state == OPENING || state == CLOSING;
    }

    /**
     * 播放器转场此刻是否握着灵动岛这块表面。
     *
     * <p>不止 OPENING/CLOSING 两个动画态：{@code state != IDLE} 连完全展开（OPEN）也算 ——
     * 播放器开着的时候，第二个想从岛上长出来的表面（灵动岛搜索栏）不能去抢
     * {@code beginPlayerSurfaceTransition()}，否则两条转场互相归零对方的动画状态；
     * 收尾由 {@code NCMScreen.closing} 补上（ESC 到摘屏之间的整个窗口）。</p>
     */
    public static boolean isBusy() {
        return state != IDLE || NCMScreen.getInstance().closing;
    }

    public static boolean isOpening() {
        return state == OPENING;
    }

    public static boolean isClosing() {
        return state == CLOSING;
    }

    public static boolean isFinished() {
        return state == IDLE;
    }

    // ------------------------------------------------------------------ 分层不透明度

    /** 播放器内容。比表面晚一档：先把黑色玻璃撑开，内容再在暗底上浮起来。 */
    public static double contentAlpha() {
        return cachedContentAlpha;
    }
    // ------------------------------------------------------------------ 几何

    /** 当前表面矩形：x、y、width、height、radius。 */
    public static double[] surfaceBounds() {
        return surfaceBoundsCache;
    }

    private static void refreshFrameCache() {
        double fx = MOTION_LAGGING.at(t);
        double fy = MOTION.at(t);
        double fw = fy;
        double fh = fx;

        // 关闭刻意不对称：它沿灵动岛路径往回走，然后
        //最后一段把表面交给紧凑胶囊。重新打开逆转这个混合。
        double capsuleMix = Math.max(0.0, Math.min(1.0, capsuleHandoffMix));
        double originX = lerp(startX, capsuleX, capsuleMix);
        double originY = lerp(startY, capsuleY, capsuleMix);
        double originWidth = lerp(startWidth, capsuleWidth, capsuleMix);
        double originHeight = lerp(startHeight, capsuleHeight, capsuleMix);
        double originRadius = lerp(startRadius, capsuleRadius, capsuleMix);

        double bulge = 1.0 + Math.sin(Math.PI * t) * Math.pow(t, 1.7) * .048;
        double width = lerp(originWidth, targetWidth, fw) * bulge;
        double height = lerp(originHeight, targetHeight, fh) * bulge;
        double x = lerp(originX + originWidth * .5, targetX + targetWidth * .5, fx) - width * .5;
        double y = lerp(originY + originHeight * .5, targetY + targetHeight * .5, fy) - height * .5;
        double radius = lerp(originRadius, targetRadius, fy);

        // 打开没有二次回弹：强调曲线简单地落到确切目标。
        // 只有关闭在真实岛接管前保留一段冲击前压缩。
        double landing = settlePulseRaw();
        if (state == CLOSING) {
            width *= 1.0 + landing * .080;
            height *= 1.0 - landing * .105;
            y += landing * 1.20;
            radius += landing * .80;
        }

        surfaceBoundsCache[0] = x;
        surfaceBoundsCache[1] = y;
        surfaceBoundsCache[2] = width;
        surfaceBoundsCache[3] = height;
        surfaceBoundsCache[4] = radius;
        cachedContentAlpha = MOTION.at(sub(t, .10, .86));
        cachedContentScale = targetWidth <= 1.0 || targetHeight <= 1.0
                ? 1.0
                : Math.max(width / targetWidth, height / targetHeight);
        cachedSettlePulse = landing;
    }
    /**
     * 内容缩放：取宽高比中较大的那个，保证内容永远能盖住当前表面。
     *
     * <p>等价于 CSS 的 object-fit: cover —— 宁可在另一根轴上被裁掉，也不能在黑色玻璃里露底。</p>
     */
    public static double contentScale() {
        return cachedContentScale;
    }

    // ------------------------------------------------------------------ 末尾细节

    /**
     * 末尾落地脉冲。
     *
     * <p>打开时看 t 的最后 26%；关闭时看 1-t 的最后 26%。这样“展开到播放器”和“收回进岛”
     * 两端都有一小段呼吸，而不是只有中段有动感。</p>
     */
    private static double settlePulseRaw() {
        // 打开必须无额外弹跳地恰好落到目标。脉冲仅用于
        //关闭，在那里它预示紧凑岛的冲击。
        if (state != CLOSING) return 0.0;
        return Math.sin(Math.PI * sub(1.0 - t, .74, 1.0));
    }


    /**
     * 绘制形变表面本身，独立于播放器内容 alpha。
     *
     * <p>旧路径只渲染 {@code basePanel}，其 alpha 跟随 {@link #contentAlpha()}。
     * 在打开的前 10% 和关闭的后 10% 该 alpha 为零，因此表面
     * 即使在几何仍在动时也消失。Apple 的岛展开恰好
     * 相反：暗色表面从第一帧就可见，内容稍后才出现。</p>
     *
     * <p>表面落定（{@code t >= 1}）后的材质交接窗里本方法<b>继续</b>画：采用<b>覆盖式交接</b>
     * 而不是交叉淡化 —— 贴图（含阴影、色块）保持全浓度不淡出，它本来就画在播放器透镜
     * （{@code ThemeGlassWidget} / {@code ThemeGlassBackdropWidget}，按 surfaceHandoff 淡入）
     * 的<b>下面</b>，透镜铺上来后自然把它盖住；只有描边按「1 - surfaceHandoff」淡出，
     * 免得交接完成后突然少一圈白边。这样两层叠出的背景覆盖率恒为 1（若交叉淡化，中段会
     * 短暂多透 25% 的原始画面，读作一次轻微闪烁），且 handoff 走完后贴图整层停画、
     * 零残留开销。几何在交接窗内冻结在最终面板矩形上，与透镜逐帧对齐。</p>
     */
    public static void renderSurface() {
        // IDLE：无表面。OPEN：透镜已是权威表面；交接窗内贴图仍在透镜下面垫着，
        // handoff 走完（透镜完全接管）后整层停画，不再支付任何绘制。
        if (state == IDLE) return;
        if (state == OPEN && surfaceHandoff >= 0.999) return;
        double keep = 1.0 - surfaceHandoff;

        double[] bounds = surfaceBounds();
        double x = bounds[0], y = bounds[1], width = bounds[2], height = bounds[3], radius = bounds[4];

        // 全新打开从灵动岛的材质开始；关闭以相同材质到达紧凑胶囊。
        //65 项渐变仅在主题/样式颜色变化时重建。
        double colorMix = MOTION.at(sub(t, .04, .30));
        int colorIndex = (int) Math.round(colorMix * COLOR_STEPS);
        Color color = SURFACE_COLOR_CACHE[Math.max(0, Math.min(COLOR_STEPS, colorIndex))];

        // 双层阴影与后面的扫光都是个位数矩形绘制，微秒级：分档摘掉它们的收益
        // 远小于「所有机器观感不一致」的代价，恒画（fps 分档已整体移除）。
        GFX.roundedRect(x + 1.0, y + 1.8, width, height, radius, SHADOW_STRONG);
        GFX.roundedRect(x + 2.0, y + 3.0, width, height, radius, SHADOW_SOFT);
        boolean liquid = GlassLook.paintPlayerTransitionSurface(x, y, width, height, radius, 1f);
        GFX.roundedRect(x, y, width, height, radius,
                liquid ? new Color(GlassLook.tintedArgb(GlassLook.Surface.PLAYER, color.getRGB()), true) : color);
        if (!liquid) GFX.roundedOutline(x, y, width, height, radius, .72,
                alphaScaled(SURFACE_EDGE, (float) keep));
    }

    /** 返回同色但 alpha 缩放为 {@code scale} 倍的 Color；scale>=0.999 时原样返回。 */
    private static Color alphaScaled(Color color, float scale) {
        if (scale >= 0.999f) return color;
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.max(0, Math.min(255, Math.round(color.getAlpha() * scale))));
    }
    /** 收起末段的边界光：只服务于胶囊撞击前的压缩，不再给打开动画叠加落地回弹。 */
    public static void renderSurfaceDetail() {
        if (state == IDLE || state == OPEN) return;
        double landing = cachedSettlePulse;
        if (landing <= .01) return;

        double[] bounds = surfaceBounds();
        float alpha = (float) landing;
        boolean closing = state == CLOSING;
        int edgeAlpha = closing ? 76 : 52;
        GFX.roundedOutline(bounds[0] - .5, bounds[1] - .5,
                bounds[2] + 1.0, bounds[3] + 1.0, bounds[4],
                .72, whiteAlpha(alpha * edgeAlpha));

        GFX.roundedOutline(bounds[0] - 1.1, bounds[1] - 1.1,
                bounds[2] + 2.2, bounds[3] + 2.2, bounds[4] + .6,
                .60, accentAlpha(alpha * (closing ? .34f : .28f)));

        // 顶部扫光：一条窄带从左到右滑过，收起时反向。
        double sheenHeight = Math.max(1.0, bounds[3] * .18);
        double bandWidth = Math.max(32.0, bounds[2] * .42);
        double bandX = bounds[0] + 1.0 + (bounds[2] - 2.0 - bandWidth) * Math.max(0.0, Math.min(1.0, t));
        Color sheenStart = closing ? whiteAlpha(alpha * 26) : TRANSPARENT_WHITE;
        Color sheenEnd = closing ? TRANSPARENT_WHITE : whiteAlpha(alpha * 26);
        GFX.roundedRectGradientHorizontal(bandX, bounds[1] + 1.0,
                bandWidth, sheenHeight,
                Math.max(1.0, bounds[4] - 1.0), sheenStart, sheenEnd);
    }
    // ------------------------------------------------------------------ 私有工具

    private static void captureStart() {
        double[] island = DownloadDynamicIsland.getLiveAnchorBounds();
        // 岛锚点恒按布局轴上报；islandToCurrent* 把它折算成「本捕获发生时的上下文坐标」，
        // 再由 toLayout* 换回布局轴 —— 两段相乘在括号内/外、开关开/关下都是恒等闭环。
        startX = WindowAdaptiveScale.toLayoutX(WindowAdaptiveScale.islandToCurrentX(island[0]));
        startY = WindowAdaptiveScale.toLayoutY(WindowAdaptiveScale.islandToCurrentY(island[1]));
        startWidth = WindowAdaptiveScale.toLayoutWidth(WindowAdaptiveScale.islandToCurrentWidth(island[2]));
        startHeight = WindowAdaptiveScale.toLayoutHeight(WindowAdaptiveScale.islandToCurrentHeight(island[3]));
        startRadius = WindowAdaptiveScale.toLayoutRadius(WindowAdaptiveScale.islandToCurrentRadius(island[4]));
    }

    private static void captureCapsule() {
        double[] island = DownloadDynamicIsland.getAnchorBounds();
        capsuleX = WindowAdaptiveScale.toLayoutX(WindowAdaptiveScale.islandToCurrentX(island[0]));
        capsuleY = WindowAdaptiveScale.toLayoutY(WindowAdaptiveScale.islandToCurrentY(island[1]));
        capsuleWidth = WindowAdaptiveScale.toLayoutWidth(WindowAdaptiveScale.islandToCurrentWidth(island[2]));
        capsuleHeight = WindowAdaptiveScale.toLayoutHeight(WindowAdaptiveScale.islandToCurrentHeight(island[3]));
        capsuleRadius = WindowAdaptiveScale.toLayoutRadius(WindowAdaptiveScale.islandToCurrentRadius(island[4]));
    }

    /** 仅在源主题/样式颜色变化时重建小型不可变颜色渐变。 */
    private static void captureSurfacePalette() {
        int islandColor = DownloadDynamicIsland.getAnchorSurfaceColor();
        int playerColor = NCMTheme.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND);
        int accentColor = NCMTheme.getAccentColor();

        if (islandColor != cachedIslandSurfaceColor || playerColor != cachedPlayerSurfaceColor) {
            cachedIslandSurfaceColor = islandColor;
            cachedPlayerSurfaceColor = playerColor;
            for (int index = 0; index <= COLOR_STEPS; index++) {
                SURFACE_COLOR_CACHE[index] = new Color(
                        mixColor(islandColor, playerColor, (double) index / COLOR_STEPS), true);
            }
        }

        if (accentColor != cachedAccentColor) {
            cachedAccentColor = accentColor;
            for (int alpha = 0; alpha < ACCENT_ALPHA_CACHE.length; alpha++) {
                ACCENT_ALPHA_CACHE[alpha] = new Color(
                        RenderSystem.reAlpha(accentColor, alpha / 255f), true);
            }
        }
    }

    private static Color whiteAlpha(float alpha) {
        int index = (int) (Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f + .5f);
        return WHITE_ALPHA_COLORS[index];
    }

    private static Color accentAlpha(float alpha) {
        int index = (int) (Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f + .5f);
        return ACCENT_ALPHA_CACHE[index];
    }

    private static void captureTarget() {
        // open() 从 initGui 运行，在 drawScreen 进入自适应布局之前。
        // tick() 在 drawScreen 已进入之后运行。两者都必须在
        //完全相同的坐标系，否则形变会落偏中心。
        boolean ownedLayout = !WindowAdaptiveScale.isTransforming();
        WindowAdaptiveScale.begin();
        try {
            NCMScreen screen = NCMScreen.getInstance();
            targetX = screen.getPanelX();
            targetY = screen.getPanelY();
            targetWidth = screen.getPanelWidth();
            targetHeight = screen.getPanelHeight();
            targetRadius = screen.getPlayerCornerRadius();
        } finally {
            if (ownedLayout) WindowAdaptiveScale.end();
        }
    }

    private static double openDuration() {
        return OPEN_MS / speed;
    }

    private static double closeDuration() {
        return CLOSE_MS / speed;
    }

    private static float clampSpeed(float value) {
        return Math.max(.30f, Math.min(3.00f, value));
    }

    private static double lerp(double from, double to, double fraction) {
        return from + (to - from) * fraction;
    }

    private static int mixColor(int from, int to, double fraction) {
        int a = (int) Math.round(lerp((from >> 24) & 0xFF, (to >> 24) & 0xFF, fraction));
        int r = (int) Math.round(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, fraction));
        int g = (int) Math.round(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, fraction));
        int b = (int) Math.round(lerp(from & 0xFF, to & 0xFF, fraction));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    private static double sub(double value, double from, double to) {
        if (value <= from) return 0.0;
        if (value >= to) return 1.0;
        return (value - from) / (to - from);
    }

}
