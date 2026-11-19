package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineDirector;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 仅对世界主投影叠加同一 partialTick 的 FOV 脉冲，保留手部投影的原值。
 * 返回类型为 float；setReturnValue 需要 cancellable，避免脉冲非零时抛出取消异常。
 */
@Mixin(GameRenderer.class)
abstract class CineFovMixin {

    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F", at = @At("RETURN"),
            cancellable = true, require = 1)
    private void muonium$cineFovPunch(Camera camera, float partialTick, boolean worldFov,
                                      CallbackInfoReturnable<Float> cir) {
        if (!worldFov || !CineDirector.isActive()) return;
        float offset = CineDirector.fovOffsetDeg(partialTick);
        if (offset != 0f) {
            cir.setReturnValue(cir.getReturnValue() + offset);
        }
    }
}
