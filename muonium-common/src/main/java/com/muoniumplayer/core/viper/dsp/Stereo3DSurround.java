// 逐行移植自 ViPERDSP/viper/utils/Stereo3DSurround（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class Stereo3DSurround {
    private float stereoWiden_;
    private float middleImage_;
    private float coeffLeft_;
    private float coeffRight_;

    public Stereo3DSurround() {
        stereoWiden_ = 0.0f;
        middleImage_ = 1.0f;
        coeffLeft_ = 0.5f;
        coeffRight_ = 0.5f;
    }

    public void process(float[] samples, int frames) {
        if (frames == 0) {
            return;
        }

        final int pairs = frames / 2;
        final int remainder = frames % 2;

        if (pairs > 0) {
            for (int i = 0; i < pairs; i++) {
                final float a = coeffLeft_ * (samples[4 * i] + samples[4 * i + 1]);
                final float b = coeffRight_ * (samples[4 * i + 1] - samples[4 * i]);
                final float c = coeffLeft_ * (samples[4 * i + 2] + samples[4 * i + 3]);
                final float d = coeffRight_ * (samples[4 * i + 3] - samples[4 * i + 2]);

                samples[4 * i] = a - b;
                samples[4 * i + 1] = a + b;
                samples[4 * i + 2] = c - d;
                samples[4 * i + 3] = c + d;
            }
        }

        if (remainder > 0) {
            for (int i = 4 * pairs; i < 2 * frames; i += 2) {
                final float a = samples[i];
                final float b = samples[i + 1];
                final float c = coeffLeft_ * (a + b);
                final float d = coeffRight_ * (b - a);

                samples[i] = c - d;
                samples[i + 1] = c + d;
            }
        }
    }

    public void setMiddleImage(float value) {
        middleImage_ = value;
        configureVariables();
    }

    public void setStereoWiden(float value) {
        stereoWiden_ = value;
        configureVariables();
    }

    private void configureVariables() {
        final float tmp = stereoWiden_ + 1.0f;

        final float x = tmp + 1.0f;
        final float y;
        if (x < 2.0f) {
            y = 0.5f;
        } else {
            y = 1.0f / x;
        }

        coeffLeft_ = middleImage_ * y;
        coeffRight_ = tmp * y;
    }
}
