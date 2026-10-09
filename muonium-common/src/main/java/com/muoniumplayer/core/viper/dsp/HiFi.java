// 逐行移植自 ViPERDSP/viper/utils/HiFi（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class HiFi {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private int sampling_rate_;

    private float gain_;

    private final WaveBuffer[] buffers_ = new WaveBuffer[2];
    private final Filters[] filters_ = new Filters[2];

    private static final class Filters {
        IIR_NOrder_BW_LH lowpass;
        IIR_NOrder_BW_LH highpass;
        IIR_NOrder_BW_BP bandpass;
    }

    public HiFi() {
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        gain_ = 1.0f;
        for (int i = 0; i < 2; i++) {
            buffers_[i] = new WaveBuffer(2, 0x800);
            filters_[i] = new Filters();
            filters_[i].lowpass = new IIR_NOrder_BW_LH(1);
            filters_[i].highpass = new IIR_NOrder_BW_LH(3);
            filters_[i].bandpass = new IIR_NOrder_BW_BP(3);
        }
        reset();
    }

    public void process(float[] samples, int size) {
        if (size > 0) {
            float[] bp_buf = buffers_[0].pushZerosGetBuffer(size);
            float[] lp_buf = buffers_[1].pushZerosGetBuffer(size);
            if (bp_buf == null || lp_buf == null) {
                reset();
                return;
            }

            for (int i = 0; i < size * 2; i++) {
                final int index = i % 2;
                final float out1 = IIR_NOrder_BW_LH.filterLH(filters_[index].lowpass, samples[i]);
                final float out2 = IIR_NOrder_BW_LH.filterLH(filters_[index].highpass, samples[i]);
                final float out3 = IIR_NOrder_BW_BP.filterBP(filters_[index].bandpass, samples[i]);
                samples[i] = out2;
                lp_buf[i] = out1;
                bp_buf[i] = out3;
            }
            final float[] bp_out = buffers_[0].getBuffer();
            final float[] lp_out = buffers_[1].getBuffer();
            for (int i = 0; i < size * 2; i++) {
                final float hp = samples[i] * gain_ * 1.2f;
                final float bp = bp_out[i] * gain_;
                samples[i] = hp + bp + lp_out[i];
            }
            buffers_[0].popSamples(size, false);
            buffers_[1].popSamples(size, false);
        }
    }

    public void reset() {
        for (Filters filter : filters_) {
            filter.lowpass.setLPF(120.0f, sampling_rate_);
            filter.lowpass.mute();
            filter.highpass.setHPF(1200.0f, sampling_rate_);
            filter.highpass.mute();
            filter.bandpass.setBPF(120.0f, 1200.0f, sampling_rate_);
            filter.bandpass.mute();
        }
        buffers_[0].reset();
        buffers_[0].pushZeros(sampling_rate_ / 400);
        buffers_[1].reset();
        buffers_[1].pushZeros(sampling_rate_ / 200);
    }

    public void setClarity(float value) {
        gain_ = value;
    }

    public void setSampleRate(int sampling_rate) {
        sampling_rate_ = sampling_rate;
        reset();
    }
}
