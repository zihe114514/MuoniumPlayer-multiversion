package com.muoniumplayer.core.preset.domain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.muoniumplayer.core.preset.model.CompatReport;
import com.muoniumplayer.core.preset.model.Facet;
import com.muoniumplayer.core.preset.model.KeyPolicy;
import com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundConfig;
import com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundMode;
import com.muoniumplayer.core.screens.ncm.foliabg.FoliaBackgroundOptions;
import com.muoniumplayer.core.settings.ConfigPaths;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class FoliaBackgroundPresetDomain implements PresetDomain {
    public static final String ID = "foliaBg";
    private static final KeyPolicy POLICY = KeyPolicy.builder().facet(Facet.BACKGROUND,
            "backgroundKind", "mode", "audioResponse", "common.useCoverColorBg", "common.coverOpacity", "common.disableGeometric",
            "common.disableVignette", "latent.displayMode", "latent.colorSource", "latent.dynamicOnlyInPlayer", "latent.enhancedBeatResponse",
            "latent.ditheringSpeed", "latent.ditheringAudioSpeed", "latent.ditheringSize", "latent.ditheringOpacity", "latent.meshSpeed",
            "latent.meshAudioSpeed", "latent.meshDistortion", "latent.meshSwirl", "latent.overlayEnabled", "latent.overlayOpacity",
            "nomand.imageSource", "nomand.effect", "nomand.ditheringType", "nomand.size", "nomand.colorSteps", "nomand.originalColors",
            "nomand.inverted", "nomand.flutedGlassSize", "nomand.flutedGlassDistortion", "nomand.flutedGlassBlur", "nomand.paperTextureContrast",
            "nomand.paperTextureRoughness", "nomand.paperTextureFiber", "nomand.halftoneDotsSize", "nomand.halftoneDotsRadius", "nomand.halftoneDotsContrast",
            "nomand.halftoneDotsOriginalColors", "nomand.halftoneDotsInverted", "nomand.lensDistortionSpread", "nomand.lensDistortionBulge",
            "nomand.lensDistortionDispersion", "nomand.overlayEnabled", "nomand.overlayOpacity")
            .facet(Facet.PERFORMANCE, "staticMode")
            .exclude("远程图片 URL 不分享，避免让对方请求任意地址并暴露 IP", "embed.items", "embed.selected")
            .enumMax("backgroundKind", () -> FoliaBackgroundOptions.KIND_LABELS.length - 1)
            .enumMax("latent.displayMode", () -> 2).enumMax("latent.colorSource", () -> 1)
            .enumMax("nomand.imageSource", () -> 1).enumMax("nomand.effect", () -> 4).enumMax("nomand.ditheringType", () -> 2).build();
    // Frozen PR#13 order.
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList(
            "backgroundKind", "mode", "audioResponse", "common.useCoverColorBg", "common.coverOpacity", "common.disableGeometric", "common.disableVignette",
            "latent.displayMode", "latent.colorSource", "latent.dynamicOnlyInPlayer", "latent.enhancedBeatResponse", "latent.ditheringSpeed",
            "latent.ditheringAudioSpeed", "latent.ditheringSize", "latent.ditheringOpacity", "latent.meshSpeed", "latent.meshAudioSpeed", "latent.meshDistortion",
            "latent.meshSwirl", "latent.overlayEnabled", "latent.overlayOpacity", "nomand.imageSource", "nomand.effect", "nomand.ditheringType", "nomand.size",
            "nomand.colorSteps", "nomand.originalColors", "nomand.inverted", "nomand.flutedGlassSize", "nomand.flutedGlassDistortion", "nomand.flutedGlassBlur",
            "nomand.paperTextureContrast", "nomand.paperTextureRoughness", "nomand.paperTextureFiber", "nomand.halftoneDotsSize", "nomand.halftoneDotsRadius",
            "nomand.halftoneDotsContrast", "nomand.halftoneDotsOriginalColors", "nomand.halftoneDotsInverted", "nomand.lensDistortionSpread",
            "nomand.lensDistortionBulge", "nomand.lensDistortionDispersion", "nomand.overlayEnabled", "nomand.overlayOpacity", "staticMode"));
    public String id() { return ID; }
    public String displayName() { return "Folia 背景"; }
    public int fragmentVersion() { return 1; }
    public int shareCode() { return 3; }
    public List<String> shareKeys() { return SHARE_KEYS; }
    public KeyPolicy policy() { return POLICY; }
    public JsonObject snapshot() { return FoliaBackgroundConfig.toJson(); }
    public JsonObject defaults() { return FoliaBackgroundConfig.defaultsJson(); }
    public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        if (value == null || !value.isJsonPrimitive()) return value;
        if ("mode".equals(key)) {
            String id = value.getAsString();
            if (!FoliaBackgroundMode.byStoredId(id).storedId().equalsIgnoreCase(id)) {
                report.warn("背景样式「" + id + "」这个版本没有，保留当前样式"); return null;
            }
        }
        if ("nomand.imageSource".equals(key) && value.getAsJsonPrimitive().isNumber() && value.getAsInt() == 1) {
            if (hasCustomImage()) report.warn("漫游背景将显示本机的自定义图片（不随分享）");
            else { report.warn("本机没有漫游背景的自定义图片，已改用封面"); return new JsonPrimitive(0); }
        }
        return value;
    }
    private static boolean hasCustomImage() {
        File[] files = ConfigPaths.FOLIA_BACKGROUND_DIR.isDirectory() ? ConfigPaths.FOLIA_BACKGROUND_DIR.listFiles() : null;
        if (files != null) for (File file : files) if (file.isFile()) return true;
        return false;
    }
    public void apply(JsonObject state, CompatReport report) {
        FoliaBackgroundConfig.applyJson(state);
        FoliaBackgroundConfig.savePresetState();
    }
}
