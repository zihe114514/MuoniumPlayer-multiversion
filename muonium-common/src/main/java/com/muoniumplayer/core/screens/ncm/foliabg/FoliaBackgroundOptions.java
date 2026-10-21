package com.muoniumplayer.core.screens.ncm.foliabg;

import com.muoniumplayer.core.utils.KeyboardUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「歌词背景」这一页的全部可编辑项：参数表、读写、枚举轮换、动作语义、六档清单。
 *
 * <p>参数表从原来的独立面板抽出来，因为「背景」现在融合进了「歌词显示」主面板：
 * 一行切八档、下面立刻换成那一档的参数。控件绘制在主面板，本类只管
 * 「有哪些项、默认值、范围、点了怎么变」——两处各画一份的话迟早会长歪。</p>
 *
 * <p>本类没有任何绘制代码、不持有面板状态；除写配置外无副作用。</p>
 */
public final class FoliaBackgroundOptions {

    private FoliaBackgroundOptions() {
    }

    /** 行种类：宿主直接拿来分派绘制，编号与主面板保持一致。 */
    public static final int KIND_MODE = 0;
    public static final int KIND_SECTION = 1;
    public static final int KIND_SLIDER = 2;
    public static final int KIND_TOGGLE = 3;
    public static final int KIND_PICK = 4;
    public static final int KIND_ACTION = 5;
    public static final int KIND_TEXT = 6;

    /** 一条可编辑参数。{@code id} 是 {@link #allSpecs()} 里的稳定键，也是读写开关的键。 */
    public static final class Spec {
        public final int kind;
        public final String id;
        public final String label;
        public final float min;
        public final float max;
        public final float step;
        public final String[] options;
        public final String note;

        Spec(int kind, String id, String label, float min, float max, float step,
             String[] options, String note) {
            this.kind = kind;
            this.id = id;
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.options = options;
            this.note = note;
        }
    }

    public static Spec slider(String id, String label, float min, float max, float step) {
        return new Spec(KIND_SLIDER, id, label, min, max, step, null, null);
    }

    public static Spec toggle(String id, String label) {
        return new Spec(KIND_TOGGLE, id, label, 0f, 1f, 0f, null, null);
    }

    public static Spec pick(String id, String label, String... options) {
        return new Spec(KIND_PICK, id, label, 0f, 0f, 0f, options, null);
    }

    public static Spec action(String id, String label) {
        return new Spec(KIND_ACTION, id, label, 0f, 0f, 0f, null, null);
    }

    public static final Spec MODE_SPEC = new Spec(KIND_MODE, "mode", "背景样式",
            0f, 0f, 0f, null, null);
    public static final Spec SECTION_LATENT = new Spec(KIND_SECTION, "", "隐现 · 图层与取色", 0, 0, 0, null, null);
    public static final Spec SECTION_LATENT_PIXEL = new Spec(KIND_SECTION, "", "隐现 · 像素层", 0, 0, 0, null, null);
    public static final Spec SECTION_LATENT_MESH = new Spec(KIND_SECTION, "", "隐现 · 流体层", 0, 0, 0, null, null);
    public static final Spec SECTION_NOMAND = new Spec(KIND_SECTION, "", "漫游 · 图片与叠色", 0, 0, 0, null, null);
    public static final Spec SECTION_NOMAND_EFFECT = new Spec(KIND_SECTION, "", "漫游 · 效果参数", 0, 0, 0, null, null);
    public static final Spec SECTION_COMMON = new Spec(KIND_SECTION, "", "通用 · 封面与几何", 0, 0, 0, null, null);
    public static final Spec SECTION_EMBED = new Spec(KIND_SECTION, "", "嵌入 · 图片源", 0, 0, 0, null, null);
    public static final Spec SECTION_GLOBAL = new Spec(KIND_SECTION, "", "全局", 0, 0, 0, null, null);

    // 全局
    public static final Spec BACKGROUND_KIND = pick("backgroundKind", "歌词背景",
            "QPlayer 流体", "模糊封面", "Folia 背景");
    public static final Spec STATIC_MODE = toggle("staticMode", "静态（省电）");
    public static final Spec AUDIO_RESPONSE = slider("audioResponse", "音频响应", 0f, 2f, 0.05f);
    public static final Spec RESET = action("reset", "恢复全部默认");

    // 通用
    public static final Spec COMMON_COVER = toggle("commonUseCoverColorBg", "封面色流体");
    public static final Spec COMMON_OPACITY = slider("commonCoverOpacity", "底色遮罩", 0f, 1f, 0.05f);
    public static final Spec COMMON_GEOMETRIC = toggle("commonDisableGeometric", "关闭几何图形");
    public static final Spec COMMON_VIGNETTE = toggle("commonDisableVignette", "关闭暗角");

