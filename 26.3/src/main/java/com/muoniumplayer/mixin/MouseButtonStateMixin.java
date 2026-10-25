package com.muoniumplayer.mixin;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把原版的鼠标按下/抬起投进 {@link Mouse} 的按住位图（{@link Mouse#setButtonPressed}）。
 *
 * <h2>为什么 26.3 需要这颗 mixin</h2>
 * {@link Mouse#isButtonDown(int)} 的全部下游（滑块拖动、全屏歌词 seek、进度条拖拽、
 * 文本框拖选）都在「本项目的界面开着」时轮询，而原版 {@code MouseHandler} 的
 * {@code isLeftPressed/isMiddlePressed/isRightPressed} 只在<b>没有任何 Screen 打开</b>时
 * 才由 {@code onButton} 更新（它们只服务于游戏内转头）——界面开着时去问它，
 * 拿到的永远是进界面前的旧值。26.2 及更早 {@code isButtonDown} 直查 GLFW 硬件状态，
 * 没有这条限制，所以同样的调用点在四棵旧树上都正常、只在 26.3 上失效。
 *
 * <p>挂 {@code MouseHandler#onButton(long, MouseButtonInfo, int)} 的入口：它是 SDL 的
 * {@code handleMouseButtonEvent} 分发下来的统一步口（无论界面上叠了多少层都会经过），
 * 注入用 HEAD 且<b>不取消</b>，原版自己照常处理。按钮号经
 * {@link Mouse#fromSdlButton(int)} 换算成 LWJGL2 编号后入位图，与
 * {@code GuiScreen#mouseClicked} 桥接处用的是同一张换算表。
 *
 * <p>第 4 参与原版语义一致：{@code 1} = 按下，其余 = 抬起
 * （onButton 字节码 {@code iconst_1} 比较所得，事件源头是
 * {@code SDL_EVENT_MOUSE_BUTTON_DOWN == 1025} 的判定）。
 *
 * <p>这颗 mixin 挂不上时不会静默失效：{@code muonium.mixins.json} 里
 * {@code required: true} 且 {@code injectors.defaultRequire: 1}，
 * 原版方法改名会在启动阶段直接报错。
 */
@Mixin(MouseHandler.class)
public class MouseButtonStateMixin {

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"))
    private void muonium$trackButtonState(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (info != null) {
            Mouse.setButtonPressed(Mouse.fromSdlButton(info.button()), action == 1);
        }
    }
}
