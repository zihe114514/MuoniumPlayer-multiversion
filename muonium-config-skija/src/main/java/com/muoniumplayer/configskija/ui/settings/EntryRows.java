package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;
import java.util.function.*;

/** Global search adapter. Every setting pixel and interaction is rendered by the existing SettingsPage. */
public final class EntryRows {
    private static final class OwnerState { final SettingsPage page; ScopedContext context; boolean seen; OwnerState(SettingsSchema schema) { page = new SettingsPage(false, schema); } }
    private final Function<SearchDocuments.Owner, SettingsSchema> schemas;
    private final Map<String, OwnerState> owners = new LinkedHashMap<>();
    private final TextInputController fallback = new TextInputController();
    private Rect viewport; private PageContext frameContext; private boolean open;
    public EntryRows() { schemas = null; }
    public EntryRows(SettingsSchema schema) { this(owner -> schema); }
    public EntryRows(Function<SearchDocuments.Owner, SettingsSchema> schemas) { this.schemas = Objects.requireNonNull(schemas); }
    public void beginFrame(PageContext context, Rect viewport) {
        if (open) throw new IllegalStateException("EntryRows frame already active"); open = true; frameContext = context; this.viewport = viewport;
        owners.values().forEach(state -> state.seen = false); if (!(context instanceof TextInputController.TextInput)) fallback.beginFrame(context);
    }
    /** offered.x/y/width are the row placement. Height is measured from the original controls; gap is external. */
    public double render(PageContext context, SearchDocuments.NarrowedRow row, Rect offered) {
        if (!open || context != frameContext) throw new IllegalStateException("Call EntryRows.beginFrame first");
        SettingsSchema schema = schemas != null ? Objects.requireNonNull(schemas.apply(row.row().owner())) : context.host() instanceof SettingsSchema.Provider provider ? provider.settingsSchema() : SettingsSchema.DEFAULT;
        String ownerId = row.row().owner().id(); OwnerState state = owners.computeIfAbsent(ownerId, ignored -> new OwnerState(schema));
        if (!state.seen) { state.context = new ScopedContext(context, "settings.owner." + ownerId.length() + ":" + ownerId + ":", viewport); state.page.beginRows(state.context, schema, viewport); state.seen = true; }
        context.painter().save(); context.painter().clip(viewport, 0);
        try { return state.page.renderEntry(state.context, row, offered); }
        finally { context.painter().restore(); }
    }
    public void endFrame(PageContext context) {
        if (!open || context != frameContext) throw new IllegalStateException("EntryRows frame is not active");
        for (OwnerState state : owners.values()) if (state.seen) state.page.endRows(); else if (state.context != null) state.page.suspendRows(state.context);
        if (!(context instanceof TextInputController.TextInput)) fallback.endFrame(context); open = false;
    }
    public boolean pointerDown(PageContext ctx, int button) { for (OwnerState state : owners.values()) if (state.seen && state.page.pointerDown(state.context, button)) return true; return false; }
    public void pointerUp(PageContext ctx, int button) { for (OwnerState state : owners.values()) if (state.seen) state.page.pointerUp(state.context, button); }
    public boolean pointerMove(PageContext ctx) { return input(ctx).pointerMove(ctx); }
    public boolean wheel(PageContext ctx, double delta) { for (OwnerState state : owners.values()) if (state.seen && state.page.wheel(state.context, delta)) return true; return false; }
    public boolean key(PageContext ctx, ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) { for (OwnerState state : owners.values()) if (state.seen && state.page.key(state.context, key, typed, control, shift, alt)) return true; return false; }
    public boolean recording() { return owners.values().stream().anyMatch(state -> state.seen && state.page.recording()); }
    public boolean rawKey(PageContext ctx, int code, boolean pressed) { for (OwnerState state : owners.values()) if (state.seen && state.page.rawKey(state.context, code, pressed)) return true; return false; }
    public void leave(PageContext ctx) { input(ctx).blur(ctx); for (OwnerState state : owners.values()) if (state.context != null) state.page.leave(state.context); open = false; }
    private TextInputController input(PageContext ctx) { return ctx instanceof TextInputController.TextInput input ? input.textInputController() : fallback; }

