package com.muoniumplayer.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.muoniumplayer.core.cine.CineCameraRotation;
import com.muoniumplayer.core.cine.CinePose;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.20.1 的世界视图按欧拉角构造，需在 pitch/yaw 前消费同次相机更新已应用的 roll。 */
@Mixin(GameRenderer.class)
abstract class CineWorldRollMixin {

    @Shadow
    public abstract Camera getMainCamera();

    @Inject(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getXRot()F"),
            require = 1, allow = 1)
    private void muonium$cineWorldRoll(float partialTick, long finishTime, PoseStack view, CallbackInfo ci) {
        CinePose pose = ((CineCameraRotation) getMainCamera()).cineAppliedPose();
        if (pose == null) return;
        // 本版 Q = Ry(-yaw) Rx(pitch) Rz(-roll)，世界 V = Ry(pi) Q^-1 = Rz(-roll) V0。
        view.mulPose(Axis.ZP.rotationDegrees(-pose.rollDeg()));
    }
}
