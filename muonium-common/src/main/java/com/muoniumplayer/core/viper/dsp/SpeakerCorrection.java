// 逐行移植自 ViPERDSP/viper/effects/SpeakerCorrection（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

// 逐行移植自 ViPERDSP/viper/effects/SpeakerCorrection.h/.cpp
public final class SpeakerCorrection {
    private static final int DEFAULT_SAMPLING_RATE = 44100;

    private boolean enable_;

    private int samplingRate_;
    private int hpCutoff_;
    private int lpCutoff_;
    private int bpCenter_;
    private float bpQ_;

    private final MultiBiquad[] highPass_ = new MultiBiquad[2];
    private final Biquad[] lowPass_ = new Biquad[2];
    private final Biquad[] bandPass_ = new Biquad[2];

    public SpeakerCorrection() {
        enable_ = false;
        samplingRate_ = DEFAULT_SAMPLING_RATE;
        hpCutoff_ = 80;
        lpCutoff_ = 13500;
        bpCenter_ = 420;
        bpQ_ = 3.88f;

        highPass_[0] = new MultiBiquad();
        highPass_[1] = new MultiBiquad();
        lowPass_[0] = new Biquad();
        lowPass_[1] = new Biquad();
        bandPass_[0] = new Biquad();
        bandPass_[1] = new Biquad();

        reset();
    }

    public void process(float[] samples, int frames) {
        if (!enable_) return;

        for (int i = 0; i < frames * 2; i += 2) {
            double outL = samples[i];
            outL = lowPass_[0].processSample((float) outL);
            outL = highPass_[0].processSample((float) outL);
            outL /= 2.0;
            outL += bandPass_[0].processSample((float) outL);
            samples[i] = (float) outL;

            double outR = samples[i + 1];
            outR = lowPass_[1].processSample((float) outR);
            outR = highPass_[1].processSample((float) outR);
            outR /= 2.0;
            outR += bandPass_[1].processSample((float) outR);
            samples[i + 1] = (float) outR;
        }
    }

    public void reset() {
        lowPass_[0].reset();
        lowPass_[1].reset();
        bandPass_[0].reset();
        bandPass_[1].reset();
        highPass_[0].reset();
        highPass_[1].reset();

        refreshHighPass();
        refreshLowPass();
        refreshBandPass();
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (enable) {
                reset();
            }
            enable_ = enable;
        }
    }

    public void setSampleRate(int samplingRate) {
        if (samplingRate_ != samplingRate) {
            samplingRate_ = samplingRate;
            reset();
        }
    }

    public void setHighPassCutoff(int value) {
        if (hpCutoff_ != value) {
            hpCutoff_ = value;
            refreshHighPass();
        }
    }

    public void setLowPassCutoff(int value) {
        if (lpCutoff_ != value) {
            lpCutoff_ = value;
            refreshLowPass();
        }
    }

    public void setBandPassCenter(int value) {
        if (bpCenter_ != value) {
            bpCenter_ = value;
            refreshBandPass();
        }
    }

    public void setBandPassQ(float value) {
        if (bpQ_ != value) {
            bpQ_ = value;
            refreshBandPass();
        }
    }

    private void refreshHighPass() {
        highPass_[0].refreshFilter(
                MultiBiquad.FilterType.HIGH_PASS, 0.0f, (float) hpCutoff_, samplingRate_, 1.0f, false);
        highPass_[1].refreshFilter(
                MultiBiquad.FilterType.HIGH_PASS, 0.0f, (float) hpCutoff_, samplingRate_, 1.0f, false);
    }

    private void refreshLowPass() {
        lowPass_[0].setLowPassParameter((float) lpCutoff_, samplingRate_, 1.0f);
        lowPass_[1].setLowPassParameter((float) lpCutoff_, samplingRate_, 1.0f);
    }

    private void refreshBandPass() {
        bandPass_[0].setBandPassParameter((float) bpCenter_, samplingRate_, bpQ_);
        bandPass_[1].setBandPassParameter((float) bpCenter_, samplingRate_, bpQ_);
    }
}
