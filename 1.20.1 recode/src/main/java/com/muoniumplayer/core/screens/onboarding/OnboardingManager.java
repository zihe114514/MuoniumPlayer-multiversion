package com.muoniumplayer.core.screens.onboarding;

import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.screens.ncm.NCMScreen;

/** Single entry point for the player-opening decision. */
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
        // Skipping is an explicit opt-out from first-run setup. The existing settings
        // and account entry points remain available from the regular player UI.
        OnboardingState.markCompleted();
        api.displayScreen(NCMScreen.getInstance());
    }
}
