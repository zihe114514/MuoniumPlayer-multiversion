package com.muoniumplayer.core.screens.hud;

import com.muoniumplayer.core.preset.PresetConfigBridge;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

/** Shared HUD-only entry points. The version-local model supplies screen opening and parent navigation. */
public final class PresetSettingsActions {
    public enum Mode { MANAGE, SAVE, UNDO, IMPORT }

    private PresetSettingsActions() { }

    public static void manage() { open(Mode.MANAGE); }
    public static void save() { open(Mode.SAVE); }
    public static void undo() { open(Mode.UNDO); }
    public static void importPreset() { open(Mode.IMPORT); }

    public static void open(Mode mode) {
        if (mode == null) return;
        MultiThreadingUtil.runOnMainThread(() -> {
            if (!PresetConfigBridge.tryOpenUi(mode.name().toLowerCase(java.util.Locale.ROOT)))
                HudSettingsModel.openPresetSettings(mode);
        });
    }
}
