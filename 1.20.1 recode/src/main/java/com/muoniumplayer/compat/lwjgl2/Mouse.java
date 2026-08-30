package com.muoniumplayer.compat.lwjgl2;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWScrollCallbackI;

/**
 * LWJGL 2 {@code org.lwjgl.input.Mouse} 的兼容替身（1.8.9 Forge -> 1.20.1 Fabric 移植用）。
 *
 * <p>坐标语义与 LWJGL2 完全一致：原点在窗口左下角，单位是 framebuffer 像素。
 * 1.20.1 的 {@code net.minecraft.client.Mouse#getY()} 原点在左上角且是窗口逻辑坐标，
 * 直接换用会把整套 HUD 命中判定上下翻转，所以这里直接查 GLFW 光标位置再翻 Y。
 *
 * <p>{@link #getDWheel()} 保留 LWJGL2 的"读一次清零"累加器语义与 120/格 的量纲。
 * GLFW 只有回调式滚轮，因此首次使用时在 MC 自己的 scroll callback 之外再套一层
 * （{@code glfwSetScrollCallback} 返回旧回调，我们负责继续转发，不会吃掉 MC 的滚轮）。
 * 全项目对 dWheel 只判正负（{@code dWheel > 0} / {@code != 0}），量纲不影响行为。
 */
public final class Mouse {

    private Mouse() {
    }

    /** LWJGL2 一格滚轮 = 120，见 LWJGL2 Mouse#getDWheel 的 Windows 实现。 */
    private static final int WHEEL_UNIT_PER_NOTCH = 120;

    private static final Object LOCK = new Object();
    private static int accumulatedWheel;
    private static boolean scrollHookInstalled;
    private static GLFWScrollCallback previousScrollCallback;
    private static GLFWScrollCallback ownScrollCallback;
    private static long hookedWindow;

    private static final double[] CURSOR_X = new double[1];
    private static final double[] CURSOR_Y = new double[1];
    private static final int[] FB_W = new int[1];
    private static final int[] FB_H = new int[1];

    /** LWJGL2 与 GLFW 的鼠标按键编号一致：0 左、1 右、2 中。 */
    public static boolean isButtonDown(int button) {
        long handle = Display.getWindowHandle();
        if (handle == 0L || button < 0) {
            return false;
        }
        return GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS;
    }

    /** 光标 X，framebuffer 像素，原点在左。 */
    public static int getX() {
        long handle = Display.getWindowHandle();
        if (handle == 0L) {
            return 0;
        }
        synchronized (LOCK) {
            GLFW.glfwGetCursorPos(handle, CURSOR_X, CURSOR_Y);
            GLFW.glfwGetFramebufferSize(handle, FB_W, FB_H);
            int windowWidth = Math.max(1, windowWidth(handle));
            double scale = FB_W[0] / (double) windowWidth;
            return (int) (CURSOR_X[0] * scale);
        }
    }

    /** 光标 Y，framebuffer 像素，原点在下（LWJGL2 语义）。 */
    public static int getY() {
        long handle = Display.getWindowHandle();
        if (handle == 0L) {
            return 0;
        }
        synchronized (LOCK) {
            GLFW.glfwGetCursorPos(handle, CURSOR_X, CURSOR_Y);
            GLFW.glfwGetFramebufferSize(handle, FB_W, FB_H);
            int windowHeight = Math.max(1, windowHeight(handle));
            double scale = FB_H[0] / (double) windowHeight;
            return (int) (FB_H[0] - CURSOR_Y[0] * scale - 1);
        }
    }

    /** 读一次即清零，与 LWJGL2 相同。 */
    public static int getDWheel() {
        installScrollHookIfNeeded();
        synchronized (LOCK) {
            int value = accumulatedWheel;
            accumulatedWheel = 0;
            return value;
        }
    }

    /** 供 GLFW 回调之外的路径（例如 Screen#mouseScrolled）补投滚轮量，单位：格。 */
    public static void pushScrollNotches(double notches) {
        synchronized (LOCK) {
            accumulatedWheel += (int) Math.round(notches * WHEEL_UNIT_PER_NOTCH);
        }
    }

    private static void installScrollHookIfNeeded() {
        long handle = Display.getWindowHandle();
        if (handle == 0L) {
            return;
        }
        synchronized (LOCK) {
            if (scrollHookInstalled && hookedWindow == handle) {
                return;
            }
            hookedWindow = handle;
            scrollHookInstalled = true;
            GLFWScrollCallbackI chained = (window, xOffset, yOffset) -> {
                synchronized (LOCK) {
                    accumulatedWheel += (int) Math.round(yOffset * WHEEL_UNIT_PER_NOTCH);
                }
                GLFWScrollCallback previous = previousScrollCallback;
                if (previous != null) {
                    previous.invoke(window, xOffset, yOffset);
                }
            };
            // 先建 Callback 对象并持强引用（否则被 GC 后 native 侧变野指针），再安装；
            // glfwSetScrollCallback 返回的旧回调（MC 自己的）由上面的 lambda 继续转发。
            ownScrollCallback = GLFWScrollCallback.create(chained);
            previousScrollCallback = GLFW.glfwSetScrollCallback(handle, ownScrollCallback);
        }
    }

    private static final int[] WIN_W = new int[1];
    private static final int[] WIN_H = new int[1];

    private static int windowWidth(long handle) {
        GLFW.glfwGetWindowSize(handle, WIN_W, WIN_H);
        return WIN_W[0];
    }

    private static int windowHeight(long handle) {
        GLFW.glfwGetWindowSize(handle, WIN_W, WIN_H);
        return WIN_H[0];
    }
}
