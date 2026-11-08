package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.MusicSpin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「陀螺模式·头乱晃」的头部动画（单段式世代）：{@code setupAnim} 之后、绘制
 * 之前，直接给头部件加 yaw/pitch 差值。
 *
 * <p>1.20.1/1.21.1 没有 extract 渲染态，头部角度在 {@code setupAnim} 里写进
 * {@code ModelPart}（{@code HeadedModel.getHead()} 的 xRot/yRot，弧度）——用
 * @Redirect 包住原调用：先原样 setupAnim，再叠加共享层的头部差值。模型角度是
 * 弧度、共享层给的是度，换算系数 2π/180 与原版 setupAnim 的写法一致。
 * {@code SpinEntityMixin} 的 pose 括号转的是整模型，头部差值叠在部件上，两者
 * 自然复合。头乱晃是纯本机视觉，不碰实体本体与服务端状态。</p>
 */
@Mixin(LivingEntityRenderer.class)
abstract class SpinHeadMixin {

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"))
    private void spin$headAnim(EntityModel model, Entity entity, float limbSwing, float limbSwingAmount,
                               float ageInTicks, float netHeadYaw, float headPitch) {
        model.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        boolean isSelf = entity == Minecraft.getInstance().player;
        if (!MusicSpin.headShakeActiveFor(isSelf, entity instanceof Player)) {
            return;
        }
        if (!(model instanceof HeadedModel headed)) {
            // 没头的生物（少数模型）不参与头部动作。
            return;
        }
        float[] head = MusicSpin.headDeltas();
        if (head[0] == 0f && head[1] == 0f) {
            return;
        }
        ModelPart headPart = headed.getHead();
        headPart.yRot += head[0] * ((float) Math.PI / 180F);
        headPart.xRot += head[1] * ((float) Math.PI / 180F);
    }
}
