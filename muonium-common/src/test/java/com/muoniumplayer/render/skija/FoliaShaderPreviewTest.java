package com.muoniumplayer.render.skija;



import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两支着色器（隐现 / 漫游）的离屏预览图（落在 {@code <repo>/.scratch/folia-visual/}）。
 *
 * <p>只用来人眼核对，不做像素断言；断言版本在 {@link FoliaBackgroundShaderTest}。</p>
 */
class FoliaShaderPreviewTest {

    private static final int W = 640;
    private static final int H = 360;

    @Test
    void writePreviewPngs() throws Exception {
        assertTrue(SkijaNatives.ensureLoaded());
        Path out = Paths.get("..", ".scratch", "folia-visual").toAbsolutePath().normalize();
        Files.createDirectories(out);

        try (Surface coverSurface = Surface.makeRasterN32Premul(256, 256)) {
            Image cover = makeCover(coverSurface);
            Shader child = cover.makeShader(
                    io.github.humbleui.skija.FilterTileMode.CLAMP,
                    io.github.humbleui.skija.FilterTileMode.CLAMP,
                    SamplingMode.LINEAR,
                    new io.github.humbleui.skija.Matrix33(
                            32f / cover.getWidth(), 0f, 0f,
                            0f, 32f / cover.getHeight(), 0f, 0f, 0f, 1f));
            try {
                latent(out.resolve("latent.png"), child);
                nomand(out.resolve("nomand-dither.png"), child, 0);
                nomand(out.resolve("nomand-glass.png"), child, 1);
                nomand(out.resolve("nomand-paper.png"), child, 2);
                nomand(out.resolve("nomand-halftone.png"), child, 3);
                nomand(out.resolve("nomand-lens.png"), child, 4);
            } finally {
                child.close();
            }
        }
        
    }

