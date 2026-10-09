package com.muoniumplayer.core.viper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.viper.dsp.ViPERParams;

/**
 * 预设（已归一化的 schema 2.1 JSON）→ {@link ViPERParams} 的映射层。
 *
 * <p><b>单位模型</b>（三层，均已取证于上游仓库）：</p>
 * <ol>
 *   <li><b>UI/语义域</b>：schema 2.1 预设里的值（官方 App 的 {@code EffectState} 域，
 *       量程见 {@code EffectGroups.kt}）。{@link ViperPresetParser} 已把旧格式（XML / v1 /
 *       schema 2）换算到此域（对应 {@code EffectPrefs.convertOldPresetFloat/Int}）。</li>
 *   <li><b>结构域</b>：{@code viper::ViPERParams} 各字段。对绝大多数参数，结构域 == UI 域
 *       （上方 {@code ParameterSnapshotTest.cpp} 与 {@code DispatchRawParam} 两条链路一致印证）。</li>
 *   <li><b>setter 域</b>：极少数效果 setter 内部再除以 100（{@code StereoImager::SetLow/Mid/HighWidth}、
 *       {@code PsychoacousticBass::SetIntensity/SetOriginalBassLevel}）。为让生效后的内部状态
 *       等于 UI 域意图，这两组参数在映射时先乘 100 补偿（与官方旧版裸参数链路
 *       {@code DispatchRawParam} 把它们作为百分数下发完全一致）。</li>
 * </ol>
 *
 * <p>缺省与钳位：字段缺失时取 {@link ViperPresetSchema} 的官方默认值（与 App 加载时一致），
 * 并一律按官方量程钳位。</p>
 */
final class ViperParamMapper {

    private ViperParamMapper() {
    }

    /** 映射结果：参数本体 + 需要走资源通道的文件引用（卷积核 / DDC 设备名）。 */
    static final class Mapped {
        final ViPERParams params;
        final String convolverKernelFile;
        final String ddcDevice;

        Mapped(ViPERParams params, String convolverKernelFile, String ddcDevice) {
            this.params = params;
            this.convolverKernelFile = convolverKernelFile;
            this.ddcDevice = ddcDevice;
        }
    }

