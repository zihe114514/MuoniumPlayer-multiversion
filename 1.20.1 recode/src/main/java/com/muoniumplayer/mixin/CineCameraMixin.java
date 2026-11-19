package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineCameraRotation;
import com.muoniumplayer.core.cine.CineDirector;
import com.muoniumplayer.core.cine.CinePose;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在相机 setup 的 TAIL 应用本帧绝对机位，保留原版第三人称与碰撞修正的执行顺序。
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

    @Unique
    private CinePose muonium$cineAppliedPose;

    @Override
    public float cineRollDegrees() {
        return muonium$cineRollDegrees;
    }

    @Override
    public CinePose cineAppliedPose() {
        Minecraft mc = Minecraft.getInstance();
        return CineDirector.isActive() && mc.player != null && mc.player.isAlive()
                && mc.getCameraEntity() == mc.player && entity == mc.player
                && mc.gameRenderer.getMainCamera() == (Object) this ? muonium$cineAppliedPose : null;
    }

    @Inject(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("HEAD"), require = 1, allow = 1)
    private void muonium$cineClearAppliedPose(net.minecraft.world.level.BlockGetter level, Entity entityRef,
                                             boolean detached, boolean thirdPersonReverse, float partialTick,
                                             CallbackInfo ci) {
        muonium$cineAppliedPose = null;
    }

    @Inject(method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
            at = @At("TAIL"), require = 1, allow = 1)
    private void muonium$cineApply(net.minecraft.world.level.BlockGetter level, Entity entityRef, boolean detached,
                                   boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
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
            muonium$cineAppliedPose = pose;
        } finally {
            muonium$cineRollDegrees = Float.NaN;
        }
    }
}
