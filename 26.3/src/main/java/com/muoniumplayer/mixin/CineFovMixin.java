package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineDirector;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「卡点运镜」的 FOV 脉冲：鼓点上广角冲出、指数回落（punch）。
 *
 * <p>注入点 {@code Camera.getFov()}（javap 核对 26.3 merged jar：public、由
 * {@code GameRenderer.extractCamera} 每帧调用写入 {@code CameraRenderState}）——
 * 在返回值上叠加 {@link CineDirector#fovOffsetDeg()}。FOV 的瞬时偏移与位姿
 * 解耦：不碰投影矩阵（{@code ProjectionMatrixBufferMixin} 那条链保持只读）。</p>
 */
@Mixin(Camera.class)
abstract class CineFovMixin {

    @Inject(method = "getFov", at = @At("RETURN"))
    private void muonium$cineFovPunch(CallbackInfoReturnable<Float> cir) {
        if (!CineDirector.isActive()) return;
        float offset = CineDirector.fovOffsetDeg();
        if (offset != 0f) {
            cir.setReturnValue(cir.getReturnValue() + offset);
        }
    }
}
