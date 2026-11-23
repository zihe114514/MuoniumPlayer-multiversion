package com.muoniumplayer.configskija.host.sound;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Random;

/** A full Minecraft bag is consumed before reshuffling, including the last/first boundary. */
public final class AmbienceShuffle {
    private final Random random;
    private final EnumMap<SoundTheme, ArrayDeque<String>> bags = new EnumMap<>(SoundTheme.class);
    private final EnumMap<SoundTheme, String> last = new EnumMap<>(SoundTheme.class);
    public AmbienceShuffle(Random random) { this.random = java.util.Objects.requireNonNull(random); }
    public String next(SoundTheme theme) {
        if (theme == SoundTheme.MODERN) return SoundPalette.PAD;
        ArrayDeque<String> bag = bags.computeIfAbsent(theme, ignored -> new ArrayDeque<>());
        if (bag.isEmpty()) {
            ArrayList<String> ids = new ArrayList<>(SoundPalette.MINECRAFT_AMBIENCE);
            Collections.shuffle(ids, random);
            if (ids.get(0).equals(last.get(theme))) Collections.swap(ids, 0, 1);
            bag.addAll(ids);
        }
        String id = bag.removeFirst(); last.put(theme, id); return id;
    }
}
