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

    public static double correctionX() {
        double layoutWidth = RenderSystem.getWidth();
        return layoutWidth <= 0.0 ? 1.0 : RenderSystem.getWidthNotScaled() / layoutWidth;
    }

    public static double correctionY() {
        double layoutHeight = RenderSystem.getHeight();
        return layoutHeight <= 0.0 ? 1.0 : RenderSystem.getHeightNotScaled() / layoutHeight;
    }

    public static int layoutWidth() {
        return (int) Math.round(RenderSystem.getWidth());
    }

    public static int layoutHeight() {
        return (int) Math.round(RenderSystem.getHeight());
    }

    // ------------------------------------------------------------------
    // CSS px 那一档布局（folia 风格的 HUD 设置界面用）
    // ------------------------------------------------------------------

    /**
     * 强制进入固定布局，不看玩家的「窗口自适应缩放」开关。
     *
     * <p>folia 的整套排版尺寸本来就是 CSS px，照搬过来的常量（面板 896、行 72、字 14）只有在
     * 同一个坐标系里才成立。用 Minecraft 的 GUI 逻辑宽高去套，面板会从占屏 47% 变成占屏 94%，
     * 形状也从接近正方形被压成一条横幅 —— 界面看着像另一个设计。所以这一套界面固定按
     * 「物理窗口归一到 ≤1920 宽」的那条轴画，与 GUI Scale 无关，正如浏览器里的字不会因为
     * Minecraft 的缩放档位而变化。</p>
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
     * 布局单位）。所以照 {@link com.muoniumplayer.core.settings.HudConfig#foliaUiScale} 那条老路，
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
}
