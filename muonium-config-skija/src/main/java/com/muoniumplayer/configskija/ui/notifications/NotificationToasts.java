/*
 * This file is part of OneConfig.
 * OneConfig - Next Generation Config Library for Minecraft: Java Edition
 * Copyright (C) 2021~2024 Polyfrost.
 *   <https://polyfrost.org> <https://github.com/Polyfrost/>
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *   OneConfig is licensed under the terms of version 3 of the GNU Lesser
 * General Public License as published by the Free Software Foundation, AND
 * under the Additional Terms Applicable to OneConfig, as published by Polyfrost,
 * either version 1.0 of the Additional Terms, or (at your option) any later
 * version.
 *
 *   This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU Lesser General Public
 * License.  If not, see <https://www.gnu.org/licenses/>. You should
 * have also received a copy of the Additional Terms Applicable
 * to OneConfig, as published by Polyfrost. If not, see
 * <https://polyfrost.org/legal/oneconfig/additional-terms>
 */

package com.muoniumplayer.configskija.ui.notifications;

import com.muoniumplayer.configskija.model.Notifications;
import com.muoniumplayer.configskija.model.Notifications.Action;
import com.muoniumplayer.configskija.model.Notifications.Notice;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.PageContext;
import com.muoniumplayer.configskija.ui.Painter;
import com.muoniumplayer.configskija.ui.Theme;
import com.muoniumplayer.configskija.ui.UiPage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Optional live toast surface, sharing the center history. Render after the shell in the supplied viewport. */
public final class NotificationToasts implements UiPage {
    private final Notifications notifications;
    private final Map<Long, ToastTimeline> timelines = new HashMap<>();
    private record Card(Notice notice, ToastTimeline timeline, List<String> lines, double rowHeight, double height, boolean bar) {}
    private record ActionBox(Action action, int index, Rect bounds) {}
    private record Placement(Card card, Rect bounds, List<ActionBox> actions, boolean hovered) {}
    private List<Placement> prepared = List.of();
    private Rect viewport = new Rect(0, 0, 0, 0);
    private double physicalScale = 1;
    private boolean screenOpen;

    public NotificationToasts(Notifications notifications) { this.notifications = Objects.requireNonNull(notifications); }
    @Override public void render(PageContext ctx) { render(ctx, new Rect(0, 0, ctx.width(), ctx.height()), 1); }
    public void render(PageContext ctx, Rect viewport, double physicalScale) { render(ctx, viewport, physicalScale, true); }
    public void render(PageContext ctx, Rect viewport, double physicalScale, boolean screenOpen) {
        tick(ctx, viewport, physicalScale, screenOpen);
        draw(ctx);
    }

