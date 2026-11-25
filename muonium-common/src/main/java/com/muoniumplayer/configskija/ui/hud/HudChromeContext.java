package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.function.*;

/** Only editor chrome uses dp density. Canvas/model/runtime remain in untransformed layout pixels. */
final class HudChromeContext implements PageContext {
    static final class Focus {
        final HudMotion motion = new HudMotion();
        final HudTooltip tooltip = new HudTooltip();
        boolean overlayActive;
        TextEdit edit;
        Consumer<String> changed;
        Runnable committed, cancelled;

        void focus(TextEdit next, Consumer<String> changed) {
            if (edit != next) blur();
            this.edit = next;
            this.changed = changed;
            next.focused = true;
        }

        void transaction(Runnable committed, Runnable cancelled) {
            this.committed = committed;
            this.cancelled = cancelled;
        }

        void blur() {
            if (edit != null) edit.focused = false;
            edit = null;
            changed = null;
            Runnable action = committed;
            committed = cancelled = null;
            if (action != null) action.run();
        }

        void cancel() {
            if (edit != null) edit.focused = false;
            edit = null;
            changed = null;
            Runnable action = cancelled;
            committed = cancelled = null;
            if (action != null) action.run();
        }

        boolean focused() {
            return edit != null && edit.focused;
        }

        boolean key(
                PageContext c, ConfigView.Key key, String typed, boolean control, boolean shift) {
            if (!focused()) return false;
            if (key == ConfigView.Key.ESCAPE) {
                cancel();
                return true;
            }
            if (key == ConfigView.Key.ENTER) {
                blur();
                return true;
            }
            String before = edit.text();
            HudWidgets.editKey(c, edit, key, typed, control, shift);
            if (!before.equals(edit.text()) && changed != null) changed.accept(edit.text());
            return true;
        }
    }

    private final PageContext base;
    final double density;
    final Focus focus;
    private final Painter painter;
    private final boolean interactive;

    HudChromeContext(PageContext base, Focus focus) {
        this(base, focus, true);
    }

    private HudChromeContext(PageContext base, Focus focus, boolean interactive) {
        this.base = base;
        this.focus = focus;
        this.interactive = interactive;
        density = Math.max(.01, base.host().uiDensity());
        painter = new ScaledPainter(base.painter(), density, base.theme());
    }

    HudChromeContext silent() {
        return new HudChromeContext(base, focus, false);
    }

    void measureHudPreview(HudRuntime runtime, HudElement element) {
        runtime.measurePreview(base.painter(), element, HudStudio.editorFrame(base));
    }

    void hudPreview(HudRuntime runtime, HudElement element, Rect card) {
        runtime.preview(base.painter(), element, screen(card), HudStudio.editorFrame(base));
    }

    void textPreview(HudRuntime runtime, HudNode node, Rect card) {
        runtime.textPreview(base.painter(), node, screen(card), base.time());
    }

    Rect screen(Rect r) {
        return new Rect(r.x() * density, r.y() * density, r.w() * density, r.h() * density);
    }

    Rect local(Rect r) {
        return new Rect(r.x() / density, r.y() / density, r.w() / density, r.h() / density);
    }

    public Painter painter() {
        return painter;
    }

    public Theme theme() {
        return base.theme();
    }

    public int accent() {
        return base.accent();
    }

    public ConfigHost host() {
        return base.host();
    }

    public UiPreferences preferences() {
        return base.preferences();
    }

    public String query() {
        return base.query();
    }

    public Navigation.Route route() {
        return base.route();
    }

    public double mouseX() {
        return base.mouseX() / density;
    }

    public double mouseY() {
        return base.mouseY() / density;
    }

    public double width() {
        return base.width() / density;
    }

    public double height() {
        return base.height() / density;
    }

    public double time() {
        return base.time();
    }

    public double delta() {
        return base.delta();
    }

    public boolean control() {
        return base.control();
    }

    public boolean shift() {
        return base.shift();
    }

    public boolean alt() {
        return base.alt();
    }

    public int nativeKeyCode() {
        return base.nativeKeyCode();
    }

    public boolean textFocused() {
        return focus.focused() || base.textFocused();
    }

    public Rect content() {
        return local(base.content());
    }

    public void content(Rect bounds) {
        base.content(screen(bounds));
    }

    public double scroll() {
        return base.scroll() / density;
    }

    public void scroll(double value) {
        base.scroll(value * density);
    }

    public void scrollRange(double maximum) {
        base.scrollRange(maximum * density);
    }

    public void hit(String id, Rect r, Runnable action, String tip) {
        if (interactive) {
            tooltip(id, r, tip);
            base.hit(
                    id,
                    screen(r),
                    () -> {
                        focus.blur();
                        focus.tooltip.clear();
                        action.run();
                    },
                    "");
        }
    }

    public void contentHit(String id, Rect r, Runnable action, String tip) {
        if (interactive) {
            tooltip(id, r, tip);
            base.contentHit(id, screen(r), action, "");
        }
    }

