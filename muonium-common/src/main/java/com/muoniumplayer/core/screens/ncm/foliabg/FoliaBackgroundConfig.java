package com.muoniumplayer.core.screens.ncm.foliabg;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

import java.io.File;

/**
 * 「Folia 背景」的全部可配置项。
 *
 * <p>这是从 本项目 的 {@code VisualizerBackgroundConfig} 一一对应搬过来的一份独立配置：
 * 那边每个背景的 tuning 各是一个对象（{@code latentBackgroundTuning}、{@code nomandBackgroundTuning}…），
 * 这里同样按背景分节存进 {@code config/muonium/folia_lyrics_background.json}。默认值全部取自
 * {@code src/types.ts} 里的 {@code DEFAULT_*_BACKGROUND_TUNING}，所以「第一次打开」看到的就是
 * folia 的出厂外观。</p>
 *
 * <h3>为什么不用 HudConfig</h3>
 * <p>两边是两套独立系统：QPlayer 流体背景读 {@code HudConfig.fullscreenLyricFluid*}，这一套只读
 * 本类。把近百个字段塞进 hud.json 会让任何一次拖动都重写整份 HUD，也会让「歌词背景」在设置树里
 * 看起来像 HUD 的子项。{@link ConfigPaths#FOLIA_LYRICS_BACKGROUND} 是它唯一的落盘点。</p>
 *
 * <h3>约定</h3>
 * <p>所有字段都是 {@code static}（与 HudConfig 一致），渲染线程每帧直接读，改完立刻生效；落盘只在
 * 松开鼠标或点一下开关时发生一次。{@link #load()} 会逐项夹取，手改坏的配置不会把画面搞炸。</p>
 */
public final class FoliaBackgroundConfig {

    private static final Gson GSON = new Gson();

    private FoliaBackgroundConfig() {
    }

    // ==================== 全局 ====================

    /**
     * 全屏歌词页用哪一套底图，取值见 {@link FoliaBackgroundOptions#KIND_LABELS}：
     * 0 = QPlayer 流体，1 = 模糊封面，2 以后是 Folia 的四种样式。
     *
     * <p>这些档是<b>平级</b>的：QPlayer 那两档走它自己的
     * {@code HudConfig.fullscreenLyricBackgroundMode} 与 {@code SkijaFluidBackground}，
     * Folia 那几档走本包。切回 QPlayer 时它的参数原样保留，切回 Folia 时这边的参数也原样保留
     * —— 两边互不改写，这就是「自由切换」的含义。</p>
     */
    public static int backgroundKind = 2;

    /** 旧的布尔口径：现在「用不用 Folia」等价于「档位不是 QPlayer 那两档」。 */
    public static boolean enabled() {
        return backgroundKind >= FoliaBackgroundOptions.KIND_FOLIA_FIRST;
    }

    /**
     * 当前背景样式的稳定 id（见 {@link FoliaBackgroundMode#storedId()}）。
     *
     * <p>存字符串而不是下标：删掉一种样式时，下标会让所有人的配置整体错位一格 ——
     * 选了「漫游」的人升级后看到「嵌入」，而他自己什么都没改。存 id 最坏只是回落到默认。</p>
     */
    public static String mode = FoliaBackgroundMode.LATENT.storedId();

    /** 省电静态模式：冻结所有动画与音频响应，只画一张静止画面。 */
    public static boolean staticMode = false;

    /**
     * 背景整体随音频响应的强度，0 表示完全忽略音频。
     *
     * <p>folia 自己把 {@code audioPower}/{@code audioBands} 直接喂给各背景；这里是唯一的总闸，
     * 关掉后每条支线（流速、抖动尺寸、星点亮度、几何呼吸）都退回各自的静止基速。</p>
     */
    public static float audioResponse = 1.0f;

    // ==================== 通用（common） ====================

