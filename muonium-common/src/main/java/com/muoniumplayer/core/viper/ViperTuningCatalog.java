package com.muoniumplayer.core.viper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调参界面的展示目录：分组的中文显示名、字段的中文显示名，以及「档位型」整型字段的
 * 离散取值表。
 *
 * <p>字段与量程的权威定义在 {@link ViperPresetSchema}（类型 / 默认 / 量程 / 旧键），这里
 * 只补「给人看」的那一半；查不到的一律回退到字段键名本身——键名取自官方转换工具，
 * 对调过参的人是可读的，所以回退不丢信息。</p>
 *
 * <p><b>档位表为什么手写</b>：官方 App 的模式字段（低音模式 / Clarity 模式 / 交叉馈送档位…）
 * 在 schema 里是无量程整型，取值集合只存在于 DSP 实现的枚举里（{@code ViPERBass.ProcessMode}
 * 的 NATURAL_BASS / PURE_BASS_PLUS / SUBWOOFER 等）。这里逐条对齐 DSP；没有把握的字段
 * （如 {@code dynamicSystem.device}，映射器不消费、属官方遗留）标记为「未使用」，界面只读展示。</p>
 */
public final class ViperTuningCatalog {

    /** 分组的中文显示名（键为 {@link ViperPresetSchema} 的分组键）。 */
    private static final Map<String, String> GROUP_NAMES = new LinkedHashMap<>();

    /** 字段的中文显示名（键为 {@code 分组键 + "." + 字段键}）。 */
    private static final Map<String, String> FIELD_NAMES = new LinkedHashMap<>();

    /** 档位型整型字段的离散取值（键同上；{@code null} 表示未收录，界面回退为只读）。 */
    private static final Map<String, int[]> INT_CHOICES = new LinkedHashMap<>();

    /** 档位取值的可读名（键同上；仅有名字的档位才收录，界面优先显示名字）。 */
    private static final Map<String, String[]> INT_CHOICE_NAMES = new LinkedHashMap<>();

    /** 标记「官方遗留、映射器不消费」的字段（界面只读展示，避免调了没反应的困惑）。 */
    private static final Map<String, Boolean> UNUSED = new LinkedHashMap<>();

    /** JamesDSP 混响的 19 个预设中文名（与 {@code JdspReverb.PRESET_NAMES} 一一对应）。 */
    private static final String[] JDSP_REVERB_PRESET_NAMES = {
            "默认", "小厅 1", "小厅 2", "中厅 1", "中厅 2",
            "大厅 1", "大厅 2", "小房间 1", "小房间 2",
            "中房间 1", "中房间 2", "大房间 1", "大房间 2",
            "中 ER 1", "中 ER 2", "板式（亮）", "板式（暗）",
            "长混响 1", "长混响 2"};

