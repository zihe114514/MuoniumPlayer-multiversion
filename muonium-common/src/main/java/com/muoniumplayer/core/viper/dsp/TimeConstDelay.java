// 逐行移植自 ViPERDSP/viper/utils/TimeConstDelay（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class TimeConstDelay {
    private int offset_;
    private int sampleCount_;

    private float[] samples_ = new float[0];

    public TimeConstDelay() {
        offset_ = 0;
        sampleCount_ = 0;
    }

    public float processSample(float sample) {
        final float val = samples_[offset_];
        samples_[offset_] = sample;
        offset_++;
        if (offset_ >= sampleCount_) {
            offset_ = 0;
        }
        return val;
    }

    public void setParameters(int samplingRate, float delay) {
        sampleCount_ = (int) ((float) samplingRate * delay);
        samples_ = new float[sampleCount_];
        offset_ = 0;
    }
}
