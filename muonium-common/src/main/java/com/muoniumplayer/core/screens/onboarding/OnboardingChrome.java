package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.rendering.RGBA;
import com.muoniumplayer.core.screens.ncm.NCMPlayerConfig;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.screens.ncm.ThemeTransitionRenderer;
import com.muoniumplayer.render.skija.SkijaClip;
import com.muoniumplayer.render.skija.SkijaShapes;

import java.awt.Color;

/**
 * 引导页的外壳 = <b>播放器自己那副骨架</b>。
 *
 * <h2>为什么数字要抄，不能再定一套</h2>
 *
 * <p>引导走完那一刻是切到 {@code NCMScreen}。如果引导的面板位置、圆角、侧栏宽、底栏高是另算的，
 * 玩家会在「完成」那一下看到整副界面跳一次位——而这一眼恰好落在他对引导的最后印象上。
 * 所以下面每一条都标了出处，改播放器那边要同步改这里，而不是反过来。</p>
 *
 * <table border="1">
 *   <tr><th>件</th><th>公式</th><th>出处</th></tr>
 *   <tr><td>面板外边距</td><td>16</td><td>{@code NCMScreen#getSpacing}</td></tr>
 *   <tr><td>面板尺寸</td><td>(屏 − 32) × 玩家设的播放器缩放</td><td>{@code getAvailablePanelWidth/Height} × {@code getEffectiveTargetScale}</td></tr>
 *   <tr><td>圆角</td><td>clamp(8, 面板高 × .028, 18)</td><td>{@code getPlayerCornerRadius}</td></tr>
 *   <tr><td>面板底色</td><td>GENERIC_BACKGROUND，alpha = 1 − 玻璃量 × .14</td><td>{@code NCMScreen#layout} 里那块 bg</td></tr>
 *   <tr><td>液态玻璃</td><td>同一个 {@link ThemeTransitionRenderer#renderLiquidGlassSurface}</td><td>{@code ThemeGlassWidget}</td></tr>
 *   <tr><td>侧栏宽</td><td>max(面板宽 × .15, min(92, 面板宽 × .30))</td><td>{@code NavigateBar#layout}</td></tr>
 *   <tr><td>侧栏底色</td><td>NAVIGATION_BACKGROUND，alpha = .9 − 玻璃量 × .22</td><td>同上</td></tr>
 *   <tr><td>底栏高</td><td>min(面板高, max(28, 面板高 × .07))</td><td>{@code NCMScreen#layout} 里 currentPanelBg 让出来的那条</td></tr>
 *   <tr><td>底栏底色</td><td>NAVIGATION_BACKGROUND，alpha = .95 − 玻璃量 × .22</td><td>{@code ControlsBar} 的 bg</td></tr>
 *   <tr><td>面板描边</td><td>白 .8 粗 + 一条随玻璃量加深的渐变描边</td><td>{@code NCMScreen#drawScreen} 末尾</td></tr>
 * </table>
 *
 * <p>只有两处是引导自己的：<b>内容区里不画任何真控件</b>（侧栏是空壳，底栏装的是引导的导航而不是
 * 播放键），以及面板之下那层「玩家此刻看到的画面」——那是 {@link OnboardingBackdrop} 抓帧糊出来的，
 * 播放器本体靠液态玻璃透游戏画面，而引导要把整屏当背景，得自己把游戏帧读回来。</p>
 */
public final class OnboardingChrome {

    /** {@code NCMScreen#getSpacing()}。 */
    static final double SPACING = 16.0;

    /**
     * 玻璃层用播放器同一个渲染器。
     *
     * <p>它只有 {@link #render} 这一条无状态路径（主题切换那道波浪才用得上实例里的时间戳），
     * 所以这里常驻一份就够了，不必跟着界面创建销毁。</p>
     */
    private static final ThemeTransitionRenderer GLASS = new ThemeTransitionRenderer();

    private OnboardingChrome() {
    }

    /**
     * 算出这一帧的骨架。
     *
     * @param width  逻辑屏宽（{@code RenderSystem.getWidth()}，已经折过 GUI Scale）
     * @param height 逻辑屏高
     */
    public static Panel of(double width, double height) {
        return of(width, height, NCMPlayerConfig.getPlayerScale());
    }

    /**
     * 同上，但把播放器缩放当参数给。
     *
     * <p>给测试用的：缩放是玩家可设的（{@value NCMPlayerConfig#MIN_SCALE}..{@value
     * NCMPlayerConfig#MAX_SCALE}，5% 一档），而 {@code NCMPlayerConfig} 读的是
     * {@code ./config/muonium/player.json}——单测里既不该依赖那个文件，更不该为它往仓库里写文件。</p>
     */
    public static Panel of(double width, double height, double scale) {
        double availableWidth = Math.max(1.0, width - SPACING * 2);
        double availableHeight = Math.max(1.0, height - SPACING * 2);
        double panelWidth = availableWidth * scale;
        double panelHeight = availableHeight * scale;
        return new Panel(width * .5 - panelWidth * .5, height * .5 - panelHeight * .5,
                panelWidth, panelHeight);
    }

