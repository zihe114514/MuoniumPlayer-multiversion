package com.muoniumplayer.mixin;

import com.muoniumplayer.core.cine.CineBars;
import com.muoniumplayer.core.cine.CineDirector;
import com.muoniumplayer.core.cine.CineTransition;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「卡点运镜」转场遮罩/电影黑边的绘制入口。
 *
 * <p>旧世代的 Gui 没有 Hud 子对象与 isHidden;隐藏走 {@code options.hideGui},
 * 闸门在 {@code Gui.render} 方法<b>中部</b>——TAIL 注入点在闸门之后,运镜开
 * hideGui 时遮罩照画(遮罩比 HUD 更该在)。26.x 的 extractRenderState 形态见
 * 26.3 树同名文件。</p>
 */
@Mixin(Gui.class)
abstract class CineHudMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void muonium$cineOverlay(GuiGraphics graphics, DeltaTracker deltaTracker,
                                     CallbackInfo ci) {
        if (!CineDirector.isActive()) return;
        // 遮罩绘制 defer 到帧末合成后(与灵动岛同链):在 extract/render 收集阶段
        // 自开 Skija 帧会做 FBO 切换+GL 快照还原,与同帧其它 Skija 调用方交错时
        // 回滚 MC 收集中间态(实机:偶发方块全黑/画面抽搐)。
        Runnable overlay = CineDirector.cineOverlayRunnable();
        if (overlay != null) {
            com.muoniumplayer.render.skija.SkijaHost.deferPostCompositeOverlay(overlay);
        }
    }
}
