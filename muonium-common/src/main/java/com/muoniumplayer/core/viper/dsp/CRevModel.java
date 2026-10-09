// 逐行移植自 ViPERDSP/viper/utils/CRevModel（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class CRevModel {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private float gain_;
    private float roomSize_;
    private float internalRoomSize_;
    private float damp_;
    private float internalDamp_;
    private float wet_;
    private float wet1_;
    private float wet2_;
    private float dry_;
    private float width_;

    private final CCombFilter[] combL_ = new CCombFilter[8];
    private final CCombFilter[] combR_ = new CCombFilter[8];

    private final CAllPassFilter[] allpassL_ = new CAllPassFilter[4];
    private final CAllPassFilter[] allpassR_ = new CAllPassFilter[4];

    public CRevModel() {
        final int[] sizes = {
                1116, 1139, 1188, 1211, 1277, 1300, 1356, 1379,
                1422, 1445, 1491, 1514, 1557, 1580, 1617, 1640,
                556, 579, 441, 464, 341, 364, 225, 248
        };

        // 上游把 24 段缓冲交给 comb_l_/comb_r_ 时是按 L/R 交错取用的：
        // comb_l_[i] = buffers_[2i]、comb_r_[i] = buffers_[2i+1]（allpass 同理从 buffers_[16] 起交错）。
        for (int i = 0; i < 8; i++) {
            combL_[i] = new CCombFilter();
            combL_[i].setBuffer(new float[sizes[2 * i]], sizes[2 * i]);
        }
        for (int i = 0; i < 8; i++) {
            combR_[i] = new CCombFilter();
            combR_[i].setBuffer(new float[sizes[2 * i + 1]], sizes[2 * i + 1]);
        }
        for (int i = 0; i < 4; i++) {
            allpassL_[i] = new CAllPassFilter();
            allpassL_[i].setBuffer(new float[sizes[16 + 2 * i]], sizes[16 + 2 * i]);
            allpassL_[i].setFeedback(0.5f);
        }
        for (int i = 0; i < 4; i++) {
            allpassR_[i] = new CAllPassFilter();
            allpassR_[i].setBuffer(new float[sizes[16 + 2 * i + 1]], sizes[16 + 2 * i + 1]);
            allpassR_[i].setFeedback(0.5f);
        }

        setWet(0.167f);
        setRoomSize(0.5f);
        setDry(0.25f);
        setDamp(0.5f);
        setWidth(1.0f);

        reset();
    }

    public void processReplace(float[] bufL, float[] bufR, int size) {
        for (int idx = 0; idx < size * 2; idx += 2) {
            float outL = 0.0f;
            float outR = 0.0f;
            final float input = (bufL[idx] + bufR[idx]) * gain_;

            for (int i = 0; i < 8; i++) {
                outL += combL_[i].process(input);
                outR += combR_[i].process(input);
            }

            for (int i = 0; i < 4; i++) {
                outL = allpassL_[i].process(outL);
                outR = allpassR_[i].process(outR);
            }

            bufL[idx] = outL * wet1_ + outR * wet2_ + bufL[idx] * dry_;
            bufR[idx] = outR * wet1_ + outL * wet2_ + bufR[idx] * dry_;
        }
    }

    public void process(float[] samples, int frames) {
        for (int idx = 0; idx < frames * 2; idx += 2) {
            float outL = 0.0f;
            float outR = 0.0f;
            final float input = (samples[idx] + samples[idx + 1]) * gain_;

            for (int i = 0; i < 8; i++) {
                outL += combL_[i].process(input);
                outR += combR_[i].process(input);
            }

            for (int i = 0; i < 4; i++) {
                outL = allpassL_[i].process(outL);
                outR = allpassR_[i].process(outR);
            }

            samples[idx] = outL * wet1_ + outR * wet2_ + samples[idx] * dry_;
            samples[idx + 1] = outR * wet1_ + outL * wet2_ + samples[idx + 1] * dry_;
        }
    }

    public void mute() {
        for (int i = 0; i < 8; i++) {
            combL_[i].mute();
            combR_[i].mute();
        }

        for (int i = 0; i < 4; i++) {
            allpassL_[i].mute();
            allpassR_[i].mute();
        }
    }

    public void reset() {
        for (int i = 0; i < 8; i++) {
            combL_[i].mute();
            combR_[i].mute();
        }

        for (int i = 0; i < 4; i++) {
            allpassL_[i].mute();
            allpassR_[i].mute();
        }
    }

    public void setRoomSize(float value) {
        roomSize_ = value * 0.28f + 0.7f;
        updateCoeffs();
    }

    public void setDamp(float value) {
        damp_ = value * 0.4f;
        updateCoeffs();
    }

    public void setWet(float value) {
        wet_ = value * 3.0f;
        updateCoeffs();
    }

    public void setDry(float value) {
        dry_ = value * 2.0f;
    }

    public void setWidth(float value) {
        width_ = value;
        updateCoeffs();
    }

    public float getRoomSize() {
        return (roomSize_ - 0.7f) / 0.28f;
    }

    public float getDamp() {
        return damp_ / 0.4f;
    }

    public float getWet() {
        return wet_ / 3.0f;
    }

    public float getDry() {
        return dry_ / 2.0f;
    }

    public float getWidth() {
        return width_;
    }

    public void updateCoeffs() {
        wet1_ = wet_ * (width_ / 2.0f + 0.5f);
        wet2_ = wet_ * (1.0f - width_) / 2.0f;

        internalRoomSize_ = roomSize_;
        internalDamp_ = damp_;
        gain_ = 0.015f;

        for (int i = 0; i < 8; i++) {
            combL_[i].setFeedback(internalRoomSize_);
            combL_[i].setDamp(internalDamp_);
            combR_[i].setFeedback(internalRoomSize_);
            combR_[i].setDamp(internalDamp_);
        }
    }
}
