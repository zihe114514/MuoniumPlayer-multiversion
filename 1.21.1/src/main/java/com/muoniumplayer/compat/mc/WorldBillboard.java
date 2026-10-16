package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

/**
 *
 * <ul>
 *   <li>{@code ModernWorld.push} → {@link #pushBillboard}：translate(rx,ry,rz) →
 *       rotateY(-yaw) → rotateX(pitch) → scale(-s,-s,s)。之后画的是「文字像素坐标」，
 *       MC 字体坐标口径（x 右、y 下、字顶为 y）。</li>
 *   <li>{@code ModernGfx.vanillaText} → {@link #drawPixelText}：阴影按原版规则
 *       拆开单独画在字前（原版把阴影和字放同一批会按远近重排，歌词的阴影和字几乎
 *       一样远，排序随视角抖动），本体 {@code drawInBatch(SEE_THROUGH, FULL_BRIGHT)}，
 *   <li>世界渲染期间的 GL 状态（关深度测试、关剔除、透明混合）由
 *       {@code WorldLyricsBridge} 在调渲染器前设好，等价于 {@code ModernWorld.begin}。</li>
 * </ul>
 *
 * <p>平滑字体档暂走像素字兜底（返回 false 的分支不再需要：drawSmoothGlyph 直接
 * 委托 drawPixelText 单字）。发光仍由 TextGlow 纹理烘焙承担 —— 本版先把行为对齐。</p>
 */
public final class WorldBillboard implements WorldLyricsRenderer.Host {

    /** 一次性诊断开关。 */
    static final boolean TRACE = Boolean.getBoolean("muonium.debug.worldLyrics");

    /** 每帧共享实例。 */
    public static final WorldBillboard INSTANCE = new WorldBillboard();

    private PoseStack poseStack;
    private MultiBufferSource.BufferSource buffers;
    private double camX, camY, camZ;
    private boolean tracedOnce;

    private WorldBillboard() {
    }

    public void begin(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                      double camX, double camY, double camZ) {
        this.poseStack = poseStack;
        this.buffers = buffers;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
    }

    public void end() {
        this.poseStack = null;
        this.buffers = null;
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
        if (poseStack == null || buffers == null || text == null || text.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        // DangoModernGfx.vanillaText 一比一：阴影拆开单独画，本体 false shadow
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

    private void drawVanilla(Minecraft mc, String text, float x, float y, int argb) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();
        mc.font.drawInBatch(text, x, y, argb, false, mat, buffers,
                Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        buffers.endBatch();
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
