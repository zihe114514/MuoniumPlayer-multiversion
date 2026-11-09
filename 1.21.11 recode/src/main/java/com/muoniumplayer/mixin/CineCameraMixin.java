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
 * <p>单注入点（setup TAIL）：1.21.11 的 setup 首参是 Level（26.x 前最后一代），
 * 1.21.10 及以前是 BlockGetter。TAIL 在原版全部修正——第三人称反转、缩放碰撞
 * （{@code move(-maxZoom)}）、睡觉眼位偏移——之后执行，运镜 pose 是最终绝对机位。
 * 此前「INVOKE setPosition AFTER + TAIL」双注入点的毛病（javap 核对 1.21.11 字节码）
 * 与 1.21.1 同款：INVOKE AFTER 点设的 pose 被后面的第三人称 {@code move} 再推走、
 * currentPose 一帧两调状态双推进——实机表现为运镜完全不生效。
 * 26.3 的修法见同名文件：单 TAIL 终裁。</p>
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
    @Inject(method = "setup", at = @At("TAIL"))
    private void muonium$cineApply(net.minecraft.world.level.Level level, Entity entityRef, boolean detached,
                                   boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
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