    /** 是否用封面色流体作为底层（folia {@code common.useCoverColorBg}）。 */
    public static boolean commonUseCoverColorBg = false;
    /** 封面色流体之上那层主题底色遮罩的不透明度（folia {@code common.opacity}，默认 0.75）。 */
    public static float commonCoverOpacity = 0.75f;
    /** 关掉几何图形层（形状 + 光点）。 */
    public static boolean commonDisableGeometric = false;
    /** 关掉暗角。 */
    public static boolean commonDisableVignette = false;

    // ==================== 隐现（latent） ====================

    /** 0 = 只画像素层，1 = 只画流体层，2 = 两层融合（folia 默认）。 */
    public static int latentDisplayMode = 2;
    /** 0 = 封面 + 主题（默认），1 = 只用封面取色。 */
    public static int latentColorSource = 0;
    /** 只在播放页启用动态（主页保持静止以省 GPU）。 */
    public static boolean latentDynamicOnlyInPlayer = true;
    /** 增强节拍响应：把宽频能量上升沿也折进速度。 */
    public static boolean latentEnhancedBeatResponse = true;
    public static float latentDitheringSpeed = 0.1f;
    public static float latentDitheringAudioSpeed = 1.2f;
    public static float latentDitheringSize = 2.5f;
    public static float latentDitheringOpacity = 0.55f;
    public static float latentMeshSpeed = 0.3f;
    public static float latentMeshAudioSpeed = 2.0f;
    public static float latentMeshDistortion = 0.8f;
    public static float latentMeshSwirl = 0.1f;
    public static boolean latentOverlayEnabled = true;
    public static float latentOverlayOpacity = 0.35f;

    // ==================== 漫游（nomand） ====================

    /** 0 = 封面，1 = 自定义图片。 */
    public static int nomandImageSource = 0;
    /** 0 像素画，1 纹理玻璃，2 纸张纹理，3 半调网点，4 镜头畸变。 */
    public static int nomandEffect = 0;
    /** 像素画图案：0 = 2x2，1 = 4x4，2 = 8x8（folia 默认）。 */
    public static int nomandDitheringType = 2;
    public static float nomandSize = 3.0f;
    public static int nomandColorSteps = 4;
    public static boolean nomandOriginalColors = false;
    public static boolean nomandInverted = false;
    public static float nomandFlutedGlassSize = 0.5f;
    public static float nomandFlutedGlassDistortion = 0.5f;
    public static float nomandFlutedGlassBlur = 0.08f;
    public static float nomandPaperTextureContrast = 0.32f;
    public static float nomandPaperTextureRoughness = 0.42f;
    public static float nomandPaperTextureFiber = 0.3f;
    public static float nomandHalftoneDotsSize = 0.5f;
    public static float nomandHalftoneDotsRadius = 1.25f;
    public static float nomandHalftoneDotsContrast = 0.4f;
    public static boolean nomandHalftoneDotsOriginalColors = false;
    public static boolean nomandHalftoneDotsInverted = false;
    public static float nomandLensDistortionSpread = 0.45f;
    public static float nomandLensDistortionBulge = 0.3f;
    public static float nomandLensDistortionDispersion = 0.65f;
    public static boolean nomandOverlayEnabled = true;
    public static float nomandOverlayOpacity = 0.35f;

    // ==================== 嵌入（embed） ====================

    /**
     * 嵌入背景的图片源列表，每项 {@code url|note}。
     *
     * <p>folia 原版这里是真的 iframe（网页框）。Minecraft 的渲染层里没有浏览器，所以这一档
     * 等价成「网络图片源」：列表、选中、遮罩这几件事与 folia 完全一致，只是页面变成静态图。</p>
     */
    public static String[] embedItems = new String[0];
    public static int embedSelected = -1;

    // ==================== 读写 ====================

    private static File file() {
        return ConfigPaths.FOLIA_LYRICS_BACKGROUND;
    }