    /** Prepare logical layout/input and advance the one retained timeline. No drawing or native resources. */
    public void tick(PageContext ctx, Rect viewport, double physicalScale, boolean screenOpen) {
        this.viewport = Objects.requireNonNull(viewport);
        if (!Double.isFinite(physicalScale) || physicalScale <= 0) throw new IllegalArgumentException("Positive physical scale required");
        this.physicalScale = physicalScale;
        this.screenOpen = screenOpen;
        Painter painter = ctx.painter();
        List<Card> cards = new ArrayList<>(); Set<Long> retained = new HashSet<>();
        double total = 0;
        // The source has no maxVisible cap: every active card advances, even when clipped by the viewport.
        for (Notice notice : notifications.active()) {
            retained.add(notice.id());
            ToastTimeline timeline = timelines.computeIfAbsent(notice.id(), ignored -> new ToastTimeline(notice));
            List<String> lines = NotificationLayout.toastLines(painter, notice.message(), 276);
            double rowHeight = Math.max(24, 21 + (lines.isEmpty() ? 0 : 4 + lines.size() * 18));
            boolean bar = notice.hasProgress() || (!notice.persistent() && notice.actions().isEmpty());
            double height = 32 + rowHeight + (bar ? 18 : 0) + (notice.actions().isEmpty() ? 0 : 42);
            cards.add(new Card(notice, timeline, lines, rowHeight, height, bar)); total += height;
        }
        timelines.keySet().retainAll(retained);
        if (!cards.isEmpty()) total += (cards.size() - 1) * 12;
        double x = viewport.right() - 356, y = viewport.bottom() - 16 - total;
        List<Placement> positions = new ArrayList<>();
        boolean pointerInside = screenOpen && viewport.contains(ctx.mouseX(), ctx.mouseY());
        for (Card card : cards) {
            Rect bounds = new Rect(x, y, 340, card.height());
            List<ActionBox> actions = actionBoxes(painter, card, bounds);
            boolean hovered = pointerInside && (bounds.contains(ctx.mouseX(), ctx.mouseY()) ||
                actions.stream().anyMatch(action -> action.bounds().contains(ctx.mouseX(), ctx.mouseY())));
            card.notice().hovered(hovered);
            card.timeline().advance((long)(ctx.time() * 1_000_000_000), hovered);
            if (card.timeline().done()) { card.notice().hovered(false); notifications.dismiss(card.notice()); }
            else {
                Placement placement = new Placement(card, bounds, actions, hovered);
                positions.add(placement);
                if (screenOpen && viewport.w() > 0 && viewport.h() > 0) registerHits(ctx, placement);
            }
            y += card.height() + 12;
        }
        prepared = List.copyOf(positions);
    }
    private List<ActionBox> actionBoxes(Painter painter, Card card, Rect bounds) {
        List<ActionBox> result = new ArrayList<>();
        double y = bounds.y() + 16 + card.rowHeight() + (card.bar() ? 18 : 0) + 12;
        double x = bounds.x() + 14;
        for (int index = 0; index < card.notice().actions().size(); index++) {
            Action action = card.notice().actions().get(index);
            Rect box = new Rect(x, y, NotificationLayout.measure(painter, action.label(), 12, 500) + 24, 30);
            result.add(new ActionBox(action, index, box)); x += box.w() + 16;
        }
        return List.copyOf(result);
    }
    private void registerHits(PageContext ctx, Placement placement) {
        Notice notice = placement.card().notice();
        Rect body = NotificationLayout.intersection(placement.bounds(), viewport);
        if (body.w() > 0 && body.h() > 0) ctx.hit("notification.toast." + notice.id(), body, () -> clickBody(ctx, notice), "");
        for (ActionBox action : placement.actions()) {
            Rect hit = NotificationLayout.intersection(action.bounds(), viewport);
            if (hit.w() > 0 && hit.h() > 0) ctx.hit("notification.toast.action." + notice.id() + "." + action.index(), hit,
                () -> clickAction(ctx, notice, action.action()), "");
        }
    }
    /** Repaint the prepared frame. Calling this twice never advances timers or registers input twice. */
    public void draw(PageContext ctx) {
        if (viewport.w() <= 0 || viewport.h() <= 0) return;
        Painter painter = ctx.painter();
        painter.save();
        try {
            painter.clip(viewport, 0);
            for (Placement placement : prepared) {
                Notice notice = placement.card().notice();
                if (notifications.contains(notice)) draw(ctx, placement);
            }
        } finally { painter.restore(); }
    }
    /** Cancel hover/input during focus loss or a Screen handoff; all timers/history remain owned here. */
    public void cancelInput() {
        screenOpen = false;
        for (Notice notice : notifications.active()) notice.hovered(false);
    }
    private void draw(PageContext ctx, Placement placement) {
        Card card = placement.card(); Rect bounds = placement.bounds();
        Painter painter = ctx.painter(); Theme theme = ctx.theme(); Notice notice = card.notice();
        boolean hovered = screenOpen && placement.hovered();
        int accent = switch (notice.type()) {
            case INFO -> theme.text(); case PROGRESS -> ctx.accent(); default -> notice.type().accent();
        };
        double cardRadius = theme.minecraft() ? 0 : 12, buttonRadius = theme.minecraft() ? 0 : 4, barRadius = theme.minecraft() ? 0 : 3;
        int bg = hovered ? lighten(theme.popup(), .06) : theme.popup();
        painter.layer(bounds, card.timeline().opacity());
        try {
            painter.rect(bounds, cardRadius, bg);
            painter.outline(bounds, cardRadius, 1, theme.border());
            Rect iconBounds = new Rect(bounds.x() + 14, bounds.y() + 16, 24, 24);
            if (notice.image() != null) {
                Notifications.ImageIcon image = notice.image(); double scale = Math.min(24.0 / image.width(), 24.0 / image.height());
                double width = image.width() * scale, height = image.height() * scale;
                painter.image(image.encoded(), new Rect(iconBounds.x() + (24 - width) / 2, iconBounds.y() + (24 - height) / 2, width, height), false);
            } else painter.icon(notice.icon() == null ? theme.iconPath(notice.type().icon()) : notice.icon(), iconBounds.x(), iconBounds.y(), 24, accent);
            double textX = bounds.x() + 50;
            NotificationLayout.text(painter, notice.title(), textX, bounds.y() + 19.5, 14, 500, accent);
            NotificationLayout.lines(painter, card.lines(), textX, bounds.y() + 41, 12, 18, false, theme.secondary());
            double y = bounds.y() + 16 + card.rowHeight();
            if (card.bar()) {
                y += 12; Rect bar = new Rect(bounds.x() + 14, y, 312, 6);
                double value = notice.hasProgress() ? card.timeline().progress() : card.timeline().timeFraction();
                value = ToastTimeline.quantize(value, 312 * physicalScale);
                painter.rect(bar, barRadius, Theme.multiplyAlpha(ctx.accent(), .5));
                if (value > 0) painter.rect(new Rect(bar.x(), bar.y(), Math.min(312, 312 * value), 6), barRadius, ctx.accent());
            }
            for (ActionBox box : placement.actions()) {
                Action action = box.action(); Rect actionBounds = box.bounds();
                int base = action.primary() ? ctx.accent() : theme.component();
                painter.rect(actionBounds, buttonRadius, screenOpen && viewport.contains(ctx.mouseX(), ctx.mouseY()) &&
                    actionBounds.contains(ctx.mouseX(), ctx.mouseY()) ? lighten(base, .12) : base);
                NotificationLayout.text(painter, action.label(), actionBounds.x() + 12, actionBounds.y() + 9, 12, 500,
                    action.primary() ? theme.accentText() : theme.text());
            }
        } finally { painter.restore(); }
    }
    private boolean clickable(Notice notice) { return notifications.contains(notice) && !notice.dismissRequested(); }
    private void clickBody(PageContext ctx, Notice notice) {
        if (!clickable(notice)) return;
        try { if (notice.onClick() != null) notice.onClick().run(); }
        catch (RuntimeException failure) { ctx.host().error("Notification body", failure); }
        finally { notice.requestDismiss(); }
    }
    private void clickAction(PageContext ctx, Notice notice, Action action) {
        if (!clickable(notice)) return;
        try { action.onClick().run(); }
        catch (RuntimeException failure) { ctx.host().error("Notification action: " + action.label(), failure); }
        finally { notifications.remove(notice); }
    }
    /** PolyColor.lighten mixes RGB toward white and preserves alpha. */
    private static int lighten(int color, double amount) {
        int result = color & 0xFF000000;
        for (int shift : new int[]{16, 8, 0}) {
            int channel = (color >>> shift) & 255;
            result |= (int)Math.round(channel + (255 - channel) * amount) << shift;
        }
        return result;
    }
}
