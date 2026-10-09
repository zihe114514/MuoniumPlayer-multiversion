// 逐行移植自 ViPERDSP/viper/utils/WaveBuffer（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class WaveBuffer {
    private int index_;
    private int channels_;

    private float[] buffer_;

    public WaveBuffer(int channels, int length) {
        index_ = 0;
        channels_ = channels;
        buffer_ = new float[length * channels];
    }

    public void reset() {
        index_ = 0;
        Arrays.fill(buffer_, 0.0f);
    }

    public int getBufferOffset() {
        return index_ / channels_;
    }

    public int getBufferSize() {
        return buffer_.length / channels_;
    }

    public float[] getBuffer() {
        return buffer_;
    }

    public void setBufferOffset(int offset) {
        final int maxOffset = buffer_.length / channels_;
        if (offset <= maxOffset) {
            index_ = offset * channels_;
        }
    }

    public int popSamples(int size, boolean resetIdx) {
        if (buffer_.length == 0) {
            return 0;
        }

        if (channels_ * size <= index_) {
            index_ -= channels_ * size;
            System.arraycopy(buffer_, channels_ * size, buffer_, 0, index_);
            return size;
        }

        if (resetIdx) {
            final int ret = index_ / channels_;
            index_ = 0;
            return ret;
        }

        return 0;
    }

    public int popSamples(float[] dest, int size, boolean resetIdx) {
        if (buffer_.length == 0 || dest == null) {
            return 0;
        }

        if (channels_ * size <= index_) {
            System.arraycopy(buffer_, 0, dest, 0, channels_ * size);
            index_ -= channels_ * size;
            System.arraycopy(buffer_, channels_ * size, buffer_, 0, index_);
            return size;
        }

        if (resetIdx) {
            final int ret = index_ / channels_;
            System.arraycopy(buffer_, 0, dest, 0, index_);
            index_ = 0;
            return ret;
        }

        return 0;
    }

    public int pushSamples(float[] source, int size) {
        if (size > 0) {
            final int requiredSize = channels_ * size + index_;
            if (requiredSize > buffer_.length) {
                buffer_ = Arrays.copyOf(buffer_, requiredSize);
            }
            System.arraycopy(source, 0, buffer_, index_, channels_ * size);
            index_ += channels_ * size;
        }

        // 上游恒返回 1（int，非 boolean）。
        return 1;
    }

    public int pushZeros(int size) {
        if (size > 0) {
            final int requiredSize = channels_ * size + index_;
            if (requiredSize > buffer_.length) {
                buffer_ = Arrays.copyOf(buffer_, requiredSize);
            }
            Arrays.fill(buffer_, index_, index_ + channels_ * size, 0.0f);
            index_ += channels_ * size;
        }

        return 1;
    }

    public float[] pushZerosGetBuffer(int size) {
        final int oldIdx = index_;

        if (size > 0) {
            final int requiredSize = channels_ * size + index_;
            if (requiredSize > buffer_.length) {
                buffer_ = Arrays.copyOf(buffer_, requiredSize);
            }
            Arrays.fill(buffer_, index_, index_ + channels_ * size, 0.0f);
            index_ += channels_ * size;
        }

        return buffer_;
    }
}
