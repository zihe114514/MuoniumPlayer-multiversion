package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncoderPNG;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.TextLine;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.skija.shaper.Shaper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skija 文字链路的静态测试：完全在 CPU 上跑，不需要 GL、不需要启动客户端。
 *
 * <p>存在的理由：宿主层自检里文字渲染出现了「只画出少数几个错误字形、多数字符缺失」的现象。
 * 形状/渐变/裁剪都正常，所以问题一定在「字体 → 字形 → 光栅」这条链上，
 * 而这条链不依赖 GPU，可以在单测里完整复现并定位。
 *
 * <p>同时它也是渲染层重写后取代 {@code ShaderPortProbeTest} 的新不变量测试：
 * 旧测试钉的是 GLSL 源码，删掉 GLSL 之后要钉的是「文字真的能画出来」。
 *
 * <p>失败时会把光栅结果写到 {@code build/skija-text-*.png}，可以直接打开看。
 */
class SkijaTextStaticTest {

    private static final String LATIN = "Skija OK 1.3.0 AVWij";
    private static final String CJK = "网易云音乐";
    private static final String MIXED = "Skija 宿主层自检 OK";

    private static byte[] fontBytes;

    @BeforeAll
    static void loadNative() throws Exception {
        assertTrue(SkijaNatives.ensureLoaded(),
                "Skija 原生库加载失败: " + SkijaNatives.failure());
        try (InputStream in = SkijaTextStaticTest.class
                .getResourceAsStream("/muonium/fonts/pf_normal.ttf")) {
            assertNotNull(in, "找不到 /muonium/fonts/pf_normal.ttf");
            fontBytes = in.readAllBytes();
        }
        assertTrue(fontBytes.length > 1_000_000,
                "字体数据长度异常: " + fontBytes.length);
    }

    private static Typeface typeface() {
        Typeface tf = FontMgr.getDefault().makeFromData(Data.makeFromBytes(fontBytes));
        assertNotNull(tf, "makeFromData 返回 null");
        return tf;
    }

    /**
     * 第一层：字体本身是否认识这些字符。
     *
     * <p>{@code getStringGlyphs} 返回 0 表示「这个字符在本字体里没有对应字形」。
     * 如果这里就大面积为 0，问题在字体数据或字体选择，与渲染无关。
     */
    @Test
    void 字体应当覆盖样本里的全部字符() {
        Typeface tf = typeface();
        for (String sample : new String[]{LATIN, CJK, MIXED}) {
            short[] glyphs = tf.getStringGlyphs(sample);
            assertNotNull(glyphs, "getStringGlyphs 返回 null: " + sample);
            StringBuilder missing = new StringBuilder();
            for (int i = 0; i < sample.length() && i < glyphs.length; i++) {
                char c = sample.charAt(i);
                if (glyphs[i] == 0 && c != ' ') {
                    missing.append('[').append(c).append("=U+")
                            .append(Integer.toHexString(c)).append(']');
                }
            }
            System.out.println("[字形覆盖] \"" + sample + "\" -> "
                    + glyphs.length + " 个字形"
                    + (missing.length() == 0 ? "，全部命中" : "，缺失 " + missing));
            assertTrue(missing.length() == 0,
                    "字体缺少字形: " + sample + " 缺失 " + missing);
        }
    }

    /** 第二层：度量是否正常（宽度必须随字符数单调增长）。 */
    @Test
    void 文本度量应当合理() {
        Typeface tf = typeface();
        Font font = new Font(tf, 16f);
        float w1 = TextLine.make("S", font).getWidth();
        float w2 = TextLine.make("SS", font).getWidth();
        float wMixed = TextLine.make(MIXED, font).getWidth();
        System.out.println("[度量] \"S\"=" + w1 + " \"SS\"=" + w2 + " 混排=" + wMixed);
        assertTrue(w1 > 0f, "单字符宽度为 0");
        assertTrue(w2 > w1 * 1.5f, "两个字符的宽度没有约等于单字符的两倍: " + w1 + " -> " + w2);
        assertTrue(wMixed > w1 * 5f, "混排字符串宽度异常小: " + wMixed);
    }

