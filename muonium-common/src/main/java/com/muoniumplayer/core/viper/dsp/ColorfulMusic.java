// 逐行移植自 ViPERDSP/viper/effects/ColorfulMusic.cpp 与 ColorfulMusic.h（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class ColorfulMusic {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private boolean enabled_;
    private int sampling_rate_;
    private final Stereo3DSurround stereo_3d_surround_ = new Stereo3DSurround();
    private final DepthSurround depth_surround_ = new DepthSurround();

    public ColorfulMusic() {
        enabled_ = false;
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        stereo_3d_surround_.setStereoWiden(0.0f);
        depth_surround_.setSampleRate(sampling_rate_);
        depth_surround_.setStrength(0);
    }

    public void process(float[] samples, int frames) {
        if (!enabled_) return;

        depth_surround_.process(samples, frames);
        stereo_3d_surround_.process(samples, frames);
    }

    public void reset() {
        depth_surround_.setSampleRate(sampling_rate_);
    }

    public void setEnable(boolean enable) {
        if (enabled_ != enable) {
            if (!enabled_) {
                reset();
            }
            enabled_ = enable;
        }
    }

    public void setDepthValue(int value) {
        depth_surround_.setStrength(value);
    }

    public void setMidImageValue(float value) {
        stereo_3d_surround_.setMiddleImage(value);
    }

    public void setWidenValue(float value) {
        stereo_3d_surround_.setStereoWiden(value);
    }

    public void setSampleRate(int sampling_rate) {
        if (sampling_rate_ != sampling_rate) {
            sampling_rate_ = sampling_rate;
            depth_surround_.setSampleRate(sampling_rate_);
        }
    }
}
