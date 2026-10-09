// 逐行移植自 ViPERDSP/viper/utils/CAllPassFilter（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class CAllPassFilter {
    private int bufferSize_;
    private int bufferIndex_;

    private float feedback_;
    private float[] buffer_;

    public CAllPassFilter() {
        bufferSize_ = 0;
        bufferIndex_ = 0;
        feedback_ = 0.0f;
        buffer_ = null;
    }

    public float process(float sample) {
        final float out = buffer_[bufferIndex_];
        buffer_[bufferIndex_] = sample + out * feedback_;
        bufferIndex_++;
        if (bufferIndex_ >= bufferSize_) {
            bufferIndex_ = 0;
        }
        return out - sample;
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

    public void setFeedback(float value) {
        feedback_ = value;
    }

    public float getFeedback() {
        return feedback_;
    }
}
