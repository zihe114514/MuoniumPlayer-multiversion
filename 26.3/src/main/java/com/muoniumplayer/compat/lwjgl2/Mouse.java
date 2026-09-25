package com.muoniumplayer.compat.lwjgl2;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;

/**
 * LWJGL 2 {@code org.lwjgl.input.Mouse} 的兼容替身（1.8.9 Forge -> 1.20.1 Fabric 移植用）。
 *
 * <p>坐标语义与 LWJGL2 完全一致：原点在窗口左下角，单位是 framebuffer 像素。
 * 1.20.1 的 {@code net.minecraft.client.Mouse#getY()} 原点在左上角且是窗口逻辑坐标，
 * 直接换用会把整套 HUD 命中判定上下翻转，所以这里取原版的窗口坐标再自己换算 Y。
 *
 * <p><b>26.3 的变化：原版从 GLFW 换成了 SDL（依赖里已没有 lwjgl-glfw）。</b>
 * 原先这里直接调 {@code glfwGetCursorPos / glfwGetFramebufferSize / glfwGetMouseButton}，
 * 现在改成问原版自己：{@link MouseHandler#xpos()}/{@link MouseHandler#ypos()} 存的就是
 * 窗口逻辑坐标（判据：{@code getScaledXPos} 的字节码是
 * {@code raw * getGuiScaledWidth() / getScreenWidth()}，说明 raw 是窗口像素而不是 GUI 像素），
 * 尺寸用 {@code Window#getWidth()/getHeight()}（framebuffer 像素，字节码 getfield framebufferWidth）
 * 与 {@code Window#getScreenWidth()/getScreenHeight()}（窗口逻辑像素，getfield width）。
 * 少一层裸 native 调用，也顺带避开了「在非渲染线程调 SDL」的坑。
 *
 * <p>{@link #getDWheel()} 保留 LWJGL2 的"读一次清零"累加器语义与 120/格 的量纲。
 * GLFW 时代可以 {@code glfwSetScrollCallback} 拿到旧回调再套一层；SDL 的事件是
 * {@code Window#handleEvent(SDL_Event)} 直接分发的，没有可套的回调，
 * 于是改由 {@code MouseScrollMixin} 挂在 {@link MouseHandler#onScroll(long, double, double)}
 * 的入口把滚轮量投进来（只注入不取消，原版照样收到滚轮）。
 * 全项目对 dWheel 只判正负（{@code dWheel > 0} / {@code != 0}），量纲不影响行为。
 */
public final class Mouse {

    private Mouse() {
    }

    /** LWJGL2 一格滚轮 = 120，见 LWJGL2 Mouse#getDWheel 的 Windows 实现。 */
    private static final int WHEEL_UNIT_PER_NOTCH = 120;

    private static final Object LOCK = new Object();
    private static int accumulatedWheel;

    /**
     * 26.3 原版鼠标事件里的按钮号 → LWJGL2 的按钮号。
     *
     * <p>原版换到 SDL 之后，事件里带的是 SDL 的按钮号而不是 GLFW 的：
     * {@code SDLMouse.SDL_BUTTON_LEFT=1 / SDL_BUTTON_MIDDLE=2 / SDL_BUTTON_RIGHT=3}
     * （判据两条：{@code SDLEventHandler#handleMouseButtonEvent} 把
     * {@code SDL_MouseButtonEvent#button()} 原样塞进 {@code MouseButtonInfo}，
     * 而 {@code MouseHandler#onButton} 用 1/2/3 分别置
     * {@code isLeftPressed}/{@code isMiddlePressed}/{@code isRightPressed}）。
     * GLFW 时代是 0/1/2（左/右/中），与 LWJGL2 相同，所以 26.2 及更早不需要这一步。
     *
     * <p>全项目约 180 处按钮判断说的都是 LWJGL2 的编号（{@code button == 0} 是左键），
     * 因此换算只允许发生在「原版事件 → 本项目」的边界上（{@code compat/mc} 那三个入口），
     * 不要在下游逐处改比较值。侧键（4/5）保持原值：它们本来就非 0，不会被误认成左键。
     */
    public static int fromSdlButton(int sdlButton) {
        if (sdlButton == 1) {
            return 0;
        }
        if (sdlButton == 2) {
            return 2;
        }
        if (sdlButton == 3) {
            return 1;
        }
        return sdlButton;
    }

    /** {@link #fromSdlButton(int)} 的反向：本项目要替原版构造事件时把编号换回去。 */
    public static int toSdlButton(int lwjgl2Button) {
        if (lwjgl2Button == 0) {
            return 1;
        }
        if (lwjgl2Button == 1) {
            return 3;
        }
        if (lwjgl2Button == 2) {
            return 2;
        }
        return lwjgl2Button;
    }

    /**
     * LWJGL2 与原版按钮编号一致：0 左、1 右、2 中。
     *
     * <p>26.3 的 {@code SDL_GetMouseState} 用的是另一套位掩码（SDL_BUTTON_LMASK 等），
     * 这里不碰它：原版本身就把按住状态存在 {@link MouseHandler} 里，问它既是同一份真值，
     * 也不用把 SDL 的按钮位图搬进本项目。
     */
    public static boolean isButtonDown(int button) {
        MouseHandler handler = handler();
        if (handler == null) {
            return false;
        }
        if (button == 0) {
            return handler.isLeftPressed();
        }
        if (button == 1) {
            return handler.isRightPressed();
        }
        if (button == 2) {
            return handler.isMiddlePressed();
        }
        return false;
    }

    /** 光标 X，framebuffer 像素，原点在左。 */
    public static int getX() {
        MouseHandler handler = handler();
        Minecraft client = minecraft();
        if (handler == null || client == null) {
            return 0;
        }
        int windowWidth = Math.max(1, client.getWindow().getScreenWidth());
        int framebufferWidth = Math.max(1, client.getWindow().getWidth());
        return (int) (handler.xpos() * framebufferWidth / (double) windowWidth);
    }

    /** 光标 Y，framebuffer 像素，原点在下（LWJGL2 语义）。 */
    public static int getY() {
        MouseHandler handler = handler();
        Minecraft client = minecraft();
        if (handler == null || client == null) {
            return 0;
        }
        int windowHeight = Math.max(1, client.getWindow().getScreenHeight());
        int framebufferHeight = Math.max(1, client.getWindow().getHeight());
        double yInPixels = handler.ypos() * framebufferHeight / (double) windowHeight;
        return (int) (framebufferHeight - yInPixels - 1);
    }

    /** 读一次即清零，与 LWJGL2 相同。 */
    public static int getDWheel() {
        synchronized (LOCK) {
            int value = accumulatedWheel;
            accumulatedWheel = 0;
            return value;
        }
    }

    /**
     * 由 {@code MouseScrollMixin}（帧内滚轮事件）与其它已有滚轮入口
     * （例如 {@code Screen#mouseScrolled}）补投滚轮量，单位：格。
     */
    public static void pushScrollNotches(double notches) {
        synchronized (LOCK) {
            accumulatedWheel += (int) Math.round(notches * WHEEL_UNIT_PER_NOTCH);
        }
    }

    private static MouseHandler handler() {
        Minecraft client = minecraft();
        return client == null ? null : client.mouseHandler;
    }

    private static Minecraft minecraft() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }
}
