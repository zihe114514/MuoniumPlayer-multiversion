package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.MusicCapsuleCard;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.settings.HudConfig;

import java.awt.Color;

/**
 * 播放器主界面从灵动岛展开 / 收回的整屏转场。
 *
 * <p>几何由独立的解析高阻尼运动驱动，反向继承当前矩形及速度。
 * 内容与材质交接读取同一可见帧时钟；附近时间采样不会推进生命周期。</p>
 */
public final class PlayerIslandTransition implements SharedRenderingConstants, SharedConstants {

    /** 形状绘制是接口上的 default 方法，静态调用需要一个实例；除此之外这个对象没有状态。 */
    private static final PlayerIslandTransition GFX = new PlayerIslandTransition();

    private PlayerIslandTransition() {
    }

    private static final int IDLE = 0, OPENING = 1, OPEN = 2, CLOSING = 3;

    /** 默认 3x 下展开约 293ms、关闭约 217ms，正常帧不强制十二步。 */
    private static final double OPEN_MS = PlayerIslandMotion.OPEN_MILLIS;
    private static final double CLOSE_MS = PlayerIslandMotion.CLOSE_MILLIS;

    private static final TransitionCurveCache.CachedBezier MOTION = TransitionCurveCache.MOTION;
    private static final PlayerIslandMotion MOVEMENT = new PlayerIslandMotion();
    private static final PlayerIslandMotion.Sample FRAME = new PlayerIslandMotion.Sample();

    private static int state = IDLE;
    private static boolean openingFromMusicCard;
    private static double[] musicCardStartBounds;

    /** 材质和旧绘制细节的有界内容相位，不再决定几何。 */
    private static double t = 0.0;

    /**
     * 落定材质交接窗，毫秒。
     *
     * <p>转场期间使用预糊背景，整面液态折射关闭。几何落定且常规底板就绪后，
     * 保持转场底图垫底，让常规玻璃按 {@link #surfaceHandoff()} 覆盖接管；
     * 交接完成才移除底图。两层不是相加为一的交叉淡化。</p>
     */
    private static final double SURFACE_HANDOFF_MS = 180.0;

    /**
     * 材质交接的进度 0..1：0 = 仍然按转场贴图画表面，1 = 已完全交给常规玻璃路径。
     * 只在 {@link #tick()} 推进（消费与 {@code tick} 相同的钳后 deltaMs），渲染侧只读。
     */
    private static double surfaceHandoff = 1.0;

    /** 保留包内测试入口；与纯运动类使用完全相同的时钟合同。 */
    static final class FrameClock extends PlayerIslandMotion.FrameClock {
    }

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
     * 岛上音乐胶囊迷你卡的打开入口：沿用播放器动画入口与配置倍率，
     * 不给卡片另开一套时钟或新增配置消费方。
     */
    public static void openFromIslandCard() {
        openingFromMusicCard = DownloadDynamicIsland.isMusicCardOpen();
        musicCardStartBounds = openingFromMusicCard ? DownloadDynamicIsland.musicCardBounds() : null;
        // 先切宿主，由 NCMScreen.initGui 捕获当前卡片轮廓并启动唯一的转场时钟。
        // 只调用 open() 会藏起岛，却没有真正打开播放器。
        com.muoniumplayer.core.screens.onboarding.OnboardingManager.openPlayerEntry();
    }

