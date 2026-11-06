package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.acoustics.AcousticsSettings;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 场景声学（scene_acoustics.json）。写入走具名 setter，只改有变化的项：
 * 这个类曾因「按位置传可空参数」出过分项值错位进总开关的事故，这里不另开批量写口。
 */
public final class AcousticsPresetDomain implements PresetDomain {

    public static final String ID = "acoustics";

    private static final KeyPolicy POLICY = KeyPolicy.builder()
            .facet(Facet.ACOUSTICS, "enabled", "submersion", "enclosure", "rainfall", "strength")
            .build();

    /**
     * 分享码键表（下标 + 1 = 编号）。<b>只许在末尾追加</b>，已发布的位置永不改、永不复用，
     * 见 {@link PresetDomain#shareKeys()}；排除项不编号。
     */
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList(
            /*   1 */ "enabled", "submersion", "enclosure", "rainfall",
            /*   5 */ "strength"));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "场景声学";
    }

    @Override
    public int fragmentVersion() {
        return 1;
    }

    @Override
    public int shareCode() {
        return 7;
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
        view.addProperty("enabled", AcousticsSettings.isEnabled());
        view.addProperty("submersion", AcousticsSettings.isSubmersion());
        view.addProperty("enclosure", AcousticsSettings.isEnclosure());
        view.addProperty("rainfall", AcousticsSettings.isRainfall());
        view.addProperty("strength", AcousticsSettings.strengthPercent());
        return view;
    }

    @Override
    public JsonObject defaults() {
        JsonObject view = new JsonObject();
        view.addProperty("enabled", AcousticsSettings.DEFAULT_ENABLED);
        view.addProperty("submersion", AcousticsSettings.DEFAULT_SUBMERSION);
        view.addProperty("enclosure", AcousticsSettings.DEFAULT_ENCLOSURE);
        view.addProperty("rainfall", AcousticsSettings.DEFAULT_RAINFALL);
        view.addProperty("strength", AcousticsSettings.DEFAULT_STRENGTH_PERCENT);
        return view;
    }

    @Override
    public void apply(JsonObject state, CompatReport report) {
        boolean submersion = TransparentPresetDomain.bool(state.get("submersion"), AcousticsSettings.isSubmersion());
        if (submersion != AcousticsSettings.isSubmersion()) {
            AcousticsSettings.setSubmersion(submersion);
        }
        boolean enclosure = TransparentPresetDomain.bool(state.get("enclosure"), AcousticsSettings.isEnclosure());
        if (enclosure != AcousticsSettings.isEnclosure()) {
            AcousticsSettings.setEnclosure(enclosure);
        }
        boolean rainfall = TransparentPresetDomain.bool(state.get("rainfall"), AcousticsSettings.isRainfall());
        if (rainfall != AcousticsSettings.isRainfall()) {
            AcousticsSettings.setRainfall(rainfall);
        }
        int strength = Math.round(TransparentPresetDomain.number(state.get("strength"),
                AcousticsSettings.strengthPercent()));
        if (strength != AcousticsSettings.strengthPercent()) {
            AcousticsSettings.setStrengthPercent(strength);
        }
        // 总开关最后动：先把分项摆好再开，第一帧就是预设里的样子。
        boolean enabled = TransparentPresetDomain.bool(state.get("enabled"), AcousticsSettings.isEnabled());
        if (enabled != AcousticsSettings.isEnabled()) {
            AcousticsSettings.setEnabled(enabled);
        }
    }
}
