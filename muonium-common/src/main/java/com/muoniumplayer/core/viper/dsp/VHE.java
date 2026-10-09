package com.muoniumplayer.core.viper.dsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * VHE — Virtual Headphone / Virtual Surround (耳机环绕 / 虚拟声场).
 *
 * Line-by-line Java port of NEURAX-FX/ViPERDSP {@code viper/effects/VHE.{h,cpp}}.
 *
 * Mirror notes vs. upstream:
 *  - Upstream stores the 5x2x2 impulse-response tables as C {@code static const float[]}
 *    arrays compiled into the binary. Here those tables are extracted (see
 *    .workbuddy/tmp/viper/extract_vhe_tables.py) into little-endian float32 resources
 *    under {@code /com/muoniumplayer/viper/vhe/} and loaded at reset() time instead.
 *  - The scalar *gain* constants are not arrays, so they are kept here (mirroring
 *    VHE.cpp) rather than emitted as resources.
 *  - {@code PConvSingle} (the partitioned convolver) and {@code WaveBuffer} are provided
 *    by sibling agents; this class calls them with the exact upstream method names in
 *    first-letter-lowercase Java form. If either class is absent the compile step will
 *    report a "cannot find symbol" — that residual error is expected and tolerated.
 *  - Resource-missing or unsupported (level/rate) paths mirror the upstream "do not load
 *    kernel" behaviour: the convolver stays unusable and {@link #process} bypasses
 *    (returns the input frame count). No exceptions are thrown and {@link #process} does
 *    no allocation and emits no logs.
 */
public final class VHE {

    /** Upstream {@code VIPER_DEFAULT_SAMPLING_RATE}. */
    private static final int DEFAULT_SAMPLING_RATE = 44100;
    /** Upstream {@code conv_size_} / segment size passed to LoadKernel. */
    private static final int CONV_SIZE = 4096;
    /** Resource table directory on the classpath. */
    private static final String RES_DIR = "/com/muoniumplayer/viper/vhe/";

    private boolean enable_;

    private int samplingRate_;
    private int effectLevel_;
    private int convSize_;

    private final PConvSingle convLeft_ = new PConvSingle();
    private final PConvSingle convRight_ = new PConvSingle();
    private final WaveBuffer bufA_;
    private final WaveBuffer bufB_;

    public VHE() {
        enable_ = false;
        samplingRate_ = DEFAULT_SAMPLING_RATE;
        effectLevel_ = 0;
        convSize_ = 0;
        bufA_ = new WaveBuffer(2, 0x1000);
        bufB_ = new WaveBuffer(2, 0x1000);
        reset();
    }

    /**
     * Mirror of {@code uint32_t VHE::Process(const float*, float*, uint32_t)}.
     *
     * @return number of frames written into {@code dest} (equals {@code frameSize} on the
     *         bypass / disabled path).
     */
    public int process(final float[] source, final float[] dest, final int frameSize) {
        if (enable_ && convLeft_.instanceUsable() && convRight_.instanceUsable()) {
            if (bufA_.pushSamples(source, frameSize) != 0) {
                while (bufA_.getBufferOffset() > convSize_) {
                    final float[] buffer = bufA_.getBuffer();
                    convLeft_.convolveInterleaved(buffer, 0);
                    convRight_.convolveInterleaved(buffer, 1);
                    bufB_.pushSamples(buffer, convSize_);
                    bufA_.popSamples(convSize_, true);
                }
                return bufB_.popSamples(dest, frameSize, false);
            }
        }
        return frameSize;
    }

    /** Mirror of {@code void VHE::Reset()}. */
    public void reset() {
        bufA_.reset();
        bufB_.reset();

        convLeft_.reset();
        convLeft_.unloadKernel();
        convRight_.reset();
        convRight_.unloadKernel();

        if (effectLevel_ > 4) {
            // Unsupported effect level: do not load a kernel (mirror upstream LOGD + return).
            return;
        }

        if (samplingRate_ != 44100 && samplingRate_ != 48000) {
            // Unsupported sampling rate: do not load a kernel.
            return;
        }

        final float[] arrLeft;
        final float[] arrRight;
        float gain;

        switch (effectLevel_) {
            case 0: {
                switch (samplingRate_) {
                    case 44100: {
                        arrLeft = loadF32("L0_44100_L.f32");
                        arrRight = loadF32("L0_44100_R.f32");
                        gain = 2.94595f;
                        break;
                    }
                    case 48000: {
                        arrLeft = loadF32("L0_48000_L.f32");
                        arrRight = loadF32("L0_48000_R.f32");
                        gain = 2.94595f;
                        break;
                    }
                    default: {
                        return;
                    }
                }
                break;
            }
            case 1: {
                switch (samplingRate_) {
                    case 44100: {
                        arrLeft = loadF32("L1_44100_L.f32");
                        arrRight = loadF32("L1_44100_R.f32");
                        gain = 0.944061f;
                        break;
                    }
                    case 48000: {
                        arrLeft = loadF32("L1_48000_L.f32");
                        arrRight = loadF32("L1_48000_R.f32");
                        gain = 0.944061f;
                        break;
                    }
                    default: {
                        return;
                    }
                }
                break;
            }
            case 2: {
                switch (samplingRate_) {
                    case 44100: {
                        arrLeft = loadF32("L2_44100_L.f32");
                        arrRight = loadF32("L2_44100_R.f32");
                        gain = 1.544582f;
                        break;
                    }
                    case 48000: {
                        arrLeft = loadF32("L2_48000_L.f32");
                        arrRight = loadF32("L2_48000_R.f32");
                        gain = 1.531516f;
                        break;
                    }
                    default: {
                        return;
                    }
                }
                break;
            }
            case 3: {
                switch (samplingRate_) {
                    case 44100: {
                        arrLeft = loadF32("L3_44100_L.f32");
                        arrRight = loadF32("L3_44100_R.f32");
                        gain = 1.584257f;
                        break;
                    }
                    case 48000: {
                        arrLeft = loadF32("L3_48000_L.f32");
                        arrRight = loadF32("L3_48000_R.f32");
                        gain = 1.567789f;
                        break;
                    }
                    default: {
                        return;
                    }
                }
                break;
            }
            case 4: {
                switch (samplingRate_) {
                    case 44100: {
                        arrLeft = loadF32("L4_44100_L.f32");
                        arrRight = loadF32("L4_44100_R.f32");
                        gain = 1.466681f;
                        break;
                    }
                    case 48000: {
                        arrLeft = loadF32("L4_48000_L.f32");
                        arrRight = loadF32("L4_48000_R.f32");
                        gain = 1.487227f;
                        break;
                    }
                    default: {
                        return;
                    }
                }
                break;
            }
            default: {
                // Unsupported effect level.
                return;
            }
        }

        // If either channel resource is missing/unreadable, mirror the upstream
        // "do not load kernel" path: leave the convolvers unusable (bypass).
        if (arrLeft == null || arrRight == null) {
            return;
        }

        convLeft_.loadKernel(arrLeft, gain, arrLeft.length, CONV_SIZE);
        convRight_.loadKernel(arrRight, gain, arrRight.length, CONV_SIZE);
        convSize_ = CONV_SIZE;
    }

    /** Mirror of {@code bool VHE::GetEnable() const}. */
    public boolean getEnable() {
        return enable_;
    }

    /** Mirror of {@code void VHE::SetEnable(bool)}. */
    public void setEnable(final boolean enable) {
        if (enable_ != enable) {
            enable_ = enable;
            if (enable_) {
                reset();
            }
        }
    }

    /** Mirror of {@code void VHE::SetEffectLevel(uint32_t)}. */
    public void setEffectLevel(final int value) {
        if (effectLevel_ != value) {
            if (value < 5) {
                effectLevel_ = value;
                reset();
            }
        }
    }

    /** Mirror of {@code void VHE::SetSamplingRate(uint32_t)}. */
    public void setSampleRate(final int samplingRate) {
        if (samplingRate_ != samplingRate) {
            samplingRate_ = samplingRate;
            reset();
        }
    }

    /**
     * Loads a little-endian float32 resource from the classpath.
     *
     * @return the float array, or {@code null} if the resource is missing or unreadable.
     *         Callers treat {@code null} as "do not load kernel" (bypass path); this
     *         method never throws.
     */
    private float[] loadF32(final String name) {
        try (InputStream in = VHE.class.getResourceAsStream(RES_DIR + name)) {
            if (in == null) {
                return null;
            }
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] tmp = new byte[8192];
            int r;
            while ((r = in.read(tmp)) != -1) {
                bos.write(tmp, 0, r);
            }
            final byte[] bytes = bos.toByteArray();
            if ((bytes.length % 4) != 0) {
                return null;
            }
            final ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            final float[] out = new float[bytes.length / 4];
            bb.asFloatBuffer().get(out);
            return out;
        } catch (final IOException ignored) {
            return null;
        }
    }
}
