package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.ncm.music.AudioSpectrumAnalyzer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPulse;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.render.skija.SkijaFluidBackground;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaImages;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

import java.util.Objects;

/**
 * Fullscreen lyric background host.
 *
 * <p>QPlayer's shaders are phase-driven, so audio must change the phase speed
 * continuously rather than being injected as a one-frame brightness flash.  The
 * host therefore integrates a smoothed motion speed and passes the accumulated
 * clock to the compositor.</p>
 */
final class QPlayerFluidLyricBackground {

    private static final int BACKGROUND_BLURRED_COVER = 1;
    private static final float BLURRED_FADE_SECONDS = .6f;

    private long lastNanos;
    private float smoothedEnergy;
    private float smoothedBeat;
    private float previousEnergy;
    private float onsetPulse;
    private float smoothedMotionDrive;
    private float motionSpeed = 1f;
    private float motionTimeSeconds;

    /** Borrowed Skia views for the sharp cover used by QPlayer fluid shaders. */
    private Image activeCoverImage;

    /** Borrowed Skia views for the legacy blurred-cover background style. */
    private Image activeBlurredImage;
    private Image previousBlurredImage;
    private String blurredTrackKey;
    private long blurredFadeStartNanos;

    private final SkijaFluidBackground fluidBackground =
            new SkijaFluidBackground(System.nanoTime());

    void dispose() {
        activeCoverImage = null;
        activeBlurredImage = null;
        previousBlurredImage = null;
        fluidBackground.dispose();
    }

    void reset() {
        lastNanos = 0L;
        smoothedEnergy = 0f;
        smoothedBeat = 0f;
        previousEnergy = 0f;
        onsetPulse = 0f;
        smoothedMotionDrive = 0f;
        motionSpeed = 1f;
        motionTimeSeconds = 0f;
        activeCoverImage = null;
        activeBlurredImage = null;
        previousBlurredImage = null;
        blurredTrackKey = null;
        blurredFadeStartNanos = 0L;
        fluidBackground.reset();
    }

