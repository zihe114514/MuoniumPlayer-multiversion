package com.muoniumplayer.configskija.model;

import com.muoniumplayer.configskija.ui.Geometry;
import com.muoniumplayer.configskija.ui.Geometry.Rect;

import java.util.Map;

/** Live host geometry is authoritative. Existing x/y accessors use normalized available travel. */
public interface HudElement {
    enum ResizeAxes {
        BOTH,
        WIDTH,
        NONE
    }

    enum ResizeMode {
        SCALE,
        CONTENT,
        SIZE
    }

    enum Anchor {
        AUTO(.5, .5),
        TOP_LEFT(0, 0),
        TOP(.5, 0),
        TOP_RIGHT(1, 0),
        LEFT(0, .5),
        CENTER(.5, .5),
        RIGHT(1, .5),
        BOTTOM_LEFT(0, 1),
        BOTTOM(.5, 1),
        BOTTOM_RIGHT(1, 1);
        public final double fx, fy;

        Anchor(double fx, double fy) {
            this.fx = fx;
            this.fy = fy;
        }

        public static Anchor at(int x, int y) {
            return values()[1 + y * 3 + x];
        }
    }

    String id();

    String title();

    double x();

    double y();

    double width();

    double height();

    double scale();

    boolean hidden();

    void position(double x, double y);

    void size(double width, double height);

    void scale(double scale);

    void hidden(boolean hidden);

    default boolean aspectLocked() {
        return false;
    }

    default void preview(double x, double y) {}

    default ResizeAxes resizeAxes() {
        return ResizeAxes.BOTH;
    }

    /** Host compat HUDs scale on the diagonal, as in the original studio. */
    default ResizeMode resizeMode() {
        return ResizeMode.SCALE;
    }

    /** Layout units per persisted position/anchor-offset unit; intrinsic host HUDs use layout units. */
    default double positionUnitScale() {
        return 1;
    }

    default double minWidth() {
        return 1;
    }

    default double minHeight() {
        return 1;
    }

    default boolean supportsScale() {
        return true;
    }

    default String type() {
        return "host";
    }

    default String groupId() {
        return "muonium";
    }

    default String groupTitle() {
        return "MuoniumPlayer";
    }

    default String icon() {
        return "hud";
    }

    default boolean multipleInstancesAllowed() {
        return false;
    }

    default boolean deletable() {
        return false;
    }

    default boolean mergeable() {
        return false;
    }

    default Anchor growthAnchor() {
        return Anchor.TOP_LEFT;
    }

    default Rect bounds(double viewportWidth, double viewportHeight) {
        return new Rect(
                x() * Math.max(0, viewportWidth - width()),
                y() * Math.max(0, viewportHeight - height()),
                width(),
                height());
    }

    default void absolutePosition(double x, double y, double viewportWidth, double viewportHeight) {
        position(
                Geometry.clamp(x / Math.max(1, viewportWidth - width()), 0, 1),
                Geometry.clamp(y / Math.max(1, viewportHeight - height()), 0, 1));
    }

    /** Detached scalar state only; implementations must include every editable content/style field. */
    default Map<String, String> captureProperties() {
        return Map.of();
    }

    default void restoreProperties(Map<String, String> properties) {
        if (!properties.isEmpty())
            throw new UnsupportedOperationException(
                    "HUD does not expose editable properties: " + id());
    }

    default HudElement duplicate(String newId) {
        throw new UnsupportedOperationException("Single instance HUD: " + id());
    }

    /** Reset must use actual factory defaults; opening the editor is not a default snapshot. */
    default boolean hasResetDefaults() {
        return false;
    }

    default void resetToDefaults(
            boolean positionOnly, double viewportWidth, double viewportHeight) {
        throw new UnsupportedOperationException("No HUD factory defaults: " + id());
    }

    /** Lifecycle is optional; capability is represented by implementation, never loader reflection. */
    default void editorDragStarted() {}

    default void editorDragEnded() {}
}
