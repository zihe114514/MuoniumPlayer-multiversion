// 逐行移植自 ViPERDSP/viper/utils/Crossfeed（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class Crossfeed {
    public static final class Preset {
        public int cutoff;
        public int feedback;

        public Preset() {}

        public Preset(int cutoff, float feedback) {
            this.cutoff = cutoff;
            this.feedback = (int) feedback;
        }
    }

    private int samplingRate_;

    private float a0Lo_;
    private float b1Lo_;
    private float a0Hi_;
    private float b1Hi_;
    private float a1Hi_;
    private float gain_;

    private final float[] asis_ = new float[2];
    private final float[] lo_ = new float[2];
    private final float[] hi_ = new float[2];

    private Preset preset_;

    public Crossfeed() {
        samplingRate_ = 44100;
        a0Lo_ = 0.0f;
        b1Lo_ = 0.0f;
        a0Hi_ = 0.0f;
        b1Hi_ = 0.0f;
        a1Hi_ = 0.0f;
        gain_ = 0.0f;
        Arrays.fill(asis_, 0.0f);
        Arrays.fill(lo_, 0.0f);
        Arrays.fill(hi_, 0.0f);
        preset_ = new Preset();
        preset_.cutoff = 700;
        preset_.feedback = 45;
    }

    public void process(float[] samples, int frames) {
        for (int i = 0; i < frames * 2; i += 2) {
            filterSample(samples, i);
        }
    }

    public void processFrames(float[] buffer, int size) {
        for (int i = 0; i < size * 2; i += 2) {
            filterSample(buffer, i);
        }
    }

    public void reset() {
        final int cutoff = preset_.cutoff;
        int level = preset_.feedback;

        level /= 10;

        final double gbLo = level * -5.0 / 6.0 - 3.0;
        final double gbHi = level / 6.0 - 3.0;

        final double gLo = Math.pow(10.0, gbLo / 20.0);
        final double gHi = 1.0 - Math.pow(10.0, gbHi / 20.0);
        final double fcHi = cutoff * Math.pow(2.0, (gbLo - 20.0 * Math.log10(gHi)) / 12.0);

        double x = Math.exp(-2.0 * Math.PI * cutoff / samplingRate_);
        b1Lo_ = (float) x;
        a0Lo_ = (float) (gLo * (1.0 - x));

        x = Math.exp(-2.0 * Math.PI * fcHi / samplingRate_);
        b1Hi_ = (float) x;
        a0Hi_ = (float) (1.0 - gHi * (1.0 - x));
        a1Hi_ = (float) (-x);

        gain_ = (float) (1.0 / (1.0 - gHi + gLo));
        Arrays.fill(asis_, 0.0f);
        Arrays.fill(lo_, 0.0f);
        Arrays.fill(hi_, 0.0f);
    }

    public int getCutoff() {
        return preset_.cutoff;
    }

    public float getFeedback() {
        return (float) preset_.feedback / 10.0f;
    }

    public float getLevelDelay() {
        if (preset_.cutoff <= 1800) {
            return 18700.0f / (float) preset_.cutoff * 10.0f;
        }
        return 0.0f;
    }

    public Preset getPreset() {
        return preset_;
    }

    public void setCutoff(int value) {
        preset_.cutoff = value;
        reset();
    }

    public void setFeedback(float value) {
        preset_.feedback = (int) (value * 10.0f);
        reset();
    }

    public void setPreset(Preset preset) {
        preset_ = preset;
        reset();
    }

    public void setSampleRate(int samplingRate) {
        if (samplingRate_ != samplingRate) {
            samplingRate_ = samplingRate;
            reset();
        }
    }

    public void filterSample(float[] sample, int offset) {
        lo_[0] = applyLoFilter(sample[offset], lo_[0]);
        lo_[1] = applyLoFilter(sample[offset + 1], lo_[1]);

        hi_[0] = applyHiFilter(sample[offset], asis_[0], hi_[0]);
        hi_[1] = applyHiFilter(sample[offset + 1], asis_[1], hi_[1]);
        asis_[0] = sample[offset];
        asis_[1] = sample[offset + 1];

        sample[offset] = (hi_[0] + lo_[1]) * gain_;
        sample[offset + 1] = (hi_[1] + lo_[0]) * gain_;
    }

    private float applyLoFilter(float in, float out1) {
        return a0Lo_ * in + b1Lo_ * out1;
    }

    private float applyHiFilter(float in, float in1, float out1) {
        return a0Hi_ * in + a1Hi_ * in1 + b1Hi_ * out1;
    }
}
