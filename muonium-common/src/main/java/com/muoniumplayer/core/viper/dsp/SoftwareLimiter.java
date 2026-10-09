// 逐行移植自 ViPERDSP/viper/effects/SoftwareLimiter.{h,cpp}（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
// 256 样本前瞻 / 80ms 释放 / 44100 固定系数（与上游一致）；arr512_ 为最大堆树、arr256_ 为延迟线。
package com.muoniumplayer.core.viper.dsp;

public final class SoftwareLimiter {

    /** 上游 constants.h 的 VIPER_DEFAULT_SAMPLING_RATE。 */
    private static final int DEFAULT_SAMPLING_RATE = 44100;

    private static final int K_LOOKAHEAD = 256;
    private static final float K_RELEASE_TAU_SEC = 0.080f;
    private static final float K_DENORM_FIX = 1e-25f;

    /** 上游文件级常量：1 - exp(-1 / (tau * fs))，以 44100 固定计算一次。 */
    private static final float K_RELEASE_COEFF =
            1.0f - (float) Math.exp(-1.0f / (K_RELEASE_TAU_SEC * (float) DEFAULT_SAMPLING_RATE));

    private boolean ready_;

    private int writeIndex_;

    private float gate_;
    private float targetGain_;
    private float gainEnvelope_;
    private float smoothedGain_;
    private final float[] arr256_ = new float[256];
    private final float[] arr512_ = new float[512];

    public SoftwareLimiter() {
        ready_ = false;
        writeIndex_ = 0;
        gate_ = 0.999999f;
        targetGain_ = 1.0f;
        gainEnvelope_ = 1.0f;
        smoothedGain_ = 1.0f;
        reset();
    }

    /** Mirror of {@code float SoftwareLimiter::Process(float sample)}. */
    public float process(float sample) {
        if (!Float.isFinite(sample)) {
            sample = 0.0f;
        }

        final int wi = writeIndex_;

        final float delayed = arr256_[wi];
        final float windowPeak = arr512_[1];

        final float targetGain = windowPeak > gate_ ? gate_ / windowPeak : 1.0f;
        final float released =
                gainEnvelope_ + K_RELEASE_COEFF * (1.0f - gainEnvelope_) + K_DENORM_FIX;
        float gain = targetGain < released ? targetGain : released;
        if (gain > 1.0f) {
            gain = 1.0f;
        }
        gainEnvelope_ = gain;

        int node = K_LOOKAHEAD + wi;
        arr512_[node] = Math.abs(sample);
        while (node > 1) {
            final int parent = node >> 1;
            final int sibling = node ^ 1;
            final float a = arr512_[node];
            final float b = arr512_[sibling];
            arr512_[parent] = a > b ? a : b;
            node = parent;
        }
        arr256_[wi] = sample;
        writeIndex_ = (wi + 1) & (K_LOOKAHEAD - 1);

        return delayed * gain;
    }

    /** Mirror of {@code void SoftwareLimiter::Reset()}. */
    public void reset() {
        java.util.Arrays.fill(arr256_, 0.0f);
        java.util.Arrays.fill(arr512_, 0.0f);
        ready_ = false;
        writeIndex_ = 0;
        gainEnvelope_ = 1.0f;
        smoothedGain_ = 1.0f;
        targetGain_ = 1.0f;
    }

    /** Mirror of {@code void SoftwareLimiter::SetGate(float gate)}. */
    public void setGate(float gate) {
        gate_ = gate;
    }
}
