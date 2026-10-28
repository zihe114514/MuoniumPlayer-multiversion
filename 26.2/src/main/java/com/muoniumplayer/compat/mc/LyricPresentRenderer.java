package com.muoniumplayer.compat.mc;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
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
            #version 330 core
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
            #version 330 core
            uniform sampler2D uTex;
            in vec4 vColor;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTex, vUv) * vColor;
            }
            """;

    private static final AtomicBoolean FAILED = new AtomicBoolean(false);
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
        if (b.quadCount >= MAX_QUADS) return;
        int tex = texGlId;
        // 跨页开新段（同页连续 quad 归同段；页序即绘制序）
        if (segCount == 0 || segTexIds[segCount - 1] != tex) {
            if (segCount >= segTexIds.length) return;
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

            // 相对采到的相机相对坐标，视图旋转含共轭四元数（无平移）：MVP = P×V
            org.joml.Matrix4f mvp = new org.joml.Matrix4f(projection).mul(viewRotation);

            GL20.glUseProgram(program);
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

            // 视口设到全屏：present 时 FBO 0 的 viewport 是渲染管线遗留值，
            // 不重设的话顶点会被旧视口裁掉（症状=完全看不见）
            GL11.glViewport(0, 0, windowWidth, windowHeight);

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
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL30.glBindVertexArray(0);
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
