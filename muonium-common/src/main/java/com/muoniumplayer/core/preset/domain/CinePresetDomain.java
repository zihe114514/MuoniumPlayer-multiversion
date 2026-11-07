package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.cine.CineSettings;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class CinePresetDomain implements PresetDomain {
    public static final String ID = "cine";
    private static final KeyPolicy POLICY = KeyPolicy.builder().facet(Facet.CAMERA,
            "enabled", "idleSeconds", "shotBeats", "speedMultiplier", "fovPunchDegrees", "handheldIntensity",
            "hideHud", "showCinematicBars", "cinematicBarPercent", "transitionMask", "transitionSeconds", "enableMobFocus",
            "mobFocusRange", "excludeHostileMobs", "excludeBats", "trackingSoftness", "showStatusMessages", "minShotSeconds").build();
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList(
            "enabled", "idleSeconds", "shotBeats", "speedMultiplier", "fovPunchDegrees", "handheldIntensity",
            "hideHud", "showCinematicBars", "cinematicBarPercent", "transitionMask", "transitionSeconds", "enableMobFocus",
            "mobFocusRange", "excludeHostileMobs", "excludeBats", "trackingSoftness", "showStatusMessages", "minShotSeconds"));
    public String id() { return ID; }
    public String displayName() { return "卡点运镜"; }
    public int fragmentVersion() { return 1; }
    public int shareCode() { return 6; }
    public List<String> shareKeys() { return SHARE_KEYS; }
    public KeyPolicy policy() { return POLICY; }
    public JsonObject snapshot() { return CineSettings.toJson(); }
    public JsonObject defaults() { return CineSettings.defaultsJson(); }
    public void apply(JsonObject state, CompatReport report) { CineSettings.applyJson(state); }
}