    public void tooltip(String id, Rect r, String text) {
        tooltip(id, r, text, Double.POSITIVE_INFINITY);
    }

    void tooltip(String id, Rect r, String text, double maxWidth) {
        if (interactive) focus.tooltip.offer(this, id, r, text, maxWidth);
    }

    public void startDrag(BiConsumer<Double, Double> update, Runnable finish) {
        base.startDrag((x, y) -> update.accept(x / density, y / density), finish);
    }

    public void navigate(Navigation.Route route) {
        base.navigate(route);
    }

    public void toast(String text) {
        base.toast(text);
    }

    public void sound(String event) {
        base.sound(event);
    }

    public void edit(ConfigHost.Option option, Object value) {
        base.edit(option, value);
    }

    public void beginEdit(String name) {
        base.beginEdit(name);
    }

    public void commitEdit() {
        base.commitEdit();
    }

    public void cancelEdit() {
        base.cancelEdit();
    }

    public void textDialog(
            String title, String hint, String initial, String action, Consumer<String> submit) {
        base.textDialog(title, hint, initial, action, submit);
    }

    public void confirm(String title, String description, Runnable action) {
        base.confirm(title, description, action);
    }

    public void overlay(UiPage overlay) {
        focus.blur();
        focus.tooltip.clear();
        focus.overlayActive = true;
        base.overlay(new Overlay(overlay, focus));
    }

    public void dismissOverlay() {
        base.dismissOverlay();
    }

    public void close() {
        base.close();
    }

    public double animate(String key, double target, double seconds, Motion.Easing easing) {
        if (key.startsWith("hud/switch")
                || key.startsWith("hud/field/")
                || key.startsWith("hud/icon-alpha/")
                || key.startsWith("hud/tour-button/"))
            return focus.motion.animate(
                    key, target, time(), 1500, 1, key.startsWith("hud/switch-thumb") ? .1 : .01);
        if (key.equals("hud/panel-enter")
                || key.equals("hud/library-open")
                || key.equals("hud/library-chrome"))
            return focus.motion.animate(key, target, time(), 400, 1, .01);
        return focus.motion.tween(key, target, time(), seconds, easing);
    }

    HudMotion motion() {
        return focus.motion;
    }

    public Rect shell() {
        return local(base.shell());
    }

    public void textField(
            String id, TextEdit edit, Rect r, String placeholder, Consumer<String> changed) {
        input(id, edit, r, placeholder, changed, true, 12);
    }

    void input(
            String id,
            TextEdit edit,
            Rect r,
            String placeholder,
            Consumer<String> changed,
            boolean decorate,
            double size) {
        if (interactive && edit.focused && !focus.focused()) focus.focus(edit, changed);
        Painter p = painter;
        boolean focused = focus.edit == edit && edit.focused;
        double t = animate("hud/field/" + id, focused ? 1 : 0, .15, Motion.Easing.FAST_OUT_SLOW_IN);
        if (decorate) {
            p.rect(r, theme().radius(5), theme().component());
            p.outline(
                    r,
                    theme().buttonRadius(),
                    1,
                    focus.motion.color(
                            "hud/field-border/" + id,
                            Theme.alpha(theme().text(), focused ? .20 : .10),
                            time()));
        }
        String text = edit.text();
        double start = p.measure(text.substring(0, edit.cursor()), size, false),
                offset = Math.max(0, start - r.w() + 28);
        p.save();
        try {
            p.clip(new Rect(r.x() + 6, r.y(), Math.max(0, r.w() - 12), r.h()), 0);
            String shown = text.isEmpty() && !focused ? placeholder : text;
            Painter.TextBounds bounds = p.textBounds(shown, size, false);
            double x = r.x() + 12 - offset,
                    y = p.textTopForCenter(shown, r.y() + r.h() / 2, size, false),
                    inkTop = y + bounds.top(), inkBottom = y + bounds.bottom();
            if (focused && edit.selected()) {
                double a = p.measure(text.substring(0, edit.selectionStart()), size, false),
                        b = p.measure(text.substring(0, edit.selectionEnd()), size, false);
                p.rect(
                        new Rect(x + a, inkTop, b - a, bounds.height()),
                        1,
                        Theme.alpha(accent(), .35));
            }
            p.text(
                    shown,
                    x,
                    y,
                    size,
                    false,
                    focus.motion.color(
                            "hud/field-ink/" + id,
                            Theme.alpha(theme().text(), focused ? .70 : .50),
                            time()));
            if (focused && (time() % 1) < .55)
                p.line(x + start, inkTop, x + start, inkBottom, 1, theme().text());
        } finally {
            p.restore();
        }
        hit(
                id,
                r,
                () -> {
                    focus.focus(edit, changed);
                    edit.click(
                            mouseX() - r.x() - 12 + offset,
                            s -> p.measure(s, size, false),
                            shift());
                },
                "");
    }

