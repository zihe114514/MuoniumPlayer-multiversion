package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.*;
import java.util.function.Supplier;

/** Adds live host setting values to the same undo snapshot as host geometry. */
final class HudHostElement implements HudElement {
    final HudElement delegate;
    private final Supplier<List<ConfigHost.Group>> groups;

    HudHostElement(HudElement delegate, Supplier<List<ConfigHost.Group>> groups) {
        this.delegate = delegate;
        this.groups = groups;
    }

    public String id() {
        return delegate.id();
    }

    public String title() {
        return delegate.title();
    }

    public double x() {
        return delegate.x();
    }

    public double y() {
        return delegate.y();
    }

    public double width() {
        return delegate.width();
    }

    public double height() {
        return delegate.height();
    }

    public double scale() {
        return delegate.scale();
    }

    public boolean hidden() {
        return delegate.hidden();
    }

    public void position(double x, double y) {
        delegate.position(x, y);
    }

    public void size(double w, double h) {
        delegate.size(w, h);
    }

    public void scale(double s) {
        delegate.scale(s);
    }

    public void hidden(boolean h) {
        delegate.hidden(h);
    }

    public boolean aspectLocked() {
        return delegate.aspectLocked();
    }

    public void preview(double x, double y) {
        delegate.preview(x, y);
    }

    public ResizeAxes resizeAxes() {
        return delegate.resizeAxes();
    }

    public ResizeMode resizeMode() {
        return delegate.resizeMode();
    }

    public double minWidth() {
        return delegate.minWidth();
    }

    public double minHeight() {
        return delegate.minHeight();
    }

    public boolean supportsScale() {
        return delegate.supportsScale();
    }

    public String type() {
        return delegate.type();
    }

    public String groupId() {
        return delegate.groupId();
    }

    public String groupTitle() {
        return delegate.groupTitle();
    }

    public String icon() {
        return delegate.icon();
    }

    public boolean multipleInstancesAllowed() {
        return delegate.multipleInstancesAllowed();
    }

    public boolean deletable() {
        return delegate.deletable();
    }

    public boolean mergeable() {
        return delegate.mergeable();
    }

    public Anchor growthAnchor() {
        return delegate.growthAnchor();
    }

    public Rect bounds(double w, double h) {
        return delegate.bounds(w, h);
    }

    public void absolutePosition(double x, double y, double w, double h) {
        delegate.absolutePosition(x, y, w, h);
    }

    public void editorDragStarted() {
        delegate.editorDragStarted();
    }

    public void editorDragEnded() {
        delegate.editorDragEnded();
    }

    public boolean hasResetDefaults() {
        return delegate.hasResetDefaults()
                || groups.get().stream()
                        .flatMap(g -> g.options().stream())
                        .anyMatch(o -> o.reset() != null);
    }

    public void resetToDefaults(boolean positionOnly, double w, double h) {
        if (delegate.hasResetDefaults()) delegate.resetToDefaults(positionOnly, w, h);
        if (!positionOnly)
            for (var group : groups.get())
                for (var option : group.options()) if (option.reset() != null) option.reset().run();
    }

    public Map<String, String> captureProperties() {
        Map<String, String> values = new LinkedHashMap<>(delegate.captureProperties());
        for (var g : groups.get())
            for (var o : g.options())
                if (o.kind() != ConfigHost.Kind.ACTION) {
                    Object value = o.value();
                    if (value != null) values.put("option/" + o.id(), String.valueOf(value));
                }
        return Map.copyOf(values);
    }

    public void restoreProperties(Map<String, String> values) {
        Map<String, String> own = new HashMap<>(values);
        own.keySet().removeIf(k -> k.startsWith("option/"));
        delegate.restoreProperties(own);
        for (var g : groups.get())
            for (var o : g.options()) {
                String v = values.get("option/" + o.id());
                if (v == null || o.kind() == ConfigHost.Kind.ACTION) continue;
                Object value =
                        switch (o.kind()) {
                            case TOGGLE -> Boolean.parseBoolean(v);
                            case SLIDER -> Double.parseDouble(v);
                            case COLOR -> Integer.parseInt(v);
                            default -> v;
                        };
                if (!Objects.equals(o.value(), value)) o.write().accept(value);
            }
    }
}
