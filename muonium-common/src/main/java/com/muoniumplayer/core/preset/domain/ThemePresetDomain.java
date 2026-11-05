package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;

/**
 * 播放器主题（theme.json + hud.json 里的配色来源）。
 *
 * <p>视图是 {@code {"theme": {"id", "customColors"}, "colorSource"}}，见 {@link NCMTheme#presetView()}：
 * 只带当前这一款的自定义配色，其余主题的私人调色不属于这一套外观。主题 id 与它的调色是一个整体键，
 * 整块替换 —— 拆开的话，只写了主题 id 的预设会把旧主题的调色错套到新主题上。</p>
 */
public final class ThemePresetDomain implements PresetDomain {

    public static final String ID = "theme";

    private static final KeyPolicy POLICY = KeyPolicy.builder()
            .facet(Facet.THEME, "theme", "colorSource")
            .enumMax("colorSource", () -> HudConfig.THEME_COLOR_SOURCE_MAX)
            .build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "主题配色";
    }

    @Override
    public int fragmentVersion() {
        return 1;
    }

    @Override
    public KeyPolicy policy() {
        return POLICY;
    }

    @Override
    public JsonObject snapshot() {
        return NCMTheme.presetView();
    }

    @Override
    public JsonObject defaults() {
        return NCMTheme.defaultPresetView();
    }

    @Override
    public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        if (!"theme".equals(key) || value == null || !value.isJsonObject()) {
            return value;
        }
        JsonElement idJson = value.getAsJsonObject().get("id");
        if (idJson == null || !idJson.isJsonPrimitive()) {
            report.warn("预设里的主题没写 id，保留当前主题");
            return null;
        }
        String id = idJson.getAsString();
        NCMTheme.ThemePreset theme = NCMTheme.byIdOrNull(id);
        if (theme == null) {
            report.warn("主题「" + id + "」这个版本没有，保留当前主题");
            return null;
        }
        if (!NCMTheme.isSelectable(theme)) {
            report.warn("「" + theme.getDisplayName() + "」在当前设备上不可用，保留当前主题");
            return null;
        }
        return value;
    }

    @Override
    public void apply(JsonObject state, CompatReport report) {
        String problem = NCMTheme.applyPresetView(state);
        if (problem != null) {
            report.warn(problem);
        }
    }
}
