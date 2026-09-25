package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把原版的滚轮事件投进 {@link Mouse#getDWheel()} 的累加器。
 *
 * <h2>为什么 26.3 需要这颗 mixin</h2>
 * 1.20.1 / 1.21.1 / 1.21.11 / 26.2 那四棵树里，这一路是 {@code GLFW.glfwSetScrollCallback}
 * 干的：GLFW 的设回调会<b>返回旧回调</b>，所以可以在原版之外再套一层、套完继续转发。
 * 26.3 把窗口/输入后端换成了 SDL —— 滚轮是 {@code Window#handleEvent(SDL_Event)}
 * 分发下来的事件，<b>没有回调可以被套</b>；而 lwjgl-glfw 已经不在依赖里，
 * 那行代码连编译都过不了。
 *
 * <p>于是改挂在 {@link MouseHandler#onScroll(long, double, double)} 的入口：
 * 它是原版处理一次滚轮的统一步口（在 SDL 事件与原版界面之间的边界上），
 * 注入用 HEAD 且<b>不取消</b>，原版自己照样收到这份滚轮，
 * 与「套一层回调再转发」等价。
 *
 * <p>量纲：{@code pushScrollNotches} 收的是「格」，与 GLFW 的 yoffset 同一量纲，
 * 由它按 LWJGL2 的 120/格 换算成 dWheel。
 *
 * <p>这颗 mixin 挂不上时不会静默失效：{@code muonium.mixins.json} 里
 * {@code required: true} 且 {@code injectors.defaultRequire: 1}，
 * 原版方法改名会在启动阶段直接报错，而不是表现为「滚轮没反应」。
 */
@Mixin(MouseHandler.class)
public class MouseScrollMixin {

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"))
    private void muonium$feedWheelAccumulator(long window, double xoffset, double yoffset, CallbackInfo ci) {
        if (yoffset != 0.0D) {
            Mouse.pushScrollNotches(yoffset);
        }
    }
}
