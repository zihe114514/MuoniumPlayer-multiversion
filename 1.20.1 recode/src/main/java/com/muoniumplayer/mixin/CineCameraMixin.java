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
 *
 * <p>1.21.11 之前 setup 首参是 BlockGetter 之前 Camera 没有 alignWithEntity——注入点是 {@code setup} 内首个
 * {@code setPosition(DDD)}(眼位锚点定下之处),与 TransparentCameraMixin 同点。
 * 26.x 的写法见 26.3 树同名文件。</p>
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

    @Inject(method = "setup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", ordinal = 0,
            shift = At.Shift.AFTER))
    private void muonium$cinePose(net.minecraft.world.level.BlockGetter level, CallbackInfo ci) {
        if (!CineDirector.isActive()) return;
        if (!(entity instanceof net.minecraft.world.entity.player.Player player)
                || player != net.minecraft.client.Minecraft.getInstance().player
                || !player.isAlive()) {
            return;
        }
        Camera self = (Camera) (Object) this;
        Vec3 pos = self.getPosition();
        CinePose pose = CineDirector.currentPose(pos.x, pos.y, pos.z);
        if (pose == null) return;
        this.setPosition(pose.x(), pose.y(), pose.z());
        this.setRotation(pose.yawDeg(), pose.pitchDeg());
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void muonium$cineFinalize(net.minecraft.world.level.BlockGetter level, CallbackInfo ci) {
        if (!CineDirector.isActive()) return;
        Camera self = (Camera) (Object) this;
        Vec3 pos = self.getPosition();
        CinePose pose = CineDirector.currentPose(pos.x, pos.y, pos.z);
        if (pose == null) return;
        float roll = Math.max(-muonium$cineMaxRollDeg, Math.min(muonium$cineMaxRollDeg, pose.rollDeg()));
        if (Math.abs(roll) < 0.01f) return;
        Quaternionf rotation = self.rotation();
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(rotation);
        rotation.rotateAxis((float) Math.toRadians(roll), forward);
    }
}
