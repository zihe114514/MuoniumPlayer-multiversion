package com.muoniumplayer.core.screens.onboarding;

/**
 * 单个新手引导页。实现自己负责渲染和点击
 * 处理，而导航和共享状态留在 {@link OnboardingScreen}。
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