    /** 从磁盘读一份；没有文件就保持默认。任何一项越界都夹回去。 */
    public static synchronized void load() {
        File target = file();
        if (!target.isFile()) return;
        try {
            JsonObject root = JsonConfigStorage.readObject(target, GSON);
            if (root == null) return;
            backgroundKind = clampInt(getInt(root, "backgroundKind",
                    getBoolean(root, "enabled", true) ? 2 : 0), 0, 2);
            mode = readMode(root);
            staticMode = getBoolean(root, "staticMode", staticMode);
            audioResponse = clamp(getFloat(root, "audioResponse", audioResponse), 0f, 2f);

            JsonObject common = object(root, "common");
            commonUseCoverColorBg = getBoolean(common, "useCoverColorBg", commonUseCoverColorBg);
            commonCoverOpacity = clamp(getFloat(common, "coverOpacity", commonCoverOpacity), 0f, 1f);
            commonDisableGeometric = getBoolean(common, "disableGeometric", commonDisableGeometric);
            commonDisableVignette = getBoolean(common, "disableVignette", commonDisableVignette);

            JsonObject latent = object(root, "latent");
            latentDisplayMode = clampInt(getInt(latent, "displayMode", latentDisplayMode), 0, 2);
            latentColorSource = clampInt(getInt(latent, "colorSource", latentColorSource), 0, 1);
            latentDynamicOnlyInPlayer = getBoolean(latent, "dynamicOnlyInPlayer", latentDynamicOnlyInPlayer);
            latentEnhancedBeatResponse = getBoolean(latent, "enhancedBeatResponse", latentEnhancedBeatResponse);
            latentDitheringSpeed = clamp(getFloat(latent, "ditheringSpeed", latentDitheringSpeed), 0f, 2f);
            latentDitheringAudioSpeed = clamp(getFloat(latent, "ditheringAudioSpeed", latentDitheringAudioSpeed), 0f, 2f);
            latentDitheringSize = clamp(getFloat(latent, "ditheringSize", latentDitheringSize), 0.5f, 8f);
            latentDitheringOpacity = clamp(getFloat(latent, "ditheringOpacity", latentDitheringOpacity), 0f, 1f);
            latentMeshSpeed = clamp(getFloat(latent, "meshSpeed", latentMeshSpeed), 0f, 2f);
            latentMeshAudioSpeed = clamp(getFloat(latent, "meshAudioSpeed", latentMeshAudioSpeed), 0f, 2f);
            latentMeshDistortion = clamp(getFloat(latent, "meshDistortion", latentMeshDistortion), 0f, 2f);
            latentMeshSwirl = clamp(getFloat(latent, "meshSwirl", latentMeshSwirl), 0f, 1f);
            latentOverlayEnabled = getBoolean(latent, "overlayEnabled", latentOverlayEnabled);
            latentOverlayOpacity = clamp(getFloat(latent, "overlayOpacity", latentOverlayOpacity), 0f, 1f);


            JsonObject nomand = object(root, "nomand");
            nomandImageSource = clampInt(getInt(nomand, "imageSource", nomandImageSource), 0, 1);
            nomandEffect = clampInt(getInt(nomand, "effect", nomandEffect), 0, 4);
            nomandDitheringType = clampInt(getInt(nomand, "ditheringType", nomandDitheringType), 0, 2);
            nomandSize = clamp(getFloat(nomand, "size", nomandSize), 0.5f, 20f);
            nomandColorSteps = clampInt(getInt(nomand, "colorSteps", nomandColorSteps), 1, 7);
            nomandOriginalColors = getBoolean(nomand, "originalColors", nomandOriginalColors);
            nomandInverted = getBoolean(nomand, "inverted", nomandInverted);
            nomandFlutedGlassSize = clamp(getFloat(nomand, "flutedGlassSize", nomandFlutedGlassSize), 0.1f, 1f);
            nomandFlutedGlassDistortion = clamp(getFloat(nomand, "flutedGlassDistortion", nomandFlutedGlassDistortion), 0f, 1f);
            nomandFlutedGlassBlur = clamp(getFloat(nomand, "flutedGlassBlur", nomandFlutedGlassBlur), 0f, 1f);
            nomandPaperTextureContrast = clamp(getFloat(nomand, "paperTextureContrast", nomandPaperTextureContrast), 0f, 1f);
            nomandPaperTextureRoughness = clamp(getFloat(nomand, "paperTextureRoughness", nomandPaperTextureRoughness), 0f, 1f);
            nomandPaperTextureFiber = clamp(getFloat(nomand, "paperTextureFiber", nomandPaperTextureFiber), 0f, 1f);
            nomandHalftoneDotsSize = clamp(getFloat(nomand, "halftoneDotsSize", nomandHalftoneDotsSize), 0.1f, 1f);
            nomandHalftoneDotsRadius = clamp(getFloat(nomand, "halftoneDotsRadius", nomandHalftoneDotsRadius), 0.1f, 2f);
            nomandHalftoneDotsContrast = clamp(getFloat(nomand, "halftoneDotsContrast", nomandHalftoneDotsContrast), 0f, 1f);
            nomandHalftoneDotsOriginalColors = getBoolean(nomand, "halftoneDotsOriginalColors", nomandHalftoneDotsOriginalColors);
            nomandHalftoneDotsInverted = getBoolean(nomand, "halftoneDotsInverted", nomandHalftoneDotsInverted);
            nomandLensDistortionSpread = clamp(getFloat(nomand, "lensDistortionSpread", nomandLensDistortionSpread), 0f, 1f);
            nomandLensDistortionBulge = clamp(getFloat(nomand, "lensDistortionBulge", nomandLensDistortionBulge), -1f, 1f);
            nomandLensDistortionDispersion = clamp(getFloat(nomand, "lensDistortionDispersion", nomandLensDistortionDispersion), 0f, 1f);
            nomandOverlayEnabled = getBoolean(nomand, "overlayEnabled", nomandOverlayEnabled);
            nomandOverlayOpacity = clamp(getFloat(nomand, "overlayOpacity", nomandOverlayOpacity), 0f, 1f);

            JsonObject embed = object(root, "embed");
            embedItems = readEmbedItems(embed);
            embedSelected = clampInt(getInt(embed, "selected", embedSelected), -1, embedItems.length - 1);
        } catch (Throwable ignored) {
            // 读坏了就保持当前值：下一个 addProperty 会把整份配置写回干净状态。
        }
    }

