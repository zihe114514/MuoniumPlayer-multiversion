package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.LightCoordsUtil;

import org.joml.Matrix4f;

/**
 *
 * <ul>
 *   <li>{@code ModernWorld.push} → {@link #pushBillboard}：translate(rx,ry,rz) →
 *       rotateY(-yaw) → rotateX(pitch) → scale(-s,-s,s)。之后画的是「文字像素坐标」，
 *       MC 字体坐标口径（x 右、y 下、字顶为 y）。</li>
 *   <li>{@code ModernGfx.vanillaText} → {@link #drawPixelText}：阴影按原版规则
 *       拆开单独画在字前（两次独立 submit，阴影先进），本体
 * </ul>
 *
 * <h2>26.2 的字要怎么画（与 1.21.1 的最大差异）</h2>
 * <p>1.21.1 的 {@code Font.drawInBatch(String, …, DisplayMode, …)} 在 26.2 已被整体删除
 * （javap 核对：26.2 的 {@code Font} 上没有任何 drawInBatch）。26.x 的渲染改成了
 * renderpearl 的延迟采集：文字由 {@code Font.prepareText(...)} 生成
 * {@code PreparedText}，由 {@code TextFeatureRenderer} 在 draw 批里 visit 出顶点。
 * 公共入口是 {@code OrderedSubmitNodeCollector.submitText(PoseStack, x, y,
 * FormattedCharSequence, dropShadow, DisplayMode, lightCoords, color, background,
 * outline)} —— 它在 {@code SubmitNodeCollection.submitText} 里就是
 * {@code new Matrix4f(poseStack.last().pose())} + prepareText。我们传根矩阵已设成
 * basePose 的 {@link PoseStack}、{@code SEE_THROUGH}、{@code FULL_BRIGHT}、
 * 零背景零描边，与 1.21.1 的 {@code drawInBatch(SEE_THROUGH, FULL_BRIGHT)} 逐参对应；
 * 本体与阴影各一次 submit，不进同一排序批。</p>
 *
 * <h2>现代化渲染通道（与 1.21.1 的 drawSmoothGlyph 同构）</h2>
 * <p>查 {@link LyricGlyphAtlas}（Skija 5× 超采样字形 + TextGlow 三层高斯发光烘在
 * 同一张格里），纹理 quad 走 26.2 的公开几何提交口
 * {@code OrderedSubmitNodeCollector.submitCustomGeometry(poseStack, renderType, 回调)}
 * （javap：{@code CustomGeometryRenderer.render(PoseStack$Pose, VertexConsumer)}，
 * 名字牌/挡视线等原版特性同款），renderType =
 * {@code RenderTypes.textSeeThrough(页 Identifier)}（1.21.1 的
 * {@code RenderType.textSeeThrough(rl)} 在 26.x 挪进了 {@code RenderTypes}）。
 * textured quad 同构，wiggle 的旋转/缩放绕字中心落进顶点矩阵；页纹理的采样器被
 * PageTexture 强制成 LINEAR（见 LyricGlyphAtlasBridge），超采样不被 NEAREST 采样毁掉。
 * 坐标是相机相对（与像素路径同基准），ModelViewMat 由 RenderType.prepare 在采集期捕获，
 * GPU 端 = 视图旋转 × 根矩阵 × billboard。</p>
 *
 * <p>进 {@code texts} 相位的 submit 会在本帧 main pass 的
 * {@code PreparedFrame.executeTranslucent()} 里走 {@code textSeeThrough} 管线上屏，
 * 混合/深度状态由 RenderType 自带 —— 1.21.1 里 Bridge 手设的那组 GL 状态不再需要。
 * 但 <b>texts / translucentCustomGeometry 相位都在 main pass 内、半透明地形和云之前</b>，
 * 玻璃和云会直接盖字 —— 实际提交走 {@link LyricOnTopSubmitter}（alwaysOnTop 相位，
 * 整张帧图的最后，云/玻璃/天气之上），两条通道都不例外。</p>
 *
 * <p>格子不可用（图集未装配/Skija 缺位/字符不可见）时回退像素字 —— 绝不吞字。</p>
 */
public final class WorldBillboard implements WorldLyricsRenderer.Host {

    /** 一次性诊断开关。 */
    static final boolean TRACE = Boolean.getBoolean("muonium.debug.worldLyrics");

