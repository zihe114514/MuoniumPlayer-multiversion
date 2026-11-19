package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.lwjgl2.Display;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原版记下 activeButton 前消费聊天音乐卡的按下与配对抬起。 */
@Mixin(MouseHandler.class)
abstract class ChatIslandMouseMixin {

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void muonium$chatIslandMouseButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (window == 0L || window != Display.getWindowHandle() || info == null) return;
        int button = Mouse.fromSdlButton(info.button());
        // 另一颗 HEAD 注入可能尚未执行；消费前同步位图，保证进度拖动能读到按住状态。
        Mouse.setButtonPressed(button, action == 1);
        if (DownloadDynamicIsland.handleMusicCardMouseButton(button, action)) ci.cancel();
    }
}
