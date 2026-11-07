package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.acoustics.AcousticsSettings;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Only named setters: no second positional multi-boolean configuration channel. */
public final class AcousticsPresetDomain implements PresetDomain {
    public static final String ID = "acoustics";
    private static final KeyPolicy POLICY = KeyPolicy.builder().facet(Facet.ACOUSTICS, "enabled", "submersion", "enclosure", "rainfall", "strength").build();
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList("enabled", "submersion", "enclosure", "rainfall", "strength"));
    public String id() { return ID; }
    public String displayName() { return "场景声学"; }
    public int fragmentVersion() { return 1; }
    public int shareCode() { return 7; }
    public List<String> shareKeys() { return SHARE_KEYS; }
    public KeyPolicy policy() { return POLICY; }
    public JsonObject snapshot() { return AcousticsSettings.toJson(); }
    public JsonObject defaults() { return AcousticsSettings.defaultsJson(); }
    public void apply(JsonObject state, CompatReport report) {
        boolean submersion = TransparentPresetDomain.bool(state.get("submersion"), AcousticsSettings.isSubmersion());
        boolean enclosure = TransparentPresetDomain.bool(state.get("enclosure"), AcousticsSettings.isEnclosure());
        boolean rainfall = TransparentPresetDomain.bool(state.get("rainfall"), AcousticsSettings.isRainfall());
        int strength = Math.round(TransparentPresetDomain.number(state.get("strength"), AcousticsSettings.strengthPercent()));
        boolean enabled = TransparentPresetDomain.bool(state.get("enabled"), AcousticsSettings.isEnabled());
        boolean changed = false;
        if (submersion != AcousticsSettings.isSubmersion()) { AcousticsSettings.setSubmersion(submersion); changed = true; }
        if (enclosure != AcousticsSettings.isEnclosure()) { AcousticsSettings.setEnclosure(enclosure); changed = true; }
        if (rainfall != AcousticsSettings.isRainfall()) { AcousticsSettings.setRainfall(rainfall); changed = true; }
        if (strength != AcousticsSettings.strengthPercent()) { AcousticsSettings.setStrengthPercent(strength); changed = true; }
        if (enabled != AcousticsSettings.isEnabled()) { AcousticsSettings.setEnabled(enabled); changed = true; }
        if (changed) AcousticsSettings.verifyPresetPersistence();
    }
}