    // 隐现
    public static final Spec LATENT_DISPLAY = pick("latentDisplayMode", "显示图层", "像素", "流体", "混合");
    public static final Spec LATENT_COLOR = pick("latentColorSource", "颜色来源", "封面与主题", "仅封面取色");
    public static final Spec LATENT_DYNAMIC = toggle("latentDynamicOnlyInPlayer", "仅播放页动态");
    public static final Spec LATENT_BEAT = toggle("latentEnhancedBeatResponse", "增强节拍响应");
    public static final Spec LATENT_OVERLAY = toggle("latentOverlayEnabled", "可读性叠色");
    public static final Spec LATENT_OVERLAY_OPACITY = slider("latentOverlayOpacity", "叠色透明度", 0f, 1f, 0.05f);
    public static final Spec LATENT_DITHER_SPEED = slider("latentDitheringSpeed", "像素基础速度", 0f, 2f, 0.1f);
    public static final Spec LATENT_DITHER_AUDIO = slider("latentDitheringAudioSpeed", "像素音频速度", 0f, 2f, 0.1f);
    public static final Spec LATENT_DITHER_SIZE = slider("latentDitheringSize", "像素尺寸", 0.5f, 8f, 0.25f);
    public static final Spec LATENT_DITHER_OPACITY = slider("latentDitheringOpacity", "像素层透明度", 0f, 1f, 0.05f);
    public static final Spec LATENT_MESH_SPEED = slider("latentMeshSpeed", "流体基础速度", 0f, 2f, 0.1f);
    public static final Spec LATENT_MESH_AUDIO = slider("latentMeshAudioSpeed", "流体音频速度", 0f, 2f, 0.1f);
    public static final Spec LATENT_MESH_DISTORTION = slider("latentMeshDistortion", "流体扭曲", 0f, 2f, 0.05f);
    public static final Spec LATENT_MESH_SWIRL = slider("latentMeshSwirl", "流体旋涡", 0f, 1f, 0.05f);

    // 漫游
    public static final Spec NOMAND_SOURCE = pick("nomandImageSource", "图片来源", "封面", "自定义图片");
    public static final Spec NOMAND_EFFECT = pick("nomandEffect", "效果",
            "像素画", "纹理玻璃", "纸张纹理", "半调网点", "镜头畸变");
    public static final Spec NOMAND_OVERLAY = toggle("nomandOverlayEnabled", "可读性叠色");
    public static final Spec NOMAND_OVERLAY_OPACITY = slider("nomandOverlayOpacity", "叠色透明度", 0f, 1f, 0.05f);
    public static final Spec NOMAND_DITHER_TYPE = pick("nomandDitheringType", "像素画图案", "2x2", "4x4", "8x8");
    public static final Spec NOMAND_SIZE = slider("nomandSize", "网点尺寸", 0.5f, 20f, 0.5f);
    public static final Spec NOMAND_STEPS = slider("nomandColorSteps", "色阶数量", 1f, 7f, 1f);
    public static final Spec NOMAND_ORIGINAL = toggle("nomandOriginalColors", "保留图片原色");
    public static final Spec NOMAND_INVERTED = toggle("nomandInverted", "反转图片明度");
    public static final Spec NOMAND_GLASS_SIZE = slider("nomandFlutedGlassSize", "纹理尺寸", 0.1f, 1f, 0.05f);
    public static final Spec NOMAND_GLASS_DISTORTION = slider("nomandFlutedGlassDistortion", "扭曲", 0f, 1f, 0.05f);
    public static final Spec NOMAND_GLASS_BLUR = slider("nomandFlutedGlassBlur", "模糊", 0f, 1f, 0.02f);
    public static final Spec NOMAND_PAPER_CONTRAST = slider("nomandPaperTextureContrast", "对比度", 0f, 1f, 0.05f);
    public static final Spec NOMAND_PAPER_ROUGHNESS = slider("nomandPaperTextureRoughness", "粗糙度", 0f, 1f, 0.05f);
    public static final Spec NOMAND_PAPER_FIBER = slider("nomandPaperTextureFiber", "纤维", 0f, 1f, 0.05f);
    public static final Spec NOMAND_DOT_SIZE = slider("nomandHalftoneDotsSize", "网点尺寸", 0.1f, 1f, 0.05f);
    public static final Spec NOMAND_DOT_RADIUS = slider("nomandHalftoneDotsRadius", "网点半径", 0.1f, 2f, 0.05f);
    public static final Spec NOMAND_DOT_CONTRAST = slider("nomandHalftoneDotsContrast", "对比度", 0f, 1f, 0.05f);
    public static final Spec NOMAND_DOT_ORIGINAL = toggle("nomandHalftoneDotsOriginalColors", "保留图片原色");
    public static final Spec NOMAND_DOT_INVERTED = toggle("nomandHalftoneDotsInverted", "反转图片明度");
    public static final Spec NOMAND_LENS_SPREAD = slider("nomandLensDistortionSpread", "色彩分离", 0f, 1f, 0.05f);
    public static final Spec NOMAND_LENS_BULGE = slider("nomandLensDistortionBulge", "镜头膨胀", -1f, 1f, 0.05f);
    public static final Spec NOMAND_LENS_DISPERSION = slider("nomandLensDistortionDispersion", "色彩色散", 0f, 1f, 0.05f);

    /** 漫游在「自定义图片」来源下才有意义：呼出系统文件选择框并复制成 background.png。 */
    public static final Spec CHOOSE_IMAGE = action("chooseImage", "自定义图片");

    // 嵌入
    public static final Spec EMBED_STATUS = new Spec(KIND_TEXT, "embedStatus", "图片源列表",
            0f, 0f, 0f, null, null);
    public static final Spec EMBED_PREV = action("embedPrev", "上一个图片源");
    public static final Spec EMBED_NEXT = action("embedNext", "下一个图片源");
    public static final Spec EMBED_PASTE = action("embedPaste", "从剪贴板添加链接");
    public static final Spec EMBED_REMOVE = action("embedRemove", "删除当前图片源");
    public static final Spec EMBED_CLEAR = action("embedClear", "清空图片源列表");

    /** 只把当前样式的字段恢复出厂，全局项与其它样式不动。 */
    public static final Spec RESET_MODE = action("resetMode", "恢复本样式默认");


