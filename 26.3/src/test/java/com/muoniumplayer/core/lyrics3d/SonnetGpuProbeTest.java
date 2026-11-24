package com.muoniumplayer.core.lyrics3d;

import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.testsupport.RequiresSkijaNative;
import io.github.humbleui.skija.Picture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.awt.Font;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import static com.muoniumplayer.core.lyrics3d.SonnetCanvasRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDL/OpenGL integration for the exact 26.3 host; never counted as a raster test. */
@RequiresSkijaNative
@EnabledIfSystemProperty(named = "muonium.test.gpuTrails", matches = "true")
class SonnetGpuProbeTest {
    private static final int WIDTH = 960, HEIGHT = 540;

    @Test
    @EnabledIfSystemProperty(named = "muonium.test.gpuSonnetBenchmark", matches = "true")
    void completeAnimatedSonnetReportsRecordingAndGpuCompletionCosts() throws Exception {
        Class<?> fixture = Class.forName("com.muoniumplayer.render.skija.SkijaTrailGpuTest$Gpu");
        java.lang.reflect.Constructor<?> ctor = fixture.getDeclaredConstructor();
        ctor.setAccessible(true);
        java.lang.reflect.Method render = fixture.getDeclaredMethod("renderMeasured", Picture.class,
                int.class, int.class, int.class, long[].class);
        render.setAccessible(true);
        WorldLyricsSettings base = settings();
        base.glowEnabled = true;
        base.glowStrength = 0.45f;
        base.glowSpread = 1.0f;
        int width = SonnetCanvasRenderer.surfaceWidth(null, base);
        int height = Math.round(width * SonnetCanvasRenderer.STAGE_HEIGHT / SonnetCanvasRenderer.STAGE_WIDTH);
        int rounds = 48;
        String[] variants = {"full-no-batch", "body-batch"};
        double[][] recordingMs = new double[variants.length][rounds];
        double[][] submitMs = new double[variants.length][rounds];
        double[][] waitMs = new double[variants.length][rounds];
        double[][][] phaseMs = new double[variants.length][3][rounds];
        long[] allocatedBytes = new long[variants.length];
        long[] batchedGlyphs = new long[variants.length];
        String gpuName;
        com.sun.management.ThreadMXBean allocationBean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) allocationBean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        Font latin = font("/muonium/fonts/sfregular.otf"), cjk = font("/muonium/fonts/pf_normal.ttf");
        gpuName = "unavailable";
        List<LyricLine> lines = fixture();
        String seed = seedFor(lines, SonnetTypography.Kind.TYPE_IMPACT);
        try {
            for (int variant = 0; variant < variants.length; variant++) {
                try (AutoCloseable gpu = (AutoCloseable) ctor.newInstance();
                     CFontRenderer bold = new CFontRenderer(font("/muonium/fonts/sfbold.otf"), 25,
                             font("/muonium/fonts/pf_middleblack.ttf"));
                     CFontRenderer regular = new CFontRenderer(latin, 25, cjk);
                     SonnetCanvasRenderer renderer = new SonnetCanvasRenderer(new SonnetTextFonts(bold, regular))) {
                    gpuName = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
                    renderer.stableGlyphBatching = variant == 1;
                    renderer.updateProgram(lines, 1, seed);
                    WorldLyricsSettings prerollSettings = settings();
                    prerollSettings.glowEnabled = true;
                    prerollSettings.glowStrength = base.glowStrength;
                    prerollSettings.glowSpread = base.glowSpread;
                    try (Picture preroll = renderer.record(0, 620, prerollSettings, width, height)) {
                        render.invoke(gpu, preroll, width, height, 0, new long[3]);
                    }
                    for (int frame = 0; frame < rounds; frame++) {
                        long timeMs = 1000L + frame * 50L;
                        WorldLyricsSettings settings = settings();
                        settings.glowEnabled = true;
                        settings.glowStrength = base.glowStrength;
                        settings.glowSpread = base.glowSpread;
                        settings.sonnetDecorations = true;
                        settings.sonnetGlyphEffects = true;
                        long[] phaseNanos = new long[3];
                        long beforeAllocated = allocationBean.getThreadAllocatedBytes(threadId);
                        long recordStart = System.nanoTime();
                        try (Picture picture = renderer.record(0, timeMs, settings, width, height)) {
                            double record = (System.nanoTime() - recordStart) / 1_000_000.0;
                            org.lwjgl.opengl.GL11.glFinish();
                            long submitStart = System.nanoTime();
                            render.invoke(gpu, picture, width, height, 0, phaseNanos);
                            double submit = (System.nanoTime() - submitStart) / 1_000_000.0;
                            long waitStart = System.nanoTime();
                            org.lwjgl.opengl.GL11.glFinish();
                            double wait = (System.nanoTime() - waitStart) / 1_000_000.0;
                            recordingMs[variant][frame] = record;
                            submitMs[variant][frame] = submit;
                            waitMs[variant][frame] = wait;
                            for (int phase = 0; phase < phaseNanos.length; phase++) {
                                phaseMs[variant][phase][frame] = phaseNanos[phase] / 1_000_000.0;
                            }
                            allocatedBytes[variant] += allocationBean.getThreadAllocatedBytes(threadId) - beforeAllocated;
                            batchedGlyphs[variant] += renderer.stableGlyphCountLastFrame;
                        }
                    }
                }
                SonnetDecorations.close();
            }
        } finally {
            SonnetDecorations.close();
        }
        System.out.printf(Locale.ROOT, "[Sonnet/bench] renderer=%s %dx%d pixels=%d timeline=1000..3350ms step=50ms%n",
                gpuName, width, height, (long) width * height);
        for (int variant = 0; variant < variants.length; variant++) {
            System.out.printf(Locale.ROOT,
                    "[Sonnet/bench] mode=%s record median/p95/max=%.3f/%.3f/%.3fms submit median/p95/max=%.3f/%.3f/%.3fms GPU wait median/p95/max=%.3f/%.3f/%.3fms Java allocation=%.0fB/frame stable-batched glyphs=%.1f/frame%n",
                    variants[variant], percentile(recordingMs[variant], 0.50), percentile(recordingMs[variant], 0.95),
                    maximum(recordingMs[variant]),
                    percentile(submitMs[variant], 0.50), percentile(submitMs[variant], 0.95), maximum(submitMs[variant]),
                    percentile(waitMs[variant], 0.50), percentile(waitMs[variant], 0.95), maximum(waitMs[variant]),
                    allocatedBytes[variant] / (double) rounds,
                    batchedGlyphs[variant] / (double) rounds);
            System.out.printf(Locale.ROOT,
                    "[Sonnet/bench] mode=%s picture-replay median/p95/max=%.3f/%.3f/%.3fms skia-flush=%.3f/%.3f/%.3fms gl-convert=%.3f/%.3f/%.3fms%n",
                    variants[variant], percentile(phaseMs[variant][0], 0.50), percentile(phaseMs[variant][0], 0.95),
                    maximum(phaseMs[variant][0]),
                    percentile(phaseMs[variant][1], 0.50), percentile(phaseMs[variant][1], 0.95), maximum(phaseMs[variant][1]),
                    percentile(phaseMs[variant][2], 0.50), percentile(phaseMs[variant][2], 0.95), maximum(phaseMs[variant][2]));
            System.out.printf(Locale.ROOT,
                    "[Sonnet/bench] mode=%s peak-times record=%dms replay=%dms flush=%dms submit=%dms%n",
                    variants[variant], timeAtMaximum(recordingMs[variant]),
                    timeAtMaximum(phaseMs[variant][0]), timeAtMaximum(phaseMs[variant][1]),
                    timeAtMaximum(submitMs[variant]));
        }
    }

    private static double percentile(double[] samples, double percentile) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * percentile) - 1)];
    }

    private static double maximum(double[] samples) {
        double maximum = 0;
        for (double sample : samples) maximum = Math.max(maximum, sample);
        return maximum;
    }

    private static long timeAtMaximum(double[] samples) {
        int maximumIndex = 0;
        for (int i = 1; i < samples.length; i++) {
            if (samples[i] > samples[maximumIndex]) maximumIndex = i;
        }
        return 1000L + maximumIndex * 50L;
    }

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

    @Test
    void parsedLyricsReachGpuAcrossLeadEntryBoundaryAndTerminalClear() throws Exception {
        Class<?> fixture = Class.forName("com.muoniumplayer.render.skija.SkijaTrailGpuTest$Gpu");
        java.lang.reflect.Constructor<?> ctor = fixture.getDeclaredConstructor();
        ctor.setAccessible(true);
        java.lang.reflect.Method render = fixture.getDeclaredMethod("render", Picture.class, int.class, int.class, int.class);
        java.lang.reflect.Method read = fixture.getDeclaredMethod("read", int.class, int.class);
        render.setAccessible(true); read.setAccessible(true);
        try (AutoCloseable gpu = (AutoCloseable) ctor.newInstance();
             SonnetLivePipelineTest.Harness live = new SonnetLivePipelineTest.Harness(
                     "[00:01.000]微光穿过夜色\n[00:02.500]你的肩上\n[00:04.000]新的文字\n"
                             + "[00:05.500]再次出现\n[00:07.000]换句清屏\n[00:08.500]最后的光")) {
            live.nativePresenter = (picture, width, height) -> {
                render.invoke(gpu, picture, width, height, 0);
                java.nio.ByteBuffer rgba = (java.nio.ByteBuffer) read.invoke(gpu, width, height);
                int[] result = new int[width * height];
                for (int i = 0; i < result.length; i++) result[i] = (rgba.get(i * 4 + 3) & 255) << 24
                        | (rgba.get(i * 4 + 2) & 255) << 16 | (rgba.get(i * 4 + 1) & 255) << 8 | rgba.get(i * 4) & 255;
                return result;
            };
            live.frame(850, true);
            assertEquals(1, live.submissions, "GPU must see the guide before a lyric row exists");
            assertTrue(java.util.Arrays.stream(live.pixels).anyMatch(pixel -> (pixel >>> 24) > 10));
            live.frame(1400, true);
            int[] entered = live.pixels.clone();
            live.frame(1650, true);
            assertFalse(java.util.Arrays.equals(entered, live.pixels), "GPU output must be animated");
            SonnetProgram program = SonnetProgram.compile(com.muoniumplayer.core.ncm.music.CloudMusic.lyrics, "sonnet");
            long boundary = (long) program.shots.get(1).startMs;
            live.frame(boundary - 100, true);
            int[] outgoing = live.pixels.clone();
            live.frame(boundary + 100, true);
            assertFalse(java.util.Arrays.equals(outgoing, live.pixels), "GPU target must replace the outgoing layout");
            live.frame(boundary - 100, true);
            assertArrayEquals(outgoing, live.pixels, "Backward seek must replay the same GPU transition");
            live.frame((long) program.finalRenderEndMs() + 1251, true);
            assertEquals(0, live.submissions, "Cleared scene must not submit a stale GPU texture");
        }
    }

    @Test
    void actualWorldCanvasShadersPreserveFaintParticleAlphaOverBrightAndDarkScenes() throws Exception {
        Class<?> fixture = Class.forName("com.muoniumplayer.render.skija.SkijaTrailGpuTest$Gpu");
        java.lang.reflect.Constructor<?> ctor = fixture.getDeclaredConstructor(); ctor.setAccessible(true);
        java.lang.reflect.Method render = fixture.getDeclaredMethod("render", Picture.class, int.class, int.class, int.class);
        render.setAccessible(true);
        java.lang.reflect.Field texture = fixture.getDeclaredField("texture"); texture.setAccessible(true);
        try (AutoCloseable gpu = (AutoCloseable) ctor.newInstance();
             SonnetFrameSurface plane = new SonnetFrameSurface();
             io.github.humbleui.skija.Paint paint = new io.github.humbleui.skija.Paint()) {
            io.github.humbleui.skija.Canvas canvas = plane.begin(128, 64);
            for (int strip = 0; strip < 4; strip++) {
                int alpha = new int[]{8, 20, 64, 255}[strip];
                canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(strip * 32, 0, 32, 64),
                        paint.setColor(alpha << 24 | 0xFF0000));
            }
            try (Picture picture = plane.finish()) { render.invoke(gpu, picture, 128, 64, 0); }
            // This is the final world quad shader as shipped in this lane, not the Skija intermediate.
            try (WorldComposite composite = new WorldComposite((int) texture.get(gpu), 128f / 2048, 64f / 1024)) {
                for (int background : new int[]{0xEEF6FC, 0x070C16, 0x34592C}) {
                    int[] result = composite.draw(background);
                    for (int strip = 0; strip < 4; strip++) {
                        double alpha = new int[]{8, 20, 64, 255}[strip] / 255.0;
                        int pixel = result[32 * 128 + strip * 32 + 16];
                        for (int channel = 0; channel < 3; channel++) {
                            int shift = channel * 8;
                            int bg = background >> shift & 255;
                            int fg = channel == 2 ? 255 : 0;
                            int expected = (int) Math.round(fg * alpha + bg * (1 - alpha));
                            assertEquals(expected, pixel >> shift & 255, 2,
                                    "World shader clipped alpha=" + alpha + " over " + Integer.toHexString(background));
                        }
                    }
                }
            }
        }
    }

    /** Small raw-GL conformance harness for the actual Minecraft resource shader and alpha blend. */
    private static final class WorldComposite implements AutoCloseable {
        final int program, vertexShader, fragmentShader, vao, vertices, dynamic, projection, target, fbo, source;
        WorldComposite(int source, float uMax, float vMax) throws Exception {
            this.source = source;
            java.lang.reflect.Field selected = Class.forName("com.muoniumplayer.compat.mc.WorldBillboard")
                    .getDeclaredField("SONNET_CANVAS_PIPELINE");
            selected.setAccessible(true);
            com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline =
                    (com.mojang.renderpearl.api.pipeline.RenderPipeline) selected.get(null);
            assertEquals(net.minecraft.client.renderer.RenderPipelines.TEXT_SEE_THROUGH.getDepthStencilState(),
                    pipeline.getDepthStencilState(), "The whole plane must stay visible through world geometry");
            assertSame(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, pipeline.getVertexFormatBinding(0));
            assertEquals(java.util.Optional.of(com.mojang.renderpearl.api.pipeline.BlendFunction.TRANSLUCENT),
                    pipeline.getColorTargetStates().get(0).blendFunction());
            assertTrue(pipeline.getBindGroupLayouts().contains(net.minecraft.client.renderer.BindGroupLayouts.DYNAMIC_TRANSFORMS));
            assertTrue(pipeline.getBindGroupLayouts().contains(net.minecraft.client.renderer.BindGroupLayouts.PROJECTION));
            assertTrue(pipeline.getBindGroupLayouts().contains(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0));
            String[] vertex = pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX).toString().split(":", 2);
            String[] fragment = pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT).toString().split(":", 2);
            vertexShader = compile(org.lwjgl.opengl.GL20.GL_VERTEX_SHADER,
                    shader("/assets/" + vertex[0] + "/shaders/" + vertex[1] + ".vsh"));
            fragmentShader = compile(org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER,
                    shader("/assets/" + fragment[0] + "/shaders/" + fragment[1] + ".fsh"));
            program = org.lwjgl.opengl.GL20.glCreateProgram();
            org.lwjgl.opengl.GL20.glAttachShader(program, vertexShader); org.lwjgl.opengl.GL20.glAttachShader(program, fragmentShader);
            org.lwjgl.opengl.GL20.glLinkProgram(program);
            assertEquals(org.lwjgl.opengl.GL11.GL_TRUE, org.lwjgl.opengl.GL20.glGetProgrami(program, org.lwjgl.opengl.GL20.GL_LINK_STATUS),
                    org.lwjgl.opengl.GL20.glGetProgramInfoLog(program));
            vao = org.lwjgl.opengl.GL30.glGenVertexArrays(); org.lwjgl.opengl.GL30.glBindVertexArray(vao);
            vertices = org.lwjgl.opengl.GL15.glGenBuffers(); org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, vertices);
            float[] quad = {-1,-1,0, 1,1,1,1, 0,0, 1,-1,0, 1,1,1,1, uMax,0, 1,1,0, 1,1,1,1, uMax,vMax,
                    -1,-1,0, 1,1,1,1, 0,0, 1,1,0, 1,1,1,1, uMax,vMax, -1,1,0, 1,1,1,1, 0,vMax};
            org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, quad, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
            org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, org.lwjgl.opengl.GL11.GL_FLOAT, false, 36, 0);
            org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 4, org.lwjgl.opengl.GL11.GL_FLOAT, false, 36, 12);
            org.lwjgl.opengl.GL20.glVertexAttribPointer(2, 2, org.lwjgl.opengl.GL11.GL_FLOAT, false, 36, 28);
            for (int attr = 0; attr < 3; attr++) org.lwjgl.opengl.GL20.glEnableVertexAttribArray(attr);
            float[] transforms = new float[40];
            for (int offset : new int[]{0, 16}) for (int axis = 0; axis < 4; axis++) transforms[offset + axis * 5] = 1;
            java.util.Arrays.fill(transforms, 32, 36, 1);
            dynamic = uniform("DynamicTransforms", 0, transforms);
            float[] identity = new float[16]; for (int axis = 0; axis < 4; axis++) identity[axis * 5] = 1;
            projection = uniform("Projection", 1, identity);
            target = org.lwjgl.opengl.GL11.glGenTextures(); org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, target);
            org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA8, 128, 64, 0,
                    org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            fbo = org.lwjgl.opengl.GL30.glGenFramebuffers(); org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, fbo);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, target, 0);
            assertEquals(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE, org.lwjgl.opengl.GL30.glCheckFramebufferStatus(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER));
        }
        private int uniform(String name, int binding, float[] data) {
            int buffer = org.lwjgl.opengl.GL15.glGenBuffers();
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER, buffer);
            org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER, data, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
            int index = org.lwjgl.opengl.GL31.glGetUniformBlockIndex(program, name); assertNotEquals(-1, index, name);
            org.lwjgl.opengl.GL31.glUniformBlockBinding(program, index, binding);
            org.lwjgl.opengl.GL30.glBindBufferBase(org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER, binding, buffer);
            return buffer;
        }
        int[] draw(int rgb) {
            org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, fbo);
            org.lwjgl.opengl.GL11.glViewport(0, 0, 128, 64);
            for (int option : new int[]{org.lwjgl.opengl.GL11.GL_SCISSOR_TEST, org.lwjgl.opengl.GL11.GL_DEPTH_TEST,
                    org.lwjgl.opengl.GL11.GL_CULL_FACE, org.lwjgl.opengl.GL11.GL_DITHER, org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_SRGB}) {
                org.lwjgl.opengl.GL11.glDisable(option);
            }
            org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
            org.lwjgl.opengl.GL11.glClearColor((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, 1);
            org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
            org.lwjgl.opengl.GL14.glBlendFuncSeparate(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA,
                    org.lwjgl.opengl.GL11.GL_ONE, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
            org.lwjgl.opengl.GL20.glUseProgram(program);
            org.lwjgl.opengl.GL20.glUniform1i(org.lwjgl.opengl.GL20.glGetUniformLocation(program, "Sampler0"), 0);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, source);
            org.lwjgl.opengl.GL30.glBindVertexArray(vao);
            org.lwjgl.opengl.GL11.glDrawArrays(org.lwjgl.opengl.GL11.GL_TRIANGLES, 0, 6);
            java.nio.ByteBuffer rgba = java.nio.ByteBuffer.allocateDirect(128 * 64 * 4);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ROW_LENGTH, 0);
            org.lwjgl.opengl.GL11.glReadPixels(0, 0, 128, 64, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, rgba);
            int[] pixels = new int[128 * 64];
            for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFF000000 | (rgba.get(i*4) & 255) << 16
                    | (rgba.get(i*4+1) & 255) << 8 | rgba.get(i*4+2) & 255;
            assertEquals(org.lwjgl.opengl.GL11.GL_NO_ERROR, org.lwjgl.opengl.GL11.glGetError());
            return pixels;
        }
        private static int compile(int kind, String source) {
            int id = org.lwjgl.opengl.GL20.glCreateShader(kind); org.lwjgl.opengl.GL20.glShaderSource(id, source);
            org.lwjgl.opengl.GL20.glCompileShader(id);
            assertEquals(org.lwjgl.opengl.GL11.GL_TRUE, org.lwjgl.opengl.GL20.glGetShaderi(id, org.lwjgl.opengl.GL20.GL_COMPILE_STATUS),
                    org.lwjgl.opengl.GL20.glGetShaderInfoLog(id));
            return id;
        }
        private static String shader(String path) throws Exception {
            String source;
            try (java.io.InputStream stream = SonnetGpuProbeTest.class.getResourceAsStream(path)) {
                assertNotNull(stream, path); source = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            java.util.regex.Matcher include = java.util.regex.Pattern.compile("#include <minecraft:([^>]+)>").matcher(source);
            StringBuilder expanded = new StringBuilder();
            while (include.find()) include.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(
                    shader("/assets/minecraft/shaders/include/" + include.group(1))));
            include.appendTail(expanded);
            return expanded.toString();
        }
        @Override public void close() {
            org.lwjgl.opengl.GL20.glUseProgram(0);
            org.lwjgl.opengl.GL20.glDeleteProgram(program); org.lwjgl.opengl.GL20.glDeleteShader(vertexShader);
            org.lwjgl.opengl.GL20.glDeleteShader(fragmentShader); org.lwjgl.opengl.GL15.glDeleteBuffers(vertices);
            org.lwjgl.opengl.GL15.glDeleteBuffers(dynamic); org.lwjgl.opengl.GL15.glDeleteBuffers(projection);
            org.lwjgl.opengl.GL30.glDeleteVertexArrays(vao); org.lwjgl.opengl.GL30.glDeleteFramebuffers(fbo);
            org.lwjgl.opengl.GL11.glDeleteTextures(target);
        }
    }

}
