package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
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
 * <p>进 {@code texts} 相位的 submit 会在本帧 main pass 的
 * {@code PreparedFrame.executeTranslucent()} 里走 {@code textSeeThrough} 管线上屏，
 * 混合/深度状态由 RenderType 自带 —— 1.21.1 里 Bridge 手设的那组 GL 状态不再需要。</p>
 *
 * <p>平滑字体档暂走像素字兜底（drawSmoothGlyph 直接委托 drawPixelText 单字）。
 * 发光仍由 TextGlow 纹理烘焙承担 —— 本版先把行为对齐。</p>
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
        net.minecraft.client.renderer.OrderedSubmitNodeCollector submitter =
                WorldLyricsBridgeAccess.submitter(mc);
        if (submitter == null) return;
        // submitText 拷走 pose（SubmitNodeCollection.submitText 里 new Matrix4f(pose)），
        // pushPose/popPose 维持栈平衡。
        poseStack.pushPose();
        poseStack.last().pose().translate(x, y, 0f);
        try {
            FormattedCharSequence seq = FormattedCharSequence.forward(text, Style.EMPTY);
            submitter.submitText(poseStack, 0f, 0f, seq,
                    false, Font.DisplayMode.SEE_THROUGH,
                    LightCoordsUtil.FULL_BRIGHT, argb, 0, 0);
        } finally {
            poseStack.popPose();
        }
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

    /** 仅供桥接类在事件回调里登记本帧的提交入口。 */
    static void setSubmitNodeCollector(net.minecraft.client.renderer.OrderedSubmitNodeCollector collector) {
        WorldLyricsBridgeAccess.current = collector;
    }

    /** 包内小闸门：collector 的存取都收在这里，避免把可变态暴露成 public 字段。 */
    static final class WorldLyricsBridgeAccess {
        /** 本帧的提交入口（事件回调里装，渲染线程单线程读写，帧末清空）。 */
        static volatile net.minecraft.client.renderer.OrderedSubmitNodeCollector current;

        static net.minecraft.client.renderer.OrderedSubmitNodeCollector submitter(Minecraft mc) {
            // collector 由 Bridge 的世界采集事件装进来（Fabric COLLECT_SUBMITS 的
            // context / NeoForge SubmitCustomGeometryEvent 的事件参数）；
            // 26.2 没有绕过事件直接拿 LevelRenderer 存储的公开路径，事件没挂上
            // 就不画（current == null 时 drawPixelText 不会走到这里之外的地方）。
            return WorldLyricsBridgeAccess.current;
        }
    }
}
