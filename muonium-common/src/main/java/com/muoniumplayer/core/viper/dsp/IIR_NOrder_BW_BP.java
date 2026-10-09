// 逐行移植自 ViPERDSP/viper/utils/IIR_NOrder_BW_BP（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class IIR_NOrder_BW_BP {
    public int order_;

    public IIR_1st[] lowpass_;
    public IIR_1st[] highpass_;

    public IIR_NOrder_BW_BP(int order) {
        this.lowpass_ = new IIR_1st[order];
        this.highpass_ = new IIR_1st[order];
        this.order_ = order;

        for (int x = 0; x < order; x++) {
            this.lowpass_[x] = new IIR_1st();
            this.lowpass_[x].mute();
            this.highpass_[x] = new IIR_1st();
            this.highpass_[x].mute();
        }
    }

    public void mute() {
        for (int x = 0; x < this.order_; x++) {
            this.lowpass_[x].mute();
            this.highpass_[x].mute();
        }
    }

    public void setBPF(float high_cut, float low_cut, int sampling_rate) {
        for (int x = 0; x < this.order_; x++) {
            this.lowpass_[x].setLowPassFilterBW(low_cut, sampling_rate);
            this.highpass_[x].setHighPassFilterBW(high_cut, sampling_rate);
        }
    }

    public static float filterBPLowPass(IIR_NOrder_BW_BP filter, float sample) {
        for (int idx = 0; idx < filter.order_; idx++) {
            sample = IIR_1st.filter(filter.lowpass_[idx], sample);
        }
        return sample;
    }

    public static float filterBPHighPass(IIR_NOrder_BW_BP filter, float sample) {
        for (int idx = 0; idx < filter.order_; idx++) {
            sample = IIR_1st.filter(filter.highpass_[idx], sample);
        }
        return sample;
    }

    public static float filterBP(IIR_NOrder_BW_BP filter, float sample) {
        return filterBPHighPass(filter, filterBPLowPass(filter, sample));
    }
}
