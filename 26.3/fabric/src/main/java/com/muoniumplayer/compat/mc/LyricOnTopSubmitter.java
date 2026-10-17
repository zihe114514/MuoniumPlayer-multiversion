package com.muoniumplayer.compat.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.muoniumplayer.core.utils.logging.LogManager;
import com.muoniumplayer.core.utils.logging.Logger;

import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * 3D 世界歌词的「永远画在最上层」提交桥 —— <b>Fabric 26.3 侧</b>。
 *
 * <h2>为什么需要它（云和玻璃盖住歌词的根因）</h2>
 * 26.3 的世界渲染是 submit-collect：{@code submitCustomGeometry} 按 RenderType
 * 分相位 —— 带混合的（我们用的 {@code textSeeThrough} 正是）进
 * {@code translucentCustomGeometry} 相位，在 classic transparency 路径的
 * <b>半透明地形（玻璃）之前</b>画（javap 核对：
 * {@code FeatureRenderDispatcher.executeTranslucent()} 里
 * {@code executePhase(collection.translucentCustomGeometry, …)} 在
 * {@code chunkSectionsToRender.renderGroup(TRANSLUCENT, …)} 之前），云也在
 * {@code executeClassicTransparency} 里、更靠后。结果玻璃和云都画在歌词<b>之后</b>，
 * 直接混叠盖字 —— 玩家实测「玻璃和云都能盖住 3D 歌词」的根因。
 * （改进透明(OIT)路径下同相位进 {@code oitTranslucent}，同样在云之前合成。）
 *
 * <p>修法是把歌词 submit 挪进 {@code alwaysOnTopGizmos} 相位（javap 核对
 * fabric-rendering-v1 27.0.14 的 {@code SubmitRenderPhases.ALWAYS_ON_TOP} lambda
 * 与 neoforge 26.3.0.16 的 {@code RenderPhaseKeys.ALWAYS_ON_TOP} 都解析到它）：
 * 它由 {@code LevelRenderer.executeAlwaysOnTop} 在 <b>main pass 的最后</b>执行
 * （solid → classic transparency/OIT → outline → see-through 之后，云也在其前），
 * 执行前先 {@code clearDepthTexture(depth, 0.0)}（深度清到远平面 = 无深度测试）——
 * 歌词永远画在云、玻璃、天气之上，手持物品（独立 pass + 手前清深度）仍在其上。</p>
 *
 * <h2>Fabric 26.3 的通路</h2>
 * fabric-api 给 {@code OrderedSubmitNodeCollector} 混入了
 * {@code submitCustom(SubmitRenderPhase, SubmitNode)}，配套常量表
 * {@link SubmitRenderPhases}。NeoForge 侧同款能力走
 * {@code submitSpecial(RenderPhaseKeys.ALWAYS_ON_TOP, …)}（见 neoforge 外壳同名类）。
 *
 * <p>submit 节点用原版现成的 {@link CustomFeatureRenderer.Submit} public record
 * （与 {@code submitCustomGeometry} 造的节点同构），相位分组与特征渲染器都按
 * submit 的 {@code featureType()} 走 —— 换相位不换绘制代码。</p>
 */
final class LyricOnTopSubmitter {

    private static final Logger logger = LogManager.getLogger("MuoniumPlayer/WorldLyrics");

    /** API 缺位/相位缺失时一次性降级，避免日志刷屏。 */
    private static boolean broken;

    private LyricOnTopSubmitter() {
    }

    private static boolean submitOnTop(SubmitNodeCollector collector,
                                       SubmitRenderPhase<SubmitNode> phase, SubmitNode node) {
        if (collector == null || broken) return false;
        try {
            collector.submitCustom(phase, node);
            return true;
        } catch (Throwable t) {
            broken = true;
            logger.error("3D 歌词 always-on-top 提交通路不可用，退回普通相位（会被云/玻璃遮挡）", t);
            return false;
        }
    }

    /** 一个自定义几何（回调稍后发射顶点），进 alwaysOnTop 相位。 */
    static void submitCustomGeometry(SubmitNodeCollector collector, PoseStack poseStack,
                                     RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer renderer) {
        CustomFeatureRenderer.Submit node = new CustomFeatureRenderer.Submit(
                poseStack.last().copy(), renderType, renderer);
        if (submitOnTop(collector, SubmitRenderPhases.ALWAYS_ON_TOP, node)) return;
        collector.submitCustomGeometry(poseStack, renderType, renderer);
    }

    /** 编译期锚点：fabric 侧 SubmitNodeCollection 类型引用（防误删 import）。 */
    @SuppressWarnings("unused")
    private static Class<?> anchor() {
        return SubmitNodeCollection.class;
    }
}
