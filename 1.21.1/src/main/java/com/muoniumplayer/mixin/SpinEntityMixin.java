package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.MusicSpin;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「陀螺模式」的实体旋转：把绕 Y 的旋转矩阵包住原版的实体渲染括号（单段式世代）。
 *
 * <p>1.20.1/1.21.1 的 {@code EntityRenderDispatcher.render(...)} 没有 extract 阶段
 * （双段式树的 bodyRot 字段路线见那边的同名 mixin），注入点与
 * {@link TransparentEntityMixin} 同坑位：首次 {@code translate} 落地后推矩阵、在
 * 火焰动画读取前还原——名牌画在火焰读取点之后，火焰、阴影、碰撞箱调试框都在
 * 括号外，不受旋转污染。旋转只动本机画面，不碰实体本体与服务端状态。</p>
 */
@Mixin(EntityRenderDispatcher.class)
abstract class SpinEntityMixin {

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0, shift = At.Shift.AFTER))
    private void spin$begin(Entity entity, double x, double y, double z, float yaw, float partialTick,
                            PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        // push/pop 的条件必须逐字对称（TransparentEntityMixin 的实测坑）：两端都走
        // entitySpins —— 纯判定、无状态推进，隔着一次模型渲染结果也恒等。
        if (!MusicSpin.entitySpins(entity == Minecraft.getInstance().player,
                entity instanceof Player, entity instanceof LivingEntity)) {
            return;
        }
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(
                MusicSpin.entityModelAngle(entity == Minecraft.getInstance().player, entity.getYRot())));
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;displayFireAnimation()Z"))
    private void spin$end(Entity entity, double x, double y, double z, float yaw, float partialTick,
                          PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (MusicSpin.entitySpins(entity == Minecraft.getInstance().player,
                entity instanceof Player, entity instanceof LivingEntity)) {
            pose.popPose();
        }
    }
}
