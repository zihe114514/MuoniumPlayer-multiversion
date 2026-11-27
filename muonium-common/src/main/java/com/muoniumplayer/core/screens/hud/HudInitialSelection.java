package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.settings.HudConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Owns the compatibility decision before the optional HUD dependency is downloaded. */
public final class HudInitialSelection {
    private static final Map<String, String> CONFLICTING_MODS = Map.of(
            "oneconfig", "OneConfig", "modernui", "Modern UI", "reglass", "ReGlass");

    private static volatile boolean oneConfigInstalled;
    private static volatile List<String> conflicts = List.of();
    private static volatile boolean noticePending;
    private static long noticeShownAt;

    private HudInitialSelection() {}

    public static void setOneConfigInstalled(boolean installed) {
        oneConfigInstalled = installed;
    }

    public static boolean isMuoniumConfigBlocked() {
        return oneConfigInstalled || !conflicts.isEmpty();
    }

    public static String allowedUi(String requested) {
        return choose(true, isMuoniumConfigBlocked(), requested);
    }

    static List<String> warningLines() {
        return List.of("致命崩溃警告 · 已阻止下载 MuoniumConfig",
                "检测到其他界面模组：" + String.join("、", conflicts),
                "强行安装 MuoniumConfig 可能导致游戏在下次启动时致命崩溃。",
                "MuoniumPlayer 的内置设置界面仍可使用。");
    }

    static boolean wantsMuoniumConfig(boolean oneConfig, boolean selected) {
        return !oneConfig && selected;
    }

    static String choose(boolean detectionReady, boolean oneConfig, String current) {
        return detectionReady && oneConfig && HudConfig.HUD_EDITOR_UI_MCONFIG.equals(current)
                ? HudConfig.HUD_EDITOR_UI_BUILTIN : current;
    }

    /** Called after loading HUD settings and before starting the download checks. */
    public static void initialize() {
        initialize(new File("mods"));
    }

    static synchronized void initialize(File modsDir) {
        List<String> detected = new ArrayList<>();
        for (String id : MuoniumConfigJarProbe.conflictingModIds(modsDir, CONFLICTING_MODS.keySet())) {
            detected.add(CONFLICTING_MODS.get(id));
        }
        if (oneConfigInstalled && !detected.contains("OneConfig")) {
            detected.add("OneConfig");
        }
        detected.sort(String::compareToIgnoreCase);
        conflicts = List.copyOf(detected);

        String selected = allowedUi(HudConfig.hudEditorUi);
        if (!selected.equals(HudConfig.hudEditorUi)) {
            HudConfig.hudEditorUi = selected;
            HudConfig.save();
        }
        noticeShownAt = 0L;
        noticePending = isMuoniumConfigBlocked();
        if (noticePending) {
            System.out.println("[MuoniumPlayer] 已阻止 MuoniumConfig 下载，检测到：" + String.join("、", conflicts));
        }
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
