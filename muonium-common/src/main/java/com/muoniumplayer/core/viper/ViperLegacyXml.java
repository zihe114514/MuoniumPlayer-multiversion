package com.muoniumplayer.core.viper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 旧版 ViPER4Android XML 预设（{@code <map>} + 数字参数 ID 格式）的容错解析。
 *
 * <p>这是网上流传最广的一代「蝰蛇音效配置文件」：根节点 {@code <map>}，子节点形如
 * {@code <int name="65538" value="1"/>}、{@code <boolean name="36868" value="true"/>}、
 * {@code <string name="65540;65541;65542">xxx.irs</string>}，其中
 * {@code name="36868"} 是母开关、也是识别该格式的标志。</p>
 *
 * <p>本类的职责是复刻官方转换工具 {@code tools/convert_preset.py} 的前半段：</p>
 * <ol>
 *   <li><b>容错扫描</b>：不依赖 XML 解析器（真实文件常带裸 {@code &}、残缺实体等，
 *       正是 Python 侧 {@code _repair_xml} 要修的东西），直接用正则按元素形状取键值，
 *       并按 XML 实体规则还原文本；</li>
 *   <li><b>旧布局归一化</b>：把 v2.7.2.x 之前的参数 ID 与量纲（{@code adopt} / {@code rescale}
 *       系列规则）折算到现代布局；</li>
 *   <li><b>映射到 v1 扁平键</b>：数字 ID → v1 键名（如 {@code 65610 → fetEnabled}），
 *       并补出旧格式没有派生量（{@code bassGain} / {@code clarityGain} / {@code vseStrength} /
 *       Dynamic System 六元组 / {@code eqBandCount}）。</li>
 * </ol>
 *
 * <p>产出是「v1 编码域」的扁平映射，单位换算与量程钳位由
 * {@link ViperPresetParser} 统一处理（与 Python 工具的 xml→v1→v2→2.1 流水线一致）。</p>
 */
final class ViperLegacyXml {

    /** 母开关的 ID：出现它才认作 ViPER 预设（与 Python 侧 {@code xml_is_viper} 相同判据）。 */
    static final String MASTER_ENABLE_ID = "36868";

    private static final Pattern SELF_CLOSED = Pattern.compile(
            "<(int|boolean|string)\\s+name=\"([^\"]*)\"(?:\\s+value=\"([^\"]*)\")?\\s*/>");
    private static final Pattern TEXT_ELEMENT = Pattern.compile(
            "<(int|boolean|string)\\s+name=\"([^\"]*)\"(?:\\s+value=\"([^\"]*)\")?\\s*>(.*?)</\\1>",
            Pattern.DOTALL);

    private static final Map<String, String> BOOL_MAP = new LinkedHashMap<>();
    private static final Map<String, String> INT_MAP = new LinkedHashMap<>();
    private static final Map<String, String> STR_MAP = new LinkedHashMap<>();
    private static final Map<String, String> MODE_MAP = new LinkedHashMap<>();

    static {
        BOOL_MAP.put(MASTER_ENABLE_ID, "masterEnabled");
        BOOL_MAP.put("65565", "agcEnabled");
        BOOL_MAP.put("65610", "fetEnabled");
        BOOL_MAP.put("65614", "fetAutoKnee");
        BOOL_MAP.put("65616", "fetAutoGain");
        BOOL_MAP.put("65618", "fetAutoAttack");
        BOOL_MAP.put("65620", "fetAutoRelease");
        BOOL_MAP.put("65626", "fetNoClip");
        BOOL_MAP.put("65546", "ddcEnabled");
        BOOL_MAP.put("65548", "vseEnabled");
        BOOL_MAP.put("65551", "eqEnabled");
        BOOL_MAP.put("65538", "convolverEnabled");
        BOOL_MAP.put("65553", "fieldSurroundEnabled");
        BOOL_MAP.put("65557", "diffSurroundEnabled");
        BOOL_MAP.put("65544", "vheEnabled");
        BOOL_MAP.put("65559", "reverbEnabled");
        BOOL_MAP.put("65569", "dynamicSystemEnabled");
        BOOL_MAP.put("65583", "tubeSimulatorEnabled");
        BOOL_MAP.put("65574", "bassEnabled");
        BOOL_MAP.put("65578", "clarityEnabled");
        BOOL_MAP.put("65581", "cureEnabled");
        BOOL_MAP.put("65584", "analogxEnabled");
        BOOL_MAP.put("65603", "speakerOptEnabled");

        INT_MAP.put("65611", "fetThreshold");
        INT_MAP.put("65612", "fetRatio");
        INT_MAP.put("65613", "fetKnee");
        INT_MAP.put("65615", "fetGain");
        INT_MAP.put("65617", "fetAttack");
        INT_MAP.put("65619", "fetRelease");
        INT_MAP.put("65621", "fetKneeMulti");
        INT_MAP.put("65622", "fetMaxAttack");
        INT_MAP.put("65623", "fetMaxRelease");
        INT_MAP.put("65624", "fetCrest");
        INT_MAP.put("65625", "fetAdapt");
        INT_MAP.put("65543", "convolverCrossChannel");
        INT_MAP.put("65555", "fieldSurroundMidImage");
        INT_MAP.put("65554;65556", "fieldSurroundWidening");
        INT_MAP.put("65558", "diffSurroundDelay");
        INT_MAP.put("65545", "vheQuality");
        INT_MAP.put("65560", "reverbRoomSize");
        INT_MAP.put("65561", "reverbWidth");
        INT_MAP.put("65562", "reverbDampening");
        INT_MAP.put("65563", "reverbWet");
        INT_MAP.put("65564", "reverbDry");
        INT_MAP.put("65573", "dynamicSystemStrength");
        INT_MAP.put("65576", "bassFrequency");
        INT_MAP.put("65582", "cureStrength");
        INT_MAP.put("65585", "analogxMode");

        STR_MAP.put("65552", "eqBands");
        STR_MAP.put("65547", "ddcDevice");
        STR_MAP.put("65540;65541;65542", "convolverKernel");

        MODE_MAP.put("65575", "bassMode");
        MODE_MAP.put("65579", "clarityMode");
    }