    /** 从当前轮廓展开；收起中再次打开保留几何和速度。 */
    public static void open(float speedMultiplier) {
        float previousSpeed = speed;
        speed = clampSpeed(speedMultiplier);
        boolean fresh = state == IDLE;
        if (fresh) {
            captureStart();
            captureCapsule();
            captureSurfacePalette();
            surfaceHandoff = 0.0;
            MOVEMENT.snap(startX, startY, startWidth, startHeight, startRadius, 0.0);
        }
        captureTarget();
        if (state == OPEN) {
            MOVEMENT.snap(targetX, targetY, targetWidth, targetHeight, targetRadius, 1.0);
            MOVEMENT.resetClock();
            DownloadDynamicIsland.finishPlayerSurfaceTransition();
        } else {
            DownloadDynamicIsland.beginPlayerSurfaceTransition();
            if (fresh || state == CLOSING) {
                MOVEMENT.retarget(targetX, targetY, targetWidth, targetHeight, targetRadius, 1.0,
                        openDuration(), false, true);
                state = OPENING;
            } else if (state == OPENING && (previousSpeed != speed || !MOVEMENT.targets(
                    targetX, targetY, targetWidth, targetHeight, targetRadius))) {
                MOVEMENT.retarget(targetX, targetY, targetWidth, targetHeight, targetRadius, 1.0,
                        Math.max(MOVEMENT.remainingMillis() * previousSpeed / speed, openDuration() * .20),
                        false, false);
            }
        }
        refreshFrameCache();
    }

    /** 收回到紧凑胶囊；反向不翻转或清空当前速度，而是自然制动。 */
    public static void close() {
        if (state == OPENING || state == OPEN) {
            captureCapsule();
            captureSurfacePalette();
            MOVEMENT.retarget(capsuleX, capsuleY, capsuleWidth, capsuleHeight, capsuleRadius, 0.0,
                    closeDuration(), true, true);
            DownloadDynamicIsland.preparePlayerAbsorb();
            state = CLOSING;
            refreshFrameCache();
        }
    }

    /** 外部直接换屏时使用：立刻复位，并让真实灵动岛恢复显示。 */
    public static void abort() {
        // 正常关闭在 NCMScreen#onGuiClosed 被调用前到达 IDLE。此时
        //真实岛刚收到吸收脉冲，必须保留它；在此中止会
        //取消冲击，使表面看起来像戛然而止。
        if (state == IDLE && t == 0.0) {
            openingFromMusicCard = false;
            musicCardStartBounds = null;
            refreshFrameCache();
            return;
        }
        DownloadDynamicIsland.cancelPlayerSurfaceTransition();
        state = IDLE;
        t = 0.0;
        surfaceHandoff = 0.0;
        openingFromMusicCard = false;
        musicCardStartBounds = null;
        MOVEMENT.snap(capsuleX, capsuleY, capsuleWidth, capsuleHeight, capsuleRadius, 0.0);
        MOVEMENT.resetClock();
        refreshFrameCache();
    }

    /** 每帧推进一次可见时钟；渲染与邻近采样只读。 */
    public static void tick() {
        boolean layoutChanged = captureTarget();
        if (state == OPEN) {
            // 已打开时普通布局拥有几何；resize 不重新启动通知表面所有权。
            if (layoutChanged) MOVEMENT.snap(targetX, targetY, targetWidth, targetHeight, targetRadius, 1.0);
            DownloadDynamicIsland.finishPlayerSurfaceTransition();
        } else if (state == OPENING && !MOVEMENT.targets(
                targetX, targetY, targetWidth, targetHeight, targetRadius)) {
            MOVEMENT.retarget(targetX, targetY, targetWidth, targetHeight, targetRadius, 1.0,
                    Math.max(MOVEMENT.remainingMillis(), openDuration() * .20), false, false);
        } else if (state == CLOSING && layoutChanged) {
            // 锚点查询返回数组，只在真实布局改变时重捕获，不逐动画帧分配。
            captureCapsule();
            if (!MOVEMENT.targets(capsuleX, capsuleY, capsuleWidth, capsuleHeight, capsuleRadius)) {
                MOVEMENT.retarget(capsuleX, capsuleY, capsuleWidth, capsuleHeight, capsuleRadius, 0.0,
                        Math.max(MOVEMENT.remainingMillis(), closeDuration() * .20), true, false);
            }
        }
        boolean wasOpening = state == OPENING;
        double remaining = MOVEMENT.remainingMillis();
        double deltaMs = MOVEMENT.tick(System.nanoTime());
        double handoffDeltaMs = deltaMs;
        if (wasOpening && !MOVEMENT.isRunning()) {
            state = OPEN;
            openingFromMusicCard = false;
            musicCardStartBounds = null;
            handoffDeltaMs = Math.max(0.0, deltaMs - remaining);
            DownloadDynamicIsland.finishPlayerSurfaceTransition();
        } else if (state == CLOSING && !MOVEMENT.isRunning()) {
            // MOVEMENT 已精确落到合法紧凑胶囊；只在这一个状态边沿触发冲击。
            state = IDLE;
            DownloadDynamicIsland.beginPlayerAbsorbImpact();
        }
        refreshFrameCache();
        advanceSurfaceHandoff(state == OPEN ? handoffDeltaMs : deltaMs);
    }

