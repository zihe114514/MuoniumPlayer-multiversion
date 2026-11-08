package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.api.features.ExtensionScreen;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.MusicCapsuleCard;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;

/**
 * 音乐胶囊迷你卡的全透明输入浮层：迷你卡展开后按下鼠标时打开，承接点击与进度拖动。
 *
 * <p>与 {@link IslandSearchOverlay} 同一套先例——<b>全透明</b>的 Screen：{@code drawScreen}
 * 不画任何东西（迷你卡由灵动岛自己的 HUD 路径画，宿主白名单放行本浮层期间岛照常渲染），
 * 它存在的唯一理由是承接鼠标事件（无 Screen 时点击会落到游戏手上），视觉上不可见。</p>
 *
 * <p>坐标链与岛内搜索同款：Screen 坐标 ÷ 布局修正 → 布局轴 → 按岛侧上报的迷你卡矩形
 * 折算回 296×130 设计空间 → {@link MusicCapsuleCard#click(double, double)}。
 * 绘制与点击共用同一套常量，缩放卡片上命中永远对齐。</p>
 */
public final class MusicCardOverlay extends ExtensionScreen implements SharedConstants {

    private static final MusicCardOverlay INSTANCE = new MusicCardOverlay();

    /** 是否正开着。initGui/onGuiClosed 维护，避免各树读「当前 Screen」的版本差异。 */
    private static volatile boolean active;

    /**
     * 岛侧展开态上第一下按下的设计坐标：浮层 initGui 消费并直接派发为点击，
     * 用户「点一下 = 按到按钮」，不用先点一下再点一下。
     */
    private static volatile double[] pendingPressDesign;

    /** 按下起点落在进度条上时进入拖动 seek。 */
    private boolean draggingProgress;

    private MusicCardOverlay() {
    }

    /** 打开浮层。已在开就不重复；迷你卡没展开也不该被调到（岛侧保证）。 */
    public static void open() {
        if (active) return;
        api.displayScreen(INSTANCE);
    }

    public static boolean isActive() {
        return active;
    }

    /** 收起迷你卡并关掉自己（Esc / 点外部 / 岛侧收起的统一出口）。 */
    public static void closeAndCollapse() {
        DownloadDynamicIsland.collapseMusicCard();
        closeSelf();
    }

    private static void closeSelf() {
        if (active) {
            api.displayScreen(null);
        }
    }

    /** 岛侧把展开态上的第一下按下交进来（设计坐标；null = 不派发）。 */
    public static void arm(double[] designPoint) {
        pendingPressDesign = designPoint;
    }

    @Override
    public void initGui() {
        active = true;
        this.draggingProgress = false;
        double[] press = pendingPressDesign;
        pendingPressDesign = null;
        if (press != null) {
            boolean onProgress = Math.abs(press[1] - MusicCapsuleCard.PROGRESS_Y) <= 10.0
                    && press[0] >= MusicCapsuleCard.PROGRESS_X
                    && press[0] <= MusicCapsuleCard.CARD_WIDTH - MusicCapsuleCard.PROGRESS_X;
            if (MusicCapsuleCard.INSTANCE.click(press[0], press[1])) {
                this.draggingProgress = onProgress;
            }
        }
    }

