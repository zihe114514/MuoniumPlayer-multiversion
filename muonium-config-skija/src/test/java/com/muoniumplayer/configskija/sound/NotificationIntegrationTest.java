package com.muoniumplayer.configskija.sound;

import com.muoniumplayer.configskija.host.notifications.NotificationIntegration;
import com.muoniumplayer.configskija.host.notifications.NotificationIntegration.Frame;
import com.muoniumplayer.configskija.host.notifications.NotificationIntegration.HitBox;
import com.muoniumplayer.configskija.host.notifications.NotificationIntegration.Viewport;
import com.muoniumplayer.configskija.model.Notifications;
import com.muoniumplayer.configskija.model.UiPreferences;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.Painter;
import com.muoniumplayer.configskija.ui.notifications.NotificationTypography;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Source global-HUD/Screen integration scenarios; no Minecraft, native renderer, game or Gradle. */
public final class NotificationIntegrationTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < .00001, message + ": " + actual + " != " + expected); }
    private static final class Drawing implements Painter, NotificationTypography {
        int saves, draws, measures, textCount; final List<String> texts = new ArrayList<>(), transforms = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>(), weights = new ArrayList<>();
        public void save() { saves++; } public void restore() { check(saves > 0, "balanced live canvas restore"); saves--; }
        public void translate(double x, double y) { transforms.add("translate:" + x + "," + y); }
        public void scale(double x, double y) { transforms.add("scale:" + x + "," + y); }
        public void clip(Rect bounds, double radius) { check(bounds.w() >= 0 && bounds.h() >= 0, "nonnegative viewport clip"); }
        public void rect(Rect bounds, double radius, int color) { draws++; colors.add(color); }
        public void outline(Rect bounds, double radius, double width, int color) { draws++; }
        public void line(double x1, double y1, double x2, double y2, double width, int color) { draws++; }
        public void text(String text, double x, double y, double size, boolean bold, int color) { draws++; textCount++; texts.add(text); }
        public double measure(String text, double size, boolean bold) { measures++; return text.codePointCount(0, text.length()) * size * .5; }
        public void icon(String icon, double x, double y, double size, int color) { draws++; }
        public void notificationText(String text, double x, double y, double size, int weight, int color) { weights.add(weight); text(text, x, y, size, weight >= 500, color); }
        public double notificationMeasure(String text, double size, int weight) { weights.add(weight); return measure(text, size, weight >= 500); }
        void clear() { draws = measures = textCount = 0; texts.clear(); transforms.clear(); colors.clear(); weights.clear(); }
    }
    private static final Notifications model = NotificationIntegration.notifications();
    private static final Drawing metrics = new Drawing(), painter = new Drawing();
    private static final UiPreferences preferences = new UiPreferences(Map.of(), () -> {});
    private static final List<String> errors = new ArrayList<>();
    private static Viewport viewport = new Viewport(110, 70, 800, 480, 2);
    private static final Object SCREEN_A = new Object(), SCREEN_B = new Object();
    private static Object screen;
    private static boolean focused = true;
    private static double now;
    private static void reset() {
        model.clearAll(); model.clearHistory(); NotificationIntegration.cancelInput();
        screen = null; focused = true; viewport = new Viewport(110, 70, 800, 480, 2);
        metrics.clear(); painter.clear(); errors.clear(); advance(.01);
    }
    private static void advance(double seconds) {
        now += seconds;
        NotificationIntegration.tick(new Frame(metrics, preferences, viewport, now, seconds, screen, focused));
    }
    private static HitBox body(Notifications.Notice notice) {
        return NotificationIntegration.hitBoxes().stream().filter(hit -> hit.id().equals("notification.toast." + notice.id())).findFirst().orElseThrow();
    }
    private static HitBox action(Notifications.Notice notice, int index) {
        return NotificationIntegration.hitBoxes().stream().filter(hit -> hit.id().equals("notification.toast.action." + notice.id() + "." + index)).findFirst().orElseThrow();
    }
    private static boolean move(Object identity, HitBox box) {
        Rect r = box.framebufferBounds(); return NotificationIntegration.pointerMove(identity, r.x() + r.w() / 2, r.y() + r.h() / 2);
    }
    private static void arm() { advance(0); NotificationIntegration.pointerMove(screen, viewport.x() + 1, viewport.y() + 1); advance(0); }

    private static void globalHud() {
        reset(); Notifications.Notice notice = model.info("Global HUD notification", "Settings are closed.");
        advance(0); check(metrics.draws == 0 && metrics.saves == 0, "tick only measures; it never draws to the host canvas");
        advance(.22); NotificationIntegration.render(painter); check(painter.texts.contains("Global HUD notification"), "toast paints with no settings or Screen open");
        check(NotificationIntegration.hitBoxes().isEmpty() && !notice.hovered(), "HUD without Screen exposes no input or hover");
        check(painter.transforms.contains("translate:110.0,70.0") && painter.transforms.contains("scale:2.0,2.0"), "source viewport transform is applied once");
        check(painter.saves == 0, "render restores the supplied canvas");
        int live = model.count(); NotificationIntegration.render(painter); NotificationIntegration.render(painter);
        check(model.count() == live && model.history().contains(notice), "duplicate HUD/Screen drawing never advances or retires timers");
        advance(4); check(model.contains(notice), "timer starts an exit rather than dropping history immediately"); advance(.22);
        check(!model.contains(notice) && model.history().contains(notice), "global tick retires a timed toast without settings or any render call");
    }
    private static void coordinatesAndHover() {
        reset(); screen = SCREEN_A; Notifications.Notice notice = model.builder("Timer", "").duration(1000).send(); arm();
        advance(.22); HitBox hit = body(notice); Rect r = hit.framebufferBounds();
        near(r.w(), 680, "340 logical dp at two framebuffer pixels per dp");
        near(r.right(), viewport.x() + viewport.width() - 32, "source 16 dp right margin in framebuffer space");
        near(r.bottom(), viewport.y() + viewport.height() - 32, "source 16 dp bottom margin");
        check(move(SCREEN_A, hit), "pointer hover resolves in framebuffer coordinates"); check(notice.hovered(), "hover pauses the actual notice");
        advance(4); check(model.contains(notice), "hover pauses timed duration over multiple seconds");
        NotificationIntegration.pointerMoveWindow(SCREEN_A, (r.x() + r.w() / 2) / 1.5, (r.y() + r.h() / 2) / 1.5, 1.5);
        check(notice.hovered(), "window coordinates use pixelRatio before viewport inverse");
        NotificationIntegration.cancelInput(); check(!notice.hovered() && !NotificationIntegration.hovered(), "cancel clears pause and all targets");
        check(NotificationIntegration.hitBoxes().isEmpty(), "cancel drops the old layout hits");
        advance(1); advance(.22); check(!model.contains(notice), "canceled hover allows the original timer to finish");
        check(model.unreadCount() == 1, "hover/cancel/timer do not mark notification history read");
    }
    private static void pressActionsAndBody() {
        reset(); screen = SCREEN_A; AtomicInteger count = new AtomicInteger();
        Notifications.Notice actionNotice = model.builder("Apply", "").persistent().action(new Notifications.Action("Done", true, count::incrementAndGet)).send();
        arm(); advance(.22); move(SCREEN_A, action(actionNotice, 0));
        check(!NotificationIntegration.pointerDown(SCREEN_A, 1), "right press does not trigger toast actions");
        check(NotificationIntegration.pointerDown(SCREEN_A, 0), "source invokes the action on left press"); check(count.get() == 1, "action ran once on press");
        check(actionNotice.dismissRequested() && !model.history().contains(actionNotice), "action requests exit and removes history");
        check(NotificationIntegration.pointerUp(SCREEN_A, 0) && count.get() == 1, "release is paired without invoking the action twice");
        advance(.01); advance(.22); check(!model.contains(actionNotice), "action exit completes");
        Notifications.Notice bodyNotice = model.builder("Open details", "").persistent().onClick(count::incrementAndGet).send(); advance(0); advance(.22); move(SCREEN_A, body(bodyNotice));
        check(NotificationIntegration.pointerDown(SCREEN_A, 0), "body press handled");
        check(count.get() == 2 && bodyNotice.dismissRequested() && model.history().contains(bodyNotice), "source body callback keeps history while dismissing live toast");
        NotificationIntegration.cancelInput(); check(!NotificationIntegration.pointerUp(SCREEN_A, 0), "canceled capture cannot deliver a late release");
        Notifications.Notice failed = model.builder("Failure", "").persistent().action("Throw", () -> { throw new IllegalStateException("expected"); }).send(); arm(); advance(.22); move(SCREEN_A, action(failed, 0));
        NotificationIntegration.pointerDown(SCREEN_A, 0); NotificationIntegration.pointerUp(SCREEN_A, 0);
        check(errors.size() == 1 && !model.history().contains(failed), "host error callback receives action failure while notification still retires");
    }
    private static void screenLifecycle() {
        reset(); screen = SCREEN_A; Notifications.Notice notice = model.builder("Persistent across screens", "").persistent().send();
        arm(); advance(.22); HitBox previous = body(notice); move(SCREEN_A, previous);
        check(notice.hovered(), "old Screen hovered before handoff");
        screen = SCREEN_B; advance(.01);
        check(model.contains(notice) && model.unreadCount() == 1, "Screen handoff retains live state and unread history");
        check(!NotificationIntegration.pointerDown(SCREEN_A, 0), "old Screen press cannot use new Screen hits");
        check(!NotificationIntegration.pointerMove(SCREEN_A, previous.framebufferBounds().x(), previous.framebufferBounds().y()), "late old Screen move is ignored");
        check(NotificationIntegration.pointerMove(SCREEN_B, previous.framebufferBounds().x() + 20, previous.framebufferBounds().y() + 20), "new Screen resolves latest retained layout");
        focused = false; advance(.01); check(!notice.hovered() && NotificationIntegration.hitBoxes().isEmpty(), "focus loss removes hit/capture/hover");
        focused = true; advance(.01); check(!NotificationIntegration.pointerDown(SCREEN_B, 0), "focus regain cannot replay stale captured input");
        arm(); HitBox latest = body(notice); move(SCREEN_B, latest);
        viewport = new Viewport(110, 70, 1400, 800, 2); advance(.01);
        check(!NotificationIntegration.pointerDown(SCREEN_B, 0), "resize invalidates old toast position");
        check(model.contains(notice), "resize never restarts or destroys a persistent toast");
        screen = null; advance(.01); check(NotificationIntegration.hitBoxes().isEmpty() && !notice.hovered(), "returning to gameplay disables input without losing notification");
        NotificationIntegration.render(painter); check(painter.texts.contains("Persistent across screens"), "global HUD can paint retained toast after Screen closes");
    }
    private static void maxVisibleAndDismissal() {
        reset(); screen = SCREEN_A; List<Notifications.Notice> all = new ArrayList<>();
        for (int i = 0; i < 80; i++) all.add(model.builder("Active " + i, "").persistent().send());
        arm(); advance(.22); check(model.count() == 80 && model.history().size() == 50, "source has no maxVisible cap; only history is limited to fifty");
        for (HitBox hit : NotificationIntegration.hitBoxes()) {
            Rect bounds = hit.framebufferBounds();
            check(bounds.x() >= viewport.x() && bounds.y() >= viewport.y() && bounds.right() <= viewport.x() + viewport.width() && bounds.bottom() <= viewport.y() + viewport.height(), "input uses clipped viewport geometry");
        }
        Notifications.Notice last = all.get(79); move(SCREEN_A, body(last)); model.dismiss(last);
        check(!NotificationIntegration.pointerDown(SCREEN_A, 0), "externally retired notice cannot fire a previously prepared hit");
        check(model.history().contains(last), "source immediate dismiss retains history");
        Notifications.Notice animated = all.get(78); NotificationIntegration.requestDismiss(animated.id());
        check(animated.dismissRequested() && model.history().contains(animated), "explicit requestDismiss preserves history");
        advance(.22); check(!model.contains(animated), "animated explicit dismissal finishes on global tick");
        Notifications.Notice immediate = all.get(77); NotificationIntegration.dismiss(immediate.id());
        check(!model.contains(immediate) && model.history().contains(immediate), "static immediate dismissal matches source");
    }
    private static void clippingClockAndThreads() throws Exception {
        reset(); screen = SCREEN_A; Notifications.Notice timed = model.builder("Minimized", "").duration(1000).send();
        viewport = new Viewport(0, 0, 0, 0, 2); advance(0); advance(.22); advance(1); advance(.22);
        check(!model.contains(timed), "minimized/no-draw frames still advance the retained timer");
        check(NotificationIntegration.hitBoxes().isEmpty(), "zero-sized viewport exposes no input");
        Viewport fromGui = Viewport.fromGui(960, 540, 2); near(fromGui.logicalBounds().w(), 1920, "source logical viewport is twice GUI width"); near(fromGui.scale(), 1, "source half-GUI-scale transform");
        boolean rejected = false; try { new Viewport(0, 0, -1, 100, 1); } catch (IllegalArgumentException expected) { rejected = true; } check(rejected, "invalid viewport rejected before native drawing");
        AtomicReference<Throwable> crossThread = new AtomicReference<>(); Thread worker = new Thread(() -> {
            model.info("Worker notification", "");
            try { NotificationIntegration.cancelInput(); } catch (Throwable expected) { crossThread.set(expected); }
        }); worker.start(); worker.join();
        check(model.history().stream().anyMatch(notice -> notice.title().equals("Worker notification")), "model accepts background host events");
        check(crossThread.get() instanceof IllegalStateException, "frame/input methods enforce the host UI thread");
        check(NotificationIntegration.notifications() == Notifications.shared(), "center and global HUD share exactly one original model");
    }
    public static void main(String[] args) throws Exception {
        NotificationIntegration.errors((action, error) -> errors.add(action));
        globalHud(); coordinatesAndHover(); pressActionsAndBody(); screenLifecycle(); maxVisibleAndDismissal(); clippingClockAndThreads();
        System.out.println("NotificationIntegrationTest PASS (" + checks + " checks)");
    }
}
