package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.mc.WorldLyricsBridge;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 3D 世界歌词的绘制时机修正 —— <b>Fabric 1.21.11 侧</b>（NeoForge 侧走
 * {@code RenderLevelStageEvent.AfterLevel}，本身就在帧图之后，不需要本 mixin）。
 *
 * <h2>为什么换钩子（云盖住歌词的根因）</h2>
 * 之前挂在 fabric-api 的 {@code WorldRenderEvents.END_MAIN} —— javap 核对
 * fabric-rendering-v1 16.2.10 的 {@code LevelRendererMixin.endMainRender} 注入点：
 * {@code @Inject(method = "method_62214" (addMainPass), at = INVOKE:LAST
 * MultiBufferSource$BufferSource.endBatch()V)}，也就是 <b>main pass 执行体的末尾</b> ——
 * 实体/地形/半透明画完，但云（clouds pass）、天气（weather pass）、透明后期链
 * （transparencyChain）都是 main pass <b>之后</b>的独立 pass。歌词画得太早，
 * 云直接混叠盖字（1.21.11 的云在 fantastic 关闭时写 main target）。
 *
 * <p>1.21.11 的 fabric-api 世界事件里没有「云之后」的挂点
 * （{@code BEFORE_DEBUG_RENDER} 注入在 extract 阶段的 {@code DebugRenderer#emitGizmos}，
 * 更早），所以本树自己加 mixin：注入 {@code LevelRenderer#renderLevel} 的 TAIL ——
 * 帧图已执行完（云、天气、后期链全部落屏），modelViewStack 还在栈上（带视图旋转），
 * 与 END_MAIN 事件时相同的矩阵环境。{@code WorldLyricsBridge.worldRenderEnd()} 自己
 * push 单位矩阵 + 自算相机旋转，不依赖栈上状态，两处都能安全调用。</p>
 *
 * <p>时序对应 NeoForge 侧 {@code GameRenderer#renderLevel} 里
 * {@code levelRenderer.renderLevel(...)} 返回之后的 {@code AfterLevel} 派发 ——
 * 同样在帧图后、手持物品（会先清主目标深度）之前。此处的深度缓存里是世界深度，
 * Bridge 里关掉深度测试画字，歌词永远可见、永远盖在云上。</p>
 *
 * <p>只注册在 fabric 加载器（本文件只进 fabric 的 mixins.json）；
 * neoforge 的 mixins.json 不含它，避免两树世界渲染跑两遍。</p>
 */
@Mixin(LevelRenderer.class)
public class WorldLyricsRenderTailMixin {

    @Inject(method = "renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
            + "Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;"
            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;"
            + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            at = @At("TAIL"))
    private void muonium$renderWorldLyricsAfterFrameGraph(CallbackInfo ci) {
        WorldLyricsBridge.worldRenderEnd();
    }
}