    public static final Spec[] ALL = new Spec[]{
            MODE_SPEC, BACKGROUND_KIND, STATIC_MODE, AUDIO_RESPONSE, RESET, RESET_MODE,
            COMMON_COVER, COMMON_OPACITY, COMMON_GEOMETRIC, COMMON_VIGNETTE,
            LATENT_DISPLAY, LATENT_COLOR, LATENT_DYNAMIC, LATENT_BEAT, LATENT_OVERLAY,
            LATENT_OVERLAY_OPACITY, LATENT_DITHER_SPEED, LATENT_DITHER_AUDIO,
            LATENT_DITHER_SIZE, LATENT_DITHER_OPACITY, LATENT_MESH_SPEED,
            LATENT_MESH_AUDIO, LATENT_MESH_DISTORTION, LATENT_MESH_SWIRL,
            NOMAND_SOURCE, NOMAND_EFFECT, NOMAND_OVERLAY, NOMAND_OVERLAY_OPACITY,
            NOMAND_DITHER_TYPE, NOMAND_SIZE, NOMAND_STEPS, NOMAND_ORIGINAL, NOMAND_INVERTED,
            NOMAND_GLASS_SIZE, NOMAND_GLASS_DISTORTION, NOMAND_GLASS_BLUR,
            NOMAND_PAPER_CONTRAST, NOMAND_PAPER_ROUGHNESS, NOMAND_PAPER_FIBER,
            NOMAND_DOT_SIZE, NOMAND_DOT_RADIUS, NOMAND_DOT_CONTRAST,
            NOMAND_DOT_ORIGINAL, NOMAND_DOT_INVERTED,
            NOMAND_LENS_SPREAD, NOMAND_LENS_BULGE, NOMAND_LENS_DISPERSION,
            CHOOSE_IMAGE, EMBED_STATUS,
            EMBED_PREV, EMBED_NEXT, EMBED_PASTE, EMBED_REMOVE, EMBED_CLEAR
    };

    public static Spec[] allSpecs() {
        return ALL;
    }

    public static Spec specById(String id) {
        for (Spec spec : ALL) {
            if (spec.id.equals(id)) return spec;
        }
        return null;
    }

    public static float readFloat(String id) {
        switch (id) {
            case "audioResponse": return FoliaBackgroundConfig.audioResponse;
            case "commonCoverOpacity": return FoliaBackgroundConfig.commonCoverOpacity;
            case "latentOverlayOpacity": return FoliaBackgroundConfig.latentOverlayOpacity;
            case "latentDitheringSpeed": return FoliaBackgroundConfig.latentDitheringSpeed;
            case "latentDitheringAudioSpeed": return FoliaBackgroundConfig.latentDitheringAudioSpeed;
            case "latentDitheringSize": return FoliaBackgroundConfig.latentDitheringSize;
            case "latentDitheringOpacity": return FoliaBackgroundConfig.latentDitheringOpacity;
            case "latentMeshSpeed": return FoliaBackgroundConfig.latentMeshSpeed;
            case "latentMeshAudioSpeed": return FoliaBackgroundConfig.latentMeshAudioSpeed;
            case "latentMeshDistortion": return FoliaBackgroundConfig.latentMeshDistortion;
            case "latentMeshSwirl": return FoliaBackgroundConfig.latentMeshSwirl;
            case "nomandOverlayOpacity": return FoliaBackgroundConfig.nomandOverlayOpacity;
            case "nomandSize": return FoliaBackgroundConfig.nomandSize;
            case "nomandColorSteps": return FoliaBackgroundConfig.nomandColorSteps;
            case "nomandFlutedGlassSize": return FoliaBackgroundConfig.nomandFlutedGlassSize;
            case "nomandFlutedGlassDistortion": return FoliaBackgroundConfig.nomandFlutedGlassDistortion;
            case "nomandFlutedGlassBlur": return FoliaBackgroundConfig.nomandFlutedGlassBlur;
            case "nomandPaperTextureContrast": return FoliaBackgroundConfig.nomandPaperTextureContrast;
            case "nomandPaperTextureRoughness": return FoliaBackgroundConfig.nomandPaperTextureRoughness;
            case "nomandPaperTextureFiber": return FoliaBackgroundConfig.nomandPaperTextureFiber;
            case "nomandHalftoneDotsSize": return FoliaBackgroundConfig.nomandHalftoneDotsSize;
            case "nomandHalftoneDotsRadius": return FoliaBackgroundConfig.nomandHalftoneDotsRadius;
            case "nomandHalftoneDotsContrast": return FoliaBackgroundConfig.nomandHalftoneDotsContrast;
            case "nomandLensDistortionSpread": return FoliaBackgroundConfig.nomandLensDistortionSpread;
            case "nomandLensDistortionBulge": return FoliaBackgroundConfig.nomandLensDistortionBulge;
            case "nomandLensDistortionDispersion": return FoliaBackgroundConfig.nomandLensDistortionDispersion;
            default: return 0f;
        }
    }

