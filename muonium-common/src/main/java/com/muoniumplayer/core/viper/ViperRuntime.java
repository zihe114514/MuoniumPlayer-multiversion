package com.muoniumplayer.core.viper;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import repackage.processing.sound.Engine;

/**
 * ViPER 音效运行时：把 {@code config/muonium/viper/} 下的开关与预设接到 JSyn 输出链上。
 *
 * <p><b>触发点</b>：{@code AudioPlayer.loadUsableSoundFile(...)} 在创建每一路 deck 时调用
 * {@link #ensureInstalled()}——与音频设备兜底探测同一时机（那一步本就会触发 Engine 创建，
 * 不改变设备选择时序）。方法幂等，稳态开销是每首曲子 1~2 次文件时间戳读取。</p>
 *
 * <p><b>生效策略</b>：文件即界面。每次触发时对比 {@code settings.json} 与当前预设文件的
 * 修改时间，有变化才重读并重新配置处理器；因此「改开关 / 换预设 / 覆盖预设文件」都在
 * 下一首曲子装载时自动生效（同曲中途不热切换——那需要音频线程重入，属于后续 UI 的工作）。</p>
 *
 * <p><b>失效语义</b>：处理器挂接失败（引擎异常等）时置 {@code unavailable}，音效保持直通、
 * 不影响出声；之后不再重试。设置文件读写失败的代价是「回到未启用」。</p>
 */
public final class ViperRuntime {

    private static final Object LOCK = new Object();
    private static final ViperMasterProcessor PROCESSOR = new ViperMasterProcessor();
    private static final ViperPresetStore STORE = new ViperPresetStore(ConfigPaths.VIPER_PRESETS);

    private static volatile boolean installed;
    private static volatile boolean unavailable;
    private static volatile ViperSettings settings;
    private static volatile long settingsStamp = -1L;
    private static volatile long presetStamp = -1L;

    private ViperRuntime() {
    }

    /** 播放链路在出声前调用；幂等。 */
    public static void ensureInstalled() {
        provisionBuiltins();
        if (unavailable) {
            return;
        }
        try {
            installIfNeeded();
            refreshIfChanged();
        } catch (Throwable failure) {
            unavailable = true;
            System.out.println("[MuoniumPlayer/ViPER] 初始化失败，音效保持直通：" + failure);
        }
    }

