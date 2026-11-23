package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import static com.muoniumplayer.configskija.ui.settings.SettingsSchema.*;

/** Visualizers share input contracts, never host music business logic. All values are read live. */
final class SettingsControls {
    final SettingsPage page;
    final Map<String, ListControl> lists = new HashMap<>();
    private final Map<String, Double> confirming = new HashMap<>(), lastTick = new HashMap<>(), tickValues = new HashMap<>();
    private final Map<String, double[]> ranges = new HashMap<>();
    private final Map<String, Pulse> pulses = new HashMap<>();
    private final Map<String, ColorClock> colorClocks = new HashMap<>();
    private static final class ColorClock { Object seed; ColorModel model; double start; }
    private static final class Pulse { boolean selected; double start, returning = Double.NaN, last = 1; }
    private final Set<String> fileDialogs = new HashSet<>();
    private final Map<String, Rect> listBounds = new LinkedHashMap<>();
    private KeybindCapture capture;
    private ChoiceMenu choiceMenu;
    private String choiceId = "";
    private long revision;
    SettingsControls(SettingsPage page) { this.page = page; }
    static boolean list(Visualizer v) { return switch(v) { case TEXT_LIST, FILE_LIST, COLOR_LIST, NUMBER_LIST, SLIDER_LIST, RANGE_SLIDER_LIST, RANGE_NUMBER_LIST, NUMBER_CHAIN, DRAGGABLE_LIST -> true; default -> false; }; }
    double width(PageContext ctx, ConfigHost.Option option, OptionStyle style, double available) {
        double width = switch(style.visualizer) {
            case SWITCH -> ctx.painter().measure(Boolean.TRUE.equals(option.value()) ? "On" : "Off", 12, false) + 50;
            case CHECKBOX -> ctx.painter().measure(Boolean.TRUE.equals(option.value()) ? "On" : "Off", 12, false) + 32;
            case SLIDER -> 403; case RANGE_SLIDER -> 476; case INHERITABLE_SLIDER -> 300 + 16 + 80 + 16 + ctx.painter().measure(style.inheritLabel, 12, false) + 20;
            case NUMBER -> 87; case COLOR -> colorWidth(ctx, option, style);
            case BUTTON -> Math.min(300, Math.max(ctx.painter().measure(style.buttonText, 13, false), style.confirmText == null ? 0 : ctx.painter().measure(style.confirmText, 13, false)) + 32);
            case KEYBIND -> Math.min(300, ctx.painter().measure(option.value() instanceof Binding b ? b.displayName() : String.valueOf(option.value()), 13, false) + 48);
            case RADIO -> Math.min(300, option.choices().stream().mapToDouble(s -> ctx.painter().measure(s, 12, false) + 16).sum() + 6);
            case HUD_RADIO -> option.choices().size() * 36; case HUD_DROPDOWN -> 175; case ALIGNMENT -> 125;
            default -> 300;
        };
        return Math.min(available, Math.max(1, width));
    }
    double height(PageContext ctx, ConfigHost.Option option, OptionStyle style, double width) {
        if (list(style.visualizer)) return listFor(option).height(option, style);
        return switch(style.visualizer) {
            case SWITCH -> 21; case CHECKBOX -> 24; case NUMBER -> 26; case SLIDER, RANGE_SLIDER, INHERITABLE_SLIDER -> 26;
            case TEXT -> style.multiline ? multilineHeight(ctx, option, style, width) : page.schema.lineHeight(ctx, 14, false) + 14;
            case FILE, KEYBIND -> page.schema.lineHeight(ctx, 14, false) + 14;
            case RADIO -> 38; case HUD_RADIO -> 36; case HUD_DROPDOWN -> 34; case ALIGNMENT -> 125;
            case COLOR, BUTTON -> page.schema.lineHeight(ctx, 13, false) + 12;
            case INFO -> infoHeight(ctx, option, width);
            default -> 32;
        };
    }
    private double multilineHeight(PageContext ctx, ConfigHost.Option option, OptionStyle style, double available) { TextEdit probe = new TextEdit(); probe.limit(Integer.MAX_VALUE); probe.multiline(true); probe.text(page.fieldText("settings.text." + option.id(), Objects.toString(option.value(), ""))); double width = Math.max(1, width(ctx, option, style, available) - 54), lineHeight = page.schema.lineHeight(ctx, 14, false); return Math.max(96, TextInputController.layout(probe, width, 14, lineHeight, true, text -> ctx.painter().measure(text, 14, false)).height() + 14); }
    void render(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        if (list(style.visualizer)) { listBounds.put(option.id(), r); listFor(option).render(ctx, option, style, r, alpha); return; }
        switch(style.visualizer) {
            case SWITCH, CHECKBOX -> toggle(ctx, option, style, r, checked -> page.write(ctx, option, checked));
            case SLIDER, INHERITABLE_SLIDER -> sliderOption(ctx, option, style, r, alpha);
            case RANGE_SLIDER, RANGE_NUMBER -> range(ctx, option, style, r, alpha);
            case NUMBER -> spinner(ctx, option, option.id(), r, numeric(option.value(), min(option)), min(option), max(option), ControlMath.spinnerStep(step(option), min(option), max(option)), style, value -> page.write(ctx, option, ControlMath.numberLike(value, option.value(), false)), alpha);
            case DROPDOWN, MULTI_SELECT, HUD_DROPDOWN -> dropdown(ctx, option, style, r, alpha);
            case RADIO, HUD_RADIO -> radio(ctx, option, style, r, alpha);
            case COLOR -> color(ctx, option, style, r, alpha);
            case BUTTON -> button(ctx, option, style, r, alpha);
            case TEXT, FILE -> text(ctx, option, style, r, alpha);
            case KEYBIND -> keybind(ctx, option, style, r, alpha);
            case ALIGNMENT -> alignment(ctx, option, style, r, alpha);
            case INFO -> info(ctx, option, style, r, alpha);
            default -> throw new IllegalStateException("Missing visualizer: " + style.visualizer);
        }
    }
    void toggle(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, Consumer<Boolean> changed) {
        boolean checked = Boolean.TRUE.equals(option.value()), checkbox = style.visualizer == Visualizer.CHECKBOX;
        double alpha = 1, size = checkbox ? 24 : 21, width = checkbox ? 24 : 42;
        String label = checked ? "On" : "Off"; ctx.painter().text(label, r.x(), r.y() + (r.h() - 12) / 2, 12, false, SettingsDraw.opacity(ctx.theme().secondary(), alpha));
        Rect control = new Rect(r.right() - width, r.y() + (r.h() - size) / 2, width, size);
        double selected = page.spring(ctx, "settings.switch." + option.id(), checked ? 1 : 0);
        int bg = checkbox ? ctx.theme().component() : 0xff74777f;
        ctx.painter().rect(control, checkbox ? ctx.theme().radius(6) : ctx.theme().radius(100), SettingsDraw.opacity(bg, alpha));
        SettingsDraw.accent(ctx, page.schema, control, checkbox ? ctx.theme().radius(6) : ctx.theme().radius(100), selected * alpha);
        if (checkbox) { ctx.painter().outline(control.inset(.75), Math.max(0, ctx.theme().radius(6) - .75), 1.5, SettingsDraw.opacity(checked ? ctx.accent() : SettingsDraw.hover(ctx, control) ? ctx.theme().secondary() : ctx.theme().border(), alpha)); if (selected > .001) ctx.painter().icon("tick", control.x() - 1, control.y() - 1, 26, SettingsDraw.opacity(ctx.theme().text(), alpha * selected)); }
        else { double offset = 3 + 21 * selected; ctx.painter().rect(new Rect(control.x() + offset, control.y() + 3, 15, 15), ctx.theme().radius(100), SettingsDraw.opacity(ctx.theme().thumb(), alpha)); if (SettingsDraw.hover(ctx, control)) ctx.painter().outline(control.inset(.5), ctx.theme().radius(100), 1, SettingsDraw.opacity(ctx.theme().text(), .15 * alpha)); }
        page.optionHit(ctx, option, "toggle", control, () -> changed.accept(!Boolean.TRUE.equals(option.value())));
    }
    private static boolean inherited(Object value, Object sentinel) { return value == null || value instanceof Number number && sentinel instanceof Number marker && Double.compare(number.doubleValue(), marker.doubleValue()) == 0; }
    void sliderOption(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        Object value = option.value(); boolean inherited = style.visualizer == Visualizer.INHERITABLE_SLIDER && inherited(value, style.inheritSentinel);
        double shown = inherited ? style.inheritedValue.get() : numeric(value, min(option));
        double chipWidth = style.visualizer == Visualizer.INHERITABLE_SLIDER ? ctx.painter().measure(style.inheritLabel, 12, false) + 20 : 0;
        double spinnerWidth = chipWidth > 0 ? 80 : 87, trackWidth = Math.max(1, r.w() - 16 - spinnerWidth - (chipWidth > 0 ? 16 + chipWidth : 0));
        Consumer<Double> update = v -> page.write(ctx, option, ControlMath.numberLike(v, option.value(), false));
        track(ctx, option, option.id(), new Rect(r.x(), r.y() + (r.h() - 19) / 2, trackWidth, 19), shown, min(option), max(option), step(option), 19, 5, update, alpha);
        spinner(ctx, option, option.id(), new Rect(r.x() + trackWidth + 16, r.y(), spinnerWidth, 26), shown, min(option), max(option), step(option) > 0 ? step(option) : 1, style, update, alpha);
        if (chipWidth > 0) { Rect chip = new Rect(r.right() - chipWidth, r.y(), chipWidth, 24); SettingsDraw.frame(ctx, chip, 8, inherited ? SettingsDraw.over(Theme.alpha(ctx.accent(), .2), ctx.theme().component()) : ctx.theme().chip(), inherited ? ctx.accent() : ctx.theme().border()); SettingsDraw.center(ctx, style.inheritLabel, chip, 12, false, inherited ? ctx.accent() : ctx.theme().secondary()); page.optionHit(ctx, option, "inherit", chip, () -> { if (!inherited(option.value(), style.inheritSentinel)) page.write(ctx, option, style.inheritSentinel instanceof Number marker ? ControlMath.numberLike(marker.doubleValue(), option.value(), false) : null); }); }
    }
    void track(PageContext ctx, ConfigHost.Option option, String id, Rect r, double value, double min, double max, double step, double thumb, double track, Consumer<Double> update, double alpha) {
        double fraction = page.spring(ctx, "settings.slider." + id, ControlMath.fraction(value, min, max));
        OptionStyle presentation = page.schema.option(option); boolean inheritance = presentation.visualizer == Visualizer.INHERITABLE_SLIDER;
        double inheritMix = inheritance ? page.spring(ctx, "settings.inherited." + id, inherited(option.value(), presentation.inheritSentinel) ? 1 : 0) : 0;
        ctx.painter().rect(new Rect(r.x(), r.y() + (r.h() - track) / 2, r.w(), track), ctx.theme().radius(6), SettingsDraw.opacity(inheritance ? ctx.theme().chip() : 0xff74777f, alpha));
        Rect fill = new Rect(r.x(), r.y() + (r.h() - track) / 2, r.w() * Geometry.clamp(fraction, 0, 1), track);
        if (inheritance) ctx.painter().rect(fill, ctx.theme().radius(6), SettingsDraw.opacity(Theme.mix(ctx.accent(), Theme.alpha(ctx.theme().secondary(), .4), inheritMix), alpha));
        else SettingsDraw.accent(ctx, page.schema, fill, ctx.theme().radius(6), alpha);
        ctx.painter().rect(new Rect(r.x() + fraction * (r.w() - thumb), r.y() + (r.h() - thumb) / 2, thumb, thumb), ctx.theme().radius(100), SettingsDraw.opacity(ctx.theme().thumb(), alpha * (1 - inheritMix * .5)));
        page.optionHit(ctx, option, "slider." + id, r, () -> page.drag(ctx, option, (x, y) -> { double next = ControlMath.sliderValue(x - r.x(), r.w(), thumb, min, max, step); tick(ctx, id, next); update.accept(next); }));
    }
    void tick(PageContext ctx, String id, double value) { Double previous = tickValues.put(id, value); if (!Objects.equals(previous, value) && ctx.time() - lastTick.getOrDefault(id, Double.NEGATIVE_INFINITY) >= .07) { lastTick.put(id, ctx.time()); ctx.sound("SLIDER_TICK"); } }
    void spinner(PageContext ctx, ConfigHost.Option option, String id, Rect r, double value, double min, double max, double step, OptionStyle style, Consumer<Double> update, double alpha) { spinner(ctx, option, id, r, value, min, max, step, style, update, alpha, min, max, null); }
    void spinner(PageContext ctx, ConfigHost.Option option, String id, Rect r, double value, double min, double max, double step, OptionStyle style, Consumer<Double> update, double alpha, double arrowMin, double arrowMax, Runnable ended) {
        double arrowLo = Math.max(min, arrowMin), arrowHi = Math.max(arrowLo, Math.min(max, arrowMax));
        SettingsDraw.frame(ctx, r, 8, SettingsDraw.opacity(ctx.theme().component(), alpha), SettingsDraw.opacity(ctx.theme().border(), alpha));
        String shown = ControlMath.format(value);
        TextInputController.Style inputStyle = new TextInputController.Style(13); inputStyle.suffix = style.unit;
        page.field(ctx, option, "settings.number." + id, new Rect(r.x() + 8, r.y() + 5, Math.max(1, r.w() - 28), Math.max(1, r.h() - 10)), shown, "", ControlMath::filterNumber, input -> { Double n = ControlMath.parse(input, min, max); if (n != null) update.accept(n); }, ended == null ? null : text -> ended.run(), inputStyle);
        if (!page.fieldFocused("settings.number." + id) && style.format != null) {
            Rect label = new Rect(r.x() + 8, r.y() + 5, Math.max(1, r.w() - 28), Math.max(1, r.h() - 10)); ctx.painter().rect(label, 0, ctx.theme().component()); ctx.painter().text(SettingsDraw.trim(ctx.painter(), formatted(style, value) + style.unit, label.w(), 13, false), label.x(), label.y(), 13, false, ctx.theme().text());
        }
        Rect up = new Rect(r.right() - 20, r.y(), 18, 13), down = new Rect(up.x(), up.bottom(), 18, 13);
        SettingsDraw.chevron(ctx, up.x() + 9, up.y() + 6.5, 14, 0, SettingsDraw.opacity(ctx.theme().secondary(), alpha * (value < arrowHi ? 1 : .4)));
        SettingsDraw.chevron(ctx, down.x() + 9, down.y() + 6.5, 14, 180, SettingsDraw.opacity(ctx.theme().secondary(), alpha * (value > arrowLo ? 1 : .4)));
        if (value < arrowHi) page.optionHit(ctx, option, "increment." + id, up, () -> update.accept(Geometry.clamp(value + step, arrowLo, arrowHi)));
        if (value > arrowLo) page.optionHit(ctx, option, "decrement." + id, down, () -> update.accept(Geometry.clamp(value - step, arrowLo, arrowHi)));
    }
    void range(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        List<Object> current = ControlMath.elements(option.value()); if (current.size() < 2) return;
        double start = numeric(current.get(0), min(option)), end = numeric(current.get(1), max(option));
        double[] draft = ranges.computeIfAbsent(option.id(), id -> new double[] {start, end});
        // Preserve a crossed pair while typing, but always store an ordered pair.
        if (!Arrays.equals(new double[] {Math.min(draft[0], draft[1]), Math.max(draft[0], draft[1])}, new double[] {start, end})) { draft[0] = start; draft[1] = end; }
        Consumer<double[]> update = pair -> { draft[0] = pair[0]; draft[1] = pair[1]; page.write(ctx, option, ControlMath.containerLike(option.value(), ControlMath.ordered(pair[0], pair[1]), true)); };
        double trackWidth = style.visualizer == Visualizer.RANGE_SLIDER ? Math.max(1, r.w() - 176) : 0;
        if (trackWidth > 0) rangeTrack(ctx, option, option.id(), new Rect(r.x(), r.y() + 3.5, trackWidth, 19), draft[0], draft[1], 13, 16, 5, update, alpha);
        double w = trackWidth > 0 ? 80 : (r.w() - 20) / 2, x = trackWidth > 0 ? r.x() + trackWidth + 16 : r.x();
        spinner(ctx, option, option.id() + ".start", new Rect(x, r.y(), w, 26), draft[0], min(option), max(option), ControlMath.spinnerStep(step(option), min(option), max(option)), style, v -> update.accept(new double[] {v, draft[1]}), alpha, min(option), draft[0] <= draft[1] ? draft[1] : max(option), () -> { if (draft[0] > draft[1]) update.accept(new double[] {draft[1], draft[0]}); });
        if (trackWidth == 0) SettingsDraw.center(ctx, "-", new Rect(x + w, r.y(), 20, 26), 13, false, ctx.theme().secondary());
        spinner(ctx, option, option.id() + ".end", new Rect(x + w + (trackWidth > 0 ? 0 : 20), r.y(), w, 26), draft[1], min(option), max(option), ControlMath.spinnerStep(step(option), min(option), max(option)), style, v -> update.accept(new double[] {draft[0], v}), alpha, draft[0] <= draft[1] ? draft[0] : min(option), max(option), () -> { if (draft[0] > draft[1]) update.accept(new double[] {draft[1], draft[0]}); });
    }
    void rangeTrack(PageContext ctx, ConfigHost.Option option, String id, Rect r, double start, double end, double tw, double th, double track, Consumer<double[]> update, double alpha) {
        double lo = Math.min(start, end), hi = Math.max(start, end), usable = Math.max(0, r.w() - tw);
        double left = page.spring(ctx, "settings.range.lo." + id, ControlMath.fraction(lo, min(option), max(option))) * usable;
        double right = page.spring(ctx, "settings.range.hi." + id, ControlMath.fraction(hi, min(option), max(option))) * usable;
        ctx.painter().rect(new Rect(r.x(), r.y() + (r.h() - track) / 2, r.w(), track), ctx.theme().radius(6), SettingsDraw.opacity(ctx.theme().chip(), alpha));
        SettingsDraw.accent(ctx, page.schema, new Rect(r.x() + left + tw, r.y() + (r.h() - track) / 2, Math.max(0, right - left - tw), track), ctx.theme().radius(6), alpha);
        triangle(ctx, new Rect(r.x() + left, r.y(), tw, th), false, alpha); triangle(ctx, new Rect(r.x() + right, r.y(), tw, th), true, alpha);
        page.optionHit(ctx, option, "range." + id, r, () -> {
            double pressed = ControlMath.sliderValue(ctx.mouseX() - r.x(), r.w(), tw, min(option), max(option), step(option));
            Boolean[] endHandle = {lo == hi ? null : Math.abs(pressed - hi) <= Math.abs(pressed - lo)}; double[] pair = {lo, hi};
            page.drag(ctx, option, (x, y) -> { double v = ControlMath.sliderValue(x - r.x(), r.w(), tw, min(option), max(option), step(option)); if (endHandle[0] == null && v != lo) endHandle[0] = v > lo; if (endHandle[0] == null) return; if (endHandle[0]) pair[1] = Math.max(pair[0], v); else pair[0] = Math.min(pair[1], v); tick(ctx, id, v); update.accept(pair.clone()); });
        });
    }
    private double alignmentScale(PageContext ctx, String key, boolean selected) {
        Pulse pulse = pulses.computeIfAbsent(key, ignored -> new Pulse());
        if (selected && !pulse.selected) { pulse.start = ctx.time(); pulse.returning = Double.NaN; }
        pulse.selected = selected; if (!selected) return pulse.last;
        double elapsed = Math.max(0, ctx.time() - pulse.start), omega = Math.sqrt(1500), damping = .5 * omega, frequency = omega * Math.sqrt(.75), displacement = -.15, c = damping * displacement / frequency;
        if (Double.isNaN(pulse.returning)) {
            double cos = Math.cos(frequency * elapsed), sin = Math.sin(frequency * elapsed), decay = Math.exp(-damping * elapsed), offset = decay * (displacement * cos + c * sin);
            double speed = decay * (-damping * (displacement * cos + c * sin) + frequency * (-displacement * sin + c * cos));
            pulse.last = 1.15 + offset;
            if (Math.abs(offset) < .01 && Math.abs(speed) < .625) { pulse.returning = ctx.time(); pulse.last = 1.15; }
        } else pulse.last = 1.15 - .15 * Motion.ease(Motion.Easing.FAST_OUT_SLOW_IN, (ctx.time() - pulse.returning) / .15);
        return pulse.last;
    }
    private void triangle(PageContext ctx, Rect r, boolean left, double alpha) {
        double[] vertices = left ? new double[] {r.right(), r.y(), r.x(), r.y() + r.h() / 2, r.right(), r.bottom()}
                                 : new double[] {r.x(), r.y(), r.right(), r.y() + r.h() / 2, r.x(), r.bottom()};
        ctx.painter().polygon(vertices, 2, SettingsDraw.opacity(ctx.theme().thumb(), alpha));
    }

