package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.TransparentMode;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 通透模式（transparent_mode.json）：HUD 随节拍压扁回弹的果冻特效。 */
public final class TransparentPresetDomain implements PresetDomain {

    public static final String ID = "transparent";

    private static final KeyPolicy POLICY = KeyPolicy.builder()
            .facet(Facet.MOTION, "enabled", "compression", "width")
            .build();

    /**
     * 分享码键表（下标 + 1 = 编号）。<b>只许在末尾追加</b>，已发布的位置永不改、永不复用，
     * 见 {@link PresetDomain#shareKeys()}；排除项不编号。
     */
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList(
            /*   1 */ "enabled", "compression", "width"));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "通透模式";
    }

    @Override
    public int fragmentVersion() {
        return 1;
    }

    @Override
    public int shareCode() {
        return 5;
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
        view.addProperty("enabled", TransparentMode.isEnabled());
        view.addProperty("compression", TransparentMode.compressionPercent());
        view.addProperty("width", TransparentMode.widthPercent());
        return view;
    }

    @Override
    public JsonObject defaults() {
        JsonObject view = new JsonObject();
        view.addProperty("enabled", TransparentMode.DEFAULT_ENABLED);
        view.addProperty("compression", TransparentMode.DEFAULT_COMPRESSION);
        view.addProperty("width", TransparentMode.DEFAULT_WIDTH);
        return view;
    }

    @Override
    public void apply(JsonObject state, CompatReport report) {
        TransparentMode.applyAll(
                bool(state.get("enabled"), TransparentMode.isEnabled()),
                number(state.get("compression"), TransparentMode.compressionPercent()),
                number(state.get("width"), TransparentMode.widthPercent()));
    }

    static boolean bool(JsonElement value, boolean fallback) {
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    static float number(JsonElement value, float fallback) {
        try {
            if (value == null || !value.isJsonPrimitive()) {
                return fallback;
            }
            float parsed = value.getAsFloat();
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException failure) {
            return fallback;
        }
    }
}
