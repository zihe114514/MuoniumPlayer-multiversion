// 逐行移植自 ViPERDSP/viper/effects/MultibandCompressor（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

// 逐行移植自 ViPERDSP/viper/effects/MultibandCompressor.h/.cpp
public final class MultibandCompressor {
    public static final int K_MAX_BANDS = 5;
    public static final int K_MAX_CROSSOVERS = 4;
    private static final int DEFAULT_SAMPLING_RATE = 44100;
    // 交织立体声缓冲预分配容量（frames）；process 内仅在超界时按需扩容（罕见路径）。
    private static final int DEFAULT_CAPACITY_FRAMES = 8192;

    private static final float[] K_DEFAULT_3_BAND_FREQS = {200.0f, 4000.0f};
    private static final float[] K_DEFAULT_5_BAND_FREQS = {120.0f, 500.0f, 4000.0f, 8000.0f};
    private static final float K_BUTTERWORTH_Q = 0.7071f;

    private boolean enable_;

    private int samplingRate_;
    private int bandCount_;

    private final float[] crossoverFreqs_ = new float[K_MAX_CROSSOVERS];

    private final MultiBiquad[] lowpassLa_ = new MultiBiquad[K_MAX_CROSSOVERS];
    private final MultiBiquad[] lowpassLb_ = new MultiBiquad[K_MAX_CROSSOVERS];
    private final MultiBiquad[] lowpassRa_ = new MultiBiquad[K_MAX_CROSSOVERS];
    private final MultiBiquad[] lowpassRb_ = new MultiBiquad[K_MAX_CROSSOVERS];
    private final MultiBiquad[] highpassLa_ = new MultiBiquad[K_MAX_CROSSOVERS];
    private final MultiBiquad[] highpassLb_ = new MultiBiquad[K_MAX_CROSSOVERS];
    private final MultiBiquad[] highpassRa_ = new MultiBiquad[K_MAX_CROSSOVERS];
    private final MultiBiquad[] highpassRb_ = new MultiBiquad[K_MAX_CROSSOVERS];

    private final FETCompressor[] compressors_ = new FETCompressor[K_MAX_BANDS];

    private final float[][] bandBuffers_ = new float[K_MAX_BANDS][];

    public MultibandCompressor() {
        enable_ = false;
        samplingRate_ = DEFAULT_SAMPLING_RATE;
        bandCount_ = 3;

        crossoverFreqs_[0] = K_DEFAULT_3_BAND_FREQS[0];
        crossoverFreqs_[1] = K_DEFAULT_3_BAND_FREQS[1];
        crossoverFreqs_[2] = 0.0f;
        crossoverFreqs_[3] = 0.0f;

        for (int i = 0; i < K_MAX_CROSSOVERS; i++) {
            lowpassLa_[i] = new MultiBiquad();
            lowpassLb_[i] = new MultiBiquad();
            lowpassRa_[i] = new MultiBiquad();
            lowpassRb_[i] = new MultiBiquad();
            highpassLa_[i] = new MultiBiquad();
            highpassLb_[i] = new MultiBiquad();
            highpassRa_[i] = new MultiBiquad();
            highpassRb_[i] = new MultiBiquad();
        }
        for (int i = 0; i < K_MAX_BANDS; i++) {
            compressors_[i] = new FETCompressor();
            compressors_[i].setSampleRate(samplingRate_);
            compressors_[i].reset();
            bandBuffers_[i] = new float[DEFAULT_CAPACITY_FRAMES * 2];
        }

        configureCrossovers();
    }

    public void process(float[] samples, int frames) {
        if (!enable_) return;
        if (frames == 0) return;

        final int numCrossovers = bandCount_ - 1;
        final int frameCount = frames * 2;

        for (int b = 0; b < bandCount_; b++) {
            if (bandBuffers_[b].length < frameCount) {
                bandBuffers_[b] = new float[frameCount];
            }
        }

        for (int b = 0; b < bandCount_; b++) {
            for (int i = 0; i < frameCount; i += 2) {
                double sampleL = samples[i];
                double sampleR = samples[i + 1];

                if (b == 0) {
                    sampleL = lowpassLa_[0].processSample((float) sampleL);
                    sampleL = lowpassLb_[0].processSample((float) sampleL);
                    sampleR = lowpassRa_[0].processSample((float) sampleR);
                    sampleR = lowpassRb_[0].processSample((float) sampleR);
                } else if (b == numCrossovers) {
                    sampleL = highpassLa_[numCrossovers - 1].processSample((float) sampleL);
                    sampleL = highpassLb_[numCrossovers - 1].processSample((float) sampleL);
                    sampleR = highpassRa_[numCrossovers - 1].processSample((float) sampleR);
                    sampleR = highpassRb_[numCrossovers - 1].processSample((float) sampleR);
                } else {
                    sampleL = highpassLa_[b - 1].processSample((float) sampleL);
                    sampleL = highpassLb_[b - 1].processSample((float) sampleL);
                    sampleL = lowpassLa_[b].processSample((float) sampleL);
                    sampleL = lowpassLb_[b].processSample((float) sampleL);
                    sampleR = highpassRa_[b - 1].processSample((float) sampleR);
                    sampleR = highpassRb_[b - 1].processSample((float) sampleR);
                    sampleR = lowpassRa_[b].processSample((float) sampleR);
                    sampleR = lowpassRb_[b].processSample((float) sampleR);
                }

                bandBuffers_[b][i] = (float) sampleL;
                bandBuffers_[b][i + 1] = (float) sampleR;
            }

            compressors_[b].process(bandBuffers_[b], frames);
        }

        for (int i = 0; i < frameCount; i += 2) {
            float sumL = 0.0f;
            float sumR = 0.0f;
            for (int b = 0; b < bandCount_; b++) {
                sumL += bandBuffers_[b][i];
                sumR += bandBuffers_[b][i + 1];
            }
            samples[i] = sumL;
            samples[i + 1] = sumR;
        }
    }

