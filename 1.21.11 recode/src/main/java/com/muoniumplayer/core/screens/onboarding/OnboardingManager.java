package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.screens.ncm.NCMScreen;

/**
 * 播放器开启决策的唯一入口。
 *
 * <p>只有两态：没做完设置 → 引导；做完或跳过 → 播放器。那枚描出来的「hello」<b>不是一页</b>，
 * 而是引导页自己挂着的覆盖层（见 {@code OnboardingOverlay}），所以它不该出现在这里的分支里——
 * 否则玩家收层的那一刻会看见界面换了一屏。</p>
 */
public final class OnboardingManager implements SharedConstants {
    private OnboardingManager() {
    }

    public static boolean shouldShow() {
        return !OnboardingState.isCompleted();
    }

    public static void openPlayerEntry() {
        if (OnboardingState.isCompleted()) {
            api.displayScreen(NCMScreen.getInstance());
        } else {
            api.displayScreen(new OnboardingScreen());
        }
    }

    public static void finish(OnboardingScreen screen) {
        OnboardingState.markCompleted();
        api.displayScreen(NCMScreen.getInstance());
    }

    public static void skip(OnboardingScreen screen) {
        // 跳过是对首次运行设置的显式 opt-out。既有设置
        //和账号入口仍可从普通播放器 UI 访问。
        skipAll();
    }

    /** 覆盖层上按 ESC：连引导一起不要，直接进播放器。 */
    public static void skipAll() {
        OnboardingState.markCompleted();
        api.displayScreen(NCMScreen.getInstance());
    }
}
