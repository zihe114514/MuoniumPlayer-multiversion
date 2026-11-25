package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;

/**
 * 旧入口保留的界面选择兼容层。内置 Skija 不依赖独立 OneConfig 的安装状态。
 * 旧 mconfig/oneconfig 值迁移到 builtin，ModernUI 的显式选择保持不变。
 */
public final class HudInitialSelection {
    private static volatile boolean oneConfigInstalled;

    private HudInitialSelection() {}

    public static void setOneConfigInstalled(boolean installed) {
        oneConfigInstalled = installed;
    }

    public static boolean isOneConfigInstalled() {
        return oneConfigInstalled;
    }

    static boolean wantsMuoniumConfig(boolean oneConfig, boolean selected) {
        return false;
    }

    static String choose(boolean detectionReady, boolean oneConfig, String current) {
        return HudConfig.HUD_EDITOR_UI_MODERNUI.equalsIgnoreCase(current)
                ? HudConfig.HUD_EDITOR_UI_MODERNUI : HudConfig.HUD_EDITOR_UI_BUILTIN;
    }

    /** 将旧入口内存中的界面选择按当前持久化规则迁移；没有独立配置包提示。 */
    public static synchronized void initialize() {
        String selected = choose(false, oneConfigInstalled, HudConfig.hudEditorUi);
        if (selected.equals(HudConfig.hudEditorUi)) return;
        HudConfig.hudEditorUi = selected;
        HudConfig.save();
    }

    /** 兼容旧提示层调用；内置设置无需下载或重启提示。 */
    public static boolean noticeVisible() {
        return false;
    }
}
