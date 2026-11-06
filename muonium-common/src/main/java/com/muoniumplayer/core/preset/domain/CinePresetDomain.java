package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonObject;
import com.muoniumplayer.core.cine.CineSettings;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    /**
     * 分享码键表（下标 + 1 = 编号）。<b>只许在末尾追加</b>，已发布的位置永不改、永不复用，
     * 见 {@link PresetDomain#shareKeys()}；排除项不编号。
     */
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList(
            /*   1 */ "enabled", "idleSeconds", "shotBeats", "speedMultiplier",
            /*   5 */ "fovPunchDegrees", "handheldIntensity", "hideHud", "showCinematicBars",
            /*   9 */ "cinematicBarPercent", "transitionMask", "transitionSeconds", "enableMobFocus",
            /*  13 */ "mobFocusRange", "excludeHostileMobs", "excludeBats", "trackingSoftness",
            /*  17 */ "showStatusMessages", "minShotSeconds"));

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
    public int shareCode() {
        return 6;
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
