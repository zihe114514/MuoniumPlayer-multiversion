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
 * <p>绘制走 {@link com.muoniumplayer.render.skija.SkijaHost#deferPostCompositeOverlay}：
 * 帧末合成（灵动岛同链）之后再画，那一刻默认帧缓冲已是最终画面、且不处在
 * MC 的 extract 收集阶段。此前「extract TAIL 处自开 Skija 帧」会做一次完整
 * 的 FBO 切换 + GL 快照捕获/还原——与同一 extract 阶段的其它 Skija 调用方
 * （HUD 元素回调、Screen afterExtract）同帧交错时，快照还原会回滚 MC 的
 * 收集中间态，实机表现为偶发方块全黑/画面抽搐（2026-11-05 实机复盘，
 * ESC 开菜单恢复 = 帧序被重置）。defer 路的 FBO 操作发生在帧末边界，
 * 与 MC 的收集阶段完全解耦。</p>
 */
@Mixin(Hud.class)
abstract class CineHudMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void muonium$cineOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker,
                                     CallbackInfo ci) {
        if (!CineDirector.isActive()) return;
        Runnable overlay = CineDirector.cineOverlayRunnable();
        if (overlay != null) {
            com.muoniumplayer.render.skija.SkijaHost.deferPostCompositeOverlay(overlay);
        }
    }
}
