package com.muoniumplayer.configskija.mixin;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.configskija.host.SkijaConfigService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The host already owns scheduling and the Skija frame; this acceptance hook only joins its lifecycle. */
@Mixin(value=MuoniumPlayerClient.class,remap=false)
public abstract class ConfigClientMixin {
    @Inject(method="onClientTick",at=@At("TAIL"),remap=false)
    private void tick(CallbackInfo callback) { SkijaConfigService.tick(); }
    @Inject(method="onHudRenderUnguarded()V",at=@At("RETURN"),remap=false)
    private void hud(CallbackInfo callback) { SkijaConfigService.afterHud(); }
    @Inject(method="onScreenRenderedUnguarded",at=@At("RETURN"),remap=false)
    private void screen(CallbackInfo callback) { SkijaConfigService.afterScreen(); }
}
