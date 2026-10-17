package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.muoniumplayer.core.utils.logging.LogManager;
import com.muoniumplayer.core.utils.logging.Logger;

import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.util.FormattedCharSequence;

import org.joml.Matrix4fc;

/**
 * 3D 世界歌词的「永远画在最上层」提交桥 —— <b>Fabric 26.2 侧</b>。
 *
 * <h2>为什么需要它（云和玻璃盖住歌词的根因）</h2>
 * 26.2 的世界渲染是 renderpearl 延迟采集：{@code submitCustomGeometry} 按 RenderType
 * 分相位 —— 带 {@link RenderType#hasBlending()} 的（我们用的
 * {@code textSeeThrough} 正是）进 {@code translucentCustomGeometry} 相位，
 * 在 <b>main pass 内部、半透明地形和云 pass 之前</b>被画（javap 核对：
 * {@code FeatureRenderDispatcher.executeTranslucent()} 里
 * {@code executePhase(collection.translucentCustomGeometry, …)} 在
 * {@code chunkSectionsToRender.renderGroup(TRANSLUCENT, …)} 之前；
 * 云是 main pass 之后的独立 clouds pass）。结果玻璃和云都画在歌词<b>之后</b>，
 * 直接混叠盖字 —— 玩家实测「玻璃和云都能盖住 3D 歌词」的根因。
 *
 * <p>修法是把歌词 submit 挪进 {@code alwaysOnTop} 相位：它由
 * {@code LevelRenderer.addAlwaysOnTopPass} 在 <b>整张帧图的最后</b>执行
 * （main → clouds → weather → transparencyChain 之后），执行前还先
 * {@code clearDepthTexture(depth, 0.0)}（深度清到远平面 = 无深度测试）——
 * 歌词永远画在云、玻璃、天气之上，手持物品与 GUI 仍在其后正常遮挡。</p>
 *
 * <h2>Fabric 26.2 的通路（javap 核对 fabric-rendering-v1 25.3.2）</h2>
 * fabric-api 给 {@code OrderedSubmitNodeCollector} 混入了
 * {@code submitCustom(SubmitRenderPhase, SubmitNode)}
 * （{@code FabricOrderedSubmitNodeCollector} 声明、{@code SubmitNodeCollectionMixin}
 * / {@code SubmitNodeStorageMixin} 实现，落进 {@code SubmitNodeCollection} 的
 * 指定相位），配套常量表 {@link SubmitRenderPhases}（其 {@code ALWAYS_ON_TOP}
 * lambda 返回 {@code SubmitNodeCollection.alwaysOnTop}）。NeoForge 侧同款能力走
 * {@code submitSpecial(RenderPhaseKeys.ALWAYS_ON_TOP, …)}（见 neoforge 外壳的同名类）。
 *
 * <p>submit 节点用原版现成的两个 public record，与原通路逐字段同构：
 * 文字 = {@link TextFeatureRenderer.Submit}（与 {@code submitText} 造的节点相同），
 * 自定义几何（字形图集 quad / TextRenderable 发射）=
 * {@link CustomFeatureRenderer.Submit}（与 {@code submitCustomGeometry} 造的相同），
 * 相位分组与特征渲染器都按 submit 的 {@code featureType()} 走 —— 换相位不换绘制代码。</p>
 */
final class LyricOnTopSubmitter {

    private static final Logger logger = LogManager.getLogger("MuoniumPlayer/WorldLyrics");

    /** fabric-api 版本差异的护栏：缺 submitCustom/常量时静默回退普通相位。 */
    private static boolean broken;

    private LyricOnTopSubmitter() {
    }

    private static boolean submitOnTop(OrderedSubmitNodeCollector collector,
                                       SubmitRenderPhase<SubmitNode> phase, SubmitNode node) {
        if (collector == null || broken) return false;
        try {
            collector.submitCustom(phase, node);
            return true;
        } catch (Throwable t) {
            // 一次性降级：这帧起退回 submitText/submitCustomGeometry 的旧相位（画得对但会被云盖），
            // 别让日志刷屏。
            broken = true;
            logger.error("3D 歌词 always-on-top 提交通路不可用，退回普通相位（会被云/玻璃遮挡）", t);
            return false;
        }
    }

    /** 一行像素字（SEE_THROUGH + FULL_BRIGHT），进 alwaysOnTop 相位。 */
    static void submitText(OrderedSubmitNodeCollector collector, PoseStack poseStack,
                           String text, float x, float y, boolean dropShadow,
                           int lightCoords, int argb) {
        TextFeatureRenderer.Submit node = new TextFeatureRenderer.Submit(
                poseStack.last().pose(), x, y,
                FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY),
                dropShadow, Font.DisplayMode.SEE_THROUGH, lightCoords, argb, 0, 0);
        if (submitOnTop(collector, SubmitRenderPhases.ALWAYS_ON_TOP, node)) return;
        collector.submitText(poseStack, x, y, FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY),
                dropShadow, Font.DisplayMode.SEE_THROUGH, lightCoords, argb, 0, 0);
    }

    /** 一个自定义几何（回调稍后发射顶点），进 alwaysOnTop 相位。 */
    static void submitCustomGeometry(OrderedSubmitNodeCollector collector, PoseStack poseStack,
                                     RenderType renderType,
                                     net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer renderer) {
        CustomFeatureRenderer.Submit node = new CustomFeatureRenderer.Submit(
                poseStack.last().copy(), renderType, renderer);
        if (submitOnTop(collector, SubmitRenderPhases.ALWAYS_ON_TOP, node)) return;
        collector.submitCustomGeometry(poseStack, renderType, renderer);
    }

    /** 供 WorldBillboard 探测（collector 是 SubmitNodeCollection 的容器）。 */
    static boolean supports(OrderedSubmitNodeCollector collector) {
        return collector != null;
    }

    /** 编译期锚点：fabric 侧 SubmitNodeCollection 类型引用（防误删 import）。 */
    @SuppressWarnings("unused")
    private static Class<?> anchor() {
        return SubmitNodeCollection.class;
    }

    /** Matrix4fc 口径占位：26.3 的 Submit 用 Matrix4fc，26.2 用 PoseStack.Pose。 */
    @SuppressWarnings("unused")
    private static void matrixAnchor(Matrix4fc unused) {
    }
}
