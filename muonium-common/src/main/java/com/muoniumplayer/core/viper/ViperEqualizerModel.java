package com.muoniumplayer.core.viper;

import com.muoniumplayer.core.viper.dsp.MinPhaseIIRCoeffs;

import java.util.Arrays;
import java.util.Objects;

/**
 * 图形 EQ 的纯内存编辑模型。只修改 equalizer 已知字段，不触碰其它效果或运行时。
 * 增益参数按真实中心频点的 log(Hz) 插值；此参数线不代表并联滤组的精确频响。
 */
public final class ViperEqualizerModel {

    private static final double MATCH_TOLERANCE_DB = 1e-6;

    private final ViperPresetEditor editor;
    private final int group;
    private final int enableField;
    private final int countField;
    private final int gainsField;
    private final int presetField;

    /** 只核对内存状态，不读磁盘、不修改底稿；非法段数/非有限增益明确拒绝。 */
    public ViperEqualizerModel(ViperPresetEditor editor) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.group = editor.groupIndex("equalizer");
        this.enableField = requiredField("enable", ViperFieldKind.BOOL);
        this.countField = requiredField("bandCount", ViperFieldKind.INT);
        this.gainsField = requiredField("bands", ViperFieldKind.DOUBLE_ARRAY);
        this.presetField = requiredField("presetId", ViperFieldKind.NULLABLE_LONG);
        bandCount();
        gains();
    }

    public static int[] supportedBandCounts() {
        return MinPhaseIIRCoeffs.supportedBandCounts();
    }

    public double[] frequencies() {
        return MinPhaseIIRCoeffs.frequenciesForBandCount(bandCount());
    }

    /**
     * 当前活动频段的独立副本。短数组保留已有值，缺少的段按引擎零增益默认补齐；
     * 不写回工作副本，超出活动段数的已有值不参与此视图。
     */
    public double[] gains() {
        int count = bandCount();
        return Arrays.copyOf(editor.getArray(group, gainsField), count);
    }

    public int bandCount() {
        double value = editor.getNumber(group, countField, Double.NaN);
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("EQ 段数必须是支持的整数");
        }
        int count = (int) value;
        MinPhaseIIRCoeffs.frequenciesForBandCount(count);
        return count;
    }

    public boolean enabled() {
        return editor.getBoolean(group, enableField, false);
    }

    public void setEnabledQuiet(boolean enabled) {
        editor.setBooleanQuiet(group, enableField, enabled);
    }

    public void setBandGainQuiet(int band, double gain) {
        requireFinite(gain);
        double[] gains = gains();
        if (band < 0 || band >= gains.length) {
            throw new IndexOutOfBoundsException("EQ 频段下标：" + band);
        }
        gains[band] = gain;
        setGainsQuiet(gains);
    }

    /** 更换段数时沿 log(Hz) 保留增益参数线；超出原频域的端点保持原端点值。 */
    public void setBandCountQuiet(int count) {
        double[] destination = MinPhaseIIRCoeffs.frequenciesForBandCount(count);
        int previous = bandCount();
        if (previous == count) {
            return;
        }
        double[] values = resample(MinPhaseIIRCoeffs.frequenciesForBandCount(previous), gains(), destination);
        editor.setArrayQuiet(group, gainsField, values);
        editor.setNumberQuiet(group, countField, count);
        editor.setNullQuiet(group, presetField);
    }

    /** 长度须等于当前段数；全量验证后按 schema 逐元素钳位到 ±12 dB。 */
    public void setGainsQuiet(double[] gains) {
        if (gains == null || gains.length != bandCount()) {
            throw new IllegalArgumentException("EQ 增益数组长度必须等于当前段数");
        }
        for (double gain : gains) {
            requireFinite(gain);
        }
        editor.setArrayQuiet(group, gainsField, gains);
        editor.setNullQuiet(group, presetField);
    }

    /** 仅 patch equalizer，按当前段数重采样，启用 EQ 并清除官方 presetId。 */
    public void applyPresetQuiet(String id) {
        ViperEqPresets.Preset preset = null;
        for (ViperEqPresets.Preset candidate : ViperEqPresets.all()) {
            if (candidate.id.equals(id)) {
                preset = candidate;
                break;
            }
        }
        if (preset == null) {
            throw new IllegalArgumentException("未知 EQ 起始预设：" + id);
        }
        double[] values = resample(ViperEqPresets.baseFrequencies(), preset.gains(), frequencies());
        setGainsQuiet(values);
        setEnabledQuiet(true);
    }

    /** 保留 enable 和 bandCount，仅清零活动增益并清除 presetId。 */
    public void flattenQuiet() {
        setGainsQuiet(new double[bandCount()]);
    }

    /** 按当前段数的重采样值匹配温和起始预设；手动曲线返回 null。 */
    public String matchingPresetId() {
        double[] frequencies = frequencies();
        double[] values = gains();
        for (ViperEqPresets.Preset preset : ViperEqPresets.all()) {
            double[] expected = resample(ViperEqPresets.baseFrequencies(), preset.gains(), frequencies);
            boolean matching = true;
            for (int band = 0; band < values.length; band++) {
                if (Math.abs(values[band] - expected[band]) > MATCH_TOLERANCE_DB) {
                    matching = false;
                    break;
                }
            }
            if (matching) {
                return preset.id;
            }
        }
        return null;
    }

    private int requiredField(String key, ViperFieldKind kind) {
        int field = editor.fieldIndex(group, key);
        if (field < 0 || editor.fieldKind(group, field) != kind) {
            throw new IllegalStateException("EQ schema 字段缺失或类型不匹配：" + key);
        }
        return field;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("EQ 增益必须有限，不能为 NaN/Infinity");
        }
    }

    private static double[] resample(double[] sourceFrequencies, double[] sourceGains,
                                     double[] destinationFrequencies) {
        if (sourceFrequencies.length == 0 || sourceFrequencies.length != sourceGains.length) {
            throw new IllegalArgumentException("EQ 重采样源数组不一致");
        }
        double[] values = new double[destinationFrequencies.length];
        int last = sourceFrequencies.length - 1;
        int left = 0;
        for (int band = 0; band < values.length; band++) {
            double frequency = destinationFrequencies[band];
            if (frequency <= sourceFrequencies[0]) {
                values[band] = sourceGains[0];
            } else if (frequency >= sourceFrequencies[last]) {
                values[band] = sourceGains[last];
            } else {
                while (left + 1 < last && sourceFrequencies[left + 1] < frequency) {
                    left++;
                }
                double fraction = Math.log(frequency / sourceFrequencies[left])
                        / Math.log(sourceFrequencies[left + 1] / sourceFrequencies[left]);
                values[band] = sourceGains[left] + fraction * (sourceGains[left + 1] - sourceGains[left]);
            }
        }
        return values;
    }
}