    /** Namespaces transient UI identity without cloning Option or changing its actual setter/history identity. */
    private final class ScopedContext implements TextInputController.TextInput {
        private PageContext delegate; private final String prefix; private Rect content;
        ScopedContext(PageContext delegate, String prefix, Rect content) { this.delegate = delegate; this.prefix = prefix; this.content = content; }
        public TextInputController textInputController() { return input(delegate); }
        public void bareTextField(String id, TextEdit edit, Rect bounds, TextInputController.Style style, Consumer<String> changed, Runnable blur) { textInputController().field(this, prefix + id, edit, bounds, style, changed, blur); }
        public void bareTextField(String id, TextEdit edit, Rect bounds, TextInputController.Style style, Consumer<String> changed, Runnable blur, Runnable submit) { textInputController().field(this, prefix + id, edit, bounds, style, changed, blur, submit); }
        public Painter painter() { return delegate.painter(); } public Theme theme() { return delegate.theme(); } public int accent() { return delegate.accent(); }
        public ConfigHost host() { return delegate.host(); } public UiPreferences preferences() { return delegate.preferences(); } public String query() { return delegate.query(); } public Navigation.Route route() { return delegate.route(); }
        public double mouseX() { return delegate.mouseX(); } public double mouseY() { return delegate.mouseY(); } public double width() { return delegate.width(); } public double height() { return delegate.height(); } public double time() { return delegate.time(); } public double delta() { return delegate.delta(); }
        public boolean control() { return delegate.control(); } public boolean shift() { return delegate.shift(); } public boolean alt() { return delegate.alt(); } public int nativeKeyCode() { return delegate.nativeKeyCode(); } public boolean textFocused() { return input(delegate).focused() || delegate.textFocused(); }
        public Rect content() { return content; } public void content(Rect value) { content = value; } public double scroll() { return delegate.scroll(); } public void scroll(double value) { delegate.scroll(value); } public void scrollRange(double value) { delegate.scrollRange(value); }
        public void hit(String id, Rect bounds, Runnable action, String tooltip) { delegate.hit(id.startsWith(prefix) ? id : prefix + id, bounds, action, tooltip); }
        public void contentHit(String id, Rect bounds, Runnable action, String tooltip) { delegate.hit(prefix + id, SettingsPage.intersection(viewport, bounds), action, tooltip); }
        public void tooltip(String id, Rect bounds, String text) { delegate.tooltip(id.startsWith(prefix) ? id : prefix + id, bounds, text); }
        public void startDrag(BiConsumer<Double, Double> update, Runnable finish) { delegate.startDrag(update, finish); }
        public void navigate(Navigation.Route route) { delegate.navigate(route); } public void toast(String message) { delegate.toast(message); } public void sound(String event) { delegate.sound(event); }
        public void edit(ConfigHost.Option option, Object value) { delegate.edit(option, value); } public void beginEdit(String name) { delegate.beginEdit(name); } public void beginEdit(ConfigHost.Option option) { delegate.beginEdit(option); } public void commitEdit() { delegate.commitEdit(); } public void cancelEdit() { delegate.cancelEdit(); }
        public void textField(String id, TextEdit edit, Rect bounds, String placeholder, Consumer<String> changed) { delegate.textField(prefix + id, edit, bounds, placeholder, changed); }
        public void textDialog(String title, String hint, String initial, String action, Consumer<String> submit) { delegate.textDialog(title, hint, initial, action, submit); }
        public void confirm(String title, String description, Runnable accepted) { delegate.confirm(title, description, accepted); }
        public void overlay(UiPage overlay) { delegate.overlay(new UiPage() {
            private void update(PageContext current) { ScopedContext.this.delegate = current; }
            public void render(PageContext current) { update(current); overlay.render(ScopedContext.this); }
            public boolean recording() { return overlay.recording(); }
            public boolean pointerDown(PageContext current, int button) { update(current); return overlay.pointerDown(ScopedContext.this, button); }
            public void pointerUp(PageContext current, int button) { update(current); overlay.pointerUp(ScopedContext.this, button); }
            public boolean wheel(PageContext current, double delta) { update(current); return overlay.wheel(ScopedContext.this, delta); }
            public boolean key(PageContext current, ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) { update(current); return overlay.key(ScopedContext.this, key, typed, control, shift, alt); }
            public void leave(PageContext current) { update(current); overlay.leave(ScopedContext.this); }
        }); }
        public void dismissOverlay() { delegate.dismissOverlay(); } public void close() { delegate.close(); }
        public double animate(String key, double target, double seconds, Motion.Easing easing) { return delegate.animate(prefix + key, target, seconds, easing); }
        public Rect shell() { return delegate.shell(); }
    }
}
