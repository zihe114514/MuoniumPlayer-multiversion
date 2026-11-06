package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.onboarding.OnboardingManager;
import com.muoniumplayer.core.screens.onboarding.OnboardingState;
import com.muoniumplayer.core.utils.logging.Logger;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

/**
 * H 键设置声明里「模板预设」那一行的逻辑。七棵树的 {@code HudSettingsModel} 只引用这里，逐字相同。
 *
 * <p>内置设置界面（{@link GuiHudEditorPro}）的入口是标题栏那根魔法棒，弹窗就开在设置页里；
 * 这一行是给 MuoniumConfig / OneConfig / ModernUI 那几层兜底的 —— 它们没有那一排标题栏按钮，
 * 点了去播放器里打开同一张面板。内置界面开着时这一行藏起来（{@link #listedInModel}），
 * 免得同一页里出现两个入口。</p>
 */
public final class PresetQuickActions {

    private static final Logger LOGGER = new Logger("PresetQuickActions");

    public static final String OPEN_PANEL_LABEL = "打开模板预设";
    public static final String OPEN_PANEL_HINT = "在播放器里打开模板预设：内置风格、我的预设、按部分勾选套用、管理分享码。";

    /** 内置设置界面开着：它的标题栏有魔法棒。 */
    private static volatile boolean headerEntryShown;

    private PresetQuickActions() {
    }

    /** 内置设置界面打开 / 关闭时各调一次。 */
    static void setHeaderEntryShown(boolean shown) {
        headerEntryShown = shown;
    }

    /** 这一行该不该出现：内置界面里不出现（那里用标题栏的魔法棒），其余几层照常出现。 */
    public static boolean listedInModel() {
        return !headerEntryShown;
    }

    /**
     * 去播放器里打开模板预设面板。
     *
     * <p>经 {@link MultiThreadingUtil#runOnMainThread}：ModernUI 那一层的点击回调在它自己的 UI 线程上，
     * 而切屏幕只能在客户端主线程做；其余几层本来就在主线程，这是原地直接跑。</p>
     */
    public static void openPanel() {
        MultiThreadingUtil.runOnMainThread(PresetQuickActions::openPanelNow);
    }

    private static void openPanelNow() {
        try {
            OnboardingManager.openPlayerEntry();
            // 新手引导还没做完时，上面打开的是引导页：别给后台那个播放器实例挂预设面板，
            // 否则等玩家走完引导，第一眼看到的会是一块莫名其妙的面板。
            if (OnboardingState.isCompleted()) {
                NCMScreen.getInstance().openPresets();
            }
        } catch (Throwable failure) {
            LOGGER.warn("打不开模板预设面板: " + failure);
        }
    }
}
