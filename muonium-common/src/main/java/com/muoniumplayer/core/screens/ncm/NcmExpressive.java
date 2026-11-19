package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.GlassLook;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.Panel;
import com.muoniumplayer.core.rendering.ui.widgets.LabelWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RectWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget;
import com.muoniumplayer.core.rendering.ui.widgets.RoundedRectWidget;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.Rect;
import io.github.humbleui.types.RRect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Shared expressive components used by the seven player prompts. */
public final class NcmExpressive {
    public static final double DIALOG_RADIUS = 24.0;
    public static final double CARD_RADIUS = 18.0;
    public static final double BUTTON_RADIUS = 25.0;
    public static final double BUTTON_HEIGHT = 50.0;

    public enum ActionStyle { PRIMARY, TONAL, NEUTRAL, DANGER }

    private NcmExpressive() { }

    public static CFontRenderer actionFont() { return FontManager.pf28bold; }
    public static CFontRenderer titleFont() { return FontManager.pf50bold; }
    public static CFontRenderer dialogTitleFont() { return FontManager.pf40bold; }
    public static CFontRenderer bodyFont() { return FontManager.pf32; }
    public static CFontRenderer labelFont() { return FontManager.pf28bold; }
    public static CFontRenderer captionFont() { return FontManager.pf25; }

    public static double dialogWidth(NCMPanel host, double preferredWidth) {
        return Math.max(1, Math.min(preferredWidth, Math.min(host.getWidth() - 48, host.getWidth() * .74)));
    }

    public static double dialogHeight(NCMPanel host, double preferredHeight) {
        return Math.max(1, Math.min(preferredHeight, Math.min(host.getHeight() - 40, host.getHeight() * .88)));
    }

