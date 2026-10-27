package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 播放器与播放器设置页共用的窗口自适应坐标层。
 *
 * <p>开启时沿用播放器现有的“固定逻辑坐标”：逻辑宽高由物理窗口推导，
 * 渲染前按当前 Minecraft GUI Scale 做一次连续校正。这样 1x / 2x / 3x GUI
 * 不会改变播放器的物理占屏比例；关闭时则直接使用 Minecraft 的 GUI 逻辑宽高。</p>
 */
public final class WindowAdaptiveScale {

    private WindowAdaptiveScale() {
    }

    public static boolean isEnabled() {
        return NCMPlayerConfig.isWindowAdaptiveScaleEnabled();
    }

    /** 必须在 RenderSystem#refreshResolution() 之后调用。 */
    public static boolean begin() {
        boolean active = isEnabled();
        RenderSystem.FIXED_SCALE = active;
        return active;
    }

    public static void end() {
        RenderSystem.FIXED_SCALE = false;
    }

    public static boolean isTransforming() {
        return RenderSystem.FIXED_SCALE;
    }

    /**
     * 布局轴 → 当前画布坐标的缩放因子。
     *
     * <p>分子恒为 GUI 逻辑宽（canvas 的初始 scale 是 guiScale，布局单位必须先乘这一层才能
     * 落到正确的物理尺寸）；分母恒为布局轴宽 {@link RenderSystem#getLayoutWidth()}——
     * 不读 {@link RenderSystem#getWidth()}，那个返回值随 FIXED_SCALE 状态翻转，用它做分母
     * 会让本方法在固定布局括号外退化成 1.0（HUD 覆盖层从未进过任何括号，叠加层于是整体
     * 随 GUI Scale 档位变大移位，3x/4x 档尤其明显）。括号内分母本就等于布局轴宽，
     * 播放器/编辑器里的值不受影响。</p>
     */
    public static double correctionX() {
        double guiWidth = RenderSystem.getWidthNotScaled();
        double layoutWidth = RenderSystem.getLayoutWidth();
        return guiWidth <= 0.0 || layoutWidth <= 0.0 ? 1.0 : guiWidth / layoutWidth;
    }

    public static double correctionY() {
        double guiHeight = RenderSystem.getHeightNotScaled();
        double layoutHeight = RenderSystem.getLayoutHeight();
        return guiHeight <= 0.0 || layoutHeight <= 0.0 ? 1.0 : guiHeight / layoutHeight;
    }

    public static int layoutWidth() {
        return (int) Math.round(RenderSystem.getWidth());
    }

    public static int layoutHeight() {
        return (int) Math.round(RenderSystem.getHeight());
    }

    // ------------------------------------------------------------------
    // 灵动岛锚点：布局轴 → 当前上下文坐标
    // ------------------------------------------------------------------

    /**
     * 灵动岛锚点（固定按布局轴上报）→ 当前渲染上下文的坐标。
     *
     * <p>锚点生产方（{@code DownloadDynamicIsland.getAnchorBounds}）不进任何固定布局括号、
     * 也不依赖「窗口自适应缩放」开关，恒按布局轴书写；消费方（播放器展开/收回转场、岛内
     * 搜索浮层）则工作在两种上下文之一：固定布局括号内几何本身就是布局轴（恒等），GUI 轴
     * 模式下要乘 correction 折算成本上下文的坐标 —— 转场内部随后会再用 {@link #toLayoutX}
     * 系列换回布局轴，两段相乘恰好把锚点送到播放器的布局坐标系。</p>
     */
    public static double islandToCurrentX(double value) {
        return RenderSystem.FIXED_SCALE ? value : value * correctionX();
    }

    public static double islandToCurrentY(double value) {
        return RenderSystem.FIXED_SCALE ? value : value * correctionY();
    }

    public static double islandToCurrentWidth(double value) {
        return RenderSystem.FIXED_SCALE ? value : value * correctionX();
    }

    public static double islandToCurrentHeight(double value) {
        return RenderSystem.FIXED_SCALE ? value : value * correctionY();
    }

    /** 半径用 X/Y correction 的均值折算，与 {@link #toLayoutRadius(double)} 互为镜像。 */
    public static double islandToCurrentRadius(double value) {
        if (RenderSystem.FIXED_SCALE) return value;
        double scaleX = correctionX();
        double scaleY = correctionY();
        return scaleX <= 0.0 || scaleY <= 0.0 ? value : value * ((scaleX + scaleY) * .5);
    }

