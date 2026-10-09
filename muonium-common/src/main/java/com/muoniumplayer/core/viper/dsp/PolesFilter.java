// 逐行移植自 ViPERDSP/viper/utils/PolesFilter（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class PolesFilter {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;
    private static final float K_ANTI_DENORMAL = 1e-25f;

    private int lowerFreq_;
    private int upperFreq_;
    private int samplingRate_;

    private final Channel[] channels_ = new Channel[2];

    private static final class Channel {
        float lowerAngle;
        float upperAngle;

        final float[] in = new float[3];
        final float[] x = new float[4];
        final float[] y = new float[4];
    }

    private final float[] tmpOut_ = new float[3];

    public PolesFilter() {
        lowerFreq_ = 160;
        upperFreq_ = 8000;
        samplingRate_ = VIPER_DEFAULT_SAMPLING_RATE;
        channels_[0] = new Channel();
        channels_[1] = new Channel();
        reset();
    }

    public void filterLeft(float sample, float[] out) {
        filterSide(channels_[0], sample, out);
    }

    public void filterRight(float sample, float[] out) {
        filterSide(channels_[1], sample, out);
    }

    public void reset() {
        for (int c = 0; c < 2; c++) {
            final Channel ch = channels_[c];
            ch.lowerAngle = 0.0f;
            ch.upperAngle = 0.0f;
            for (int i = 0; i < 3; i++) {
                ch.in[i] = 0.0f;
            }
            for (int i = 0; i < 4; i++) {
                ch.x[i] = 0.0f;
                ch.y[i] = 0.0f;
            }
        }
        updateCoeff();
    }

    public void setPassFilter(int lowerFreq, int upperFreq) {
        lowerFreq_ = lowerFreq;
        upperFreq_ = upperFreq;
        updateCoeff();
    }

    public void setSampleRate(int samplingRate) {
        samplingRate_ = samplingRate;
        updateCoeff();
    }

    public void updateCoeff() {
        channels_[0].lowerAngle = (float) lowerFreq_ * (float) Math.PI / (float) samplingRate_;
        channels_[1].lowerAngle = (float) lowerFreq_ * (float) Math.PI / (float) samplingRate_;
        channels_[0].upperAngle = (float) upperFreq_ * (float) Math.PI / (float) samplingRate_;
        channels_[1].upperAngle = (float) upperFreq_ * (float) Math.PI / (float) samplingRate_;
    }

    private static void filterSide(Channel side, float sample, float[] out) {
        final float oldestSampleIn = side.in[2];
        side.in[2] = side.in[1];
        side.in[1] = side.in[0];
        side.in[0] = sample;

        side.x[0] += side.lowerAngle * (sample - side.x[0]) + K_ANTI_DENORMAL;
        side.x[1] += side.lowerAngle * (side.x[0] - side.x[1]) + K_ANTI_DENORMAL;
        side.x[2] += side.lowerAngle * (side.x[1] - side.x[2]) + K_ANTI_DENORMAL;
        side.x[3] += side.lowerAngle * (side.x[2] - side.x[3]) + K_ANTI_DENORMAL;

        side.y[0] += side.upperAngle * (sample - side.y[0]) + K_ANTI_DENORMAL;
        side.y[1] += side.upperAngle * (side.y[0] - side.y[1]) + K_ANTI_DENORMAL;
        side.y[2] += side.upperAngle * (side.y[1] - side.y[2]) + K_ANTI_DENORMAL;
        side.y[3] += side.upperAngle * (side.y[2] - side.y[3]) + K_ANTI_DENORMAL;

        out[0] = side.x[3];
        out[1] = oldestSampleIn - side.y[3];
        out[2] = side.y[3] - side.x[3];
    }
}
