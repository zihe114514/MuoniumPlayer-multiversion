package com.muoniumplayer.configskija.ui.settings;

import com.muoniumplayer.configskija.model.ConfigHost;
import com.muoniumplayer.configskija.ui.PageContext;
import java.util.*;

/** Platform codes stay opaque; modifier classification and display names are owned by the host. */
final class KeybindCapture {
    final ConfigHost.Option option; private final SettingsPage page; private final boolean single;
    private final LinkedHashSet<Integer> keys = new LinkedHashSet<>(), mouse = new LinkedHashSet<>();
    private final Set<Integer> heldKeys = new HashSet<>(), heldMouse = new HashSet<>();
    KeybindCapture(SettingsPage page, ConfigHost.Option option, boolean single) { this.page = page; this.option = option; this.single = single; }
    boolean raw(PageContext ctx, int code, boolean pressed) { if (!page.available(option)) { page.captureDone(); return true; } if (code <= 0) return true;
        if (pressed) { keys.add(code); heldKeys.add(code); if (single) commit(ctx); }
        else { heldKeys.remove(code); if (heldKeys.isEmpty() && heldMouse.isEmpty() && (!keys.isEmpty() || !mouse.isEmpty())) commit(ctx); }
        return true;
    }
    boolean mouse(PageContext ctx, int button, boolean pressed) { if (!page.available(option)) { page.captureDone(); return true; } if (pressed) { mouse.add(button); heldMouse.add(button); if (single) commit(ctx); } else { heldMouse.remove(button); if (heldKeys.isEmpty() && heldMouse.isEmpty() && (!keys.isEmpty() || !mouse.isEmpty())) commit(ctx); } return true; }
    void unbind(PageContext ctx) { page.write(ctx, option, SettingsSchema.Binding.none()); page.captureDone(); }
    private void commit(PageContext ctx) { int mods = 0; List<Integer> normal = new ArrayList<>(); for (int code : keys) { int bit = page.schema.modifierBit(code); if (bit == 0) normal.add(code); else mods |= bit; } if (normal.isEmpty() && mouse.isEmpty() && !keys.isEmpty()) { normal.addAll(keys); mods = 0; } page.write(ctx, option, new SettingsSchema.Binding(normal, new ArrayList<>(mouse), mods, display(page.schema))); page.captureDone(); }
    String display(SettingsSchema schema) { List<String> names = new ArrayList<>(); keys.forEach(code -> names.add(schema.keyName(code))); mouse.forEach(button -> names.add(schema.mouseName(button))); return names.isEmpty() ? "Press keys..." : String.join(" + ", names); }
}
