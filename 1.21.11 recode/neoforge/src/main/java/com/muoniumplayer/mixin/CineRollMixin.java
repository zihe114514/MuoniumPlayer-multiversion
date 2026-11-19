package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineCameraRotation;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** 把 roll 交给 NeoForge 三参 setter，同时维护 roll 字段、四元数和基向量。 */
@Mixin(Camera.class)
abstract class CineRollMixin {

    @ModifyArg(method = "setRotation(FF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FFF)V"),
            index = 2, require = 1, allow = 1)
    private float muonium$cineRollDegrees(float originalRollDegrees) {
        float roll = ((CineCameraRotation) (Object) this).cineRollDegrees();
        return Float.isFinite(roll) ? roll : originalRollDegrees;
    }
}