    /** 不暂停游戏（岛内搜索同款）。 */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        active = false;
        this.draggingProgress = false;
        pendingPressDesign = null;
        // 外部换屏（开播放器、开聊天等）也把卡收回去，别留一张悬空的展开态。
        DownloadDynamicIsland.collapseMusicCard();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY) {
        // 不画任何东西。这里只做两件事：拖动 seek 的持续驱动，和「卡已不在了就退场」的收尾。
        if (!DownloadDynamicIsland.isMusicCardOpen()) {
            closeSelf();
            return;
        }
        // 与 mouseClicked 同括号：换算因子只有进布局轴才有真值（岛内搜索同款纪律）。
        boolean ownedLayout = !RenderSystem.FIXED_SCALE;
        if (ownedLayout) WindowAdaptiveScale.beginForced();
        try {
            RenderSystem.refreshResolution();
            double[] point = toLayoutPoint(mouseX, mouseY);
            if (point == null) return;
            double[] design = toDesignPoint(point[0], point[1]);
            if (design == null) return;
            boolean lmb = Mouse.isButtonDown(0);
            if (this.draggingProgress && lmb) {
                // 拖动中：进度条 x 直接换算成比例 seek。节流交给 CloudMusic 侧，这里按帧给即可。
                MusicCapsuleCard.INSTANCE.seekToFraction(
                        (design[0] - MusicCapsuleCard.PROGRESS_X)
                                / (MusicCapsuleCard.CARD_WIDTH - MusicCapsuleCard.PROGRESS_X * 2.0));
            } else if (this.draggingProgress && !lmb) {
                this.draggingProgress = false;
            }
        } finally {
            if (ownedLayout) WindowAdaptiveScale.end();
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (keyCode == com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE) {
            closeAndCollapse();
        }
        // 其余按键一律吞掉：浮层只为迷你卡服务，不该有字符通道。
    }

    @Override
    public void mouseClicked(int mX, int mY, int mouseButton) {
        if (mouseButton != 0) return;
        // 与 drawScreen 同括号：换算因子只有进布局轴才有真值（岛内搜索 mouseClicked 同款纪律）。
        boolean ownedLayout = !RenderSystem.FIXED_SCALE;
        if (ownedLayout) WindowAdaptiveScale.beginForced();
        try {
            RenderSystem.refreshResolution();
            double[] point = toLayoutPoint(mX, mY);
            if (point == null) return;
            double[] design = toDesignPoint(point[0], point[1]);
            if (design == null) {
                // 点在卡外：收起（透明浮层的「点空白收回」语义）。
                closeAndCollapse();
                return;
            }
            boolean onProgress = Math.abs(design[1] - MusicCapsuleCard.PROGRESS_Y) <= 10.0
                    && design[0] >= MusicCapsuleCard.PROGRESS_X
                    && design[0] <= MusicCapsuleCard.CARD_WIDTH - MusicCapsuleCard.PROGRESS_X;
            if (MusicCapsuleCard.INSTANCE.click(design[0], design[1])) {
                this.draggingProgress = onProgress;
            }
            // 卡内空白处：吞掉点击但不收起——误触按钮边缘不该把整张卡点没。
        } finally {
            if (ownedLayout) WindowAdaptiveScale.end();
        }
    }

    // ------------------------------------------------------------------ 坐标换算

    /** Screen GUI 坐标 → 布局轴坐标（岛侧迷你卡矩形所在的轴）。 */
    private double[] toLayoutPoint(double mouseX, double mouseY) {
        double xScale = RenderSystem.getWidthNotScaled() / RenderSystem.getWidth();
        double yScale = RenderSystem.getHeightNotScaled() / RenderSystem.getHeight();
        if (!Double.isFinite(xScale) || !Double.isFinite(yScale) || xScale <= 0 || yScale <= 0) return null;
        return new double[]{mouseX / xScale, mouseY / yScale};
    }

    /** 布局轴坐标 → 迷你卡 296×130 设计空间坐标；不在卡内返回 null。 */
    private double[] toDesignPoint(double layoutX, double layoutY) {
        double[] bounds = DownloadDynamicIsland.musicCardBounds();
        if (bounds == null) return null;
        if (layoutX < bounds[0] || layoutX > bounds[0] + bounds[2]
                || layoutY < bounds[1] || layoutY > bounds[1] + bounds[3]) {
            return null;
        }
        double scale = bounds[3] / MusicCapsuleCard.CARD_HEIGHT;
        if (scale <= 0 || !Double.isFinite(scale)) return null;
        return new double[]{(layoutX - bounds[0]) / scale, (layoutY - bounds[1]) / scale};
    }
}
