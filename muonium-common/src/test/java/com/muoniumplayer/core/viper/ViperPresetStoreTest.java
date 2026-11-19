package com.muoniumplayer.core.viper;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ViperPresetStoreTest {

    @TempDir
    Path temp;

    private ViperPreset defaults(String name) {
        return ViperPresetParser.parse("{\"schemaVersion\":2.1}", name);
    }

    @Test
    void checkedSaveNormalizesPreservesUnknownsAndAlignsEmbeddedName() throws Exception {
        ViperPreset incoming = ViperPresetParser.parse(
                "{\"schemaVersion\":2.1,\"future\":{\"nil\":null},\"equalizer\":{\"extra\":[1,null]}}", "旧内嵌名");
        incoming.group("bass").addProperty("gain", 99);
        ViperPreset named = new ViperPreset("新名|预设", incoming.json());
        ViperPresetStore store = new ViperPresetStore(temp.resolve("presets").toFile());
        store.saveChecked(named);
        Path file = temp.resolve("presets/新名_预设.json");
        assertTrue(Files.isRegularFile(file));
        ViperPreset result = ViperPresetParser.parse(Files.readString(file), "fallback");
        assertEquals("新名_预设", result.name());
        assertEquals("新名_预设", result.json().get("name").getAsString());
        assertEquals(10, result.number("bass", "gain", 0));
        assertTrue(result.json().getAsJsonObject("future").get("nil").isJsonNull());
        assertEquals(incoming.group("equalizer").get("extra"), result.group("equalizer").get("extra"));
        assertEquals("旧内嵌名", incoming.json().get("name").getAsString(), "保存不能改调用方 JSON");
        assertEquals(List.of("新名_预设.json"), entries(temp.resolve("presets")));
    }

    @Test
    void replacingExistingPresetLeavesNoTemporaryFiles() throws Exception {
        ViperPresetStore store = new ViperPresetStore(temp.toFile());
        store.saveChecked(defaults("既有"));
        ViperPreset changed = defaults("既有");
        changed.group("equalizer").addProperty("enable", true);
        store.saveChecked(changed);
        assertTrue(store.load("既有").group("equalizer").get("enable").getAsBoolean());
        assertEquals(List.of("既有.json"), entries(temp));
    }

    @Test
    void directoryFailuresAreThrownAndCompatibilitySaveOnlyLogs() throws Exception {
        Path file = temp.resolve("不是目录");
        Files.writeString(file, "sentinel", StandardCharsets.UTF_8);
        ViperPresetStore store = new ViperPresetStore(file.toFile());
        assertThrows(IOException.class, () -> store.saveChecked(defaults("目标")));
        assertDoesNotThrow(() -> store.save(defaults("目标")));
        assertEquals("sentinel", Files.readString(file));

        Path occupied = temp.resolve("占名.json");
        Files.createDirectory(occupied);
        Files.writeString(occupied.resolve("不能损坏"), "sentinel");
        ViperPresetStore normal = new ViperPresetStore(temp.toFile());
        assertThrows(IOException.class, () -> normal.saveChecked(defaults("占名")));
        assertEquals("sentinel", Files.readString(occupied.resolve("不能损坏")));
        assertEquals(List.of("不是目录", "占名.json"), entries(temp));
    }

    @Test
    void invalidSerializationNeverTruncatesOldPreset() throws Exception {
        ViperPresetStore store = new ViperPresetStore(temp.toFile());
        store.saveChecked(defaults("旧文件"));
        byte[] original = Files.readAllBytes(temp.resolve("旧文件.json"));
        JsonObject invalid = defaults("旧文件").json().deepCopy();
        invalid.addProperty("futureNumber", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> store.saveChecked(new ViperPreset("旧文件", invalid)));
        assertArrayEquals(original, Files.readAllBytes(temp.resolve("旧文件.json")));
        assertEquals(List.of("旧文件.json"), entries(temp));
    }

    @Test
    void unknownLargeJsonNumberIsPreservedWithoutDoubleOverflowRejection() throws Exception {
        ViperPresetStore store = new ViperPresetStore(temp.toFile());
        ViperPreset large = ViperPresetParser.parse("{\"schemaVersion\":2.1,\"futureNumber\":1e400}", "大数");
        store.saveChecked(large);
        assertEquals("1e400", store.load("大数").json().get("futureNumber").getAsString());
    }

    @Test
    void scratchOwnershipUsesLastSuccessfulFileContent() throws Exception {
        ViperPresetStore store = new ViperPresetStore(temp.toFile());
        ViperPreset first = defaults("新建");
        String saved = store.saveNewChecked(first);
        assertThrows(IOException.class, () -> store.saveNewChecked(first));
        ViperPreset second = defaults("新建");
        second.group("bass").addProperty("gain", 2);
        String next = store.saveIfUnchangedChecked(second, saved);
        assertEquals(2, store.load("新建").number("bass", "gain", 0));
        assertThrows(IOException.class, () -> store.saveIfUnchangedChecked(first, saved));
        assertEquals(next, Files.readString(temp.resolve("新建.json")));
        Files.writeString(temp.resolve("新建.json"), "external");
        assertThrows(IOException.class, () -> store.saveIfUnchangedChecked(first, next));
        assertEquals("external", Files.readString(temp.resolve("新建.json")));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void checkedSaveFailureOnLockedWindowsTargetKeepsOldBytes() throws Exception {
        ViperPresetStore store = new ViperPresetStore(temp.toFile());
        store.saveChecked(defaults("被锁"));
        Path file = temp.resolve("被锁.json");
        byte[] original = Files.readAllBytes(file);
        // NOSHARE_DELETE 禁止原子替换，验证落盘失败而不是“目录不是文件”的前置检查。
        Class<?> options = Class.forName("com.sun.nio.file.ExtendedOpenOption");
        java.nio.file.OpenOption noDelete = (java.nio.file.OpenOption) options.getField("NOSHARE_DELETE").get(null);
        try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.READ, noDelete)) {
            ViperPreset changed = defaults("被锁");
            changed.group("bass").addProperty("gain", 3);
            assertThrows(IOException.class, () -> store.saveChecked(changed));
            assertArrayEquals(original, Files.readAllBytes(file));
        }
        assertEquals(List.of("被锁.json"), entries(temp));
    }

    private List<String> entries(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }
}
