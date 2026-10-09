// 逐行移植自 ViPERDSP/viper/utils/IIR_NOrder_BW_LH（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class IIR_NOrder_BW_LH {
    public int order_;

    public IIR_1st[] filters_;

    public IIR_NOrder_BW_LH(int order) {
        this.filters_ = new IIR_1st[order];
        this.order_ = order;

        for (int x = 0; x < order; x++) {
            this.filters_[x] = new IIR_1st();
            this.filters_[x].mute();
        }
    }

    public void mute() {
        for (int x = 0; x < this.order_; x++) {
            this.filters_[x].mute();
        }
    }

    public void setLPF(float frequency, int sampling_rate) {
        for (int x = 0; x < this.order_; x++) {
            this.filters_[x].setLowPassFilterBW(frequency, sampling_rate);
        }
    }

    public void setHPF(float frequency, int sampling_rate) {
        for (int x = 0; x < this.order_; x++) {
            this.filters_[x].setHighPassFilterBW(frequency, sampling_rate);
        }
    }

    public static float filterLH(IIR_NOrder_BW_LH filter, float sample) {
        for (int idx = 0; idx < filter.order_; idx++) {
            sample = IIR_1st.filter(filter.filters_[idx], sample);
        }
        return sample;
    }
}
