package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.cine.CineSettings;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;

/** 卡点运镜（cine.json）。全部键都是运镜手感，没有本地资源引用。 */
public final class CinePresetDomain implements PresetDomain {

    public static final String ID = "cine";

    private static final KeyPolicy POLICY = KeyPolicy.builder()
            .facet(Facet.CAMERA,
                    "enabled", "idleSeconds", "shotBeats", "speedMultiplier", "fovPunchDegrees",
                    "handheldIntensity", "hideHud", "showCinematicBars", "cinematicBarPercent",
                    "transitionMask", "transitionSeconds", "enableMobFocus", "mobFocusRange",
                    "excludeHostileMobs", "excludeBats", "trackingSoftness", "showStatusMessages",
                    "minShotSeconds")
            .build();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "卡点运镜";
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
        return CineSettings.toJson();
    }

    @Override
    public JsonObject defaults() {
        return CineSettings.defaultsJson();
    }

    @Override
    public void apply(JsonObject state, CompatReport report) {
        CineSettings.applyJson(state);
    }
}
