// 逐行移植自 ViPERDSP/viper/effects/StereoImager.cpp 与 StereoImager.h（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class StereoImager {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;
    private static final float K_BUTTERWORTH_Q = 0.7071f;
    // 预分配容量：足够容纳交织立体声 frame_count(=frames*2) 个 float；按规则 5 在构造器分配，process 内不分配。
    private static final int BAND_BUFFER_CAPACITY = 32768;

    static final int K_NUM_BANDS = 3;
    static final int K_NUM_CROSSOVERS = 2;

    private boolean enable_;

    private int sampling_rate_;

    private final float[] band_widths_ = new float[K_NUM_BANDS];
    private final float[] crossover_freqs_ = new float[K_NUM_CROSSOVERS];

    private final MultiBiquad[] lowpass_la_ = new MultiBiquad[K_NUM_CROSSOVERS];
    private final MultiBiquad[] lowpass_lb_ = new MultiBiquad[K_NUM_CROSSOVERS];
    private final MultiBiquad[] lowpass_ra_ = new MultiBiquad[K_NUM_CROSSOVERS];
    private final MultiBiquad[] lowpass_rb_ = new MultiBiquad[K_NUM_CROSSOVERS];
    private final MultiBiquad[] highpass_la_ = new MultiBiquad[K_NUM_CROSSOVERS];
    private final MultiBiquad[] highpass_lb_ = new MultiBiquad[K_NUM_CROSSOVERS];
    private final MultiBiquad[] highpass_ra_ = new MultiBiquad[K_NUM_CROSSOVERS];
    private final MultiBiquad[] highpass_rb_ = new MultiBiquad[K_NUM_CROSSOVERS];

    private final float[][] band_buffers_ = new float[K_NUM_BANDS][];

    public StereoImager() {
        enable_ = false;
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;

        band_widths_[0] = 1.0f;
        band_widths_[1] = 1.0f;
        band_widths_[2] = 1.0f;

        crossover_freqs_[0] = 200.0f;
        crossover_freqs_[1] = 4000.0f;

        initFilterArray(lowpass_la_);
        initFilterArray(lowpass_lb_);
        initFilterArray(lowpass_ra_);
        initFilterArray(lowpass_rb_);
        initFilterArray(highpass_la_);
        initFilterArray(highpass_lb_);
        initFilterArray(highpass_ra_);
        initFilterArray(highpass_rb_);

        for (int b = 0; b < K_NUM_BANDS; b++) {
            band_buffers_[b] = new float[BAND_BUFFER_CAPACITY];
        }

        configureCrossovers();
    }

    private static void initFilterArray(MultiBiquad[] arr) {
        for (int i = 0; i < K_NUM_CROSSOVERS; i++) {
            arr[i] = new MultiBiquad();
        }
    }

    public void process(float[] samples, int frames) {
        if (!enable_) return;
        if (frames == 0) return;

        final int frame_count = frames * 2;

        for (int b = 0; b < K_NUM_BANDS; b++) {
            for (int i = 0; i < frame_count; i += 2) {
                double sample_l = samples[i];
                double sample_r = samples[i + 1];

                if (b == 0) {
                    sample_l = lowpass_la_[0].processSample((float) sample_l);
                    sample_l = lowpass_lb_[0].processSample((float) sample_l);
                    sample_r = lowpass_ra_[0].processSample((float) sample_r);
                    sample_r = lowpass_rb_[0].processSample((float) sample_r);
                } else if (b == 2) {
                    sample_l = highpass_la_[1].processSample((float) sample_l);
                    sample_l = highpass_lb_[1].processSample((float) sample_l);
                    sample_r = highpass_ra_[1].processSample((float) sample_r);
                    sample_r = highpass_rb_[1].processSample((float) sample_r);
                } else {
                    sample_l = highpass_la_[0].processSample((float) sample_l);
                    sample_l = highpass_lb_[0].processSample((float) sample_l);
                    sample_l = lowpass_la_[1].processSample((float) sample_l);
                    sample_l = lowpass_lb_[1].processSample((float) sample_l);
                    sample_r = highpass_ra_[0].processSample((float) sample_r);
                    sample_r = highpass_rb_[0].processSample((float) sample_r);
                    sample_r = lowpass_ra_[1].processSample((float) sample_r);
                    sample_r = lowpass_rb_[1].processSample((float) sample_r);
                }

                final float f_l = (float) sample_l;
                final float f_r = (float) sample_r;

                final float mid = (f_l + f_r) * 0.5f;
                float side = (f_l - f_r) * 0.5f;
                side *= band_widths_[b];

                band_buffers_[b][i] = mid + side;
                band_buffers_[b][i + 1] = mid - side;
            }
        }

        for (int i = 0; i < frame_count; i += 2) {
            float sum_l = 0.0f;
            float sum_r = 0.0f;
            for (int b = 0; b < K_NUM_BANDS; b++) {
                sum_l += band_buffers_[b][i];
                sum_r += band_buffers_[b][i + 1];
            }
            samples[i] = sum_l;
            samples[i + 1] = sum_r;
        }
    }

    public void reset() {
        for (int i = 0; i < K_NUM_CROSSOVERS; i++) {
            lowpass_la_[i].reset();
            lowpass_lb_[i].reset();
            lowpass_ra_[i].reset();
            lowpass_rb_[i].reset();
            highpass_la_[i].reset();
            highpass_lb_[i].reset();
            highpass_ra_[i].reset();
            highpass_rb_[i].reset();
        }
        configureCrossovers();
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (enable) reset();
            enable_ = enable;
        }
    }

    public void setLowWidth(float value) {
        band_widths_[0] = value / 100.0f;
    }

    public void setMidWidth(float value) {
        band_widths_[1] = value / 100.0f;
    }

    public void setHighWidth(float value) {
        band_widths_[2] = value / 100.0f;
    }

    public void setLowCrossover(float value) {
        if (crossover_freqs_[0] != value) {
            crossover_freqs_[0] = value;
            configureCrossovers();
        }
    }

    public void setHighCrossover(float value) {
        if (crossover_freqs_[1] != value) {
            crossover_freqs_[1] = value;
            configureCrossovers();
        }
    }

    public void setSampleRate(int sampling_rate) {
        if (sampling_rate_ != sampling_rate) {
            sampling_rate_ = sampling_rate;
            configureCrossovers();
        }
    }

    private void configureCrossovers() {
        for (int i = 0; i < K_NUM_CROSSOVERS; i++) {
            lowpass_la_[i].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS,
                    1.0f,
                    crossover_freqs_[i],
                    sampling_rate_,
                    K_BUTTERWORTH_Q,
                    false
            );
            lowpass_lb_[i].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS,
                    1.0f,
                    crossover_freqs_[i],
                    sampling_rate_,
                    K_BUTTERWORTH_Q,
                    false
            );
            lowpass_ra_[i].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS,
                    1.0f,
                    crossover_freqs_[i],
                    sampling_rate_,
                    K_BUTTERWORTH_Q,
                    false
            );
            lowpass_rb_[i].refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS,
                    1.0f,
                    crossover_freqs_[i],
                    sampling_rate_,
                    K_BUTTERWORTH_Q,
                    false
            );
            highpass_la_[i].refreshFilter(
                    MultiBiquad.FilterType.HIGH_PASS,
                    1.0f,
                    crossover_freqs_[i],
                    sampling_rate_,
                    K_BUTTERWORTH_Q,
                    false
            );
            highpass_lb_[i].refreshFilter(
                    MultiBiquad.FilterType.HIGH_PASS,
                    1.0f,
                    crossover_freqs_[i],
                    sampling_rate_,
                    K_BUTTERWORTH_Q,
                    false
            );
            highpass_ra_[i].refreshFilter(
                    MultiBiquad.FilterType.HIGH_PASS,
                    1.0f,
                    crossover_freqs_[i],
                    sampling_rate_,
                    K_BUTTERWORTH_Q,
                    false
            );
            highpass_rb_[i].refreshFilter(
                    MultiBiquad.FilterType.HIGH_PASS,
                    1.0f,
                    crossover_freqs_[i],
                    sampling_rate_,
                    K_BUTTERWORTH_Q,
                    false
            );
        }
    }
}
