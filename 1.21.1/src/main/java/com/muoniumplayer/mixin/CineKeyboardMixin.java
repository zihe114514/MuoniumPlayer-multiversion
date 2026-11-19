package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.mc.CineProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 只记录宿主原始输入，不拦截原版事件。目标描述符逐版本/加载器核实。 */
@Mixin(KeyboardHandler.class)
abstract class CineKeyboardMixin {

    @Inject(method = "keyPress(JIIII)V", at = @At("HEAD"), require = 1)
    private void muonium$cineKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        if (window != 0L && window == Minecraft.getInstance().getWindow().getWindow()) CineProbe.onKey(key, action);
    }
}
