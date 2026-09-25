package com.muoniumplayer.core.screens.onboarding;

/** 刻意小型的六个首次运行页。 */
public enum OnboardingStep {
    WELCOME("欢迎使用", "Welcome"),
    ACCOUNT("连接账号", "Account"),
    SOURCE("选择音源", "Source"),
    APPEARANCE("个性化播放器", "Appearance"),
    AUTOMIX("Automix 模型", "Automix"),
    FINISH("设置完成", "Finish");

    private final String title;
    private final String id;

    OnboardingStep(String title, String id) {
        this.title = title;
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public String getId() {
        return id;
    }
}