    static {
        GROUP_NAMES.put("masterLimiter", "主限幅与输出");
        GROUP_NAMES.put("playbackGainControl", "自动增益（AGC）");
        GROUP_NAMES.put("lufs", "响度归一（LUFS）");
        GROUP_NAMES.put("fetCompressor", "FET 压缩器");
        GROUP_NAMES.put("multibandCompressor", "多段压缩器");
        GROUP_NAMES.put("ddc", "DDC 耳机校正");
        GROUP_NAMES.put("spectrumExtension", "频谱扩展");
        GROUP_NAMES.put("equalizer", "图形均衡器");
        GROUP_NAMES.put("dynamicEq", "动态均衡器");
        GROUP_NAMES.put("convolver", "卷积器");
        GROUP_NAMES.put("fieldSurround", "场景环绕");
        GROUP_NAMES.put("diffSurround", "差分环绕");
        GROUP_NAMES.put("stereoImager", "立体声像");
        GROUP_NAMES.put("headphoneSurround", "耳机环绕（VHE）");
        GROUP_NAMES.put("reverb", "混响");
        GROUP_NAMES.put("jdspReverb", "JamesDSP 混响");
        GROUP_NAMES.put("dynamicSystem", "动态系统");
        GROUP_NAMES.put("psychoacousticBass", "心理声学低音");
        GROUP_NAMES.put("bass", "ViPER 低音");
        GROUP_NAMES.put("bassMono", "ViPER 低音（单声道）");
        GROUP_NAMES.put("clarity", "音质修复（Clarity）");
        GROUP_NAMES.put("cure", "交叉馈送（Cure）");
        GROUP_NAMES.put("tubeSimulator", "胆机模拟");
        GROUP_NAMES.put("analogX", "AnalogX");
        GROUP_NAMES.put("speakerCorrection", "扬声器校正");

        field("masterLimiter", "threshold", "限幅门限");
        field("masterLimiter", "outputVolume", "输出音量");
        field("masterLimiter", "channelPan", "声道平衡");
        field("playbackGainControl", "enable", "启用");
        field("playbackGainControl", "strength", "强度");
        field("playbackGainControl", "maxGain", "最大增益");
        field("playbackGainControl", "outputThreshold", "输出门限");
        field("lufs", "enable", "启用");
        field("lufs", "target", "目标响度");
        field("lufs", "maxGain", "最大增益");
        field("lufs", "speed", "响应速度");
        field("fetCompressor", "enable", "启用");
        field("fetCompressor", "threshold", "门限");
        field("fetCompressor", "ratio", "压缩比");
        field("fetCompressor", "kneeAuto", "自动拐点");
        field("fetCompressor", "knee", "拐点宽度");
        field("fetCompressor", "kneeMulti", "拐点倍率");
        field("fetCompressor", "gainAuto", "自动增益");
        field("fetCompressor", "gain", "补偿增益");
        field("fetCompressor", "attackAuto", "自动启动");
        field("fetCompressor", "attack", "启动时间");
        field("fetCompressor", "maxAttack", "最大启动");
        field("fetCompressor", "releaseAuto", "自动释放");
        field("fetCompressor", "release", "释放时间");
        field("fetCompressor", "maxRelease", "最大释放");
        field("fetCompressor", "crest", "波峰系数");
        field("fetCompressor", "adapt", "自适应速率");
        field("fetCompressor", "noClip", "防削波");
        field("ddc", "enable", "启用");
        field("ddc", "device", "系数文件（.vdc）");
        field("spectrumExtension", "enable", "启用");
        field("spectrumExtension", "strength", "强度");
        field("spectrumExtension", "exciter", "激励量");
        field("equalizer", "enable", "启用");
        field("equalizer", "bandCount", "频段数");
        field("equalizer", "bands", "各频段增益（dB）");
        field("equalizer", "presetId", "官方预设 ID");
        field("dynamicEq", "enable", "启用");
        field("dynamicEq", "bandCount", "频段数");
        field("dynamicEq", "freqs", "频点（Hz）");
        field("dynamicEq", "qs", "Q 值");
        field("dynamicEq", "gains", "增益（dB）");
        field("dynamicEq", "thresholds", "门限");
        field("dynamicEq", "attacks", "启动时间");
        field("dynamicEq", "releases", "释放时间");
        field("dynamicEq", "filterTypes", "滤波类型");
        field("convolver", "enable", "启用");
        field("convolver", "kernelFile", "脉冲响应文件");
        field("convolver", "crossChannel", "串音分量");
        field("fieldSurround", "enable", "启用");
        field("fieldSurround", "widening", "展宽量");
        field("fieldSurround", "midImage", "中置成像");
        field("fieldSurround", "depth", "深度");
        field("diffSurround", "enable", "启用");
        field("diffSurround", "delay", "延迟");
        field("diffSurround", "reverse", "左右反转");
        field("diffSurround", "wetDryMix", "湿干比");
        field("diffSurround", "lpCutoff", "低通截止");
        field("stereoImager", "enable", "启用");
        field("stereoImager", "lowWidth", "低频宽度");
        field("stereoImager", "midWidth", "中频宽度");
        field("stereoImager", "highWidth", "高频宽度");
        field("stereoImager", "lowCrossover", "低频分频点");
        field("stereoImager", "highCrossover", "高频分频点");
        field("headphoneSurround", "enable", "启用");
        field("headphoneSurround", "quality", "品质档位");
        field("reverb", "enable", "启用");
        field("reverb", "roomSize", "房间大小");
        field("reverb", "width", "宽度");
        field("reverb", "damp", "阻尼");
        field("reverb", "wet", "湿声");
        field("reverb", "dry", "干声");
        field("jdspReverb", "enable", "启用");
        field("jdspReverb", "preset", "预设");
        field("jdspReverb", "custom", "自定义参数");
        field("jdspReverb", "oversampleFactor", "过采样倍率");
        field("jdspReverb", "ertolate", "早期反射混合");
        field("jdspReverb", "erefwet", "早期反射湿声");
        field("jdspReverb", "dry", "干声增益");
        field("jdspReverb", "ereffactor", "早期反射延迟系数");
        field("jdspReverb", "erefwidth", "早期反射宽度");
        field("jdspReverb", "width", "立体声宽度");
        field("jdspReverb", "wet", "湿声增益");
        field("jdspReverb", "wander", "调制深度");
        field("jdspReverb", "bassb", "低频增强");
        field("jdspReverb", "spin", "LFO 速率");
        field("jdspReverb", "inputlpf", "输入低通");
        field("jdspReverb", "basslpf", "低频低通");
        field("jdspReverb", "damplpf", "阻尼低通");
        field("jdspReverb", "outputlpf", "输出低通");
        field("jdspReverb", "rt60", "混响时长 RT60");
        field("jdspReverb", "delay", "输出延迟");
        field("dynamicSystem", "enable", "启用");
        field("dynamicSystem", "device", "设备（未使用）");
        field("dynamicSystem", "strength", "强度");
        field("dynamicSystem", "xLow", "低频系数");
        field("dynamicSystem", "xHigh", "高频系数");
        field("dynamicSystem", "yLow", "低频门槛");
        field("dynamicSystem", "yHigh", "高频门槛");
        field("dynamicSystem", "sideGainLow", "低频侧链增益");
        field("dynamicSystem", "sideGainHigh", "高频侧链增益");
        field("psychoacousticBass", "enable", "启用");
        field("psychoacousticBass", "cutoff", "截止频率");
        field("psychoacousticBass", "intensity", "强度");
        field("psychoacousticBass", "harmonicOrder", "谐波阶数");
        field("psychoacousticBass", "originalLevel", "原始电平");
        field("bass", "enable", "启用");
        field("bass", "mode", "模式");
        field("bass", "frequency", "频率");
        field("bass", "gain", "增益");
        field("bass", "antiPop", "防爆音");
        field("bassMono", "enable", "启用");
        field("bassMono", "mode", "模式");
        field("bassMono", "frequency", "频率");
        field("bassMono", "gain", "增益");
        field("bassMono", "antiPop", "防爆音");
        field("clarity", "enable", "启用");
        field("clarity", "mode", "模式");
        field("clarity", "gain", "增益");
        field("cure", "enable", "启用");
        field("cure", "crossfeedPreset", "交叉馈送档位");
        field("tubeSimulator", "enable", "启用");
        field("analogX", "enable", "启用");
        field("analogX", "mode", "模式");
        field("speakerCorrection", "enable", "启用");

        // 档位表：逐条对齐 DSP 枚举（见各类的 ProcessMode / ClarityMode 等）。
        INT_CHOICES.put("bass.mode", new int[] {0, 1, 2});
        INT_CHOICES.put("bassMono.mode", new int[] {0, 1, 2});
        INT_CHOICES.put("clarity.mode", new int[] {0, 1, 2});
        INT_CHOICES.put("analogX.mode", new int[] {0, 1, 2});
        INT_CHOICES.put("cure.crossfeedPreset", new int[] {0, 1, 2, 3, 4, 5});
        INT_CHOICES.put("equalizer.bandCount", new int[] {10});
        INT_CHOICES.put("dynamicEq.bandCount", new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        // 扩展：JamesDSP 混响——19 个预设（档位名见 INT_CHOICE_NAMES）+ 过采样倍率。
        INT_CHOICES.put("jdspReverb.preset",
                new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18});
        INT_CHOICES.put("jdspReverb.oversampleFactor", new int[] {1, 2});
        INT_CHOICE_NAMES.put("jdspReverb.preset", JDSP_REVERB_PRESET_NAMES);

        UNUSED.put("dynamicSystem.device", Boolean.TRUE);
    }

