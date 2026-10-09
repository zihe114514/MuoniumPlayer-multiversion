// 逐行移植自 ViPERDSP/viper/effects/FETCompressor（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

// 逐行移植自 ViPERDSP/viper/effects/FETCompressor.h/.cpp
public final class FETCompressor {
    private static final int DEFAULT_SAMPLING_RATE = 44100;
    private static final float K_NATURAL_LOG_TO_DB = 8.685889638F;

    private boolean enable_;
    private boolean autoKnee_;
    private boolean autoGain_;
    private boolean autoAttack_;
    private boolean autoRelease_;
    private boolean noClip_;

    private int samplingRate_;

    private float attackRaw_;
    private float releaseRaw_;
    private float crestRaw_;
    private float adaptRaw_;

    private float smoothingCoeff_;
    private float releaseSmoothGr_;
    private float attackSmoothGr_;
    private float adaptiveGainState_;
    private float smoothedThreshold_;
    private float threshold_;
    private float knee_;
    private float smoothedGain_;
    private float gain_;
    private float ratio_;
    private float runningPeak_;
    private float runningRms_;
    private float attack1_;
    private float attack2_;
    private float release1_;
    private float release2_;
    private float kneeMulti_;
    private float maxAttack_;
    private float maxRelease_;
    private float crest1_;
    private float crest2_;
    private float adapt1_;
    private float adapt2_;

    public FETCompressor() {
        enable_ = true;
        autoKnee_ = true;
        autoGain_ = true;
        autoAttack_ = true;
        autoRelease_ = true;
        noClip_ = true;
        samplingRate_ = DEFAULT_SAMPLING_RATE;
        attackRaw_ = 0.514679f;
        releaseRaw_ = 0.384311f;
        crestRaw_ = 0.615689f;
        adaptRaw_ = 0.660964f;

        setThreshold(0.0f);
        setRatio(0.0f);
        setKnee(0.0f);
        setGain(0.0f);
        setAttack(attackRaw_);
        setRelease(releaseRaw_);
        setKneeMulti(0.5f);
        setMaxAttack(0.879450f);
        setMaxRelease(0.884311f);
        setCrest(crestRaw_);
        setAdapt(adaptRaw_);
        reset();
    }

    public void process(float[] samples, int frames) {
        if (!enable_ || frames == 0) return;

        for (int i = 0; i < frames * 2; i += 2) {
            final double inL = Math.abs(samples[i]);
            final double inR = Math.abs(samples[i + 1]);
            final double in = Math.max(inL, inR);

            final double out = processSidechain(in);
            samples[i] *= (float) out;
            samples[i + 1] *= (float) out;

            smoothedThreshold_ = smoothedThreshold_ + (threshold_ - smoothedThreshold_) * smoothingCoeff_;
            smoothedGain_ = smoothedGain_ + smoothingCoeff_ * (gain_ - smoothedGain_);
        }
    }

    public void reset() {
        smoothingCoeff_ = calculateExpSomething(samplingRate_, 0.05f);
        smoothedThreshold_ = threshold_;
        smoothedGain_ = gain_;
        runningPeak_ = 0.000001f;
        runningRms_ = 0.000001f;
        releaseSmoothGr_ = 0.0f;
        attackSmoothGr_ = 0.0f;
        adaptiveGainState_ = 0.0f;
    }

    public void setEnable(boolean enable) {
        enable_ = enable;
    }

    public void setThreshold(float value) {
        threshold_ = (float) Math.log(Math.pow(10.0f, value * -60.0f / 20.0f));
    }

    public void setRatio(float value) {
        ratio_ = -value;
    }

    public void setKnee(float value) {
        knee_ = (float) Math.log(Math.pow(10.0f, value * 60.0f / 20.0f));
    }

    public void setKneeAuto(boolean enable) {
        autoKnee_ = enable;
    }

    public void setGain(float value) {
        gain_ = (float) Math.log(Math.pow(10.0f, value * 60.0f / 20.0f));
    }

    public void setGainAuto(boolean enable) {
        autoGain_ = enable;
    }

    public void setAttack(float value) {
        attackRaw_ = value;
        attack1_ = (float) Math.exp(value * 7.600903f - 9.21034f);
        attack2_ = calculateTimeCoeff(samplingRate_, value, 7.600903f, -9.21034f);
    }

    public void setAttackAuto(boolean enable) {
        autoAttack_ = enable;
    }

    public void setRelease(float value) {
        releaseRaw_ = value;
        release1_ = (float) Math.exp(value * 5.991465f - 5.298317f);
        release2_ = calculateTimeCoeff(samplingRate_, value, 5.991465f, -5.298317f);
    }

    public void setReleaseAuto(boolean enable) {
        autoRelease_ = enable;
    }

    public void setKneeMulti(float value) {
        kneeMulti_ = value * 4.0f;
    }

    public void setMaxAttack(float value) {
        maxAttack_ = (float) Math.exp(value * 7.600903f - 9.21034f);
    }

    public void setMaxRelease(float value) {
        maxRelease_ = (float) Math.exp(value * 5.991465f - 5.298317f);
    }

    public void setCrest(float value) {
        crestRaw_ = value;
        crest1_ = (float) Math.exp(value * 5.991465f - 5.298317f);
        crest2_ = calculateTimeCoeff(samplingRate_, value, 5.991465f, -5.298317f);
    }

