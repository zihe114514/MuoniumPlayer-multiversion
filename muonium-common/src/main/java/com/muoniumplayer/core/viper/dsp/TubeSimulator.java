// 逐行移植自 ViPERDSP/viper/effects/TubeSimulator（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

// 逐行移植自 ViPERDSP/viper/effects/TubeSimulator.h/.cpp
public final class TubeSimulator {
    private boolean enable_;

    private final double[] acc_ = new double[2];

    public TubeSimulator() {
        enable_ = false;
        acc_[0] = 0.0;
        acc_[1] = 0.0;
    }

    public void process(float[] buffer, int frames) {
        if (!enable_) return;

        for (int i = 0; i < frames; i++) {
            acc_[0] = (acc_[0] + buffer[i * 2]) / 2.0;
            acc_[1] = (acc_[1] + buffer[i * 2 + 1]) / 2.0;
            buffer[i * 2] = (float) acc_[0];
            buffer[i * 2 + 1] = (float) acc_[1];
        }
    }

    public void reset() {
        acc_[0] = 0.0;
        acc_[1] = 0.0;
        enable_ = false;
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (!enable_) {
                reset();
            }
            enable_ = enable;
        }
    }
}
