package com.muoniumplayer.core.ncm.music;

import java.awt.image.BufferedImage;

/**
 * 从封面提取「彼此色相拉开」的代表色序列（最多 4 色，主色在前），供歌词渐变使用。
 *
 * <p>与 {@link CoverPalette}（weighted median-cut，按面积切桶，服务主题/频谱）并存：
 * 渐变需要的是「色相彼此拉开的少数几色」，median-cut 按面积切常给同色系的明暗变体，
 * 拉不开渐变 —— 这里改走色相直方图：</p>
 *
 * <ul>
 *   <li>24 个色相桶 + 普通中性桶 + 浅中性桶；alpha ≥ 0.1 计入，x/y 步进 2 采样。</li>
 *   <li>面积不足 0.5% 的桶视为零散杂色剔除；混合均色落在「暗部弱偏色」
 *       （S &lt; 0.65 且 V &lt; 0.65 且 S·V·255 &lt; 48）的也剔除 —— 提亮也救不回的暗晕。</li>
 *   <li>色相相邻近 1/8 色环的桶合并去重 —— 渐变端点彼此要拉得开。</li>
 *   <li>出口提亮：S ≤ 0.75、V ≥ 0.92（渐变叠在亮背景上仍读得清）。</li>
 *   <li>彩色不足 1~2 色且浅中性面积 ≥ 5% 时补一色浅中性（近灰去饱和，保留米白/浅灰绿）；
 *       全无彩色时取封面自身灰阶略微提亮。</li>
 * </ul>
 */
public final class CoverRepresentativeColors {

    private static final int BINS = 24;
    private static final int NEUTRAL_BIN = BINS;
    private static final int LIGHT_NEUTRAL_BIN = BINS + 1;

    private CoverRepresentativeColors() {
    }

    /** 提取最多 max 色的代表色序列（ARGB 高字节恒 0xFF）。图无效返回空数组。 */
    public static int[] extract(BufferedImage image, int max) {
        if (image == null || max <= 0 || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return new int[0];
        }
        final int bins = BINS + 2;
        double[] area = new double[bins];
        double[] wt = new double[bins];
        double[] rs = new double[bins];
        double[] gs = new double[bins];
        double[] bs = new double[bins];
        double visible = 0.0;

        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        float[] hsb = new float[3];
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int argb = pixels[y * width + x];
                double alpha = ((argb >>> 24) & 0xFF) / 255.0;
                if (alpha < 0.1) continue;
                visible += alpha;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                java.awt.Color.RGBtoHSB(r, g, b, hsb);
                int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                // 接近黑时相对饱和度不可靠，同时保留真正高饱和的深色。
                boolean neutral = hsb[1] < 0.12f
                        || (hsb[1] < 0.65f && (chroma < 24 || (hsb[2] < 0.65f && chroma < 48)));
                double w = alpha * (neutral ? 1.0 : 1.0 + 0.5 * hsb[1]);
                // 浅中性独立统计：避免阴影拉低均值后又被强行抬白。
                boolean lightNeutral = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0 >= 0.60;
                int k = neutral ? (lightNeutral ? LIGHT_NEUTRAL_BIN : NEUTRAL_BIN)
                        : (int) (hsb[0] * BINS);
                if (k >= BINS) k = BINS - 1;
                area[k] += alpha;
                wt[k] += w;
                rs[k] += r * w;
                gs[k] += g * w;
                bs[k] += b * w;
            }
        }
        if (visible <= 0.0) return new int[0];

        java.util.List<Integer> ranked = new java.util.ArrayList<Integer>();
        for (int k = 0; k < BINS; k++) ranked.add(k);
        ranked.sort((a, b2) -> Double.compare(wt[b2], wt[a]));

        java.util.List<Integer> colors = new java.util.ArrayList<Integer>();
        java.util.List<Float> hues = new java.util.ArrayList<Float>();
        for (int k : ranked) {
            if (area[k] < visible * 0.005) continue;   // 零散杂色
            if (wt[k] <= 1e-6) continue;
            java.awt.Color.RGBtoHSB((int) (rs[k] / wt[k]), (int) (gs[k] / wt[k]),
                    (int) (bs[k] / wt[k]), hsb);
            // 暗部弱偏色：提亮前剔除。
            if (hsb[1] < 0.65f && hsb[2] < 0.65f && hsb[1] * hsb[2] * 255 < 48) continue;
            // 色相近邻去重：渐变端点彼此要拉得开。
            boolean near = false;
            for (float hue : hues) {
                float distance = Math.abs(hue - hsb[0]);
                float d = Math.min(distance, 1f - distance);
                if (d < 1f / 8f) { near = true; break; }
            }
            if (near) continue;
            colors.add(java.awt.Color.HSBtoRGB(hsb[0],
                    Math.min(0.75f, hsb[1]), Math.max(0.92f, hsb[2])) & 0xFFFFFF);
            hues.add(hsb[0]);
            if (colors.size() >= max) break;
        }
        // 彩色不足且浅中性面积可观：补一色（近灰去饱和，保留米白/浅灰绿）。
        int light = LIGHT_NEUTRAL_BIN;
        if (colors.size() >= 1 && colors.size() <= 2 && area[light] >= visible * 0.05) {
            int r = (int) (rs[light] / wt[light]);
            int g = (int) (gs[light] / wt[light]);
            int b = (int) (bs[light] / wt[light]);
            java.awt.Color.RGBtoHSB(r, g, b, hsb);
            float saturation = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 12
                    ? 0f : hsb[1];
            colors.add(java.awt.Color.HSBtoRGB(hsb[0], saturation,
                    Math.max(hsb[2], Math.min(0.92f, hsb[2] + 0.08f))) & 0xFFFFFF);
        }
        // 渐变兜底：只剩 1 色时用「主色 + 主色变体」凑两色端点 —— 单色渐变
        // （coverColorMode 开着却整句恒定一色）的观感等于功能失效。变体 =
        // 亮度推高的同相色（主色 → 主色的浅色端），色相不失真。
        if (colors.size() == 1) {
            int main = colors.get(0) & 0xFFFFFF;
            java.awt.Color.RGBtoHSB((main >> 16) & 0xFF, (main >> 8) & 0xFF, main & 0xFF, hsb);
            int lightEnd = java.awt.Color.HSBtoRGB(hsb[0],
                    Math.max(0f, hsb[1] - 0.25f), Math.min(1f, hsb[2] + 0.18f)) & 0xFFFFFF;
            colors.add(lightEnd);
        }
        if (colors.isEmpty()) {
            // 无彩色：封面自身灰阶，纯暗图略微提亮。
            double weight = wt[NEUTRAL_BIN] + wt[light];
            double value = weight > 0
                    ? (0.2126 * (rs[NEUTRAL_BIN] + rs[light])
                       + 0.7152 * (gs[NEUTRAL_BIN] + gs[light])
                       + 0.0722 * (bs[NEUTRAL_BIN] + bs[light])) / weight / 255.0
                    : 0.0;
            float v = (float) Math.min(1.0, value + 0.08);
            int gray = java.awt.Color.HSBtoRGB(0f, 0f, v) & 0xFFFFFF;
            // 灰阶也给两端：灰 → 亮灰，渐变仍成立。
            colors.add(gray);
            colors.add(java.awt.Color.HSBtoRGB(0f, 0f, Math.min(1f, v + 0.25f)) & 0xFFFFFF);
        }
        int[] out = new int[colors.size()];
        for (int i = 0; i < out.length; i++) out[i] = colors.get(i);
        return out;
    }
}
