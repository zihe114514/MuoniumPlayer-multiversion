package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
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

    /** 1 = 完全展开。全部曲线都读它，一帧只推进一次（{@link #tick()}）。 */
    private static double t = 0.0;
    /** 平滑的灵动岛 → 胶囊交接；在关闭被中途逆转时保留。 */
    private static double capsuleHandoffMix = 0.0;
    /**
     * 过渡专用的单调时钟。共享的帧增量助手对长帧设上限，且也
     * 被 HUD/音乐渲染共享；当播放把帧率降到 25fps 以下时，旧的 40ms
     * 上限使该形变在墙上时钟上更久，即使其配置速度未变。
     */
    private static long lastTickNanos;

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
        lastTickNanos = 0L;
        refreshFrameCache();
    }

    /** 每帧推进一次时钟。必须在读取任何曲线之前调用。 */
    public static void tick() {
        captureTarget();

        long now = System.nanoTime();
        double deltaMs = lastTickNanos == 0L ? 0.0 : (now - lastTickNanos) / 1_000_000.0;
        lastTickNanos = now;
        // 在掉帧期间保持实时步调。该上限只防标签页切换和
        //调试器暂停；音乐引起的正常卡顿绝不能拉长动画。
        deltaMs = Math.min(250.0, Math.max(0.0, deltaMs));

        // 完全打开后，普通面板拥有渲染权。仅刷新四个缓存输出；
        // this avoids re-solving two bezier curves and rebuilding the pulse every idle player frame.
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

    public static boolean isRunning() {
        return state == OPENING || state == CLOSING;
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
     */
    public static void renderSurface() {
        // 一旦打开，basePanel 已绘制权威表面。再绘制这个回退会
        //在播放器保持打开的每一帧使圆角矩形工作翻倍。
        if (state == IDLE || state == OPEN) return;

        double[] bounds = surfaceBounds();
        double x = bounds[0], y = bounds[1], width = bounds[2], height = bounds[3], radius = bounds[4];

        // 全新打开从灵动岛的材质开始；关闭以相同材质到达紧凑胶囊。
        //65 项渐变仅在主题/样式颜色变化时重建。
        double colorMix = MOTION.at(sub(t, .04, .30));
        int colorIndex = (int) Math.round(colorMix * COLOR_STEPS);
        Color color = SURFACE_COLOR_CACHE[Math.max(0, Math.min(COLOR_STEPS, colorIndex))];

        if (!TransitionPerformanceBudget.isMinimal()) {
            GFX.roundedRect(x + 1.0, y + 1.8, width, height, radius, SHADOW_STRONG);
            GFX.roundedRect(x + 2.0, y + 3.0, width, height, radius, SHADOW_SOFT);
        }
        GFX.roundedRect(x, y, width, height, radius, color);
        GFX.roundedOutline(x, y, width, height, radius, .72, SURFACE_EDGE);
    }
    /** 收起末段的边界光：只服务于胶囊撞击前的压缩，不再给打开动画叠加落地回弹。 */
    public static void renderSurfaceDetail() {
        if (state == IDLE || state == OPEN || TransitionPerformanceBudget.isMinimal()) return;
        double landing = cachedSettlePulse;
        if (landing <= .01) return;

        double[] bounds = surfaceBounds();
        float alpha = (float) landing;
        boolean closing = state == CLOSING;
        int edgeAlpha = closing ? 76 : 52;
        GFX.roundedOutline(bounds[0] - .5, bounds[1] - .5,
                bounds[2] + 1.0, bounds[3] + 1.0, bounds[4],
                .72, whiteAlpha(alpha * edgeAlpha));

        if (!TransitionPerformanceBudget.simplifySurfaceDetail()) {
            GFX.roundedOutline(bounds[0] - 1.1, bounds[1] - 1.1,
                    bounds[2] + 2.2, bounds[3] + 2.2, bounds[4] + .6,
                    .60, accentAlpha(alpha * (closing ? .34f : .28f)));
        }

        // 顶部扫光：一条窄带从左到右滑过，收起时反向。
        double sheenHeight = Math.max(1.0, bounds[3] * .18);
        double bandWidth = Math.max(32.0, bounds[2] * .42);
        double bandX = bounds[0] + 1.0 + (bounds[2] - 2.0 - bandWidth) * Math.max(0.0, Math.min(1.0, t));
        Color sheenStart = closing ? whiteAlpha(alpha * 26) : TRANSPARENT_WHITE;
        Color sheenEnd = closing ? TRANSPARENT_WHITE : whiteAlpha(alpha * 26);
        if (!TransitionPerformanceBudget.simplifySurfaceDetail()) {
            GFX.roundedRectGradientHorizontal(bandX, bounds[1] + 1.0,
                    bandWidth, sheenHeight,
                    Math.max(1.0, bounds[4] - 1.0), sheenStart, sheenEnd);
        }
    }
    // ------------------------------------------------------------------ 私有工具

    private static void captureStart() {
        double[] island = DownloadDynamicIsland.getLiveAnchorBounds();
        startX = WindowAdaptiveScale.toLayoutX(island[0]);
        startY = WindowAdaptiveScale.toLayoutY(island[1]);
        startWidth = WindowAdaptiveScale.toLayoutWidth(island[2]);
        startHeight = WindowAdaptiveScale.toLayoutHeight(island[3]);
        startRadius = WindowAdaptiveScale.toLayoutRadius(island[4]);
    }

    private static void captureCapsule() {
        double[] island = DownloadDynamicIsland.getAnchorBounds();
        capsuleX = WindowAdaptiveScale.toLayoutX(island[0]);
        capsuleY = WindowAdaptiveScale.toLayoutY(island[1]);
        capsuleWidth = WindowAdaptiveScale.toLayoutWidth(island[2]);
        capsuleHeight = WindowAdaptiveScale.toLayoutHeight(island[3]);
        capsuleRadius = WindowAdaptiveScale.toLayoutRadius(island[4]);
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








