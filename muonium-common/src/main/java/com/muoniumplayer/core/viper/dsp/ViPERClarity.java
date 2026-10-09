// 逐行移植自 ViPERDSP/viper/effects/ViPERClarity（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class ViPERClarity {
    public enum ClarityMode {
        NATURAL,
        OZONE,
        XHIFI,
    }

    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private boolean enable_;

    private ClarityMode process_mode_;

    private int sampling_rate_;

    private float gain_;

    private final NoiseSharpening noise_sharpening_;
    private final HighShelf[] high_shelf_ = new HighShelf[2];
    private final HiFi hifi_;

    public ViPERClarity() {
        enable_ = false;
        process_mode_ = ClarityMode.NATURAL;
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        gain_ = 0.0f;
        high_shelf_[0] = new HighShelf();
        high_shelf_[1] = new HighShelf();
        noise_sharpening_ = new NoiseSharpening();
        hifi_ = new HiFi();
        high_shelf_[0].setFrequency(12000.0f);
        high_shelf_[1].setFrequency(12000.0f);
        high_shelf_[0].setGain(1.0f);
        high_shelf_[1].setGain(1.0f);
        high_shelf_[0].setSampleRate(VIPER_DEFAULT_SAMPLING_RATE);
        high_shelf_[1].setSampleRate(VIPER_DEFAULT_SAMPLING_RATE);
        reset();
    }

    public void process(float[] samples, int frames) {
        if (!enable_) return;

        switch (process_mode_) {
            case NATURAL: {
                noise_sharpening_.process(samples, frames);
                break;
            }
            case OZONE: {
                for (int i = 0; i < frames * 2; i += 2) {
                    samples[i] = high_shelf_[0].process(samples[i]);
                    samples[i + 1] = high_shelf_[1].process(samples[i + 1]);
                }
                break;
            }
            case XHIFI: {
                hifi_.process(samples, frames);
                break;
            }
        }
    }

    public void reset() {
        noise_sharpening_.setSampleRate(sampling_rate_);
        noise_sharpening_.reset();
        setClarityToFilter();
        high_shelf_[0].setFrequency(8250.0f);
        high_shelf_[1].setFrequency(8250.0f);
        high_shelf_[0].setSampleRate(sampling_rate_);
        high_shelf_[1].setSampleRate(sampling_rate_);
        hifi_.setSampleRate(sampling_rate_);
        hifi_.reset();
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (enable) {
                reset();
            }
            enable_ = enable;
        }
    }

    public void setProcessMode(ClarityMode mode) {
        if (process_mode_ != mode) {
            process_mode_ = mode;
            reset();
        }
    }

    public void setClarityGain(float value) {
        gain_ = value;
        if (process_mode_ == ClarityMode.OZONE) {
            reset();
        } else {
            setClarityToFilter();
        }
    }

    public void setClarityToFilter() {
        noise_sharpening_.setGain(gain_);
        high_shelf_[0].setGain(gain_ + 1.0f);
        high_shelf_[1].setGain(gain_ + 1.0f);
        hifi_.setClarity(gain_ + 1.0f);
    }

    public void setSampleRate(int sampling_rate) {
        if (sampling_rate_ != sampling_rate) {
            sampling_rate_ = sampling_rate;
            reset();
        }
    }
}
