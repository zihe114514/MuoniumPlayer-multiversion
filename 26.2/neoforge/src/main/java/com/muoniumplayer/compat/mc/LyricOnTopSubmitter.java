package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.muoniumplayer.core.utils.logging.LogManager;
import com.muoniumplayer.core.utils.logging.Logger;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.submit.RenderPhaseKey;
import net.neoforged.neoforge.client.submit.RenderPhaseKeys;

/**
 * 3D 世界歌词的「永远画在最上层」提交桥 —— <b>NeoForge 26.2 侧</b>。
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
 * <h2>NeoForge 26.2 的通路（javap 核对 neoforge-26.2.0.72）</h2>
 * {@link OrderedSubmitNodeCollector} 实现
 * {@code OrderedSubmitNodeCollectorExtension}，其
 * {@code submitSpecial(RenderPhaseKey, SubmitNode)} 把节点落进
 * {@code RenderPhaseKey.resolve(collection)} 指定的相位；
 * {@link RenderPhaseKeys#ALWAYS_ON_TOP} 的 lambda 返回
 * {@code SubmitNodeCollection.alwaysOnTop}。Fabric 侧同款能力走
 * {@code submitCustom(SubmitRenderPhases.ALWAYS_ON_TOP, …)}（见 fabric 外壳的同名类）。
 *
 * <p>submit 节点用原版现成的两个 public record，与原通路逐字段同构：
 * 文字 = {@link TextFeatureRenderer.Submit}（与 {@code submitText} 造的节点相同），
 * 自定义几何（字形图集 quad）= {@link CustomFeatureRenderer.Submit}
 * （与 {@code submitCustomGeometry} 造的相同），相位分组与特征渲染器都按
 * submit 的 {@code featureType()} 走 —— 换相位不换绘制代码。</p>
 */
final class LyricOnTopSubmitter {

    private static final Logger logger = LogManager.getLogger("MuoniumPlayer/WorldLyrics");

    /** API 缺位/相位缺失时一次性降级，避免日志刷屏。 */
    private static boolean broken;

    private LyricOnTopSubmitter() {
    }

    private static final RenderPhaseKey<SubmitNode> ON_TOP = RenderPhaseKeys.ALWAYS_ON_TOP;

    private static boolean submitOnTop(OrderedSubmitNodeCollector collector, SubmitNode node) {
        if (collector == null || broken) return false;
        try {
            collector.submitSpecial(ON_TOP, node);
            return true;
        } catch (Throwable t) {
            broken = true;
            logger.error("3D 歌词 always-on-top 提交通路不可用，退回普通相位（会被云/玻璃遮挡）", t);
            return false;
        }
    }

    /** 一行像素字（SEE_THROUGH + FULL_BRIGHT），进 alwaysOnTop 相位。 */
    static void submitText(OrderedSubmitNodeCollector collector, PoseStack poseStack,
                           String text, float x, float y, boolean dropShadow,
                           int lightCoords, int argb) {
        FormattedCharSequence seq = FormattedCharSequence.forward(text, Style.EMPTY);
        TextFeatureRenderer.Submit node = new TextFeatureRenderer.Submit(
                poseStack.last().pose(), x, y, seq, dropShadow,
                Font.DisplayMode.SEE_THROUGH, lightCoords, argb, 0, 0);
        if (submitOnTop(collector, node)) return;
        collector.submitText(poseStack, x, y, seq, dropShadow,
                Font.DisplayMode.SEE_THROUGH, lightCoords, argb, 0, 0);
    }

    /** 一个自定义几何（回调稍后发射顶点），进 alwaysOnTop 相位。 */
    static void submitCustomGeometry(OrderedSubmitNodeCollector collector, PoseStack poseStack,
                                     RenderType renderType,
                                     net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer renderer) {
        CustomFeatureRenderer.Submit node = new CustomFeatureRenderer.Submit(
                poseStack.last().copy(), renderType, renderer);
        if (submitOnTop(collector, node)) return;
        collector.submitCustomGeometry(poseStack, renderType, renderer);
    }

    /** 编译期锚点：neoforge 侧 SubmitNodeStorage 类型引用（防误删 import）。 */
    @SuppressWarnings("unused")
    private static Class<?> anchor() {
        return SubmitNodeStorage.class;
    }

    @SuppressWarnings("unused")
    private static Class<?> collectionAnchor() {
        return SubmitNodeCollection.class;
    }
}
