package com.muoniumplayer.core.preset;

import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import com.muoniumplayer.core.settings.HudConfig;
import java.awt.Color;

/** One shared sync seam, called after apply/restore on the client main/render thread. No font/loader/UI wiring. */
public final class HudPresetStateSync {
    private HudPresetStateSync() {}
    public static void sync() {
        MuoniumPlayerExtension extension;
        try { extension = MuoniumPlayerExtension.getInstance(); }
        catch (LinkageError notInitialized) { return; }
        if (extension == null) return;
        if (extension.musicInfo != null) {
            extension.musicInfo.setEnabled(HudConfig.musicInfoEnabled);
            extension.musicInfo.volume.setValue((double) HudConfig.playerVolume);
        }
        if (extension.musicLyrics != null) {
            extension.musicLyrics.setEnabled(HudConfig.musicLyricsEnabled);
            extension.musicLyrics.lyricColor.setValue(new Color(HudConfig.lyricColorRgb, true));
            extension.musicLyrics.currentLyricColor.setValue(new Color(HudConfig.currentLyricColorRgb, true));
        }
        HudSettingsModel.syncLyricViewportModuleValues();
        HudSettingsModel.syncAnimatedCoverModuleValue();
    }
}
