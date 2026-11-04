package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineDirector;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 「卡点运镜」的 FOV 脉冲：挂在 Camera.getFov 返回值上(26.x getFov 是 public 逐帧路径)。 */
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
