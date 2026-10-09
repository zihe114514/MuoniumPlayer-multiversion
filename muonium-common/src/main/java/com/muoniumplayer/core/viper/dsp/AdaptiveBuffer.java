// 逐行移植自 ViPERDSP/viper/utils/AdaptiveBuffer（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class AdaptiveBuffer {
    private int length_;
    private int offset_;
    private int channels_;

    private float[] buffer_;

    public AdaptiveBuffer(int channels, int length) {
        length_ = length;
        offset_ = 0;
        channels_ = channels;
        buffer_ = new float[channels * length];
    }

    public int getBufferLength() {
        return length_;
    }

    public int getBufferOffset() {
        return offset_;
    }

    public float[] getBuffer() {
        return buffer_;
    }

    public int getChannels() {
        return channels_;
    }

    public void setBufferOffset(int value) {
        offset_ = value;
    }

    public void panFrames(float left, float right) {
        if (channels_ == 2) {
            for (int i = 0; i < offset_ * channels_; i++) {
                if (i % 2 == 0) {
                    buffer_[i] = buffer_[i] * left;
                } else {
                    buffer_[i] = buffer_[i] * right;
                }
            }
        }
    }

    public int popFrames(float[] frames, int length) {
        if (offset_ < length) {
            return 0;
        }

        if (length != 0) {
            System.arraycopy(buffer_, 0, frames, 0, length * channels_);
            offset_ = offset_ - length;
            if (offset_ != 0) {
                System.arraycopy(buffer_, length * channels_, buffer_, 0, offset_ * channels_);
            }
        }

        return 1;
    }

    public int pushFrames(float[] frames, int length) {
        if (length != 0) {
            if (offset_ + length > length_) {
                buffer_ = Arrays.copyOf(buffer_, (offset_ + length) * channels_);
                length_ = offset_ + length;
            }

            System.arraycopy(frames, 0, buffer_, offset_ * channels_, length * channels_);
            offset_ = offset_ + length;
        }

        return 1;
    }

    public void scaleFrames(float scale) {
        for (int i = 0; i < offset_ * channels_; i++) {
            buffer_[i] = buffer_[i] * scale;
        }
    }

    public int pushZero(int length) {
        if (offset_ + length > length_) {
            buffer_ = Arrays.copyOf(buffer_, (offset_ + length) * channels_);
            length_ = offset_ + length;
        }

        Arrays.fill(buffer_, offset_ * channels_, offset_ * channels_ + length * channels_, 0.0f);
        offset_ = offset_ + length;

        return 1;
    }

    public void flushBuffer() {
        offset_ = 0;
        Arrays.fill(buffer_, 0.0f);
    }
}
