package com.muoniumplayer.configskija.ui.notifications;

import com.muoniumplayer.configskija.model.Notifications;
import com.muoniumplayer.configskija.model.Notifications.Action;
import com.muoniumplayer.configskija.model.Notifications.Notice;
import com.muoniumplayer.configskija.ui.ConfigView;
import com.muoniumplayer.configskija.ui.Geometry;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.Motion;
import com.muoniumplayer.configskija.ui.PageContext;
import com.muoniumplayer.configskija.ui.Painter;
import com.muoniumplayer.configskija.ui.Theme;
import com.muoniumplayer.configskija.ui.UiPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** The source 368 dp notification history popup. It shares the model used for live toasts. */
public final class NotificationsCenter implements UiPage {
    private final Notifications notifications;
    private final Rect bell;
    private final String clearLabel;
    private final LongSupplier clock;
    private final NotificationScroll wheel = new NotificationScroll();
    private final String motionKey = "notifications." + System.identityHashCode(this) + ".";
    private Rect bounds, listBounds;
    private double scroll, maximumScroll;
    private boolean closed;

    private record Row(Notice notice, String time, List<String> title, List<String> message,
                       double headerHeight, double height, Double progress) {}

    public NotificationsCenter(Notifications notifications, Rect bellBounds) {
        this(notifications, bellBounds, "Clear all notifications");
    }
    /** Only the source footer is localized. The source title, empty state and times are English. */
    public NotificationsCenter(Notifications notifications, Rect bellBounds, String clearLabel) {
        this(notifications, bellBounds, clearLabel, System::currentTimeMillis);
    }
    public NotificationsCenter(Notifications notifications, Rect bellBounds, String clearLabel, LongSupplier clock) {
        this.notifications = Objects.requireNonNull(notifications); bell = Objects.requireNonNull(bellBounds);
        this.clearLabel = Objects.requireNonNull(clearLabel); this.clock = Objects.requireNonNull(clock);
    }
    public boolean closed() { return closed; }
    public Rect bounds() { return bounds; }
    public Rect listBounds() { return listBounds; }
    public double scrollOffset() { return scroll; }
    public double maximumScroll() { return maximumScroll; }

