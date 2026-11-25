package com.muoniumplayer.configskija.ui.hud;

import com.muoniumplayer.configskija.ui.Geometry.Rect;
import com.muoniumplayer.configskija.ui.PageContext;
import com.muoniumplayer.configskija.ui.Painter;

import java.util.ArrayList;
import java.util.List;

/** TooltipArea(delayMillis=200), ComponentRect(TopCenter), 12x8 padding and fixed 8dp radius. */
final class HudTooltip {
    private record Candidate(String id, Rect anchor, String text, double maxWidth) {}

    private Candidate current, candidate;
    private double entered;
    private Rect drawn;

    void begin() {
        candidate = null;
        drawn = null;
    }

    void offer(PageContext c, String id, Rect bounds, String text, double maxWidth) {
        if (text != null && !text.isBlank() && bounds.contains(c.mouseX(), c.mouseY()))
            candidate = new Candidate(id, bounds, text, maxWidth);
    }

    void clear() {
        current = candidate = null;
        drawn = null;
    }

    Rect drawn() {
        return drawn;
    }

    void render(PageContext c) {
        if (candidate == null) {
            current = null;
            return;
        }
        if (current == null || !current.id().equals(candidate.id())) {
            current = candidate;
            entered = c.time();
            return;
        }
        current = candidate;
        if (c.time() - entered + 1e-9 < .2) return;
        List<String> lines =
                lines(
                        c.painter(),
                        current.text(),
                        Math.max(1, Math.min(c.width() - 24, current.maxWidth() - 24)),
                        12);
        double width = 0;
        for (String line : lines) width = Math.max(width, c.painter().measure(line, 12, false));
        width += 24;
        double height = lines.size() * 14 + 16;
        Rect anchor = current.anchor();
        drawn =
                HudWidgets.clamp(
                        new Rect(
                                anchor.x() + (anchor.w() - width) / 2,
                                anchor.y() - height,
                                width,
                                height),
                        c.width(),
                        c.height());
        Painter p = c.painter();
        p.rect(drawn, 8, c.theme().popup());
        p.outline(drawn, 8, 1, c.theme().border());
        double y = drawn.y() + 8;
        for (String line : lines) {
            HudWidgets.label(c, line, drawn.x() + 12, y, 12, false, c.theme().text());
            y += 14;
        }
    }

    static List<String> lines(Painter painter, String text, double width, double fontSize) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < paragraph.length(); ) {
                int cp = paragraph.codePointAt(i);
                i += Character.charCount(cp);
                String next = new String(Character.toChars(cp));
                if (line.length() > 0 && painter.measure(line + next, fontSize, false) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(next);
            }
            lines.add(line.toString());
        }
        return List.copyOf(lines);
    }
}
