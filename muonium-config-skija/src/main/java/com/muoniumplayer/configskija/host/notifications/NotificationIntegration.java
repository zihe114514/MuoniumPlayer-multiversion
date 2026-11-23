package com.muoniumplayer.configskija.host.notifications;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.model.HudElement;
import com.muoniumplayer.configskija.model.Navigation;
import com.muoniumplayer.configskija.model.Notifications;
import com.muoniumplayer.configskija.model.UiPreferences;
import com.muoniumplayer.configskija.ui.ConfigView;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.Motion;
import com.muoniumplayer.configskija.ui.PageContext;
import com.muoniumplayer.configskija.ui.Painter;
import com.muoniumplayer.configskija.ui.TextEdit;
import com.muoniumplayer.configskija.ui.Theme;
import com.muoniumplayer.configskija.ui.UiPage;
import com.muoniumplayer.configskija.ui.notifications.NotificationLayout;
import com.muoniumplayer.configskija.ui.notifications.NotificationToasts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Persistent adapter for the host's existing global Skija frame. No native renderer or MC hooks.
 * Call tick once per global frame, then render after HUD/Screen. Mouse coordinates are framebuffer
 * pixels; screenIdentity is the real stable Screen identity, or null when no Screen is open.
 * Only notifications() is worker-safe. All frame/input methods run on the same host UI thread.
 */
public final class NotificationIntegration {
    private static final Session SHARED = new Session(Notifications.shared());
    private NotificationIntegration() {}