    /** 把当前值整份写回磁盘。调用点只在「松手 / 点一下开关」之后。 */
    public static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("backgroundKind", backgroundKind);
            root.addProperty("mode", mode);
            root.addProperty("staticMode", staticMode);
            root.addProperty("audioResponse", audioResponse);

            JsonObject common = new JsonObject();
            common.addProperty("useCoverColorBg", commonUseCoverColorBg);
            common.addProperty("coverOpacity", commonCoverOpacity);
            common.addProperty("disableGeometric", commonDisableGeometric);
            common.addProperty("disableVignette", commonDisableVignette);
            root.add("common", common);

            JsonObject latent = new JsonObject();
            latent.addProperty("displayMode", latentDisplayMode);
            latent.addProperty("colorSource", latentColorSource);
            latent.addProperty("dynamicOnlyInPlayer", latentDynamicOnlyInPlayer);
            latent.addProperty("enhancedBeatResponse", latentEnhancedBeatResponse);
            latent.addProperty("ditheringSpeed", latentDitheringSpeed);
            latent.addProperty("ditheringAudioSpeed", latentDitheringAudioSpeed);
            latent.addProperty("ditheringSize", latentDitheringSize);
            latent.addProperty("ditheringOpacity", latentDitheringOpacity);
            latent.addProperty("meshSpeed", latentMeshSpeed);
            latent.addProperty("meshAudioSpeed", latentMeshAudioSpeed);
            latent.addProperty("meshDistortion", latentMeshDistortion);
            latent.addProperty("meshSwirl", latentMeshSwirl);
            latent.addProperty("overlayEnabled", latentOverlayEnabled);
            latent.addProperty("overlayOpacity", latentOverlayOpacity);
            root.add("latent", latent);


