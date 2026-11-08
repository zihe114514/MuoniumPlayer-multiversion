package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.TransparentMode;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「通透模式」的摄像机跟随：玩家自己也被压扁时，镜头眼高跟着一起降。
 *
 * <p>1.21.11 没有 26.x 的 {@code alignWithEntity}，眼位锚点就在 {@code setup} 内
 * 首个（也是唯一一个）{@code setPosition(DDD)}——按 eyeHeight lerp 完成的那个；
 * 在这里按同一比例修正，第三人称反转、缩放碰撞与移动都发生在后面，不受影响。
 * 模型矩阵那半在 TransparentEntityMixin。</p>
 */
@Mixin(Camera.class)
abstract class TransparentCameraMixin {

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "setup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", ordinal = 0, shift = At.Shift.AFTER))
    private void transmode$camera(Level level, Entity entity, boolean detached,
                                  boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (!(entity instanceof Player player) || player != Minecraft.getInstance().player
                || !player.isAlive() || player.isSleeping()) return;
        // 镜头跟随开关（默认关）：关着时镜头纹丝不动，压扁动画只在第三人称/
        // 别人身上可见——第一人称不再随节拍上下颠。
        if (!TransparentMode.cameraFollows()) return;
        float heightScale = TransparentMode.heightScale();
        if (heightScale >= 1f) return;
        Vec3 position = ((Camera) (Object) this).position();
        double feetY = Mth.lerp((double) partialTick, player.yo, player.getY());
        double offset = (position.y - feetY) * (heightScale - 1);
        setPosition(position.add(0, offset, 0));
    }
}
