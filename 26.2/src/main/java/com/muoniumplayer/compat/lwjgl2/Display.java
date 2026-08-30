package com.muoniumplayer.compat.lwjgl2;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * LWJGL 2 {@code org.lwjgl.opengl.Display} 的兼容替身（1.8.9 Forge -> 1.20.1 Fabric 移植用）。
 *
 * <p>只实现项目实际用到的 4 件事：{@code getWidth} / {@code getHeight} / {@code isVisible}，
 * 以及 CursorUtils 原先通过反射 {@code Display.display_impl.hwnd} 拿到的 Win32 HWND。
 *
 * <p>尺寸语义：1.8.9 里 {@code Display.getWidth()} 是 GL framebuffer 的像素宽。26.2 的官方名下
 * {@link Window#getWidth()} 就是 framebufferWidth（字节码实测 getfield framebufferWidth），
 * 而窗口逻辑尺寸是 {@code getScreenWidth()} —— 与 Yarn 的命名正好相反，别看名字想当然。
 */
public final class Display {

    private Display() {
    }

    /** 返回 GLFW 窗口句柄；客户端尚未初始化时返回 0。 */
    public static long getWindowHandle() {
        Window window = window();
        return window == null ? 0L : window.handle();
    }

    public static int getWidth() {
        Window window = window();
        return window == null ? 0 : window.getWidth();
    }

    public static int getHeight() {
        Window window = window();
        return window == null ? 0 : window.getHeight();
    }

    /**
     * LWJGL2 的 {@code isVisible()} = 窗口已映射且未最小化。
     * BloomShader / GaussianBlurShader 用它在窗口不可见时跳过后处理。
     */
    public static boolean isVisible() {
        long handle = getWindowHandle();
        if (handle == 0L) {
            return false;
        }
        return GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE) != 0
                && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == 0;
    }

    /**
     * Win32 HWND。1.8.9 里靠反射 {@code org.lwjgl.opengl.WindowsDisplay#hwnd} 取，
     * LWJGL3 有公开入口 {@link GLFWNativeWin32#glfwGetWin32Window(long)}。
     * 非 Windows 平台返回 0（调用方 CursorUtils 已有 SUPPORTED 降级分支）。
     */
    public static long getHwnd() {
        long handle = getWindowHandle();
        if (handle == 0L) {
            return 0L;
        }
        try {
            return GLFWNativeWin32.glfwGetWin32Window(handle);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static Window window() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        try {
            return mc.getWindow();
        } catch (Throwable t) {
            return null;
        }
    }
}
