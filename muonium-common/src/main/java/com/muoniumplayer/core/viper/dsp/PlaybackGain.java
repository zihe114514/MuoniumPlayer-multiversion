// 逐行移植自 ViPERDSP/viper/effects/PlaybackGain（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

// 逐行移植自 ViPERDSP/viper/effects/PlaybackGain.h/.cpp
public final class PlaybackGain {
    private static final int DEFAULT_SAMPLING_RATE = 44100;
    private static final float K_WARMUP_SECONDS = 0.4f;

    private boolean enable_;

    private int samplingRate_;
    private int rampProgress_;
    private int rampFrames_;

    private float logCoeff_;
    private float ratio1_;
    private float ratio2_;
    private float volume_;
    private float maxGainFactor_;
    private float currentGainL_;
    private float currentGainR_;

    private final Biquad biquad1_ = new Biquad();
    private final Biquad biquad2_ = new Biquad();

    public PlaybackGain() {
        enable_ = false;
        samplingRate_ = DEFAULT_SAMPLING_RATE;
        rampProgress_ = 0;
        rampFrames_ = (int) (DEFAULT_SAMPLING_RATE * K_WARMUP_SECONDS);
        logCoeff_ = 0.4342945f;
        ratio1_ = 2.0f;
        ratio2_ = 0.5f;
        volume_ = 1.0f;
        maxGainFactor_ = 1.0f;
        currentGainL_ = 1.0f;
        currentGainR_ = 1.0f;

        biquad1_.setBandPassParameter(2200.0f, samplingRate_, 0.33f);
        biquad2_.setBandPassParameter(2200.0f, samplingRate_, 0.33f);
    }

    public void process(float[] samples, int frames) {
        if (!enable_) return;
        if (frames == 0) return;

        double analyzed = analyseWave(samples, frames);

        if (analyzed < 1e-10) {
            analyzed = 1e-10;
        }
        final double a = Math.log(analyzed);

        if (rampProgress_ < rampFrames_) {
            rampProgress_ += frames;
            if (rampProgress_ > rampFrames_) {
                rampProgress_ = rampFrames_;
            }
        }
        final double ramp = rampFrames_ == 0 ? 1.0
                : (double) rampProgress_ / (double) rampFrames_;

        final double b = a * logCoeff_ * 10.0 + 23.0;
        final double c = ramp * (b * ratio2_ - b);
        final double d = c / 100.0;
        final double e = Math.pow(10.0, (c - d * d * 50.0) / 20.0);

        final int f;
        if (frames < samplingRate_ / 40) {
            f = samplingRate_ / 40;
        } else {
            f = frames;
        }

        final double target = e * volume_;

        for (int ch = 0; ch < 2; ch++) {
            float gain = (ch == 0) ? currentGainL_ : currentGainR_;
            double g = (target - gain) / f;
            if (g >= 0.0) {
                g *= 0.0625;
            }
            for (int i = 0; i < frames; i++) {
                samples[i * 2 + ch] *= gain;
                gain += (float) g;
                if (gain > maxGainFactor_) {
                    gain = maxGainFactor_;
                } else if (gain < -maxGainFactor_) {
                    gain = -maxGainFactor_;
                }
            }
            if (ch == 0) {
                currentGainL_ = gain;
            } else {
                currentGainR_ = gain;
            }
        }
    }

    public void reset() {
        biquad1_.setBandPassParameter(2200.0f, samplingRate_, 0.33f);
        biquad2_.setBandPassParameter(2200.0f, samplingRate_, 0.33f);
        currentGainL_ = 1.0f;
        rampProgress_ = 0;
        currentGainR_ = 1.0f;
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (enable) {
                reset();
            }
            enable_ = enable;
        }
    }

    public void setMaxGainFactor(float maxGainFactor) {
        maxGainFactor_ = maxGainFactor;
    }

    public void setRatio(float ratio) {
        ratio1_ = ratio + 1.0f;
        ratio2_ = 1.0f / ratio1_;
    }

    public void setVolume(float volume) {
        volume_ = volume;
    }

    public void setSampleRate(int samplingRate) {
        if (samplingRate_ != samplingRate) {
            samplingRate_ = samplingRate;
            rampFrames_ = (int) (samplingRate * K_WARMUP_SECONDS);
            reset();
        }
    }

    private double analyseWave(final float[] samples, final int frames) {
        double tmpL = 0.0;
        double tmpR = 0.0;

        for (int i = 0; i < frames * 2; i += 2) {
            final double tmpL2 = biquad1_.processSample(samples[i]);
            tmpL += tmpL2 * tmpL2;

            final double tmpR2 = biquad2_.processSample(samples[i + 1]);
            tmpR += tmpR2 * tmpR2;
        }

        final double tmp = Math.max(tmpL, tmpR);

        return tmp / (double) frames;
    }
}
