package com.muoniumplayer.compat.mc;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * 3D 歌词辉光后处理管线（裸 GL 3.2 core，不经 MC 的 RenderSystem）。
 *
 * <p>输入一张 mask（字的形状，白色为有墨），输出加色垫到底色画面：</p>
 * <ol>
 *   <li>mask 逐级减半下采样 7 级（Kawase 双重核：中心 ×4 + 四斜角，附带模糊）；</li>
 *   <li>从最小级逐级放回、加法混合叠加 —— 小级别给出贴字亮边，大级别给出
 *       大范围柔晕，总能量「中心亮、往外慢衰」；</li>
 *   <li>合成：指数色调映射 {@code 1-exp(-energy)}，字芯过曝趋白、边缘保持
 *       光色；落到 8bit 时按概率进位抖动 —— 晕最外圈低于 1/255 的能量以
 *       细颗粒淡出，否则在暗背景上舍入成一条硬边。中间各级都是半精度浮点
 *       （RGBA16F），模糊累加不出色带。</li>
 * </ol>
 *
 * <p>全链路只在调用者给定的窗口内切换 FBO/视口，进入前保存、退出后原样恢复
 * （含清屏色 —— 调用方每帧用它清屏，缓存值不能污染）。任何一步出错永久熔断
 * （{@link #failed}），调用方退回无辉光路径，字照画。</p>
 */
final class BloomComposite {

    private static final int LEVELS = 7;

    private static final String VERT = """
            #version 150
            in vec2 aPos;
            in vec2 aUv;
            out vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """;

    /** 下采样：中心 + 四斜角（双重 Kawase），一次附带模糊。 */
    private static final String DOWN = """
            #version 150
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D tex;
            uniform vec2 texel;
            void main() {
                vec2 uv = vUv;
                vec4 c = texture(tex, uv) * 4.0;
                c += texture(tex, uv - texel);
                c += texture(tex, uv + texel);
                c += texture(tex, uv + vec2(texel.x, -texel.y));
                c += texture(tex, uv + vec2(-texel.x, texel.y));
                fragColor = c / 8.0;
            }
            """;

    /** 上采样：8 点帐篷滤波，乘扩散强度后加到上一级。 */
    private static final String UP = """
            #version 150
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D tex;
            uniform vec2 texel;
            uniform float intensity;
            void main() {
                vec2 uv = vUv;
                vec4 s = texture(tex, uv + vec2(-2.0 * texel.x, 0.0));
                s += texture(tex, uv + vec2(2.0 * texel.x, 0.0));
                s += texture(tex, uv + vec2(0.0, -2.0 * texel.y));
                s += texture(tex, uv + vec2(0.0, 2.0 * texel.y));
                s += texture(tex, uv + vec2(-texel.x, texel.y)) * 2.0;
                s += texture(tex, uv + vec2(texel.x, texel.y)) * 2.0;
                s += texture(tex, uv + vec2(texel.x, -texel.y)) * 2.0;
                s += texture(tex, uv + vec2(-texel.x, -texel.y)) * 2.0;
                fragColor = s / 12.0 * intensity;
            }
            """;

    /** 合成：色调映射 + 概率进位抖动。 */
    private static final String COMPOSITE = """
            #version 150
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D tex;
            uniform sampler2D avgTex;
            uniform vec3 tint;
            uniform float exposure;
            uniform float normalizeGain;
            float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }
            void main() {
                vec4 g = texture(tex, v_Uv);
                // 覆盖率归一：晕观感 ∝ 覆盖率×曝光。逐字出现时 mask 只有几个字，
                // 覆盖率极低，同样的 exposure 出不来晕 —— 除以 mask 平均亮度把
                // 观感拉回与在屏字数无关的档位。avg 顶到 0.003 防全空帧除零；
                // ×8 是把 mip 顶层均值（中心采样）放大到可用的能量标度。
                float avg = clamp(texture(avgTex, vec2(0.5)).r * 8.0, 0.0025, 1.0);
                // 只增不减 + 封顶：满句帧（avg ≥ gain）保持原曝光；稀疏帧按
                // gain/avg 增亮但封顶 4× —— 否则极稀疏帧的 boost 会把曝光滑杆
                // 的贡献稀释到看不见（boost 主导一切，强度滑杆失效感）。
                float boost = min(4.0, max(1.0, normalizeGain / avg));
                vec3 energy = g.rgb * tint * exposure * boost;
                vec3 c = vec3(1.0) - exp(-energy);
                c = floor(c * 255.0 + rand(gl_FragCoord.xy)) / 255.0;
                fragColor = vec4(c, max(c.r, max(c.g, c.b)));
            }
            """;

    /** mask 平色通路：把字形 quad 画成纯 alpha 能量（RGB 白 × alpha）。 */
    private static final String MASK_FS = """
            #version 150
            in vec2 vUv;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vColor.rgb * vColor.a, vColor.a);
            }
            """;

    private static final String MASK_VS = """
            #version 150
            uniform mat4 uMvp;
            in vec3 aPos;
            in vec4 aColor;
            in vec2 aUv;
            out vec2 vUv;
            out vec4 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vUv = aUv;
                vColor = aColor;
            }
            """;

    private boolean failed;
    private boolean maskProgramReady;

    private int progDown, progUp, progComp, progMask;
    private int uDownTexel, uUpTexel, uUpIntensity, uCompTint, uCompExposure,
            uCompAvg, uCompNormalize, uMaskMvp;

    private int width, height;
    private int maskFbo;
    private final int[] levelFbo = new int[LEVELS];
    private final int[] levelTex = new int[LEVELS];
    private final int[] levelW = new int[LEVELS];
    private final int[] levelH = new int[LEVELS];

    private int quadVao, quadVbo;
    private int maskVao, maskVbo, maskEbo, maskUBo;
    private int maskQuadCapacity = -1;

    boolean failed() {
        return failed;
    }

    /** 释放全部 GL 资源（窗口尺寸变化重建 / 熔断时）。 */
    private void release() {
        if (maskFbo != 0) GL30.glDeleteFramebuffers(maskFbo);
        for (int i = 0; i < LEVELS; i++) {
            if (levelFbo[i] != 0) GL30.glDeleteFramebuffers(levelFbo[i]);
            if (levelTex[i] != 0) GL11.glDeleteTextures(levelTex[i]);
        }
        java.util.Arrays.fill(levelFbo, 0);
        java.util.Arrays.fill(levelTex, 0);
        maskFbo = 0;
        width = 0;
        height = 0;
    }

    void shutdown() {
        release();
        if (quadVao != 0) GL30.glDeleteVertexArrays(quadVao);
        if (quadVbo != 0) GL15.glDeleteBuffers(quadVbo);
        if (maskVao != 0) GL30.glDeleteVertexArrays(maskVao);
        if (maskVbo != 0) GL15.glDeleteBuffers(maskVbo);
        if (maskEbo != 0) GL15.glDeleteBuffers(maskEbo);
        if (maskUBo != 0) GL15.glDeleteBuffers(maskUBo);
        quadVao = quadVbo = maskVao = maskVbo = maskEbo = maskUBo = 0;
        if (progDown != 0) GL20.glDeleteProgram(progDown);
        if (progUp != 0) GL20.glDeleteProgram(progUp);
        if (progComp != 0) GL20.glDeleteProgram(progComp);
        if (progMask != 0) GL20.glDeleteProgram(progMask);
        progDown = progUp = progComp = progMask = 0;
    }

    private int compile(int type, String src) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(sh, 4096);
            GL20.glDeleteShader(sh);
            throw new IllegalStateException("bloom shader compile: " + log);
        }
        return sh;
    }

    private int program(String frag, boolean bindQuadAttribs) {
        int vs = compile(GL20.GL_VERTEX_SHADER, VERT);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, frag);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        if (bindQuadAttribs) {
            GL20.glBindAttribLocation(p, 0, "aPos");
            GL20.glBindAttribLocation(p, 1, "aUv");
        }
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(p, 4096);
            GL20.glDeleteProgram(p);
            throw new IllegalStateException("bloom shader link: " + log);
        }
        return p;
    }

    private boolean ensurePrograms() {
        if (progDown != 0) return true;
        try {
            progDown = program(DOWN, true);
            progUp = program(UP, true);
            progComp = program(COMPOSITE, true);
            GL20.glUseProgram(progDown);
            uDownTexel = GL20.glGetUniformLocation(progDown, "texel");
            GL20.glUniform1i(GL20.glGetUniformLocation(progDown, "tex"), 0);
            GL20.glUseProgram(progUp);
            uUpTexel = GL20.glGetUniformLocation(progUp, "texel");
            uUpIntensity = GL20.glGetUniformLocation(progUp, "intensity");
            GL20.glUniform1i(GL20.glGetUniformLocation(progUp, "tex"), 0);
            GL20.glUseProgram(progComp);
            uCompTint = GL20.glGetUniformLocation(progComp, "tint");
            uCompExposure = GL20.glGetUniformLocation(progComp, "exposure");
            uCompAvg = GL20.glGetUniformLocation(progComp, "avgTex");
            uCompNormalize = GL20.glGetUniformLocation(progComp, "normalizeGain");
            GL20.glUniform1i(GL20.glGetUniformLocation(progComp, "tex"), 0);
            GL20.glUseProgram(0);
            return true;
        } catch (Throwable t) {
            shutdown();
            failed = true;
            WorldLyricsHudDraw.traceOnce("辉光管线着色器不可用，永久退回无辉光: " + t);
            return false;
        }
    }

    /** mask 通路着色器 + VAO（惰性建，与四边形通路分开）。 */
    private boolean ensureMaskProgram() {
        if (progMask != 0) return true;
        try {
            int vs = compile(GL20.GL_VERTEX_SHADER, MASK_VS);
            int fs = compile(GL20.GL_FRAGMENT_SHADER, MASK_FS);
            progMask = GL20.glCreateProgram();
            GL20.glAttachShader(progMask, vs);
            GL20.glAttachShader(progMask, fs);
            GL20.glBindAttribLocation(progMask, 0, "aPos");
            GL20.glBindAttribLocation(progMask, 1, "aColor");
            GL20.glBindAttribLocation(progMask, 2, "aUv");
            GL20.glLinkProgram(progMask);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(progMask, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("mask link: " + GL20.glGetProgramInfoLog(progMask));
            }
            uMaskMvp = GL20.glGetUniformLocation(progMask, "uMvp");
            return true;
        } catch (Throwable t) {
            failed = true;
            WorldLyricsHudDraw.traceOnce("辉光 mask 着色器不可用，永久退回: " + t);
            return false;
        }
    }

    private int newTexture(int w, int h, boolean hdr) {
        return newTexture(w, h, hdr, false);
    }

    private int newTexture(int w, int h, boolean hdr, boolean mipmap) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                mipmap ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, hdr ? 0x881A /* RGBA16F */ : GL11.GL_RGBA8,
                w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return id;
    }

    private int newFbo(int tex) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, tex, 0);
        return fbo;
    }

    private boolean ensureTargets(int w, int h) {
        if (w == width && h == height && maskFbo != 0) return true;
        release();
        try {
            width = w;
            height = h;
            int maskTex = newTexture(w, h, false, true);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL,
                    31 - Integer.numberOfLeadingZeros(Math.max(1, Math.min(w, h))));
            maskTexId = maskTex;
            maskFbo = newFbo(maskTex);
            int lw = w, lh = h;
            for (int i = 0; i < LEVELS; i++) {
                lw = Math.max(1, lw / 2);
                lh = Math.max(1, lh / 2);
                levelW[i] = lw;
                levelH[i] = lh;
                int tex = newTexture(lw, lh, true);
                levelTex[i] = tex;
                levelFbo[i] = newFbo(tex);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            return true;
        } catch (Throwable t) {
            release();
            failed = true;
            WorldLyricsHudDraw.traceOnce("辉光目标创建失败，永久退回: " + t);
            return false;
        }
    }

    private void ensureQuadVao() {
        if (quadVao != 0) return;
        quadVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(quadVao);
        quadVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        FloatBuffer fb = ByteBuffer.allocateDirect(16 * 4).order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer();
        // 全屏两三角：pos2 + uv2
        fb.put(new float[] {
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                 1f,  1f, 1f, 1f,
                -1f,  1f, 0f, 1f,
        }).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private void drawQuad() {
        GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
    }

    private void ensureMaskBuffer(int quadCount) {
        if (maskVao == 0) {
            maskVao = GL30.glGenVertexArrays();
            maskVbo = GL15.glGenBuffers();
            maskEbo = GL15.glGenBuffers();
        }
        if (maskQuadCapacity < quadCount) {
            maskQuadCapacity = Math.max(256, quadCount * 2);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, maskEbo);
            IntBuffer ib = ByteBuffer.allocateDirect(maskQuadCapacity * 6 * 4)
                    .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            for (int q = 0; q < maskQuadCapacity; q++) {
                int v = q * 4, o = q * 6;
                ib.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3);
            }
            ib.flip();
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
    }

    /**
     * 全程：mask 上传 → bloom → 合成垫底。调用方已处于「直画最终画面」的
     * FBO 0（或等价目标）与窗口视口上；本方法保存现场、结束时恢复。
     *
     * @param maskQuads 字形 quad 平铺数据（9 float/顶点：pos3 + rgba4 + uv2），
     *                   坐标已是投影前空间
     * @param mvp       mask 顶点的变换矩阵（投影 × 视图 × 模型）
     * @param windowW/H 目标窗口像素
     */
    void run(float[] maskQuads, int quadCount, org.joml.Matrix4f mvp, int windowW, int windowH,
             float tintR, float tintG, float tintB, float exposure, float spread) {
        run(maskQuads, quadCount, mvp, windowW, windowH, tintR, tintG, tintB, exposure, spread, 1f);
    }

    /**
     * 带覆盖率归一的完整入口。normalizeGain：合成能量除以 mask 平均亮度后乘的
     * 补偿系数 —— 传 1 关闭归一；传「参考覆盖率」时，实际覆盖率低于参考的帧
     * （逐字出现期）被等比增亮，观感与满句一致。
     */
    void run(float[] maskQuads, int quadCount, org.joml.Matrix4f mvp, int windowW, int windowH,
             float tintR, float tintG, float tintB, float exposure, float spread, float normalizeGain) {
        if (failed || windowW <= 0 || windowH <= 0 || quadCount <= 0) return;
        if (!ensurePrograms() || !ensureTargets(windowW, windowH)) return;

        int prevFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        IntBuffer prevViewport = ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder())
                .asIntBuffer();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);
        FloatBuffer prevClear = ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer();
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, prevClear);
        boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        int pSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int pDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int pSrcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int pDstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int prevDepthMode = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int prevArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        java.nio.ByteBuffer prevColorMask = org.lwjgl.system.MemoryStack.stackGet()
                .malloc(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, prevColorMask);
        // 清屏色临时改透明黑：MC 每帧用缓存的清屏色清屏，改完必须原样放回
        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            // 上游 RenderType 可能留着部分 colorMask（如只写 RGB 不写 alpha）——
            // mask 清屏与 bloom 写入会缺通道，晕颜色错或整体不可见，全部强制开
            GL11.glColorMask(true, true, true, true);
            // MC 遗留在纹理单元上的 sampler 会盖掉贴图过滤（LINEAR 被换成
            // NEAREST 之类），全通道解绑后再跑滤波
            for (int unit = 0; unit < 4; unit++) GL33.glBindSampler(unit, 0);

            // ---- 1. mask：字形 quad 平色画进离屏画布 ----
            if (!ensureMaskProgram()) return;
            ensureQuadVao();
            ensureMaskBuffer(quadCount);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, maskFbo);
            GL11.glViewport(0, 0, windowW, windowH);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL20.glUseProgram(progMask);
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                FloatBuffer m = stack.mallocFloat(16);
                mvp.get(m);
                GL20.glUniformMatrix4fv(uMaskMvp, false, m);
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, maskVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, maskQuads, GL15.GL_STREAM_DRAW);
            GL30.glBindVertexArray(maskVao);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, maskEbo);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 36, 0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, 36, 12);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, 36, 28);
            // mask 累加用加法：字形描边互相重叠处的能量应更亮（与 bloom 的能量观一致）；
            // alpha 通道同加，晕边界不打折
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
            GL11.glDrawElements(GL11.GL_TRIANGLES, quadCount * 6, GL11.GL_UNSIGNED_INT, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

            // ---- 2. 逐级下采样（关混合，纯替换）----
            GL30.glBindVertexArray(quadVao);
            GL11.glDisable(GL11.GL_BLEND);
            GL20.glUseProgram(progDown);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTexId);
            int srcW = windowW, srcH = windowH;
            for (int i = 0; i < LEVELS; i++) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, levelFbo[i]);
                GL11.glViewport(0, 0, levelW[i], levelH[i]);
                GL20.glUniform2f(uDownTexel, 1f / srcW, 1f / srcH);
                drawQuad();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, levelTex[i]);
                srcW = levelW[i];
                srcH = levelH[i];
            }

            // ---- 3. 逐级放回叠加（加法混合）----
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
            GL20.glUseProgram(progUp);
            // spread=0 语义 = 无外晕（用户拖到最左期望晕消失）：级间强度随 spread
            // 线性归零，而不是停在 0.55 的底值。
            GL20.glUniform1f(uUpIntensity, spread * (0.55f + spread * 0.85f));
            for (int i = LEVELS - 2; i >= 0; i--) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, levelFbo[i]);
                GL11.glViewport(0, 0, levelW[i], levelH[i]);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, levelTex[i + 1]);
                GL20.glUniform2f(uUpTexel, 1f / levelW[i + 1], 1f / levelH[i + 1]);
                drawQuad();
            }

            // ---- 4. 合成垫底（回到调用方的目标，加色混合）----
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevFbo);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glViewport(prevViewport.get(0), prevViewport.get(1),
                    prevViewport.get(2), prevViewport.get(3));
            GL20.glUseProgram(progComp);
            GL20.glUniform3f(uCompTint, tintR, tintG, tintB);
            GL20.glUniform1f(uCompExposure, exposure);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, levelTex[0]);
            drawQuad();
        } catch (Throwable t) {
            failed = true;
            WorldLyricsHudDraw.traceOnce("辉光管线运行失败，永久退回无辉光: " + t);
        } finally {
            // ---- 现场恢复 ----
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
            GL13.glActiveTexture(prevActiveTex);
            GL20.glUseProgram(prevProgram);
            GL11.glColorMask(prevColorMask.get(0) != 0, prevColorMask.get(1) != 0,
                    prevColorMask.get(2) != 0, prevColorMask.get(3) != 0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuffer);
            GL11.glClearColor(prevClear.get(0), prevClear.get(1), prevClear.get(2), prevClear.get(3));
            if (prevScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            if (prevBlend) GL11.glEnable(GL11.GL_BLEND);
            else GL11.glDisable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(pSrcRgb, pDstRgb, pSrcA, pDstA);
            if (prevDepthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(prevDepthMode);
            GL11.glDepthMask(prevDepthMask);
            if (prevCull) GL11.glEnable(GL11.GL_CULL_FACE);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevFbo);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glViewport(prevViewport.get(0), prevViewport.get(1),
                    prevViewport.get(2), prevViewport.get(3));
        }
    }

    private int maskTexId;
}
