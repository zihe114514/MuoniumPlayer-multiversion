package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.muoniumplayer.core.lyrics3d.HuiguangMotion;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;
import com.muoniumplayer.core.rendering.font.TextGlow;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import com.muoniumplayer.mixin.RenderTypeAccessors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;

/**
 * 3D 世界歌词的宿主绘制实现 —— <b>公告牌矩阵、像素字与逐字平滑字形三条通路</b>。
 *
 * <h2>26.3 的世界渲染是 submit-collect，不再是「当场画」</h2>
 * 1.21.1 里本类拿到 {@code MultiBufferSource.BufferSource} 直接 {@code drawInBatch}。
 * 26.3 把世界实体渲染改成了 submit-collect（{@code LevelRenderer.submitFeatures} 收集、
 * {@code FeatureRenderDispatcher.prepareFrame/execute*} 稍后按阶段批量绘制），即时取
 * buffer 画字的 {@code MultiBufferSource} 世界通路已经不存在。等价做法是原版名字牌同款：
 * {@code font.prepareText(...)} 造出逐字形的 {@code TextRenderable}，再
 * {@code submitCustomGeometry(poseStack, renderable.renderType(SEE_THROUGH), ...)}
 * 交给原版的 {@code CustomFeatureRenderer} 稍后一次性发射顶点 —— 顶点发射路径
 * （{@code TextRenderable.render(Matrix4fc, VertexConsumer, light, false)}）与
 * {@code TextFeatureRenderer.GlyphRenderer.acceptRenderable} 逐字节相同。
 *
 * <h2>通路与口径</h2>
 * <ul>
 *   <li>{@code ModernWorld.push} → {@link #pushBillboard}：translate(rx,ry,rz) →
 *       rotateY(-yaw) → rotateX(pitch) → scale(-s,-s,s)。之后画的是「文字像素坐标」，
 *       MC 字体坐标口径（x 右、y 下、字顶为 y）。</li>
 *   <li>{@code ModernGfx.vanillaText} → {@link #drawPixelText}：阴影按原版规则拆开
 *       单独画在字前（原版把阴影和字放同一批会按远近重排，歌词的阴影和字几乎一样远，
 *       排序随视角抖动）。26.3 里这个拆分由 {@code Font.PreparedTextBuilder} 自带：
 *       {@code dropShadow=true} 时每个字形实例先发阴影四边形（阴影色 =
 *       {@code ARGB.scaleRGB(color, 0.25f)}，与 1.21.1 手算的
 *       {@code (argb & 0xFCFCFC) >> 2} 完全同值）再发本体，仍然一批一次提交。</li>
 *   <li>{@code SEE_THROUGH + FULL_BRIGHT}：{@link Font.DisplayMode#SEE_THROUGH} 走
 *       {@code RenderPipelines.TEXT_SEE_THROUGH}（javap 实测：无深度模板状态 =
 *       深度测试整个关闭、透明混合），1.21.1 同款语义；光值传
 *       {@code 0xF000F0}（26.3 的 {@code LightTexture.FULL_BRIGHT} 常量已随类删除，
 *       实体渲染器里裸写 {@code 15728880} 是原版现役做法，如 {@code GuardianRenderer}）。</li>
 *   <li>世界渲染期间的 GL 状态（关深度测试、关剔除、透明混合）不再手工设：
 *       submit-collect 下状态归 RenderType/pipeline 所有，1.21.1 的
 *       {@code WorldLyricsBridge.GlCompat} 在 26.3 没有对应物也没有必要。</li>
 * </ul>
 *
 * <h2>现代化渲染通道（与 1.21.1 的 drawSmoothGlyph 同构）</h2>
 * <p>查 {@link LyricGlyphAtlas}（Skija 5× 超采样字形 + TextGlow 三层高斯发光烘在
 * 同一张格里），纹理 quad 走 26.3 的公开几何提交口
 * {@code SubmitNodeCollector.submitCustomGeometry(poseStack, renderType, 回调)}
 * （javap：{@code CustomGeometryRenderer.render(PoseStack$Pose, VertexConsumer)}，
 * 与像素档的 {@code PreparedText.visit} 完全同一条口），renderType =
 * {@code RenderTypes.textSeeThrough(页 Identifier)}（1.21.1 的
 * {@code RenderType.textSeeThrough(rl)} 在 26.x 挪进了 {@code RenderTypes}）。
 * 回调里发一个 POSITION_COLOR_TEX_LIGHTMAP 四顶点 quad —— 与上游的逐字
 * textured quad 同构，wiggle 的旋转/缩放绕字中心落进顶点矩阵；页纹理的采样器被
 * PageTexture 强制成 LINEAR（见 LyricGlyphAtlasBridge），超采样不被 NEAREST 采样毁掉。
 * 坐标是相机相对（与像素路径同基准），ModelViewMat 由 RenderType.prepare 在采集期捕获，
 * GPU 端 = 视图旋转 × 根矩阵 × billboard。FULL_BRIGHT 拆两坐标与 1.21.1 相同
 * （26.3 没有 LightCoordsUtil 之外的光照拆包口，低半 = 方块光、高半 = 天空光）。</p>
 *
 * <p>格子不可用（图集未装配/Skija 缺位/字符不可见）时回退像素字 —— 绝不吞字。</p>
 *
 * <h2>提交相位：永远画在最上层（云/玻璃盖字的修复）</h2>
 * <p>两条通道的 submit 都走 {@link LyricOnTopSubmitter} 进 {@code alwaysOnTop}
 * 相位 —— {@code translucentCustomGeometry} 相位在半透明地形（玻璃）和云
 * <b>之前</b>执行，玻璃和云会直接混叠盖字；alwaysOnTop 相位由
 * {@code LevelRenderer.executeAlwaysOnTop} 在 main pass 的最后执行（云也在其前），
 * 且执行前清深度（远平面 = 无深度测试），歌词永远可见。submit 节点仍用原版
 * {@code CustomFeatureRenderer.Submit} record，特征渲染器与顶点发射路径不变。</p>
 */
public final class WorldBillboard implements WorldLyricsRenderer.Host {

    /** 一次性诊断开关。 */
    static final boolean TRACE = Boolean.getBoolean("muonium.debug.worldLyrics");

    /**
     * 字形阴影的右下偏移（设计像素）。游戏实测（浅色天空背景）(2,3) 在加色晕的
     * 垫底下读作「重影」—— 收到 (1,1.5)：紧贴字缘的暗边，立体可察而不脱形。
     */
    static final float SHADOW_OFFSET_PX = 0.5f;
    /** 阴影的 Y 偏移（设计像素）：略沉于 X，光照习惯上来自上方。 */
    static final float SHADOW_OFFSET_Y_PX = 1f;
    /**
     * 阴影强度（顶点 alpha 系数）。字色×0.25 的「原版口径」在加色晕垫底下仍是
     * 一团实色（重影感的另一半）—— 纯黑 ×0.22：浅底上是轻描的暗边、深底上
     * 融进背景，任何背景下都只是「一点点」（游戏内三轮收敛定案）。
     */
    static final float SHADOW_STRENGTH = 0.22f;

    /** 全亮度光值（1.21.1 的 {@code LightTexture.FULL_BRIGHT}）。26.3 该常量类已删除，
     * 值 15728880（0xF000F0）在原版实体渲染器中照旧裸写。
     */
    private static final int FULL_BRIGHT = 15728880;
    private static final java.util.ArrayList<Identifier> retiredTextures = new java.util.ArrayList<>();

    /** 每帧共享实例。 */
    public static final WorldBillboard INSTANCE = new WorldBillboard();

    private PoseStack poseStack;
    private SubmitNodeCollector collector;
    private double camX, camY, camZ;
    private boolean tracedOnce;
    private boolean tracedSmooth;

    private WorldBillboard() {
    }

    // ==================== 光影包适配：渲染类型单点分发 ====================

