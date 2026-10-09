package com.muoniumplayer.core.viper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ViperPresetParser} 的格式回归：四代预设（旧版 XML / v1 扁平 JSON / schema 2 / schema 2.1）
 * 到统一语义单位（schema 2.1）的换算必须与官方 App + {@code tools/convert_preset.py} 一致。
 *
 * <p>钉住的行为：旧布局 ID 归一化与量纲折算、v1 派生量（bassGain / clarityGain / vseStrength /
 * Dynamic System 六元组）、单位换算（dB→原始、ms→秒、4^x、/100…）、量程钳位取 App 的语义域、
 * 缺省字段按 App 默认值补齐、未知字段无损透传。</p>
 */
class ViperPresetParserTest {

    private static final double EPS = 1e-9;

    private static ViperPreset parse(String text) {
        return ViperPresetParser.parse(text, "测试预设");
    }

    private static double num(ViperPreset preset, String group, String field) {
        return preset.number(group, field, Double.NaN);
    }

    private static JsonArray array(ViperPreset preset, String group, String field) {
        return preset.group(group).getAsJsonArray(field);
    }

    @Test
    @DisplayName("旧版 XML：新旧 ID 混排 + 派生量 + 单位换算")
    void legacyXml() {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<map>\n"
                + "  <int name=\"36868\" value=\"1\"/>\n"
                + "  <int name=\"65589\" value=\"1\"/>\n"                       // 旧：convolver enable
                + "  <string name=\"65591;65592;65593\">DBE_Studio.irs&amp;v2</string>\n" // 旧：kernel
                + "  <int name=\"65543\" value=\"50\"/>\n"
                + "  <int name=\"65545\" value=\"3\"/>\n"
                + "  <int name=\"65548\" value=\"1\"/>\n"
                + "  <int name=\"65549;65550\" value=\"6\"/>\n"
                + "  <int name=\"65551\" value=\"1\"/>\n"
                + "  <string name=\"65552\">3.0;3.0;-2.0;0.0;1.0;-1.0;0.0;0.0;2.0;4.0;</string>\n"
                + "  <int name=\"65554;65556\" value=\"3\"/>\n"
                + "  <int name=\"65555\" value=\"7\"/>\n"
                + "  <int name=\"65558\" value=\"5\"/>\n"
                + "  <int name=\"65569\" value=\"1\"/>\n"
                + "  <string name=\"65570;65571;65572\">100;5600;40;80;50;50</string>\n"
                + "  <int name=\"65573\" value=\"50\"/>\n"
                + "  <int name=\"65574\" value=\"1\"/>\n"
                + "  <int name=\"65575\" value=\"1\"/>\n"
                + "  <int name=\"65576\" value=\"60\"/>\n"
                + "  <int name=\"65577\" value=\"6\"/>\n"
                + "  <int name=\"65578\" value=\"1\"/>\n"
                + "  <int name=\"65579\" value=\"2\"/>\n"
                + "  <int name=\"65580\" value=\"3\"/>\n"
                + "  <int name=\"65581\" value=\"0\"/>\n"
                + "  <int name=\"65584\" value=\"1\"/>\n"
                + "  <int name=\"65585\" value=\"1\"/>\n"
                + "  <int name=\"65603\" value=\"0\"/>\n"
                + "  <int name=\"65627\" value=\"1\"/>\n"                       // 旧：FET enable
                + "  <int name=\"65611\" value=\"-20\"/>\n"
                + "  <int name=\"65612\" value=\"120\"/>\n"
                + "  <int name=\"65617\" value=\"30\"/>\n"
                + "</map>";

        ViperPreset preset = parse(xml);
        assertEquals("测试预设", preset.name());
        assertTrue(preset.legacyMasterEnabled(), "36868 母开关应保留为根层提示");
        assertEquals(2.1, preset.json().get("schemaVersion").getAsDouble(), EPS);

        // 旧 ID 归一化（65589→65538、65591..3→65540..2、65627→65610）与实体还原。
        assertTrue(boolOf(preset, "convolver", "enable"));
        assertEquals("DBE_Studio.irs&v2", preset.text("convolver", "kernelFile", ""));
        assertTrue(boolOf(preset, "fetCompressor", "enable"));

        // 单位换算。
        assertEquals(0.5, num(preset, "convolver", "crossChannel"), EPS);
        assertEquals(ViperPresetSchema.dbToRaw(-20.0), num(preset, "fetCompressor", "threshold"), EPS);
        assertEquals(-1.2, num(preset, "fetCompressor", "ratio"), EPS);
        assertEquals(0.030, num(preset, "fetCompressor", "attack"), EPS);
        assertEquals(3.5, num(preset, "bass", "gain"), EPS);
        assertEquals(1.5, num(preset, "clarity", "gain"), EPS);
        assertEquals(5800.0, num(preset, "spectrumExtension", "strength"), EPS);
        assertEquals(1.7, num(preset, "fieldSurround", "midImage"), EPS);
        assertEquals(3.0, num(preset, "fieldSurround", "widening"), EPS);

        // 整数域换算（+15）。
        assertEquals(75.0, num(preset, "bass", "frequency"), EPS);
        assertEquals(1.0, num(preset, "bass", "mode"), EPS);
        assertEquals(2.0, num(preset, "clarity", "mode"), EPS);
        assertEquals(1.0, num(preset, "analogX", "mode"), EPS);

        // Dynamic System 六元组 + 语义域钳位（strength 11 → App 量程 8）。
        assertTrue(boolOf(preset, "dynamicSystem", "enable"));
        assertEquals(100.0, num(preset, "dynamicSystem", "xLow"), EPS);
        assertEquals(5600.0, num(preset, "dynamicSystem", "xHigh"), EPS);
        assertEquals(40.0, num(preset, "dynamicSystem", "yLow"), EPS);
        assertEquals(80.0, num(preset, "dynamicSystem", "yHigh"), EPS);
        assertEquals(0.5, num(preset, "dynamicSystem", "sideGainLow"), EPS);
        assertEquals(0.5, num(preset, "dynamicSystem", "sideGainHigh"), EPS);
        assertEquals(8.0, num(preset, "dynamicSystem", "strength"), EPS,
                "越界值按 App 的语义域量程钳位");

        // 10 段均衡器。
        assertTrue(boolOf(preset, "equalizer", "enable"));
        assertEquals(10.0, num(preset, "equalizer", "bandCount"), EPS);
        JsonArray bands = array(preset, "equalizer", "bands");
        assertEquals(10, bands.size());
        assertEquals(3.0, bands.get(0).getAsDouble(), EPS);
        assertEquals(-2.0, bands.get(2).getAsDouble(), EPS);
        assertEquals(4.0, bands.get(9).getAsDouble(), EPS);

        // 未出现的字段用 App 默认值补齐（混响 damp 默认 0.0 —— 与上游 ReverbParams 对齐）。
        assertEquals(1.0, num(preset, "masterLimiter", "outputVolume"), EPS);
        assertEquals(0.0, num(preset, "reverb", "damp"), EPS);
        assertEquals(0.100, num(preset, "fetCompressor", "maxRelease"), EPS);
        assertFalse(boolOf(preset, "tubeSimulator", "enable"));
    }

