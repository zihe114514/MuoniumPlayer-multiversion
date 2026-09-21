package com.muoniumplayer.core.rendering.font;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontEdging;
import io.github.humbleui.skija.FontHinting;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontMetrics;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.TextBlob;
import io.github.humbleui.skija.TextBlobBuilder;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 临时探针：量 pf* 那一档真实落屏的墨迹竖向范围。跑完就删。
 *
 * <p>墨迹盒按 {@code sizePx*8} 光栅再除以 8，所以小数位是真的（关闭 hinting 后轮廓随字号线性
 * 缩放，只有行盒与基线偏移按实际字号取整，那两量单列）。</p>
 */
class TextInkProbeTest {

    /** 光栅放大倍率：把 9px 的字放大到 72px 量，墨迹边界才有小数位。 */
    private static final double ZOOM = 8.0;
    private static final int W = 700;
    private static final int H = 260;
    /** 大图上基线所在的行。 */
    private static final float BASELINE = 130f;

    @Test
    void probe() throws Exception {
        com.muoniumplayer.render.skija.SkijaNatives.ensureLoaded();

        String regularMain = "/muonium/fonts/sfregular.otf";
        String boldMain = "/muonium/fonts/sfbold.otf";
        String regularFb = "/muonium/fonts/pf_normal.ttf";
        String boldFb = "/muonium/fonts/pf_middleblack.ttf";

        for (String text : new String[]{"搜索", "取消", "保存", "逐字", "中", "Lyrics", "gjp", "稻香"}) {
            boolean latin = text.equals("Lyrics") || text.equals("gjp");
            for (double name : new double[]{12, 14, 16, 18, 20}) {
                // FontManager: createText(n) -> new CFontRenderer(main, n * 0.5f, kerning, pf 回退)
                probe("pf" + (int) name + " " + (latin ? "reg " : "bold"),
                        latin ? regularMain : boldMain,
                        latin ? regularFb : boldFb,
                        name * .5, text);
            }
        }
    }