    /** Framebuffer rectangle and framebuffer pixels per logical notification dp. */
    public record Viewport(double x, double y, double width, double height, double scale) {
        public Viewport {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) ||
                !Double.isFinite(height) || !Double.isFinite(scale) || width < 0 || height < 0 || scale <= 0)
                throw new IllegalArgumentException("Finite nonnegative viewport and positive scale required");
        }
        /** Source: notification scale = guiScale * .5; GUI width/height are MC scaled screen units. */
        public static Viewport fromGui(double guiWidth, double guiHeight, double guiScale) {
            return new Viewport(0, 0, guiWidth * guiScale, guiHeight * guiScale, guiScale * .5);
        }
        public Rect logicalBounds() { return new Rect(0, 0, width / scale, height / scale); }
        public double localX(double framebufferX) { return (framebufferX - x) / scale; }
        public double localY(double framebufferY) { return (framebufferY - y) / scale; }
        public Rect framebufferBounds(Rect logical) {
            return new Rect(x + logical.x() * scale, y + logical.y() * scale, logical.w() * scale, logical.h() * scale);
        }
    }
    /** Painter is used only for text metrics during tick. render receives the already-owned live painter. */
    public record Frame(Painter metrics, UiPreferences preferences, Viewport viewport,
                        double monotonicSeconds, double deltaSeconds, Object screenIdentity, boolean focused) {
        public Frame {
            Objects.requireNonNull(metrics); Objects.requireNonNull(preferences); Objects.requireNonNull(viewport);
            if (!Double.isFinite(monotonicSeconds) || !Double.isFinite(deltaSeconds) || monotonicSeconds < 0 || deltaSeconds < 0)
                throw new IllegalArgumentException("Finite nonnegative monotonic time and delta required");
        }
    }
    /** Read-only debug/acceptance geometry. Never expose callbacks from a previous Screen or layout. */
    public record HitBox(String id, Rect framebufferBounds) {}

    public static Notifications notifications() { return SHARED.notifications; }
    public static void errors(BiConsumer<String, Throwable> handler) { SHARED.errors = Objects.requireNonNull(handler); }
    public static void tick(Frame frame) { SHARED.tick(frame); }
    public static void render(Painter painter) { SHARED.render(painter); }
    public static boolean pointerMove(Object screenIdentity, double framebufferX, double framebufferY) {
        return SHARED.move(screenIdentity, framebufferX, framebufferY);
    }
    /** Window-system logical coordinates, as used by the original ToastInput, need pixelRatio first. */
    public static boolean pointerMoveWindow(Object screenIdentity, double x, double y, double pixelRatio) {
        if (!Double.isFinite(pixelRatio) || pixelRatio <= 0) throw new IllegalArgumentException("Positive pixel ratio required");
        return pointerMove(screenIdentity, x * pixelRatio, y * pixelRatio);
    }
    /** Source dispatches on left press, not on release. True means the toast handled this press. */
    public static boolean pointerDown(Object screenIdentity, int button) { return SHARED.down(screenIdentity, button); }
    public static boolean pointerUp(Object screenIdentity, int button) { return SHARED.up(screenIdentity, button); }
    /** Use for window focus loss, cursor leave, removed Screen or canceled capture. Does not dismiss notifications. */
    public static void cancelInput() { SHARED.cancel(); }
    public static boolean hovered() { return SHARED.target != null; }
    public static List<HitBox> hitBoxes() { return SHARED.boxes(); }
    /** Animated body-style dismissal; preserves history and unread state. */
    public static void requestDismiss(long id) { SHARED.requestDismiss(id); }
    /** Source Notifications.dismiss: immediately removes the live toast and retains history. */
    public static void dismiss(long id) { SHARED.dismiss(id); }

    private record Hit(String id, Rect bounds, Runnable action) {}
    private static final class Session {
        private final Notifications notifications;
        private final NotificationToasts toasts;
        private final Context context = new Context();
        private final Motion motion = new Motion();
        private final List<Hit> hits = new ArrayList<>();
        private volatile BiConsumer<String, Throwable> errors = (action, failure) ->
            System.getLogger("MuoniumConfig/Notifications").log(System.Logger.Level.ERROR, action, failure);
        private Thread uiThread;
        private Frame frame;
        private Painter painter;
        private Theme theme;
        private Hit target;
        private double now, mouseX = Double.NaN, mouseY = Double.NaN;
        private boolean canceled, pressed;
        private Object pressedScreen;

        private Session(Notifications notifications) { this.notifications = notifications; toasts = new NotificationToasts(notifications); }
        private void thread() {
            Thread current = Thread.currentThread();
            if (uiThread == null) uiThread = current;
            else if (uiThread != current) throw new IllegalStateException("Notification frames and input require one host UI thread");
        }
        private boolean input() {
            return frame != null && frame.screenIdentity() != null && frame.focused() && !canceled &&
                frame.viewport().width() > 0 && frame.viewport().height() > 0 &&
                Double.isFinite(mouseX) && Double.isFinite(mouseY) && mouseX >= 0 && mouseY >= 0;
        }
        private void tick(Frame next) {
            thread(); Objects.requireNonNull(next);
            boolean screenChanged = frame == null || frame.screenIdentity() != next.screenIdentity();
            boolean viewportChanged = frame != null && !frame.viewport().equals(next.viewport());
            if (screenChanged || viewportChanged || !next.focused()) invalidate();
            if (screenChanged) canceled = false; // source retains latest mouse coordinates across Screen changes
            if (!next.focused()) canceled = true;
            frame = next; now = Math.max(now, next.monotonicSeconds());
            painter = next.metrics(); theme = Theme.transition(context);
            prepare();
        }
        private void prepare() {
            hits.clear(); target = null;
            if (frame == null) return;
            painter = frame.metrics();
            toasts.tick(context, frame.viewport().logicalBounds(), frame.viewport().scale(), input());
            if (input()) {
                double x = frame.viewport().localX(mouseX), y = frame.viewport().localY(mouseY);
                for (Hit hit : hits) if (hit.bounds().contains(x, y)) target = hit;
            }
        }
        private void render(Painter drawing) {
            thread(); Objects.requireNonNull(drawing);
            if (frame == null) return;
            Painter previous = painter; painter = drawing;
            drawing.save();
            try {
                Viewport viewport = frame.viewport();
                drawing.translate(viewport.x(), viewport.y()); drawing.scale(viewport.scale(), viewport.scale());
                toasts.draw(context);
            } finally { drawing.restore(); painter = previous; }
        }
        private boolean move(Object screen, double x, double y) {
            thread();
            if (frame != null && screen != frame.screenIdentity()) return false;
            mouseX = x; mouseY = y;
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0) { cancel(); return false; }
            canceled = false;
            if (frame != null) prepare();
            return target != null;
        }
        private boolean down(Object screen, int button) {
            thread();
            if (button != 0 || pressed || !input() || screen != frame.screenIdentity()) return false;
            Hit clicked = target;
            if (clicked == null || !live(clicked)) { hits.clear(); target = null; toasts.cancelInput(); return false; }
            pressed = true; pressedScreen = screen;
            try { clicked.action().run(); }
            finally { hits.clear(); target = null; toasts.cancelInput(); }
            return true;
        }
        private boolean live(Hit hit) {
            for (Notifications.Notice notice : notifications.active()) {
                if ((hit.id().equals("notification.toast." + notice.id()) ||
                    hit.id().startsWith("notification.toast.action." + notice.id() + ".")) && !notice.dismissRequested()) return true;
            }
            return false;
        }
        private boolean up(Object screen, int button) {
            thread();
            if (button != 0) return false;
            boolean handled = pressed && screen == pressedScreen && frame != null && screen == frame.screenIdentity();
            pressed = false; pressedScreen = null; return handled;
        }
        private void invalidate() {
            hits.clear(); target = null; pressed = false; pressedScreen = null; toasts.cancelInput();
        }
        private void cancel() { thread(); canceled = true; invalidate(); }
        private List<HitBox> boxes() {
            thread();
            if (frame == null) return List.of();
            return hits.stream().map(hit -> new HitBox(hit.id(), frame.viewport().framebufferBounds(hit.bounds()))).toList();
        }
        private void requestDismiss(long id) {
            thread();
            for (Notifications.Notice notice : notifications.active()) if (notice.id() == id) notice.requestDismiss();
            if (frame != null) prepare();
        }
        private void dismiss(long id) {
            thread();
            for (Notifications.Notice notice : notifications.active()) if (notice.id() == id) notice.hovered(false);
            notifications.dismiss(id); if (frame != null) prepare();
        }

        /** Only the toast's neutral drawing/input contract is exposed; unrelated page commands fail explicitly. */
        private final class Context implements PageContext, ConfigHost {
            @Override public Painter painter() { return painter; }
            @Override public Theme theme() { return theme; }
            @Override public int accent() { return Theme.accent(preferences(), now); }
            @Override public ConfigHost host() { return this; }
            @Override public UiPreferences preferences() { return frame.preferences(); }
            @Override public String query() { return ""; }
            @Override public Navigation.Route route() { return new Navigation.Route(Navigation.Page.SETTINGS); }
            @Override public double mouseX() { return input() ? frame.viewport().localX(mouseX) : Double.NEGATIVE_INFINITY; }
            @Override public double mouseY() { return input() ? frame.viewport().localY(mouseY) : Double.NEGATIVE_INFINITY; }
            @Override public double width() { return frame.viewport().logicalBounds().w(); }
            @Override public double height() { return frame.viewport().logicalBounds().h(); }
            @Override public double time() { return now; }
            @Override public double delta() { return frame.deltaSeconds(); }
            @Override public boolean control() { return false; }
            @Override public boolean shift() { return false; }
            @Override public boolean alt() { return false; }
            @Override public int nativeKeyCode() { return 0; }
            @Override public boolean textFocused() { return false; }
            @Override public Rect shell() { return frame.viewport().logicalBounds(); }
            @Override public Rect content() { return shell(); }
            @Override public void hit(String id, Rect bounds, Runnable action, String tooltip) {
                if (!input()) return;
                Rect clipped = NotificationLayout.intersection(bounds, shell());
                if (clipped.w() > 0 && clipped.h() > 0) hits.add(new Hit(id, clipped, action));
            }
            @Override public void contentHit(String id, Rect bounds, Runnable action, String tooltip) { hit(id, bounds, action, tooltip); }
            @Override public double animate(String key, double target, double seconds, Motion.Easing easing) {
                return motion.value("notifications/" + key, target, seconds, easing, now);
            }
            @Override public void error(String action, Throwable failure) { errors.accept(action, failure); }
            private UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Global notifications do not own a config page"); }
            @Override public List<Category> categories() { throw unsupported(); }
            @Override public List<HudElement> huds() { throw unsupported(); }
            @Override public void persist() { throw unsupported(); }
            @Override public void close() { throw unsupported(); }
            @Override public String clipboard() { throw unsupported(); }
            @Override public void clipboard(String text) { throw unsupported(); }
            @Override public void content(Rect bounds) { throw unsupported(); }
            @Override public double scroll() { return 0; }
            @Override public void scroll(double value) { throw unsupported(); }
            @Override public void scrollRange(double maximum) { throw unsupported(); }
            @Override public void tooltip(String id, Rect bounds, String text) { throw unsupported(); }
            @Override public void startDrag(BiConsumer<Double, Double> update, Runnable finish) { throw unsupported(); }
            @Override public void navigate(Navigation.Route route) { throw unsupported(); }
            @Override public void toast(String message) { throw unsupported(); }
            @Override public void sound(String event) { throw unsupported(); }
            @Override public void edit(Option option, Object value) { throw unsupported(); }
            @Override public void beginEdit(String name) { throw unsupported(); }
            @Override public void commitEdit() { throw unsupported(); }
            @Override public void cancelEdit() { throw unsupported(); }
            @Override public void textField(String id, TextEdit edit, Rect bounds, String placeholder, Consumer<String> changed) { throw unsupported(); }
            @Override public void textDialog(String title, String hint, String initial, String action, Consumer<String> submit) { throw unsupported(); }
            @Override public void confirm(String title, String description, Runnable accepted) { throw unsupported(); }
            @Override public void overlay(UiPage overlay) { throw unsupported(); }
            @Override public void dismissOverlay() { throw unsupported(); }
        }
    }
}
