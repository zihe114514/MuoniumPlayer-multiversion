package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.muoniumplayer.core.lyrics3d.HuiguangMotion;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
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
                                int rgb, float alpha, float rot, float dx, float dy, float sc,
                                float glowMul) {
        if (poseStack == null || mc == null) return;
        OrderedSubmitNodeCollector submitter = WorldLyricsBridgeAccess.submitter(mc);
        if (submitter == null) return;
        // 发光参数取本帧快照（Bridge 在帧头装配好；没装配时按无发光查格）
        com.muoniumplayer.core.lyrics3d.WorldLyricsSettings settings =
                WorldLyricsBridge.currentSettings;
        float glowStrength = settings != null ? settings.glowStrength : 0f;
        float glowSpread = settings != null ? settings.glowSpread : 1f;
        int glowColor = settings != null ? settings.glowColorRgb : 0xFFFFFF;
        // 心象的发光脉冲：正在唱的块满档、其余块压档 —— 查格前乘上去（缓存键已量化）。
        glowStrength *= glowMul;

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
        net.minecraft.resources.Identifier pageRl =
                ((LyricGlyphAtlasBridge.PageHandle) cell.page).resourceLocation();

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
    public void drawTrail(float[] xs, float[] ys, int count,
                          int argb, float widthPx) {
        if (poseStack == null || mc == null || count < 2) return;
        Identifier rl = trailTexture();
        if (rl == null) return;
        OrderedSubmitNodeCollector submitter = WorldLyricsBridgeAccess.submitter(mc);
        if (submitter == null) return;

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

        RenderType renderType = RenderTypes.textSeeThrough(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(submitter, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                emitTrail(vc, mat, xsC, ysC, ptCount, half, baseAlpha, rgb, 1f, 1f);
                emitTrail(vc, mat, xsC, ysC, ptCount, half * 3f, baseAlpha, rgb, 1f, 0.35f);
            });
        } finally {
            poseStack.popPose();
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
                .setUv2(LightCoordsUtil.FULL_BRIGHT & 0xFFFF,
                        (LightCoordsUtil.FULL_BRIGHT >> 16) & 0xFFFF)
                .setUv1(0, 0);
    }

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
     * 特效贴图（径迹/光晕/星芒/条纹/拖尾画布共用）与字形图集页
     * （{@link LyricGlyphAtlasBridge} 的 PageTexture）的基座：仅强制 LINEAR
     * + CLAMP_TO_EDGE —— 渐变/径向贴图 + 默认 NEAREST 会出条带；26.x 的
     * 过滤强制点在采样器（{@code setFilter} 已删），覆写 {@link #getSampler()}。
     *
     * <p><b>mip 为什么跳过（与 1.21.1/1.20.1/1.21.11 树的分工）</b>：26.x 的
     * {@code GpuDevice.createTexture} 是 texStorage 型单级存储（DynamicTexture
     * 硬编码 mipLevels=1）—— {@code glGenerateMipmap} 不能给不可变存储扩级，
     * 只解 {@code GL_TEXTURE_MAX_LEVEL} 会让采样链不完整（不完整纹理采样
     * undefined，主流驱动返回不透明黑，整块贴图发黑）。所以 26.x 两棵树
     * <b>不生成、不采样 mip</b>：毛边由 CPU 侧 5× 超采样 + LINEAR 承担，
     * 拖尾的抗锯齿由 CPU 光栅化保证。曾做过带完整 mip 链的三线性版，
     * 游戏内特效贴图整块渲染发黑（正是链不完整），已退化回单级分配。</p>
     */
    abstract static class MipDynamicTexture extends DynamicTexture {

        MipDynamicTexture(java.util.function.Supplier<String> label, NativeImage img) {
            super(label, img);
        }

        MipDynamicTexture(String label, int width, int height, boolean clear) {
            super(label, width, height, clear);
        }

        /**
         * textSeeThrough 的绑定路径（RenderSetup.prepareTextures 对 2 参 withTexture
         * 的 null sampler 回退本方法）：无论谁取这个纹理，拿到的都是
         * 单级 LINEAR + CLAMP_TO_EDGE（1.21.1 的 setFilter(true, false) 强制点）。
         */
        @Override
        public com.mojang.blaze3d.textures.GpuSampler getSampler() {
            return com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                    .getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR);
        }
    }

    /**
     * 特效贴图（径迹/光晕/星芒/条纹/拖尾画布共用）：继承 {@link MipDynamicTexture}
     * 拿强制 LINEAR 采样器 —— 渐变/径向贴图 + NEAREST 会出条带。
     */
    private static final class TrailTexture extends MipDynamicTexture {
        TrailTexture(NativeImage img) {
            this((java.util.function.Supplier<String>) () -> "muoniumplayer_worldlyrics_trail", img);
        }

        /** 拖尾画布用：label 传自己的名字，GL 调试/截帧里一眼认出这块纹理。 */
        TrailTexture(java.util.function.Supplier<String> label, NativeImage img) {
            super(label, img);
        }
    }

    private static Identifier trailRl;
    private static boolean trailError;
    private static int rlCounter;

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
     * quad 覆盖句字面区域 —— 整帧径迹一条纹理一次收批，圆滑无锯齿。
     * 尺寸不变时复用纹理只重传像素（尺寸随句字面包围盒变化才重建）；
     * slot 是行槽位（active 0、淡出行 1 起），各槽独立纹理互不覆盖。
     *
     * <p>26.2 与 1.21.1 的差异：几何走 submitCustomGeometry 延迟采集（回调稍后
     * 展开，见 drawHalo）。像素灌装仍在本调用内同步完成 —— 回调只捕获平面
     * 坐标与顶点色发四顶点、不持有 px（渲染层传进来的是逐帧复用的画布缓冲），
     * 因此没有 drawTrail 那种「共享缓冲被下一个字覆写」的拷贝问题。</p>
     */
    @Override
    public boolean drawTrailCanvas(int[] px, int w, int h,
                                   float planeX, float planeY,
                                   float planeW, float planeH, float alpha,
                                   int slot) {
        if (poseStack == null || mc == null || trailCanvasError
                || px == null || w <= 0 || h <= 0 || alpha <= 0.01f
                || slot < 0 || slot >= trailCanvasRl.length) return false;
        OrderedSubmitNodeCollector submitter = WorldLyricsBridgeAccess.submitter(mc);
        if (submitter == null) return false;
        Identifier rl = trailCanvasRl[slot];
        TrailTexture tex = trailCanvasTex[slot];
        try {
            if (tex == null || trailCanvasW[slot] != w || trailCanvasH[slot] != h) {
                if (rl != null) {
                    Minecraft.getInstance().getTextureManager().release(rl);
                    trailCanvasTex[slot] = null;
                }
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
                tex = new TrailTexture(
                        () -> "muoniumplayer_worldlyrics_trailcanvas", img);
                rl = Identifier.fromNamespaceAndPath("muoniumplayer",
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
                    img.setPixelABGR(col, row, px[i]);
                }
            }
            // 基底层每帧全量变化 → 重传（≤1600² 亚毫秒，仅飞行窗口）。
            trailCanvasTex[slot].upload();
        } catch (Throwable t) {
            trailCanvasError = true;
            trailCanvasErrSlot = slot;
            if (TRACE) System.out.println("[WorldLyrics/trace] 拖尾画布纹理失败，径迹退回 quad 带: " + t);
            return false;
        }
        int argb = Math.min(255, (int) (alpha * 255f)) << 24 | 0xFFFFFF;
        RenderType renderType = RenderTypes.textSeeThrough(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(submitter, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                // 四顶点：左上 → 左下 → 右下 → 右上（QUADS 环绕序，同 drawHalo）
                putHaloVertex(vc, mat, planeX, planeY, 0f, 0f, argb);
                putHaloVertex(vc, mat, planeX, planeY + planeH, 0f, 1f, argb);
                putHaloVertex(vc, mat, planeX + planeW, planeY + planeH, 1f, 1f, argb);
                putHaloVertex(vc, mat, planeX + planeW, planeY, 1f, 0f, argb);
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
     * <p>26.2 的子矩形上传与 {@link LyricGlyphAtlasBridge.PageTexture#uploadSub}
     * 同口径：6 参 writeToTexture 是「整幅图平移到 (x,y)」（越界校验按图自身
     * 尺寸），所以把带切进一张恰好 bandW×bandH 的暂存图再平移上传；整页 CPU
     * 镜像同步写，用于诊断与全量重传兜底。26.x 无 mip（texStorage 型单级，
     * 见 MipDynamicTexture），上传后无需重生成链。</p>
     */
    @Override
    public boolean drawTrailCanvasBand(int[] px, int bandX, int bandY,
                                       int bandW, int bandH,
                                       int fullW, int fullH,
                                       float planeX, float planeY,
                                       float planeW, float planeH, float alpha,
                                       int slot) {
        if (poseStack == null || mc == null || trailCanvasError
                || px == null || bandW <= 0 || bandH <= 0 || alpha <= 0.01f
                || slot < 0 || slot >= trailCanvasRl.length) return false;
        OrderedSubmitNodeCollector submitter = WorldLyricsBridgeAccess.submitter(mc);
        if (submitter == null) return false;
        // 画布纹理必须已存在且尺寸一致（渲染层保证 band 画自当前画布）
        TrailTexture tex = trailCanvasTex[slot];
        if (tex == null || trailCanvasW[slot] != fullW || trailCanvasH[slot] != fullH) {
            return false;   // 画布刚重建（本帧首次）：退回整图版先建纹理
        }
        try {
            NativeImage img = tex.getPixels();
            if (img == null || px.length < bandW * bandH) return false;
            try (NativeImage band = new NativeImage(bandW, bandH, true)) {
                for (int row = 0, i = 0; row < bandH; row++) {
                    for (int col = 0; col < bandW; col++, i++) {
                        // 共享层已按 NativeImage 内存序（ABGR、直通 alpha）输出，
                        // 纯拷贝；整页 CPU 镜像同写（诊断/全量重传兜底用）。
                        img.setPixelABGR(bandX + col, bandY + row, px[i]);
                        band.setPixelABGR(col, row, px[i]);
                    }
                }
                // 暂存图全尺寸 = 带尺寸，6 参的 (bandX,bandY) 平移目标合法。
                com.mojang.blaze3d.systems.RenderSystem.getDevice()
                        .createCommandEncoder()
                        .writeToTexture(tex.getTexture(), band, 0, 0, bandX, bandY);
            }
        } catch (Throwable t) {
            trailCanvasError = true;
            trailCanvasErrSlot = slot;
            if (TRACE) System.out.println("[WorldLyrics/trace] 拖尾画布带上传失败，径迹退回 quad 带: " + t);
            return false;
        }
        int argb = Math.min(255, (int) (alpha * 255f)) << 24 | 0xFFFFFF;
        Identifier rl = trailCanvasRl[slot];
        RenderType renderType = RenderTypes.textSeeThrough(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(submitter, poseStack, renderType, (pose, vc) -> {
                Matrix4f mat = pose.pose();
                // 四顶点：左上 → 左下 → 右下 → 右上（QUADS 环绕序，UV 铺满整张画布）
                putHaloVertex(vc, mat, planeX, planeY, 0f, 0f, argb);
                putHaloVertex(vc, mat, planeX, planeY + planeH, 0f, 1f, argb);
                putHaloVertex(vc, mat, planeX + planeW, planeY + planeH, 1f, 1f, argb);
                putHaloVertex(vc, mat, planeX + planeW, planeY, 1f, 0f, argb);
            });
        } finally {
            poseStack.popPose();
        }
        return true;
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
        if (poseStack == null || mc == null || (argb >>> 24) == 0) return;
        Identifier rl = haloTexture();
        if (rl == null) return;
        OrderedSubmitNodeCollector submitter = WorldLyricsBridgeAccess.submitter(mc);
        if (submitter == null) return;
        final float half = Math.max(1f, sizePx * 0.5f);

        RenderType renderType = RenderTypes.textSeeThrough(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(submitter, poseStack, renderType, (pose, vc) -> {
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

    /** 光晕 quad 顶点（26.2 顶点链，同 {@link #putTrailVertex} 口径）。 */
    private static void putHaloVertex(VertexConsumer vc, Matrix4f mat,
                                      float x, float y, float u, float v, int argb) {
        vc.addVertex(mat, x, y, 0f)
                .setColor(argb)
                .setUv(u, v)
                .setUv2(LightCoordsUtil.FULL_BRIGHT & 0xFFFF,
                        (LightCoordsUtil.FULL_BRIGHT >> 16) & 0xFFFF)
                .setUv1(0, 0);
    }

    /**
     * 光晕贴图：128×128 双高斯白点（紧核 + 宽晕）：a = exp(−r²×9)×0.85 +
     * exp(−r²×2.8)×0.15 —— 核心能量紧贴字形（r=0.3 处 ~48%），外围 15% 峰值的
     * 宽缓晕托住扫光过渡（单高斯 r=0.5 后几乎归零，扫过时晕边缘生硬；双高斯
     * 晕尾自然衰减到边缘）。首绘惰性生成并注册，TrailTexture 同款强制
     * LINEAR（getSampler 覆写）；失败熔断只丢光晕。
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
    public void drawStar(float cx, float cy, float sizePx, float rotDeg, int argb) {
        if (poseStack == null || mc == null || (argb >>> 24) == 0) return;
        Identifier rl = starTexture();
        if (rl == null) return;
        OrderedSubmitNodeCollector submitter = WorldLyricsBridgeAccess.submitter(mc);
        if (submitter == null) return;
        final float half = Math.max(1f, sizePx * 0.5f);

        // 贴图旋转（folia star.rotation）：绕星心转四角 —— cos/sin 在回调外算好捕获，
        // 回调内只做乘加（延迟采集版）。
        final float cos = (float) Math.cos(Math.toRadians(rotDeg));
        final float sin = (float) Math.sin(Math.toRadians(rotDeg));

        RenderType renderType = RenderTypes.textSeeThrough(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开；
        // 星芒贴图在回调外惰性生成 —— 惰性单例不受延迟采集影响）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(submitter, poseStack, renderType, (pose, vc) -> {
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
        if (poseStack == null || mc == null || (argb >>> 24) == 0) return;
        Identifier rl = streakTexture();
        if (rl == null) return;
        OrderedSubmitNodeCollector submitter = WorldLyricsBridgeAccess.submitter(mc);
        if (submitter == null) return;
        // 旋转量在回调外算好捕获（延迟采集版）：回调里只做乘加。
        final float cos = (float) Math.cos(Math.toRadians(rotDeg));
        final float sin = (float) Math.sin(Math.toRadians(rotDeg));
        final float hw = Math.max(0.5f, width * 0.5f);
        final float hh = Math.max(0.5f, height * 0.5f);

        RenderType renderType = RenderTypes.textSeeThrough(rl);
        // 提交进 alwaysOnTop 相位（submit 拷走提交时的 pose，pushPose/popPose
        // 维持栈平衡；回调在世界渲染的稍后阶段才被调用，四角全部在回调里展开；
        // 条纹贴图在回调外惰性生成 —— 惰性单例不受延迟采集影响）。
        poseStack.pushPose();
        try {
            LyricOnTopSubmitter.submitCustomGeometry(submitter, poseStack, renderType, (pose, vc) -> {
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

    /** 爆闪条纹 quad 顶点（26.2 顶点链，同 {@link #putHaloVertex} 口径）。 */
    private static void putBurstVertex(VertexConsumer vc, Matrix4f mat,
                                       float x, float y, float u, float v, int argb) {
        vc.addVertex(mat, x, y, 0f)
                .setColor(argb)
                .setUv(u, v)
                .setUv2(LightCoordsUtil.FULL_BRIGHT & 0xFFFF,
                        (LightCoordsUtil.FULL_BRIGHT >> 16) & 0xFFFF)
                .setUv1(0, 0);
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
     * <p>26.2 的 API 差异（javap loom-cache 核对）：亮度口 {@code getRawBrightness}
     * 的归属接口由 1.21.1 的 {@code BlockAndTintGetter} 改名 {@code BlockAndLightGetter}
     * （LevelReader 的新父接口，default 方法同名同参）—— ClientLevel 经 Level/LevelReader
     * 继承到，调用点与 1.21.1 逐字相同；{@code BlockPos.containing(double,double,double)}
     * 与 {@code Minecraft.level} 字段不变。相机坐标用 begin() 存下的 camX/Y/Z
     * （公告牌附近的世界坐标，语义与 1.21.1 一致）。</p>
     */
    @Override
    public float worldLightLevel() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) return 1f;
        // 感知亮度 0..15（方块光 ∪ 减去天色暗度后的天空光，正午地表 15、
        // 满月地表 4、无光源洞窟 0）—— 一个指标同时覆盖昼夜与洞窟/火把
        int raw = client.level.getRawBrightness(
                net.minecraft.core.BlockPos.containing(camX, camY, camZ), 0);
        return Math.max(0f, Math.min(1f, raw / 15f));
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
