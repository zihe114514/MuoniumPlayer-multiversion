package com.muoniumplayer.configskija.host;

import com.google.gson.JsonParser;
import com.muoniumplayer.configskija.host.sound.AmbienceShuffle;
import com.muoniumplayer.configskija.host.sound.SoundPalette;
import com.muoniumplayer.configskija.host.sound.SoundTheme;
import com.muoniumplayer.configskija.ui.SkijaPainter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedConfigAssetsTest {
    private static final String ROOT = "assets/muoniumconfigskija/";

    @Test
    void iconsAndCompactSoundsAreAvailable() throws Exception {
        assertFalse(SkijaPainter.loadIcons().isEmpty());
        ClassLoader loader = getClass().getClassLoader();
        try (var stream = loader.getResourceAsStream(ROOT + "sounds.json")) {
            assertNotNull(stream);
            var events = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(Set.of("ui.open", "ui.close", "ui.click", "ui.slider", "ui.pad_loop"), events.keySet());
        }
        for (String name : Set.of("open", "close", "click", "slider", "pad_loop")) {
            try (var stream = loader.getResourceAsStream(ROOT + "sounds/ui/" + name + ".ogg")) {
                assertNotNull(stream, name);
                assertArrayEquals(new byte[] {'O', 'g', 'g', 'S'}, stream.readNBytes(4), name);
            }
        }
        AmbienceShuffle ambience = new AmbienceShuffle(new Random(1));
        for (SoundTheme theme : SoundTheme.values()) assertEquals(SoundPalette.PAD, ambience.next(theme));
    }

    @Test
    void removedStandaloneAssetsAreNotPackaged() {
        ClassLoader loader = getClass().getClassLoader();
        for (String path : Set.of("fonts/minecraft/Minecraft-Regular.otf", "fonts/minecraft/Minecraft-Bold.otf",
                "sounds/ambient/trees.ogg", "sounds/ambient/moog_1.ogg", "sounds/ambient/moog_2.ogg",
                "sounds/ambient/lullaby.ogg", "open-classes.txt", "open-glyphs.txt", "font/legacy_default.json")) {
            assertNull(loader.getResource(ROOT + path), path);
        }
    }
}
