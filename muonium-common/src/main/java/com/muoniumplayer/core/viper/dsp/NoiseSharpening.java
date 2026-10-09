// 逐行移植自 ViPERDSP/viper/utils/NoiseSharpening（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class NoiseSharpening {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private int sampling_rate_;

    private float gain_;
    private final float[] in_ = new float[2];

    private final IIR_1st[] filters_ = new IIR_1st[2];

    public NoiseSharpening() {
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        gain_ = 0.0f;
        filters_[0] = new IIR_1st();
        filters_[1] = new IIR_1st();
        reset();
    }

    public void process(float[] buffer, int size) {
        for (int i = 0; i < size; i++) {
            final float sample_l = buffer[i * 2];
            final float sample_r = buffer[i * 2 + 1];
            final float prev_l = in_[0];
            final float prev_r = in_[1];
            in_[0] = sample_l;
            in_[1] = sample_r;
            final float diff_l = (sample_l - prev_l) * gain_;
            final float diff_r = (sample_r - prev_r) * gain_;

            final float sample_l_in = sample_l + diff_l;
            final float sample_r_in = sample_r + diff_r;

            float hist = sample_l_in * filters_[0].b1_;
            final float left = filters_[0].prev_sample_ + sample_l_in * filters_[0].b0_;
            filters_[0].prev_sample_ = sample_l_in * filters_[0].a1_ + hist;

            hist = sample_r_in * filters_[1].b1_;
            final float right = filters_[1].prev_sample_ + sample_r_in * filters_[1].b0_;
            filters_[1].prev_sample_ = sample_r_in * filters_[1].a1_ + hist;

            buffer[i * 2] = left;
            buffer[i * 2 + 1] = right;
        }
    }

    public void reset() {
        for (int i = 0; i < 2; i++) {
            filters_[i].setLowPassFilterBW(
                    (float) (sampling_rate_ / 2.0 - 1000.0), sampling_rate_);
            filters_[i].mute();
            in_[i] = 0.0f;
        }
    }

    public void setGain(float gain) {
        gain_ = gain;
    }

    public void setSampleRate(int sampling_rate) {
        sampling_rate_ = sampling_rate;
        reset();
    }
}