            JsonObject nomand = new JsonObject();
            nomand.addProperty("imageSource", nomandImageSource);
            nomand.addProperty("effect", nomandEffect);
            nomand.addProperty("ditheringType", nomandDitheringType);
            nomand.addProperty("size", nomandSize);
            nomand.addProperty("colorSteps", nomandColorSteps);
            nomand.addProperty("originalColors", nomandOriginalColors);
            nomand.addProperty("inverted", nomandInverted);
            nomand.addProperty("flutedGlassSize", nomandFlutedGlassSize);
            nomand.addProperty("flutedGlassDistortion", nomandFlutedGlassDistortion);
            nomand.addProperty("flutedGlassBlur", nomandFlutedGlassBlur);
            nomand.addProperty("paperTextureContrast", nomandPaperTextureContrast);
            nomand.addProperty("paperTextureRoughness", nomandPaperTextureRoughness);
            nomand.addProperty("paperTextureFiber", nomandPaperTextureFiber);
            nomand.addProperty("halftoneDotsSize", nomandHalftoneDotsSize);
            nomand.addProperty("halftoneDotsRadius", nomandHalftoneDotsRadius);
            nomand.addProperty("halftoneDotsContrast", nomandHalftoneDotsContrast);
            nomand.addProperty("halftoneDotsOriginalColors", nomandHalftoneDotsOriginalColors);
            nomand.addProperty("halftoneDotsInverted", nomandHalftoneDotsInverted);
            nomand.addProperty("lensDistortionSpread", nomandLensDistortionSpread);
            nomand.addProperty("lensDistortionBulge", nomandLensDistortionBulge);
            nomand.addProperty("lensDistortionDispersion", nomandLensDistortionDispersion);
            nomand.addProperty("overlayEnabled", nomandOverlayEnabled);
            nomand.addProperty("overlayOpacity", nomandOverlayOpacity);
            root.add("nomand", nomand);