    public void setAdapt(float value) {
        adaptRaw_ = value;
        adapt1_ = (float) Math.exp(value * 1.386294f);
        adapt2_ = calculateTimeCoeff(samplingRate_, value, 1.386294f, 0.0f);
    }

    public void setNoClip(boolean enable) {
        noClip_ = enable;
    }

    public void setSampleRate(int samplingRate) {
        samplingRate_ = samplingRate;
        setAttack(attackRaw_);
        setRelease(releaseRaw_);
        setCrest(crestRaw_);
        setAdapt(adaptRaw_);
        reset();
    }

    public float getGainReductionDb() {
        return enable_ ? (float) Math.max(0.0, attackSmoothGr_ * K_NATURAL_LOG_TO_DB) : 0.0F;
    }

    private double processSidechain(final double in) {
        float in2 = (float) (in * in);
        if (in2 < 0.000001f) {
            in2 = 0.000001f;
        }

        float attackCoeff = attack2_;
        float releaseCoeff = release2_;
        float adaptiveAttackTime = attack1_;

        final float runningPeak = runningPeak_ + crest2_ * (in2 - runningPeak_);
        final float runningRms = runningRms_ + crest2_ * (in2 - runningRms_);

        if (in2 < runningPeak) {
            in2 = runningPeak;
        }

        runningRms_ = runningRms;
        runningPeak_ = in2;

        final float crestRatio = in2 / runningRms;

        if (autoAttack_) {
            adaptiveAttackTime = 2.0f * maxAttack_ / crestRatio;
            if (adaptiveAttackTime <= 0.0f) {
                attackCoeff = 1.0f;
            } else {
                attackCoeff = calculateExpSomething(samplingRate_, adaptiveAttackTime);
            }
        }

        if (autoRelease_) {
            final float adaptiveReleaseTime = 2.0f * maxRelease_ / crestRatio - adaptiveAttackTime;
            if (adaptiveReleaseTime <= 0.0f) {
                releaseCoeff = 1.0f;
            } else {
                releaseCoeff = calculateExpSomething(samplingRate_, adaptiveReleaseTime);
            }
        }

        final float logInput = (float) Math.log(in >= 0.000001f ? (float) in : 0.000001f);

        final float diff = logInput - smoothedThreshold_;
        float ratioMul;
        float halfThreshGr;
        float halfKnee;
        float kneeWidth;

        if (!autoKnee_) {
            final float negRatio = -ratio_;
            kneeWidth = knee_;
            halfThreshGr = smoothedThreshold_ * negRatio * 0.5f;
            halfKnee = kneeWidth * 0.5f;
            ratioMul = negRatio;
        } else {
            halfThreshGr = smoothedThreshold_ * 0.5f;
            final float kneeBase = adaptiveGainState_ + halfThreshGr;
            kneeWidth = -(kneeBase * kneeMulti_);
            if (kneeWidth <= 0.0f) {
                ratioMul = 1.0f;
                halfKnee = 0.0f;
                kneeWidth = 0.0f;
            } else {
                ratioMul = 1.0f;
                halfKnee = kneeWidth * 0.5f;
            }
        }

        float gainReduction;
        if (diff >= halfKnee) {
            gainReduction = diff;
        } else if (diff <= -(kneeWidth * 0.5f)) {
            gainReduction = 0.0f;
        } else {
            final float doubled = kneeWidth * 2.0f;
            final float shifted = diff + halfKnee;
            gainReduction = shifted * shifted / doubled;
        }

        gainReduction *= ratioMul;

        final float relSmoothed = releaseSmoothGr_ + (gainReduction - releaseSmoothGr_) * releaseCoeff;
        if (gainReduction <= relSmoothed) {
            gainReduction = relSmoothed;
        }

        final float atkDiff = gainReduction - attackSmoothGr_;
        releaseSmoothGr_ = gainReduction;
        final float smoothedGr = attackSmoothGr_ + atkDiff * attackCoeff;

        final float negSmoothedGr = -smoothedGr;
        final float adaptTarget = negSmoothedGr - halfThreshGr - adaptiveGainState_;
        attackSmoothGr_ = smoothedGr;

        adaptiveGainState_ = adaptiveGainState_ + adaptTarget * adapt2_;

        if (autoGain_) {
            if (!noClip_) {
                final float makeupGain = adaptiveGainState_ + halfThreshGr;
                return Math.exp(negSmoothedGr - makeupGain);
            }
            float outputLevel = logInput - smoothedGr;
            float makeupGain = halfThreshGr + adaptiveGainState_;
            if (outputLevel - makeupGain > 0.0011512704f) {
                outputLevel = outputLevel - halfThreshGr;
                outputLevel = outputLevel + 0.0011512704f;
                makeupGain = outputLevel + halfThreshGr;
                adaptiveGainState_ = outputLevel;
            }
            return Math.exp(negSmoothedGr - makeupGain);
        }

        return Math.exp(smoothedGain_ - smoothedGr);
    }

    private static float calculateExpSomething(final int samplingRate, final float time) {
        return 1.0f - (float) Math.exp(-1.0f / (time * samplingRate));
    }

    private static float calculateTimeCoeff(
            final int samplingRate, final float value, final float scale, final float offset) {
        final float time = (float) Math.exp(value * scale + offset);
        if (time <= 0.0f) {
            return 1.0f;
        }
        return calculateExpSomething(samplingRate, time);
    }
}
