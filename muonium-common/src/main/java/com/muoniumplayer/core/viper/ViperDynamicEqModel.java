package com.muoniumplayer.core.viper;

import java.util.Arrays;
import java.util.Objects;

/**
 * 动态 EQ 的内存编辑模型，提供完整活动数组与安全参数值，不修改 DSP 或音频线程。
 * Getter 只生成安全视图；所有写入先全量校验，再同步补齐/写回七项数组。
 */
public final class ViperDynamicEqModel {

    private static final String[] FIELDS = {
            "freqs", "qs", "gains", "thresholds", "attacks", "releases", "filterTypes"
    };
    private static final int Q_INDEX = 1;
    private static final int FILTER_INDEX = 6;

    private final ViperPresetEditor editor;
    private final int group;
    private final int countField;
    private final int enableField;
    private final int[] fieldIndices = new int[FIELDS.length];
    private final ViperPresetSchema.Field[] definitions = new ViperPresetSchema.Field[FIELDS.length];

    /** 只验证内存，短数组/旧类型码不会在构造或 getter 时写回。 */
    public ViperDynamicEqModel(ViperPresetEditor editor) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.group = editor.groupIndex("dynamicEq");
        this.countField = requiredField("bandCount", ViperFieldKind.INT);
        this.enableField = requiredField("enable", ViperFieldKind.BOOL);
        ViperPresetSchema.Group schema = ViperPresetSchema.group("dynamicEq");
        for (int index = 0; index < FIELDS.length; index++) {
            ViperFieldKind kind = index == 0 || index == FILTER_INDEX
                    ? ViperFieldKind.INT_ARRAY : ViperFieldKind.FLOAT_ARRAY;
            fieldIndices[index] = requiredField(FIELDS[index], kind);
            definitions[index] = schema.fields.get(fieldIndices[index]);
        }
        preparedArrays(bandCount());
    }

    public int bandCount() {
        double count = editor.getNumber(group, countField, Double.NaN);
        if (!Double.isFinite(count) || count != Math.rint(count) || count < 1 || count > 10) {
            throw new IllegalArgumentException("动态 EQ 段数必须为 1..10 的整数");
        }
        return (int) count;
    }

    /** 调整段数并同步全部活动数组；新增位置取 schema 默认，超出默认长度延伸末项。 */
    public void setBandCountQuiet(int count) {
        if (count < 1 || count > 10) {
            throw new IllegalArgumentException("动态 EQ 段数必须为 1..10");
        }
        bandCount();
        double[][] arrays = preparedArrays(count);
        writeArrays(arrays);
        editor.setNumberQuiet(group, countField, count);
    }

    /** 七项数组字段的安全编辑视图；filterTypes 返回 canonical 0/1/2。 */
    public double value(String field, int band) {
        int index = fieldIndex(field);
        int count = bandCount();
        checkBand(band, count);
        return preparedArrays(count)[index][band];
    }

    /** 参数按 schema 钳位；shelf 的 Q 实为斜率，只允许 .5..1。 */
    public void setValueQuiet(String field, int band, double value) {
        int index = fieldIndex(field);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("动态 EQ 参数必须有限，不能为 NaN/Infinity");
        }
        int count = bandCount();
        checkBand(band, count);
        if (index == FILTER_INDEX && (value != Math.rint(value) || value < 0 || value > 2)) {
            throw new IllegalArgumentException("动态 EQ 滤波类型写入只接受 0/1/2");
        }
        double[][] arrays = preparedArrays(count);
        arrays[index][band] = normalize(definitions[index], value);
        constrainShelves(arrays);
        writeArrays(arrays);
    }

    /** 兼容导入的 5/6/7，返回 0=peak、1=low shelf、2=high shelf。 */
    public int filterType(int band) {
        return (int) value("filterTypes", band);
    }

    /** UI 只写 canonical 0/1/2；切换 shelf 时同时钳位该段 Q/斜率。 */
    public void setFilterTypeQuiet(int band, int type) {
        setValueQuiet("filterTypes", band, type);
    }

    public boolean enabled() {
        return editor.getBoolean(group, enableField, false);
    }

    /** 启用前同样保证活动数组完整、安全；不启动运行时或保存文件。 */
    public void setEnabledQuiet(boolean enabled) {
        double[][] arrays = preparedArrays(bandCount());
        writeArrays(arrays);
        editor.setBooleanQuiet(group, enableField, enabled);
    }

    private int requiredField(String key, ViperFieldKind kind) {
        int field = editor.fieldIndex(group, key);
        if (field < 0 || editor.fieldKind(group, field) != kind) {
            throw new IllegalStateException("动态 EQ schema 字段缺失或类型不匹配：" + key);
        }
        return field;
    }

    private static int fieldIndex(String field) {
        for (int index = 0; index < FIELDS.length; index++) {
            if (FIELDS[index].equals(field)) {
                return index;
            }
        }
        throw new IllegalArgumentException("未知动态 EQ 数组字段：" + field);
    }

    private double[][] preparedArrays(int count) {
        double[][] arrays = new double[FIELDS.length][];
        for (int index = 0; index < FIELDS.length; index++) {
            double[] source = editor.getArray(group, fieldIndices[index]);
            double[] values = Arrays.copyOf(source, count);
            for (int band = 0; band < count; band++) {
                double value = band < source.length ? source[band] : defaultValue(definitions[index], band);
                values[band] = index == FILTER_INDEX ? canonicalFilterType(value) : normalize(definitions[index], value);
            }
            arrays[index] = values;
        }
        constrainShelves(arrays);
        return arrays;
    }

    private void writeArrays(double[][] arrays) {
        // preparedArrays / setValue 已验证所有数据。编辑器线程独占，以下替换不会半途类型/finite失败。
        for (int index = 0; index < arrays.length; index++) {
            editor.setArrayQuiet(group, fieldIndices[index], arrays[index]);
        }
    }

    private static double defaultValue(ViperPresetSchema.Field field, int band) {
        if (field.kind == ViperPresetSchema.Kind.INT_ARRAY) {
            int[] values = (int[]) field.defaultValue;
            return values[Math.min(band, values.length - 1)];
        }
        double[] values = (double[]) field.defaultValue;
        return values[Math.min(band, values.length - 1)];
    }

    private static double normalize(ViperPresetSchema.Field field, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("动态 EQ 参数必须有限：" + field.key);
        }
        double normalized = field.ranged() ? Math.max(field.min, Math.min(field.max, value)) : value;
        return field.kind == ViperPresetSchema.Kind.INT_ARRAY ? Math.round(normalized) : normalized;
    }

    private static int canonicalFilterType(double type) {
        if (type != Math.rint(type)) {
            throw new IllegalArgumentException("动态 EQ 滤波类型必须为整数");
        }
        if (type == 0 || type == 5) return 0;
        if (type == 1 || type == 6) return 1;
        if (type == 2 || type == 7) return 2;
        throw new IllegalArgumentException("不支持的动态 EQ 滤波类型：" + type);
    }

    private static void constrainShelves(double[][] arrays) {
        for (int band = 0; band < arrays[Q_INDEX].length; band++) {
            if (arrays[FILTER_INDEX][band] != 0) {
                // MultiBiquad shelf sqrt 含 (1/Q-1)；Q <= 1 避免任意增益下出现负根号。
                arrays[Q_INDEX][band] = Math.max(0.5, Math.min(1.0, arrays[Q_INDEX][band]));
            }
        }
    }

    private static void checkBand(int band, int count) {
        if (band < 0 || band >= count) {
            throw new IndexOutOfBoundsException("动态 EQ 频段下标：" + band + "，段数 " + count);
        }
    }
}
