package com.muoniumplayer.core.viper;

import java.util.Map;
import java.util.Set;

/** 工作台的用户向分类和配置单位；不改变 ViPER 的参数域或处理顺序。 */
public final class ViperWorkbenchCatalog {

    public enum Category {
        TONE("音色", "均衡与清晰度"), BASS("低频质感", "低音、谐波与模拟染色"),
        SPACE("空间", "环绕、卷积与混响"), DYNAMICS("动态", "增益、响度与压缩"),
        DEVICE("设备校正", "耳机与扬声器补偿");
        public final String title;
        public final String description;
        Category(String title, String description) { this.title = title; this.description = description; }
    }

    private static final Map<String, Category> CATEGORIES = Map.ofEntries(
            Map.entry("equalizer", Category.TONE), Map.entry("dynamicEq", Category.TONE),
            Map.entry("spectrumExtension", Category.TONE), Map.entry("clarity", Category.TONE),
            Map.entry("bass", Category.BASS), Map.entry("bassMono", Category.BASS),
            Map.entry("psychoacousticBass", Category.BASS), Map.entry("dynamicSystem", Category.BASS),
            Map.entry("tubeSimulator", Category.BASS), Map.entry("analogX", Category.BASS),
            Map.entry("convolver", Category.SPACE), Map.entry("fieldSurround", Category.SPACE),
            Map.entry("diffSurround", Category.SPACE), Map.entry("stereoImager", Category.SPACE),
            Map.entry("headphoneSurround", Category.SPACE), Map.entry("reverb", Category.SPACE),
            Map.entry("jdspReverb", Category.SPACE), Map.entry("cure", Category.SPACE),
            Map.entry("masterLimiter", Category.DYNAMICS), Map.entry("playbackGainControl", Category.DYNAMICS),
            Map.entry("lufs", Category.DYNAMICS), Map.entry("fetCompressor", Category.DYNAMICS),
            Map.entry("multibandCompressor", Category.DYNAMICS), Map.entry("ddc", Category.DEVICE),
            Map.entry("speakerCorrection", Category.DEVICE));
    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("equalizer", "在曲线和频段滑杆中塑造音色，支持 10 / 15 / 25 / 31 段"),
            Map.entry("dynamicEq", "超过门限后调整频段增益，逐段设置频点、Q 与响应时间"),
            Map.entry("spectrumExtension", "高频延伸与激励，少量使用可增加亮度"),
            Map.entry("clarity", "切换清晰度算法，调整修饰强度"),
            Map.entry("bass", "立体声低音增强，支持自然、Pure Bass+ 与低音炮模式"),
            Map.entry("bassMono", "用左右声道的共同低频增强低音"),
            Map.entry("psychoacousticBass", "以谐波补充低频听感，不等同于扬声器真实低频延伸"),
            Map.entry("dynamicSystem", "按频段与侧链参数塑造动态低音"),
            Map.entry("tubeSimulator", "电子管模拟染色，仅有开关"),
            Map.entry("analogX", "三档模拟音色，调整声音的色彩与质感"),
            Map.entry("convolver", "加载 .irs / .wav 脉冲响应，复现房间或设备的音色"),
            Map.entry("fieldSurround", "拓宽声场并调整深度；不是 Minecraft 的场景声学"),
            Map.entry("diffSurround", "用左右差分延迟产生空间听感"),
            Map.entry("stereoImager", "分别控制低、中、高三个频段的立体声宽度"),
            Map.entry("headphoneSurround", "内置耳机环绕核，原生适用 44.1 / 48 kHz"),
            Map.entry("reverb", "ViPER Freeverb 混响，分别设置房间、阻尼与湿干声"),
            Map.entry("jdspReverb", "JamesDSP 混响：19 种房间预设或自定义参数"),
            Map.entry("cure", "耳机交叉馈送，减轻左右声道过度分离的听感"),
            Map.entry("masterLimiter", "链尾输出电平、声道平衡与限幅；不是 EQ 前级增益"),
            Map.entry("playbackGainControl", "自动增益控制，调节不同片段的电平变化"),
            Map.entry("lufs", "按目标响度调整增益，原生适用 44.1 / 48 kHz"),
            Map.entry("fetCompressor", "ViPER FET 动态控制；配置域与常见压缩器旋钮并非完全等价"),
            Map.entry("multibandCompressor", "逐频段调整动态控制与补偿，分频点必须递增"),
            Map.entry("ddc", "加载 .vdc 耳机系数，原生适用 44.1 / 48 kHz"),
            Map.entry("speakerCorrection", "固定的扬声器补偿，不是自动测量校准"));
    private static final Set<String> PERCENT = Set.of("masterLimiter.channelPan", "convolver.crossChannel",
            "diffSurround.wetDryMix", "stereoImager.lowWidth", "stereoImager.midWidth", "stereoImager.highWidth",
            "reverb.roomSize", "reverb.width", "reverb.damp", "reverb.wet", "reverb.dry", "jdspReverb.ertolate",
            "jdspReverb.width", "jdspReverb.wander", "jdspReverb.bassb", "dynamicSystem.sideGainLow",
            "dynamicSystem.sideGainHigh", "psychoacousticBass.intensity", "psychoacousticBass.originalLevel");
    private static final Set<String> FREQUENCY = Set.of("dynamicEq.freqs", "diffSurround.lpCutoff",
            "stereoImager.lowCrossover", "stereoImager.highCrossover", "psychoacousticBass.cutoff",
            "bass.frequency", "bassMono.frequency", "jdspReverb.inputlpf", "jdspReverb.basslpf",
            "jdspReverb.damplpf", "jdspReverb.outputlpf", "multibandCompressor.crossovers");
    private static final Set<String> ADVANCED = Set.of("presetId", "device", "kneeMulti", "maxAttack",
            "maxRelease", "crest", "adapt", "kneeMultis", "maxAttacks", "maxReleases", "crests", "adapts");
    private static final double LOG_TO_DB = 20 / Math.log(10);

    private ViperWorkbenchCatalog() { }

    public static Category category(String groupKey) {
        Category category = CATEGORIES.get(groupKey);
        if (category == null) throw new IllegalArgumentException("未分类的效果器：" + groupKey);
        return category;
    }

    public static String description(String groupKey) { return DESCRIPTIONS.getOrDefault(groupKey, groupKey); }

    public static String fieldHint(String group, String field) {
        String key = group + "." + field;
        if ("enable".equals(field)) return "只控制此效果器；ViPER 总链开关独立";
        if ("presetId".equals(field)) return "官方数据库引用，仅保留，音频链不消费";
        if ("dynamicSystem.device".equals(key)) return "官方遗留字段，调整不影响声音";
        if ("masterLimiter.outputVolume".equals(key)) return "全链之后的输出补偿；提升频段时可手动降低，不保证前级不失真";
        if ("masterLimiter.threshold".equals(key)) return "双声道限幅器门限，位于输出增益与平衡之后";
        if ("masterLimiter.channelPan".equals(key)) return "左右声道的输出平衡；0% 为居中";
        if ("reverb.dry".equals(key)) return "Freeverb 内部会乘 2；配置 50% 才是 1 倍干声";
        if ("convolver.kernelFile".equals(key)) return "从 viper/kernels 选择 .irs / .wav 文件，也保留导入的现有引用";
        if ("ddc.device".equals(key)) return "从 viper/ddc 选择 .vdc 系数文件";
        if (compressor(group)) {
            if (field.startsWith("ratio")) return "ViPER 压缩控制量，保留原配置域；不能解释为常见的 4:1 压缩比";
            if (timeField(field)) return "预设的时间配置值；ViPER setter 还有指数映射，不把它当作实测响应时间";
            if (dbField(field)) return "预设的 dB 配置值；ViPER setter 继续沿用原有映射";
            if (field.contains("Auto")) return "自动模式开启后，对应手动控制量由算法接管";
        }
        if ("dynamicEq.qs".equals(key)) return "Peak 使用 Q；Shelf 使用坡度，编辑时限制为 0.5–1 以保持系数有限";
        if ("dynamicEq.thresholds".equals(key)) return "输入 RMS 超过门限后逐渐达到设定增益";
        if ("dynamicEq.attacks".equals(key) || "dynamicEq.releases".equals(key)) return "动态 EQ 的毫秒域，和 FET 的时间配置域不同";
        if ("jdspReverb.custom".equals(key)) return "开启后使用自定义参数；关闭时使用房间预设";
        if ("jdspReverb.preset".equals(key)) return "19 个房间/板式混响预设；自定义模式下不使用";
        if ("jdspReverb.spin".equals(key)) return "混响调制的 LFO 频率";
        if (FREQUENCY.contains(key)) return "频率控制；选择适合当前采样率的值";
        if (PERCENT.contains(key)) return "配置比例；不是监听到的能量百分比";
        return "写入当前预设，松手或确认数值后保存并应用";
    }

    public static String unit(String group, String field) {
        String key = group + "." + field;
        if (FREQUENCY.contains(key) || "jdspReverb.spin".equals(key)) return "Hz";
        if ("masterLimiter.outputVolume".equals(key)) return "dB";
        if ("masterLimiter.threshold".equals(key) || "playbackGainControl.outputThreshold".equals(key)) return "dBFS";
        if ("playbackGainControl.maxGain".equals(key)) return "dB";
        if (compressor(group) && dbField(field)) return "dB 配置";
        if (compressor(group) && timeField(field)) return "ms 配置";
        if (compressor(group) && field.startsWith("ratio")) return "% 控制量";
        if ("dynamicEq".equals(group) && ("gains".equals(field) || "thresholds".equals(field))) return "dB";
        if ("dynamicEq".equals(group) && ("attacks".equals(field) || "releases".equals(field))) return "ms";
        if ("jdspReverb.delay".equals(key)) return "ms";
        if ("jdspReverb.rt60".equals(key)) return "s";
        if ("jdspReverb".equals(group) && Set.of("wet", "dry", "erefwet").contains(field)) return "dB";
        if ("lufs.target".equals(key)) return "LUFS";
        if ("lufs.maxGain".equals(key)) return "dB";
        if ("diffSurround.delay".equals(key)) return "ms";
        if (PERCENT.contains(key)) return "%";
        return "";
    }

    public static double toDisplay(String group, String field, double value) {
        finite(value);
        String key = group + "." + field;
        if (logGain(key)) {
            if (value <= 0) throw new IllegalArgumentException("电平倍率必须大于零");
            return Math.log10(value) * 20;
        }
        if (compressor(group) && dbField(field)) return value * LOG_TO_DB;
        if (compressor(group) && timeField(field) || "jdspReverb.delay".equals(key)) return value * 1000;
        if (compressor(group) && field.startsWith("ratio")) return value * -100;
        if (PERCENT.contains(key)) return value * 100;
        return value;
    }

    public static double fromDisplay(String group, String field, double value) {
        finite(value);
        String key = group + "." + field;
        double stored;
        if (logGain(key)) stored = Math.pow(10, value / 20);
        else if (compressor(group) && dbField(field)) stored = value / LOG_TO_DB;
        else if (compressor(group) && timeField(field) || "jdspReverb.delay".equals(key)) stored = value / 1000;
        else if (compressor(group) && field.startsWith("ratio")) stored = value / -100;
        else if (PERCENT.contains(key)) stored = value / 100;
        else stored = value;
        finite(stored);
        return stored;
    }

    public static boolean logarithmic(String group, String field) {
        return FREQUENCY.contains(group + "." + field) && !"lpCutoff".equals(field);
    }

    public static boolean advanced(String group, String field) {
        if (ADVANCED.contains(field)) return true;
        return "jdspReverb".equals(group) && !Set.of("enable", "preset", "custom", "rt60", "wet", "dry").contains(field);
    }

    private static boolean compressor(String group) { return "fetCompressor".equals(group) || "multibandCompressor".equals(group); }
    private static boolean dbField(String field) { return Set.of("threshold", "thresholds", "gain", "gains", "knee", "knees").contains(field); }
    private static boolean timeField(String field) { return Set.of("attack", "attacks", "maxAttack", "maxAttacks", "release", "releases", "maxRelease", "maxReleases", "crest", "crests").contains(field); }
    private static boolean logGain(String key) { return Set.of("masterLimiter.outputVolume", "masterLimiter.threshold", "playbackGainControl.outputThreshold", "playbackGainControl.maxGain").contains(key); }
    private static void finite(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException("参数必须是有限数值"); }
}