    public static void writeFloat(String id, float value) {
        switch (id) {
            case "audioResponse": FoliaBackgroundConfig.audioResponse = value; return;
            case "commonCoverOpacity": FoliaBackgroundConfig.commonCoverOpacity = value; return;
            case "latentOverlayOpacity": FoliaBackgroundConfig.latentOverlayOpacity = value; return;
            case "latentDitheringSpeed": FoliaBackgroundConfig.latentDitheringSpeed = value; return;
            case "latentDitheringAudioSpeed": FoliaBackgroundConfig.latentDitheringAudioSpeed = value; return;
            case "latentDitheringSize": FoliaBackgroundConfig.latentDitheringSize = value; return;
            case "latentDitheringOpacity": FoliaBackgroundConfig.latentDitheringOpacity = value; return;
            case "latentMeshSpeed": FoliaBackgroundConfig.latentMeshSpeed = value; return;
            case "latentMeshAudioSpeed": FoliaBackgroundConfig.latentMeshAudioSpeed = value; return;
            case "latentMeshDistortion": FoliaBackgroundConfig.latentMeshDistortion = value; return;
            case "latentMeshSwirl": FoliaBackgroundConfig.latentMeshSwirl = value; return;
            case "nomandOverlayOpacity": FoliaBackgroundConfig.nomandOverlayOpacity = value; return;
            case "nomandSize": FoliaBackgroundConfig.nomandSize = value; return;
            case "nomandColorSteps": FoliaBackgroundConfig.nomandColorSteps = Math.round(value); return;
            case "nomandFlutedGlassSize": FoliaBackgroundConfig.nomandFlutedGlassSize = value; return;
            case "nomandFlutedGlassDistortion": FoliaBackgroundConfig.nomandFlutedGlassDistortion = value; return;
            case "nomandFlutedGlassBlur": FoliaBackgroundConfig.nomandFlutedGlassBlur = value; return;
            case "nomandPaperTextureContrast": FoliaBackgroundConfig.nomandPaperTextureContrast = value; return;
            case "nomandPaperTextureRoughness": FoliaBackgroundConfig.nomandPaperTextureRoughness = value; return;
            case "nomandPaperTextureFiber": FoliaBackgroundConfig.nomandPaperTextureFiber = value; return;
            case "nomandHalftoneDotsSize": FoliaBackgroundConfig.nomandHalftoneDotsSize = value; return;
            case "nomandHalftoneDotsRadius": FoliaBackgroundConfig.nomandHalftoneDotsRadius = value; return;
            case "nomandHalftoneDotsContrast": FoliaBackgroundConfig.nomandHalftoneDotsContrast = value; return;
            case "nomandLensDistortionSpread": FoliaBackgroundConfig.nomandLensDistortionSpread = value; return;
            case "nomandLensDistortionBulge": FoliaBackgroundConfig.nomandLensDistortionBulge = value; return;
            case "nomandLensDistortionDispersion": FoliaBackgroundConfig.nomandLensDistortionDispersion = value; return;
            default:
        }
    }

    public static boolean readBoolean(String id) {
        switch (id) {
            case "staticMode": return FoliaBackgroundConfig.staticMode;
            case "commonUseCoverColorBg": return FoliaBackgroundConfig.commonUseCoverColorBg;
            case "commonDisableGeometric": return FoliaBackgroundConfig.commonDisableGeometric;
            case "commonDisableVignette": return FoliaBackgroundConfig.commonDisableVignette;
            case "latentDynamicOnlyInPlayer": return FoliaBackgroundConfig.latentDynamicOnlyInPlayer;
            case "latentEnhancedBeatResponse": return FoliaBackgroundConfig.latentEnhancedBeatResponse;
            case "latentOverlayEnabled": return FoliaBackgroundConfig.latentOverlayEnabled;
            case "nomandOverlayEnabled": return FoliaBackgroundConfig.nomandOverlayEnabled;
            case "nomandOriginalColors": return FoliaBackgroundConfig.nomandOriginalColors;
            case "nomandInverted": return FoliaBackgroundConfig.nomandInverted;
            case "nomandHalftoneDotsOriginalColors": return FoliaBackgroundConfig.nomandHalftoneDotsOriginalColors;
            case "nomandHalftoneDotsInverted": return FoliaBackgroundConfig.nomandHalftoneDotsInverted;
            default: return false;
        }
    }

    public static void writeBoolean(String id, boolean value) {
        switch (id) {
            case "staticMode": FoliaBackgroundConfig.staticMode = value; return;
            case "commonUseCoverColorBg": FoliaBackgroundConfig.commonUseCoverColorBg = value; return;
            case "commonDisableGeometric": FoliaBackgroundConfig.commonDisableGeometric = value; return;
            case "commonDisableVignette": FoliaBackgroundConfig.commonDisableVignette = value; return;
            case "latentDynamicOnlyInPlayer": FoliaBackgroundConfig.latentDynamicOnlyInPlayer = value; return;
            case "latentEnhancedBeatResponse": FoliaBackgroundConfig.latentEnhancedBeatResponse = value; return;
            case "latentOverlayEnabled": FoliaBackgroundConfig.latentOverlayEnabled = value; return;
            case "nomandOverlayEnabled": FoliaBackgroundConfig.nomandOverlayEnabled = value; return;
            case "nomandOriginalColors": FoliaBackgroundConfig.nomandOriginalColors = value; return;
            case "nomandInverted": FoliaBackgroundConfig.nomandInverted = value; return;
            case "nomandHalftoneDotsOriginalColors": FoliaBackgroundConfig.nomandHalftoneDotsOriginalColors = value; return;
            case "nomandHalftoneDotsInverted": FoliaBackgroundConfig.nomandHalftoneDotsInverted = value; return;
            default:
        }
    }

