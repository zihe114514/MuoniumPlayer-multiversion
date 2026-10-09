// 逐行移植自 ViPERDSP/viper/utils/Subwoofer（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class Subwoofer {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private int sampling_rate_;

    private float gain_;
    private float gain_lower_;

    private final MultiBiquad[] peak_ = new MultiBiquad[2];
    private final MultiBiquad[] peak_low_ = new MultiBiquad[2];
    private final MultiBiquad[] lowpass_ = new MultiBiquad[2];

    public Subwoofer() {
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        gain_ = 0.0f;
        gain_lower_ = 0.0f;

        peak_[0] = new MultiBiquad();
        peak_[1] = new MultiBiquad();
        peak_low_[0] = new MultiBiquad();
        peak_low_[1] = new MultiBiquad();
        lowpass_[0] = new MultiBiquad();
        lowpass_[1] = new MultiBiquad();

        peak_[0].refreshFilter(
                MultiBiquad.FilterType.PEAK, gain_, 37.0f, sampling_rate_, 1.0f, false);
        peak_[1].refreshFilter(
                MultiBiquad.FilterType.PEAK, gain_, 37.0f, sampling_rate_, 1.0f, false);
        peak_low_[0].refreshFilter(
                MultiBiquad.FilterType.PEAK, gain_lower_, 75.0f, sampling_rate_, 1.0f, false);
        peak_low_[1].refreshFilter(
                MultiBiquad.FilterType.PEAK, gain_lower_, 75.0f, sampling_rate_, 1.0f, false);
        lowpass_[0].refreshFilter(
                MultiBiquad.FilterType.LOW_PASS, 0.0f, 200.0f, sampling_rate_, 1.0f, false);
        lowpass_[1].refreshFilter(
                MultiBiquad.FilterType.LOW_PASS, 0.0f, 200.0f, sampling_rate_, 1.0f, false);
    }

    public void process(float[] samples, int size) {
        for (int i = 0; i < size * 2; i += 2) {

            float tmp = peak_[0].processSample(samples[i]);
            tmp = peak_low_[0].processSample(tmp);
            tmp = lowpass_[0].processSample(tmp - samples[i]);
            samples[i] = samples[i] * 0.5f + tmp * 0.6f;

            tmp = peak_[1].processSample(samples[i + 1]);
            tmp = peak_low_[1].processSample(tmp);
            tmp = lowpass_[1].processSample(tmp - samples[i + 1]);
            samples[i + 1] = samples[i + 1] * 0.5f + tmp * 0.6f;
        }
    }

    public void setBassGain(int sampling_rate, float gain_db) {
        gain_ = 20.0f * (float) Math.log10(gain_db);
        gain_lower_ = 20.0f * (float) Math.log10(gain_db / 8.0f);

        peak_[0].refreshFilter(
                MultiBiquad.FilterType.PEAK, gain_, 44.0f, sampling_rate, 0.75f, true);
        peak_[1].refreshFilter(
                MultiBiquad.FilterType.PEAK, gain_, 44.0f, sampling_rate, 0.75f, true);
        peak_low_[0].refreshFilter(
                MultiBiquad.FilterType.PEAK, gain_lower_, 80.0f, sampling_rate, 0.2f, true);
        peak_low_[1].refreshFilter(
                MultiBiquad.FilterType.PEAK, gain_lower_, 80.0f, sampling_rate, 0.2f, true);
        lowpass_[0].refreshFilter(
                MultiBiquad.FilterType.LOW_PASS, 0.0f, 380.0f, sampling_rate, 0.6f, false);
        lowpass_[1].refreshFilter(
                MultiBiquad.FilterType.LOW_PASS, 0.0f, 380.0f, sampling_rate, 0.6f, false);
    }
}
