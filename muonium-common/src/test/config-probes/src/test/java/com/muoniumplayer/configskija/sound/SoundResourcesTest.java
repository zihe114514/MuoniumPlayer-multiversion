package com.muoniumplayer.configskija.sound;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.Set;

public final class SoundResourcesTest {
    public static void main(String[] args) throws Exception {
        Path target = Path.of(args[0]).resolve("src/main/resources/assets/muoniumconfigskija");
        var manifest = JsonParser.parseString(Files.readString(target.resolve("sounds.json"))).getAsJsonObject();
        Set<String> expected = Set.of("ui.open", "ui.close", "ui.click", "ui.slider", "ui.pad_loop");
        if (!manifest.keySet().equals(expected)) throw new AssertionError("Unexpected sound events: " + manifest.keySet());
        for (String event : expected) {
            String name = event.substring(3);
            byte[] bytes = Files.readAllBytes(target.resolve("sounds/ui/" + name + ".ogg"));
            if (bytes.length < 4 || bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S')
                throw new AssertionError("Invalid Ogg resource: " + name);
        }
        if (Files.exists(target.resolve("sounds/ambient"))) throw new AssertionError("Unused ambience directory remains");
        System.out.println("SoundResourcesTest PASS (five retained sounds)");
    }
}