    @Test
    @DisplayName("旧版 XML：更老布局的 ID 与量纲折算（adopt / adopt_room / rescale）")
    void legacyXmlOldLayout() {
        String xml = "<map>"
                + "<int name=\"36868\" value=\"1\"/>"
                + "<int name=\"65598\" value=\"50\"/>"          // 旧 room（0..100）→ 5
                + "<int name=\"65599\" value=\"50\"/>"          // 旧 width（0..100）→ 5
                + "<int name=\"65573\" value=\"200\"/>"         // 旧 strength 绝对量 → 索引 5
                + "<int name=\"65577\" value=\"100\"/>"         // 旧 bass gain 绝对量 → 索引 1
                + "<int name=\"65554;65556\" value=\"150\"/>"   // 旧 widening 绝对量 → 3
                + "</map>";
        ViperPreset preset = parse(xml);
        assertEquals(0.5, num(preset, "reverb", "roomSize"), EPS);
        assertEquals(0.5, num(preset, "reverb", "width"), EPS);
        assertEquals(2.0, num(preset, "dynamicSystem", "strength"), EPS);
        assertEquals(1.0, num(preset, "bass", "gain"), EPS);
        assertEquals(3.0, num(preset, "fieldSurround", "widening"), EPS);
    }

    @Test
    @DisplayName("旧版 XML：裸 & 的容错与不可识别内容的拒绝")
    void legacyXmlTolerance() {
        ViperPreset bare = parse("<map><int name=\"36868\" value=\"1\"/>"
                + "<string name=\"65540;65541;65542\">A&B.irs</string></map>");
        assertEquals("A&B.irs", bare.text("convolver", "kernelFile", ""));

        assertThrows(IllegalArgumentException.class, () -> parse("<html><body/></html>"));
        assertThrows(IllegalArgumentException.class, () -> parse(""));
        assertThrows(IllegalArgumentException.class, () -> parse("{ not json"));
        assertThrows(IllegalArgumentException.class, () -> parse("[1,2,3]"));
    }