    /**
     * 光影包激活时的渲染类型：原版信标光柱工厂（beaconBeam）自带全亮管线
     * （BEACON_BEAM_TRANSLUCENT —— 不采样光照图、无 alpha 裁剪、半透明混合），
     * 纹理直接换成我们的动态图集。每纹理懒建并缓存。
     * 布尔参数 true = translucent 变体（false 是 opaque 信标柱）。
     * 挂起合批模式不存在（26.x 是 submit-collect，无延迟批次），帧间模式翻转天然安全。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Identifier, RenderType> SHADER_TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static RenderType shaderTextType(Identifier rl) {
        return SHADER_TYPES.computeIfAbsent(rl, r ->
                RenderTypes.beaconBeam(r, true));
    }

    /**
     * 晕格通道的<b>加色</b>渲染类型：光晕以 srcAlpha,one 叠加 —— 只增不减，
     * 亮背景上光晕融入、暗背景上浮出，永远不会像 alpha 混合那样把背景压成
     * 灰雾（「真发光不糊」的混合模型，见 TextGlow.drawGlowOn）。
     *
     * <p>26.3 的 RenderType.create 是包私有 —— 照抄原版 beaconBeam(rl,true) 的
     * 组装（javap 核对）：自建 RenderPipeline（BEACON_BEAM_SNIPPET 同内容 +
     * BlendFunction.LIGHTNING 换混合）→ RenderSetup.builder(pipeline)
     * .withTexture("Sampler0", rl).sortOnUpload() → RenderType.create。
     * 光晕属瞬时发光体，不吃光照图、不进 OIT（与 beaconBeam translucent 同判）。
     * 布尔参数 false 是 opaque 信标柱，我们恒走 translucent 形态（BEAM_TRANSLUCENT
     * 的 DepthStencilState.GREATER_THAN_OR_EQUAL、写色不写深）。</p>
     */
    /**
     * PipelineCache 按 RenderPipeline 对象身份缓存编译结果；管线本身不含页纹理，
     * 必须在所有图集页之间共享，避免每个新页都在渲染线程触发一次阻塞编译。
     */
    private static final com.mojang.renderpearl.api.pipeline.RenderPipeline LYRIC_GLOW_PIPELINE =
            com.mojang.renderpearl.api.pipeline.RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("muonium", "lyric_glow"))
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.GLOBALS)
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.PROJECTION)
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.DYNAMIC_TRANSFORMS)
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.FOG)
                    .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0)
                    .withVertexShader("core/rendertype_beacon_beam")
                    .withFragmentShader("core/rendertype_beacon_beam")
                    .withVertexBinding(0, com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK)
                    .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS)
                    .withColorTargetState(new com.mojang.renderpearl.api.pipeline.ColorTargetState(
                            com.mojang.renderpearl.api.pipeline.BlendFunction.LIGHTNING))
                    .withDepthStencilState(new com.mojang.renderpearl.api.pipeline.DepthStencilState(
                            com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .build();

    private static final java.util.concurrent.ConcurrentHashMap<Identifier, RenderType> GLOW_TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static RenderType glowType(Identifier rl) {
        return GLOW_TYPES.computeIfAbsent(rl, r ->
                RenderTypeAccessors.muonium$create("muonium_lyric_glow",
                        net.minecraft.client.renderer.rendertype.RenderSetup.builder(LYRIC_GLOW_PIPELINE)
                                .withTexture("Sampler0", r)
                                .sortOnUpload()
                                .createRenderSetup()));
    }

    /**
     * 歌词世界绘制的渲染类型入口 —— 全部字形/特效 quad 的唯一取发点。
     * 光影包激活（ShaderPackProbe 本帧值）走信标全亮程序，否则原版 textSeeThrough。
     * refresh() 在帧头调用，帧中读的是本帧缓存值，同一帧内模式恒定。
     */
    private static RenderType currentWorldTextType(Identifier rl) {
        return ShaderPackProbe.isActive() ? shaderTextType(rl)
                : RenderTypes.textSeeThrough(rl);
    }

    private static void releaseRetiredTextures() {
        if (retiredTextures.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        for (Identifier rl : retiredTextures) {
            mc.getTextureManager().release(rl);
        }
        retiredTextures.clear();
    }

    /** 事件入口（worldRenderEnd 的矩阵在 Bridge 里已设好）。 */
    public void begin(PoseStack poseStack, SubmitNodeCollector collector,
                      double camX, double camY, double camZ) {
        this.poseStack = poseStack;
        this.collector = collector;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        releaseRetiredTextures();
        // 「更激进的适配」采集模式：光影激活 + 开关开 → 本帧不 submit，
        // 平滑字形/特效改记进 present 兜底缓冲（WorldLyricsHudDraw 类注释）。
        // 像素字路径不进采集（原版名牌同路径，光影下本就正常）。
        this.captureMode = WorldLyricsHudDraw.captureModeThisFrame();
        if (captureMode) {
            LyricPresentRenderer.beginFrame();
        }
    }

    /** 本帧是否在采集（capture 模式下 submit 全部旁路）。 */
    private boolean captureMode;

    public void end() {
        if (captureMode) {
            boolean got = LyricPresentRenderer.endFrame();
            WorldLyricsHudDraw.onCaptureEnd(got);
            captureMode = false;
        }
        this.poseStack = null;
        this.collector = null;
    }

    @Override
    public void pushBillboard(double rx, double ry, double rz,
                              double yawDeg, double pitchDeg, float scale) {
        if (poseStack == null) return;
        // 公告牌矩阵 push（上游 ModernWorld.push 口径）：
        //   translate(rx,ry,rz) → rotateY(-yaw) → rotateX(pitch) → scale(-s,-s,s)
        poseStack.pushPose();
        PoseStack.Pose pose = poseStack.last();
        pose.pose().translate((float) rx, (float) ry, (float) rz);
        pose.pose().rotateY((float) -Math.toRadians(yawDeg));
        pose.pose().rotateX((float) Math.toRadians(pitchDeg));
        pose.pose().scale(-scale, -scale, scale);
    }

    @Override
    public void popBillboard() {
        if (poseStack == null) return;
        poseStack.popPose();
    }

    // ==================== 行级光晕垫底 ====================

    /**
     * 行光晕纹理池。同一帧里可能有活跃行 + 若干淡出行同时要垫光晕，而 26.x 的
     * 几何提交是<b>延迟展开</b>的 —— 若共用一张纹理，后一行的像素会覆盖先提交
     * quad 的内容，光晕整块串到别的行上。按槽位各持一张，槽位按活跃行序号轮转。
     */
    private static final int GLOW_SLOTS = 4;
    private static final Identifier[] glowRl = new Identifier[GLOW_SLOTS];
    private static final TrailTexture[] glowTex = new TrailTexture[GLOW_SLOTS];
    private static final int[] glowW = new int[GLOW_SLOTS];
    private static final int[] glowH = new int[GLOW_SLOTS];
    private static final String[] glowKey = new String[GLOW_SLOTS];
    private static boolean glowError;

    /** 光晕 sprite 的设计像素尺寸（padPx 外扩后），对位 quad 用。 */
    private static float glowPlaneW, glowPlaneH, glowPad;

    @Override
    public void drawGlowBackdrop(String text, float widthPx,
                                 int glowColor, float alpha) {
        drawGlowBackdrop(text, widthPx, glowColor, alpha, 0);
    }

    @Override
    public void drawGlowBackdrop(String text, float widthPx,
                                 int glowColor, float alpha, int slot) {
        if (poseStack == null || collector == null || glowError
                || text == null || text.isEmpty()) return;
        com.muoniumplayer.core.lyrics3d.WorldLyricsSettings settings =
                WorldLyricsBridge.currentSettings;
        float strength = settings != null ? settings.glowStrength : 0f;
        float spread = settings != null ? settings.glowSpread : 1f;
        String key = text + "|" + Integer.toHexString(glowColor & 0xFFFFFF)
                + "|" + Math.round(strength * 64f) + "|" + Math.round(spread * 16f)
                + "|" + LyricGlyphAtlas.fontGeneration();

        // 槽位由渲染层给（同 trailCanvas 口径）：延迟提交下每行必须
        // 有独立纹理，否则后一行像素会盖掉先提交 quad 的内容。
        int s = slot % GLOW_SLOTS;
        int[] px = com.muoniumplayer.core.lyrics3d.RowGlowSprite.bake(
                text, glowColor, strength, spread);
        if (px == null) return;
        glowPad = com.muoniumplayer.core.lyrics3d.RowGlowSprite.padPx(strength, spread);
        int w = com.muoniumplayer.core.lyrics3d.RowGlowSprite.width();
        int h = com.muoniumplayer.core.lyrics3d.RowGlowSprite.height();
        if (w <= 0 || h <= 0 || px.length < w * h) return;
        try {
            // 纹理按容量分配（宽高向上取 2 的幂、封顶上限）：卡拉OK逐字点亮中
            // 行宽逐帧增长，若按精确尺寸建纹理就会每帧 release+重建 —— 延迟提交
            // 下更早提交的 quad 还握着旧 rl，纹理已删，绘制读空 → 黑紫方块闪帧。
            // 容量余量让「行变长」落在已有纹理内，只重传像素，重建只发生在
            // 跨容量级（罕见）。
            int capW = nextPow2(w), capH = nextPow2(h);
            TrailTexture tex = glowTex[s];
            if (tex == null || glowW[s] < w || glowH[s] < h) {
                if (glowRl[s] != null) {
                    retiredTextures.add(glowRl[s]);
                    glowRl[s] = null;
                    glowTex[s] = null;
                }
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, capW, capH, false);
                tex = new TrailTexture(img);
                Identifier rl = Identifier.fromNamespaceAndPath("muoniumplayer",
                        "dynamic/worldlyrics_rowglow_" + s + "_" + (rlCounter++));
                Minecraft.getInstance().getTextureManager().register(rl, tex);
                glowRl[s] = rl;
                glowTex[s] = tex;
                glowW[s] = capW;
                glowH[s] = capH;
                glowKey[s] = null;
            }
            if (!key.equals(glowKey[s])) {
                // 内容变了才重传；只灌 w×h 实际区域（纹理尾部容量区透明，UV 不采到）
                tex.uploadSub(px, w, h);
                glowKey[s] = key;
            }
        } catch (Throwable t) {
            glowError = true;
            if (TRACE) System.out.println("[WorldLyrics/trace] 行光晕纹理失败: " + t);
            return;
        }

        // 对位：sprite 位图含 pad 外扩（设计像素），quad 覆盖「pad 外扩后的行 bbox」，
        // 中心原点坐标系（公告牌平面）。文字宽用 sprite 自量（bakeFont 口径）——
        // 与晕逐像素对上；公告牌平面中心对齐由调用方保证（渲染层按行居中排版）。
        // UV 按实际内容 w×h 归一（纹理是容量尺寸，尾部区域透明不采到）。
        float textW = com.muoniumplayer.core.lyrics3d.RowGlowSprite.textWidth();
        float planeW = textW + glowPad * 2f;
        float planeH = com.muoniumplayer.core.lyrics3d.WorldLyricsState.LINE_H + glowPad * 2f;
        glowPlaneW = planeW;
        glowPlaneH = planeH;
        float x0 = -(textW * 0.5f) - glowPad;
        float y0 = -(planeH * 0.5f);
        float uMax = (float) w / glowW[s];
        float vMax = (float) h / glowH[s];
        int argb = ((int) (Math.min(1f, alpha) * 255f) << 24) | (glowColor & 0xFFFFFF);
        final Identifier rl = glowRl[s];

        if (captureMode) {
            captureEffectQuad(rl,
                    x0, y0, 0f, 0f,
                    x0, y0 + planeH, 0f, vMax,
                    x0 + planeW, y0 + planeH, uMax, vMax,
                    x0 + planeW, y0, uMax, 0f,
                    argb);
            return;
        }

        RenderType renderType = currentWorldTextType(rl);
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                putHaloVertex(vc, mat, x0, y0, 0f, 0f, argb);
                putHaloVertex(vc, mat, x0, y0 + planeH, 0f, vMax, argb);
                putHaloVertex(vc, mat, x0 + planeW, y0 + planeH, uMax, vMax, argb);
                putHaloVertex(vc, mat, x0 + planeW, y0, uMax, 0f, argb);
            });
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    public void drawPixelText(String text, float x, float y, int argb, boolean shadow) {
        if (poseStack == null || collector == null || text == null || text.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        // 原版文字阴影拆分策略：阴影拆开画在字前 —— 26.3 由 prepareText 的
        // dropShadow 开关承担（阴影四边形在字形本体之前发射，颜色 = color×0.25）。
        Font.PreparedText prepared = mc.font.prepareText(text, x, y, argb, shadow, 0);
        // 按原版造字路径取回每个 TextRenderable，用 1.21.1 时代
        // drawInBatch(SEE_THROUGH, FULL_BRIGHT) 的同款参数交给 CustomFeatureRenderer
        // 逐个提交（glyph 渲染入口与 TextFeatureRenderer.GlyphRenderer.acceptRenderable
        // 完全一致：renderable.render(pose, consumer, FULL_BRIGHT, false)）。
        prepared.visit(new Font.GlyphVisitor() {
            @Override
            public void acceptGlyph(TextRenderable.Styled renderable) {
                RenderType renderType = renderable.renderType(Font.DisplayMode.SEE_THROUGH);
                LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, renderType, (pose, consumer) ->
                        renderable.render(pose.pose(), consumer, FULL_BRIGHT, false));
            }
        });
        if (TRACE && !tracedOnce) {
            tracedOnce = true;
            System.out.println("[WorldLyrics/trace] 原版像素字已提交 submit: \"" + text + "\"");
        }
    }

    /**
     * 现代化风格单字：字形图集取格（Skija 5× 超采样 + TextGlow 发光烘焙），
     * submitCustomGeometry + textSeeThrough(页纹理) 发一个四顶点 quad。
     *
     * <p>坐标口径：传进来的 (x, y) 是字形<b>顶点</b>（字位框左上角，设计像素），
     * 对应格内 (inkX, inkY)。quad 覆盖整格（含发光留白），尺寸 = 格框 ÷ BAKE_SCALE。
     * wiggle 四量绕<b>字中心</b>（半个 advance × 半个字位框）旋转/缩放 —— 与上游
     * withWiggle 的 translate(cx,cy)→rotate→scale→translate(-cx,-cy) 同构；dx/dy 已由
     * 渲染器加进 x/y。回调拿到的 Pose 已含 billboard 矩阵（submitCustomGeometry 拷走
     * 提交时的 poseStack 栈顶），四角直接在该矩阵下展开。</p>
     */
    @Override
    public void drawSmoothGlyph(char c, float x, float y, float sizePx,
                                int rgb, float alpha, float rot, float dx, float dy, float sc,
                                float glowMul, boolean glowOn, int glowColorRgb, int inkRgb) {
        if (poseStack == null || collector == null) return;
        // 发光参数取本帧快照（Bridge 在帧头装配好；没装配时按无发光查格）
        com.muoniumplayer.core.lyrics3d.WorldLyricsSettings settings =
                WorldLyricsBridge.currentSettings;
        float glowStrength = settings != null ? settings.glowStrength : 0f;
        float glowSpread = settings != null ? settings.glowSpread : 1f;
        // 心象的发光脉冲：正在唱的块满档、其余块压档 —— 查格前乘上去（缓存键已量化）。
        glowStrength *= glowMul;
        boolean glowActive = glowOn && glowStrength > 0.004f;

        // 墨格：无发光留白（晕在独立晕格，墨格回归防裁切最小 pad）
        LyricGlyphAtlas.GlyphCell cell = LyricGlyphAtlas.getInkCell(c, rgb & 0xFFFFFF);
        if (cell == null) {
            // 绘光模式下「预算内暂时没烘出来」不回退像素字 —— 那正是用户看到的
            // 「有时退回原版渲染风格」：字形瞬闪像素风再换回烘焙字形，非常扎眼。
            // 图集可用时静默跳过该字（下一帧补齐，烘焙预算 4 格/帧）；
            // 图集整体不可用（host 缺失/字体缺位）仍回退像素字，绝不吞字。
            if (settings != null && settings.huiguang && LyricGlyphAtlas.isFunctional()) {
                return;
            }
            // 图集不可用/超尺寸：像素字兜底（带阴影 —— 与平滑档的立体感语义一致）
            int argb = ((int) (alpha * 255f) << 24) | (rgb & 0xFFFFFF);
            drawPixelText(String.valueOf(c), x, y, argb, true);
            return;
        }
        if (!(cell.page instanceof LyricGlyphAtlasBridge.PageHandle)) {
            return;
        }
        net.minecraft.resources.Identifier inkRl =
                ((LyricGlyphAtlasBridge.PageHandle) cell.page).resourceLocation();

        // 晕格：独立烘焙（只含高斯光形）。晕格未就绪（预算/页满）只丢晕不丢字。
        LyricGlyphAtlas.GlyphCell glowCell = glowActive
                ? LyricGlyphAtlas.getGlowCell(c, glowStrength, glowSpread,
                        glowColorRgb & 0xFFFFFF, rgb & 0xFFFFFF,
                        settings != null ? settings.glowTier : TextGlow.GLOW_TIER_DEFAULT)
                : null;
        net.minecraft.resources.Identifier glowRl = null;
        if (glowCell != null && glowCell.page instanceof LyricGlyphAtlasBridge.PageHandle) {
            glowRl = ((LyricGlyphAtlasBridge.PageHandle) glowCell.page).resourceLocation();
        }

        // 烘焙像素 → 设计像素。字形顶点 (x, y) 是「字位框左上角」在<b>行内坐标</b>里的位置
        // （buildGlyphs 逐字推进的坐标），格内墨迹原点 (inkX, inkY) 对到它 —— quad 四角
        // 晕/墨两格烘焙域不同（晕格 HDR bloom 在 3× 低频域、墨格 5× 超采样），
        // 各按自己的 cell.scale 折算，绝不能共用一个除数。
        float inv = 1f / cell.scale;
        float qLeft = x - cell.inkX * inv;
        float qTop = y - cell.inkY * inv;
        float qRight = qLeft + cell.w * inv;
        float qBottom = qTop + cell.h * inv;

        float pageF = LyricGlyphAtlas.PAGE_SIZE;
        float halfTexel = 0.5f / pageF;

        // 绕字中心（行内坐标：半个 advance × 半个字位框）的旋转/缩放（上游 withWiggle 同构）。
        // 旋转中心按墨格 advance 计算；晕格以同一 (x,y) 锚点展开，pad 差向外扩 —— 晕形
        // 与字形天然像素对齐（两格的字位框起点同为 (pad,pad) 口径的 (x,y) 处）。
        float cx = x + (cell.advance * inv) * 0.5f;
        float cy = y + (cell.inkHeight * inv) * 0.5f;
        boolean transformed = rot != 0f || sc != 1f;
        float cos = transformed ? (float) Math.cos(Math.toRadians(rot)) : 1f;
        float sin = transformed ? (float) Math.sin(Math.toRadians(rot)) : 0f;
        float s = transformed ? sc : 1f;

        int argb = (((int) (alpha * 255f) & 0xFF) << 24) | (rgb & 0xFFFFFF);

        // 一点点立体感：墨格同格重画一遍、右下偏移、半透明黑压暗。
        // 不新烘格 —— 同一张墨格页纹理，UV 相同；只多一个 quad 的开销。
        final float shOff = SHADOW_OFFSET_PX;
        final int shadowA = (int) (((argb >>> 24) & 0xFF) * SHADOW_STRENGTH);
        final int shadowArgb = (shadowA << 24);   // 纯黑 × 进出场 alpha × 0.35
        final float shLeft = qLeft + shOff, shTop = qTop + SHADOW_OFFSET_Y_PX;
        final float shRight = qRight + shOff, shBottom = qBottom + SHADOW_OFFSET_Y_PX;

        // 晕格 quad（晕格 pad ≥ 墨格 pad，锚点对齐后 quad 更大、盖过墨格）。
        // lambda 捕获要求：一次赋值后不再改写（glowCell 为 null 时保持零值不进 lambda）
        final float gLeft, gTop, gRight, gBottom;
        final float gu0, gv0, gu1, gv1;
        final int glowArgb;
        if (glowRl != null) {
            float gInv = 1f / glowCell.scale;
            gLeft = x - glowCell.inkX * gInv;
            gTop = y - glowCell.inkY * gInv;
            gRight = gLeft + glowCell.w * gInv;
            gBottom = gTop + glowCell.h * gInv;
            gu0 = glowCell.x / pageF + halfTexel;
            gv0 = glowCell.y / pageF + halfTexel;
            gu1 = (glowCell.x + glowCell.w) / pageF - halfTexel;
            gv1 = (glowCell.y + glowCell.h) / pageF - halfTexel;
            // 晕的顶点 alpha 用墨格同一进出场 alpha —— 晕格能量已按 strength 烘进像素
            glowArgb = argb & 0x00FFFFFF | (((int) (alpha * 255f) & 0xFF) << 24);
        } else {
            gLeft = gTop = gRight = gBottom = 0f;
            gu0 = gv0 = gu1 = gv1 = 0f;
            glowArgb = 0;
        }

        // ---- 更激进的适配：采集模式 —— 不 submit，把 quad 记进 present 兜底缓冲 ----
        if (captureMode) {
            // 阴影 → 晕 → 墨（阴影与晕同为整格 quad，先后无遮挡语义，压暗段在墨前即可）
            captureGlyphQuad(inkRl, shLeft, shTop, shRight, shBottom, cx, cy, cos, sin, s,
                    shadowArgb, cell.x / pageF + halfTexel, cell.y / pageF + halfTexel,
                    (cell.x + cell.w) / pageF - halfTexel, (cell.y + cell.h) / pageF - halfTexel,
                    false);
            if (glowRl != null) {
                captureGlyphQuad(glowRl, gLeft, gTop, gRight, gBottom, cx, cy, cos, sin, s,
                        glowArgb, gu0, gv0, gu1, gv1, true);
            }
            captureGlyphQuad(inkRl, qLeft, qTop, qRight, qBottom, cx, cy, cos, sin, s,
                    argb, cell.x / pageF + halfTexel, cell.y / pageF + halfTexel,
                    (cell.x + cell.w) / pageF - halfTexel, (cell.y + cell.h) / pageF - halfTexel,
                    false);
            if (TRACE && !tracedSmooth) {
                tracedSmooth = true;
                System.out.println("[WorldLyrics/trace] 现代档字形 quad 已采集（present 兜底）: '" + c
                        + "' cell=" + cell.w + "x" + cell.h + (glowRl != null ? " +glow" : ""));
            }
            return;
        }
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开）。
        // 晕先（加色 RenderType，srcAlpha,one）、墨后 —— 不同 RenderType 天然分批，
        // 无需同批内的顺序保证。
        poseStack.pushPose();
        try {
            // 阴影先（普通 alpha 段、同页纹理压暗色）→ 晕（加色）→ 墨。
            // 阴影压在晕上：加色不可交换，晕能量 × 阴影 alpha(≤0.22) 的重叠带
            // 二阶差可忽略；墨最后 —— 立体感垫层的三层序。
            RenderType shadowRt = currentWorldTextType(inkRl);
            LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, shadowRt, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                putGlyphVertex(vc, mat, shLeft, shTop, cx, cy, cos, sin, s, shadowArgb,
                        cell.x / pageF + halfTexel, cell.y / pageF + halfTexel);
                putGlyphVertex(vc, mat, shLeft, shBottom, cx, cy, cos, sin, s, shadowArgb,
                        cell.x / pageF + halfTexel, (cell.y + cell.h) / pageF - halfTexel);
                putGlyphVertex(vc, mat, shRight, shBottom, cx, cy, cos, sin, s, shadowArgb,
                        (cell.x + cell.w) / pageF - halfTexel, (cell.y + cell.h) / pageF - halfTexel);
                putGlyphVertex(vc, mat, shRight, shTop, cx, cy, cos, sin, s, shadowArgb,
                        (cell.x + cell.w) / pageF - halfTexel, cell.y / pageF + halfTexel);
            });
            if (glowRl != null) {
                // 晕格恒走加色管线（自建 beacon_beam × LIGHTNING，见 glowType）——
                // 不随光影探测切换：加色就是光晕的定义，光影下也无需 beacon 变体
                //（present 通路里晕格段同样恒加色，两通路语义一致）
                RenderType glowRt = glowType(glowRl);
                LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, glowRt, (pose, vc) -> {
                    Matrix4f mat = pose.pose();
                    putGlyphVertex(vc, mat, gLeft, gTop, cx, cy, cos, sin, s, glowArgb, gu0, gv0);
                    putGlyphVertex(vc, mat, gLeft, gBottom, cx, cy, cos, sin, s, glowArgb, gu0, gv1);
                    putGlyphVertex(vc, mat, gRight, gBottom, cx, cy, cos, sin, s, glowArgb, gu1, gv1);
                    putGlyphVertex(vc, mat, gRight, gTop, cx, cy, cos, sin, s, glowArgb, gu1, gv0);
                });
            }
            RenderType renderType = currentWorldTextType(inkRl);
            LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                // 四顶点：左上 → 左下 → 右下 → 右上（QUADS 环绕序）
                putGlyphVertex(vc, mat, qLeft, qTop, cx, cy, cos, sin, s, argb,
                        cell.x / pageF + halfTexel, cell.y / pageF + halfTexel);
                putGlyphVertex(vc, mat, qLeft, qBottom, cx, cy, cos, sin, s, argb,
                        cell.x / pageF + halfTexel, (cell.y + cell.h) / pageF - halfTexel);
                putGlyphVertex(vc, mat, qRight, qBottom, cx, cy, cos, sin, s, argb,
                        (cell.x + cell.w) / pageF - halfTexel, (cell.y + cell.h) / pageF - halfTexel);
                putGlyphVertex(vc, mat, qRight, qTop, cx, cy, cos, sin, s, argb,
                        (cell.x + cell.w) / pageF - halfTexel, cell.y / pageF + halfTexel);
            });
        } finally {
            poseStack.popPose();
        }

        if (TRACE && !tracedSmooth) {
            tracedSmooth = true;
            System.out.println("[WorldLyrics/trace] 现代档字形 quad 已提交: '" + c
                    + "' cell=" + cell.w + "x" + cell.h + (glowRl != null ? " +glow" : ""));
        }
    }

    /** 一个字形 quad 顶点：绕字中心旋转/缩放（设计像素口径），再进 billboard 矩阵。 */
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
        vc.addVertex(mat, vx, vy, 0f)
                .setColor(argb)
                .setUv(u, v)
                // packed light 拆两个坐标（BakedGlyph 同款）：u = 低半（方块光）、v = 高半（天空光）。
                // 传 (0, 240) 会把方块光压成 0 —— 夜里光查出来几乎全黑，字形被乘成黑方块。
                .setUv2(FULL_BRIGHT & 0xFFFF, (FULL_BRIGHT >> 16) & 0xFFFF)
                .setNormal(0f, 0f, 1f)
                .setUv1(0, 0);
        // Normal+UV1 必须发：光影模式 BufferBuilder 被 Iris 换格式 —— beaconBeam 的
        // BLOCK 动态扩成 TERRAIN（追加 Normal/UV1/实体 ID 等，MixinBufferBuilder
        // iris$extendFormat），这些都是必需元素，缺了 endLastVertex 直接 Missing
        // elements 崩溃（2026-10-28 真机两轮实测）。普通模式 textSeeThrough 的
        // POSITION_TEX_LIGHTMAP_COLOR 没有这两个元素，beginElement 对不存在元素
        // 返回 -1 短路，多发无害 —— 所以恒发，两种模式共用同一条顶点链。
        // 公告牌面向相机，法线恒为局部 +Z。
    }

    // ==================== 更激进的适配：present 兜底采集 ====================

    /**
     * 把一个字形 quad 按当前 billboard 矩阵变换成「相机相对世界轴」坐标后记入
     * present 兜底缓冲。GPU 端 submit 通路的变换链是 视图旋转 × 根矩阵 × billboard；
     * 采集通路在 CPU 上只乘 billboard（根矩阵 = 单位矩阵，26.3 收集期如此），
     * 剩下的视图旋转交给 present 端的 MVP（CameraRenderState.viewRotationMatrix）。
     */
    private void captureGlyphQuad(Identifier pageRl,
                                  float qLeft, float qTop, float qRight, float qBottom,
                                  float cx, float cy, float cos, float sin, float s,
                                  int argb, float u0, float v0, float u1, float v1,
                                  boolean additive) {
        Matrix4f mat = poseStack != null ? poseStack.last().pose() : null;
        if (mat == null) return;
        // 四角先绕字中心旋转/缩放（与 putGlyphVertex 同式），再进 billboard 矩阵
        float ax = cx + ((qLeft - cx) * cos - (qTop - cy) * sin) * s;
        float ay = cy + ((qLeft - cx) * sin + (qTop - cy) * cos) * s;
        float bx = cx + ((qLeft - cx) * cos - (qBottom - cy) * sin) * s;
        float by = cy + ((qLeft - cx) * sin + (qBottom - cy) * cos) * s;
        float gx = cx + ((qRight - cx) * cos - (qBottom - cy) * sin) * s;
        float gy = cy + ((qRight - cx) * sin + (qBottom - cy) * cos) * s;
        float dx = cx + ((qRight - cx) * cos - (qTop - cy) * sin) * s;
        float dy = cy + ((qRight - cx) * sin + (qTop - cy) * cos) * s;
        // transformPosition = addVertex(Matrix4f) 的同款数学（billboard 无透视分量）；
        // 三行全取 —— z 行是深度，丢 z 顶点贴相机平面，透视近平面整帧静默裁剪
        LyricPresentRenderer.addQuad(pageRl,
                matX(mat, ax, ay), matY(mat, ax, ay), matZ(mat, ax, ay), u0, v0,
                matX(mat, bx, by), matY(mat, bx, by), matZ(mat, bx, by), u0, v1,
                matX(mat, gx, gy), matY(mat, gx, gy), matZ(mat, gx, gy), u1, v1,
                matX(mat, dx, dy), matY(mat, dx, dy), matZ(mat, dx, dy), u1, v0,
                argb, additive);
    }

    /** billboard 矩阵 × (x,y,0) 的 X 分量（Matrix4fc.transformPosition 的展开式）。 */
    private static float matX(Matrix4f m, float x, float y) {
        return m.m00() * x + m.m10() * y + m.m30();
    }

    /** billboard 矩阵 × (x,y,0) 的 Y 分量。 */
    private static float matY(Matrix4f m, float x, float y) {
        return m.m01() * x + m.m11() * y + m.m31();
    }

    /** billboard 矩阵 × (x,y,0) 的 Z 分量（深度行，m32 = 相对相机的纵深）。 */
    private static float matZ(Matrix4f m, float x, float y) {
        return m.m02() * x + m.m12() * y + m.m32();
    }

    /**
     * 特效 quad 的采集：四角（公告牌行内坐标）按 billboard 矩阵变换后记入缓冲。
     * 径迹/光晕/星芒/爆闪/拖尾画布共用 —— 顶点序与其他通路一致：
     * 左上 → 左下 → 右下 → 右上。
     */
    private void captureEffectQuad(Identifier rl,
                                   float ax, float ay, float au, float av,
                                   float bx, float by, float bu, float bv,
                                   float gx, float gy, float gu, float gv,
                                   float dx, float dy, float du, float dv,
                                   int argb) {
        Matrix4f mat = poseStack != null ? poseStack.last().pose() : null;
        if (mat == null) return;
        LyricPresentRenderer.addQuad(rl,
                matX(mat, ax, ay), matY(mat, ax, ay), matZ(mat, ax, ay), au, av,
                matX(mat, bx, by), matY(mat, bx, by), matZ(mat, bx, by), bu, bv,
                matX(mat, gx, gy), matY(mat, gx, gy), matZ(mat, gx, gy), gu, gv,
                matX(mat, dx, dy), matY(mat, dx, dy), matZ(mat, dx, dy), du, dv,
                argb, false);
    }

    @Override
    public void drawTrail(float[] xs, float[] ys, int count,
                          int argb, float widthPx) {
        if (poseStack == null || collector == null || count < 2) return;
        Identifier rl = trailTexture();
        if (rl == null) return;

        // submitCustomGeometry 的回调在世界渲染稍后阶段才执行，而 common 渲染层
        // 传进来的是它逐字复用的 scratch 数组，且本帧每个飞行字都会再调一次
        // drawTrail —— 任何共享缓冲（无论入参还是本类静态）都会被下一个字覆写，
        // 让已提交回调全部画成最后一个字的径迹。必须每字分配独立副本、由
        // 回调捕获持有，回调执行完随 lambda 一起变成垃圾。
        if (count > HuiguangMotion.TRACK_SAMPLES + 1) count = HuiguangMotion.TRACK_SAMPLES + 1;
        final float[] xsC = java.util.Arrays.copyOf(xs, count);
        final float[] ysC = java.util.Arrays.copyOf(ys, count);
        final int ptCount = count;

        // 逐段 quad 带（folia trackLayer 的 stroke 等价物）：均匀宽度、沿迹均匀
        // 顶点色 —— folia 是整条一个 alpha 的描边（round cap/join），没有头尾渐变。
        // UV 钉在白纹理的不透明行（v≈0.03）。两层模拟 bloom：窄亮芯 + 宽淡晕
        // （folia 的辉光来自 graphics 组 bloom 过滤器，本仓库无后处理，双 pass 等效）。
        final float half = Math.max(0.15f, widthPx * 0.5f);
        final int baseAlpha = (argb >>> 24) & 0xFF;
        final int rgb = argb & 0xFFFFFF;

        // 采集模式：径迹 quad 带直接按 billboard 矩阵变换记入缓冲（两层 bloom 同发射序）
        if (captureMode) {
            emitTrailCapture(rl, xsC, ysC, ptCount, half, baseAlpha, rgb, 1f, 1f);
            emitTrailCapture(rl, xsC, ysC, ptCount, half, baseAlpha, rgb, 3f, 0.35f);
            return;
        }

        RenderType renderType = currentWorldTextType(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                emitTrail(vc, mat, xsC, ysC, ptCount, half, baseAlpha, rgb, 1f, 1f);
                emitTrail(vc, mat, xsC, ysC, ptCount, half * 3f, baseAlpha, rgb, 1f, 0.35f);
            });
        } finally {
            poseStack.popPose();
        }
    }

    /** 径迹 quad 带的采集版：与 {@link #emitTrail} 同几何，顶点直接进 present 缓冲。 */
    private void emitTrailCapture(Identifier rl, float[] xs, float[] ys, int count,
                                  float half, int baseAlpha, int rgb,
                                  float widthScale, float alphaScale) {
        float hw = half * widthScale;
        int a = Math.min(255, (int) (baseAlpha * alphaScale));
        int argb = (a << 24) | rgb;
        for (int i = 0; i < count - 1; i++) {
            float x0 = xs[i], y0 = ys[i];
            float x1 = xs[i + 1], y1 = ys[i + 1];
            int p0 = Math.max(0, i - 1), q0 = Math.min(count - 1, i + 1);
            float dx0 = xs[q0] - xs[p0], dy0 = ys[q0] - ys[p0];
            float l0 = (float) Math.sqrt(dx0 * dx0 + dy0 * dy0);
            float n0x = l0 < 1e-4f ? 0f : -dy0 / l0, n0y = l0 < 1e-4f ? 1f : dx0 / l0;
            int p1 = i, q1 = Math.min(count - 1, i + 2);
            float dx1 = xs[q1] - xs[p1], dy1 = ys[q1] - ys[p1];
            float l1 = (float) Math.sqrt(dx1 * dx1 + dy1 * dy1);
            float n1x = l1 < 1e-4f ? 0f : -dy1 / l1, n1y = l1 < 1e-4f ? 1f : dx1 / l1;
            // UV 钉在白纹理不透明行（与 putTrailVertex 同参数）；白纹理与字形页共用本通路
            captureEffectQuad(rl,
                    x0 + n0x * hw, y0 + n0y * hw, 0.5f, 0.03f,
                    x0 - n0x * hw, y0 - n0y * hw, 0.5f, 0.03f,
                    x1 - n1x * hw, y1 - n1y * hw, 0.5f, 0.03f,
                    x1 + n1x * hw, y1 + n1y * hw, 0.5f, 0.03f,
                    argb);
        }
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
        // —— 延迟采集版：回调执行时这里拿到的是已拷贝的 xsC/ysC，口径同 1.21.1。
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

    /** 径迹 quad 顶点（文字像素坐标直接进公告牌矩阵；FULL_BRIGHT 同字形通路；
     *  UV 钉在白纹理的不透明行 —— 顶点 alpha 即整条径迹的均匀透明度）。 */
    private static void putTrailVertex(VertexConsumer vc, Matrix4f mat,
                                       float x, float y, int alpha, int rgb) {
        int argb = (alpha << 24) | rgb;
        vc.addVertex(mat, x, y, 0f)
                .setColor(argb)
                .setUv(0.5f, 0.03f)
                .setUv2(FULL_BRIGHT & 0xFFFF, (FULL_BRIGHT >> 16) & 0xFFFF)
                .setNormal(0f, 0f, 1f)
                .setUv1(0, 0);
        // Normal+UV1 必须发（Iris 把 BLOCK 扩成 TERRAIN，见 putGlyphVertex 注释）；
        // 普通模式无这两个元素，beginElement 短路无害。法线恒局部 +Z。
    }

    // ==================== 绘光拖尾画布（Skija 光栅化通路的宿主侧） ====================

    // 按槽位各持一张画布纹理：同一帧 active 行 + 淡出行都进拖尾窗口时
    //（短句/seek），若共用一张纹理，后上传的行会覆盖先提交 quad 的像素
    private static final Identifier[] trailCanvasRl = new Identifier[4];
    private static final TrailTexture[] trailCanvasTex = new TrailTexture[4];
    private static final int[] trailCanvasW = new int[4];
    private static final int[] trailCanvasH = new int[4];
    private static boolean trailCanvasError;
    private static int trailCanvasErrSlot = -1;

    /**
     * 绘光拖尾画布上屏：渲染层用 Skija 在 CPU 端光栅化整句径迹（多层辉光 +
     * 沿程渐变 + 圆头圆接 + 超采样 AA），这里把像素灌进一张动态纹理、以一个
     * quad 覆盖句字面区域 —— 整帧径迹一条纹理一次提交，圆滑无锯齿。
     * 尺寸不变时复用纹理只重传像素（尺寸有 8px 网格量化，换行才重建）；
     * slot 是行槽位（active 0、淡出行 1 起），各槽独立纹理互不覆盖。
     *
     * <p>像素上传在 submit 时立即执行（writeToTexture 只能在 render pass 外调，
     * 与字形格上传同一时机）；回调里只发 quad。lambda 捕获的全是标量与纹理键，
     * 不存在 {@link #drawTrail} 那类共享数组被后续调用覆写的问题（px 每帧由
     * 渲染层新分配，且在 submit 时就已消费完）。</p>
     */
    @Override
    public boolean supportsGpuTrailCanvas() {
        return com.muoniumplayer.render.skija.SkijaTrailRenderer.available();
    }

    @Override
    public boolean drawGpuTrailCanvas(io.github.humbleui.skija.Picture picture, int w, int h,
                                      float planeX, float planeY, float planeW, float planeH,
                                      float alpha, int slot) {
        return drawTrailCanvas(null, picture, w, h, planeX, planeY, planeW, planeH, alpha, slot);
    }

    @Override
    public boolean drawTrailCanvas(int[] px, int w, int h,
                                   float planeX, float planeY,
                                   float planeW, float planeH, float alpha,
                                   int slot) {
        return drawTrailCanvas(px, null, w, h, planeX, planeY, planeW, planeH, alpha, slot);
    }

    private boolean drawTrailCanvas(int[] px, io.github.humbleui.skija.Picture picture, int w, int h,
                                    float planeX, float planeY, float planeW, float planeH,
                                    float alpha, int slot) {
        if (poseStack == null || collector == null || trailCanvasError
                || (px == null && picture == null) || w <= 0 || h <= 0 || alpha <= 0.01f
                || slot < 0 || slot >= trailCanvasRl.length) return false;
        Identifier rl = trailCanvasRl[slot];
        TrailTexture tex = trailCanvasTex[slot];
        try {
            // 同 drawGlowBackdrop：按容量（2 的幂）分配 —— 径迹画布尺寸随句长变化，
            // 逐帧 release+重建会让已提交 quad 读死纹理（黑紫方块闪帧）。
            int capW = nextPow2(w), capH = nextPow2(h);
            if (tex == null || trailCanvasW[slot] < w || trailCanvasH[slot] < h) {
                if (rl != null) {
                    retiredTextures.add(rl);
                    trailCanvasRl[slot] = null;
                    trailCanvasTex[slot] = null;
                }
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, capW, capH, false);
                tex = new TrailTexture(img);
                rl = Identifier.fromNamespaceAndPath("muoniumplayer",
                        "dynamic/worldlyrics_trailcanvas_" + (rlCounter++));
                Minecraft.getInstance().getTextureManager().register(rl, tex);
                trailCanvasRl[slot] = rl;
                trailCanvasTex[slot] = tex;
                trailCanvasW[slot] = capW;
                trailCanvasH[slot] = capH;
            }
            if (picture != null) {
                int glId = ((com.mojang.renderpearl.backend.opengl.GlTexture) tex.getTexture()).glId();
                if (!com.muoniumplayer.render.skija.SkijaTrailRenderer.render(picture, w, h,
                        glId, trailCanvasW[slot], trailCanvasH[slot], slot)) return false;
            } else {
                // Keep the capacity texture, but write/upload only the actual canvas rectangle.
                tex.uploadSub(px, w, h);
            }
        } catch (Throwable t) {
            if (picture != null) return false;
            trailCanvasError = true;
            trailCanvasErrSlot = slot;
            if (TRACE) System.out.println("[WorldLyrics/trace] 拖尾画布纹理失败，径迹退回 quad 带: " + t);
            return false;
        }
        int argb = Math.min(255, (int) (alpha * 255f)) << 24 | 0xFFFFFF;
        // UV 按实际内容 w×h 归一（纹理是容量尺寸，尾部区域透明不采到）
        float uMax = (float) w / trailCanvasW[slot];
        float vMax = (float) h / trailCanvasH[slot];
        // 采集模式：拖尾画布 quad 直接记入 present 缓冲（纹理像素已在本调用中上传完）
        if (captureMode) {
            captureEffectQuad(rl,
                    planeX, planeY, 0f, 0f,
                    planeX, planeY + planeH, 0f, vMax,
                    planeX + planeW, planeY + planeH, uMax, vMax,
                    planeX + planeW, planeY, uMax, 0f,
                    argb);
            return true;
        }
        RenderType renderType = currentWorldTextType(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开）。
        // 渲染层先逐字 halo、再整块画布、最后字形 —— submit 顺序即绘制顺序，
        // 拖尾垫在字下、压在光晕上（与 1.21.1 即时缓冲的层序一致）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                // 四顶点：左上 → 左下 → 右下 → 右上（QUADS 环绕序），UV 按内容区归一
                putHaloVertex(vc, mat, planeX, planeY, 0f, 0f, argb);
                putHaloVertex(vc, mat, planeX, planeY + planeH, 0f, vMax, argb);
                putHaloVertex(vc, mat, planeX + planeW, planeY + planeH, uMax, vMax, argb);
                putHaloVertex(vc, mat, planeX + planeW, planeY, uMax, 0f, argb);
            });
        } finally {
            poseStack.popPose();
        }
        return true;
    }

    /**
     * 拖尾画布脏区带上屏：只把径迹覆盖带写进纹理子矩形并上传（整图重传的
     * 替代 —— 拖尾活跃帧的宿主侧 JNI 写入与 GPU 上传都按带面积计，典型省一
     * 半以上）。band 外的纹理内容不被触碰：画布 clear 语义下 band 外本帧已
     * 是零，上帧残留都在 band 内（collectDirtyRect 的并集保证）。
     * 子矩形写入仍整带覆写（透明像素写 0 清上一帧残留）。
     *
     * <p>26.3 的子矩形上传与 {@link LyricGlyphAtlasBridge.PageTexture#uploadSub}
     * 同口径：6 参 writeToTexture 是「整幅图平移到 (x,y)」（越界校验按图自身
     * 尺寸），所以把带切进一张恰好 bandW×bandH 的暂存图再平移上传；整页 CPU
     * 镜像同步写，用于诊断与全量重传兜底。26.x 无 mip（texStorage 型单级，
     * 见 TrailTexture 注释），上传后无需重生成链。</p>
     */
    
    /**
     * 径迹贴图：128×8 白色横条（v 从上不透明白到下全透明，历史渐变保留）——
     * 绘光还原度批次后径迹顶点 alpha 均匀、UV 钉在不透明行（v≈0.03），纹理
     * 只当白底用。首绘惰性生成并注册
     * （26.x 的 NativeImage 内存序 ABGR：setPixelABGR 吃 (a<<24)|(b<<16)|(g<<8)|r）。
     */
    private static Identifier trailTexture() {
        if (trailError) return null;
        if (trailRl == null) {
            try {
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, 128, 8, false);
                for (int y = 0; y < 8; y++) {
                    // 头（y=0）不透明 → 尾（y=7）透明；sRGB 感知的平方衰减更接近 folia 的渐隐
                    int a = Math.max(0, 255 - (255 * y * y) / (7 * 7));
                    for (int x = 0; x < 128; x++) {
                        img.setPixelABGR(x, y, (a << 24) | 0xFFFFFF);
                    }
                }
                TrailTexture tex = new TrailTexture(img);
                // 构造器 (Supplier, NativeImage) 内部已 createTexture + upload，无需再 upload。
                Identifier rl = Identifier.fromNamespaceAndPath("muoniumplayer",
                        "dynamic/worldlyrics_trail_" + (rlCounter++));
                Minecraft.getInstance().getTextureManager().register(rl, tex);
                trailRl = rl;
            } catch (Throwable t) {
                trailError = true;
                if (TRACE) System.out.println("[WorldLyrics/trace] 径迹纹理生成失败，拖尾停用: " + t);
                return null;
            }
        }
        return trailRl;
    }

    /**
     * 特效贴图（径迹白底 + 光晕/星芒/条纹 + 拖尾画布共用）：26.x 的过滤强制点
     * 在采样器（{@code setFilter} 已删）—— 覆写 {@link #getSampler()} 返回
     * LINEAR + CLAMP_TO_EDGE 的单参缓存采样器。
     *
     * <p><b>mip 为什么跳过（2026-10-25 画质轮同步，与 1.21.1/1.20.1/1.21.11 树
     * 的分工）</b>：26.x 的 {@code GpuDevice.createTexture} 是 texStorage 型
     * 单级不可变存储（DynamicTexture 硬编码 mipLevels=1）——
     * {@code glGenerateMipmap} 不能给不可变存储扩级，只解
     * {@code GL_TEXTURE_MAX_LEVEL} 会得到不完整采样链（主流驱动返回不透明黑，
     * 整块贴图发黑）。所以 26.x 不生成、不采样 mip：毛边由 CPU 侧 5× 超采样 +
     * LINEAR 承担，拖尾的抗锯齿由 CPU 光栅化保证。曾试过三线性 mip，游戏内
     * 特效贴图整块发黑，正是这个根因。</p>
     */
    /** ABGR int[] writes are bounded by the canvas, never by spare array/texture capacity. */
    static void writeCanvasPixels(NativeImage image, int[] pixels, int w, int h) {
        long count = (long) w * h;
        if (image == null || image.format() != NativeImage.Format.RGBA || pixels == null
                || w <= 0 || h <= 0 || w > image.getWidth() || h > image.getHeight()
                || count > pixels.length) {
            throw new IllegalArgumentException("Invalid lyric canvas rectangle: " + w + "x" + h);
        }
        java.nio.IntBuffer target = image.getPixelBytes().order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for (int row = 0; row < h; row++) {
            target.position(row * image.getWidth());
            target.put(pixels, row * w, w);
        }
    }

    private static final class TrailTexture extends DynamicTexture {
        private java.nio.ByteBuffer uploadBytes;
        private java.nio.IntBuffer uploadPixels;

        TrailTexture(NativeImage img) {
            super((java.util.function.Supplier<String>) () -> "muoniumplayer_worldlyrics_trail", img);
        }

        void uploadSub(int[] pixels, int w, int h) {
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
            NativeImage image = this.getPixels();
            writeCanvasPixels(image, pixels, w, h);
            if (this.getTexture() == null) throw new IllegalStateException("Disposed lyric canvas texture");
            if (uploadBytes == null) {
                uploadBytes = java.nio.ByteBuffer.allocateDirect(image.getWidth() * image.getHeight() * Integer.BYTES)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                uploadPixels = uploadBytes.asIntBuffer();
            }
            int count = w * h;
            uploadPixels.clear();
            uploadPixels.put(pixels, 0, count);
            uploadBytes.clear();
            uploadBytes.limit(count * Integer.BYTES);
            com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(this.getTexture(), uploadBytes, 0, 0, 0, 0, w, h);
        }

        @Override
        public com.mojang.renderpearl.api.textures.GpuSampler getSampler() {
            return com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(
                    com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
        }
    }

    private static Identifier trailRl;
    private static boolean trailError;
    private static int rlCounter;

    /** 向上取 2 的幂（≥1）。动态纹理按容量分配：内容增长落在已有纹理内不重建。 */
    private static int nextPow2(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    /**
     * 绘光逐字光晕（folia halos）：径向渐变柔光点 quad 垫在字下。
     * folia haloAlpha = glyphAlpha × lit × (0.03+0.14illum+0.08flash)、
     * size = fontPx × (2.2+0.8illum)；渲染层已按 lit 算好 size/alpha。
     * 与字形同一 alwaysOnTop 相位：渲染层先逐字调 drawHalo 再逐字调
     * drawSmoothGlyph，submit 顺序即绘制顺序 —— 晕垫在字下。
     */
    @Override
    public void drawHalo(float cx, float cy, float sizePx, int argb) {
        if (poseStack == null || collector == null || (argb >>> 24) == 0) return;
        Identifier rl = haloTexture();
        if (rl == null) return;
        final float half = Math.max(1f, sizePx * 0.5f);

        if (captureMode) {
            captureEffectQuad(rl,
                    cx - half, cy - half, 0f, 0f,
                    cx - half, cy + half, 0f, 1f,
                    cx + half, cy + half, 1f, 1f,
                    cx + half, cy - half, 1f, 0f,
                    argb);
            return;
        }

        RenderType renderType = currentWorldTextType(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                putHaloVertex(vc, mat, cx - half, cy - half, 0f, 0f, argb);
                putHaloVertex(vc, mat, cx - half, cy + half, 0f, 1f, argb);
                putHaloVertex(vc, mat, cx + half, cy + half, 1f, 1f, argb);
                putHaloVertex(vc, mat, cx + half, cy - half, 1f, 0f, argb);
            });
        } finally {
            poseStack.popPose();
        }
    }

    /** 光晕 quad 顶点（26.3 顶点链，同 {@link #putTrailVertex} 口径）。 */
    private static void putHaloVertex(VertexConsumer vc, Matrix4f mat,
                                      float x, float y, float u, float v, int argb) {
        vc.addVertex(mat, x, y, 0f)
                .setColor(argb)
                .setUv(u, v)
                .setUv2(FULL_BRIGHT & 0xFFFF, (FULL_BRIGHT >> 16) & 0xFFFF)
                .setNormal(0f, 0f, 1f)
                .setUv1(0, 0);
        // Normal+UV1 必须发（Iris 把 BLOCK 扩成 TERRAIN，见 putGlyphVertex 注释）；
        // 普通模式无这两个元素，beginElement 短路无害。法线恒局部 +Z。
    }

    /**
     * 光晕贴图：128×128 双高斯白点（紧核 + 宽晕）：a = exp(−r²×9)×0.85 +
     * exp(−r²×2.8)×0.15 —— 核心能量紧贴字形（r=0.3 处 ~48%），外围留一层
     * 15% 峰值的宽缓晕托住扫光过渡（单高斯 r=0.5 后几乎归零，扫过时晕边缘
     * 生硬；双高斯晕尾自然衰减到边缘）。首绘惰性生成并注册，TrailTexture
     * 同款强制 LINEAR（getSampler 覆写）；失败熔断只丢光晕。
     * （26.x 的 NativeImage 内存序 ABGR：setPixelABGR 吃 (a<<24)|(b<<16)|(g<<8)|r。）
     */
    private static Identifier haloTexture() {
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
                        img.setPixelABGR(x, y, (a << 24) | 0xFFFFFF);
                    }
                }
                TrailTexture tex = new TrailTexture(img);
                // 构造器 (Supplier, NativeImage) 内部已 createTexture + upload，无需再 upload。
                haloRl = Identifier.fromNamespaceAndPath("muoniumplayer",
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
     * 与字形同一 alwaysOnTop 相位：渲染层逐字调 drawSmoothGlyph 之后才调
     * drawStar，submit 顺序即绘制顺序 —— 星芒压在字上。
     */
    @Override
    public void drawRing(float cx, float cy, float sizePx, int argb) {
        if (poseStack == null || collector == null || (argb >>> 24) == 0) return;
        Identifier rl = ringTexture();
        if (rl == null) return;
        final float half = Math.max(1f, sizePx * 0.5f);
        if (captureMode) {
            captureEffectQuad(rl,
                    cx - half, cy - half, 0f, 0f,
                    cx - half, cy + half, 0f, 1f,
                    cx + half, cy + half, 1f, 1f,
                    cx + half, cy - half, 1f, 0f,
                    argb);
            return;
        }
        RenderType renderType = currentWorldTextType(rl);
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                putHaloVertex(vc, mat, cx - half, cy - half, 0f, 0f, argb);
                putHaloVertex(vc, mat, cx - half, cy + half, 0f, 1f, argb);
                putHaloVertex(vc, mat, cx + half, cy + half, 1f, 1f, argb);
                putHaloVertex(vc, mat, cx + half, cy - half, 1f, 0f, argb);
            });
        } finally {
            poseStack.popPose();
        }
    }

    /** 冲击环贴图：128×128 白色圆环（folia bokeh 环的等价物）——
     *  环心 r=0.62、环宽半高斯 σ=0.05，外缘/内缘平滑收零；LINEAR 下无台阶。 */
    private static Identifier ringRl;
    private static boolean ringError;

    private static Identifier ringTexture() {
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
                        img.setPixelABGR(x, y, 0xFFFFFF | ((int) (255 * band) << 24));
                    }
                }
                DynamicTexture tex = new TrailTexture(img);
                ringRl = Identifier.fromNamespaceAndPath("muoniumplayer",
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

    @Override
    public void drawStar(float cx, float cy, float sizePx, float rotDeg, int argb) {
        if (poseStack == null || collector == null || (argb >>> 24) == 0) return;
        Identifier rl = starTexture();
        if (rl == null) return;
        final float half = Math.max(1f, sizePx * 0.5f);

        // 贴图旋转（folia star.rotation）：绕星心转四角 —— cos/sin 在回调外算好捕获，
        // 回调内只做乘加（延迟采集版）。
        final float cos = (float) Math.cos(Math.toRadians(rotDeg));
        final float sin = (float) Math.sin(Math.toRadians(rotDeg));

        if (captureMode) {
            captureEffectQuad(rl,
                    cx + rot2(-half, -half, cos, sin)[0], cy + rot2(-half, -half, cos, sin)[1], 0f, 0f,
                    cx + rot2(-half, half, cos, sin)[0], cy + rot2(-half, half, cos, sin)[1], 0f, 1f,
                    cx + rot2(half, half, cos, sin)[0], cy + rot2(half, half, cos, sin)[1], 1f, 1f,
                    cx + rot2(half, -half, cos, sin)[0], cy + rot2(half, -half, cos, sin)[1], 1f, 0f,
                    argb);
            return;
        }

        RenderType renderType = currentWorldTextType(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开；
        // 星芒贴图在回调外惰性生成 —— 惰性单例不受延迟采集影响）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                putHaloVertex(vc, mat, cx + rot2(-half, -half, cos, sin)[0], cy + rot2(-half, -half, cos, sin)[1], 0f, 0f, argb);
                putHaloVertex(vc, mat, cx + rot2(-half, half, cos, sin)[0], cy + rot2(-half, half, cos, sin)[1], 0f, 1f, argb);
                putHaloVertex(vc, mat, cx + rot2(half, half, cos, sin)[0], cy + rot2(half, half, cos, sin)[1], 1f, 1f, argb);
                putHaloVertex(vc, mat, cx + rot2(half, -half, cos, sin)[0], cy + rot2(half, -half, cos, sin)[1], 1f, 0f, argb);
            });
        } finally {
            poseStack.popPose();
        }
    }

    /** 局部偏移 (ox, oy) 旋转 rot 后的分量。 */
    private static float[] rot2(float ox, float oy, float cos, float sin) {
        return new float[] {ox * cos - oy * sin, ox * sin + oy * cos};
    }

    /**
     * 星芒贴图：256×256 解析式四芒星（folia sprites 的精修版）——
     * 热核（锐高斯）+ 4 条长锥形射线（沿轴指数衰减 × 垂直高斯收腰、向尖端
     * 渐细）+ 4 条短对角芒；全部指数/高斯无多项式色带，LINEAR 下芒尖平滑
     * 收细。首绘惰性生成并注册，TrailTexture 同款强制 LINEAR（getSampler
     * 覆写）；失败熔断只丢星芒。
     * （26.x 的 NativeImage 内存序 ABGR：setPixelABGR 吃 (a<<24)|(b<<16)|(g<<8)|r。）
     */
    private static Identifier starTexture() {
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
                        img.setPixelABGR(x, y, (a << 24) | 0xFFFFFF);
                    }
                }
                TrailTexture tex = new TrailTexture(img);
                // 构造器 (Supplier, NativeImage) 内部已 createTexture + upload，无需再 upload。
                starRl = Identifier.fromNamespaceAndPath("muoniumplayer",
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

    private static Identifier starRl;
    private static boolean starError;

    private static Identifier haloRl;
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
        if (poseStack == null || collector == null || (argb >>> 24) == 0) return;
        Identifier rl = streakTexture();
        if (rl == null) return;
        // 旋转量在回调外算好捕获（延迟采集版）：回调里只做乘加。
        final float cos = (float) Math.cos(Math.toRadians(rotDeg));
        final float sin = (float) Math.sin(Math.toRadians(rotDeg));
        final float hw = Math.max(0.5f, width * 0.5f);
        final float hh = Math.max(0.5f, height * 0.5f);

        if (captureMode) {
            // 四角 = 中心 ± 长轴半宽·轴向 ± 短轴半宽·法向（旋转由 cos/sin 合成）
            float axX = cos, axY = sin;
            float nX = -sin, nY = cos;
            captureEffectQuad(rl,
                    cx - axX * hw - nX * hh, cy - axY * hw - nY * hh, 0f, 0f,
                    cx - axX * hw + nX * hh, cy - axY * hw + nY * hh, 0f, 1f,
                    cx + axX * hw + nX * hh, cy + axY * hw + nY * hh, 1f, 1f,
                    cx + axX * hw - nX * hh, cy + axY * hw - nY * hh, 1f, 0f,
                    argb);
            return;
        }

        RenderType renderType = currentWorldTextType(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开；
        // 条纹贴图在回调外惰性生成 —— 惰性单例不受延迟采集影响）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                // 四角 = 中心 ± 长轴半宽·轴向 ± 短轴半宽·法向（旋转由 cos/sin 合成）
                float axX = cos, axY = sin;
                float nX = -sin, nY = cos;
                putBurstVertex(vc, mat, cx - axX * hw - nX * hh, cy - axY * hw - nY * hh, 0f, 0f, argb);
                putBurstVertex(vc, mat, cx - axX * hw + nX * hh, cy - axY * hw + nY * hh, 0f, 1f, argb);
                putBurstVertex(vc, mat, cx + axX * hw + nX * hh, cy + axY * hw + nY * hh, 1f, 1f, argb);
                putBurstVertex(vc, mat, cx + axX * hw - nX * hh, cy + axY * hw - nY * hh, 1f, 0f, argb);
            });
        } finally {
            poseStack.popPose();
        }
    }

    /** 爆闪条纹 quad 顶点（26.3 顶点链，同 {@link #putHaloVertex} 口径）。 */
    private static void putBurstVertex(VertexConsumer vc, Matrix4f mat,
                                       float x, float y, float u, float v, int argb) {
        vc.addVertex(mat, x, y, 0f)
                .setColor(argb)
                .setUv(u, v)
                .setUv2(FULL_BRIGHT & 0xFFFF, (FULL_BRIGHT >> 16) & 0xFFFF)
                .setNormal(0f, 0f, 1f)
                .setUv1(0, 0);
        // Normal+UV1 必须发（Iris 把 BLOCK 扩成 TERRAIN，见 putGlyphVertex 注释）；
        // 普通模式无这两个元素，beginElement 短路无害。法线恒局部 +Z。
    }

    /**
     * 光柱条纹贴图：128×32，长轴（u）高斯聚中 + 短轴（v）高斯柔边，白色。
     * 首绘惰性生成并注册，TrailTexture 同款强制 LINEAR（getSampler 覆写）；
     * 失败熔断只丢爆闪。
     * （26.x 的 NativeImage 内存序 ABGR：setPixelABGR 吃 (a<<24)|(b<<16)|(g<<8)|r。）
     */
    private static Identifier streakTexture() {
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
                        img.setPixelABGR(x, y, ((int) (255 * a) << 24) | 0xFFFFFF);
                    }
                }
                TrailTexture tex = new TrailTexture(img);
                // 构造器 (Supplier, NativeImage) 内部已 createTexture + upload，无需再 upload。
                streakRl = Identifier.fromNamespaceAndPath("muoniumplayer",
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

    private static Identifier streakRl;
    private static boolean streakError;

    /**
     * 歌词所在环境的感知亮度 0..1：白天 1、深夜 0（天空暗度），洞窟/夜晚持火把
     * 等场景取公告牌处方块光兜底。绘光的未唱字用它调制「冷暗档 ↔ 自发光冰蓝」。
     *
     * <p>26.3 的 API 差异（javap loom-cache 核对）：亮度口 {@code getRawBrightness}
     * 的归属接口由 1.21.1 的 {@code BlockAndTintGetter} 改名 {@code BlockAndLightGetter}
     * （LevelReader 的新父接口，default 方法同名同参）—— ClientLevel 经 Level/LevelReader
     * 继承到，调用点与 1.21.1 逐字相同；{@code BlockPos.containing(double,double,double)}
     * 与 {@code Minecraft.level} 字段不变。相机坐标用 begin() 存下的 camX/Y/Z
     * （公告牌附近的世界坐标，语义与 1.21.1 一致）。</p>
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
