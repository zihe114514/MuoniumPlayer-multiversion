package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.settings.HudConfig;

/**
 * 3D 世界歌词的设置快照门面。
 *
 * <p>真实存储在 {@link HudConfig}（每棵版本树一份、字段逐字同步），common 的渲染/状态
 * 代码只读这个门面 —— 字段名收拢在一处，改名只动这个文件。每帧开头取一次快照，
 * 渲染过程中看到的是同一份一致状态（拖滑块中途不会半行新半行旧）。</p>
 */
public final class WorldLyricsSettings implements WorldLyricsState.Params {

    @Override public double distance() { return distance; }
    @Override public double spreadDeg() { return spreadDeg; }
    @Override public double scale() { return scale; }
    @Override public boolean facePlayer() { return facePlayer; }
    @Override public boolean keepAway() { return keepAway; }
    @Override public boolean wordPop() { return wordPop; }
    @Override public boolean splitLines() { return splitLines; }
    @Override public boolean cadenza() { return cadenza; }
    @Override public boolean huiguang() { return huiguang; }
    @Override public boolean stayOnScreen() { return stayOnScreen; }
    @Override public boolean aboveGround() { return aboveGround; }

    public boolean enabled;
    public double distance = 10.5;
    public boolean facePlayer = true;
    public boolean keepAway = true;
    public double spreadDeg = 30.0;
    public double scale = 1.0;
    /** 现代化渲染风格：Skija 超采样字形 + TextGlow 烘焙发光。关 = 原版像素字。 */
    public boolean styleModern = true;
    public boolean splitLines = true;
    public boolean glowEnabled = true;
    public float glowStrength = 0.35f;
    public float glowSpread = 1.3f;
    public int glowColorRgb = 0xFFFFFF;
    public int colorRgb = 0xFFFFFF;
    /** 文字颜色是否被取色器自定义过（false = 用默认纯白）。 */
    public boolean colorCustom;
    public boolean wordPop;
    public boolean textFlash;
    /** 心象动效模式（仅现代化引擎生效）：整行分块、按块的出入场动画/呼吸/块色画。 */
    public boolean cadenza;
    /** 绘光动效模式（仅现代化引擎生效）：换句逐字飞行重排 + 径迹拖尾 + 卡拉OK点亮。 */
    public boolean huiguang;
    /** 回屏保持（stayOnScreen）：转开视角时歌词滑回视野边缘。 */
    public boolean stayOnScreen;
    /** Above ground：歌词中心不低于眼睛下方 0.5 格。 */
    public boolean aboveGround;
    /** 绘光关键字表（folia wordColors）：格式「词:RRGGBB,词:RRGGBB,…」；空 = 无关键字。 */
    public String keywordColors = "";

    /** 帧头快照（WorldLyricsBridge 调）。 */
    public static WorldLyricsSettings snapshot() {
        return buildFromConfig();
    }

    private static WorldLyricsSettings buildFromConfig() {
        WorldLyricsSettings s = new WorldLyricsSettings();
        s.enabled = HudConfig.worldLyricsEnabled;
        s.distance = clamp(HudConfig.worldLyricsDistance, 3.0, 48.0);
        s.facePlayer = HudConfig.worldLyricsFacePlayer;
        s.keepAway = HudConfig.worldLyricsKeepAway;
        s.spreadDeg = clamp(HudConfig.worldLyricsSpreadDeg, 0.0, 60.0);
        s.scale = clamp(HudConfig.worldLyricsScale, 0.4, 6.0);
        s.styleModern = HudConfig.worldLyricsModernStyle;
        s.splitLines = HudConfig.worldLyricsSplitLines;
        s.glowEnabled = HudConfig.worldLyricsGlowEnabled;
        s.glowStrength = (float) clamp(HudConfig.worldLyricsGlowStrength, 0.0, 1.0);
        s.glowSpread = (float) clamp(HudConfig.worldLyricsGlowSpread, 0.5, 1.8);
        s.glowColorRgb = HudConfig.worldLyricsGlowColorRgb & 0xFFFFFF;
        s.colorRgb = HudConfig.worldLyricsColorRgb & 0xFFFFFF;
        s.colorCustom = HudConfig.worldLyricsColorCustom;
        s.wordPop = HudConfig.worldLyricsWordPop;
        s.textFlash = HudConfig.worldLyricsTextFlash;
        s.cadenza = HudConfig.worldLyricsCadenza;
        s.huiguang = HudConfig.worldLyricsHuiguang;
        s.stayOnScreen = HudConfig.worldLyricsStayOnScreen;
        s.aboveGround = HudConfig.worldLyricsAboveGround;
        s.keywordColors = HudConfig.worldLyricsKeywordColors != null
                ? HudConfig.worldLyricsKeywordColors : "";
        if (!s.glowEnabled) s.glowStrength = 0f;
        return s;
    }

    /** 纯默认值（测试 / HudConfig 不可用兜底）。 */
    public static WorldLyricsSettings defaults() {
        return new WorldLyricsSettings();
    }

    private WorldLyricsSettings() {
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}
