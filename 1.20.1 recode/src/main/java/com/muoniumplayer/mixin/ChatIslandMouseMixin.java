package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.lwjgl2.Display;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原版记下 activeButton 前消费聊天音乐卡的按下与配对抬起。 */
@Mixin(MouseHandler.class)
abstract class ChatIslandMouseMixin {

    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void muonium$chatIslandMouseButton(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (window == 0L || window != Display.getWindowHandle()) return;
        if (DownloadDynamicIsland.handleMusicCardMouseButton(button, action)) ci.cancel();
    }
}
