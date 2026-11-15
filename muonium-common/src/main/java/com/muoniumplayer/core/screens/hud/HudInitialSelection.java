package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;

/**
 * 检测到独立 OneConfig 时，避免它与 MuoniumConfig 冲突。
 *
 * <p>已接入检测的 Fabric 入口使用加载器查询 mod ID {@code oneconfig}，并将结果传入本类。
 * 已核对的 MuoniumConfig 1.8.0 Fabric 包的 mod ID 是 {@code muoniumconfigv1}，
 * 与独立 OneConfig 不同；单独安装该包不会因其自身 ID 命中这项检测。</p>
 *
 * <p>检测到独立 OneConfig 且当前选择为 {@code mconfig} 时，将
 * {@link HudConfig#hudEditorUi} 切换为 {@code builtin}，并调用 {@link HudConfig#save()}
 * 保存；OneConfig 仍存在时，启动检查再次遇到 {@code mconfig} 也会重新切换。</p>
 */
public final class HudInitialSelection {
    private static volatile boolean oneConfigInstalled;
    private static volatile boolean detectionReady;
    private static volatile boolean noticePending;
    private static long noticeShownAt;

    private HudInitialSelection() {}

    public static void setOneConfigInstalled(boolean installed) {
        oneConfigInstalled = installed;
        detectionReady = true;
    }

    public static boolean isOneConfigInstalled() {
        return oneConfigInstalled;
    }

    static String choose(boolean oneConfig) {
        return oneConfig ? HudConfig.HUD_EDITOR_UI_BUILTIN : HudConfig.HUD_EDITOR_UI_MCONFIG;
    }

    /**
     * 检测到独立 OneConfig 且当前界面为 {@code mconfig} 时，切换到内置界面并尝试保存。
     *
     * @see HudConfig#save()
     */
    public static synchronized void initialize() {
        if (!detectionReady || !oneConfigInstalled
                || !HudConfig.HUD_EDITOR_UI_MCONFIG.equals(HudConfig.hudEditorUi)) {
            return;
        }
        HudConfig.hudEditorUi = choose(oneConfigInstalled);
        HudConfig.save();

        noticeShownAt = 0L;
        noticePending = true;

        System.out.println("[MuoniumPlayer] 检测到已安装 OneConfig，为避免冲突已将 HUD 设置界面切换为内置界面，并且不下载MuoniumConfig");
    }

    /**
     * 仅用于显示一次性提示文案（10 秒后自动隐藏）。
     * 配置修改和保存请求在 {@link #initialize()} 中进行，此处不再复原。
     */
    public static synchronized boolean noticeVisible() {
        if (!noticePending) return false;
        long now = System.nanoTime();
        if (noticeShownAt == 0L) noticeShownAt = now;
        if (now - noticeShownAt >= 10_000_000_000L) {
            noticePending = false;
            return false;
        }
        return true;
    }
}
