package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.HudPresetStateSync;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.settings.HudConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Shared hud.json policy; seven version-tree seams retain their own load primitives. */
public final class HudPresetDomain implements PresetDomain {
    public static final String ID = "hud";
    private static final KeyPolicy POLICY = KeyPolicy.builder()
            .facet(Facet.VISIBILITY, "musicInfoEnabled", "musicLyricsEnabled", "spectrumInHud", "dynamicIslandEnabled", "worldLyricsEnabled")
            .facet(Facet.LYRICS,
                    "lyricColorRgb", "currentLyricColorRgb", "currentLyricEffectsEnabled", "osdKaraokeEmphasisEnabled", "normalLyricEffectsEnabled",
                    "lyricEdgeFadeEnabled", "currentLineScale", "currentWordScale", "currentGlowStrength", "lyricGlowSpread", "fullscreenGlowStrength",
                    "fullscreenLyricStyle", "fullscreenLyricEdgeBlur", "osdLyricStyle", "osdLyricColorFollowCover", "osdKaraokeTransitionWidth",
                    "osdKaraokePulseStrength", "osdKaraokeSmoothing", "osdDuetSpread", "normalOpacity", "normalScale", "normalGlowStrength",
                    "normalLineSpacing", "edgeFadeSize", "scrollSmoothness", "secondaryOpacity", "cadenzaDecorColorMode", "cadenzaDecorColorCount",
                    "cadenzaBeatsPerColor", "cadenzaDecorColors", "lyricFontScale", "luminousWordSpacing", "luminousFloatAmount", "luminousWordRotation",
                    "partitaStaggerMin", "partitaStaggerMax", "partitaGuideLines", "fumeTextHoldRatio", "fumeCameraSpeed", "fumeHeroScale",
                    "fumeGeometricBackground", "cappellaBubbleWidth", "cappellaAvatarSource", "cappellaEmojiEnabled", "cappellaEmojiChance",
                    "tiltSplitProbability", "tiltStyleProbability", "tiltColorScheme", "dioramaDepthSpread", "dioramaDust", "pendoloArcRadius",
                    "pendoloArcAngle", "pendoloActiveScale", "pendoloGearDecor")
            .facet(Facet.BACKGROUND, "fullscreenLyricBackgroundMode", "fullscreenLyricFluidStyle", "fullscreenLyricFluidSaturation",
                    "fullscreenLyricFluidAudio", "fullscreenLyricBackgroundLayers", "fullscreenLyricLayerLatentDither", "fullscreenLyricLayerSora")
            .facet(Facet.WORLD, "worldLyricsDistance", "worldLyricsFacePlayer", "worldLyricsKeepAway", "worldLyricsSpreadDeg", "worldLyricsScale",
                    "worldLyricsModernStyle", "worldLyricsSplitLines", "worldLyricsGlowEnabled", "worldLyricsGlowStrength", "worldLyricsGlowSpread",
                    "worldLyricsGlowColorRgb", "worldLyricsGlowColorMode", "worldLyricsColorRgb", "worldLyricsColorCustom", "worldLyricsWordPop",
                    "worldLyricsTextFlash", "worldLyricsCadenza", "worldLyricsHuiguang", "worldLyricsKeywordColors", "worldLyricsStayOnScreen",
                    "worldLyricsKeepDistance", "worldLyricsAboveGround", "worldLyricsTranslation", "worldLyricsTransEffects", "worldLyricsCoverColorMode",
                    "worldLyricsCoverGradient", "worldLyricsCoverPaletteRgb0", "worldLyricsCoverPaletteRgb1", "worldLyricsCoverPaletteRgb2",
                    "worldLyricsCoverPaletteRgb3", "worldLyricsGlowMode")
            .facet(Facet.ISLAND, "dynamicIslandScale", "dynamicIslandTextScale", "dynamicIslandMaxWidth", "dynamicIslandProgressHeight",
                    "dynamicIslandCompletionHoldSeconds", "dynamicIslandQueueIntervalSeconds", "dynamicIslandStyle", "dynamicIslandExpandSpeed",
                    "dynamicIslandCollapseSpeed", "dynamicIslandContentSpeed", "dynamicIslandEntranceDuration", "dynamicIslandOvershoot",
                    "dynamicIslandSpinnerSpeed", "dynamicIslandAlwaysOn", "dynamicIslandAmbientFps", "dynamicIslandAmbientPing", "dynamicIslandAmbientClock",
                    "dynamicIslandAmbientNowPlaying", "dynamicIslandAmbientRotate", "dynamicIslandAmbientStatusSeconds", "dynamicIslandAmbientNowPlayingSeconds",
                    "islandSearchEnabled", "islandMiniCardScale", "islandMusicShowsLyric", "islandHoverExpand")
            .facet(Facet.PANEL, "blossomBackdropBlur", "blossomPanelOpacity", "blossomVisibleLines", "blossomRhythmBorder", "blossomBorderStrength",
                    "glassSurfacePlayer", "glassSurfaceIsland", "glassSurfaceDesktopLyric", "glassSurfaceEditors", "glassBlurSigma", "glassBlurPlayer",
                    "glassBlurIsland", "glassBlurDesktopLyric", "glassBlurEditors", "glassBloom", "glassFusion", "glassTintStrength", "glassEdgeGlow",
                    "infoCardBackdrop", "animatedCoverEnabled")
            .facet(Facet.SPECTRUM, "spectrumEnabled", "spectrumHudStyle", "spectrumInPlayer", "spectrumFullscreenStyle", "spectrumBarCountPreset",
                    "spectrumCustomBarCount", "spectrumColorSource", "spectrumOpacity", "spectrumHeight")
            .facet(Facet.MOTION, "playerFadeSpeed", "playerUiSwitchSpeed")
            .facet(Facet.LAYOUT, "infoX", "infoY", "infoScale", "lyricX", "lyricY", "lyricScale", "lyricWidth", "lyricHeight", "dynamicIslandY", "lyricSearchUiScale")
            .facet(Facet.PERFORMANCE, "glassBackdropScale", "glassRealtimeCapture", "worldLyricsQualityTier", "worldLyricsGlowTier", "worldLyricsHudDraw", "fullscreenLyricFluidStatic")
            .exclude("HUD 设置界面自己的状态，不是分享外观", "hudEditorUi", "hudEditorPanelX", "hudEditorPanelY", "hudEditorLastCategory", "hudEditorPanelScaleW", "hudEditorPanelScaleH")
            .exclude("材质迁移标记由原配置维护", "glassMaterialVersion")
            .exclude("设备级实验 opt-in 不能被外来预设打开", "glassMobileExperimental")
            .exclude("播放态来源取决于本地播放器环境", "playbackFeed")
            .exclude("SMTC 时间轴校准跟本机声源与输出链路绑定，外来预设的值对不上", "smtcTimelineDelayMs", "smtcTimelineWindowMs")
            .exclude("音量只进可信本地完整状态，不分享", "playerVolume")
            .exclude("配色来源由主题域调用原 transition 接缝", "themeColorSource")
            .enumMax("fullscreenLyricStyle", () -> HudConfig.FULLSCREEN_LYRIC_STYLE_MAX)
            .enumMax("fullscreenLyricBackgroundMode", () -> HudConfig.FULLSCREEN_LYRIC_BACKGROUND_MODE_MAX)
            .enumMax("fullscreenLyricFluidStyle", () -> HudConfig.FULLSCREEN_LYRIC_FLUID_STYLE_MAX)
            .enumMax("osdLyricStyle", () -> HudConfig.OSD_LYRIC_STYLE_MAX)
            .enumMax("dynamicIslandStyle", () -> HudConfig.DYNAMIC_ISLAND_STYLE_MAX)
            .enumMax("infoCardBackdrop", () -> HudConfig.INFO_CARD_BACKDROP_MAX)
            .enumMax("worldLyricsGlowMode", () -> 1)
            .enumMax("worldLyricsGlowColorMode", () -> 1)
            .enumMax("worldLyricsTranslation", () -> 2)
            .enumMax("worldLyricsGlowTier", () -> 2)
            .enumMax("worldLyricsCoverColorMode", () -> 2)
            .enumMax("worldLyricsQualityTier", () -> 7)
            .enumMax("cappellaAvatarSource", () -> 2)
            .enumMax("tiltColorScheme", () -> 3)
            .enumMax("spectrumHudStyle", () -> 1)
            .enumMax("spectrumFullscreenStyle", () -> 1)
            .enumMax("spectrumBarCountPreset", () -> 5)
            .enumMax("spectrumColorSource", () -> 1)
            .enumMax("fullscreenLyricBackgroundLayers", () -> 3)
            .enumMax("cadenzaDecorColorMode", () -> 1)
            .build();
    // Published PR#13 prefix, frozen: do not sort, delete or insert. New shareable keys append after 166.
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList(
            /*   1 */ "musicInfoEnabled", "musicLyricsEnabled", "spectrumInHud", "dynamicIslandEnabled",
            /*   5 */ "worldLyricsEnabled", "lyricColorRgb", "currentLyricColorRgb", "currentLyricEffectsEnabled",
            /*   9 */ "osdKaraokeEmphasisEnabled", "normalLyricEffectsEnabled", "lyricEdgeFadeEnabled", "currentLineScale",
            /*  13 */ "currentWordScale", "currentGlowStrength", "lyricGlowSpread", "fullscreenGlowStrength",
            /*  17 */ "fullscreenLyricStyle", "fullscreenLyricEdgeBlur", "osdLyricStyle", "osdLyricColorFollowCover",
            /*  21 */ "osdKaraokeTransitionWidth", "osdKaraokePulseStrength", "osdKaraokeSmoothing", "osdDuetSpread",
            /*  25 */ "normalOpacity", "normalScale", "normalGlowStrength", "normalLineSpacing",
            /*  29 */ "edgeFadeSize", "scrollSmoothness", "secondaryOpacity", "cadenzaDecorColorMode",
            /*  33 */ "cadenzaDecorColorCount", "cadenzaBeatsPerColor", "cadenzaDecorColors", "lyricFontScale",
            /*  37 */ "luminousWordSpacing", "luminousFloatAmount", "luminousWordRotation", "partitaStaggerMin",
            /*  41 */ "partitaStaggerMax", "partitaGuideLines", "fumeTextHoldRatio", "fumeCameraSpeed",
            /*  45 */ "fumeHeroScale", "fumeGeometricBackground", "cappellaBubbleWidth", "cappellaAvatarSource",
            /*  49 */ "cappellaEmojiEnabled", "cappellaEmojiChance", "tiltSplitProbability", "tiltStyleProbability",
            /*  53 */ "tiltColorScheme", "dioramaDepthSpread", "dioramaDust", "pendoloArcRadius",
            /*  57 */ "pendoloArcAngle", "pendoloActiveScale", "pendoloGearDecor", "fullscreenLyricBackgroundMode",
            /*  61 */ "fullscreenLyricFluidStyle", "fullscreenLyricFluidSaturation", "fullscreenLyricFluidAudio", "fullscreenLyricBackgroundLayers",
            /*  65 */ "fullscreenLyricLayerLatentDither", "fullscreenLyricLayerSora", "worldLyricsDistance", "worldLyricsFacePlayer",
            /*  69 */ "worldLyricsKeepAway", "worldLyricsSpreadDeg", "worldLyricsScale", "worldLyricsModernStyle",
            /*  73 */ "worldLyricsSplitLines", "worldLyricsGlowEnabled", "worldLyricsGlowStrength", "worldLyricsGlowSpread",
            /*  77 */ "worldLyricsGlowColorRgb", "worldLyricsGlowColorMode", "worldLyricsColorRgb", "worldLyricsColorCustom",
            /*  81 */ "worldLyricsWordPop", "worldLyricsTextFlash", "worldLyricsCadenza", "worldLyricsHuiguang",
            /*  85 */ "worldLyricsKeywordColors", "worldLyricsStayOnScreen", "worldLyricsKeepDistance", "worldLyricsAboveGround",
            /*  89 */ "worldLyricsTranslation", "worldLyricsTransEffects", "worldLyricsCoverColorMode", "worldLyricsCoverGradient",
            /*  93 */ "worldLyricsCoverPaletteRgb0", "worldLyricsCoverPaletteRgb1", "worldLyricsCoverPaletteRgb2", "worldLyricsCoverPaletteRgb3",
            /*  97 */ "dynamicIslandScale", "dynamicIslandTextScale", "dynamicIslandMaxWidth", "dynamicIslandProgressHeight",
            /* 101 */ "dynamicIslandCompletionHoldSeconds", "dynamicIslandQueueIntervalSeconds", "dynamicIslandStyle", "dynamicIslandExpandSpeed",
            /* 105 */ "dynamicIslandCollapseSpeed", "dynamicIslandContentSpeed", "dynamicIslandEntranceDuration", "dynamicIslandOvershoot",
            /* 109 */ "dynamicIslandSpinnerSpeed", "dynamicIslandAlwaysOn", "dynamicIslandAmbientFps", "dynamicIslandAmbientPing",
            /* 113 */ "dynamicIslandAmbientClock", "dynamicIslandAmbientNowPlaying", "dynamicIslandAmbientRotate", "dynamicIslandAmbientStatusSeconds",
            /* 117 */ "dynamicIslandAmbientNowPlayingSeconds", "islandSearchEnabled", "blossomBackdropBlur", "blossomPanelOpacity",
            /* 121 */ "blossomVisibleLines", "blossomRhythmBorder", "blossomBorderStrength", "glassSurfacePlayer",
            /* 125 */ "glassSurfaceIsland", "glassSurfaceDesktopLyric", "glassSurfaceEditors", "glassBlurSigma",
            /* 129 */ "glassBlurPlayer", "glassBlurIsland", "glassBlurDesktopLyric", "glassBlurEditors",
            /* 133 */ "glassBloom", "glassFusion", "glassTintStrength", "glassEdgeGlow",
            /* 137 */ "infoCardBackdrop", "animatedCoverEnabled", "spectrumEnabled", "spectrumHudStyle",
            /* 141 */ "spectrumInPlayer", "spectrumFullscreenStyle", "spectrumBarCountPreset", "spectrumCustomBarCount",
            /* 145 */ "spectrumColorSource", "spectrumOpacity", "spectrumHeight", "playerFadeSpeed",
            /* 149 */ "playerUiSwitchSpeed", "infoX", "infoY", "infoScale",
            /* 153 */ "lyricX", "lyricY", "lyricScale", "lyricWidth",
            /* 157 */ "lyricHeight", "dynamicIslandY", "lyricSearchUiScale", "glassBackdropScale",
            /* 161 */ "glassRealtimeCapture", "worldLyricsQualityTier", "worldLyricsGlowTier", "worldLyricsHudDraw",
            /* 165 */ "fullscreenLyricFluidStatic", "worldLyricsGlowMode",
            /* 167 */ "islandMiniCardScale", "islandMusicShowsLyric", "islandHoverExpand"));
    public String id() { return ID; }
    public String displayName() { return "HUD 与歌词"; }
    public int fragmentVersion() { return 1; }
    public int shareCode() { return 1; }
    public List<String> shareKeys() { return SHARE_KEYS; }
    public KeyPolicy policy() { return POLICY; }
    public static KeyPolicy staticPolicy() { return POLICY; }
    public JsonObject snapshot() { return HudConfig.toJson(); }
    public JsonObject defaults() { return HudConfig.defaultsJson(); }
    public void apply(JsonObject state, CompatReport report) {
        HudConfig.applyJson(state);
        HudPresetStateSync.sync();
        HudConfig.savePresetState();
    }
}
