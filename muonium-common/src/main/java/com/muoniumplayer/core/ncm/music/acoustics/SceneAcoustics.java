package com.muoniumplayer.core.ncm.music.acoustics;

import com.muoniumplayer.core.ncm.music.audio.OpenAlReverbBus;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 引擎尾槽的场景声学。直射与反射从同一份原始信号分流,不能把已空间化、
 * 已衰减的直射再当作房间激励。湿声始终由真抽头 + FDN 生成;OpenAL 只做 HRTF。
 *
 * <p>采样线程只交付一个完整计划;音频线程独占滤波与延迟历史。原生上下文和
 * HRTF 数据在后台准备,render 不查世界、不加载模型、不访问文件。</p>
 */
public final class SceneAcoustics {

    private static final double BAND_OPEN_HZ = 20000.0;
    private static final double MIN_BAND_HZ = 120.0;
    private static final double LOG_BAND_OPEN = Math.log10(BAND_OPEN_HZ);
    private static final double BAND_TAU_SEC = 0.22;
    private static final double WET_TAU_SEC = 0.30;
    private static final double TRANSITION_MIN_SEC = 0.10;
    private static final double TRANSITION_MAX_SEC = 1.20;
    private static final double DIRECT_TAU_SEC = 0.08;
    private static final double DELAY_CROSSFADE_SEC = 0.025;
    private static final double PAN_TAU_SEC = 0.025;
    private static final double LEGACY_ITD_SEC = 0.00065;
    private static final double SPEED_OF_SOUND = 343.0;
    // Weighted mono fold retains some phase-opposed content at a single world source.
    private static final float SPEAKER_FOLD_SIDE = 0.18f;
    private static final float SPEAKER_SIDE_GAIN = 0.55f;
    // Reserve headroom for the sum of three convolved PCM16 sources.
    private static final float NATIVE_PCM_SCALE = 0.25f;
    private static final float WET_FLOOR = 0.003f;
    private static final double BUTTERWORTH_Q = 0.7071067811865476;
    private static final double LIMITER_KNEE = 0.89;
    private static final double TAIL_MAKEUP = 2.0;
    private static final double MID_PROTECT_RATIO = 0.45;
    private static final double WET_SHELF_HZ = 150.0;
    private static final double WET_SHELF_KEEP = 0.42;
    private static final double SIDECHAIN_LP_HZ = 110.0;
    private static final float SIDECHAIN_DUCK = 0.82f;
    private static final double SIDECHAIN_ATTACK_SEC = 0.005;
    private static final double SIDECHAIN_RELEASE_SEC = 0.18;
    private static final double SC_ENV_THRESHOLD = 0.04;
    private static final int SPATIAL_BUFFER_FRAMES = 512;

    // ---- 跨线程交接:复合数据只发布一份引用 ----

    private static volatile RoomPlan targetPlan = RoomPlan.idle();
    private static volatile ListenerPose listenerPose = new ListenerPose(0, 0, 0,
            0f, 0f, 1f, 0f, 1f, 0f);
    private static final AtomicBoolean flushRequested = new AtomicBoolean();
    private static final AtomicBoolean preparingSpatialAudio = new AtomicBoolean();
    private static volatile int requestedRate = 48000;
    private static volatile int attemptedRate = -1;

    // ---- 音频线程独占 ----

    private static int ownedRate = -1;
    private static double logBand = LOG_BAND_OPEN;
    private static float wet;
    private static float loudness = 1f;
    private static float dryGain = 1f;
    private static float preDelaySec;
    private static float decaySec;
    private static float decayLowSec;
    private static float decayHighSec;
    private static float tone = SceneResponse.NEUTRAL_TONE;
    private static float directGain = 1f;
    private static double directLogCutoff = LOG_BAND_OPEN;
    private static float directPan;
    private static double directDelayOld;
    private static double directDelayNew;
    private static double wetDelayOld;
    private static double wetDelayNew;
    private static double delayCrossfade;
    private static double delayCrossfadeStep;
    private static double directMidState;
    private static double directSideState;
    private static boolean directWasActive;
    private static boolean wetWasActive;
    private static boolean tailWasActive;
    private static boolean erWasActive;
    private static boolean speakerWasActive;
    private static boolean spatialWasActive;
    private static OpenAlReverbBus renderBus;
    private static double listenerX, listenerY, listenerZ;
    private static float listenerFx, listenerFy, listenerFz, listenerUx, listenerUy, listenerUz;
    private static boolean listenerPoseReady;
    private static double viewX, viewY, viewZ, upX, upY, upZ, rightX, rightY, rightZ;
    private static float spatialMix;
    private static int silentSpatialFrames;
    private static double earLowStep, earHighStep, earLevelStep, earFadeStep;

