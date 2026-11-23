package com.muoniumplayer.configskija.host.notifications;

/** Client-thread policy shared by the seven exact MouseHandler adapters; no Minecraft/loader symbols. */
public final class ToastInputBridge {
    public enum Backend {
        GLFW(0), SDL(1);
        private final int left;
        Backend(int left) { this.left = left; }
    }
    private static boolean captured;
    private static long capturedWindow;
    private ToastInputBridge() {}

    /** Window coordinates are converted by the global adapter using actual framebuffer/window pixel ratio. */
    public static void move(long eventWindow, long currentWindow, Object screen, boolean configScreen,
                            boolean focused, boolean clientThread, double x, double y, double pixelRatio) {
        if (!eligibleWindow(eventWindow, currentWindow)) return;
        requireThread(clientThread);
        if (configScreen) { clearCapture(); return; } // its own Screen already routes these events
        if (screen == null || !focused || disabled() || !validPixelRatio(pixelRatio)) { cancelInput(); return; }
        NotificationIntegration.pointerMoveWindow(screen, x, y, pixelRatio);
    }
    /** Return true only when this event must not reach the underlying vanilla Screen. */
    public static boolean button(Backend backend, long eventWindow, long currentWindow, Object screen,
                                 boolean configScreen, boolean focused, boolean clientThread,
                                 int nativeButton, int action, double x, double y, double pixelRatio) {
        if (!eligibleWindow(eventWindow, currentWindow)) return false;
        requireThread(clientThread);
        if (configScreen) { clearCapture(); return false; }
        if (screen == null || !focused || disabled() || !validPixelRatio(pixelRatio)) { cancelInput(); return false; }
        // Both callbacks already normalize DOWN/UP to 1/0. SDL_EVENT_MOUSE_BUTTON_* are not actions here.
        if (nativeButton != backend.left || (action != 0 && action != 1)) return false;
        if (action == 0) {
            boolean paired = captured && capturedWindow == currentWindow;
            boolean handled = NotificationIntegration.pointerUp(screen, 0);
            clearCapture();
            return paired || handled;
        }
        if (captured && capturedWindow == currentWindow) return true;
        NotificationIntegration.pointerMoveWindow(screen, x, y, pixelRatio);
        // Capture before dispatch: the callback can replace/close the current Screen during the press.
        captured = true; capturedWindow = currentWindow;
        try {
            boolean handled = NotificationIntegration.pointerDown(screen, 0);
            if (!handled) clearCapture();
            return handled;
        } catch (RuntimeException | Error failure) { clearCapture(); throw failure; }
    }
    public static void cancelInput() { clearCapture(); NotificationIntegration.cancelInput(); }
    private static void clearCapture() { captured = false; capturedWindow = 0; }
    private static boolean eligibleWindow(long eventWindow, long currentWindow) { return eventWindow != 0 && eventWindow == currentWindow; }
    private static boolean validPixelRatio(double ratio) { return Double.isFinite(ratio) && ratio > 0; }
    private static boolean disabled() { return Boolean.getBoolean("muonium.config.skija.disabled"); }
    private static void requireThread(boolean clientThread) {
        if (!clientThread) throw new IllegalStateException("Toast mouse cancellation requires the Minecraft client thread");
    }
}
