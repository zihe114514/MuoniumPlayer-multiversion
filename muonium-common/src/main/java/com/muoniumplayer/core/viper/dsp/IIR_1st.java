// 逐行移植自 ViPERDSP/viper/utils/IIR_1st（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class IIR_1st {
    private static final float PI = (float) Math.PI;

    public float b0_;
    public float b1_;
    public float a1_;
    public float prev_sample_;

    public IIR_1st() {
        b0_ = 0.0f;
        b1_ = 0.0f;
        a1_ = 0.0f;
        prev_sample_ = 0.0f;
    }

    public void mute() {
        prev_sample_ = 0.0f;
    }

    public void setCoefficients(float b0, float b1, float a1) {
        b0_ = b0;
        b1_ = b1;
        a1_ = a1;
    }

    public void setHighPassFilterBW(float frequency, int sampling_rate) {
        final float omega_2 = omega2(frequency, sampling_rate);
        final float tan_omega_2 = (float) Math.tan(omega_2);
        b0_ = 1.0f / (1.0f + tan_omega_2);
        b1_ = -b0_;
        a1_ = (1.0f - tan_omega_2) / (1.0f + tan_omega_2);
    }

    public void setLowPassFilterBW(float frequency, int sampling_rate) {
        final float omega_2 = omega2(frequency, sampling_rate);
        final float tan_omega_2 = (float) Math.tan(omega_2);
        a1_ = (1.0f - tan_omega_2) / (1.0f + tan_omega_2);
        b0_ = tan_omega_2 / (1.0f + tan_omega_2);
        b1_ = b0_;
    }

    public static float filter(IIR_1st filter, float sample) {
        final float hist = sample * filter.b1_;
        sample = filter.prev_sample_ + sample * filter.b0_;
        filter.prev_sample_ = sample * filter.a1_ + hist;
        return sample;
    }

    public static float angle(float frequency, int sampling_rate) {
        return (float) Math.exp(
                -1.0f * PI * frequency / ((float) sampling_rate / 2.0f)
        );
    }

    public static float omega(float frequency, int sampling_rate) {
        return 2.0f * PI * frequency / (float) sampling_rate;
    }

    public static float omega2(float frequency, int sampling_rate) {
        return PI * frequency / (float) sampling_rate;
    }
}
