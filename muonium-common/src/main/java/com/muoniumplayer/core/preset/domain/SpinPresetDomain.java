package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.ncm.music.MusicSpin;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SpinPresetDomain implements PresetDomain {
    public static final String ID = "spin";
    private static final KeyPolicy POLICY = KeyPolicy.builder().facet(Facet.MOTION, "enabled", "mode", "range", "step").build();
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList("enabled", "mode", "range", "step"));
    public String id() { return ID; }
    public String displayName() { return "陀螺模式"; }
    public int fragmentVersion() { return 1; }
    public int shareCode() { return 9; }
    public List<String> shareKeys() { return SHARE_KEYS; }
    public KeyPolicy policy() { return POLICY; }
    public JsonObject snapshot() { return MusicSpin.toJson(); }
    public JsonObject defaults() { return MusicSpin.defaultsJson(); }
    public void apply(JsonObject state, CompatReport report) {
        MusicSpin.applyAll(TransparentPresetDomain.bool(state.get("enabled"), MusicSpin.isEnabled()),
                (int) TransparentPresetDomain.number(state.get("mode"), MusicSpin.mode()),
                (int) TransparentPresetDomain.number(state.get("range"), MusicSpin.range()),
                TransparentPresetDomain.number(state.get("step"), MusicSpin.stepDegrees()));
    }
}
