package com.muoniumplayer.configskija.mixin;

import com.muoniumplayer.core.screens.hud.MuoniumConfigDownload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skip the Compose companion auto-fetch while this acceptance UI owns the H route. */
@Mixin(value = MuoniumConfigDownload.class, remap = false)
public abstract class ConfigDownloadMixin {
    @Inject(method = "wantsMuoniumConfig()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private static void wants(CallbackInfoReturnable<Boolean> result) {
        if (!Boolean.getBoolean("muonium.config.skija.disabled")) result.setReturnValue(false);
    }
}
