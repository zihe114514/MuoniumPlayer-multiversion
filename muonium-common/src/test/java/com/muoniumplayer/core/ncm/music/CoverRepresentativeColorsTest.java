package com.muoniumplayer.core.ncm.music;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 封面代表色提取的回归探针：过滤规则（零散杂色/暗部弱偏色/相近色相）与
 * 兜底（浅中性补充/灰阶提亮）用合成图逐项钉住。
 */
class CoverRepresentativeColorsTest {

    private static BufferedImage image(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        }
        return img;
    }

    /** 左半主色块 + 右下角一小撮杂色：杂色面积不足 0.5% 应被滤掉。 */
    @Test
    void dominantColorSurvivesScatteredNoise() {
        BufferedImage img = image(128, 128, 0xFF3A6EA5);   // 蓝主色
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) img.setRGB(120 + x, 120 + y, 0xFFFF2200); // 16px 噪点
        }
        int[] out = CoverRepresentativeColors.extract(img, 4);
        assertEquals(2, out.length, "杂色被滤后 = 主色 + 主色浅变体（渐变兜底保 2 色）");
        // 出口提亮（S≤0.75 / V≥0.92）：色相不变、亮度不低于原值。
        int r = (out[0] >> 16) & 0xFF, g = (out[0] >> 8) & 0xFF, b = out[0] & 0xFF;
        assertTrue(b >= 0xA5 && b >= r && b >= g, "蓝色主相保留且已提亮");
        assertTrue(Math.max(r, Math.max(g, b)) >= 0xEB, "V 出口 ≥0.92（≈235）");
    }

    @Test
    void fullyDarkImageFallsBackToLiftedGray() {
        BufferedImage img = image(64, 64, 0xFF0A0A0C);
        int[] out = CoverRepresentativeColors.extract(img, 4);
        // 灰阶兜底现在给双端（灰 → 亮灰），渐变成立
        assertEquals(2, out.length);
        int r = (out[0] >> 16) & 0xFF, g = (out[0] >> 8) & 0xFF, b = out[0] & 0xFF;
        int v = Math.max(r, Math.max(g, b));
        assertTrue(v > 0x0A, "纯暗图应提亮兜底，实际最大通道 " + v);
        assertEquals(r, g);
        assertEquals(g, b);
    }

    @Test
    void nearDuplicateHuesAreMerged() {
        // 两个色相仅差 5° 的色块（同去重带）+ 足够面积：只出一色。
        BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                img.setRGB(x, y, x < 64 ? 0xFFFF3000 : 0xFFFF6600);
            }
        }
        int[] out = CoverRepresentativeColors.extract(img, 4);
        // 去重后剩 1 色，渐变兜底再补成 2 色（同相不同明度端点）
        assertEquals(2, out.length, "去重后兜底为 2 色端点");
        assertNotEquals(out[0], out[1], "兜底两端不得相同");
    }

    @Test
    void distinctHuesProduceMultipleEntries() {
        BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int q = (x / 32) % 4;
                img.setRGB(x, y, new int[] {0xFFD32F2F, 0xFF2F6BD3, 0xFF2FD35A, 0xFFF2D32F}[q]);
            }
        }
        int[] out = CoverRepresentativeColors.extract(img, 4);
        assertEquals(4, out.length, "四个色相彼此拉开的色块应各出一色");
        for (int i = 1; i < out.length; i++) {
            assertNotEquals(out[i], out[i - 1], "相邻代表色不得相同");
        }
    }

    @Test
    void lightNeutralAddedWhenColorfulAreaSmall() {
        // 大面积浅米白 + 一小块彩色：彩色出 1 色 + 浅中性补 1 色。
        BufferedImage img = image(128, 128, 0xFFF5F0E6);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) img.setRGB(x, y, 0xFF2F6BD3);
        }
        int[] out = CoverRepresentativeColors.extract(img, 4);
        assertTrue(out.length >= 2, "浅中性面积 ≥5% 应补充色，实际 " + out.length);
    }

    @Test
    void transparentImageYieldsEmpty() {
        BufferedImage img = image(32, 32, 0x003A6EA5);
        int[] out = CoverRepresentativeColors.extract(img, 4);
        assertEquals(0, out.length, "全透明图无有效像素，返回空");
    }
}
