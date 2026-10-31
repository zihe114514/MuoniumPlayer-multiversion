package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import com.muoniumplayer.core.lyrics3d.WorldLyricsSettings;
import com.muoniumplayer.core.lyrics3d.WorldLyricsState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

/**
 * 3D 世界歌词的宿主绘制实现 —— <b>公告牌矩阵、像素字与逐字平滑字形三条通路</b>。
 *
 * <h2>两条绘制通道</h2>
 * <ul>
 *   <li>原版风格 {@link #drawPixelText}：原版 vanillaText 口径 —— 阴影按原版
 *       规则拆开单独画在字前，本体 {@code drawInBatch(SEE_THROUGH, FULL_BRIGHT)}，
 *       画完立刻 endBatch。</li>
 *   <li>现代化风格 {@link #drawSmoothGlyph}：查 {@link LyricGlyphAtlas}
 *       （Skija 5× 超采样字形 + TextGlow 三层高斯发光烘在同一张格里），以原版
 *       {@code RenderType.textSeeThrough(页纹理)} 发一个 POSITION_COLOR_TEX_LIGHTMAP
 *       四顶点 quad —— 逐字 textured quad，wiggle 的旋转/缩放
 *       绕字中心落进顶点矩阵；页纹理强制 LINEAR（见 PageTexture），超采样不被
 *       NEAREST 采样毁掉。</li>
 * </ul>
 *
 * <p>世界渲染期间的 GL 状态（关深度测试、关剔除、透明混合）由
 * {@code WorldLyricsBridge} 在调渲染器前设好，等价 {@code ModernWorld.begin → worldState}。
 * 两条通道共用同一份 billboard 矩阵与坐标口径，切换风格只换绘制通道，几何行为一致。</p>
 */
public final class WorldBillboard implements WorldLyricsRenderer.Host {

    /** 一次性诊断开关。 */
    static final boolean TRACE = Boolean.getBoolean("muonium.debug.worldLyrics");

    /** 每帧共享实例。 */
    public static final WorldBillboard INSTANCE = new WorldBillboard();

    private PoseStack poseStack;
    private MultiBufferSource.BufferSource buffers;
    private double camX, camY, camZ;
    // 同页字形合批：当前挂起的页句柄与字形数（endBatch 延迟到页切换/帧尾）
    private Object pendingGlyphPage;
    private int pendingGlyphCount;
    private boolean tracedOnce;
    private boolean tracedSmooth;

    /** 辉光 mask 采集（直画端帧头放进来；null = 本帧不采集）。 */
    private com.muoniumplayer.core.lyrics3d.MaskQuadCollector glowMask;

    private WorldBillboard() {
    }

    /** 直画端帧头：放进 mask 缓冲让本帧字形双写；end 后收回。 */
    public void beginGlowMask(com.muoniumplayer.core.lyrics3d.MaskQuadCollector mask) {
        this.glowMask = mask;
    }

    public void endGlowMask() {
        this.glowMask = null;
    }

    @Override
    public com.muoniumplayer.core.lyrics3d.MaskQuadCollector glowMaskTarget() {
        return glowMask;
    }

    // ==================== 光影包适配：渲染类型单点分发 ====================

    /**
     * HUD 阶段直画（「更激进的适配」）时强制原版 textSeeThrough：此时 Oculus 已收工，
     * 原版着色器就是「无光影的样子」；beaconBeam 信标类型只在光影管线的世界阶段有意义。
     */
    static boolean forceVanillaType;