    public static Panel shell(NCMPanel overlay, double preferredWidth, DoubleSupplier preferredHeight,
                              DoubleSupplier presentation, Runnable outsideClose) {
        RectWidget mask = new RectWidget();
        overlay.addChild(mask);
        mask.setClickable(false).setColor(0x000000);
        mask.setBeforeRenderCallback(() -> {
            mask.setMargin(0);
            mask.setAlpha((float) (presentation.getAsDouble() * .48));
        });
        overlay.setOnClickCallback((x, y, button) -> {
            if (button != 0) return false;
            if (outsideClose != null) outsideClose.run();
            return true;
        });
        Panel dialog = new Panel();
        overlay.addChild(dialog);
        dialog.setOnClickCallback((x, y, button) -> true);
        dialog.setBeforeRenderCallback(() -> {
            double progress = presentation.getAsDouble();
            dialog.setBounds(dialogWidth(overlay, preferredWidth),
                    dialogHeight(overlay, preferredHeight.getAsDouble()));
            dialog.center();
            dialog.setPosition(dialog.getRelativeX(), dialog.getRelativeY() + (1 - progress) * 12);
            dialog.setAlpha((float) progress);
        });
        Panel depth = new Panel() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                Canvas canvas = SkijaGfx.canvas();
                if (canvas == null || getAlpha() <= 0) return;
                canvas.drawRRect(RRect.makeXYWH((float) getX(), (float) (getY() + 5),
                        (float) getWidth(), (float) getHeight(), (float) DIALOG_RADIUS),
                        SkijaGfx.fillBlurred(withAlpha(0xFF000000, getAlpha() * .22), 9));
            }
        };
        dialog.addChild(depth);
        depth.setClickable(false).setBeforeRenderCallback(() -> {
            depth.setPosition(0, 0);
            depth.setBounds(dialog.getWidth(), dialog.getHeight());
        });
        RoundedRectWidget background = surface(dialog, 0, 0, dialog::getWidth, dialog::getHeight,
                DIALOG_RADIUS, () -> NcmExpressivePalette.live().surface());
        background.setGlassSheet(GlassLook.Surface.PLAYER);
        background.setFillAlpha(GlassLook.washAlpha(GlassLook.Surface.PLAYER, 1));
        return dialog;
    }

    /** 保留 fitWidth 的断句位置，但把超宽的尾标点再次折行，不让正文被 Label 的省略号吃掉。 */
    public static String[] wrap(CFontRenderer font, String source, double width) {
        if (source == null) source = "";
        if (font == null) return source.split("\\n", -1);
        List<String> lines = new ArrayList<>();
        double breakWidth = Math.max(1, width - font.getStringWidthD("，"));
        for (String paragraph : source.split("\\n", -1)) {
            for (String line : font.fitWidth(paragraph, breakWidth)) {
                if (line.isEmpty()) {
                    lines.add("");
                    continue;
                }
                int start = 0;
                while (start < line.length()) {
                    int end = start;
                    while (end < line.length()) {
                        int next = end + Character.charCount(line.codePointAt(end));
                        if (end > start && font.getStringWidthD(line.substring(start, next)) > width) break;
                        end = next;
                    }
                    lines.add(line.substring(start, end));
                    start = end;
                }
            }
        }
        return lines.toArray(new String[0]);
    }

    public static double headerHeight(Panel dialog) {
        return dialog.getHeight() < 460 ? 100 : 140;
    }

    public static void header(Panel dialog, String glyph, Supplier<String> eyebrow,
                               Supplier<String> title, Runnable close) {
        medallion(dialog, glyph, () -> headerHeight(dialog) < 140 ? 44 : 55,
                () -> headerHeight(dialog) < 140 ? 35 : 55, () -> headerHeight(dialog) < 140 ? 40 : 62,
                () -> NcmExpressivePalette.live().primaryContainer(), () -> NcmExpressivePalette.live().primary());
        label(dialog, eyebrow, NcmExpressive::captionFont, () -> headerHeight(dialog) < 140 ? 76 : 102,
                () -> headerHeight(dialog) < 140 ? 35 : 54,
                () -> Math.max(1, dialog.getWidth() - (headerHeight(dialog) < 140 ? 100 : 126) - (close == null ? 0 : 52)),
                () -> NcmExpressivePalette.live().muted());
        label(dialog, title, NcmExpressive::dialogTitleFont, () -> 24, () -> headerHeight(dialog) - 25,
                () -> dialog.getWidth() - 48, () -> NcmExpressivePalette.live().text());
        if (close != null) {
            hitSurface(dialog, () -> dialog.getWidth() - 66, () -> headerHeight(dialog) < 140 ? 14 : 34,
                    () -> 42, () -> 42, 21, () -> NcmExpressivePalette.live().container(), close);
            icon(dialog, PartyIcons.CLOSE, NcmSheet.ICON_M, () -> dialog.getWidth() - 45,
                    () -> headerHeight(dialog) < 140 ? 35 : 55, () -> NcmExpressivePalette.live().muted());
        }
    }

    public static RoundedRectWidget surface(AbstractWidget<?> host, double x, double y,
                                             DoubleSupplier width, DoubleSupplier height,
                                             double radius, IntSupplier color) {
        return surface(host, () -> x, () -> y, width, height, radius, color);
    }

    public static RoundedRectWidget surface(AbstractWidget<?> host, DoubleSupplier x, DoubleSupplier y,
                                             DoubleSupplier width, DoubleSupplier height,
                                             double radius, IntSupplier color) {
        RoundedRectWidget widget = new RoundedRectWidget() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                super.onRender(mouseX, mouseY);
                Canvas canvas = SkijaGfx.canvas();
                if (canvas == null || getWidth() <= 1 || getHeight() <= 1) return;
                int outline = withAlpha(NcmExpressivePalette.live().outline(), getAlpha() * .14);
                canvas.drawRRect(RRect.makeXYWH((float) (getX() + .5), (float) (getY() + .5),
                        (float) Math.max(0, getWidth() - 1), (float) Math.max(0, getHeight() - 1), (float) getRadius()),
                        SkijaGfx.stroke(outline, 1));
            }
        };
        host.addChild(widget);
        widget.setClickable(false).setRadius(radius);
        widget.setBeforeRenderCallback(() -> {
            widget.setBounds(Math.max(0, width.getAsDouble()), Math.max(0, height.getAsDouble()));
            widget.setPosition(x.getAsDouble(), y.getAsDouble());
            widget.setColor(color.getAsInt());
        });
        return widget;
    }

    public static RoundedRectWidget hitSurface(AbstractWidget<?> host, DoubleSupplier x, DoubleSupplier y,
                                                DoubleSupplier width, DoubleSupplier height,
                                                double radius, IntSupplier color, Runnable onClick) {
        NcmPromptMotion hover = new NcmPromptMotion(0);
        NcmPromptMotion press = new NcmPromptMotion(0);
        RoundedRectWidget widget = new RoundedRectWidget() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                long now = System.nanoTime();
                double hovering = hover.update(testHovered(mouseX, mouseY) ? 1 : 0, 45, now);
                double pressed = press.update(testHovered(mouseX, mouseY) && Mouse.isButtonDown(0) ? 1 : 0, 28, now);
                setColor(NcmExpressivePalette.mix(color.getAsInt(), NcmExpressivePalette.live().primaryContainer(),
                        hovering * .64 + pressed * .18));
                setRadius(radius - pressed * 2);
                super.onRender(mouseX, mouseY);
            }
        };
        host.addChild(widget);
        widget.setShouldOverrideMouseCursor(true).setRadius(radius);
        widget.setBeforeRenderCallback(() -> {
            widget.setBounds(Math.max(0, width.getAsDouble()), Math.max(0, height.getAsDouble()));
            widget.setPosition(x.getAsDouble(), y.getAsDouble());
        });
        widget.setOnClickCallback((mx, my, button) -> {
            if (button != 0) return false;
            onClick.run();
            return true;
        });
        return widget;
    }

    public static LabelWidget icon(AbstractWidget<?> host, Supplier<String> glyph, double box,
                                   DoubleSupplier centerX, DoubleSupplier centerY, IntSupplier color) {
        LabelWidget icon = new LabelWidget(glyph, NcmSheet.iconFont(box));
        host.addChild(icon);
        icon.setClickable(false).setBeforeRenderCallback(() -> {
            CFontRenderer renderer = NcmSheet.iconFont(box);
            icon.setFont(renderer).setColor(color.getAsInt());
            placeInk(icon, renderer, centerX.getAsDouble(), centerY.getAsDouble());
        });
        return icon;
    }

    public static LabelWidget icon(AbstractWidget<?> host, String glyph, double box,
                                   DoubleSupplier centerX, DoubleSupplier centerY, IntSupplier color) {
        return icon(host, () -> glyph, box, centerX, centerY, color);
    }

    private static void placeInk(LabelWidget label, CFontRenderer font, double centerX, double centerY) {
        Rect ink = font == null ? null : font.getGlyphBounds(label.getLabel());
        if (ink == null) {
            label.setPosition(centerX - label.getWidth() * .5, centerY - label.getHeight() * .5);
        } else {
            label.setPosition(centerX - (ink.getLeft() + ink.getRight()) * .5,
                    centerY - (ink.getTop() + ink.getBottom()) * .5);
        }
    }

    public static LabelWidget label(AbstractWidget<?> host, Supplier<String> text, CFontRenderer font,
                                    DoubleSupplier x, DoubleSupplier centerY, DoubleSupplier maxWidth,
                                    IntSupplier color) {
        return label(host, text, () -> font == null ? bodyFont() : font, x, centerY, maxWidth, color);
    }

    public static LabelWidget label(AbstractWidget<?> host, Supplier<String> text, Supplier<CFontRenderer> font,
                                    DoubleSupplier x, DoubleSupplier centerY, DoubleSupplier maxWidth,
                                    IntSupplier color) {
        LabelWidget label = new LabelWidget(text, font.get());
        host.addChild(label);
        label.setClickable(false).setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
        label.setBeforeRenderCallback(() -> {
            CFontRenderer renderer = font.get();
            label.setFont(renderer).setColor(color.getAsInt());
            label.setMaxWidth(Math.max(1, maxWidth.getAsDouble()));
            Rect ink = renderer == null ? null : renderer.getGlyphBounds(renderer.trim(label.getLabel(), label.getMaxWidth()));
            label.setPosition(x.getAsDouble(), ink == null ? NcmSheet.inkTop(label, centerY.getAsDouble())
                    : centerY.getAsDouble() - (ink.getTop() + ink.getBottom()) * .5);
        });
        return label;
    }

    public static LabelWidget rightAligned(AbstractWidget<?> host, Supplier<String> text, CFontRenderer font,
                                            DoubleSupplier rightX, DoubleSupplier centerY,
                                            DoubleSupplier maxWidth, IntSupplier color) {
        return rightAligned(host, text, () -> font == null ? captionFont() : font, rightX, centerY, maxWidth, color);
    }

    public static LabelWidget rightAligned(AbstractWidget<?> host, Supplier<String> text, Supplier<CFontRenderer> font,
                                            DoubleSupplier rightX, DoubleSupplier centerY,
                                            DoubleSupplier maxWidth, IntSupplier color) {
        LabelWidget label = label(host, text, font, () -> 0, centerY, maxWidth, color);
        label.setBeforeRenderCallback(() -> {
            CFontRenderer renderer = font.get();
            label.setFont(renderer).setColor(color.getAsInt());
            label.setMaxWidth(Math.max(1, maxWidth.getAsDouble()));
            String visible = renderer == null ? "" : renderer.trim(label.getLabel(), label.getMaxWidth());
            Rect ink = renderer == null ? null : renderer.getGlyphBounds(visible);
            label.setPosition(rightX.getAsDouble() - (ink == null ? label.getWidth() : ink.getRight()),
                    ink == null ? NcmSheet.inkTop(label, centerY.getAsDouble())
                            : centerY.getAsDouble() - (ink.getTop() + ink.getBottom()) * .5);
        });
        return label;
    }

    public static LabelWidget centered(AbstractWidget<?> host, Supplier<String> text, CFontRenderer font,
                                       DoubleSupplier centerX, DoubleSupplier centerY,
                                       DoubleSupplier maxWidth, IntSupplier color) {
        return centered(host, text, () -> font == null ? bodyFont() : font, centerX, centerY, maxWidth, color);
    }

    public static LabelWidget centered(AbstractWidget<?> host, Supplier<String> text, Supplier<CFontRenderer> font,
                                       DoubleSupplier centerX, DoubleSupplier centerY,
                                       DoubleSupplier maxWidth, IntSupplier color) {
        LabelWidget label = label(host, text, font, () -> 0, centerY, maxWidth, color);
        label.setBeforeRenderCallback(() -> {
            CFontRenderer renderer = font.get();
            label.setFont(renderer).setColor(color.getAsInt());
            label.setMaxWidth(Math.max(1, maxWidth.getAsDouble()));
            String visible = renderer == null ? "" : renderer.trim(label.getLabel(), label.getMaxWidth());
            Rect ink = renderer == null ? null : renderer.getGlyphBounds(visible);
            label.setPosition(centerX.getAsDouble() - (ink == null ? label.getWidth() * .5
                            : (ink.getLeft() + ink.getRight()) * .5),
                    ink == null ? NcmSheet.inkTop(label, centerY.getAsDouble())
                            : centerY.getAsDouble() - (ink.getTop() + ink.getBottom()) * .5);
        });
        return label;
    }

    public static RoundedButtonWidget action(AbstractWidget<?> host, String glyph, Supplier<String> caption,
                                              ActionStyle style, DoubleSupplier x, DoubleSupplier y,
                                              DoubleSupplier width, DoubleSupplier height, Runnable onClick) {
        NcmPromptMotion hover = new NcmPromptMotion(0);
        NcmPromptMotion press = new NcmPromptMotion(0);
        RoundedButtonWidget button = new RoundedButtonWidget("", actionFont()) {
            @Override
            public void onRender(double mouseX, double mouseY) {
                long now = System.nanoTime();
                boolean inside = testHovered(mouseX, mouseY) && isClickable();
                double h = hover.update(inside ? 1 : 0, 48, now);
                double p = press.update(inside && Mouse.isButtonDown(0) ? 1 : 0, 25, now);
                setColor(NcmExpressivePalette.mix(actionSurface(style, false), actionSurface(style, true), h));
                setRadius(Math.min(getHeight() * .5, BUTTON_RADIUS) - p * 3);
                super.onRender(mouseX, mouseY);
                Canvas canvas = SkijaGfx.canvas();
                if (canvas != null && getWidth() > 1 && getHeight() > 1) {
                    canvas.drawRRect(RRect.makeXYWH((float) (getX() + 1), (float) (getY() + 1),
                            (float) Math.max(0, getWidth() - 2), (float) Math.max(0, getHeight() - 2), (float) getRadius()),
                            SkijaGfx.stroke(withAlpha(NcmExpressivePalette.live().onPrimary(), getAlpha() * (.04 + h * .08)), 1));
                }
            }
        };
        host.addChild(button);
        button.setRadius(BUTTON_RADIUS).setShouldOverrideMouseCursor(true);
        button.setOnClickCallback((mx, my, mouseButton) -> {
            if (mouseButton != 0) return false;
            onClick.run();
            return true;
        });
        Panel content = new Panel() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                Canvas canvas = SkijaGfx.canvas();
                CFontRenderer font = actionFont();
                if (canvas == null || font == null || button.getWidth() <= 24 || button.getHeight() <= 1) return;
                CFontRenderer iconFont = NcmSheet.iconFont(NcmSheet.ICON_M);
                Rect mark = glyph == null || glyph.isEmpty() || iconFont == null ? null : iconFont.getGlyphBounds(glyph);
                double iconWidth = mark == null ? 0 : mark.getWidth();
                String value = caption.get();
                if (value == null) value = "";
                double gap = iconWidth == 0 || value.isEmpty() ? 0 : 7;
                value = font.trim(value, Math.max(0, button.getWidth() - 24 - iconWidth - gap));
                Rect ink = font.getGlyphBounds(value);
                double textWidth = ink == null ? 0 : ink.getWidth();
                double left = (button.getWidth() - iconWidth - gap - textWidth) * .5;
                double cy = button.getHeight() * .5;
                int color = actionInk(style, button);
                int argb = (Math.round(getAlpha() * 255) << 24) | (color & 0xFFFFFF);
                if (mark != null) {
                    iconFont.drawString(glyph, getX() + left - mark.getLeft(),
                            getY() + cy - (mark.getTop() + mark.getBottom()) * .5, argb);
                }
                if (ink != null) {
                    font.drawString(value, getX() + left + iconWidth + gap - ink.getLeft(),
                            getY() + cy - (ink.getTop() + ink.getBottom()) * .5, argb);
                }
            }
        };
        button.addChild(content);
        content.setClickable(false).setBeforeRenderCallback(() -> {
            content.setPosition(0, 0);
            content.setBounds(Math.max(0, button.getWidth()), Math.max(0, button.getHeight()));
        });
        button.setBeforeRenderCallback(() -> {
            double w = width.getAsDouble(), h = height.getAsDouble();
            button.setBounds(Math.max(0, w), Math.max(0, h));
            button.setPosition(x.getAsDouble(), y.getAsDouble());
            button.setClickable(w > 1 && h > 1);
            button.setColor(actionSurface(style, button.isHovering()));
            button.setFillAlpha(1);
        });
        return button;
    }

    private static int actionSurface(ActionStyle style, boolean hover) {
        NcmExpressivePalette palette = NcmExpressivePalette.live();
        switch (style) {
            case PRIMARY: return hover ? palette.primaryHover() : palette.primary();
            case DANGER: return NcmExpressivePalette.mix(palette.container(), palette.error(), hover ? .20 : .11);
            case TONAL: return NcmExpressivePalette.mix(palette.primaryContainer(), palette.primary(), hover ? .16 : 0);
            default: return hover ? palette.primaryContainer() : palette.container();
        }
    }

    private static int actionInk(ActionStyle style, RoundedButtonWidget button) {
        NcmExpressivePalette palette = NcmExpressivePalette.live();
        int desired = style == ActionStyle.PRIMARY ? palette.onPrimary()
                : style == ActionStyle.DANGER ? palette.error() : palette.text();
        return NcmExpressivePalette.readableColor(desired, button.getColor().getRGB());
    }

    public static AbstractWidget<?> medallion(AbstractWidget<?> host, String glyph,
                                              DoubleSupplier centerX, DoubleSupplier centerY,
                                              DoubleSupplier diameter, IntSupplier background, IntSupplier ink) {
        Panel badge = new Panel() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                Canvas canvas = SkijaGfx.canvas();
                if (canvas == null || getWidth() <= 1) return;
                scallop(canvas, getX() + getWidth() * .5, getY() + getHeight() * .5,
                        getWidth() * .5, 8, 0, .055, getHexColor());
            }
        };
        host.addChild(badge);
        badge.setClickable(false).setBeforeRenderCallback(() -> {
            double size = Math.max(0, diameter.getAsDouble());
            badge.setBounds(size, size);
            badge.setAlpha(size > 1 ? 1 : 0);
            badge.setPosition(centerX.getAsDouble() - size * .5, centerY.getAsDouble() - size * .5);
            badge.setColor(background.getAsInt());
        });
        icon(badge, glyph, NcmSheet.ICON_XL, () -> badge.getWidth() * .5, () -> badge.getHeight() * .5, ink);
        return badge;
    }

    public static AbstractWidget<?> loading(AbstractWidget<?> host, DoubleSupplier centerX,
                                            DoubleSupplier centerY, DoubleSupplier diameter, IntSupplier color) {
        long started = System.nanoTime();
        Panel shape = new Panel() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                Canvas canvas = SkijaGfx.canvas();
                if (canvas == null || getWidth() <= 1 || getAlpha() <= 0) return;
                double time = (System.nanoTime() - started) / 1_000_000_000.0;
                double cx = getX() + getWidth() * .5, cy = getY() + getHeight() * .5;
                scallop(canvas, cx, cy, getWidth() * .5, 6, time * 1.25,
                        .07 + .02 * Math.sin(time * 2.5), getHexColor());
                int hole = (Math.round(getAlpha() * 255) << 24) | (NcmExpressivePalette.live().container() & 0xFFFFFF);
                canvas.drawCircle((float) cx, (float) cy, (float) (getWidth() * .12), SkijaGfx.fill(hole));
            }
        };
        host.addChild(shape);
        shape.setClickable(false).setBeforeRenderCallback(() -> {
            double size = Math.max(0, diameter.getAsDouble());
            shape.setBounds(size, size);
            shape.setPosition(centerX.getAsDouble() - size * .5, centerY.getAsDouble() - size * .5);
            shape.setColor(color.getAsInt());
        });
        return shape;
    }

    public static AbstractWidget<?> progress(AbstractWidget<?> host, DoubleSupplier x, DoubleSupplier y,
                                             DoubleSupplier width, DoubleSupplier progress, BooleanSupplier busy) {
        long started = System.nanoTime();
        Panel bar = new Panel() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                Canvas canvas = SkijaGfx.canvas();
                if (canvas == null || getWidth() <= 1 || getAlpha() <= 0) return;
                double time = busy.getAsBoolean() ? (System.nanoTime() - started) / 1_000_000_000.0 : 0;
                double amount = progress.getAsDouble();
                double from = amount < 0 ? (.5 + .5 * Math.sin(time * 1.8)) * .62 : 0;
                double to = amount < 0 ? Math.min(1, from + .38) : Math.max(0, Math.min(1, amount));
                int opacity = Math.round(getAlpha() * 255) << 24;
                NcmExpressivePalette palette = NcmExpressivePalette.live();
                canvas.drawLine((float) getX(), (float) (getY() + 4), (float) (getX() + getWidth()),
                        (float) (getY() + 4), SkijaGfx.stroke(opacity | (palette.container() & 0xFFFFFF), 4));
                if (to <= from) return;
                try (PathBuilder builder = new PathBuilder()) {
                    double length = (to - from) * getWidth();
                    int points = Math.max(2, (int) Math.ceil(length / 2));
                    for (int i = 0; i <= points; i++) {
                        double dx = from * getWidth() + length * i / points;
                        double dy = busy.getAsBoolean() ? Math.sin(dx * Math.PI / 12 - time * 4) * 2 : 0;
                        float px = (float) (getX() + dx), py = (float) (getY() + 4 + dy);
                        if (i == 0) builder.moveTo(px, py); else builder.lineTo(px, py);
                    }
                    try (Path path = builder.detach()) {
                        canvas.drawPath(path, SkijaGfx.stroke(opacity | (palette.primary() & 0xFFFFFF), 3));
                    }
                }
            }
        };
        host.addChild(bar);
        bar.setClickable(false).setBeforeRenderCallback(() -> {
            bar.setBounds(Math.max(0, width.getAsDouble()), 8);
            bar.setPosition(x.getAsDouble(), y.getAsDouble());
        });
        return bar;
    }

    public static int withAlpha(int color, double opacity) {
        return ((int) Math.round(Math.max(0, Math.min(1, opacity)) * 255) << 24) | (color & 0xFFFFFF);
    }

    /** 结果勾线从左下到右上描出，只在真实成功状态下由调用方显示。 */
    public static AbstractWidget<?> successMark(AbstractWidget<?> host, DoubleSupplier centerX,
                                               DoubleSupplier centerY, DoubleSupplier diameter,
                                               DoubleSupplier reveal, IntSupplier color) {
        Panel mark = new Panel() {
            @Override
            public void onRender(double mouseX, double mouseY) {
                Canvas canvas = SkijaGfx.canvas();
                if (canvas == null || getWidth() <= 1 || getAlpha() <= 0) return;
                double progress = Math.max(0, Math.min(1, reveal.getAsDouble()));
                double size = getWidth(), x = getX(), y = getY();
                double first = Math.min(1, progress / .38);
                double second = Math.max(0, (progress - .38) / .62);
                try (PathBuilder path = new PathBuilder()) {
                    path.moveTo((float) (x + size * .20), (float) (y + size * .51));
                    path.lineTo((float) (x + size * (.20 + .22 * first)), (float) (y + size * (.51 + .21 * first)));
                    if (second > 0) path.lineTo((float) (x + size * (.42 + .39 * second)), (float) (y + size * (.72 - .44 * second)));
                    try (Path stroke = path.detach()) {
                        canvas.drawPath(stroke, SkijaGfx.stroke(withAlpha(color.getAsInt(), getAlpha()), Math.max(2, size * .065)));
                    }
                }
            }
        };
        host.addChild(mark);
        mark.setClickable(false).setBeforeRenderCallback(() -> {
            double size = Math.max(0, diameter.getAsDouble());
            mark.setBounds(size, size);
            mark.setPosition(centerX.getAsDouble() - size * .5, centerY.getAsDouble() - size * .5);
        });
        return mark;
    }

    private static void scallop(Canvas canvas, double cx, double cy, double radius, int lobes,
                                 double angle, double amplitude, int color) {
        try (PathBuilder builder = new PathBuilder()) {
            for (int i = 0; i <= 144; i++) {
                double a = i * Math.PI * 2 / 144;
                double r = radius * (1 - amplitude + amplitude * Math.cos(a * lobes));
                float x = (float) (cx + Math.cos(a + angle) * r), y = (float) (cy + Math.sin(a + angle) * r);
                if (i == 0) builder.moveTo(x, y); else builder.lineTo(x, y);
            }
            builder.closePath();
            try (Path path = builder.detach()) { canvas.drawPath(path, SkijaGfx.fill(color)); }
        }
    }

    public static void rule(AbstractWidget<?> host, DoubleSupplier x, DoubleSupplier y,
                            DoubleSupplier width, IntSupplier color) {
        RectWidget line = new RectWidget();
        host.addChild(line);
        line.setClickable(false).setBeforeRenderCallback(() -> {
            line.setBounds(Math.max(1, width.getAsDouble()), 1);
            line.setPosition(x.getAsDouble(), y.getAsDouble());
            line.setColor(color.getAsInt());
            line.setAlpha(.72f);
        });
    }

    public static void status(AbstractWidget<?> host, Supplier<String> glyph, Supplier<String> message,
                              DoubleSupplier x, DoubleSupplier y, DoubleSupplier width,
                              IntSupplier semanticColor) {
        final double height = 42;
        IntSupplier surfaceColor = () -> NcmExpressivePalette.mix(
                NcmExpressivePalette.live().surface(), semanticColor.getAsInt(), .10);
        surface(host, x, y, width, () -> height, 14, surfaceColor);
        icon(host, glyph, NcmSheet.ICON_S, () -> x.getAsDouble() + 17,
                () -> y.getAsDouble() + height * .5,
                () -> NcmExpressivePalette.readableColor(semanticColor.getAsInt(), surfaceColor.getAsInt()));
        label(host, message, NcmExpressive::captionFont, () -> x.getAsDouble() + 32,
                () -> y.getAsDouble() + height * .5, () -> Math.max(1, width.getAsDouble() - 44),
                () -> NcmExpressivePalette.readableColor(NcmExpressivePalette.live().muted(), surfaceColor.getAsInt()));
    }
}
