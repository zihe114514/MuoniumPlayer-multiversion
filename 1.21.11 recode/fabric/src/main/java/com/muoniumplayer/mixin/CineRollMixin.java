package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineCameraRotation;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** 在原版旋转 setter 构造四元数时加入 roll，让基向量随最终姿态一起更新。 */
@Mixin(Camera.class)
abstract class CineRollMixin {

    @ModifyArg(method = "setRotation(FF)V",
            at = @At(value = "INVOKE",
                    target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;", remap = false),
            index = 2, require = 1, allow = 1)
    private float muonium$cineRollRadians(float originalRollRadians) {
        float roll = ((CineCameraRotation) (Object) this).cineRollDegrees();
        return Float.isFinite(roll) ? -(float) Math.toRadians(roll) : originalRollRadians;
    }
}
