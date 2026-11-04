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
 * <p>注入点与 {@link TransparentCameraMixin} 相邻——都在 {@code alignWithEntity}
 * 的首个 {@code setPosition(DDD)} 之后。共存的语义：通透模式先按果冻压缩量下调
 * 眼位，本 mixin 在其后用运镜 pose 整体覆盖（含 FOV punch 的注入点在
 * {@link CineFovMixin}）。运镜开启时通透模式的眼位偏移被覆盖属预期行为：
 * 电影镜头本来就是自由机位，眼高锚定没有意义。</p>
 *
 * <p>roll（荷兰角）：26.3 的 {@code setRotation} 只有 yaw/pitch 两参，roll 通过
 * 重写 rotation 四元数实现——setRotation 内部会用 yaw/pitch 重建四元数，之后
 * 在本 mixin 尾部把 roll 乘上去。注入点选 TAIL 保证四元数已定型。</p>
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
     * 运镜接管：位置 + 朝向整体覆写。相机实体必须是本地玩家本人——
     * 观战/铜像视角不该被运镜抢走。
     */
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
        // roll 附加：本注入点之后 alignWithEntity 还会做第三人称反转与缩放碰撞，
        // 但运镜 pose 是绝对机位，后续修正会破坏构图——因此这里在 TAIL 再压一次。
        // 见 muonium$cineFinalize。
    }

    /**
     * TAIL 终裁：运镜开启时在四元数上乘 roll（荷兰角 ≤12°，防 3D 晕）。
     * 只在 isActive 时生效，退出后原版相机路径不受影响。
     */
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
        // 绕视线轴转 roll：先取 forward 向量，再绕它把当前旋转转 roll 度。
        Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(rotation);
        rotation.rotateAxis((float) Math.toRadians(roll), forward);
    }
}
