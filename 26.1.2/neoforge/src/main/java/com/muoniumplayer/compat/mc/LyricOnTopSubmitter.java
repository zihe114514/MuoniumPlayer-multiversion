package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.muoniumplayer.core.utils.logging.LogManager;
import com.muoniumplayer.core.utils.logging.Logger;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * 3D 世界歌词的提交桥 —— <b>NeoForge 26.1.2 侧（降级形态）</b>。
 *
 * <h2>为什么这一代没有「永远画在最上层」</h2>
 * 26.2/26.3 的世界渲染是 renderpearl 延迟采集 + 相位化提交：{@code submitCustomGeometry}
 * 按 RenderType 分相位，{@code alwaysOnTop} 相位在整张帧图的最后执行（云、玻璃、天气
 * 之后），歌词因此能盖住一切。那条通路的两根支柱在 26.1.2 都不存在（javap 核对
 * 26.1.2 客户端 jar 与 fabric-api 0.155.3+26.1.2）：
 * <ol>
 *   <li>原版侧没有 {@code feature.submit.SubmitNode} 节点抽象
 *       （{@code TextFeatureRenderer.Submit} / {@code CustomFeatureRenderer.Submit}
 *       两个 public record 都是 26.2 才拆出来的）；</li>
 *   <li>加载器侧也不需要额外通路：NeoForge 的 {@code submitSpecial(RenderPhaseKey,…)}
 *       扩展是 26.2 才加的，26.1.2 上两条腿都缺。</li>
 * </ol>
 * 所以本树退回普通相位：{@code submitText} / {@code submitCustomGeometry} 原样转发。
 * 已知限制：玻璃和云会盖住 3D 歌词（ translucentCustomGeometry 相位在半透明地形与
 * 云之前）——26.1.2 上这是 API 边界，不是遗漏；26.2+ 树的同名类才是完整形态。
 *
 * <h2>为什么保留独立类而不是让 WorldBillboard 直调 collector</h2>
 * 两个加载器外壳共用树层的 {@code WorldBillboard} 调用面
 * （{@code submitText / submitCustomGeometry / supports}），NeoForge 侧的同名类
 * 将来接 {@code submitSpecial} 通路时不必动共享层；这一代两侧实现一致纯属巧合。
 */
final class LyricOnTopSubmitter {

    private static final Logger logger = LogManager.getLogger("MuoniumPlayer/WorldLyrics");

    private LyricOnTopSubmitter() {
    }

    /** 一行像素字（SEE_THROUGH + FULL_BRIGHT），普通相位。 */
    static void submitText(OrderedSubmitNodeCollector collector, PoseStack poseStack,
                           String text, float x, float y, boolean dropShadow,
                           int lightCoords, int argb) {
        if (collector == null) {
            logger.warn("3D 歌词提交通道拿到空 collector，本帧歌词跳过");
            return;
        }
        // 提交时必须快照矩阵：PoseStack.pushPose 对已存在的深度复用 poses[i].set(prev)
        //（Matrix4f 原地覆写），下一个 drawVanilla 的 push+translate 会把这里捕获的
        // 「活矩阵」原地改写 —— 帧内所有像素字都渲染在最后一个字的变换上。
        // 原版提交路径同样是 new Matrix4f(...) 快照口径，这里沿用同口径传活矩阵即可
        //（collector 实现内部自己快照）。
        collector.submitText(poseStack, x, y,
                FormattedCharSequence.forward(text, Style.EMPTY), dropShadow,
                Font.DisplayMode.SEE_THROUGH, lightCoords, argb, 0, 0);
    }

    /** 一个自定义几何（回调稍后发射顶点），普通相位。 */
    static void submitCustomGeometry(OrderedSubmitNodeCollector collector, PoseStack poseStack,
                                     RenderType renderType,
                                     net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer renderer) {
        if (collector == null) return;
        collector.submitCustomGeometry(poseStack, renderType, renderer);
    }

    /** 供 WorldBillboard 探测（collector 是 SubmitNodeCollection 的容器）。 */
    static boolean supports(OrderedSubmitNodeCollector collector) {
        return collector != null;
    }
}
