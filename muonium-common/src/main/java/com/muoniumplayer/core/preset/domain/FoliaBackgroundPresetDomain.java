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

/**
 * 全屏歌词「Folia 背景」（folia_lyrics_background.json，两层嵌套，键按点路径登记）。
 */
public final class FoliaBackgroundPresetDomain implements PresetDomain {

    public static final String ID = "foliaBg";

    private static final KeyPolicy POLICY = KeyPolicy.builder()
            .facet(Facet.BACKGROUND,
                    "backgroundKind", "mode", "audioResponse",
                    "common.useCoverColorBg", "common.coverOpacity", "common.disableGeometric",
                    "common.disableVignette",
                    "latent.displayMode", "latent.colorSource", "latent.dynamicOnlyInPlayer",
                    "latent.enhancedBeatResponse", "latent.ditheringSpeed", "latent.ditheringAudioSpeed",
                    "latent.ditheringSize", "latent.ditheringOpacity", "latent.meshSpeed",
                    "latent.meshAudioSpeed", "latent.meshDistortion", "latent.meshSwirl",
                    "latent.overlayEnabled", "latent.overlayOpacity",
                    "nomand.imageSource", "nomand.effect", "nomand.ditheringType", "nomand.size",
                    "nomand.colorSteps", "nomand.originalColors", "nomand.inverted",
                    "nomand.flutedGlassSize", "nomand.flutedGlassDistortion", "nomand.flutedGlassBlur",
                    "nomand.paperTextureContrast", "nomand.paperTextureRoughness", "nomand.paperTextureFiber",
                    "nomand.halftoneDotsSize", "nomand.halftoneDotsRadius", "nomand.halftoneDotsContrast",
                    "nomand.halftoneDotsOriginalColors", "nomand.halftoneDotsInverted",
                    "nomand.lensDistortionSpread", "nomand.lensDistortionBulge",
                    "nomand.lensDistortionDispersion", "nomand.overlayEnabled", "nomand.overlayOpacity")
            .facet(Facet.PERFORMANCE, "staticMode")
            .exclude("嵌入背景的图片源是网络地址：随预设分享等于让对方客户端去请求任意 URL（会暴露 IP）",
                    "embed.items", "embed.selected")
            .enumMax("backgroundKind", () -> FoliaBackgroundOptions.KIND_LABELS.length - 1)
            .build();

    /**
     * 分享码键表（下标 + 1 = 编号）。<b>只许在末尾追加</b>，已发布的位置永不改、永不复用，
     * 见 {@link PresetDomain#shareKeys()}；排除项不编号。
     */
    private static final List<String> SHARE_KEYS = Collections.unmodifiableList(Arrays.asList(
            /*   1 */ "backgroundKind", "mode", "audioResponse", "common.useCoverColorBg",
            /*   5 */ "common.coverOpacity", "common.disableGeometric", "common.disableVignette", "latent.displayMode",
            /*   9 */ "latent.colorSource", "latent.dynamicOnlyInPlayer", "latent.enhancedBeatResponse", "latent.ditheringSpeed",
            /*  13 */ "latent.ditheringAudioSpeed", "latent.ditheringSize", "latent.ditheringOpacity", "latent.meshSpeed",
            /*  17 */ "latent.meshAudioSpeed", "latent.meshDistortion", "latent.meshSwirl", "latent.overlayEnabled",
            /*  21 */ "latent.overlayOpacity", "nomand.imageSource", "nomand.effect", "nomand.ditheringType",
            /*  25 */ "nomand.size", "nomand.colorSteps", "nomand.originalColors", "nomand.inverted",
            /*  29 */ "nomand.flutedGlassSize", "nomand.flutedGlassDistortion", "nomand.flutedGlassBlur", "nomand.paperTextureContrast",
            /*  33 */ "nomand.paperTextureRoughness", "nomand.paperTextureFiber", "nomand.halftoneDotsSize", "nomand.halftoneDotsRadius",
            /*  37 */ "nomand.halftoneDotsContrast", "nomand.halftoneDotsOriginalColors", "nomand.halftoneDotsInverted", "nomand.lensDistortionSpread",
            /*  41 */ "nomand.lensDistortionBulge", "nomand.lensDistortionDispersion", "nomand.overlayEnabled", "nomand.overlayOpacity",
            /*  45 */ "staticMode"));

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Folia 背景";
    }

    @Override
    public int fragmentVersion() {
        return 1;
    }

    @Override
    public int shareCode() {
        return 3;
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
        return FoliaBackgroundConfig.toJson();
    }

    @Override
    public JsonObject defaults() {
        return FoliaBackgroundConfig.defaultsJson();
    }

    @Override
    public JsonElement sanitize(String key, JsonElement value, CompatReport report) {
        if (value == null || !value.isJsonPrimitive()) {
            return value;
        }
        if ("mode".equals(key)) {
            String id = value.getAsString();
            if (!FoliaBackgroundMode.byStoredId(id).storedId().equalsIgnoreCase(id)) {
                report.warn("背景样式「" + id + "」这个版本没有，保留当前样式");
                return null;
            }
            return value;
        }
        if ("nomand.imageSource".equals(key) && value.getAsJsonPrimitive().isNumber() && value.getAsInt() == 1) {
            // 1 = 自定义图片：图片本身不随预设分享。本机有自定义图就用本机的，没有就退回封面。
            if (hasCustomImage()) {
                report.warn("漫游背景用的是自定义图片：图片不随预设分享，将显示你本机的那张");
                return value;
            }
            report.warn("漫游背景用的是自定义图片：图片不随预设分享，你本机没有，已改用封面");
            return new JsonPrimitive(0);
        }
        return value;
    }

    private static boolean hasCustomImage() {
        File[] files = ConfigPaths.FOLIA_BACKGROUND_DIR.isDirectory()
                ? ConfigPaths.FOLIA_BACKGROUND_DIR.listFiles() : null;
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file.isFile()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void apply(JsonObject state, CompatReport report) {
        FoliaBackgroundConfig.applyJson(state);
        FoliaBackgroundConfig.save();
    }
}
