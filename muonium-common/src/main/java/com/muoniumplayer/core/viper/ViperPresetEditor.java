package com.muoniumplayer.core.viper;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 预设参数编辑器：把 {@link ViperPresetSchema} 当表单元数据用，对一份归一化预设做
 * 「逐组逐字段」的类型化读写，写完立即落盘并让音频链生效。
 *
 * <p><b>编辑目标</b>：{@link #overActive()} 以当前选中的预设为底稿（没有选中就等价于
 * 一份全默认的「自定义」底稿）。所有修改都写在内存里的工作副本上；{@link #persist()}
 * 把它经过解析器做一次归一化 + 钳位后写回目标预设文件——「自定义」底稿第一次落盘时会
 * 自动被选中，让改动立刻可以被听见。{@link #saveAs(String)} 另存为新预设并切换编辑目标、
 * 同时选中它。</p>
 *
 * <p><b>与音频链的关系</b>：每次 {@link #persist()} 后调 {@link ViperRuntime#reload()}，
 * 参数在下一个音频缓冲生效——与官方 App「调完即听」的行为一致。拖动中的连续修改请用
 * quiet 写入（{@link #setNumberQuiet}），拖完再 {@link #persist()} 一次，避免每帧写盘。</p>
 *
 * <p><b>钳位</b>：写入即按 schema 量程钳位（{@code min > max} 的字段不钳）；落盘时的
 * 解析器往返再做第二道保险。数组字段本版不开放编辑（界面只读），随预设原样保留。</p>
 */
public final class ViperPresetEditor {

    /** 没有选中任何预设时的编辑底稿名。 */
    public static final String SCRATCH_NAME = "自定义";

    private static final ViperPresetStore STORE = new ViperPresetStore(ConfigPaths.VIPER_PRESETS);

    private ViperPreset preset;
    private ViperPresetStore store;
    private String targetName;
    /** 「自定义」底稿是否已经落盘并被选中（第一次 persist 时置位并选中）。 */
    private boolean scratchSelected;

    /** 落盘后的生效钩子；生产路径 = 重载运行时，测试替换为空操作以免碰全局引擎/配置。 */
    private Runnable reloadHook = ViperRuntime::reload;
    /** 选中预设的钩子；生产路径 = 写 settings 并重载。 */
    private java.util.function.Consumer<String> selectHook = ViperRuntime::selectPreset;

    /** 测试专用：替换生效/选中钩子（生产代码不要调）。 */
    void hooksForTest(Runnable reload, java.util.function.Consumer<String> select) {
        this.reloadHook = reload;
        this.selectHook = select;
    }

    private ViperPresetEditor(ViperPreset preset, String targetName, ViperPresetStore store) {
        this.preset = preset;
        this.targetName = targetName;
        this.store = store;
    }

    /** 以当前选中的预设为底稿；没有选中或加载失败时回退到全默认的「自定义」。 */
    public static ViperPresetEditor overActive() {
        String active = ViperRuntime.activePresetName();
        ViperPreset base = active == null ? null : STORE.load(active);
        if (base != null) {
            return new ViperPresetEditor(base, active, STORE);
        }
        return overDefaults();
    }

    /** 以全默认值（schema 2.1 缺省）为底稿，目标名为「自定义」。 */
    public static ViperPresetEditor overDefaults() {
        ViperPreset defaults = ViperPresetParser.parse("{\"schemaVersion\":2.1}", SCRATCH_NAME);
        return new ViperPresetEditor(defaults, SCRATCH_NAME, STORE);
    }

    /** 测试注入：指定底稿与目标目录。 */
    static ViperPresetEditor over(ViperPreset preset, String targetName, ViperPresetStore store) {
        return new ViperPresetEditor(preset, targetName, store);
    }

    // ──────────────────────────── 枚举（给表单用） ────────────────────────────

    /** 分组数（schema 顺序，与官方 App 的组序一致）。 */
    public int groupCount() {
        return ViperPresetSchema.groups().size();
    }

    public String groupKey(int groupIndex) {
        return ViperPresetSchema.groups().get(groupIndex).key;
    }

    public String groupDisplayName(int groupIndex) {
        return ViperTuningCatalog.groupDisplayName(groupKey(groupIndex));
    }

    public int fieldCount(int groupIndex) {
        return ViperPresetSchema.groups().get(groupIndex).fields.size();
    }

    /** 字段键。 */
    public String fieldKey(int groupIndex, int fieldIndex) {
        return field(groupIndex, fieldIndex).key;
    }

    /** 字段类型（BOOL / INT / FLOAT / STRING / 数组…）。 */
    public ViperFieldKind fieldKind(int groupIndex, int fieldIndex) {
        return ViperFieldKind.of(field(groupIndex, fieldIndex).kind);
    }

    /** 是否有量程（INT/FLOAT 专用）。 */
    public boolean fieldRanged(int groupIndex, int fieldIndex) {
        return field(groupIndex, fieldIndex).ranged();
    }

    public double fieldMin(int groupIndex, int fieldIndex) {
        return field(groupIndex, fieldIndex).min;
    }

    public double fieldMax(int groupIndex, int fieldIndex) {
        return field(groupIndex, fieldIndex).max;
    }

    /** 字段显示名（目录未收录时回退键名）。 */
    public String fieldDisplayName(int groupIndex, int fieldIndex) {
        return ViperTuningCatalog.fieldDisplayName(groupKey(groupIndex), fieldKey(groupIndex, fieldIndex));
    }

    /** 档位型整型字段的离散取值；未收录返回 {@code null}。 */
    public int[] fieldIntChoices(int groupIndex, int fieldIndex) {
        return ViperTuningCatalog.intChoices(groupKey(groupIndex), fieldKey(groupIndex, fieldIndex));
    }

    /** 档位取值的可读名（如 JamesDSP 混响预设名）；无名字时返回 {@code null}。 */
    public String fieldIntChoiceName(int groupIndex, int fieldIndex, int value) {
        return ViperTuningCatalog.intChoiceName(
                groupKey(groupIndex), fieldKey(groupIndex, fieldIndex), value);
    }

    /** 是否为官方遗留、映射器不消费的字段（界面只读展示）。 */
    public boolean fieldUnused(int groupIndex, int fieldIndex) {
        return ViperTuningCatalog.isUnusedField(groupKey(groupIndex), fieldKey(groupIndex, fieldIndex));
    }

    // ──────────────────────────── 读 ────────────────────────────

    public boolean getBoolean(int groupIndex, int fieldIndex, boolean fallback) {
        ViperPresetSchema.Field field = field(groupIndex, fieldIndex);
        JsonObject group = preset.group(groupKey(groupIndex));
        if (group == null || group.get(field.key) == null || !group.get(field.key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return group.get(field.key).getAsBoolean();
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    public double getNumber(int groupIndex, int fieldIndex, double fallback) {
        return preset.number(groupKey(groupIndex), fieldKey(groupIndex, fieldIndex), fallback);
    }

    public int getInt(int groupIndex, int fieldIndex, int fallback) {
        return (int) Math.round(getNumber(groupIndex, fieldIndex, fallback));
    }

    public String getString(int groupIndex, int fieldIndex, String fallback) {
        return preset.text(groupKey(groupIndex), fieldKey(groupIndex, fieldIndex), fallback);
    }

    // ──────────────────────────── 写 ────────────────────────────

    /** 写布尔：立即落盘并重载。 */
    public void setBoolean(int groupIndex, int fieldIndex, boolean value) {
        setRaw(groupIndex, fieldIndex, value);
        persist();
    }

    /** 写数值（int/float 通用，按字段类型取整/钳位）：立即落盘并重载。 */
    public void setNumber(int groupIndex, int fieldIndex, double value) {
        setNumberQuiet(groupIndex, fieldIndex, value);
        persist();
    }

    /** 拖动中的静默写入：只改工作副本，不落盘（拖完调 {@link #persist()}）。 */
    public void setNumberQuiet(int groupIndex, int fieldIndex, double value) {
        setRaw(groupIndex, fieldIndex, value);
    }

    /** 写字符串：立即落盘并重载。 */
    public void setString(int groupIndex, int fieldIndex, String value) {
        setRaw(groupIndex, fieldIndex, value);
        persist();
    }

    /** 把一组恢复为 schema 默认值：立即落盘并重载。 */
    public void resetGroup(int groupIndex) {
        ViperPresetSchema.Group group = ViperPresetSchema.groups().get(groupIndex);
        JsonObject body = groupBody(groupKey(groupIndex));
        for (ViperPresetSchema.Field field : group.fields) {
            writeDefault(body, field);
        }
        persist();
    }

    // ──────────────────────────── 目标与落盘 ────────────────────────────

    /** 当前编辑目标（保存到的预设名）。 */
    public String targetName() {
        return targetName;
    }

    /** 是否为「自定义」底稿（尚未与任何既有预设绑定）。 */
    public boolean isScratch() {
        return SCRATCH_NAME.equals(targetName) && !scratchSelected;
    }

    /**
     * 落盘并让音频链生效。目标为「自定义」底稿的第一次落盘会同时选中它——
     * 调参立刻可听；其余情况只重载当前选中预设。
     */
    public void persist() {
        try {
            ViperPreset normalized = ViperPresetParser.parse(
                    ViperPresetParser.toJsonText(preset), targetName);
            store.save(new ViperPreset(targetName, normalized.json()));
        } catch (RuntimeException failure) {
            System.out.println("[MuoniumPlayer/ViPER] 调参保存失败（" + targetName + "）：" + failure);
            return;
        }
        if (isScratch()) {
            selectHook.accept(targetName);
            scratchSelected = true;
            return;
        }
        reloadHook.run();
    }

    /**
     * 另存为新预设：切换编辑目标、落盘并选中它（调参立刻可听）。失败时保持原目标。
     *
     * @return 实际使用的预设名（经文件基名净化）
     */
    public String saveAs(String rawName) {
        String name = ViperPresetStore.sanitizeName(rawName);
        if (name.isEmpty()) {
            return targetName;
        }
        String previous = targetName;
        targetName = name;
        try {
            ViperPreset normalized = ViperPresetParser.parse(
                    ViperPresetParser.toJsonText(preset), name);
            store.save(new ViperPreset(name, normalized.json()));
        } catch (RuntimeException failure) {
            targetName = previous;
            System.out.println("[MuoniumPlayer/ViPER] 另存预设失败（" + name + "）：" + failure);
            return targetName;
        }
        scratchSelected = true;
        selectHook.accept(name);
        return name;
    }

    // ──────────────────────────── 文件清单（选择器数据源） ────────────────────────────

    /** 内核目录里可用的脉冲响应文件（.irs / .wav，按名排序）。 */
    public List<String> kernelFiles() {
        return listFiles("kernels", new String[] {".irs", ".wav"});
    }

    /** DDC 目录里可用的系数设备名（.vdc 去扩展名，按名排序）。 */
    public List<String> ddcDevices() {
        List<String> names = new ArrayList<>();
        File dir = new File(ConfigPaths.VIPER_DIR, "ddc");
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (file.isFile() && name.toLowerCase().endsWith(".vdc")) {
                    names.add(name.substring(0, name.length() - 4));
                }
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private List<String> listFiles(String subDirectory, String[] extensions) {
        List<String> names = new ArrayList<>();
        File dir = new File(ConfigPaths.VIPER_DIR, subDirectory);
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                String lower = file.getName().toLowerCase();
                for (String extension : extensions) {
                    if (lower.endsWith(extension)) {
                        names.add(file.getName());
                        break;
                    }
                }
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    // ──────────────────────────── 内部 ────────────────────────────

    private ViperPresetSchema.Field field(int groupIndex, int fieldIndex) {
        return ViperPresetSchema.groups().get(groupIndex).fields.get(fieldIndex);
    }

    /** 底稿里的一个分组体（界面读数组长度等只读信息用）；不存在返回 {@code null}。 */
    public JsonObject presetGroup(String groupKey) {
        return preset.group(groupKey);
    }

    private JsonObject groupBody(String groupKey) {
        JsonObject group = preset.group(groupKey);
        if (group == null) {
            group = new JsonObject();
            preset.json().add(groupKey, group);
        }
        return group;
    }

    private void setRaw(int groupIndex, int fieldIndex, Object value) {
        ViperPresetSchema.Field field = field(groupIndex, fieldIndex);
        JsonObject group = groupBody(groupKey(groupIndex));
        if (field.ranged() && value instanceof Double) {
            double clamped = Math.max(field.min, Math.min(field.max, (Double) value));
            value = field.kind == ViperPresetSchema.Kind.INT ? (int) Math.round(clamped) : clamped;
        }
        if (value instanceof Boolean) {
            group.addProperty(field.key, (Boolean) value);
        } else if (value instanceof Integer) {
            group.addProperty(field.key, (Number) value);
        } else if (value instanceof Double) {
            group.addProperty(field.key, (Number) value);
        } else {
            group.addProperty(field.key, String.valueOf(value));
        }
    }

    private void writeDefault(JsonObject body, ViperPresetSchema.Field field) {
        Object value = field.defaultValue;
        if (value instanceof Boolean) {
            body.addProperty(field.key, (Boolean) value);
        } else if (value instanceof Integer) {
            body.addProperty(field.key, (Number) value);
        } else if (value instanceof Double) {
            body.addProperty(field.key, (Number) value);
        } else if (value instanceof String) {
            body.addProperty(field.key, (String) value);
        }
        // 数组 / 可空字段的默认值不在本版编辑范围，保持原样。
    }
}
