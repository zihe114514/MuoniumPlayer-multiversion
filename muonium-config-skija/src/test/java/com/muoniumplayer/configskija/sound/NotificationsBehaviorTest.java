package com.muoniumplayer.configskija.sound;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.model.Notifications.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.notifications.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Headless notification center, bell, toast and history behavior tests through the real PageContext contract. */
public final class NotificationsBehaviorTest {
    static int checks;
    static void check(boolean pass, String message) { checks++; if (!pass) throw new AssertionError(message); }
    static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < .00001, message + ": " + actual + " != " + expected); }
    record Hit(Rect bounds, Runnable action) {}
    record Shape(Rect bounds, double radius, int color) {}
    static class Draw implements Painter {
        int saves; final List<String> texts = new ArrayList<>(), icons = new ArrayList<>(); final List<Shape> shapes = new ArrayList<>();
        public void save() { saves++; } public void restore() { check(saves > 0, "restore has a matching save"); saves--; }
        public void translate(double x, double y) {} public void scale(double x, double y) {}
        public void clip(Rect r, double radius) { check(r.w() >= 0 && r.h() >= 0, "valid clip"); }
        public void rect(Rect r, double radius, int color) { check(Double.isFinite(r.x()) && Double.isFinite(r.y()) && r.w() >= 0 && r.h() >= 0, "valid shape"); shapes.add(new Shape(r, radius, color)); }
        public void outline(Rect r, double radius, double w, int color) {} public void line(double x1, double y1, double x2, double y2, double w, int color) {}
        public void text(String text, double x, double y, double size, boolean bold, int color) { texts.add(text); }
        public void icon(String name, double x, double y, double size, int color) { icons.add(name); }
        public double measure(String text, double size, boolean bold) { return text.codePointCount(0, text.length()) * size * .5; }
    }
    static final class Context implements InvocationHandler {
        final Draw painter = new Draw(); final List<String> errors = new ArrayList<>(), sounds = new ArrayList<>();
        final Map<String, Hit> hits = new LinkedHashMap<>(); final UiPreferences prefs = new UiPreferences(Map.of(), () -> {});
        final PageContext context = (PageContext)Proxy.newProxyInstance(PageContext.class.getClassLoader(), new Class<?>[]{PageContext.class}, this);
        final ConfigHost host = new ConfigHost() {
            public List<Category> categories() { return List.of(); } public List<HudElement> huds() { return List.of(); }
            public void persist() {} public void close() {} public String clipboard() { return ""; } public void clipboard(String text) {}
            public void error(String action, Throwable error) { errors.add(action); }
        };
        UiPage overlay; Theme theme = Theme.BUILTIN[0]; Rect shell = new Rect(0, 0, 1391, 700);
        double now, x = -10, y = -10, delta = .016; int dismissed;
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "painter" -> painter; case "theme" -> theme; case "accent" -> 0xFF2B4BFF; case "host" -> host; case "preferences" -> prefs;
                case "query" -> ""; case "route" -> new Navigation.Route(Navigation.Page.SETTINGS);
                case "mouseX" -> x; case "mouseY" -> y; case "width" -> shell.w(); case "height" -> shell.h(); case "shell", "content" -> shell;
                case "time" -> now; case "delta" -> delta; case "control", "shift", "alt" -> false; case "nativeKeyCode" -> 0; case "scroll" -> 0d;
                case "hit", "contentHit" -> { hits.put((String)args[0], new Hit((Rect)args[1], (Runnable)args[2])); yield null; }
                case "overlay" -> { if (overlay != null) overlay.leave(context); overlay = (UiPage)args[0]; yield null; }
                case "dismissOverlay" -> { UiPage old = overlay; overlay = null; dismissed++; if (old != null) old.leave(context); yield null; }
                case "sound" -> { sounds.add((String)args[0]); yield null; }
                case "animate" -> args[1];
                default -> null;
            };
        }
        void frame(UiPage page) { hits.clear(); painter.texts.clear(); painter.icons.clear(); painter.shapes.clear(); page.render(context); check(painter.saves == 0, "balanced draw state"); }
        void click(String id) { Hit hit = hits.get(id); check(hit != null, "hit exists: " + id); x = hit.bounds.x() + hit.bounds.w() / 2; y = hit.bounds.y() + hit.bounds.h() / 2; hit.action.run(); }
    }
    static void history() throws Exception {
        AtomicLong clock = new AtomicLong(1000); Notifications notices = new Notifications(clock::get); Notice first = notices.info("first", "body");
        clock.set(2000); Notice second = notices.error("second", "body");
        check(first.createdAt() == 1000 && second.createdAt() == 2000, "notification timestamps use the supplied clock");
        check(notices.active().equals(List.of(first, second)) && notices.history().equals(List.of(second, first)), "active oldest/history newest");
        List<Notice> old = notices.history(); for (int i = 0; i < 60; i++) notices.success("notice " + i, "");
        check(old.size() == 2, "history snapshots are stable"); check(notices.history().size() == 50 && notices.count() == 62, "history limit does not retire toasts");
        check(notices.unreadCount() == 50, "only retained history counts as unread");
        notices.markAllRead(); check(notices.unreadCount() == 0 && notices.history().stream().allMatch(Notice::read), "mark all read");
        Notice later = notices.info("later", ""); check(notices.unreadCount() == 1, "new events remain unread"); notices.dismiss(later); check(notices.history().contains(later), "toast dismissal keeps history");
        notices.clearHistory(); check(notices.history().isEmpty() && notices.count() == 62, "clear history keeps live toasts");
        notices.info("saved", ""); notices.clearAll(); check(notices.count() == 0 && notices.history().size() == 1, "clearAll is source active-only");
        AtomicInteger called = new AtomicInteger(); Notice progress = notices.progress("loading", "", () -> 2); near(progress.progressOrNull(), 1, "clamp progress high");
        check(!progress.persistent() && progress.hasProgress(), "progress duration semantics");
        Notice failed = notices.progress("loading", "", () -> { throw new IllegalStateException(); }); check(failed.progressOrNull() == null, "failing progress degrades without crashing paint");
        Notice persistent = notices.builder("persistent", "").persistent().action("ok", called::incrementAndGet).send(); check(persistent.persistent(), "persistent builder");
        byte[] image = {1, 2}; Notice icon = notices.builder("icon", "").image(image, 2, 1).send(); image[0] = 9;
        check(icon.image().encoded()[0] == 1, "custom image owns a byte copy");
        notices.remove(persistent); check(persistent.dismissRequested() && !notices.history().contains(persistent) && notices.active().contains(persistent), "remove requests animated exit and retires history");
        Notifications concurrent = new Notifications(); List<Thread> writers = new ArrayList<>();
        for (int i = 0; i < 4; i++) { Thread writer = new Thread(() -> { for (int j = 0; j < 100; j++) { concurrent.info("async", ""); concurrent.history(); } }); writers.add(writer); writer.start(); }
        for (Thread writer : writers) writer.join(); check(concurrent.count() == 400 && concurrent.history().size() == 50, "host worker events are safe to read from UI snapshots");
    }
    static void timesAndLayout() {
        long now = 10_000_000; long[] seconds = {-10, 0, 4, 5, 59, 60, 3599, 3600, 86399, 86400};
        String[] expected = {"Just now", "Just now", "Just now", "5s ago", "59s ago", "1m ago", "59m ago", "1h ago", "23h ago", "1d ago"};
        for (int i = 0; i < seconds.length; i++) check(NotificationLayout.relativeTime(now - seconds[i] * 1000, now).equals(expected[i]), "relative time boundary " + seconds[i]);
        Rect bell = new Rect(210, 650, 18, 18); Rect panel = NotificationLayout.panel(bell, 200, new Rect(0, 0, 1391, 700));
        near(panel.x(), 210, "BottomStart x"); near(panel.bottom(), 638, "twelve dp above bell"); near(panel.w(), 368, "source popup width");
        Draw draw = new Draw(); List<String> lines = NotificationLayout.wrap(draw, "通知😀内容 abcdefghijklmnop\n第二行", 35, 12, false);
        check(lines.size() > 2 && lines.stream().allMatch(line -> draw.measure(line, 12, false) <= 35), "CJK/newline/long token wrapping");
        NotificationScroll smooth = new NotificationScroll(); smooth.push(150); double distance = 0; for (int i = 0; i < 120; i++) distance += smooth.advance(1.0 / 60);
        near(distance, 150, "wheel spring settles at exact notch distance"); smooth.push(150); double first = smooth.advance(.016); smooth.push(-150); double reverse = smooth.advance(.016); check(first > 0 && reverse < 0, "reversing cancels old glide");
    }
    static void centerAndBell() {
        Notifications notices = new Notifications(() -> 1000); Notice notice = notices.info("Source title", "Text in the history"); Context ctx = new Context();
        NotificationBell bell = new NotificationBell(notices, "清除全部通知"); Rect anchor = new Rect(210, 650, 18, 18); bell.render(ctx.context, anchor); ctx.click("notifications.bell");
        check(ctx.overlay instanceof NotificationsCenter, "bell opens a real UiPage"); NotificationsCenter center = (NotificationsCenter)ctx.overlay; ctx.frame(center);
        check(ctx.painter.texts.containsAll(List.of("Notifications", "Source title", "Text in the history", "清除全部通知")), "source center content and localized footer");
        check(notices.unreadCount() == 1 && !notice.read(), "opening preserves unread"); near(center.bounds().w(), 368, "center width"); near(center.bounds().bottom(), 638, "center anchor");
        check(ctx.painter.icons.contains("ico/info.svg"), "source type icon");
        ctx.x = -1; ctx.y = -1; check(center.pointerDown(ctx.context, 0), "outside click is consumed"); check(center.closed() && notices.unreadCount() == 0 && ctx.overlay == null, "dismiss marks all history read");
        notices.success("New", ""); bell.render(ctx.context, anchor); ctx.click("notifications.bell"); center = (NotificationsCenter)ctx.overlay; ctx.frame(center);
        int live = notices.count(); ctx.click("notifications.clear"); check(notices.history().isEmpty() && notices.count() == live && !center.closed(), "clear footer keeps popup open and live toasts"); ctx.frame(center); check(ctx.painter.texts.contains("You're all caught up."), "source empty message appears only for real empty history");
        center.key(ctx.context, ConfigView.Key.ESCAPE, "", false, false, false); check(center.closed(), "escape dismisses");
        AtomicInteger actions = new AtomicInteger(); Notice action = notices.builder("Apply", "").action(new Action("Done", true, actions::incrementAndGet)).send();
        center = new NotificationsCenter(notices, anchor); ctx.overlay = center; ctx.frame(center); ctx.click("notification.action." + action.id() + ".0");
        check(actions.get() == 1 && action.dismissRequested() && !notices.history().contains(action), "history action runs and removes notification"); check(notices.active().contains(action), "action leaves active toast until exit animation");
        center.leave(ctx.context); center.leave(ctx.context); check(center.closed(), "leave is idempotent");
        notices.clearAll(); notices.clearHistory(); for (int i = 0; i < 20; i++) notices.info("Event " + i, "A long notification body to make this list scroll.");
        center = new NotificationsCenter(notices, anchor); ctx.overlay = center; ctx.frame(center); near(center.listBounds().h(), 420, "source maximum list height");
        check(center.maximumScroll() > 0, "long history scrolls"); ctx.x = center.listBounds().x() + 40; ctx.y = center.listBounds().y() + 40; center.wheel(ctx.context, -1);
        for (int i = 0; i < 120; i++) { ctx.now += .016; ctx.frame(center); } near(center.scrollOffset(), 150, "real center scrolls original Windows notch distance");
        center.scrollWheel(ctx.context, -100, 150, true); near(center.scrollOffset(), center.maximumScroll(), "precise scroll clamps to content extent");
        for (Theme theme : Theme.BUILTIN) { ctx.theme = theme; ctx.frame(center); check(ctx.painter.saves == 0, "all theme draw states balance"); }
    }
    static void typography() {
        class ExactDraw extends Draw implements NotificationTypography {
            final List<Integer> weights = new ArrayList<>();
            public void notificationText(String text, double x, double y, double size, int weight, int color) { weights.add(weight); super.text(text, x, y, size, weight >= 500, color); }
            public double notificationMeasure(String text, double size, int weight) { weights.add(weight); return super.measure(text, size, weight >= 500); }
        }
        ExactDraw draw = new ExactDraw();
        NotificationLayout.text(draw, "medium", 0, 0, 18, 500, -1); NotificationLayout.text(draw, "semi", 0, 0, 14, 600, -1);
        check(draw.weights.equals(List.of(500, 600)), "exact source medium and semibold reach the host extension");
    }
    static void toastTimeline() {
        Notifications notices = new Notifications(); Notice timed = notices.builder("timer", "").duration(1000).send(); ToastTimeline timeline = new ToastTimeline(timed);
        timeline.advance(0, false); near(timeline.opacity(), 0, "enter starts transparent"); timeline.advance(110_000_000, false); near(timeline.opacity(), .875, "source easeOutCubic midpoint");
        timeline.advance(220_000_000, false); timeline.advance(720_000_000, false); near(timeline.timeFraction(), .5, "timer starts after entering");
        timeline.advance(2_720_000_000L, true); near(timeline.timeFraction(), .5, "hover pauses remaining time");
        timeline.advance(3_220_000_000L, false); check(timeline.phase() == ToastTimeline.Phase.EXIT, "timer starts exit");
        timeline.advance(3_330_000_000L, false); near(timeline.opacity(), .875, "source easeInCubic exit midpoint"); timeline.advance(3_440_000_000L, false); check(timeline.done(), "220 ms exit completes");
        AtomicReference<Double> value = new AtomicReference<>(.2); Notice progress = notices.progress("progress", "", () -> value.get()); ToastTimeline pt = new ToastTimeline(progress);
        pt.advance(0, false); pt.advance(220_000_000, false); value.set(1.0); pt.advance(300_000_000, false); check(pt.phase() == ToastTimeline.Phase.LINGER, "completed progress lingers");
        pt.advance(899_999_999, false); check(pt.phase() == ToastTimeline.Phase.LINGER, "linger lasts full 600 ms"); pt.advance(900_000_000, false); check(pt.phase() == ToastTimeline.Phase.EXIT, "progress enters exit");
        Notice persistent = notices.builder("keep", "").persistent().send(); ToastTimeline keep = new ToastTimeline(persistent); keep.advance(0, false); keep.advance(220_000_000, false); keep.advance(100_000_000_000L, false); check(keep.phase() == ToastTimeline.Phase.WAIT, "persistent toast has no timeout");
        persistent.requestDismiss(); keep.advance(100_000_000_001L, false); check(keep.phase() == ToastTimeline.Phase.EXIT, "manual dismiss"); near(ToastTimeline.quantize(.501, 312), 156d / 312, "physical pixel quantization");
    }
    static void toastSurface() {
        Notifications notices = new Notifications(); Notice timed = notices.info("Toast", "A source toast message"); Context ctx = new Context(); NotificationToasts toasts = new NotificationToasts(notices);
        ctx.frame(toasts); ctx.now = .22; ctx.frame(toasts); check(ctx.painter.texts.contains("Toast"), "live toast actually draws");
        Hit body = ctx.hits.get("notification.toast." + timed.id()); check(body != null, "toast body hit"); near(body.bounds.w(), 340, "source toast width"); near(body.bounds.right(), 1391 - 16, "source toast right margin");
        ctx.click("notification.toast." + timed.id()); ctx.now = .23; ctx.frame(toasts); ctx.now = .45; ctx.frame(toasts);
        check(!notices.active().contains(timed) && notices.history().contains(timed), "body dismissal animates out and retains history");
        Notice action = notices.builder("Failure action", "").persistent().action("Throw", () -> { throw new IllegalStateException(); }).send();
        ctx.now = 1; ctx.frame(toasts); ctx.now = 1.22; ctx.frame(toasts); ctx.click("notification.toast.action." + action.id() + ".0");
        check(!ctx.errors.isEmpty() && action.dismissRequested() && !notices.history().contains(action), "toast callback failure reports host error and still retires");
        Notice noScreen = notices.info("No screen", ""); ctx.hits.clear(); ctx.now = 2; toasts.render(ctx.context, ctx.shell, 1, false);
        check(!noScreen.hovered() && ctx.hits.isEmpty(), "global toast input is disabled without a Minecraft screen");
    }
    public static void main(String[] args) throws Exception {
        history(); timesAndLayout(); centerAndBell(); typography(); toastTimeline(); toastSurface();
        System.out.println("NotificationsBehaviorTest PASS (" + checks + " checks)");
    }
}
