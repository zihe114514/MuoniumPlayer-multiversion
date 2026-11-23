package com.muoniumplayer.configskija.mixin;

import com.muoniumplayer.render.skija.GlassBackdrop;
import com.muoniumplayer.configskija.host.SkijaConfigService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Requests only the already-owned host capture; it never redirects a framebuffer or creates a second renderer. */
@Mixin(value=GlassBackdrop.class,remap=false)
public abstract class ConfigBackdropMixin {
    @Inject(method="realtime()Z",at=@At("HEAD"),cancellable=true,remap=false)
    private static void realtime(CallbackInfoReturnable<Boolean> callback){if(SkijaConfigService.backgroundWanted())callback.setReturnValue(true);}
    @Inject(method="downscale()I",at=@At("HEAD"),cancellable=true,remap=false)
    private static void downscale(CallbackInfoReturnable<Integer> callback){if(SkijaConfigService.backgroundWanted())callback.setReturnValue(1);}
    @Inject(method="wanted()Z",at=@At("HEAD"),cancellable=true,remap=false)
    private static void wanted(CallbackInfoReturnable<Boolean> callback){if(SkijaConfigService.backgroundWanted())callback.setReturnValue(true);}
}