    // ------------------------------------------------------------------
    // CSS px 那一档布局（folia 风格的 HUD 设置界面用）
    // ------------------------------------------------------------------

    /**
     * 强制进入固定布局，不看玩家的「窗口自适应缩放」开关。
     *
     * <p>消费方是两类「固定设计」的渲染：HUD 编辑器（GuiHudEditorPro 的面板排版常量只有
     * 在同一坐标系里才成立，预览还必须与真实 HUD 同轴），以及 HUD 覆盖层自身的括号
     * （灵动岛/频谱/Automix 动画的渲染入口各自包一层，见各 render 方法的 ownedLayout 守卫）。
     * 这一轴是「物理窗口归一到 ≤1920 宽再对折」，与 GUI Scale 无关 —— 正如浏览器里的字
     * 不会因为 Minecraft 的缩放档位而变化。配套的矩阵层是 {@link #correctionX()}：
     * 布局 × correction = GUI 逻辑，再由 canvas 的 guiScale 上屏。</p>
     */
    public static boolean beginForced() {
        RenderSystem.FIXED_SCALE = true;
        return true;
    }

    /**
     * 设计空间永远是播放器布局空间的 <b>2 倍</b>，与开关无关。
     *
     * <p>固定模式下布局是「物理宽归一到 ≤1920 再对折」，设计空间就是把那个对折乘回去；关掉自适应
     * 时布局退成 Minecraft 的 GUI 逻辑宽高，设计空间仍是它的 2 倍 —— 于是把浮层整体按这个倍率
     * 换轴，不需要读开关状态，也不会出现「换了 GUI 档位尺寸就跳一下」。</p>
     *
     * <p>要给某个浮层单独换到 CSS px 那一档时，就绕原点乘上这个倍率的倒数（{@code scale(.5, .5, 1)}）
     * 并把它的边界与鼠标坐标一起乘上它；三者必须一起改，只改绘制不改命中就是点不着。</p>
     */
    public static final double DESIGN_PER_LAYOUT = 2.0;

    /**
     * 歌词搜索弹窗那一层的「布局单位 → 弹窗单位」倍率。
     *
     * <p>这一页的排版数值照的是 folia 的 CSS px，所以基础倍率就是 {@link #DESIGN_PER_LAYOUT}：
     * 1 弹窗单位 = 1 物理像素，和 dpr=1 的浏览器一模一样。问题是浏览器里的 14 px 正文落到
     * Minecraft 里只有一半 —— 播放器其它界面的文字是它的两倍（布局轴上 {@code pf14} 就是 14 个
     * 布局单位）。所以沿用「弹窗界面缩放」那条老路，
     * 让用户自己挑一档：倍率 {@code >1} 等于把整个弹窗（几何、字号、间距一起）放大，弹窗内部的
     * 比例与 folia 完全同构。</p>
     *
     * <p>放大不是免费的：弹窗宽是 {@code min(1024, 85% × 视口)}，视口按倍率变小之后，
     * 大约从 1.5× 起 {@code max-w-5xl} 那一档会先让位给 85% 的宽度夹，形状朝横幅偏一点。
     * 真机上觉得字小就往上调，觉得弹窗太占屏就往下调，这里只兜住非法值。</p>
     */
    public static double lyricSearchUnitsPerLayout() {
        return DESIGN_PER_LAYOUT / clampUiScale(HudConfig.lyricSearchUiScale);
    }

    /** 用户倍率的兜底：配置层已经夹过一遍，这里只防 NaN / 没初始化 / 手写负数。 */
    private static double clampUiScale(float value) {
        double scale = value;
        if (!(scale >= 0.25) || scale > 4.0) return 1.0;   // NaN 与越界都退回「原样一像素一像素」
        return scale;
    }

    /**
     * 设计空间宽：1 单位 = 1 CSS px。
     *
     * <p>{@link RenderSystem#getWidth()} 在固定模式下是 {@code min(物理宽, 1920) * .5}
     * —— 播放器那一套用的是这个半尺寸轴（所以图标字体才有「create(24) 落屏 12 逻辑像素」那条规矩）。
     * 乘回来才是 CSS px。</p>
     */
    public static int designWidth() {
        return (int) Math.round(RenderSystem.getWidth() * DESIGN_PER_LAYOUT);
    }

