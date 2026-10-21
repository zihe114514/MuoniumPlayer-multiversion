package com.muoniumplayer.core.screens.ncm.foliabg;

import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.NCMTheme;

/**
 * folia {@code Theme} 在播放器里的映射。
 *
 * <p>folia 的每个背景都只读四个颜色（{@code backgroundColor / primaryColor / secondaryColor /
 * accentColor}）和一个日间标记，所以这里也只暴露这五个量，颜色按 {@link NCMScreen.ColorType}
 * 取当前主题（含「封面跟随」生成的主题）的<b>动画中</b>值 —— 与歌词页其它部分同一条过渡，
 * 不会出现背景突然换色、文字慢慢变的情况。</p>
 *
 * <p>颜色以 {@code 0xRRGGBB} 存放（不带 alpha），与 folia 的 CSS 颜色一一对应；需要 alpha 的
 * 地方由调用方乘。</p>
 */
public final class FoliaTheme {

    public final int background;
    public final int primary;
    public final int secondary;
    public final int accent;
    public final boolean daylight;

    private FoliaTheme(int background, int primary, int secondary, int accent, boolean daylight) {
        this.background = background & 0xFFFFFF;
        this.primary = primary & 0xFFFFFF;
        this.secondary = secondary & 0xFFFFFF;
        this.accent = accent & 0xFFFFFF;
        this.daylight = daylight;
    }

    /** 读当前主题。只在渲染线程调用（NCMTheme 内部有锁，但每帧一次就够）。 */
    public static FoliaTheme current() {
        return new FoliaTheme(
                NCMTheme.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                NCMTheme.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                NCMTheme.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                NCMTheme.getColor(NCMScreen.ColorType.ACCENT),
                NCMTheme.isLightPreset());
    }

    /** 取一个与当前主题无关的固定主题（测试 / 缺主题时的兜底）。 */
    public static FoliaTheme of(int background, int primary, int secondary, int accent, boolean daylight) {
        return new FoliaTheme(background, primary, secondary, accent, daylight);
    }

    public int rgb(int index) {
        switch (index) {
            case 0:
                return background;
            case 1:
                return primary;
            case 2:
                return secondary;
            default:
                return accent;
        }
    }

    // ==================== 颜色运算 ====================

    public static float r(int rgb) {
        return ((rgb >> 16) & 0xFF) / 255f;
    }

    public static float g(int rgb) {
        return ((rgb >> 8) & 0xFF) / 255f;
    }

    public static float b(int rgb) {
        return (rgb & 0xFF) / 255f;
    }

    public static int rgb(float r, float g, float b) {
        int ri = (int) Math.round(clamp01(r) * 255f);
        int gi = (int) Math.round(clamp01(g) * 255f);
        int bi = (int) Math.round(clamp01(b) * 255f);
        return (ri << 16) | (gi << 8) | bi;
    }

    /** 两色线性混合，{@code amount=0} 取 {@code from}。 */
    public static int mix(int from, int to, double amount) {
        double t = clamp01(amount);
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        return (round(fr + (tr - fr) * t) << 16)
                | (round(fg + (tg - fg) * t) << 8)
                | round(fb + (tb - fb) * t);
    }

    /** 朝白色提亮。 */
    public static int lighten(int rgb, double amount) {
        return mix(rgb, 0xFFFFFF, amount);
    }

    public static double luminance(int rgb) {
        return 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
    }

    /** 逐通道 {@code saturate()}，与 CSS / Paper 的 saturate 同义（按 Rec.709 亮度）。 */
    public static int saturate(int rgb, double amount) {
        double l = luminance(rgb);
        double r = l + (((rgb >> 16) & 0xFF) - l) * amount;
        double g = l + (((rgb >> 8) & 0xFF) - l) * amount;
        double b = l + ((rgb & 0xFF) - l) * amount;
        return rgb((float) (r / 255.0), (float) (g / 255.0), (float) (b / 255.0));
    }

    /** {@code 0xRRGGBB} + alpha → Skia 的 {@code 0xAARRGGBB}。 */
    public static int argb(int rgb, double alpha) {
        int a = (int) Math.round(clamp01(alpha) * 255.0);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    public static double clamp01(double value) {
        return value < 0 ? 0 : (value > 1 ? 1 : value);
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static int round(double value) {
        int i = (int) Math.round(value);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}