    /**
     * 推进落定材质交接：0 = 转场贴图，1 = 常规玻璃路径完全接管。
     * 只有几何生命周期落定到 OPEN 后向前走；运动期间沿同一可见 delta 回卷。
     * 内容 alpha 可先到 1，不能把它误当几何已经停止。
     */
    private static void advanceSurfaceHandoff(double deltaMs) {
        if (state == OPEN) {
            // Cold content may still be attaching after the geometry stops. Keep the
            // transition backing until the regular surface can actually cover it.
            if (NCMScreen.getInstance().isOpeningSurfaceReady()) {
                surfaceHandoff = Math.min(1.0, surfaceHandoff + deltaMs / SURFACE_HANDOFF_MS);
            }
        } else if (surfaceHandoff > 0.0) {
            surfaceHandoff = Math.max(0.0, surfaceHandoff - deltaMs / SURFACE_HANDOFF_MS);
        }
    }

    /**
     * 本帧表面该按多大比例交给常规玻璃路径（0..1）。
     *
     * <p>0 = 转场底图拥有表面，1 = 常规玻璃接管完成。中间阶段底图保持全浓度，
     * NCMScreen 的常规材质在上方淡入；此值也控制转场描边退出，不同时淡掉两张底。</p>
     */
    public static double surfaceHandoff() {
        return surfaceHandoff;
    }

