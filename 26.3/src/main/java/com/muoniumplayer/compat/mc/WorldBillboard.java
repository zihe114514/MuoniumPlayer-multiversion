package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

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
 * <p>平滑字体档暂走像素字兜底（drawSmoothGlyph 直接委托 drawPixelText 单字），
 * 与 1.21.1 相同；发光仍由 TextGlow 纹理烘焙承担。</p>
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
                collector.submitCustomGeometry(poseStack, renderType, (pose, consumer) ->
                        renderable.render(pose.pose(), consumer, FULL_BRIGHT, false));
            }
        });
        if (TRACE && !tracedOnce) {
            tracedOnce = true;
            System.out.println("[WorldLyrics/trace] 原版像素字已提交 submit: \"" + text + "\"");
        }
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

    @Override
    public void drawSmoothGlyph(char c, float x, float y, float sizePx,
                                int rgb, float alpha, float rot, float dx, float dy, float sc) {
        // 平滑字体档（纹理烘焙）后续接入；先统一走原版像素字，保证行为 1:1 先跑通
        int argb = ((int) (alpha * 255f) << 24) | (rgb & 0xFFFFFF);
        drawPixelText(String.valueOf(c), x, y, argb, false);
    }

    @Override
    public float smoothCharWidth(char c, float sizePx) {
        return pixelCharWidth(c);
    }
}