    public static String readOption(Spec spec) {
        switch (spec.id) {
            case "latentDisplayMode":
                return spec.options[Math.max(0, Math.min(2, FoliaBackgroundConfig.latentDisplayMode))];
            case "latentColorSource":
                return spec.options[Math.max(0, Math.min(1, FoliaBackgroundConfig.latentColorSource))];
            case "nomandImageSource":
                return spec.options[Math.max(0, Math.min(1, FoliaBackgroundConfig.nomandImageSource))];
            case "nomandEffect":
                return spec.options[Math.max(0, Math.min(4, FoliaBackgroundConfig.nomandEffect))];
            case "nomandDitheringType":
                return spec.options[Math.max(0, Math.min(2, FoliaBackgroundConfig.nomandDitheringType))];
            case "backgroundKind":
                return spec.options[Math.max(0, Math.min(2, FoliaBackgroundConfig.backgroundKind))];
            default:
                return "";
        }
    }

    /**
     * 滑杆右侧的数值文本。
     *
     * <p>按显示语义分三档：0~1 的「比例」显示成百分比、像素/倍率显示小数、整数档位显示成「N 级」。
     * 每个 id 都必须真的在 {@link #readFloat(String)} 里有对应字段 —— 否则读出来永远是 0，
     * 屏幕上就是一个不动的 "0"（写错 id 的分支就是这种下场）。</p>
     */
    public static String formatValue(Spec spec, float value) {
        switch (spec.id) {
            case "latentDitheringSize":
            case "nomandSize":
                return String.format(java.util.Locale.ROOT, "%.1f", value);
            case "nomandColorSteps":
                return Math.round(value) + " 级";
            case "audioResponse":
            case "latentDitheringSpeed":
            case "latentDitheringAudioSpeed":
            case "latentMeshSpeed":
            case "latentMeshAudioSpeed":
            case "latentMeshDistortion":
                // 倍率类：0~2 的「多少倍」，写成小数比百分比直观。
                return String.format(java.util.Locale.ROOT, "%.2f", value);
            default:
                // 其余滑杆都是 0~1 的比例（不透明度、强度、对比度…）。
                return Math.round(value * 100f) + "%";
        }
    }

    /** 动作行的右侧提示：把「点了会发生什么」和当前状态写在同一行里。 */
    public static String actionValue(Spec spec) {
        if (spec == null) return "点击";
        if ("chooseImage".equals(spec.id)) {
            return FoliaCustomImage.available() ? "重新选择…" : "选择图片…";
        }
        if ("embedPrev".equals(spec.id) || "embedNext".equals(spec.id)) {
            int count = FoliaBackgroundConfig.embedItems.length;
            if (count == 0) return "列表为空";
            int index = FoliaBackgroundConfig.embedSelected;
            return (index < 0 ? "未选中" : (index + 1) + " / " + count) + " · 共 " + count + " 项";
        }
        if ("embedRemove".equals(spec.id)) {
            return FoliaBackgroundConfig.embedItems.length == 0 ? "列表为空" : "删除";
        }
        return "点击";
    }

    public static void cyclePick(Spec spec, boolean back) {
        switch (spec.id) {
            case "latentDisplayMode":
                FoliaBackgroundConfig.latentDisplayMode = cycle(FoliaBackgroundConfig.latentDisplayMode, 3, back);
                return;
            case "latentColorSource":
                FoliaBackgroundConfig.latentColorSource = cycle(FoliaBackgroundConfig.latentColorSource, 2, back);
                return;
            case "nomandImageSource":
                FoliaBackgroundConfig.nomandImageSource = cycle(FoliaBackgroundConfig.nomandImageSource, 2, back);
                return;
            case "nomandEffect":
                FoliaBackgroundConfig.nomandEffect = cycle(FoliaBackgroundConfig.nomandEffect, 5, back);
                return;
            case "nomandDitheringType":
                FoliaBackgroundConfig.nomandDitheringType = cycle(FoliaBackgroundConfig.nomandDitheringType, 3, back);
                return;
            case "backgroundKind":
                FoliaBackgroundConfig.backgroundKind = cycle(FoliaBackgroundConfig.backgroundKind, 3, back);
                return;
            default:
        }
    }

    public static int cycle(int value, int count, boolean back) {
        int next = value + (back ? -1 : 1);
        return ((next % count) + count) % count;
    }

    public static void runAction(String id) {
        switch (id) {
            case "reset":
                FoliaBackgroundConfig.resetAll();
                FoliaBackgroundConfig.save();
                return;
            case "resetMode":
                resetCurrentMode();
                FoliaBackgroundConfig.save();
                return;
            case "chooseImage":
                pickCustomImage();
                return;
            case "embedPrev":
            case "embedNext": {
                int count = FoliaBackgroundConfig.embedItems.length;
                if (count == 0) return;
                int next = FoliaBackgroundConfig.embedSelected + (id.equals("embedPrev") ? -1 : 1);
                FoliaBackgroundConfig.embedSelected = ((next % count) + count) % count;
                return;
            }
            case "embedPaste": {
                String clip = KeyboardUtils.getClipboardString();
                if (clip == null) return;
                String url = clip.trim();
                if (!url.startsWith("http://") && !url.startsWith("https://")) return;
                String[] items = FoliaBackgroundConfig.embedItems;
                String[] next = new String[items.length + 1];
                System.arraycopy(items, 0, next, 0, items.length);
                next[items.length] = url + "|" + url;
                FoliaBackgroundConfig.embedItems = next;
                FoliaBackgroundConfig.embedSelected = items.length;
                return;
            }
            case "embedRemove": {
                int index = FoliaBackgroundConfig.embedSelected;
                String[] items = FoliaBackgroundConfig.embedItems;
                if (index < 0 || index >= items.length) return;
                String[] next = new String[items.length - 1];
                int at = 0;
                for (int i = 0; i < items.length; i++) {
                    if (i != index) next[at++] = items[i];
                }
                FoliaBackgroundConfig.embedItems = next;
                FoliaBackgroundConfig.embedSelected = Math.min(index, next.length - 1);
                return;
            }
            case "embedClear":
                FoliaBackgroundConfig.embedItems = new String[0];
                FoliaBackgroundConfig.embedSelected = -1;
                return;
            default:
        }
    }

