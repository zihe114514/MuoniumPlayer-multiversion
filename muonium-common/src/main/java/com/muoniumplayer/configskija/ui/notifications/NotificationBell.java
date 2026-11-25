package com.muoniumplayer.configskija.ui.notifications;

import com.muoniumplayer.configskija.model.Notifications;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.PageContext;
import com.muoniumplayer.configskija.ui.Painter;
import com.muoniumplayer.configskija.ui.Theme;
import java.util.Objects;

/** Optional sidebar helper; a shell can draw its own account area and call toggle instead. */
public final class NotificationBell {
    private final Notifications notifications;
    private final String clearLabel;
    private NotificationsCenter center;
    public NotificationBell(Notifications notifications) { this(notifications, "Clear all notifications"); }
    public NotificationBell(Notifications notifications, String clearLabel) {
        this.notifications = Objects.requireNonNull(notifications); this.clearLabel = Objects.requireNonNull(clearLabel);
    }
    public void render(PageContext ctx, Rect bounds) {
        Painter painter = ctx.painter(); Theme theme = ctx.theme();
        painter.icon(theme.iconPath("bell"), bounds.x(), bounds.y(), 18, theme.text());
        if (notifications.unreadCount() > 0) {
            Rect glow = new Rect(bounds.x() + 12, bounds.y() - 2, 8, 8);
            painter.shadow(glow, theme.circleRadius(8), 7, 0, 0, 0, ctx.accent());
            painter.rect(new Rect(glow.x() + 1, glow.y() + 1, 6, 6), theme.circleRadius(6), ctx.accent());
        }
        ctx.hit("notifications.bell", bounds, () -> toggle(ctx, bounds), "");
    }
    public void toggle(PageContext ctx, Rect bellBounds) {
        ctx.sound("click");
        if (center != null && !center.closed()) { ctx.dismissOverlay(); center = null; }
        else { center = new NotificationsCenter(notifications, bellBounds, clearLabel); ctx.overlay(center); }
    }
    public NotificationsCenter center() { return center; }
}
