package com.muoniumplayer.core.ncm.music;

/**
 * Converts the cached Folia envelopes into qplayer-next's learned-EQ feature contract.
 * This runs during plan preparation, never from the JSyn audio callback.
 */
final class AutomixLearnedEqProfile {

    private static final int FEATURE_COUNT = AutomixLearnedEq.IN;
    private static final int MAX_FRAMES = 600;
    private static final long MIN_WINDOW_MILLIS = 2_000L;
    private static final double EPS = 1e-6;

    private AutomixLearnedEqProfile() {
    }

    static float[] controls(AutomixEnvelope outgoing, AutomixEnvelope incoming,
                            long fireMillis, long cueMillis, long overlapMillis,
                            AutomixAnalyzer.BeatGrid outgoingGrid,
                            AutomixAnalyzer.BeatGrid incomingGrid, float keyMatch) {
        if (outgoing == null || incoming == null || !outgoing.isUsable() || !incoming.isUsable()
                || overlapMillis < MIN_WINDOW_MILLIS) return null;
        int frames = (int) Math.min(MAX_FRAMES, overlapMillis / AutomixEnvelope.HOP_MILLIS);
        if (frames < 20) return null;

        double bpmA = outgoingGrid == null ? 0.0 : outgoingGrid.bpm;
        double bpmB = incomingGrid == null ? 0.0 : incomingGrid.bpm;
        double meanBpm = (bpmA + bpmB) * .5;
        double bpmGap = meanBpm > 1e-3 ? Math.abs(bpmA - bpmB) / meanBpm : 0.0;
        double bpmAbs = bpmA / 180.0;
        double windowNorm = overlapMillis / 8_000.0;
        float[][] features = new float[frames][FEATURE_COUNT];
        double[] energy = new double[frames];
        double peak = 0.0;
        int peakFrame = 0;
        double energySum = 0.0;

        for (int frame = 0; frame < frames; frame++) {
            long offset = (long) frame * AutomixEnvelope.HOP_MILLIS;
            long aMillis = fireMillis + offset;
            long bMillis = cueMillis + offset;
            double aFull = Math.max(EPS, outgoing.rmsAt(aMillis));
            double bFull = Math.max(EPS, incoming.rmsAt(bMillis));
            double aLow = Math.min(aFull, aFull * outgoing.lowShareAt(aMillis));
            double aHigh = Math.min(aFull, aFull * outgoing.highShareAt(aMillis));
            double bLow = Math.min(bFull, bFull * incoming.lowShareAt(bMillis));
            double bHigh = Math.min(bFull, bFull * incoming.highShareAt(bMillis));
            double aMid = Math.sqrt(Math.max(0.0, aFull * aFull - aLow * aLow - aHigh * aHigh));
            double bMid = Math.sqrt(Math.max(0.0, bFull * bFull - bLow * bLow - bHigh * bHigh));
            double combined = aLow + aMid + aHigh + bLow + bMid + bHigh;
            energy[frame] = combined;
            energySum += combined;
            if (combined > peak) {
                peak = combined;
                peakFrame = frame;
            }

            float[] row = features[frame];
            row[0] = finite(aLow);
            row[1] = finite(aMid);
            row[2] = finite(aHigh);
            row[3] = finite(bLow);
            row[4] = finite(bMid);
            row[5] = finite(bHigh);
            row[6] = centroid(outgoing, aMillis);
            row[7] = centroid(incoming, bMillis);
            row[8] = frame / (float) Math.max(1, frames - 1);
            row[9] = (float) windowNorm;
            row[10] = (float) bpmGap;
            row[11] = clamp01(keyMatch);
            row[12] = (float) clamp01(outgoing.onsetAt(aMillis));
            row[13] = (float) bpmAbs;
            row[14] = (float) clamp01(aLow / aFull);
            row[15] = (float) clamp01(bLow / bFull);

            double[] pastA = bands(outgoing, Math.max(0L, aMillis - AutomixEnvelope.HOP_MILLIS));
            double[] pastB = bands(incoming, Math.max(0L, bMillis - AutomixEnvelope.HOP_MILLIS));
            double[] futureA = bands(outgoing, aMillis + AutomixEnvelope.HOP_MILLIS);
            double[] futureB = bands(incoming, bMillis + AutomixEnvelope.HOP_MILLIS);
            row[16] = (float) Math.max(0.0, aLow - pastA[0] + aMid - pastA[1] + aHigh - pastA[2]);
            for (int band = 0; band < 3; band++) {
                row[17 + band] = finite(bandValue(aLow, aMid, aHigh, band)
                        - pastA[band]);
                row[20 + band] = finite(bandValue(bLow, bMid, bHigh, band)
                        - pastB[band]);
                row[23 + band] = finite(futureA[band]
                        - bandValue(aLow, aMid, aHigh, band));
                row[26 + band] = finite(futureB[band]
                        - bandValue(bLow, bMid, bHigh, band));
            }
        }

        double peakPos = peakFrame / (double) Math.max(1, frames - 1);
        double meanEnergy = energySum / Math.max(1, frames);
        double peakAmp = Math.min(5.0, peak / Math.max(EPS, meanEnergy));
        for (int frame = 0; frame < frames; frame++) {
            float[] row = features[frame];
            double lowRatio = row[14];
            double midSum = row[0] + row[1] + row[2];
            double midRatio = midSum <= EPS ? .5 : row[1] / midSum;
            double onset = row[12];
            double[] genres = genres(bpmA, onset, lowRatio, midRatio);
            row[29] = (float) peakPos;
            row[30] = (float) peakAmp;
            for (int genre = 0; genre < genres.length; genre++) row[31 + genre] = (float) genres[genre];
        }

        AutomixLearnedEq model = AutomixLearnedEq.available() ? AutomixLearnedEq.embedded() : null;
        if (model == null) return ruleControls(features, frames);
        double[] sum = new double[AutomixLearnedEq.OUT];
        double[] output = new double[AutomixLearnedEq.OUT];
        for (float[] row : features) {
            model.forward(row, output);
            for (int index = 0; index < sum.length; index++) sum[index] += output[index];
        }
        float[] controls = new float[AutomixLearnedEq.OUT];
        for (int index = 0; index < controls.length; index++) {
            controls[index] = (float) clamp01(sum[index] / frames);
        }
        return controls;
    }

