package com.muoniumplayer.core.viper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Schema 驱动的预设工作副本。Quiet 写入只编辑内存；checked 保存成功后才调用生效钩子。
 *
 * <p>编辑器是线程独占的：界面拖动时用 quiet API，松手时在界面线程取得 {@link #copy()}，
 * 再把独立副本交给串行后台保存。后台不能读写界面正在使用的编辑器；保存回执可以通过
 * {@link #acknowledgeCommit(ViperPresetEditor)} 确认首次保存，不覆盖更晚的 quiet 修改。</p>
 */
public final class ViperPresetEditor {

    public static final String SCRATCH_NAME = "自定义";

    // 测试注入和纯内存 copy 不应因类初始化触发 ConfigPaths 的历史文件迁移。
    private static final class DefaultStore {
        private static final ViperPresetStore INSTANCE = new ViperPresetStore(ConfigPaths.VIPER_PRESETS);
    }

    /** 只有保存侧使用；同一新建会话的多个独立副本共享文件所有权，不共享 JSON。 */
    private static final class ScratchSession {
        private final boolean collisionSafe;
        private String lastSavedText;
        private boolean selected;

        private ScratchSession(boolean collisionSafe) {
            this.collisionSafe = collisionSafe;
        }
    }

    private ViperPreset preset;
    private final ViperPresetStore store;
    private String targetName;
    private boolean scratchTarget;
    private boolean scratchSelected;
    private ScratchSession scratchSession;

    private Runnable reloadHook = ViperRuntime::reload;
    private Consumer<String> selectHook = ViperRuntime::selectPreset;

    /**
     * 选择 checked 设置发布路径；仅替换钩子，不读取配置、不写盘、不启动音频引擎。
     * 工作台在后台加载/新建后显式调用，其它调用方保留原默认行为。
     */
    public void useCheckedRuntimeHooks() {
        selectHook = name -> ViperRuntime.applyState(ViperRuntime.isEnabled(), name);
        reloadHook = () -> ViperRuntime.applyState(ViperRuntime.isEnabled(), ViperRuntime.activePresetName());
    }

    /** 测试专用：不触碰全局引擎/配置。 */
    void hooksForTest(Runnable reload, Consumer<String> select) {
        this.reloadHook = Objects.requireNonNull(reload, "reload");
        this.selectHook = Objects.requireNonNull(select, "select");
    }

    private ViperPresetEditor(ViperPreset preset, String targetName, ViperPresetStore store,
                              boolean scratchTarget, ScratchSession scratchSession) {
        this.targetName = ViperPresetStore.sanitizeName(targetName);
        this.preset = normalizedCopy(Objects.requireNonNull(preset, "preset"), this.targetName);
        this.store = Objects.requireNonNull(store, "store");
        this.scratchTarget = scratchTarget;
        this.scratchSession = scratchSession;
    }

    /** 后台加载入口；未选中或加载失败时使用不会覆盖既有预设的新底稿。 */
    public static ViperPresetEditor overActive() {
        ViperPresetStore store = DefaultStore.INSTANCE;
        String active = ViperRuntime.activePresetName();
        ViperPreset base = active == null ? null : store.load(active);
        if (base != null) {
            return new ViperPresetEditor(base, active, store, false, null);
        }
        return newScratch(store);
    }

    /** 兼容旧入口：固定以「自定义」为目标；新建界面应优先使用 {@link #newScratch()}。 */
    public static ViperPresetEditor overDefaults() {
        return new ViperPresetEditor(defaultPreset(SCRATCH_NAME), SCRATCH_NAME, DefaultStore.INSTANCE,
                true, new ScratchSession(false));
    }

    /**
     * 新建底稿，扫描目录选择空缺名（自定义、自定义（2）……）；应在后台调用。
     * 首存不覆盖后来占用此名称的文件，同一会话后续副本可继续保存自己创建的文件。
     */
    public static ViperPresetEditor newScratch() {
        return newScratch(DefaultStore.INSTANCE);
    }

    /** 测试注入：扫描只发生在给定目录。 */
    static ViperPresetEditor newScratch(ViperPresetStore store) {
        Objects.requireNonNull(store, "store");
        File directory = store.directory();
        Set<String> occupied = new HashSet<>();
        if (directory.exists()) {
            File[] entries = directory.listFiles();
            if (entries == null) {
                throw new IllegalStateException("无法扫描预设目录：" + directory);
            }
            for (File entry : entries) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".json") || name.endsWith(".xml")) {
                    occupied.add(name.substring(0, name.lastIndexOf('.')));
                }
            }
        }
        String name = SCRATCH_NAME;
        for (int suffix = 2; occupied.contains(name.toLowerCase(Locale.ROOT)); suffix++) {
            name = SCRATCH_NAME + "（" + suffix + "）";
        }
        return new ViperPresetEditor(defaultPreset(name), name, store, true, new ScratchSession(true));
    }

    /** 测试注入；构造只归一化独立 JSON，不读取目标目录或配置。 */
    static ViperPresetEditor over(ViperPreset preset, String targetName, ViperPresetStore store) {
        boolean scratch = SCRATCH_NAME.equals(ViperPresetStore.sanitizeName(targetName));
        return new ViperPresetEditor(preset, targetName, store, scratch,
                scratch ? new ScratchSession(false) : null);
    }

    /** 归一化、深复制工作副本和目标/钩子状态；不读取任何配置或文件。 */
    public ViperPresetEditor copy() {
        ViperPresetEditor copy = new ViperPresetEditor(preset, targetName, store, scratchTarget, scratchSession);
        copy.scratchSelected = scratchSelected;
        copy.reloadHook = reloadHook;
        copy.selectHook = selectHook;
        return copy;
    }

    /** 只确认同目标的成功首次保存；保留本编辑器更晚的 quiet 修改。 */
    public void acknowledgeCommit(ViperPresetEditor committed) {
        Objects.requireNonNull(committed, "committed");
        if (targetName.equals(committed.targetName) && committed.scratchSelected) {
            scratchSelected = true;
        }
    }

    // ──────────────────────────── 元数据 ────────────────────────────

    public int groupCount() {
        return ViperPresetSchema.groups().size();
    }

    /** 找不到（包括 null）返回 -1。 */
    public int groupIndex(String key) {
        for (int index = 0; index < groupCount(); index++) {
            if (groupKey(index).equals(key)) {
                return index;
            }
        }
        return -1;
    }

    /** 找不到或分组下标无效返回 -1。 */
    public int fieldIndex(int groupIndex, String key) {
        if (groupIndex < 0 || groupIndex >= groupCount()) {
            return -1;
        }
        for (int index = 0; index < fieldCount(groupIndex); index++) {
            if (fieldKey(groupIndex, index).equals(key)) {
                return index;
            }
        }
        return -1;
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

    public String fieldKey(int groupIndex, int fieldIndex) {
        return field(groupIndex, fieldIndex).key;
    }

    public ViperFieldKind fieldKind(int groupIndex, int fieldIndex) {
        return ViperFieldKind.of(field(groupIndex, fieldIndex).kind);
    }

    public boolean fieldRanged(int groupIndex, int fieldIndex) {
        return field(groupIndex, fieldIndex).ranged();
    }

    public double fieldMin(int groupIndex, int fieldIndex) {
        return field(groupIndex, fieldIndex).min;
    }

    public double fieldMax(int groupIndex, int fieldIndex) {
        return field(groupIndex, fieldIndex).max;
    }

    public String fieldDisplayName(int groupIndex, int fieldIndex) {
        return ViperTuningCatalog.fieldDisplayName(groupKey(groupIndex), fieldKey(groupIndex, fieldIndex));
    }

    public int[] fieldIntChoices(int groupIndex, int fieldIndex) {
        return ViperTuningCatalog.intChoices(groupKey(groupIndex), fieldKey(groupIndex, fieldIndex));
    }

    public String fieldIntChoiceName(int groupIndex, int fieldIndex, int value) {
        return ViperTuningCatalog.intChoiceName(groupKey(groupIndex), fieldKey(groupIndex, fieldIndex), value);
    }

    public boolean fieldUnused(int groupIndex, int fieldIndex) {
        return ViperTuningCatalog.isUnusedField(groupKey(groupIndex), fieldKey(groupIndex, fieldIndex));
    }

    // ──────────────────────────── 内存读取 ────────────────────────────

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

    /** 数值数组的独立副本；BOOL_ARRAY 请使用 {@link #getBooleanArray(int, int)}。 */
    public double[] getArray(int groupIndex, int fieldIndex) {
        ViperPresetSchema.Field field = numericArrayField(groupIndex, fieldIndex);
        JsonArray array = arrayBody(groupIndex, field);
        double[] values = new double[array.size()];
        for (int index = 0; index < values.length; index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalStateException("数组包含非数值：" + field.key + "[" + index + "]");
            }
            double value = element.getAsDouble();
            requireFinite(value);
            if (field.kind == ViperPresetSchema.Kind.INT_ARRAY
                    && (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)) {
                throw new IllegalStateException("数组包含非整型值：" + field.key + "[" + index + "]");
            }
            values[index] = value;
        }
        return values;
    }

    public boolean[] getBooleanArray(int groupIndex, int fieldIndex) {
        ViperPresetSchema.Field field = requireKind(groupIndex, fieldIndex, ViperPresetSchema.Kind.BOOL_ARRAY);
        JsonArray array = arrayBody(groupIndex, field);
        boolean[] values = new boolean[array.size()];
        for (int index = 0; index < values.length; index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalStateException("数组包含非布尔值：" + field.key + "[" + index + "]");
            }
            values[index] = element.getAsBoolean();
        }
        return values;
    }

    // ──────────────────────────── 类型化写入 ────────────────────────────

    public void setBoolean(int groupIndex, int fieldIndex, boolean value) {
        setBooleanQuiet(groupIndex, fieldIndex, value);
        persist();
    }

    public void setBooleanQuiet(int groupIndex, int fieldIndex, boolean value) {
        ViperPresetSchema.Field field = requireKind(groupIndex, fieldIndex, ViperPresetSchema.Kind.BOOL);
        groupBody(groupKey(groupIndex)).addProperty(field.key, value);
    }

    public void setNumber(int groupIndex, int fieldIndex, double value) {
        setNumberQuiet(groupIndex, fieldIndex, value);
        persist();
    }

    /** INT/FLOAT/可空整数的数值写入；非有限输入拒绝，不修改工作副本。 */
    public void setNumberQuiet(int groupIndex, int fieldIndex, double value) {
        ViperPresetSchema.Field field = field(groupIndex, fieldIndex);
        if (field.kind != ViperPresetSchema.Kind.INT && field.kind != ViperPresetSchema.Kind.FLOAT
                && field.kind != ViperPresetSchema.Kind.NULLABLE_LONG) {
            throw wrongKind(field, "数值标量");
        }
        Number normalized = numericValue(field, value);
        groupBody(groupKey(groupIndex)).addProperty(field.key, normalized);
    }

    public void setString(int groupIndex, int fieldIndex, String value) {
        setStringQuiet(groupIndex, fieldIndex, value);
        persist();
    }

    public void setStringQuiet(int groupIndex, int fieldIndex, String value) {
        ViperPresetSchema.Field field = requireKind(groupIndex, fieldIndex, ViperPresetSchema.Kind.STRING);
        if (value == null) {
            throw new IllegalArgumentException("字符串不可为 null：" + field.key);
        }
        groupBody(groupKey(groupIndex)).addProperty(field.key, value);
    }

    /** 先验证、逐元素钳位整个数组，再一次替换；不保存输入数组引用。 */
    public void setArrayQuiet(int groupIndex, int fieldIndex, double[] values) {
        ViperPresetSchema.Field field = numericArrayField(groupIndex, fieldIndex);
        if (values == null) {
            throw new IllegalArgumentException("数组不可为 null：" + field.key);
        }
        JsonArray array = new JsonArray(values.length);
        for (double value : values) {
            array.add(numericValue(field, value));
        }
        groupBody(groupKey(groupIndex)).add(field.key, array);
    }

    public void setArrayElementQuiet(int groupIndex, int fieldIndex, int elementIndex, double value) {
        numericArrayField(groupIndex, fieldIndex);
        requireFinite(value);
        double[] values = getArray(groupIndex, fieldIndex);
        checkElementIndex(elementIndex, values.length);
        values[elementIndex] = value;
        setArrayQuiet(groupIndex, fieldIndex, values);
    }

    public void setBooleanArrayQuiet(int groupIndex, int fieldIndex, boolean[] values) {
        ViperPresetSchema.Field field = requireKind(groupIndex, fieldIndex, ViperPresetSchema.Kind.BOOL_ARRAY);
        if (values == null) {
            throw new IllegalArgumentException("数组不可为 null：" + field.key);
        }
        JsonArray array = new JsonArray(values.length);
        for (boolean value : values) {
            array.add(value);
        }
        groupBody(groupKey(groupIndex)).add(field.key, array);
    }

    public void setBooleanArrayElementQuiet(int groupIndex, int fieldIndex, int elementIndex, boolean value) {
        boolean[] values = getBooleanArray(groupIndex, fieldIndex);
        checkElementIndex(elementIndex, values.length);
        values[elementIndex] = value;
        setBooleanArrayQuiet(groupIndex, fieldIndex, values);
    }

    public void setNullQuiet(int groupIndex, int fieldIndex) {
        ViperPresetSchema.Field field = requireKind(groupIndex, fieldIndex, ViperPresetSchema.Kind.NULLABLE_LONG);
        groupBody(groupKey(groupIndex)).add(field.key, JsonNull.INSTANCE);
    }

    /** 包括数组、布尔数组与 null；schema 的默认数组不会与工作副本共享。 */
    public void resetFieldQuiet(int groupIndex, int fieldIndex) {
        ViperPresetSchema.Field field = field(groupIndex, fieldIndex);
        groupBody(groupKey(groupIndex)).add(field.key, defaultElement(field));
    }

    /** 重置本组全部已知字段，保留未知字段。 */
    public void resetGroupQuiet(int groupIndex) {
        for (int index = 0; index < fieldCount(groupIndex); index++) {
            resetFieldQuiet(groupIndex, index);
        }
    }

    public void resetGroup(int groupIndex) {
        resetGroupQuiet(groupIndex);
        persist();
    }

    // ──────────────────────────── 目标与后台保存 ────────────────────────────

    public String targetName() {
        return targetName;
    }

    public boolean isScratch() {
        return scratchTarget && !scratchSelected;
    }

    /** 兼容入口：记录失败；需要向界面回报失败的调用方使用 checked 版本。 */
    public void persist() {
        try {
            persistChecked();
        } catch (IOException | RuntimeException failure) {
            System.out.println("[MuoniumPlayer/ViPER] 调参保存失败（" + targetName + "）：" + failure);
        }
    }

    /** 保存成功后更新归一化工作副本，再选择新底稿或重载；失败不调用钩子。 */
    public void persistChecked() throws IOException {
        ViperPreset normalized = normalizedCopy(preset, targetName);
        ScratchSession session = scratchSession;
        if (session != null) {
            synchronized (session) {
                if (session.collisionSafe) {
                    session.lastSavedText = session.lastSavedText == null
                            ? store.saveNewChecked(normalized)
                            : store.saveIfUnchangedChecked(normalized, session.lastSavedText);
                } else {
                    store.saveChecked(normalized);
                }
                preset = normalized;
                if (!session.selected) {
                    selectHook.accept(targetName);
                    session.selected = true;
                } else {
                    reloadHook.run();
                }
                scratchSelected = true;
            }
        } else {
            store.saveChecked(normalized);
            preset = normalized;
            reloadHook.run();
        }
    }

    /** 兼容入口，失败记录错误并返回原目标名。 */
    public String saveAs(String rawName) {
        try {
            return saveAsChecked(rawName);
        } catch (IOException | RuntimeException failure) {
            System.out.println("[MuoniumPlayer/ViPER] 另存预设失败（" + rawName + "）：" + failure);
            return targetName;
        }
    }

    /** 另存成功才切换编辑目标并选中；文件名和内嵌 JSON name 一致。 */
    public String saveAsChecked(String rawName) throws IOException {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("预设名不能为空");
        }
        String name = ViperPresetStore.sanitizeName(rawName);
        if (name.equals(targetName) && scratchSession != null) {
            persistChecked();
            return name;
        }
        ViperPreset normalized = normalizedCopy(preset, name);
        store.saveChecked(normalized);
        preset = normalized;
        targetName = name;
        scratchTarget = false;
        scratchSession = null;
        selectHook.accept(name);
        scratchSelected = true;
        return name;
    }

    /**
     * 不覆盖同名预设的另存：已有 JSON/XML（包括目录和大小写变体）时明确失败。
     * 文件写入和选用钩子均成功后才切换工作副本/目标；选用失败保留原稿，新文件留存并报告。
     */
    public String saveAsNewChecked(String rawName) throws IOException {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("预设名不能为空");
        }
        String name = ViperPresetStore.sanitizeName(rawName);
        ViperPreset normalized = normalizedCopy(preset, name);
        ScratchSession session = new ScratchSession(true);
        session.lastSavedText = store.saveNewChecked(normalized);
        try {
            selectHook.accept(name);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("新预设「" + name + "」已保存，但选用失败；原编辑目标保持不变", failure);
        }
        session.selected = true;
        preset = normalized;
        targetName = name;
        scratchTarget = false;
        scratchSession = session;
        scratchSelected = true;
        return name;
    }

    // ──────────────────────────── 后台文件选择器 ────────────────────────────

    public List<String> kernelFiles() {
        return listFiles("kernels", new String[] {".irs", ".wav"});
    }

    public List<String> ddcDevices() {
        List<String> names = new ArrayList<>();
        File dir = new File(ConfigPaths.VIPER_DIR, "ddc");
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (file.isFile() && name.toLowerCase(Locale.ROOT).endsWith(".vdc")) {
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
                String lower = file.getName().toLowerCase(Locale.ROOT);
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

    /** 历史只读视图；不得把此活动 JSON 交给后台保存线程。 */
    public JsonObject presetGroup(String groupKey) {
        return preset.group(groupKey);
    }

    private ViperPresetSchema.Field field(int groupIndex, int fieldIndex) {
        return ViperPresetSchema.groups().get(groupIndex).fields.get(fieldIndex);
    }

    private ViperPresetSchema.Field requireKind(int groupIndex, int fieldIndex, ViperPresetSchema.Kind kind) {
        ViperPresetSchema.Field field = field(groupIndex, fieldIndex);
        if (field.kind != kind) {
            throw wrongKind(field, kind.toString());
        }
        return field;
    }

    private ViperPresetSchema.Field numericArrayField(int groupIndex, int fieldIndex) {
        ViperPresetSchema.Field field = field(groupIndex, fieldIndex);
        if (field.kind != ViperPresetSchema.Kind.INT_ARRAY && field.kind != ViperPresetSchema.Kind.FLOAT_ARRAY
                && field.kind != ViperPresetSchema.Kind.DOUBLE_ARRAY) {
            throw wrongKind(field, "数值数组（不含布尔数组）");
        }
        return field;
    }

    private static IllegalArgumentException wrongKind(ViperPresetSchema.Field field, String expected) {
        return new IllegalArgumentException("字段 " + field.key + " 是 " + field.kind + "，需要 " + expected);
    }

    private JsonArray arrayBody(int groupIndex, ViperPresetSchema.Field field) {
        JsonObject group = preset.group(groupKey(groupIndex));
        JsonElement value = group == null ? null : group.get(field.key);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalStateException("字段不是有效数组：" + field.key);
        }
        return value.getAsJsonArray();
    }

    private JsonObject groupBody(String groupKey) {
        JsonObject group = preset.group(groupKey);
        if (group == null) {
            group = new JsonObject();
            preset.json().add(groupKey, group);
        }
        return group;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("数值必须有限，不能为 NaN/Infinity");
        }
    }

    private static void checkElementIndex(int index, int length) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("数组下标 " + index + "，长度 " + length);
        }
    }

    private static Number numericValue(ViperPresetSchema.Field field, double value) {
        requireFinite(value);
        double clamped = field.ranged() ? Math.max(field.min, Math.min(field.max, value)) : value;
        if (field.kind == ViperPresetSchema.Kind.INT || field.kind == ViperPresetSchema.Kind.INT_ARRAY) {
            long rounded = Math.round(clamped);
            if (rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("整型数值越界：" + field.key);
            }
            return (int) rounded;
        }
        if (field.kind == ViperPresetSchema.Kind.NULLABLE_LONG) {
            if (clamped < 0.0 || clamped >= 0x1.0p63) {
                throw new IllegalArgumentException("可空整数必须为非负 long：" + field.key);
            }
            return Math.round(clamped);
        }
        return clamped;
    }

    private static JsonElement defaultElement(ViperPresetSchema.Field field) {
        Object value = field.defaultValue;
        switch (field.kind) {
            case BOOL:
                return new JsonPrimitive((Boolean) value);
            case INT:
            case FLOAT:
                return new JsonPrimitive((Number) value);
            case STRING:
                return new JsonPrimitive((String) value);
            case NULLABLE_LONG:
                return JsonNull.INSTANCE;
            case INT_ARRAY: {
                JsonArray array = new JsonArray();
                for (int element : (int[]) value) {
                    array.add(element);
                }
                return array;
            }
            case FLOAT_ARRAY:
            case DOUBLE_ARRAY: {
                JsonArray array = new JsonArray();
                for (double element : (double[]) value) {
                    array.add(element);
                }
                return array;
            }
            case BOOL_ARRAY: {
                JsonArray array = new JsonArray();
                for (boolean element : (boolean[]) value) {
                    array.add(element);
                }
                return array;
            }
            default:
                throw new IllegalStateException("未知 schema 类型：" + field.kind);
        }
    }

    private static ViperPreset defaultPreset(String name) {
        return ViperPresetParser.parse("{\"schemaVersion\":2.1}", name);
    }

    private static ViperPreset normalizedCopy(ViperPreset source, String name) {
        JsonObject body = source.json().deepCopy();
        body.addProperty(ViperPreset.KEY_NAME, name);
        // JsonObject.toString 保留未知 null；Parser.toJsonText 的兼容序列化器会省略 null。
        return ViperPresetParser.parse(body.toString(), name);
    }
}