    /** 一副骨架：外框 + 圆角 + 侧栏宽 + 底栏高，其余全部由这四样推出来。 */
    public static final class Panel {
        public final double x;
        public final double y;
        public final double width;
        public final double height;
        public final double radius;
        public final double sidebarWidth;
        public final double controlsHeight;

        Panel(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.radius = Math.max(8.0, Math.min(18.0, height * .028));
            this.sidebarWidth = Math.max(width * .15, Math.min(92.0, width * .30));
            this.controlsHeight = Math.min(height, Math.max(28.0, height * .07));
        }

        /** 内容区：侧栏右边、底栏上边。引导正文只许画在这里头。 */
        public double contentX() {
            return x + sidebarWidth;
        }

        public double contentY() {
            return y;
        }

        public double contentWidth() {
            return Math.max(1.0, width - sidebarWidth);
        }

        public double contentHeight() {
            return Math.max(1.0, height - controlsHeight);
        }

        /** 底栏那条，引导的导航放在这里。 */
        public double controlsX() {
            return x + sidebarWidth;
        }

        public double controlsY() {
            return y + height - controlsHeight;
        }

        public double controlsWidth() {
            return Math.max(1.0, width - sidebarWidth);
        }

        /** 侧栏顶部那块，进度点放在这里。 */
        public double railX() {
            return x;
        }

        public double railY() {
            return y;
        }

        public double railWidth() {
            return sidebarWidth;
        }
    }

    /**
     * 画骨架。
     *
     * @param alpha 整层不透明度，0..1。打开引导那一下的淡入走这里，
     *              而不是像播放器那样由控件树各自带 alpha。
     */
    public static void draw(Panel panel, float alpha) {
        if (panel == null || alpha <= .004f) {
            return;
        }
        float glass = NCMTheme.getLiquidGlassAmount();
        // 侧栏和底栏都是直角矩形，靠这层圆角裁剪长出面板的那四个圆角——
        // 播放器自己也是这么做的（它用 StencilClipManager，这里用 Skija 的录制式裁剪）。
        SkijaClip.beginClip(() -> SkijaShapes.roundedRect(panel.x, panel.y,
                panel.width, panel.height, panel.radius, 0xFFFFFFFF));
        try {
            OnboardingUi.roundedRect(panel.x, panel.y, panel.width, panel.height, 0,
                    OnboardingUi.themed(NCMScreen.ColorType.GENERIC_BACKGROUND,
                            GlassLook.tintedAlpha(GlassLook.Surface.PLAYER, alpha)));
            GLASS.renderLiquidGlassSurface(panel.x, panel.y, panel.width, panel.height,
                    panel.radius, glass * alpha);
            OnboardingUi.roundedRect(panel.x, panel.y, panel.sidebarWidth,
                    panel.height - panel.controlsHeight, 0,
                    OnboardingUi.themed(NCMScreen.ColorType.NAVIGATION_BACKGROUND,
                            GlassLook.washAlpha(GlassLook.Surface.PLAYER, alpha * .9f)));
            OnboardingUi.roundedRect(panel.controlsX(), panel.controlsY(), panel.controlsWidth(),
                    panel.controlsHeight, 0,
                    OnboardingUi.themed(NCMScreen.ColorType.NAVIGATION_BACKGROUND,
                            GlassLook.washAlpha(GlassLook.Surface.PLAYER, alpha * .95f)));
        } finally {
            SkijaClip.endClip();
        }
        drawOutline(panel, alpha, glass);
    }

    /** 面板外框：一条静态白边 + 一条随玻璃量加深的渐变边，和播放器同一对参数。 */
    private static void drawOutline(Panel panel, float alpha, float glass) {
        OnboardingUi.roundedOutline(panel.x, panel.y, panel.width, panel.height, panel.radius,
                NCMScreen.PLAYER_BORDER_THICKNESS,
                new Color(255, 255, 255, channel(alpha * 25f)));
        if (glass <= .001f) {
            return;
        }
        int glassAlpha = channel(alpha * glass * 88f);
        OnboardingUi.roundedOutlineGradient(panel.x, panel.y, panel.width, panel.height,
                panel.radius, .72,
                new Color(92, 138, 196, glassAlpha / 3),
                new Color(255, 255, 255, glassAlpha),
                new Color(115, 164, 230, glassAlpha / 2),
                new Color(225, 244, 255, glassAlpha));
        // 顶边那条高光：播放器画在圆角之间，是因为圆角那两段已经有渐变边了。
        OnboardingUi.roundedRect(panel.x + panel.radius * 1.25, panel.y + .75,
                Math.max(1.0, panel.width - panel.radius * 2.5), .7, .35,
                RGBA.color(0xFFFFFF, alpha * glass * .30f));
    }

    private static int channel(float alphaTimes255) {
        return Math.max(0, Math.min(255, (int) alphaTimes255));
    }
}
