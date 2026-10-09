// 逐行移植自 ViPERDSP/viper/utils/PConvSingle.{h,cpp}（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
// 上游以 pffft 实现分段重叠保留（overlap-save）复数卷积；本文件以 Fft 替代 pffft（仅 PFFFT_REAL 路径）。
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class PConvSingle {

    private boolean instanceUsable_;

    private int segmentCount_;
    private int segmentSize_;
    private int fftSize_;
    private int delayLineIndex_;

    private Fft fftSetup_;
    private float[] fftWork_;
    private float[][] filterSegments_;
    private float[][] inputHistory_;
    private float[] overlapBuffer_;
    private float[] fftBuffer_;
    private float[] accumBuffer_;
    private float[] monoBuffer_;

    public PConvSingle() {
        instanceUsable_ = false;
        segmentCount_ = 0;
        segmentSize_ = 0;
        fftSize_ = 0;
        delayLineIndex_ = 0;
        fftSetup_ = null;
        fftWork_ = null;
        filterSegments_ = null;
        inputHistory_ = null;
        overlapBuffer_ = null;
        fftBuffer_ = null;
        accumBuffer_ = null;
        monoBuffer_ = null;
    }

    public void reset() {
        if (!instanceUsable_) {
            return;
        }
        for (int i = 0; i < segmentCount_; i++) {
            Arrays.fill(inputHistory_[i], 0.0f);
        }
        Arrays.fill(overlapBuffer_, 0.0f);
        Arrays.fill(fftBuffer_, 0.0f);
        Arrays.fill(accumBuffer_, 0.0f);
        Arrays.fill(monoBuffer_, 0.0f);
        Arrays.fill(fftWork_, 0.0f);
        delayLineIndex_ = 0;
    }

    public int getFFTSize() {
        return segmentSize_ * 2;
    }

    public int getSegmentCount() {
        return segmentCount_;
    }

    public int getSegmentSize() {
        return segmentSize_;
    }

    public boolean instanceUsable() {
        return instanceUsable_;
    }

    public void convolve(float[] buffer) {
        convSegment(buffer, false, 0);
    }

    public void convolveInterleaved(float[] buffer, int channel) {
        convSegment(buffer, true, channel);
    }

    public void convSegment(float[] buffer, boolean interleaved, int channel) {
        if (!instanceUsable_) {
            return;
        }

        float[] input;
        if (interleaved) {
            for (int i = 0; i < segmentSize_; i++) {
                monoBuffer_[i] = buffer[i * 2 + channel];
            }
            input = monoBuffer_;
        } else {
            input = buffer;
        }

        // Overlap-save：在 fft_buffer_ 中拼成 [previous_overlap | current_input]。
        System.arraycopy(overlapBuffer_, 0, fftBuffer_, 0, segmentSize_);
        System.arraycopy(input, 0, fftBuffer_, segmentSize_, segmentSize_);

        // 保存当前输入作为下次重叠。
        System.arraycopy(input, 0, overlapBuffer_, 0, segmentSize_);

        // 正向 FFT 拼合缓冲，写入当前延迟线槽位。
        fftSetup_.transform(fftBuffer_, inputHistory_[delayLineIndex_], fftWork_, Fft.FORWARD);

        // 频域按段乘累加。
        Arrays.fill(accumBuffer_, 0.0f);
        for (int k = 0; k < segmentCount_; k++) {
            final int idx = (delayLineIndex_ - k + segmentCount_) % segmentCount_;
            fftSetup_.zconvolveAccumulate(
                    inputHistory_[idx], filterSegments_[k], accumBuffer_, 1.0f);
        }

        // 逆 FFT。
        fftSetup_.transform(accumBuffer_, fftBuffer_, fftWork_, Fft.BACKWARD);

        // 按 1/N 缩放（pffft 不做归一化）。
        final float scale = 1.0f / (float) fftSize_;
        for (int i = 0; i < fftSize_; i++) {
            fftBuffer_[i] *= scale;
        }

        // 输出后半段（有效的 overlap-save 区域）。
        final float[] output = fftBuffer_; // 第二半从 segmentSize_ 起
        if (interleaved) {
            for (int i = 0; i < segmentSize_; i++) {
                buffer[i * 2 + channel] = output[i + segmentSize_];
            }
        } else {
            System.arraycopy(output, segmentSize_, buffer, 0, segmentSize_);
        }

        // 推进延迟线环形缓冲。
        delayLineIndex_ = (delayLineIndex_ + 1) % segmentCount_;
    }

    public int loadKernel(float[] kernel, int kernelSize, int segmentSize) {
        if (kernel != null && kernelSize >= 2 && segmentSize >= 2
                && (segmentSize & (segmentSize - 1)) == 0) {
            instanceUsable_ = false;
            releaseResources();
            segmentSize_ = segmentSize;
            final int n = processKernel(kernel, kernelSize);
            if (n != 0) {
                instanceUsable_ = true;
                return n;
            }
            releaseResources();
        }
        return 0;
    }

    public int loadKernel(float[] kernel, float gain, int kernelSize, int segmentSize) {
        if (kernel != null && kernelSize >= 2 && segmentSize >= 2
                && (segmentSize & (segmentSize - 1)) == 0) {
            instanceUsable_ = false;
            releaseResources();
            segmentSize_ = segmentSize;
            final int n = processKernel(kernel, gain, kernelSize);
            if (n != 0) {
                instanceUsable_ = true;
                return n;
            }
            releaseResources();
        }
        return 0;
    }

    public int processKernel(float[] kernel, int kernelSize) {
        fftSize_ = segmentSize_ * 2;
        segmentCount_ = (kernelSize + segmentSize_ - 1) / segmentSize_;

        fftSetup_ = new Fft(fftSize_);
        if (fftSetup_ == null) {
            return 0;
        }

        fftWork_ = new float[fftSize_];
        fftBuffer_ = new float[fftSize_];
        accumBuffer_ = new float[fftSize_];
        overlapBuffer_ = new float[segmentSize_];
        monoBuffer_ = new float[segmentSize_];

        if (fftWork_ == null || fftBuffer_ == null || accumBuffer_ == null
                || overlapBuffer_ == null || monoBuffer_ == null) {
            return 0;
        }

        Arrays.fill(overlapBuffer_, 0.0f);
        Arrays.fill(monoBuffer_, 0.0f);

        filterSegments_ = new float[segmentCount_][];
        inputHistory_ = new float[segmentCount_][];
        for (int i = 0; i < segmentCount_; i++) {
            filterSegments_[i] = new float[fftSize_];
            inputHistory_[i] = new float[fftSize_];
            Arrays.fill(inputHistory_[i], 0.0f);
        }

        // 将核分段、零填充到 fft_size_ 并做正向 FFT。
        for (int i = 0; i < segmentCount_; i++) {
            Arrays.fill(fftBuffer_, 0.0f);
            final int offset = i * segmentSize_;
            int remaining = kernelSize - offset;
            final int count = remaining < segmentSize_ ? remaining : segmentSize_;
            System.arraycopy(kernel, offset, fftBuffer_, 0, count);
            fftSetup_.transform(fftBuffer_, filterSegments_[i], fftWork_, Fft.FORWARD);
        }

        delayLineIndex_ = 0;
        return segmentCount_;
    }

    public int processKernel(float[] kernel, float gain, int kernelSize) {
        // 处理前先按增益缩放核。
        final float[] scaled = new float[kernelSize];
        if (scaled == null) {
            return 0;
        }
        for (int i = 0; i < kernelSize; i++) {
            scaled[i] = kernel[i] * gain;
        }
        final int result = processKernel(scaled, kernelSize);
        return result;
    }

    public void releaseResources() {
        if (filterSegments_ != null) {
            for (int i = 0; i < segmentCount_; i++) {
                filterSegments_[i] = null;
            }
            filterSegments_ = null;
        }

        if (inputHistory_ != null) {
            for (int i = 0; i < segmentCount_; i++) {
                inputHistory_[i] = null;
            }
            inputHistory_ = null;
        }

        overlapBuffer_ = null;
        fftBuffer_ = null;
        accumBuffer_ = null;
        monoBuffer_ = null;
        fftWork_ = null;
        fftSetup_ = null;

        instanceUsable_ = false;
        segmentCount_ = 0;
        segmentSize_ = 0;
        fftSize_ = 0;
        delayLineIndex_ = 0;
    }

    public void unloadKernel() {
        instanceUsable_ = false;
        releaseResources();
    }
}
