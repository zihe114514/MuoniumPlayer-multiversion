package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.mc.LyricOnTopSubmitter;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/**
 * 3D 世界歌词 always-on-top 相位的执行门修补 —— <b>Fabric 26.3 专属</b>。
 *
 * <h2>为什么需要这颗 mixin（26.3 歌词整体无效果的根因）</h2>
 * 上一轮把歌词 submit 挪进 {@code alwaysOnTopGizmos} 相位修「云/玻璃盖字」
 * （commit 2a18fc7）。逐字节核对了三个 jar 的 always-on-top pass 执行门：
 * <ul>
 *   <li>26.2 vanilla（双加载器共用语义）：门是 {@code featureFrame.hasAnyAlwaysOnTop()}
 *       —— 相位非空就执行，我们的提交能画；</li>
 *   <li>26.3 <b>NeoForge</b>（patched 26.3.0.16-beta）：vanilla 的门被 Neo 补丁改成
 *       {@code frameHasAlwaysOnTopGizmos() || featureFrame.hasAnyAlwaysOnTop()}
 *       （patched sources 注释 "Neo: also check the FeatureFrame …"），我们的提交也能画；</li>
 *   <li>26.3 <b>vanilla（Fabric 跑的就是它）</b>：门只剩
 *       {@code frameHasAlwaysOnTopGizmos()} —— 只看「帧里有没有 always-on-top gizmo
 *       原语」（F3 调试 gizmo 之类）。整个 merged jar 里没有 {@code hasAnyAlwaysOnTop}
 *       方法（javap 计数为 0），两处调用点：{@code render} 偏移 483（决定建不建
 *       {@code always_on_top_depth} 目标 + hasAlwaysOnTopGizmos 局部）与
 *       {@code addMainPass} 偏移 239（决定 main pass 末尾调不调 executeAlwaysOnTop）。</li>
 * </ul>
 * 结果：歌词节点被正常 drain、正常 buildGroup 发顶点、进 groupsByPhase —— 然后
 * {@code executeAlwaysOnTop} <b>从不被调用</b>（gizmo 原语为空 → 门关），分组随
 * {@code PreparedFrame.close()} 丢弃。全程无异常、无日志，两种风格都静默不显示。
 * NeoForge 侧门里已含 OR 半句，<b>这颗 mixin 只注册在 fabric</b>。
 *
 * <h2>修补方式</h2>
 * 用 MixinExtras 的 {@code @ModifyExpressionValue} 把两处
 * {@code frameHasAlwaysOnTopGizmos()} 的返回值 OR 上「本帧歌词提交过 always-on-top」
 * （{@link LyricOnTopSubmitter#submittedThisFrame}，由 Bridge 在帧头清零、提交成功时
 * 置位）。本帧没提交就保持 vanilla 门原样（不空开 pass）；真常开的代价也只是多一个
 * 空 pass，无副作用。fabric-loader 0.19.5 内嵌 mixinextras-fabric 0.5.5，
 * {@code @ModifyExpressionValue} 无需额外依赖。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererAlwaysOnTopGateMixin {

    @ModifyExpressionValue(
            method = {
                    "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
                    "addMainPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Z)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;frameHasAlwaysOnTopGizmos()Z"
            ))
    private boolean muonium$orNonGizmoAlwaysOnTop(boolean vanilla) {
        return vanilla || LyricOnTopSubmitter.submittedThisFrame;
    }
}