    /** 每帧共享实例。 */
    public static final WorldBillboard INSTANCE = new WorldBillboard();

    private PoseStack poseStack;
    private Minecraft mc;
    private double camX, camY, camZ;
    private boolean tracedOnce;
    private boolean tracedSmooth;

    private WorldBillboard() {
    }

    public void begin(PoseStack poseStack, Minecraft mc,
                      double camX, double camY, double camZ) {
        this.poseStack = poseStack;
        this.mc = mc;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
    }

    public void end() {
        this.poseStack = null;
        this.mc = null;
    }

    @Override
    public void pushBillboard(double rx, double ry, double rz,
                              double yawDeg, double pitchDeg, float scale) {
        if (poseStack == null) return;
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
        if (poseStack == null || mc == null || text == null || text.isEmpty()) return;
        // DangoModernGfx.vanillaText 一比一：阴影拆开单独画（先进），本体后进
        if (shadow) {
            int shadowColor = ((argb & 0xFCFCFC) >> 2) | (argb & 0xFF000000);
            drawVanilla(text, x + 1f, y + 1f, shadowColor);
        }
        drawVanilla(text, x, y, argb);
        if (TRACE && !tracedOnce) {
            tracedOnce = true;
            System.out.println("[WorldLyrics/trace] 原版像素字已提交: \"" + text + "\"");
        }
    }