    private record Overlay(UiPage page, Focus focus)
            implements UiPage, com.muoniumplayer.configskija.ui.pages.AppearancePages.RawKeyInput {
        public boolean rawKey(PageContext c, String token, boolean pressed) {
            return page
                            instanceof
                            com.muoniumplayer.configskija.ui.pages.AppearancePages.RawKeyInput raw
                    && raw.rawKey(context(c), token, pressed);
        }

        public boolean rawMouse(PageContext c, int button, boolean pressed) {
            return page
                            instanceof
                            com.muoniumplayer.configskija.ui.pages.AppearancePages.RawKeyInput raw
                    && raw.rawMouse(context(c), button, pressed);
        }

        public boolean recording() {
            return page
                            instanceof
                            com.muoniumplayer.configskija.ui.pages.AppearancePages.RawKeyInput raw
                    && raw.recording();
        }

        private HudChromeContext context(PageContext c) {
            return new HudChromeContext(c, focus);
        }

        public void render(PageContext c) {
            HudChromeContext cc = context(c);
            focus.tooltip.begin();
            page.render(cc);
            focus.tooltip.render(cc);
        }

        public boolean pointerDown(PageContext c, int button) {
            return page.pointerDown(context(c), button);
        }

        public void pointerUp(PageContext c, int button) {
            page.pointerUp(context(c), button);
        }

        public boolean wheel(PageContext c, double delta) {
            return page.wheel(context(c), delta);
        }

        public boolean key(
                PageContext c,
                ConfigView.Key key,
                String typed,
                boolean ctrl,
                boolean shift,
                boolean alt) {
            return page.key(context(c), key, typed, ctrl, shift, alt);
        }

        public void leave(PageContext c) {
            page.leave(context(c));
            focus.blur();
            focus.tooltip.clear();
            focus.overlayActive = false;
        }
    }

    private record ScaledPainter(Painter base, double density, Theme theme) implements Painter {
        private Rect rect(Rect r) {
            return new Rect(r.x() * density, r.y() * density, r.w() * density, r.h() * density);
        }

        public void save() {
            base.save();
        }

        public void restore() {
            base.restore();
        }

        public void layer(Rect r, double alpha) {
            base.layer(rect(r), alpha);
        }

        public long textMetricsRevision() {
            return base.textMetricsRevision();
        }

        public void shadow(
                Rect r, double radius, double sigma, double spread, double x, double y, int color) {
            base.shadow(
                    rect(r),
                    radius * density,
                    sigma * density,
                    spread * density,
                    x * density,
                    y * density,
                    color);
        }

        public void image(byte[] bytes, Rect r, boolean nearest) {
            base.image(bytes, rect(r), nearest);
        }

        public void translate(double x, double y) {
            base.translate(x * density, y * density);
        }

        public void scale(double x, double y) {
            base.scale(x, y);
        }

        public void clip(Rect r, double radius) {
            base.clip(rect(r), radius * density);
        }

        public void rect(Rect r, double radius, int color) {
            base.rect(rect(r), radius * density, color);
        }

        public void outline(Rect r, double radius, double width, int color) {
            base.outline(rect(r), radius * density, width * density, color);
        }

        public void line(double x, double y, double tx, double ty, double width, int color) {
            base.line(x * density, y * density, tx * density, ty * density, width * density, color);
        }

        public void text(String text, double x, double y, double size, boolean bold, int color) {
            base.text(text, x * density, y * density, size * density, bold, color);
        }

        public double measure(String text, double size, boolean bold) {
            return base.measure(text, size * density, bold) / density;
        }

        public Painter.TextBounds textBounds(String text, double size, boolean bold) {
            Painter.TextBounds bounds = base.textBounds(text, size * density, bold);
            return new Painter.TextBounds(bounds.top() / density, bounds.bottom() / density);
        }

        public void icon(String id, double x, double y, double size, int color) {
            base.icon(
                    id.contains("/") ? id : theme.iconPath(id),
                    x * density,
                    y * density,
                    size * density,
                    color);
        }

        public void roundedPolygon(double[][] loops, double radius, int color) {
            double[][] scaled = new double[loops.length][];
            for (int i = 0; i < loops.length; i++) {
                scaled[i] = loops[i].clone();
                for (int j = 0; j < scaled[i].length; j++) scaled[i][j] *= density;
            }
            base.roundedPolygon(scaled, radius * density, color);
        }

        public void polygon(double[] xy, double radius, int color) {
            double[] scaled = xy.clone();
            for (int i = 0; i < scaled.length; i++) scaled[i] *= density;
            base.polygon(scaled, radius * density, color);
        }

        public void gradientOutline(Rect r, double radius, double width, int top, int bottom) {
            base.gradientOutline(rect(r), radius * density, width * density, top, bottom);
        }

        public void vignette(Rect r, double cx, double cy, double radius, int color) {
            base.vignette(rect(r), cx * density, cy * density, radius * density, color);
        }

        public void glow(Rect r, double x, double y, double radius, int color, double alpha) {
            base.glow(rect(r), x * density, y * density, radius * density, color, alpha);
        }

        public void gradient(Rect r, int a, int b, int c, int d) {
            base.gradient(rect(r), a, b, c, d);
        }
    }
}
