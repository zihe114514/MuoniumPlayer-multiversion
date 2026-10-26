package com.muoniumplayer.core.utils.cursor;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import lombok.experimental.UtilityClass;
import com.muoniumplayer.compat.lwjgl2.Display;
import com.muoniumplayer.core.interfaces.User32Interface;


/**
 */
@UtilityClass
public class CursorUtils {

    private boolean SUPPORTED = true;

    public static final long ARROW = WinUser.IDC_ARROW;
    public static final long HAND = WinUser.IDC_HAND;
    public static final long TEXT = WinUser.IDC_IBEAM;
    public static final long NOT_ALLOWED = WinUser.IDC_NO;
    public static final long RESIZE_EW = WinUser.IDC_SIZEWE;
    public static final long RESIZE_NS = WinUser.IDC_SIZENS;
    public static final long RESIZE_NWSE = WinUser.IDC_SIZENWSE;
    public static final long RESIZE_NESW = WinUser.IDC_SIZENESW;

    private static long curCursor = ARROW;
    private static long overrideCursor = ARROW;

    public void resetOverride() {
        overrideCursor = ARROW;
    }

    public void setOverride(long cursor) {
        overrideCursor = cursor;
    }

    public void setOverride() {
        setCursor(overrideCursor);
    }

    public void setCursor(long cursor) {

        if (curCursor != cursor) {
            curCursor = cursor;

            if (!SUPPORTED)
                return;

            try {
                WinDef.HCURSOR hCursor = User32Interface.INSTANCE.LoadCursorW(null, (int) cursor);

                if (hCursor != null) {
                    User32Interface.INSTANCE.SetClassLongPtrW(new WinDef.HWND(new Pointer(getHwnd())), User32Interface.INSTANCE.GCLP_HCURSOR, hCursor);

                    User32Interface.INSTANCE.SetCursor(hCursor);
                }
            } catch (Throwable t) {
                SUPPORTED = false;
            }
        }

    }

    private long hwnd = -1;

    /**
     * 1.8.9 时这里反射 {@code org.lwjgl.opengl.WindowsDisplay#hwnd}（经 {@code Display.display_impl}）。
     * LWJGL3 没有 WindowsDisplay，HWND 由 {@code glfwGetWin32Window} 公开提供，
     * 封装在 {@link Display#getHwnd()} 里。返回 0 表示拿不到，交由上面的 SUPPORTED 降级分支处理。
     */
    public static long getHwnd() {
        if (hwnd == -1) {
            hwnd = Display.getHwnd();
        }
        return hwnd;
    }

}