    static Mapped map(ViperPreset preset) {
        ViPERParams p = new ViPERParams();

        // —— masterLimiter ——
        p.masterLimiter.threshold = (float) n(preset, "masterLimiter", "threshold", 1.0, 0.3, 1.0);
        p.masterLimiter.outputVolume = (float) n(preset, "masterLimiter", "outputVolume", 1.0, 0.01, 2.0);
        p.masterLimiter.channelPan = (float) n(preset, "masterLimiter", "channelPan", 0.0, -1.0, 1.0);

        // —— playbackGainControl ——
        p.playbackGainControl.enable = b(preset, "playbackGainControl", "enable", false);
        p.playbackGainControl.strength = (float) n(preset, "playbackGainControl", "strength", 1.0, 0.5, 3.0);
        p.playbackGainControl.maxGain = (float) n(preset, "playbackGainControl", "maxGain", 1.0, 1.0, 10.0);
        p.playbackGainControl.outputThreshold =
                (float) n(preset, "playbackGainControl", "outputThreshold", 1.0, 0.3, 1.0);

        // —— lufs ——
        p.lufs.enable = b(preset, "lufs", "enable", false);
        p.lufs.target = (float) n(preset, "lufs", "target", -14.0, -24.0, -8.0);
        p.lufs.maxGain = (float) n(preset, "lufs", "maxGain", 6.0, 0.0, 12.0);
        p.lufs.speed = (int) n(preset, "lufs", "speed", 1, 0, 2);

        // —— fetCompressor（dB/ms 等量化值均已在 UI 域；setter 无内部除 100） ——
        p.fetCompressor.enable = b(preset, "fetCompressor", "enable", false);
        p.fetCompressor.threshold = (float) n(preset, "fetCompressor", "threshold", dbToRaw(-18.0), dbToRaw(-48.0), 0.0);
        p.fetCompressor.ratio = (float) n(preset, "fetCompressor", "ratio", -1.0, -2.0, 0.0);
        p.fetCompressor.kneeAuto = b(preset, "fetCompressor", "kneeAuto", true);
        p.fetCompressor.knee = (float) n(preset, "fetCompressor", "knee", 0.0, 0.0, dbToRaw(12.0));
        p.fetCompressor.kneeMulti = (float) n(preset, "fetCompressor", "kneeMulti", 0.0, 0.0, 4.0);
        p.fetCompressor.gainAuto = b(preset, "fetCompressor", "gainAuto", true);
        p.fetCompressor.gain = (float) n(preset, "fetCompressor", "gain", 0.0, 0.0, dbToRaw(24.0));
        p.fetCompressor.attackAuto = b(preset, "fetCompressor", "attackAuto", true);
        p.fetCompressor.attack = (float) n(preset, "fetCompressor", "attack", 0.020, 0.001, 0.100);
        p.fetCompressor.maxAttack = (float) n(preset, "fetCompressor", "maxAttack", 0.080, 0.001, 0.100);
        p.fetCompressor.releaseAuto = b(preset, "fetCompressor", "releaseAuto", true);
        p.fetCompressor.release = (float) n(preset, "fetCompressor", "release", 0.050, 0.005, 0.500);
        p.fetCompressor.maxRelease = (float) n(preset, "fetCompressor", "maxRelease", 0.100, 0.005, 0.500);
        p.fetCompressor.crest = (float) n(preset, "fetCompressor", "crest", 0.100, 0.005, 0.300);
        p.fetCompressor.adapt = (float) n(preset, "fetCompressor", "adapt", 2.0, 1.0, 16.0);
        p.fetCompressor.noClip = b(preset, "fetCompressor", "noClip", true);

        // —— bass / bassMono ——
        p.bass.enable = b(preset, "bass", "enable", false);
        p.bass.mode = (int) n(preset, "bass", "mode", 0, NO_MIN, NO_MAX);
        p.bass.frequency = (int) n(preset, "bass", "frequency", 60, 15, 150);
        p.bass.gain = (float) n(preset, "bass", "gain", 0.5, 0.5, 10.0);
        p.bass.antiPop = b(preset, "bass", "antiPop", false);

        p.bassMono.enable = b(preset, "bassMono", "enable", false);
        p.bassMono.mode = (int) n(preset, "bassMono", "mode", 0, NO_MIN, NO_MAX);
        p.bassMono.frequency = (int) n(preset, "bassMono", "frequency", 60, 15, 150);
        p.bassMono.gain = (float) n(preset, "bassMono", "gain", 0.5, 0.5, 10.0);
        p.bassMono.antiPop = b(preset, "bassMono", "antiPop", false);

        // —— psychoacousticBass（intensity/originalLevel 为 setter 域 ×100 补偿） ——
        p.psychoacousticBass.enable = b(preset, "psychoacousticBass", "enable", false);
        p.psychoacousticBass.cutoff = (int) n(preset, "psychoacousticBass", "cutoff", 80, 60, 150);
        p.psychoacousticBass.intensity =
                (int) Math.round(n(preset, "psychoacousticBass", "intensity", 0.5, 0.0, 1.0) * 100.0);
        p.psychoacousticBass.harmonicOrder = (int) n(preset, "psychoacousticBass", "harmonicOrder", 3, 2, 5);
        p.psychoacousticBass.originalLevel =
                (int) Math.round(n(preset, "psychoacousticBass", "originalLevel", 1.0, 0.0, 1.0) * 100.0);

        // —— spectrumExtension ——
        p.spectrumExtension.enable = b(preset, "spectrumExtension", "enable", false);
        p.spectrumExtension.strength = (int) n(preset, "spectrumExtension", "strength", 7600, 2200, 8200);
        p.spectrumExtension.exciter = (float) n(preset, "spectrumExtension", "exciter", 0.0, 0.0, 6.0);

        // —— equalizer ——
        p.equalizer.enable = b(preset, "equalizer", "enable", false);
        p.equalizer.bandCount = clampInt((int) n(preset, "equalizer", "bandCount", 10, NO_MIN, NO_MAX), 0, 31);
        double[] bands = dArray(preset, "equalizer", "bands");
        for (int i = 0; i < bands.length && i < p.equalizer.bandLevels.length; i++) {
            p.equalizer.bandLevels[i] = (float) clamp(bands[i], -12.0, 12.0);
        }

        // —— dynamicEq ——
        p.dynamicEq.enable = b(preset, "dynamicEq", "enable", false);
        p.dynamicEq.bandCount = clampInt((int) n(preset, "dynamicEq", "bandCount", 3, NO_MIN, NO_MAX), 0, 10);
        int[] deqFreqs = iArray(preset, "dynamicEq", "freqs", new int[] {60, 150, 400});
        double[] deqQs = dArray(preset, "dynamicEq", "qs", new double[] {1.0, 1.0, 1.5});
        double[] deqGains = dArray(preset, "dynamicEq", "gains", new double[] {0.0, 0.0, 0.0});
        double[] deqThresholds = dArray(preset, "dynamicEq", "thresholds", new double[] {-20.0, -20.0, -20.0});
        double[] deqAttacks = dArray(preset, "dynamicEq", "attacks", new double[] {10.0, 10.0, 10.0});
        double[] deqReleases = dArray(preset, "dynamicEq", "releases", new double[] {100.0, 100.0, 100.0});
        int[] deqFilterTypes = iArray(preset, "dynamicEq", "filterTypes", new int[] {0, 0, 0});
        for (int i = 0; i < p.dynamicEq.bandCount && i < p.dynamicEq.bands.length; i++) {
            ViPERParams.DynamicEqBandParams band = p.dynamicEq.bands[i];
            // 仅填充 bandCount 内的频段；数组内缺省位保持结构默认（对应 App 序列化器只写前 bandCount 段）。
            band.frequency = i < deqFreqs.length ? (float) clamp(deqFreqs[i], 20.0, 20000.0) : 0.0f;
            band.q = (float) elem(deqQs, i, 0.5, 8.0);
            band.gain = (float) elem(deqGains, i, -12.0, 12.0);
            band.threshold = (float) elem(deqThresholds, i, -80.0, 0.0);
            band.attack = (float) elem(deqAttacks, i, 1.0, 100.0);
            band.release = (float) elem(deqReleases, i, 10.0, 500.0);
            band.filterType = i < deqFilterTypes.length ? deqFilterTypes[i] : 0;
        }

        // —— convolver（wet/gain/routing/delay 无对应预设字段，保持上游结构默认） ——
        p.convolver.enable = b(preset, "convolver", "enable", false);
        p.convolver.crossChannel = (float) n(preset, "convolver", "crossChannel", 0.0, 0.0, 1.0);
        String kernelFile = preset.text("convolver", "kernelFile", "");

        // —— ddc ——
        p.ddc.enable = b(preset, "ddc", "enable", false);
        String ddcDevice = preset.text("ddc", "device", "");

        // —— fieldSurround ——
        p.fieldSurround.enable = b(preset, "fieldSurround", "enable", false);
        p.fieldSurround.widening = (float) n(preset, "fieldSurround", "widening", 0.0, 0.0, 8.0);
        p.fieldSurround.midImage = (float) n(preset, "fieldSurround", "midImage", 1.5, 1.0, 2.0);
        p.fieldSurround.depth = (short) (int) n(preset, "fieldSurround", "depth", 200, 200, 950);

        // —— diffSurround ——
        p.diffSurround.enable = b(preset, "diffSurround", "enable", false);
        p.diffSurround.delay = (float) n(preset, "diffSurround", "delay", 5.0, 1.0, 20.0);
        p.diffSurround.reverse = b(preset, "diffSurround", "reverse", false);
        p.diffSurround.wetDryMix = (float) n(preset, "diffSurround", "wetDryMix", 1.0, 0.0, 1.0);
        p.diffSurround.lpCutoff = (float) n(preset, "diffSurround", "lpCutoff", 0, 0, 20000);

        // —— stereoImager（宽度为 setter 域 ×100 补偿） ——
        p.stereoImager.enable = b(preset, "stereoImager", "enable", false);
        p.stereoImager.lowWidth =
                (float) (n(preset, "stereoImager", "lowWidth", 1.0, 0.0, 2.0) * 100.0);
        p.stereoImager.midWidth =
                (float) (n(preset, "stereoImager", "midWidth", 1.0, 0.0, 2.0) * 100.0);
        p.stereoImager.highWidth =
                (float) (n(preset, "stereoImager", "highWidth", 1.0, 0.0, 2.0) * 100.0);
        p.stereoImager.lowCrossover = (float) n(preset, "stereoImager", "lowCrossover", 200, 80, 400);
        p.stereoImager.highCrossover = (float) n(preset, "stereoImager", "highCrossover", 4000, 2000, 8000);

        // —— headphoneSurround ——
        p.headphoneSurround.enable = b(preset, "headphoneSurround", "enable", false);
        p.headphoneSurround.quality = (int) n(preset, "headphoneSurround", "quality", 0, 0, 4);

        // —— reverb ——
        // 上游 ReverbParams 五字段默认全为 0.0f（ViPERParams.h:400-406），这里逐项对齐。
        p.reverb.enable = b(preset, "reverb", "enable", false);
        p.reverb.roomSize = (float) n(preset, "reverb", "roomSize", 0.0, 0.0, 1.0);
        p.reverb.width = (float) n(preset, "reverb", "width", 0.0, 0.0, 1.0);
        p.reverb.damp = (float) n(preset, "reverb", "damp", 0.0, 0.0, 1.0);
        p.reverb.wet = (float) n(preset, "reverb", "wet", 0.0, 0.0, 1.0);
        p.reverb.dry = (float) n(preset, "reverb", "dry", 0.0, 0.0, 1.0);

        // —— dynamicSystem ——
        p.dynamicSystem.enable = b(preset, "dynamicSystem", "enable", false);
        p.dynamicSystem.strength = (float) n(preset, "dynamicSystem", "strength", 1.0, 1.0, 8.0);
        p.dynamicSystem.xCoeffLow = (int) n(preset, "dynamicSystem", "xLow", 100, 0, 2400);
        p.dynamicSystem.xCoeffHigh = (int) n(preset, "dynamicSystem", "xHigh", 5600, 0, 12000);
        p.dynamicSystem.yCoeffLow = (int) n(preset, "dynamicSystem", "yLow", 40, 0, 200);
        p.dynamicSystem.yCoeffHigh = (int) n(preset, "dynamicSystem", "yHigh", 80, 0, 300);
        p.dynamicSystem.sideGainLow = (float) n(preset, "dynamicSystem", "sideGainLow", 0.5, 0.0, 1.0);
        p.dynamicSystem.sideGainHigh = (float) n(preset, "dynamicSystem", "sideGainHigh", 0.5, 0.0, 1.0);

        // —— clarity ——
        p.clarity.enable = b(preset, "clarity", "enable", false);
        p.clarity.mode = (int) n(preset, "clarity", "mode", 0, NO_MIN, NO_MAX);
        p.clarity.gain = (float) n(preset, "clarity", "gain", 0.5, 0.0, 4.5);

        // —— cure ——
        p.cure.enable = b(preset, "cure", "enable", false);
        p.cure.crossfeedPreset = (int) n(preset, "cure", "crossfeedPreset", 0, NO_MIN, NO_MAX);

        // —— tubeSimulator ——
        p.tubeSimulator.enable = b(preset, "tubeSimulator", "enable", false);

        // —— analogX ——
        p.analogX.enable = b(preset, "analogX", "enable", false);
        p.analogX.mode = (int) n(preset, "analogX", "mode", 0, NO_MIN, NO_MAX);

        // —— speakerCorrection ——
        p.speakerCorrection.enable = b(preset, "speakerCorrection", "enable", false);

        // —— multibandCompressor（bandCount 由 bandEnables 长度决定，与 App 序列化一致） ——
        p.multibandCompressor.enable = b(preset, "multibandCompressor", "enable", false);
        boolean[] mbEnables = bArray(preset, "multibandCompressor", "bandEnables", true);
        p.multibandCompressor.bandCount = clampInt(mbEnables.length, 0, 5);
        int[] mbCrossovers = iArray(preset, "multibandCompressor", "crossovers",
                new int[] {120, 500, 4000, 8000});
        for (int i = 0; i < p.multibandCompressor.crossoverFrequencies.length; i++) {
            // 缺省位保持结构默认 0.0（对应 App 序列化器只写数组内元素、其余留 0）。
            p.multibandCompressor.crossoverFrequencies[i] =
                    i < mbCrossovers.length ? (float) clamp(mbCrossovers[i], 30.0, 16000.0) : 0.0f;
        }
        double[] mbThresholds = dArray(preset, "multibandCompressor", "thresholds", null);
        double[] mbRatios = dArray(preset, "multibandCompressor", "ratios", null);
        double[] mbGains = dArray(preset, "multibandCompressor", "gains", null);
        double[] mbKnees = dArray(preset, "multibandCompressor", "knees", null);
        double[] mbKneeMultis = dArray(preset, "multibandCompressor", "kneeMultis", null);
        double[] mbAttacks = dArray(preset, "multibandCompressor", "attacks", null);
        double[] mbMaxAttacks = dArray(preset, "multibandCompressor", "maxAttacks", null);
        double[] mbReleases = dArray(preset, "multibandCompressor", "releases", null);
        double[] mbMaxReleases = dArray(preset, "multibandCompressor", "maxReleases", null);
        double[] mbCrests = dArray(preset, "multibandCompressor", "crests", null);
        double[] mbAdapts = dArray(preset, "multibandCompressor", "adapts", null);
        boolean[] mbKneeAutos = bArray(preset, "multibandCompressor", "kneeAutos", true);
        boolean[] mbGainAutos = bArray(preset, "multibandCompressor", "gainAutos", true);
        boolean[] mbAttackAutos = bArray(preset, "multibandCompressor", "attackAutos", true);
        boolean[] mbReleaseAutos = bArray(preset, "multibandCompressor", "releaseAutos", true);
        boolean[] mbNoClips = bArray(preset, "multibandCompressor", "noClips", true);
        for (int i = 0; i < p.multibandCompressor.bands.length; i++) {
            ViPERParams.MultibandCompressorBandParams band = p.multibandCompressor.bands[i];
            // 数组内缺省位保持结构默认（0 / 0.0 / false）——对应 App 序列化器 getOrZero/getOrFalse。
            band.enable = i < mbEnables.length && mbEnables[i];
            band.threshold = (float) elem(mbThresholds, i, dbToRaw(-48.0), 0.0);
            band.ratio = (float) elem(mbRatios, i, -2.0, 0.0);
            band.knee = (float) elem(mbKnees, i, 0.0, dbToRaw(12.0));
            band.kneeAuto = i < mbKneeAutos.length && mbKneeAutos[i];
            band.gain = (float) elem(mbGains, i, 0.0, dbToRaw(24.0));
            band.gainAuto = i < mbGainAutos.length && mbGainAutos[i];
            band.attack = (float) elem(mbAttacks, i, 0.001, 0.100);
            band.attackAuto = i < mbAttackAutos.length && mbAttackAutos[i];
            band.release = (float) elem(mbReleases, i, 0.005, 0.500);
            band.releaseAuto = i < mbReleaseAutos.length && mbReleaseAutos[i];
            band.kneeMulti = (float) elem(mbKneeMultis, i, 0.0, 4.0);
            band.maxAttack = (float) elem(mbMaxAttacks, i, 0.001, 0.100);
            band.maxRelease = (float) elem(mbMaxReleases, i, 0.005, 0.500);
            band.crest = (float) elem(mbCrests, i, 0.005, 0.300);
            band.adapt = (float) elem(mbAdapts, i, 1.0, 16.0);
            band.noClip = i < mbNoClips.length && mbNoClips[i];
        }

        // —— 扩展：JamesDSP 混响（非上游 ViPER 组；sndfilter sf_reverb） ——
        // 默认走 19 个预设（preset 0..18）；custom=true 时改用 17 个自定义参数
        // （oversampleFactor + sf_advancereverb 的 16 个入口参数）。
        p.jdspReverb.enable = b(preset, "jdspReverb", "enable", false);
        p.jdspReverb.preset = (int) n(preset, "jdspReverb", "preset", 0.0, 0.0, 18.0);
        p.jdspReverb.custom = b(preset, "jdspReverb", "custom", false);
        p.jdspReverb.oversampleFactor =
                (int) n(preset, "jdspReverb", "oversampleFactor", 1.0, 1.0, 2.0);
        p.jdspReverb.ertolate = (float) n(preset, "jdspReverb", "ertolate", 0.40, 0.0, 1.0);
        p.jdspReverb.erefwet = (float) n(preset, "jdspReverb", "erefwet", -9.0, -60.0, 12.0);
        p.jdspReverb.dry = (float) n(preset, "jdspReverb", "dry", -7.0, -60.0, 12.0);
        p.jdspReverb.ereffactor = (float) n(preset, "jdspReverb", "ereffactor", 1.6, 0.1, 3.0);
        p.jdspReverb.erefwidth = (float) n(preset, "jdspReverb", "erefwidth", 0.7, -1.0, 1.0);
        p.jdspReverb.width = (float) n(preset, "jdspReverb", "width", 1.0, 0.0, 1.0);
        p.jdspReverb.wet = (float) n(preset, "jdspReverb", "wet", 0.0, -60.0, 12.0);
        p.jdspReverb.wander = (float) n(preset, "jdspReverb", "wander", 0.25, 0.0, 1.0);
        p.jdspReverb.bassb = (float) n(preset, "jdspReverb", "bassb", 0.15, 0.0, 1.0);
        p.jdspReverb.spin = (float) n(preset, "jdspReverb", "spin", 0.7, 0.0, 20.0);
        p.jdspReverb.inputlpf =
                (float) n(preset, "jdspReverb", "inputlpf", 17000.0, 100.0, 24000.0);
        p.jdspReverb.basslpf = (float) n(preset, "jdspReverb", "basslpf", 500.0, 100.0, 24000.0);
        p.jdspReverb.damplpf =
                (float) n(preset, "jdspReverb", "damplpf", 7000.0, 100.0, 24000.0);
        p.jdspReverb.outputlpf =
                (float) n(preset, "jdspReverb", "outputlpf", 10000.0, 100.0, 24000.0);
        p.jdspReverb.rt60 = (float) n(preset, "jdspReverb", "rt60", 3.2, 0.1, 30.0);
        p.jdspReverb.delay = (float) n(preset, "jdspReverb", "delay", 0.020, -0.1, 0.1);

        return new Mapped(p, kernelFile, ddcDevice);
    }