    /**
     * 第三层：CPU 光栅。这是与宿主层自检里出问题那一步最接近的一层，
     * 唯一的差别是这里没有 GPU、没有 Minecraft 的 GL 状态。
     *
     * <p>判定方式不是「有没有非零像素」——那太弱了。而是按列统计墨迹：
     * 一行正常的文字，有墨迹的列应当占相当比例且连续分布；
     * 「只画出几个字形」会表现为极少数孤立的列有墨迹。
     */
    @Test
    void CPU光栅应当画出连续的文字墨迹() throws Exception {
        Typeface tf = typeface();
        Font font = new Font(tf, 16f);
        for (String sample : new String[]{LATIN, CJK, MIXED}) {
            rasterAndAssert(font, sample, "plain");
        }
    }

    /** 第四层：显式走 HarfBuzz 整形器，与第三层对比，区分「整形」和「光栅」哪一边有问题。 */
    @Test
    void 整形后光栅也应当画出连续的文字墨迹() throws Exception {
        Typeface tf = typeface();
        Font font = new Font(tf, 16f);
        try (Shaper shaper = Shaper.makeShapeDontWrapOrReorder()) {
            for (String sample : new String[]{LATIN, CJK, MIXED}) {
                TextLine line = shaper.shapeLine(sample, font);
                rasterLineAndAssert(line, sample, "shaped");
            }
        }
    }

    private void rasterAndAssert(Font font, String sample, String tag) throws Exception {
        rasterLineAndAssert(TextLine.make(sample, font), sample, tag);
    }

    private void rasterLineAndAssert(TextLine line, String sample, String tag) throws Exception {
        int w = Math.max(32, (int) Math.ceil(line.getWidth()) + 8);
        int h = 32;
        int inkColumns;
        try (Surface surface = Surface.makeRasterN32Premul(w, h);
             Paint paint = new Paint().setColor(0xFFFFFFFF).setAntiAlias(true)) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0xFF000000);
            canvas.drawTextLine(line, 4f, 22f, paint);

            Bitmap bitmap = new Bitmap();
            bitmap.allocPixels(ImageInfo.makeN32Premul(w, h));
            assertTrue(surface.readPixels(bitmap, 0, 0), "readPixels 失败");
            byte[] px = bitmap.readPixels();

            // N32Premul 在小端机上是 BGRA。只看某一列有没有任何非黑像素。
            boolean[] columnHasInk = new boolean[w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    int b = px[i] & 0xFF;
                    int g = px[i + 1] & 0xFF;
                    int r = px[i + 2] & 0xFF;
                    if (r > 40 || g > 40 || b > 40) {
                        columnHasInk[x] = true;
                    }
                }
            }
            int count = 0;
            for (boolean ink : columnHasInk) {
                if (ink) {
                    count++;
                }
            }
            inkColumns = count;

            // 无论通过与否都留一份图，便于人工确认。
            Path out = Path.of("build", "skija-text-" + tag + "-"
                    + Integer.toHexString(sample.hashCode()) + ".png");
            Files.createDirectories(out.getParent());
            try (Image img = surface.makeImageSnapshot();
                 Data png = EncoderPNG.encode(img)) {
                if (png != null) {
                    Files.write(out, png.getBytes());
                }
            }
            System.out.println("[光栅/" + tag + "] \"" + sample + "\" 宽=" + w
                    + " 有墨迹的列=" + inkColumns
                    + " 占比=" + Math.round(100.0 * inkColumns / w) + "%"
                    + " -> " + out);
        }

        // 一行正常渲染的文字，有墨迹的列应当远超一半（字符之间的间隙很窄）。
        // 「只画出少数几个字形」会让这个比例掉到 20% 以下。
        assertTrue(inkColumns > w * 0.45,
                "文字墨迹过于稀疏，说明多数字形没有被画出来: \"" + sample
                        + "\" 宽=" + w + " 有墨迹的列=" + inkColumns);
    }
}