    @Override public void render(PageContext ctx) {
        if (closed) return;
        Painter painter = ctx.painter(); Theme theme = ctx.theme();
        List<Notice> history = notifications.history(); List<Row> rows = new ArrayList<>();
        double total = 0; long now = clock.getAsLong();
        for (Notice notice : history) {
            String time = NotificationLayout.relativeTime(notice.createdAt(), now);
            double titleWidth = 295 - painter.measure(time, 10, false) - 8 - (notice.read() ? 0 : 12);
            List<String> title = NotificationLayout.wrap(painter, notice.title(), titleWidth, 14, true);
            List<String> message = NotificationLayout.wrap(painter, notice.message(), 295, 12, false);
            double headerHeight = Math.max(20, title.size() * 20);
            Double progress = notice.progressOrNull();
            double column = headerHeight + (message.isEmpty() ? 0 : 2 + message.size() * 21)
                + (progress == null ? 0 : 16) + (notice.actions().isEmpty() ? 0 : 40);
            double height = Math.max(32, column);
            rows.add(new Row(notice, time, title, message, headerHeight, height, progress));
            total += height;
        }
        if (rows.size() > 1) total += (rows.size() - 1) * 33;
        double listHeight = history.isEmpty() ? 34 : Math.min(NotificationLayout.LIST_MAX_HEIGHT, total);
        maximumScroll = Math.max(0, total - listHeight);
        double step = wheel.advance(Math.max(0, ctx.delta()));
        double moved = Geometry.clamp(scroll + step, 0, maximumScroll);
        if (Math.abs(moved - scroll - step) > .5) wheel.reset();
        scroll = moved;
        // 16 vertical padding, 28 title, three 16 dp gaps, 1 separator, 18 footer.
        bounds = NotificationLayout.panel(bell, 32 + 28 + 48 + listHeight + 1 + 18, ctx.shell());
        int panel = Theme.alpha(theme.popup(), .8);
        painter.save();
        try {
            painter.clip(bounds, theme.popupRadius());
            painter.rect(bounds, theme.popupRadius(), panel);
            painter.outline(bounds, theme.popupRadius(), 1, theme.border());
            NotificationLayout.text(painter, "Notifications", bounds.x() + 14, bounds.y() + 21, 18, 500, theme.text());
            listBounds = new Rect(bounds.x() + 14, bounds.y() + 60, 340, listHeight);
            if (history.isEmpty()) {
                painter.text("You're all caught up.", listBounds.x(), listBounds.y() + 11, 12, false, theme.secondary());
            } else {
                painter.save();
                try {
                    painter.clip(listBounds, 0);
                    double y = listBounds.y() - scroll;
                    for (int i = 0; i < rows.size(); i++) {
                        Row row = rows.get(i);
                        if (y + row.height() > listBounds.y() && y < listBounds.bottom()) drawRow(ctx, row, y);
                        y += row.height();
                        if (i != rows.size() - 1) {
                            y += 16;
                            painter.rect(new Rect(listBounds.x(), y, 340, 1), 0, theme.border());
                            y += 17;
                        }
                    }
                    double top = ctx.animate(motionKey + "top", scroll > 0 ? 1 : 0, .35, Motion.Easing.SPRING);
                    double bottom = ctx.animate(motionKey + "bottom", scroll < maximumScroll ? 1 : 0, .35, Motion.Easing.SPRING);
                    double fade = Math.min(32, listHeight / 2);
                    int transparent = Theme.alpha(panel, 0);
                    painter.layer(listBounds, top);
                    try { painter.gradient(new Rect(listBounds.x(), listBounds.y(), 340, fade), panel, panel, transparent, transparent); }
                    finally { painter.restore(); }
                    painter.layer(listBounds, bottom);
                    try { painter.gradient(new Rect(listBounds.x(), listBounds.bottom() - fade, 340, fade), transparent, transparent, panel, panel); }
                    finally { painter.restore(); }
                } finally { painter.restore(); }
            }
            double separator = listBounds.bottom() + 16;
            painter.rect(new Rect(listBounds.x(), separator, 340, 1), 0, theme.border());
            drawFooter(ctx, new Rect(listBounds.x(), separator + 17, 340, 18));
        } finally { painter.restore(); }
    }
    private void drawRow(PageContext ctx, Row row, double y) {
        Painter painter = ctx.painter(); Theme theme = ctx.theme(); Notice notice = row.notice();
        int accent = switch (notice.type()) {
            case INFO -> theme.text(); case PROGRESS -> ctx.accent(); default -> notice.type().accent();
        };
        double x = listBounds.x();
        painter.icon(theme.iconPath(notice.type().icon()), x + 4, y + 4, 24, accent);
        double textX = x + 45;
        NotificationLayout.lines(painter, row.title(), textX, y, 14, 20, true, accent);
        double dotWidth = notice.read() ? 0 : 12;
        double timeWidth = painter.measure(row.time(), 10, false);
        painter.text(row.time(), listBounds.right() - dotWidth - timeWidth, y + (row.headerHeight() - 10) / 2, 10, false, theme.secondary());
        if (!notice.read()) painter.rect(new Rect(listBounds.right() - 6, y + (row.headerHeight() - 6) / 2, 6, 6), theme.circleRadius(6), ctx.accent());
        double textY = y + row.headerHeight();
        if (!row.message().isEmpty()) {
            textY += 2;
            NotificationLayout.lines(painter, row.message(), textX, textY, 12, 21, false, theme.secondary());
            textY += row.message().size() * 21;
        }
        if (row.progress() != null) {
            textY += 10;
            Rect progress = new Rect(textX, textY, 295, 6);
            painter.rect(progress, theme.circleRadius(6), Theme.alpha(ctx.accent(), 128 / 255.0));
            if (row.progress() > 0) painter.rect(new Rect(progress.x(), progress.y(), progress.w() * row.progress(), 6), theme.circleRadius(6), ctx.accent());
            textY += 6;
        }
        if (!notice.actions().isEmpty()) {
            textY += 10; double actionX = textX;
            for (int index = 0; index < notice.actions().size(); index++) {
                Action action = notice.actions().get(index);
                double width = NotificationLayout.measure(painter, action.label(), 12, 500) + 24;
                Rect actionBounds = new Rect(actionX, textY, width, 30);
                String id = "notification.action." + notice.id() + "." + index;
                int base = action.primary() ? ctx.accent() : 0x14FFFFFF;
                Rect hit = NotificationLayout.intersection(actionBounds, listBounds);
                double hover = ctx.animate(motionKey + id, hit.contains(ctx.mouseX(), ctx.mouseY()) ? 1 : 0, .35, Motion.Easing.SPRING);
                painter.rect(actionBounds, theme.buttonRadius(), Theme.mixOklab(base, overlayWhite(base, .12), hover));
                NotificationLayout.text(painter, action.label(), actionX + 12, textY + 9, 12, 500, 0xE6FFFFFF);
                if (hit.w() > 0 && hit.h() > 0) ctx.hit(id, hit, () -> invokeAction(ctx, notice, action), "");
                actionX += width + 16;
            }
        }
    }
    static int overlayWhite(int base, double alpha) {
        double ba = (base >>> 24) / 255.0, a = alpha + ba * (1 - alpha);
        int result = (int)Math.round(a * 255) << 24;
        for (int shift : new int[]{16, 8, 0}) {
            int channel = (int)Math.round((255 * alpha + ((base >>> shift) & 255) * ba * (1 - alpha)) / a);
            result |= channel << shift;
        }
        return result;
    }
    private void invokeAction(PageContext ctx, Notice notice, Action action) {
        ctx.sound("click");
        try { action.onClick().run(); notifications.remove(notice); }
        catch (RuntimeException failure) { ctx.host().error("Notification action: " + action.label(), failure); }
    }
    private void drawFooter(PageContext ctx, Rect footer) {
        Painter painter = ctx.painter(); Theme theme = ctx.theme();
        double width = 30 + NotificationLayout.measure(painter, clearLabel, 12, 500);
        Rect button = new Rect(footer.x(), footer.y(), Math.min(footer.w(), width), 18);
        double hover = ctx.animate(motionKey + "clear", button.contains(ctx.mouseX(), ctx.mouseY()) ? 1 : 0, .35, Motion.Easing.SPRING);
        int color = Theme.mixOklab(Theme.multiplyAlpha(theme.text(), .85), theme.text(), hover);
        painter.icon(theme.iconPath("trash"), footer.x(), footer.y(), 18, color);
        NotificationLayout.text(painter, clearLabel, footer.x() + 30, footer.y() + 3, 12, 500, color);
        ctx.hit("notifications.clear", button, () -> { ctx.sound("click"); notifications.clearHistory(); scroll = 0; wheel.reset(); }, "");
    }
    private void dismiss(PageContext ctx) { if (!closed) { leave(ctx); ctx.dismissOverlay(); } }
    @Override public boolean pointerDown(PageContext ctx, int button) {
        if (bounds == null || !bounds.contains(ctx.mouseX(), ctx.mouseY())) { dismiss(ctx); return true; }
        return button != 0;
    }
    @Override public boolean wheel(PageContext ctx, double delta) {
        scrollWheel(ctx, delta, 150, false);
        return true;
    }
    /** Raw wheel units: Windows/Linux wheel 150 dp, macOS wheel 50; precise Windows 150, others 10. */
    public void scrollWheel(PageContext ctx, double delta, double unitDp, boolean precise) {
        if (listBounds == null || !listBounds.contains(ctx.mouseX(), ctx.mouseY())) return;
        if (precise) { wheel.reset(); scroll = Geometry.clamp(scroll - delta * unitDp, 0, maximumScroll); }
        else wheel.push(-delta * unitDp);
    }
    @Override public boolean key(PageContext ctx, ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) {
        if (key == ConfigView.Key.ESCAPE) { dismiss(ctx); return true; }
        return false;
    }
    /** Mark on dismissal, including shell close. Opening the center never consumes unread state. */
    @Override public void leave(PageContext ctx) {
        if (!closed) { closed = true; notifications.markAllRead(); wheel.reset(); }
    }
}
