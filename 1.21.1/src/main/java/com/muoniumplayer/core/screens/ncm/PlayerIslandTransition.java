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
    /** Smooth live-island → capsule handoff; retained when a close is reversed mid-flight. */
    private static double capsuleHandoffMix = 0.0;
    /**
     * Transition-local monotonic clock. The shared frame-delta helper caps long frames and is also
     * shared by HUD/music rendering; when playback drops the frame rate below 25fps, the old 40ms
     * cap made this morph take longer in wall-clock time even though its configured speed was unchanged.
     */
    private static long lastTickNanos;

    private static float speed = 1.0f;

    private static double startX, startY, startWidth, startHeight, startRadius;
    /** Opening follows the live resident island; closing must eventually land on the compact pill. */
    private static double capsuleX, capsuleY, capsuleWidth, capsuleHeight, capsuleRadius;
    private static double targetX, targetY, targetWidth, targetHeight, targetRadius;

    /** 本帧几何缓存。tick 后所有调用方复用同一份结果，避免重复解曲线和分配数组。 */
    private static final double[] surfaceBoundsCache = new double[5];
    private static double cachedContentAlpha = 0.0;
    private static double cachedContentScale = 1.0;
    private static double cachedSettlePulse = 0.0;

    /** Immutable colors used every transition frame; allocating these per frame only added GC pressure. */
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
        // Only a fresh open starts at the island. Reopening during a close must preserve the current
        // geometry; recapturing a stale anchor would make the surface jump sideways or change size.
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
            // The resident island may be expanded, but Apple's handoff lands in the compact pill.
            // Capture that destination now; tick() blends into it without changing the current frame.
            captureCapsule();
            captureSurfacePalette();
            lastTickNanos = System.nanoTime();
            DownloadDynamicIsland.preparePlayerAbsorb();
            state = CLOSING;
        }
    }

    /** 外部直接换屏时使用：立刻复位，并让真实灵动岛恢复显示。 */
    public static void abort() {
        // Normal closing reaches IDLE before NCMScreen#onGuiClosed is invoked. In that case the
        // real island has just received the absorb impulse and must keep it; abort here would
        // cancel the impact and make the surface look like it stopped dead.
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
        // Keep real-time pacing across frame drops. The cap only protects against tab switches and
        // debugger pauses; normal music-induced frame hitches must not stretch the animation.
        deltaMs = Math.min(250.0, Math.max(0.0, deltaMs));

        // Once fully open, the normal panel owns rendering. Refresh only the four cached outputs;
        // this avoids re-solving two bezier curves and rebuilding the pulse every idle player frame.
        if (state == OPEN) {
            // Screen resize/init can call open() again while the player is already open. Finish any
            // re-armed surface transition so notifications are not blocked indefinitely.
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

        // Keep the anchor change time-based. Reversing a close mid-flight must not snap the origin
        // back from the capsule to the larger resident island in one frame.
        if (state == OPENING || state == CLOSING) {
            // Start the compact-pill handoff inside the main collapse instead of waiting for a
            // separate final stage. Reversing still eases through this retained mix.
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

        // Closing is intentionally asymmetric: it starts back along the live-island path, then the
        // last segment hands the surface to the compact capsule. Reopening reverses this blend.
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

        // Opening has no secondary rebound: the emphasized curve simply settles at the exact target.
        // Only closing keeps a pre-impact compression before the real island takes over.
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
        // Opening must land exactly on the target with no added bounce. The pulse exists only for
        // closing, where it anticipates the compact-island impact.
        if (state != CLOSING) return 0.0;
        return Math.sin(Math.PI * sub(1.0 - t, .74, 1.0));
    }


    /**
     * Draws the morphing surface itself, independent of player content alpha.
     *
     * <p>The old path only rendered {@code basePanel}, whose alpha follows {@link #contentAlpha()}.
     * At the first 10% of opening and the last 10% of closing that alpha is zero, so the surface
     * vanished even while its geometry was still moving. Apple's island expansion is exactly the
     * opposite: the dark surface is visible from the first frame and content only appears later.</p>
     */
    public static void renderSurface() {
        // Once open, basePanel already draws the authoritative surface. Drawing this fallback too
        // doubled the rounded-rect work for every frame the player remained open.
        if (state == IDLE || state == OPEN) return;

        double[] bounds = surfaceBounds();
        double x = bounds[0], y = bounds[1], width = bounds[2], height = bounds[3], radius = bounds[4];

        // A fresh open starts with the live island's material; closing reaches the compact pill
        // with the same material. The 65-entry ramp is rebuilt only when theme/style colors change.
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

    /** Rebuilds small immutable color ramps only when the source theme/style colors change. */
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
        // open() runs from initGui, before drawScreen has entered the adaptive layout.
        // tick() runs after drawScreen has already entered it. Both must capture the target in
        // exactly the same coordinate system, otherwise the morph lands off-center.
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








