package com.muoniumplayer.configskija.sound;

import com.muoniumplayer.configskija.host.notifications.NotificationIntegration;
import com.muoniumplayer.configskija.host.notifications.ToastInputBridge;
import com.muoniumplayer.configskija.host.notifications.ToastInputBridge.Backend;
import com.muoniumplayer.configskija.model.Notifications;
import com.muoniumplayer.configskija.model.UiPreferences;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.Painter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real global adapter + native-number policy. Does not initialize Minecraft, a renderer or a loader. */
public final class ToastInputBridgeBehaviorTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final long WINDOW = 9001;
    private static final Object A = new Object(), B = new Object(), OWN = new Object();
    private static final Notifications model = NotificationIntegration.notifications();
    private static final UiPreferences prefs = new UiPreferences(Map.of(), () -> {});
    private static final Painter metrics = new Painter() {
        public void save() {} public void restore() {} public void translate(double x, double y) {} public void scale(double x, double y) {}
        public void clip(Rect r, double radius) {} public void rect(Rect r, double radius, int color) {} public void outline(Rect r, double radius, double width, int color) {}
        public void line(double x1, double y1, double x2, double y2, double width, int color) {} public void text(String text, double x, double y, double size, boolean bold, int color) {}
        public double measure(String text, double size, boolean bold) { return text.codePointCount(0, text.length()) * size * .5; }
    };
    private static final NotificationIntegration.Viewport viewport = new NotificationIntegration.Viewport(0, 0, 1280, 720, 1);
    private static double now, x, y;
    private static void tick(Object screen, boolean focus, double seconds) {
        now += seconds;
        NotificationIntegration.tick(new NotificationIntegration.Frame(metrics, prefs, viewport, now, seconds, screen, focus));
    }
    private static Notifications.Notice fresh(String title, AtomicInteger clicked) {
        ToastInputBridge.cancelInput(); model.clearAll(); model.clearHistory();
        Notifications.Notice notice = model.builder(title, "").persistent().onClick(clicked::incrementAndGet).send();
        tick(A, true, .01);
        ToastInputBridge.move(WINDOW, WINDOW, A, false, true, true, 0, 0, 2);
        tick(A, true, .22);
        Rect hit = NotificationIntegration.hitBoxes().stream().filter(box -> box.id().equals("notification.toast." + notice.id())).findFirst().orElseThrow().framebufferBounds();
        x = (hit.x() + hit.w() / 2) / 2; y = (hit.y() + hit.h() / 2) / 2;
        ToastInputBridge.move(WINDOW, WINDOW, A, false, true, true, x, y, 2);
        return notice;
    }
    private static boolean button(Backend backend, Object screen, boolean own, boolean focused, int nativeButton, int action) {
        return ToastInputBridge.button(backend, WINDOW, WINDOW, screen, own, focused, true, nativeButton, action, x, y, 2);
    }
    private static void backendNumbersAndConsumption() {
        for (Backend backend : Backend.values()) {
            AtomicInteger clicked = new AtomicInteger(); Notifications.Notice notice = fresh(backend.name(), clicked);
            int left = backend == Backend.SDL ? 1 : 0;
            check(notice.hovered(), "window coordinate pixel ratio reaches the real toast hover");
            check(!button(backend, A, false, true, backend == Backend.SDL ? 3 : 1, 1), "right mouse button passes through");
            check(!button(backend, A, false, true, backend == Backend.SDL ? 2 : 2, 1), "middle mouse button passes through");
            check(!button(backend, A, false, true, left, 2), "GLFW repeat/unknown normalized action is not a release or a click");
            check(clicked.get() == 0, "only actual left DOWN dispatches");
            int lowerScreenPresses = 0;
            if (!button(backend, A, false, true, left, 1)) lowerScreenPresses++;
            check(lowerScreenPresses == 0 && clicked.get() == 1, "consumed press must cancel before lower Screen dispatch");
            check(notice.dismissRequested() && model.history().contains(notice), "body press requests animated exit with history intact");
            check(button(backend, A, false, true, left, 1) && clicked.get() == 1, "duplicate down remains consumed without dispatching again");
            check(button(backend, A, false, true, left, 0), "paired release is consumed even if body callback retired the hit");
            check(!button(backend, A, false, true, left, 0), "unpaired release passes through");
            check(clicked.get() == 1, "release never repeats an action");
        }
    }
    private static void gatesAndFocus() {
        AtomicInteger clicked = new AtomicInteger(); Notifications.Notice notice = fresh("gates", clicked);
        ToastInputBridge.move(WINDOW + 1, WINDOW, A, false, true, true, 1, 1, 2);
        check(notice.hovered(), "foreign-window move cannot change main-window hover");
        check(!ToastInputBridge.button(Backend.GLFW, WINDOW + 1, WINDOW, A, false, true, true, 0, 1, x, y, 2), "foreign-window press passes through");
        check(!ToastInputBridge.button(Backend.GLFW, 0, 0, A, false, true, true, 0, 1, x, y, 2), "null native handles never target toast");
        check(!button(Backend.GLFW, OWN, true, true, 0, 1) && !button(Backend.GLFW, OWN, true, true, 0, 0), "SkijaConfigScreen delegates to its existing input path exactly once");
        check(clicked.get() == 0, "bypassed screens never dispatch");
        check(!button(Backend.GLFW, null, false, true, 0, 1), "gameplay without a Screen is never intercepted");
        check(!notice.hovered() && !NotificationIntegration.hovered(), "no-Screen clears stale global input");
        fresh("focus", clicked);
        check(button(Backend.GLFW, A, false, true, 0, 1), "capture before focus loss");
        ToastInputBridge.move(WINDOW, WINDOW, A, false, false, true, x, y, 2);
        check(!NotificationIntegration.hovered(), "focus-loss move cancels hover and capture");
        check(!button(Backend.GLFW, A, false, false, 0, 0), "inactive window release is not intercepted");
        tick(A, false, .01); tick(A, true, .01);
        check(!button(Backend.GLFW, A, false, true, 0, 0), "focus regain does not resurrect the old captured press");
        fresh("ratio", clicked);
        check(!ToastInputBridge.button(Backend.GLFW, WINDOW, WINDOW, A, false, true, true, 0, 1, x, y, 0), "zero window width/pixel ratio is rejected safely");
        boolean offThread = false;
        try { ToastInputBridge.move(WINDOW, WINDOW, A, false, true, false, x, y, 2); }
        catch (IllegalStateException expected) { offThread = true; }
        check(offThread, "never requeue a synchronous cancel to a later thread");
        check(clicked.get() == 1, "only the deliberate focus capture action ran");
    }
    private static void releaseAfterScreenChange() {
        AtomicInteger clicked = new AtomicInteger(); fresh("handoff", clicked);
        check(button(Backend.SDL, A, false, true, 1, 1), "left down is consumed before handoff");
        tick(B, true, .01);
        check(button(Backend.SDL, B, false, true, 1, 0), "physical paired release does not fall into a replacement vanilla Screen");
        check(clicked.get() == 1, "Screen handoff does not synthesize another action");
        fresh("foreign release", clicked); check(button(Backend.GLFW, A, false, true, 0, 1), "capture on main window");
        check(!ToastInputBridge.button(Backend.GLFW, WINDOW + 1, WINDOW, B, false, true, true, 0, 0, x, y, 2), "foreign release is ignored without stealing the main capture");
        check(button(Backend.GLFW, A, false, true, 0, 0), "real main release still consumes");
        fresh("cursor leave", clicked); check(button(Backend.GLFW, A, false, true, 0, 1), "capture before explicit cancel");
        ToastInputBridge.cancelInput();
        check(!button(Backend.GLFW, A, false, true, 0, 0), "explicit cancel prevents a late paired callback");
    }
    private static void actionAndOptOut() {
        AtomicInteger clicked = new AtomicInteger(); ToastInputBridge.cancelInput(); model.clearAll(); model.clearHistory();
        Notifications.Notice notice = model.builder("Action", "").persistent().action("Apply", clicked::incrementAndGet).send();
        tick(A, true, .01); ToastInputBridge.move(WINDOW, WINDOW, A, false, true, true, 0, 0, 2); tick(A, true, .22);
        Rect box = NotificationIntegration.hitBoxes().stream().filter(hit -> hit.id().equals("notification.toast.action." + notice.id() + ".0")).findFirst().orElseThrow().framebufferBounds();
        x = (box.x() + box.w() / 2) / 2; y = (box.y() + box.h() / 2) / 2;
        check(button(Backend.GLFW, A, false, true, 0, 1), "action hit is resolved at the latest native cursor position");
        check(clicked.get() == 1 && !model.history().contains(notice), "action retires history unlike body dismissal");
        check(button(Backend.GLFW, A, false, true, 0, 0), "action retirement still consumes matching release");
        fresh("disabled", clicked);
        String previous = System.getProperty("muonium.config.skija.disabled");
        try {
            System.setProperty("muonium.config.skija.disabled", "true");
            check(!button(Backend.GLFW, A, false, true, 0, 1), "existing acceptance opt-out disables interception");
            check(!NotificationIntegration.hovered(), "opt-out clears global hover");
        } finally { if (previous == null) System.clearProperty("muonium.config.skija.disabled"); else System.setProperty("muonium.config.skija.disabled", previous); }
    }
    public static void main(String[] args) {
        backendNumbersAndConsumption(); gatesAndFocus(); releaseAfterScreenChange(); actionAndOptOut();
        System.out.println("ToastInputBridgeBehaviorTest PASS (" + checks + " checks)");
    }
}
