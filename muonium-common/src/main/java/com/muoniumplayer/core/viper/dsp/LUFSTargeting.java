// 逐行移植自 ViPERDSP/viper/effects/LUFSTargeting（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

// 逐行移植自 ViPERDSP/viper/effects/LUFSTargeting.h/.cpp
public final class LUFSTargeting {
    private static final int DEFAULT_SAMPLING_RATE = 44100;
    private static final int K_MAX_WINDOWS = 40;
    private static final double K_ABSOLUTE_GATE_LUFS = -70.0;

    private boolean enable_;

    private int speed_;
    private int samplingRate_;
    private int windowSize_;
    private int stepSize_;
    private int sampleCounter_;
    private int windowSampleCount_;
    private int windowWriteIdx_;
    private int windowCount_;

    private float targetLufs_;
    private float maxGainDb_;

    private double windowAccumulator_;
    private final double[] windowPower_ = new double[K_MAX_WINDOWS];
    private double smoothedGainDb_;
    private double attackCoeff_;
    private double releaseCoeff_;

    private final Biquad kWeightStage1L_ = new Biquad();
    private final Biquad kWeightStage1R_ = new Biquad();
    private final Biquad kWeightStage2L_ = new Biquad();
    private final Biquad kWeightStage2R_ = new Biquad();

    public LUFSTargeting() {
        enable_ = false;
        speed_ = 1;
        samplingRate_ = DEFAULT_SAMPLING_RATE;
        windowSize_ = 0;
        stepSize_ = 0;
        sampleCounter_ = 0;
        windowSampleCount_ = 0;
        windowWriteIdx_ = 0;
        windowCount_ = 0;
        targetLufs_ = -14.0f;
        maxGainDb_ = 6.0f;
        windowAccumulator_ = 0.0;
        smoothedGainDb_ = 0.0;
        attackCoeff_ = 0.0;
        releaseCoeff_ = 0.0;

        configureFilters();
        updateSmoothingCoeffs();
        windowSize_ = (int) (samplingRate_ * 0.4);
        stepSize_ = windowSize_ / 4;
    }

    public void process(float[] samples, int frames) {
        if (!enable_) return;
        if (frames == 0) return;

        final double gateThreshold = Math.pow(10.0, (K_ABSOLUTE_GATE_LUFS + 0.691) / 10.0);

        for (int i = 0; i < frames; i++) {
            final double left = samples[i * 2];
            final double right = samples[i * 2 + 1];

            double kLeft = kWeightStage1L_.processSample((float) left);
            kLeft = kWeightStage2L_.processSample((float) kLeft);

            double kRight = kWeightStage1R_.processSample((float) right);
            kRight = kWeightStage2R_.processSample((float) kRight);

            windowAccumulator_ += kLeft * kLeft + kRight * kRight;
            windowSampleCount_++;
            sampleCounter_++;

            if (sampleCounter_ >= stepSize_) {
                sampleCounter_ = 0;

                if (windowSampleCount_ >= windowSize_) {
                    final double meanSquare =
                            windowAccumulator_ / (double) windowSampleCount_;

                    if (meanSquare > gateThreshold) {
                        windowPower_[windowWriteIdx_] = meanSquare;
                        windowWriteIdx_ = (windowWriteIdx_ + 1) % K_MAX_WINDOWS;
                        if (windowCount_ < K_MAX_WINDOWS) {
                            windowCount_++;
                        }
                    }

                    final int shiftSamples = windowSize_ - stepSize_;
                    final double ratio = (double) shiftSamples / (double) windowSampleCount_;
                    windowAccumulator_ *= ratio;
                    windowSampleCount_ = shiftSamples;
                }
            }

            double measuredLufs = -70.0;
            if (windowCount_ > 0) {
                double sum = 0.0;
                for (int w = 0; w < windowCount_; w++) {
                    sum += windowPower_[w];
                }
                final double gatedMean = sum / (double) windowCount_;
                if (gatedMean > 1e-20) {
                    measuredLufs = -0.691 + 10.0 * Math.log10(gatedMean);
                }
            }

            double desiredGainDb = targetLufs_ - measuredLufs;

            if (desiredGainDb > maxGainDb_) {
                desiredGainDb = maxGainDb_;
            } else if (desiredGainDb < -maxGainDb_) {
                desiredGainDb = -maxGainDb_;
            }

            final double coeff;
            if (desiredGainDb > smoothedGainDb_) {
                coeff = attackCoeff_;
            } else {
                coeff = releaseCoeff_;
            }
            smoothedGainDb_ += coeff * (desiredGainDb - smoothedGainDb_);

            final double gainLinear = Math.pow(10.0, smoothedGainDb_ / 20.0);

            samples[i * 2] *= (float) gainLinear;
            samples[i * 2 + 1] *= (float) gainLinear;
        }
    }