    private ViperLegacyXml() {
    }

    /** 文本是否是旧版 XML 预设（含母开关 ID）。 */
    static boolean isLegacyPreset(String text) {
        return text != null && text.contains("name=\"" + MASTER_ENABLE_ID + "\"");
    }

    /** 容错扫描：取出 {@code name -> value/text} 的原始映射。 */
    static Map<String, String> scan(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher selfClosed = SELF_CLOSED.matcher(text);
        while (selfClosed.find()) {
            String tag = selfClosed.group(1);
            String name = selfClosed.group(2);
            String value = selfClosed.group(3);
            if (value == null) {
                value = "";
            }
            // int/boolean 取 value 属性；string 取文本（自闭合时文本为空）。
            values.put(name, "string".equals(tag) ? "" : unescape(value));
        }
        Matcher withText = TEXT_ELEMENT.matcher(text);
        while (withText.find()) {
            String tag = withText.group(1);
            String name = withText.group(2);
            String value = withText.group(3);
            if ("string".equals(tag)) {
                values.put(name, unescape(withText.group(4)));
            } else {
                // 与 Python 一致：int/boolean 只认 value 属性，文本节点不参与。
                values.put(name, unescape(value == null ? "" : value));
            }
        }
        return values;
    }

    /**
     * 归一化旧布局：先补 ID（{@code adopt}），再按需折算旧量纲（{@code rescale}）。
     * 逐条对应 Python 的 {@code _xml_normalize}。
     */
    static void normalize(Map<String, String> values) {
        for (int offset = 0; offset < 17; offset++) {
            adopt(values, String.valueOf(65627 + offset), String.valueOf(65610 + offset));
        }
        adopt(values, "65595", "65551");
        adopt(values, "65596", "65552");
        adopt(values, "65589", "65538");
        adopt(values, "65591;65592;65593", "65540;65541;65542");
        adopt(values, "65594", "65543");
        adopt(values, "65597", "65559");
        adopt(values, "65600", "65562");
        adopt(values, "65601", "65563");
        adopt(values, "65602", "65564");
        adoptRoom(values, "65598", "65560");
        adoptRoom(values, "65599", "65561");

        rescale(values, "65554;65556", 8, value -> (value - 120) / 10);
        rescale(values, "65555", 10, value -> (value - 120) / 10);
        rescale(values, "65558", 19, value -> value / 100 - 1);
        rescale(values, "65573", 100, value -> (value - 100) / 20);
        rescale(values, "65576", 135, value -> value - 15);
        rescale(values, "65577", 11, value -> (value - 50) / 50);
        rescale(values, "65580", 9, value -> value / 50);
    }

