package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.management.UiFontLibrary;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 界面字体（ui_font.json）。预设只带字体 id，不带字体文件：本机没有这款就保留当前字体并提示。
 *
 * <p>{@code file:} id 由 {@link UiFontLibrary} 只认 fonts 目录里的单个文件名（防路径穿越）；系统字体按族名匹配，
 * 因机器而异，所以这一项总是「尽力而为」。</p>
 */
public final class FontPresetDomain implements PresetDomain {

    public static final String ID = "font";

    private static final KeyPolicy POLICY = KeyPolicy.builder()
            .facet(Facet.FONT, "id")
            .build();

    /**
     * 分享码键表（下标 + 1 = 编号）。<b>只许在末尾追加</b>，已发布的位置永不改、永不复用，
     * 见 {@link PresetDomain#shareKeys()}；排除项不编号。
     */
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList(
            /*   1 */ "id"));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "界面字体";
    }

    @Override
    public int fragmentVersion() {
        return 1;
    }

    @Override
    public int shareCode() {
        return 4;
    }

    @Override
    public List<String> shareKeys() {
        return SHARE_KEYS;
    }

    @Override
    public KeyPolicy policy() {
        return POLICY;
    }

    @Override
    public JsonObject snapshot() {
        JsonObject view = new JsonObject();
        view.addProperty("id", UiFontLibrary.activeId());
        return view;
    }

    @Override
    public JsonObject defaults() {
        JsonObject view = new JsonObject();
        view.addProperty("id", UiFontLibrary.defaultFontId());
        return view;
    }

    @Override
    public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        if (!"id".equals(key) || value == null || !value.isJsonPrimitive()) {
            return value;
        }
        String problem = UiFontLibrary.unavailableReason(value.getAsString());
        if (problem != null) {
            report.warn(problem);
            return null;
        }
        return value;
    }

    @Override
    public void apply(JsonObject state, CompatReport report) {
        JsonElement id = state.get("id");
        if (id == null || !id.isJsonPrimitive()) {
            return;
        }
        String problem = UiFontLibrary.selectById(id.getAsString());
        if (problem != null) {
            report.warn(problem);
        }
    }
}
