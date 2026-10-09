// 逐行移植自 ViPERDSP/viper/effects/DiffSurround.cpp 与 DiffSurround.h（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class DiffSurround {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private boolean enable_;
    private boolean reverse_;

    private int sampling_rate_;

    private float delay_time_;
    private float wet_dry_mix_;
    private float lp_cutoff_;

    private final WaveBuffer[] buffers_ = new WaveBuffer[] {
            new WaveBuffer(1, 0x1000), new WaveBuffer(1, 0x1000)
    };
    private final MultiBiquad lp_filter_ = new MultiBiquad();

    public DiffSurround() {
        enable_ = false;
        reverse_ = false;
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        delay_time_ = 0.0f;
        wet_dry_mix_ = 1.0f;
        lp_cutoff_ = 0.0f;
        reset();
    }

    public void process(float[] samples, int frames) {
        if (!enable_) return;

        final float[] bufs0 = buffers_[0].pushZerosGetBuffer(frames);
        final float[] bufs1 = buffers_[1].pushZerosGetBuffer(frames);

        for (int i = 0; i < frames * 2; i++) {
            if (i % 2 == 0) {
                bufs0[i / 2] = samples[i];
            } else {
                bufs1[i / 2] = samples[i];
            }
        }

        final float[] out0 = buffers_[0].getBuffer();
        final float[] out1 = buffers_[1].getBuffer();

        if (wet_dry_mix_ >= 1.0f && lp_cutoff_ <= 0.0f) {
            for (int i = 0; i < frames * 2; i++) {
                if (i % 2 == 0) {
                    samples[i] = out0[i / 2];
                } else {
                    samples[i] = out1[i / 2];
                }
            }
        } else {
            final int delayed_ch = reverse_ ? 0 : 1;
            final int direct_ch = 1 - delayed_ch;
            final float wet = wet_dry_mix_;
            final float dry = 1.0f - wet;

            for (int i = 0; i < frames; i++) {
                final float direct_sample = (direct_ch == 0 ? out0 : out1)[i];
                float delayed_sample = (delayed_ch == 0 ? out0 : out1)[i];

                if (lp_cutoff_ > 0.0f) {
                    delayed_sample = (float) lp_filter_.processSample(delayed_sample);
                }

                samples[i * 2 + direct_ch] = direct_sample;
                samples[i * 2 + delayed_ch] = dry * direct_sample + wet * delayed_sample;
            }
        }

        buffers_[0].popSamples(frames, false);
        buffers_[1].popSamples(frames, false);
    }

    public void reset() {
        buffers_[0].reset();
        buffers_[1].reset();

        final int delay_samples =
                (int) (delay_time_ / 1000.0 * sampling_rate_);
        buffers_[reverse_ ? 0 : 1].pushZeros(delay_samples);

        lp_filter_.reset();
        if (lp_cutoff_ > 0.0f) {
            lp_filter_.refreshFilter(
                    MultiBiquad.FilterType.LOW_PASS,
                    0.0f,
                    lp_cutoff_,
                    sampling_rate_,
                    0.7071f,
                    false
            );
        }
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (!enable_) {
                reset();
            }
            enable_ = enable;
        }
    }

    public void setDelayTime(float value) {
        if (delay_time_ != value) {
            delay_time_ = value;
            reset();
        }
    }

    public void setReverse(boolean value) {
        if (reverse_ != value) {
            reverse_ = value;
            reset();
        }
    }

    public void setWetDryMix(float value) {
        if (value < 0.0f) value = 0.0f;
        if (value > 1.0f) value = 1.0f;
        wet_dry_mix_ = value;
    }

    public void setLPCutoff(float value) {
        if (value < 0.0f) value = 0.0f;
        if (value > 20000.0f) value = 20000.0f;
        if (lp_cutoff_ != value) {
            lp_cutoff_ = value;
            if (value > 0.0f) {
                lp_filter_.refreshFilter(
                        MultiBiquad.FilterType.LOW_PASS,
                        0.0f,
                        value,
                        sampling_rate_,
                        0.7071f,
                        false
                );
            }
        }
    }

    public void setSampleRate(int sampling_rate) {
        if (sampling_rate_ != sampling_rate) {
            sampling_rate_ = sampling_rate;
            reset();
        }
    }
}
