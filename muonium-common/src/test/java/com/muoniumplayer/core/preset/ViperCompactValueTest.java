package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.domain.ViperPresetDomain;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.viper.ViperPresetParser;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ViperCompactValueTest {
    @Test void 内置预设压缩后只剩改过的组且能原样还原() throws Exception {
        String text;
        try (InputStream in = getClass().getResourceAsStream("/com/muoniumplayer/viper/presets/酷狗HiFi.json")) {
            assertNotNull(in); text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject full = ViperPresetParser.parse(text, "酷狗HiFi").json(); full.addProperty("name", "酷狗HiFi");
        JsonObject minimal = new ViperPresetDomain().compactValue("presetData", full).getAsJsonObject();
        assertEquals("酷狗HiFi", minimal.get("name").getAsString()); assertTrue(minimal.has("convolver")); assertFalse(minimal.has("createdAt"));
        assertTrue(minimal.toString().length() * 5 < full.toString().length());
        JsonObject restored = ViperPresetParser.parse(minimal.toString(), "酷狗HiFi").json(); full.remove("createdAt"); restored.remove("createdAt"); assertEquals(full, restored);
    }
    @Test void 其余键原样返回() { JsonObject value = new JsonObject(); value.addProperty("x", 1); assertEquals(value, new ViperPresetDomain().compactValue("enabled", value)); }
    @Test void 嵌套未知隐私字段与危险资源路径不会随分享流出() {
        JsonObject full = ViperPresetParser.parse("{\"schemaVersion\":2.1,\"name\":\"safe\",\"cookie\":\"secret\",\"convolver\":{\"enable\":true,\"kernelFile\":\"../../outside.wav\",\"token\":\"private\"}}", "safe").json();
        JsonObject clean = new ViperPresetDomain().sanitize("presetData", full, new CompatReport()).getAsJsonObject();
        assertFalse(clean.has("cookie")); assertFalse(clean.getAsJsonObject("convolver").has("token")); assertEquals("", clean.getAsJsonObject("convolver").get("kernelFile").getAsString());
        assertFalse(new ViperPresetDomain().compactValue("presetData", full).toString().contains("outside.wav"));
    }
}
