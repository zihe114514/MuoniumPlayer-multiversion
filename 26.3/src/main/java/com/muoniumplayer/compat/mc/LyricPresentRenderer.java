package com.muoniumplayer.compat.mc;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 3D 歌词的帧末兜底直画器（26.x「更激进的适配」的落屏端）——
 * <b>裸 GL 3.3 + 自管 VBO/着色器，把采集的公告牌 quad 画上窗口 FBO 0</b>。
 *
 * <h2>为什么是裸 GL</h2>
 * {@code GlSurface#present()} 入口（{@code GlSurfaceMixin} 注入点）时原版帧图、
 * Iris 合成链、GUI 批全部收工，画面已 blit 进 FBO 0、只差交换 —— 此时任何
 * 原版渲染 API（RenderType/GuiGraphics）都不再有效，仓内此槽位的先例
 * （Skija 合成）就是纯 GL 互操作。本类用同样的姿态：最小状态、自管资源、
 * 进出快照恢复（GlStateManager 契约：改了什么还什么）。
 *
 * <h2>采集协议</h2>
 * 世界采集阶段（{@link WorldBillboard} capture 模式）逐字形调
 * {@link #addQuad}：四个顶点 = <b>相机相对世界轴坐标</b>（与 submit 通路
 * 同口径：rx=世界−相机，视图旋转把它转回相机空间）+ ARGB + UV + 纹理页 id。
 * 缓冲是双份大数组 + 帧计数复用（零逐帧分配）；MVP 由
 * {@link WorldLyricsHudDraw#presentComposite} 从 CameraRenderState 算好传入。
 *
 * <h2>着色器</h2>
 * 单一 GLSL 330 core 对：v_mvp（P×V）、v_tex（图集）、u_tex0（页纹理）。
 * 片元 = texture × 顶点色、SRC_ALPHA 混合、无深度无剔除 —— 语义与原版
 * textSeeThrough 完全一致（全亮、透墙、透明混合）。多页纹理按页分批画。
 *
 * <p>全部 GL 调用包在 try/finally 里熔断：一次失败永久停用（回退世界路由），
 * 首次失败无条件打日志。MobileGlues 走 GL ES 3.2 → GL 3.3 翻译，同 Skija
 * 已验证的兼容等级。</p>
 */
final class LyricPresentRenderer {

    /** 单帧 quad 容量上限（一行 ~60 字 × 8 层特效 ≈ 500，翻倍冗余）。 */
    private static final int MAX_QUADS = 4096;
    private static final int FLOATS_PER_VERTEX = 9; // x,y,z,r,g,b,a,u,v
    private static final int FLOATS_PER_QUAD = FLOATS_PER_VERTEX * 4;

    private static final String VS = """
            #version 150
            uniform mat4 uMvp;
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec4 aColor;
            layout(location=2) in vec2 aUv;
            out vec4 vColor;
            out vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vColor = aColor;
                vUv = aUv;
            }
            """;

    private static final String FS = """
            #version 150
            uniform sampler2D uTex;
            in vec4 vColor;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTex, vUv) * vColor;
            }
            """;

    private static final AtomicBoolean FAILED = new AtomicBoolean(false);
    /** 自检开关：-Dmuonium.lyricsPresentDebug=1 = 每帧先画全屏白块再画歌词。 */
    private static final boolean DEBUG_FULLSCREEN = Boolean.getBoolean("muonium.lyricsPresentDebug");
    private static int diagTick;
    private static boolean failureLogged;

    /** 顶点数据：浮点直接摊平（颜色拆 rgba），quad 按纹理页分组。 */
    private static final class Batch {
        /** 页纹理 id → 本页 quad 起止（按 addQuad 顺序）。分批段首动态数组。 */
        final float[] floats = new float[MAX_QUADS * FLOATS_PER_QUAD];
        int quadCount;
        /** 本 quad 用的 GL 纹理 id（页纹理按 addQuad 时的页分组；跨页按序分批）。 */
        int texId = 0;
    }

    /** 帧交替双缓冲：采集端写 front，present 端画 back，present 后交换。 */
    private static final Batch[] batches = { new Batch(), new Batch() };
    private static int front;
    /** 本帧有至少一个采集页（present 后清零）。 */
    private static boolean captured;

    /** 采集的页纹理 id 序列（quad 顺序，页切换即记录 —— 同页连续 quad 合一段）。 */
    private static final int[] segTexIds = new int[256];
    private static final int[] segQuadStart = new int[256];
    private static final int[] segQuadCount = new int[256];
    private static int segCount;

    /** GL 资源（首次 draw 惰性建）。 */
    private static int program;
    private static int vbo;
    private static int vao;
    private static int ebo;
    private static int uMvpLoc;
    private static int uTexLoc;
    /** 窗口像素尺寸（presentComposite 每帧传入，视口用）。 */
    private static volatile int windowWidth = 1, windowHeight = 1;
    /** 诊断：最近一次 draw 实际提交的 quad 数 / 采集端累计拒绝数（缓冲满/段满）。 */
    private static volatile int lastDrawnQuads;
    private static int rejectedQuads;
    /** 诊断：最近一次 draw 后从 FBO 中心读回的 RGBA（画没画上的决定性证据）。 */
    private static volatile String lastCenterPixel = "-";

    static void setWindowSize(int w, int h) {
        if (w > 0 && h > 0) { windowWidth = w; windowHeight = h; }
    }

    private LyricPresentRenderer() {
    }

    // ==================== 采集端（世界渲染线程，collect 阶段） ====================

    /** 帧头清空采集缓冲（collectWorldLyrics 进 capture 模式时先调）。 */
    static void beginFrame() {
        Batch b = batches[front];
        b.quadCount = 0;
        b.texId = 0;
        segCount = 0;
        captured = false;
    }

    /**
     * 记一个字形/特效 quad。四顶点顺序与其他通路一致：
     * 左上 → 左下 → 右下 → 右上；颜色为 ARGB int；texGlId = 页纹理的 GL 名。
     */
    static void addQuad(int texGlId,
                        float x0, float y0, float u0, float v0,
                        float x1, float y1, float u1, float v1,
                        float x2, float y2, float u2, float v2,
                        float x3, float y3, float u3, float v3,
                        int argb) {
        if (FAILED.get() || capturedDone()) return;
        Batch b = batches[front];
        if (b.quadCount >= MAX_QUADS) { rejectedQuads++; return; }
        int tex = texGlId;
        // 跨页开新段（同页连续 quad 归同段；页序即绘制序）
        if (segCount == 0 || segTexIds[segCount - 1] != tex) {
            if (segCount >= segTexIds.length) { rejectedQuads++; return; }
            segTexIds[segCount] = tex;
            segQuadStart[segCount] = b.quadCount;
            segQuadCount[segCount] = 0;
            segCount++;
        }
        segQuadCount[segCount - 1]++;

        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float bl = (argb & 0xFF) / 255f;
        int o = b.quadCount * FLOATS_PER_QUAD;
        float[] f = b.floats;
        // 左上
        f[o] = x0; f[o+1] = y0; f[o+2] = 0f; f[o+3] = r; f[o+4] = g; f[o+5] = bl; f[o+6] = a; f[o+7] = u0; f[o+8] = v0;
        // 左下
        f[o+9] = x0; f[o+10] = y1; f[o+11] = 0f; f[o+12] = r; f[o+13] = g; f[o+14] = bl; f[o+15] = a; f[o+16] = u1; f[o+17] = v1;
        // 右下
        f[o+18] = x1; f[o+19] = y1; f[o+20] = 0f; f[o+21] = r; f[o+22] = g; f[o+23] = bl; f[o+24] = a; f[o+25] = u2; f[o+26] = v2;
        // 右上
        f[o+27] = x1; f[o+28] = y0; f[o+29] = 0f; f[o+30] = r; f[o+31] = g; f[o+32] = bl; f[o+33] = a; f[o+34] = u3; f[o+35] = v3;
        b.quadCount++;
        captured = true;
    }

    /** 采集结束（capture 模式下 WorldLyricsRenderer.render 返回后调）。 */
    static boolean endFrame() {
        return captured;
    }

    /** 防止 present 已画后采集端仍写（不会发生：present 在帧末、采集在帧头；防御性）。 */
    private static boolean capturedDone() {
        return false;
    }

    static int quadCount() {
        return batches[front].quadCount;
    }

    /** 诊断：最近一次 draw 实际提交的 quad 数；-1 = 尚未 draw 过。 */
    static int lastDrawnQuads() {
        return lastDrawnQuads;
    }

    /** 诊断：采集端累计拒绝的 quad 数（缓冲/段表满）。 */
    static int rejectedQuads() {
        return rejectedQuads;
    }

    /** 诊断：最近一次 draw 后 FBO 中心像素 RGBA（0-255，逗号分隔）。 */
    static String lastCenterPixel() {
        return lastCenterPixel;
    }

    // ==================== 呈现链关键件（照 DangoMusic 的 McCompat 移植） ====================
    // 我们此前「画上 FBO 0 却看不见」的原因（px 读回证明 GPU 真写了）：
    // ① 绑的 FBO 0 不是原版认可的绘制面 —— 原版按「颜色+深度贴图视图」从 FrameBufferCache
    //    取 FBO，且 GlStateManager 记着的绑定号与实际不一致时后续 pass 会重绑覆盖；
    // ② 原版给纹理单元绑的 sampler 会盖掉贴图过滤设置；
    // ③ 用 CameraRenderState 的原始透视矩阵，丢了视角摇晃（bob）合成，与世界画面有偏差。
    // 以下照抄 DangoMusic McCompat 的三件套：显存读投影 / mainFramebuffer / bindFramebuffer。

    /** GlBuffer.handle 字段（private，反射取）——显存读投影用。 */
    private static java.lang.reflect.Field bufferHandleField;
    private static boolean bufferHandleSearched;
    private static final java.nio.FloatBuffer readback16 = java.nio.ByteBuffer
            .allocateDirect(64).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();

    /**
     * 从显存里把原版当前投影 UBO 读出来（含视角摇晃合成，和原版画世界用的完全一样）。
     * 读不到时退回 CameraRenderState 的原始透视（调用方传入的 fallback）。
     */
    static boolean readProjectionFromUbo(org.joml.Matrix4f dest) {
        try {
            com.mojang.renderpearl.api.buffers.GpuBufferSlice slice =
                    com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrixBuffer();
            if (slice == null) return false;
            if (!(slice.buffer() instanceof com.mojang.renderpearl.backend.opengl.GlBuffer glBuf)) return false;
            if (!bufferHandleSearched) {
                bufferHandleSearched = true;
                try {
                    // handle() 在 26.3 是 public（javap 核对），26.2 若没有则反射字段
                    bufferHandleField = com.mojang.renderpearl.backend.opengl.GlBuffer.class
                            .getDeclaredField("handle");
                    bufferHandleField.setAccessible(true);
                } catch (Throwable ignored) {
                }
            }
            int handle;
            try {
                handle = glBuf.handle();
            } catch (Throwable t) {
                if (bufferHandleField == null) return false;
                handle = bufferHandleField.getInt(glBuf);
            }
            int prev = GL11.glGetInteger(GL31.GL_COPY_READ_BUFFER);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, handle);
            readback16.clear();
            GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, slice.offset(), readback16);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, prev);
            dest.set(readback16);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反射找 GlDevice 上的 directStateAccess() / frameBufferCache()（DangoMusic 同款）。 */
    private static Object glBackend() {
        try {
            Object device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
            java.lang.reflect.Field f = null;
            for (Class<?> c = device.getClass(); c != null && f == null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field sf : c.getDeclaredFields()) {
                    if (sf.getName().equals("backend")) { f = sf; break; }
                }
            }
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(device);
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Method directStateAccessMethod;
    private static java.lang.reflect.Method frameBufferCacheMethod;
    private static boolean backendMethodsSearched;
    private static int dummyFramebuffer;

    private static java.lang.reflect.Method backendMethod(String name) {
        if (!backendMethodsSearched) {
            backendMethodsSearched = true;
            try {
                Object backend = glBackend();
                if (backend != null) {
                    directStateAccessMethod = backend.getClass().getDeclaredMethod(name.equals("directStateAccess") ? "directStateAccess" : name);
                    directStateAccessMethod.setAccessible(true);
                }
            } catch (Throwable t) {
                try {
                    Object backend = glBackend();
                    if (backend != null) {
                        frameBufferCacheMethod = backend.getClass().getDeclaredMethod("frameBufferCache");
                        frameBufferCacheMethod.setAccessible(true);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return name.equals("directStateAccess") ? directStateAccessMethod : frameBufferCacheMethod;
    }

    /**
     * 主渲染目标的帧缓冲编号 —— 与原版自己画东西时用的是同一个
     * （原版按「颜色+深度贴图视图」从缓存取）。拿不到退回当前绑定。
     */
    @SuppressWarnings("unchecked")
    static int mainFramebuffer(net.minecraft.client.Minecraft mc) {
        try {
            com.mojang.blaze3d.pipeline.RenderTarget target = mc.gameRenderer.mainRenderTarget();
            Object backend = glBackend();
            if (backend == null) return GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            java.lang.reflect.Method dsaM = null, cacheM = null;
            for (java.lang.reflect.Method m : backend.getClass().getDeclaredMethods()) {
                if (m.getName().equals("directStateAccess")) dsaM = m;
                if (m.getName().equals("frameBufferCache")) cacheM = m;
            }
            if (dsaM == null || cacheM == null) return GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            Object dsa = dsaM.invoke(backend);
            Object cache = cacheM.invoke(backend);
            com.mojang.renderpearl.api.textures.GpuTextureView color = target.getColorTextureView();
            com.mojang.renderpearl.api.textures.GpuTextureView depth = target.getDepthTextureView();
            if (!(color instanceof com.mojang.renderpearl.backend.opengl.FrameBufferAttachment)) {
                return GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            }
            java.util.List<com.mojang.renderpearl.backend.opengl.FrameBufferAttachment> attachments =
                    java.util.List.of((com.mojang.renderpearl.backend.opengl.FrameBufferAttachment) color);
            return cacheM.invoke(cache, dsa, attachments,
                    depth instanceof com.mojang.renderpearl.backend.opengl.FrameBufferAttachment ? depth : null) instanceof Integer i ? i
                    : GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        } catch (Throwable t) {
            return GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        }
    }

    /** 绑帧缓冲并让原版 GlStateManager 记的值与实际一致（先绑 dummy 再绑真值）。 */
    static void bindFramebufferRefreshed(int target, int id) {
        if (dummyFramebuffer == 0) dummyFramebuffer = GL30.glGenFramebuffers();
        com.mojang.renderpearl.backend.opengl.GlStateManager._glBindFramebuffer(target, dummyFramebuffer);
        com.mojang.renderpearl.backend.opengl.GlStateManager._glBindFramebuffer(target, id);
    }

    /** 丢弃当前帧采集（异常/矩阵不可用路径）。 */
    static void reset() {
        batches[front].quadCount = 0;
        segCount = 0;
        captured = false;
    }

    // ==================== 落屏端（present 线程，GlSurfaceMixin 注入） ====================

    /**
     * 把上一采集帧的 quad 画上当前绑定的帧缓冲（FBO 0）。
     * mvp = 投影 × 视图旋转（顶点已是相机相对坐标，无平移分量）。
     */
    static void draw(org.joml.Matrix4f projection, org.joml.Matrix4f viewRotation) {
        if (FAILED.get()) return;
        Batch b = batches[front];
        if (b.quadCount == 0 || segCount == 0) return;
        try {
            ensureResources();
            if (FAILED.get()) return;

            // 投影优先从显存 UBO 读（含视角摇晃合成，与原版画世界完全同源，
            // DangoMusic 同款）；读不到退回 CameraRenderState 的原始透视。
            org.joml.Matrix4f worldProj = new org.joml.Matrix4f(projection);
            readProjectionFromUbo(worldProj);
            // 相对采到的相机相对坐标，视图旋转含共轭四元数（无平移）：MVP = P×V
            org.joml.Matrix4f mvp = worldProj.mul(viewRotation);

            // 绑定【原版主渲染目标的 FBO】而不是 0 —— 26.x 原版按「颜色+深度贴图视图」
            // 从 FrameBufferCache 取 FBO；GlStateManager 记的绑定号必须与实际一致，
            // 否则后续 pass 按缓存重绑会覆盖我们的输出（此前画 FBO 0 不可见的根因，
            // DangoMusic bindFramebuffer 同款）。
            net.minecraft.client.Minecraft mcD = net.minecraft.client.Minecraft.getInstance();
            int prevFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int mainFbo = mainFramebuffer(mcD);
            bindFramebufferRefreshed(GL30.GL_FRAMEBUFFER, mainFbo);
            com.mojang.blaze3d.pipeline.RenderTarget mainTarget = mcD.gameRenderer.mainRenderTarget();
            GL11.glViewport(0, 0, mainTarget.width, mainTarget.height);

            // 原版给纹理单元绑的 sampler 会盖掉贴图自己的过滤/边缘设置 —— 解开
            //（DangoMusic beforeDraw 同款；原版自己每次画都会重绑，无需还原）
            for (int unit = 0; unit < 4; unit++) GL33.glBindSampler(unit, 0);

            GL20.glUseProgram(program);
            // 自检开关（-Dmuonium.lyricsPresentDebug=1）：用 identity MVP 画一个
            // 不透明白色全屏 quad —— 屏幕变白 = FBO/GL 链通（问题在矩阵/纹理）；
            // 没变白 = 绘制根本没落地（问题在 FBO/图元/状态）。
            if (DEBUG_FULLSCREEN) {
                try (MemoryStack st2 = MemoryStack.stackPush()) {
                    FloatBuffer idm = st2.mallocFloat(16);
                    new org.joml.Matrix4f().get(idm);
                    GL20.glUniformMatrix4fv(uMvpLoc, false, idm);
                }
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                float[] white = new float[] {
                        -1, -1, 0, 1, 1, 1, 1, 0, 0,
                        -1,  1, 0, 1, 1, 1, 1, 0, 1,
                         1,  1, 0, 1, 1, 1, 1, 1, 1,
                         1, -1, 0, 1, 1, 1, 1, 1, 0};
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, white, GL15.GL_STREAM_DRAW);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
                GL11.glEnable(GL11.GL_BLEND);
                GL20.glUseProgram(0);
                GL30.glBindVertexArray(0);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                GL20.glUseProgram(program);
                try (MemoryStack st3 = MemoryStack.stackPush()) {
                    FloatBuffer m2 = st3.mallocFloat(16);
                    mvp.get(m2);
                    GL20.glUniformMatrix4fv(uMvpLoc, false, m2);
                }
                GL30.glBindVertexArray(vao);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer m = stack.mallocFloat(16);
                mvp.get(m);
                GL20.glUniformMatrix4fv(uMvpLoc, false, m);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(uTexLoc, 0);

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            // 复用巨型 VBO：一次上传整帧浮动数组（~4096 quad × 36f × 4B ≈ 590KB 上限）
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, b.floats, GL15.GL_STREAM_DRAW);

            // GL 3.3 core / GLES 都没有 QUADS 图元（ES 下 glDrawArrays(GL_QUADS)
            // 直接 INVALID_ENUM 静默不画 —— 2026-10-28 真机「开激进适配全隐」的
            // 真因）。改 TRIANGLES 索引绘制：每 quad 4 顶点 + 6 索引（EBO 一次性建）。
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);

            int drawn = 0;
            for (int s = 0; s < segCount; s++) {
                if (segQuadCount[s] <= 0) continue;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, segTexIds[s]);
                GL11.glDrawElements(GL11.GL_TRIANGLES, segQuadCount[s] * 6,
                        GL11.GL_UNSIGNED_INT, segQuadStart[s] * 6L * 4);
                drawn += segQuadCount[s];
            }
            lastDrawnQuads = drawn;
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL30.glBindVertexArray(0);
            // 恢复原 FBO 绑定（同款刷新机制，保证 GlStateManager 与实际一致）
            bindFramebufferRefreshed(GL30.GL_FRAMEBUFFER, prevFbo);
            // 诊断（每 16 帧一次）：读回 FBO 中心像素。alpha>0 = GPU 真写进去了
            //（问题在呈现链）；alpha=0 = 绘制没落地（问题在 GL 状态/图元/矩阵）。
            if ((diagTick++ & 15) == 0) {
                java.nio.ByteBuffer px = java.nio.ByteBuffer.allocateDirect(4);
                GL11.glReadPixels(windowWidth / 2, windowHeight / 2, 1, 1,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
                lastCenterPixel = (px.get(0) & 0xFF) + "," + (px.get(1) & 0xFF) + ","
                        + (px.get(2) & 0xFF) + "," + (px.get(3) & 0xFF);
                if (DEBUG_FULLSCREEN) {
                    int err = GL11.glGetError();
                    if (err != 0) lastCenterPixel += " glErr=" + err;
                }
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL20.glUseProgram(0);
            // 采集缓冲已消费：交换双缓冲，下一帧采集写另一份
            front = 1 - front;
            batches[front].quadCount = 0;
            segCount = 0;
            captured = false;
        } catch (Throwable t) {
            logFailure(t);
        }
    }

    /** 一次性建 program/vbo/vao；失败即永久熔断（回退世界路由）。 */
    private static void ensureResources() {
        if (program != 0) return;
        int vs = 0, fs = 0;
        try {
            vs = compile(GL20.GL_VERTEX_SHADER, VS);
            fs = compile(GL20.GL_FRAGMENT_SHADER, FS);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL20.glBindAttribLocation(program, 0, "aPos");
            GL20.glBindAttribLocation(program, 1, "aColor");
            GL20.glBindAttribLocation(program, 2, "aUv");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("link failed: " + GL20.glGetProgramInfoLog(program));
            }
            uMvpLoc = GL20.glGetUniformLocation(program, "uMvp");
            uTexLoc = GL20.glGetUniformLocation(program, "uTex");

            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            // stride = 9 floats: pos(3) color(4) uv(2)
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 3 * 4);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 7 * 4);
            // EBO：quad 顶点序 左上→左下→右下→右上（0,1,2,3）→ 两三角 (0,1,2)(0,2,3)
            ebo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            int[] idx = new int[MAX_QUADS * 6];
            for (int q = 0; q < MAX_QUADS; q++) {
                int v = q * 4, o = q * 6;
                idx[o] = v;     idx[o+1] = v+1; idx[o+2] = v+2;
                idx[o+3] = v;   idx[o+4] = v+2; idx[o+5] = v+3;
            }
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idx, GL15.GL_STATIC_DRAW);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
        } catch (Throwable t) {
            if (vs != 0) GL20.glDeleteShader(vs);
            if (fs != 0) GL20.glDeleteShader(fs);
            logFailure(t);
        }
    }

    private static int compile(int type, String src) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(sh);
            GL20.glDeleteShader(sh);
            throw new IllegalStateException("shader compile failed: " + log);
        }
        return sh;
    }

    /** 首次失败无条件打日志；此后熔断（回退世界路由），静默。 */
    static void logFailure(Throwable t) {
        if (FAILED.compareAndSet(false, true)) {
            System.out.println("[MuoniumPlayer/WorldLyrics] present 兜底直画失败，已停用"
                    + "（回退世界路由，光影会加工歌词）: " + t);
            if (t.getStackTrace().length > 0) {
                System.out.println("    at " + t.getStackTrace()[0]);
            }
        } else if (!failureLogged) {
            failureLogged = true;
        }
    }
}