    public static int designHeight() {
        return (int) Math.round(RenderSystem.getHeight() * DESIGN_PER_LAYOUT);
    }

    /** 设计空间 → GUI 逻辑坐标的缩放，正好是 {@link #correctionX()} 的一半。 */
    public static double designCorrectionX() {
        return correctionX() * .5;
    }

    public static double designCorrectionY() {
        return correctionY() * .5;
    }

    public static double toDesignX(double x) {
        double scale = designCorrectionX();
        return scale <= 0.0 ? x : x / scale;
    }

    public static double toDesignY(double y) {
        double scale = designCorrectionY();
        return scale <= 0.0 ? y : y / scale;
    }

    public static int toLayoutX(int x) {
        return (int) Math.round(toLayoutX((double) x));
    }

    public static int toLayoutY(int y) {
        return (int) Math.round(toLayoutY((double) y));
    }

    /**
     * 把一个 Minecraft GUI 坐标转换为播放器窗口自适应布局。
     *
     * <p>在 {@link #begin()} 之外调用也安全：它临时启用
     * 布局，仅长到足以取得修正因子。过渡代码从全局 HUD
     * 坐标系捕获锚点，而该坐标系不总在播放器渲染作用域内。</p>
     */
    public static double toLayoutX(double x) {
        if (!isEnabled()) return x;
        boolean ownedLayout = beginLayoutIfNeeded();
        try {
            double scale = correctionX();
            return scale <= 0.0 ? x : x / scale;
        } finally {
            if (ownedLayout) end();
        }
    }

    public static double toLayoutY(double y) {
        if (!isEnabled()) return y;
        boolean ownedLayout = beginLayoutIfNeeded();
        try {
            double scale = correctionY();
            return scale <= 0.0 ? y : y / scale;
        } finally {
            if (ownedLayout) end();
        }
    }

    public static double toLayoutWidth(double width) {
        if (!isEnabled()) return width;
        boolean ownedLayout = beginLayoutIfNeeded();
        try {
            double scale = correctionX();
            return scale <= 0.0 ? width : width / scale;
        } finally {
            if (ownedLayout) end();
        }
    }

    public static double toLayoutHeight(double height) {
        if (!isEnabled()) return height;
        boolean ownedLayout = beginLayoutIfNeeded();
        try {
            double scale = correctionY();
            return scale <= 0.0 ? height : height / scale;
        } finally {
            if (ownedLayout) end();
        }
    }

    public static double toLayoutRadius(double radius) {
        if (!isEnabled()) return radius;
        boolean ownedLayout = beginLayoutIfNeeded();
        try {
            double scaleX = correctionX();
            double scaleY = correctionY();
            if (scaleX <= 0.0 || scaleY <= 0.0) return radius;
            return radius / ((scaleX + scaleY) * .5);
        } finally {
            if (ownedLayout) end();
        }
    }

    private static boolean beginLayoutIfNeeded() {
        if (isTransforming()) return false;
        begin();
        return true;
    }

    // ------------------------------------------------------------------
    // 无条件布局轴（HUD 编辑器专用，不理会「窗口自适应缩放」开关）
    // ------------------------------------------------------------------

    /**
     * GUI 坐标 → 布局轴，无视「窗口自适应缩放」开关的 {@link #toLayoutX(double)}。
     *
     * <p>HUD 编辑器（GuiHudEditorPro）是固定设计界面，且真实 HUD 覆盖层已经无条件按布局轴
     * 渲染 —— 编辑器的预览与命中必须同轴，否则玩家关掉自适应开关后编辑器里鼠标点不中预览。
     * 普通播放器界面继续用带开关闸的版本。</p>
     */
    public static double toLayoutXForced(double x) {
        double scale = correctionX();
        return scale <= 0.0 ? x : x / scale;
    }

    public static double toLayoutYForced(double y) {
        double scale = correctionY();
        return scale <= 0.0 ? y : y / scale;
    }

    /** 同 {@link #toLayoutXForced(double)}，入参/出参按 GUI 整数坐标（编辑器鼠标换算用）。 */
    public static int toLayoutXForced(int x) {
        return (int) Math.round(toLayoutXForced((double) x));
    }

    public static int toLayoutYForced(int y) {
        return (int) Math.round(toLayoutYForced((double) y));
    }
}
