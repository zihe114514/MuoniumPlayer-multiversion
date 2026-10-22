package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;

/**
 * 灵动岛快捷搜索栏 从 灵动岛 展开 / 收回 的胶囊转场。
 *
 * <p>与 {@link PlayerIslandTransition} 同一条时间驱动思路、同一套岛侧钩子：
 * <ul>
 *   <li>打开：从 {@code DownloadDynamicIsland.getLiveAnchorBounds()} 捕获起点（岛可见时是
 *       上一帧真实渲染的实时轮廓，不可见时退回公式值），随后 {@code beginPlayerSurfaceTransition()}
 *       让岛停画 —— 通知队列照常接收、可见时钟被暂停，转场结束后自动恢复；</li>
 *   <li>关闭：几何收回到紧凑胶囊，落定时 {@code beginPlayerAbsorbImpact()} —— 岛以 260ms
 *       撞击波「撞回来」，与播放器收起共用同一条交接路径；</li>
 *   <li>中止（外部换屏）：{@code cancelPlayerSurfaceTransition()}，立即复位。</li>
 * </ul></p>
 *
 * <p>只有一个 {@code t ∈ [0,1]} 的时钟，几何都是 t 的纯函数（{@link TransitionCurveCache#MOTION}
 * 与播放器同一条 emphasized 减速曲线），中途反向从当前位置原路折回。</p>
 *
 * <p>坐标：内部一律工作在播放器的<em>布局轴</em>（岛锚点经 {@code WindowAdaptiveScale.toLayout*}
 * 换算，与 {@code PlayerIslandTransition.captureStart()} 同款）；Overlay 渲染时在
 * {@code WindowAdaptiveScale.begin()} 内绘制即可对齐。</p>
 */
public final class IslandSearchTransition implements SharedConstants {

    private IslandSearchTransition() {
    }

    private static final int IDLE = 0, OPENING = 1, OPEN = 2, CLOSING = 3;

    private static final TransitionCurveCache.CachedBezier MOTION = TransitionCurveCache.MOTION;

    /** 展开 / 收回各占的墙钟时长（ms），与 {@link IslandSearchModel} 的常量同源。 */
    private static final double OPEN_MS = IslandSearchModel.OPEN_MS;
    private static final double CLOSE_MS = IslandSearchModel.CLOSE_MS;

    private static int state = IDLE;
    /** 1 = 完全展开。一帧只在 {@link #tick()} 里推进一次。 */
    private static double t = 0.0;
    /**
     * 单调时钟；掉帧钳 {@link #MAX_STEP_MS}（与播放器转场同一条规矩，理由也相同：
     * 这条动画是用户盯着看的，一帧跳 23% 读起来就是「跳过了动画」）。
     */
    private static long lastTickNanos;

    /** 单帧最多推进的动画时间，毫秒。见 {@link PlayerIslandTransition#tick()} 的注释。 */
    private static final double MAX_STEP_MS = 50.0;

    /** 起点布局坐标 = 灵动岛锚点（公式值或实时轮廓）。 */
    private static double startX, startY, startWidth, startHeight, startRadius;
    /** 展开目标的布局坐标 = 搜索胶囊（Overlay 按屏幕宽算好传进来）。 */
    private static double targetX, targetY, targetWidth, targetHeight, targetRadius;
    /** 终点（关闭落点）= 紧凑胶囊。打开时捕获，保证收起总是回到同一颗胶囊。 */
    private static double capsuleX, capsuleY, capsuleWidth, capsuleHeight, capsuleRadius;

    /** 本帧几何缓存：tick 后渲染与命中检测复用同一份。 */
    private static final double[] surfaceBoundsCache = new double[5];
    /** 内容（输入框、结果列表）的淡入 alpha：比表面晚一档，先把玻璃撑开再让内容浮现。 */
    private static double cachedContentAlpha = 0.0;

    // ------------------------------------------------------------------ 生命周期

    /**
     * 从灵动岛锚点开始展开。 {@code tx/ty/tw/th/tr} 是搜索胶囊的目标矩形（布局坐标）。
     *
     * <p>播放器转场忙（{@code PlayerIslandTransition.isBusy()}）时拒绝打开 ——
     * 两条转场抢同一个 {@code beginPlayerSurfaceTransition()} 会互相归零对方的动画状态。</p>
     */
    static boolean open(double tx, double ty, double tw, double th, double tr) {
        if (PlayerIslandTransition.isBusy()) return false;

        if (state == IDLE) {
            captureStart();
            captureCapsule();
            DownloadDynamicIsland.beginPlayerSurfaceTransition();
            t = 0.0;
        }
        // CLOSING 中途重开：保留 t 原路折回，锚点不重捕（几何连续）。
        targetX = tx; targetY = ty;
        targetWidth = tw; targetHeight = th; targetRadius = tr;
        lastTickNanos = System.nanoTime();
        state = state == OPEN ? OPEN : OPENING;
        refreshFrameCache();
        return true;
    }

