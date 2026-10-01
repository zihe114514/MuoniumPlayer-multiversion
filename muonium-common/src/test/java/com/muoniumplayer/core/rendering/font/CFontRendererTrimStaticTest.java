package com.muoniumplayer.core.rendering.font;

import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 折叠（{@code trim}）与行高（{@code getStringHeight}）重写后的等价性测试。
 *
 * <p>这两处都在<b>每帧、每个标签</b>的热路径上，改的是「怎么算」而不是「算什么」，所以测试的唯一职责
 * 就是证明新算法和旧算法在真实字体上<b>逐字符给出同一个结果</b>。为此新实现与旧实现要在同一个进程里
 * 各跑一遍：旧实现按原样抄在这里（{@link #trimOld}），而不是靠记忆断言几个样例。</p>
 *
 * <p>用真的 {@code pf_normal.ttf} 走真 Skia 量宽，不用假字宽：全角括号替换、中日韩字符的宽步进、
 * 以及字距项「前字符替换后、后字符用原字符」这一不对称口径，只有在真字体上才量得出来。</p>
 */
class CFontRendererTrimStaticTest {

    private static CFontRenderer plain;
    /** 测试自己留着那个 Font 实例：{@code CFontRenderer.sourceFont} 是私有的，而 Skia 侧认的就是实例本身。 */
    private static java.awt.Font pfFont;

    @BeforeAll
    static void buildRenderers() throws Exception {
        assertTrue(com.muoniumplayer.render.skija.SkijaNatives.ensureLoaded(),
                "Skija 原生库加载失败: " + com.muoniumplayer.render.skija.SkijaNatives.failure());

        pfFont = awtFont("/muonium/fonts/pf_normal.ttf");
        plain = new CFontRenderer(pfFont, 18f);

        assertNotNull(plain);
        // 量不出来（原生库或字体没起来）时所有字宽都是 0，测试会假绿，所以先钉一条真实步进。
        assertTrue(plain.getStringWidthD("MMMMMMMM") > plain.getStringWidthD("M"),
                "字体没有真的解析出字宽，测试前提不成立");
    }

    private static java.awt.Font awtFont(String resource) throws Exception {
        byte[] bytes;
        try (InputStream in = CFontRendererTrimStaticTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "找不到字体资源 " + resource);
            bytes = in.readAllBytes();
        }
        java.awt.Font font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,
                new ByteArrayInputStream(bytes));
        // typefaceFor 认的是实例本身，必须把传进 CFontRenderer 的那个 Font 登记进去。
        com.muoniumplayer.render.skija.SkijaText.register(font, bytes);
        return font;
    }

    /** 旧实现原样照抄：一步一个 substring，一步步往回试。 */
    private static String trimOld(CFontRenderer renderer, String text, double width) {
        String name = text == null ? "" : text;
        if (width <= 0.0 || name.isEmpty()) {
            return "";
        }
        if (renderer.getStringWidthD(name) > width) {
            int idx = name.length();
            while (idx > 0) {
                String substring = name.substring(0, idx);
                if (renderer.getStringWidthD(substring + "...") <= width) {
                    return substring + "...";
                }
                idx--;
            }
            return "";
        }
        return name;
    }

    private static final String[] CORPUS = {
            "稻香",
            "晴天 (Live)",
            "晴天（Live）",
            "Starfall - Official Audio Stream",
            "夜的第七章 - 周杰伦 / 潘儿 Widgetz",
            "A",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "一二三四五六七八九十百千万亿兆京陔秭壤沟涧正载极恒河沙阿僧祇那由他福紫",
            "混合 Mixed 文字 text 数字 1234 标点 ，。！？、（）《》",
            "        空格开头和结尾        ",
            "ellipsis…已经在了",
            "",
            "  ",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    };

    /**
     * 折叠结果必须逐字符相同：宽度扫一遍，从「连一个字符都放不下」一直扫到「全放得下」，
     * 再把每个前缀长度各试一次。
     *
     * <p>语料里特意留了 {@code 晴天（Live）} 这一条：测量时全角括号会换成半角，而字距项拿的下一个
     * 字符仍是<b>未替换</b>的原字符。新旧实现在这个不对称口径上必须给出同一个数，不然标题里带括号的
     * 歌就会在换算法的那天集体挪一位。</p>
     *
     * <p>字距项本身没测到 —— {@link FontKerning} 在这个版本里是恒返回 0 的占位实现，而它是 final
     * 类，测试没法注入一个非零的假字距。等哪天真的接了 kerning 表，这一格要补上。</p>
     */
    @Test
    void trimMatchesOldImplementationOnRealFont() {
        for (String text : CORPUS) {
            double full = plain.getStringWidthD(text);
            List<Double> widths = new ArrayList<Double>();
            widths.add(0.0);
            widths.add(-3.0);
            widths.add(1.0);
            widths.add(full * .5);
            widths.add(full * .17);
            widths.add(full - 1);
            widths.add(full);
            widths.add(full + 1);
            for (int i = 0; i < text.length(); i++) {
                widths.add(plain.getStringWidthD(text.substring(0, i + 1)) + 3);
            }
            for (double width : widths) {
                assertEquals(trimOld(plain, text, width), plain.trim(text, width),
                        "宽度 " + width + " 上新旧折叠不一致: " + text);
            }
        }
    }

    /** 带 {@code §} 与换行的串走的是保留下来的老路径，行为同样不能变。 */
    @Test
    void trimKeepsOldBehaviourForControlCodesAndNewlines() {
        String[] dirty = {
                "\u00a7a红色歌名\u00a7r 后面还有很长的正文内容",
                "第一行\n第二行更长一些需要折叠的内容",
                "\u00a71\n\u00a72",
                "abc\u00a7defg",
        };
        for (String text : dirty) {
            double full = plain.getStringWidthD(text);
            for (double width : new double[]{full * .3, full * .6, full - 1, full + 1}) {
                assertEquals(trimOld(plain, text, width), plain.trim(text, width),
                        "脏文本在宽度 " + width + " 上折叠结果不同: " + text.replace('\n', '|'));
            }
        }
    }

    /** {@code null} 与非正宽度这两条边界维持原样。 */
    @Test
    void trimHandlesNullAndNonPositiveWidth() {
        assertEquals("", plain.trim(null, 100));
        assertEquals("", plain.trim("任意文本", 0));
        assertEquals("", plain.trim("任意文本", -1));
        assertEquals("", plain.trim("", 100));
    }

    /** 行高计数与 {@code split("\n").length} 对齐，含「尾部空段丢掉」这一条容易漏的语义。 */
    @Test
    void lineCountMatchesSplit() {
        String[] texts = {
                "", "\n", "\n\n", "a", "a\n", "a\n\n", "\na", "\n\na", "a\nb", "a\n\nb",
                "a\nb\nc", "a\n\n\n", "   \n   ", "稻香\n第二行\n第三行",
                "\n\n\n\n", "x", "x\n\ny\n\n\n",
        };
        for (String text : texts) {
            assertEquals(text.split("\n").length, CFontRenderer.countSplitLines(text),
                    "行高计数与 split 不一致: " + text.replace('\n', '|'));
        }
    }

    /** {@code getStringHeight} 整条链：与旧表达式在同一个字体上给出同一个数。 */
    @Test
    void stringHeightMatchesOldExpression() {
        for (String text : new String[]{"", "单行", "两\n行", "三\n行\n字", "尾随换行\n"}) {
            double expected = text.split("\n").length * (plain.getFontHeight() + 4) - 4;
            assertEquals(expected, plain.getStringHeight(text), "getStringHeight 变了: " + text);
        }
    }

    /** 新折叠不能把一次性前缀写进字宽表：那会把整表顶到上限并触发全表清空。 */
    @Test
    void trimDoesNotPolluteWidthCache() {
        String longTitle = "夜的第七章 - 周杰伦 / 潘儿 Widgetz 混音版 现场 Live 完整版";
        CFontRenderer renderer = new CFontRenderer(pfFont, 18f);
        renderer.trim(longTitle, 100);        // 先把「整串宽度」这一条正常缓存填上
        int before = widthCacheSize(renderer);
        for (double width = 10; width < 400; width += 7) {
            renderer.trim(longTitle, width);
        }
        assertEquals(before, widthCacheSize(renderer),
                "折叠过程往字宽记忆里塞了东西（旧实现每步一条，是打开长列表时的卡顿来源之一）");
    }

    private static int widthCacheSize(CFontRenderer renderer) {
        try {
            java.lang.reflect.Field field = CFontRenderer.class.getDeclaredField("stringWidthMapD");
            field.setAccessible(true);
            return ((java.util.Map<?, ?>) field.get(renderer)).size();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("字宽记忆表字段名变了，测试要跟着改: " + failure);
        }
    }

    /** 兜住 {@link Typeface} 的导入：没有它说明 Skia 根本没参与，前面的断言就都成了空的。 */
    @Test
    void skiaTypefaceIsInUse() {
        Typeface typeface = com.muoniumplayer.render.skija.SkijaText.typefaceFor(pfFont);
        assertNotNull(typeface, "字体没有拿到 Skia typeface，宽度全部按 0 计算");
    }
}
