package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.mc.WorldLyricsHudDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「更激进的适配」的 26.2 绘制时机 —— 已在同类 3D 歌词渲染中验证：
 * 挂在 {@code GameRenderer.renderLevel} 里 {@code LevelRenderer.render} 调用的
 * <b>之后</b>（AFTER）：此时世界帧图已执行完、Iris 合成链已收工，mainRenderTarget
 * 里就是光影处理完的最终画面；手部/GUI 未画、present 的 blit 未发生 ——
 * 在这里画进 mainTarget，字随原版正常链路上屏。
 */
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public class GameRendererRenderLevelMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void muonium$lyricsAfterLevel(CallbackInfo ci) {
        WorldLyricsHudDraw.worldRenderDraw();
    }
}
