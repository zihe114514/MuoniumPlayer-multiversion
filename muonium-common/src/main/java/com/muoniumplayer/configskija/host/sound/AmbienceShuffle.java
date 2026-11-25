package com.muoniumplayer.configskija.host.sound;

import java.util.Random;

/** The compact built-in ambience loop is shared by all UI themes. */
public final class AmbienceShuffle {
    public AmbienceShuffle(Random random) { java.util.Objects.requireNonNull(random); }
    public String next(SoundTheme theme) {
        java.util.Objects.requireNonNull(theme);
        return SoundPalette.PAD;
    }
}
