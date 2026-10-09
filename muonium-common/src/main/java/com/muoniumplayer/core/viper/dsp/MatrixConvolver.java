// 逐行移植自 ViPERDSP/viper/utils/MatrixConvolver.{h,cpp}（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
// 上游以 pffft 实现 2 入 4 出的矩阵卷积（含干声延迟、串扰延迟、湿/增益/路由混合）；本文件以 Fft 替代 pffft。
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class MatrixConvolver {

    public enum MatrixRouting {
        DIRECT_AND_CROSS,
        DIRECT_ONLY,
        CROSS_ONLY
    }

    /** 等价替代上游 MatrixConvolverMix 结构。 */
    public static final class MatrixConvolverMix {
        public float cross;
        public float wet;
        public float outputGain;
        public MatrixRouting routing;
        public int crossDelayFrames;
    }

    private static final int K_PATH_COUNT = 4;
    private static final int K_INPUT_CHANNEL_COUNT = 2;
    private static final int K_SEGMENT_SIZE = 1024;
    private static final int K_FFT_SIZE = K_SEGMENT_SIZE * 2;

    private boolean usable_;
    private int segmentCount_;
    private int delayLineIndex_;
    private int inputFill_;
    private int outputIndex_;
    private int dryDelayIndex_;
    private int crossDelayCapacity_;
    private int crossDelayIndex_;

    private Fft fftSetup_;
    private float[] fftWork_;
    private float[][] kernelSpectra_;
    private float[][] inputHistory_;
    private float[][] overlap_;
    private float[][] inputBlock_;
    private float[][] fftInput_;
    private float[][] accum_;
    private float[][] outputBlock_;
    private float[][] dryDelay_;
    private float[][] crossDelay_;

    public MatrixConvolver() {
        usable_ = false;
        segmentCount_ = 0;
        delayLineIndex_ = 0;
        inputFill_ = 0;
        outputIndex_ = K_SEGMENT_SIZE;
        dryDelayIndex_ = 0;
        crossDelayCapacity_ = 0;
        crossDelayIndex_ = 0;
        fftSetup_ = null;
        fftWork_ = null;
        kernelSpectra_ = new float[K_PATH_COUNT][];
        inputHistory_ = new float[K_INPUT_CHANNEL_COUNT][];
        overlap_ = new float[K_INPUT_CHANNEL_COUNT][];
        inputBlock_ = new float[K_INPUT_CHANNEL_COUNT][];
        fftInput_ = new float[K_INPUT_CHANNEL_COUNT][];
        accum_ = new float[K_PATH_COUNT][];
        outputBlock_ = new float[K_PATH_COUNT][];
        dryDelay_ = new float[K_INPUT_CHANNEL_COUNT][];
        crossDelay_ = new float[K_INPUT_CHANNEL_COUNT][];
    }

    public boolean loadInterleaved(
            float[] kernel, int frameCount, int channelCount, int sampleRate) {
        if (kernel == null || frameCount == 0
                || (channelCount != 1 && channelCount != 2 && channelCount != 4)
                || sampleRate < 8000 || sampleRate > 384000) {
            return false;
        }

        final MatrixConvolver candidate = new MatrixConvolver();
        if (!candidate.build(kernel, frameCount, channelCount, sampleRate)) {
            candidate.unload();
            return false;
        }
        swap(candidate);
        return true;
    }

    private boolean build(
            float[] kernel, int frameCount, int channelCount, int sampleRate) {
        final long sampleCount = (long) frameCount * channelCount;
        if (sampleCount / channelCount != frameCount) {
            return false;
        }
        for (long i = 0; i < sampleCount; i++) {
            if (!Float.isFinite(kernel[(int) i])) {
                return false;
            }
        }

        final int kernelFrameCount = frameCount;
        final long segmentCount =
                ((long) kernelFrameCount + K_SEGMENT_SIZE - 1) / K_SEGMENT_SIZE;
        if (segmentCount == 0 || segmentCount > 0xFFFFFFFFL) {
            return false;
        }
        segmentCount_ = (int) segmentCount;
        final long spectraSize = (long) segmentCount_ * K_FFT_SIZE;
        if (spectraSize / K_FFT_SIZE != segmentCount_) {
            return false;
        }
        final long kernelBytes = (long) kernelFrameCount * 4;
        if (kernelBytes / 4 != kernelFrameCount) {
            return false;
        }

        final float[][] timeKernel = new float[K_PATH_COUNT][];
        for (int p = 0; p < K_PATH_COUNT; p++) {
            timeKernel[p] = new float[kernelFrameCount];
        }

        for (int frame = 0; frame < frameCount; frame++) {
            if (channelCount == 4) {
                for (int path = 0; path < K_PATH_COUNT; path++) {
                    timeKernel[path][frame] = kernel[frame * 4 + path];
                }
            } else if (channelCount == 2) {
                final float left = kernel[frame * 2];
                final float right = kernel[frame * 2 + 1];
                timeKernel[0][frame] = left;
                timeKernel[3][frame] = right;
                timeKernel[2][frame] = left;
                timeKernel[1][frame] = right;
            } else {
                final float mono = kernel[frame];
                timeKernel[0][frame] = mono;
                timeKernel[3][frame] = mono;
                timeKernel[1][frame] = mono;
                timeKernel[2][frame] = mono;
            }
        }

        crossDelayCapacity_ = (int) (Math.ceil((double) sampleRate * 0.010)) + 1;

        fftSetup_ = new Fft(K_FFT_SIZE);
        if (fftSetup_ == null) {
            return false;
        }

        fftWork_ = new float[K_FFT_SIZE];
        for (int path = 0; path < K_PATH_COUNT; path++) {
            kernelSpectra_[path] = new float[(int) spectraSize];
            accum_[path] = new float[K_FFT_SIZE];
            outputBlock_[path] = new float[K_SEGMENT_SIZE];
        }
        for (int channel = 0; channel < K_INPUT_CHANNEL_COUNT; channel++) {
            inputHistory_[channel] = new float[(int) spectraSize];
            overlap_[channel] = new float[K_SEGMENT_SIZE];
            inputBlock_[channel] = new float[K_SEGMENT_SIZE];
            fftInput_[channel] = new float[K_FFT_SIZE];
            dryDelay_[channel] = new float[K_SEGMENT_SIZE];
            crossDelay_[channel] = new float[crossDelayCapacity_];
        }

        if (fftWork_ == null) {
            return false;
        }
        for (int path = 0; path < K_PATH_COUNT; path++) {
            if (kernelSpectra_[path] == null || accum_[path] == null
                    || outputBlock_[path] == null) {
                return false;
            }
        }
        for (int channel = 0; channel < K_INPUT_CHANNEL_COUNT; channel++) {
            if (inputHistory_[channel] == null || overlap_[channel] == null
                    || inputBlock_[channel] == null || fftInput_[channel] == null
                    || dryDelay_[channel] == null || crossDelay_[channel] == null) {
                return false;
            }
        }

        Arrays.fill(fftWork_, 0.0f);
        for (int channel = 0; channel < K_INPUT_CHANNEL_COUNT; channel++) {
            Arrays.fill(inputHistory_[channel], 0.0f);
            Arrays.fill(overlap_[channel], 0.0f);
            Arrays.fill(inputBlock_[channel], 0.0f);
            Arrays.fill(fftInput_[channel], 0.0f);
            Arrays.fill(dryDelay_[channel], 0.0f);
            Arrays.fill(crossDelay_[channel], 0.0f);
        }
        for (int path = 0; path < K_PATH_COUNT; path++) {
            Arrays.fill(accum_[path], 0.0f);
            Arrays.fill(outputBlock_[path], 0.0f);
            for (int segment = 0; segment < segmentCount_; segment++) {
                Arrays.fill(fftInput_[0], 0.0f);
                final int offset = segment * K_SEGMENT_SIZE;
                final int remaining = kernelFrameCount - offset;
                final int copyCount = Math.min(remaining, K_SEGMENT_SIZE);
                System.arraycopy(
                        timeKernel[path], offset, fftInput_[0], 0, copyCount);
                // 等价替代 pffft_transform(..., kernelSpectra_[path] + segment*K_FFT_SIZE, ...)。
                // 本实现 transform 从 0 写入，故先写到 fftWork_ 暂存，再拷到段偏移处。
                fftSetup_.transform(fftInput_[0], fftWork_, fftWork_, Fft.FORWARD);
                System.arraycopy(
                        fftWork_, 0, kernelSpectra_[path], segment * K_FFT_SIZE, K_FFT_SIZE);
            }
        }

        usable_ = true;
        reset();
        return true;
    }

    public int process(
            float[] source, float[] destination, int frameCount, final MatrixConvolverMix mix) {
        if (source == null || destination == null || frameCount == 0) {
            return 0;
        }
        if (!usable_) {
            if (source != destination) {
                System.arraycopy(source, 0, destination, 0, frameCount * 2);
            }
            return frameCount;
        }

        final float cross = clampFinite(mix.cross, 0.0F, 1.0F, 0.0F);
        final float wet = clampFinite(mix.wet, 0.0F, 1.0F, 1.0F);
        final float outputGain = clampFinite(mix.outputGain, 0.0F, 15.848932F, 1.0F);
        final MatrixRouting routing =
                mix.routing == MatrixRouting.DIRECT_ONLY || mix.routing == MatrixRouting.CROSS_ONLY
                        ? mix.routing
                        : MatrixRouting.DIRECT_AND_CROSS;
        final float directWeight = routing == MatrixRouting.CROSS_ONLY ? 0.0F : 1.0F;
        final float crossWeight = routing == MatrixRouting.DIRECT_ONLY ? 0.0F : cross;
        final float denominator = directWeight + crossWeight;
        final float branchNorm = denominator > 0.0F ? 1.0F / denominator : 1.0F;
        final int crossDelayFrames = Math.min(mix.crossDelayFrames, crossDelayCapacity_ - 1);

        for (int frame = 0; frame < frameCount; frame++) {
            final float inputLeft = source[frame * 2];
            final float inputRight = source[frame * 2 + 1];

            final float delayedDryLeft = dryDelay_[0][dryDelayIndex_];
            final float delayedDryRight = dryDelay_[1][dryDelayIndex_];
            dryDelay_[0][dryDelayIndex_] = inputLeft;
            dryDelay_[1][dryDelayIndex_] = inputRight;
            dryDelayIndex_ = (dryDelayIndex_ + 1) % K_SEGMENT_SIZE;

            float directLeft = 0.0F;
            float crossRight = 0.0F;
            float crossLeft = 0.0F;
            float directRight = 0.0F;
            if (outputIndex_ < K_SEGMENT_SIZE) {
                directLeft = outputBlock_[0][outputIndex_];
                crossRight = outputBlock_[1][outputIndex_];
                crossLeft = outputBlock_[2][outputIndex_];
                directRight = outputBlock_[3][outputIndex_];
                ++outputIndex_;
            }

            crossDelay_[0][crossDelayIndex_] = crossLeft;
            crossDelay_[1][crossDelayIndex_] = crossRight;
            final int crossReadIndex =
                    (crossDelayIndex_ + crossDelayCapacity_ - crossDelayFrames)
                            % crossDelayCapacity_;
            crossLeft = crossDelay_[0][crossReadIndex];
            crossRight = crossDelay_[1][crossReadIndex];
            crossDelayIndex_ = (crossDelayIndex_ + 1) % crossDelayCapacity_;

            final float wetLeft =
                    (directWeight * directLeft + crossWeight * crossLeft) * branchNorm;
            final float wetRight =
                    (directWeight * directRight + crossWeight * crossRight) * branchNorm;
            destination[frame * 2] =
                    (delayedDryLeft * (1.0F - wet) + wetLeft * wet) * outputGain;
            destination[frame * 2 + 1] =
                    (delayedDryRight * (1.0F - wet) + wetRight * wet) * outputGain;

            inputBlock_[0][inputFill_] = inputLeft;
            inputBlock_[1][inputFill_] = inputRight;
            ++inputFill_;
            if (inputFill_ == K_SEGMENT_SIZE) {
                processBlock();
                inputFill_ = 0;
            }
        }
        return frameCount;
    }

    private void processBlock() {
        for (int channel = 0; channel < K_INPUT_CHANNEL_COUNT; channel++) {
            System.arraycopy(overlap_[channel], 0, fftInput_[channel], 0, K_SEGMENT_SIZE);
            System.arraycopy(
                    inputBlock_[channel], 0, fftInput_[channel], K_SEGMENT_SIZE, K_SEGMENT_SIZE);
            System.arraycopy(inputBlock_[channel], 0, overlap_[channel], 0, K_SEGMENT_SIZE);
            // 等价替代 pffft_transform(..., input_history_[channel] + delay_line_index_*kFftSize, ...)。
            // 本实现 transform 恒从下标 0 写出，故先写到 fftWork_ 暂存，再拷入当前延迟线槽位
            // （与 build() 中处理核频谱段的方式一致）；否则分段数 >1 时后续段会读不到历史频谱。
            fftSetup_.transform(fftInput_[channel], fftWork_, fftWork_, Fft.FORWARD);
            System.arraycopy(
                    fftWork_, 0, inputHistory_[channel], delayLineIndex_ * K_FFT_SIZE, K_FFT_SIZE);
        }

        final float fftScale = 1.0F / (float) K_FFT_SIZE;
        for (int path = 0; path < K_PATH_COUNT; path++) {
            Arrays.fill(accum_[path], 0.0f);
            final int inputChannel = path < 2 ? 0 : 1;
            for (int segment = 0; segment < segmentCount_; segment++) {
                final int historyIndex =
                        (delayLineIndex_ + segmentCount_ - segment) % segmentCount_;
                fftSetup_.zconvolveAccumulate(
                        inputHistory_[inputChannel], historyIndex * K_FFT_SIZE,
                        kernelSpectra_[path], segment * K_FFT_SIZE,
                        accum_[path], 0,
                        fftScale);
            }
            fftSetup_.transform(accum_[path], accum_[path], fftWork_, Fft.BACKWARD);
            System.arraycopy(
                    accum_[path], K_SEGMENT_SIZE, outputBlock_[path], 0, K_SEGMENT_SIZE);
        }

        delayLineIndex_ = (delayLineIndex_ + 1) % segmentCount_;
        outputIndex_ = 0;
    }

    public void reset() {
        if (!usable_) {
            return;
        }
        final long spectraSize = (long) segmentCount_ * K_FFT_SIZE;
        for (int channel = 0; channel < K_INPUT_CHANNEL_COUNT; channel++) {
            Arrays.fill(inputHistory_[channel], 0.0f);
            Arrays.fill(overlap_[channel], 0.0f);
            Arrays.fill(inputBlock_[channel], 0.0f);
            Arrays.fill(fftInput_[channel], 0.0f);
            Arrays.fill(dryDelay_[channel], 0.0f);
            Arrays.fill(crossDelay_[channel], 0.0f);
        }
        for (int path = 0; path < K_PATH_COUNT; path++) {
            Arrays.fill(accum_[path], 0.0f);
            Arrays.fill(outputBlock_[path], 0.0f);
        }
        Arrays.fill(fftWork_, 0.0f);
        delayLineIndex_ = 0;
        inputFill_ = 0;
        outputIndex_ = K_SEGMENT_SIZE;
        dryDelayIndex_ = 0;
        crossDelayIndex_ = 0;
    }

    public void unload() {
        usable_ = false;
        for (int path = 0; path < K_PATH_COUNT; path++) {
            kernelSpectra_[path] = null;
            accum_[path] = null;
            outputBlock_[path] = null;
        }
        for (int channel = 0; channel < K_INPUT_CHANNEL_COUNT; channel++) {
            inputHistory_[channel] = null;
            overlap_[channel] = null;
            inputBlock_[channel] = null;
            fftInput_[channel] = null;
            dryDelay_[channel] = null;
            crossDelay_[channel] = null;
        }
        fftWork_ = null;
        fftSetup_ = null;
        segmentCount_ = 0;
        delayLineIndex_ = 0;
        inputFill_ = 0;
        outputIndex_ = K_SEGMENT_SIZE;
        dryDelayIndex_ = 0;
        crossDelayCapacity_ = 0;
        crossDelayIndex_ = 0;
    }

    private void swap(MatrixConvolver other) {
        boolean tUsable = usable_;
        int tSegmentCount = segmentCount_;
        int tDelayLineIndex = delayLineIndex_;
        int tInputFill = inputFill_;
        int tOutputIndex = outputIndex_;
        int tDryDelayIndex = dryDelayIndex_;
        int tCrossDelayCapacity = crossDelayCapacity_;
        int tCrossDelayIndex = crossDelayIndex_;
        Fft tFftSetup = fftSetup_;
        float[] tFftWork = fftWork_;
        float[][] tKernelSpectra = kernelSpectra_;
        float[][] tInputHistory = inputHistory_;
        float[][] tOverlap = overlap_;
        float[][] tInputBlock = inputBlock_;
        float[][] tFftInput = fftInput_;
        float[][] tAccum = accum_;
        float[][] tOutputBlock = outputBlock_;
        float[][] tDryDelay = dryDelay_;
        float[][] tCrossDelay = crossDelay_;

        usable_ = other.usable_;
        segmentCount_ = other.segmentCount_;
        delayLineIndex_ = other.delayLineIndex_;
        inputFill_ = other.inputFill_;
        outputIndex_ = other.outputIndex_;
        dryDelayIndex_ = other.dryDelayIndex_;
        crossDelayCapacity_ = other.crossDelayCapacity_;
        crossDelayIndex_ = other.crossDelayIndex_;
        fftSetup_ = other.fftSetup_;
        fftWork_ = other.fftWork_;
        kernelSpectra_ = other.kernelSpectra_;
        inputHistory_ = other.inputHistory_;
        overlap_ = other.overlap_;
        inputBlock_ = other.inputBlock_;
        fftInput_ = other.fftInput_;
        accum_ = other.accum_;
        outputBlock_ = other.outputBlock_;
        dryDelay_ = other.dryDelay_;
        crossDelay_ = other.crossDelay_;

        other.usable_ = tUsable;
        other.segmentCount_ = tSegmentCount;
        other.delayLineIndex_ = tDelayLineIndex;
        other.inputFill_ = tInputFill;
        other.outputIndex_ = tOutputIndex;
        other.dryDelayIndex_ = tDryDelayIndex;
        other.crossDelayCapacity_ = tCrossDelayCapacity;
        other.crossDelayIndex_ = tCrossDelayIndex;
        other.fftSetup_ = tFftSetup;
        other.fftWork_ = tFftWork;
        other.kernelSpectra_ = tKernelSpectra;
        other.inputHistory_ = tInputHistory;
        other.overlap_ = tOverlap;
        other.inputBlock_ = tInputBlock;
        other.fftInput_ = tFftInput;
        other.accum_ = tAccum;
        other.outputBlock_ = tOutputBlock;
        other.dryDelay_ = tDryDelay;
        other.crossDelay_ = tCrossDelay;
    }

    /** 等价替代上游 MatrixConvolver.cpp 匿名空间 helper {@code ClampFinite}。 */
    private static float clampFinite(float value, float minimum, float maximum, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.min(Math.max(value, minimum), maximum);
    }

    public boolean isUsable() {
        return usable_;
    }

    public int latencyFrames() {
        return K_SEGMENT_SIZE;
    }
}
