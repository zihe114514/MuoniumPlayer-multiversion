package com.muoniumplayer.configskija.host.sound;

import java.util.Locale;

public enum SoundTheme {
    MODERN, MINECRAFT;

    /** Accepts the shell's theme ID or the original activeTheme preference name. */
    public static SoundTheme of(String theme) {
        return theme != null && theme.toLowerCase(Locale.ROOT).startsWith("minecraft") ? MINECRAFT : MODERN;
    }
    public float ambienceVolume(float volume) { return (this == MODERN ? .5f : .08f) * volume; }
}
