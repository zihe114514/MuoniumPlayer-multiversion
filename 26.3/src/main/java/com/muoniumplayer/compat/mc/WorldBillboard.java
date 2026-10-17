package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import org.joml.Matrix4f;

/**
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
     * 全亮度光值（1.21.1 的 {@code LightTexture.FULL_BRIGHT}）。26.3 该常量类已删除，
     * 值 15728880（0xF000F0）在原版实体渲染器中照旧裸写。
     */
    private static final int FULL_BRIGHT = 15728880;

    /** 每帧共享实例。 */
    public static final WorldBillboard INSTANCE = new WorldBillboard();

    private PoseStack poseStack;
    private SubmitNodeCollector collector;
    private double camX, camY, camZ;
    private boolean tracedOnce;
    private boolean tracedSmooth;

    private WorldBillboard() {
    }

    public void begin(PoseStack poseStack, SubmitNodeCollector collector,
                      double camX, double camY, double camZ) {
        this.poseStack = poseStack;
        this.collector = collector;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
    }

    public void end() {
        this.poseStack = null;
        this.collector = null;
    }

    @Override
    public void pushBillboard(double rx, double ry, double rz,
                              double yawDeg, double pitchDeg, float scale) {
        if (poseStack == null) return;
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

    @Override
    public void drawPixelText(String text, float x, float y, int argb, boolean shadow) {
        if (poseStack == null || collector == null || text == null || text.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        // DangoModernGfx.vanillaText 一比一：阴影拆开画在字前 —— 26.3 由 prepareText 的
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
     * withWiggle 的 translate(cx,cy)→rotate→scale→translate(-cx,-cy) 同构；dx/dy 已由
     * 渲染器加进 x/y。回调拿到的 Pose 已含 billboard 矩阵（submitCustomGeometry 拷走
     * 提交时的 poseStack 栈顶），四角直接在该矩阵下展开。</p>
     */
    @Override
    public void drawSmoothGlyph(char c, float x, float y, float sizePx,
                                int rgb, float alpha, float rot, float dx, float dy, float sc) {
        if (poseStack == null || collector == null) return;
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
            LyricOnTopSubmitter.submitCustomGeometry(collector, poseStack, renderType, (pose, vc) -> {
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
                .setUv2(FULL_BRIGHT & 0xFFFF, (FULL_BRIGHT >> 16) & 0xFFFF)
                .setUv1(0, 0);
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
