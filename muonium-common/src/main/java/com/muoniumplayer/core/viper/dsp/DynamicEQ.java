// 逐行移植自 ViPERDSP/viper/effects/DynamicEQ（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class DynamicEQ {
    public static final int K_MAX_BANDS = 10;

    private static final float K_GAIN_CHANGE_THRESHOLD = 0.1f;
    private static final float K_OVERSHOOT_RANGE_DB = 12.0f;
    private static final float K_MIN_ENVELOPE = 1e-20f;

    private static final class BandParam {
        float frequency;
        float q;
        float targetGainDb;
        float thresholdDb;
        float attackMs;
        float releaseMs;
        MultiBiquad.FilterType filterType;
    }

    private static final class BandState {
        double envelopeL;
        double envelopeR;
        double smoothedGainDb;
        float lastAppliedGainDb;
        double attackCoeff;
        double releaseCoeff;
    }

    private boolean enable_;

    private int samplingRate_;
    private int bandCount_;

    private final BandParam[] params_ = new BandParam[K_MAX_BANDS];
    private final BandState[] state_ = new BandState[K_MAX_BANDS];

    private final MultiBiquad[] applyL_ = new MultiBiquad[K_MAX_BANDS];
    private final MultiBiquad[] applyR_ = new MultiBiquad[K_MAX_BANDS];

    public DynamicEQ() {
        enable_ = false;
        samplingRate_ = 44100;
        bandCount_ = 0;

        for (int i = 0; i < K_MAX_BANDS; i++) {
            params_[i] = new BandParam();
            params_[i].frequency = 1000.0f;
            params_[i].q = 1.0f;
            params_[i].targetGainDb = 0.0f;
            params_[i].thresholdDb = -24.0f;
            params_[i].attackMs = 10.0f;
            params_[i].releaseMs = 100.0f;
            params_[i].filterType = MultiBiquad.FilterType.PEAK;

            state_[i] = new BandState();
            state_[i].envelopeL = 0.0;
            state_[i].envelopeR = 0.0;
            state_[i].smoothedGainDb = 0.0;
            state_[i].lastAppliedGainDb = 0.0f;
            state_[i].attackCoeff = 0.0;
            state_[i].releaseCoeff = 0.0;

            applyL_[i] = new MultiBiquad();
            applyR_[i] = new MultiBiquad();
        }

        reset();
    }

    public void process(final float[] samples, final int frames) {
        if (!enable_) return;
        if (bandCount_ == 0) return;
        if (frames == 0) return;

        final int frameCount = frames * 2;

        for (int b = 0; b < bandCount_; b++) {
            double attackCoeff = state_[b].attackCoeff;
            double releaseCoeff = state_[b].releaseCoeff;
            double envL = state_[b].envelopeL;
            double envR = state_[b].envelopeR;
            double smoothedGain = state_[b].smoothedGainDb;
            float lastApplied = state_[b].lastAppliedGainDb;
            final float targetGain = params_[b].targetGainDb;
            final float threshold = params_[b].thresholdDb;

            for (int i = 0; i < frameCount; i += 2) {
                final double sampleL = samples[i];
                final double sampleR = samples[i + 1];

                final double powerL = sampleL * sampleL;
                final double powerR = sampleR * sampleR;

                final double smoothCoeffL = powerL > envL ? attackCoeff : releaseCoeff;
                envL += smoothCoeffL * (powerL - envL);

                final double smoothCoeffR = powerR > envR ? attackCoeff : releaseCoeff;
                envR += smoothCoeffR * (powerR - envR);

                double rmsLinear = Math.sqrt(Math.max(envL, envR));
                if (rmsLinear < K_MIN_ENVELOPE) rmsLinear = K_MIN_ENVELOPE;

                final double envelopeDb = 20.0 * Math.log10(rmsLinear);

                final double overshoot = envelopeDb - (double) threshold;
                double desiredGainDb = 0.0;
                if (overshoot > 0.0) {
                    double ratio = overshoot / (double) K_OVERSHOOT_RANGE_DB;
                    if (ratio > 1.0) ratio = 1.0;
                    desiredGainDb = (double) targetGain * ratio;
                }

                final double gainCoeff = Math.abs(desiredGainDb) > Math.abs(smoothedGain)
                        ? attackCoeff
                        : releaseCoeff;
                smoothedGain += gainCoeff * (desiredGainDb - smoothedGain);

                final float currentGainDb = (float) smoothedGain;
                if (Math.abs(currentGainDb - lastApplied) > K_GAIN_CHANGE_THRESHOLD) {
                    configureApplicationFilter(b, currentGainDb);
                    lastApplied = currentGainDb;
                }

                samples[i] = (float) applyL_[b].processSample((float) sampleL);
                samples[i + 1] = (float) applyR_[b].processSample((float) sampleR);
            }

            state_[b].envelopeL = envL;
            state_[b].envelopeR = envR;
            state_[b].smoothedGainDb = smoothedGain;
            state_[b].lastAppliedGainDb = lastApplied;
        }
    }

    public void reset() {
        for (int i = 0; i < K_MAX_BANDS; i++) {
            state_[i].envelopeL = 0.0;
            state_[i].envelopeR = 0.0;
            state_[i].smoothedGainDb = 0.0;
            state_[i].lastAppliedGainDb = 0.0f;

            applyL_[i].reset();
            applyR_[i].reset();

            recalcAttackRelease(i);
            configureApplicationFilter(i, 0.0f);
        }
    }

    public void setEnable(final boolean enable) {
        if (enable_ != enable) {
            if (enable) reset();
            enable_ = enable;
        }
    }

    public void setBandCount(int count) {
        if (count > K_MAX_BANDS) count = K_MAX_BANDS;
        if (bandCount_ != count) {
            bandCount_ = count;
            reset();
        }
    }

    public void setSampleRate(final int samplingRate) {
        if (samplingRate_ != samplingRate) {
            samplingRate_ = samplingRate;
            reset();
        }
    }

    public void setBandFrequency(final int band, final float value) {
        if (band >= K_MAX_BANDS) return;
        params_[band].frequency = value;
        configureApplicationFilter(band, 0.0f);
        state_[band].lastAppliedGainDb = 0.0f;
    }

    public void setBandQ(final int band, final float value) {
        if (band >= K_MAX_BANDS) return;
        params_[band].q = value;
        configureApplicationFilter(band, 0.0f);
        state_[band].lastAppliedGainDb = 0.0f;
    }

    public void setBandGain(final int band, final float value) {
        if (band >= K_MAX_BANDS) return;
        params_[band].targetGainDb = value;
    }

    public void setBandThreshold(final int band, final float value) {
        if (band >= K_MAX_BANDS) return;
        params_[band].thresholdDb = value;
    }

    public void setBandAttack(final int band, final float value) {
        if (band >= K_MAX_BANDS) return;
        params_[band].attackMs = value;
        recalcAttackRelease(band);
    }

    public void setBandRelease(final int band, final float value) {
        if (band >= K_MAX_BANDS) return;
        params_[band].releaseMs = value;
        recalcAttackRelease(band);
    }

    public void setBandFilterType(final int band, final int value) {
        if (band >= K_MAX_BANDS) return;
        // MultiBiquad.FilterType ordinal mapping: PEAK=5, LOW_SHELF=6, HIGH_SHELF=7
        MultiBiquad.FilterType resolved;
        switch (value) {
            case 0:
            case 5:
                resolved = MultiBiquad.FilterType.PEAK;
                break;
            case 1:
            case 6:
                resolved = MultiBiquad.FilterType.LOW_SHELF;
                break;
            case 2:
            case 7:
                resolved = MultiBiquad.FilterType.HIGH_SHELF;
                break;
            default:
                resolved = MultiBiquad.FilterType.PEAK;
                break;
        }
        params_[band].filterType = resolved;
        configureApplicationFilter(band, 0.0f);
        state_[band].lastAppliedGainDb = 0.0f;
    }

    private void recalcAttackRelease(final int band) {
        final double attackSec = (double) params_[band].attackMs / 1000.0;
        final double releaseSec = (double) params_[band].releaseMs / 1000.0;
        final double sr = (double) samplingRate_;

        if (attackSec > 0.0) {
            state_[band].attackCoeff = 1.0 - Math.exp(-1.0 / (attackSec * sr));
        } else {
            state_[band].attackCoeff = 1.0;
        }

        if (releaseSec > 0.0) {
            state_[band].releaseCoeff = 1.0 - Math.exp(-1.0 / (releaseSec * sr));
        } else {
            state_[band].releaseCoeff = 1.0;
        }
    }

    private void configureApplicationFilter(final int band, final float gainDb) {
        applyL_[band].refreshFilter(
                params_[band].filterType,
                gainDb,
                params_[band].frequency,
                samplingRate_,
                params_[band].q,
                false
        );
        applyR_[band].refreshFilter(
                params_[band].filterType,
                gainDb,
                params_[band].frequency,
                samplingRate_,
                params_[band].q,
                false
        );
    }
}
