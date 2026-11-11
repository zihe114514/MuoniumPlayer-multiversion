package com.muoniumplayer.render.skija;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Picture;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.humbleui.types.Rect;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * GPU 拖尾：Skia 重放同一 Picture，然后在 GPU 内转成原管线的直通 RGBA8。
 * 不读回像素、不上传 CPU 位图。目标纹理归宿主；源表面只作串行转换暂存，
 * GL 命令顺序保证重用发生在上一次转换之后。同帧每行使用不同的目标槽位。
 */
public final class SkijaTrailRenderer {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("muonium.lyrics3d.gpuTrails", "true"));
    private static final Target TARGET = new Target();
    private static DirectContext owner;
    private static int program, vao, outputFbo, sourceUniform, heightUniform;
    private static boolean unavailable;
    private static boolean logged;

    private static final String VERTEX = """
            #version 150
            void main() {
                vec2 p = gl_VertexID == 0 ? vec2(-1.0, -1.0)
                       : gl_VertexID == 1 ? vec2(3.0, -1.0) : vec2(-1.0, 3.0);
                gl_Position = vec4(p, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D uSource;
            uniform int uHeight;
            out vec4 fragColor;
            void main() {
                // Skia FBO: bottom-left. NativeImage/world UV: first row is the top.
                ivec2 p = ivec2(int(gl_FragCoord.x), uHeight - 1 - int(gl_FragCoord.y));
                ivec4 q = ivec4(floor(texelFetch(uSource, p, 0) * 255.0 + 0.5));
                ivec3 rgb = q.rgb;
                // Match readPixelsAbgr's integer rounding before LINEAR world sampling.
                if (q.a > 0 && q.a < 255) rgb = min(ivec3(255), (rgb * 255 + q.a / 2) / q.a);
                fragColor = vec4(vec3(rgb), float(q.a)) / 255.0;
            }
            """;

    private static final class Target {
        int texture, fbo, width, height;
        BackendRenderTarget backend;
        Surface surface;

        void close() {
            if (surface != null) surface.close();
            if (backend != null) backend.close();
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (texture != 0) GL11.glDeleteTextures(texture);
            surface = null;
            backend = null;
            texture = fbo = width = height = 0;
        }
    }

    private SkijaTrailRenderer() {
    }

    public static boolean available() {
        // 实验放行移动端：真机验证 Skia GL 互操作（MobileGL/MobileGlues 翻译层之上）。
        // 失败路径不变：renderOn 抛错（含 GL 3.2 缺失）→ unavailable 熔断 → 同帧 CPU 回放；
        // 逃生开关 -Dmuonium.lyrics3d.gpuTrails=false 恒可强制 CPU。
        return ENABLED && !unavailable
                && SkijaHost.context() != null && !SkijaHost.isDisabled() && !SkijaHost.inFrame();
    }

    /** False means the caller must replay the Picture through the CPU fallback this frame. */
    public static boolean render(Picture picture, int width, int height,
                                 int texture, int textureWidth, int textureHeight, int slot) {
        if (!available()) return false;
        DirectContext context = SkijaHost.context();
        try {
            return SkijaHost.renderOffscreen(() -> renderOn(context, picture,
                    width, height, texture, textureWidth, textureHeight, slot));
        } catch (Throwable failure) {
            unavailable = true;
            if (!logged) {
                logged = true;
                System.err.println("[MuoniumPlayer/GpuTrails] GPU 拖尾不可用，本帧及后续回放 CPU: " + failure);
            }
            return false;
        }
    }

    /** Current GL context and state isolation are owned by the caller; also used by GPU tests. */
    static void renderOn(DirectContext context, Picture picture, int width, int height,
                         int texture, int textureWidth, int textureHeight, int slot) {
        if (context == null || picture == null || texture <= 0 || slot < 0 || slot >= 4
                || width <= 0 || height <= 0 || width > 1600 || height > 1600
                || textureWidth < width || textureHeight < height) {
            throw new IllegalArgumentException("Invalid GPU trail target");
        }
        if (!GL.getCapabilities().OpenGL32) throw new IllegalStateException("GPU trails require OpenGL 3.2");
        if (owner != context) {
            release();
            owner = context;
        }
        ensureProgram();
        Target target = TARGET;
        ensureTarget(context, target, width, height);
        int save = target.surface.getCanvas().save();
        try {
            target.surface.getCanvas().clipRect(Rect.makeWH(width, height));
            target.surface.getCanvas().clear(0);
            target.surface.getCanvas().drawPicture(picture);
        } finally {
            target.surface.getCanvas().restoreToCount(save);
        }
        context.flushAndSubmit(target.surface);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, texture, 0);
        try {
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("GPU trail destination FBO is incomplete");
            }
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DITHER);
            GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glColorMask(true, true, true, true);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects) {
                org.lwjgl.opengl.GL33.glBindSampler(0, 0);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.texture);
            GL20.glUniform1i(sourceUniform, 0);
            GL20.glUniform1i(heightUniform, target.height);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            // Do not hold a framebuffer attachment to a texture whose lifetime belongs to MC.
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, 0, 0);
        }
    }

    private static void ensureTarget(DirectContext context, Target target, int width, int height) {
        if (target.surface != null && target.width >= width && target.height >= height) return;
        target.close();
        int capacityWidth = nextPow2(width), capacityHeight = nextPow2(height);
        target.texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, capacityWidth, capacityHeight,
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        target.fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, target.texture, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("GPU trail source FBO is incomplete");
        }
        target.backend = BackendRenderTarget.makeGL(capacityWidth, capacityHeight,
                0, 0, target.fbo, FramebufferFormat.GR_GL_RGBA8);
        // No color-space conversion: preserve the raster Bitmap's existing byte-space gradients.
        target.surface = Surface.wrapBackendRenderTarget(context, target.backend,
                SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, null);
        if (target.surface == null) throw new IllegalStateException("GPU trail surface could not be wrapped");
        target.width = capacityWidth;
        target.height = capacityHeight;
        context.resetAll();
    }

    private static int nextPow2(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
    }

    private static void ensureProgram() {
        if (program != 0) return;
        int vertex = shader(GL20.GL_VERTEX_SHADER, VERTEX);
        int fragment = 0;
        try {
            fragment = shader(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
            sourceUniform = GL20.glGetUniformLocation(program, "uSource");
            heightUniform = GL20.glGetUniformLocation(program, "uHeight");
            vao = GL30.glGenVertexArrays();
            outputFbo = GL30.glGenFramebuffers();
        } finally {
            GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static int shader(int kind, String source) {
        int shader = GL20.glCreateShader(kind);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(log);
        }
        return shader;
    }

    /** Called while GL is current, before the shared DirectContext is destroyed. */
    public static void release() {
        TARGET.close();
        if (outputFbo != 0) GL30.glDeleteFramebuffers(outputFbo);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (program != 0) GL20.glDeleteProgram(program);
        owner = null;
        program = vao = outputFbo = 0;
        unavailable = logged = false;
    }
}
