package com.muoniumplayer.core.lyrics3d;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SonnetDecorationProbeTest {

    private static final int WIDTH = 1440;
    private static final int HEIGHT = 840;
    private static final int BACKGROUND = 0xFF101720;

    @Test
    void entryGuidesAndWordFramesRenderVisibleGeometry() throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(BACKGROUND, true));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.translate(WIDTH * 0.5, HEIGHT * 0.5);
        graphics.scale(8.0, 8.0);

        AtomicInteger trailCalls = new AtomicInteger();
        AtomicInteger starCalls = new AtomicInteger();
        WorldLyricsRenderer.Host host = (WorldLyricsRenderer.Host) java.lang.reflect.Proxy
                .newProxyInstance(WorldLyricsRenderer.Host.class.getClassLoader(),
                        new Class<?>[] {WorldLyricsRenderer.Host.class}, (proxy, method, args) -> {
                            if ("drawTrail".equals(method.getName())) {
                                drawTrail(graphics, (float[]) args[0], (float[]) args[1],
                                        (int) args[2], (int) args[3], (float) args[4]);
                                trailCalls.incrementAndGet();
                            } else if ("drawStar".equals(method.getName())) {
                                drawStar(graphics, (float) args[0], (float) args[1],
                                        (float) args[2], (float) args[3], (int) args[4]);
                                starCalls.incrementAndGet();
                            }
                            return null;
                        });

        String[] words = {"微光", "穿过", "漫长", "夜色", "落在", "你的", "肩上"};
        String line = String.join(" ", words);
        long[] charTimes = new long[line.replace(" ", "").length()];
        int charIndex = 0;
        for (int i = 0; i < words.length; i++) {
            for (int c = 0; c < words[i].length(); c++) charTimes[charIndex++] = i * 280L;
        }
        WorldLyricsState.SpawnedLine spawned = new WorldLyricsState.SpawnedLine(
                List.of(line), 0L, 1800L, 0, 0, 0, 0, 0, charTimes);
        boolean rendered = false;

        for (long nowMs = 0; nowMs <= 3000L && !rendered; nowMs += 40L) {
            WorldLyricsState.LyricGlyphs glyphs = WorldLyricsState.buildGlyphsForRender(
                    spawned, nowMs, 1f, false, false, c -> 10f,
                    false, false, true, null, null);
            for (WorldLyricsState.LyricGlyph glyph : glyphs.glyphs) {
                if (!glyph.sonnetDecorationOwner || glyph.sonnetGuideAlpha < 0.1f
                        || glyph.sonnetFrameAlpha < 0.1f) continue;
                for (WorldLyricsState.LyricGlyph item : glyphs.glyphs) {
                    if (item.sonnetDecorationOwner) {
                        WorldLyricsRenderer.drawSonnetDecorations(host, item, 0x76E7FF);
                    }
                }
                graphics.setFont(new Font("Dialog", Font.BOLD, 9));
                graphics.setColor(Color.WHITE);
                for (WorldLyricsState.LyricGlyph item : glyphs.glyphs) {
                    graphics.drawString(String.valueOf(item.c), item.x + item.dx,
                            item.y + item.dy + 9f);
                }
                rendered = true;
                break;
            }
        }
        graphics.dispose();

        assertTrue(rendered, "探针歌词应找到同步出现的引导线与矩形包边");
        assertTrue(trailCalls.get() >= 2, "应分别提交曲线和矩形边线");
        assertTrue(starCalls.get() >= 3, "引导头和粒子应可见");
        assertTrue(hasDrawnPixels(image), "探针渲染不能是空白图");

        String probePath = System.getenv("MUONIUM_SONNET_PROBE_PATH");
        if (probePath != null && !probePath.isEmpty()) {
            Path output = Path.of(probePath);
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            ImageIO.write(image, "png", output.toFile());
        }
    }

    private static void drawTrail(Graphics2D graphics, float[] xs, float[] ys, int count,
                                  int argb, float width) {
        if (count < 2) return;
        Path2D path = new Path2D.Float();
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < count; i++) path.lineTo(xs[i], ys[i]);
        graphics.setColor(new Color(argb, true));
        graphics.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.draw(path);
    }

    private static void drawStar(Graphics2D graphics, float cx, float cy, float size,
                                 float rotation, int argb) {
        graphics.setColor(new Color(argb, true));
        graphics.setStroke(new BasicStroke(Math.max(0.2f, size * 0.12f),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double radians = Math.toRadians(rotation);
        float radius = size * 0.5f;
        for (int i = 0; i < 4; i++) {
            double angle = radians + i * Math.PI / 4.0;
            float dx = (float) Math.cos(angle) * radius;
            float dy = (float) Math.sin(angle) * radius;
            graphics.drawLine(Math.round(cx - dx), Math.round(cy - dy),
                    Math.round(cx + dx), Math.round(cy + dy));
        }
    }

    private static boolean hasDrawnPixels(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != BACKGROUND) return true;
            }
        }
        return false;
    }
}