    private static double[][] bandState = new double[0][];
    private static double cB0, cB1, cB2, cA1, cA2;
    private static double lastCoefBand = -1;
    private static final StereoDelayLine directLine = new StereoDelayLine();
    private static final StereoDelayLine earLine = new StereoDelayLine();
    private static final SpeakerRay centerRay = new SpeakerRay(false);
    private static final SpeakerRay positiveSideRay = new SpeakerRay(true);
    private static final SpeakerRay negativeSideRay = new SpeakerRay(true);
    private static final StereoDelayLine wetPropagation = new StereoDelayLine();
    private static final StereoDelayLine tailPredelay = new StereoDelayLine();
    private static final EarlyReflections reflections = new EarlyReflections();
    private static final FdnTail fdn = new FdnTail();
    private static final float[] erOut = new float[2];
    private static final float[] fdnOut = new float[2];

    private static final float[] wetInput = new float[SPATIAL_BUFFER_FRAMES * 2];
    private static final float[] dryInput = new float[SPATIAL_BUFFER_FRAMES * 2];
    private static final float[] midInput = new float[SPATIAL_BUFFER_FRAMES];
    private static final float[] sideInput = new float[SPATIAL_BUFFER_FRAMES];
    private static final short[] busMono = new short[SPATIAL_BUFFER_FRAMES];
    private static final short[] busSide = new short[SPATIAL_BUFFER_FRAMES * 2];
    private static final float[] busOut = new float[SPATIAL_BUFFER_FRAMES * 2];
    private static double dcXL, dcYL, dcXR, dcYR;
    private static double midBPB0, midBPB1, midBPB2, midBPA1, midBPA2;
    private static double midBPX1, midBPX2, midBPY1, midBPY2;
    private static double shelfCoef, shelfL, shelfR;
    private static double scLpCoef, scLpL, scLpR;
    private static float sidechainGain = 1f;
    private static double dcFeedback;

    private SceneAcoustics() {
    }

    public static void applyTarget(RoomPlan plan) {
        targetPlan = plan == null ? RoomPlan.idle() : plan;
    }

    public static void clearTarget() {
        applyTarget(RoomPlan.idle());
    }

    /** 控制线程只请求清态,所有延迟/原生队列由音频线程在缓冲头清理。 */
    public static void flush() {
        flushRequested.set(true);
    }

    public static void setListenerPose(double ex, double ey, double ez,
                                       float fx, float fy, float fz,
                                       float ux, float uy, float uz) {
        final double rx = (double) fy * uz - (double) fz * uy;
        final double ry = (double) fz * ux - (double) fx * uz;
        final double rz = (double) fx * uy - (double) fy * ux;
        if (Double.isFinite(ex) && Double.isFinite(ey) && Double.isFinite(ez)
                && rx * rx + ry * ry + rz * rz > 1e-10
                && Float.isFinite(fx) && Float.isFinite(fy) && Float.isFinite(fz)
                && Float.isFinite(ux) && Float.isFinite(uy) && Float.isFinite(uz)) {
            listenerPose = new ListenerPose(ex, ey, ez, fx, fy, fz, ux, uy, uz);
        }
    }