    private static float[] ruleControls(float[][] features, int frames) {
        double conflict = 0.0;
        for (int frame = 0; frame < frames; frame++) {
            conflict += features[frame][0] * features[frame][3];
        }
        double cut = Math.max(0.0, Math.min(.65, conflict / Math.max(1, frames) * 2.4));
        float[] controls = new float[AutomixLearnedEq.OUT];
        for (int band = 0; band < 3; band++) {
            double amount = cut * (band == 0 ? 1.0 : band == 1 ? .55 : .35);
            controls[band * 4] = (float) (1.0 - amount * .55);
            controls[band * 4 + 1] = (float) (1.0 - amount);
            controls[band * 4 + 2] = (float) (1.0 - amount * .65);
            controls[band * 4 + 3] = 1.0f;
        }
        return controls;
    }

    private static double[] bands(AutomixEnvelope envelope, long millis) {
        double full = Math.max(EPS, envelope.rmsAt(millis));
        double low = Math.min(full, full * envelope.lowShareAt(millis));
        double high = Math.min(full, full * envelope.highShareAt(millis));
        double mid = Math.sqrt(Math.max(0.0, full * full - low * low - high * high));
        return new double[]{low, mid, high};
    }

    private static double bandValue(double low, double mid, double high, int band) {
        return band == 0 ? low : band == 1 ? mid : high;
    }

    private static float centroid(AutomixEnvelope envelope, long millis) {
        double current = envelope.rmsAt(millis);
        double previous = envelope.rmsAt(Math.max(0L, millis - AutomixEnvelope.HOP_MILLIS));
        return (float) clamp01((current - previous) * 4.0 + .5);
    }

    private static double[] genres(double bpm, double onset, double lowRatio, double midRatio) {
        double edm = sigmoid((bpm - 115.0) / 5.0) * onset;
        double hiphop = sigmoid((90.0 - bpm) / 5.0) * onset * lowRatio;
        double dnb = sigmoid((bpm - 160.0) / 5.0) * onset;
        double rock = sigmoid((bpm - 105.0) / 8.0) * (1.0 - onset) * midRatio;
        double def = 1.0 - Math.max(Math.max(edm, hiphop), Math.max(dnb, rock));
        double[] result = {Math.max(0.0, edm), Math.max(0.0, hiphop), Math.max(0.0, dnb),
                Math.max(0.0, rock), Math.max(0.0, def)};
        double sum = 0.0;
        for (double value : result) sum += value;
        for (int index = 0; index < result.length; index++) result[index] /= Math.max(EPS, sum);
        return result;
    }

    private static double sigmoid(double value) {
        return 1.0 / (1.0 + Math.exp(-Math.max(-60.0, Math.min(60.0, value))));
    }

    private static float finite(double value) {
        return (float) (Double.isFinite(value) ? value : 0.0);
    }

    private static float clamp01(double value) {
        if (!Double.isFinite(value)) return 0f;
        return (float) Math.max(0.0, Math.min(1.0, value));
    }
}
