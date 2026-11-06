package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.settings.HudConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * hud.json（{@link HudConfig}）。
 *
 * <p>{@code HudConfig} 是版本树各自一份的留守文件，但七棵树的 {@code toJson/applyJson/defaultsJson}
 * 同名同签名、键集合一致，所以这里在共享层写一次。哪棵树多了 / 少了键，守卫测试在那棵树上就红。</p>
 *
 * <p><b>新加 HudConfig 字段时</b>：在下面的策略里把它归到一个分面，或者用 {@code exclude} 写明理由 ——
 * 不归类，{@code PresetPolicyCompletenessTest} 会直接指出是哪一个键。</p>
 */
public final class HudPresetDomain implements PresetDomain {

    public static final String ID = "hud";

    private static final KeyPolicy POLICY = KeyPolicy.builder()
            .facet(Facet.VISIBILITY,
                    "musicInfoEnabled", "musicLyricsEnabled", "spectrumInHud",
                    "dynamicIslandEnabled", "worldLyricsEnabled")
            .facet(Facet.LYRICS,
                    "lyricColorRgb", "currentLyricColorRgb",
                    "currentLyricEffectsEnabled", "osdKaraokeEmphasisEnabled", "normalLyricEffectsEnabled",
                    "lyricEdgeFadeEnabled", "currentLineScale", "currentWordScale", "currentGlowStrength",
                    "lyricGlowSpread", "fullscreenGlowStrength", "fullscreenLyricStyle", "fullscreenLyricEdgeBlur",
                    "osdLyricStyle", "osdLyricColorFollowCover",
                    "osdKaraokeTransitionWidth", "osdKaraokePulseStrength", "osdKaraokeSmoothing", "osdDuetSpread",
                    "normalOpacity", "normalScale", "normalGlowStrength", "normalLineSpacing",
                    "edgeFadeSize", "scrollSmoothness", "secondaryOpacity",
                    "cadenzaDecorColorMode", "cadenzaDecorColorCount", "cadenzaBeatsPerColor", "cadenzaDecorColors",
                    "lyricFontScale", "luminousWordSpacing", "luminousFloatAmount", "luminousWordRotation",
                    "partitaStaggerMin", "partitaStaggerMax", "partitaGuideLines",
                    "fumeTextHoldRatio", "fumeCameraSpeed", "fumeHeroScale", "fumeGeometricBackground",
                    "cappellaBubbleWidth", "cappellaAvatarSource", "cappellaEmojiEnabled", "cappellaEmojiChance",
                    "tiltSplitProbability", "tiltStyleProbability", "tiltColorScheme",
                    "dioramaDepthSpread", "dioramaDust",
                    "pendoloArcRadius", "pendoloArcAngle", "pendoloActiveScale", "pendoloGearDecor")
            .facet(Facet.BACKGROUND,
                    "fullscreenLyricBackgroundMode", "fullscreenLyricFluidStyle", "fullscreenLyricFluidSaturation",
                    "fullscreenLyricFluidAudio", "fullscreenLyricBackgroundLayers",
                    "fullscreenLyricLayerLatentDither", "fullscreenLyricLayerSora")
            .facet(Facet.WORLD,
                    "worldLyricsDistance", "worldLyricsFacePlayer", "worldLyricsKeepAway", "worldLyricsSpreadDeg",
                    "worldLyricsScale", "worldLyricsModernStyle", "worldLyricsSplitLines",
                    "worldLyricsGlowEnabled", "worldLyricsGlowStrength", "worldLyricsGlowSpread",
                    "worldLyricsGlowColorRgb", "worldLyricsGlowColorMode", "worldLyricsColorRgb",
                    "worldLyricsColorCustom", "worldLyricsWordPop", "worldLyricsTextFlash", "worldLyricsCadenza",
                    "worldLyricsHuiguang", "worldLyricsKeywordColors", "worldLyricsStayOnScreen",
                    "worldLyricsKeepDistance", "worldLyricsAboveGround", "worldLyricsTranslation",
                    "worldLyricsTransEffects", "worldLyricsCoverColorMode", "worldLyricsCoverGradient",
                    "worldLyricsCoverPaletteRgb0", "worldLyricsCoverPaletteRgb1",
                    "worldLyricsCoverPaletteRgb2", "worldLyricsCoverPaletteRgb3")
            .facet(Facet.ISLAND,
                    "dynamicIslandScale", "dynamicIslandTextScale", "dynamicIslandMaxWidth",
                    "dynamicIslandProgressHeight", "dynamicIslandCompletionHoldSeconds",
                    "dynamicIslandQueueIntervalSeconds", "dynamicIslandStyle", "dynamicIslandExpandSpeed",
                    "dynamicIslandCollapseSpeed", "dynamicIslandContentSpeed", "dynamicIslandEntranceDuration",
                    "dynamicIslandOvershoot", "dynamicIslandSpinnerSpeed", "dynamicIslandAlwaysOn",
                    "dynamicIslandAmbientFps", "dynamicIslandAmbientPing", "dynamicIslandAmbientClock",
                    "dynamicIslandAmbientNowPlaying", "dynamicIslandAmbientRotate",
                    "dynamicIslandAmbientStatusSeconds", "dynamicIslandAmbientNowPlayingSeconds",
                    "islandSearchEnabled")
            .facet(Facet.PANEL,
                    "blossomBackdropBlur", "blossomPanelOpacity", "blossomVisibleLines", "blossomRhythmBorder",
                    "blossomBorderStrength",
                    "glassSurfacePlayer", "glassSurfaceIsland", "glassSurfaceDesktopLyric", "glassSurfaceEditors",
                    "glassBlurSigma", "glassBlurPlayer", "glassBlurIsland", "glassBlurDesktopLyric",
                    "glassBlurEditors", "glassBloom", "glassFusion", "glassTintStrength", "glassEdgeGlow",
                    "infoCardBackdrop", "animatedCoverEnabled")
            .facet(Facet.SPECTRUM,
                    "spectrumEnabled", "spectrumHudStyle", "spectrumInPlayer", "spectrumFullscreenStyle",
                    "spectrumBarCountPreset", "spectrumCustomBarCount", "spectrumColorSource",
                    "spectrumOpacity", "spectrumHeight")
            .facet(Facet.MOTION,
                    "playerFadeSpeed", "playerUiSwitchSpeed")
            .facet(Facet.LAYOUT,
                    "infoX", "infoY", "infoScale", "lyricX", "lyricY", "lyricScale", "lyricWidth", "lyricHeight",
                    "dynamicIslandY", "lyricSearchUiScale")
            .facet(Facet.PERFORMANCE,
                    "glassBackdropScale", "glassRealtimeCapture", "worldLyricsQualityTier", "worldLyricsGlowTier",
                    "worldLyricsHudDraw", "fullscreenLyricFluidStatic")
            .exclude("HUD 设置界面自己的状态（用哪套设置界面、面板摆在哪），不是外观",
                    "hudEditorUi", "hudEditorPanelX", "hudEditorPanelY", "hudEditorLastCategory",
                    "hudEditorPanelScaleW", "hudEditorPanelScaleH")
            .exclude("玻璃材质参数的迁移标记，由 GlassMaterialProfile 自己维护", "glassMaterialVersion")
            .exclude("手机档真玻璃的实验开关：设备级、用户自担风险的 opt-in，不能被别人的预设打开",
                    "glassMobileExperimental")
            .exclude("播放态来源（内部 / 镜像外部播放器）依赖本机环境，不是外观", "playbackFeed")
            .exclude("音量是听歌习惯，不是外观；随预设一换音量会吓人一跳", "playerVolume")
            .exclude("配色来源由主题域代管：切换必须走 NCMTheme.setColorSource 的过渡与动态配色重置",
                    "themeColorSource")
            .enumMax("fullscreenLyricStyle", () -> HudConfig.FULLSCREEN_LYRIC_STYLE_MAX)
            .enumMax("fullscreenLyricBackgroundMode", () -> HudConfig.FULLSCREEN_LYRIC_BACKGROUND_MODE_MAX)
            .enumMax("fullscreenLyricFluidStyle", () -> HudConfig.FULLSCREEN_LYRIC_FLUID_STYLE_MAX)
            .enumMax("osdLyricStyle", () -> HudConfig.OSD_LYRIC_STYLE_MAX)
            .enumMax("dynamicIslandStyle", () -> HudConfig.DYNAMIC_ISLAND_STYLE_MAX)
            .enumMax("infoCardBackdrop", () -> HudConfig.INFO_CARD_BACKDROP_MAX)
            .build();

