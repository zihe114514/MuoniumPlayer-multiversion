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
 * <h2>为什么挂在 {@code RenderTarget#blitToScreen()} 的出口</h2>
 * 26.1.2 的 GUI 仍是延迟批处理：{@code GuiGraphicsExtractor} 只往 render state 里追加
 * 元素，真正提交在帧末的 GUI 渲染器里。所以本渲染层的所有调用点
 * （HUD 元素回调、{@code Screen#extractRenderState}、{@code ScreenEvents.afterExtract}）
 * 全都处在「收集」阶段，那时原版 GUI 一个像素都还没画，在那里合成一定会被盖住。
 *
 * <p>同代里的 26.2/26.3 把这条链换成了 {@code GlSurface#present()} 入口
 * （{@code GpuSurface#present()} 校验状态后转发，present() 的字节码只有一句换缓冲）；
 * 但 <b>26.1.2 还没有 {@code GlSurface} 这个类</b>（javap 核对 26.1.2 客户端 jar：
 * blaze3d.opengl 里没有 Surface 形态的类，帧末出口仍是
 * {@code RenderTarget#blitToScreen()}——26.2 引入 GpuDevice/GpuSurface 抽象时才把它
 * 拆出去）。所以本树退回 1.21.11 {@code FramebufferMixin} 的形状：挂 blitToScreen 的
 * TAIL。出口处默认帧缓冲里已经是这一帧的最终画面，而 {@code Window#swapBuffers}
 * 还在后面，中间不会再有任何东西改动默认帧缓冲——挂入口而不是出口很关键，
 * 出口时交换缓冲已执行完，贴上去的内容要么看不见要么下一帧闪一下。</p>
 *
 * <h2>为什么必须判等主帧缓冲</h2>
 * {@code blitToScreen} 定义在抽象类 {@code RenderTarget} 上，任何 {@code RenderTarget}
 * 实例都可能被别的 mod 调用；原版每帧只对主帧缓冲调一次。判等保证一帧只合成一次，
 * 多合成一次就是多贴一层半透明界面。（26.2+ 的 GlSurface 一个窗口只有一个实例，
 * 天然一帧一次，才免掉了这个判等——26.1.2 还享受不到。）</p>
 *
 * <h2>Vulkan 后端</h2>
 * <p>26.1.2 已有 Vulkan 后端开关（{@code net.minecraft.client.PreferredGraphicsApi} 的
 * {@code DEFAULT / OPENGL / VULKAN} 三档）。本渲染层用的 Skija 走 GL 互操作，
 * Vulkan 下无法工作，属于已知限制；为了不出现「无报错、界面就是不出现」这种最难排查
 * 的症状，{@link SkijaHost#requireOpenGlBackend()} 会在初始化阶段主动探测并明确报错。</p>
 */
@Mixin(RenderTarget.class)
public class RenderTargetPresentMixin {

    @Inject(method = "blitToScreen()V", at = @At("TAIL"))
    private void muonium$compositeSkijaFrame(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getMainRenderTarget() != (Object) this) {
            return;
        }
        // 无论本帧有没有内容都要调用：compositeFrame 同时负责复位「本帧已清屏」标志。
        // 3D 歌词直画也在 compositeFrame 内部（玻璃采集后、Skija 合成前的插槽），
        // 保证歌词落在模组界面之下；这里不再单独调 presentComposite 防双画。
        SkijaHost.compositeFrame();
    }
}
