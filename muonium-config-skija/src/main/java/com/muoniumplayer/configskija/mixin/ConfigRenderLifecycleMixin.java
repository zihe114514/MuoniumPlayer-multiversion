package com.muoniumplayer.configskija.mixin;

import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.configskija.host.SkijaConfigService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=SkijaHost.class,remap=false)
public abstract class ConfigRenderLifecycleMixin {
    @Inject(method="shutdown()V",at=@At("HEAD"),remap=false)
    private static void shutdown(CallbackInfo callback){SkijaConfigService.shutdown();}
}
