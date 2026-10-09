package com.muoniumplayer.core.viper;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * ViPER 音效的总开关与当前预设（默认 {@code config/muonium/viper/settings.json}）。
 *
 * <p>与预设文件分开落盘：改开关、换预设都不会重写任何一份预设。读失败的代价是
 * 「回到未启用、未选择预设」，与项目里其他小配置文件同一个尽力而为语义。</p>
 *
 * <p>格式（与官方 App 的 masterEnable / 预设选择面板分离这一点对齐）：</p>
 * <pre>{@code
 * { "enabled": true, "preset": "默认" }
 * }</pre>
 */
public final class ViperSettings {

    private static final Gson GSON = new Gson();
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PRESET = "preset";

    private final File file;
    private volatile boolean enabled;
    private volatile String preset;

    public ViperSettings(File file) {
        this.file = file;
    }

    /** 从磁盘加载；文件缺失或损坏时返回「未启用、未选择」的默认实例。 */
    public static ViperSettings load(File file) {
        ViperSettings settings = new ViperSettings(file);
        if (file == null || !file.isFile()) {
            return settings;
        }
        try {
            JsonObject object = JsonConfigStorage.readObject(file, GSON);
            if (object == null) {
                return settings;
            }
            JsonElement enabledElement = object.get(KEY_ENABLED);
            if (enabledElement != null && enabledElement.isJsonPrimitive()) {
                settings.enabled = enabledElement.getAsJsonPrimitive().isBoolean()
                        ? enabledElement.getAsBoolean()
                        : enabledElement.getAsString().equalsIgnoreCase("true");
            }
            JsonElement presetElement = object.get(KEY_PRESET);
            if (presetElement != null && presetElement.isJsonPrimitive()) {
                String value = presetElement.getAsString();
                if (value != null && !value.trim().isEmpty()) {
                    settings.preset = value.trim();
                }
            }
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/ViPER] settings.json 读取失败（按未启用处理）：" + failure);
        }
        return settings;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 当前预设名；未选择时为 {@code null}。 */
    public String presetName() {
        return preset;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public void setPresetName(String value) {
        this.preset = value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** 落盘；失败只打日志（下一轮播放前还会重读，不需要上层补偿）。 */
    public void save() {
        if (file == null) {
            return;
        }
        try {
            JsonObject object = new JsonObject();
            object.addProperty(KEY_ENABLED, enabled);
            if (preset != null) {
                object.addProperty(KEY_PRESET, preset);
            }
            JsonConfigStorage.writeObject(file, GSON, object);
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/ViPER] settings.json 写入失败：" + failure);
        }
    }
}
