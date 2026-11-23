package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.ArrayList;
import java.util.List;

final class SettingsDraw {
    private SettingsDraw() {}
    static int opacity(int argb, double opacity) { return (int)Math.round((argb >>> 24) * Geometry.clamp(opacity, 0, 1)) << 24 | argb & 0xffffff; }
    static int over(int fg, int bg) { double a = (fg >>> 24) / 255d, b = (bg >>> 24) / 255d, out = a + b * (1 - a); if (out == 0) return 0; int rgb = 0;
        for (int shift = 0; shift < 24; shift += 8) rgb |= (int)Math.round((((fg >>> shift & 255) * a) + (bg >>> shift & 255) * b * (1 - a)) / out) << shift;
        return (int)Math.round(out * 255) << 24 | rgb;
    }
    static boolean hover(PageContext ctx, Rect r) { return r.contains(ctx.mouseX(), ctx.mouseY()); }
    static void frame(PageContext ctx, Rect r, double radius, int fill, int border) { ctx.painter().rect(r, ctx.theme().radius(radius), fill); ctx.painter().outline(r.inset(.5), Math.max(0, ctx.theme().radius(radius) - .5), 1, border); }
    static void center(PageContext ctx, String text, Rect r, double size, boolean bold, int color) { ctx.painter().text(text, r.x() + (r.w() - ctx.painter().measure(text, size, bold)) / 2, r.y() + (r.h() - size) / 2, size, bold, color); }
    static String trim(Painter p, String text, double width, double size, boolean bold) {
        if (text == null) return ""; if (p.measure(text, size, bold) <= width) return text;
        double limit = Math.max(0, width - p.measure("…", size, bold)); int end = 0;
        while (end < text.length()) { int next = text.offsetByCodePoints(end, 1); if (p.measure(text.substring(0, next), size, bold) > limit) break; end = next; }
        return text.substring(0, end) + "…";
    }
    static List<String> wrap(Painter p, String text, double width, double size, boolean bold) {
        List<String> lines = new ArrayList<>(); if (text == null || text.isEmpty()) return lines;
        for (String paragraph : text.split("\n", -1)) { if (paragraph.isEmpty()) { lines.add(""); continue; } int start = 0;
            while (start < paragraph.length()) { int end = start, wordEnd = -1;
                while (end < paragraph.length()) { int next = paragraph.offsetByCodePoints(end, 1); if (p.measure(paragraph.substring(start, next), size, bold) > width && end > start) break; if (Character.isWhitespace(paragraph.codePointAt(end))) wordEnd = next; end = next; }
                if (end < paragraph.length() && wordEnd > start) end = wordEnd;
                lines.add(paragraph.substring(start, end).stripTrailing()); start = end;
            }
        }
        return lines;
    }
    static double text(PageContext ctx, SettingsSchema schema, String text, double x, double y, double width, double size, boolean bold, int color) {
        double height = schema.lineHeight(ctx, size, bold); List<String> lines = wrap(ctx.painter(), text, width, size, bold);
        for (String line : lines) { ctx.painter().text(line, x, y, size, bold, color); y += height; }
        return height * lines.size();
    }
    static Rect popup(Rect anchor, double width, double height, double offset, Rect shell) {
        double x = Geometry.clamp(anchor.right() - width, shell.x() + 8, Math.max(shell.x() + 8, shell.right() - width - 8));
        double below = anchor.bottom() + offset, above = anchor.y() - offset - height;
        double y = below + height <= shell.bottom() - 8 ? below : above >= shell.y() + 8 ? above : Geometry.clamp(below, shell.y() + 8, Math.max(shell.y() + 8, shell.bottom() - height - 8));
        return new Rect(x, y, width, Math.min(height, shell.h() - 16));
    }
    static void accent(PageContext ctx, SettingsSchema schema, Rect r, double radius, double alpha) {
        ctx.painter().save(); ctx.painter().clip(r, radius); int left = opacity(ctx.accent(), alpha), right = opacity(schema.accentEnd(ctx), alpha); ctx.painter().gradient(r, left, right, right, left); ctx.painter().restore();
    }
    static void fadingEdges(PageContext ctx, SettingsSchema schema, Rect viewport, double scroll, double maximum, int color, String key) {
        double edge = Math.min(32, viewport.h() / 2); if (edge <= 0) return;
        double top = ctx.animate(key + ".top", scroll > 0 ? 1 : 0, 1, Motion.Easing.SPRING), bottom = ctx.animate(key + ".bottom", scroll < maximum ? 1 : 0, 1, Motion.Easing.SPRING);
        if (top > 0) { Rect r = new Rect(viewport.x(), viewport.y(), viewport.w(), edge); int solid = opacity(color, top), clear = color & 0xffffff; ctx.painter().gradient(r, solid, solid, clear, clear); }
        if (bottom > 0) { Rect r = new Rect(viewport.x(), viewport.bottom() - edge, viewport.w(), edge); int solid = opacity(color, bottom), clear = color & 0xffffff; ctx.painter().gradient(r, clear, clear, solid, solid); }
    }
    static void chevron(PageContext ctx, double x, double y, double size, double degrees, int color) {
        double angle = Math.toRadians(degrees), c = Math.cos(angle), s = Math.sin(angle);
        double[] px = {-size / 3, 0, size / 3}, py = {size / 6, -size / 6, size / 6};
        for (int i = 0; i < 2; i++) ctx.painter().line(x + px[i] * c - py[i] * s, y + px[i] * s + py[i] * c, x + px[i + 1] * c - py[i + 1] * s, y + px[i + 1] * s + py[i + 1] * c, 1.5, color);
    }
}
