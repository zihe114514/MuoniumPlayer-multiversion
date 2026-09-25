package com.muoniumplayer.core.screens.ncm;

import java.awt.Color;

/**
 * 把封面代表色转换为播放器的完整颜色组。
 *
 * <p>颜色只作为主题色源使用，不写回用户预设。生成时会沿用当前预设的明暗方向，
 * 深色主题保持深底浅字，浅色主题保持浅底深字；强调色再从封面中饱和度与亮度综合
 * 最高的颜色派生，避免暗色封面把整个 UI 压成无层次的黑块。</p>
 */
final class CoverThemeGenerator {

    private CoverThemeGenerator() {
    }

    static int[] generate(int[] palette, boolean light) {
        int accentSeed = pickAccent(palette);
        float[] hsb = new float[3];
        Color.RGBtoHSB((accentSeed >>> 16) & 0xFF, (accentSeed >>> 8) & 0xFF,
                accentSeed & 0xFF, hsb);
        float hue = hsb[0];
        float saturation = clamp(hsb[1], .34f, .92f);
        float brightness = clamp(hsb[2], .30f, .86f);

        int accent = light
                ? rgb(Color.HSBtoRGB(hue, Math.max(.58f, saturation),
                        clamp(brightness, .40f, .68f)))
                : rgb(Color.HSBtoRGB(hue, Math.max(.58f, saturation),
                        clamp(brightness, .54f, .86f)));
        int accentHover = light
                ? rgb(Color.HSBtoRGB(hue, Math.max(.44f, saturation),
                        clamp(brightness + .08f, .46f, .74f)))
                : rgb(Color.HSBtoRGB(hue, Math.max(.44f, saturation),
                        clamp(brightness + .14f, .58f, .96f)));

        int background;
        int elementBackground;
        int primaryText;
        int inputBackground;
        int navigationBackground;
        if (light) {
            background = rgb(Color.HSBtoRGB(hue, clamp(saturation * .12f, .04f, .16f), .93f));
            elementBackground = rgb(Color.HSBtoRGB(hue, clamp(saturation * .10f, .03f, .13f), .985f));
            primaryText = rgb(Color.HSBtoRGB(hue, .14f, .13f));
            inputBackground = rgb(Color.HSBtoRGB(hue, clamp(saturation * .09f, .03f, .12f), .965f));
            navigationBackground = rgb(Color.HSBtoRGB(hue, clamp(saturation * .14f, .05f, .18f), .90f));
        } else {
            background = rgb(Color.HSBtoRGB(hue, saturation * .26f,
                    brightness > .70f ? .105f : .085f));
            elementBackground = rgb(Color.HSBtoRGB(hue, saturation * .22f, .145f));
            primaryText = rgb(Color.HSBtoRGB(hue, .08f, .96f));
            inputBackground = rgb(Color.HSBtoRGB(hue, saturation * .18f, .065f));
            navigationBackground = rgb(Color.HSBtoRGB(hue, saturation * .28f, .045f));
        }

        int elementHover = blend(elementBackground, accent, light ? .16f : .24f);
        int secondaryText = blend(primaryText, background, .48f);
        int border = blend(background, accent, .34f);

        int[] byColorType = new int[NCMScreen.ColorType.values().length];
        byColorType[NCMScreen.ColorType.GENERIC_BACKGROUND.ordinal()] = background;
        byColorType[NCMScreen.ColorType.ELEMENT_BACKGROUND.ordinal()] = elementBackground;
        byColorType[NCMScreen.ColorType.ELEMENT_HOVER.ordinal()] = elementHover;
        byColorType[NCMScreen.ColorType.PRIMARY_TEXT.ordinal()] = primaryText;
        byColorType[NCMScreen.ColorType.SECONDARY_TEXT.ordinal()] = secondaryText;
        byColorType[NCMScreen.ColorType.ACCENT.ordinal()] = accent;
        byColorType[NCMScreen.ColorType.ACCENT_HOVER.ordinal()] = accentHover;
        byColorType[NCMScreen.ColorType.INPUT_BACKGROUND.ordinal()] = inputBackground;
        byColorType[NCMScreen.ColorType.NAVIGATION_BACKGROUND.ordinal()] = navigationBackground;
        byColorType[NCMScreen.ColorType.BORDER.ordinal()] = border;
        return byColorType;
    }

    private static int pickAccent(int[] palette) {
        int best = palette.length == 0 ? 0x7B9CFF : palette[0];
        float bestScore = -1f;
        float[] hsb = new float[3];
        for (int color : palette) {
            Color.RGBtoHSB((color >>> 16) & 0xFF, (color >>> 8) & 0xFF, color & 0xFF, hsb);
            float score = hsb[1] * (.45f + hsb[2]);
            if (score > bestScore) {
                bestScore = score;
                best = color;
            }
        }
        return best;
    }

    private static int blend(int from, int to, float amount) {
        float t = clamp(amount, 0f, 1f);
        int r = Math.round(((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * t);
        int g = Math.round(((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static int rgb(int argb) {
        return argb & 0xFFFFFF;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
