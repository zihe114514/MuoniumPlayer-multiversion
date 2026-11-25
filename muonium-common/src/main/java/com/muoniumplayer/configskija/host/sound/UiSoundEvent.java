package com.muoniumplayer.configskija.host.sound;

import java.util.Locale;

/** The nine source UI events. Unsupported HUD starts intentionally stay silent per theme. */
public enum UiSoundEvent {
    OPEN, CLOSE, CLICK, SLIDER_TICK, HUD_SELECT, HUD_DRAG_START, HUD_DRAG_END, HUD_RESIZE_START, HUD_RESIZE_END;

    public static UiSoundEvent parse(String event) {
        if (event == null) return null;
        String key = event.trim().replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        if (key.equals("SLIDER")) return SLIDER_TICK;
        if (key.equals("NAVIGATE")) return CLICK;
        try { return valueOf(key); } catch (IllegalArgumentException unknown) { return null; }
    }
}
