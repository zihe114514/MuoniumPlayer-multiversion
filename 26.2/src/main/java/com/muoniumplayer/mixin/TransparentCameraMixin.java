package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.TransparentMode;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「通透模式」的摄像机跟随：玩家自己也被压扁时，镜头眼高跟着一起降。
 *
 * <p>挂在 {@code alignWithEntity} 里首个 {@code setPosition(DDD)} 之后——那正是
 * 原版按 {@code eyeHeight} 插值定好眼位锚点的地方；在这里按同一比例修正，
 * 第三人称反转、缩放碰撞与移动都发生在后面，不受影响。模型矩阵那半在
 * {@link TransparentEntityMixin}。</p>
 */
@Mixin(Camera.class)
abstract class TransparentCameraMixin {

    @Shadow
    private Entity entity;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", ordinal = 0, shift = At.Shift.AFTER))
    private void transmode$camera(float partialTick, CallbackInfo ci) {
        if (!(entity instanceof Player player) || player != Minecraft.getInstance().player
                || !player.isAlive() || player.isSleeping()) return;
        float heightScale = TransparentMode.heightScale();
        if (heightScale >= 1f) return;
        Vec3 position = ((Camera) (Object) this).position();
        double feetY = player.getY();
        double offset = (position.y - feetY) * (heightScale - 1);
        setPosition(position.x, position.y + offset, position.z);
    }
}
