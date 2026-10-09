// 逐行移植自 ViPERDSP/viper/utils/MultiBiquad（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class MultiBiquad {
    public enum FilterType {
        LOW_PASS,
        HIGH_PASS,
        BAND_PASS,
        BAND_STOP,
        ALL_PASS,
        PEAK,
        LOW_SHELF,
        HIGH_SHELF
    }

    private static final double PI = Math.PI;

    // 上游字段均为 double
    private double x1_;
    private double x2_;
    private double y1_;
    private double y2_;
    private double a1_;
    private double a2_;
    private double b0_;
    private double b1_;
    private double b2_;

    public MultiBiquad() {
        reset();
    }

    // 返回类型保持 float 以兼容现有调用方（Subwoofer 等）
    public float processSample(float sample) {
        final double out = sample * b0_ + x1_ * b1_ + x2_ * b2_ + y1_ * a1_ + y2_ * a2_;

        x2_ = x1_;
        x1_ = sample;
        y2_ = y1_;
        y1_ = out;

        return (float) out;
    }

    public void reset() {
        a1_ = 0.0;
        a2_ = 0.0;
        b0_ = 0.0;
        b1_ = 0.0;
        b2_ = 0.0;
        x1_ = 0.0;
        x2_ = 0.0;
        y1_ = 0.0;
        y2_ = 0.0;
    }

    public void refreshFilter(
            FilterType type,
            float gain_amp,
            float frequency,
            int sampling_rate,
            float q_factor,
            boolean is_bandwidth) {
        double gain;

        if (type == FilterType.PEAK || type == FilterType.LOW_SHELF || type == FilterType.HIGH_SHELF) {
            gain = Math.pow(10.0, gain_amp / 40.0);
        } else {
            gain = Math.pow(10.0, gain_amp / 20.0);
        }

        final double omega = 2.0 * PI * frequency / sampling_rate;
        final double sin_omega = Math.sin(omega);
        final double cos_omega = Math.cos(omega);

        double y;
        double z;

        if (type == FilterType.LOW_SHELF || type == FilterType.HIGH_SHELF) {
            y = sin_omega / 2.0
                    * Math.sqrt((1.0 / gain + gain) * (1.0 / q_factor - 1.0) + 2.0);
            z = Math.sqrt(gain) * 2.0 * y;
        } else if (is_bandwidth) {
            y = Math.sinh(q_factor * Math.log(2.0) * omega / 2.0 / sin_omega)
                    * sin_omega;
            z = -1.0;
        } else {
            y = sin_omega / (q_factor + q_factor);
            z = -1.0;
        }

        double a0 = 0;
        double a1 = 0;
        double a2 = 0;
        double b0 = 0;
        double b1 = 0;
        double b2 = 0;

        switch (type) {
            case LOW_PASS: {
                a0 = 1.0 + y;
                a1 = -2.0 * cos_omega;
                a2 = 1.0 - y;
                b0 = (1.0 - cos_omega) / 2.0;
                b1 = 1.0 - cos_omega;
                b2 = (1.0 - cos_omega) / 2.0;
                break;
            }
            case HIGH_PASS: {
                a0 = 1.0 + y;
                a1 = -2.0 * cos_omega;
                a2 = 1.0 - y;
                b0 = (1.0 + cos_omega) / 2.0;
                b1 = -(1.0 + cos_omega);
                b2 = (1.0 + cos_omega) / 2.0;
                break;
            }
            case BAND_PASS: {
                a0 = 1.0 + y;
                a1 = -2.0 * cos_omega;
                a2 = 1.0 - y;
                b0 = y;
                b1 = 0.0;
                b2 = -y;
                break;
            }
            case BAND_STOP: {
                a0 = 1.0 + y;
                a1 = -2.0 * cos_omega;
                a2 = 1.0 - y;
                b0 = 1.0;
                b1 = -2.0 * cos_omega;
                b2 = 1.0;
                break;
            }
            case ALL_PASS: {
                a0 = 1.0 + y;
                a1 = -2.0 * cos_omega;
                a2 = 1.0 - y;
                b0 = 1.0 - y;
                b1 = -2.0 * cos_omega;
                b2 = 1.0 + y;
                break;
            }
            case PEAK: {
                a0 = 1.0 + y / gain;
                a1 = -2.0 * cos_omega;
                a2 = 1.0 - y / gain;
                b0 = 1.0 + y * gain;
                b1 = -2.0 * cos_omega;
                b2 = 1.0 - y * gain;
                break;
            }
            case LOW_SHELF: {
                final double tmp1 = gain + 1.0 - (gain - 1.0) * cos_omega;
                final double tmp2 = gain + 1.0 + (gain - 1.0) * cos_omega;
                a1 = (gain - 1.0 + (gain + 1.0) * cos_omega) * -2.0;
                a2 = tmp2 - z;
                b1 = gain * 2.0 * (gain - 1.0 - (gain + 1.0) * cos_omega);
                a0 = tmp2 + z;
                b0 = (tmp1 + z) * gain;
                b2 = (tmp1 - z) * gain;
                break;
            }
            case HIGH_SHELF: {
                final double tmp1 = gain + 1.0 + (gain - 1.0) * cos_omega;
                final double tmp2 = gain + 1.0 - (gain - 1.0) * cos_omega;
                a2 = tmp2 - z;
                a0 = tmp2 + z;
                a1 = (gain - 1.0 - (gain + 1.0) * cos_omega) * 2.0;
                b1 = gain * -2.0 * (gain - 1.0 + (gain + 1.0) * cos_omega);
                b0 = (tmp1 + z) * gain;
                b2 = (tmp1 - z) * gain;
                break;
            }
        }

        a1_ = -(a1 / a0);
        a2_ = -(a2 / a0);
        b0_ = b0 / a0;
        b1_ = b1 / a0;
        b2_ = b2 / a0;
    }
}
