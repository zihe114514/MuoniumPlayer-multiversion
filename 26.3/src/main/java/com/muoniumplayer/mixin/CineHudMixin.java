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

/**
 * 「卡点运镜」的转场遮罩/电影黑边绘制入口。
 *
 * <p>为什么不能用 HudElementRegistry（mod 的常规 HUD 路）：26.3 的
 * {@code Hud.extractRenderState} 在 {@code isHidden} 时直接跳过全部 extract
 * 分支（javap 核对 offset 38 {@code ifne 78}），而 Fabric 的 HUD 元素列表
 * 就跑在 extract 阶段内 —— 运镜一开 {@code hideHud} 主动藏 HUD，把转场
 * 遮罩自己的绘制入口也关掉了（2026-11-05 实机复盘：转场机在轮转、屏幕
 * 没有任何遮罩）。</p>
 *
 * <p>照 AmbientCamera Fabric 版的 {@code HudMixin} 同款解法：注入
 * {@code extractRenderState} 的 TAIL —— 该注入点在 hidden 闸门之外
 * （闸门只跳中间的 extract 分支），运镜遮罩因此「HUD 隐藏时照画」，
 * 这正是运镜需要的语义（遮罩比 HUD 更该在）。NeoForge 侧走
 * {@code RenderGuiEvent.Post} 无此问题，本 mixin 两侧同挂也无害
 * （同帧两次 draw 只在罕见的双重注册下发生）。</p>
 */
@Mixin(Hud.class)
abstract class CineHudMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void muonium$cineOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker,
                                     CallbackInfo ci) {
        if (!CineDirector.isActive()) return;
        // extract 阶段不在 inSkijaFrame 括号内,自己开:与 onHudRender 同款
        // begin/end 对,内容累积到同一张帧末离屏图(GlSurfaceMixin 合成)。
        if (com.muoniumplayer.render.skija.SkijaHost.beginFrame() == null) return;
        try {
            CineTransition.render();
            CineBars.render();
        } finally {
            com.muoniumplayer.render.skija.SkijaHost.endFrame();
        }
    }
}
