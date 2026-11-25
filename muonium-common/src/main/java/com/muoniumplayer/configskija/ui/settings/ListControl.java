package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;
import java.util.function.*;
import static com.muoniumplayer.configskija.ui.settings.SettingsSchema.*;

/** Shared list chrome plus all text/file/color/number/range/chain/draggable row variants. */
final class ListControl {
    private final SettingsControls controls; private final SettingsPage page;
    private StableList<Object> state; private List<Object> lastStored = List.of(); private final Set<Long> checked = new HashSet<>();
    private double scroll, maxScroll, dragAccum; private int dragging = -1, target = -1;
    private final Map<Long, double[]> ranges = new HashMap<>();
    ListControl(SettingsControls controls) { this.controls = controls; this.page = controls.page; }
    private boolean chain(OptionStyle s) { return s.visualizer == Visualizer.NUMBER_CHAIN; }
    private boolean draggable(OptionStyle s) { return s.visualizer == Visualizer.DRAGGABLE_LIST; }
    private double rowHeight(OptionStyle s) { return chain(s) ? 36 : draggable(s) ? s.checkable ? 40 : 32 : 34; }
    private double gap(OptionStyle s) { return draggable(s) ? 14 : 8; }
    private double padding(OptionStyle s) { return chain(s) ? 12 : draggable(s) ? 15 : 8; }
    private List<Object> decode(ConfigHost.Option option, OptionStyle s) {
        List<Object> values = ControlMath.elements(option.value());
        if (s.visualizer == Visualizer.RANGE_SLIDER_LIST || s.visualizer == Visualizer.RANGE_NUMBER_LIST) { List<Object> pairs = new ArrayList<>(); for (int i = 0; i < values.size(); i += 2) pairs.add(List.of(SettingsControls.numeric(values.get(i), controls.min(option)), i + 1 < values.size() ? SettingsControls.numeric(values.get(i + 1), controls.max(option)) : controls.max(option))); return pairs; }
        if (s.visualizer == Visualizer.TEXT_LIST || s.visualizer == Visualizer.FILE_LIST || s.visualizer == Visualizer.DRAGGABLE_LIST) return values.stream().map(v -> (Object)Objects.toString(v, "")).toList();
        if (s.visualizer == Visualizer.COLOR_LIST) return values.stream().map(v -> (Object)SettingsControls.colorArgb(v)).toList();
        if (s.visualizer == Visualizer.NUMBER_LIST || s.visualizer == Visualizer.SLIDER_LIST || s.visualizer == Visualizer.NUMBER_CHAIN) return values.stream().map(v -> (Object)SettingsControls.numeric(v, controls.min(option))).toList();
        return values;
    }
    private void sync(ConfigHost.Option option, OptionStyle s) {
        List<Object> values = decode(option, s);
        if (state == null) {
            if (draggable(s)) { List<Object> all = new ArrayList<>(values); if (s.checkable) { for (String choice : option.choices()) if (!values.contains(choice)) all.add(choice); } else if (all.isEmpty()) all.addAll(option.choices()); state = new StableList<>(all); for (StableList.Entry<Object> entry : state.entries()) if (values.contains(entry.value())) checked.add(entry.id()); }
            else state = new StableList<>(values);
            lastStored = List.copyOf(values); return;
        }
        if (!values.equals(lastStored) && dragging < 0) { if (!draggable(s)) state.replace(values); else { List<Object> all = new ArrayList<>(values); if (s.checkable) for (String choice : option.choices()) if (!values.contains(choice)) all.add(choice); state.replace(all); checked.clear(); for (StableList.Entry<Object> entry : state.entries()) if (values.contains(entry.value())) checked.add(entry.id()); } lastStored = List.copyOf(values); }
    }
    double height(ConfigHost.Option option, OptionStyle style) { sync(option, style); int n = state.entries().size(); double content = n * rowHeight(style) + Math.max(0, n - 1) * gap(style), body = chain(style) ? content : Math.min(280, content); boolean add = !draggable(style) && (style.maxEntries <= 0 || n < style.maxEntries); return padding(style) * 2 + body + (add ? (n > 0 ? gap(style) : 0) + rowHeight(style) : 0); }
    void render(PageContext ctx, ConfigHost.Option option, OptionStyle style, Rect bounds, double alpha) {
        sync(option, style); double pad = padding(style), rh = rowHeight(style), gap = gap(style); List<StableList.Entry<Object>> entries = state.entries();
        SettingsDraw.frame(ctx, bounds, draggable(style) || chain(style) ? 8 : 16, SettingsDraw.opacity(ctx.theme().component(), alpha), SettingsDraw.opacity(ctx.theme().border(), alpha));
        double contentHeight = entries.size() * rh + Math.max(0, entries.size() - 1) * gap, bodyHeight = chain(style) ? contentHeight : Math.min(280, contentHeight);
        Rect viewport = new Rect(bounds.x() + pad, bounds.y() + pad, bounds.w() - pad * 2, bodyHeight); maxScroll = Math.max(0, contentHeight - bodyHeight); scroll = Geometry.clamp(scroll, 0, maxScroll);
        ctx.painter().save(); ctx.painter().clip(viewport, 0);
        page.withClip(viewport, () -> {
            for (int pass = 0; pass < 2; pass++) for (int i = 0; i < entries.size(); i++) {
                boolean isDrag = i == dragging; if ((pass == 1) != isDrag) continue; StableList.Entry<Object> entry = entries.get(i); double offset = i * (rh + gap);
                if (isDrag) offset += dragAccum; else if (dragging >= 0 && target > dragging && i > dragging && i <= target) offset -= rh + gap; else if (dragging >= 0 && target < dragging && i >= target && i < dragging) offset += rh + gap;
                if (!isDrag) offset = page.spring(ctx, "settings.list.offset." + option.id() + "." + entry.id(), offset);
                Rect row = new Rect(viewport.x(), viewport.y() + offset - scroll, viewport.w(), rh); if (row.intersects(viewport)) row(ctx, option, style, entry, i, row, viewport, alpha, isDrag);
            }
        });
        SettingsDraw.fadingEdges(ctx, page.schema, viewport, scroll, maxScroll, ctx.theme().component(), "settings.list.fade." + option.id());
        ctx.painter().restore();
        boolean add = !draggable(style) && (style.maxEntries <= 0 || entries.size() < style.maxEntries);
        if (add) { Rect button = new Rect(viewport.x(), viewport.bottom() + (entries.isEmpty() ? 0 : gap), viewport.w(), rh); SettingsDraw.frame(ctx, button, chain(style) ? 8 : 8, 0, SettingsDraw.opacity(ctx.theme().border(), alpha)); ctx.painter().icon("plus", button.x() + 10, button.y() + (rh - 16) / 2, 16, ctx.theme().secondary()); ctx.painter().text(style.addText, button.x() + 36, button.y() + (rh - 13) / 2, 13, false, ctx.theme().secondary()); page.optionHit(ctx, option, "list.add", button, () -> { state.add(defaultEntry(option, style)); commit(ctx, option, style); }); }
    }
    private Object defaultEntry(ConfigHost.Option option, OptionStyle style) {
        return switch(style.visualizer) { case COLOR_LIST -> -1; case NUMBER_LIST, SLIDER_LIST -> controls.min(option); case RANGE_SLIDER_LIST, RANGE_NUMBER_LIST -> List.of(controls.min(option), controls.max(option)); case NUMBER_CHAIN -> nextChainValue(option, style); default -> ""; };
    }
    private double nextChainValue(ConfigHost.Option option, OptionStyle style) { if (style.nextValue != null) try { return style.nextValue.apply(state.values().stream().map(v -> SettingsControls.numeric(v, controls.min(option))).toList()); } catch (RuntimeException ignored) { /* Original nextValue falls back to max on metadata callback errors. */ } return controls.max(option); }
    private void row(PageContext ctx, ConfigHost.Option option, OptionStyle style, StableList.Entry<Object> entry, int index, Rect r, Rect viewport, double alpha, boolean isDrag) {
        boolean movable = !chain(style) || index >= style.lockedLeading; boolean invalid = style.visualizer == Visualizer.TEXT_LIST && !SettingsControls.valid(style.regex, Objects.toString(entry.value(), ""));
        SettingsDraw.frame(ctx, r, 8, SettingsDraw.opacity(isDrag ? ctx.accent() : chain(style) ? ctx.theme().chip() : draggable(style) ? ctx.theme().component() : ctx.theme().card(), alpha), SettingsDraw.opacity(invalid ? 0xffff4444 : ctx.theme().border(), alpha));
        double x = r.x() + (draggable(style) ? 12 : 10), end = r.right() - 10; boolean handle = movable && (chain(style) || draggable(style) || style.reorderable);
        if (handle) { double size = chain(style) ? 14 : 16; Rect drag = new Rect(x, r.y() + (r.h() - size) / 2, size, size); ctx.painter().icon("dots-grid", drag.x(), drag.y(), size, ctx.theme().secondary()); page.optionHit(ctx, option, "list.drag." + entry.id(), drag, () -> startDrag(ctx, option, style, index, viewport)); x += size + (draggable(style) ? 15 : 10); }
        else if (chain(style)) { ctx.painter().icon("dots-grid", x, r.y() + 11, 14, ctx.theme().secondary()); x += 24; }
        if (!draggable(style)) { if (movable) { Rect remove = new Rect(end - 16, r.y() + (r.h() - 16) / 2, 16, 16); ctx.painter().icon(chain(style) ? "close" : "trash", remove.x(), remove.y(), 16, ctx.theme().secondary()); page.optionHit(ctx, option, "list.remove." + entry.id(), remove, () -> { state.remove(entry.id()); commit(ctx, option, style); }); } end -= 26; }
        if (draggable(style) && style.checkable) { Rect check = new Rect(x, r.y() + (r.h() - 24) / 2, 24, 24); boolean checked = this.checked.contains(entry.id()); SettingsDraw.frame(ctx, check, 6, checked ? ctx.accent() : ctx.theme().component(), ctx.theme().border()); if (checked) ctx.painter().icon("tick", check.x() - 1, check.y() - 1, 26, ctx.theme().text()); page.optionHit(ctx, option, "list.check." + entry.id(), check, () -> { if (!this.checked.remove(entry.id())) this.checked.add(entry.id()); commit(ctx, option, style); }); x += 39; }
        Rect field = new Rect(x, r.y() + (r.h() - 26) / 2, Math.max(1, end - x), 26); String id = option.id() + "." + entry.id();
        switch(style.visualizer) {
            case TEXT_LIST, FILE_LIST -> {
                if (style.visualizer == Visualizer.FILE_LIST) { Rect folder = new Rect(field.right() - 16, r.y() + (r.h() - 16) / 2, 16, 16); ctx.painter().icon("folder", folder.x(), folder.y(), 16, ctx.theme().secondary()); page.optionHit(ctx, option, "list.file." + entry.id(), folder, () -> controls.chooseFile(ctx, option, style, Objects.toString(entry.value(), ""), selected -> { if (state.update(entry.id(), selected)) commit(ctx, option, style); })); field = new Rect(field.x(), field.y(), Math.max(1, field.w() - 26), field.h()); }
                page.field(ctx, option, "settings.list.text." + id, field, Objects.toString(entry.value(), ""), style.placeholder, UnaryOperator.identity(), value -> { state.update(entry.id(), value); commit(ctx, option, style); });
            }
            case NUMBER_LIST, SLIDER_LIST -> { Consumer<Double> update = value -> { state.update(entry.id(), value); commit(ctx, option, style); }; if (style.visualizer == Visualizer.SLIDER_LIST) { double tw = Math.max(1, field.w() - 76); controls.track(ctx, option, id, new Rect(field.x(), r.y() + (r.h() - 14) / 2, tw, 14), SettingsControls.numeric(entry.value(), controls.min(option)), controls.min(option), controls.max(option), controls.step(option), 14, 4, update, alpha); controls.spinner(ctx, option, id, new Rect(field.x() + tw + 10, field.y(), 66, 26), SettingsControls.numeric(entry.value(), controls.min(option)), controls.min(option), controls.max(option), ControlMath.spinnerStep(controls.step(option), controls.min(option), controls.max(option)), style, update, alpha); } else controls.spinner(ctx, option, id, new Rect(field.x(), field.y(), Math.min(87, field.w()), 26), SettingsControls.numeric(entry.value(), controls.min(option)), controls.min(option), controls.max(option), ControlMath.spinnerStep(controls.step(option), controls.min(option), controls.max(option)), style, update, alpha); }
            case RANGE_SLIDER_LIST, RANGE_NUMBER_LIST -> rangeRow(ctx, option, style, entry, field, alpha);
            case COLOR_LIST -> { int argb = SettingsControls.colorArgb(entry.value()); Rect swatch = new Rect(field.x(), r.y() + 5, field.w(), r.h() - 10); SettingsDraw.frame(ctx, swatch, 8, argb, ctx.theme().border()); int text = ColorModel.luminance(argb) > .6 ? 0xff000000 : 0xffffffff; ctx.painter().icon("paintbrush", swatch.x() + 8, swatch.y() + (swatch.h() - 12) / 2, 12, text); ctx.painter().text("#" + ColorModel.hex(argb), swatch.x() + 28, swatch.y() + (swatch.h() - 12) / 2, 12, false, text); page.optionHit(ctx, option, "list.color." + entry.id(), swatch, () -> { OptionStyle pickerStyle = new OptionStyle(Visualizer.COLOR); pickerStyle.alpha = style.alpha; ctx.overlay(new ColorPicker(page, option, pickerStyle, swatch, () -> state.entries().stream().filter(e -> e.id() == entry.id()).map(StableList.Entry::value).findFirst().orElse(entry.value()), value -> { if (state.update(entry.id(), value)) commit(ctx, option, style); })); }); }
            case NUMBER_CHAIN -> {
                if (movable) { String fieldId = "settings.chain." + id;
                    page.field(ctx, option, fieldId, field, ControlMath.format(SettingsControls.numeric(entry.value(), controls.min(option))), "", ControlMath::filterNumber, value -> {}, value -> { Double parsed = ControlMath.parse(value, controls.min(option), controls.max(option)); if (parsed != null && state.update(entry.id(), parsed)) commit(ctx, option, style); });
                    if (!page.fieldFocused(fieldId) && style.format != null) { ctx.painter().rect(field, 0, ctx.theme().chip()); ctx.painter().text(SettingsControls.formatted(style, SettingsControls.numeric(entry.value(), controls.min(option))), field.x(), field.y() + 6, 13, false, ctx.theme().secondary()); }
                } else { String label = style.format == null ? ControlMath.format(SettingsControls.numeric(entry.value(), controls.min(option))) : style.format.apply(SettingsControls.numeric(entry.value(), controls.min(option))); ctx.painter().text(label, field.x(), field.y() + 6, 13, false, ctx.theme().secondary()); }
            }
            case DRAGGABLE_LIST -> { String label = Objects.toString(entry.value(), ""); int choice = option.choices().indexOf(label); if (choice >= 0 && choice < style.optionValues.size()) label = Objects.toString(style.optionValues.get(choice), label); ctx.painter().text(SettingsDraw.trim(ctx.painter(), label, field.w(), 13, false), field.x(), r.y() + (r.h() - 13) / 2, 13, false, ctx.theme().secondary()); }
            default -> throw new IllegalStateException("Unexpected list visualizer: " + style.visualizer);
        }
    }
    private void rangeRow(PageContext ctx, ConfigHost.Option option, OptionStyle style, StableList.Entry<Object> entry, Rect field, double alpha) {
        List<Object> values = ControlMath.elements(entry.value()); double[] draft = ranges.computeIfAbsent(entry.id(), id -> new double[] {SettingsControls.numeric(values.get(0), controls.min(option)), SettingsControls.numeric(values.get(1), controls.max(option))});
        double storedLo = SettingsControls.numeric(values.get(0), controls.min(option)), storedHi = SettingsControls.numeric(values.get(1), controls.max(option));
        if (!Objects.equals(List.of(Math.min(draft[0], draft[1]), Math.max(draft[0], draft[1])), List.of(Math.min(storedLo, storedHi), Math.max(storedLo, storedHi)))) { draft[0] = storedLo; draft[1] = storedHi; }
        Consumer<double[]> update = pair -> { draft[0] = pair[0]; draft[1] = pair[1]; state.update(entry.id(), List.of(pair[0], pair[1])); commit(ctx, option, style); };
        boolean slider = style.visualizer == Visualizer.RANGE_SLIDER_LIST; double tw = slider ? Math.max(1, field.w() - 122) : 0, w = slider ? 56 : (field.w() - 20) / 2, x = slider ? field.x() + tw + 10 : field.x();
        if (slider) controls.rangeTrack(ctx, option, option.id() + "." + entry.id(), new Rect(field.x(), field.y() + 6, tw, 14), draft[0], draft[1], 11, 14, 4, update, alpha);
        controls.spinner(ctx, option, option.id() + "." + entry.id() + ".start", new Rect(x, field.y(), w, 26), draft[0], controls.min(option), controls.max(option), ControlMath.spinnerStep(controls.step(option), controls.min(option), controls.max(option)), style, value -> update.accept(new double[] {value, draft[1]}), alpha, controls.min(option), draft[0] <= draft[1] ? draft[1] : controls.max(option), () -> { if (draft[0] > draft[1]) update.accept(new double[] {draft[1], draft[0]}); });
        if (!slider) SettingsDraw.center(ctx, "-", new Rect(x + w, field.y(), 20, 26), 13, false, ctx.theme().secondary());
        controls.spinner(ctx, option, option.id() + "." + entry.id() + ".end", new Rect(x + w + (slider ? 0 : 20), field.y(), w, 26), draft[1], controls.min(option), controls.max(option), ControlMath.spinnerStep(controls.step(option), controls.min(option), controls.max(option)), style, value -> update.accept(new double[] {draft[0], value}), alpha, draft[0] <= draft[1] ? draft[0] : controls.min(option), controls.max(option), () -> { if (draft[0] > draft[1]) update.accept(new double[] {draft[1], draft[0]}); });
    }
    private void startDrag(PageContext ctx, ConfigHost.Option option, OptionStyle style, int index, Rect viewport) {
        dragging = target = index; dragAccum = 0; double startY = ctx.mouseY(), stride = rowHeight(style) + gap(style); int locked = chain(style) ? Math.min(style.lockedLeading, state.entries().size()) : 0;
        page.drag(ctx, option, (x, y) -> { dragAccum = Geometry.clamp(y - startY, -(index - locked) * stride, (state.entries().size() - 1 - index) * stride); target = (int)Geometry.clamp(index + Math.floor(dragAccum / stride + .5), locked, state.entries().size() - 1); controls.tick(ctx, option.id() + ".reorder", target); }, () -> { if (dragging >= 0 && target != dragging) { state.move(dragging, target, locked); commit(ctx, option, style); } dragging = target = -1; dragAccum = 0; });
    }
    private void commit(PageContext ctx, ConfigHost.Option option, OptionStyle style) {
        List<Object> values = new ArrayList<>(state.values());
        if (style.visualizer == Visualizer.TEXT_LIST) values.removeIf(value -> Objects.toString(value, "").isBlank() || !SettingsControls.valid(style.regex, Objects.toString(value, "")));
        if (style.visualizer == Visualizer.FILE_LIST) values.removeIf(value -> Objects.toString(value, "").isBlank());
        if (draggable(style) && style.checkable) values = state.entries().stream().filter(e -> checked.contains(e.id())).map(StableList.Entry::value).toList();
        if (style.visualizer == Visualizer.RANGE_SLIDER_LIST || style.visualizer == Visualizer.RANGE_NUMBER_LIST) { List<Object> flat = new ArrayList<>(); for (Object pair : values) { List<Object> ends = ControlMath.elements(pair); flat.addAll(ControlMath.ordered(SettingsControls.numeric(ends.get(0), controls.min(option)), SettingsControls.numeric(ends.get(1), controls.max(option)))); } values = flat; }
        if (style.visualizer == Visualizer.COLOR_LIST && option.value() instanceof java.awt.Color[]) { values = values.stream().map(v -> (Object)new java.awt.Color(SettingsControls.colorArgb(v), true)).toList(); }
        page.write(ctx, option, ControlMath.containerLike(option.value(), values, style.visualizer != Visualizer.TEXT_LIST && style.visualizer != Visualizer.FILE_LIST && style.visualizer != Visualizer.DRAGGABLE_LIST));
        lastStored = List.copyOf(decode(option, style));
        if (chain(style)) state.replace(lastStored);
    }
    void cancelDrag() { dragging = target = -1; dragAccum = 0; }
    boolean wheel(double delta) { if (maxScroll <= 0) return false; double old = scroll; scroll = Geometry.clamp(scroll - delta * 36, 0, maxScroll); return old != scroll; }
}
