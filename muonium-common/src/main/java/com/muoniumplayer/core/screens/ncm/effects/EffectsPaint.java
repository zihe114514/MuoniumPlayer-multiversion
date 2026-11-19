package com.muoniumplayer.core.screens.ncm.effects;

import com.muoniumplayer.core.rendering.entities.impl.TextField;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import static com.muoniumplayer.core.screens.ncm.effects.EffectsLayout.Box;

/** 本页的 Expressive 矢量图元。Canvas、字体均借用；只有这两支 Paint 由页面拥有。 */
public final class EffectsPaint implements AutoCloseable {

    /** 仅显式启用的离屏探针接收度量，正常绘制不分配诊断记录。 */
    public interface Probe {
        default void text(String source, String shown, Box ink, Box allowed) { }
        default void button(String label, Box button, Box icon, Box text, Box content) { }
        default void icon(Icon icon, Box box) { }
    }

    private Probe probe;
    public void setProbe(Probe probe) { this.probe = probe; }

    public record ButtonContent(String text, Box icon, Box label, Box bounds) { }

    private EffectsPalette palette = EffectsPalette.fallback();

    public void setPalette(EffectsPalette palette) {
        this.palette = java.util.Objects.requireNonNull(palette, "palette");
    }

    public enum Icon { PRESETS, EQ, EFFECTS, ROOM, AMBIENT, CLOSE, BACK, CHECK, PLUS, IMPORT,
        REFRESH, COPY, DELETE, UNDO, REDO, POWER, SEARCH, CHEVRON, WATER, RAIN, SAVE }

