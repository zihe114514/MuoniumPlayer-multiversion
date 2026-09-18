package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.screens.ncm.NCMScreen;

/** 播放器开启决策的唯一入口。 */
public final class OnboardingManager implements SharedConstants {
    private OnboardingManager() {
    }

    public static boolean shouldShow() {
        return !OnboardingState.isCompleted();
    }

    public static void openPlayerEntry() {
        if (shouldShow()) {
            api.displayScreen(new OnboardingScreen());
        } else {
            api.displayScreen(NCMScreen.getInstance());
        }
    }

    public static void finish(OnboardingScreen screen) {
        OnboardingState.markCompleted();
        api.displayScreen(NCMScreen.getInstance());
    }

    public static void skip(OnboardingScreen screen) {
        // 跳过是对首次运行设置的显式 opt-out。既有设置
        //和账号入口仍可从普通播放器 UI 访问。
        OnboardingState.markCompleted();
        api.displayScreen(NCMScreen.getInstance());
    }
}