    void dropdown(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        boolean opened = choiceMenu != null && !choiceMenu.closed() && choiceId.equals(option.id());
        double on = page.spring(ctx, "settings.dropdown.open." + option.id(), opened ? 1 : 0), hovered = page.spring(ctx, "settings.dropdown.hover." + option.id(), opened || SettingsDraw.hover(ctx, r) ? 1 : 0);
        if (style.visualizer == Visualizer.HUD_DROPDOWN) { ctx.painter().rect(r, 8, SettingsDraw.opacity(0xff192126, alpha)); ctx.painter().outline(r.inset(.5), 7.5, 1, SettingsDraw.opacity(ctx.theme().border(), alpha)); }
        else SettingsDraw.frame(ctx, r, 8, SettingsDraw.opacity(SettingsDraw.over(Theme.alpha(ctx.accent(), .2 * on), ctx.theme().component()), alpha), SettingsDraw.opacity(Theme.mix(ctx.theme().border(), ctx.accent(), on), alpha));
        String label = choiceLabel(option, style); int text = SettingsDraw.opacity(Theme.mix(ctx.theme().secondary(), ctx.theme().text(), hovered), alpha);
        ctx.painter().text(SettingsDraw.trim(ctx.painter(), label, r.w() - 44, style.visualizer == Visualizer.HUD_DROPDOWN ? 12 : 13, false), r.x() + 12, r.y() + (r.h() - 13) / 2, style.visualizer == Visualizer.HUD_DROPDOWN ? 12 : 13, false, text);
        SettingsDraw.chevron(ctx, r.right() - 18, r.y() + r.h() / 2, 12, page.spring(ctx, "settings.dropdown.arrow." + option.id(), opened ? 0 : 180), text);
        page.optionHit(ctx, option, "choice", r, () -> { choiceId = option.id(); choiceMenu = new ChoiceMenu(page, option, style, r); ctx.overlay(choiceMenu); });
    }
    static String choiceLabel(ConfigHost.Option option, OptionStyle style) {
        if (style.visualizer == Visualizer.MULTI_SELECT && style.checkable) { List<Object> flags = ControlMath.elements(option.value()); long count = flags.stream().filter(Boolean.TRUE::equals).count(); return count == 0 ? "None selected" : count == option.choices().size() ? "All selected" : count + " / " + option.choices().size() + " selected"; }
        int index = selectedIndex(option, style); return index >= 0 && index < option.choices().size() ? option.choices().get(index) : "—";
    }
    static int selectedIndex(ConfigHost.Option option, OptionStyle style) { Object value = option.value(); if (!style.optionValues.isEmpty()) return style.optionValues.indexOf(value); if (value instanceof Number n) return n.intValue(); if (value instanceof Enum<?> e) return e.ordinal(); return option.choices().indexOf(String.valueOf(value)); }
    static Object choiceValue(ConfigHost.Option option, OptionStyle style, int index) { if (index < style.optionValues.size()) return style.optionValues.get(index); Object value = option.value(); if (value instanceof Enum<?> e) return e.getDeclaringClass().getEnumConstants()[index]; return value instanceof Number ? index : option.choices().get(index); }
    void radio(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        boolean hud = style.visualizer == Visualizer.HUD_RADIO;
        if (hud) { ctx.painter().rect(r, 6, SettingsDraw.opacity(0xb3192126, alpha)); ctx.painter().outline(r.inset(.5), 5.5, 1, SettingsDraw.opacity(ctx.theme().border(), alpha)); }
        else SettingsDraw.frame(ctx, r, 8, SettingsDraw.opacity(ctx.theme().component(), alpha), SettingsDraw.opacity(ctx.theme().border(), alpha)); int selected = selectedIndex(option, style); double x = r.x() + (hud ? 0 : 3), available = r.w() - (hud ? 0 : 6);
        double total = option.choices().stream().mapToDouble(s -> hud ? 36 : ctx.painter().measure(s, 12, false) + 16).sum();
        for (int i = 0; i < option.choices().size(); i++) { String label = option.choices().get(i); double w = (hud ? 36 : ctx.painter().measure(label, 12, false) + 16) * Math.min(1, available / Math.max(1, total)); Rect item = new Rect(x, r.y() + (hud ? 0 : 3), w, hud ? 36 : 32); int index = i;
            if (i == selected && !hud) { double offset = page.spring(ctx, "settings.radio.x." + option.id(), x - r.x()), width = page.spring(ctx, "settings.radio.w." + option.id(), w); ctx.painter().rect(new Rect(r.x() + offset, item.y(), width, item.h()), ctx.theme().radius(hud ? 6 : 5), SettingsDraw.opacity(ctx.accent(), alpha)); }
            int color = SettingsDraw.opacity(i == selected || SettingsDraw.hover(ctx, item) ? ctx.theme().text() : ctx.theme().secondary(), alpha);
            if (hud && i < style.optionIcons.size()) ctx.painter().icon(style.optionIcons.get(i), item.x() + (w - 18) / 2, item.y() + 9, 18, color); else SettingsDraw.center(ctx, label, item, 12, false, color);
            page.optionHit(ctx, option, "radio." + i, item, () -> page.write(ctx, option, choiceValue(option, style, index))); x += w;
        }
    }
    private double colorWidth(PageContext ctx, ConfigHost.Option option, OptionStyle style) { int argb = colorArgb(option.value()); String label = "#" + ColorModel.hex(argb); if (option.value() instanceof ColorValue c && style.gradient && c.gradient() && !c.chroma()) label += " → #" + ColorModel.hex(c.gradientArgb()); return ctx.painter().measure(label, 13, false) + 50; }
    static int colorArgb(Object value) { return value instanceof ColorValue c ? c.argb() : value instanceof java.awt.Color c ? c.getRGB() : value instanceof Number n ? n.intValue() : -1; }
    void color(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        int argb = colorArgb(option.value());
        if (option.value() instanceof ColorValue animated && animated.chroma()) { ColorClock clock = colorClocks.computeIfAbsent(option.id(), ignored -> new ColorClock()); if (!Objects.equals(clock.seed, animated)) { clock.seed = animated; clock.model = new ColorModel(animated.argb(), style.alpha); clock.start = ctx.time(); } argb = ColorModel.hsv(clock.model.hue + (ctx.time() - clock.start) * 360 / page.schema.chromaCycleSeconds() * animated.speed(), clock.model.saturation, clock.model.brightness, clock.model.alpha); }
        boolean gradient = option.value() instanceof ColorValue c && style.gradient && c.gradient() && !c.chroma();
        if (gradient) { ColorValue c = (ColorValue)option.value(); ctx.painter().save(); ctx.painter().clip(r, ctx.theme().radius(8)); ctx.painter().gradient(r, argb, c.gradientArgb(), c.gradientArgb(), argb); ctx.painter().restore(); }
        else ctx.painter().rect(r, ctx.theme().radius(8), SettingsDraw.opacity(argb, alpha));
        ctx.painter().outline(r, ctx.theme().radius(8), 1, SettingsDraw.hover(ctx, r) ? SettingsDraw.opacity(ctx.theme().text(), .3 * alpha) : SettingsDraw.opacity(ctx.theme().border(), alpha));
        int text = SettingsDraw.opacity(ColorModel.luminance(argb) > .6 ? 0xff000000 : 0xffffffff, alpha); ctx.painter().icon("paintbrush", r.x() + 12, r.y() + (r.h() - 14) / 2, 14, text);
        String label = "#" + ColorModel.hex(argb) + (gradient ? " → #" + ColorModel.hex(((ColorValue)option.value()).gradientArgb()) : "");
        ctx.painter().text(label, r.x() + 38, r.y() + (r.h() - 13) / 2, 13, false, text);
        page.optionHit(ctx, option, "color", r, () -> ctx.overlay(new ColorPicker(page, option, style, r)));
    }
    void button(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        Double first = confirming.get(option.id()); boolean confirm = first != null; int fill = confirm ? 0xffe35b5b : ctx.accent(); if (SettingsDraw.hover(ctx, r)) fill = SettingsDraw.opacity(fill, .75);
        SettingsDraw.frame(ctx, r, 8, SettingsDraw.opacity(fill, alpha), 0); SettingsDraw.center(ctx, confirm ? style.confirmText : style.buttonText, r, 13, false, SettingsDraw.opacity(ctx.theme().accentText(), alpha));
        page.optionHit(ctx, option, "button", r, () -> { if (style.confirmText != null) { Double since = confirming.get(option.id()); if (since == null) { confirming.put(option.id(), ctx.time()); return; } if (ctx.time() - since < .4) return; confirming.remove(option.id()); } page.write(ctx, option, ""); });
    }
    void text(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        String value = Objects.toString(option.value(), ""), draft = page.fieldText("settings.text." + option.id(), value); boolean valid = valid(style.regex, draft); SettingsDraw.frame(ctx, r, 8, SettingsDraw.opacity(ctx.theme().card(), alpha), SettingsDraw.opacity(valid ? ctx.theme().border() : 0xffff4444, alpha));
        boolean file = style.visualizer == Visualizer.FILE; double reserved = file ? value.isEmpty() ? 30 : 52 : 42;
        if (!file) ctx.painter().icon("text-input", r.x() + 12, r.y() + 7, 18, SettingsDraw.opacity(ctx.theme().secondary(), alpha));
        page.field(ctx, option, "settings.text." + option.id(), new Rect(r.x() + (file ? 12 : 42), r.y() + 7, Math.max(1, r.w() - (file ? 12 : 42) - (file ? reserved : 12)), r.h() - 14), value, style.placeholder, UnaryOperator.identity(), s -> { if (file || valid(style.regex, s)) page.write(ctx, option, s); });
        if (file) { Rect folder = new Rect(r.right() - 24, r.y() + (r.h() - 18) / 2, 18, 18); ctx.painter().icon("folder", folder.x(), folder.y(), 18, ctx.theme().secondary()); page.optionHit(ctx, option, "file", folder, () -> chooseFile(ctx, option, style, value, s -> page.write(ctx, option, s))); if (!value.isEmpty()) { Rect clear = new Rect(folder.x() - 22, r.y() + (r.h() - 16) / 2, 16, 16); ctx.painter().icon("close", clear.x(), clear.y(), 16, ctx.theme().secondary()); page.optionHit(ctx, option, "file.clear", clear, () -> page.write(ctx, option, "")); } }
    }
    static boolean valid(String regex, String text) { if (regex == null || regex.isBlank() || text.isEmpty()) return true; try { return Pattern.matches(regex, text); } catch (java.util.regex.PatternSyntaxException e) { return true; } }
    void chooseFile(PageContext ctx, ConfigHost.Option option, OptionStyle style, String current, Consumer<String> selected) {
        if (!fileDialogs.add(option.id())) return; long ticket = revision;
        boolean supported;
        try { supported = page.schema.chooseFile(option, style, current, value -> { fileDialogs.remove(option.id()); if (value != null && ticket == revision && page.available(option)) selected.accept(value); }); }
        catch (RuntimeException error) { fileDialogs.remove(option.id()); throw error; }
        if (!supported) { fileDialogs.remove(option.id()); ctx.toast("Native file selection requires SettingsSchema.chooseFile."); }
    }
    double infoHeight(PageContext ctx, ConfigHost.Option option, double width) { double h = SettingsDraw.wrap(ctx.painter(), option.title(), width - 34, 16, true).size() * 19; if (option.description() != null && !option.description().isBlank()) h += 2 + SettingsDraw.wrap(ctx.painter(), option.description(), width - 34, 13, false).size() * 16; return Math.max(24, h); }
    void info(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        int color = switch(style.infoType) { case "success" -> 0xff239a60; case "warning" -> 0xffffab1d; case "error", "danger" -> 0xffff4444; default -> ctx.accent(); };
        String icon = switch(style.infoType) { case "success" -> "check-circle"; case "warning" -> "alert-circle"; case "error", "danger" -> "x-circle"; default -> "info"; };
        ctx.painter().icon(icon, r.x(), r.y(), 24, SettingsDraw.opacity(color, alpha)); double y = r.y(); for (String line : SettingsDraw.wrap(ctx.painter(), option.title(), r.w() - 34, 16, true)) { ctx.painter().text(line, r.x() + 34, y, 16, true, SettingsDraw.opacity(ctx.theme().text(), alpha)); y += 19; } if (option.description() != null) for (String line : SettingsDraw.wrap(ctx.painter(), option.description(), r.w() - 34, 13, false)) { ctx.painter().text(line, r.x() + 34, y + 2, 13, false, SettingsDraw.opacity(ctx.theme().secondary(), alpha)); y += 16; }
    }
    void alignment(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        ctx.painter().rect(r, 8, SettingsDraw.opacity(0xb3192126, alpha)); ctx.painter().outline(r.inset(.5), 7.5, 1, SettingsDraw.opacity(ctx.theme().border(), alpha)); int selected = selectedIndex(option, style); double cell = 125d / 3;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) { int index = row * 3 + col; Rect box = new Rect(r.x() + col * cell, r.y() + row * cell, cell, cell); boolean hover = SettingsDraw.hover(ctx, box), on = index == selected;
            double a = ctx.animate("settings.align.hover." + option.id() + index, hover && !on ? .08 : 0, .15, Motion.Easing.FAST_OUT_SLOW_IN); ctx.painter().rect(box, 0, SettingsDraw.opacity(ctx.theme().text(), a * alpha));
            int color = SettingsDraw.opacity(on ? ctx.accent() : hover ? ctx.theme().text() : ctx.theme().secondary(), alpha); double scale = alignmentScale(ctx, option.id() + "." + index, on), stroke = 1.5 * scale; double cx = box.x() + cell / 2, cy = box.y() + cell / 2, len = 8 * scale;
            if (row == 1 && col == 1) { ctx.painter().line(cx - len * .8, cy, cx + len * .8, cy, stroke * 1.2, color); ctx.painter().line(cx, cy - len * .8, cx, cy + len * .8, stroke * 1.2, color); ctx.painter().outline(new Rect(cx - 9.92 * scale, cy - 9.92 * scale, 19.84 * scale, 19.84 * scale), 6, 1.2, SettingsDraw.opacity(color, .13)); }
            else if (row != 1 && col != 1) { double px = box.x() + (col == 0 ? cell * .22 : cell * .78), py = box.y() + (row == 0 ? cell * .22 : cell * .78); ctx.painter().line(px, py, px + (col == 0 ? len : -len), py, stroke, color); ctx.painter().line(px, py, px, py + (row == 0 ? len : -len), stroke, color); }
            else if (row == 1) { double tip = cx + (col == 0 ? len * .5 : -len * .5), base = cx + (col == 0 ? -len * .3 : len * .3); ctx.painter().line(base, cy, tip, cy, stroke, color); ctx.painter().line(tip, cy - len * .5, tip, cy + len * .5, stroke, color); }
            else { double tip = cy + (row == 0 ? len * .5 : -len * .5), base = cy + (row == 0 ? -len * .3 : len * .3); ctx.painter().line(cx, base, cx, tip, stroke, color); ctx.painter().line(cx - len * .5, tip, cx + len * .5, tip, stroke, color); }
            page.optionHit(ctx, option, "alignment." + index, box, () -> page.write(ctx, option, choiceValue(option, style, index)));
        }
    }
    void keybind(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect r, double alpha) {
        boolean recording = capture != null && capture.option == option, conflict = page.schema.bindingConflict(option); int color = recording ? ctx.accent() : conflict ? 0xffe0524f : ctx.theme().text();
        SettingsDraw.frame(ctx, r, 8, recording ? SettingsDraw.over(Theme.alpha(ctx.accent(), .2), ctx.theme().component()) : ctx.theme().component(), recording || conflict ? color : ctx.theme().border()); ctx.painter().icon("keyboard", r.x() + 12, r.y() + 7, 16, color);
        String text = recording ? capture.display(page.schema) : option.value() instanceof Binding b ? b.displayName() : Objects.toString(option.value(), "None");
        ctx.painter().text(text, r.x() + 36, r.y() + 7, 13, false, SettingsDraw.opacity(color, alpha)); page.optionHit(ctx, option, "keybind", r, () -> { if (capture == null) capture = new KeybindCapture(page, option, style.singleKey); });
    }
    private ListControl listFor(ConfigHost.Option option) { return lists.computeIfAbsent(option.id(), id -> new ListControl(this)); }
    double min(ConfigHost.Option option) { OptionStyle style = page.schema.option(option); return style.minimum == null ? option.min() : style.minimum.getAsDouble(); }
    double max(ConfigHost.Option option) { OptionStyle style = page.schema.option(option); return style.maximum == null ? option.max() : style.maximum.getAsDouble(); }
    double step(ConfigHost.Option option) { OptionStyle style = page.schema.option(option); return style.step == null ? option.step() : style.step.getAsDouble(); }
    static String formatted(OptionStyle style, double value) { if (style.format != null) try { return style.format.apply(value); } catch (RuntimeException ignored) { /* Original valueFormat falls back on formatter errors. */ } return ControlMath.format(value); }
    static double numeric(Object value, double fallback) { return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : fallback; }
    boolean pointerDown(PageContext ctx, int button) { return capture != null && capture.mouse(ctx, button, true); }
    void pointerUp(PageContext ctx, int button) { if (capture != null) capture.mouse(ctx, button, false); }
    boolean rawKey(PageContext ctx, int code, boolean pressed) { return capture != null && capture.raw(ctx, code, pressed); }
    boolean key(PageContext ctx, ConfigView.Key key, String typed, boolean control, boolean shift, boolean alt) { if (capture == null) return false; if (key == ConfigView.Key.ESCAPE || key == ConfigView.Key.DELETE || key == ConfigView.Key.BACKSPACE) capture.unbind(ctx); else if (ctx.nativeKeyCode() > 0) capture.raw(ctx, ctx.nativeKeyCode(), true); return true; }
    void beginFrame() { listBounds.clear(); }
    void cancelDrags() { lists.values().forEach(ListControl::cancelDrag); }
    boolean wheel(PageContext ctx, double delta) { for (Map.Entry<String, Rect> entry : listBounds.entrySet()) if (entry.getValue().contains(ctx.mouseX(), ctx.mouseY()) && lists.containsKey(entry.getKey()) && lists.get(entry.getKey()).wheel(delta)) return true; return false; }
    void routeChanged(PageContext ctx) { capture = null; choiceMenu = null; cancelDrags(); fileDialogs.clear(); listBounds.clear(); confirming.clear(); revision++; }
    boolean recording() { return capture != null; }
    void stopCapture() { capture = null; }
}