    public void reset() {
        for (int i = 0; i < K_MAX_BANDS; i++) {
            compressors_[i].reset();
        }

        for (int i = 0; i < K_MAX_CROSSOVERS; i++) {
            lowpassLa_[i].reset();
            lowpassLb_[i].reset();
            lowpassRa_[i].reset();
            lowpassRb_[i].reset();
            highpassLa_[i].reset();
            highpassLb_[i].reset();
            highpassRa_[i].reset();
            highpassRb_[i].reset();
        }
        configureCrossovers();
    }

    public float getBandGainReductionDb(int band) {
        if (!enable_ || band >= bandCount_ || band >= K_MAX_BANDS) return 0.0F;
        return compressors_[band].getGainReductionDb();
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (enable) reset();
            enable_ = enable;
        }
    }

    public void setBandCount(int count) {
        if (count != 3 && count != 5) return;
        if (bandCount_ != count) {
            bandCount_ = count;
            if (count == 3) {
                crossoverFreqs_[0] = K_DEFAULT_3_BAND_FREQS[0];
                crossoverFreqs_[1] = K_DEFAULT_3_BAND_FREQS[1];
                crossoverFreqs_[2] = 0.0f;
                crossoverFreqs_[3] = 0.0f;
            } else {
                crossoverFreqs_[0] = K_DEFAULT_5_BAND_FREQS[0];
                crossoverFreqs_[1] = K_DEFAULT_5_BAND_FREQS[1];
                crossoverFreqs_[2] = K_DEFAULT_5_BAND_FREQS[2];
                crossoverFreqs_[3] = K_DEFAULT_5_BAND_FREQS[3];
            }
            reset();
        }
    }

    public void setCrossoverFrequency(int index, float frequency) {
        final int numCrossovers = bandCount_ - 1;
        if (index >= numCrossovers) return;
        if (crossoverFreqs_[index] != frequency) {
            crossoverFreqs_[index] = frequency;
            configureCrossovers();
        }
    }

    public void setSampleRate(int samplingRate) {
        if (samplingRate_ != samplingRate) {
            samplingRate_ = samplingRate;
            for (int i = 0; i < K_MAX_BANDS; i++) {
                compressors_[i].setSampleRate(samplingRate_);
            }
            configureCrossovers();
        }
    }

    public void setBandEnable(int band, boolean enable) {
        if (band >= bandCount_) return;
        compressors_[band].setEnable(enable);
    }

    public void setBandThreshold(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setThreshold(value);
    }

    public void setBandRatio(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setRatio(value);
    }

    public void setBandKnee(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setKnee(value);
    }

    public void setBandKneeAuto(int band, boolean enable) {
        if (band >= bandCount_) return;
        compressors_[band].setKneeAuto(enable);
    }

    public void setBandGain(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setGain(value);
    }

    public void setBandGainAuto(int band, boolean enable) {
        if (band >= bandCount_) return;
        compressors_[band].setGainAuto(enable);
    }

    public void setBandAttack(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setAttack(value);
    }

    public void setBandAttackAuto(int band, boolean enable) {
        if (band >= bandCount_) return;
        compressors_[band].setAttackAuto(enable);
    }

    public void setBandRelease(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setRelease(value);
    }

    public void setBandReleaseAuto(int band, boolean enable) {
        if (band >= bandCount_) return;
        compressors_[band].setReleaseAuto(enable);
    }

    public void setBandKneeMulti(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setKneeMulti(value);
    }

    public void setBandMaxAttack(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setMaxAttack(value);
    }

    public void setBandMaxRelease(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setMaxRelease(value);
    }

    public void setBandCrest(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setCrest(value);
    }

    public void setBandAdapt(int band, float value) {
        if (band >= bandCount_) return;
        compressors_[band].setAdapt(value);
    }

    public void setBandNoClip(int band, boolean enable) {
        if (band >= bandCount_) return;
        compressors_[band].setNoClip(enable);
    }

    private void configureCrossovers() {
        final int numCrossovers = bandCount_ - 1;
        for (int i = 0; i < numCrossovers; i++) {
            lowpassLa_[i].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS, 1.0f, crossoverFreqs_[i], samplingRate_, K_BUTTERWORTH_Q, false);
            lowpassLb_[i].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS, 1.0f, crossoverFreqs_[i], samplingRate_, K_BUTTERWORTH_Q, false);
            lowpassRa_[i].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS, 1.0f, crossoverFreqs_[i], samplingRate_, K_BUTTERWORTH_Q, false);
            lowpassRb_[i].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS, 1.0f, crossoverFreqs_[i], samplingRate_, K_BUTTERWORTH_Q, false);
            highpassLa_[i].refreshFilter(
                    MultiBiquad.FilterType.HIGH_PASS, 1.0f, crossoverFreqs_[i], samplingRate_, K_BUTTERWORTH_Q, false);
            highpassLb_[i].refreshFilter(
                    MultiBiquad.FilterType.HIGH_PASS, 1.0f, crossoverFreqs_[i], samplingRate_, K_BUTTERWORTH_Q, false);
            highpassRa_[i].refreshFilter(
                    MultiBiquad.FilterType.HIGH_PASS, 1.0f, crossoverFreqs_[i], samplingRate_, K_BUTTERWORTH_Q, false);
            highpassRb_[i].refreshFilter(
                    MultiBiquad.FilterType.HIGH_PASS, 1.0f, crossoverFreqs_[i], samplingRate_, K_BUTTERWORTH_Q, false);
        }
    }
}