    /** 展开、收起和材质交接期间给协作任务更小的主线程预算。 */
    public static boolean needsFrameBudget() {
        return isRunning() || (state == OPEN && surfaceHandoff < 0.999);
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

    /** 当前表面矩形：x、y、width、height、radius；沿用同一个共享数组，不是快照。 */
    public static double[] surfaceBounds() {
        return surfaceBoundsCache;
    }

    /**
     * 当前 / 附近时间的纯数据输出，调用方复用 out；不推进动画、不分配数组。
     * offsetMillis 限制在 +/-33.33ms，历史不能跨本次 retarget 边界。
     * 位置为布局坐标，速度为布局单位/秒；内容变换是 scale*p+translate。
     */
    public static void sampleMotion(double offsetMillis, PlayerIslandMotion.Sample out) {
        MOVEMENT.sample(offsetMillis, out);
    }

    /** 最近一帧消费的有界可见时间（毫秒），不是阻塞前的墙钟间隔。 */
    public static double lastFrameMillis() {
        return MOVEMENT.lastFrameMillis();
    }

    private static void refreshFrameCache() {
        MOVEMENT.sample(0.0, FRAME);
        surfaceBoundsCache[0] = FRAME.x;
        surfaceBoundsCache[1] = FRAME.y;
        surfaceBoundsCache[2] = FRAME.width;
        surfaceBoundsCache[3] = FRAME.height;
        surfaceBoundsCache[4] = FRAME.radius;
        cachedContentAlpha = FRAME.contentAlpha;
        cachedContentScale = FRAME.contentScale;
        cachedSettlePulse = 0.0;
        t = cachedContentAlpha;
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

    // 关闭冲击由真实岛在吸收边沿负责；形变本身不叠加鼓胀或预压缩。

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
        boolean liquid;
        if (openingFromMusicCard) {
            io.github.humbleui.skija.Canvas canvas = com.muoniumplayer.render.skija.SkijaGfx.canvas();
            int save = canvas == null ? -1 : canvas.save();
            try (io.github.humbleui.skija.Path shape = com.muoniumplayer.core.rendering.MusicCardShape.outline(
                    x, y, width, height, radius)) {
                if (canvas != null) canvas.clipPath(shape, true);
                liquid = GlassLook.paintMusicCardTransitionFrost(x, y, width, height, radius);
                float cardMix = (float) (1.0 - MOTION.at(sub(t, 0.0, .30)));
                MusicCapsuleCard.INSTANCE.drawTransitionSurface(x, y, width, height, radius, cardMix, color, liquid);
            } finally {
                if (canvas != null) canvas.restoreToCount(save);
            }
        } else {
            liquid = GlassLook.paintPlayerTransitionSurface(x, y, width, height, radius, 1f);
            GFX.roundedRect(x, y, width, height, radius,
                    liquid ? new Color(GlassLook.tintedArgb(GlassLook.Surface.PLAYER, color.getRGB()), true) : color);
        }
        if (openingFromMusicCard && musicCardStartBounds != null) {
            double amount = 1.0 - MOTION.at(sub(t, .03, .26));
            if (amount > .001) {
                double cardWidth = musicCardStartBounds[2];
                double cardHeight = musicCardStartBounds[3];
                MusicCapsuleCard.INSTANCE.drawCard(x + (width - cardWidth) * .5,
                        y + Math.min(0.0, (height - cardHeight) * .5), cardWidth, cardHeight, (float) amount, false);
            }
        }
        if (!liquid && !openingFromMusicCard) GFX.roundedOutline(x, y, width, height, radius, .72,
                alphaScaled(SURFACE_EDGE, (float) keep));
    }

    /** 返回同色但 alpha 缩放为 {@code scale} 倍的 Color；scale>=0.999 时原样返回。 */
    private static Color alphaScaled(Color color, float scale) {
        if (scale >= 0.999f) return color;
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.max(0, Math.min(255, Math.round(color.getAlpha() * scale))));
    }
    /** 打开时沿边缘短暂提亮；收起沿用原有末段细节分支。 */
    public static void renderSurfaceDetail() {
        if (state == IDLE || state == OPEN) return;
        if (state == OPENING) {
            // A narrow highlight rides the unfolding edge, then disappears before
            // the regular glass takes over. No blur, offscreen layer or trailing copies.
            double reveal = Math.sin(Math.PI * Math.max(0.0, Math.min(1.0, t)));
            double inset = surfaceBoundsCache[4] + 2.0;
            double span = Math.max(0.0, surfaceBoundsCache[2] - inset * 2.0);
            if (span > 1.0 && reveal > .01) {
                GFX.roundedRect(surfaceBoundsCache[0] + inset, surfaceBoundsCache[1] + .8,
                        span, .65, .325, whiteAlpha((float) (reveal * 18.0)));
            }
            return;
        }
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
        double[] island = openingFromMusicCard && musicCardStartBounds != null
                ? musicCardStartBounds : DownloadDynamicIsland.getLiveAnchorBounds();
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
        int islandColor = openingFromMusicCard ? 0xFF080B11 : DownloadDynamicIsland.getAnchorSurfaceColor();
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

    private static boolean captureTarget() {
        // open() 从 initGui 运行，在 drawScreen 进入自适应布局之前。
        // tick() 在 drawScreen 已进入之后运行。两者都必须在
        // 完全相同的坐标系，否则形变会落偏中心。
        double previousX = targetX, previousY = targetY;
        double previousWidth = targetWidth, previousHeight = targetHeight, previousRadius = targetRadius;
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
        MOVEMENT.setPanel(targetX, targetY, targetWidth, targetHeight);
        return previousX != targetX || previousY != targetY || previousWidth != targetWidth
                || previousHeight != targetHeight || previousRadius != targetRadius;
    }

    private static double openDuration() {
        return OPEN_MS / speed;
    }

    private static double closeDuration() {
        return CLOSE_MS / speed;
    }

    private static float clampSpeed(float value) {
        return Float.isFinite(value) ? Math.max(.30f, Math.min(3.00f, value)) : 1.0f;
    }

    private static double lerp(double from, double to, double fraction) {
        return from + (to - from) * fraction;
    }

    private static int mixColor(int from, int to, double fraction) {
        fraction = Math.max(0.0, Math.min(1.0, fraction));
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
