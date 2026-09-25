package com.muoniumplayer.mixin;

import com.mojang.renderpearl.backend.opengl.GlSurface;
import com.muoniumplayer.render.skija.SkijaHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在这一帧的最终画面已经进入窗口默认帧缓冲、但还没 swapBuffers 的那一刻，
 * 把 Skija 离屏图盖上去。
 *
 * <h2>为什么必须挂在帧末，而不是业务层的调用点</h2>
 * 26.3 的 GUI 仍然是延迟批处理：{@code GuiGraphicsExtractor} 只往 render state 里追加
 * 元素，真正提交在帧末的 GUI 渲染器里。所以本渲染层的所有调用点
 * （HUD 元素回调、{@code Screen#extractRenderState}、{@code ScreenEvents.afterExtract}）
 * 全都处在「收集」阶段，那时原版 GUI 一个像素都还没画，在那里合成一定会被盖住。
 *
 * <h2>为什么是 {@code GlSurface#present()} 的入口</h2>
 * 1.21.11 时期这个钩子挂在 {@code Framebuffer#blitToScreen()} 的出口，
 * 但 26.2 把渲染后端抽象成了 {@code GpuDevice} / {@code GpuSurface}，
 * {@code RenderTarget}（旧 {@code Framebuffer}）上的 {@code blitToScreen()} 已经不存在。
 * 26.3 又把这一整层从 blaze3d 搬进了 {@code com.mojang.renderpearl.*}，
 * 本 mixin 的目标类因此变成 {@code renderpearl.backend.opengl.GlSurface}；
 * 方法名与描述符没动（javap 两版都是 {@code public void present()}）。
 *
 * <p>新链路是（javap -c 实测）：
 * <ol>
 *   <li>{@code GpuSurface#blitFromTexture} → {@code GlSurface#blitFromTexture}
 *       → {@code GlCommandEncoder#presentTexture(view, w, h)}
 *       → {@code blitFrameBuffers(drawFbo, 0, ...)}：把主渲染目标整屏 blit 到
 *       默认帧缓冲（FBO 名 0）。</li>
 *   <li>{@code GpuSurface#present()} 校验状态后转发到
 *       {@code GlSurface#present()}，而后者的全部字节码只有一句
 *       {@code SDLVideo.SDL_GL_SwapWindow(windowHandle)}。</li>
 *       （26.2 这里是 {@code GLFW.glfwSwapBuffers}：26.3 把窗口后端换成了 SDL，
 *       依赖里已经没有 lwjgl-glfw，但「present() 里只有换缓冲这一句」这个
 *       让钩子成立的事实是同一个，javap -c 实测 present() 的字节码只有 8 条指令。）
 * </ol>
 *
 * <p>因此 {@code GlSurface#present()} 的<b>入口</b>正好等价于旧版
 * {@code blitToScreen()} 的<b>出口</b>：最终画面已经落在 FBO 0 里，
 * 交换缓冲还没发生，中间不会再有任何东西改动默认帧缓冲。
 * 挂入口而不是出口很关键——出口时 {@code SDL_GL_SwapWindow} 已经执行完，
 * 这时候贴上去的内容会留在后台缓冲里，要么完全看不见，要么下一帧闪一下。
 *
 * <h2>为什么挂 GlSurface 而不是 GpuSurface</h2>
 * {@code GpuSurface} 是后端无关的外壳，OpenGL 和 Vulkan 两条路都会走到它。
 * 本渲染层用的 Skija 走的是 GL 互操作（{@code DirectContext.makeGL()}，
 * 直接把 GL FBO 包成 Skia surface），在 Vulkan 后端下根本无法工作。
 * 挂在 {@code GlSurface} 上可以让「后端不对时钩子干脆不触发」，
 * 而不是在 Vulkan 下往一个不存在的 GL 上下文里乱写。
 *
 * <p>26.2 新增了 Vulkan 后端（{@code net.minecraft.client.PreferredGraphicsApi}
 * 有 {@code DEFAULT / OPENGL / VULKAN} 三档，26.3 里这个开关还在，只是后端实现
 * 挪到了 {@code renderpearl.backend.vulkan}）。用户切到 Vulkan 时本 mod 不会渲染，
 * 这属于已知限制；为了不出现「无报错、界面就是不出现」这种最难排查的症状，
 * {@link SkijaHost#requireOpenGlBackend()} 会在初始化阶段主动探测并明确报错。
 *
 * <h2>为什么不需要判等实例</h2>
 * 旧版挂 {@code Framebuffer#blitToScreen()} 时必须判等主帧缓冲，
 * 因为任何 {@code Framebuffer} 实例都可能被别的 mod 调用。
 * {@code GlSurface} 不同：它一个窗口只有一个实例，
 * 且 {@code present()} 就是「本帧结束」的定义本身，天然一帧一次。
 */
@Mixin(GlSurface.class)
public class GlSurfaceMixin {

    @Inject(method = "present()V", at = @At("HEAD"))
    private void muonium$compositeSkijaFrame(CallbackInfo ci) {
        // 无论本帧有没有内容都要调用：compositeFrame 同时负责复位「本帧已清屏」标志。
        SkijaHost.compositeFrame();
    }
}