    /**
     * 光影包激活时的渲染类型：信标光柱程序（全亮、不采样光照图、无 alpha 裁剪）
     * + 我们的动态纹理 + 半透明混合 + 无深度测试。其余状态镜像 textSeeThrough。
     * 每纹理懒建并缓存 —— RenderType 是无状态对象，同纹理复用同一实例
     * 才能吃到 BufferSource 的同批合批。
     */
    private static final java.util.concurrent.ConcurrentHashMap<ResourceLocation, RenderType> SHADER_TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 特效通道的加色混合渲染类型（全息语义）：纹理 × 顶点色后以 srcAlpha,one
     * 叠加 —— 光效罩到亮背景也只会更亮，不再像透明覆盖那样显灰。
     */
    private static final java.util.concurrent.ConcurrentHashMap<ResourceLocation, RenderType> ADDITIVE_TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static RenderType additiveType(ResourceLocation rl) {
        return ADDITIVE_TYPES.computeIfAbsent(rl, r -> {
            RenderType.CompositeState state = RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_BEACON_BEAM_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(r, false, false))
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false);
            return RenderType.create("muonium_lyrics_world_fx",
                    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                    VertexFormat.Mode.QUADS, 1536, false, true, state);
        });
    }

    private static RenderType shaderTextType(ResourceLocation rl) {
        return SHADER_TYPES.computeIfAbsent(rl, r -> {
            RenderType.CompositeState state = RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_BEACON_BEAM_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(r, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false);
            return RenderType.create("muonium_lyrics_world_text",
                    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                    VertexFormat.Mode.QUADS, 1536, false, true, state);
        });
    }

    /**
     * 歌词世界绘制的渲染类型入口 —— 全部字形 quad 的唯一取发点。
     * 光影包激活（ShaderPackProbe 本帧值）走信标全亮程序，否则原版 textSeeThrough。
     * refresh() 在帧头调用，帧中读的是本帧缓存值，同一帧内模式恒定 ——
     * 挂起合批不会跨模式串批（模式若在帧间翻转，挂起键在 begin 里重置）。
     */
    private RenderType currentGlyphType(ResourceLocation pageRl) {
        return forceVanillaType || !ShaderPackProbe.isActive()
                ? RenderType.textSeeThrough(pageRl)
                : shaderTextType(pageRl);
    }

    /** 同 {@link #currentGlyphType}，特效通道（拖尾/光晕/星芒/爆闪/环/拖尾画布）。 */
    /**
     * 特效通道（拖尾/光晕/星芒/爆闪/环/拖尾画布）：恒走加色混合 —— 柔边光效
     * 在 alpha 混合下永远只是「盖一层灰」，加色才是光的语义（亮背景也不被
     * 压灰）。HUD 直画窗口内同一张表（Oculus 已收工，程序不被拦）。
     */
    private RenderType currentEffectType(ResourceLocation rl) {
        return additiveType(rl);
    }

    /** 事件入口（worldRenderEnd 的矩阵在 Bridge 里已设好）。 */
    public void begin(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                      double camX, double camY, double camZ) {
        this.poseStack = poseStack;
        this.buffers = buffers;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        // 帧间模式翻转（用户中途开关光影包）时，挂起的合批记录若残留，
        // end() 会用新模式收旧模式的批 —— 直接清掉，下一帧重新合批（无观感差异）
        if (pendingEffectRl != null || pendingGlyphPage != null) {
            pendingEffectRl = null;
            pendingGlyphPage = null;
            pendingGlyphCount = 0;
        }
    }

    public void end() {
        // 帧尾把挂起的字形与特效批次收掉（列顺序无关紧要：深度测试已关、文字后画）。
        // 挂起记录先复位再收批（finally 保证）：若 endBatch 抛异常，记录也不能残留到
        // 下一帧 —— 否则下一帧 begin 丢弃记录后，这批孤儿顶点会被原版在任意时点
        // 以错误状态冲刷（幽灵字形）。
        try {
            if (buffers != null && pendingGlyphPage != null) {
                buffers.endBatch(currentGlyphType(
                        ((LyricGlyphAtlasBridge.PageHandle) pendingGlyphPage).resourceLocation()));
            }
            if (buffers != null && pendingEffectRl != null) {
                buffers.endBatch(currentEffectType(pendingEffectRl));
            }
        } finally {
            pendingGlyphPage = null;
            pendingGlyphCount = 0;
            pendingEffectRl = null;
        }
        this.poseStack = null;
        this.buffers = null;
    }

    @Override
    public void pushBillboard(double rx, double ry, double rz,
                              double yawDeg, double pitchDeg, float scale) {
        if (poseStack == null) return;
        // 公告牌矩阵 push（上游 ModernWorld.push 口径）：
        //   translate(rx,ry,rz) → rotateY(-yaw) → rotateX(pitch) → scale(-s,-s,s)
        poseStack.pushPose();
        Matrix4f mat = poseStack.last().pose();
        mat.translate((float) rx, (float) ry, (float) rz);
        mat.rotateY((float) -Math.toRadians(yawDeg));
        mat.rotateX((float) Math.toRadians(pitchDeg));
        mat.scale(-scale, -scale, scale);
    }

    @Override
    public void popBillboard() {
        if (poseStack == null) return;
        poseStack.popPose();
    }

    @Override
    public void drawPixelText(String text, float x, float y, int argb, boolean shadow) {
        if (poseStack == null || buffers == null || text == null || text.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        // 原版文字阴影拆分策略：阴影拆开单独画，本体 false shadow
        if (shadow) {
            int shadowColor = ((argb & 0xFCFCFC) >> 2) | (argb & 0xFF000000);
            drawVanilla(mc, text, x + 1f, y + 1f, shadowColor);
        }
        drawVanilla(mc, text, x, y, argb);
        if (TRACE && !tracedOnce) {
            tracedOnce = true;
            System.out.println("[WorldLyrics/trace] 原版像素字已发顶点: \"" + text + "\"");
        }
    }

    /** 像素字：SEE_THROUGH + FULL_BRIGHT + 画完 endBatch。 */
    private void drawVanilla(Minecraft mc, String text, float x, float y, int argb) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();
        mc.font.drawInBatch(text, x, y, argb, false, mat, buffers,
                Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        buffers.endBatch();
    }

    /**
     * 现代化风格单字：字形图集取格（Skija 5× 超采样 + TextGlow 发光烘焙），
     * 原版 textSeeThrough 管线（页纹理强制 LINEAR）发一个四顶点 quad。
     *
     * <p>坐标口径：传进来的 (x, y) 是字形<b>顶点</b>（字位框左上角，设计像素），
     * 对应格内 (inkX, inkY)。quad 覆盖整格（含发光留白），尺寸 = 格框 ÷ BAKE_SCALE。
     * wiggle 四量绕<b>字中心</b>（半个 advance × 半个字位框）旋转/缩放 —— 与上游
     * withWiggle 的 translate(cx,cy)→rotate→scale→translate(-cx,-cy) 同构；dx/dy 已由
     * 渲染器加进 x/y。</p>
     *
     * <p>格子不可用（图集未装配/Skija 缺位/字符不可见）时回退像素字 —— 绝不吞字。</p>
     */
    @Override
    public void drawSmoothGlyph(char c, float x, float y, float sizePx,
                                int rgb, float alpha, float rot, float dx, float dy, float sc,
                                float glowMul) {
        if (poseStack == null || buffers == null) return;
        // 发光参数取本帧快照（Bridge 在帧头装配好；没装配时按无发光查格）
        WorldLyricsSettings settings = WorldLyricsBridge.currentSettings;
        float glowStrength = settings != null ? settings.glowStrength : 0f;
        float glowSpread = settings != null ? settings.glowSpread : 1f;
        int glowColor = settings != null ? settings.glowColorRgb : 0xFFFFFF;
        // 心象的发光脉冲：正在唱的块满档、其余块压档 —— 查格前乘上去（缓存键已量化）。
        glowStrength *= glowMul;
        // 辉光后处理接管发光：本帧 mask 采集激活时字形按零烘焙强度取格 ——
        // 晕全由 bloom 从 mask 生成，烘焙晕与后处理晕不再叠加（能量翻倍会糊）。
        if (glowMask != null) glowStrength = 0f;

        LyricGlyphAtlas.GlyphCell cell = LyricGlyphAtlas.getGlyph(
                c, rgb & 0xFFFFFF, glowStrength, glowSpread, glowColor);
        if (cell == null) {
            // 绘光模式下「预算内暂时没烘出来」不回退像素字 —— 那正是用户看到的
            // 「有时退回原版渲染风格」：字形瞬闪像素风再换回烘焙字形，非常扎眼。
            // 图集可用时静默跳过该字（下一帧补齐，烘焙预算已提到 4 格/帧）；
            // 图集整体不可用（host 缺失/字体缺位）仍回退像素字，绝不吞字。
            if (settings != null && settings.huiguang && LyricGlyphAtlas.isFunctional()) {
                return;
            }
            // 图集不可用/超尺寸：像素字兜底（无阴影，与平滑档的无阴影语义一致）
            int argb = ((int) (alpha * 255f) << 24) | (rgb & 0xFFFFFF);
            drawPixelText(String.valueOf(c), x, y, argb, false);
            return;
        }
        if (!(cell.page instanceof LyricGlyphAtlasBridge.PageHandle)) {
            return;
        }
        ResourceLocation pageRl = ((LyricGlyphAtlasBridge.PageHandle) cell.page).resourceLocation();

        // 同页合批：页切换（或首次）时把上一页的字形批次收掉 —— 一句 40 字
        // 从每字一次 endBatch 降到每页一次。页内 endBatch 省略不影响正确性：
        // textSeeThrough 是 shared batch，getBuffer 同 RenderType 连续顶点
        // 天然累积；页切换时 lastSharedType 自动换批的前提是参数不同 —— 同页
        // 参数相同、跨页显式 flush 保证不串页。
        if (pendingGlyphPage != cell.page) {
            if (pendingGlyphPage != null) {
                buffers.endBatch(currentGlyphType(
                        ((LyricGlyphAtlasBridge.PageHandle) pendingGlyphPage).resourceLocation()));
            }
            pendingGlyphPage = cell.page;
            pendingGlyphCount = 0;
        }
        pendingGlyphCount++;

        // 烘焙像素 → 设计像素。字形顶点 (x, y) 是「字位框左上角」在<b>行内坐标</b>里的位置
        // （buildGlyphs 逐字推进的坐标），格内墨迹原点 (inkX, inkY) 对到它 —— quad 四角
        // 必须加上 (x, y) 再进公告牌矩阵。初版漏加，整行所有字都叠在公告牌原点。
        // 档位运行时化：读 volatile int（bakeScale）实时除，切档即时生效
        float inv = 1f / LyricGlyphAtlas.bakeScale();
        float qLeft = x - cell.inkX * inv;
        float qTop = y - cell.inkY * inv;
        float qRight = qLeft + cell.w * inv;
        float qBottom = qTop + cell.h * inv;

        float pageF = LyricGlyphAtlas.PAGE_SIZE;
        // UV 半像素内缩：LINEAR 采样时 quad 边缘正落 texel 边界会混入邻格
        // （页内格间零间隔）——内缩半个 texel 让采样核完全落在格内，去边缘灰缝
        float halfTexel = 0.5f / pageF;
        float u0 = cell.x / pageF + halfTexel;
        float v0 = cell.y / pageF + halfTexel;
        float u1 = (cell.x + cell.w) / pageF - halfTexel;
        float v1 = (cell.y + cell.h) / pageF - halfTexel;

        // 绕字中心（行内坐标：半个 advance × 半个字位框）的旋转/缩放（上游 withWiggle 同构）
        float cx = x + (cell.advance * inv) * 0.5f;
        float cy = y + (cell.inkHeight * inv) * 0.5f;
        boolean transformed = rot != 0f || sc != 1f;
        float cos = transformed ? (float) Math.cos(Math.toRadians(rot)) : 1f;
        float sin = transformed ? (float) Math.sin(Math.toRadians(rot)) : 0f;
        float s = transformed ? sc : 1f;

        int argb = (((int) (alpha * 255f) & 0xFF) << 24) | (rgb & 0xFFFFFF);

        VertexConsumer vc = buffers.getBuffer(currentGlyphType(pageRl));
        Matrix4f mat = poseStack.last().pose();
        // 四顶点：左上 → 左下 → 右下 → 右上（QUADS 环绕序）
        putGlyphVertex(vc, mat, qLeft, qTop, cx, cy, cos, sin, s, argb, u0, v0);
        putGlyphVertex(vc, mat, qLeft, qBottom, cx, cy, cos, sin, s, argb, u0, v1);
        putGlyphVertex(vc, mat, qRight, qBottom, cx, cy, cos, sin, s, argb, u1, v1);
        putGlyphVertex(vc, mat, qRight, qTop, cx, cy, cos, sin, s, argb, u1, v0);

        // 辉光 mask 双写：同一份几何（绕中心变换前）记进平色缓冲，alpha 随字形顶点。
        if (glowMask != null) {
            glowMask.addQuad(mat, qLeft, qTop, qLeft, qBottom, qRight, qBottom, qRight, qTop,
                    cx, cy, cos, sin, s, ((int) (alpha * 255f) & 0xFF) << 24);
        }

        // 同页合批：这里不再逐字 endBatch —— 页切换/帧尾统一收（见上）。

        if (TRACE && !tracedSmooth) {
            tracedSmooth = true;
            System.out.println("[WorldLyrics/trace] 现代档字形 quad 已发: '" + c
                    + "' cell=" + cell.w + "x" + cell.h + " pad=" + cell.inkX);
        }
    }

    /**
     * 一个字形 quad 顶点：绕字中心旋转/缩放（设计像素口径），再进 billboard 矩阵。
     *
     * <p>1.20.1 的 {@code VertexConsumer} 还是旧式链（javap 核对：{@code vertex/
     * color/uv/uv2/overlayCoords + endVertex()}，没有 1.21 起的
     * {@code addVertex/setColor/setUv/setUv2/setUv1}），顶点必须以 {@code endVertex()}
     * 收尾；{@code color(int)} 单参重载与 {@code vertex(Matrix4f, float, float, float)}
     * 便捷重载在 1.20.1 已存在，直接用。lightmap 拆两个坐标走 {@code uv2(int, int)}。</p>
     */
    private static void putGlyphVertex(VertexConsumer vc, Matrix4f mat,
                                       float px, float py, float cx, float cy,
                                       float cos, float sin, float s, int argb,
                                       float u, float v) {
        float vx = px, vy = py;
        if (cos != 1f || sin != 0f || s != 1f) {
            float ox = px - cx;
            float oy = py - cy;
            vx = cx + (ox * cos - oy * sin) * s;
            vy = cy + (ox * sin + oy * cos) * s;
        }
        vc.vertex(mat, vx, vy, 0f)
                .color(argb)
                .uv(u, v)
                // packed light 拆两个坐标（BakedGlyph 同款）：u = 低半（方块光）、v = 高半（天空光）。
                // 传 (0, 240) 会把方块光压成 0 —— 夜里光查出来几乎全黑，字形被乘成黑方块。
                .uv2(LightTexture.FULL_BRIGHT & 0xFFFF,
                        (LightTexture.FULL_BRIGHT >> 16) & 0xFFFF)
                .endVertex();
    }

    @Override
    public void drawTrail(float[] xs, float[] ys, int count,
                          int argb, float widthPx) {
        if (poseStack == null || buffers == null || count < 2) return;
        ResourceLocation rl = trailTexture();
        if (rl == null) return;

        // 逐段 quad 带（folia trackLayer 的 stroke 等价物）：均匀宽度、沿迹均匀
        // 顶点色 —— folia 是整条一个 alpha 的描边（round cap/join），没有头尾渐变。
        // UV 钉在白纹理的不透明行（v≈0.03）。两层模拟 bloom：窄亮芯 + 宽淡晕
        // （folia 的辉光来自 graphics 组 bloom 过滤器，本仓库无后处理，双 pass 等效）。
        float half = Math.max(0.15f, widthPx * 0.5f);
        int baseAlpha = (argb >>> 24) & 0xFF;
        int rgb = argb & 0xFFFFFF;

        VertexConsumer vc = buffers.getBuffer(currentEffectType(rl));
        Matrix4f mat = poseStack.last().pose();
        emitTrail(vc, mat, xs, ys, count, half, baseAlpha, rgb, 1f, 1f);
        emitTrail(vc, mat, xs, ys, count, half * 3f, baseAlpha, rgb, 1f, 0.35f);
        flushEffectBatch(rl);
    }

    /** 发一段径迹折线的 quad 带（widthScale/alphaScale 用于泛光外层）。 */
    private static void emitTrail(VertexConsumer vc, Matrix4f mat,
                                  float[] xs, float[] ys, int count,
                                  float half, int baseAlpha, int rgb,
                                  float widthScale, float alphaScale) {
        float hw = half * widthScale;
        int a = Math.min(255, (int) (baseAlpha * alphaScale));
        // 每点法线 = 相邻两段法线的归一化平均（folia round join 的廉价近似：
        // 拐点处带子平滑过渡，不再有外缘细缝/内缘叠色；端点退化为单段法线）
        for (int i = 0; i < count - 1; i++) {
            float x0 = xs[i], y0 = ys[i];
            float x1 = xs[i + 1], y1 = ys[i + 1];
            // 标量法线（相邻两段平均，端点退化单段）—— 原数组版是飞行窗口最大分配源
            int p0 = Math.max(0, i - 1), q0 = Math.min(count - 1, i + 1);
            float dx0 = xs[q0] - xs[p0], dy0 = ys[q0] - ys[p0];
            float l0 = (float) Math.sqrt(dx0 * dx0 + dy0 * dy0);
            float n0x = l0 < 1e-4f ? 0f : -dy0 / l0, n0y = l0 < 1e-4f ? 1f : dx0 / l0;
            int p1 = i, q1 = Math.min(count - 1, i + 2);
            float dx1 = xs[q1] - xs[p1], dy1 = ys[q1] - ys[p1];
            float l1 = (float) Math.sqrt(dx1 * dx1 + dy1 * dy1);
            float n1x = l1 < 1e-4f ? 0f : -dy1 / l1, n1y = l1 < 1e-4f ? 1f : dx1 / l1;
            putTrailVertex(vc, mat, x0 + n0x * hw, y0 + n0y * hw, a, rgb);
            putTrailVertex(vc, mat, x0 - n0x * hw, y0 - n0y * hw, a, rgb);
            putTrailVertex(vc, mat, x1 + n1x * hw, y1 + n1y * hw, a, rgb);
            putTrailVertex(vc, mat, x1 - n1x * hw, y1 - n1y * hw, a, rgb);
        }
    }

    /**
     * 径迹 quad 顶点（文字像素坐标直接进公告牌矩阵；FULL_BRIGHT 同字形通路；
     * UV 钉在白纹理的不透明行 —— 顶点 alpha 即整条径迹的均匀透明度）。
     *
     * <p>1.20.1 的 {@code VertexConsumer} 还是旧式链（与 {@link #putGlyphVertex} 同口径）：
     * {@code vertex/color/uv/uv2} + {@code endVertex()} 收尾。</p>
     */
    private static void putTrailVertex(VertexConsumer vc, Matrix4f mat,
                                       float x, float y, int alpha, int rgb) {
        int argb = (alpha << 24) | rgb;
        vc.vertex(mat, x, y, 0f)
                .color(argb)
                .uv(0.5f, 0.03f)
                .uv2(LightTexture.FULL_BRIGHT & 0xFFFF,
                        (LightTexture.FULL_BRIGHT >> 16) & 0xFFFF)
                .endVertex();
    }

    /**
     * 径迹贴图：128×8 白色横条（v 从上不透明白到下全透明，历史渐变保留）——
     * 绘光还原度批次后径迹顶点 alpha 均匀、UV 钉在不透明行（v≈0.03），纹理
     * 只当白底用。首绘惰性生成并注册。
     */
    private static ResourceLocation trailTexture() {
        if (trailError) return null;
        if (trailRl == null) {
            try {
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, 128, 8, false);
                for (int y = 0; y < 8; y++) {
                    // 头（y=0）不透明 → 尾（y=7）透明；sRGB 感知的平方衰减更接近 folia 的渐隐
                    int a = Math.max(0, 255 - (255 * y * y) / (7 * 7));
                    for (int x = 0; x < 128; x++) {
                        img.setPixelRGBA(x, y, 0xFFFFFF | (a << 24));
                    }
                }
                // 8 行渐变 + 默认 NEAREST = 8 级色带；shard 每次 bind 都按 (false,false)
                // 调 setFilter（同字形页 PageTexture 的口径），构造后一次性设置会被冲掉
                // —— TrailTexture 覆写 setFilter 强制 LINEAR。
                DynamicTexture tex = new TrailTexture(img);
                // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath，只有公开构造器（javap 核对）。
                trailRl = new ResourceLocation("muoniumplayer",
                        "dynamic/worldlyrics_trail_" + (rlCounter++));
                Minecraft.getInstance().getTextureManager().register(trailRl, tex);
            } catch (Throwable t) {
                trailError = true;
                if (TRACE) System.out.println("[WorldLyrics/trace] 径迹纹理生成失败，拖尾停用: " + t);
                return null;
            }
        }
        return trailRl;
    }

    private static ResourceLocation trailRl;
    private static boolean trailError;
    private static int rlCounter;

    /**
     * 绘光逐字光晕（folia halos）：径向渐变柔光点 quad 垫在字下。
     * folia haloAlpha = glyphAlpha × lit × (0.03+0.14illum+0.08flash)、
     * size = fontPx × (2.2+0.8illum)；渲染层已按 lit 算好 size/alpha。
     */
    @Override
    public void drawHalo(float cx, float cy, float sizePx, int argb) {
        if (poseStack == null || buffers == null || (argb >>> 24) == 0) return;
        ResourceLocation rl = haloTexture();
        if (rl == null) return;
        float half = Math.max(1f, sizePx * 0.5f);

        VertexConsumer vc = buffers.getBuffer(currentEffectType(rl));
        Matrix4f mat = poseStack.last().pose();
        putHaloVertex(vc, mat, cx - half, cy - half, 0f, 0f, argb);
        putHaloVertex(vc, mat, cx - half, cy + half, 0f, 1f, argb);
        putHaloVertex(vc, mat, cx + half, cy + half, 1f, 1f, argb);
        putHaloVertex(vc, mat, cx + half, cy - half, 1f, 0f, argb);
        flushEffectBatch(rl);
    }

    /**
     * 特效通道挂起合批（镜像字形合批模式）：halo/star/streak/trail 四通道各自
     * 贴图恒定，textSeeThrough 是 shared batch —— 同 RenderType 连续 addVertex
     * 天然累积；记录挂起贴图，切换到另一张时先收批。发射顺序即绘制顺序
     * （深度测试已关），BufferSource 跨类型自动换批保证不串纹理，零视觉变化。
     */
    private ResourceLocation pendingEffectRl;

    private void flushEffectBatch(ResourceLocation newRl) {
        if (pendingEffectRl != null && pendingEffectRl != newRl) {
            buffers.endBatch(currentEffectType(pendingEffectRl));
        }
        pendingEffectRl = newRl;
    }

    /** 光晕 quad 顶点（1.20.1 旧式顶点链，同 {@link #putTrailVertex} 口径）。 */
    private static void putHaloVertex(VertexConsumer vc, Matrix4f mat,
                                      float x, float y, float u, float v, int argb) {
        vc.vertex(mat, x, y, 0f)
                .color(argb)
                .uv(u, v)
                .uv2(LightTexture.FULL_BRIGHT & 0xFFFF,
                        (LightTexture.FULL_BRIGHT >> 16) & 0xFFFF)
                .endVertex();
    }

    // ==================== 绘光拖尾画布（Skija 光栅化通路的宿主侧） ====================

    // 按槽位各持一张画布纹理：同一帧 active 行 + 淡出行都进拖尾窗口时
    //（短句/seek），若共用一张纹理，后上传的行会覆盖先提交 quad 的像素
    private static final ResourceLocation[] trailCanvasRl = new ResourceLocation[4];
    private static final TrailTexture[] trailCanvasTex = new TrailTexture[4];
    private static final int[] trailCanvasW = new int[4];
    private static final int[] trailCanvasH = new int[4];
    private static boolean trailCanvasError;
    private static int trailCanvasErrSlot = -1;

    /**
     * 绘光拖尾画布上屏：渲染层用 Skija 在 CPU 端光栅化整句径迹（多层辉光 +
     * 沿程渐变 + 圆头圆接 + 超采样 AA），这里把像素灌进一张动态纹理、以一个
     * quad 覆盖句字面区域 —— 整帧径迹一条纹理一次收批，圆滑无锯齿。
     * 尺寸不变时复用纹理只重传像素（尺寸有 8px 网格量化，换行才重建）；
     * slot 是行槽位（active 0、淡出行 1 起），各槽独立纹理互不覆盖。
     */
    @Override
    public boolean drawTrailCanvas(int[] px, int w, int h,
                                   float planeX, float planeY,
                                   float planeW, float planeH, float alpha,
                                   int slot) {
        if (poseStack == null || buffers == null || trailCanvasError
                || px == null || w <= 0 || h <= 0 || alpha <= 0.01f
                || slot < 0 || slot >= trailCanvasRl.length) return false;
        ResourceLocation rl = trailCanvasRl[slot];
        TrailTexture tex = trailCanvasTex[slot];
        // 挂起批次自检：本槽纹理的 quad 还在缓冲里没收批时，必须先收掉再
        // 改写/释放纹理 —— 否则同帧双拖尾行时后行像素覆盖前行 quad（同尺寸）、
        // 或 release 后挂起 quad 引用已删纹理画出缺失纹理（变尺寸）
        if (pendingEffectRl == rl && buffers != null) {
            buffers.endBatch(currentEffectType(rl));
            pendingEffectRl = null;
        }
        try {
            if (tex == null || trailCanvasW[slot] != w || trailCanvasH[slot] != h) {
                if (rl != null) {
                    Minecraft.getInstance().getTextureManager().release(rl);
                    trailCanvasTex[slot] = null;
                }
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
                tex = new TrailTexture(img);
                // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath，只有公开构造器（javap 核对）。
                rl = new ResourceLocation("muoniumplayer",
                        "dynamic/worldlyrics_trailcanvas_" + (rlCounter++));
                Minecraft.getInstance().getTextureManager().register(rl, tex);
                trailCanvasRl[slot] = rl;
                trailCanvasTex[slot] = tex;
                trailCanvasW[slot] = w;
                trailCanvasH[slot] = h;
            }
            NativeImage img = tex.getPixels();
            if (img == null || px.length < w * h) return false;
            // 共享层已按 NativeImage 内存序（ABGR、直通 alpha）输出 —— 这里是
            // 纯数组拷贝，不再做每像素换位（拖尾活跃帧像素量几十万~上百万级，
            // 每帧省一整遍位运算扫描）。整图逐帧全量覆写：尺寸复用时旧径迹必须
            // 被清掉，透明像素也写 0 —— 留白跳写会残留上一帧拖尾。
            // 边界钉死 w×h：曾有共享层缓冲大于画布时按 px.length 越界写、
            // 写坏相邻 native 内存的教训。
            for (int row = 0, i = 0; row < h; row++) {
                for (int col = 0; col < w; col++, i++) {
                    img.setPixelRGBA(col, row, px[i]);
                }
            }
            tex.upload();
            // 0 级每帧全变 → 重生成 mip 链（画布已按设备像素封顶 1600²，亚毫秒）
            if (tex.mipTop > 0) {
                org.lwjgl.opengl.GL30.glGenerateMipmap(
                        com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D);
            }
        } catch (Throwable t) {
            trailCanvasError = true;
            trailCanvasErrSlot = slot;
            if (TRACE) System.out.println("[WorldLyrics/trace] 拖尾画布纹理失败，径迹退回 quad 带: " + t);
            return false;
        }
        int argb = Math.min(255, (int) (alpha * 255f)) << 24 | 0xFFFFFF;
        VertexConsumer vc = buffers.getBuffer(currentEffectType(rl));
        Matrix4f mat = poseStack.last().pose();
        putHaloVertex(vc, mat, planeX, planeY, 0f, 0f, argb);
        putHaloVertex(vc, mat, planeX, planeY + planeH, 0f, 1f, argb);
        putHaloVertex(vc, mat, planeX + planeW, planeY + planeH, 1f, 1f, argb);
        putHaloVertex(vc, mat, planeX + planeW, planeY, 1f, 0f, argb);
        flushEffectBatch(rl);
        return true;
    }


    /**
     * 光晕贴图：128×128 双高斯白点（紧核 + 宽晕）：a = exp(−r²×9)×0.85 +
     * exp(−r²×2.8)×0.15 —— 核心能量紧贴字形，外围留一层 15% 峰值的宽缓晕托住
     * 扫光过渡（单高斯 r=0.5 后几乎归零，扫过时晕边缘生硬；双高斯晕尾自然
     * 衰减到边缘）。首绘惰性生成并注册，TrailTexture 同款 LINEAR；
     * 失败熔断只丢光晕。
     */
    private static ResourceLocation haloTexture() {
        if (haloError) return null;
        if (haloRl == null) {
            try {
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, 128, 128, false);
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        float dx = (x + 0.5f) / 64f - 1f;
                        float dy = (y + 0.5f) / 64f - 1f;
                        float r2 = dx * dx + dy * dy;
                        int a = (int) (255 * ((float) Math.exp(-r2 * 9f) * 0.85f
                                + (float) Math.exp(-r2 * 2.8f) * 0.15f));
                        img.setPixelRGBA(x, y, 0xFFFFFF | (a << 24));
                    }
                }
                DynamicTexture tex = new TrailTexture(img);
                // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath，只有公开构造器（javap 核对）。
                haloRl = new ResourceLocation("muoniumplayer",
                        "dynamic/worldlyrics_halo_" + (rlCounter++));
                Minecraft.getInstance().getTextureManager().register(haloRl, tex);
            } catch (Throwable t) {
                haloError = true;
                if (TRACE) System.out.println("[WorldLyrics/trace] 光晕纹理生成失败，逐字光晕停用: " + t);
                return null;
            }
        }
        return haloRl;
    }

    /**
     * 绘光四芒闪点（folia stars）：点亮瞬间的星芒爆闪。四长芒 + 四短芒 +
     * 圆核的贴图 quad，画在字的上面一层；随包络尺寸微鼓、alpha 快起慢落。
     */
    @Override
    public void drawStar(float cx, float cy, float sizePx, float rotDeg, int argb) {
        if (poseStack == null || buffers == null || (argb >>> 24) == 0) return;
        ResourceLocation rl = starTexture();
        if (rl == null) return;
        float half = Math.max(1f, sizePx * 0.5f);

        // 贴图旋转（folia star.rotation）：绕星心转四角
        float cos = (float) Math.cos(Math.toRadians(rotDeg));
        float sin = (float) Math.sin(Math.toRadians(rotDeg));
        VertexConsumer vc = buffers.getBuffer(currentEffectType(rl));
        Matrix4f mat = poseStack.last().pose();
        // rot2 内联成标量（原先每角两次调用各 new float[2]，4 角 8 个分配）
        putHaloVertex(vc, mat, cx + (-half * cos + half * sin), cy + (-half * sin - half * cos), 0f, 0f, argb);
        putHaloVertex(vc, mat, cx + (-half * cos - half * sin), cy + (-half * sin + half * cos), 0f, 1f, argb);
        putHaloVertex(vc, mat, cx + (half * cos - half * sin), cy + (half * sin + half * cos), 1f, 1f, argb);
        putHaloVertex(vc, mat, cx + (half * cos + half * sin), cy + (half * sin - half * cos), 1f, 0f, argb);
        flushEffectBatch(rl);
    }

    /**
     * 星芒贴图：256×256 解析式四芒星（folia sprites 的精修版）——
     * 热核（锐高斯）+ 4 条长锥形射线（沿轴指数衰减 × 垂直高斯收腰、向尖端
     * 渐细）+ 4 条短对角芒；全部指数/高斯无多项式色带，LINEAR 下芒尖平滑
     * 收细。首绘惰性生成并注册，TrailTexture 同款强制 LINEAR；失败熔断只丢星芒。
     */
    private static ResourceLocation starTexture() {
        if (starError) return null;
        if (starRl == null) {
            try {
                int size = 256;
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, size, size, false);
                float half = size * 0.5f;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        float nx = (x + 0.5f - half) / half;
                        float ny = (y + 0.5f - half) / half;
                        float r2 = nx * nx + ny * ny;
                        // 热核：中心锐高斯亮点 + 柔和星体底光
                        float v = (float) Math.exp(-r2 * 22f)
                                + (float) Math.exp(-r2 * 4.5f) * 0.22f;
                        // 4 条长轴射线：沿轴指数衰减，垂直高斯收腰且向尖端渐细
                        float ax = Math.abs(nx);
                        float ay = Math.abs(ny);
                        float rayX = (float) Math.exp(-ax * 4.2f)
                                * (float) Math.exp(-(ny * ny) * (90f + 260f * ax));
                        float rayY = (float) Math.exp(-ay * 4.2f)
                                * (float) Math.exp(-(nx * nx) * (90f + 260f * ay));
                        v += (rayX + rayY) * 0.85f;
                        // 4 条短对角芒（45°，更短更细更淡）
                        float d45x = (nx + ny) * 0.7071f;
                        float d45y = (ny - nx) * 0.7071f;
                        float adx = Math.abs(d45x);
                        float ady = Math.abs(d45y);
                        float rayD = (float) Math.exp(-adx * 9f)
                                * (float) Math.exp(-(d45y * d45y) * (160f + 380f * adx))
                                + (float) Math.exp(-ady * 9f)
                                * (float) Math.exp(-(d45x * d45x) * (160f + 380f * ady));
                        v += rayD * 0.4f;
                        int a = Math.min(255, (int) (255 * Math.min(1f, v)));
                        img.setPixelRGBA(x, y, 0xFFFFFF | (a << 24));
                    }
                }
                DynamicTexture tex = new TrailTexture(img);
                // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath，只有公开构造器（javap 核对）。
                starRl = new ResourceLocation("muoniumplayer",
                        "dynamic/worldlyrics_star_" + (rlCounter++));
                Minecraft.getInstance().getTextureManager().register(starRl, tex);
            } catch (Throwable t) {
                starError = true;
                if (TRACE) System.out.println("[WorldLyrics/trace] 星芒纹理生成失败，闪点停用: " + t);
                return null;
            }
        }
        return starRl;
    }

    private static ResourceLocation starRl;
    private static boolean starError;

    /**
     * 绘光模式启用时的贴图预热：特效贴图惰性生成的首帧成本（star 256²
     * 约 5~15ms）会掉帧一次——绘光开启的第一帧提前生成，把卡顿移出歌内。
     * Bridge 帧头 settings.huiguang 为真时调用；幂等。
     */
    public static void prewarmTextures() {
        trailTexture();
        haloTexture();
        starTexture();
        streakTexture();
    }

    private static ResourceLocation haloRl;
    private static boolean haloError;

    /**
     * 绘光十字爆闪的一层（folia crossBurst 的竖/横光柱 glow+core）：条纹贴图
     * 沿条纹方向拉伸到 width、横向撑满 height，rotDeg 旋转（0=水平条纹、
     * 90=竖直条纹）。streak 贴图：128×32 横向白条，长轴高斯锐中段 + 纵向
     * 高斯柔边（glow/core 共用一张，靠高度拉伸与 alpha 区分）。
     */
    @Override
    public void drawBurstLayer(float cx, float cy, float width, float height,
                               float rotDeg, int argb) {
        if (poseStack == null || buffers == null || (argb >>> 24) == 0) return;
        ResourceLocation rl = streakTexture();
        if (rl == null) return;
        float cos = (float) Math.cos(Math.toRadians(rotDeg));
        float sin = (float) Math.sin(Math.toRadians(rotDeg));
        float hw = Math.max(0.5f, width * 0.5f);
        float hh = Math.max(0.5f, height * 0.5f);
        VertexConsumer vc = buffers.getBuffer(currentEffectType(rl));
        Matrix4f mat = poseStack.last().pose();
        // 四角 = 中心 ± 长轴半宽·轴向 ± 短轴半宽·法向（旋转由 cos/sin 合成）
        float axX = cos, axY = sin;
        float nX = -sin, nY = cos;
        putBurstVertex(vc, mat, cx - axX * hw - nX * hh, cy - axY * hw - nY * hh, 0f, 0f, argb);
        putBurstVertex(vc, mat, cx - axX * hw + nX * hh, cy - axY * hw + nY * hh, 0f, 1f, argb);
        putBurstVertex(vc, mat, cx + axX * hw + nX * hh, cy + axY * hw + nY * hh, 1f, 1f, argb);
        putBurstVertex(vc, mat, cx + axX * hw - nX * hh, cy + axY * hw - nY * hh, 1f, 0f, argb);
        flushEffectBatch(rl);
    }

    /** 爆闪条纹 quad 顶点（1.20.1 旧式顶点链，同 {@link #putHaloVertex} 口径）。 */
    private static void putBurstVertex(VertexConsumer vc, Matrix4f mat,
                                       float x, float y, float u, float v, int argb) {
        vc.vertex(mat, x, y, 0f)
                .color(argb)
                .uv(u, v)
                .uv2(LightTexture.FULL_BRIGHT & 0xFFFF,
                        (LightTexture.FULL_BRIGHT >> 16) & 0xFFFF)
                .endVertex();
    }

    /**
     * 光柱条纹贴图：128×32，长轴（u）高斯聚中 + 短轴（v）高斯柔边，白色。
     * 首绘惰性生成并注册，TrailTexture 同款强制 LINEAR；失败熔断只丢爆闪。
     */
    private static ResourceLocation streakTexture() {
        if (streakError) return null;
        if (streakRl == null) {
            try {
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, 128, 32, false);
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 128; x++) {
                        float u = (x + 0.5f) / 128f * 2f - 1f;
                        float v = (y + 0.5f) / 32f * 2f - 1f;
                        float a = (float) Math.exp(-u * u * 3.2f)
                                * (float) Math.exp(-v * v * 3.5f);
                        img.setPixelRGBA(x, y, 0xFFFFFF | ((int) (255 * a) << 24));
                    }
                }
                DynamicTexture tex = new TrailTexture(img);
                // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath，只有公开构造器（javap 核对）。
                streakRl = new ResourceLocation("muoniumplayer",
                        "dynamic/worldlyrics_streak_" + (rlCounter++));
                Minecraft.getInstance().getTextureManager().register(streakRl, tex);
            } catch (Throwable t) {
                streakError = true;
                if (TRACE) System.out.println("[WorldLyrics/trace] 光柱纹理生成失败，爆闪停用: " + t);
                return null;
            }
        }
        return streakRl;
    }

    private static ResourceLocation streakRl;
    private static boolean streakError;

    @Override
    public void drawRing(float cx, float cy, float sizePx, int argb) {
        if (poseStack == null || buffers == null || (argb >>> 24) == 0) return;
        ResourceLocation rl = ringTexture();
        if (rl == null) return;
        float half = Math.max(1f, sizePx * 0.5f);
        VertexConsumer vc = buffers.getBuffer(currentEffectType(rl));
        Matrix4f mat = poseStack.last().pose();
        putHaloVertex(vc, mat, cx - half, cy - half, 0f, 0f, argb);
        putHaloVertex(vc, mat, cx - half, cy + half, 0f, 1f, argb);
        putHaloVertex(vc, mat, cx + half, cy + half, 1f, 1f, argb);
        putHaloVertex(vc, mat, cx + half, cy - half, 1f, 0f, argb);
        flushEffectBatch(rl);
    }

    /** 冲击环贴图：128×128 白色圆环（folia bokeh 环的等价物）——
     *  环心 r=0.62、环宽半高斯 σ=0.05，外缘/内缘平滑收零；LINEAR 下无台阶。 */
    private static ResourceLocation ringTexture() {
        if (ringError) return null;
        if (ringRl == null) {
            try {
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, 128, 128, false);
                for (int y = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++) {
                        float dx = (x + 0.5f) / 64f - 1f;
                        float dy = (y + 0.5f) / 64f - 1f;
                        float r = (float) Math.sqrt(dx * dx + dy * dy);
                        float band = (float) Math.exp(-((r - 0.62f) * (r - 0.62f)) / (2f * 0.05f * 0.05f));
                        img.setPixelRGBA(x, y, 0xFFFFFF | ((int) (255 * band) << 24));
                    }
                }
                DynamicTexture tex = new TrailTexture(img);
                // 1.20.1 没有 ResourceLocation.fromNamespaceAndPath，只有公开构造器（javap 核对）。
                ringRl = new ResourceLocation("muoniumplayer",
                        "dynamic/worldlyrics_ring_" + (rlCounter++));
                Minecraft.getInstance().getTextureManager().register(ringRl, tex);
            } catch (Throwable t) {
                ringError = true;
                if (TRACE) System.out.println("[WorldLyrics/trace] 冲击环纹理生成失败，环停用: " + t);
                return null;
            }
        }
        return ringRl;
    }

    private static ResourceLocation ringRl;
    private static boolean ringError;

    /**
     * 特效贴图（拖尾渐变带/光晕/星芒/条纹/环/拖尾画布）：构造时生成一次 mip 链，
     * 绑定过滤强制三线性 —— 光晕/星芒按 1.7~2.4 字号（17~24 设计像素）上屏而
     * 贴图 128²/256²，正常视距下缩小 5~7 倍，双线性会随移动闪烁（高频边缘欠
     * 采样）；三线性把各级预均摊，光斑移动平滑无噪点。与 1.21.1 标杆同款配方
     * （见 {@link #setupMipChain}）。
     */
    private static final class TrailTexture extends DynamicTexture {
        /** 本贴图的 mip 顶层数（floor(log2(min(w,h)))）；-1 = 已熔断纯 LINEAR。 */
        final int mipTop;

        TrailTexture(NativeImage img) {
            super(img);
            bind();
            this.mipTop = setupMipChain(img != null ? img.getWidth() : 0,
                    img != null ? img.getHeight() : 0);
        }

        @Override
        public void setFilter(boolean blur, boolean mipmap) {
            super.setFilter(true, mipTop > 0);
        }
    }

    /**
     * 安全 mip 链（与 LyricGlyphAtlasBridge.PageTexture 同配方）：解钳
     * MAX_LEVEL/MAX_LOD（prepareImage 钉死 0，MAX_LOD 不解开则 LOD 恒 0、
     * 三线性静默退化双线性）→ glGenerateMipmap 自管扩级 → 读回自检，失败
     * 返回 -1（setFilter 据此永久落纯 LINEAR，绝不产生不完整采样）。
     * 调用前提：本纹理当前已绑定。
     */
    private static int setupMipChain(int width, int height) {
        try {
            int top = 31 - Integer.numberOfLeadingZeros(Math.min(width, height));
            if (top < 1) return -1;
            com.muoniumplayer.compat.gl.GL11.glTexParameteri(
                    com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL, top);
            com.muoniumplayer.compat.gl.GL11.glTexParameteri(
                    com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL12.GL_TEXTURE_MIN_LOD, 0);
            com.muoniumplayer.compat.gl.GL11.glTexParameterf(
                    com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL13.GL_TEXTURE_MAX_LOD, (float) top);
            org.lwjgl.opengl.GL30.glGenerateMipmap(
                    com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D);
            int tailW = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(
                    com.muoniumplayer.compat.gl.GL11.GL_TEXTURE_2D, top,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
            int err = org.lwjgl.opengl.GL11.glGetError();
            if (err != 0 || tailW != Math.max(1, width >> top)) {
                WorldLyricsBridge.traceOnce("特效贴图 mip 链自检失败（err=0x"
                        + Integer.toHexString(err) + " tailW=" + tailW + "），熔断回纯 LINEAR");
                return -1;
            }
            return top;
        } catch (Throwable t) {
            WorldLyricsBridge.traceOnce("特效贴图 mip 链生成异常，熔断回纯 LINEAR: " + t);
            return -1;
        }
    }

    /**
     * 歌词所在环境的感知亮度 0..1：白天 1、深夜 0（天空暗度），洞窟/夜晚持火把
     * 等场景取公告牌处方块光兜底。绘光的未唱字用它调制「冷暗档 ↔ 自发光冰蓝」。
     *
     * <p>1.20.1 的 API 与 1.21.1 同名同参（javap loom-cache 核对）：
     * {@code BlockAndTintGetter.getRawBrightness(BlockPos, int)}（ClientLevel 经
     * Level/LevelReader 继承到）、{@code BlockPos.containing(double, double, double)}、
     * {@code Minecraft.level} 字段 —— 实现与 1.21.1 逐字相同。</p>
     */
    @Override
    public float worldLightLevel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return 1f;
        // 感知亮度 0..15（方块光 ∪ 减去天色暗度后的天空光，正午地表 15、
        // 满月地表 4、无光源洞窟 0）—— 一个指标同时覆盖昼夜与洞窟/火把
        int raw = mc.level.getRawBrightness(
                net.minecraft.core.BlockPos.containing(camX, camY, camZ), 0);
        return Math.max(0f, Math.min(1f, raw / 15f));
    }

    @Override
    public float pixelWidth(String text) {
        Minecraft mc = Minecraft.getInstance();
        return (mc == null || mc.font == null || text == null) ? 0f : mc.font.width(text);
    }

    @Override
    public float pixelCharWidth(char c) {
        Minecraft mc = Minecraft.getInstance();
        return (mc == null || mc.font == null) ? 0f : mc.font.width(String.valueOf(c));
    }

    /**
     * 现代档的单字宽：字形图集的烘焙 advance（与烘焙字体同源、随字体切换走）。
     * 图集不可用时退像素度量 —— 量宽与画字永远同一条代码路径，行内不漂移。
     */
    @Override
    public float smoothCharWidth(char c, float sizePx) {
        float adv = LyricGlyphAtlas.designAdvance(c);
        if (adv > 0f) return adv;
        return pixelCharWidth(c);
    }
}
