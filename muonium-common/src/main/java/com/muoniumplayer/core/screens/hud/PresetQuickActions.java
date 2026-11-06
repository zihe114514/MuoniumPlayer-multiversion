package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.preset.PresetService;
import com.muoniumplayer.core.preset.codec.PresetFormatException;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.StylePreset;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.onboarding.OnboardingManager;
import com.muoniumplayer.core.screens.onboarding.OnboardingState;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import java.util.EnumSet;
import java.util.Set;

/**
 * H 键设置页里「模板预设」那一组的全部逻辑。七棵树的 {@code HudSettingsModel} 只在「界面与主题」页插一组
 * 条目引用这里，逐字相同 —— 改行为只改这一个共享文件。
 *
 * <p>设置界面拿不到套用页那种分面勾选，所以这里的操作都用默认勾选（布局位置、画质性能不动），并且都可撤销。
 * 点完的结果在按钮文字上停留几秒（标签是 Supplier，每帧现读），同时发一条灵动岛提示。</p>
 *
 * <p><b>线程</b>：五个动作都经 {@link MultiThreadingUtil#runOnMainThread} 执行。内置 / MConfig / OneConfig 三层本来就在客户端主线程，
 * 这是原地直接跑；ModernUI 那一层的点击回调在它自己的 UI 线程上，而切屏幕、读写剪贴板（GLFW）、套用预设
 * （改 HUD 组件与主题）都只能在主线程做，所以排到下一 tick。</p>
 */
public final class PresetQuickActions {

    public static final String OPEN_PANEL_HINT = "在播放器里打开模板预设：内置风格、我的预设、按部分勾选套用、管理分享码。";
    public static final String MINIMAL_HINT = "只听歌：隐藏桌面歌词、信息卡、灵动岛、3D 歌词与 HUD 频谱；样式不变，可以撤销。";
    public static final String UNDO_HINT = "回到上一次套用之前的样子；连点可以继续往回撤。";
    public static final String COPY_HINT = "把当前外观做成分享码复制到剪贴板（只写改过的项；不含布局位置、画质与音效）。";
    public static final String PASTE_HINT = "读取剪贴板里的分享码，存进「我的预设」并立即套用（布局与画质不动，可以撤销）。";

    /** 结果在按钮上停留多久。 */
    private static final long FLASH_MILLIS = 4000L;
    /** 按钮放不下长句：结果文字截到这么多字，完整原因走灵动岛。 */
    private static final int FLASH_CHARS = 18;

    private static volatile String flashKey;
    private static volatile String flashText;
    private static volatile long flashUntil;

    private PresetQuickActions() {
    }

    // ---- 打开面板 ----

    public static String openPanelLabel() {
        return flashed("open", "打开模板预设面板");
    }

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
            flash("open", "打不开播放器", failure.getMessage());
        }
    }

    // ---- 简约 ----

    public static String minimalLabel() {
        return flashed("minimal", "一键「简约」：只听歌");
    }

    public static void applyMinimal() {
        MultiThreadingUtil.runOnMainThread(PresetQuickActions::applyMinimalNow);
    }

    private static void applyMinimalNow() {
        try {
            StylePreset minimal = builtin("builtin:minimal");
            if (minimal == null) {
                flash("minimal", "找不到内置「简约」", null);
                return;
            }
            report("minimal", "已套用「简约」", service().apply(minimal, service().defaultSelection(minimal)));
        } catch (Throwable failure) {
            flash("minimal", "套用失败", failure.getMessage());
        }
    }

    // ---- 撤销 ----

    public static String undoLabel() {
        return flashed("undo", service().canUndo() ? "撤销上次套用" : "撤销上次套用（暂无）");
    }

    public static void undo() {
        MultiThreadingUtil.runOnMainThread(PresetQuickActions::undoNow);
    }

    private static void undoNow() {
        try {
            if (!service().canUndo()) {
                flash("undo", "没有可以撤销的套用", null);
                return;
            }
            CompatReport result = service().undo();
            if (result == null) {
                flash("undo", "备份读不出来", null);
            } else {
                report("undo", "已撤销", result);
            }
        } catch (Throwable failure) {
            flash("undo", "撤销失败", failure.getMessage());
        }
    }

    // ---- 复制分享码 ----

    public static String copyLabel() {
        return flashed("copy", "复制当前外观的分享码");
    }

    public static void copyAppearance() {
        MultiThreadingUtil.runOnMainThread(PresetQuickActions::copyAppearanceNow);
    }

    private static void copyAppearanceNow() {
        try {
            Set<Facet> facets = EnumSet.noneOf(Facet.class);
            for (Facet facet : Facet.withoutOptIn(Facet.ofGroup(Facet.Group.APPEARANCE))) {
                if (service().engine().availableFacets().contains(facet)) {
                    facets.add(facet);
                }
            }
            StylePreset snapshot = service().snapshot("我的外观", facets);
            String text = service().shareText(snapshot);
            KeyboardUtils.setClipboardString(text);
            flash("copy", "已复制（" + text.length() + " 字）", "分享码已复制，共 " + text.length() + " 字");
        } catch (Throwable failure) {
            flash("copy", "生成分享码失败", failure.getMessage());
        }
    }

    // ---- 粘贴并套用 ----

    public static String pasteLabel() {
        return flashed("paste", "粘贴分享码并套用");
    }

    public static void pasteAndApply() {
        MultiThreadingUtil.runOnMainThread(PresetQuickActions::pasteAndApplyNow);
    }

    private static void pasteAndApplyNow() {
        try {
            StylePreset imported = service().importText(KeyboardUtils.getClipboardString());
            report("paste", "已套用「" + imported.meta().name() + "」",
                    service().apply(imported, service().defaultSelection(imported)));
        } catch (PresetFormatException bad) {
            flash("paste", bad.getMessage(), bad.getMessage());
        } catch (Throwable failure) {
            flash("paste", "导入失败", failure.getMessage());
        }
    }

    // ---- 小工具 ----

    private static PresetService service() {
        return PresetService.get();
    }

    private static StylePreset builtin(String id) {
        for (StylePreset preset : service().builtins()) {
            if (id.equals(preset.meta().id())) {
                return preset;
            }
        }
        return null;
    }

    private static void report(String key, String success, CompatReport result) {
        if (result.hasFailures()) {
            flash(key, "部分没套上", result.failures().get(0));
        } else if (result.changedKeys() == 0) {
            flash(key, "已经是这个样子了", null);
        } else {
            String detail = "改动 " + result.changedKeys() + " 项"
                    + (result.lines().isEmpty() ? "" : " · " + result.lines().get(0));
            flash(key, success, detail);
        }
    }

    private static void flash(String key, String label, String detail) {
        String shortLabel = label == null ? "" : label;
        if (shortLabel.length() > FLASH_CHARS) {
            shortLabel = shortLabel.substring(0, FLASH_CHARS - 1) + "…";
        }
        flashText = shortLabel;
        flashKey = key;
        flashUntil = System.currentTimeMillis() + FLASH_MILLIS;
        try {
            DownloadDynamicIsland.showPresetNotice("模板预设", detail == null || detail.isEmpty() ? label : detail);
        } catch (Throwable ignored) {
            // 灵动岛还没准备好不要紧：按钮上的文字已经说明了结果。
        }
    }

    private static String flashed(String key, String idle) {
        String text = flashText;
        return key.equals(flashKey) && text != null && System.currentTimeMillis() < flashUntil ? text : idle;
    }
}
