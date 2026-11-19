package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineCameraRotation;
import com.muoniumplayer.core.cine.CineDirector;
import com.muoniumplayer.core.cine.CinePose;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 alignWithEntity 的 TAIL 应用本帧绝对机位，随后由原版建立视图、裁剪和投影状态。
 * roll 只在本模组的 setRotation 调用内生效，由加载器适配交给原版 setter 统一更新姿态。
 */
@Mixin(Camera.class)
abstract class CineCameraMixin implements CineCameraRotation {

    @Shadow
    private Entity entity;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Unique
    private float muonium$cineRollDegrees = Float.NaN;

    @Override
    public float cineRollDegrees() {
        return muonium$cineRollDegrees;
    }

    @Inject(method = "alignWithEntity(F)V", at = @At("TAIL"), require = 1, allow = 1)
    private void muonium$cineApply(float partialTick, CallbackInfo ci) {
        if (!CineDirector.isActive()) return;
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)
                || player != net.minecraft.client.Minecraft.getInstance().player
                || !player.isAlive()) {
            return;
        }
        CinePose pose = CineDirector.currentPose(partialTick);
        if (pose == null) return;
        this.setPosition(pose.x(), pose.y(), pose.z());
        muonium$cineRollDegrees = pose.rollDeg();
        try {
            this.setRotation(pose.yawDeg(), pose.pitchDeg());
        } finally {
            muonium$cineRollDegrees = Float.NaN;
        }
    }
}
