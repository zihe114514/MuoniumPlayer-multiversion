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
    @Override public boolean wiggle() { return wiggle; }
    @Override public double wiggleFps() { return 8.0; }
    @Override public double wiggleAmount() { return 0.8; }

    public boolean enabled;
    public double distance = 10.5;
    public boolean facePlayer = true;
    public boolean keepAway = true;
    public double spreadDeg = 30.0;
    public double scale = 1.0;
    public boolean smoothFont = true;
    public boolean splitLines = true;
    public boolean glowEnabled = true;
    public float glowStrength = 0.35f;
    public float glowSpread = 1.3f;
    public int glowColorRgb = 0xFFFFFF;
    public int colorRgb = 0xFFFFFF;
    public boolean wiggle;
    public boolean wordPop;
    public boolean textFlash;

    private WorldLyricsSettings() {
    }

    /** 从 HudConfig 读一份快照。字段缺失时（异常配置）整组回默认、安全静默。 */
    public static WorldLyricsSettings snapshot() {
        WorldLyricsSettings s = new WorldLyricsSettings();
        s.enabled = HudConfig.worldLyricsEnabled;
        s.distance = clamp(HudConfig.worldLyricsDistance, 3.0, 24.0);
        s.facePlayer = HudConfig.worldLyricsFacePlayer;
        s.keepAway = HudConfig.worldLyricsKeepAway;
        s.spreadDeg = clamp(HudConfig.worldLyricsSpreadDeg, 0.0, 60.0);
        s.scale = clamp(HudConfig.worldLyricsScale, 0.4, 3.0);
        s.smoothFont = HudConfig.worldLyricsSmoothFont;
        s.splitLines = HudConfig.worldLyricsSplitLines;
        s.glowEnabled = HudConfig.worldLyricsGlowEnabled;
        s.glowStrength = (float) clamp(HudConfig.worldLyricsGlowStrength, 0.0, 1.0);
        s.glowSpread = (float) clamp(HudConfig.worldLyricsGlowSpread, 0.5, 1.8);
        s.glowColorRgb = HudConfig.worldLyricsGlowColorRgb & 0xFFFFFF;
        s.colorRgb = HudConfig.worldLyricsColorRgb & 0xFFFFFF;
        s.wiggle = HudConfig.worldLyricsWiggle;
        s.wordPop = HudConfig.worldLyricsWordPop;
        s.textFlash = HudConfig.worldLyricsTextFlash;
        if (!s.glowEnabled) s.glowStrength = 0f;
        return s;
    }

    /** 纯默认值（测试 / HudConfig 不可用兜底）。 */
    public static WorldLyricsSettings defaults() {
        return new WorldLyricsSettings();
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}
