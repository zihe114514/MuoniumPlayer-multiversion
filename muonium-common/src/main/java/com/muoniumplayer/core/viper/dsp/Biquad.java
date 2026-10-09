// 逐行移植自 ViPERDSP/viper/utils/Biquad（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class Biquad {
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

    public Biquad() {
        reset();
        setCoeffs(1.0, 0.0, 0.0, 1.0, 0.0, 0.0);
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

    public void setCoeffs(double a0, double a1, double a2, double b0, double b1, double b2) {
        a1_ = -(a1 / a0);
        a2_ = -(a2 / a0);
        b0_ = b0 / a0;
        b1_ = b1 / a0;
        b2_ = b2 / a0;
    }

    public void setBandPassParameter(float frequency, int sampling_rate, float q_factor) {
        final double omega = 2.0 * PI * frequency / sampling_rate;
        final double sin_omega = Math.sin(omega);
        final double cos_omega = Math.cos(omega);

        final double alpha = sin_omega / (q_factor + q_factor);
        final double a0 = alpha + 1.0;
        final double a1 = cos_omega * -2.0;
        final double a2 = 1.0 - alpha;
        final double b0 = sin_omega / 2.0;
        final double b1 = 0.0;
        final double b2 = -(sin_omega / 2.0);

        setCoeffs(a0, a1, a2, b0, b1, b2);
    }

    public void setHighPassParameter(
            float frequency, int sampling_rate, double db_gain, float q_factor) {
        final double omega = 2.0 * PI * frequency / sampling_rate;
        final double sin_omega = Math.sin(omega);
        final double cos_omega = Math.cos(omega);

        final double a = Math.pow(10.0, db_gain / 40.0);
        final double sqrt_a = Math.sqrt(a);

        final double z = sin_omega / 2.0
                * Math.sqrt((1.0 / a + a) * (1.0 / q_factor - 1.0) + 2.0);

        final double a0 = a + 1.0 - (a - 1.0) * cos_omega + sqrt_a * 2.0 * z;
        final double a1 = (a - 1.0 - (a + 1.0) * cos_omega) * 2.0;
        final double a2 = a + 1.0 - (a - 1.0) * cos_omega - sqrt_a * 2.0 * z;
        final double b0 = (a + 1.0 + (a - 1.0) * cos_omega + sqrt_a * 2.0 * z) * a * omega;
        final double b1 = a * -2.0 * (a - 1.0 + (a + 1.0) * cos_omega) * omega;
        final double b2 = (a + 1.0 + (a - 1.0) * cos_omega - sqrt_a * 2.0 * z) * a * omega;

        setCoeffs(a0, a1, a2, b0, b1, b2);
    }

    public void setLowPassParameter(float frequency, int sampling_rate, float q_factor) {
        final double omega = 2.0 * PI * frequency / sampling_rate;
        final double sin_omega = Math.sin(omega);
        final double cos_omega = Math.cos(omega);

        final double alpha = sin_omega / (q_factor + q_factor);

        final double a0 = alpha + 1.0;
        final double a1 = cos_omega * -2.0;
        final double a2 = 1.0 - alpha;
        final double b0 = (1.0 - cos_omega) / 2.0;
        final double b1 = 1.0 - cos_omega;
        final double b2 = (1.0 - cos_omega) / 2.0;

        setCoeffs(a0, a1, a2, b0, b1, b2);
    }
}
