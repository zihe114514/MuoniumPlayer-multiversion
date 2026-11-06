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
 * <p>单注入点（alignWithEntity TAIL）：TAIL 在原版全部修正——第三人称反转、
 * 缩放碰撞、（26.3）通透模式的眼位下调——之后执行，运镜 pose 是最终绝对机位，
 * 不会被后续阶段破坏。此前用「INVOKE AFTER + TAIL」双注入点各调一次
 * {@link CineDirector#currentPose} 是实机抽搐的根因：currentPose 带状态推进
 * （平滑基线/cutPending 消费），一帧多调会让机位在吸附值与平滑值之间跳变，
 * 且 INVOKE AFTER 点设的 pose 还会被后续第三人称反转改掉。</p>
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

    /**
     * TAIL 终裁：整帧一次，覆盖位置与朝向，再乘 roll（荷兰角 ≤12°，防 3D 晕）。
     * 相机实体必须是本地玩家本人——观战/铜像视角不该被运镜抢走。
     */
    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void muonium$cineApply(float partialTick, CallbackInfo ci) {
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
        float roll = Math.max(-muonium$cineMaxRollDeg, Math.min(muonium$cineMaxRollDeg, pose.rollDeg()));
        if (Math.abs(roll) < 0.01f) return;
        Quaternionf rotation = self.rotation();
        // 绕视线轴转 roll：先取 forward 向量，再绕它把当前旋转转 roll 度。
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(rotation);
        rotation.rotateAxis((float) Math.toRadians(roll), forward);
    }
}
