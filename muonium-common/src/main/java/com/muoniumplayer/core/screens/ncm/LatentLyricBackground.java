package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.ncm.music.PlaybackPulse;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaLatentEffects;

/**
 * 「隐现」全屏歌词背景的播放状态层。
 *
 * <p>shader 只负责每帧绘制；这里负责把实时音频包络、顺序播放时间和封面色板平滑后送进去。
 * 参数、默认值和音频速度映射对齐 本项目 的 {@code DEFAULT_LATENT_BACKGROUND_TUNING}：
 * 像素层与流体层各自有基础速度、音频最大速度和独立的透明度/扭曲参数。</p>
 */
final class LatentLyricBackground {

    private long lastNanos;
    private float meshTime;
    private float ditherTime;
    private float smoothedEnergy;
    private float smoothedDrive;
    private float previousEnergy;
    private float onsetPulse;
    private float smoothedBeatSpeed;

    private final int[] meshColors = new int[6];
    private int ditherBack;
    private int ditherFront;

    boolean render(double x, double y, double width, double height, float alpha,
                   int[] palette) {
        if (!SkijaGfx.active()) return false;

        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        if (dt < 0.0) dt = 0.0;
        if (dt > .05) dt = .05;

        boolean paused = com.muoniumplayer.core.ncm.music.CloudMusic.player == null
                || com.muoniumplayer.core.ncm.music.CloudMusic.player.isPausing();
        float energy = 0f;
        float beat = 0f;
        if (!paused && PlaybackPulse.isAvailable()) {
            energy = clamp01(PlaybackPulse.loudness());
            beat = clamp01(PlaybackPulse.beat());
        }

        float powerApproach = (float) (1.0 - Math.exp(-dt / .28));
        float driveApproach = (float) (1.0 - Math.exp(-dt / .16));
        smoothedEnergy += (energy - smoothedEnergy) * powerApproach;
        smoothedDrive += (Math.max(energy, beat) - smoothedDrive) * driveApproach;

        float beatTarget;
        if (HudConfig.latentEnhancedBeatResponse) {
            onsetPulse = Math.max(onsetPulse * .84f, clamp01((energy - previousEnergy) * 7f));
            beatTarget = clamp01(energy * .42f + onsetPulse * .85f);
        } else {
            onsetPulse = 0f;
            beatTarget = energy;
        }
        previousEnergy = energy;
        float beatApproach = (float) (1.0 - Math.exp(
                -dt / (beatTarget > smoothedBeatSpeed ? .06 : .20)));
        smoothedBeatSpeed += (beatTarget - smoothedBeatSpeed) * beatApproach;

        float meshSpeed = resolveSpeed(HudConfig.latentMeshSpeed,
                HudConfig.latentMeshAudioSpeed, smoothedBeatSpeed, paused);
        float ditherSpeed = resolveSpeed(HudConfig.latentDitheringSpeed,
                HudConfig.latentDitheringAudioSpeed, smoothedBeatSpeed, paused);
        meshTime += (float) (dt * meshSpeed);
        ditherTime += (float) (dt * ditherSpeed);

        float distortion = HudConfig.latentMeshDistortion + smoothedEnergy * .62f;
        float swirl = HudConfig.latentMeshSwirl + smoothedDrive * .38f;
        float ditherSize = Math.max(.5f,
                HudConfig.latentDitheringSize
                        - smoothedEnergy * HudConfig.latentDitheringSize * .34f);

        boolean showDithering = HudConfig.latentBackgroundDisplayMode != 1;
        boolean showMesh = HudConfig.latentBackgroundDisplayMode != 0;
        resolveColors(palette);

        float ditherOpacity = showMesh
                ? Math.min(1f, HudConfig.latentDitheringOpacity + smoothedEnergy * .25f)
                : 1f;

        return SkijaLatentEffects.draw(x, y, width, height, meshTime, ditherTime,
                distortion, swirl, ditherSize, ditherOpacity, alpha,
                ditherBack, ditherFront, meshColors, showDithering, showMesh);
    }

    private void resolveColors(int[] palette) {
        int themeBackground = NCMScreen.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND) & 0xFFFFFF;
        int themeAccent = NCMScreen.getColor(NCMScreen.ColorType.ACCENT) & 0xFFFFFF;
        int themeText = NCMScreen.getColor(NCMScreen.ColorType.PRIMARY_TEXT) & 0xFFFFFF;

        int primary = colorAt(palette, 0, themeAccent);
        int secondary = colorAt(palette, 1, themeText);
        int tertiary = colorAt(palette, 2, primary);
        int quaternary = colorAt(palette, 3, secondary);
        int quinary = colorAt(palette, 4, secondary);
        int senary = colorAt(palette, 5, tertiary);

        if (HudConfig.latentBackgroundColorSource == 1) {
            meshColors[0] = primary;
            meshColors[1] = secondary;
            meshColors[2] = tertiary;
            meshColors[3] = quaternary;
            meshColors[4] = quinary;
            meshColors[5] = senary;
            ditherBack = tertiary;
            ditherFront = primary;
            return;
        }

        // 原版「封面与主题」不是把 6 个封面色原样铺满：最深/最亮的主题底色和强调色各占一席，
        // 这样暗色封面不会整片压成黑块。
        meshColors[0] = primary;
        meshColors[1] = secondary;
        meshColors[2] = tertiary;
        meshColors[3] = quaternary;
        meshColors[4] = themeBackground;
        meshColors[5] = themeAccent;
        ditherBack = themeBackground;
        ditherFront = primary;
    }

    private static float resolveSpeed(float baseSpeed, float audioSpeed,
                                      float audioAmount, boolean paused) {
        float pausedScale = HudConfig.latentDynamicOnlyInPlayer ? .12f : .55f;
        float speed = paused
                ? baseSpeed * pausedScale
                : baseSpeed + (audioSpeed - baseSpeed) * clamp01(audioAmount);
        return Math.max(0f, Math.min(2f, speed));
    }

    private static int colorAt(int[] colors, int index, int fallback) {
        return colors != null && index < colors.length ? colors[index] & 0xFFFFFF : fallback;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return value < 0f ? 0f : value > 1f ? 1f : value;
    }
}
