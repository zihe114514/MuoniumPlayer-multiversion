package com.muoniumplayer.configskija.mixin;

import com.muoniumplayer.configskija.host.SkijaConfigScreen;
import com.muoniumplayer.core.screens.hud.OneConfigHudBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Acceptance-only route. No second hotkey registration. */
@Mixin(value = OneConfigHudBridge.class, remap = false)
public abstract class ConfigOpenMixin {
    @Inject(method = "open()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private static void open(CallbackInfoReturnable<Boolean> result) {
        if (Boolean.getBoolean("muonium.config.skija.disabled")) return;
        SkijaConfigScreen.open();
        result.setReturnValue(true);
    }
}
