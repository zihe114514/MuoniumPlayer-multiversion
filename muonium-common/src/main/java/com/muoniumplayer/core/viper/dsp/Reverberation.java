// 逐行移植自 ViPERDSP/viper/effects/Reverberation.cpp 与 Reverberation.h（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class Reverberation {
    private boolean enable_;

    private final CRevModel model_ = new CRevModel();

    public Reverberation() {
        enable_ = false;
        model_.setRoomSize(0.0f);
        model_.setWidth(0.0f);
        model_.setDamp(0.0f);
        model_.setWet(0.0f);
        model_.setDry(0.5f);
        model_.reset();
    }

    public void process(float[] buffer, int frames) {
        if (enable_) {
            // 上游 ProcessReplace(buffer, buffer + 1, size) 传入的是同一交织缓冲上的 L/R 双指针，
            // 其内部按 idx += 2 步进（buf_l[idx]=偶=左、buf_r[idx]=奇=右）。
            // CRevModel.process(samples, frames) 即该交织语义的等价实现。
            model_.process(buffer, frames);
        }
    }

    public void reset() {
        model_.reset();
    }

    public void setEnable(boolean enable) {
        if (enable_ != enable) {
            if (!enable_) {
                reset();
            }
            enable_ = enable;
        }
    }

    public void setDamp(float value) {
        model_.setDamp(value);
    }

    public void setDry(float value) {
        model_.setDry(value);
    }

    public void setRoomSize(float value) {
        model_.setRoomSize(value);
    }

    public void setWet(float value) {
        model_.setWet(value);
    }

    public void setWidth(float value) {
        model_.setWidth(value);
    }
}