    private static void field(String group, String key, String name) {
        FIELD_NAMES.put(group + "." + key, name);
    }

    private ViperTuningCatalog() {
    }

    /** 分组显示名；未收录的回退到分组键。 */
    public static String groupDisplayName(String groupKey) {
        String name = GROUP_NAMES.get(groupKey);
        return name == null ? groupKey : name;
    }

    /** 字段显示名；未收录的回退到字段键。 */
    public static String fieldDisplayName(String groupKey, String fieldKey) {
        String name = FIELD_NAMES.get(groupKey + "." + fieldKey);
        return name == null ? fieldKey : name;
    }

    /** 档位型整型字段的离散取值；未收录返回 {@code null}（界面回退只读）。 */
    public static int[] intChoices(String groupKey, String fieldKey) {
        return INT_CHOICES.get(groupKey + "." + fieldKey);
    }

    /**
     * 档位取值的显示名（如 JamesDSP 混响的预设名）；未收录该字段或取值不在表内时返回
     * {@code null}，界面回退为显示数字。
     */
    public static String intChoiceName(String groupKey, String fieldKey, int value) {
        String[] names = INT_CHOICE_NAMES.get(groupKey + "." + fieldKey);
        int[] values = INT_CHOICES.get(groupKey + "." + fieldKey);
        if (names == null || values == null) {
            return null;
        }
        for (int i = 0; i < values.length && i < names.length; i++) {
            if (values[i] == value) {
                return names[i];
            }
        }
        return null;
    }

    /** 是否为官方遗留、映射器不消费的字段（界面只读展示）。 */
    public static boolean isUnusedField(String groupKey, String fieldKey) {
        return UNUSED.containsKey(groupKey + "." + fieldKey);
    }
}
