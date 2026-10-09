// 逐行移植自 ViPERDSP/viper/utils/PassFilter（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class PassFilter {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private int sampling_rate_;

    private final IIR_NOrder_BW_LH[] filters_;

    public PassFilter() {
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        filters_ = new IIR_NOrder_BW_LH[] {
                new IIR_NOrder_BW_LH(3),
                new IIR_NOrder_BW_LH(3),
                new IIR_NOrder_BW_LH(1),
                new IIR_NOrder_BW_LH(1)
        };
        reset();
    }

    public void process(float[] buffer, int size) {
        for (int x = 0; x < size; x++) {
            float left = buffer[2 * x];
            float right = buffer[2 * x + 1];

            left = IIR_NOrder_BW_LH.filterLH(filters_[2], left);
            left = IIR_NOrder_BW_LH.filterLH(filters_[0], left);
            right = IIR_NOrder_BW_LH.filterLH(filters_[3], right);
            right = IIR_NOrder_BW_LH.filterLH(filters_[1], right);

            buffer[2 * x] = left;
            buffer[2 * x + 1] = right;
        }
    }

    public void reset() {
        float cutoff;
        if (sampling_rate_ < 44100) {
            cutoff = (float) sampling_rate_ - 100.0f;
        } else {
            cutoff = 18000.0f;
        }

        filters_[0].setLPF(cutoff, sampling_rate_);
        filters_[1].setLPF(cutoff, sampling_rate_);
        filters_[2].setHPF(10.0f, sampling_rate_);
        filters_[3].setHPF(10.0f, sampling_rate_);

        filters_[0].mute();
        filters_[1].mute();
        filters_[2].mute();
        filters_[3].mute();
    }

    public void setSampleRate(int sampling_rate) {
        if (sampling_rate_ != sampling_rate) {
            sampling_rate_ = sampling_rate;
            reset();
        }
    }
}
