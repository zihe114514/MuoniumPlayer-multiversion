package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineDirector;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「卡点运镜」的 FOV 脉冲。26.x 前 之前 FOV 在
 * {@code GameRenderer.getFov(Camera, float, boolean)}(private,由 renderLevel
 * 每帧调一次算投影)——注入其 RETURN 叠加偏移。26.x 的写法见 26.3 树同名文件。
 */
@Mixin(GameRenderer.class)
abstract class CineFovMixin {

    @Inject(method = "getFov", at = @At("RETURN"))
    private void muonium$cineFovPunch(CallbackInfoReturnable<Double> cir) {
        if (!CineDirector.isActive()) return;
        float offset = CineDirector.fovOffsetDeg();
        if (offset != 0f) {
            cir.setReturnValue((double) (cir.getReturnValue() + offset));
        }
    }
}
