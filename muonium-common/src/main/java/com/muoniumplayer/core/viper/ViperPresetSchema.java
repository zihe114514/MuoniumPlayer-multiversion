package com.muoniumplayer.core.viper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ViPER 预设的字段注册表（schema 2.1，语义单位）。
 *
 * <p>这张表是官方 ViPER4Android App（{@code EffectGroups.kt} / {@code EffectPrefs.kt}）
 * 与官方转换工具 {@code tools/convert_preset.py} 的字段并集，包含四件事：</p>
 *
 * <ul>
 *   <li><b>输出顺序</b>：{@link #GROUPS} 的顺序就是官方 {@code GROUP_ORDER} / {@code EFFECT_GROUPS}
 *       的顺序，导出的 JSON 与官方保持同一形状；</li>
 *   <li><b>默认值</b>：全部取官方 App 的 schema 2.1 默认值（{@code EffectStates.kt}）。
 *       注意 {@code convert_preset.py} 里 {@code fetThreshold} 的默认值（100）与范围列
 *       仍是旧版编码域，与 App 的语义默认值（{@code compressorDbToRaw(-18)}）不同；
 *       本实现统一采用 App 的值——因为「缺省字段」最终会以我们的值写进导出文件，
 *       必须与 App 加载时使用的默认值一致；</li>
 *   <li><b>量程</b>：官方 App 的语义域量程（{@code EffectGroups.kt} 的 {@code range}），
 *       用于对导入值做钳位；</li>
 *   <li><b>旧版键与换算</b>：v1 扁平 JSON 的键名（含扬声器命名空间的 {@code spk} 变体），
 *       以及「旧版编码 → 语义单位」的换算标签 {@link Conv}（逐条对应 App
 *       {@code convertOldPresetFloat} / {@code convertOldPresetInt}）。</li>
 * </ul>
 *
 * <p>约定：{@code min > max} 表示该字段不做量程钳位；数组类字段的量程作用于每个元素。</p>
 */
final class ViperPresetSchema {

    /** 字段的值类型。 */
    enum Kind {
        BOOL, INT, FLOAT, STRING, NULLABLE_LONG,
        INT_ARRAY, FLOAT_ARRAY, BOOL_ARRAY, DOUBLE_ARRAY
    }

    /** 旧版编码 → 语义单位 的换算标签。 */
    enum Conv {
        NONE,
        /** {@code v / 100}：绝大多数 0..1 比例量。 */
        DIV100,
        /** {@code v / 10}：动态 EQ 增益/阈值、LUFS 最大增益、混响 room/width/damp。 */
        DIV10,
        /** {@code v / 25}：压缩器 kneeMulti。 */
        DIV25,
        /** {@code v * ln(10) / 20}：压缩器的 dB 量（threshold/knee/gain）。 */
        DB,
        /** {@code -(v / 100)}：压缩器 ratio。 */
        RATIO,
        /** {@code v / 1000}：压缩器时间量（attack/release/crest…）。 */
        MS,
        /** {@code 4^(v / 100)}：压缩器 adapt。 */
        ADAPT,
        /** {@code v + 15}：ViPER 低音频率。 */
        PLUS15,
        /** {@code v * 75 + 200}：Field Surround depth。 */
        DEPTH,
        /** {@code v / -10}：LUFS target。 */
        LUFS_TARGET,
        /** {@code 1 + v/100*20}：Dynamic System strength。 */
        DS_STRENGTH,
        /** {@code v/10 + 1}：Field Surround midImage。 */
        MID_IMAGE,
        /** {@code v/100*5.6}：Spectrum Extension exciter。 */
        EXCITER
    }

    /** 单个字段定义。 */
    static final class Field {
        final Kind kind;
        final String key;
        final Conv conv;
        final Object defaultValue;
        final double min;
        final double max;
        final String v1Key;
        final String spkKey;

        Field(Kind kind, String key, Conv conv, Object defaultValue,
              double min, double max, String v1Key, String spkKey) {
            this.kind = kind;
            this.key = key;
            this.conv = conv;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.v1Key = v1Key;
            this.spkKey = spkKey;
        }

        boolean ranged() {
            return min <= max;
        }
    }

    /** 一个效果分组。 */
    static final class Group {
        final String key;
        final List<Field> fields;

        Group(String key, List<Field> fields) {
            this.key = key;
            this.fields = Collections.unmodifiableList(fields);
        }
    }

    private static final double NO_RANGE = Double.NaN;

    private static final List<Group> GROUPS;
    private static final Map<String, Group> BY_KEY;

    static {
        List<Group> groups = new ArrayList<>();

        // —— masterLimiter：无 enable；threshold 即限幅门限（1.0 = 不削减 1.0 以下的内容）。
        groups.add(group("masterLimiter",
                f("threshold", 1.0, 0.3, 1.0, Conv.DIV100, "limiter", "spkLimiter"),
                f("outputVolume", 1.0, 0.01, 2.0, Conv.DIV100, "outputVolume", "spkOutputVolume"),
                f("channelPan", 0.0, -1.0, 1.0, Conv.DIV100, "channelPan", "spkChannelPan")));

        groups.add(group("playbackGainControl",
                b("enable", false, "agcEnabled", "spkAgcEnabled"),
                f("strength", 1.0, 0.5, 3.0, Conv.DIV100, "agcStrength", "spkAgcStrength"),
                f("maxGain", 1.0, 1.0, 10.0, Conv.DIV100, "agcMaxGain", "spkAgcMaxGain"),
                f("outputThreshold", 1.0, 0.3, 1.0, Conv.DIV100, "agcOutputThreshold", "spkAgcOutputThreshold")));

        groups.add(group("lufs",
                b("enable", false, "lufsEnabled", "spkLufsEnabled"),
                f("target", -14.0, -24.0, -8.0, Conv.LUFS_TARGET, "lufsTarget", "spkLufsTarget"),
                f("maxGain", 6.0, 0.0, 12.0, Conv.DIV10, "lufsMaxGain", "spkLufsMaxGain"),
                i("speed", 1, 0, 2, "lufsSpeed", "spkLufsSpeed")));

        groups.add(group("fetCompressor",
                b("enable", false, "fetEnabled", "spkFetEnabled"),
                f("threshold", dbToRaw(-18.0), dbToRaw(-48.0), 0.0, Conv.DB, "fetThreshold", "spkFetThreshold"),
                f("ratio", -1.0, -2.0, 0.0, Conv.RATIO, "fetRatio", "spkFetRatio"),
                b("kneeAuto", true, "fetAutoKnee", "spkFetAutoKnee"),
                f("knee", 0.0, 0.0, dbToRaw(12.0), Conv.DB, "fetKnee", "spkFetKnee"),
                f("kneeMulti", 0.0, 0.0, 4.0, Conv.DIV25, "fetKneeMulti", "spkFetKneeMulti"),
                b("gainAuto", true, "fetAutoGain", "spkFetAutoGain"),
                f("gain", 0.0, 0.0, dbToRaw(24.0), Conv.DB, "fetGain", "spkFetGain"),
                b("attackAuto", true, "fetAutoAttack", "spkFetAutoAttack"),
                f("attack", 0.020, 0.001, 0.100, Conv.MS, "fetAttack", "spkFetAttack"),
                f("maxAttack", 0.080, 0.001, 0.100, Conv.MS, "fetMaxAttack", "spkFetMaxAttack"),
                b("releaseAuto", true, "fetAutoRelease", "spkFetAutoRelease"),
                f("release", 0.050, 0.005, 0.500, Conv.MS, "fetRelease", "spkFetRelease"),
                f("maxRelease", 0.100, 0.005, 0.500, Conv.MS, "fetMaxRelease", "spkFetMaxRelease"),
                f("crest", 0.100, 0.005, 0.300, Conv.MS, "fetCrest", "spkFetCrest"),
                f("adapt", 2.0, 1.0, 16.0, Conv.ADAPT, "fetAdapt", "spkFetAdapt"),
                b("noClip", true, "fetNoClip", "spkFetNoClip")));

        groups.add(group("multibandCompressor",
                b("enable", false, "mbcEnabled", "spkMbcEnabled"),
                boolArray("bandEnables", new boolean[] {true, true, true, true, true}, "mbcBandEnables", "spkMbcBandEnables"),
                intArray("crossovers", new int[] {120, 500, 4000, 8000}, 30, 16000, "mbcCrossovers", "spkMbcCrossovers"),
                floatArray("thresholds", repeat(dbToRaw(-18.0), 5), dbToRaw(-48.0), 0.0, Conv.DB, "mbcThresholds", "spkMbcThresholds"),
                floatArray("ratios", repeat(-0.5, 5), -2.0, 0.0, Conv.RATIO, "mbcRatios", "spkMbcRatios"),
                floatArray("gains", repeat(0.0, 5), 0.0, dbToRaw(24.0), Conv.DB, "mbcGains", "spkMbcGains"),
                floatArray("knees", repeat(0.0, 5), 0.0, dbToRaw(12.0), Conv.DB, "mbcKnees", "spkMbcKnees"),
                floatArray("kneeMultis", repeat(0.0, 5), 0.0, 4.0, Conv.DIV25, "mbcKneeMultis", "spkMbcKneeMultis"),
                floatArray("attacks", repeat(0.001, 5), 0.001, 0.100, Conv.MS, "mbcAttacks", "spkMbcAttacks"),
                floatArray("maxAttacks", repeat(0.044, 5), 0.001, 0.100, Conv.MS, "mbcMaxAttacks", "spkMbcMaxAttacks"),
                floatArray("releases", repeat(0.100, 5), 0.005, 0.500, Conv.MS, "mbcReleases", "spkMbcReleases"),
                floatArray("maxReleases", repeat(0.200, 5), 0.005, 0.500, Conv.MS, "mbcMaxReleases", "spkMbcMaxReleases"),
                floatArray("crests", repeat(0.100, 5), 0.005, 0.300, Conv.MS, "mbcCrests", "spkMbcCrests"),
                floatArray("adapts", repeat(2.0, 5), 1.0, 16.0, Conv.ADAPT, "mbcAdapts", "spkMbcAdapts"),
                boolArray("kneeAutos", new boolean[] {true, true, true, true, true}, "mbcAutoKnees", "spkMbcAutoKnees"),
                boolArray("gainAutos", new boolean[] {true, true, true, true, true}, "mbcAutoGains", "spkMbcAutoGains"),
                boolArray("attackAutos", new boolean[] {true, true, true, true, true}, "mbcAutoAttacks", "spkMbcAutoAttacks"),
                boolArray("releaseAutos", new boolean[] {true, true, true, true, true}, "mbcAutoReleases", "spkMbcAutoReleases"),
                boolArray("noClips", new boolean[] {true, true, true, true, true}, "mbcNoClips", "spkMbcNoClips")));

        groups.add(group("ddc",
                b("enable", false, "ddcEnabled", "spkDdcEnabled"),
                s("device", "", "ddcDevice", "spkDdcDevice")));

        groups.add(group("spectrumExtension",
                b("enable", false, "vseEnabled", "spkVseEnabled"),
                i("strength", 7600, 2200, 8200, "vseStrength", "spkVseStrength"),
                f("exciter", 0.0, 0.0, 6.0, Conv.EXCITER, "vseExciter", "spkVseExciter")));

        groups.add(group("equalizer",
                b("enable", false, "eqEnabled", "spkEqEnabled"),
                i("bandCount", 10, NO_RANGE, NO_RANGE, "eqBandCount", "spkEqBandCount"),
                doubleArray("bands", repeat(0.0, 10), -12.0, 12.0, "eqBands", "spkEqBands"),
                n("presetId", "eqPresetId", "spkEqPresetId")));

        groups.add(group("dynamicEq",
                b("enable", false, "dynamicEqEnabled", "spkDynamicEqEnabled"),
                i("bandCount", 3, NO_RANGE, NO_RANGE, "dynamicEqBandCount", "spkDynamicEqBandCount"),
                intArray("freqs", new int[] {60, 150, 400}, 20, 20000, "dynamicEqFreqs", "spkDynamicEqFreqs"),
                floatArray("qs", new double[] {1.0, 1.0, 1.5}, 0.5, 8.0, Conv.DIV100, "dynamicEqQs", "spkDynamicEqQs"),
                floatArray("gains", new double[] {0.0, 0.0, 0.0}, -12.0, 12.0, Conv.DIV10, "dynamicEqGains", "spkDynamicEqGains"),
                floatArray("thresholds", new double[] {-20.0, -20.0, -20.0}, -80.0, 0.0, Conv.DIV10, "dynamicEqThresholds", "spkDynamicEqThresholds"),
                floatArray("attacks", new double[] {10.0, 10.0, 10.0}, 1.0, 100.0, Conv.NONE, "dynamicEqAttacks", "spkDynamicEqAttacks"),
                floatArray("releases", new double[] {100.0, 100.0, 100.0}, 10.0, 500.0, Conv.NONE, "dynamicEqReleases", "spkDynamicEqReleases"),
                intArray("filterTypes", new int[] {0, 0, 0}, NO_RANGE, NO_RANGE, "dynamicEqFilterTypes", "spkDynamicEqFilterTypes")));

        groups.add(group("convolver",
                b("enable", false, "convolverEnabled", "spkConvolverEnabled"),
                s("kernelFile", "", "convolverKernel", "spkConvolverKernel"),
                f("crossChannel", 0.0, 0.0, 1.0, Conv.DIV100, "convolverCrossChannel", "spkConvolverCrossChannel")));

        groups.add(group("fieldSurround",
                b("enable", false, "fieldSurroundEnabled", "spkFieldSurroundEnabled"),
                f("widening", 0.0, 0.0, 8.0, Conv.NONE, "fieldSurroundWidening", "spkFieldSurroundWidening"),
                f("midImage", 1.5, 1.0, 2.0, Conv.MID_IMAGE, "fieldSurroundMidImage", "spkFieldSurroundMidImage"),
                i("depth", 200, 200, 950, Conv.DEPTH, "fieldSurroundDepth", "spkFieldSurroundDepth")));

        groups.add(group("diffSurround",
                b("enable", false, "diffSurroundEnabled", "spkDiffSurroundEnabled"),
                f("delay", 5.0, 1.0, 20.0, Conv.NONE, "diffSurroundDelay", "spkDiffSurroundDelay"),
                b("reverse", false, "diffSurroundReverse", "spkDiffSurroundReverse"),
                f("wetDryMix", 1.0, 0.0, 1.0, Conv.DIV100, "diffSurroundWetDryMix", "spkDiffSurroundWetDryMix"),
                i("lpCutoff", 0, 0, 20000, "diffSurroundLpCutoff", "spkDiffSurroundLpCutoff")));

        groups.add(group("stereoImager",
                b("enable", false, "stereoImgEnabled", "spkStereoImgEnabled"),
                f("lowWidth", 1.0, 0.0, 2.0, Conv.DIV100, "stereoImgLowWidth", "spkStereoImgLowWidth"),
                f("midWidth", 1.0, 0.0, 2.0, Conv.DIV100, "stereoImgMidWidth", "spkStereoImgMidWidth"),
                f("highWidth", 1.0, 0.0, 2.0, Conv.DIV100, "stereoImgHighWidth", "spkStereoImgHighWidth"),
                i("lowCrossover", 200, 80, 400, "stereoImgLowCrossover", "spkStereoImgLowCrossover"),
                i("highCrossover", 4000, 2000, 8000, "stereoImgHighCrossover", "spkStereoImgHighCrossover")));

        groups.add(group("headphoneSurround",
                b("enable", false, "vheEnabled", "spkVheEnabled"),
                i("quality", 0, 0, 4, "vheQuality", "spkVheQuality")));

        // 默认值与上游 ReverbParams（ViPERParams.h:400-406）逐项对齐：五项全 0.0f。
        groups.add(group("reverb",
                b("enable", false, "reverbEnabled", "spkReverbEnabled"),
                f("roomSize", 0.0, 0.0, 1.0, Conv.DIV10, "reverbRoomSize", "spkReverbRoomSize"),
                f("width", 0.0, 0.0, 1.0, Conv.DIV10, "reverbWidth", "spkReverbWidth"),
                f("damp", 0.0, 0.0, 1.0, Conv.DIV10, "reverbDampening", "spkReverbDampening"),
                f("wet", 0.0, 0.0, 1.0, Conv.DIV100, "reverbWet", "spkReverbWet"),
                f("dry", 0.0, 0.0, 1.0, Conv.DIV100, "reverbDry", "spkReverbDry")));

        groups.add(group("dynamicSystem",
                b("enable", false, "dynamicSystemEnabled", "spkDynamicSystemEnabled"),
                n("presetId", "dsPresetId", "spkDsPresetId"),
                i("device", 0, NO_RANGE, NO_RANGE, "dynamicSystemDevice", "spkDynamicSystemDevice"),
                f("strength", 1.0, 1.0, 8.0, Conv.DS_STRENGTH, "dynamicSystemStrength", "spkDynamicSystemStrength"),
                i("xLow", 100, 0, 2400, "dsXLow", "spkDsXLow"),
                i("xHigh", 5600, 0, 12000, "dsXHigh", "spkDsXHigh"),
                i("yLow", 40, 0, 200, "dsYLow", "spkDsYLow"),
                i("yHigh", 80, 0, 300, "dsYHigh", "spkDsYHigh"),
                f("sideGainLow", 0.5, 0.0, 1.0, Conv.DIV100, "dsSideGainLow", "spkDsSideGainLow"),
                f("sideGainHigh", 0.5, 0.0, 1.0, Conv.DIV100, "dsSideGainHigh", "spkDsSideGainHigh")));

        groups.add(group("psychoacousticBass",
                b("enable", false, "psychoBassEnabled", "spkPsychoBassEnabled"),
                i("cutoff", 80, 60, 150, "psychoBassCutoff", "spkPsychoBassCutoff"),
                f("intensity", 0.5, 0.0, 1.0, Conv.DIV100, "psychoBassIntensity", "spkPsychoBassIntensity"),
                i("harmonicOrder", 3, 2, 5, "psychoBassHarmonicOrder", "spkPsychoBassHarmonicOrder"),
                f("originalLevel", 1.0, 0.0, 1.0, Conv.DIV100, "psychoBassOriginalLevel", "spkPsychoBassOriginalLevel")));

        groups.add(group("bass",
                b("enable", false, "bassEnabled", "spkBassEnabled"),
                i("mode", 0, NO_RANGE, NO_RANGE, "bassMode", "spkBassMode"),
                i("frequency", 60, 15, 150, Conv.PLUS15, "bassFrequency", "spkBassFrequency"),
                f("gain", 0.5, 0.5, 10.0, Conv.DIV100, "bassGain", "spkBassGain"),
                b("antiPop", false, "bassAntiPop", "spkBassAntiPop")));

        groups.add(group("bassMono",
                b("enable", false, "bassMonoEnabled", "spkBassMonoEnabled"),
                i("mode", 0, NO_RANGE, NO_RANGE, "bassMonoMode", "spkBassMonoMode"),
                i("frequency", 60, 15, 150, Conv.PLUS15, "bassMonoFrequency", "spkBassMonoFrequency"),
                f("gain", 0.5, 0.5, 10.0, Conv.DIV100, "bassMonoGain", "spkBassMonoGain"),
                b("antiPop", false, "bassMonoAntiPop", "spkBassMonoAntiPop")));

        groups.add(group("clarity",
                b("enable", false, "clarityEnabled", "spkClarityEnabled"),
                i("mode", 0, NO_RANGE, NO_RANGE, "clarityMode", "spkClarityMode"),
                f("gain", 0.5, 0.0, 4.5, Conv.DIV100, "clarityGain", "spkClarityGain")));

        groups.add(group("cure",
                b("enable", false, "cureEnabled", "spkCureEnabled"),
                i("crossfeedPreset", 0, NO_RANGE, NO_RANGE, "cureStrength", "spkCureStrength")));

        groups.add(group("tubeSimulator",
                b("enable", false, "tubeSimulatorEnabled", "spkTubeSimulatorEnabled")));

        groups.add(group("analogX",
                b("enable", false, "analogxEnabled", "spkAnalogxEnabled"),
                i("mode", 0, NO_RANGE, NO_RANGE, "analogxMode", "spkAnalogxMode")));

        groups.add(group("speakerCorrection",
                b("enable", false, "speakerOptEnabled", "speakerOptEnabled")));

        GROUPS = Collections.unmodifiableList(groups);
        Map<String, Group> byKey = new LinkedHashMap<>();
        for (Group group : groups) {
            byKey.put(group.key, group);
        }
        BY_KEY = Collections.unmodifiableMap(byKey);
    }

    private ViperPresetSchema() {
    }

    static List<Group> groups() {
        return GROUPS;
    }

    static Group group(String key) {
        return BY_KEY.get(key);
    }

    /** 官方压缩器的 dB → 原始量换算（{@code compressorDbToRaw}）。 */
    static double dbToRaw(double db) {
        return db * Math.log(10.0) / 20.0;
    }

    // —— 小工具：构造字段 ——

    private static Group group(String key, Field... fields) {
        List<Field> list = new ArrayList<>(fields.length);
        Collections.addAll(list, fields);
        return new Group(key, list);
    }

    private static Field b(String key, boolean def, String v1, String spk) {
        return new Field(Kind.BOOL, key, Conv.NONE, def, NO_RANGE, NO_RANGE, v1, spk);
    }

    private static Field i(String key, int def, double min, double max, String v1, String spk) {
        return new Field(Kind.INT, key, Conv.NONE, def, min, max, v1, spk);
    }

    private static Field i(String key, int def, double min, double max, Conv conv, String v1, String spk) {
        return new Field(Kind.INT, key, conv, def, min, max, v1, spk);
    }

    private static Field f(String key, double def, double min, double max, Conv conv, String v1, String spk) {
        return new Field(Kind.FLOAT, key, conv, def, min, max, v1, spk);
    }

    private static Field s(String key, String def, String v1, String spk) {
        return new Field(Kind.STRING, key, Conv.NONE, def, NO_RANGE, NO_RANGE, v1, spk);
    }

    private static Field n(String key, String v1, String spk) {
        return new Field(Kind.NULLABLE_LONG, key, Conv.NONE, null, NO_RANGE, NO_RANGE, v1, spk);
    }

    private static Field intArray(String key, int[] def, double min, double max, String v1, String spk) {
        return new Field(Kind.INT_ARRAY, key, Conv.NONE, def, min, max, v1, spk);
    }

    private static Field floatArray(String key, double[] def, double min, double max, Conv conv, String v1, String spk) {
        return new Field(Kind.FLOAT_ARRAY, key, conv, def, min, max, v1, spk);
    }

    private static Field boolArray(String key, boolean[] def, String v1, String spk) {
        return new Field(Kind.BOOL_ARRAY, key, Conv.NONE, def, NO_RANGE, NO_RANGE, v1, spk);
    }

    private static Field doubleArray(String key, double[] def, double min, double max, String v1, String spk) {
        return new Field(Kind.DOUBLE_ARRAY, key, Conv.NONE, def, min, max, v1, spk);
    }

    private static double[] repeat(double value, int count) {
        double[] out = new double[count];
        for (int index = 0; index < count; index++) {
            out[index] = value;
        }
        return out;
    }
}