    private void drawVanilla(String text, float x, float y, int argb) {
        OrderedSubmitNodeCollector submitter = WorldLyricsBridgeAccess.submitter(mc);
        if (submitter == null) return;
        // submit 节点里拷走矩阵（SubmitNodeCollection.submitText/our Submit record 都在
        // 提交时快照 pose），pushPose/popPose 维持栈平衡。
        poseStack.pushPose();
        try {
            poseStack.last().pose().translate(x, y, 0f);
            LyricOnTopSubmitter.submitText(submitter, poseStack, text, 0f, 0f,
                    false, LightCoordsUtil.FULL_BRIGHT, argb);
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * 现代化风格单字：字形图集取格（Skija 5× 超采样 + TextGlow 发光烘焙），
     * submitCustomGeometry + textSeeThrough(页纹理) 发一个四顶点 quad。
     *
     * <p>坐标口径：传进来的 (x, y) 是字形<b>顶点</b>（字位框左上角，设计像素），
     * 对应格内 (inkX, inkY)。quad 覆盖整格（含发光留白），尺寸 = 格框 ÷ BAKE_SCALE。
     * withWiggle 的 translate(cx,cy)→rotate→scale→translate(-cx,-cy) 同构；dx/dy 已由
     * 渲染器加进 x/y。回调拿到的 Pose 已含 billboard 矩阵（submitCustomGeometry 拷走
     * 提交时的 poseStack 栈顶），四角直接在该矩阵下展开。</p>
     */
    @Override
    public void drawSmoothGlyph(char c, float x, float y, float sizePx,
                                int rgb, float alpha, float rot, float dx, float dy, float sc) {
        if (poseStack == null || mc == null) return;
        OrderedSubmitNodeCollector submitter = WorldLyricsBridgeAccess.submitter(mc);
        if (submitter == null) return;
        // 发光参数取本帧快照（Bridge 在帧头装配好；没装配时按无发光查格）
        com.muoniumplayer.core.lyrics3d.WorldLyricsSettings settings =
                WorldLyricsBridge.currentSettings;
        float glowStrength = settings != null ? settings.glowStrength : 0f;
        float glowSpread = settings != null ? settings.glowSpread : 1f;
        int glowColor = settings != null ? settings.glowColorRgb : 0xFFFFFF;

        LyricGlyphAtlas.GlyphCell cell = LyricGlyphAtlas.getGlyph(
                c, rgb & 0xFFFFFF, glowStrength, glowSpread, glowColor);
        if (cell == null) {
            // 图集不可用/超尺寸：像素字兜底（无阴影，与平滑档的无阴影语义一致）
            int argb = ((int) (alpha * 255f) << 24) | (rgb & 0xFFFFFF);
            drawPixelText(String.valueOf(c), x, y, argb, false);
            return;
        }
        if (!(cell.page instanceof LyricGlyphAtlasBridge.PageHandle)) {
            return;
        }
        net.minecraft.resources.Identifier pageRl =
                ((LyricGlyphAtlasBridge.PageHandle) cell.page).resourceLocation();

        // 烘焙像素 → 设计像素。字形顶点 (x, y) 是「字位框左上角」在<b>行内坐标</b>里的位置
        // （buildGlyphs 逐字推进的坐标），格内墨迹原点 (inkX, inkY) 对到它 —— quad 四角
        // 必须加上 (x, y) 再进公告牌矩阵。初版漏加，整行所有字都叠在公告牌原点。
        float inv = 1f / LyricGlyphAtlas.BAKE_SCALE;
        float qLeft = x - cell.inkX * inv;
        float qTop = y - cell.inkY * inv;
        float qRight = qLeft + cell.w * inv;
        float qBottom = qTop + cell.h * inv;

        float pageF = LyricGlyphAtlas.PAGE_SIZE;
        float u0 = cell.x / pageF;
        float v0 = cell.y / pageF;
        float u1 = (cell.x + cell.w) / pageF;
        float v1 = (cell.y + cell.h) / pageF;

        float cx = x + (cell.advance * inv) * 0.5f;
        float cy = y + (cell.inkHeight * inv) * 0.5f;
        boolean transformed = rot != 0f || sc != 1f;
        float cos = transformed ? (float) Math.cos(Math.toRadians(rot)) : 1f;
        float sin = transformed ? (float) Math.sin(Math.toRadians(rot)) : 0f;
        float s = transformed ? sc : 1f;

        int argb = (((int) (alpha * 255f) & 0xFF) << 24) | (rgb & 0xFFFFFF);

        RenderType renderType = RenderTypes.textSeeThrough(pageRl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(submitter, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                // 四顶点：左上 → 左下 → 右下 → 右上（QUADS 环绕序）
                putGlyphVertex(vc, mat, qLeft, qTop, cx, cy, cos, sin, s, argb, u0, v0);
                putGlyphVertex(vc, mat, qLeft, qBottom, cx, cy, cos, sin, s, argb, u0, v1);
                putGlyphVertex(vc, mat, qRight, qBottom, cx, cy, cos, sin, s, argb, u1, v1);
                putGlyphVertex(vc, mat, qRight, qTop, cx, cy, cos, sin, s, argb, u1, v0);
            });
        } finally {
            poseStack.popPose();
        }

        if (TRACE && !tracedSmooth) {
            tracedSmooth = true;
            System.out.println("[WorldLyrics/trace] 现代档字形 quad 已提交: '" + c
                    + "' cell=" + cell.w + "x" + cell.h + " pad=" + cell.inkX);
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
                .setUv2(LightCoordsUtil.FULL_BRIGHT & 0xFFFF,
                        (LightCoordsUtil.FULL_BRIGHT >> 16) & 0xFFFF)
                .setUv1(0, 0);
    }

    @Override
    public float pixelWidth(String text) {
        Minecraft client = Minecraft.getInstance();
        return (client == null || client.font == null || text == null) ? 0f : client.font.width(text);
    }

    @Override
    public float pixelCharWidth(char c) {
        Minecraft client = Minecraft.getInstance();
        return (client == null || client.font == null) ? 0f : client.font.width(String.valueOf(c));
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

    /** 仅供桥接类在事件回调里登记本帧的提交入口。 */
    static void setSubmitNodeCollector(OrderedSubmitNodeCollector collector) {
        WorldLyricsBridgeAccess.current = collector;
    }

    /** 包内小闸门：collector 的存取都收在这里，避免把可变态暴露成 public 字段。 */
    static final class WorldLyricsBridgeAccess {
        /** 本帧的提交入口（事件回调里装，渲染线程单线程读写，帧末清空）。 */
        static volatile OrderedSubmitNodeCollector current;

        static OrderedSubmitNodeCollector submitter(Minecraft mc) {
            // collector 由 Bridge 的世界采集事件装进来（Fabric COLLECT_SUBMITS 的
            // context / NeoForge SubmitCustomGeometryEvent 的事件参数）；
            // 26.2 没有绕过事件直接拿 LevelRenderer 存储的公开路径，事件没挂上
            // 就不画（current == null 时 drawPixelText 不会走到这里之外的地方）。
            return WorldLyricsBridgeAccess.current;
        }
    }
}
