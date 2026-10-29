package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.mc.WorldLyricsHudDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「更激进的适配」的 26.x 绘制时机 —— 已在同类 3D 歌词渲染中验证：
 * 挂在 {@code GameRenderer.renderLevel} 里 {@code LevelRenderer.render} 调用的
 * <b>之后</b>（AFTER）：此时世界帧图已执行完、Iris 的合成链也已收工
 * （Iris 的 finalizeLevelRendering 注入在 LevelRenderer.render 内部的 popMatrix），
 * mainRenderTarget 里就是光影处理完的最终画面；而手部/GUI 还没画、
 * present 的 blit 还没发生 —— 在这里画进 mainTarget，字随原版正常链路上屏。
 *
 * <p>此前挂在 {@code GlSurface.present()} 入口晚了整整一个阶段：blit 已把
 * mainTarget 送进窗口，画进 mainTarget 的字永远赶不上 swap。</p>
 */
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public class GameRendererRenderLevelMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V",
            shift = At.Shift.AFTER
        )
    )
    private void muonium$lyricsAfterLevel(CallbackInfo ci) {
        WorldLyricsHudDraw.worldRenderDraw();
    }
}