    private static void probe(String tag, String mainRes, String fbRes, double sizePx, String text)
            throws Exception {
        try (Typeface mainTf = typeface(mainRes);
             Typeface fbTf = typeface(fbRes);
             Font metricsFont = newFont(mainTf, sizePx);
             Font metricsFb = newFont(fbTf, sizePx);
             Font big = newFont(mainTf, sizePx * ZOOM);
             Font bigFb = newFont(fbTf, sizePx * ZOOM)) {

            FontMetrics mm = metricsFont.getMetrics();
            FontMetrics fm = metricsFb.getMetrics();
            int mainA = (int) Math.ceil(-mm.getAscent());
            int mainD = (int) Math.ceil(mm.getDescent());
            int fbA = (int) Math.ceil(-fm.getAscent());
            int fbD = (int) Math.ceil(fm.getDescent());
            int box = Math.max(mainA + mainD, fbA + fbD);
            float baselineOffset = (box + mainA - mainD) * .5f;

            // —— 复刻 GlyphInkMetrics：AWT 侧量同一枚字形，看它和真实落屏差多少 ——
            java.awt.Font awtMain = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,
                    new ByteArrayInputStream(bytes(mainRes))).deriveFont((float) (sizePx * 2));
            java.awt.Font awtFb = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,
                    new ByteArrayInputStream(bytes(fbRes))).deriveFont((float) (sizePx * 2));
            java.awt.Graphics2D g = new java.awt.image.BufferedImage(1, 1,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
            java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(
                    new java.awt.geom.AffineTransform(), true, true);
            java.awt.Font draw = awtMain.canDisplay(text.charAt(0)) ? awtMain : awtFb;
            java.awt.font.GlyphVector gv = draw.createGlyphVector(frc, text);
            java.awt.geom.Rectangle2D inkBox = gv.getOutline().getBounds2D();
            java.awt.FontMetrics afm = g.getFontMetrics(draw);
            int awtMainA = afm.getAscent(), awtMainD = afm.getDescent();
            java.awt.FontMetrics mfm = g.getFontMetrics(awtMain);
            int atlas = Math.max(mfm.getAscent() + mfm.getDescent(),
                    afm.getAscent() + afm.getDescent());
            int awtBaseline = (atlas + afm.getAscent() - afm.getDescent()) / 2;
            double awtTop = -2.0 + (awtBaseline + inkBox.getY()) * .5;
            double awtH = inkBox.getHeight() * .5;
            double awtCenter = awtTop + awtH * .5;

            Typeface chosenTf = mainTf.getUTF32Glyph(text.charAt(0)) == 0 ? fbTf : mainTf;
            Font chosen = chosenTf == mainTf ? big : bigFb;
            short[] glyphs = new short[text.length()];
            float[] pen = new float[text.length()];
            float x = 0;
            for (int i = 0; i < text.length(); i++) {
                glyphs[i] = chosenTf.getUTF32Glyph(text.charAt(i));
                pen[i] = x;
                x += chosen.getWidths(new short[]{glyphs[i]})[0];
            }

            int[] rowRed = new int[H];
            int[] rowMax = new int[H];
            try (Surface surface = Surface.makeRasterN32Premul(W, H);
                 TextBlobBuilder builder = new TextBlobBuilder();
                 Paint paint = new Paint().setAntiAlias(true).setColor(0xFFFFFFFF)) {
                Canvas canvas = surface.getCanvas();
                canvas.clear(0xFF000000);
                builder.appendRunPosH(chosen, glyphs, pen, BASELINE);
                try (TextBlob blob = builder.build()) {
                    canvas.drawTextBlob(blob, 20f, 0f, paint);
                }
                try (Bitmap bitmap = new Bitmap()) {
                    bitmap.allocPixels(ImageInfo.makeN32Premul(W, H));
                    surface.readPixels(bitmap, 0, 0);
                    for (int row = 0; row < H; row++) {
                        int sum = 0;
                        int max = 0;
                        for (int col = 0; col < W; col++) {
                            int red = (bitmap.getColor(col, row) >> 16) & 0xFF;
                            sum += red;
                            max = Math.max(max, red);
                        }
                        rowRed[row] = sum;
                        rowMax[row] = max;
                    }
                }
            }

            int top = -1, bottom = -1, topSoft = -1, bottomSoft = -1;
            double weight = 0, moment = 0;
            for (int row = 0; row < H; row++) {
                if (rowRed[row] == 0) {
                    continue;
                }
                if (topSoft < 0) {
                    topSoft = row;
                }
                bottomSoft = row;
                weight += rowRed[row];
                moment += (double) rowRed[row] * (row + .5);
                if (rowMax[row] >= 200) {   // 该行有接近实心的像素才算实墨（淡墨边只用于对照）
                    if (top < 0) {
                        top = row;
                    }
                    bottom = row;
                }
            }
            double above = (BASELINE - top) / ZOOM;
            double below = (bottom - BASELINE) / ZOOM;
            double centroid = (moment / weight - BASELINE) / ZOOM;
            double softAbove = (BASELINE - topSoft) / ZOOM;
            double softBelow = (bottomSoft - BASELINE) / ZOOM;

            // drawString(text, X, y)：originY = y - 2，基线在 originY + baselineOffset。
            double inkTop = -2 + baselineOffset - above;
            double inkBottom = -2 + baselineOffset + below;
            double inkCenter = -2 + baselineOffset + (below - above) * .5;
            double inkH = above + below;
            System.out.println("[INK] " + tag + " \"" + text + "\""
                    + " sizePx=" + sizePx + " mainA/D=" + mainA + "/" + mainD
                    + " fbA/D=" + fbA + "/" + fbD + " box=" + box
                    + " 行盒高=" + (box * 2.0 - 8.5) * .5 + " baseOff=" + baselineOffset
                    + " || 墨迹(相对 y) 上=" + round(inkTop) + " 下=" + round(inkBottom)
                    + " 高=" + round(inkH) + " 中心=" + round(inkCenter)
                    + " 质心=" + round(-2 + baselineOffset + centroid)
                    + " || AWT墨迹 上=" + round(awtTop) + " 高=" + round(awtH) + " 中心=" + round(awtCenter)
                + " 差=" + round(awtCenter - (-2 + baselineOffset + (below - above) * .5))
                + " || 淡墨上=" + round(-2 + baselineOffset - softAbove)
                    + " 淡墨下=" + round(-2 + baselineOffset + softBelow));
        }
    }

    private static String round(double v) {
        return String.format("%.2f", v);
    }

    private static Font newFont(Typeface typeface, double sizePx) {
        Font f = new Font(typeface, (float) sizePx);
        f.setEdging(FontEdging.ANTI_ALIAS);
        f.setHinting(FontHinting.NONE);
        f.setSubpixel(true);
        f.setBaselineSnapped(false);
        return f;
    }

    private static byte[] bytes(String resource) throws Exception {
        try (InputStream in = TextInkProbeTest.class.getResourceAsStream(resource)) {
            return in.readAllBytes();
        }
    }

    private static Typeface typeface(String resource) throws Exception {
        byte[] data = bytes(resource);
        try (Data d = Data.makeFromBytes(data)) {
            return FontMgr.getDefault().makeFromData(d);
        }
    }
}
