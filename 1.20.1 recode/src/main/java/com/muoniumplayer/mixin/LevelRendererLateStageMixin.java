package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.mc.WorldLyricsBridge;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 3D 世界歌词的「真正最上层」绘制窗口 —— <b>1.20.1 专属</b>。
 *
 * <h2>为什么需要它（水/云/模型盖住歌词的根因）</h2>
 * 1.20.1 的 Fabric 侧挂在 {@code WorldRenderEvents.LAST}，它的注入点是
 * {@code renderChunkDebugInfo}（javap 实测：fabric-rendering-v1 3.0.9 的
 * {@code WorldRendererMixin.onChunkDebugRender}），换算到 mojmap 是
 * {@code LevelRenderer.method_22989}。<b>这个方法在 {@code renderLevel} 的末尾
 * 才被调用</b> —— 而在此之前，原版已经画完了：
 * <ol>
 *   <li>第一遍半透明地形（水、玻璃）；</li>
 *   <li>粒子（Fabulous 下先进 particlesTarget）；</li>
 *   <li>第二遍半透明地形（Fabulous 下进 translucentTarget）;</li>
 *   <li>云（{@code renderClouds}）；</li>
 *   <li>天气（{@code renderSnowAndRain}）与世界边界（{@code renderWorldBorder}）。</li>
 * </ol>
 * 也就是说 {@code LAST} 实际上落在<b>这些之后</b>才轮到 {@code renderDebug}。
 * 但 {@code renderDebug} 本身只画 F3 的区块边框，且 Fabulous 模式下
 * {@code translucentTarget} 的合成（{@code transparencyChain.process}）发生在
 * 云之后 —— 歌词虽然画在主目标上，但半透明目标的内容随后会<b>叠回主目标</b>，
 * 水/玻璃那一层就这么盖到了歌词身上。用户实测「字会被水挡住、会被模型挡住」，
 * 正是这一层的直接后果。
 *
 * <h2>修法</h2>
 * 在 {@code renderLevel} <b>真正返回之后</b>再画一次：那时半透明合成、云、天气、
 * 世界边界全部结束，主目标就是最终画面，歌词画上去没有任何东西能再盖。
 * 注入 {@code RETURN}（此时 {@code RenderLevelStageEvent} 的 Forge 侧
 * {@code AFTER_LEVEL} 也是同一时点，两边口径由此对齐）。
 *
 * <p>重入保护：{@code Bridge} 的一条帧内标志位保证同一帧只画一次 ——
 * Fabric 的 {@code LAST} 已经画过时这里跳过，避免重复叠加（加色晕会被画两遍）。</p>
 *
 * <p>GL 状态：{@code renderLevel} 返回前已把 depthMask 复位、blend 关闭、
 * 雾关掉；{@code worldRenderEnd()} 自己会重新设它需要的状态并在结束时还原，
 * 所以这里直接调就行，不需要额外包装。</p>
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererLateStageMixin {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void muonium$drawLyricsOnTop(PoseStack poseStack, float partialTick, long finishNanos,
                                         boolean renderBlockOutline, Camera camera,
                                         GameRenderer gameRenderer, LightTexture lightTexture,
                                         Matrix4f projectionMatrix, CallbackInfo ci) {
        // 「更激进的适配」（光影包 → HUD 阶段直画）开启时，歌词要推迟到 HUD 阶段，
        // 这里不能抢画 —— 否则光影后处理会把它当世界内容二次加工（Bridge 类注释）。
        if (WorldLyricsBridge.defersToHudPhase()) return;
        WorldLyricsBridge.worldRenderEnd();
    }
}
