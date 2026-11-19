package com.muoniumplayer.core.screens.ncm.effects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EffectsPresetFilesTest {

    @TempDir Path directory;

    @Test
    void readsGroupedAndLegacyXmlAsDataWithoutChangingTheSource() throws Exception {
        Path json = directory.resolve("input.json");
        String source = "{\"schemaVersion\":2.1,\"name\":\"导入测试\",\"bass\":{\"gain\":1.0},\"unknown\":{\"keep\":true}}";
        Files.writeString(json, source, StandardCharsets.UTF_8);
        var preset = EffectsPresetFiles.read(json.toFile());
        assertEquals("导入测试", preset.name());
        assertTrue(preset.json().getAsJsonObject("unknown").get("keep").getAsBoolean());
        assertEquals(source, Files.readString(json));
        Path xml = directory.resolve("legacy.xml");
        Files.writeString(xml, "<map><boolean name=\"36868\" value=\"true\"/><boolean name=\"65551\" value=\"true\"/><string name=\"65552\">1;2;3;0;0;0;0;0;0;0</string></map>");
        assertTrue(EffectsPresetFiles.read(xml.toFile()).group("equalizer").get("enable").getAsBoolean());
    }

    @Test
    void oversizedMissingOrMalformedInputsFailWithoutWriting() throws Exception {
        Path large = directory.resolve("too-big.json");
        Files.write(large, new byte[2 * 1024 * 1024 + 1]);
        assertThrows(java.io.IOException.class, () -> EffectsPresetFiles.read(large.toFile()));
        assertThrows(java.io.IOException.class, () -> EffectsPresetFiles.read(directory.resolve("missing.json").toFile()));
        Path invalid = directory.resolve("bad.json");
        Files.writeString(invalid, "not a preset");
        assertThrows(IllegalArgumentException.class, () -> EffectsPresetFiles.read(invalid.toFile()));
        assertEquals("not a preset", Files.readString(invalid));
    }
}
