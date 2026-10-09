// 逐行移植自 ViPERDSP/viper/effects/ViPERBassMono（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class ViPERBassMono {
    public enum ProcessMode {
        NATURAL_BASS,
        PURE_BASS_PLUS,
        SUBWOOFER,
    }

    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private boolean enable_;

    private ProcessMode process_mode_;

    private int sampling_rate_;
    private int frequency_;

    private float sampling_rate_period_;
    private float anti_pop_;
    private float bass_factor_;
    private float bass_factor_smoothed_;
    private float smoothing_coeff_;

    private final Polyphase polyphase_;
    private final Biquad biquad_;
    private final Subwoofer subwoofer_;
    private final WaveBuffer wave_buffer_;

    public ViPERBassMono() {
        enable_ = false;
        process_mode_ = ProcessMode.NATURAL_BASS;
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        frequency_ = 60;
        sampling_rate_period_ = 1.0f / VIPER_DEFAULT_SAMPLING_RATE;
        anti_pop_ = 0.0f;
        bass_factor_ = 0.0f;
        bass_factor_smoothed_ = 0.0f;
        smoothing_coeff_ = 0.0f;
        polyphase_ = new Polyphase(2);
        biquad_ = new Biquad();
        subwoofer_ = new Subwoofer();
        wave_buffer_ = new WaveBuffer(1, 4096);
        biquad_.reset();
        biquad_.setLowPassParameter((float) frequency_, sampling_rate_, 0.53f);
        subwoofer_.setBassGain(sampling_rate_, 0.0f);
        reset();
    }

    public void process(float[] samples, int frames) {
        if (!enable_) return;
        if (frames == 0) return;

        if (anti_pop_ < 1.0f) {
            for (int i = 0; i < frames * 2; i += 2) {
                samples[i] *= anti_pop_;
                samples[i + 1] *= anti_pop_;

                float x = anti_pop_ + sampling_rate_period_;
                if (x > 1.0f) x = 1.0f;
                anti_pop_ = x;
            }
        }

        final float KNEE = 0.5f;

        switch (process_mode_) {
            case NATURAL_BASS: {
                for (int i = 0; i < frames * 2; i += 2) {
                    bass_factor_smoothed_ +=
                            (bass_factor_ - bass_factor_smoothed_) * smoothing_coeff_;
                    final double sample = ((double) samples[i] + (double) samples[i + 1]) / 2.0;
                    final float x = biquad_.processSample((float) sample) * bass_factor_smoothed_;
                    float mixed_l = samples[i] + x;
                    float mixed_r = samples[i + 1] + x;
                    // sat_mix
                    {
                        final float a_l = Math.abs(mixed_l);
                        final float a_r = Math.abs(mixed_r);
                        final float drive = a_l > a_r ? a_l : a_r;
                        if (drive > KNEE) {
                            final float over = drive - KNEE;
                            final float shaped = KNEE + over / (float) Math.sqrt(1.0f + over * over);
                            final float scale = shaped / drive;
                            mixed_l *= scale;
                            mixed_r *= scale;
                        }
                    }
                    samples[i] = mixed_l;
                    samples[i + 1] = mixed_r;
                }
                break;
            }
            case PURE_BASS_PLUS: {
                if (wave_buffer_.pushSamples(samples, frames) != 0) {
                    float[] buffer = wave_buffer_.getBuffer();
                    final int buffer_offset = wave_buffer_.getBufferOffset();

                    for (int i = 0; i < frames * 2; i += 2) {
                        final double sample =
                                ((double) samples[i] + (double) samples[i + 1]) / 2.0;
                        final float x = biquad_.processSample((float) sample);
                        buffer[buffer_offset - frames + i / 2] = x;
                    }

                    if (polyphase_.process(samples, frames) == frames) {
                        for (int i = 0; i < frames * 2; i += 2) {
                            bass_factor_smoothed_ +=
                                    (bass_factor_ - bass_factor_smoothed_) * smoothing_coeff_;
                            final float x = buffer[i / 2] * bass_factor_smoothed_;
                            float mixed_l = samples[i] + x;
                            float mixed_r = samples[i + 1] + x;
                            // sat_mix
                            {
                                final float a_l = Math.abs(mixed_l);
                                final float a_r = Math.abs(mixed_r);
                                final float drive = a_l > a_r ? a_l : a_r;
                                if (drive > KNEE) {
                                    final float over = drive - KNEE;
                                    final float shaped =
                                            KNEE + over / (float) Math.sqrt(1.0f + over * over);
                                    final float scale = shaped / drive;
                                    mixed_l *= scale;
                                    mixed_r *= scale;
                                }
                            }
                            samples[i] = mixed_l;
                            samples[i + 1] = mixed_r;
                        }
                        wave_buffer_.popSamples(frames, true);
                    }
                }
                break;
            }
            case SUBWOOFER: {
                subwoofer_.process(samples, frames);
                break;
            }
        }
    }

    public void reset() {
        polyphase_.setSampleRate(sampling_rate_);
        polyphase_.reset();
        wave_buffer_.reset();
        wave_buffer_.pushZeros(polyphase_.getLatency());
        subwoofer_.setBassGain(sampling_rate_, bass_factor_ * 2.5f);
        biquad_.setLowPassParameter((float) frequency_, sampling_rate_, 0.53f);
        sampling_rate_period_ = 1.0f / (float) sampling_rate_;
        anti_pop_ = 0.0f;
        smoothing_coeff_ =
                1.0f - (float) Math.exp(-1.0f / (0.030f * (float) sampling_rate_));
        bass_factor_smoothed_ = bass_factor_;
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (enable) reset();
            enable_ = enable;
        }
    }

    public void setProcessMode(ProcessMode mode) {
        if (process_mode_ != mode) {
            process_mode_ = mode;
            reset();
        }
    }

    public void setBassFactor(float value) {
        if (bass_factor_ != value) {
            bass_factor_ = value;
            subwoofer_.setBassGain(sampling_rate_, bass_factor_ * 2.5f);
        }
    }

    public void setFrequency(int value) {
        if (frequency_ != value) {
            frequency_ = value;
            biquad_.setLowPassParameter((float) frequency_, sampling_rate_, 0.53f);
        }
    }

    public void setAntiPop(boolean enable) {
        if (enable) {
            anti_pop_ = 0.0f;
        } else {
            anti_pop_ = 1.0f;
        }
    }

    public void setSampleRate(int sampling_rate) {
        if (sampling_rate_ != sampling_rate) {
            sampling_rate_ = sampling_rate;
            sampling_rate_period_ = 1.0f / (float) sampling_rate;
            polyphase_.setSampleRate(sampling_rate);
            biquad_.setLowPassParameter((float) frequency_, sampling_rate_, 0.53f);
            subwoofer_.setBassGain(sampling_rate_, bass_factor_ * 2.5f);
        }
    }
}
