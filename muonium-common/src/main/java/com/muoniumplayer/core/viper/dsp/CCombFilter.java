// 逐行移植自 ViPERDSP/viper/utils/CCombFilter（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class CCombFilter {
    private int bufferSize_;
    private int bufferIndex_;

    private float feedback_;
    private float filterStore_;
    private float damp_;
    private float damp2_;
    private float[] buffer_;

    public CCombFilter() {
        bufferSize_ = 0;
        bufferIndex_ = 0;
        feedback_ = 0.0f;
        filterStore_ = 0.0f;
        damp_ = 0.0f;
        damp2_ = 0.0f;
        buffer_ = null;
    }

    public float process(float sample) {
        final float out = buffer_[bufferIndex_];
        filterStore_ = out * damp2_ + filterStore_ * damp_;
        buffer_[bufferIndex_] = sample + filterStore_ * feedback_;
        bufferIndex_ = (bufferIndex_ + 1) % bufferSize_;
        return out;
    }

    public void mute() {
        if (buffer_ != null) {
            Arrays.fill(buffer_, 0.0f);
        }
    }

    public void setBuffer(float[] buffer, int size) {
        buffer_ = buffer;
        bufferSize_ = size;
        bufferIndex_ = 0;
    }

    public void setDamp(float value) {
        damp_ = value;
        damp2_ = 1.0f - value;
    }

    public void setFeedback(float value) {
        feedback_ = value;
    }

    public float getDamp() {
        return damp_;
    }

    public float getFeedback() {
        return feedback_;
    }
}
