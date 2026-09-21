package com.muoniumplayer.core.rendering.texture;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link DynamicTexture#readImage} 的 webp 兜底：咪咕搜索给的封面是 webp
 * （d.musicapp.migu.cn 实测 content-type image/webp），Java 自带 ImageIO 解不出来，
 * 必须由进程里已带的 skija 接手。fixture 用真实咪咕封面（.scratch/mg_cover.webp）。
 */
public class DynamicTextureWebpStaticTest {

    @Test
    public void imageioAloneCannotReadWebp() throws Exception {
        byte[] webp = fixture();
        assumeTrue(webp != null, "缺少 .scratch/mg_cover.webp 夹具");
        // 钉住根因：这条链路以前就是在 ImageIO 这里拿到 null 的。
        try (InputStream in = new ByteArrayInputStream(webp)) {
            assertNull(ImageIO.read(in));
        }
    }

    @Test
    public void readImageDecodesRealMiguWebpCover() throws Exception {
        byte[] webp = fixture();
        assumeTrue(webp != null, "缺少 .scratch/mg_cover.webp 夹具");
        BufferedImage image;
        try (InputStream in = new ByteArrayInputStream(webp)) {
            image = DynamicTexture.readImage(in);
        }
        assertNotNull(image, "skija 兜底没把咪咕 webp 封面解出来");
        assertTrue(image.getWidth() > 100 && image.getHeight() > 100,
                "尺寸异常: " + image.getWidth() + "x" + image.getHeight());
        // 全 0 像素说明字节序/行距换算错了；真实封面必有色彩波动。
        int distinct = 0, last = 0;
        for (int y = 0; y < image.getHeight(); y += Math.max(1, image.getHeight() / 32)) {
            for (int x = 0; x < image.getWidth(); x += Math.max(1, image.getWidth() / 32)) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                if (distinct > 0 && rgb != last) distinct++;
                if (distinct == 0) distinct = 1;
                last = rgb;
            }
        }
        assertTrue(distinct > 0, "像素全黑/全同，疑似解码错位");
    }

    @Test
    public void garbageBytesStayNull() throws Exception {
        byte[] junk = "not an image at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (InputStream in = new ByteArrayInputStream(junk)) {
            assertNull(DynamicTexture.readImage(in));
        }
    }

    private static byte[] fixture() throws Exception {
        File dir = new File("").getAbsoluteFile();
        for (int depth = 0; dir != null && depth < 6; depth++, dir = dir.getParentFile()) {
            File candidate = new File(dir, ".scratch/mg_cover.webp");
            if (candidate.isFile()) return Files.readAllBytes(candidate.toPath());
        }
        return null;
    }
}
