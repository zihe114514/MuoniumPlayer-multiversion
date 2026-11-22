package com.muoniumplayer.core.ncm.music;

/** Streaming BS.1770-style integrated loudness meter for mono and stereo PCM. */
final class IntegratedLoudnessMeter {

    private static final int HISTOGRAM_BINS = 8_001;
    private static final double ABSOLUTE_GATE_LUFS = -70.0;
    private static final double RELATIVE_GATE_LU = 10.0;
    private static final double LUFS_OFFSET = -0.691;
    private static final double MIN_POWER = 1e-12;
    private static final double HISTOGRAM_STEP_LU = 0.01;

    private final int sampleRate;
    private final int blockFrames;
    private final int hopFrames;
    private final double[] rollingPower;
    private final long[] blockCounts = new long[HISTOGRAM_BINS];
    private final double[] blockPowerSums = new double[HISTOGRAM_BINS];
    private final Biquad[] shelves = new Biquad[2];
    private final Biquad[] highPasses = new Biquad[2];

    private long frames;
    private int rollingIndex;
    private double rollingSum;
    private double allPowerSum;
    private double samplePeak;
    private boolean unsupportedChannels;

    IntegratedLoudnessMeter(int sampleRate) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be positive");
        this.sampleRate = sampleRate;
        this.blockFrames = Math.max(1, (int) Math.round(sampleRate * 0.4));
        this.hopFrames = Math.max(1, blockFrames / 4);
        this.rollingPower = new double[blockFrames];
        for (int channel = 0; channel < 2; channel++) {
            shelves[channel] = highShelf(sampleRate);
            highPasses[channel] = highPass(sampleRate);
        }
    }

    boolean acceptFrame(float left, float right, int channels) {
        if (channels < 1 || channels > 2) {
            unsupportedChannels = true;
            return false;
        }
        double l = finiteSample(left);
        double r = channels == 2 ? finiteSample(right) : 0.0;
        samplePeak = Math.max(samplePeak, Math.abs(l));
        if (channels == 2) samplePeak = Math.max(samplePeak, Math.abs(r));

        double weightedLeft = highPasses[0].process(shelves[0].process(l));
        double power = weightedLeft * weightedLeft;
        if (channels == 2) {
            double weightedRight = highPasses[1].process(shelves[1].process(r));
            power += weightedRight * weightedRight;
        }
        if (!Double.isFinite(power)) return false;
        allPowerSum += power;

        if (frames >= blockFrames) rollingSum -= rollingPower[rollingIndex];
        rollingPower[rollingIndex] = power;
        rollingSum += power;
        rollingIndex++;
        if (rollingIndex == blockFrames) rollingIndex = 0;
        frames++;

        if (frames >= blockFrames && (frames - blockFrames) % hopFrames == 0L) {
            addBlock(rollingSum / blockFrames);
        }
        return true;
    }

    boolean acceptPcm16(short[] interleaved, int sampleCount, int channels) {
        if (interleaved == null || channels < 1 || channels > 2 || sampleCount % channels != 0) return false;
        for (int offset = 0; offset < sampleCount; offset += channels) {
            float left = interleaved[offset] / 32768.0f;
            float right = channels == 2 ? interleaved[offset + 1] / 32768.0f : 0.0f;
            if (!acceptFrame(left, right, channels)) return false;
        }
        return true;
    }

    boolean acceptPcmBytes(byte[] interleaved, int length, int channels, int bitsPerSample) {
        if (interleaved == null || channels < 1 || channels > 2) return false;
        int bytesPerSample = bitsPerSample / 8;
        int bytesPerFrame = bytesPerSample * channels;
        if ((bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24)
                || bytesPerFrame <= 0 || length < 0 || length > interleaved.length
                || length % bytesPerFrame != 0) return false;
        for (int offset = 0; offset < length; offset += bytesPerFrame) {
            float left = decodePcm(interleaved, offset, bitsPerSample);
            float right = channels == 2
                    ? decodePcm(interleaved, offset + bytesPerSample, bitsPerSample) : 0.0f;
            if (!acceptFrame(left, right, channels)) return false;
        }
        return true;
    }

    Measurement finish() {
        if (unsupportedChannels || frames == 0L) return null;
        double power;
        if (frames < blockFrames) {
            power = allPowerSum / frames;
        } else {
            double absolutePowerSum = 0.0;
            long absoluteCount = 0L;
            for (int bin = 1; bin < HISTOGRAM_BINS; bin++) {
                absolutePowerSum += blockPowerSums[bin];
                absoluteCount += blockCounts[bin];
            }
            if (absoluteCount == 0L) return null;
            double relativeThreshold = toLufs(absolutePowerSum / absoluteCount) - RELATIVE_GATE_LU;
            double gatedPowerSum = 0.0;
            long gatedCount = 0L;
            for (int bin = 1; bin < HISTOGRAM_BINS; bin++) {
                double bucketLevel = ABSOLUTE_GATE_LUFS + bin * HISTOGRAM_STEP_LU;
                if (bucketLevel > relativeThreshold) {
                    gatedPowerSum += blockPowerSums[bin];
                    gatedCount += blockCounts[bin];
                }
            }
            if (gatedCount == 0L) return null;
            power = gatedPowerSum / gatedCount;
        }
        if (!(power > MIN_POWER) || !Double.isFinite(power)) return null;
        return new Measurement((float) toLufs(power), (float) samplePeak, frames, sampleRate);
    }

    private void addBlock(double power) {
        if (!(power > MIN_POWER) || !Double.isFinite(power)) return;
        double level = toLufs(power);
        if (level <= ABSOLUTE_GATE_LUFS) return;
        int bin = (int) Math.round((level - ABSOLUTE_GATE_LUFS) / HISTOGRAM_STEP_LU);
        bin = Math.max(1, Math.min(HISTOGRAM_BINS - 1, bin));
        blockCounts[bin]++;
        blockPowerSums[bin] += power;
    }

    private static double toLufs(double power) {
        return LUFS_OFFSET + 10.0 * Math.log10(power);
    }

    private static double finiteSample(float value) {
        return Float.isFinite(value) ? value : 0.0;
    }

    private static float decodePcm(byte[] data, int offset, int bitsPerSample) {
        if (bitsPerSample == 8) return ((data[offset] & 0xff) - 128) / 128.0f;
        if (bitsPerSample == 16) {
            int value = (data[offset] & 0xff) | (data[offset + 1] << 8);
            return value / 32768.0f;
        }
        int value = (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8)
                | (data[offset + 2] << 16);
        return value / 8388608.0f;
    }

    private static Biquad highShelf(int sampleRate) {
        double gainDb = 3.999843853973347;
        double q = .7071752369554196;
        double frequency = 1681.974450955533;
        double k = Math.tan(Math.PI * frequency / sampleRate);
        double vh = Math.pow(10.0, gainDb / 20.0);
        double vb = Math.pow(vh, .4996667741545416);
        double denominator = 1.0 + k / q + k * k;
        double b0 = (vh + vb * k / q + k * k) / denominator;
        double b1 = 2.0 * (k * k - vh) / denominator;
        double b2 = (vh - vb * k / q + k * k) / denominator;
        double a1 = 2.0 * (k * k - 1.0) / denominator;
        double a2 = (1.0 - k / q + k * k) / denominator;
        return new Biquad(b0, b1, b2, a1, a2);
    }

    private static Biquad highPass(int sampleRate) {
        double q = .5003270373238773;
        double frequency = 38.13547087602444;
        double k = Math.tan(Math.PI * frequency / sampleRate);
        double denominator = 1.0 + k / q + k * k;
        return new Biquad(1.0 / denominator, -2.0 / denominator, 1.0 / denominator,
                2.0 * (k * k - 1.0) / denominator, (1.0 - k / q + k * k) / denominator);
    }

    static final class Measurement {
        final float integratedLufs;
        final float samplePeak;
        final long frames;
        final int sampleRate;

        Measurement(float integratedLufs, float samplePeak, long frames, int sampleRate) {
            this.integratedLufs = integratedLufs;
            this.samplePeak = samplePeak;
            this.frames = frames;
            this.sampleRate = sampleRate;
        }
    }

    private static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        Biquad(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        double process(double input) {
            double output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = input;
            y2 = y1;
            y1 = Double.isFinite(output) ? output : 0.0;
            return y1;
        }
    }
}
