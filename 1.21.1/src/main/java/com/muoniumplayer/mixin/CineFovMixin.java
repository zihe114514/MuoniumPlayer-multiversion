package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineDirector;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「卡点运镜」的 FOV 脉冲。26.x 前 之前 FOV 在
 * {@code GameRenderer.getFov(Camera, float, boolean)}(private,由 renderLevel
 * 每帧调一次算投影)——注入其 RETURN 叠加偏移。26.x 的写法见 26.3 树同名文件。
 *
 * <p><b>{@code CallbackInfoReturnable} 的类型参数必须是 {@code Double}：</b>
 * 1.21.1 的 {@code getFov} 实测描述符是 {@code (LCamera;FZ)D}（1.21.10 才改成 float，
 * 见那一树同名文件）。写 {@code Float} 不会在 apply 阶段报错 —— 泛型被擦除，
 * 但编译器按 {@code Float} 在 {@code + offset} 处插了 {@code checkcast} 再拆箱，
 * 等到「运镜开启且踩中鼓点脉冲」那一帧才抛 {@code ClassCastException} 崩渲染线程。</p>
 */
@Mixin(GameRenderer.class)
abstract class CineFovMixin {

    @Inject(method = "getFov", at = @At("RETURN"))
    private void muonium$cineFovPunch(CallbackInfoReturnable<Double> cir) {
        if (!CineDirector.isActive()) return;
        float offset = CineDirector.fovOffsetDeg();
        if (offset != 0f) {
            cir.setReturnValue((double) (cir.getReturnValue() + offset));
        }
    }
}