    /**
     * 「恢复本样式默认」：只把当前 {@link FoliaBackgroundMode} 自己的 tuning 字段写回 folia 出厂值。
     *
     * <p>刻意不调 {@link FoliaBackgroundConfig#resetAll()}：那个会把别的样式和全局项一起清掉，
     * 用户只是想撤回这一种背景这一次的折腾。数值必须与 {@code resetAll()} 里的同名字段逐项一致，
     * 改默认值要两处一起改（静态测试会逐个字段核对可达性）。</p>
     */
    public static void resetCurrentMode() {
        switch (FoliaBackgroundMode.current()) {
            case COMMON:
                FoliaBackgroundConfig.commonUseCoverColorBg = false;
                FoliaBackgroundConfig.commonCoverOpacity = 0.75f;
                FoliaBackgroundConfig.commonDisableGeometric = false;
                FoliaBackgroundConfig.commonDisableVignette = false;
                break;
            case NOMAND:
                FoliaBackgroundConfig.nomandImageSource = 0;
                FoliaBackgroundConfig.nomandEffect = 0;
                FoliaBackgroundConfig.nomandDitheringType = 2;
                FoliaBackgroundConfig.nomandSize = 3.0f;
                FoliaBackgroundConfig.nomandColorSteps = 4;
                FoliaBackgroundConfig.nomandOriginalColors = false;
                FoliaBackgroundConfig.nomandInverted = false;
                FoliaBackgroundConfig.nomandFlutedGlassSize = 0.5f;
                FoliaBackgroundConfig.nomandFlutedGlassDistortion = 0.5f;
                FoliaBackgroundConfig.nomandFlutedGlassBlur = 0.08f;
                FoliaBackgroundConfig.nomandPaperTextureContrast = 0.32f;
                FoliaBackgroundConfig.nomandPaperTextureRoughness = 0.42f;
                FoliaBackgroundConfig.nomandPaperTextureFiber = 0.3f;
                FoliaBackgroundConfig.nomandHalftoneDotsSize = 0.5f;
                FoliaBackgroundConfig.nomandHalftoneDotsRadius = 1.25f;
                FoliaBackgroundConfig.nomandHalftoneDotsContrast = 0.4f;
                FoliaBackgroundConfig.nomandHalftoneDotsOriginalColors = false;
                FoliaBackgroundConfig.nomandHalftoneDotsInverted = false;
                FoliaBackgroundConfig.nomandLensDistortionSpread = 0.45f;
                FoliaBackgroundConfig.nomandLensDistortionBulge = 0.3f;
                FoliaBackgroundConfig.nomandLensDistortionDispersion = 0.65f;
                FoliaBackgroundConfig.nomandOverlayEnabled = true;
                FoliaBackgroundConfig.nomandOverlayOpacity = 0.35f;
                break;
            case EMBED:
                FoliaBackgroundConfig.embedItems = new String[0];
                FoliaBackgroundConfig.embedSelected = -1;
                break;
            case LATENT:
            default:
                FoliaBackgroundConfig.latentDisplayMode = 2;
                FoliaBackgroundConfig.latentColorSource = 0;
                FoliaBackgroundConfig.latentDynamicOnlyInPlayer = true;
                FoliaBackgroundConfig.latentEnhancedBeatResponse = true;
                FoliaBackgroundConfig.latentDitheringSpeed = 0.1f;
                FoliaBackgroundConfig.latentDitheringAudioSpeed = 1.2f;
                FoliaBackgroundConfig.latentDitheringSize = 2.5f;
                FoliaBackgroundConfig.latentDitheringOpacity = 0.55f;
                FoliaBackgroundConfig.latentMeshSpeed = 0.3f;
                FoliaBackgroundConfig.latentMeshAudioSpeed = 2.0f;
                FoliaBackgroundConfig.latentMeshDistortion = 0.8f;
                FoliaBackgroundConfig.latentMeshSwirl = 0.1f;
                FoliaBackgroundConfig.latentOverlayEnabled = true;
                FoliaBackgroundConfig.latentOverlayOpacity = 0.35f;
                break;
        }
    }

