package com.muoniumplayer.compat.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 3D 歌词「更激进的适配」的采集缓冲（26.x）。
 *
 * <p>采集端（{@link WorldBillboard} capture 模式）逐字形调 {@link #addQuad}：
 * 每顶点 9 float = pos3 + rgba4 + uv2，坐标为<b>完整 3D 相机相对世界轴值</b>
 * （billboard 矩阵三行全取 —— z 是深度行，丢 z 顶点全贴相机平面，透视近平面
 * 整帧裁剪）；quad 按纹理页分连续段。缓冲是双份大数组 + 帧计数复用（零逐帧分配）。
 *
 * <p>落屏端在 {@link #drawToFbo0}（GlSurface.present() 入口裸 GL 直画 FBO 0，
 * 画面已定、交换未发生 —— 物理上不可能再被任何渲染/后处理加工）。</p>
 */
final class LyricPresentRenderer {

    /** 单帧 quad 容量上限（一行 ~60 字 × 8 层特效 ≈ 500，翻倍冗余）。 */
    static final int MAX_QUADS = 4096;
    static final int FLOATS_PER_VERTEX = 9; // x,y,z,r,g,b,a,u,v
    static final int FLOATS_PER_QUAD = FLOATS_PER_VERTEX * 4;

    /** 顶点数据：浮点直接摊平（颜色拆 rgba），quad 按纹理页分组。 */
    static final class Batch {
        final float[] floats = new float[MAX_QUADS * FLOATS_PER_QUAD];
        int quadCount;
    }

    /** 帧交替双缓冲：采集端写 front，落屏端画完交换。 */
    private static final Batch[] batches = { new Batch(), new Batch() };
    private static int front;
    /** 本帧有至少一个采集页（落屏后清零）。 */
    private static boolean captured;

    /** 采集的纹理段序列（quad 顺序，页切换即记录 —— 同页连续 quad 合一段）。
     *  段存页纹理资源路径：落屏端按它解析 GpuTextureView（官方通路不经裸 GL）。 */
    private static final Object[] segTextures = new Object[256];
    private static final int[] segQuadStart = new int[256];
    private static final int[] segQuadCount = new int[256];
    private static int segCount;

    /** 诊断：最近一次 draw 实际提交的 quad 数 / 采集端累计拒绝数（缓冲满/段满）。 */
    private static volatile int lastDrawnQuads;
    private static int rejectedQuads;
    /** 诊断：最近一次 draw 的顶点 z 范围（近平面裁剪判定依据；全 0 = 采集丢深度）。 */
    private static volatile String lastVertexZRange = "-";

    private LyricPresentRenderer() {
    }

    // ==================== 采集端（collect 阶段） ====================

    /** 帧头清空采集缓冲（collectWorldLyrics 进 capture 模式时先调）。 */
    static void beginFrame() {
        batches[front].quadCount = 0;
        segCount = 0;
        captured = false;
    }

    /**
     * 记一个字形/特效 quad。四顶点顺序与其他通路一致：
     * 左上 → 左下 → 右下 → 右上；坐标为完整 3D 相机相对世界轴值（z 不可丢：
     * 投影的近平面裁剪按它判定，z=0 会被整帧静默裁掉）；颜色为 ARGB int；
     * pageRl = 页纹理资源路径（落屏端解析成 GpuTextureView）。
     */
    static void addQuad(net.minecraft.resources.Identifier pageRl,
                        float x0, float y0, float z0, float u0, float v0,
                        float x1, float y1, float z1, float u1, float v1,
                        float x2, float y2, float z2, float u2, float v2,
                        float x3, float y3, float z3, float u3, float v3,
                        int argb) {
        if (FAILED.get()) return;
        Batch b = batches[front];
        if (b.quadCount >= MAX_QUADS) { rejectedQuads++; return; }
        Object tex = pageRl;
        // 跨页开新段（同页连续 quad 归同段；页序即绘制序）
        if (segCount == 0 || !java.util.Objects.equals(segTextures[segCount - 1], tex)) {
            if (segCount >= segTextures.length) { rejectedQuads++; return; }
            segTextures[segCount] = tex;
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
        f[o] = x0; f[o+1] = y0; f[o+2] = z0; f[o+3] = r; f[o+4] = g; f[o+5] = bl; f[o+6] = a; f[o+7] = u0; f[o+8] = v0;
        // 左下
        f[o+9] = x1; f[o+10] = y1; f[o+11] = z1; f[o+12] = r; f[o+13] = g; f[o+14] = bl; f[o+15] = a; f[o+16] = u1; f[o+17] = v1;
        // 右下
        f[o+18] = x2; f[o+19] = y2; f[o+20] = z2; f[o+21] = r; f[o+22] = g; f[o+23] = bl; f[o+24] = a; f[o+25] = u2; f[o+26] = v2;
        // 右上
        f[o+27] = x3; f[o+28] = y3; f[o+29] = z3; f[o+30] = r; f[o+31] = g; f[o+32] = bl; f[o+33] = a; f[o+34] = u3; f[o+35] = v3;
        b.quadCount++;
        captured = true;
    }

    /** 采集结束（capture 模式下 WorldLyricsRenderer.render 返回后调）。 */
    static boolean endFrame() {
        return captured;
    }

    static int quadCount() {
        return batches[front].quadCount;
    }

    /** 诊断：最近一次 draw 实际提交的 quad 数。 */
    static int lastDrawnQuads() {
        return lastDrawnQuads;
    }

    /** 诊断：采集端累计拒绝的 quad 数（缓冲/段表满）。 */
    static int rejectedQuads() {
        return rejectedQuads;
    }

    /** 诊断：最近一次 draw 的顶点 z 范围（全 0 = 采集丢深度）。 */
    static String lastVertexZRange() {
        return lastVertexZRange;
    }

    static String lastCenterPixel() {
        // 官方通路没有裸 GL 读回口；GPU 是否落像素由画面本身验证
        return "-";
    }

    /** 丢弃当前帧采集（异常/矩阵不可用路径）。 */
    static void reset() {
        batches[front].quadCount = 0;
        segCount = 0;
        captured = false;
    }

    private static final AtomicBoolean FAILED = new AtomicBoolean(false);
    private static boolean failureLogged;

    /** 一次失败永久熔断（回退世界路由）；首例无条件打印。 */
    static void logFailure(Throwable t) {
        if (FAILED.compareAndSet(false, true)) {
            System.out.println("[MuoniumPlayer/WorldLyrics] present 直画失败，已停用"
                    + "（回退世界路由）: " + t);
        } else if (!failureLogged) {
            failureLogged = true;
            System.out.println("[MuoniumPlayer/WorldLyrics] present 直画后续失败（只报一次）: " + t);
        }
    }

    /** 落屏端报告本帧实际提交 quad 数并消费缓冲（交换双缓冲）。 */
    static void noteConsumed(int drawn) {
        lastDrawnQuads = drawn;
        front = 1 - front;
        batches[front].quadCount = 0;
        segCount = 0;
        captured = false;
    }

    /** 扫描本帧顶点缓冲的 z 分量范围（stride 9，z 在槽 2）。 */
    private static void scanVertexZ(Batch b) {
        float zMin = Float.POSITIVE_INFINITY, zMax = Float.NEGATIVE_INFINITY;
        int n = b.quadCount * 4;
        for (int i = 0; i < n; i++) {
            float z = b.floats[i * FLOATS_PER_VERTEX + 2];
            if (z < zMin) zMin = z;
            if (z > zMax) zMax = z;
        }
        lastVertexZRange = b.quadCount == 0 ? "-"
                : String.format("%.2f..%.2f", zMin, zMax);
    }

    // ==================== 落屏端只读视图 ====================

    /** 落屏端对采集缓冲的只读访问口（本帧待画数据）。 */
    static final class BatchView {
        float[] floats() { return batches[front].floats; }
        int quadCount() { return batches[front].quadCount; }
        int segmentCount() { return segCount; }
        int segmentQuadStart(int s) { return segQuadStart[s]; }
        int segmentQuadCount(int s) { return segQuadCount[s]; }
        /** 段 s 的页纹理资源路径（采集端记录，落屏端解析成 GpuTextureView）。 */
        Object segmentTexture(int s) { return segTextures[s]; }
        boolean isFailed() { return FAILED.get(); }
        void scanZ() { scanVertexZ(batches[front]); }
    }

    static final BatchView VIEW = new BatchView();

    // ==================== 落屏端（present 阶段，裸 GL 直画 FBO 0） ====================
    // 此前这条路的真凶：glGetBufferSubData 在 MobileGL 上不存在，失败后把零缓冲
    // 写进投影矩阵（MVP=0 → 整帧被近平面裁剪）。矩阵现在由调用方传入
    // CameraRenderState 快照（官方通路已验证能出画面），不再读任何显存。

    static void drawToFbo0(org.joml.Matrix4f projection, org.joml.Matrix4f viewRotation,
                           org.joml.Matrix4f bobConjugate,
                           int windowW, int windowH,
                           com.muoniumplayer.core.lyrics3d.MaskQuadCollector glowMask,
                           boolean captureMode) {
        if (FAILED.get()) return;
        Batch b = batches[front];
        // 世界 submit 路由（captureMode=false）：本体已由帧图画出，这里只跑
        // bloom-only 合成 —— mask 缓冲在采集期为同一次遍历双写，几何仍逐帧对齐。
        if (!captureMode) {
            runGlowOnly(glowMask, projection, viewRotation, bobConjugate, windowW, windowH);
            front = 1 - front;
            batches[front].quadCount = 0;
            segCount = 0;
            captured = false;
            return;
        }
        if (b.quadCount == 0 || segCount == 0) return;
        scanVertexZ(b);
        if (!ensureGlResources()) return;
        try {
            // MVP = P·B·view·bobConj（bobConjugate=null 时与旧行为同源）。
            // 投影带 bob 合成（P·B）时共轭阵 M = viewᵀ·B⁻¹·view 把 B 精确对消：
            // P·B·view·M = P·view —— 歌词钉在无摇晃视角上（世界照旧抖，防晕 3D）。
            org.joml.Matrix4f mvp = bobConjugate == null
                    ? new org.joml.Matrix4f(projection).mul(viewRotation)
                    : new org.joml.Matrix4f(projection).mul(viewRotation).mul(bobConjugate);

            // 辉光后处理（清晰本体之前）：mask 平色 → bloom → 加色垫到 FBO 0。
            // 合成是加法、本体随后 alpha 混合 —— 晕垫底、字芯被能量推白。
            // mask 空/未配置/熔断时整段跳过，随后本体照常画。
            if (glowMask != null) {
                com.muoniumplayer.core.lyrics3d.GlowBloom.Params gp =
                        com.muoniumplayer.core.lyrics3d.GlowBloom.take();
                com.muoniumplayer.core.lyrics3d.WorldLyricsState.traceGlowOnce(
                        "capture bloom 段: params=" + (gp == null ? "null" : "ok")
                                + " maskQuads=" + (glowMask == null ? -1 : glowMask.quadCount())
                                + " window=" + windowW + "x" + windowH);
                if (gp != null && glowMask.quadCount() > 0) {
                    if (glowBloom == null) glowBloom = new BloomComposite();
                    if (!glowBloom.failed()) {
                        glowBloom.run(glowMask.floats(), glowMask.quadCount(), mvp,
                                windowW, windowH,
                                ((gp.tintRgb >>> 16) & 0xFF) / 255f,
                                ((gp.tintRgb >>> 8) & 0xFF) / 255f,
                                (gp.tintRgb & 0xFF) / 255f,
                                gp.exposure, gp.spread, 0.12f);
                    }
                }
                glowMask.beginFrame();   // 已消费，防陈旧
            }

            int prevProgram = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
            int prevVao = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING);
            int prevVbo = org.lwjgl.opengl.GL15.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
            int prevActiveTex = org.lwjgl.opengl.GL13.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            int prevTex0 = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            boolean prevBlend = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
            int pSrcRgb = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB);
            int pDstRgb = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB);
            int pSrcA = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA);
            int pDstA = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
            boolean prevDepthTest = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            boolean prevDepthMask = org.lwjgl.opengl.GL11.glGetBoolean(org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK);
            boolean prevCull = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE);
            int[] prevViewport = new int[4];
            org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, prevViewport);
            int prevDrawFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);

            try {
                // 目标恒为窗口默认帧缓冲：此刻画面已 blit 进来，只差交换
                org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
                org.lwjgl.opengl.GL11.glViewport(0, 0, windowW, windowH);
                // 原版/光影留在纹理单元上的 sampler 会盖掉贴图过滤 —— 解开（原版每画必重绑）
                for (int unit = 0; unit < 4; unit++) org.lwjgl.opengl.GL33.glBindSampler(unit, 0);

                org.lwjgl.opengl.GL20.glUseProgram(glProgram);
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.FloatBuffer m = stack.mallocFloat(16);
                    mvp.get(m);
                    org.lwjgl.opengl.GL20.glUniformMatrix4fv(glUMvp, false, m);
                }
                org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                org.lwjgl.opengl.GL20.glUniform1i(glUTex, 0);

                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                org.lwjgl.opengl.GL11.glDepthMask(false);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_CULL_FACE);
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
                org.lwjgl.opengl.GL14.glBlendFuncSeparate(
                        org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA,
                        org.lwjgl.opengl.GL11.GL_ONE, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);

                org.lwjgl.opengl.GL30.glBindVertexArray(glVao);
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, glVbo);
                org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, b.floats, org.lwjgl.opengl.GL15.GL_STREAM_DRAW);
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, glEbo);

                int drawn = 0;
                TextureManager tm = Minecraft.getInstance().getTextureManager();
                for (int s = 0; s < segCount; s++) {
                    int segQuads = segQuadCount[s];
                    if (segQuads <= 0) continue;
                    Object segTex = segTextures[s];
                    if (!(segTex instanceof net.minecraft.resources.Identifier segRl)) continue;
                    AbstractTexture tex = tm.getTexture(segRl);
                    if (tex == null || tex.getTexture() == null) continue;
                    int glId;
                    try {
                        // 26.3 公开方法（javap 核对）；Vulkan 后端此处必然不匹配 → 熔断
                        glId = ((com.mojang.renderpearl.backend.opengl.GlTexture) tex.getTexture()).glId();
                    } catch (ClassCastException e) {
                        logFailure(e);
                        return;
                    }
                    org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, glId);
                    org.lwjgl.opengl.GL11.glDrawElements(org.lwjgl.opengl.GL11.GL_TRIANGLES,
                            segQuads * 6, org.lwjgl.opengl.GL11.GL_UNSIGNED_INT,
                            (long) segQuadStart[s] * 6L * 4L);
                    drawn += segQuads;
                }
                lastDrawnQuads = drawn;

                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
                org.lwjgl.opengl.GL30.glBindVertexArray(0);
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
                org.lwjgl.opengl.GL20.glUseProgram(0);
            } finally {
                org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, prevTex0);
                org.lwjgl.opengl.GL13.glActiveTexture(prevActiveTex);
                if (prevBlend) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
                else org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND);
                org.lwjgl.opengl.GL14.glBlendFuncSeparate(pSrcRgb, pDstRgb, pSrcA, pDstA);
                org.lwjgl.opengl.GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
                org.lwjgl.opengl.GL20.glUseProgram(prevProgram);
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, prevVbo);
                org.lwjgl.opengl.GL30.glBindVertexArray(prevVao);
                // 深度/掩码/剔除走 GlStateManager 还：这类状态带「值相等即短路」缓存，
                // 上面裸 GL 改过硬件值，缓存还停在改后的值 —— 先 dummy 双调刷掉缓存
                // 差异，真值才能落到硬件。漏还任何一项，深度缓冲的清屏与测试都会
                // 失常（glClear 受深度掩码约束），观感为世界随移动闪烁。
                com.mojang.renderpearl.backend.opengl.GlStateManager._disableDepthTest();
                if (prevDepthTest) {
                    com.mojang.renderpearl.backend.opengl.GlStateManager._enableDepthTest();
                }
                com.mojang.renderpearl.backend.opengl.GlStateManager._depthMask(false);
                com.mojang.renderpearl.backend.opengl.GlStateManager._depthMask(prevDepthMask);
                com.mojang.renderpearl.backend.opengl.GlStateManager._disableCull();
                if (prevCull) {
                    com.mojang.renderpearl.backend.opengl.GlStateManager._enableCull();
                }
                // FBO 绑定走 GlStateManager 缓存口径还回去（dummy 双绑防短路）
                com.mojang.renderpearl.backend.opengl.GlStateManager._glBindFramebuffer(
                        org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
                com.mojang.renderpearl.backend.opengl.GlStateManager._glBindFramebuffer(
                        org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, prevDrawFbo);
            }
            // 采集缓冲已消费：交换双缓冲
            front = 1 - front;
            batches[front].quadCount = 0;
            segCount = 0;
            captured = false;
        } catch (Throwable t) {
            logFailure(t);
        }
    }

    /** GLSL 150 对：GLSL 150 无 layout(location)，链前 glBindAttribLocation 钉死属性槽。 */
    private static final String GL_VS = """
            #version 150
            uniform mat4 uMvp;
            in vec3 aPos;
            in vec4 aColor;
            in vec2 aUv;
            out vec4 vColor;
            out vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vColor = aColor;
                vUv = aUv;
            }
            """;
    private static final String GL_FS = """
            #version 150
            uniform sampler2D uTex;
            in vec4 vColor;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTex, vUv) * vColor;
            }
            """;

    private static int glProgram, glVbo, glVao, glEbo, glUMvp, glUTex;

    /**
     * bloom-only 合成（世界 submit 路由）：MVP 与世界帧图展开的字体管线同源
     * （P·view·bobConj），mask 能量加色垫到 FBO 0。未配置/空 mask/熔断时零成本。
     */
    private static void runGlowOnly(com.muoniumplayer.core.lyrics3d.MaskQuadCollector glowMask,
                                    org.joml.Matrix4f projection, org.joml.Matrix4f viewRotation,
                                    org.joml.Matrix4f bobConjugate, int windowW, int windowH) {
        if (glowMask == null || windowW <= 0 || windowH <= 0) {
            com.muoniumplayer.core.lyrics3d.WorldLyricsState.traceGlowOnce(
                    "runGlowOnly 短路: mask=" + (glowMask == null ? "null" : "ok")
                            + " window=" + windowW + "x" + windowH);
            return;
        }
        com.muoniumplayer.core.lyrics3d.GlowBloom.Params gp =
                com.muoniumplayer.core.lyrics3d.GlowBloom.take();
        if (gp == null || glowMask.quadCount() == 0) {
            com.muoniumplayer.core.lyrics3d.WorldLyricsState.traceGlowOnce(
                    "runGlowOnly 短路: params=" + (gp == null ? "null" : "ok")
                            + " maskQuads=" + glowMask.quadCount());
            return;
        }
        com.muoniumplayer.core.lyrics3d.WorldLyricsState.traceGlowOnce(
                "runGlowOnly 运行: quads=" + glowMask.quadCount()
                        + " exposure=" + gp.exposure + " spread=" + gp.spread);
        if (glowBloom == null) glowBloom = new BloomComposite();
        if (glowBloom.failed()) return;
        org.joml.Matrix4f mvp = bobConjugate == null
                ? new org.joml.Matrix4f(projection).mul(viewRotation)
                : new org.joml.Matrix4f(projection).mul(viewRotation).mul(bobConjugate);
        glowBloom.run(glowMask.floats(), glowMask.quadCount(), mvp, windowW, windowH,
                ((gp.tintRgb >>> 16) & 0xFF) / 255f,
                ((gp.tintRgb >>> 8) & 0xFF) / 255f,
                (gp.tintRgb & 0xFF) / 255f,
                gp.exposure, gp.spread, 0.12f);
    }

    /** 辉光后处理管线（惰性建；熔断后 permanently 跳过）。 */
    private static BloomComposite glowBloom;

    /** 一次性建裸 GL 资源；失败永久熔断（回退世界路由）。 */
    private static boolean ensureGlResources() {
        if (glProgram != 0) return true;
        int vs = 0, fs = 0;
        try {
            vs = compileShader(org.lwjgl.opengl.GL20.GL_VERTEX_SHADER, GL_VS);
            fs = compileShader(org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER, GL_FS);
            glProgram = org.lwjgl.opengl.GL20.glCreateProgram();
            org.lwjgl.opengl.GL20.glAttachShader(glProgram, vs);
            org.lwjgl.opengl.GL20.glAttachShader(glProgram, fs);
            org.lwjgl.opengl.GL20.glBindAttribLocation(glProgram, 0, "aPos");
            org.lwjgl.opengl.GL20.glBindAttribLocation(glProgram, 1, "aColor");
            org.lwjgl.opengl.GL20.glBindAttribLocation(glProgram, 2, "aUv");
            org.lwjgl.opengl.GL20.glLinkProgram(glProgram);
            if (org.lwjgl.opengl.GL20.glGetProgrami(glProgram, org.lwjgl.opengl.GL20.GL_LINK_STATUS)
                    == org.lwjgl.opengl.GL11.GL_FALSE) {
                throw new IllegalStateException("link failed: "
                        + org.lwjgl.opengl.GL20.glGetProgramInfoLog(glProgram));
            }
            glUMvp = org.lwjgl.opengl.GL20.glGetUniformLocation(glProgram, "uMvp");
            glUTex = org.lwjgl.opengl.GL20.glGetUniformLocation(glProgram, "uTex");

            glVao = org.lwjgl.opengl.GL30.glGenVertexArrays();
            org.lwjgl.opengl.GL30.glBindVertexArray(glVao);
            glVbo = org.lwjgl.opengl.GL15.glGenBuffers();
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, glVbo);
            // stride 36B：pos3 + color4 + uv2（与采集缓冲逐字节一致）
            org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
            org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, org.lwjgl.opengl.GL11.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 0);
            org.lwjgl.opengl.GL20.glEnableVertexAttribArray(1);
            org.lwjgl.opengl.GL20.glVertexAttribPointer(1, 4, org.lwjgl.opengl.GL11.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 3 * 4);
            org.lwjgl.opengl.GL20.glEnableVertexAttribArray(2);
            org.lwjgl.opengl.GL20.glVertexAttribPointer(2, 2, org.lwjgl.opengl.GL11.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 7 * 4);
            glEbo = org.lwjgl.opengl.GL15.glGenBuffers();
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, glEbo);
            int[] idx = new int[MAX_QUADS * 6];
            for (int q = 0; q < MAX_QUADS; q++) {
                int v = q * 4, o = q * 6;
                idx[o] = v; idx[o+1] = v+1; idx[o+2] = v+2;
                idx[o+3] = v; idx[o+4] = v+2; idx[o+5] = v+3;
            }
            java.nio.IntBuffer ib = java.nio.ByteBuffer
                    .allocateDirect(idx.length * 4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            ib.put(idx).flip();
            org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, ib, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
            org.lwjgl.opengl.GL30.glBindVertexArray(0);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            org.lwjgl.opengl.GL20.glDeleteShader(vs);
            org.lwjgl.opengl.GL20.glDeleteShader(fs);
            return true;
        } catch (Throwable t) {
            if (vs != 0) org.lwjgl.opengl.GL20.glDeleteShader(vs);
            if (fs != 0) org.lwjgl.opengl.GL20.glDeleteShader(fs);
            logFailure(t);
            return false;
        }
    }

    private static int compileShader(int type, String source) {
        int sh = org.lwjgl.opengl.GL20.glCreateShader(type);
        org.lwjgl.opengl.GL20.glShaderSource(sh, source);
        org.lwjgl.opengl.GL20.glCompileShader(sh);
        if (org.lwjgl.opengl.GL20.glGetShaderi(sh, org.lwjgl.opengl.GL20.GL_COMPILE_STATUS)
                == org.lwjgl.opengl.GL11.GL_FALSE) {
            throw new IllegalStateException("shader compile failed: "
                    + org.lwjgl.opengl.GL20.glGetShaderInfoLog(sh));
        }
        return sh;
    }
}
