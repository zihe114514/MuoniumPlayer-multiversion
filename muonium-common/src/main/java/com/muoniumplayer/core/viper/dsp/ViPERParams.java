// 逐行移植自 ViPERDSP/include/ViPERParams.h（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
// 24 组参数结构体（C++ struct → Java 嵌套类）；字段名与默认值逐字镜像上游，
// equals() 逐条对应上游 operator==（含「双方均未启用即相等」的提前返回语义）。
package com.muoniumplayer.core.viper.dsp;

import java.util.Arrays;

public final class ViPERParams {

    // 默认构造（对应 C++ 默认构造的结构体）。

    // —— Master Limiter ——

    public static final class MasterLimiterParams {
        public float threshold = 1.0f;     // 0..1
        public float outputVolume = 1.0f;  // 0..1
        public float channelPan = 0.0f;    // -1..+1

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MasterLimiterParams)) {
                return false;
            }
            MasterLimiterParams o = (MasterLimiterParams) other;
            return threshold == o.threshold
                    && outputVolume == o.outputVolume
                    && channelPan == o.channelPan;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(threshold, outputVolume, channelPan);
        }
    }

    // —— Playback Gain Control ——

    public static final class PlaybackGainControlParams {
        public boolean enable = false;
        public float strength = 0.0f;         // 0..1
        public float maxGain = 0.0f;          // 0..1
        public float outputThreshold = 0.0f;  // 0..1

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PlaybackGainControlParams)) {
                return false;
            }
            PlaybackGainControlParams o = (PlaybackGainControlParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && strength == o.strength
                    && maxGain == o.maxGain && outputThreshold == o.outputThreshold;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, strength, maxGain, outputThreshold)
                    : 0;
        }
    }

    // —— LUFS Targeting ——

    public static final class LufsParams {
        public boolean enable = false;
        public float target = 0.0f;
        public float maxGain = 0.0f; // dB
        public int speed = 0;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LufsParams)) {
                return false;
            }
            LufsParams o = (LufsParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && target == o.target
                    && maxGain == o.maxGain && speed == o.speed;
        }

        @Override
        public int hashCode() {
            return enable ? java.util.Objects.hash(true, target, maxGain, speed) : 0;
        }
    }

    // —— FET Compressor ——

    public static final class FetCompressorParams {
        public boolean enable = false;
        public float threshold = 0.0f; // 0..1
        public float ratio = 0.0f;     // 0..1
        public float knee = 0.0f;      // 0..1
        public boolean kneeAuto = false;
        public float gain = 0.0f; // 0..1
        public boolean gainAuto = false;
        public float attack = 0.0f; // 0..1
        public boolean attackAuto = false;
        public float release = 0.0f; // 0..1
        public boolean releaseAuto = false;
        public float kneeMulti = 0.0f;
        public float maxAttack = 0.0f;
        public float maxRelease = 0.0f;
        public float crest = 0.0f;
        public float adapt = 0.0f;
        public boolean noClip = false;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FetCompressorParams)) {
                return false;
            }
            FetCompressorParams o = (FetCompressorParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && threshold == o.threshold
                    && ratio == o.ratio && knee == o.knee
                    && kneeAuto == o.kneeAuto && gain == o.gain
                    && gainAuto == o.gainAuto && attack == o.attack
                    && attackAuto == o.attackAuto && release == o.release
                    && releaseAuto == o.releaseAuto && kneeMulti == o.kneeMulti
                    && maxAttack == o.maxAttack && maxRelease == o.maxRelease
                    && crest == o.crest && adapt == o.adapt
                    && noClip == o.noClip;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, threshold, ratio, knee, kneeAuto, gain,
                            gainAuto, attack, attackAuto, release, releaseAuto, kneeMulti,
                            maxAttack, maxRelease, crest, adapt, noClip)
                    : 0;
        }
    }

    // —— Bass ——

    public static final class BassParams {
        public boolean enable = false;
        public int mode = 0;
        public int frequency = 0; // Hz
        public float gain = 0.0f; // 0..1
        public boolean antiPop = false;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof BassParams)) {
                return false;
            }
            BassParams o = (BassParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && mode == o.mode
                    && frequency == o.frequency && gain == o.gain
                    && antiPop == o.antiPop;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, mode, frequency, gain, antiPop)
                    : 0;
        }
    }

    // —— Bass Mono ——

    public static final class BassMonoParams {
        public boolean enable = false;
        public int mode = 0;
        public int frequency = 0;
        public float gain = 0.0f;
        public boolean antiPop = false;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof BassMonoParams)) {
                return false;
            }
            BassMonoParams o = (BassMonoParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && mode == o.mode
                    && frequency == o.frequency && gain == o.gain
                    && antiPop == o.antiPop;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, mode, frequency, gain, antiPop)
                    : 0;
        }
    }

    // —— Psychoacoustic Bass ——

    public static final class PsychoacousticBassParams {
        public boolean enable = false;
        public int cutoff = 0; // Hz
        public int intensity = 0;
        public int harmonicOrder = 0;
        public int originalLevel = 0;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PsychoacousticBassParams)) {
                return false;
            }
            PsychoacousticBassParams o = (PsychoacousticBassParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && cutoff == o.cutoff
                    && intensity == o.intensity && harmonicOrder == o.harmonicOrder
                    && originalLevel == o.originalLevel;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, cutoff, intensity, harmonicOrder,
                            originalLevel)
                    : 0;
        }
    }

    // —— Spectrum Extension ——

    public static final class SpectrumExtensionParams {
        public boolean enable = false;
        public int strength = 0;
        public float exciter = 0.0f; // 0..1

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SpectrumExtensionParams)) {
                return false;
            }
            SpectrumExtensionParams o = (SpectrumExtensionParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && strength == o.strength && exciter == o.exciter;
        }

        @Override
        public int hashCode() {
            return enable ? java.util.Objects.hash(true, strength, exciter) : 0;
        }
    }

    // —— Equalizer ——

    public static final class EqualizerParams {
        public boolean enable = false;
        public int bandCount = 0;
        public float[] bandLevels = new float[31];

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof EqualizerParams)) {
                return false;
            }
            EqualizerParams o = (EqualizerParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && bandCount == o.bandCount
                    && Arrays.equals(bandLevels, o.bandLevels);
        }

        @Override
        public int hashCode() {
            return enable ? java.util.Objects.hash(true, bandCount, Arrays.hashCode(bandLevels)) : 0;
        }
    }

    // —— Convolver ——

    public static final class ConvolverParams {
        public boolean enable = false;
        public float crossChannel = 0.0f; // 0..1
        public float wet = 1.0f;          // 0..1
        public float outputGainDb = 0.0f;
        public int routing = 0;           // 0=direct+cross, 1=direct, 2=cross
        public float crossDelayMs = 0.3125f;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ConvolverParams)) {
                return false;
            }
            ConvolverParams o = (ConvolverParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && crossChannel == o.crossChannel
                    && wet == o.wet && outputGainDb == o.outputGainDb
                    && routing == o.routing && crossDelayMs == o.crossDelayMs;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, crossChannel, wet, outputGainDb, routing,
                            crossDelayMs)
                    : 0;
        }
    }

    // —— DDC ——

    public static final class DdcParams {
        public boolean enable = false;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DdcParams)) {
                return false;
            }
            DdcParams o = (DdcParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable;
        }

        @Override
        public int hashCode() {
            return enable ? 1 : 0;
        }
    }

    // —— Field Surround ——

    public static final class FieldSurroundParams {
        public boolean enable = false;
        public float widening = 0.0f;  // 0..1
        public float midImage = 0.0f;  // 0..1
        public short depth = 0;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldSurroundParams)) {
                return false;
            }
            FieldSurroundParams o = (FieldSurroundParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && widening == o.widening
                    && midImage == o.midImage && depth == o.depth;
        }

        @Override
        public int hashCode() {
            return enable ? java.util.Objects.hash(true, widening, midImage, depth) : 0;
        }
    }

    // —— Differential Surround ——

    public static final class DiffSurroundParams {
        public boolean enable = false;
        public float delay = 0.0f;       // 0..1
        public boolean reverse = false;
        public float wetDryMix = 0.0f;   // 0..1
        public float lpCutoff = 0.0f;    // Hz

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DiffSurroundParams)) {
                return false;
            }
            DiffSurroundParams o = (DiffSurroundParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && delay == o.delay && reverse == o.reverse
                    && wetDryMix == o.wetDryMix && lpCutoff == o.lpCutoff;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, delay, reverse, wetDryMix, lpCutoff)
                    : 0;
        }
    }

    // —— Stereo Imager ——

    public static final class StereoImagerParams {
        public boolean enable = false;
        public float lowWidth = 0.0f;      // percent: 0..200
        public float midWidth = 0.0f;      // percent: 0..200
        public float highWidth = 0.0f;     // percent: 0..200
        public float lowCrossover = 0.0f;  // Hz
        public float highCrossover = 0.0f; // Hz

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StereoImagerParams)) {
                return false;
            }
            StereoImagerParams o = (StereoImagerParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && lowWidth == o.lowWidth
                    && midWidth == o.midWidth && highWidth == o.highWidth
                    && lowCrossover == o.lowCrossover
                    && highCrossover == o.highCrossover;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, lowWidth, midWidth, highWidth,
                            lowCrossover, highCrossover)
                    : 0;
        }
    }

    // —— Headphone Surround (VHE) ——

    public static final class HeadphoneSurroundParams {
        public boolean enable = false;
        public int quality = 0;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof HeadphoneSurroundParams)) {
                return false;
            }
            HeadphoneSurroundParams o = (HeadphoneSurroundParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && quality == o.quality;
        }

        @Override
        public int hashCode() {
            return enable ? java.util.Objects.hash(true, quality) : 0;
        }
    }

    // —— Reverb ——

    public static final class ReverbParams {
        public boolean enable = false;
        public float roomSize = 0.0f; // 0..1
        public float width = 0.0f;    // 0..1
        public float damp = 0.0f;     // 0..1
        public float wet = 0.0f;      // 0..1
        public float dry = 0.0f;      // 0..1

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ReverbParams)) {
                return false;
            }
            ReverbParams o = (ReverbParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && roomSize == o.roomSize
                    && width == o.width && damp == o.damp && wet == o.wet
                    && dry == o.dry;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, roomSize, width, damp, wet, dry)
                    : 0;
        }
    }

    // —— Dynamic System ——

    public static final class DynamicSystemParams {
        public boolean enable = false;
        public int xCoeffLow = 0;
        public int xCoeffHigh = 0;
        public int yCoeffLow = 0;
        public int yCoeffHigh = 0;
        public float sideGainLow = 0.0f;  // 0..1
        public float sideGainHigh = 0.0f; // 0..1
        public float strength = 0.0f;     // 0..1

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DynamicSystemParams)) {
                return false;
            }
            DynamicSystemParams o = (DynamicSystemParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && xCoeffLow == o.xCoeffLow
                    && xCoeffHigh == o.xCoeffHigh && yCoeffLow == o.yCoeffLow
                    && yCoeffHigh == o.yCoeffHigh
                    && sideGainLow == o.sideGainLow
                    && sideGainHigh == o.sideGainHigh && strength == o.strength;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, xCoeffLow, xCoeffHigh, yCoeffLow,
                            yCoeffHigh, sideGainLow, sideGainHigh, strength)
                    : 0;
        }
    }

    // —— Clarity ——

    public static final class ClarityParams {
        public boolean enable = false;
        public int mode = 0; // 0..1
        public float gain = 0.0f;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ClarityParams)) {
                return false;
            }
            ClarityParams o = (ClarityParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && mode == o.mode && gain == o.gain;
        }

        @Override
        public int hashCode() {
            return enable ? java.util.Objects.hash(true, mode, gain) : 0;
        }
    }

    // —— Cure (Crossfeed) ——

    public static final class CureParams {
        public boolean enable = false;
        public int crossfeedPreset = 0; // 0..2

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CureParams)) {
                return false;
            }
            CureParams o = (CureParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && crossfeedPreset == o.crossfeedPreset;
        }

        @Override
        public int hashCode() {
            return enable ? java.util.Objects.hash(true, crossfeedPreset) : 0;
        }
    }

    // —— Tube Simulator ——

    public static final class TubeSimulatorParams {
        public boolean enable = false;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TubeSimulatorParams)) {
                return false;
            }
            TubeSimulatorParams o = (TubeSimulatorParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable;
        }

        @Override
        public int hashCode() {
            return enable ? 1 : 0;
        }
    }

    // —— AnalogX ——

    public static final class AnalogXParams {
        public boolean enable = false;
        public int mode = 0;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof AnalogXParams)) {
                return false;
            }
            AnalogXParams o = (AnalogXParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && mode == o.mode;
        }

        @Override
        public int hashCode() {
            return enable ? java.util.Objects.hash(true, mode) : 0;
        }
    }

    // —— Speaker Correction ——

    public static final class SpeakerCorrectionParams {
        public boolean enable = false;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SpeakerCorrectionParams)) {
                return false;
            }
            SpeakerCorrectionParams o = (SpeakerCorrectionParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable;
        }

        @Override
        public int hashCode() {
            return enable ? 1 : 0;
        }
    }

    // —— Multiband Compressor ——

    public static final class MultibandCompressorBandParams {
        public boolean enable = false;
        public float threshold = 0.0f;
        public float ratio = 0.0f;
        public float knee = 0.0f;
        public boolean kneeAuto = false;
        public float gain = 0.0f;
        public boolean gainAuto = false;
        public float attack = 0.0f;
        public boolean attackAuto = false;
        public float release = 0.0f;
        public boolean releaseAuto = false;
        public float kneeMulti = 0.0f;
        public float maxAttack = 0.0f;
        public float maxRelease = 0.0f;
        public float crest = 0.0f;
        public float adapt = 0.0f;
        public boolean noClip = false;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MultibandCompressorBandParams)) {
                return false;
            }
            MultibandCompressorBandParams o = (MultibandCompressorBandParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && threshold == o.threshold
                    && ratio == o.ratio && knee == o.knee
                    && kneeAuto == o.kneeAuto && gain == o.gain
                    && gainAuto == o.gainAuto && attack == o.attack
                    && attackAuto == o.attackAuto && release == o.release
                    && releaseAuto == o.releaseAuto && kneeMulti == o.kneeMulti
                    && maxAttack == o.maxAttack && maxRelease == o.maxRelease
                    && crest == o.crest && adapt == o.adapt
                    && noClip == o.noClip;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, threshold, ratio, knee, kneeAuto, gain,
                            gainAuto, attack, attackAuto, release, releaseAuto, kneeMulti,
                            maxAttack, maxRelease, crest, adapt, noClip)
                    : 0;
        }
    }

    public static final class MultibandCompressorParams {
        public boolean enable = false;
        public int bandCount = 0;
        public float[] crossoverFrequencies = new float[5]; // Hz
        public MultibandCompressorBandParams[] bands = newBands();

        private static MultibandCompressorBandParams[] newBands() {
            MultibandCompressorBandParams[] bands = new MultibandCompressorBandParams[5];
            for (int i = 0; i < bands.length; i++) {
                bands[i] = new MultibandCompressorBandParams();
            }
            return bands;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MultibandCompressorParams)) {
                return false;
            }
            MultibandCompressorParams o = (MultibandCompressorParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && bandCount == o.bandCount
                    && Arrays.equals(crossoverFrequencies, o.crossoverFrequencies)
                    && Arrays.equals(bands, o.bands);
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, bandCount,
                            Arrays.hashCode(crossoverFrequencies), Arrays.hashCode(bands))
                    : 0;
        }
    }

    // —— Dynamic EQ ——

    public static final class DynamicEqBandParams {
        public float frequency = 0.0f; // Hz
        public float q = 0.0f;
        public float gain = 0.0f;      // dB
        public float threshold = 0.0f; // dB
        public float attack = 0.0f;    // ms
        public float release = 0.0f;   // ms
        public int filterType = 0;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DynamicEqBandParams)) {
                return false;
            }
            DynamicEqBandParams o = (DynamicEqBandParams) other;
            return frequency == o.frequency && q == o.q && gain == o.gain
                    && threshold == o.threshold && attack == o.attack
                    && release == o.release && filterType == o.filterType;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(frequency, q, gain, threshold, attack, release,
                    filterType);
        }
    }

    public static final class DynamicEqParams {
        public boolean enable = false;
        public int bandCount = 0;
        public DynamicEqBandParams[] bands = newBands();

        private static DynamicEqBandParams[] newBands() {
            DynamicEqBandParams[] bands = new DynamicEqBandParams[10];
            for (int i = 0; i < bands.length; i++) {
                bands[i] = new DynamicEqBandParams();
            }
            return bands;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DynamicEqParams)) {
                return false;
            }
            DynamicEqParams o = (DynamicEqParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable && bandCount == o.bandCount
                    && Arrays.equals(bands, o.bands);
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, bandCount, Arrays.hashCode(bands))
                    : 0;
        }
    }

    // —— 顶层聚合（字段顺序与上游 ViPERParams 一致） ——

    public final MasterLimiterParams masterLimiter = new MasterLimiterParams();
    public final PlaybackGainControlParams playbackGainControl = new PlaybackGainControlParams();
    public final LufsParams lufs = new LufsParams();
    public final FetCompressorParams fetCompressor = new FetCompressorParams();
    public final BassParams bass = new BassParams();
    public final BassMonoParams bassMono = new BassMonoParams();
    public final PsychoacousticBassParams psychoacousticBass = new PsychoacousticBassParams();
    public final SpectrumExtensionParams spectrumExtension = new SpectrumExtensionParams();
    public final EqualizerParams equalizer = new EqualizerParams();
    public final ConvolverParams convolver = new ConvolverParams();
    public final DdcParams ddc = new DdcParams();
    public final FieldSurroundParams fieldSurround = new FieldSurroundParams();
    public final DiffSurroundParams diffSurround = new DiffSurroundParams();
    public final StereoImagerParams stereoImager = new StereoImagerParams();
    public final HeadphoneSurroundParams headphoneSurround = new HeadphoneSurroundParams();
    public final ReverbParams reverb = new ReverbParams();
    public final DynamicSystemParams dynamicSystem = new DynamicSystemParams();
    public final ClarityParams clarity = new ClarityParams();
    public final CureParams cure = new CureParams();
    public final TubeSimulatorParams tubeSimulator = new TubeSimulatorParams();
    public final AnalogXParams analogX = new AnalogXParams();
    public final SpeakerCorrectionParams speakerCorrection = new SpeakerCorrectionParams();
    public final MultibandCompressorParams multibandCompressor =
            new MultibandCompressorParams();
    public final DynamicEqParams dynamicEq = new DynamicEqParams();

    // ──────────────────────── 扩展：JamesDSP 混响 ────────────────────────
    //
    // 以下不属于上游 ViPER（ViPERDSP 只有 Freeverb 系的 reverb）。这是本模组额外移植的
    // JamesDSP 混响（sndfilter sf_reverb），参数模型对齐 JamesDSP 的 Reverb_SetParam +
    // sf_advancereverb 的 16 个入口参数。放在 ViPERParams 里是为了复用既有的
    // 预设 → 映射 → 引擎 下发链路（面板自动可调）。

    /** JamesDSP 混响参数（{@link com.muoniumplayer.core.jdsp.JdspReverb}）。 */
    public static final class JdspReverbParams {
        /** 总开关。 */
        public boolean enable = false;
        /** 预设索引 0..18（{@link com.muoniumplayer.core.jdsp.JdspReverb#PRESET_NAMES}）。 */
        public int preset = 0;
        /** true = 使用下面 17 个自定义参数；false = 使用预设表（默认）。 */
        public boolean custom = false;

        // —— 自定义参数（对齐 sf_advancereverb 的入口，仅 custom=true 时生效） ——
        /** 过采样倍率 1..2（上游为 int）。 */
        public int oversampleFactor = 1;
        /** 早期反射到后期混响的混合量 0..1。 */
        public float ertolate = 0.40f;
        /** 早期反射湿声（dB）。 */
        public float erefwet = -9.0f;
        /** 干声增益（dB）。 */
        public float dry = -7.0f;
        /** 早期反射延迟缩放系数。 */
        public float ereffactor = 1.6f;
        /** 早期反射声场宽度 -1..1。 */
        public float erefwidth = 0.7f;
        /** 立体声宽度 0..1。 */
        public float width = 1.0f;
        /** 混响湿声增益（dB）。 */
        public float wet = 0.0f;
        /** 调制深度 0..1。 */
        public float wander = 0.25f;
        /** 低频增强量 0..1。 */
        public float bassb = 0.15f;
        /** LFO 旋转速率 0..20。 */
        public float spin = 0.7f;
        /** 输入低通频率（Hz）。 */
        public float inputlpf = 17000.0f;
        /** 低频低通频率（Hz）。 */
        public float basslpf = 500.0f;
        /** 阻尼低通频率（Hz）。 */
        public float damplpf = 7000.0f;
        /** 输出低通频率（Hz）。 */
        public float outputlpf = 10000.0f;
        /** 混响时长 RT60（秒）。 */
        public float rt60 = 3.2f;
        /** 输出延迟（秒，可为负）。 */
        public float delay = 0.020f;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof JdspReverbParams)) {
                return false;
            }
            JdspReverbParams o = (JdspReverbParams) other;
            if (!enable && !o.enable) {
                return true;
            }
            return enable == o.enable
                    && preset == o.preset
                    && custom == o.custom
                    && oversampleFactor == o.oversampleFactor
                    && ertolate == o.ertolate
                    && erefwet == o.erefwet
                    && dry == o.dry
                    && ereffactor == o.ereffactor
                    && erefwidth == o.erefwidth
                    && width == o.width
                    && wet == o.wet
                    && wander == o.wander
                    && bassb == o.bassb
                    && spin == o.spin
                    && inputlpf == o.inputlpf
                    && basslpf == o.basslpf
                    && damplpf == o.damplpf
                    && outputlpf == o.outputlpf
                    && rt60 == o.rt60
                    && delay == o.delay;
        }

        @Override
        public int hashCode() {
            return enable
                    ? java.util.Objects.hash(true, preset, custom, oversampleFactor, ertolate,
                            erefwet, dry, ereffactor, erefwidth, width, wet, wander, bassb, spin,
                            inputlpf, basslpf, damplpf, outputlpf, rt60, delay)
                    : 0;
        }
    }

    /** JamesDSP 混响（本模组扩展；见上方说明）。 */
    public final JdspReverbParams jdspReverb = new JdspReverbParams();
}