    /**
     * 呼出系统文件选择框挑一张图片，复制成 {@code config/muonium/folia_background/background.png}，
     * 再把当前样式的来源切到「自定义图片」。
     *
     * <p>与 {@code UiFontLibrary#browseForFile()} 同一套路：原生对话框是模态的，直接在渲染线程
     * 弹会把整个游戏冻在那一帧，所以开一条守护线程去等用户选；选完只在后台线程做一次
     * {@code Files.copy}，复制用临时文件 + 原子替换，渲染线程不会读到写了一半的 PNG。</p>
     *
     * <p>这里不新增依赖：tinyfd 随 Minecraft（{@code lwjgl-tinyfd}）一起分发，但并非每个
     * 版本的编译期都能看见它，因此按名字反射调用，拿不到就什么都不做（面板仍可用，
     * 用户也可以手动把图片放到同名路径）。</p>
     */
    private static boolean installCustomImage(File source) {
        if (source == null || !source.isFile()) return false;
        if (!hasImageExtension(source.getName())) {
            System.out.println("[MuoniumPlayer/FoliaBg] 只支持 png / jpg / jpeg / bmp / gif: "
                    + source.getName());
            return false;
        }
        File target = customImageFile();
        File dir = target.getParentFile();
        try {
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return false;
            // 先写临时文件再原子替换：渲染线程任何时刻读到的都是完整 PNG。
            File temp = new File(dir, "background.png.tmp");
            java.nio.file.Files.copy(source.toPath(), temp.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                java.nio.file.Files.move(temp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicUnsupported) {
                java.nio.file.Files.move(temp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            FoliaCustomImage.clear();
            return true;
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/FoliaBg] 复制自定义背景图失败: " + failure);
            return false;
        }
    }

    private static boolean hasImageExtension(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".bmp") || lower.endsWith(".gif");
    }

    private static void markCustomSource(FoliaBackgroundMode mode) {
        if (mode == FoliaBackgroundMode.NOMAND) {
            FoliaBackgroundConfig.nomandImageSource = 1;
        }
    }

    private static String nativeOpenImageDialog() {
        try {
            Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
            Class<?> pointerBuffer = Class.forName("org.lwjgl.PointerBuffer");
            java.lang.reflect.Method open = dialogs.getMethod("tinyfd_openFileDialog",
                    CharSequence.class, CharSequence.class, pointerBuffer,
                    CharSequence.class, boolean.class);
            Object result = open.invoke(null, "\u9009\u62e9 Folia 背景图片",
                    customImageFile().getParentFile() == null ? null
                            : customImageFile().getParentFile().getAbsolutePath() + File.separator,
                    null, "png,jpg,jpeg", Boolean.FALSE);
            return result == null ? null : result.toString();
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/FoliaBg] 这个版本打不开系统文件选择框: " + failure);
            return null;
        }
    }

    /** 自定义图片的落盘路径（面板与宿主共用同一个约定）。 */
    public static File customImageFile() {
        return new File(com.muoniumplayer.core.settings.ConfigPaths.FOLIA_BACKGROUND_DIR,
                "background.png");
    }

    public static void pickCustomImage() {
        final FoliaBackgroundMode mode = FoliaBackgroundMode.current();
        Thread picker = new Thread(new Runnable() {
            @Override
            public void run() {
                String picked = nativeOpenImageDialog();
                if (picked == null || picked.trim().isEmpty()) return;
                if (installCustomImage(new File(picked.trim()))) {
                    markCustomSource(mode);
                }
            }
        }, "MuoniumPlayer-FoliaImagePicker");
        picker.setDaemon(true);
        picker.start();
    }

    // ──────────────────────────── 八档背景 ────────────────────────────

    /**
     * 背景的六个档位：两档 QPlayer + 四个 Folia 样式。
     *
     * <p>这是「一个菜单、切换即换配置项」的唯一枚举源。索引直接就是
     * {@link FoliaBackgroundConfig#backgroundKind} 的取值，所以落盘值与界面顺序天然一致；
     * 2 以后按 {@link FoliaBackgroundMode} 的顺序对应六个样式。</p>
     */
    public static final int KIND_QPLAYER_FLUID = 0;
    public static final int KIND_QPLAYER_BLUR = 1;
    public static final int KIND_FOLIA_FIRST = 2;

    public static final String[] KIND_LABELS = {
            "QPlayer 流体", "模糊封面",
            "通用", "隐现", "漫游", "嵌入"
    };

    public static int kindCount() {
        return KIND_LABELS.length;
    }

    public static int clampKind(int kind) {
        return ((kind % KIND_LABELS.length) + KIND_LABELS.length) % KIND_LABELS.length;
    }

    /** 当前选中的档位（0/1 = QPlayer，2.. = Folia 六个样式）。 */
    public static int currentKind() {
        return clampKind(FoliaBackgroundConfig.backgroundKind);
    }

    /** 这一档是不是 QPlayer（流体或模糊封面）。 */
    public static boolean isQPlayer(int kind) {
        int clamped = clampKind(kind);
        return clamped == KIND_QPLAYER_FLUID || clamped == KIND_QPLAYER_BLUR;
    }

    /** 这一档对应的 Folia 样式；QPlayer 那两档返回 {@code null}。 */
    public static FoliaBackgroundMode foliaModeOf(int kind) {
        if (isQPlayer(kind)) return null;
        return FoliaBackgroundMode.byIndex(clampKind(kind) - KIND_FOLIA_FIRST);
    }

    /**
     * 切到某一档：同时写「用哪套」与那一套自己的「用哪种」。
     *
     * <p>三处状态必须一起动，否则会出现「选了隐现、画面还是模糊封面」这种最迷惑的状态：
     * {@code backgroundKind} 决定渲染分派，{@code mode} 决定 Folia 画哪一个，
     * QPlayer 那两档还要把 {@code HudConfig.fullscreenLyricBackgroundMode} 设成 0/1。</p>
     */
    public static void applyKind(int kind) {
        int clamped = clampKind(kind);
        FoliaBackgroundConfig.backgroundKind = clamped;
        if (isQPlayer(clamped)) {
            if (hudBridge != null) {
                hudBridge.setBackgroundMode(clamped == KIND_QPLAYER_BLUR ? 1 : 0);
            }
        } else {
            FoliaBackgroundConfig.mode = FoliaBackgroundMode
                    .byIndex(clamped - KIND_FOLIA_FIRST).storedId();
        }
        FoliaBackgroundConfig.save();
    }

    /** 某一档在界面上显示的名字。QPlayer 流体那一档带当前算法名，信息量更大。 */
    public static String kindLabel(int kind) {
        int clamped = clampKind(kind);
        if (clamped == KIND_QPLAYER_FLUID && hudBridge != null) {
            String style = hudBridge.fluidStyleName();
            if (style != null && !style.isEmpty()) return KIND_LABELS[clamped] + " · " + style;
        }
        return KIND_LABELS[clamped];
    }

