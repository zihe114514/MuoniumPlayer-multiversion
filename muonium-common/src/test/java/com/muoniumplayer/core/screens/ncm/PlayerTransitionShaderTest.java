package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.render.skija.SkijaNatives;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/** Real SkSL compilation and raster pixels; not a GPU timing or in-game visual acceptance. */
@RequiresSkijaNative
class PlayerTransitionShaderTest {
    @BeforeAll
    static void loadNative() {
        assertTrue(SkijaNatives.ensureLoaded());
    }

    @Test
    void exposureProgramCompilesWithFourPackedUniforms() {
        try (RuntimeEffect effect = RuntimeEffect.makeForShader(PlayerTransitionContentCache.MOTION_SHADER)) {
            assertEquals(64, effect.getUniformSize());
        }
    }

    @Test
    void noExposurePreservesPixelsAndOpacityIsPremultiplied() {
        try (Surface sourceSurface = Surface.makeRasterN32Premul(32, 32);
             Paint color = new Paint().setColor(0xFFFF4000);
             Surface output = Surface.makeRasterN32Premul(32, 32);
             RuntimeEffect effect = RuntimeEffect.makeForShader(PlayerTransitionContentCache.MOTION_SHADER)) {
            sourceSurface.getCanvas().drawRect(Rect.makeXYWH(0, 0, 32, 32), color);
            try (Image image = sourceSurface.makeImageSnapshot();
                 Shader source = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null);
                 Data data = uniforms(.5f, 0f, 1f, 0f, 0f);
                 Shader shader = effect.makeShader(data, new Shader[]{source});
                 Paint paint = new Paint().setShader(shader)) {
                output.getCanvas().clear(0);
                output.getCanvas().drawRect(Rect.makeXYWH(0, 0, 32, 32), paint);
                byte[] pixels = pixels(output);
                int i = (16 * 32 + 16) * 4;
                assertEquals(128, pixels[i + 3] & 255, 1);
                assertEquals(128, pixels[i + 2] & 255, 1);
                assertEquals(32, pixels[i + 1] & 255, 1);
            }
        }
    }

    @Test
    void finiteExposureSoftensAnEdgeWithoutLeakingOutsideTheViewport() {
        try (Surface sourceSurface = Surface.makeRasterN32Premul(32, 32);
             Paint white = new Paint().setColor(0xFFFFFFFF);
             Surface output = Surface.makeRasterN32Premul(32, 32);
             RuntimeEffect effect = RuntimeEffect.makeForShader(PlayerTransitionContentCache.MOTION_SHADER)) {
            sourceSurface.getCanvas().clear(0xFF000000);
            sourceSurface.getCanvas().drawRect(Rect.makeXYWH(16, 0, 16, 32), white);
            try (Image image = sourceSurface.makeImageSnapshot();
                 Shader source = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null);
                 Data data = uniforms(1f, 1f, 1f, 4f, 0f);
                 Shader shader = effect.makeShader(data, new Shader[]{source});
                 Paint paint = new Paint().setShader(shader)) {
                output.getCanvas().clear(0);
                output.getCanvas().drawRect(Rect.makeXYWH(0, 0, 32, 32), paint);
                byte[] pixels = pixels(output);
                int edge = (16 * 32 + 14) * 4;
                assertTrue((pixels[edge + 2] & 255) > 0 && (pixels[edge + 2] & 255) < 255);
                assertEquals(0, pixels[(2 * 32 + 2) * 4 + 3] & 255,
                        "sampling must not leak beyond the cached viewport");
            }
        }
    }

    private static Data uniforms(float opacity, float exposure, float ratio, float dx, float dy) {
        ByteBuffer bytes = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        for (float value : new float[]{1, 1, 0, 0, 4, 4, 28, 28, ratio, dx, dy, 0,
                opacity, exposure, 0, 0}) bytes.putFloat(value);
        return Data.makeFromBytes(bytes.array());
    }

    private static byte[] pixels(Surface surface) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocPixels(ImageInfo.makeN32Premul(32, 32));
            assertTrue(surface.readPixels(bitmap, 0, 0));
            return bitmap.readPixels();
        }
    }
}
