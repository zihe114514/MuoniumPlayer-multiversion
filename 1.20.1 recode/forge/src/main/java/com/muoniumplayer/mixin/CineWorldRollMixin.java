package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineCameraRotation;
import com.muoniumplayer.core.cine.CinePose;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.ViewportEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 把同次已应用位姿交给 Forge 的后置欧拉/roll 世界视图，非运镜事件保持原样。 */
@Mixin(GameRenderer.class)
abstract class CineWorldRollMixin {

    @Redirect(method = "renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/client/ForgeHooksClient;onCameraSetup(Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/Camera;F)Lnet/minecraftforge/client/event/ViewportEvent$ComputeCameraAngles;",
                    remap = false),
            require = 1, allow = 1)
    private ViewportEvent.ComputeCameraAngles muonium$cineWorldAngles(GameRenderer renderer, Camera camera,
                                                                     float partialTick) {
        ViewportEvent.ComputeCameraAngles event = ForgeHooksClient.onCameraSetup(renderer, camera, partialTick);
        CinePose pose = ((CineCameraRotation) camera).cineAppliedPose();
        if (pose != null) {
            // 事件仍按原路径完整派发；运镜独占期间用已应用的绝对姿态统一后置欧拉与世界 roll。
            event.setYaw(pose.yawDeg());
            event.setPitch(pose.pitchDeg());
            // 1.20.1 相机坐标约定要求世界 Z 旋转取 -roll，与 Q 和基向量一致。
            event.setRoll(-pose.rollDeg());
        }
        return event;
    }
}