    /** 随包内置内核/预设的铺设（幂等；失败只记日志，不影响音频与面板）。 */
    private static void provisionBuiltins() {
        try {
            ViperBuiltin.ensureProvisioned();
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/ViPER] 内置预设铺设失败：" + failure);
        }
    }

    /** 强制重读磁盘并重新配置处理器（供显式调用；{@link #ensureInstalled()} 之外的入口）。 */
    public static void reload() {
        if (unavailable) {
            return;
        }
        try {
            installIfNeeded();
            synchronized (LOCK) {
                reloadLocked();
            }
        } catch (Throwable failure) {
            unavailable = true;
            System.out.println("[MuoniumPlayer/ViPER] 重载失败，音效保持直通：" + failure);
        }
    }

    /**
     * 供界面首次打开时对齐磁盘状态：只在尚未加载过时读一次并配置处理器，<b>不碰音频引擎</b>。
     *
     * <p>{@link #reload()} 会顺手挂接引擎，而挂接属于「装载曲目」那个时机（会把引擎创建提前、
     * 改变设备选择时序），面板打开时不能走它。</p>
     *
     * <p>与「只读加载」的关键区别：这里也必须真的把处理器配置好。若只把 {@code settings} 与
     * 时间戳对齐而不配置，下一次 {@code ensureInstalled} 会因「文件没变」跳过重配 ——
     * 表现为「界面显示启用、实际旁路」的静默错位。</p>
     */
    public static void syncFromDisk() {
        provisionBuiltins();
        if (unavailable || settings != null) {
            return;
        }
        try {
            synchronized (LOCK) {
                if (settings == null) {
                    reloadLocked();
                }
            }
        } catch (Throwable failure) {
            // 面板读取失败不改 unavailable：音频路径的重试语义不归这一次界面读取管。
            System.out.println("[MuoniumPlayer/ViPER] 面板同步磁盘状态失败：" + failure);
        }
    }

    public static boolean isEnabled() {
        ViperSettings current = settings;
        return current != null && current.enabled();
    }

    /** 设置总开关并立即落盘、重载。 */
    public static void setEnabled(boolean enabled) {
        updateSettings(current -> current.setEnabled(enabled));
    }

    /** 选择当前预设（{@code null} 取消选择），立即落盘、重载。 */
    public static void selectPreset(String name) {
        updateSettings(current -> current.setPresetName(name));
    }

    /** 当前选择的预设名；未选择返回 {@code null}。 */
    public static String activePresetName() {
        ViperSettings current = settings;
        return current == null ? null : current.presetName();
    }

    /** 预设目录里的全部预设名（文件基名，已排序）。 */
    public static List<String> listPresetNames() {
        return STORE.listNames();
    }

    /**
     * 导入一份预设文件（官方 JSON / 旧版 XML 均可），归一化落盘后立即重载。
     *
     * @return 落盘后的预设名
     */
    public static String importPreset(File source) throws IOException {
        String name = STORE.importFile(source);
        reload();
        return name;
    }

    /**
     * 删除一份预设（json 与 xml 同名文件都会删掉）；删的若是当前预设，顺带取消选择。
     *
     * <p>面板的删除键与外部脚本共用这一条路径，避免「文件没了、配置还在引用它」的半状态。</p>
     */
    public static void removePreset(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        STORE.delete(name);
        ViperSettings current = settings;
        if (current != null && name.equals(current.presetName())) {
            updateSettings(change -> change.setPresetName(null));
        } else {
            reload();
        }
    }

    /**
     * 面向界面的一行状态描述。
     *
     * <p>与 {@link #statusLine()} 的分工：那个给日志（键值对，方便 grep），这个给人看（面板
     * 状态行直接显示）。两条都从同一份 {@code settings} 推导，口径不会分岔。</p>
     */
    public static String describe() {
        ViperSettings current = settings;
        boolean enabled = current != null && current.enabled();
        String preset = current == null ? null : current.presetName();
        if (!enabled) {
            return "音效已关闭 · 音频直通，不做任何处理";
        }
        if (preset == null) {
            return "已启用，但还没有选择预设";
        }
        return "已启用 · 预设「" + preset + "」生效中";
    }

    // ════════════════════════════ 模板预设 ════════════════════════════

    /** 按名取一份预设的归一化 JSON（schema 2.1，深拷贝）；不存在或解析失败返回 {@code null}。 */
    public static JsonObject exportPresetJson(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        try {
            ViperPreset preset = STORE.load(name);
            return preset == null ? null : preset.json().deepCopy();
        } catch (Throwable failure) {
            return null;
        }
    }

    /**
     * 把一份分享来的预设内容落进 presets 目录，返回落盘名。
     *
     * <p>同名且内容相同就直接复用；内容不同则另存为「名字（分享）」「名字（分享 2）」……
     * <b>绝不覆盖玩家自己的同名预设</b>。比较时剥掉 {@code schemaVersion/name/createdAt} 三个头字段。
     * 内容先过一遍 {@link ViperPresetParser}：未知字段丢弃、数值钳位，与从文件导入同一条路。</p>
     *
     * @throws IllegalArgumentException 内容不是可识别的 ViPER 预设
     */
    public static String importPresetJson(JsonObject json, String preferredName) {
        if (json == null) {
            throw new IllegalArgumentException("预设内容为空");
        }
        ViperPreset incoming = ViperPresetParser.parse(json.toString(), ViperPresetStore.sanitizeName(preferredName));
        String base = ViperPresetStore.sanitizeName(incoming.name());
        JsonObject content = withoutHeader(incoming.json());
        for (int attempt = 1; attempt <= 20; attempt++) {
            String candidate = attempt == 1 ? base
                    : ViperPresetStore.sanitizeName(base + "（分享" + (attempt == 2 ? "" : " " + (attempt - 1)) + "）");
            ViperPreset existing = STORE.load(candidate);
            if (existing == null) {
                JsonObject body = incoming.json().deepCopy();
                body.addProperty(ViperPreset.KEY_NAME, candidate);
                STORE.save(new ViperPreset(candidate, body));
                return candidate;
            }
            if (withoutHeader(existing.json()).equals(content)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("同名预设太多，换个名字再试");
    }

    /** 一次设置总开关与当前预设（{@code null} 取消选择）：只落一次盘、只重载一次。 */
    public static void applyState(boolean enabled, String presetName) {
        updateSettings(current -> {
            current.setEnabled(enabled);
            current.setPresetName(presetName);
        });
    }

    /** 预设目录里有没有这一份（json 或 xml）。 */
    public static boolean hasPreset(String name) {
        return name != null && STORE.find(name) != null;
    }

    private static JsonObject withoutHeader(JsonObject body) {
        JsonObject copy = body.deepCopy();
        copy.remove(ViperPreset.KEY_SCHEMA_VERSION);
        copy.remove(ViperPreset.KEY_NAME);
        copy.remove(ViperPreset.KEY_CREATED_AT);
        return copy;
    }

    /** 一行状态描述（日志与未来的调试界面共用）。 */
    public static String statusLine() {
        ViperSettings current = settings;
        boolean enabled = current != null && current.enabled();
        String preset = current == null ? null : current.presetName();
        return "enabled=" + enabled
                + ", preset=" + (preset == null ? "无" : preset)
                + ", processor=" + (PROCESSOR.isActive() ? "active" : "bypass");
    }

    // —— 内部 ——

    private static void installIfNeeded() {
        if (installed) {
            return;
        }
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            Engine.getEngine().setOutputProcessor(PROCESSOR);
            installed = true;
            System.out.println("[MuoniumPlayer/ViPER] 输出处理器已挂接（" + ConfigPaths.VIPER_DIR + "）");
        }
    }

    private static void refreshIfChanged() {
        ViperSettings current = settings;
        if (current == null
                || fileStamp(ConfigPaths.VIPER_SETTINGS) != settingsStamp
                || presetFileChanged(current)) {
            synchronized (LOCK) {
                reloadLocked();
            }
        }
    }

    private static boolean presetFileChanged(ViperSettings current) {
        String wanted = current.presetName();
        long stamp = wanted == null ? 0L : STORE.stamp(wanted);
        return stamp != presetStamp;
    }

    private static void reloadLocked() {
        ViperSettings loaded = ViperSettings.load(ConfigPaths.VIPER_SETTINGS);
        String wanted = loaded.presetName();
        ViperPreset preset = null;
        long stamp = 0L;
        if (wanted != null) {
            stamp = STORE.stamp(wanted);
            preset = STORE.load(wanted);
        }
        settings = loaded;
        settingsStamp = fileStamp(ConfigPaths.VIPER_SETTINGS);
        presetStamp = stamp;
        PROCESSOR.configure(loaded.enabled(), preset);

        StringBuilder status = new StringBuilder("[MuoniumPlayer/ViPER] ");
        status.append(loaded.enabled() ? "启用" : "停用");
        if (preset != null) {
            status.append("，预设 '").append(preset.name()).append('\'');
        } else if (wanted != null) {
            status.append("，预设缺失 '").append(wanted).append("'（")
                    .append(ConfigPaths.VIPER_PRESETS.getPath()).append(')');
        } else {
            status.append("，未选择预设");
        }
        if (loaded.enabled() && preset == null) {
            status.append("；等待选择预设");
        }
        System.out.println(status);
    }

    private static void updateSettings(Consumer<ViperSettings> change) {
        ViperSettings current = settings;
        if (current == null) {
            current = ViperSettings.load(ConfigPaths.VIPER_SETTINGS);
        }
        change.accept(current);
        current.save();
        reload();
    }

    private static long fileStamp(File file) {
        if (file == null || !file.isFile()) {
            return 0L;
        }
        long modified = file.lastModified();
        return modified == 0L ? 1L : modified;
    }
}
