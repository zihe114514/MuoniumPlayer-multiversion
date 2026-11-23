package com.muoniumplayer.configskija.mixin;

import com.muoniumplayer.configskija.host.SkijaConfigScreen;
import com.muoniumplayer.configskija.host.notifications.ToastInputBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.1.2: exact callback descriptors and native numbering verified independently for both loaders. */
@Mixin(MouseHandler.class)
public abstract class ConfigToastMouseMixin {
    @Inject(method = "onMove(JDD)V", at = @At("HEAD"), require = 1)
    private void muoniumconfigskija$toastMove(long eventWindow, double x, double y, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        Object screen = mc.screen;
        double pixelRatio = window.getScreenWidth() > 0 ? window.getWidth() / (double)window.getScreenWidth() : 0;
        ToastInputBridge.move(eventWindow, window.handle(), screen, screen instanceof SkijaConfigScreen,
            mc.isWindowActive(), mc.isSameThread(), x, y, pixelRatio);
    }

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void muoniumconfigskija$toastButton(long eventWindow, MouseButtonInfo button, int action, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        Object screen = mc.screen;
        MouseHandler mouse = (MouseHandler)(Object)this;
        double pixelRatio = window.getScreenWidth() > 0 ? window.getWidth() / (double)window.getScreenWidth() : 0;
        if (ToastInputBridge.button(ToastInputBridge.Backend.GLFW, eventWindow, window.handle(), screen,
            screen instanceof SkijaConfigScreen, mc.isWindowActive(), mc.isSameThread(), button.button(), action,
            mouse.xpos(), mouse.ypos(), pixelRatio)) ci.cancel();
    }
}