    /**
     * 读 / 写 QPlayer 那两个字段的钩子。
     *
     * <p>不让本类直接 import {@code HudConfig}：那个类是<b>按版本分叉</b>的
     * （见 {@code SHARE-MANIFEST.md}），共享层直接引用会让老树编译不过。由
     * {@code LyricStyleQuickPanel} 在类初始化时登记实现。</p>
     */
    public interface HudBridge {
        void setBackgroundMode(int mode);

        int backgroundMode();

        String fluidStyleName();
    }

    private static HudBridge hudBridge;

    public static void installHudBridge(HudBridge value) {
        hudBridge = value;
    }

    /** 某一档下面要显示哪些参数行 —— 「切换即换配置项」就靠它。 */
    public static List<Spec> rowsFor(int kind) {
        FoliaBackgroundMode mode = foliaModeOf(kind);
        if (mode == null) return new ArrayList<>();
        return rowsForFoliaMode(mode);
    }

    /**
     * 某个 Folia 样式自己的全部行（含分节标题）。
     *
     * <p>QPlayer 那两档的行由 {@code LyricStyleQuickPanel} 自己拼 —— 它要读 HudConfig 的
     * 流体算法表与两条滑杆，那些不属于本包。</p>
     */
    public static List<Spec> rowsForFoliaMode(FoliaBackgroundMode mode) {
        List<Spec> rows = new ArrayList<>();
        if (mode == null) return rows;
        switch (mode) {
            case COMMON:
                rows.add(SECTION_COMMON);
                rows.add(COMMON_COVER);
                rows.add(COMMON_OPACITY);
                rows.add(COMMON_GEOMETRIC);
                rows.add(COMMON_VIGNETTE);
                break;
            case NOMAND:
                rows.add(SECTION_NOMAND);
                rows.add(NOMAND_SOURCE);
                if (FoliaBackgroundConfig.nomandImageSource == 1) rows.add(CHOOSE_IMAGE);
                rows.add(NOMAND_EFFECT);
                rows.add(NOMAND_OVERLAY);
                if (FoliaBackgroundConfig.nomandOverlayEnabled) rows.add(NOMAND_OVERLAY_OPACITY);
                rows.add(SECTION_NOMAND_EFFECT);
                switch (FoliaBackgroundConfig.nomandEffect) {
                    case 1:
                        rows.add(NOMAND_GLASS_SIZE);
                        rows.add(NOMAND_GLASS_DISTORTION);
                        rows.add(NOMAND_GLASS_BLUR);
                        break;
                    case 2:
                        rows.add(NOMAND_PAPER_CONTRAST);
                        rows.add(NOMAND_PAPER_ROUGHNESS);
                        rows.add(NOMAND_PAPER_FIBER);
                        break;
                    case 3:
                        rows.add(NOMAND_DOT_SIZE);
                        rows.add(NOMAND_DOT_RADIUS);
                        rows.add(NOMAND_DOT_CONTRAST);
                        rows.add(NOMAND_DOT_ORIGINAL);
                        rows.add(NOMAND_DOT_INVERTED);
                        break;
                    case 4:
                        rows.add(NOMAND_LENS_SPREAD);
                        rows.add(NOMAND_LENS_BULGE);
                        rows.add(NOMAND_LENS_DISPERSION);
                        break;
                    default:
                        rows.add(NOMAND_DITHER_TYPE);
                        rows.add(NOMAND_SIZE);
                        rows.add(NOMAND_STEPS);
                        rows.add(NOMAND_ORIGINAL);
                        rows.add(NOMAND_INVERTED);
                        break;
                }
                break;
            case EMBED:
                rows.add(SECTION_EMBED);
                rows.add(EMBED_STATUS);
                rows.add(EMBED_PREV);
                rows.add(EMBED_NEXT);
                rows.add(EMBED_PASTE);
                rows.add(EMBED_REMOVE);
                rows.add(EMBED_CLEAR);
                break;
            case LATENT:
            default:
                rows.add(SECTION_LATENT);
                rows.add(LATENT_DISPLAY);
                rows.add(LATENT_COLOR);
                rows.add(LATENT_DYNAMIC);
                rows.add(LATENT_BEAT);
                rows.add(LATENT_OVERLAY);
                if (FoliaBackgroundConfig.latentOverlayEnabled) rows.add(LATENT_OVERLAY_OPACITY);
                rows.add(SECTION_LATENT_PIXEL);
                rows.add(LATENT_DITHER_SPEED);
                rows.add(LATENT_DITHER_AUDIO);
                rows.add(LATENT_DITHER_SIZE);
                rows.add(LATENT_DITHER_OPACITY);
                rows.add(SECTION_LATENT_MESH);
                rows.add(LATENT_MESH_SPEED);
                rows.add(LATENT_MESH_AUDIO);
                rows.add(LATENT_MESH_DISTORTION);
                rows.add(LATENT_MESH_SWIRL);
                break;
        }
        return rows;
    }

    /** 行高：与主面板的两种行高对齐（滑杆高一点，整行按钮矮一点）。 */
    public static double rowHeight(Spec spec) {
        if (spec == null) return 26.0;
        switch (spec.kind) {
            case KIND_SLIDER:
                return 32.0;
            case KIND_SECTION:
                return 20.0;
            case KIND_TEXT:
                return 34.0;
            default:
                return 26.0;
        }
    }
}



