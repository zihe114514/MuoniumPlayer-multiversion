package com.muoniumplayer.core.screens.onboarding;

/**
 * A single onboarding page. Implementations own their own rendering and click
 * handling, while navigation and shared state stay in {@link OnboardingScreen}.
 */
public interface OnboardingStepView {
    void render(OnboardingController controller,
                double x, double y, double width, double height,
                double mouseX, double mouseY);

    boolean mouseClicked(OnboardingController controller,
                         double x, double y, double width, double height,
                         double mouseX, double mouseY, int mouseButton);

    default void close() {
    }
}
