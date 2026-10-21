package com.muoniumplayer.core.screens.ncm.foliabg;

import com.muoniumplayer.core.ncm.music.AudioSpectrumAnalyzer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.PlaybackPulse;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.screens.ncm.NCMScreen;

/**
 * 背景共用的一帧输入：音频包络、主题、封面调色板、时间。
 *
 * <p>folia 的每个背景读同一组 {@code audioPower} / {@code audioBands}（bass、lowMid、mid、vocal、
 * treble）。播放器这边只有两路真值：{@link PlaybackPulse} 的宽带包络/节拍，以及
 * {@link AudioSpectrumAnalyzer} 的 48 段 FFT。这里把它们折算成 folia 那五个频段，并做与
 * {@code LatentBackground} 相同的一阶平滑（0.12 / 0.16 / 0.13），所以各背景拿到的曲线形状与
 * 原版一致，只是来源换成了真实频谱。</p>
 *
 * <p>只有「当前背景确实读频段」时才请求 FFT：{@link AudioSpectrumAnalyzer#requestFrame} 会调度
 * 一次 48 段分析，常驻一路是白付的 CPU。</p>
 */
final class FoliaAudioFrame {

    /** folia 的五个频段，0..255（与原版 {@code audioBands} 同量纲）。 */
    float bass;
    float lowMid;
    float mid;
    float vocal;
    float treble;
    /** 宽带能量 0..1（原版 audioPower / 255）。 */
    float power;
    /** 节拍包络 0..1。 */
    float beat;
    /** 本帧距上一帧的秒数（已夹到 50ms）。 */
    float deltaSeconds;
    /** folia 的宽频能量（五个频段加权后开 0.55 次方），0..1。 */
    float energy;
    /** 宽频能量上升沿的脉冲，0..1。 */
    float onset;
    /** 速度混合系数：0 = 只用基速，1 = 到音频速度上限。 */
    float speedFactor;

    private long lastNanos;
    private float smoothedBass;
    private float smoothedLowMid;
    private float smoothedMid;
    private float smoothedVocal;
    private float smoothedTreble;
    private float previousEnergy;
    private float onsetPulse;
    private float smoothedSpeedFactor;

    /** 是否需要分段频段（Latent / Nomand 读它们；Sora 只用宽带）。 */
    void update(boolean wantsBands) {
        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        if (dt < 0.0) dt = 0.0;
        if (dt > .05) dt = .05;
        deltaSeconds = (float) dt;

        boolean audible = CloudMusic.isAudibleNow();
        float targetPower = 0f;
        float targetBeat = 0f;
        if (audible) {
            if (PlaybackPulse.isAvailable()) {
                targetPower = clamp01(PlaybackPulse.loudness());
                targetBeat = clamp01(PlaybackPulse.beat());
            }
        }
        power = targetPower;
        beat = targetBeat;

        float b = 0f, lm = 0f, m = 0f, v = 0f, t = 0f;
        if (audible && wantsBands) {
            AudioSpectrumAnalyzer.Frame frame = AudioSpectrumAnalyzer.requestFrame(now);
            if (frame != null && frame.capturedAt > 0L && now - frame.capturedAt < 250_000_000L) {
                // 48 段对数频段（40Hz-16kHz）：按 folia 的频段划分折成五路。
                b = average(frame, 0, 7);       // ≈ 40-108 Hz
                lm = average(frame, 8, 13);     // ≈ 108-229 Hz
                m = average(frame, 14, 23);     // ≈ 229-1.0 kHz
                v = average(frame, 24, 33);     // ≈ 1.0-4.0 kHz
                t = average(frame, 34, 47);     // ≈ 4.0-16 kHz
            }
        }

        float audioAmount = FoliaBackgroundConfig.clamp(FoliaBackgroundConfig.audioResponse, 0f, 2f);
        smoothedBass = ease(smoothedBass, b * audioAmount, .16f);
        smoothedLowMid = ease(smoothedLowMid, lm * audioAmount, .16f);
        smoothedMid = ease(smoothedMid, m * audioAmount, .13f);
        smoothedVocal = ease(smoothedVocal, v * audioAmount, .13f);
        smoothedTreble = ease(smoothedTreble, t * audioAmount, .16f);
        power = clamp01(power * audioAmount);
        beat = clamp01(beat * audioAmount);

        // 宽频能量：folia resolveLatentBroadbandEnergy 的同一公式。
        float bandEnergy = (float) Math.pow(
                clamp01(smoothedBass) * 0.22f
                        + clamp01(smoothedLowMid) * 0.18f
                        + clamp01(smoothedMid) * 0.22f
                        + clamp01(smoothedVocal) * 0.28f
                        + clamp01(smoothedTreble) * 0.10f, 0.55);
        energy = bandEnergy;
        onsetPulse = Math.max(onsetPulse * 0.84f,
                clamp01((bandEnergy - previousEnergy) * 7f));
        previousEnergy = bandEnergy;
        float target = FoliaBackgroundConfig.latentEnhancedBeatResponse
                ? clamp01(bandEnergy * 0.42f + onsetPulse * 0.85f)
                : clamp01(bandEnergy);
        smoothedSpeedFactor += (target - smoothedSpeedFactor)
                * (target > smoothedSpeedFactor ? 0.42f : 0.14f);
        speedFactor = clamp01(smoothedSpeedFactor);
        onset = onsetPulse;

        bass = clamp01(smoothedBass) * 255f;
        lowMid = clamp01(smoothedLowMid) * 255f;
        mid = clamp01(smoothedMid) * 255f;
        vocal = clamp01(smoothedVocal) * 255f;
        treble = clamp01(smoothedTreble) * 255f;
    }

    void reset() {
        lastNanos = 0L;
        smoothedBass = 0f;
        smoothedLowMid = 0f;
        smoothedMid = 0f;
        smoothedVocal = 0f;
        smoothedTreble = 0f;
        bass = lowMid = mid = vocal = treble = 0f;
        power = 0f;
        beat = 0f;
        deltaSeconds = 0f;
        energy = 0f;
        onset = 0f;
        speedFactor = 0f;
        previousEnergy = 0f;
        onsetPulse = 0f;
        smoothedSpeedFactor = 0f;
    }

    /** 当前主题的四个颜色（folia {@code Theme}）。 */
    static FoliaTheme theme() {
        return FoliaTheme.of(
                NCMTheme.getColor(NCMScreen.ColorType.GENERIC_BACKGROUND),
                NCMTheme.getColor(NCMScreen.ColorType.PRIMARY_TEXT),
                NCMTheme.getColor(NCMScreen.ColorType.SECONDARY_TEXT),
                NCMTheme.getColor(NCMScreen.ColorType.ACCENT),
                NCMTheme.isLightPreset());
    }

    private static float average(AudioSpectrumAnalyzer.Frame frame, int from, int to) {
        float sum = 0f;
        int count = 0;
        for (int i = from; i <= to; i++) {
            sum += frame.band(i);
            count++;
        }
        return count == 0 ? 0f : clamp01(sum / count);
    }

    private static float ease(float current, float target, float amount) {
        return current + (target - current) * amount;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}