    /** 更新目标矩形（窗口 resize / 配置变化时）。只在展开完成后生效。 */
    static void retarget(double tx, double ty, double tw, double th, double tr) {
        targetX = tx; targetY = ty;
        targetWidth = tw; targetHeight = th; targetRadius = tr;
        if (state == OPEN) refreshFrameCache();
    }

    /** 开始收回。落定时由 {@link #tick()} 触发岛的撞击回弹。 */
    static void close() {
        if (state == OPENING || state == OPEN) {
            lastTickNanos = System.nanoTime();
            DownloadDynamicIsland.preparePlayerAbsorb();
            state = CLOSING;
        }
    }

    /** 外部直接换屏时使用：立刻复位，让真实灵动岛恢复显示（不播撞击）。 */
    static void abort() {
        if (state == IDLE && t == 0.0) {
            refreshFrameCache();
            return;
        }
        DownloadDynamicIsland.cancelPlayerSurfaceTransition();
        state = IDLE;
        t = 0.0;
        lastTickNanos = 0L;
        refreshFrameCache();
    }

    /** 每帧推进一次时钟。必须在读取任何几何之前调用。 */
    static void tick() {
        long now = System.nanoTime();
        double deltaMs = lastTickNanos == 0L ? 0.0 : (now - lastTickNanos) / 1_000_000.0;
        lastTickNanos = now;
        deltaMs = Math.min(MAX_STEP_MS, Math.max(0.0, deltaMs));

        if (state == OPENING) {
            t = Math.min(1.0, t + deltaMs / OPEN_MS);
            if (t >= 1.0) {
                t = 1.0;
                state = OPEN;
                // 刻意【不】在这里调 finishPlayerSurfaceTransition：搜索胶囊不是整屏表面，
                // 岛一旦解除接管就会按常驻逻辑（FPS/时钟那一条）从胶囊背后淡回来
                // （2026-10-21 真机截图实锤：环境岛叠在胶囊上）。接管一直保持到
                // close()/abort() 显式交还，期间 renderInternal 短路、岛一个像素都不画。
            }
        } else if (state == CLOSING) {
            t = Math.max(0.0, t - deltaMs / CLOSE_MS);
            if (t <= 0.0) {
                t = 0.0;
                state = IDLE;
                DownloadDynamicIsland.beginPlayerAbsorbImpact();
            }
        }
        refreshFrameCache();
    }

    public static boolean isRunning() {
        return state == OPENING || state == CLOSING;
    }

    public static boolean isOpen() {
        return state == OPEN;
    }

    public static boolean isIdle() {
        return state == IDLE;
    }

    /** 活动中（含完全展开）。岛停画的整个窗口都算。 */
    static boolean isActive() {
        return state != IDLE;
    }

    // ------------------------------------------------------------------ 几何

    /** 当前表面矩形（布局坐标）：x、y、width、height、radius。 */
    public static double[] surfaceBounds() {
        return surfaceBoundsCache;
    }

    /** 搜索内容（输入框/结果列表）淡入 alpha。比表面晚一档。 */
    public static double contentAlpha() {
        return cachedContentAlpha;
    }

    private static void refreshFrameCache() {
        double fx = MOTION.at(t);

        // 关闭末段把宽高交接给紧凑胶囊：最后 30% 沿岛路径折回胶囊 —— 与播放器
        // 「先沿原路折回，最后一段交给胶囊」同一手法。
        double handoff = state == CLOSING ? MOTION.at(clamp01((1.0 - t) - 0.70) / 0.30) : 0.0;

        double[] bounds = IslandSearchModel.lerpBounds(
                new double[]{startX, startY, startWidth, startHeight, startRadius},
                new double[]{targetX, targetY, targetWidth, targetHeight, targetRadius},
                fx);
        double[] landed = IslandSearchModel.lerpBounds(bounds,
                new double[]{capsuleX, capsuleY, capsuleWidth, capsuleHeight, capsuleRadius},
                handoff);

        double width = landed[2];
        double height = landed[3];
        double x = landed[0];
        double y = landed[1];

        // 展开中段的一次轻微横向鼓包：玻璃被「吹」出来的那口气，与播放器转场同源但更轻。
        if (state == OPENING) {
            double bulge = Math.sin(Math.PI * t) * 0.028;
            width *= 1.0 + bulge;
            x -= width * bulge * .5;
        }

        surfaceBoundsCache[0] = x;
        surfaceBoundsCache[1] = y;
        surfaceBoundsCache[2] = width;
        surfaceBoundsCache[3] = height;
        surfaceBoundsCache[4] = landed[4];
        cachedContentAlpha = MOTION.at(clamp01((t - 0.30) / 0.55));
    }

    private static void captureStart() {
        // 岛可见度 > 0.72 时 getLiveAnchorBounds 返回上一帧真实渲染的实时轮廓（已含
        // configuredScale），否则退回公式值 —— 与 PlayerIslandTransition.captureStart 完全同款。
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

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
