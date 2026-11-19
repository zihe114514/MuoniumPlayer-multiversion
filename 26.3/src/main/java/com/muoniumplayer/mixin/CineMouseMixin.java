package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.mc.CineProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 只记录宿主原始输入，不拦截原版事件。目标描述符逐版本/加载器核实。 */
@Mixin(MouseHandler.class)
abstract class CineMouseMixin {

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), require = 1)
    private void muonium$cineButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (window != 0L && window == Minecraft.getInstance().getWindow().handle()) CineProbe.onButton(info.button(), action);
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), require = 1)
    private void muonium$cineScroll(long window, double x, double y, CallbackInfo ci) {
        if (window != 0L && window == Minecraft.getInstance().getWindow().handle()) CineProbe.onScroll(x, y);
    }

    @Inject(method = "onMove(JDDDD)V", at = @At("HEAD"), require = 1)
    private void muonium$cineMove(long window, double x, double y, double dx, double dy, CallbackInfo ci) {
        if (window != 0L && window == Minecraft.getInstance().getWindow().handle()) CineProbe.onRelativeMove(dx, dy);
    }
}
