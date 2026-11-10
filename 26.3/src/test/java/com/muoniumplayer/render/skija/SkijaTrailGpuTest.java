package com.muoniumplayer.render.skija;

import com.muoniumplayer.core.lyrics3d.HuiguangTrailCanvas;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Picture;
import io.github.humbleui.skija.PictureRecorder;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Real local OpenGL probe; explicitly enabled, never silently substituted by raster rendering. */
@RequiresSkijaNative
@EnabledIfSystemProperty(named = "muonium.test.gpuTrails", matches = "true")
class SkijaTrailGpuTest {
    private static final float[] XS = {12f, 20f, 38f, 51f, 73f, 94f};
    private static final float[] YS = {11f, 18f, 9f, 32f, 38f, 24f};

    private static final class Gpu implements AutoCloseable {
        long window, gl;
        DirectContext context;
        int texture, fbo, capacityWidth = 2048, capacityHeight = 1024;
        final Object previousContext;
        final Object previousRenderThread;
        final boolean previousDisabled;

        Gpu() throws Exception {
            previousContext = field("context").get(null);
            Field renderThread = renderThread();
            previousRenderThread = renderThread.get(null);
            renderThread.set(null, null);
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            previousDisabled = field("disabled").getBoolean(null);
            field("disabled").setBoolean(null, false);
            assertTrue(SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO), SDLError.SDL_GetError());
            assertTrue(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 3));
            assertTrue(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 2));
            assertTrue(SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE));
            window = SDLVideo.SDL_CreateWindow("Muonium GPU trail test", 64, 64,
                    SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
            assertNotEquals(0, window, SDLError.SDL_GetError());
            gl = SDLVideo.SDL_GL_CreateContext(window);
            assertNotEquals(0, gl, SDLError.SDL_GetError());
            assertTrue(SDLVideo.SDL_GL_MakeCurrent(window, gl));
            GL.createCapabilities();
            context = DirectContext.makeGL();
            assertNotNull(context);
            field("context").set(null, context);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, capacityWidth, capacityHeight, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            fbo = GL30.glGenFramebuffers();
        }

        void render(Picture picture, int width, int height, int slot) {
            assertTrue(SkijaHost.renderOffscreen(() -> SkijaTrailRenderer.renderOn(context, picture,
                    width, height, texture, capacityWidth, capacityHeight, slot)));
        }

        ByteBuffer read(int width, int height) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            return pixels;
        }

        @Override
        public void close() throws Exception {
            HuiguangTrailCanvas.close();
            if (context != null) {
                SkijaTrailRenderer.release();
                context.close();
            }
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (texture != 0) GL11.glDeleteTextures(texture);
            field("context").set(null, previousContext);
            field("disabled").setBoolean(null, previousDisabled);
            renderThread().set(null, previousRenderThread);
            GL.setCapabilities(null);
            if (gl != 0) SDLVideo.SDL_GL_DestroyContext(gl);
            if (window != 0) SDLVideo.SDL_DestroyWindow(window);
            SDLInit.SDL_Quit();
        }
    }

    private static Field renderThread() throws Exception {
        Field field = com.mojang.blaze3d.systems.RenderSystem.class.getDeclaredField("renderThread");
        field.setAccessible(true);
        return field;
    }

    private static Field field(String name) throws Exception {
        Field field = SkijaHost.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void draw(float[] xs, float[] ys, int color, float alpha, boolean overlap) {
        HuiguangTrailCanvas.strokeTrail(xs, ys, xs.length, color, alpha, 10f);
        if (overlap) HuiguangTrailCanvas.strokeTrail(new float[] {17f, 52f, 91f},
                new float[] {34f, 22f, 12f}, 3, 0x3040FF, .48f, 10f);
    }

    private static Picture recording(float width, float height, float[] xs, float[] ys,
                                     int color, float alpha, boolean overlap) {
        assertTrue(HuiguangTrailCanvas.beginRecording(width, height, 0f, 0f));
        draw(xs, ys, color, alpha, overlap);
        Picture picture = HuiguangTrailCanvas.finishRecording();
        assertNotNull(picture);
        return picture;
    }

    private static void compare(int[] expected, ByteBuffer actual, String label, int width, int height) {
        double alphaError = 0, premulError = 0;
        int maximumAlphaError = 0;
        for (int i = 0; i < expected.length; i++) {
            int a = actual.get(i * 4 + 3) & 255;
            int oldA = expected[i] >>> 24;
            int diff = Math.abs(a - oldA);
            alphaError += diff;
            maximumAlphaError = Math.max(maximumAlphaError, diff);
            for (int c = 0; c < 3; c++) {
                int value = actual.get(i * 4 + c) & 255;
                int old = expected[i] >>> (c * 8) & 255;
                // Compare actual SRC_ALPHA contributions, not arbitrary RGB at zero alpha.
                premulError += Math.abs(value * a - old * oldA) / 255.0;
            }
        }
        double mae = alphaError / expected.length;
        double colorMae = premulError / (expected.length * 3);
        System.out.printf(Locale.ROOT, "[GpuTrails] %s %dx%d alpha MAE=%.4f max=%d premul MAE=%.4f%n",
                label, width, height, mae, maximumAlphaError, colorMae);
        assertTrue(mae < 1.0, label + ": alpha drift or inverted image");
        assertTrue(colorMae < 1.0, label + ": visible color drift");
        assertTrue(maximumAlphaError <= 40, label + ": isolated edge error is excessive");
        // Compare LINEAR filtering's final contribution on dark and light backgrounds.
        double filteredError = 0;
        for (float scale : new float[] {.4f, 1f, 1.7f}) {
            for (int sample = 0; sample < 300; sample++) {
                float x = ((sample * 31) % width + .37f) / scale;
                float y = ((sample * 17) % height + .63f) / scale;
                x = Math.max(0, Math.min(width - 1, x));
                y = Math.max(0, Math.min(height - 1, y));
                int x0 = (int) x, y0 = (int) y;
                int x1 = Math.min(width - 1, x0 + 1), y1 = Math.min(height - 1, y0 + 1);
                float tx = x - x0, ty = y - y0;
                for (int channel = 0; channel < 3; channel++) {
                    double newA = bilinear(actual, null, width, x0, y0, x1, y1, tx, ty, 3);
                    double oldA = bilinear(null, expected, width, x0, y0, x1, y1, tx, ty, 3);
                    double newC = bilinear(actual, null, width, x0, y0, x1, y1, tx, ty, channel);
                    double oldC = bilinear(null, expected, width, x0, y0, x1, y1, tx, ty, channel);
                    for (int background : new int[] {12, 224}) {
                        filteredError += Math.abs(newC * newA / 255.0 + background * (1 - newA / 255.0)
                                - oldC * oldA / 255.0 - background * (1 - oldA / 255.0));
                    }
                }
            }
        }
        assertTrue(filteredError / (3 * 300 * 3 * 2) < 1.0, label + ": filtered edge/composite drift");
    }

    private static double bilinear(ByteBuffer bytes, int[] pixels, int width, int x0, int y0,
                                   int x1, int y1, float tx, float ty, int channel) {
        int[] indices = {y0 * width + x0, y0 * width + x1, y1 * width + x0, y1 * width + x1};
        double[] values = new double[4];
        for (int i = 0; i < 4; i++) values[i] = bytes != null
                ? bytes.get(indices[i] * 4 + channel) & 255 : pixels[indices[i]] >>> (channel * 8) & 255;
        return (values[0] * (1 - tx) + values[1] * tx) * (1 - ty)
                + (values[2] * (1 - tx) + values[3] * tx) * ty;
    }

    @Test
    void realGpuPreservesAlphaOrientationOverlapAndReusedTargetSizes() throws Exception {
        try (Gpu gpu = new Gpu()) {
            System.out.println("[GpuTrails] Renderer: " + GL11.glGetString(GL11.GL_RENDERER));
            for (int i = 0; i < 8; i++) {
                float width = i == 4 ? 511.5f : i == 5 ? 47.5f : 113f;
                float height = i == 4 ? 257.5f : i == 5 ? 29.5f : 57f;
                float[] xs = i == 6 ? new float[] {42f, 42f, 42f} : XS;
                float[] ys = i == 6 ? new float[] {21f, 21f, 21f} : YS;
                float alpha = i == 1 ? .08f : i == 2 ? 1f : .75f;
                int color = i == 3 ? 0 : i == 7 ? 0xFFFFFF : 0xF08030;
                boolean overlap = i == 2;
                assertTrue(HuiguangTrailCanvas.begin(width, height, 0f, 0f));
                draw(xs, ys, color, alpha, overlap);
                int[] expected = HuiguangTrailCanvas.readPixelsAbgr();
                int w = HuiguangTrailCanvas.canvasWidth(), h = HuiguangTrailCanvas.canvasHeight();
                try (Picture picture = recording(width, height, xs, ys, color, alpha, overlap)) {
                    gpu.render(picture, w, h, i % 4);
                }
                compare(expected, gpu.read(w, h), "case=" + i, w, h);
            }
            SkijaTrailRenderer.release();
            try (Picture picture = recording(113f, 57f, XS, YS, 0xF08030, .75f, false)) {
                gpu.render(picture, 226, 114, 0);
            }
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        }
    }

    @Test
    void conversionMatchesRasterIntegerUnpremultiplyAndRestoresGlStateEvenOnFailure() throws Exception {
        try (Gpu gpu = new Gpu(); PictureRecorder recorder = new PictureRecorder(); Paint paint = new Paint()) {
            Canvas canvas = recorder.beginRecording(Rect.makeWH(64, 32));
            canvas.clear(0);
            int[] colors = {0x803418E7, 0xFF40A030, 0x0100FFFF, 0x400080FF};
            for (int i = 0; i < colors.length; i++) {
                paint.setColor(colors[i]);
                canvas.drawRect(Rect.makeXYWH(i * 16, 0, 16, 32), paint);
            }
            try (Picture picture = recorder.finishRecordingAsPicture()) {
                assertTrue(HuiguangTrailCanvas.beginRecording(32f, 16f, 0f, 0f));
                try (Picture empty = HuiguangTrailCanvas.finishRecording()) {
                    assertNotNull(empty);
                }
                assertTrue(HuiguangTrailCanvas.rasterize(picture));
                int[] expected = HuiguangTrailCanvas.readPixelsAbgr();
                gpu.render(picture, 64, 32, 0);
                ByteBuffer actual = gpu.read(64, 32);
                for (int i = 0; i < expected.length; i++) {
                    for (int c = 0; c < 4; c++) {
                        assertEquals(expected[i] >>> (c * 8) & 255, actual.get(i * 4 + c) & 255,
                                "solid color byte " + c + " at " + i);
                    }
                }
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, gpu.fbo);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
                GL11.glViewport(2, 3, 41, 37);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(4, 5, 21, 19);
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                GL11.glEnable(GL11.GL_DITHER);
                GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
                GL11.glClearColor(.13f, .27f, .39f, .51f);
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + 3);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, gpu.texture);
                gpu.render(picture, 64, 32, 0);
                assertTrue(GL11.glIsEnabled(GL11.GL_DITHER));
                assertTrue(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
                assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
                assertTrue(GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB));
                assertEquals(GL13.GL_TEXTURE0 + 3, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
                assertEquals(gpu.texture, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
                assertEquals(gpu.fbo, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
                assertEquals(0, GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
                int[] viewport = new int[4];
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                assertArrayEquals(new int[] {2, 3, 41, 37}, viewport);
                assertThrows(IllegalStateException.class, () -> SkijaHost.renderOffscreen(() -> {
                    GL11.glDisable(GL11.GL_DITHER);
                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glViewport(0, 0, 2, 2);
                    throw new IllegalStateException("GPU operation rejected");
                }));
                assertTrue(GL11.glIsEnabled(GL11.GL_DITHER));
                assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                assertArrayEquals(new int[] {2, 3, 41, 37}, viewport);
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "muonium.test.gpuTrailBenchmark", matches = "true")
    void warmPathReportsCpuTimeAllocationAndCompletedGpuWork() throws Exception {
        try (Gpu gpu = new Gpu()) {
            float[][] xs = new float[12][21], ys = new float[12][21];
            for (int line = 0; line < xs.length; line++) {
                for (int p = 0; p < 21; p++) {
                    xs[line][p] = 15f + p * 23f;
                    ys[line][p] = 20f + line * 23f + (float) Math.sin(p * .4 + line) * 9f;
                }
            }
            int width = 1024, height = 640;
            ByteBuffer upload = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
            com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            int rounds = 24;
            double[] cpuMs = new double[rounds], gpuMs = new double[rounds];
            long[] allocations = new long[2];
            for (int mode = 0; mode < 2; mode++) {
                for (int frame = -8; frame < rounds; frame++) {
                    GL11.glFinish();
                    long beforeBytes = bean.getThreadAllocatedBytes(thread);
                    long start = System.nanoTime();
                    if (mode == 0) assertTrue(HuiguangTrailCanvas.begin(512, 320, 0, 0));
                    else assertTrue(HuiguangTrailCanvas.beginRecording(512, 320, 0, 0));
                    for (int line = 0; line < xs.length; line++) {
                        HuiguangTrailCanvas.strokeTrail(xs[line], ys[line], 21, 0x4080FF, .65f, 10f);
                    }
                    if (mode == 0) {
                        int[] pixels = HuiguangTrailCanvas.readPixelsAbgr();
                        upload.clear();
                        upload.asIntBuffer().put(pixels);
                        GL13.glActiveTexture(GL13.GL_TEXTURE0);
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, gpu.texture);
                        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, upload);
                    } else {
                        try (Picture picture = HuiguangTrailCanvas.finishRecording()) {
                            gpu.render(picture, width, height, 0);
                        }
                    }
                    double submitMs = (System.nanoTime() - start) / 1_000_000.0;
                    long allocated = bean.getThreadAllocatedBytes(thread) - beforeBytes;
                    GL11.glFinish();
                    double completedMs = (System.nanoTime() - start) / 1_000_000.0;
                    if (frame >= 0) {
                        (mode == 0 ? cpuMs : gpuMs)[frame] = completedMs;
                        allocations[mode] += allocated;
                        if (frame == rounds - 1) System.out.printf(Locale.ROOT,
                                "[GpuTrails/bench] mode=%s last submit=%.3fms completed=%.3fms%n",
                                mode == 0 ? "CPU" : "GPU", submitMs, completedMs);
                    }
                }
            }
            Arrays.sort(cpuMs);
            Arrays.sort(gpuMs);
            System.out.printf(Locale.ROOT,
                    "[GpuTrails/bench] 1024x640 12 trails | completed median CPU=%.3fms GPU=%.3fms | Java allocation CPU=%d GPU=%d bytes/frame%n",
                    cpuMs[rounds / 2], gpuMs[rounds / 2], allocations[0] / rounds, allocations[1] / rounds);
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        }
    }
}