    @Test
    @DisplayName("v1 扁平 JSON：量纲换算与 spk 命名空间")
    void v1FlatJson() {
        String json = "{"
                + "\"masterEnabled\":true,"
                + "\"eqEnabled\":true,"
                + "\"eqBands\":\"1.5;-1.5;0;0;0;0;0;0;0;0;\","
                + "\"bassEnabled\":true,"
                + "\"bassFrequency\":45,"
                + "\"bassGain\":400,"
                + "\"agcEnabled\":true,"
                + "\"agcStrength\":150,"
                + "\"fetEnabled\":true,"
                + "\"fetThreshold\":-18,"
                + "\"fetRatio\":100,"
                + "\"fetAttack\":20,"
                + "\"fetAdapt\":50,"
                + "\"vseEnabled\":true,"
                + "\"vseStrength\":4000,"
                + "\"vseExciter\":50,"
                + "\"lufsEnabled\":true,"
                + "\"lufsTarget\":140,"
                + "\"lufsMaxGain\":60,"
                + "\"cureEnabled\":true,"
                + "\"cureStrength\":2"
                + "}";
        ViperPreset preset = parse(json);
        assertTrue(preset.legacyMasterEnabled());
        assertEquals(1.5, array(preset, "equalizer", "bands").get(0).getAsDouble(), EPS);
        assertEquals(60.0, num(preset, "bass", "frequency"), EPS);
        assertEquals(4.0, num(preset, "bass", "gain"), EPS);
        assertEquals(1.5, num(preset, "playbackGainControl", "strength"), EPS);
        assertEquals(ViperPresetSchema.dbToRaw(-18.0), num(preset, "fetCompressor", "threshold"), EPS);
        assertEquals(-1.0, num(preset, "fetCompressor", "ratio"), EPS);
        assertEquals(0.02, num(preset, "fetCompressor", "attack"), EPS);
        assertEquals(2.0, num(preset, "fetCompressor", "adapt"), EPS);
        assertEquals(4000.0, num(preset, "spectrumExtension", "strength"), EPS);
        assertEquals(2.8, num(preset, "spectrumExtension", "exciter"), EPS);
        assertEquals(-14.0, num(preset, "lufs", "target"), EPS);
        assertEquals(6.0, num(preset, "lufs", "maxGain"), EPS);
        assertEquals(2.0, num(preset, "cure", "crossfeedPreset"), EPS);
        // 缺省补齐。
        assertEquals(0.0, num(preset, "reverb", "damp"), EPS);
        assertEquals("", preset.text("convolver", "kernelFile", "X"));

        ViperPreset speaker = parse("{"
                + "\"spkMasterEnabled\":true,"
                + "\"spkBassEnabled\":true,"
                + "\"spkBassFrequency\":45,"
                + "\"spkBassGain\":400,"
                + "\"bassEnabled\":true"   // 扬声器命名空间下，这个普通键必须被忽略
                + "}");
        assertEquals(60.0, num(speaker, "bass", "frequency"), EPS);
        assertEquals(4.0, num(speaker, "bass", "gain"), EPS);
        assertFalse(boolOf(speaker, "bassMono", "enable"));
    }

