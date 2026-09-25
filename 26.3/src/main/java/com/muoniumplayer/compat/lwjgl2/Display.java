package com.muoniumplayer.compat.lwjgl2;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/**
 * LWJGL 2 {@code org.lwjgl.opengl.Display} 的兼容替身（1.8.9 Forge -> 1.20.1 Fabric 移植用）。
 *
 * <p>只实现项目实际用到的 4 件事：{@code getWidth} / {@code getHeight} / {@code isVisible}，
 * 以及 CursorUtils 原先通过反射 {@code Display.display_impl.hwnd} 拿到的 Win32 HWND。
 *
 * <p>尺寸语义：1.8.9 里 {@code Display.getWidth()} 是 GL framebuffer 的像素宽。26.2 / 26.3 的官方名下
 * {@link Window#getWidth()} 就是 framebufferWidth（两版字节码实测都是 getfield framebufferWidth），
 * 而窗口逻辑尺寸是 {@code getScreenWidth()} —— 与 Yarn 的命名正好相反，别看名字想当然。
 *
 * <p><b>26.3 特别之处：原版的窗口/输入后端从 GLFW 换成了 SDL</b>（依赖里是 lwjgl-sdl，
 * 已经没有 lwjgl-glfw）。因此：
 * <ul>
 *   <li>{@link #getWindowHandle()} 返回的是 SDL 窗口指针，不再是 GLFW 窗口指针。
 *       原版自己也是这么用的（{@code Window#handle()} 交给
 *       {@code GpuDevice#createSurface(long, BooleanSupplier)} 建 surface）；</li>
 *   <li>{@link #isVisible()} 从两次 {@code glfwGetWindowAttrib} 改成读一次
 *       {@code SDL_GetWindowFlags}，判 {@code SDL_WINDOW_HIDDEN} / {@code SDL_WINDOW_MINIMIZED}
 *       两个位（语义与 GLFW 的 VISIBLE / ICONIFIED 一一对应）；</li>
 *   <li>{@link #getHwnd()}：GLFW 时代是 {@code GLFWNativeWin32.glfwGetWin32Window(handle)}，
 *       SDL3 取消了 {@code SDL_GetWindowWMInfo} 这类结构体入口，窗口平台细节改走 property，
 *       于是读 {@code SDL_PROP_WINDOW_WIN32_HWND_POINTER}。非 Windows 平台这个 key 不存在，
 *       拿到默认值 0，与调用方 CursorUtils 的 SUPPORTED 降级分支一致。</li>
 * </ul>
 */
public final class Display {

    private Display() {
    }

    /** 返回 SDL 窗口指针（26.2 及更早是 GLFW 窗口指针）；客户端尚未初始化时返回 0。 */
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
        try {
            long flags = SDLVideo.SDL_GetWindowFlags(handle);
            return (flags & SDLVideo.SDL_WINDOW_HIDDEN) == 0L
                    && (flags & SDLVideo.SDL_WINDOW_MINIMIZED) == 0L;
        } catch (Throwable t) {
            // 问不到就当不可见：最坏是这一帧跳过后处理，不会画错。
            return false;
        }
    }

    /**
     * Win32 HWND。1.8.9 里靠反射 {@code org.lwjgl.opengl.WindowsDisplay#hwnd} 取，
     * 26.2 走 {@code GLFWNativeWin32#glfwGetWin32Window}，26.3 起走 SDL 的窗口 property
     * （见类注释）。非 Windows 平台返回 0（调用方 CursorUtils 已有 SUPPORTED 降级分支）。
     */
    public static long getHwnd() {
        long handle = getWindowHandle();
        if (handle == 0L) {
            return 0L;
        }
        try {
            return SDLProperties.SDL_GetPointerProperty(
                    SDLVideo.SDL_GetWindowProperties(handle),
                    SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER,
                    0L);
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
