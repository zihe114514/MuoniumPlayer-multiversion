package com.muoniumplayer.core.ncm.music.acoustics;

import java.util.Arrays;

/** 真反射抽头的双耳渲染。两组交叉淡化期间只记最新待办,不重启正在进行的淡化。 */
final class EarlyReflections {

    private static final int CAPACITY = 192;
    private static final double ITD_SEC = 0.00065;
    private static final double FADE_SEC = 0.20;
    private static final double GAIN_TAU_SEC = 0.08;
    private static final double PAN_TAU_SEC = 0.035;
    private static final double HF_SHELF_HZ = 8000.0;
    private final StereoDelayLine ring = new StereoDelayLine();
    private TapSet active = new TapSet();
    private TapSet incoming = new TapSet();
    private RoomPlan latest;
    private int rate;
    private boolean fading;
    private float fade;
    private float fadeStep;

    void rebuild(int sampleRate) {
        rate = sampleRate;
        ring.rebuild((int) (sampleRate * (0.24 + ITD_SEC)) + 8);
        flush();
    }

    void update(RoomPlan plan, int frames) {
        latest = plan;
        if (fading) {
            if (incoming.matches(plan, rate)) {
                incoming.target = plan;
            }
        } else if (active.matches(plan, rate)) {
            active.target = plan;
        } else if (active.count == 0) {
            active.build(plan, rate);
        } else {
            incoming.build(plan, rate);
            fading = true;
            fade = 0f;
        }
        final float gainStep = (float) (1.0 - Math.exp(-frames / (rate * GAIN_TAU_SEC)));
        final float panStep = (float) (1.0 - Math.exp(-frames / (rate * PAN_TAU_SEC)));
        active.smooth(gainStep, panStep);
        if (fading) {
            incoming.smooth(gainStep, panStep);
        }
        fadeStep = (float) (1.0 / (rate * FADE_SEC));
    }

    void process(float left, float right, float[] out) {
        ring.write(left, right);
        active.process(ring, rate, out);
        if (!fading) {
            return;
        }
        final float aL = out[0];
        final float aR = out[1];
        incoming.process(ring, rate, out);
        out[0] = aL * (1f - fade) + out[0] * fade;
        out[1] = aR * (1f - fade) + out[1] * fade;
        fade = Math.min(1f, fade + fadeStep);
        if (fade >= 1f) {
            final TapSet old = active;
            active = incoming;
            incoming = old;
            incoming.clear();
            fading = false;
            if (latest != null && active.matches(latest, rate)) {
                active.target = latest;
            }
        }
    }

    void flush() {
        ring.flush();
        active.clear();
        incoming.clear();
        latest = null;
        fading = false;
        fade = 1f;
    }

    private static final class TapSet {
        private final int[] delay = new int[CAPACITY];
        private final float[] pan = new float[CAPACITY];
        private final float[] gainL = new float[CAPACITY];
        private final float[] gainR = new float[CAPACITY];
        private final float[] highRatio = new float[CAPACITY];
        private final float[] airAlpha = new float[CAPACITY];
        private final float[] materialAlpha = new float[CAPACITY];
        private final float[] airLowL = new float[CAPACITY];
        private final float[] airLowR = new float[CAPACITY];
        private final float[] materialLowL = new float[CAPACITY];
        private final float[] materialLowR = new float[CAPACITY];
        private int count;
        private RoomPlan target;

        boolean matches(RoomPlan plan, int rate) {
            if (count != plan.tapCount) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                if (delay[i] != sampleDelay(plan, i, rate)) {
                    return false;
                }
            }
            return true;
        }

        void build(RoomPlan plan, int rate) {
            clear();
            count = plan.tapCount;
            target = plan;
            for (int i = 0; i < count; i++) {
                delay[i] = sampleDelay(plan, i, rate);
                pan[i] = DirectPath.panFromGains(plan.tapGainL[i], plan.tapGainR[i]);
                gainL[i] = plan.tapGainL[i];
                gainR[i] = plan.tapGainR[i];
                final double path = plan.tapDelaySec[i] * 343.0 + plan.directDistanceMeters;
                final double airFc = DirectPath.airAbsorptionFc(path);
                final double airRatio = airFc >= DirectPath.FC_OPEN ? 1.0
                        : Math.max(0.05, Math.min(1.0, airFc / HF_SHELF_HZ));
                final double materialRatio = plan.tapHighRatio.length > i
                        ? Math.max(0.02, Math.min(1.0, plan.tapHighRatio[i])) : 1.0;
                highRatio[i] = (float) (airRatio * materialRatio);
                airAlpha[i] = airFc >= DirectPath.FC_OPEN ? 1f
                        : (float) (1.0 - Math.exp(-2.0 * Math.PI
                                * Math.min(airFc, rate * 0.45) / rate));
                final double ratio = Math.max(0.02, highRatio[i]);
                final double shelfFc = HF_SHELF_HZ * Math.sqrt(ratio / Math.max(0.02, 1.0 - ratio));
                materialAlpha[i] = (float) (1.0 - Math.exp(-2.0 * Math.PI
                        * Math.min(shelfFc, rate * 0.45) / rate));
            }
        }

        void smooth(float gainStep, float panStep) {
            if (target == null) {
                return;
            }
            for (int i = 0; i < count; i++) {
                gainL[i] += (target.tapGainL[i] - gainL[i]) * gainStep;
                gainR[i] += (target.tapGainR[i] - gainR[i]) * gainStep;
                pan[i] += (DirectPath.panFromGains(target.tapGainL[i], target.tapGainR[i])
                        - pan[i]) * panStep;
            }
        }

        void process(StereoDelayLine ring, int rate, float[] out) {
            float l = 0f;
            float r = 0f;
            for (int i = 0; i < count; i++) {
                final double itd = pan[i] * ITD_SEC * rate;
                final float sampleL = ring.left(delay[i] + Math.max(0.0, itd));
                final float sampleR = ring.right(delay[i] + Math.max(0.0, -itd));
                airLowL[i] += (sampleL - airLowL[i]) * airAlpha[i];
                airLowR[i] += (sampleR - airLowR[i]) * airAlpha[i];
                materialLowL[i] += (airLowL[i] - materialLowL[i]) * materialAlpha[i];
                materialLowR[i] += (airLowR[i] - materialLowR[i]) * materialAlpha[i];
                final float shelfL = airLowL[i] - (1f - highRatio[i])
                        * (airLowL[i] - materialLowL[i]);
                final float shelfR = airLowR[i] - (1f - highRatio[i])
                        * (airLowR[i] - materialLowR[i]);
                l += shelfL * gainL[i];
                r += shelfR * gainR[i];
            }
            out[0] = l;
            out[1] = r;
        }

        void clear() {
            count = 0;
            target = null;
            Arrays.fill(airLowL, 0f);
            Arrays.fill(airLowR, 0f);
            Arrays.fill(materialLowL, 0f);
            Arrays.fill(materialLowR, 0f);
        }

        private static int sampleDelay(RoomPlan plan, int i, int rate) {
            return Math.max(1, Math.min((int) (rate * 0.24),
                    Math.round(plan.tapDelaySec[i] * rate)));
        }
    }
}