    boolean render(double x, double y, double width, double height, float alpha,
                   Music current) {
        if (!SkijaGfx.active() || current == null) return false;

        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        if (dt < 0.0) dt = 0.0;
        if (dt > .05) dt = .05;

        // Keep the original full-screen blurred-cover option available.  It does
        // not consume the audio analyser and never changes the QPlayer motion clock.
        if (HudConfig.fullscreenLyricBackgroundMode == BACKGROUND_BLURRED_COVER) {
            return renderBlurredCover(x, y, width, height, alpha, current, now);
        }

        boolean audible = CloudMusic.isAudibleNow();
        float energy = 0f;
        float beat = 0f;
        if (audible && PlaybackPulse.isAvailable()) {
            energy = clamp01(PlaybackPulse.loudness());
            beat = clamp01(PlaybackPulse.beat());
        }
        // Split bands for the folia-port styles: the 48-band log-spaced FFT
        // (40Hz-16kHz) folded into a bass share (bands 0-7 ≈ 40-108Hz) and a mid
        // share (bands 14-29 ≈ 229Hz-1.7kHz). Zero when silent, so the styles
        // fall back to their calm idle drift.
        float audioLow = 0f;
        float audioMid = 0f;
        // 只有真正读分段频段的风格（folia 移植系）或层叠模式才调度 FFT——
        // QPlayer 原生算法只读 audio 包络，常驻一路 FFT 线程是白付的唤醒
        // （对照 Analyzer「Only a visible consumer schedules FFT」的口径）。
        boolean bandsNeeded = HudConfig.fullscreenLyricFluidStyle >= SkijaFluidBackground.STYLE_LATENT
                || HudConfig.fullscreenLyricBackgroundLayers != 0;
        if (audible && bandsNeeded) {
            AudioSpectrumAnalyzer.Frame frame = AudioSpectrumAnalyzer.requestFrame(now);
            if (frame.capturedAt > 0L && now - frame.capturedAt < 250_000_000L) {
                float lowSum = 0f;
                for (int band = 0; band < 8; band++) lowSum += frame.band(band);
                audioLow = clamp01(lowSum / 8f);
                float midSum = 0f;
                for (int band = 14; band < 30; band++) midSum += frame.band(band);
                audioMid = clamp01(midSum / 16f);
            }
        }
        // The user's audio-response dial scales every audio path with the same
        // factor, so "0 = ignore audio" holds for all styles (review R2 #1):
        // the split bands below, the shader brightness envelope (QPlayer's own
        // shaders read it too), and the motion-clock speed boost.
        float audioAmount = clamp01(HudConfig.fullscreenLyricFluidAudio / 100f);
        audioLow *= audioAmount;
        audioMid *= audioAmount;

        float energyApproach = approach(dt, .28);
        float beatApproach = approach(dt, .12);
        smoothedEnergy += (energy - smoothedEnergy) * energyApproach;
        smoothedBeat += (beat - smoothedBeat) * beatApproach;

        // Keep onset detection as a small impulse, then smooth it again before it
        // reaches the motion clock.  A raw beat pulse must never directly modulate
        // color, UV scale, or shader phase: that is the source of the old flashing.
        float rising = clamp01((energy - previousEnergy) * 6f);
        float onsetDecay = (float) Math.exp(-dt / .24);
        onsetPulse = Math.max(Math.max(onsetPulse * onsetDecay, smoothedBeat * .72f), rising * .55f);
        previousEnergy = energy;

        float targetDrive = clamp01((smoothedEnergy * .62f
                + smoothedBeat * .18f
                + onsetPulse * .14f) * audioAmount);
        float driveApproach = approach(dt, targetDrive > smoothedMotionDrive ? .14 : .42);
        smoothedMotionDrive += (targetDrive - smoothedMotionDrive) * driveApproach;

        // 1x is the normal QPlayer clock.  Audio only adds an inertial speed boost,
        // so the fluid visibly accelerates and eases back instead of blinking.
        // The dial scales the boost, never the 1x base, so 0% keeps the calm
        // idle drift of every style instead of freezing the picture.
        float targetSpeed = 1f + smoothedMotionDrive * .85f;
        float speedApproach = approach(dt, targetSpeed > motionSpeed ? .12 : .46);
        motionSpeed += (targetSpeed - motionSpeed) * speedApproach;
        motionTimeSeconds += (float) dt * motionSpeed;

        // The shader receives only the slow envelope.  It is intentionally kept
        // subtle because the actual beat response is carried by motionTimeSeconds.
        float shaderAudio = clamp01(smoothedMotionDrive * .78f + smoothedEnergy * .22f);

        Image cover = resolveCoverImage(current);
        // 「无」底层 + 星空层不需要封面：没有封面时也把层叠画面画出来。
        boolean layerStack = HudConfig.fullscreenLyricBackgroundLayers != 0
                || HudConfig.fullscreenLyricFluidStyle == SkijaFluidBackground.STYLE_NONE;
        if (cover == null && !layerStack) return false;

        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return false;
        int save = canvas.save();
        try {
            canvas.translate((float) x, (float) y);
            // 层叠组合：位掩码非零时走底层+叠加层路径；融合形态的底层由渲染层
            // 自动换成拆分层，避免同一效果画两遍。
            int layerBits = HudConfig.fullscreenLyricBackgroundLayers;
            fluidBackground.setLayerStack(layerBits,
                    HudConfig.fullscreenLyricLayerLatentDither / 100f,
                    HudConfig.fullscreenLyricLayerSora / 100f);
            // Sora's starfield is theme-tinted like folia's: particles carry the
            // accent colour with a warm-white fallback when the theme is grayscale.
            boolean soraInvolved = HudConfig.fullscreenLyricFluidStyle == SkijaFluidBackground.STYLE_SORA
                    || (layerBits & SkijaFluidBackground.LAYER_BIT_SORA) != 0;
            float[] tint = soraInvolved ? themeTint() : null;
            return fluidBackground.renderWithTime(canvas,
                    SkijaGfx.context(), (float) SkijaGfx.guiScale(),
                    (float) width, (float) height, cover,
                    current.getStableKey(), motionTimeSeconds, now,
                    HudConfig.fullscreenLyricFluidStatic,
                    HudConfig.fullscreenLyricFluidStyle,
                    Math.round(HudConfig.fullscreenLyricFluidSaturation),
                    alpha, shaderAudio, audioLow, audioMid, tint);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** Sora's particle colours: accent for the main tint, its hover for the sparkle set. */
    private static float[] themeTint() {
        int accent = NCMTheme.getAccentColor() & 0xFFFFFF;
        int hover = NCMTheme.getColor(NCMScreen.ColorType.ACCENT_HOVER) & 0xFFFFFF;
        return new float[] {
                ((accent >>> 16) & 0xFF) / 255f,
                ((accent >>> 8) & 0xFF) / 255f,
                (accent & 0xFF) / 255f,
                ((hover >>> 16) & 0xFF) / 255f,
                ((hover >>> 8) & 0xFF) / 255f,
                (hover & 0xFF) / 255f,
        };
    }

    private boolean renderBlurredCover(double x, double y, double width, double height,
                                       float alpha, Music current, long nowNanos) {
        Image resolved = resolveBlurredCoverImage(current);
        String trackKey = current.getStableKey();
        if (resolved != null) {
            if (activeBlurredImage == null) {
                activeBlurredImage = resolved;
                blurredTrackKey = trackKey;
                blurredFadeStartNanos = nowNanos;
            } else if (!Objects.equals(trackKey, blurredTrackKey)
                    || resolved != activeBlurredImage) {
                previousBlurredImage = activeBlurredImage;
                activeBlurredImage = resolved;
                blurredTrackKey = trackKey;
                blurredFadeStartNanos = nowNanos;
            }
        }

        Image active = activeBlurredImage;
        if (active == null) return false;

        float progress = blurredFadeProgress(nowNanos);
        Canvas canvas = SkijaGfx.canvas();
        if (canvas == null) return false;
        int save = canvas.save();
        try {
            canvas.translate((float) x, (float) y);
            float bgSize = Math.max((float) width, (float) height);
            Rect destination = Rect.makeXYWH(
                    ((float) width - bgSize) * .5f,
                    ((float) height - bgSize) * .5f,
                    bgSize, bgSize);

            boolean fading = previousBlurredImage != null && progress < 1f;
            if (fading) {
                drawBlurredImage(canvas, previousBlurredImage, destination,
                        alpha * (1f - progress));
            }
            drawBlurredImage(canvas, active, destination, fading ? alpha * progress : alpha);

            try (Paint shade = new Paint().setColor(0xFF000000)) {
                shade.setAlphaf(clamp01(alpha * .35f));
                canvas.drawRect(Rect.makeXYWH(0, 0, (float) width, (float) height), shade);
            }
            return true;
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private float blurredFadeProgress(long nowNanos) {
        if (previousBlurredImage == null) return 1f;
        float progress = (nowNanos - blurredFadeStartNanos)
                / 1_000_000_000f / BLURRED_FADE_SECONDS;
        if (progress >= 1f) {
            previousBlurredImage = null;
            return 1f;
        }
        if (progress <= 0f) return 0f;
        return .5f - .5f * (float) Math.cos(Math.PI * progress);
    }

    private static void drawBlurredImage(Canvas canvas, Image image, Rect destination,
                                         float alpha) {
        if (image == null || alpha <= .001f) return;
        try (Paint paint = new Paint()) {
            paint.setAlphaf(clamp01(alpha));
            canvas.drawImageRect(image,
                    Rect.makeWH(image.getWidth(), image.getHeight()), destination,
                    SamplingMode.LINEAR, paint, true);
        }
    }

    private Image resolveCoverImage(Music current) {
        Location location = CloudMusic.preferredCoverLocation(current, current.getCoverLocation());
        if (location == null) return activeCoverImage;
        ITextureObject texture = TextureManager.getInstance().getTexture(location);
        if (texture == null) return activeCoverImage;
        if (SkijaImages.sizeOf(texture.getGlTextureId()) == null) return activeCoverImage;
        Image resolved = SkijaImages.wrapForShader(texture.getGlTextureId());
        if (resolved != null) activeCoverImage = resolved;
        return activeCoverImage;
    }

    private Image resolveBlurredCoverImage(Music current) {
        Location location = CloudMusic.preferredBlurredCoverLocation(current);
        if (location == null) return activeBlurredImage;
        ITextureObject texture = TextureManager.getInstance().getTexture(location);
        if (texture == null) return activeBlurredImage;
        if (SkijaImages.sizeOf(texture.getGlTextureId()) == null) return activeBlurredImage;
        Image resolved = SkijaImages.wrapForShader(texture.getGlTextureId());
        return resolved == null ? activeBlurredImage : resolved;
    }

    private static float approach(double dt, double tau) {
        if (dt <= 0.0) return 0f;
        return (float) (1.0 - Math.exp(-dt / tau));
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return value < 0f ? 0f : value > 1f ? 1f : value;
    }
}

