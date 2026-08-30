package com.muoniumplayer.mixin;

import com.muoniumplayer.render.skija.SkijaHost;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在这一帧的最终画面已经进入窗口默认帧缓冲、但还没 swapBuffers 的那一刻，
 * 把 Skija 离屏图盖上去。
 *
 * <h2>为什么必须挂在这里</h2>
 * 1.21.11 的 GUI 是延迟批处理：{@code GuiGraphics} 只往 {@code GuiRenderState} 追加
 * render state，真正提交在帧末的 {@code GuiRenderer#render} 里。所以本渲染层的调用点
 * （HUD 覆盖层回调、{@code Screen#render}、{@code ScreenEvents.afterRender}）全在
 * 「收集」阶段，那时原版 GUI 一个像素都还没画，在那里合成会被盖住。
 *
 * <p>但挂 {@code GuiRenderer#render} 的出口同样不行，这是实测踩过的坑：
 * 每个 render pass 结束时 {@code GlCommandEncoder#closePass()} 会执行
 * {@code glBindFramebuffer(GL_FRAMEBUFFER, 0)}，于是那个出口处绑定的是窗口默认帧缓冲；
 * 我们贴上去之后，Minecraft 还要在 {@code Minecraft#render} 里调
 * {@code RenderTarget#blitToScreen()}，把主帧缓冲的颜色附件整屏 blit 到默认帧缓冲，
 * 正好把刚贴的内容全部覆盖。症状是「初始化成功、无报错、无 GL 错误，界面就是不出现」。
 *
 * <p>{@code blitToScreen()} 的出口没有这个问题：默认帧缓冲里已经是最终画面，
 * 而 {@code Window#swapBuffers} 还在后面（字节码里两者相隔约 70 条指令，
 * 中间只有计时、profiler 与 Tracy 抓帧），中途没有任何东西会再动默认帧缓冲。
 *
 * <h2>为什么要判等主帧缓冲</h2>
 * {@code blitToScreen} 定义在抽象类 {@code RenderTarget} 上，任何 {@code RenderTarget}
 * 实例都可以被调用。原版每帧只对主帧缓冲调一次，但别的 mod 未必；
 * 判等可以保证一帧只合成一次，多合成一次就是多贴一层半透明界面。
 */
@Mixin(RenderTarget.class)
public class FramebufferMixin {

    @Inject(method = "blitToScreen()V", at = @At("TAIL"))
    private void muonium$compositeSkijaFrame(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getMainRenderTarget() != (Object) this) {
            return;
        }
        // 无论本帧有没有内容都要调用：compositeFrame 同时负责复位「本帧已清屏」标志。
        SkijaHost.compositeFrame();
    }
}
