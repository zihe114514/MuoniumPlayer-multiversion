package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineBars;
import com.muoniumplayer.core.cine.CineDirector;
import com.muoniumplayer.core.cine.CineTransition;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 「卡点运镜」转场/黑边:Hud.extractRenderState TAIL(isHidden 闸门之外),26.2 同 26.3 形。 */
@Mixin(Hud.class)
abstract class CineHudMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void muonium$cineOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker,
                                     CallbackInfo ci) {
        if (!CineDirector.isActive()) return;
        if (com.muoniumplayer.render.skija.SkijaHost.beginFrame() == null) return;
        try {
            CineTransition.render();
            CineBars.render();
        } finally {
            com.muoniumplayer.render.skija.SkijaHost.endFrame();
        }
    }
}
