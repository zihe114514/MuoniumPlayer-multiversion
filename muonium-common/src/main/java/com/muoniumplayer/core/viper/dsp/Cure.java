// 逐行移植自 ViPERDSP/viper/effects/Cure.cpp 与 Cure.h（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class Cure {
    private boolean enabled_;

    private final Crossfeed crossfeed_ = new Crossfeed();
    private final PassFilter pass_filter_ = new PassFilter();

    public Cure() {
        enabled_ = false;
        reset();
    }

    public void process(float[] buffer, int frames) {
        if (!enabled_) return;

        crossfeed_.processFrames(buffer, frames);
        pass_filter_.process(buffer, frames);
    }

    public void reset() {
        crossfeed_.reset();
        pass_filter_.reset();
    }

    public void setEnable(boolean enable) {
        if (enabled_ != enable) {
            if (enable) {
                reset();
            }
            enabled_ = enable;
        }
    }

    public void setPreset(int value) {
        switch (value) {
            case 0: {
                final Crossfeed.Preset preset = new Crossfeed.Preset();
                preset.cutoff = 650;
                preset.feedback = 95;
                crossfeed_.setPreset(preset);
                break;
            }
            case 1: {
                final Crossfeed.Preset preset = new Crossfeed.Preset();
                preset.cutoff = 700;
                preset.feedback = 60;
                crossfeed_.setPreset(preset);
                break;
            }
            case 2: {
                final Crossfeed.Preset preset = new Crossfeed.Preset();
                preset.cutoff = 700;
                preset.feedback = 45;
                crossfeed_.setPreset(preset);
                break;
            }
            default:
                break;
        }
    }

    public void setSampleRate(int sampling_rate) {
        crossfeed_.setSampleRate(sampling_rate);
        pass_filter_.setSampleRate(sampling_rate);
    }
}
