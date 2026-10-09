// 逐行移植自 ViPERDSP/viper/ViPER.{h,cpp}（逆向重建的 ViPER4Android DSP；本仓库按用户「个人使用、非商用」前提移植）
// 主引擎：24 个效果 + 波形/自适应缓冲 + 双软件限幅器；Process 链序、复位语义、
// Apply* 参数下发与核/DDC 装载接口逐条对应上游。DispatchRawParam（裸参数协议入口，
// 依赖 ParameterSnapshot.cpp 的 483 行快照器）不在本移植范围——本模组走类型化 Apply 通道，
// 与官方 App 的新版全量参数通道（PARAM_SET_FULL_PARAMS → ApplyParams）一致。
package com.muoniumplayer.core.viper.dsp;

import com.muoniumplayer.core.jdsp.JdspReverb;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ViPER {

    /** 上游 constants.h 的 VIPER_DEFAULT_SAMPLING_RATE。 */
    private static final int DEFAULT_SAMPLING_RATE = 44100;

    /** 上游 ViPER.cpp 文件级常量 kKernelChunkFloats。 */
    private static final int K_KERNEL_CHUNK_FLOATS = 2046;

    private final AtomicBoolean pendingEffectsReset_ = new AtomicBoolean(false);
    private final AtomicBoolean pendingBuffersReset_ = new AtomicBoolean(false);

    private volatile int samplingRate_;
    private long processFrameCount_;

    private float frameScale_;
    private float leftPan_;
    private float rightPan_;

    // —— Effects（顺序与上游成员声明一致） ——
    private final AdaptiveBuffer adaptiveBuffer_ = new AdaptiveBuffer(2, 4096);
    private final WaveBuffer waveBuffer_ = new WaveBuffer(2, 4096);
    private final Convolver convolver_ = new Convolver();
    private final VHE vhe_ = new VHE();
    private final ViPERDDC viperDdc_ = new ViPERDDC();
    private final SpectrumExtend spectrumExtend_ = new SpectrumExtend();
    private final StereoImager stereoImager_ = new StereoImager();
    private final IIRFilter iirFilter_ = new IIRFilter(10);
    private final DynamicEQ dynamicEq_ = new DynamicEQ();
    private final ColorfulMusic colorfulMusic_ = new ColorfulMusic();
    private final Reverberation reverberation_ = new Reverberation();
    private final PlaybackGain playbackGain_ = new PlaybackGain();
    private final LUFSTargeting lufsTargeting_ = new LUFSTargeting();
    private final FETCompressor fetCompressor_ = new FETCompressor();
    private final MultibandCompressor multibandCompressor_ = new MultibandCompressor();
    private final DynamicSystem dynamicSystem_ = new DynamicSystem();
    private final ViPERBass viperBass_ = new ViPERBass();
    private final ViPERBassMono viperBassMono_ = new ViPERBassMono();
    private final PsychoacousticBass psychoacousticBass_ = new PsychoacousticBass();
    private final ViPERClarity viperClarity_ = new ViPERClarity();
    private final DiffSurround diffSurround_ = new DiffSurround();
    private final Cure cure_ = new Cure();
    private final TubeSimulator tubeSimulator_ = new TubeSimulator();
    private final AnalogX analogX_ = new AnalogX();
    private final SpeakerCorrection speakerCorrection_ = new SpeakerCorrection();
    private final SoftwareLimiter[] softwareLimiters_ = {
            new SoftwareLimiter(), new SoftwareLimiter()
    };

    // —— 扩展（非上游 ViPER）：JamesDSP 混响，挂在 ViPER 全链之后、输出段之前 ——
    private final JdspReverb jdspReverb_ = new JdspReverb();
    /** 当前 JamesDSP 混响参数（采样率变化时按它重建）。 */
    private ViPERParams.JdspReverbParams jdspReverbParams_ = new ViPERParams.JdspReverbParams();

    /**
     * 上次应用的参数快照。上游在每组 Apply 内逐组存回 {@code last_applied_}；
     * 由于「已应用组必然等于入参、未应用组本就等于入参」，applyParams 结束时
     * 逐组相等 ⟺ 整体等价，故此处以「结束时整引用替换」等价实现（见 applyParams）。
     */
    private ViPERParams lastApplied_ = new ViPERParams();

    public ViPER() {
        samplingRate_ = DEFAULT_SAMPLING_RATE;
        processFrameCount_ = 0;
        frameScale_ = 1.0f;
        leftPan_ = 1.0f;
        rightPan_ = 1.0f;

        convolver_.setEnable(false);
        convolver_.setSampleRate(samplingRate_);
        convolver_.reset();

        vhe_.setEnable(false);
        vhe_.setSampleRate(samplingRate_);
        vhe_.reset();

        viperDdc_.setEnable(false);
        viperDdc_.setSampleRate(samplingRate_);
        viperDdc_.reset();

        spectrumExtend_.setEnable(false);
        spectrumExtend_.setSampleRate(samplingRate_);
        spectrumExtend_.setReferenceFrequency(7600);
        spectrumExtend_.setExciter(0.0f);
        spectrumExtend_.reset();

        iirFilter_.setEnable(false);
        iirFilter_.setSampleRate(samplingRate_);
        iirFilter_.reset();

        dynamicEq_.setEnable(false);
        dynamicEq_.setSampleRate(samplingRate_);
        dynamicEq_.reset();

        colorfulMusic_.setEnable(false);
        colorfulMusic_.setSampleRate(samplingRate_);
        colorfulMusic_.reset();

        stereoImager_.setEnable(false);
        stereoImager_.setSampleRate(samplingRate_);
        stereoImager_.reset();

        reverberation_.setEnable(false);
        reverberation_.reset();

        playbackGain_.setEnable(false);
        playbackGain_.setSampleRate(samplingRate_);
        playbackGain_.reset();

        lufsTargeting_.setEnable(false);
        lufsTargeting_.setSampleRate(samplingRate_);
        lufsTargeting_.reset();

        fetCompressor_.setEnable(false);
        fetCompressor_.setSampleRate(samplingRate_);
        fetCompressor_.reset();

        multibandCompressor_.setEnable(false);
        multibandCompressor_.setSampleRate(samplingRate_);
        multibandCompressor_.reset();

        dynamicSystem_.setEnable(false);
        dynamicSystem_.setSampleRate(samplingRate_);
        dynamicSystem_.reset();

        viperBass_.setSampleRate(samplingRate_);
        viperBass_.reset();

        viperBassMono_.setSampleRate(samplingRate_);
        viperBassMono_.reset();

        psychoacousticBass_.setEnable(false);
        psychoacousticBass_.setSampleRate(samplingRate_);
        psychoacousticBass_.reset();

        viperClarity_.setSampleRate(samplingRate_);
        viperClarity_.reset();

        diffSurround_.setEnable(false);
        diffSurround_.setSampleRate(samplingRate_);
        diffSurround_.reset();

        cure_.setEnable(false);
        cure_.setSampleRate(samplingRate_);
        cure_.reset();

        tubeSimulator_.setEnable(false);
        tubeSimulator_.reset();

        analogX_.setEnable(false);
        analogX_.setSampleRate(samplingRate_);
        analogX_.setProcessingModel(0);
        analogX_.reset();

        speakerCorrection_.setEnable(false);
        speakerCorrection_.setSampleRate(samplingRate_);
        speakerCorrection_.reset();

        // JamesDSP 混响（扩展）：默认关闭；启用经 applyJdspReverb / ResetAllEffects 重建。
        jdspReverb_.reset();

        for (SoftwareLimiter limiter : softwareLimiters_) {
            limiter.reset();
        }
    }

    /**
     * Mirror of {@code void ViPER::Process(float *buffer, uint32_t size)}.
     * 输入/输出均为交织立体声（buffer[0..size*2)）；返回后 buffer 即处理结果。
     */
    public void process(float[] buffer, int size) {
        if (pendingEffectsReset_.getAndSet(false)) {
            pendingBuffersReset_.set(false);
            resetAllEffects();
        } else if (pendingBuffersReset_.getAndSet(false)) {
            resetBuffers();
        }
        processFrameCount_ += size;

        float[] tmpBuf;
        int tmpBufSize;

        if (convolver_.getEnable() || vhe_.getEnable()) {
            if (waveBuffer_.pushSamples(buffer, size) == 0) {
                waveBuffer_.reset();
                Arrays.fill(buffer, 0, size * 2, 0.0f);
                return;
            }

            float[] ptr = waveBuffer_.getBuffer();
            int ret = convolver_.process(ptr, ptr, size);
            ret = vhe_.process(ptr, ptr, ret);
            waveBuffer_.setBufferOffset(ret);

            if (adaptiveBuffer_.pushZero(ret) == 0) {
                waveBuffer_.reset();
                adaptiveBuffer_.flushBuffer();
                Arrays.fill(buffer, 0, size * 2, 0.0f);
                return;
            }

            ptr = adaptiveBuffer_.getBuffer();
            ret = waveBuffer_.popSamples(ptr, ret, true);
            adaptiveBuffer_.setBufferOffset(ret);

            tmpBuf = ptr;
            tmpBufSize = ret;
        } else {
            if (adaptiveBuffer_.pushFrames(buffer, size) != 0) {
                adaptiveBuffer_.setBufferOffset(size);

                tmpBuf = adaptiveBuffer_.getBuffer();
                tmpBufSize = size;
            } else {
                adaptiveBuffer_.flushBuffer();
                Arrays.fill(buffer, 0, size * 2, 0.0f);
                return;
            }
        }

        if (tmpBufSize != 0) {
            viperDdc_.process(tmpBuf, tmpBufSize);
            spectrumExtend_.process(tmpBuf, tmpBufSize);
            iirFilter_.process(tmpBuf, tmpBufSize);
            dynamicEq_.process(tmpBuf, tmpBufSize);
            colorfulMusic_.process(tmpBuf, tmpBufSize);
            stereoImager_.process(tmpBuf, tmpBufSize);
            diffSurround_.process(tmpBuf, tmpBufSize);
            playbackGain_.process(tmpBuf, tmpBufSize);
            multibandCompressor_.process(tmpBuf, tmpBufSize);
            fetCompressor_.process(tmpBuf, tmpBufSize);
            dynamicSystem_.process(tmpBuf, tmpBufSize);
            tubeSimulator_.process(tmpBuf, tmpBufSize);
            psychoacousticBass_.process(tmpBuf, tmpBufSize);
            viperBass_.process(tmpBuf, tmpBufSize);
            viperBassMono_.process(tmpBuf, tmpBufSize);
            viperClarity_.process(tmpBuf, tmpBufSize);
            cure_.process(tmpBuf, tmpBufSize);
            analogX_.process(tmpBuf, tmpBufSize);
            reverberation_.process(tmpBuf, tmpBufSize);
            speakerCorrection_.process(tmpBuf, tmpBufSize);
            lufsTargeting_.process(tmpBuf, tmpBufSize);

            // JamesDSP 混响（扩展）：ViPER 全部效果之后、输出段（音量/pan/限幅）之前。
            // 与上游 ViPER 链无耦合——关闭时 jdspReverb_ 未配置，process 直接返回。
            jdspReverb_.process(tmpBuf, tmpBufSize);

            if (frameScale_ != 1.0) {
                adaptiveBuffer_.scaleFrames(frameScale_);
            }

            if (leftPan_ < 1.0 || rightPan_ < 1.0) {
                adaptiveBuffer_.panFrames(leftPan_, rightPan_);
            }

            for (int i = 0; i < tmpBufSize * 2; i += 2) {
                tmpBuf[i] = softwareLimiters_[0].process(tmpBuf[i]);
                tmpBuf[i + 1] = softwareLimiters_[1].process(tmpBuf[i + 1]);
            }

            if (adaptiveBuffer_.popFrames(buffer, tmpBufSize) == 0) {
                adaptiveBuffer_.flushBuffer();
                Arrays.fill(buffer, 0, size * 2, 0.0f);
                return;
            }

            if (size <= tmpBufSize) {
                return;
            }
        }

        // 上游 memmove + memset：把有效数据挪到缓冲尾部、前部补零。
        System.arraycopy(buffer, 0, buffer, (size - tmpBufSize) * 2, tmpBufSize * 2);
        Arrays.fill(buffer, 0, (size - tmpBufSize) * 2, 0.0f);
    }

    /** Mirror of {@code std::array<float, 6> ViPER::GetCompressorGainReductionDb()}. */
    public float[] getCompressorGainReductionDb() {
        float[] values = new float[6];
        for (int band = 0; band < MultibandCompressor.K_MAX_BANDS; band++) {
            values[band] = multibandCompressor_.getBandGainReductionDb(band);
        }
        values[5] = fetCompressor_.getGainReductionDb();
        return values;
    }

    /** Mirror of {@code void ViPER::RequestEffectsReset()}. */
    public void requestEffectsReset() {
        pendingEffectsReset_.set(true);
    }

    /** Mirror of {@code void ViPER::ResetAllEffects()}. */
    public void resetAllEffects() {
        adaptiveBuffer_.flushBuffer();

        waveBuffer_.reset();

        convolver_.setSampleRate(samplingRate_);
        convolver_.reset();

        vhe_.setSampleRate(samplingRate_);
        vhe_.reset();

        viperDdc_.setSampleRate(samplingRate_);
        viperDdc_.reset();

        spectrumExtend_.setSampleRate(samplingRate_);
        spectrumExtend_.reset();

        iirFilter_.setSampleRate(samplingRate_);
        iirFilter_.reset();

        dynamicEq_.setSampleRate(samplingRate_);
        dynamicEq_.reset();

        colorfulMusic_.setSampleRate(samplingRate_);
        colorfulMusic_.reset();

        stereoImager_.setSampleRate(samplingRate_);
        stereoImager_.reset();

        reverberation_.reset();

        playbackGain_.setSampleRate(samplingRate_);
        playbackGain_.reset();

        lufsTargeting_.setSampleRate(samplingRate_);
        lufsTargeting_.reset();

        fetCompressor_.setSampleRate(samplingRate_);
        fetCompressor_.reset();

        multibandCompressor_.setSampleRate(samplingRate_);
        multibandCompressor_.reset();

        dynamicSystem_.setSampleRate(samplingRate_);
        dynamicSystem_.reset();

        viperBass_.setSampleRate(samplingRate_);
        viperBass_.reset();

        viperBassMono_.setSampleRate(samplingRate_);
        viperBassMono_.reset();

        psychoacousticBass_.setSampleRate(samplingRate_);
        psychoacousticBass_.reset();

        viperClarity_.setSampleRate(samplingRate_);
        viperClarity_.reset();

        diffSurround_.setSampleRate(samplingRate_);
        diffSurround_.reset();

        cure_.setSampleRate(samplingRate_);
        cure_.reset();

        tubeSimulator_.reset();

        analogX_.setSampleRate(samplingRate_);
        analogX_.reset();

        speakerCorrection_.setSampleRate(samplingRate_);
        speakerCorrection_.reset();

        // JamesDSP 混响（扩展）：延迟长度按采样率缩放，此处必须按新采样率重建；
        // 关闭时只标记未配置（process 直接旁路）。
        if (jdspReverbParams_.enable) {
            configureJdspReverbLocked(jdspReverbParams_);
        } else {
            jdspReverb_.reset();
        }

        for (SoftwareLimiter limiter : softwareLimiters_) {
            limiter.reset();
        }
    }

    /** Mirror of {@code void ViPER::RequestBuffersReset()}. */
    public void requestBuffersReset() {
        pendingBuffersReset_.set(true);
    }

    /** Mirror of {@code void ViPER::ResetBuffers()}. */
    public void resetBuffers() {
        adaptiveBuffer_.flushBuffer();
        waveBuffer_.reset();
        reverberation_.reset();
    }

    /**
     * Mirror of {@code void ViPER::ApplyParams(const viper::ViPERParams &params)}：
     * 与上次应用值逐组比较，仅在变化时下发（{@code operator==} 语义，含「双方均未启用即相等」）。
     *
     * <p>末行的整引用替换与上游逐组 {@code last_applied_.x = p} 等价：调用过程中未被下发的组
     * 本就与入参相等，被下发的组已等于入参——结束时逐组相等 ⟺ 整体引用替换。调用方不应在
     * 下发后继续修改传入对象。</p>
     */
    public void applyParams(ViPERParams params) {
        if (!params.masterLimiter.equals(lastApplied_.masterLimiter)) {
            applyMasterLimiter(params.masterLimiter);
        }
        if (!params.playbackGainControl.equals(lastApplied_.playbackGainControl)) {
            applyPlaybackGainControl(params.playbackGainControl);
        }
        if (!params.lufs.equals(lastApplied_.lufs)) {
            applyLufs(params.lufs);
        }
        if (!params.fetCompressor.equals(lastApplied_.fetCompressor)) {
            applyFetCompressor(params.fetCompressor);
        }
        if (!params.bass.equals(lastApplied_.bass)) {
            applyBass(params.bass);
        }
        if (!params.bassMono.equals(lastApplied_.bassMono)) {
            applyBassMono(params.bassMono);
        }
        if (!params.psychoacousticBass.equals(lastApplied_.psychoacousticBass)) {
            applyPsychoacousticBass(params.psychoacousticBass);
        }
        if (!params.spectrumExtension.equals(lastApplied_.spectrumExtension)) {
            applySpectrumExtension(params.spectrumExtension);
        }
        if (!params.equalizer.equals(lastApplied_.equalizer)) {
            applyEqualizer(params.equalizer);
        }
        if (!params.convolver.equals(lastApplied_.convolver)) {
            applyConvolver(params.convolver);
        }
        if (!params.ddc.equals(lastApplied_.ddc)) {
            applyDdc(params.ddc);
        }
        if (!params.fieldSurround.equals(lastApplied_.fieldSurround)) {
            applyFieldSurround(params.fieldSurround);
        }
        if (!params.diffSurround.equals(lastApplied_.diffSurround)) {
            applyDiffSurround(params.diffSurround);
        }
        if (!params.stereoImager.equals(lastApplied_.stereoImager)) {
            applyStereoImager(params.stereoImager);
        }
        if (!params.headphoneSurround.equals(lastApplied_.headphoneSurround)) {
            applyHeadphoneSurround(params.headphoneSurround);
        }
        if (!params.reverb.equals(lastApplied_.reverb)) {
            applyReverb(params.reverb);
        }
        if (!params.dynamicSystem.equals(lastApplied_.dynamicSystem)) {
            applyDynamicSystem(params.dynamicSystem);
        }
        if (!params.clarity.equals(lastApplied_.clarity)) {
            applyClarity(params.clarity);
        }
        if (!params.cure.equals(lastApplied_.cure)) {
            applyCure(params.cure);
        }
        if (!params.tubeSimulator.equals(lastApplied_.tubeSimulator)) {
            applyTubeSimulator(params.tubeSimulator);
        }
        if (!params.analogX.equals(lastApplied_.analogX)) {
            applyAnalogX(params.analogX);
        }
        if (!params.speakerCorrection.equals(lastApplied_.speakerCorrection)) {
            applySpeakerCorrection(params.speakerCorrection);
        }
        if (!params.multibandCompressor.equals(lastApplied_.multibandCompressor)) {
            applyMultibandCompressor(params.multibandCompressor);
        }
        if (!params.dynamicEq.equals(lastApplied_.dynamicEq)) {
            applyDynamicEq(params.dynamicEq);
        }
        // 扩展：JamesDSP 混响（非上游 ViPER；上游无此组，diff 语义与其它组一致）。
        if (!params.jdspReverb.equals(lastApplied_.jdspReverb)) {
            applyJdspReverb(params.jdspReverb);
        }

        lastApplied_ = params;
    }

    /**
     * Mirror of {@code DspGraph::ApplySnapshot(const viper::ViPERParams &snapshot)}：
     * 图重建 / 快照装载时的<b>无条件</b>全量下发（24 组依次 Apply，并同步 last_applied_）。
     *
     * <p>与 {@link #applyParams}（增量 diff 通道）的分工与上游一致：Prepare 时全量、之后信箱增量。
     * 若此处也用 diff，会出现「入参恰等于结构默认值的组被跳过」的语义空洞——例如
     * {@code masterLimiter.threshold = 1.0}（结构与预设双默认）时，限幅器门限会停在构造默认
     * {@code 0.999999f} 而不是 1.0。</p>
     */
    public void applySnapshot(ViPERParams params) {
        applyMasterLimiter(params.masterLimiter);
        applyPlaybackGainControl(params.playbackGainControl);
        applyLufs(params.lufs);
        applyFetCompressor(params.fetCompressor);
        applyBass(params.bass);
        applyBassMono(params.bassMono);
        applyPsychoacousticBass(params.psychoacousticBass);
        applySpectrumExtension(params.spectrumExtension);
        applyEqualizer(params.equalizer);
        applyConvolver(params.convolver);
        applyDdc(params.ddc);
        applyFieldSurround(params.fieldSurround);
        applyDiffSurround(params.diffSurround);
        applyStereoImager(params.stereoImager);
        applyHeadphoneSurround(params.headphoneSurround);
        applyReverb(params.reverb);
        applyDynamicSystem(params.dynamicSystem);
        applyClarity(params.clarity);
        applyCure(params.cure);
        applyTubeSimulator(params.tubeSimulator);
        applyAnalogX(params.analogX);
        applySpeakerCorrection(params.speakerCorrection);
        applyMultibandCompressor(params.multibandCompressor);
        applyDynamicEq(params.dynamicEq);
        // 扩展：JamesDSP 混响（与其它组的无条件全量下发一致）。
        applyJdspReverb(params.jdspReverb);
        lastApplied_ = params;
    }

    /** Mirror of {@code void ViPER::ApplyMasterLimiter(...)}. */
    public void applyMasterLimiter(ViPERParams.MasterLimiterParams p) {
        softwareLimiters_[0].setGate(p.threshold);
        softwareLimiters_[1].setGate(p.threshold);
        frameScale_ = p.outputVolume;
        if (p.channelPan < 0.0f) {
            leftPan_ = 1.0f;
            rightPan_ = 1.0f + p.channelPan;
        } else {
            leftPan_ = 1.0f - p.channelPan;
            rightPan_ = 1.0f;
        }
    }

    /** Mirror of {@code void ViPER::ApplyPlaybackGainControl(...)}. */
    public void applyPlaybackGainControl(ViPERParams.PlaybackGainControlParams p) {
        playbackGain_.setEnable(p.enable);
        playbackGain_.setRatio(p.strength);
        playbackGain_.setMaxGainFactor(p.maxGain);
        playbackGain_.setVolume(p.outputThreshold);
    }

    /** Mirror of {@code void ViPER::ApplyLufs(...)}. */
    public void applyLufs(ViPERParams.LufsParams p) {
        lufsTargeting_.setEnable(p.enable);
        lufsTargeting_.setTargetLUFS(p.target);
        lufsTargeting_.setMaxGain(p.maxGain);
        lufsTargeting_.setSpeed(p.speed);
    }

    /** Mirror of {@code void ViPER::ApplyFetCompressor(...)}. */
    public void applyFetCompressor(ViPERParams.FetCompressorParams p) {
        fetCompressor_.setEnable(p.enable);
        fetCompressor_.setThreshold(p.threshold);
        fetCompressor_.setRatio(p.ratio);
        fetCompressor_.setKnee(p.knee);
        fetCompressor_.setKneeAuto(p.kneeAuto);
        fetCompressor_.setGain(p.gain);
        fetCompressor_.setGainAuto(p.gainAuto);
        fetCompressor_.setAttack(p.attack);
        fetCompressor_.setAttackAuto(p.attackAuto);
        fetCompressor_.setRelease(p.release);
        fetCompressor_.setReleaseAuto(p.releaseAuto);
        fetCompressor_.setKneeMulti(p.kneeMulti);
        fetCompressor_.setMaxAttack(p.maxAttack);
        fetCompressor_.setMaxRelease(p.maxRelease);
        fetCompressor_.setCrest(p.crest);
        fetCompressor_.setAdapt(p.adapt);
        fetCompressor_.setNoClip(p.noClip);
    }

    /** Mirror of {@code void ViPER::ApplyBass(...)}. */
    public void applyBass(ViPERParams.BassParams p) {
        viperBass_.setEnable(p.enable);
        viperBass_.setProcessMode(processModeViPERBass(p.mode));
        viperBass_.setFrequency(p.frequency);
        viperBass_.setBassFactor(p.gain);
        viperBass_.setAntiPop(p.antiPop);
    }

    /** Mirror of {@code void ViPER::ApplyBassMono(...)}. */
    public void applyBassMono(ViPERParams.BassMonoParams p) {
        viperBassMono_.setEnable(p.enable);
        viperBassMono_.setProcessMode(processModeBassMono(p.mode));
        viperBassMono_.setFrequency(p.frequency);
        viperBassMono_.setBassFactor(p.gain);
        viperBassMono_.setAntiPop(p.antiPop);
    }

    /** Mirror of {@code void ViPER::ApplyPsychoacousticBass(...)}. */
    public void applyPsychoacousticBass(ViPERParams.PsychoacousticBassParams p) {
        psychoacousticBass_.setEnable(p.enable);
        psychoacousticBass_.setCutoff(p.cutoff);
        psychoacousticBass_.setIntensity(p.intensity);
        psychoacousticBass_.setHarmonicOrder(p.harmonicOrder);
        psychoacousticBass_.setOriginalBassLevel(p.originalLevel);
    }

    /** Mirror of {@code void ViPER::ApplySpectrumExtension(...)}. */
    public void applySpectrumExtension(ViPERParams.SpectrumExtensionParams p) {
        spectrumExtend_.setEnable(p.enable);
        spectrumExtend_.setReferenceFrequency(p.strength);
        spectrumExtend_.setExciter(p.exciter);
    }

    /** Mirror of {@code void ViPER::ApplyEqualizer(...)}. */
    public void applyEqualizer(ViPERParams.EqualizerParams p) {
        iirFilter_.setEnable(p.enable);
        iirFilter_.setBandCount(p.bandCount);
        for (int i = 0; i < p.bandCount && i < p.bandLevels.length; i++) {
            iirFilter_.setBandLevel(i, p.bandLevels[i]);
        }
    }

    /** Mirror of {@code void ViPER::ApplyConvolver(...)}. */
    public void applyConvolver(ViPERParams.ConvolverParams p) {
        convolver_.setCrossChannel(p.crossChannel);
        convolver_.setWet(p.wet);
        convolver_.setOutputGain(p.outputGainDb);
        convolver_.setRouting(matrixRouting(p.routing));
        convolver_.setCrossDelay(p.crossDelayMs);
        convolver_.setEnable(p.enable);
    }

    /** Mirror of {@code void ViPER::ApplyDdc(...)}. */
    public void applyDdc(ViPERParams.DdcParams p) {
        viperDdc_.setEnable(p.enable);
    }

    /** Mirror of {@code void ViPER::ApplyFieldSurround(...)}. */
    public void applyFieldSurround(ViPERParams.FieldSurroundParams p) {
        colorfulMusic_.setEnable(p.enable);
        colorfulMusic_.setWidenValue(p.widening);
        colorfulMusic_.setMidImageValue(p.midImage);
        colorfulMusic_.setDepthValue(p.depth);
    }

    /** Mirror of {@code void ViPER::ApplyDiffSurround(...)}. */
    public void applyDiffSurround(ViPERParams.DiffSurroundParams p) {
        diffSurround_.setEnable(p.enable);
        diffSurround_.setDelayTime(p.delay);
        diffSurround_.setReverse(p.reverse);
        diffSurround_.setWetDryMix(p.wetDryMix);
        diffSurround_.setLPCutoff(p.lpCutoff);
    }

    /** Mirror of {@code void ViPER::ApplyStereoImager(...)}. */
    public void applyStereoImager(ViPERParams.StereoImagerParams p) {
        stereoImager_.setEnable(p.enable);
        stereoImager_.setLowWidth(p.lowWidth);
        stereoImager_.setMidWidth(p.midWidth);
        stereoImager_.setHighWidth(p.highWidth);
        stereoImager_.setLowCrossover(p.lowCrossover);
        stereoImager_.setHighCrossover(p.highCrossover);
    }

    /** Mirror of {@code void ViPER::ApplyHeadphoneSurround(...)}. */
    public void applyHeadphoneSurround(ViPERParams.HeadphoneSurroundParams p) {
        vhe_.setEnable(p.enable);
        vhe_.setEffectLevel(p.quality);
    }

    /** Mirror of {@code void ViPER::ApplyReverb(...)}. */
    public void applyReverb(ViPERParams.ReverbParams p) {
        reverberation_.setEnable(p.enable);
        reverberation_.setRoomSize(p.roomSize);
        reverberation_.setWidth(p.width);
        reverberation_.setDamp(p.damp);
        reverberation_.setWet(p.wet);
        reverberation_.setDry(p.dry);
    }

    /**
     * JamesDSP 混响（本模组扩展，非上游 ViPER）：应用一组参数并立即按当前采样率重建。
     *
     * <p>重建成本约 1MB 的延迟线清零 + 系数计算，仅发生在「开关 / 预设 / 自定义参数变化」时；
     * 采样率变化走 {@link #resetAllEffects()} 的重建分支。关闭时不重建——{@code process}
     * 见未配置即直接旁路。</p>
     */
    public void applyJdspReverb(ViPERParams.JdspReverbParams p) {
        jdspReverbParams_ = p;
        if (p.enable) {
            configureJdspReverbLocked(p);
        } else {
            jdspReverb_.reset();
        }
    }

    /** 按当前采样率配置 JamesDSP 混响（预设表或 17 个自定义参数）。 */
    private void configureJdspReverbLocked(ViPERParams.JdspReverbParams p) {
        if (p.custom) {
            jdspReverb_.configureCustom(samplingRate_, new float[]{
                    (float) p.oversampleFactor, p.ertolate, p.erefwet, p.dry, p.ereffactor,
                    p.erefwidth, p.width, p.wet, p.wander, p.bassb, p.spin, p.inputlpf,
                    p.basslpf, p.damplpf, p.outputlpf, p.rt60, p.delay
            });
        } else {
            jdspReverb_.configure(samplingRate_, p.preset);
        }
    }

    /** Mirror of {@code void ViPER::ApplyDynamicSystem(...)}. */
    public void applyDynamicSystem(ViPERParams.DynamicSystemParams p) {
        dynamicSystem_.setEnable(p.enable);
        dynamicSystem_.setXCoeffs(p.xCoeffLow, p.xCoeffHigh);
        dynamicSystem_.setYCoeffs(p.yCoeffLow, p.yCoeffHigh);
        dynamicSystem_.setSideGain(p.sideGainLow, p.sideGainHigh);
        dynamicSystem_.setBassGain(p.strength);
    }

    /** Mirror of {@code void ViPER::ApplyClarity(...)}. */
    public void applyClarity(ViPERParams.ClarityParams p) {
        viperClarity_.setEnable(p.enable);
        viperClarity_.setProcessMode(clarityMode(p.mode));
        viperClarity_.setClarityGain(p.gain);
    }

    /** Mirror of {@code void ViPER::ApplyCure(...)}. */
    public void applyCure(ViPERParams.CureParams p) {
        cure_.setEnable(p.enable);
        cure_.setPreset(p.crossfeedPreset);
    }

    /** Mirror of {@code void ViPER::ApplyTubeSimulator(...)}. */
    public void applyTubeSimulator(ViPERParams.TubeSimulatorParams p) {
        tubeSimulator_.setEnable(p.enable);
    }

    /** Mirror of {@code void ViPER::ApplyAnalogX(...)}. */
    public void applyAnalogX(ViPERParams.AnalogXParams p) {
        analogX_.setEnable(p.enable);
        analogX_.setProcessingModel(p.mode);
    }

    /** Mirror of {@code void ViPER::ApplySpeakerCorrection(...)}. */
    public void applySpeakerCorrection(ViPERParams.SpeakerCorrectionParams p) {
        speakerCorrection_.setEnable(p.enable);
    }

    /** Mirror of {@code void ViPER::ApplyMultibandCompressor(...)}. */
    public void applyMultibandCompressor(ViPERParams.MultibandCompressorParams p) {
        multibandCompressor_.setEnable(p.enable);
        multibandCompressor_.setBandCount(p.bandCount);
        for (int i = 0; i < p.bandCount && i < p.crossoverFrequencies.length; i++) {
            multibandCompressor_.setCrossoverFrequency(i, p.crossoverFrequencies[i]);
        }
        for (int i = 0; i < p.bandCount && i < p.bands.length; i++) {
            ViPERParams.MultibandCompressorBandParams b = p.bands[i];
            multibandCompressor_.setBandEnable(i, b.enable);
            multibandCompressor_.setBandThreshold(i, b.threshold);
            multibandCompressor_.setBandRatio(i, b.ratio);
            multibandCompressor_.setBandKnee(i, b.knee);
            multibandCompressor_.setBandKneeAuto(i, b.kneeAuto);
            multibandCompressor_.setBandGain(i, b.gain);
            multibandCompressor_.setBandGainAuto(i, b.gainAuto);
            multibandCompressor_.setBandAttack(i, b.attack);
            multibandCompressor_.setBandAttackAuto(i, b.attackAuto);
            multibandCompressor_.setBandRelease(i, b.release);
            multibandCompressor_.setBandReleaseAuto(i, b.releaseAuto);
            multibandCompressor_.setBandKneeMulti(i, b.kneeMulti);
            multibandCompressor_.setBandMaxAttack(i, b.maxAttack);
            multibandCompressor_.setBandMaxRelease(i, b.maxRelease);
            multibandCompressor_.setBandCrest(i, b.crest);
            multibandCompressor_.setBandAdapt(i, b.adapt);
            multibandCompressor_.setBandNoClip(i, b.noClip);
        }
    }

    /** Mirror of {@code void ViPER::ApplyDynamicEq(...)}. */
    public void applyDynamicEq(ViPERParams.DynamicEqParams p) {
        dynamicEq_.setEnable(p.enable);
        dynamicEq_.setBandCount(p.bandCount);
        for (int i = 0; i < p.bandCount && i < p.bands.length; i++) {
            ViPERParams.DynamicEqBandParams b = p.bands[i];
            dynamicEq_.setBandFrequency(i, b.frequency);
            dynamicEq_.setBandQ(i, b.q);
            dynamicEq_.setBandGain(i, b.gain);
            dynamicEq_.setBandThreshold(i, b.threshold);
            dynamicEq_.setBandAttack(i, b.attack);
            dynamicEq_.setBandRelease(i, b.release);
            dynamicEq_.setBandFilterType(i, b.filterType);
        }
    }

    /**
     * Mirror of {@code std::optional<uint32_t> ViPER::LoadConvolverKernel(...)}.
     *
     * @return 装入成功时返回 kernelId，否则 {@code null}（对应上游 std::nullopt）。
     */
    public Integer loadConvolverKernel(float[] samples, int frameCount, int channels, int kernelId) {
        if (samples == null) {
            return null;
        }
        if (channels != 1 && channels != 2 && channels != 4) {
            return null;
        }
        if (frameCount < 16) {
            return null;
        }
        if (frameCount > Integer.MAX_VALUE / channels) {
            return null;
        }

        final int totalFloats = frameCount * channels;
        if (totalFloats == 0) {
            return null;
        }

        convolver_.prepareKernelBuffer(totalFloats, channels, false);

        int written = 0;
        while (written < totalFloats) {
            final int remaining = totalFloats - written;
            final int chunk = Math.min(remaining, K_KERNEL_CHUNK_FLOATS);
            convolver_.setKernelBuffer(samples, written, chunk);
            written += chunk;
        }

        final int crc = Crc32.crc32(samples, totalFloats);

        convolver_.commitKernelBuffer(totalFloats, crc, kernelId);

        if (convolver_.getKernelID() != kernelId) {
            return null;
        }
        return kernelId;
    }

    /** Mirror of {@code void ViPER::UnloadConvolverKernel()}. */
    public void unloadConvolverKernel() {
        convolver_.prepareKernelBuffer(0, 0, true);
    }

    /** Mirror of {@code void ViPER::LoadDdcCoefficients(...)}。两个表按 5 float 一段排列。 */
    public void loadDdcCoefficients(float[] sections44100, float[] sections48000, int sectionCount) {
        if (sectionCount == 0) {
            viperDdc_.setCoeffs(0, null, null);
        }
        final int totalFloats = sectionCount * 5;
        viperDdc_.setCoeffs(totalFloats, sections44100, sections48000);
    }

    /** Mirror of {@code void ViPER::UnloadDdcCoefficients()}. */
    public void unloadDdcCoefficients() {
        viperDdc_.clearCoeffs();
    }

    /**
     * Mirror of {@code const viper::ViPERParams &ViPER::CurrentParams()}。
     *
     * <p>注意：本实现的 lastApplied_ 为「上次 applyParams 入参的引用」（见 applyParams 说明），
     * 调用方不得修改返回对象。</p>
     */
    public ViPERParams currentParams() {
        return lastApplied_;
    }

    public int getSampleRate() {
        return samplingRate_;
    }

    /** Mirror of {@code void ViPER::SetSamplingRate(uint32_t)}（仅设值；生效经 ResetAllEffects）。 */
    public void setSampleRate(int rate) {
        samplingRate_ = rate;
    }

    public long getProcessedFrames() {
        return processFrameCount_;
    }

    public int getConvolverKernelID() {
        return convolver_.getKernelID();
    }

    // —— 小工具：越界枚举值安全映射（C++ 为未定义行为，预设实际取值恒在界内） ——

    private static ViPERBass.ProcessMode processModeViPERBass(int mode) {
        ViPERBass.ProcessMode[] values = ViPERBass.ProcessMode.values();
        return values[mode >= 0 && mode < values.length ? mode : 0];
    }

    private static ViPERBassMono.ProcessMode processModeBassMono(int mode) {
        ViPERBassMono.ProcessMode[] values = ViPERBassMono.ProcessMode.values();
        return values[mode >= 0 && mode < values.length ? mode : 0];
    }

    private static ViPERClarity.ClarityMode clarityMode(int mode) {
        ViPERClarity.ClarityMode[] values = ViPERClarity.ClarityMode.values();
        return values[mode >= 0 && mode < values.length ? mode : 0];
    }

    private static MatrixConvolver.MatrixRouting matrixRouting(int routing) {
        MatrixConvolver.MatrixRouting[] values = MatrixConvolver.MatrixRouting.values();
        return values[routing >= 0 && routing < values.length ? routing : 0];
    }
}