    private void latent(Path path, Shader cover) throws Exception {
        RuntimeEffect effect = compile("latent.sksl");
        assertNotNull(effect);
        try (Surface surface = Surface.makeRasterN32Premul(W, H)) {
            surface.getCanvas().clear(0xFF000000);
            Shader shaded = null;
            try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(effect)) {
                builder.setUniform("resolution", (float) W, (float) H);
                builder.setUniform("meshTime", 3.0f);
                builder.setUniform("ditherTime", 2.0f);
                builder.setUniform("alpha", 1f);
                builder.setUniform("layer", 2f);
                int[] mesh = {0xE85D04, 0x14213D, 0x14532D, 0xF2E9E4, 0x1B1B1F, 0x9A4DFF};
                for (int i = 0; i < 6; i++) {
                    int rgb = mesh[i];
                    builder.setUniform("c" + i,
                            ((rgb >> 16) & 0xFF) / 255f,
                            ((rgb >> 8) & 0xFF) / 255f,
                            (rgb & 0xFF) / 255f, 1f);
                }
                builder.setUniform("distortion", 1.0f);
                builder.setUniform("swirl", 0.2f);
                builder.setUniform("grainOverlay", 0.01f);
                builder.setUniform("meshSaturation", 1.1f);
                builder.setUniform("meshBrightness", 0.96f);
                builder.setUniform("meshScale", 1.025f);
                builder.setUniform("ditherBack", .08f, .08f, .1f);
                builder.setUniform("ditherFront", .9f, .55f, .2f);
                builder.setUniform("ditherSize", 2.5f);
                builder.setUniform("ditherOpacity", 0.55f);
                builder.setUniform("ditherScale", 1.015f);
                builder.setChild("cover", cover);
                shaded = builder.makeShader();
                try (Paint paint = new Paint()) {
                    paint.setShader(shaded);
                    surface.getCanvas().drawRect(Rect.makeXYWH(0, 0, W, H), paint);
                }
            } finally {
                if (shaded != null) shaded.close();
            }
            Image snapshot = surface.makeImageSnapshot();
            write(path, snapshot);
        }
        effect.close();
    }

    private void nomand(Path path, Shader cover, int effectIndex) throws Exception {
        RuntimeEffect effect = compile("nomand.sksl");
        assertNotNull(effect);
        try (Surface surface = Surface.makeRasterN32Premul(W, H)) {
            surface.getCanvas().clear(0xFF000000);
            Shader shaded = null;
            try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(effect)) {
                builder.setUniform("resolution", (float) W, (float) H);
                builder.setUniform("alpha", 1f);
                builder.setUniform("effect", (float) effectIndex);
                builder.setUniform("inverted", 0f);
                builder.setUniform("originalColors", 0f);
                builder.setUniform("colorSteps", 4f);
                builder.setUniform("pxSize", 3f);
                builder.setUniform("ditherType", 2f);
                builder.setUniform("colorFront", .92f, .36f, .02f, 1f);
                builder.setUniform("colorBack", .06f, .07f, .09f, 1f);
                builder.setUniform("colorHighlight", .95f, .8f, .4f, 1f);
                builder.setUniform("colorShadow", .5f, .5f, .55f, 1f);
                builder.setUniform("glassSize", 0.5f);
                builder.setUniform("glassDistortion", 0.5f);
                builder.setUniform("glassBlur", 0.08f);
                builder.setUniform("paperContrast", 0.32f);
                builder.setUniform("paperRoughness", 0.42f);
                builder.setUniform("paperFiber", 0.3f);
                builder.setUniform("dotSize", 0.5f);
                builder.setUniform("dotRadius", 1.25f);
                builder.setUniform("dotContrast", 0.4f);
                builder.setUniform("lensSpread", 0.45f);
                builder.setUniform("lensBulge", 0.3f);
                builder.setUniform("lensDispersion", 0.65f);
                builder.setUniform("brightness", 0.92f);
                builder.setUniform("themeBack", .06f, .07f, .09f);
                builder.setChild("cover", cover);
                shaded = builder.makeShader();
                try (Paint paint = new Paint()) {
                    paint.setShader(shaded);
                    surface.getCanvas().drawRect(Rect.makeXYWH(0, 0, W, H), paint);
                }
            } finally {
                if (shaded != null) shaded.close();
            }
            Image snapshot = surface.makeImageSnapshot();
            write(path, snapshot);
        }
        effect.close();
    }


    private static RuntimeEffect compile(String name) throws Exception {
        try (InputStream in = FoliaShaderPreviewTest.class.getResourceAsStream("/shaders/folia/" + name)) {
            StringBuilder source = new StringBuilder();
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) source.append(buffer, 0, read);
            return RuntimeEffect.makeForShader(source.toString());
        }
    }

    private static String brightness(Image image) throws Exception {
        try (io.github.humbleui.skija.Bitmap bitmap = new io.github.humbleui.skija.Bitmap()) {
            bitmap.allocN32Pixels(image.getWidth(), image.getHeight(), false);
            image.readPixels(bitmap, 0, 0);
            int bright = 0, mid = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int c = bitmap.getColor(x, y);
                    int l = Math.max((c >> 16) & 0xFF, Math.max((c >> 8) & 0xFF, c & 0xFF));
                    if (l > 160) bright++;
                    else if (l > 60) mid++;
                }
            }
            return "bright=" + bright + " mid=" + mid;
        }
    }

    private static void write(Path path, Image image) throws Exception {
        try {
            io.github.humbleui.skija.Data data = image.encodeToData(EncodedImageFormat.PNG);
            if (data != null) Files.write(path, data.getBytes());
        } finally {
            image.close();
        }
    }

    private static Image makeCover(Surface surface) {
        Canvas canvas = surface.getCanvas();
        canvas.clear(0xFF14213D);
        int[] stripes = {0xFFE85D04, 0xFF14532D, 0xFF3D1442};
        for (int i = 0; i < stripes.length; i++) {
            try (Paint paint = new Paint().setColor(stripes[i])) {
                canvas.drawRect(Rect.makeXYWH(40 + i * 56, 30 + i * 40, 70, 120), paint);
            }
        }
        try (Paint paint = new Paint().setColor(0xFFF2E9E4)) {
            canvas.drawCircle(180, 200, 40, paint);
        }
        return surface.makeImageSnapshot();
    }
}






