// 逐行移植自 JamesDSPManager：Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/reverb.c（939 行，单文件自包含）
// 原始算法：(c) Copyright 2016, Sean Connelly (@voidqk), http://syntheti.cc —— sndfilter
// JamesDSP 修改版（MIT License）；本仓库按用户「个人使用、非商用」前提移植
package com.muoniumplayer.core.jdsp;

/**
 * JamesDSP 混响（Reverberation）——sndfilter 的 {@code sf_reverb} 完整移植。
 *
 * <p>与 ViPER 自带的 Freeverb 混响（{@code core.viper.dsp.CRevModel}）是两个独立实现：
 * 本类包含<b>早期反射 + 2 倍过采样 + 噪声/LFO 调制的全通扩散网络 + 交叉淡化低频增强 +
 * 输出组合</b>，并提供上游的 <b>19 个预设</b>（Default / Small Hall / Medium Hall / Large Hall /
 * Small Room / Medium Room / Large Room / Medium ER / Plate High / Plate Low / Long Reverb …）。</p>
 *
 * <p><b>参数模型（1:1 对齐上游）</b>：上游 JamesDSP 的对外 API 只有
 * {@code Reverb_SetParam(jdsp, presets)}（预设索引）、{@code ReverbEnable/Disable}、
 * {@code ReverbProcess}。预设表内部展开为 {@code sf_advancereverb(...)} 的 <b>16 个参数</b>
 * （ertolate / erefwet / dry / ereffactor / erefwidth / width / wet / wander / bassb /
 * spin / inputlpf / basslpf / damplpf / outputlpf / rt60 / delay）；本移植同样把 16 个参数
 * 作为可选覆盖通道暴露（{@link #configureCustom}），预设选择走 {@link #configure}。</p>
 *
 * <p><b>采样率</b>：上游用 {@code totfactor = osrate / 34125} 对全部延迟长度做比例缩放
 * （{@code nextprime} 取质数），本移植一致。最大缓冲按上游编译期常量预分配，
 * {@link #configure} 只做复位与尺寸设定（无分配），可在音频线程调用。</p>
 */
public final class JdspReverb {

    // ──────────────────────────── 上游常量 ────────────────────────────

    /** {@code SF_REVERB_DS} */
    private static final int DS = 3000;
    /** {@code SF_REVERB_OF} */
    private static final int OF = 2;
    /** {@code SF_REVERB_NS} */
    private static final int NS = (1 << 11);
    /** {@code SF_REVERB_APS} */
    private static final int APS = 3400;
    /** {@code SF_REVERB_AP2S1 / AP2S2} */
    private static final int AP2S1 = 4200;
    private static final int AP2S2 = 3000;
    /** {@code SF_REVERB_AP3S1 / AP3M1 / AP3S2 / AP3S3} */
    private static final int AP3S1 = 4000;
    private static final int AP3M1 = 600;
    private static final int AP3S2 = 2000;
    private static final int AP3S3 = 3000;
    /** {@code SF_REVERB_APMS / APMM} */
    private static final int APMS = 3600;
    private static final int APMM = 137;
    /** {@code SF_REVERB_CS} */
    private static final int CS = 1500;

    /** {@code sf_reverb_process} 里的硬编码常数。 */
    private static final float MOD_NOISE_1 = 0.09f;
    private static final float MOD_NOISE_2 = 0.06f;
    private static final float CROSSFEED = 0.4f;

    /** {@code delay_get} 越界返回的兜底（对应上游 {@code SF_REVERB_DS}）。 */
    private static final int SF_REVERB_DS = DS;

    // ──────────────────────────── 工具函数（上游同名） ────────────────────────────

    private static float db2lin(float db) {
        return (float) Math.pow(10.0f, 0.05f * db);
    }