    /**
     * 映射为 v1 扁平映射（v1 编码域）。
     *
     * <p>值类型与 Python 的 {@code xml_to_v1} 一致：布尔为 {@link Boolean}、
     * 数值为 {@link Integer}、字符串为 {@link String}；{@code masterEnabled} 保留在
     * 根层（官方转换工具会丢弃它，这里保留仅为信息无损）。</p>
     */
    static Map<String, Object> toV1(String text) {
        Map<String, String> values = scan(text);
        normalize(values);
        Map<String, Object> v1 = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : BOOL_MAP.entrySet()) {
            String raw = values.get(entry.getKey());
            if (raw != null) {
                // 现实文件两种写法都有：<boolean value="true"/> 与旧式的 <int value="1"/>。
                String normalized = raw.trim();
                v1.put(entry.getValue(),
                        "true".equalsIgnoreCase(normalized) || "1".equals(normalized));
            }
        }
        for (Map.Entry<String, String> entry : INT_MAP.entrySet()) {
            Integer parsed = toInt(values.get(entry.getKey()));
            if (parsed != null) {
                v1.put(entry.getValue(), parsed);
            }
        }
        for (Map.Entry<String, String> entry : STR_MAP.entrySet()) {
            String raw = values.get(entry.getKey());
            if (raw != null) {
                v1.put(entry.getValue(), raw);
            }
        }
        for (Map.Entry<String, String> entry : MODE_MAP.entrySet()) {
            Integer parsed = toInt(values.get(entry.getKey()));
            if (parsed != null) {
                v1.put(entry.getValue(), parsed);
            }
        }

        // 旧格式里以「档位索引」表达的量，折算成 v1 编码域的连续值。
        Integer bassIndex = toInt(values.get("65577"));
        if (bassIndex != null) {
            v1.put("bassGain", clampInt(bassIndex * 50 + 50, 50, 1000));
        }
        Integer clarityIndex = toInt(values.get("65580"));
        if (clarityIndex != null) {
            v1.put("clarityGain", clampInt(clarityIndex * 50, 0, 450));
        }
        Integer vseIndex = toInt(values.get("65549;65550"));
        if (vseIndex != null) {
            v1.put("vseStrength", clampInt(2200 + vseIndex * 600, 2200, 8200));
        }

        String dynamicSystem = values.get("65570;65571;65572");
        if (dynamicSystem != null && !dynamicSystem.isEmpty()) {
            String[] parts = dynamicSystem.split(";", -1);
            if (parts.length >= 6) {
                String[] keys = {"dsXLow", "dsXHigh", "dsYLow", "dsYHigh", "dsSideGainLow", "dsSideGainHigh"};
                for (int index = 0; index < keys.length; index++) {
                    Integer parsed = toInt(parts[index]);
                    if (parsed != null) {
                        v1.put(keys[index], parsed);
                    }
                }
                v1.put("dynamicSystemDevice", 0);
            }
        }

        // 旧格式只出 10 段均衡器。
        v1.put("eqBandCount", 10);
        return v1;
    }

    // —— 内部工具 ——

    private static void adopt(Map<String, String> values, String oldId, String newId) {
        if (values.containsKey(oldId) && !values.containsKey(newId)) {
            values.put(newId, values.get(oldId));
        }
    }

    /** 混响的 room/width：旧布局以 0..100 表达，折算为 0..10 的档位。 */
    private static void adoptRoom(Map<String, String> values, String oldId, String newId) {
        String raw = values.get(newId);
        if (raw == null) {
            raw = values.get(oldId);
        }
        Integer parsed = toInt(raw);
        if (parsed == null) {
            return;
        }
        values.put(newId, String.valueOf(parsed > 10 ? parsed / 10 : parsed));
    }

    /** 旧量纲折算：仅当数值超过新版上限（即确实是旧布局的绝对值）时换算。 */
    private static void rescale(Map<String, String> values, String id, int cap, IntOperator operator) {
        Integer parsed = toInt(values.get(id));
        if (parsed == null || parsed <= cap) {
            return;
        }
        values.put(id, String.valueOf(operator.apply(parsed)));
    }

    private interface IntOperator {
        int apply(int value);
    }

    private static Integer toInt(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException first) {
            try {
                return (int) Double.parseDouble(trimmed);
            } catch (NumberFormatException second) {
                return null;
            }
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static String unescape(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current != '&') {
                out.append(current);
                index++;
                continue;
            }
            int end = text.indexOf(';', index);
            if (end < 0) {
                out.append(current);
                index++;
                continue;
            }
            String entity = text.substring(index + 1, end);
            String replacement = entityValue(entity);
            if (replacement == null) {
                out.append(current);
                index++;
            } else {
                out.append(replacement);
                index = end + 1;
            }
        }
        return out.toString();
    }

    private static String entityValue(String entity) {
        switch (entity) {
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            default:
                break;
        }
        if (entity.startsWith("#x") || entity.startsWith("#X")) {
            try {
                return String.valueOf((char) Integer.parseInt(entity.substring(2), 16));
            } catch (NumberFormatException failure) {
                return null;
            }
        }
        if (entity.startsWith("#")) {
            try {
                return String.valueOf((char) Integer.parseInt(entity.substring(1)));
            } catch (NumberFormatException failure) {
                return null;
            }
        }
        return null;
    }
}