    /** 游戏 tick 调用;原生库/HRIR 初始化只进入后台池,失败同一采样率不反复加载。 */
    public static void prepareSpatialAudio() {
        final RoomPlan plan = targetPlan;
        final int rate = requestedRate;
        if (!plan.hasSpeakerAnchor || !plan.useOpenAlReverb || attemptedRate == rate
                || !preparingSpatialAudio.compareAndSet(false, true)) {
            return;
        }
        attemptedRate = rate;
        BackgroundWork.submit(() -> OpenAlReverbBus.createIfAvailable(rate, SPATIAL_BUFFER_FRAMES))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        attemptedRate = -1;
                    }
                    preparingSpatialAudio.set(false);
                });
    }

    /** 一个完整缓冲只读一次计划,不组合不同采样帧的目标量。 */
    public static void render(double[] interleaved, int channels, int sampleRate) {
        if (interleaved == null || channels <= 0 || sampleRate <= 0
                || interleaved.length < channels) {
            return;
        }
        final int frames = interleaved.length / channels;
        final int ch = Math.min(channels, 2);
        final RoomPlan plan = targetPlan;
        requestedRate = sampleRate;
        final boolean reset = flushRequested.getAndSet(false);
        if (sampleRate != ownedRate) {
            rebuild(sampleRate, ch);
        } else if (bandState.length != ch) {
            bandState = new double[ch][4];
        }
        if (reset) {
            doFlush(plan);
        }
        final ListenerPose pose = listenerPose;
        final OpenAlReverbBus ready = plan.hasSpeakerAnchor && plan.useOpenAlReverb
                ? OpenAlReverbBus.get() : null;
        final int blockSize = ready == null ? SPATIAL_BUFFER_FRAMES
                : Math.min(SPATIAL_BUFFER_FRAMES, ready.frameCapacity());
        // Large callbacks use the same bounded scratch and continuous native queue.
        for (int offset = 0; offset < frames;) {
            final int count = Math.min(blockSize, frames - offset);
            renderBlock(interleaved, channels, ch, sampleRate, plan, pose, ready, offset, count);
            offset += count;
        }
    }

    private static void renderBlock(double[] interleaved, int channels, int ch, int sampleRate,
                                    RoomPlan plan, ListenerPose pose, OpenAlReverbBus ready,
                                    int offset, int frames) {
        final double transitionSec = plan.transitionSec;
        final double tau = transitionSec > 0f
                ? Math.max(TRANSITION_MIN_SEC, Math.min(TRANSITION_MAX_SEC, transitionSec))
                : WET_TAU_SEC;
        final double targetLogBand = Math.log10(plan.bandHz);
        final double bandTau = targetLogBand > logBand ? Math.min(0.10, tau)
                : transitionSec > 0f ? tau : BAND_TAU_SEC;
        logBand += (targetLogBand - logBand) * step(frames, sampleRate, bandTau);
        final float wetStep = (float) step(frames, sampleRate, tau);
        wet += (plan.wet - wet) * wetStep;
        loudness += (plan.loudness - loudness) * wetStep;
        dryGain += (plan.dryGain - dryGain) * wetStep;
        preDelaySec += (plan.predelaySec - preDelaySec) * wetStep;
        decaySec += (plan.decaySec - decaySec) * wetStep;
        decayLowSec += (plan.decayLowSec - decayLowSec) * wetStep;
        decayHighSec += (plan.decayHighSec - decayHighSec) * wetStep;
        tone += (plan.tone - tone) * wetStep;
        final double directStep = step(frames, sampleRate, DIRECT_TAU_SEC);
        directGain += (plan.directGain - directGain) * directStep;
        final double targetLogCutoff = Math.log10(plan.directCutoffHz);
        directLogCutoff += (targetLogCutoff - directLogCutoff) * step(frames, sampleRate,
                targetLogCutoff > directLogCutoff ? 0.04 : DIRECT_TAU_SEC);
        directPan += (plan.directPan - directPan) * step(frames, sampleRate, PAN_TAU_SEC);

        final boolean bandActive = logBand < LOG_BAND_OPEN - 0.001;
        final boolean tailActive = wet > WET_FLOOR;
        final boolean erActive = plan.tapCount > 0;
        final boolean wetActive = tailActive || erActive;
        final boolean directActive = plan.hasSpeakerAnchor || directGain < 0.999f
                || directLogCutoff < LOG_BAND_OPEN - 0.001 || Math.abs(directPan) > 0.001f;
        final boolean loudActive = dryGain < 0.999f || loudness < 0.999f;
        if (!bandActive && !wetActive && !directActive && !loudActive) {
            if (wetWasActive) {
                clearWetHistory();
            }
            if (spatialWasActive && renderBus != null && !renderBus.isBroken()) {
                renderBus.flush();
            }
            directWasActive = wetWasActive = tailWasActive = erWasActive = speakerWasActive = spatialWasActive = false;
            listenerPoseReady = false;
            spatialMix = 0f;
            return;
        }
        if (bandActive && lastCoefBand != logBand) {
            designLowpass(Math.pow(10.0, logBand), sampleRate);
            lastCoefBand = logBand;
        }
        if (wetActive && !wetWasActive) {
            clearWetHistory();
        }
        if (tailActive && !tailWasActive) {
            clearTailHistory();
        }
        tailWasActive = tailActive;
        if (erActive && !erWasActive && !tailActive) {
            reflections.flush();
        }
        erWasActive = erActive;
        wetWasActive = wetActive;
        if (directActive && (!directWasActive || plan.hasSpeakerAnchor != speakerWasActive)) {
            clearDirectHistory();
            wetPropagation.flush();
            directDelayOld = directDelayNew = plan.hasSpeakerAnchor
                    ? plan.directPathMeters / SPEED_OF_SOUND * sampleRate : 0.0;
            wetDelayOld = wetDelayNew = plan.hasSpeakerAnchor
                    ? plan.directDistanceMeters / SPEED_OF_SOUND * sampleRate : 0.0;
            delayCrossfade = 1.0;
            delayCrossfadeStep = 1.0;
        }
        directWasActive = directActive;
        speakerWasActive = plan.hasSpeakerAnchor;

        if (plan.hasSpeakerAnchor) {
            // Latch only at a completed fade. No intermediate EMA delay or sliding read head.
            final double nextDirectDelay = plan.directPathMeters / SPEED_OF_SOUND * sampleRate;
            final double nextWetDelay = plan.directDistanceMeters / SPEED_OF_SOUND * sampleRate;
            if (delayCrossfade >= 1.0
                    && (Math.abs(nextDirectDelay - directDelayNew) > 0.5
                    || Math.abs(nextWetDelay - wetDelayNew) > 0.5)) {
                directDelayOld = directDelayNew;
                wetDelayOld = wetDelayNew;
                directDelayNew = nextDirectDelay;
                wetDelayNew = nextWetDelay;
                delayCrossfade = 0.0;
                delayCrossfadeStep = 1.0 / (sampleRate * DELAY_CROSSFADE_SEC);
            }
        } else {
            directDelayOld = directDelayNew = wetDelayOld = wetDelayNew = 0.0;
            delayCrossfade = 1.0;
            listenerPoseReady = false;
        }
        final boolean directLpOpen = directLogCutoff >= LOG_BAND_OPEN - 0.001;
        final double directLpCoef = 1.0 - Math.exp(-2.0 * Math.PI
                * Math.min(Math.pow(10.0, directLogCutoff), sampleRate * 0.45) / sampleRate);
        final double dx, dy, dz, sideX, sideZ;
        if (plan.hasSpeakerAnchor) {
            updateListener(pose, frames, sampleRate);
            // This is the chosen arrival point, i.e. the wall edge when diffraction won.
            // Live position changes direction; the scene plan still owns gain, Fc and time.
            dx = plan.speakerX - listenerX;
            dy = plan.speakerY - listenerY;
            dz = plan.speakerZ - listenerZ;
            final double horizontal = Math.hypot(dx, dz);
            final double widthScale = DirectPath.SPEAKER_HALF_WIDTH_METERS
                    / Math.max(DirectPath.SPEAKER_HALF_WIDTH_METERS, horizontal);
            sideX = dz * widthScale;
            sideZ = -dx * widthScale;
            centerRay.update(dx, dy, dz, sampleRate);
            positiveSideRay.update(dx + sideX, dy, dz + sideZ, sampleRate);
            negativeSideRay.update(dx - sideX, dy, dz - sideZ, sampleRate);
        } else {
            dx = dy = dz = sideX = sideZ = 0.0;
        }
        final double legacyItd = directPan * LEGACY_ITD_SEC * sampleRate;
        final double legacyGainL = DirectPath.gainL(directPan) * Math.sqrt(2.0) * directGain;
        final double legacyGainR = DirectPath.gainR(directPan) * Math.sqrt(2.0) * directGain;

        // 同一原始信号分三路:直射 M/S、反射激励、其他声道的整体响度。
        for (int f = 0; f < frames; f++) {
            final int index = (offset + f) * channels;
            double l = interleaved[index];
            double r = ch > 1 ? interleaved[index + 1] : l;
            if (bandActive) {
                l = biquad(l, bandState[0]);
                r = ch > 1 ? biquad(r, bandState[1]) : l;
            }
            final float mid = (float) ((l + r) * 0.5);
            final float side = (float) ((l - r) * 0.5);
            final float source = plan.hasSpeakerAnchor ? mid + SPEAKER_FOLD_SIDE * side : mid;
            final double delayBlend = plan.hasSpeakerAnchor
                    ? Math.min(1.0, delayCrossfade + delayCrossfadeStep * (f + 1.0)) : 1.0;
            wetPropagation.write(plan.hasSpeakerAnchor ? source : (float) l,
                    plan.hasSpeakerAnchor ? source : (float) r);
            final float wetOldL = wetPropagation.left(wetDelayOld);
            final float wetOldR = wetPropagation.right(wetDelayOld);
            final float wetNewL = wetPropagation.left(wetDelayNew);
            final float wetNewR = wetPropagation.right(wetDelayNew);
            wetInput[f * 2] = (float) (wetOldL + (wetNewL - wetOldL) * delayBlend);
            wetInput[f * 2 + 1] = (float) (wetOldR + (wetNewR - wetOldR) * delayBlend);
            if (directActive) {
                directMidState = directLpOpen ? source
                        : directMidState + directLpCoef * (source - directMidState);
                directSideState = directLpOpen ? side
                        : directSideState + directLpCoef * (side - directSideState);
                directLine.write((float) directMidState, (float) directSideState);
                if (plan.hasSpeakerAnchor) {
                    midInput[f] = blendDirectDelay(false, delayBlend);
                    sideInput[f] = blendDirectDelay(true, delayBlend) * SPEAKER_SIDE_GAIN;
                    earLine.write(midInput[f], sideInput[f]);
                    dryInput[f * 2] = (centerRay.left.sample() + positiveSideRay.left.sample()
                            - negativeSideRay.left.sample()) * directGain;
                    dryInput[f * 2 + 1] = (centerRay.right.sample() + positiveSideRay.right.sample()
                            - negativeSideRay.right.sample()) * directGain;
                } else {
                    // Preserve manually authored, non-anchor pan/gain plans.
                    final double ageL = Math.max(0.0, legacyItd);
                    final double ageR = Math.max(0.0, -legacyItd);
                    dryInput[f * 2] = (float) ((directLine.left(ageL) + directLine.right(ageL)) * legacyGainL);
                    dryInput[f * 2 + 1] = (float) ((directLine.left(ageR) - directLine.right(ageR)) * legacyGainR);
                }
            } else {
                dryInput[f * 2] = (float) l;
                dryInput[f * 2 + 1] = (float) r;
            }
        }
        delayCrossfade = plan.hasSpeakerAnchor
                ? Math.min(1.0, delayCrossfade + delayCrossfadeStep * frames) : 1.0;

        // 后台未准备好/无 HRTF/熔断:本缓冲保留已准备的双耳降级声,不把零读回写入输出。
        final boolean spatialActive = renderSpatial(ready, frames, sampleRate, dx, dy, dz, sideX, sideZ);
        if (!spatialActive && spatialWasActive && renderBus != null && !renderBus.isBroken()) {
            renderBus.flush();
        }
        spatialWasActive = spatialActive;
        if (wetActive) {
            if (erActive) {
                reflections.update(plan, frames);
            }
            if (tailActive) {
                final double low = decayLowSec > 0 ? decayLowSec : decaySec;
                final double high = decayHighSec > 0 ? decayHighSec : decaySec * (1.0 - 0.45 * tone);
                fdn.update(low, decaySec, high);
                fdn.setDiffusion(plan.diffusion);
                fdn.advanceModulation(frames, sampleRate);
            }
        }

        final double predelay = preDelaySec * sampleRate;
        final float erBoost = AcousticsDebugTuning.erBoost(1.4f);
        final float tailMakeup = AcousticsDebugTuning.tailMakeup((float) TAIL_MAKEUP);
        final double scAttack = step(1, sampleRate, SIDECHAIN_ATTACK_SEC);
        final double scRelease = step(1, sampleRate, SIDECHAIN_RELEASE_SEC);
        for (int f = 0; f < frames; f++) {
            float erL = 0f;
            float erR = 0f;
            float tailL = 0f;
            float tailR = 0f;
            if (wetActive) {
                final float inL = wetInput[f * 2];
                final float inR = wetInput[f * 2 + 1];
                final double blockedL = inL - dcXL + dcFeedback * dcYL;
                final double blockedR = inR - dcXR + dcFeedback * dcYR;
                dcXL = inL; dcYL = blockedL;
                dcXR = inR; dcYR = blockedR;
                if (erActive) {
                    reflections.process((float) blockedL, (float) blockedR, erOut);
                }
                if (tailActive) {
                    tailPredelay.write((float) blockedL, (float) blockedR);
                    final float delayedL = tailPredelay.left(predelay);
                    final float delayedR = tailPredelay.right(predelay);
                    final double mono = (delayedL + delayedR) * 0.5;
                    final double midBand = midBPB0 * mono + midBPB1 * midBPX1 + midBPB2 * midBPX2
                            - midBPA1 * midBPY1 - midBPA2 * midBPY2;
                    midBPX2 = midBPX1; midBPX1 = mono;
                    midBPY2 = midBPY1; midBPY1 = midBand;
                    fdn.process((float) (delayedL - midBand * MID_PROTECT_RATIO),
                            (float) (delayedR - midBand * MID_PROTECT_RATIO), fdnOut);

                    scLpL += (blockedL - scLpL) * scLpCoef;
                    scLpR += (blockedR - scLpR) * scLpCoef;
                    final double energy = Math.max(scLpL * scLpL, scLpR * scLpR);
                    final float scTarget = energy > SC_ENV_THRESHOLD ? SIDECHAIN_DUCK : 1f;
                    sidechainGain += (scTarget - sidechainGain)
                            * (scTarget < sidechainGain ? scAttack : scRelease);
                    final float amount = wet * loudness * sidechainGain;
                    tailL = fdnOut[0] * tailMakeup * amount;
                    tailR = fdnOut[1] * tailMakeup * amount;
                }
            }
            if (erActive) {
                erL = erOut[0] * erBoost * loudness * sidechainGain;
                erR = erOut[1] * erBoost * loudness * sidechainGain;
            }
            float wetL = erL + tailL;
            float wetR = erR + tailR;
            shelfL += (wetL - shelfL) * shelfCoef;
            shelfR += (wetR - shelfR) * shelfCoef;
            wetL -= (1.0 - WET_SHELF_KEEP) * shelfL;
            wetR -= (1.0 - WET_SHELF_KEEP) * shelfR;

            final int index = (offset + f) * channels;
            final double l = dryInput[f * 2] * dryGain + wetL;
            final double r = dryInput[f * 2 + 1] * dryGain + wetR;
            // 双耳联动限幅:峰值控制不改左右增益比,避免破坏定位线索。
            final double peak = Math.max(Math.abs(l), Math.abs(r));
            final double limitGain = peak > LIMITER_KNEE ? softLimit(peak) / peak : 1.0;
            interleaved[index] = (ch > 1 ? l : (l + r) * 0.5) * limitGain;
            if (ch > 1) {
                interleaved[index + 1] = r * limitGain;
            }
            for (int c = 2; c < channels; c++) {
                interleaved[index + c] *= loudness;
            }
        }
    }

    private static void updateListener(ListenerPose pose, int frames, int rate) {
        final double poseStep = step(frames, rate, 0.025);
        if (!listenerPoseReady) {
            listenerX = pose.x; listenerY = pose.y; listenerZ = pose.z;
        } else {
            listenerX += (pose.x - listenerX) * poseStep;
            listenerY += (pose.y - listenerY) * poseStep;
            listenerZ += (pose.z - listenerZ) * poseStep;
        }
        // Opposite view or up can make the interpolated basis degenerate.
        if (!listenerPoseReady || nearlyOpposite(listenerFx, listenerFy, listenerFz, pose.fx, pose.fy, pose.fz)
                || nearlyOpposite(listenerUx, listenerUy, listenerUz, pose.ux, pose.uy, pose.uz)) {
            listenerFx = pose.fx; listenerFy = pose.fy; listenerFz = pose.fz;
            listenerUx = pose.ux; listenerUy = pose.uy; listenerUz = pose.uz;
            listenerPoseReady = true;
        } else {
            listenerFx += (pose.fx - listenerFx) * poseStep;
            listenerFy += (pose.fy - listenerFy) * poseStep;
            listenerFz += (pose.fz - listenerFz) * poseStep;
            listenerUx += (pose.ux - listenerUx) * poseStep;
            listenerUy += (pose.uy - listenerUy) * poseStep;
            listenerUz += (pose.uz - listenerUz) * poseStep;
        }
        // Normalize only the render basis, never overwrite the EMA state.
        if (!buildBasis(listenerFx, listenerFy, listenerFz, listenerUx, listenerUy, listenerUz)) {
            buildBasis(pose.fx, pose.fy, pose.fz, pose.ux, pose.uy, pose.uz);
        }
    }

    private static boolean nearlyOpposite(double ax, double ay, double az, double bx, double by, double bz) {
        return ax * bx + ay * by + az * bz < -0.95
                * Math.sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz));
    }

    private static boolean buildBasis(double fx, double fy, double fz, double ux, double uy, double uz) {
        final double rx = fy * uz - fz * uy;
        final double ry = fz * ux - fx * uz;
        final double rz = fx * uy - fy * ux;
        final double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (!(rl > 1e-6)) {
            return false;
        }
        final double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        viewX = fx / fl; viewY = fy / fl; viewZ = fz / fl;
        rightX = rx / rl; rightY = ry / rl; rightZ = rz / rl;
        upX = rightY * viewZ - rightZ * viewY;
        upY = rightZ * viewX - rightX * viewZ;
        upZ = rightX * viewY - rightY * viewX;
        return true;
    }

    private static float blendDirectDelay(boolean side, double blend) {
        final float next = side ? directLine.right(directDelayNew) : directLine.left(directDelayNew);
        if (blend >= 1.0) {
            return next;
        }
        final float previous = side ? directLine.right(directDelayOld) : directLine.left(directDelayOld);
        return (float) (previous + (next - previous) * blend);
    }

    private static boolean renderSpatial(OpenAlReverbBus ready, int frames, int rate,
                                         double dx, double dy, double dz, double sideX, double sideZ) {
        if (ready == null || ready.isBroken() || ready.sampleRate() != rate) {
            spatialMix = 0f;
            silentSpatialFrames = 0;
            return false;
        }
        if (ready != renderBus) {
            if (renderBus != null) {
                renderBus.close();
            }
            renderBus = ready;
            spatialMix = 0f;
            silentSpatialFrames = 0;
        }
        double centerEnergy = 0;
        double sideEnergy = 0;
        for (int f = 0; f < frames; f++) {
            final float center = midInput[f];
            final float side = sideInput[f];
            busMono[f] = toPcm16(center * NATIVE_PCM_SCALE);
            final short sidePcm = toPcm16(side * NATIVE_PCM_SCALE);
            busSide[f * 2] = sidePcm;
            busSide[f * 2 + 1] = (short) -sidePcm;
            centerEnergy += center * center;
            sideEnergy += side * side;
        }
        ready.renderDirect(dx, dy, dz, sideX, sideZ,
                (float) viewX, (float) viewY, (float) viewZ, (float) upX, (float) upY, (float) upZ,
                busMono, busSide, busOut, frames);
        if (ready.isBroken()) {
            spatialMix = 0f;
            return false;
        }
        double outputEnergy = 0;
        for (int i = 0; i < frames * 2; i++) {
            if (!Float.isFinite(busOut[i])) {
                ready.markBroken();
                spatialMix = 0f;
                return false;
            }
            outputEnergy += busOut[i] * busOut[i];
        }
        // Opposed side sources can cancel by design; only sustained center-dominated
        // input is suitable for the watchdog, after allowing FIR startup.
        if (centerEnergy > sideEnergy * 4 && centerEnergy / frames > 1e-4
                && outputEnergy / (frames * 2) < 1e-8) {
            silentSpatialFrames += frames;
            if (silentSpatialFrames > rate / 5) {
                ready.markBroken();
                spatialMix = 0f;
                return false;
            }
        } else {
            silentSpatialFrames = 0;
        }
        final float mixStep = 1f / (float) (rate * 0.04);
        // Apply path gain once after the PCM16 readback, so a quiet path keeps its resolution.
        final float nativeGain = directGain / NATIVE_PCM_SCALE;
        for (int f = 0; f < frames; f++) {
            final float mix = Math.min(1f, spatialMix + mixStep * (f + 1));
            dryInput[f * 2] += (busOut[f * 2] * nativeGain - dryInput[f * 2]) * mix;
            dryInput[f * 2 + 1] += (busOut[f * 2 + 1] * nativeGain - dryInput[f * 2 + 1]) * mix;
        }
        spatialMix = Math.min(1f, spatialMix + mixStep * frames);
        return true;
    }

    private static short toPcm16(float value) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, value)) * 32767f);
    }

    private static double softLimit(double peak) {
        final double over = peak - LIMITER_KNEE;
        final double headroom = 1.0 - LIMITER_KNEE;
        return LIMITER_KNEE + headroom * over / (headroom + over);
    }

    private static double step(int frames, int rate, double tau) {
        return 1.0 - Math.exp(-frames / (rate * tau));
    }

    private static double biquad(double in, double[] state) {
        final double out = cB0 * in + cB1 * state[0] + cB2 * state[1]
                - cA1 * state[2] - cA2 * state[3];
        state[1] = state[0]; state[0] = in;
        state[3] = state[2]; state[2] = out;
        return out;
    }

    private static void designLowpass(double fc, int rate) {
        final double w = 2.0 * Math.PI * Math.min(fc, rate * 0.475) / rate;
        final double alpha = Math.sin(w) / (2.0 * BUTTERWORTH_Q);
        final double a0 = 1.0 + alpha;
        cB0 = (1.0 - Math.cos(w)) * 0.5 / a0;
        cB1 = (1.0 - Math.cos(w)) / a0;
        cB2 = cB0;
        cA1 = -2.0 * Math.cos(w) / a0;
        cA2 = (1.0 - alpha) / a0;
    }

    private static void rebuild(int rate, int channels) {
        ownedRate = rate;
        bandState = new double[channels][4];
        directLine.rebuild((int) (rate * (168.0 / SPEED_OF_SOUND + LEGACY_ITD_SEC)) + 8);
        earLine.rebuild((int) (rate * DirectPath.MAX_EAR_DELAY_SEC) + 8);
        earLowStep = step(1, rate, 1.0 / (2.0 * Math.PI * 1600.0));
        earHighStep = step(1, rate, 1.0 / (2.0 * Math.PI * 4800.0));
        earLevelStep = step(1, rate, 0.010);
        earFadeStep = 1.0 / (rate * 0.004);
        wetPropagation.rebuild((int) (rate * 160.0 / SPEED_OF_SOUND) + 8);
        tailPredelay.rebuild((int) (rate * 0.060) + 8);
        reflections.rebuild(rate);
        fdn.rebuild(rate);
        dcFeedback = Math.exp(-2.0 * Math.PI * 10.0 / rate);
        shelfCoef = 1.0 - Math.exp(-2.0 * Math.PI * WET_SHELF_HZ / rate);
        scLpCoef = 1.0 - Math.exp(-2.0 * Math.PI * SIDECHAIN_LP_HZ / rate);
        final double midW = 2.0 * Math.PI * Math.min(1800.0, rate * 0.45) / rate;
        final double alpha = Math.sin(midW) / (2.0 * 0.8);
        final double a0 = 1.0 + alpha;
        midBPB0 = alpha / a0; midBPB1 = 0; midBPB2 = -midBPB0;
        midBPA1 = -2.0 * Math.cos(midW) / a0;
        midBPA2 = (1.0 - alpha) / a0;
        clearWetHistory();
        clearDirectHistory();
        tailWasActive = erWasActive = false;
        directWasActive = wetWasActive = speakerWasActive = false;
        listenerPoseReady = false;
        spatialMix = 0f;
        silentSpatialFrames = 0;
        lastCoefBand = -1;
    }

    private static void clearDirectHistory() {
        directMidState = directSideState = 0;
        directLine.flush();
        earLine.flush();
        centerRay.clear();
        positiveSideRay.clear();
        negativeSideRay.clear();
    }

    private static void clearWetHistory() {
        reflections.flush();
        clearTailHistory();
        wetPropagation.flush();
        shelfL = shelfR = 0;
    }

    private static void clearTailHistory() {
        tailPredelay.flush();
        fdn.flush();
        dcXL = dcYL = dcXR = dcYR = 0;
        midBPX1 = midBPX2 = midBPY1 = midBPY2 = 0;
        scLpL = scLpR = 0;
        sidechainGain = 1f;
    }

    private static void doFlush(RoomPlan plan) {
        logBand = Math.log10(plan.bandHz);
        wet = plan.wet; loudness = plan.loudness; dryGain = plan.dryGain;
        preDelaySec = plan.predelaySec; decaySec = plan.decaySec;
        decayLowSec = plan.decayLowSec; decayHighSec = plan.decayHighSec;
        tone = plan.tone; directGain = plan.directGain;
        directLogCutoff = Math.log10(plan.directCutoffHz);
        directPan = plan.directPan;
        directDelayOld = directDelayNew = plan.hasSpeakerAnchor
                ? plan.directPathMeters / SPEED_OF_SOUND * ownedRate : 0.0;
        wetDelayOld = wetDelayNew = plan.hasSpeakerAnchor
                ? plan.directDistanceMeters / SPEED_OF_SOUND * ownedRate : 0.0;
        delayCrossfade = 1.0;
        delayCrossfadeStep = 1.0;
        clearDirectHistory();
        clearWetHistory();
        for (double[] state : bandState) {
            Arrays.fill(state, 0);
        }
        if (renderBus != null && !renderBus.isBroken()) {
            renderBus.flush();
        }
        directWasActive = wetWasActive = tailWasActive = erWasActive = speakerWasActive = spatialWasActive = false;
        listenerPoseReady = false;
        spatialMix = 0f;
        silentSpatialFrames = 0;
        lastCoefBand = -1;
    }

    public static double bandOpenHz() {
        return BAND_OPEN_HZ;
    }

    public static double bandFloorHz() {
        return MIN_BAND_HZ;
    }

    /** Modelled ear cues only; measured front/back and elevation responses stay with HRTF. */
    private static final class SpeakerRay {
        final Ear left;
        final Ear right;

        SpeakerRay(boolean side) {
            left = new Ear(side);
            right = new Ear(side);
        }

        void update(double x, double y, double z, int rate) {
            final double distance = Math.sqrt(x * x + y * y + z * z);
            // Fade to a neutral image at coincidence rather than let roundoff choose a side.
            final double norm = 1.0 / Math.max(0.12, distance);
            final double lateral = (x * rightX + y * rightY + z * rightZ) * norm;
            final double rear = Math.max(0.0, -(x * viewX + y * viewY + z * viewZ) * norm);
            final double elevation = (x * upX + y * upY + z * upZ) * norm;
            final double itd = DirectPath.earDelaySeconds(lateral) * rate;
            left.update(Math.max(0.0, itd), -lateral, rear, elevation);
            right.update(Math.max(0.0, -itd), lateral, rear, elevation);
        }

        void clear() {
            left.clear();
            right.clear();
        }
    }

    private static final class Ear {
        final boolean side;
        double oldDelay, newDelay, delayBlend;
        double low, high, gain, dark, height;
        double gainTarget, darkTarget, heightTarget;
        boolean valid;

        Ear(boolean side) {
            this.side = side;
        }

        void update(double delay, double toward, double rear, double elevation) {
            final double shadow = Math.max(0.0, -toward);
            gainTarget = 1.0 + 0.08 * Math.max(0.0, toward) - 0.15 * shadow;
            // Keep bass in the far ear. Rear darkness and elevation brightness are modest
            // shelves expressing a direction, not an individualized pinna response.
            darkTarget = 0.44 * shadow + 0.13 * rear;
            heightTarget = 0.18 * elevation;
            if (!valid) {
                valid = true;
                oldDelay = newDelay = delay;
                delayBlend = 1.0;
                gain = gainTarget; dark = darkTarget; height = heightTarget;
            } else if (delayBlend >= 1.0 && Math.abs(delay - newDelay) >= 0.125) {
                // A brief transition between fixed ITD taps also avoids pitch sliding on head turns.
                oldDelay = newDelay;
                newDelay = delay;
                delayBlend = 0.0;
            }
        }

        float sample() {
            double in = side ? earLine.right(newDelay) : earLine.left(newDelay);
            if (delayBlend < 1.0) {
                final double old = side ? earLine.right(oldDelay) : earLine.left(oldDelay);
                delayBlend = Math.min(1.0, delayBlend + earFadeStep);
                in = old + (in - old) * delayBlend;
            }
            low += earLowStep * (in - low);
            high += earHighStep * (in - high);
            gain += earLevelStep * (gainTarget - gain);
            dark += earLevelStep * (darkTarget - dark);
            height += earLevelStep * (heightTarget - height);
            return (float) (gain * in + dark * (low - in) + height * (in - high));
        }

        void clear() {
            valid = false;
            low = high = 0.0;
        }
    }

    private static final class ListenerPose {
        final double x, y, z;
        final float fx, fy, fz, ux, uy, uz;

        ListenerPose(double x, double y, double z, float fx, float fy, float fz,
                     float ux, float uy, float uz) {
            this.x = x; this.y = y; this.z = z;
            this.fx = fx; this.fy = fy; this.fz = fz;
            this.ux = ux; this.uy = uy; this.uz = uz;
        }
    }
}