    /**
     * 分享码键表（下标 + 1 = 编号）。<b>只许在末尾追加</b>，已发布的位置永不改、永不复用，
     * 见 {@link PresetDomain#shareKeys()}；排除项不编号。
     */
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
            /* 165 */ "fullscreenLyricFluidStatic"));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "HUD 与歌词";
    }

    @Override
    public int fragmentVersion() {
        return 1;
    }

    @Override
    public int shareCode() {
        return 1;
    }

    @Override
    public List<String> shareKeys() {
        return SHARE_KEYS;
    }

    @Override
    public KeyPolicy policy() {
        return POLICY;
    }

    public static KeyPolicy staticPolicy() {
        return POLICY;
    }

    @Override
    public JsonObject snapshot() {
        return HudConfig.toJson();
    }

    @Override
    public JsonObject defaults() {
        return HudConfig.defaultsJson();
    }

    @Override
    public void apply(JsonObject state, CompatReport report) {
        HudConfig.applyJson(state);
        HudConfig.save();
        // 下面几项有组件把值缓存在自己身上，设置界面改它们时也是这样逐个推过去的（见 HudSettingsModel）。
        try {
            MuoniumPlayerExtension extension = MuoniumPlayerExtension.getInstance();
            extension.musicInfo.setEnabled(HudConfig.musicInfoEnabled);
            extension.musicLyrics.setEnabled(HudConfig.musicLyricsEnabled);
            extension.musicLyrics.loadHudEditorSettings();
            extension.musicInfo.animatedCover.setValue(HudConfig.animatedCoverEnabled);
        } catch (Throwable ignored) {
            // 组件还没建好（例如还在主菜单之前）无所谓：HudConfig 才是真实来源，启动时会按它初始化。
        }
    }
}