    // —— 读取工具 ——

    private static final double NO_MIN = Double.NaN;
    private static final double NO_MAX = Double.NaN;

    /** 读组内数值（缺省 fallback），并按 [min, max] 钳位（min/max 为 NaN 表示不钳）。 */
    private static double n(ViperPreset preset, String group, String field,
                            double fallback, double min, double max) {
        double value = preset.number(group, field, fallback);
        if (!Double.isFinite(value)) {
            value = fallback;
        }
        return clamp(value, min, max);
    }

    private static boolean b(ViperPreset preset, String group, String field, boolean fallback) {
        JsonObject g = preset.group(group);
        if (g == null) {
            return fallback;
        }
        JsonElement e = g.get(field);
        if (e == null || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return e.getAsJsonPrimitive().isBoolean()
                    ? e.getAsBoolean()
                    : e.getAsString().equalsIgnoreCase("true");
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    private static double[] dArray(ViperPreset preset, String group, String field) {
        return dArray(preset, group, field, null);
    }

    /** 读数值数组；非数组或缺失时返回 {@code fallback}（缺省元素以 0 补齐由调用方钳位规则决定）。 */
    private static double[] dArray(ViperPreset preset, String group, String field, double[] fallback) {
        JsonObject g = preset.group(group);
        JsonElement e = g == null ? null : g.get(field);
        if (e == null || !e.isJsonArray()) {
            return fallback == null ? new double[0] : fallback;
        }
        JsonArray array = e.getAsJsonArray();
        double[] out = new double[array.size()];
        for (int i = 0; i < array.size(); i++) {
            try {
                out[i] = array.get(i).getAsDouble();
            } catch (RuntimeException failure) {
                out[i] = 0.0;
            }
        }
        return out;
    }

    private static int[] iArray(ViperPreset preset, String group, String field, int[] fallback) {
        JsonObject g = preset.group(group);
        JsonElement e = g == null ? null : g.get(field);
        if (e == null || !e.isJsonArray()) {
            return fallback;
        }
        JsonArray array = e.getAsJsonArray();
        int[] out = new int[array.size()];
        for (int i = 0; i < array.size(); i++) {
            try {
                out[i] = (int) Math.round(array.get(i).getAsDouble());
            } catch (RuntimeException failure) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static boolean[] bArray(ViperPreset preset, String group, String field, boolean fillDefault) {
        JsonObject g = preset.group(group);
        JsonElement e = g == null ? null : g.get(field);
        if (e == null || !e.isJsonArray()) {
            return new boolean[0];
        }
        JsonArray array = e.getAsJsonArray();
        boolean[] out = new boolean[array.size()];
        for (int i = 0; i < array.size(); i++) {
            try {
                JsonElement item = array.get(i);
                out[i] = item.getAsJsonPrimitive().isBoolean()
                        ? item.getAsBoolean()
                        : item.getAsString().equalsIgnoreCase("true");
            } catch (RuntimeException failure) {
                out[i] = fillDefault;
            }
        }
        return out;
    }

    /** 数组元素：存在则钳位，缺省位返回结构默认 0.0（对应 App 序列化器 getOrZero）。 */
    private static double elem(double[] array, int index, double min, double max) {
        return index < array.length ? clamp(array[index], min, max) : 0.0;
    }

    private static boolean at(boolean[] array, int index, boolean fallback) {
        return index < array.length ? array[index] : fallback;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isNaN(min)) {
            value = Math.max(min, value);
        }
        if (!Double.isNaN(max)) {
            value = Math.min(max, value);
        }
        return value;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 官方压缩器的 dB → 原始量换算（{@code compressorDbToRaw}）。 */
    private static double dbToRaw(double db) {
        return db * Math.log(10.0) / 20.0;
    }
}
