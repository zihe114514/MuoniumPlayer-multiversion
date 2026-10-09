// 逐行移植自 ViPERDSP/viper/effects/ViPERDDC（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class ViPERDDC {
    private boolean enable_;
    private boolean setCoeffsOk_;

    private int samplingRate_;
    private int arrSize_;

    private float[][] coeffsArr44100_;
    private float[][] coeffsArr48000_;
    private float[] x1L_;
    private float[] x1R_;
    private float[] x2L_;
    private float[] x2R_;
    private float[] y1L_;
    private float[] y1R_;
    private float[] y2L_;
    private float[] y2R_;

    public ViPERDDC() {
        enable_ = false;
        setCoeffsOk_ = false;
        samplingRate_ = 44100;
        arrSize_ = 0;
    }

    public void process(final float[] samples, final int frames) {
        if (!setCoeffsOk_ || arrSize_ == 0) return;
        if (!enable_) return;

        float[][] coeffsArr;

        switch (samplingRate_) {
            case 44100: {
                coeffsArr = coeffsArr44100_;
                break;
            }
            case 48000: {
                coeffsArr = coeffsArr48000_;
                break;
            }
            default: {
                return;
            }
        }

        final int n = frames * 2;
        for (int i = 0; i < n; i += 2) {
            float sampleL = samples[i];
            float sampleR = samples[i + 1];

            for (int j = 0; j < arrSize_; j++) {
                final float[] coeffs = coeffsArr[j];

                final float b0 = coeffs[0];
                final float b1 = coeffs[1];
                final float b2 = coeffs[2];
                final float a1 = coeffs[3];
                final float a2 = coeffs[4];

                final float outL = sampleL * b0 + x1L_[j] * b1 + x2L_[j] * b2
                        + y1L_[j] * a1 + y2L_[j] * a2;

                x2L_[j] = x1L_[j];
                x1L_[j] = sampleL;
                y2L_[j] = y1L_[j];
                y1L_[j] = outL;

                sampleL = outL;

                final float outR = sampleR * b0 + x1R_[j] * b1 + x2R_[j] * b2
                        + y1R_[j] * a1 + y2R_[j] * a2;

                x2R_[j] = x1R_[j];
                x1R_[j] = sampleR;
                y2R_[j] = y1R_[j];
                y1R_[j] = outR;

                sampleR = outR;
            }

            samples[i] = sampleL;
            samples[i + 1] = sampleR;
        }
    }

    public void reset() {
        if (!setCoeffsOk_) return;
        if (arrSize_ == 0) return;

        Arrays.fill(x1L_, 0.0f);
        Arrays.fill(x1R_, 0.0f);
        Arrays.fill(x2L_, 0.0f);
        Arrays.fill(x2R_, 0.0f);
        Arrays.fill(y1L_, 0.0f);
        Arrays.fill(y1R_, 0.0f);
        Arrays.fill(y2L_, 0.0f);
        Arrays.fill(y2R_, 0.0f);
    }

    public void setEnable(final boolean enable) {
        if (enable_ != enable) {
            enable_ = enable;
            if (enable) {
                reset();
            }
        }
    }

    public void setCoeffs(
            final int totalFloats,
            final float[] sections44100,
            final float[] sections48000
    ) {
        releaseResources();

        if (totalFloats == 0) return;

        arrSize_ = totalFloats / 5;
        coeffsArr44100_ = new float[arrSize_][5];
        coeffsArr48000_ = new float[arrSize_][5];

        for (int i = 0; i < arrSize_; i++) {
            coeffsArr44100_[i][0] = sections44100[i * 5];
            coeffsArr44100_[i][1] = sections44100[i * 5 + 1];
            coeffsArr44100_[i][2] = sections44100[i * 5 + 2];
            coeffsArr44100_[i][3] = sections44100[i * 5 + 3];
            coeffsArr44100_[i][4] = sections44100[i * 5 + 4];

            coeffsArr48000_[i][0] = sections48000[i * 5];
            coeffsArr48000_[i][1] = sections48000[i * 5 + 1];
            coeffsArr48000_[i][2] = sections48000[i * 5 + 2];
            coeffsArr48000_[i][3] = sections48000[i * 5 + 3];
            coeffsArr48000_[i][4] = sections48000[i * 5 + 4];
        }

        x1L_ = new float[arrSize_];
        x1R_ = new float[arrSize_];
        x2L_ = new float[arrSize_];
        x2R_ = new float[arrSize_];
        y1L_ = new float[arrSize_];
        y1R_ = new float[arrSize_];
        y2L_ = new float[arrSize_];
        y2R_ = new float[arrSize_];

        setCoeffsOk_ = true;
    }

    public void clearCoeffs() {
        releaseResources();
    }

    public void setSampleRate(final int samplingRate) {
        if (samplingRate_ != samplingRate) {
            samplingRate_ = samplingRate;
            reset();
        }
    }

    private void releaseResources() {
        setCoeffsOk_ = false;

        coeffsArr44100_ = null;
        coeffsArr48000_ = null;

        x1L_ = null;
        x1R_ = null;
        x2L_ = null;
        x2R_ = null;
        y1L_ = null;
        y1R_ = null;
        y2L_ = null;
        y2R_ = null;
    }
}