    @Test
    @DisplayName("schema 2 分组 JSON：旧编码域换算")
    void schema2Grouped() {
        String json = "{\"schemaVersion\":2,"
                + "\"bass\":{\"enable\":true,\"mode\":1,\"frequency\":45,\"gain\":400,\"antiPop\":true},"
                + "\"fetCompressor\":{\"enable\":true,\"threshold\":-18,\"ratio\":100}}";
        ViperPreset preset = parse(json);
        assertEquals(60.0, num(preset, "bass", "frequency"), EPS);
        assertEquals(4.0, num(preset, "bass", "gain"), EPS);
        assertEquals(ViperPresetSchema.dbToRaw(-18.0), num(preset, "fetCompressor", "threshold"), EPS);
        assertEquals(-1.0, num(preset, "fetCompressor", "ratio"), EPS);
        // 未给的字段补齐默认（含同组内其他字段）。
        assertTrue(boolOf(preset, "fetCompressor", "kneeAuto"));
        assertEquals(0.020, num(preset, "fetCompressor", "attack"), EPS);
    }

    @Test
    @DisplayName("schema 2.1：语义单位直通、未知字段无损透传、可往返")
    void schema21Passthrough() {
        String json = "{\"schemaVersion\":2.1,\"name\":\"直通\",\"futureRoot\":123,"
                + "\"masterLimiter\":{\"threshold\":0.8,\"outputVolume\":1.2,\"channelPan\":-0.5},"
                + "\"bass\":{\"enable\":true,\"mode\":0,\"frequency\":75,\"gain\":3.5,\"antiPop\":false,\"futureField\":7},"
                + "\"unknownGroupX\":{\"foo\":1}}";
        ViperPreset preset = parse(json);
        assertEquals("直通", preset.name());
        assertEquals(75.0, num(preset, "bass", "frequency"), EPS, "2.1 不再做 +15 换算");
        assertEquals(3.5, num(preset, "bass", "gain"), EPS);
        assertEquals(0.8, preset.masterThreshold(), EPS);
        assertEquals(1.2, preset.masterOutputVolume(), EPS);
        assertEquals(-0.5, preset.masterChannelPan(), EPS);
        assertTrue(preset.json().has("futureRoot"), "根层未知键应保留");
        assertTrue(preset.json().has("unknownGroupX"), "未知分组应保留");
        assertEquals(7.0, num(preset, "bass", "futureField"), EPS, "组内未知字段应保留");

        // 往返：导出后再解析，值不变。
        String exported = ViperPresetParser.toJsonText(preset);
        ViperPreset reimported = ViperPresetParser.parse(exported, "往返");
        assertEquals("直通", reimported.name());
        assertEquals(75.0, num(reimported, "bass", "frequency"), EPS);
        assertEquals(0.8, reimported.masterThreshold(), EPS);
        assertEquals(7.0, num(reimported, "bass", "futureField"), EPS);
    }

    @Test
    @DisplayName("量程钳位：越界值一律钳到 App 量程")
    void clamping() {
        String json = "{\"schemaVersion\":2.1,"
                + "\"bass\":{\"frequency\":999,\"gain\":99},"
                + "\"masterLimiter\":{\"outputVolume\":0.001,\"channelPan\":-9}}";
        ViperPreset preset = parse(json);
        assertEquals(150.0, num(preset, "bass", "frequency"), EPS);
        assertEquals(10.0, num(preset, "bass", "gain"), EPS);
        assertEquals(0.01, num(preset, "masterLimiter", "outputVolume"), EPS);
        assertEquals(-1.0, num(preset, "masterLimiter", "channelPan"), EPS);
    }

    private static boolean boolOf(ViperPreset preset, String group, String field) {
        JsonObject object = preset.group(group);
        return object != null && object.get(field).getAsBoolean();
    }
}
