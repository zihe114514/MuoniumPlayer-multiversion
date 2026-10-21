package com.muoniumplayer.core.screens.ncm.foliabg;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.CoverPalette;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaImages;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffectBuilder;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * 全屏歌词页的 Folia 背景宿主。
 *
 * <p>一个入口 {@link #render} 负责整条链路：取封面 → 缩到 256 工作纹理 → 按
 * {@link FoliaBackgroundMode} 选渲染器 → 画到歌词页的矩形里。与 QPlayer 那套完全独立：
 * 那边读 {@code HudConfig.fullscreenLyricFluid*}，这边只读 {@link FoliaBackgroundConfig}；
 * 两边共用的是「怎么把 GL 纹理借给 Skia」这一层（{@link SkijaImages}），不是任何状态。</p>
 *
 * <h3>图层顺序（对齐 folia 的 VisualizerBackdrop）</h3>
 * <ol>
 *   <li>背景本体（{@link FoliaBackgroundMode}）</li>
 *   <li>主题底色 / 各背景自己的遮罩（Latent 与 Nomand 的 overlay）</li>
 *   <li>通用背景的暗角与几何层</li>
 * </ol>
 * 视频层与模组舞台层在 folia 里属于宿主，播放器没有对应物，这里不画。
 *
 * <h3>静态模式</h3>
 * <p>{@code staticMode} 下时间冻结（用固定时刻），音频输入清零；与 folia 的
 * {@code VisualizerBackgroundRenderProps.staticMode} 一致 —— 省电、也不在后台抖。</p>
 */
public final class FoliaBackgroundHost {

    /** 封面工作纹理边长；抖动格与网格锚点需要真实像素，32px 不够。 */
    private static final int COVER_TEX = FoliaSkSL.COVER_TEX;

    private final FoliaAudioFrame audio = new FoliaAudioFrame();
    private final FoliaCommonBackground commonBackground = new FoliaCommonBackground();


    /** 动画时钟：与音频解耦，暂停/静音时仍以基速漂移（folia 的行为）。 */
    private double clock;
    /** 每套背景各自的相位偏移，避免切换时画面「从同一个点开始」。 */
    private double latentMeshClock;
    private double latentDitherClock;
    private double nomandClock;
    private long lastNanos;

    /** 借来的封面视图与它自己的 256 工作纹理。 */
    private Image coverSource;
    private Image coverTexture;
    private Shader coverShader;
    private String coverKey;
    /** 当前封面提取出的代表色（0xRRGGBB）。 */
    private int[] coverColors = new int[0];

    /** 上一帧的尺寸，用来判断静态缓存是否失效。 */
    private float lastWidth = -1f;
    private float lastHeight = -1f;

    /** 上一次成功的画面（静态模式的缓存帧）。 */
    private Image staticFrame;
    private int staticW = -1;
    private int staticH = -1;
    private boolean staticDirty = true;

    /** 切换背景时做一次淡入，避免硬切。 */
    private FoliaBackgroundMode shownMode;
    private long modeChangedAt;

    public void dispose() {
        releaseCover();
        releaseStatic();
        audio.reset();
    }

    public void reset() {
        releaseCover();
        releaseStatic();
        audio.reset();
        clock = 0;
        latentMeshClock = 0;
        latentDitherClock = 0;
        nomandClock = 0;
        lastNanos = 0L;
        shownMode = null;
        coverKey = null;
        coverColors = new int[0];
    }

    /**
     * 画一帧。
     *
     * @return 是否真的画了东西；{@code false} 时调用方铺兜底底色。
     */
    public boolean render(double x, double y, double width, double height, float alpha, Music current) {
        if (!SkijaGfx.active() || width < 1.0 || height < 1.0 || alpha <= .004f) return false;
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return false;

        FoliaBackgroundMode mode = FoliaBackgroundMode.current();
        if (shownMode != mode) {
            shownMode = mode;
            modeChangedAt = System.nanoTime();
            staticDirty = true;
            // 换样式就是换一整套画法：上一档留下的缓存与时钟相位都不再适用。
        }

        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        if (dt < 0.0) dt = 0.0;
        if (dt > .05) dt = .05;

        boolean frozen = FoliaBackgroundConfig.staticMode;
        boolean paused = !CloudMusic.isAudibleNow();
        FoliaTheme theme = FoliaAudioFrame.theme();
        audio.update(!frozen && wantsBands(mode));
        if (frozen) {
            audio.power = 0f;
            audio.beat = 0f;
            audio.bass = audio.lowMid = audio.mid = audio.vocal = audio.treble = 0f;
        }

        // 各背景自己的基速。Latent 两层各有 base → audio 的目标速度，用同一个平滑系数混合
        // （folia: lerp(base, audioSpeed, smoothedBeatSpeed)）；暂停时降到 12%（原版行为）。
        double speed = frozen ? 0.0 : (paused ? 0.12 : 1.0);
        clock += dt * speed;
        double meshSpeed = lerp(FoliaBackgroundConfig.latentMeshSpeed,
                FoliaBackgroundConfig.latentMeshAudioSpeed, audio.speedFactor);
        double ditherSpeed = lerp(FoliaBackgroundConfig.latentDitheringSpeed,
                FoliaBackgroundConfig.latentDitheringAudioSpeed, audio.speedFactor);
        latentMeshClock += dt * speed * Math.max(0.0, meshSpeed);
        latentDitherClock += dt * speed * Math.max(0.0, ditherSpeed);
        nomandClock += dt * speed;

        // 自定义图片来源（漫游的 uploaded-global）优先于封面，两种来源互为回落。
        boolean wantsCustom = mode == FoliaBackgroundMode.NOMAND
                && FoliaBackgroundConfig.nomandImageSource == 1;
        Image custom = wantsCustom ? FoliaCustomImage.get() : null;
        boolean needsCover = FoliaBackgroundConfig.needsCover(mode) || mode == FoliaBackgroundMode.EMBED;
        if (custom != null) {
            useCustomCover(custom);
        } else if (needsCover) {
            updateCover(current);
        }

        int save = canvas.save();
        try {
            canvas.translate((float) x, (float) y);
            Rect full = Rect.makeXYWH(0f, 0f, (float) width, (float) height);
            int clip = canvas.save();
            canvas.clipRect(full);
            try {
                boolean drawn;
                switch (mode) {
                    case COMMON:
                        drawn = drawCommon(canvas, (float) width, (float) height, theme, alpha);
                        break;
                    case NOMAND:
                        drawn = drawNomand(canvas, (float) width, (float) height, theme, alpha);
                        break;
                    case EMBED:
                        drawn = drawEmbed(canvas, (float) width, (float) height, theme, alpha);
                        break;
                    case LATENT:
                    default:
                        drawn = drawLatent(canvas, (float) width, (float) height, theme, alpha);
                        break;
                }
                if (drawn) drawModeFade(canvas, (float) width, (float) height, alpha);
                return drawn;
            } finally {
                canvas.restoreToCount(clip);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static double lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static boolean wantsBands(FoliaBackgroundMode mode) {
        return mode == FoliaBackgroundMode.LATENT || mode == FoliaBackgroundMode.NOMAND;
    }

    // ==================================================================
    // 隐现 / Latent
    // ==================================================================

    private boolean drawLatent(Canvas canvas, float width, float height, FoliaTheme theme, float alpha) {
        var effect = FoliaSkSL.effect(FoliaSkSL.LATENT);
        if (effect == null) {
            fill(canvas, width, height, theme.background, alpha);
            return false;
        }

        int[] palette = paletteFor(theme);
        int backgroundColor = theme.background;
        int ditherBack;
        int ditherFront;
        int[] mesh = new int[6];
        if (FoliaBackgroundConfig.latentColorSource == 1) {
            int primary = palette.length > 0 ? palette[0] : theme.secondary;
            int secondary = palette.length > 1 ? palette[1] : primary;
            int tertiary = palette.length > 2 ? palette[2] : secondary;
            int quaternary = palette.length > 3 ? palette[3] : primary;
            int quinary = palette.length > 4 ? palette[4] : secondary;
            int senary = palette.length > 5 ? palette[5] : tertiary;
            ditherBack = tertiary;
            ditherFront = primary;
            mesh[0] = primary;
            mesh[1] = secondary;
            mesh[2] = tertiary;
            mesh[3] = quaternary;
            mesh[4] = quinary;
            mesh[5] = senary;
        } else {
            int primary = palette.length > 0 ? palette[0] : theme.secondary;
            int secondary = palette.length > 1 ? palette[1] : theme.primary;
            int tertiary = palette.length > 2 ? palette[2] : primary;
            int quaternary = palette.length > 3 ? palette[3] : secondary;
            ditherBack = theme.background;
            ditherFront = primary;
            mesh[0] = primary;
            mesh[1] = secondary;
            mesh[2] = tertiary;
            mesh[3] = quaternary;
            mesh[4] = theme.background;
            mesh[5] = theme.accent;
        }

        // 底色（folia 在 mesh 下面垫 theme.backgroundColor）
        fill(canvas, width, height, backgroundColor, alpha);

        // 音频派生量，逐条对齐 LatentBackground.tsx 的每帧写法。
        float power = audio.power;
        float bass = audio.bass / 255f;
        float midBand = Math.max(audio.mid, audio.vocal) / 255f;

        float distortion = FoliaBackgroundConfig.latentMeshDistortion + power * 0.62f;
        float swirl = FoliaBackgroundConfig.latentMeshSwirl + midBand * 0.38f;
        float meshSaturation = 1.04f + midBand * 0.34f;
        float meshBrightness = 0.94f + power * 0.16f;
        float meshScale = 1.025f + power * 0.018f;
        float ditherScale = 1.015f + bass * 0.025f;
        float ditherSize = Math.max(0.5f,
                FoliaBackgroundConfig.latentDitheringSize
                        - bass * FoliaBackgroundConfig.latentDitheringSize * 0.34f);
        float ditherOpacity = Math.min(1f,
                FoliaBackgroundConfig.latentDitheringOpacity + bass * 0.25f);

        int displayMode = FoliaBackgroundConfig.latentDisplayMode;
        boolean showMesh = displayMode != 0;
        boolean showDither = displayMode != 1;

        if (showMesh) {
            drawLatentLayer(canvas, width, height, alpha, effect, 0f, mesh, distortion, swirl,
                    meshSaturation, meshBrightness, meshScale, ditherBack, ditherFront,
                    ditherSize, 1f, ditherScale);
        }
        if (showDither) {
            // 只有抖动层时它是不透明底（folia 的 mixBlendMode: normal + opacity 1）。
            float layerAlpha = showMesh ? ditherOpacity : 1f;
            drawLatentLayer(canvas, width, height, alpha, effect, 1f, mesh, distortion, swirl,
                    meshSaturation, meshBrightness, meshScale, ditherBack, ditherFront,
                    ditherSize, layerAlpha, ditherScale);
        }

        if (FoliaBackgroundConfig.latentOverlayEnabled
                && FoliaBackgroundConfig.latentOverlayOpacity > 0f) {
            fill(canvas, width, height, backgroundColor,
                    alpha * FoliaBackgroundConfig.latentOverlayOpacity);
        }
        return true;
    }

    private void drawLatentLayer(Canvas canvas, float width, float height, float alpha,
                                 io.github.humbleui.skija.RuntimeEffect effect, float layer,
                                 int[] mesh, float distortion, float swirl, float saturation,
                                 float brightness, float meshScale, int ditherBack,
                                 int ditherFront, float ditherSize, float ditherOpacity,
                                 float ditherScale) {
        Shader shaded = null;
        try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(effect)) {
            builder.setUniform("resolution", width, height);
            builder.setUniform("meshTime", (float) latentMeshClock);
            builder.setUniform("ditherTime", (float) latentDitherClock);
            builder.setUniform("alpha", alpha);
            builder.setUniform("layer", layer);
            builder.setUniform("c0", FoliaTheme.r(mesh[0]), FoliaTheme.g(mesh[0]), FoliaTheme.b(mesh[0]), 1f);
            builder.setUniform("c1", FoliaTheme.r(mesh[1]), FoliaTheme.g(mesh[1]), FoliaTheme.b(mesh[1]), 1f);
            builder.setUniform("c2", FoliaTheme.r(mesh[2]), FoliaTheme.g(mesh[2]), FoliaTheme.b(mesh[2]), 1f);
            builder.setUniform("c3", FoliaTheme.r(mesh[3]), FoliaTheme.g(mesh[3]), FoliaTheme.b(mesh[3]), 1f);
            builder.setUniform("c4", FoliaTheme.r(mesh[4]), FoliaTheme.g(mesh[4]), FoliaTheme.b(mesh[4]), 1f);
            builder.setUniform("c5", FoliaTheme.r(mesh[5]), FoliaTheme.g(mesh[5]), FoliaTheme.b(mesh[5]), 1f);
            builder.setUniform("distortion", distortion);
            builder.setUniform("swirl", swirl);
            builder.setUniform("grainOverlay", 0.01f);
            builder.setUniform("meshSaturation", saturation);
            builder.setUniform("meshBrightness", brightness);
            builder.setUniform("meshScale", meshScale);
            builder.setUniform("ditherBack", FoliaTheme.r(ditherBack),
                    FoliaTheme.g(ditherBack), FoliaTheme.b(ditherBack));
            builder.setUniform("ditherFront", FoliaTheme.r(ditherFront),
                    FoliaTheme.g(ditherFront), FoliaTheme.b(ditherFront));
            builder.setUniform("ditherSize", ditherSize);
            builder.setUniform("ditherOpacity", ditherOpacity);
            builder.setUniform("ditherScale", ditherScale);
            shaded = builder.makeShader();
            try (Paint paint = new Paint()) {
                paint.setShader(shaded);
                paint.setAlphaf(1f);
                if (layer > 0.5f) {
                    // 叠加层用 soft-light，与 folia 的 CSS mix-blend-mode 一致。
                    paint.setBlendMode(BlendMode.SOFT_LIGHT);
                }
                canvas.drawRect(Rect.makeXYWH(0f, 0f, width, height), paint);
            }
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/FoliaBg] Latent 绘制失败: " + failure);
        } finally {
            if (shaded != null) shaded.close();
        }
    }

    private boolean drawNomand(Canvas canvas, float width, float height, FoliaTheme theme, float alpha) {
        fill(canvas, width, height, theme.background, alpha);
        if (coverShader == null) return true;
        var effect = FoliaSkSL.effect(FoliaSkSL.NOMAND);
        if (effect == null) return true;

        int front = theme.accent;
        int back = theme.background;
        int highlight = theme.primary;
        int shadow = theme.secondary;

        boolean originalColors = FoliaBackgroundConfig.nomandOriginalColors;
        boolean inverted = daylightInversion(FoliaBackgroundConfig.nomandInverted,
                originalColors, theme.daylight);

        int effectIndex = FoliaBackgroundConfig.nomandEffect;
        boolean halftoneOriginal = FoliaBackgroundConfig.nomandHalftoneDotsOriginalColors;
        if (effectIndex == 3) {
            inverted = halftoneInversion(FoliaBackgroundConfig.nomandHalftoneDotsInverted,
                    halftoneOriginal, theme.daylight);
        }

        Shader shaded = null;
        try (RuntimeEffectBuilder builder = new RuntimeEffectBuilder(effect)) {
            builder.setUniform("resolution", width, height);
            builder.setUniform("alpha", alpha);
            builder.setUniform("effect", (float) effectIndex);
            builder.setUniform("inverted", inverted ? 1f : 0f);
            builder.setUniform("originalColors",
                    (effectIndex == 3 ? halftoneOriginal : originalColors) ? 1f : 0f);
            builder.setUniform("colorSteps", (float) FoliaBackgroundConfig.nomandColorSteps);
            builder.setUniform("pxSize", Math.max(0.5f, FoliaBackgroundConfig.nomandSize));
            builder.setUniform("ditherType", (float) FoliaBackgroundConfig.nomandDitheringType);
            builder.setUniform("colorFront", FoliaTheme.r(front), FoliaTheme.g(front), FoliaTheme.b(front), 1f);
            builder.setUniform("colorBack", FoliaTheme.r(back), FoliaTheme.g(back), FoliaTheme.b(back), 1f);
            builder.setUniform("colorHighlight", FoliaTheme.r(highlight), FoliaTheme.g(highlight), FoliaTheme.b(highlight), 1f);
            builder.setUniform("colorShadow", FoliaTheme.r(shadow), FoliaTheme.g(shadow), FoliaTheme.b(shadow), 1f);
            builder.setUniform("glassSize", FoliaBackgroundConfig.nomandFlutedGlassSize);
            builder.setUniform("glassDistortion", FoliaBackgroundConfig.nomandFlutedGlassDistortion);
            builder.setUniform("glassBlur", FoliaBackgroundConfig.nomandFlutedGlassBlur);
            builder.setUniform("paperContrast", FoliaBackgroundConfig.nomandPaperTextureContrast);
            builder.setUniform("paperRoughness", FoliaBackgroundConfig.nomandPaperTextureRoughness);
            builder.setUniform("paperFiber", FoliaBackgroundConfig.nomandPaperTextureFiber);
            builder.setUniform("dotSize", FoliaBackgroundConfig.nomandHalftoneDotsSize);
            builder.setUniform("dotRadius", FoliaBackgroundConfig.nomandHalftoneDotsRadius);
            builder.setUniform("dotContrast", FoliaBackgroundConfig.nomandHalftoneDotsContrast);
            builder.setUniform("lensSpread", FoliaBackgroundConfig.nomandLensDistortionSpread);
            builder.setUniform("lensBulge", FoliaBackgroundConfig.nomandLensDistortionBulge);
            builder.setUniform("lensDispersion", FoliaBackgroundConfig.nomandLensDistortionDispersion);
            builder.setUniform("brightness", 0.92f + audio.power * 0.16f);
            builder.setUniform("themeBack", FoliaTheme.r(back), FoliaTheme.g(back), FoliaTheme.b(back));
            builder.setChild("cover", coverShader);
            shaded = builder.makeShader();
            try (Paint paint = new Paint()) {
                paint.setShader(shaded);
                canvas.drawRect(Rect.makeXYWH(0f, 0f, width, height), paint);
            }
        } catch (Throwable failure) {
            System.out.println("[MuoniumPlayer/FoliaBg] Nomand 绘制失败: " + failure);
        } finally {
            if (shaded != null) shaded.close();
        }

        if (FoliaBackgroundConfig.nomandOverlayEnabled
                && FoliaBackgroundConfig.nomandOverlayOpacity > 0f) {
            fill(canvas, width, height, back, alpha * FoliaBackgroundConfig.nomandOverlayOpacity);
        }
        return true;
    }

    /** 浅色主题下非原色模式要翻转亮度，见 nomandShaderAdjustments.ts。 */
    private static boolean daylightInversion(boolean inverted, boolean originalColors, boolean daylight) {
        return daylight && !originalColors ? !inverted : inverted;
    }

    /** 半调网点的亮度约定与像素画相反，基准先翻一次。 */
    private static boolean halftoneInversion(boolean inverted, boolean originalColors, boolean daylight) {
        return daylightInversion(!inverted, originalColors, daylight);
    }

    private boolean drawCommon(Canvas canvas, float width, float height, FoliaTheme theme, float alpha) {
        // 通用背景的几何层与暗角收在 FoliaCommonBackground 里（与 folia 的 GeometricBackground 同构）。
        commonBackground.render(canvas, width, height, theme, alpha, clock,
                new float[]{audio.bass, audio.lowMid, audio.mid, audio.vocal, audio.treble},
                FoliaBackgroundConfig.commonUseCoverColorBg ? coverShader : null);
        return true;
    }

    // ==================================================================
    // 嵌入 / Embed
    // ==================================================================

    private boolean drawEmbed(Canvas canvas, float width, float height, FoliaTheme theme, float alpha) {
        fill(canvas, width, height, 0x000000, alpha);
        String url = selectedEmbedUrl();
        if (url == null) return true;
        Image image = FoliaEmbedImages.get(url);
        if (image != null) {
            float imageW = image.getWidth();
            float imageH = image.getHeight();
            float scale = Math.max(width / imageW, height / imageH);
            float drawW = imageW * scale;
            float drawH = imageH * scale;
            try (Paint paint = new Paint()) {
                paint.setAlphaf(FoliaTheme.clamp(alpha, 0f, 1f));
                canvas.drawImageRect(image,
                        Rect.makeWH(imageW, imageH),
                        Rect.makeXYWH((width - drawW) * 0.5f, (height - drawH) * 0.5f, drawW, drawH),
                        SamplingMode.LINEAR, paint, true);
            }
        }
        // folia 的 iframe 上方那层可读性遮罩：to bottom, .15 → .35
        try (Shader shade = Shader.makeLinearGradient(0f, 0f, 0f, height,
                new int[]{alpha(0x000000, 0.15f * alpha), alpha(0x000000, 0.35f * alpha)})) {
            try (Paint paint = new Paint()) {
                paint.setShader(shade);
                canvas.drawRect(Rect.makeXYWH(0f, 0f, width, height), paint);
            }
        }
        return true;
    }

    static String selectedEmbedUrl() {
        String[] items = FoliaBackgroundConfig.embedItems;
        int index = FoliaBackgroundConfig.embedSelected;
        if (items == null || index < 0 || index >= items.length) return null;
        String item = items[index];
        int split = item.indexOf('|');
        String url = split < 0 ? item : item.substring(0, split);
        return url == null || url.trim().isEmpty() ? null : url.trim();
    }

    // ==================================================================
    // 切换淡入
    // ==================================================================

    /**
     * 换背景时的一小段淡入。
     *
     * <p>folia 用柔焦过渡（旧样式放大虚化退下、新样式从虚化里落定）；这里没有离屏图层，
     * 用「整体压暗 220ms 淡出」近似一次软切。</p>
     */
    private void drawModeFade(Canvas canvas, float width, float height, float alpha) {
        long elapsed = System.nanoTime() - modeChangedAt;
        float progress = Math.min(1f, elapsed / 220_000_000f);
        if (progress >= 1f) return;
        float shade = (1f - progress) * 0.55f;
        try (Paint paint = new Paint()) {
            paint.setColor(alpha(0x000000, shade * alpha));
            canvas.drawRect(Rect.makeXYWH(0f, 0f, width, height), paint);
        }
    }

    // ==================================================================
    // 封面
    // ==================================================================

    private void updateCover(Music current) {
        if (current == null) return;
        String key = current.getStableKey();
        if (Objects.equals(key, coverKey) && coverShader != null) return;
        Location location = CloudMusic.preferredCoverLocation(current, current.getCoverLocation());
        if (location == null) return;
        ITextureObject texture = TextureManager.getInstance().getTexture(location);
        if (texture == null) return;
        if (SkijaImages.sizeOf(texture.getGlTextureId()) == null) return;
        Image source = SkijaImages.wrapForShader(texture.getGlTextureId());
        if (source == null) return;
        releaseCover();
        coverKey = key;
        coverSource = source;
        coverTexture = makeCoverTexture(source);
        coverShader = FoliaSkSL.childShader(coverTexture);
        coverColors = readPalette(current);
        staticDirty = true;
    }

    private void useCustomCover(Image custom) {
        if (custom == coverSource && coverShader != null) return;
        releaseCover();
        coverKey = "custom:" + System.identityHashCode(custom);
        coverSource = custom;
        coverTexture = makeCoverTexture(custom);
        coverShader = FoliaSkSL.childShader(coverTexture);
        coverColors = readPaletteFrom(custom);
        staticDirty = true;
    }

    private int[] readPaletteFrom(Image source) {
        try {
            BufferedImage image = FoliaCoverSampler.sample(source);
            if (image != null) {
                int[] palette = CoverPalette.extract(image, 6);
                if (palette.length > 0) return palette;
            }
        } catch (Throwable ignored) {
        }
        return new int[0];
    }

    private static Image makeCoverTexture(Image source) {
        try {
            ImageInfo info = new ImageInfo(COVER_TEX, COVER_TEX,
                    ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
            Bitmap thumb = new Bitmap();
            if (!thumb.allocPixels(info)) {
                thumb.close();
                return null;
            }
            try (Canvas canvas = new Canvas(thumb)) {
                canvas.drawImageRect(source,
                        Rect.makeWH(source.getWidth(), source.getHeight()),
                        Rect.makeWH(COVER_TEX, COVER_TEX), SamplingMode.LINEAR, null, true);
            }
            Image image = Image.makeFromBitmap(thumb);
            thumb.close();
            return image;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int[] readPalette(Music current) {
        try {
            int[] palette = current.getCoverPalette();
            if (palette != null && palette.length > 0) return palette;
        } catch (Throwable ignored) {
        }
        try {
            BufferedImage image = FoliaCoverSampler.sample(coverSource);
            if (image != null) {
                int[] palette = CoverPalette.extract(image, 6);
                if (palette.length > 0) {
                    current.setCoverPalette(palette);
                    return palette;
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[0];
    }

    private int[] paletteFor(FoliaTheme theme) {
        if (coverColors != null && coverColors.length > 0) return coverColors;
        return new int[]{theme.secondary, theme.primary, theme.accent, theme.background};
    }

    private void releaseCover() {
        if (coverShader != null) {
            coverShader.close();
            coverShader = null;
        }
        if (coverTexture != null) {
            coverTexture.close();
            coverTexture = null;
        }
        coverSource = null;
    }

    private void releaseStatic() {
        if (staticFrame != null) {
            staticFrame.close();
            staticFrame = null;
        }
        staticW = -1;
        staticH = -1;
    }

    private static void fill(Canvas canvas, float width, float height, int rgb, float alpha) {
        if (alpha <= .004f) return;
        try (Paint paint = new Paint()) {
            paint.setColor(alpha(rgb, alpha));
            canvas.drawRect(Rect.makeXYWH(0f, 0f, width, height), paint);
        }
    }

    static int alpha(int rgb, float a) {
        int value = Math.round(FoliaTheme.clamp(a, 0f, 1f) * 255f);
        return (value << 24) | (rgb & 0xFFFFFF);
    }
}






