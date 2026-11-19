package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineDirector;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在世界 FOV 写回相机之前叠加同一 partialTick 的脉冲，使主投影和裁剪一起更新。
 * calculateHudFov 保留原值；setReturnValue 必须声明 cancellable。
 */
@Mixin(Camera.class)
abstract class CineFovMixin {

    @Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true, require = 1)
    private void muonium$cineFovPunch(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (!CineDirector.isActive()) return;
        float offset = CineDirector.fovOffsetDeg(partialTick);
        if (offset != 0f) {
            cir.setReturnValue(cir.getReturnValue() + offset);
        }
    }
}
