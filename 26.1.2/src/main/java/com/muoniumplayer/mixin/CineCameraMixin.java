package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineDirector;
import com.muoniumplayer.core.cine.CinePose;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「卡点运镜」的相机接管：{@link CineDirector#isActive()} 时整体覆写相机位姿。
 * 注入点与 TransparentCameraMixin 相邻(alignWithEntity 首个 setPosition 之后)。
 */
@Mixin(Camera.class)
abstract class CineCameraMixin {

    @Shadow
    private Entity entity;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Unique
    private static final float muonium$cineMaxRollDeg = 12f;

    @Inject(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", ordinal = 0,
            shift = At.Shift.AFTER))
    private void muonium$cinePose(float partialTick, CallbackInfo ci) {
        if (!CineDirector.isActive()) return;
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)
                || player != net.minecraft.client.Minecraft.getInstance().player
                || !player.isAlive()) {
            return;
        }
        Camera self = (Camera) (Object) this;
        Vec3 pos = self.position();
        CinePose pose = CineDirector.currentPose(pos.x, pos.y, pos.z);
        if (pose == null) return;
        this.setPosition(pose.x(), pose.y(), pose.z());
        this.setRotation(pose.yawDeg(), pose.pitchDeg());
    }

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void muonium$cineFinalize(float partialTick, CallbackInfo ci) {
        if (!CineDirector.isActive()) return;
        Camera self = (Camera) (Object) this;
        Vec3 pos = self.position();
        CinePose pose = CineDirector.currentPose(pos.x, pos.y, pos.z);
        if (pose == null) return;
        float roll = Math.max(-muonium$cineMaxRollDeg, Math.min(muonium$cineMaxRollDeg, pose.rollDeg()));
        if (Math.abs(roll) < 0.01f) return;
        Quaternionf rotation = self.rotation();
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(rotation);
        rotation.rotateAxis((float) Math.toRadians(roll), forward);
    }
}