    private final Paint fill = new Paint().setAntiAlias(true);
    private final Paint stroke = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE)
            .setStrokeCap(PaintStrokeCap.ROUND);
    private boolean closed;

    public void rect(Canvas canvas, Box box, int color) {
        if (box.width() <= 0 || box.height() <= 0) return;
        canvas.drawRect(rect(box), fill.setColor(color));
    }

    public void round(Canvas canvas, Box box, double radius, int color) {
        if (box.width() <= 0 || box.height() <= 0) return;
        canvas.drawRRect(RRect.makeXYWH((float) box.x(), (float) box.y(), (float) box.width(),
                (float) box.height(), (float) Math.min(radius, Math.min(box.width(), box.height()) * .5)),
                fill.setColor(color));
    }

    public void outline(Canvas canvas, Box box, double radius, double width, int color) {
        if (box.width() <= 0 || box.height() <= 0) return;
        canvas.drawRRect(RRect.makeXYWH((float) box.x(), (float) box.y(), (float) box.width(),
                (float) box.height(), (float) radius), stroke.setColor(color).setStrokeWidth((float) width));
    }

    public void line(Canvas canvas, double x1, double y1, double x2, double y2, double width, int color) {
        canvas.drawLine((float) x1, (float) y1, (float) x2, (float) y2,
                stroke.setColor(color).setStrokeWidth((float) width));
    }

    public void circle(Canvas canvas, double x, double y, double radius, int color) {
        canvas.drawCircle((float) x, (float) y, (float) radius, fill.setColor(color));
    }

    public void path(Canvas canvas, Path path, double width, int color) {
        canvas.drawPath(path, stroke.setColor(color).setStrokeWidth((float) width));
    }

    public void pathFill(Canvas canvas, Path path, int color) { canvas.drawPath(path, fill.setColor(color)); }

    public String fit(CFontRenderer font, String value, double width) {
        if (font == null || value == null || value.isEmpty() || width <= 0) return "";
        double available = Math.max(0, width - 1);
        String shown = font.getStringWidthD(value) <= available ? value : font.trim(value, available);
        while (!shown.isEmpty()) {
            Rect ink = font.getGlyphBounds(shown);
            double extent = ink == null ? 0 : ink.getRight() - Math.min(0, ink.getLeft());
            if (ink == null || extent <= available + .01) break;
            available -= Math.max(.5, extent - available);
            shown = font.trim(value, available);
        }
        return shown;
    }

    public void text(Canvas canvas, CFontRenderer font, String value, double x, double centerY,
                     double maxWidth, int color) {
        if (font == null || value == null || value.isEmpty() || maxWidth <= 0) return;
        String shown = fit(font, value, maxWidth);
        if (shown.isEmpty()) return;
        Rect ink = font.getGlyphBounds(shown);
        double top = ink == null ? centerY - Math.max(1, font.getFontHeight()) * .5
                : centerY - ink.getTop() - ink.getHeight() * .5;
        double left = x - (ink == null ? 0 : Math.min(0, ink.getLeft()));
        font.drawGlyphsOn(canvas, shown, left, top, color, color >>> 24, 0, null);
        if (probe != null) {
            Box actual = ink == null ? new Box(x, centerY - font.getFontHeight() * .5,
                    font.getStringWidthD(shown), font.getFontHeight())
                    : new Box(left + ink.getLeft(), top + ink.getTop(), ink.getWidth(), ink.getHeight());
            probe.text(value, shown, actual, new Box(x, centerY - lineHeight(font) * .5, maxWidth, lineHeight(font)));
        }
    }

    public ButtonContent buttonContent(CFontRenderer font, String label, boolean withIcon, Box box) {
        double padding = Math.min(10, box.width() * .15);
        double iconSize = withIcon ? Math.min(18, Math.min(Math.max(0, box.height() - 10), box.width() - padding * 2)) : 0;
        double gap = withIcon ? 7 : 0;
        String shown = fit(font, label, Math.max(0, box.width() - padding * 2 - iconSize - gap));
        if (shown.isEmpty()) gap = 0;
        double textWidth = shown.isEmpty() ? 0 : font.getStringWidthD(shown);
        Rect ink = shown.isEmpty() ? null : font.getGlyphBounds(shown);
        if (ink != null) textWidth = Math.max(textWidth, ink.getRight() - Math.min(0, ink.getLeft()));
        double total = iconSize + gap + textWidth;
        double left = box.centerX() - total * .5;
        Box icon = new Box(left, box.centerY() - iconSize * .5, iconSize, iconSize);
        Box caption = new Box(left + iconSize + gap, box.y(), textWidth, box.height());
        return new ButtonContent(shown, icon, caption, new Box(left, box.y(), total, box.height()));
    }

    public void buttonLabel(Canvas canvas, CFontRenderer font, String label, Icon icon, Box box, int color) {
        ButtonContent content = buttonContent(font, label, icon != null, box);
        if (icon != null) icon(canvas, icon, content.icon(), color);
        text(canvas, font, content.text(), content.label().x(), box.centerY(), content.label().width() + 1, color);
        if (probe != null) probe.button(label, box, content.icon(), content.label(), content.bounds());
    }

    public String[] lines(CFontRenderer font, String text, double width) {
        if (font == null || text == null || text.isBlank() || width <= 0) return new String[0];
        java.util.List<String> wrapped = new java.util.ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isBlank()) { wrapped.add(""); continue; }
            for (String candidate : font.fitWidth(paragraph, Math.max(1, width - 2))) {
                String remaining = candidate;
                while (!remaining.isEmpty()) {
                    int end = remaining.length();
                    while (end > 1 && font.getStringWidthD(remaining.substring(0, end)) > width - 2) end--;
                    wrapped.add(remaining.substring(0, end));
                    remaining = remaining.substring(end);
                }
            }
        }
        return wrapped.toArray(new String[0]);
    }

    public double lineHeight(CFontRenderer font) {
        return Math.max(18, Math.max(font.getFontHeight() + 6, font.sizePx + 6));
    }

    public double paragraph(Canvas canvas, CFontRenderer font, String text, double x, double top,
                            double width, int color) {
        String[] lines = lines(font, text, width);
        double step = lineHeight(font);
        for (int i = 0; i < lines.length; i++) text(canvas, font, lines[i], x, top + step * (i + .5), width, color);
        return lines.length * step;
    }

    public void centered(Canvas canvas, CFontRenderer font, String value, Box box, int color) {
        if (font == null) return;
        buttonLabel(canvas, font, value, null, box, color);
    }

    /** 复用 TextField 的编辑/IME/剪贴板语义，绘制不走它的旧光标和下划线动画。 */
    public void input(Canvas canvas, TextField field, CFontRenderer font, Box box, long now) {
        round(canvas, box, 12, palette.input());
        if (field.isFocused()) outline(canvas, box.inset(1), 11, 1.5, palette.primary());
        String value = field.getText();
        if (!field.isFocused() && value.isEmpty()) {
            text(canvas, font, field.placeholder, box.x() + 12, box.centerY(), box.width() - 24, palette.muted());
            return;
        }
        int cursor = Math.max(0, Math.min(value.length(), field.getCursorPosition()));
        int selection = Math.max(0, Math.min(value.length(), field.getSelectionEnd()));
        double before = font.getStringWidthD(value.substring(0, cursor));
        double offset = Math.max(0, before - Math.max(1, box.width() - 28));
        int saved = canvas.save();
        canvas.clipRect(rect(box.inset(6)), true);
        try {
            double x = box.x() + 12 - offset;
            if (field.isFocused() && selection != cursor) {
                int a = Math.min(selection, cursor), b = Math.max(selection, cursor);
                double left = x + font.getStringWidthD(value.substring(0, a));
                double length = font.getStringWidthD(value.substring(a, b));
                round(canvas, new Box(left, box.centerY() - 9, length, 18), 3, palette.primaryContainer());
            }
            text(canvas, font, value, x, box.centerY(), Math.max(box.width(), font.getStringWidthD(value) + 2), palette.text());
            if (field.isFocused() && (now / 500_000_000L) % 2 == 0) {
                line(canvas, x + before, box.centerY() - 9, x + before, box.centerY() + 9, 1.2, palette.primary());
            }
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    /** 重点图标盘从圆到六瓣软形；不放大图标，也不让装饰越过给定盒。 */
    public void emblem(Canvas canvas, Icon icon, Box box, double expression, int surface, int ink) {
        double amount = Math.max(0, Math.min(1, expression));
        try (PathBuilder builder = new PathBuilder()) {
            for (int i = 0; i <= 72; i++) {
                double angle = i * Math.PI * 2 / 72;
                double radius = Math.min(box.width(), box.height()) * .5 * (1 - amount * .12 * (1 - Math.cos(angle * 6)));
                float x = (float) (box.centerX() + Math.cos(angle) * radius);
                float y = (float) (box.centerY() + Math.sin(angle) * radius);
                if (i == 0) builder.moveTo(x, y); else builder.lineTo(x, y);
            }
            builder.closePath();
            try (Path shape = builder.build()) { pathFill(canvas, shape, surface); }
        }
        icon(canvas, icon, box.inset(Math.min(box.width(), box.height()) * .25), ink);
    }

    public void wave(Canvas canvas, Box box, double phase, double amount, int color) {
        if (box.width() <= 0 || box.height() <= 0) return;
        try (PathBuilder builder = new PathBuilder()) {
            for (int i = 0; i <= 48; i++) {
                double t = i / 48.0;
                double envelope = Math.sin(Math.PI * t);
                float x = (float) (box.x() + t * box.width());
                float y = (float) (box.centerY() + Math.sin(t * Math.PI * 5 + phase) * envelope * box.height() * .35 * amount);
                if (i == 0) builder.moveTo(x, y); else builder.lineTo(x, y);
            }
            try (Path path = builder.build()) { path(canvas, path, 1.5, color); }
        }
    }

    public void icon(Canvas canvas, Icon icon, Box box, int color) {
        if (box.width() <= 0 || box.height() <= 0) return;
        if (probe != null) probe.icon(icon, box);
        double scale = Math.min(box.width(), box.height()) / 20;
        double x = box.centerX() - 10 * scale, y = box.centerY() - 10 * scale;
        int saved = canvas.save();
        canvas.translate((float) x, (float) y);
        canvas.scale((float) scale, (float) scale);
        try {
            switch (icon) {
                case CLOSE -> { segment(canvas, 5, 5, 15, 15, color); segment(canvas, 15, 5, 5, 15, color); }
                case CHECK -> { segment(canvas, 3, 10, 8, 15, color); segment(canvas, 8, 15, 17, 5, color); }
                case PLUS -> { segment(canvas, 10, 4, 10, 16, color); segment(canvas, 4, 10, 16, 10, color); }
                case BACK -> { segment(canvas, 12, 4, 6, 10, color); segment(canvas, 6, 10, 12, 16, color); }
                case CHEVRON -> { segment(canvas, 7, 5, 12, 10, color); segment(canvas, 12, 10, 7, 15, color); }
                case EQ, EFFECTS -> {
                    for (int i = 0; i < 3; i++) {
                        double px = 5 + i * 5, py = i == 1 ? 6 : 13;
                        segment(canvas, px, 3, px, 17, color);
                        round(canvas, new Box(px - 2.6, py - 1.5, 5.2, 3), 1.5, color);
                    }
                }
                case PRESETS -> {
                    outline(canvas, new Box(3, 4, 14, 13), 4, 1.6, color);
                    segment(canvas, 6, 1, 14, 1, color);
                    circle(canvas, 10, 10.5, 2.3, color);
                }
                case ROOM -> {
                    segment(canvas, 3, 9, 10, 3, color); segment(canvas, 10, 3, 17, 9, color);
                    segment(canvas, 5, 8, 5, 17, color); segment(canvas, 15, 8, 15, 17, color);
                    segment(canvas, 5, 17, 15, 17, color);
                }
                case AMBIENT -> {
                    segment(canvas, 12, 3, 12, 13, color); segment(canvas, 12, 3, 17, 5, color);
                    circle(canvas, 8.5, 14, 3.3, color); segment(canvas, 2, 4, 5, 4, color);
                    segment(canvas, 3.5, 2.5, 3.5, 5.5, color);
                }
                case IMPORT -> {
                    segment(canvas, 10, 2, 10, 12, color); segment(canvas, 6, 8, 10, 12, color);
                    segment(canvas, 14, 8, 10, 12, color); segment(canvas, 3, 13, 3, 17, color);
                    segment(canvas, 3, 17, 17, 17, color); segment(canvas, 17, 17, 17, 13, color);
                }
                case COPY, SAVE -> {
                    outline(canvas, new Box(6, 6, 11, 11), 2, 1.6, color);
                    segment(canvas, 3, 13, 3, 3, color); segment(canvas, 3, 3, 13, 3, color);
                }
                case DELETE -> {
                    segment(canvas, 3, 5, 17, 5, color); segment(canvas, 7, 2, 13, 2, color);
                    segment(canvas, 5, 5, 6, 17, color); segment(canvas, 6, 17, 14, 17, color);
                    segment(canvas, 14, 17, 15, 5, color); segment(canvas, 8, 8, 8, 14, color);
                    segment(canvas, 12, 8, 12, 14, color);
                }
                case REFRESH, UNDO, REDO -> {
                    try (PathBuilder builder = new PathBuilder()) {
                        if (icon == Icon.REDO) {
                            builder.moveTo(4, 15).cubicTo(1, 6, 11, 2, 17, 7)
                                    .moveTo(17, 3).lineTo(17, 8).lineTo(12, 8);
                        } else {
                            builder.moveTo(16, 15).cubicTo(19, 6, 9, 2, 3, 7)
                                    .moveTo(3, 3).lineTo(3, 8).lineTo(8, 8);
                        }
                        try (Path path = builder.build()) { path(canvas, path, 1.6, color); }
                    }
                }
                case POWER -> {
                    try (PathBuilder builder = new PathBuilder()) {
                        builder.moveTo(5, 4).cubicTo(0, 10, 4, 17, 10, 17).cubicTo(16, 17, 20, 10, 15, 4);
                        try (Path path = builder.build()) { path(canvas, path, 1.8, color); }
                    }
                    segment(canvas, 10, 1, 10, 10, color);
                }
                case SEARCH -> {
                    outline(canvas, new Box(3, 3, 10, 10), 5, 1.6, color); segment(canvas, 12, 12, 17, 17, color);
                }
                case WATER -> {
                    try (PathBuilder builder = new PathBuilder()) {
                        builder.moveTo(10, 2).cubicTo(7, 7, 3, 10, 4, 14)
                                .cubicTo(5, 19, 15, 19, 16, 14).cubicTo(17, 10, 13, 7, 10, 2).closePath();
                        try (Path path = builder.build()) { path(canvas, path, 1.6, color); }
                    }
                }
                case RAIN -> {
                    outline(canvas, new Box(2, 3, 16, 8), 4, 1.6, color);
                    for (int i = 0; i < 3; i++) segment(canvas, 6 + i * 4, 14, 4 + i * 4, 18, color);
                }
            }
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private void segment(Canvas canvas, double x1, double y1, double x2, double y2, int color) {
        line(canvas, x1, y1, x2, y2, 1.7, color);
    }

    public static Rect rect(Box box) {
        return Rect.makeXYWH((float) box.x(), (float) box.y(), (float) Math.max(0, box.width()), (float) Math.max(0, box.height()));
    }

    public static int alpha(int color, double amount) {
        int own = color >>> 24;
        return (Math.max(0, Math.min(255, (int) Math.round(own * amount))) << 24) | (color & 0xFFFFFF);
    }

    public static int mix(int from, int to, double t) {
        t = Math.max(0, Math.min(1, t));
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            out |= ((int) Math.round(a + (b - a) * t)) << shift;
        }
        return out;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        fill.close();
        stroke.close();
    }
}