            JsonObject embed = new JsonObject();
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < embedItems.length; i++) {
                if (i > 0) list.append('\n');
                list.append(embedItems[i]);
            }
            embed.addProperty("items", list.toString());
            embed.addProperty("selected", embedSelected);
            root.add("embed", embed);

            JsonConfigStorage.writeObject(file(), GSON, root);
        } catch (Throwable ignored) {
            // 落盘失败不阻断渲染：下一次改动会再试。
        }
    }

    /** 把全部字段恢复出厂（folia 默认）。 */
    public static synchronized void resetAll() {
        backgroundKind = 2;
        mode = FoliaBackgroundMode.LATENT.storedId();
        staticMode = false;
        audioResponse = 1.0f;
        commonUseCoverColorBg = false;
        commonCoverOpacity = 0.75f;
        commonDisableGeometric = false;
        commonDisableVignette = false;
        latentDisplayMode = 2;
        latentColorSource = 0;
        latentDynamicOnlyInPlayer = true;
        latentEnhancedBeatResponse = true;
        latentDitheringSpeed = 0.1f;
        latentDitheringAudioSpeed = 1.2f;
        latentDitheringSize = 2.5f;
        latentDitheringOpacity = 0.55f;
        latentMeshSpeed = 0.3f;
        latentMeshAudioSpeed = 2.0f;
        latentMeshDistortion = 0.8f;
        latentMeshSwirl = 0.1f;
        latentOverlayEnabled = true;
        latentOverlayOpacity = 0.35f;
        nomandImageSource = 0;
        nomandEffect = 0;
        nomandDitheringType = 2;
        nomandSize = 3.0f;
        nomandColorSteps = 4;
        nomandOriginalColors = false;
        nomandInverted = false;
        nomandFlutedGlassSize = 0.5f;
        nomandFlutedGlassDistortion = 0.5f;
        nomandFlutedGlassBlur = 0.08f;
        nomandPaperTextureContrast = 0.32f;
        nomandPaperTextureRoughness = 0.42f;
        nomandPaperTextureFiber = 0.3f;
        nomandHalftoneDotsSize = 0.5f;
        nomandHalftoneDotsRadius = 1.25f;
        nomandHalftoneDotsContrast = 0.4f;
        nomandHalftoneDotsOriginalColors = false;
        nomandHalftoneDotsInverted = false;
        nomandLensDistortionSpread = 0.45f;
        nomandLensDistortionBulge = 0.3f;
        nomandLensDistortionDispersion = 0.65f;
        nomandOverlayEnabled = true;
        nomandOverlayOpacity = 0.35f;
    }

    // ==================== 小工具 ====================

    /** 某一种样式是否在音频响应之外还要求封面纹理。 */
    public static boolean needsCover(FoliaBackgroundMode mode) {
        switch (mode) {
            case COMMON:
                return commonUseCoverColorBg;
            case LATENT:
            case NOMAND:
                return true;
            default:
                return false;
        }
    }

    /**
     * 读「当前背景样式」：新格式是字符串 id，老格式是下标。
     *
     * <p>老格式必须走 {@link FoliaBackgroundMode#fromLegacyIndex(int)} 迁移，因为这次
     * 删掉了「空」「莫奈」两档，旧下标 3/4/5 对应的已经不是同一个样式了。判断依据是
     * JSON 里那个值的类型：字符串＝新格式，数字＝老格式。读不出来就用默认（隐现）。</p>
     */
    private static String readMode(JsonObject root) {
        if (root == null) return FoliaBackgroundMode.LATENT.storedId();
        com.google.gson.JsonElement element = root.get("mode");
        if (element == null) return FoliaBackgroundMode.LATENT.storedId();
        try {
            if (element.isJsonPrimitive()
                    && element.getAsJsonPrimitive().isString()) {
                return FoliaBackgroundMode.byStoredId(element.getAsString()).storedId();
            }
            int legacy = element.getAsInt();
            return FoliaBackgroundMode.fromLegacyIndex(legacy).storedId();
        } catch (Throwable ignored) {
            return FoliaBackgroundMode.LATENT.storedId();
        }
    }

    private static JsonObject object(JsonObject root, String name) {
        if (root == null) return null;
        com.google.gson.JsonElement element = root.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String[] readEmbedItems(JsonObject embed) {
        if (embed == null) return new String[0];
        String raw = getString(embed, "items", "");
        if (raw.isEmpty()) return new String[0];
        String[] lines = raw.split("\n");
        int count = 0;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) count++;
        }
        String[] result = new String[count];
        int at = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty()) result[at++] = line;
        }
        return result;
    }

    private static String normalizeHex(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) trimmed = trimmed.substring(1);
        if (trimmed.length() != 6) return fallback;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(trimmed.charAt(i), 16) < 0) return fallback;
        }
        return "#" + trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    private static int getInt(JsonObject object, String name, int fallback) {
        if (object == null) return fallback;
        try {
            com.google.gson.JsonElement element = object.get(name);
            return element == null ? fallback : element.getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float getFloat(JsonObject object, String name, float fallback) {
        if (object == null) return fallback;
        try {
            com.google.gson.JsonElement element = object.get(name);
            return element == null ? fallback : element.getAsFloat();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean getBoolean(JsonObject object, String name, boolean fallback) {
        if (object == null) return fallback;
        try {
            com.google.gson.JsonElement element = object.get(name);
            return element == null ? fallback : element.getAsBoolean();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String getString(JsonObject object, String name, String fallback) {
        if (object == null) return fallback;
        try {
            com.google.gson.JsonElement element = object.get(name);
            return element == null ? fallback : element.getAsString();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    public static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) return min;
        return value < min ? min : (value > max ? max : value);
    }
}


