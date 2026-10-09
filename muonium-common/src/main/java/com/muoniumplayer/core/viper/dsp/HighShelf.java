// 逐行移植自 ViPERDSP/viper/utils/HighShelf（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class HighShelf {
    private static final double PI = Math.PI;

    // 上游 frequency_ 为 float，其余状态字段均为 double
    private float frequency_;

    private double gain_;
    private double x1_;
    private double x2_;
    private double y1_;
    private double y2_;
    private double b0_;
    private double b1_;
    private double b2_;
    private double a0_;
    private double a1_;
    private double a2_;

    public HighShelf() {
        frequency_ = 0.0f;
        gain_ = 0.0;
        x1_ = 0.0;
        x2_ = 0.0;
        y1_ = 0.0;
        y2_ = 0.0;
        b0_ = 0.0;
        b1_ = 0.0;
        b2_ = 0.0;
        a0_ = 0.0;
        a1_ = 0.0;
        a2_ = 0.0;
    }

    // 返回类型保持 float（上游 double；保持现有接口，内部双精度计算后截断返回）
    public float process(float sample) {
        final double out =
                (x1_ * b1_ + sample * b0_ + b2_ * x2_ - y1_ * a1_ - a2_ * y2_) * a0_;
        y2_ = y1_;
        y1_ = out;
        x2_ = x1_;
        x1_ = sample;
        return (float) out;
    }

    public void setFrequency(float value) {
        frequency_ = value;
    }

    public void setGain(float value) {
        gain_ = 20.0 * Math.log10(value);
    }

    public void setSampleRate(int sampling_rate) {
        final double x = 2.0 * PI * frequency_ / sampling_rate;
        final double sin_x = Math.sin(x);
        final double cos_x = Math.cos(x);
        final double y = Math.exp(gain_ * Math.log(10.0) / 40.0);

        x1_ = 0.0;
        x2_ = 0.0;
        y1_ = 0.0;
        y2_ = 0.0;

        final double z = Math.sqrt(y * 2.0) * sin_x;
        final double a = (y - 1.0) * cos_x;
        final double b = y + 1.0 - a;
        final double c = z + b;
        final double d = (y + 1.0) * cos_x;
        final double e = y + 1.0 + a;
        final double f = y - 1.0 - d;

        a0_ = 1.0 / c;
        a1_ = f * 2.0;
        a2_ = b - z;
        b0_ = (e + z) * y;
        b1_ = -y * 2.0 * (y - 1.0 + d);
        b2_ = (e - z) * y;
    }
}
