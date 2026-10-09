// 逐行移植自 ViPERDSP/viper/utils/Polyphase（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
package com.muoniumplayer.core.viper.dsp;

public final class Polyphase {
    private static final int VIPER_DEFAULT_SAMPLING_RATE = 44100;

    private static final float[] K_POLYPHASE_COEFFICIENTS2 = {
            -0.002339f, -0.002073f, -0.001940f, -0.001675f, -0.001515f, -0.001329f, -0.001223f,
            -0.001037f, -0.000904f, -0.000851f, -0.000532f, -0.000851f, -0.000106f, -0.001010f,
            0.000558f,  -0.001435f, 0.001302f,  -0.001967f, 0.002259f,  -0.002605f, 0.003216f,
            -0.003562f, 0.004784f,  -0.005475f, 0.007655f,  -0.008506f, 0.017622f,  -0.024639f,
            0.028679f,  -0.017303f, -0.032507f, 0.623321f,  0.184702f,  -0.166867f, 0.025729f,
            -0.078490f, -0.015735f, -0.041199f, -0.023151f, -0.031524f, -0.020121f, -0.024985f,
            -0.017303f, -0.019616f, -0.015018f, -0.015204f, -0.012838f, -0.011881f, -0.010951f,
            -0.009516f, -0.009090f, -0.007788f, -0.007442f, -0.006353f, -0.006087f, -0.005183f,
            -0.004970f, -0.004253f, -0.003987f, -0.003482f, -0.003216f, -0.002871f, -0.002578f
    };

    private static final float[] K_POLYPHASE_COEFFICIENTS_OTHER = {
            -0.014194f, -0.002339f, -0.006220f, -0.019722f, -0.020626f, -0.014885f, -0.012240f,
            -0.012386f, -0.011801f, -0.011376f, -0.016293f, -0.018845f, -0.018327f, -0.013902f,
            -0.014951f, -0.015895f, -0.019044f, -0.017928f, -0.020094f, -0.017715f, -0.018845f,
            -0.015377f, -0.018354f, -0.016665f, -0.018951f, -0.011416f, -0.019469f, -0.017250f,
            0.003549f,  -0.076045f, 0.288350f,  0.267751f,  -0.041212f, -0.005130f, -0.088418f,
            -0.089348f, -0.087686f, -0.065625f, -0.041305f, -0.013343f, 0.001422f,  0.010313f,
            0.005834f,  -0.001170f, -0.014499f, -0.021822f, -0.030792f, -0.029331f, -0.031071f,
            -0.018407f, -0.027271f, -0.008373f, -0.010791f, -0.040680f, 0.229171f,  0.080324f,
            -0.070955f, 0.021689f,  -0.046607f, -0.025011f, -0.026886f, -0.027271f, -0.032919f
    };

    private int sampling_rate_;
    private int latency_;

    private final float[] buffer_ = new float[0x7e0];

    private final FIR fir1_ = new FIR();
    private final FIR fir2_ = new FIR();
    private WaveBuffer wave_buffer1_;
    private WaveBuffer wave_buffer2_;

    public Polyphase(int coeff_type) {
        sampling_rate_ = VIPER_DEFAULT_SAMPLING_RATE;
        latency_ = 63;
        wave_buffer1_ = new WaveBuffer(2, 0x1000);
        wave_buffer2_ = new WaveBuffer(2, 0x1000);
        if (coeff_type == 2) {
            fir1_.loadCoefficients(K_POLYPHASE_COEFFICIENTS2, 63, 1008);
            fir2_.loadCoefficients(K_POLYPHASE_COEFFICIENTS2, 63, 1008);
        } else {
            fir1_.loadCoefficients(K_POLYPHASE_COEFFICIENTS_OTHER, 63, 1008);
            fir2_.loadCoefficients(K_POLYPHASE_COEFFICIENTS_OTHER, 63, 1008);
        }
    }

    public int process(float[] samples, int size) {
        if (wave_buffer1_.pushSamples(samples, size) != 0) {
            while (wave_buffer1_.getBufferOffset() >= 1008) {
                if (wave_buffer1_.popSamples(buffer_, 1008, false) == 1008) {
                    fir1_.filterSamplesInterleaved(buffer_, 0, 1008, 2);
                    fir2_.filterSamplesInterleaved(buffer_, 1, 1008, 2);
                    wave_buffer2_.pushSamples(buffer_, 1008);
                }
            }

            if (wave_buffer2_.getBufferOffset() < size) {
                return 0;
            }

            wave_buffer2_.popSamples(samples, size, true);
        }

        return size;
    }

    public void reset() {
        fir1_.reset();
        fir2_.reset();
        wave_buffer1_.reset();
        wave_buffer2_.reset();
    }

    public int getLatency() {
        return latency_;
    }

    public void setSampleRate(int sampling_rate) {
        if (sampling_rate_ != sampling_rate) {
            sampling_rate_ = sampling_rate;
        }
    }
}
