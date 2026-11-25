package com.muoniumplayer.configskija.settings;

import com.muoniumplayer.configskija.model.*;
import com.muoniumplayer.configskija.ui.*;
import com.muoniumplayer.configskija.ui.search.SettingsSearchAdapter;
import com.muoniumplayer.configskija.ui.settings.*;
import com.muoniumplayer.configskija.ui.Geometry.Rect;
import java.util.*;

/** Global settings rows reuse the same page with live Option identity and owner-isolated UI state. */
public final class EntryRowsHarness {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void equal(Object a, Object b, String message) { check(Objects.deepEquals(a, b), message + ": " + a + " != " + b); }
    private static SearchDocuments.NarrowedRow leaf(SettingsHarness.Fixture host, String owner, ConfigHost.Option option) {
        SearchDocuments.SettingsSnapshot snapshot = SearchDocuments.settings(owner, owner, host.categories(), new SettingsSearchAdapter(host)); SearchDocuments.Row row = snapshot.rows().rowOfPayload(option); return new SearchDocuments.NarrowedRow(row, List.of(option), true);
    }
    private static double frame(SettingsHarness.Context ctx, EntryRows renderer, List<SearchDocuments.NarrowedRow> rows) {
        Rect viewport = new Rect(289, 88, 1052, 562); ctx.content(viewport); ctx.hits.clear(); ctx.input.beginFrame(ctx); renderer.beginFrame(ctx, viewport); double y = 88;
        try { for (SearchDocuments.NarrowedRow row : rows) y += renderer.render(ctx, row, new Rect(289, y, 1052, 0)) + 8; }
        finally { renderer.endFrame(ctx); }
        if (ctx.overlay != null) { ctx.input.pushScope(ctx, "overlay", true); try { ctx.overlay.render(ctx); } finally { ctx.input.popScope(); } }
        ctx.input.endFrame(ctx); return y - 88;
    }
    private static String id(SettingsHarness.Context ctx, String owner, String ending) { return ctx.hits.keySet().stream().filter(key -> key.contains(owner) && key.endsWith(ending)).findFirst().orElseThrow(() -> new AssertionError("Missing owner hit " + owner + " / " + ending + ": " + ctx.hits.keySet())); }
    private static void multiOwner() {
        SettingsHarness.Fixture a = new SettingsHarness.Fixture(), b = new SettingsHarness.Fixture(); ConfigHost.Option left = a.add("same", ConfigHost.Kind.CHOICE, "left"), right = b.add("same", ConfigHost.Kind.CHOICE, "right"); a.styles.get("same").visualizer = SettingsSchema.Visualizer.TEXT; b.styles.get("same").visualizer = SettingsSchema.Visualizer.TEXT; b.styles.get("same").multiline = true; a.category("a", "A", left); b.category("b", "B", right);
        SearchDocuments.NarrowedRow rowA = leaf(a, "ownerA", left), rowB = leaf(b, "ownerB", right); SettingsHarness.Context ctx = new SettingsHarness.Context(a, false); EntryRows renderer = new EntryRows(owner -> owner.id().equals("ownerA") ? a : b);
        double total = frame(ctx, renderer, List.of(rowA, rowB)); check(total > 150, "global renderer uses measured multiline row height"); String fieldA = id(ctx, "ownerA", "settings.text.same"), fieldB = id(ctx, "ownerB", "settings.text.same"); check(!fieldA.equals(fieldB), "same local Option id has separate owner UI identity");
        check(ctx.input.focus(ctx, fieldA, true), "first owner bare field registered"); ctx.input.key(ctx, ConfigView.Key.NONE, "changed-left", false, false, false); equal(a.values.get("same"), "changed-left", "first owner actual Option setter used"); equal(b.values.get("same"), "right", "other owner remains untouched");
        check(ctx.input.focus(ctx, fieldB, true), "second owner bare field registered"); ctx.input.key(ctx, ConfigView.Key.NONE, "changed-right", false, false, false); equal(b.values.get("same"), "changed-right", "second owner actual Option setter used");
        ctx.input.focus(ctx, fieldA, false); frame(ctx, renderer, List.of(rowA)); equal(ctx.input.focusedId(), fieldA, "removing another owner does not blur current owner's editor");
        a.styles.get("same").visible = () -> false; double hidden = frame(ctx, renderer, List.of(rowA)); check(hidden == 8, "hidden global row returns zero height without fabricating a control"); check(!ctx.input.focused(), "hidden global row releases text focus"); renderer.leave(ctx);
    }
    private static void accordionAndLock() {
        SettingsHarness.Fixture host = new SettingsHarness.Fixture(); ConfigHost.Option toggle = host.add("master", ConfigHost.Kind.TOGGLE, true), child = host.add("child", ConfigHost.Kind.SLIDER, 20d); host.category("cat", "Category", toggle, child); ConfigHost.Group group = host.categories.get(0).groups().get(0); host.groups.put(group.id(), new SettingsSchema.GroupStyle(true, false, null, "accordion description", "settings"));
        SearchDocuments.SettingsSnapshot snapshot = SearchDocuments.settings("owner", "Owner", host.categories(), new SettingsSearchAdapter(host)); SearchDocuments.Row row = snapshot.rows().rowOfPayload(group); check(row != null && row.accordion(), "real accordion search row captured");
        SearchDocuments.NarrowedRow whole = new SearchDocuments.NarrowedRow(row, List.of(toggle, child), true), narrowed = new SearchDocuments.NarrowedRow(row, List.of(child), false); SettingsHarness.Context ctx = new SettingsHarness.Context(host, false); EntryRows renderer = new EntryRows(host);
        frame(ctx, renderer, List.of(whole)); String toggleHit = id(ctx, "owner", "settings.toggle.master"); ctx.click(toggleHit); equal(host.values.get("master"), false, "whole accordion uses original toggle setter"); frame(ctx, renderer, List.of(narrowed)); check(ctx.hits.keySet().stream().noneMatch(key -> key.endsWith("settings.toggle.master")), "child-only search does not draw unrelated accordion controls"); check(ctx.hits.keySet().stream().anyMatch(key -> key.endsWith("settings.slider.child.child")), "child-only search retains actual slider");
        host.lock = true; ctx.painter.layers.clear(); frame(ctx, renderer, List.of(whole)); check(ctx.painter.layers.stream().filter(alpha -> Math.abs(alpha - .45) < .000001).count() == 1, "owner lock composites the whole accordion at .45 exactly once"); check(ctx.hits.keySet().stream().noneMatch(key -> key.endsWith("settings.toggle.master") || key.endsWith("settings.slider.child.child")), "owner lock blocks all accordion controls independently of enabledReason"); renderer.leave(ctx);
    }
    private static void lockExemption() {
        SettingsHarness.Fixture host = new SettingsHarness.Fixture(); ConfigHost.Option master = host.add("master", ConfigHost.Kind.TOGGLE, true), child = host.add("child", ConfigHost.Kind.TOGGLE, true); host.category("cat", "Category", master, child);
        SettingsSchema schema = new SettingsSchema() { public OptionStyle option(ConfigHost.Option option) { return host.option(option); } public boolean locked() { return true; } public String lockExemptId() { return "master"; } };
        EntryRows renderer = new EntryRows(schema); SettingsHarness.Context ctx = new SettingsHarness.Context(host, false); host.disabled.put("child", "disabled separately"); frame(ctx, renderer, List.of(leaf(host, "owner", master), leaf(host, "owner", child))); check(ctx.hits.keySet().stream().anyMatch(key -> key.endsWith("settings.toggle.master")), "real master option id is exempt from owner lock"); check(ctx.hits.keySet().stream().noneMatch(key -> key.endsWith("settings.toggle.child")), "other options stay locked"); check(ctx.painter.layers.stream().anyMatch(alpha -> Math.abs(alpha - .45 * .65) < .000001), "owner lock and option disabled alpha remain independent"); renderer.leave(ctx);
    }
    public static void main(String[] args) { multiOwner(); accordionAndLock(); lockExemption(); System.out.println("EntryRows harness passed: " + checks + " integration assertions."); }
}