    public void reset() {
        kWeightStage1L_.reset();
        kWeightStage1R_.reset();
        kWeightStage2L_.reset();
        kWeightStage2R_.reset();
        configureFilters();
        updateSmoothingCoeffs();
        smoothedGainDb_ = 0.0;
        sampleCounter_ = 0;
        windowAccumulator_ = 0.0;
        windowSampleCount_ = 0;
        windowWriteIdx_ = 0;
        windowCount_ = 0;
        for (int i = 0; i < K_MAX_WINDOWS; i++) {
            windowPower_[i] = 0.0;
        }
        windowSize_ = (int) (samplingRate_ * 0.4);
        stepSize_ = windowSize_ / 4;
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (enable) {
                reset();
            }
            enable_ = enable;
        }
    }

    public void setTargetLUFS(float value) {
        targetLufs_ = value;
    }

    public void setMaxGain(float value) {
        maxGainDb_ = value;
    }

    public void setSpeed(int value) {
        if (value < 0) speed_ = 0;
        if (value > 2) speed_ = 2;
        updateSmoothingCoeffs();
    }

    public void setSampleRate(int samplingRate) {
        if (samplingRate_ != samplingRate) {
            samplingRate_ = samplingRate;
            reset();
        }
    }

    private void configureFilters() {
        if (samplingRate_ == 48000) {
            kWeightStage1L_.setCoeffs(
                    1.0, -1.69065929318241, 0.73248077421585, 1.53512485958697, -2.69169618940638, 1.19839281085285);
            kWeightStage1R_.setCoeffs(
                    1.0, -1.69065929318241, 0.73248077421585, 1.53512485958697, -2.69169618940638, 1.19839281085285);
            kWeightStage2L_.setCoeffs(
                    1.0, -1.99004745483398, 0.99007225036621, 1.0, -2.0, 1.0);
            kWeightStage2R_.setCoeffs(
                    1.0, -1.99004745483398, 0.99007225036621, 1.0, -2.0, 1.0);
        } else {
            kWeightStage1L_.setCoeffs(
                    1.0, -1.6636551132560204, 0.7125954280732254, 1.5308412300503478, -2.6509799951547297, 1.1690790799215869);
            kWeightStage1R_.setCoeffs(
                    1.0, -1.6636551132560204, 0.7125954280732254, 1.5308412300503478, -2.6509799951547297, 1.1690790799215869);
            kWeightStage2L_.setCoeffs(
                    1.0, -1.9891696736297957, 0.9891990357870394, 1.0, -2.0, 1.0);
            kWeightStage2R_.setCoeffs(
                    1.0, -1.9891696736297957, 0.9891990357870394, 1.0, -2.0, 1.0);
        }
    }

    private void updateSmoothingCoeffs() {
        double attackMs;
        double releaseMs;

        switch (speed_) {
            case 0:
                attackMs = 200.0;
                releaseMs = 1000.0;
                break;
            case 2:
                attackMs = 50.0;
                releaseMs = 200.0;
                break;
            default:
                attackMs = 100.0;
                releaseMs = 500.0;
                break;
        }

        final double attackSamples = (double) samplingRate_ * attackMs / 1000.0;
        final double releaseSamples = (double) samplingRate_ * releaseMs / 1000.0;

        attackCoeff_ = 1.0 - Math.exp(-1.0 / attackSamples);
        releaseCoeff_ = 1.0 - Math.exp(-1.0 / releaseSamples);
    }
}
