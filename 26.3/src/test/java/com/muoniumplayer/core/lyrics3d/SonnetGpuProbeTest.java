package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Picture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.awt.Font;
import java.util.List;
import static com.muoniumplayer.core.lyrics3d.SonnetCanvasRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDL/OpenGL integration for the exact 26.3 host; never counted as a raster test. */
@RequiresSkijaNative
@EnabledIfSystemProperty(named = "muonium.test.gpuTrails", matches = "true")
class SonnetGpuProbeTest {
    private static final int WIDTH = 960, HEIGHT = 540;
    @Test
    @EnabledIfSystemProperty(named = "muonium.test.gpuTrails", matches = "true")
    void recordedSonnetTextAndScreenEdgesActuallyReplayThroughThe263GpuHost() throws Exception {
        // Reuse this lane's hidden SDL/OpenGL fixture. It owns the MC render-thread/context handoff.
        Class<?> fixture = Class.forName("com.muoniumplayer.render.skija.SkijaTrailGpuTest$Gpu");
        java.lang.reflect.Constructor<?> ctor = fixture.getDeclaredConstructor();
        ctor.setAccessible(true);
        java.lang.reflect.Method render = fixture.getDeclaredMethod("render", Picture.class, int.class, int.class, int.class);
        java.lang.reflect.Method read = fixture.getDeclaredMethod("read", int.class, int.class);
        render.setAccessible(true); read.setAccessible(true);
        Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
        try (AutoCloseable gpu = (AutoCloseable) ctor.newInstance();
             CFontRenderer bold = new CFontRenderer(latin, 25, cjk);
             CFontRenderer regular = new CFontRenderer(latin, 25, cjk);
             SonnetCanvasRenderer renderer = new SonnetCanvasRenderer(new SonnetTextFonts(bold, regular));
             SonnetFrameSurface raster = new SonnetFrameSurface()) {
            List<LyricLine> lines = fixture();
            renderer.updateProgram(lines, 1, seedFor(lines, SonnetTypography.Kind.TYPE_IMPACT));
            for (int frame = 0; frame < 4; frame++) {
                try (Picture picture = renderer.record(0, 1200 + frame * 400, settings(), WIDTH, HEIGHT)) {
                    int[] cpu = raster.rasterize(picture, WIDTH, HEIGHT).clone();
                    render.invoke(gpu, picture, WIDTH, HEIGHT, 0);
                    java.nio.ByteBuffer actual = (java.nio.ByteBuffer) read.invoke(gpu, WIDTH, HEIGHT);
                    double error = 0;
                    int gpuVisible = 0;
                    for (int i = 0; i < cpu.length; i++) {
                        double ca = (cpu[i] >>> 24) / 255.0;
                        double ga = (actual.get(i * 4 + 3) & 255) / 255.0;
                        if (ga > 0.04) gpuVisible++;
                        for (int channel = 0; channel < 3; channel++) {
                            int cc = cpu[i] >>> (channel * 8) & 255;
                            int gc = actual.get(i * 4 + channel) & 255;
                            error += Math.abs(cc * ca - gc * ga);
                        }
                    }
                    assertTrue(gpuVisible > 100, "GPU path must contain real text pixels");
                    assertTrue(error / (cpu.length * 3) < 3, "GPU/raster composite drift: " + error / (cpu.length * 3));
                }
            }
        } finally { SonnetDecorations.close(); }
    }

}
