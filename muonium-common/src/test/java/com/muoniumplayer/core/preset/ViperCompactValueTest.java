package com.muoniumplayer.core.preset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.domain.ViperPresetDomain;
import com.muoniumplayer.core.viper.ViperPresetParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ViPER 预设体在分享码里只留非默认字段，对方用解析器补齐默认值后与原内容完全一致。 */
class ViperCompactValueTest {

    @Test
    void 内置预设压缩后只剩改过的组且能原样还原() throws Exception {
        String text = resource("/com/muoniumplayer/viper/presets/酷狗HiFi.json");
        JsonObject full = ViperPresetParser.parse(text, "酷狗HiFi").json();
        full.addProperty("name", "酷狗HiFi");

        JsonElement compact = new ViperPresetDomain().compactValue("presetData", full);
        JsonObject minimal = compact.getAsJsonObject();
        assertEquals("酷狗HiFi", minimal.get("name").getAsString(), "名字要留着：套用时靠它确认内容属于哪个预设");
        assertTrue(minimal.has("convolver"), "改过的组要在：" + minimal);
        assertFalse(minimal.has("createdAt"));
        assertTrue(minimal.toString().length() * 5 < full.toString().length(),
                "压缩后应远小于完整体：" + minimal.toString().length() + " vs " + full.toString().length());

        JsonObject restored = ViperPresetParser.parse(minimal.toString(), "酷狗HiFi").json();
        JsonObject expected = full.deepCopy();
        expected.remove("createdAt");
        restored.remove("createdAt");
        assertEquals(expected, restored, "补齐默认值之后必须与原内容一致");
    }

    @Test
    void 其余键原样返回() {
        JsonObject value = new JsonObject();
        value.addProperty("x", 1);
        assertEquals(value, new ViperPresetDomain().compactValue("enabled", value));
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = ViperCompactValueTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "资源缺失：" + path);
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
