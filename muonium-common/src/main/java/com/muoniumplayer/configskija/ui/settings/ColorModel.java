package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.ui.Geometry;
import java.util.Locale;

/** Same HSV and AARRGGBB convention as ColorOption.kt. Hue survives saturation/value dragging. */
public final class ColorModel {
    public double hue, saturation, brightness, alpha;
    public boolean chroma, gradient;
    public double speed = 1;
    public int gradientArgb;
    public final boolean alphaEnabled;
    public ColorModel(int argb, boolean alphaEnabled) { this.alphaEnabled = alphaEnabled; apply(argb); if (!alphaEnabled) alpha = 1; gradientArgb = argb; }
    public void apply(int argb) {
        double r = (argb >>> 16 & 255) / 255d, g = (argb >>> 8 & 255) / 255d, b = (argb & 255) / 255d;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        brightness = max; saturation = max == 0 ? 0 : d / max;
        hue = d == 0 ? 0 : max == r ? mod((g - b) / d, 6) * 60 : max == g ? ((b - r) / d + 2) * 60 : ((r - g) / d + 4) * 60;
        alpha = (argb >>> 24) / 255d;
    }
    public int argb() { return hsv(hue, saturation, brightness, alpha); }
    public SettingsSchema.ColorValue value() { return new SettingsSchema.ColorValue(argb(), chroma, speed, gradient, gradientArgb); }
    public static int hsv(double hue, double saturation, double brightness, double alpha) {
        double h = mod(hue, 360) / 60, c = brightness * saturation, x = c * (1 - Math.abs(mod(h, 2) - 1)), m = brightness - c;
        double r, g, b;
        if (h < 1) { r = c; g = x; b = 0; } else if (h < 2) { r = x; g = c; b = 0; }
        else if (h < 3) { r = 0; g = c; b = x; } else if (h < 4) { r = 0; g = x; b = c; }
        else if (h < 5) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return byteValue(alpha) << 24 | byteValue(r + m) << 16 | byteValue(g + m) << 8 | byteValue(b + m);
    }
    private static int byteValue(double value) { return (int)Math.floor(Geometry.clamp(value, 0, 1) * 255 + .5); }
    private static double mod(double value, double modulus) { return (value % modulus + modulus) % modulus; }
    public static String hex(int argb) { return String.format(Locale.ROOT, argb >>> 24 == 255 ? "%06X" : "%08X", argb >>> 24 == 255 ? argb & 0xffffff : argb); }
    public static Integer parseHex(String input) {
        String text = input.startsWith("#") ? input.substring(1).trim() : input.trim();
        if (text.length() != 6 && text.length() != 8 || !text.matches("[0-9a-fA-F]+")) return null;
        try { long value = Long.parseLong(text, 16); return (int)(text.length() == 6 ? value | 0xff000000L : value); }
        catch (NumberFormatException e) { return null; }
    }
    public static String filterHex(String text) { StringBuilder out = new StringBuilder(); text.codePoints().filter(Character::isLetterOrDigit).limit(8).forEach(out::appendCodePoint); return out.toString(); }
    public static double luminance(int argb) { return .2126 * linear(argb >>> 16 & 255) + .7152 * linear(argb >>> 8 & 255) + .0722 * linear(argb & 255); }
    private static double linear(int channel) { double v = channel / 255d; return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }
}