    private static int clampi(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float clampf(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static boolean isPrime(int value) {
        int v = value;
        if (v < 0) {
            v = -v;
        }
        if (v < 2) {
            return false;
        }
        if (v <= 3) {
            return true;
        }
        if ((v % 2) == 0 || (v % 3) == 0) {
            return false;
        }
        int max = (int) Math.sqrt((double) v);
        for (int i = 5; i <= max; i += 6) {
            if ((v % i) == 0 || (v % (i + 2)) == 0) {
                return false;
            }
        }
        return true;
    }

    private static int nextPrime(int v) {
        int value = v;
        while (!isPrime(value)) {
            value++;
        }
        return value;
    }

    /**
     * 上游的 {@code randfloat()}：xorshift 类的确定性 RNG（种子固定 123/456）。
     * 它只用于生成噪声缓冲，序列必须逐位一致才能保证与上游输出一致。
     */
    private int randSeed = 123;
    private int randI = 456;

    private float randfloat() {
        int m = 0x5bd1e995;
        int k = randI++ * m;
        randSeed = (k ^ (k >> 24) ^ (randSeed * m)) * m;
        int r = (randSeed ^ (randSeed >> 13)) & 0x007FFFFF;
        int bits = 0x3F800000 | r;
        return Float.intBitsToFloat(bits) - 1.0f;
    }

    // ──────────────────────────── delay ────────────────────────────

    /** 对应 {@code sf_rv_delay_st}。 */
    static final class Delay {
        final float[] buf = new float[DS];
        int pos;
        int size;

        void make(int size) {
            pos = 0;
            this.size = clampi(size, 1, DS);
            java.util.Arrays.fill(buf, 0, this.size, 0.0f);
        }

        float step(float v) {
            float out = buf[pos];
            buf[pos] = v;
            pos = (pos + 1) % size;
            return out;
        }

        /** {@code delay_get(d, offset)}：offset 为「回看的样本数」，1 = 最后写入的值。 */
        float get(int offset) {
            int off = offset;
            if (off > size) {
                return buf[pos];
            } else if (off <= 0) {
                off = 1;
            }
            int p = pos - off;
            if (p < 0) {
                p += size;
            }
            return buf[p];
        }

        float getLast() {
            return buf[pos];
        }
    }

    // ──────────────────────────── iir1（一阶 Butterworth） ────────────────────────────

    /** 对应 {@code sf_rv_iir1_st}。 */
    static final class Iir1 {
        float b1;
        float b2;
        float a2;
        float y1;

        void makeLpf(int rate, float freq) {
            float f = clampf(freq, 0, (float) rate * 0.5f);
            float omega2 = (float) Math.PI * f / (float) rate;
            float tano2 = (float) Math.tan(omega2);
            b1 = tano2 / (1.0f + tano2);
            b2 = b1;
            a2 = (1.0f - tano2) / (1.0f + tano2);
            y1 = 0;
        }

        void makeHpf(int rate, float freq) {
            float f = clampf(freq, 0, (float) rate * 0.5f);
            float omega2 = (float) Math.PI * f / (float) rate;
            float tano2 = (float) Math.tan(omega2);
            b1 = 1.0f / (1.0f + tano2);
            b2 = -b1;
            a2 = (1.0f - tano2) / (1.0f + tano2);
            y1 = 0;
        }

        float step(float v) {
            float out = v * b1 + y1;
            y1 = out * a2 + v * b2;
            return out;
        }
    }

    // ──────────────────────────── biquad ────────────────────────────

    /** 对应 {@code sf_rv_biquad_st}。 */
    static final class Biquad {
        float b0;
        float b1;
        float b2;
        float a1;
        float a2;
        float xn1;
        float xn2;
        float yn1;
        float yn2;

        private void resetState() {
            xn1 = 0;
            xn2 = 0;
            yn1 = 0;
            yn2 = 0;
        }

        void makeLpf(int rate, float freq, float bw) {
            float f = clampf(freq, 0, (float) rate * 0.5f);
            float omega = 2.0f * (float) Math.PI * f / (float) rate;
            float cs = (float) Math.cos(omega);
            float sn = (float) Math.sin(omega);
            float alpha = sn * (float) Math.sinh(0.693147180559945309417232121458176568f * 0.5f
                    * bw * omega / sn);
            float a0inv = 1.0f / (1.0f + alpha);
            b0 = a0inv * (1.0f - cs) * 0.5f;
            b1 = 2.0f * b0;
            b2 = b0;
            a1 = a0inv * -2.0f * cs;
            a2 = a0inv * (1.0f - alpha);
            resetState();
        }

        void makeLpfQ(int rate, float freq, float bw) {
            float f = clampf(freq, 0, (float) rate * 0.5f);
            float omega = 2.0f * (float) Math.PI * f / (float) rate;
            float cs = (float) Math.cos(omega);
            float alpha = (float) Math.sin(omega) * 2.0f * bw;
            float a0inv = 1.0f / (1.0f + alpha);
            b0 = a0inv * (1.0f - cs) * 0.5f;
            b1 = 2.0f * b0;
            b2 = b0;
            a1 = a0inv * -2.0f * cs;
            a2 = a0inv * (1.0f - alpha);
            resetState();
        }

        void makeApf(int rate, float freq, float bw) {
            float f = clampf(freq, 0, (float) rate * 0.5f);
            float omega = 2.0f * (float) Math.PI * f / (float) rate;
            float sn = (float) Math.sin(omega);
            float alpha = sn * (float) Math.sinh(0.693147180559945309417232121458176568f * 0.5f
                    * bw * omega / sn);
            float a0inv = 1.0f / (1.0f + alpha);
            b0 = a0inv * (1.0f - alpha);
            b1 = a0inv * -2.0f * (float) Math.cos(omega);
            b2 = a0inv * (1.0f + alpha);
            a1 = b1;
            a2 = b0;
            resetState();
        }

        float step(float v) {
            float out = v * b0 + xn1 * b1 + xn2 * b2 - yn1 * a1 - yn2 * a2;
            xn2 = xn1;
            xn1 = v;
            yn2 = yn1;
            yn1 = out;
            return out;
        }
    }

    // ──────────────────────────── 其它子结构 ────────────────────────────

    /** 对应 {@code sf_rv_dccut_st}。 */
    static final class DcCut {
        float gain;
        float y1;
        float y2;

        void make(int rate, float freq) {
            float f = clampf(freq, 0, (float) rate * 0.5f);
            float ang = 2.0f * (float) Math.PI * f / (float) rate;
            float sn = (float) Math.sin(ang);
            float sqrt3 = 1.7320508075688772f;
            gain = (sqrt3 - 2.0f * sn) / (sn + sqrt3 * (float) Math.cos(ang));
            y1 = 0;
            y2 = 0;
        }

        float step(float v) {
            float out = v - y1 + gain * y2;
            y1 = v;
            y2 = out;
            return out;
        }
    }

    /** 对应 {@code sf_rv_noise_st}（含上游的「细分中点位移」噪声生成）。 */
    static final class Noise {
        final float[] buf = new float[NS];
        int pos = NS;

        void make() {
            pos = NS;
        }

        float step(JdspReverb owner) {
            if (pos >= NS) {
                pos = 0;
                int len = NS;
                int tot = 1;
                float r = 0.8f;
                float rmul = 0.7071067811865475f;
                buf[0] = 0;
                while (len > 1) {
                    float left = 0;
                    for (int i = tot - 1; i >= 0; i--) {
                        float right = left;
                        left = buf[i * len];
                        float midpoint = (left + right) * 0.5f;
                        float newv = midpoint + r * (2.0f * owner.randfloat() - 1.0f);
                        buf[i * len + (len / 2)] = clampf(newv, -1.0f, 1.0f);
                    }
                    len /= 2;
                    tot *= 2;
                    r *= rmul;
                }
            }
            return buf[pos++];
        }
    }

    /** 对应 {@code sf_rv_lfo_st}（复平面旋转振荡器，带周期性归一化）。 */
    static final class Lfo {
        int count;
        float re;
        float im;
        float sn;
        float co;

        void make(int rate, float freq) {
            count = 0;
            re = 1.0f;
            im = 0.0f;
            float theta = 2.0f * (float) Math.PI * freq / (float) rate;
            sn = (float) Math.sin(theta);
            co = (float) Math.cos(theta);
        }

        float step() {
            float v = im;
            float r = re * co - im * sn;
            float i = re * sn + im * co;
            if (count++ > 100000) {
                count = 0;
                float leninv = 1.0f / (float) Math.sqrt(r * r + i * i);
                r *= leninv;
                i *= leninv;
            }
            re = r;
            im = i;
            return v;
        }
    }

    // ──────────────────────────── allpass 系列 ────────────────────────────

    /** 对应 {@code sf_rv_allpass_st}。 */
    static final class AllPass {
        final float[] buf = new float[APS];
        int pos;
        int size;
        float feedback;
        float decay;

        void make(int size, float feedback, float decay) {
            pos = 0;
            this.size = clampi(size, 1, APS);
            this.feedback = feedback;
            this.decay = decay;
            java.util.Arrays.fill(buf, 0, this.size, 0.0f);
        }

        float step(float v) {
            float value = v + feedback * buf[pos];
            float out = decay * buf[pos] - feedback * value;
            buf[pos] = value;
            pos = (pos + 1) % size;
            return out;
        }
    }

    /** 对应 {@code sf_rv_allpass2_st}（两级串联全通）。 */
    static final class AllPass2 {
        final float[] buf1 = new float[AP2S1];
        final float[] buf2 = new float[AP2S2];
        int pos1;
        int pos2;
        int size1;
        int size2;
        float feedback1;
        float feedback2;
        float decay1;
        float decay2;

        void make(int size1, int size2, float feedback1, float feedback2,
                  float decay1, float decay2) {
            pos1 = 0;
            pos2 = 0;
            this.size1 = clampi(size1, 1, AP2S1);
            this.size2 = clampi(size2, 1, AP2S2);
            this.feedback1 = feedback1;
            this.feedback2 = feedback2;
            this.decay1 = decay1;
            this.decay2 = decay2;
            java.util.Arrays.fill(buf1, 0, this.size1, 0.0f);
            java.util.Arrays.fill(buf2, 0, this.size2, 0.0f);
        }

        float step(float v) {
            float value = v + feedback2 * buf2[pos2];
            float out = decay2 * buf2[pos2] - value * feedback2;
            value += feedback1 * buf1[pos1];
            buf2[pos2] = decay1 * buf1[pos1] - value * feedback1;
            buf1[pos1] = value;
            pos1 = (pos1 + 1) % size1;
            pos2 = (pos2 + 1) % size2;
            return out;
        }

        float get1(int offset) {
            int off = offset;
            if (off > size1) {
                return buf1[pos1];
            } else if (off <= 0) {
                off = 1;
            }
            int rp = pos1 - off;
            if (rp < 0) {
                rp += size1;
            }
            return buf1[rp];
        }

        float get2(int offset) {
            int off = offset;
            if (off > size2) {
                return buf2[pos2];
            } else if (off <= 0) {
                off = 1;
            }
            int rp = pos2 - off;
            if (rp < 0) {
                rp += size2;
            }
            return buf2[rp];
        }
    }

    /** 对应 {@code sf_rv_allpass3_st}（三级串联 + 第一级带调制读指针）。 */
    static final class AllPass3 {
        final float[] buf1 = new float[AP3S1 + AP3M1];
        final float[] buf2 = new float[AP3S2];
        final float[] buf3 = new float[AP3S3];
        int rpos1;
        int wpos1;
        int pos2;
        int pos3;
        int size1;
        int msize1;
        int size2;
        int size3;
        float feedback1;
        float feedback2;
        float feedback3;
        float decay1;
        float decay2;
        float decay3;

        void make(int size1, int msize1, int size2, int size3,
                  float feedback1, float feedback2, float feedback3,
                  float decay1, float decay2, float decay3) {
            int s1 = clampi(size1, 1, AP3S1);
            int m1 = clampi(msize1, 1, AP3M1);
            if (m1 > s1) {
                m1 = s1;
            }
            int newsize = s1 + m1;
            rpos1 = (m1 * 2) % newsize;
            wpos1 = 0;
            pos2 = 0;
            pos3 = 0;
            this.size1 = newsize;
            this.msize1 = m1;
            this.size2 = clampi(size2, 1, AP3S2);
            this.size3 = clampi(size3, 1, AP3S3);
            this.feedback1 = feedback1;
            this.feedback2 = feedback2;
            this.feedback3 = feedback3;
            this.decay1 = decay1;
            this.decay2 = decay2;
            this.decay3 = decay3;
            java.util.Arrays.fill(buf1, 0, this.size1, 0.0f);
            java.util.Arrays.fill(buf2, 0, this.size2, 0.0f);
            java.util.Arrays.fill(buf3, 0, this.size3, 0.0f);
        }

        float step(float v, float mod) {
            float m = (mod + 1.0f) * (float) msize1;
            float floormod = (float) Math.floor(m);
            float mfrac = m - floormod;
            int r1 = rpos1 - (int) floormod;
            if (r1 < 0) {
                r1 += size1;
            }
            int r2 = r1 - 1;
            if (r2 < 0) {
                r2 += size1;
            }
            float value = v + feedback3 * buf3[pos3];
            float out = decay3 * buf3[pos3] - feedback3 * value;
            value += feedback2 * buf2[pos2];
            buf3[pos3] = decay2 * buf2[pos2] - feedback2 * value;
            float tmp = buf1[r2] * mfrac + buf1[r1] * (1.0f - mfrac);
            value += feedback1 * tmp;
            buf2[pos2] = decay1 * tmp - feedback1 * value;
            buf1[wpos1] = value;
            wpos1 = (wpos1 + 1) % size1;
            rpos1 = (rpos1 + 1) % size1;
            pos2 = (pos2 + 1) % size2;
            pos3 = (pos3 + 1) % size3;
            return out;
        }

        float get1(int offset) {
            int off = offset;
            if (off > size1) {
                return buf1[rpos1];
            } else if (off <= 0) {
                off = 1;
            }
            int rp = rpos1 - off;
            if (rp < 0) {
                rp += size1;
            }
            return buf1[rp];
        }

        float get2(int offset) {
            int off = offset;
            if (off > size2) {
                return buf2[pos2];
            } else if (off <= 0) {
                off = 1;
            }
            int rp = pos2 - off;
            if (rp < 0) {
                rp += size2;
            }
            return buf2[rp];
        }

        float get3(int offset) {
            int off = offset;
            if (off > size3) {
                return buf3[pos3];
            } else if (off <= 0) {
                off = 1;
            }
            int rp = pos3 - off;
            if (rp < 0) {
                rp += size3;
            }
            return buf3[rp];
        }
    }

    /** 对应 {@code sf_rv_allpassm_st}（带调制读指针的全通）。 */
    static final class AllPassM {
        final float[] buf = new float[APMS + APMM];
        int rpos;
        int wpos;
        int size;
        int msize;
        float feedback;
        float decay;
        float z1;

        void make(int size, int msize, float feedback, float decay) {
            int s = clampi(size, 1, APMS);
            int m = clampi(msize, 1, APMM);
            if (m > s) {
                m = s;
            }
            int newsize = s + m;
            rpos = (m * 2) % newsize;
            wpos = 0;
            this.size = newsize;
            this.msize = m;
            this.feedback = feedback;
            this.decay = decay;
            z1 = 0;
            java.util.Arrays.fill(buf, 0, this.size, 0.0f);
        }

        float step(float v, float mod, float fbmod) {
            float mfeedback = feedback + fbmod;
            float m = (mod + 1.0f) * (float) msize;
            float floormod = (float) Math.floor(m);
            float mfrac = 1.0f - m + floormod;
            int r1 = rpos - (int) floormod;
            if (r1 < 0) {
                r1 += size;
            }
            int r2 = r1 - 1;
            if (r2 < 0) {
                r2 += size;
            }
            z1 = buf[r2] + mfrac * (buf[r1] - z1);
            rpos = (rpos + 1) % size;
            buf[wpos] = v + z1 * mfeedback;
            float out = decay * z1 - buf[wpos] * mfeedback;
            wpos = (wpos + 1) % size;
            return out;
        }
    }

    /** 对应 {@code sf_rv_comb_st}。 */
    static final class Comb {
        final float[] buf = new float[CS];
        int pos;
        int size;

        void make(int size) {
            pos = 0;
            this.size = clampi(size, 1, CS);
            java.util.Arrays.fill(buf, 0, this.size, 0.0f);
        }

        float step(float v, float feedback) {
            float value = buf[pos] * feedback + v;
            buf[pos] = value;
            pos = (pos + 1) % size;
            return value;
        }
    }

    // ──────────────────────────── 早期反射 / 过采样 ────────────────────────────

    /** 对应 {@code sf_rv_earlyref_st}。 */
    static final class EarlyRef {
        final Delay delayPwl = new Delay();
        final Delay delayPwr = new Delay();
        final Delay delayRl = new Delay();
        final Delay delayLr = new Delay();
        final Biquad allpassXl = new Biquad();
        final Biquad allpassXr = new Biquad();
        final Biquad allpassL = new Biquad();
        final Biquad allpassR = new Biquad();
        final Iir1 lpfL = new Iir1();
        final Iir1 lpfR = new Iir1();
        final Iir1 hpfL = new Iir1();
        final Iir1 hpfR = new Iir1();
        final int[] delaytblL = new int[18];
        final int[] delaytblR = new int[18];
        float wet1;
        float wet2;

        void make(int rate, float factor, float width) {
            final float[] delaytblLc = {0.0043f, 0.0215f, 0.0225f, 0.0268f, 0.0270f, 0.0298f,
                    0.0458f, 0.0485f, 0.0572f, 0.0587f, 0.0595f, 0.0612f, 0.0707f, 0.0708f,
                    0.0726f, 0.0741f, 0.0753f, 0.0797f};
            final float[] delaytblRc = {0.0053f, 0.0225f, 0.0235f, 0.0278f, 0.0290f, 0.0288f,
                    0.0468f, 0.0475f, 0.0582f, 0.0577f, 0.0575f, 0.0622f, 0.0697f, 0.0718f,
                    0.0736f, 0.0751f, 0.0763f, 0.0817f};
            wet1 = width * 0.5f + 0.5f;
            wet2 = (1.0f - width) * 0.5f;
            int lrdelay = (int) (0.0002f * (float) rate);
            delayRl.make(lrdelay);
            delayLr.make(lrdelay);
            allpassXl.makeApf(rate, 740.0f, 4.0f);
            allpassXr.makeApf(rate, 740.0f, 4.0f);
            allpassL.makeApf(rate, 150.0f, 4.0f);
            allpassR.makeApf(rate, 150.0f, 4.0f);
            float f = factor * (float) rate;
            for (int i = 0; i < 18; i++) {
                delaytblL[i] = (int) (delaytblLc[i] * f);
                delaytblR[i] = (int) (delaytblRc[i] * f);
            }
            delayPwl.make(delaytblL[17] + 10);
            delayPwr.make(delaytblR[17] + 10);
            lpfL.makeLpf(rate, 20000.0f);
            lpfR.makeLpf(rate, 20000.0f);
            hpfL.makeHpf(rate, 4.0f);
            hpfR.makeHpf(rate, 4.0f);
        }

        void step(float inputL, float inputR, float[] out) {
            final float[] gaintblL = {0.841f, 0.504f, 0.491f, 0.379f, 0.380f, 0.346f, 0.289f,
                    0.272f, 0.192f, 0.193f, 0.217f, 0.181f, 0.180f, 0.181f, 0.176f, 0.142f,
                    0.167f, 0.134f};
            final float[] gaintblR = {0.842f, 0.506f, 0.489f, 0.382f, 0.300f, 0.346f, 0.290f,
                    0.271f, 0.193f, 0.192f, 0.217f, 0.195f, 0.192f, 0.166f, 0.186f, 0.131f,
                    0.168f, 0.133f};
            float wetL = 0;
            float wetR = 0;
            delayPwl.step(inputL);
            delayPwr.step(inputR);
            for (int i = 0; i < 18; i++) {
                wetL += gaintblL[i] * delayPwl.get(delaytblL[i]);
                wetR += gaintblR[i] * delayPwr.get(delaytblR[i]);
            }
            float l = delayRl.step(inputR + wetR);
            l = allpassXl.step(l);
            l = allpassL.step(wet1 * wetL + wet2 * l);
            l = hpfL.step(l);
            l = lpfL.step(l);
            float r = delayLr.step(inputL + wetL);
            r = allpassXr.step(r);
            r = allpassR.step(wet1 * wetR + wet2 * r);
            r = hpfR.step(r);
            r = lpfR.step(r);
            out[0] = l;
            out[1] = r;
        }
    }

    /** 对应 {@code sf_rv_oversample_st}。 */
    static final class Oversample {
        int factor;
        final Biquad lpfU = new Biquad();
        final Biquad lpfD = new Biquad();

        void make(int factor) {
            this.factor = clampi(factor, 1, OF);
            lpfU.makeLpfQ(2 * this.factor, 1.0f, 0.5773502691896258f);
            lpfD.makeLpfQ(2 * this.factor, 1.0f, 0.5773502691896258f);
        }

        void stepUp(float input, float[] output) {
            if (factor == 1) {
                output[0] = input;
                return;
            }
            output[0] = lpfU.step(input * factor);
            for (int i = 1; i < factor; i++) {
                output[i] = lpfU.step(0);
            }
        }

        float stepDown(float[] input) {
            if (factor == 1) {
                return input[0];
            }
            for (int i = 0; i < factor; i++) {
                lpfD.step(input[i]);
            }
            return input[0];
        }
    }

    // ──────────────────────────── 主状态（sf_reverb_state_st） ────────────────────────────

    private final EarlyRef earlyref = new EarlyRef();
    private final Oversample oversampleL = new Oversample();
    private final Oversample oversampleR = new Oversample();
    private final DcCut dccutL = new DcCut();
    private final DcCut dccutR = new DcCut();
    private final Noise noise = new Noise();
    private final Lfo lfo1 = new Lfo();
    private final Iir1 lfo1Lpf = new Iir1();
    private final AllPassM[] diffL = new AllPassM[10];
    private final AllPassM[] diffR = new AllPassM[10];
    private final AllPass[] crossL = new AllPass[4];
    private final AllPass[] crossR = new AllPass[4];
    private final Iir1 clpfL = new Iir1();
    private final Iir1 clpfR = new Iir1();
    private final Delay cdelayL = new Delay();
    private final Delay cdelayR = new Delay();
    private final Biquad bassApL = new Biquad();
    private final Biquad bassApR = new Biquad();
    private final Biquad bassLpL = new Biquad();
    private final Biquad bassLpR = new Biquad();
    private final Iir1 dampLpL = new Iir1();
    private final Iir1 dampLpR = new Iir1();
    private final AllPassM dampAp1L = new AllPassM();
    private final AllPassM dampAp1R = new AllPassM();
    private final Delay dampDL = new Delay();
    private final Delay dampDR = new Delay();
    private final AllPassM dampAp2L = new AllPassM();
    private final AllPassM dampAp2R = new AllPassM();
    private final Delay cBassD1L = new Delay();
    private final Delay cBassD1R = new Delay();
    private final AllPass2 cBassAp1L = new AllPass2();
    private final AllPass2 cBassAp1R = new AllPass2();
    private final Delay cBassD2L = new Delay();
    private final Delay cBassD2R = new Delay();
    private final AllPass3 cBassAp2L = new AllPass3();
    private final AllPass3 cBassAp2R = new AllPass3();
    private final Lfo lfo2 = new Lfo();
    private final Iir1 lfo2Lpf = new Iir1();
    private final Comb combL = new Comb();
    private final Comb combR = new Comb();
    private final Biquad lastLpfL = new Biquad();
    private final Biquad lastLpfR = new Biquad();
    private final Delay lastDelayL = new Delay();
    private final Delay lastDelayR = new Delay();
    private final Delay inpDelayL = new Delay();
    private final Delay inpDelayR = new Delay();
    private final int[] outco = new int[32];
    private float loopdecay;
    private float wet1out;
    private float wet2out;
    private float wander;
    private float bassb;
    private float ertolate;
    private float erefwet;
    private float dry;

    /** 过采样临时缓冲（{@code float osL[SF_REVERB_OF]}）。 */
    private final float[] osL = new float[OF];
    private final float[] osR = new float[OF];
    /** 早期反射输出暂存。 */
    private final float[] earlyOut = new float[2];

    private boolean configured;

    public JdspReverb() {
        for (int i = 0; i < 10; i++) {
            diffL[i] = new AllPassM();
            diffR[i] = new AllPassM();
        }
        for (int i = 0; i < 4; i++) {
            crossL[i] = new AllPass();
            crossR[i] = new AllPass();
        }
    }

    // ──────────────────────────── sf_advancereverb ────────────────────────────

    /**
     * 对应 {@code sf_advancereverb(rv, rate, oversamplefactor, ertolate, erefwet, dry,
     * ereffactor, erefwidth, width, wet, wander, bassb, spin, inputlpf, basslpf, damplpf,
     * outputlpf, rt60, delay)}。所有延迟长度按 {@code totfactor = osrate / 34125} 缩放并取质数。
     */
    public void configure(int rate, int oversampleFactor, float pErtolate, float pErefwet,
                          float pDry, float pEreffactor, float pErefwidth, float pWidth,
                          float pWet, float pWander, float pBassb, float pSpin,
                          float pInputlpf, float pBasslpf, float pDamplpf,
                          float pOutputlpf, float pRt60, float pDelay) {
        ertolate = pErtolate;
        erefwet = db2lin(pErefwet);
        dry = db2lin(pDry);
        float wet = db2lin(pWet);
        wet1out = wet * (pWidth * 0.5f + 0.5f);
        wet2out = wet * ((1.0f - pWidth) * 0.5f);
        wander = pWander;
        bassb = pBassb;
        earlyref.make(rate, pEreffactor, pErefwidth);
        oversampleL.make(oversampleFactor);
        oversampleR.make(oversampleFactor);
        int osrate = rate * oversampleL.factor;
        dccutL.make(osrate, 5.0f);
        dccutR.make(osrate, 5.0f);
        noise.make();
        lfo1.make(osrate, pSpin);
        lfo1Lpf.makeLpf(osrate, 20.0f);
        lfo2.make(osrate, (float) Math.sqrt(100.0f - (10.0f - pSpin) * (10.0f - pSpin)) * 0.5f);
        lfo2Lpf.makeLpf(osrate, 12.0f);
        final int[] diffLc = {617, 535, 434, 347, 218, 162, 144, 122, 109, 74};
        final int[] diffRc = {603, 547, 416, 364, 236, 162, 140, 131, 111, 79};
        int totfactor = osrate / 34125;
        int msize = nextPrime(10 * osrate / 34125);
        for (int i = 0; i < 10; i++) {
            diffL[i].make(nextPrime(diffLc[i] * totfactor), msize, -0.78f, 1.0f);
            diffR[i].make(nextPrime(diffRc[i] * totfactor), msize, -0.78f, 1.0f);
        }
        final int[] crossLc = {430, 341, 264, 174};
        final int[] crossRc = {447, 324, 247, 191};
        for (int i = 0; i < 4; i++) {
            crossL[i].make(nextPrime(crossLc[i] * totfactor), 0.78f, 1.0f);
            crossR[i].make(nextPrime(crossRc[i] * totfactor), 0.78f, 1.0f);
        }
        clpfL.makeLpf(osrate, pInputlpf);
        clpfR.makeLpf(osrate, pInputlpf);
        cdelayL.make(nextPrime(1572 * totfactor));
        cdelayR.make(nextPrime(16 * totfactor));
        dampDL.make(nextPrime(2 * totfactor));
        dampDR.make(nextPrime(totfactor));
        cBassD1L.make(nextPrime(1055 * totfactor));
        cBassD1R.make(nextPrime(1460 * totfactor));
        cBassD2L.make(nextPrime(344 * totfactor));
        cBassD2R.make(nextPrime(500 * totfactor));
        bassApL.makeApf(osrate, 150.0f, 4.0f);
        bassApR.makeApf(osrate, 150.0f, 4.0f);
        bassLpL.makeLpf(osrate, pBasslpf, 2.0f);
        bassLpR.makeLpf(osrate, pBasslpf, 2.0f);
        dampLpL.makeLpf(osrate, pDamplpf);
        dampLpR.makeLpf(osrate, pDamplpf);
        float decay0 = (float) Math.pow(10.0f, (float) Math.log10(0.237f) / pRt60);
        float decay1 = (float) Math.pow(10.0f, (float) Math.log10(0.938f) / pRt60);
        float decay2 = (float) Math.pow(10.0f, (float) Math.log10(0.844f) / pRt60);
        float decay3 = (float) Math.pow(10.0f, (float) Math.log10(0.906f) / pRt60);
        loopdecay = decay0;
        msize = nextPrime(32 * totfactor);
        dampAp1L.make(nextPrime(239 * totfactor), msize, 0.375f, decay2);
        dampAp1R.make(nextPrime(205 * totfactor), msize, 0.375f, decay2);
        dampAp2L.make(nextPrime(392 * totfactor), msize, 0.312f, decay3);
        dampAp2R.make(nextPrime(329 * totfactor), msize, 0.312f, decay3);
        cBassAp1L.make(nextPrime(1944 * totfactor), nextPrime(612 * totfactor),
                0.250f, 0.406f, decay1, decay2);
        cBassAp1R.make(nextPrime(2032 * totfactor), nextPrime(368 * totfactor),
                0.250f, 0.406f, decay1, decay2);
        cBassAp2L.make(nextPrime(1212 * totfactor), nextPrime(121 * totfactor),
                nextPrime(816 * totfactor), nextPrime(1264 * totfactor),
                0.250f, 0.250f, 0.406f, decay1, decay1, decay2);
        cBassAp2R.make(nextPrime(1452 * totfactor), nextPrime(5 * totfactor),
                nextPrime(688 * totfactor), nextPrime(1340 * totfactor),
                0.250f, 0.250f, 0.406f, decay1, decay1, decay2);
        final int[] outco0 = {
                1, 40, 192, 276, 321, 110, 468, 1572, 121, 480, 103, 26, 780, 1200, 310, 780,
                625, 468, 312, 24, 36, 790, 189, 8, 10, 359, 30, 10, 109, 1310, 800, 10};
        for (int i = 0; i < 32; i++) {
            outco[i] = outco0[i] * totfactor;
        }
        combL.make(nextPrime(22 * osrate / 1000));
        combR.make(nextPrime(22 * osrate / 1000));
        lastLpfL.makeLpf(osrate, pOutputlpf, 1.0f);
        lastLpfR.makeLpf(osrate, pOutputlpf, 1.0f);
        int delaysamp = (int) (osrate * pDelay);
        if (delaysamp >= 0) {
            inpDelayL.make(0);
            inpDelayR.make(0);
            lastDelayL.make(delaysamp);
            lastDelayR.make(delaysamp);
        } else {
            inpDelayL.make(-delaysamp);
            inpDelayR.make(-delaysamp);
            lastDelayL.make(0);
            lastDelayR.make(0);
        }
        configured = true;
    }

    // ──────────────────────────── sf_presetreverb（19 预设） ────────────────────────────

    /** 预设索引（对应上游 {@code sf_reverb_preset} 枚举）。 */
    public static final int PRESET_DEFAULT = 0;
    public static final int PRESET_SMALL_HALL_1 = 1;
    public static final int PRESET_SMALL_HALL_2 = 2;
    public static final int PRESET_MEDIUM_HALL_1 = 3;
    public static final int PRESET_MEDIUM_HALL_2 = 4;
    public static final int PRESET_LARGE_HALL_1 = 5;
    public static final int PRESET_LARGE_HALL_2 = 6;
    public static final int PRESET_SMALL_ROOM_1 = 7;
    public static final int PRESET_SMALL_ROOM_2 = 8;
    public static final int PRESET_MEDIUM_ROOM_1 = 9;
    public static final int PRESET_MEDIUM_ROOM_2 = 10;
    public static final int PRESET_LARGE_ROOM_1 = 11;
    public static final int PRESET_LARGE_ROOM_2 = 12;
    public static final int PRESET_MEDIUM_ER_1 = 13;
    public static final int PRESET_MEDIUM_ER_2 = 14;
    public static final int PRESET_PLATE_HIGH = 15;
    public static final int PRESET_PLATE_LOW = 16;
    public static final int PRESET_LONG_REVERB_1 = 17;
    public static final int PRESET_LONG_REVERB_2 = 18;

    /** 预设数量。 */
    public static final int PRESET_COUNT = 19;

    /** 预设名（与上游枚举一一对应，供 UI 展示）。 */
    public static final String[] PRESET_NAMES = {
            "Default", "Small Hall 1", "Small Hall 2", "Medium Hall 1", "Medium Hall 2",
            "Large Hall 1", "Large Hall 2", "Small Room 1", "Small Room 2",
            "Medium Room 1", "Medium Room 2", "Large Room 1", "Large Room 2",
            "Medium ER 1", "Medium ER 2", "Plate High", "Plate Low",
            "Long Reverb 1", "Long Reverb 2"};

    /**
     * 上游 {@code sf_presetreverb} 的预设参数表（19 行 × 16 参数）。
     * 列序：osf, ertolate, erefwet, dry, ereffactor, erefwidth, width, wet, wander,
     * bassb, spin, inputlpf, basslpf, damplpf, outputlpf, rt60, delay。
     */
    private static final float[][] PRESET_TABLE = {
            {1, 0.40f, -9.0f, -7, 1.6f, 0.7f, 1.0f, -0, 0.25f, 0.15f, 0.7f, 17000, 500, 7000, 10000, 3.2f, 0.020f},
            {1, 0.30f, -9.0f, -7, 1.0f, 0.7f, 1.0f, -8, 0.3f, 0.25f, 0.7f, 18000, 600, 9000, 17000, 2.1f, 0.010f},
            {1, 0.30f, -9.0f, -7, 1.0f, 0.7f, 1.0f, -8, 0.25f, 0.20f, 0.5f, 18000, 600, 7000, 9000, 2.3f, 0.010f},
            {1, 0.30f, -9.0f, -7, 1.2f, 0.7f, 1.0f, -8, 0.25f, 0.20f, 0.7f, 18000, 500, 8000, 16000, 2.8f, 0.010f},
            {1, 0.30f, -9.0f, -7, 1.2f, 0.7f, 1.0f, -8, 0.2f, 0.15f, 0.5f, 18000, 500, 6000, 8000, 2.9f, 0.010f},
            {1, 0.20f, -9.0f, -7, 1.4f, 0.7f, 1.0f, -8, 0.15f, 0.20f, 1.0f, 18000, 400, 9000, 14000, 3.8f, 0.018f},
            {1, 0.20f, -9.0f, -7, 1.5f, 0.7f, 1.0f, -8, 0.20f, 0.20f, 0.5f, 18000, 400, 5000, 7000, 4.2f, 0.018f},
            {1, 0.70f, -8.0f, -7, 0.7f, -0.4f, 0.8f, -8, 0.20f, 0.30f, 1.6f, 18000, 1000, 18000, 18000, 0.5f, 0.005f},
            {1, 0.70f, -8.0f, -7, 0.8f, 0.6f, 0.9f, -8, 0.30f, 0.30f, 0.4f, 18000, 300, 10000, 18000, 0.5f, 0.005f},
            {1, 0.50f, -8.0f, -7, 1.2f, -0.4f, 0.8f, -8, 0.20f, 0.10f, 1.6f, 18000, 1000, 18000, 18000, 0.8f, 0.008f},
            {1, 0.50f, -8.0f, -7, 1.2f, 0.6f, 0.9f, -8, 0.30f, 0.10f, 0.4f, 18000, 300, 10000, 18000, 1.2f, 0.016f},
            {1, 0.20f, -8.0f, -7, 2.2f, -0.4f, 0.9f, -8, 0.20f, 0.10f, 1.6f, 18000, 1000, 16000, 18000, 1.8f, 0.010f},
            {1, 0.20f, -8.0f, -7, 2.2f, 0.6f, 0.9f, -8, 0.30f, 0.10f, 0.4f, 18000, 500, 9000, 18000, 1.9f, 0.020f},
            {1, 0.50f, -7.0f, -6, 1.2f, -0.4f, 0.8f, -70, 0.20f, 0.10f, 1.6f, 18000, 1000, 18000, 18000, 0.8f, 0.008f},
            {1, 0.50f, -7.0f, -6, 1.2f, 0.6f, 0.9f, -70, 0.30f, 0.10f, 0.4f, 18000, 300, 10000, 18000, 1.2f, 0.016f},
            {2, 0.00f, -30.0f, -12, 1.0f, 1.0f, 1.0f, -8, 0.20f, 0.10f, 1.6f, 18000, 1000, 16000, 18000, 1.8f, 0.000f},
            {2, 0.00f, -30.0f, -12, 1.0f, 1.0f, 1.0f, -8, 0.30f, 0.20f, 0.4f, 18000, 500, 9000, 18000, 1.9f, 0.000f},
            {2, 0.10f, -16.0f, -14, 1.0f, 0.1f, 1.0f, -5, 0.35f, 0.05f, 1.0f, 18000, 100, 10000, 18000, 12.0f, 0.000f},
            {2, 0.10f, -16.0f, -14, 1.0f, 0.1f, 1.0f, -5, 0.40f, 0.05f, 1.0f, 18000, 100, 9000, 18000, 30.0f, 0.000f},
    };

    /**
     * 对应 {@code sf_presetreverb(rv, rate, preset)}。
     *
     * @param rate   采样率（Hz）
     * @param preset 预设索引 0..18（越界按 0 处理，与上游 switch 的 default 落空不同——
     *               上游越界时什么都不做，这里为健壮性回退到 DEFAULT）
     */
    public void configure(int rate, int preset) {
        int index = (preset >= 0 && preset < PRESET_TABLE.length) ? preset : 0;
        float[] p = PRESET_TABLE[index];
        configure(rate, (int) p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9],
                p[10], p[11], p[12], p[13], p[14], p[15], p[16]);
    }

    /** 自定义参数覆盖通道（对应 {@code sf_advancereverb} 的 16 个入口参数，供高级调参）。 */
    public void configureCustom(int rate, float[] params16) {
        configure(rate, (int) params16[0], params16[1], params16[2], params16[3], params16[4],
                params16[5], params16[6], params16[7], params16[8], params16[9], params16[10],
                params16[11], params16[12], params16[13], params16[14], params16[15],
                params16[16]);
    }

    /** 预设表原始行（供 UI 展示/自定义初值），长度 17。 */
    public static float[] presetRow(int preset) {
        int index = (preset >= 0 && preset < PRESET_TABLE.length) ? preset : 0;
        return PRESET_TABLE[index].clone();
    }

    /** 是否已经完成过一次 {@link #configure}。 */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * 复位到「未配置」状态（下一次 {@link #process} 直接旁路）。
     *
     * <p>注意：<b>不</b>重置内部噪声 RNG——上游的 {@code randfloat()} 用 {@code static}
     * 局部变量，跨 configure 持续前进；重置会改变后续噪声序列，破坏与上游的逐样本一致。</p>
     */
    public void reset() {
        configured = false;
    }

    // ──────────────────────────── sf_reverb_process ────────────────────────────

    /**
     * 对应 {@code sf_reverb_process(rv, inputL, inputR, &outputL, &outputR)}。
     * 立体声原地处理（左右交错，{@code frames} 帧）。
     */
    public void process(float[] interleaved, int frames) {
        if (!configured) {
            return;
        }
        for (int i = 0; i < frames; i++) {
            float inL = interleaved[i * 2];
            float inR = interleaved[i * 2 + 1];
            earlyref.step(inL, inR, earlyOut);
            float outLRef = earlyOut[0];
            float outRRef = earlyOut[1];
            float erL = outLRef * ertolate + inL;
            float erR = outRRef * ertolate + inR;
            oversampleL.stepUp(erL, osL);
            oversampleR.stepUp(erR, osR);
            for (int i2 = 0; i2 < oversampleL.factor; i2++) {
                float outL = dccutL.step(osL[i2]);
                float outR = dccutR.step(osR[i2]);
                float mnoise = noise.step(this);
                float lfo = (lfo1.step() + MOD_NOISE_1 * mnoise) * wander;
                lfo = lfo1Lpf.step(lfo);
                mnoise *= MOD_NOISE_2;
                for (int d = 0, s = -1; d < 10; d++, s = -s) {
                    outL = diffL[d].step(outL, lfo * s, mnoise);
                    outR = diffR[d].step(outR, lfo, mnoise * s);
                }
                float crossLV = outL;
                float crossRV = outR;
                for (int c = 0; c < 4; c++) {
                    crossLV = crossL[c].step(crossLV);
                    crossRV = crossR[c].step(crossRV);
                }
                outL = clpfL.step(outL + CROSSFEED * crossRV);
                outR = clpfR.step(outR + CROSSFEED * crossLV);
                crossLV = cdelayL.getLast();
                crossRV = cdelayR.getLast();
                outL += loopdecay * (crossRV + bassb
                        * bassLpL.step(bassApL.step(crossRV)));
                outR += loopdecay * (crossLV + bassb
                        * bassLpR.step(bassApR.step(crossLV)));
                outL = dampAp2L.step(
                        dampDL.step(
                                dampAp1L.step(dampLpL.step(outL), lfo, mnoise)),
                        -lfo, -mnoise);
                outR = dampAp2R.step(
                        dampDR.step(
                                dampAp1R.step(dampLpR.step(outR), -lfo, -mnoise)),
                        lfo, mnoise);
                cdelayL.step(
                        cBassAp2L.step(
                                cBassD2L.step(
                                        cBassAp1L.step(cBassD1L.step(outL))),
                                lfo));
                cdelayR.step(
                        cBassAp2R.step(
                                cBassD2R.step(
                                        cBassAp1R.step(cBassD1R.step(outR))),
                                -lfo));
                float d1 = cBassD1L.get(outco[0]);
                float d2 = cBassD2L.get(outco[1])
                        - cBassD2R.get(outco[2])
                        + cBassD2L.get(outco[3])
                        - cdelayR.get(outco[4])
                        - cBassD1R.get(outco[5])
                        - cBassD2R.get(outco[6]);
                float d3 = cdelayL.get(outco[7])
                        + cBassAp1L.get1(outco[8])
                        + cBassAp1L.get2(outco[9])
                        - cBassAp1R.get2(outco[10])
                        + cBassAp2L.get1(outco[11])
                        + cBassAp2L.get2(outco[12])
                        + cBassAp2L.get3(outco[13])
                        - cBassAp2R.get2(outco[14]);
                float d4 = cdelayL.get(outco[15]);
                float b1 = cBassD1R.get(outco[16]);
                float b2 = cBassD2R.get(outco[17])
                        - cBassD2L.get(outco[18])
                        + cBassD2R.get(outco[19])
                        - cdelayL.get(outco[20])
                        - cBassD1L.get(outco[21])
                        - cBassD2L.get(outco[22]);
                float b3 = cdelayR.get(outco[23])
                        + cBassAp1R.get1(outco[24])
                        + cBassAp1R.get2(outco[25])
                        - cBassAp1L.get2(outco[26])
                        + cBassAp2R.get1(outco[27])
                        + cBassAp2R.get2(outco[28])
                        + cBassAp2R.get3(outco[29])
                        - cBassAp2L.get2(outco[30]);
                float b4 = cdelayR.get(outco[31]);
                float dv = d1 * 0.469f + d2 * 0.219f + d3 * 0.064f + d4 * 0.045f;
                float bv = b1 * 0.469f + b2 * 0.219f + b3 * 0.064f + b4 * 0.045f;
                lfo = lfo2Lpf.step(lfo2.step() * wander);
                outL = combL.step(dv, lfo);
                outR = combR.step(bv, -lfo);
                outL = lastDelayL.step(lastLpfL.step(outL));
                outR = lastDelayR.step(lastLpfR.step(outR));
                osL[i2] = outL * wet1out + outR * wet2out
                        + inpDelayL.step(osL[i2]) * dry;
                osR[i2] = outR * wet1out + outL * wet2out
                        + inpDelayR.step(osR[i2]) * dry;
            }
            float outL = oversampleL.stepDown(osL);
            float outR = oversampleR.stepDown(osR);
            outL += outLRef * erefwet + inL * dry;
            outR += outRRef * erefwet + inR * dry;
            interleaved[i * 2] = outL;
            interleaved[i * 2 + 1] = outR;
        }
    }
}
