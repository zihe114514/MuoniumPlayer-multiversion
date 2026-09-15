package com.muoniumplayer.core.ncm.music;

import lombok.Getter;
import lombok.SneakyThrows;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
import repackage.processing.sound.AutomixDeckChain;
import repackage.processing.sound.SoundFile;

import java.io.File;

/**
 * 播放视频里面的音频
 */
public class AudioPlayer {
    public SoundFile player;
    public Runnable afterPlayed;

    @Getter
    public float volume = 0.25f;

    /**
     * Crossfade multiplier applied on top of {@link #volume}. It exists so an automix handover can ramp
     * one deck down while another ramps up without touching the persisted player volume, which both
     * decks keep sharing.
     */
    private volatile float fadeGain = 1.0f;

    /**
     * The automix processing chain (low shelf + vocal-band canceller) sitting in this deck's own signal
     * path. Only attached when automix is actually going to use it, so a player with automix off keeps
     * exactly the original graph. At 0 dB and depth 0 both stages are bit-exact pass-throughs, so the
     * chain can stay attached for a whole track.
     */
    private AutomixDeckChain automixChain;
    private volatile float bassGainDb = 0.0f;
    private volatile float vocalDuckDepth = 0.0f;
    private volatile float midGainDb = 0.0f;
    private volatile float highGainDb = 0.0f;
    private volatile float playbackRate = 1.0f;
    /** 音高倍率。只有「整半音吸附」会动它；节奏锁定恒为 1。 */
    private volatile float pitchRatio = 1.0f;

    /**
     * 上一次 {@link #setPlaybackRate} 是否由 WSOLA 时间伸缩完成（而不是重采样）。
     *
     * <p>只用于日志与诊断。真正的分支判断每次都重新问 {@code SoundFile.timeStretch()} 的返回值，
     * 不依赖这个缓存 —— 曲目换了、伸缩层能力变了，缓存会过期。</p>
     */
    private volatile boolean pitchPreserved;

    /**
     * The decoded cache file this deck was built from, kept so offline analysis can open it again with
     * its own handle. Reading the live JSyn sample instead is not an option: it streams from a shared
     * sliding window, and moving that window under the audio thread breaks playback outright.
     */
    private volatile File sourceFile;
    /** Human-readable track metadata used by non-audio status surfaces. */
    private volatile String trackName;

    /**
     * Some downloaded files can be opened by the decoder but contain no usable
     * audio frames. AudioSample.position() performs a modulo by frames(), so an
     * empty sample must never reach the playback or rendering paths.
     */
    private boolean closed;

    /** Set by {@link #prepareStart(float)}: the reader is cued, the shelf is wired and the window warm. */
    private volatile boolean prepared;

    /**
     * The intent "this deck has been asked to play", latched synchronously at the moment
     * {@link #play()} queues its command.
     *
     * <p><b>Why this exists.</b> {@code AudioSample.isPlaying()} reads the engine thread's state, while
     * {@code play()} only queues a command - it takes effect at the audio thread's next
     * {@code generateNextBuffer()}. Until then {@code isPlaying()} is still false. The player UI draws
     * its pause icon from exactly {@code !isPlaying()}, so "decoded, command queued, sound arriving any
     * moment" and "not playing at all" look identical on screen.</p>
     *
     * <p>First playback makes that window especially long: between the decode finishing and
     * {@code play()} there is a {@code loadLyric()} network round trip (a multi-source lyric match on
     * the first run), preceded by a fixed 250 ms wait in {@code stopPreviousPlayer()}. What the user
     * sees is a track already audible while the pause icon and the progress bar have not moved.</p>
     *
     * <p>Splitting "intent" from "engine state" means the UI's question to {@link #isPausing()} is
     * answered by whether this playback counts as running from the user's point of view, no longer
     * jittering with the command queue's latency. It deliberately does <b>not</b> replace
     * {@code isPlaying()}: code that truly needs to know whether the engine is sounding still asks the
     * engine.</p>
     */
    private volatile boolean playRequested;

    public AudioPlayer(File file) {
        finished = false;
        this.player = loadUsableSoundFile(file);
        this.sourceFile = file;
        this.setListeners();
        this.closed = false;
    }

    public void setAudio(File file) {
        // Decode and validate before closing the currently playing song. A
        // corrupt/empty replacement must not destroy an otherwise usable player.
        SoundFile next = loadUsableSoundFile(file);
        next.setOnFinished(() -> finished = true);

        releaseAutomixChain();
        SoundFile previous = this.player;
        this.player = next;
        finished = false;
        this.closed = false;
        this.prepared = false;
        // The new track has not been asked to play yet. This deck still carries the previous
        // track's play intent, and leaving it set would make the UI report "playing" before the
        // new track has actually started - the very mismatch this change fixes.
        this.playRequested = false;
        this.bassGainDb = 0.0f;
        this.vocalDuckDepth = 0.0f;
        this.playbackRate = 1.0f;
        this.pitchRatio = 1.0f;
        this.pitchPreserved = false;
        // 交叉淡入的增益必须一起归位。fadeGain 属于某一次转场，不属于这个 deck：
        // 淡出侧会被压到 0，预卷（pre-roll）的 deck 也从 0 起步。如果那次转场被打断
        // （被抢先的切歌、队列版本变化、一起听房间中途换歌），只有 incoming == ownedPlayer
        // 这一种情形会被还原成 1；其余情况这个 deck 带着 0 活下来，随后又可能被提升成
        // 活动播放器。那时 setAudio() 换上新曲，effectiveAmp() = volume * 0 —— 下载正常、
        // 解码正常、进度条正常走，就是一点声音都没有，而且不报任何错。
        this.fadeGain = 1.0f;
        this.sourceFile = file;
        dispose(previous);
    }

    public void setListeners() {
        if (player != null)
            player.setOnFinished(() -> finished = true);
    }

    public void play() {
        if (!isUsable())
            return;

        attachAutomixChain();

        finished = false;
        // Latch before queueing: once this command is in, the UI already knows this deck has been
        // asked to play, whatever the engine thread does next - it need not wait for isPlaying().
        this.playRequested = true;
        try {
            // Amplitude first: playback starts at the configured level instead of at the engine's
            // default of full volume for the first few rendered frames.
            this.player.amp(effectiveAmp());
            this.player.play();
            this.player.amp(effectiveAmp());
        } catch (ChannelMismatchException mismatch) {
            // Preserve the existing retry path in CloudMusic for channel changes.
            this.playRequested = false;
            throw mismatch;
        } catch (RuntimeException ignored) {
            // A decoder can become invalid between validation and playback.
            this.playRequested = false;
        }
    }

    @SneakyThrows
    public void setPlaybackTime(float millis) {
        if (!isUsable())
            return;

        float total = getTotalTimeMillis();
        if (total <= 0.0f)
            return;

        float safeMillis = Math.max(0.0f, Math.min(total, millis));
        try {
            this.player.jump(safeMillis / 1000F);
            this.player.resyncTimeStretch();
            this.player.amp(effectiveAmp());
        } catch (RuntimeException ignored) {
            // Seeking is best-effort. A malformed stream must not crash the UI.
        }
    }

    /**
     * Starts playback from an explicit offset. Used by the automix handover so the incoming deck can
     * enter after a dead intro instead of always starting at zero.
     *
     * <p>{@code cue()} stops the reader first, so the following {@code play()} re-uses the same JSyn
     * voice rather than allocating a second concurrent one.</p>
     */
    public void playFrom(float millis) {
        if (!isUsable())
            return;

        finished = false;
        this.playRequested = true;
        try {
            this.player.amp(effectiveAmp());
            cueTo(millis);
            // After cue(), never before: AudioSample.cue() goes through stop(), which rips the effect
            // out of the circuit and never puts it back (JSyn's Processing wrapper has its own TODO
            // about that). Attaching first meant the incoming deck of a blend played completely
            // unfiltered - both tracks kept their full low end and the seam thumped.
            attachAutomixChain();
            this.player.play();
            this.player.amp(effectiveAmp());
        } catch (ChannelMismatchException mismatch) {
            this.playRequested = false;
            throw mismatch;
        } catch (RuntimeException ignored) {
            // A decoder can become invalid between validation and playback.
            this.playRequested = false;
        }
    }

    /**
     * Does everything a start needs <em>except</em> making a sound: cues the reader, wires the automix
     * low shelf into the (idle) circuit and pulls the first streaming window off the disk.
     *
     * <p>This exists because of how a deck actually reads its audio. The decoded WAV is not held in
     * RAM; a {@code RandomAccessFile} is read through a 4096-sample sliding window that is refilled
     * <b>from the JSyn engine thread</b>. A deck that is started at the moment of a crossfade therefore
     * pays for a file seek, an 8 KB read and a graph rewire inside the audio callback, right when the
     * other deck also wants to be served - which is exactly the hitch a "seamless" handover must not
     * have. Doing all of it on the arming thread, seconds early, leaves only the amplitude ramp for the
     * seam itself.</p>
     */
    public void prepareStart(float millis) {
        if (!isUsable()) return;
        try {
            finished = false;
            this.player.amp(0.0f);
            float cued = cueTo(millis);
            attachAutomixChain();
            primeStreamWindow(cued);
            this.prepared = true;
        } catch (RuntimeException ignored) {
            this.prepared = false;
        }
    }

    /**
     * Starts a deck made ready by {@link #prepareStart(float)}. Deliberately does not cue: cueing again
     * would stop the reader, strip the low shelf back out and throw away the primed window.
     */
    public void startPrepared() {
        if (!isUsable()) return;
        if (!this.prepared) {
            play();
            return;
        }
        finished = false;
        this.playRequested = true;
        try {
            this.player.amp(effectiveAmp());
            attachAutomixChain();   // idempotent; the chain normally survives from prepareStart()
            this.player.play();
            this.player.amp(effectiveAmp());
        } catch (ChannelMismatchException mismatch) {
            this.playRequested = false;
            throw mismatch;
        } catch (RuntimeException ignored) {
            this.playRequested = false;
        }
    }

    /** True once {@link #prepareStart(float)} has cued and warmed this deck. */
    public boolean isPrepared() {
        return this.prepared;
    }

    /** Clamps to the sample and cues the reader. Returns the position actually cued to, in millis. */
    private float cueTo(float millis) {
        float total = getTotalTimeMillis();
        float safeMillis = total <= 0.0f
                ? 0.0f
                : Math.max(0.0f, Math.min(Math.max(0.0f, total - 50.0f), millis));
        if (safeMillis > 0.0f) this.player.cue(safeMillis / 1000F);
        // 定位之后必须让时间伸缩层重新对齐：它的输出时间轴是自己往前长的，
        // 不重锚就会在跳转后继续放跳转前那一段。
        this.player.resyncTimeStretch();
        return safeMillis;
    }

    /**
     * Forces the streaming window to the given position so the first read does not happen inside the
     * audio callback. One sample is enough: the reader fills a whole window around the index it is
     * asked for. Only ever called on a deck that is not sounding - moving the window of a playing
     * sample is what used to crash the engine thread.
     */
    private void primeStreamWindow(float millis) {
        try {
            int channels = Math.max(1, this.player.channels());
            int sampleRate = this.player.sampleRate();
            if (sampleRate <= 0) return;
            long frame = (long) ((millis / 1000.0f) * sampleRate);
            int frames = this.player.frames();
            if (frames <= 0) return;
            if (frame < 0L) frame = 0L;
            if (frame > frames - 1L) frame = frames - 1L;
            long index = frame * channels;
            if (index > Integer.MAX_VALUE - channels) return;
            this.player.read((int) index);
        } catch (RuntimeException ignored) {
            // Priming is an optimisation; a decoder that refuses it still plays.
        }
    }

    /**
     * Sets the crossfade multiplier. {@code 0} is silent, {@code 1} is the configured player volume;
     * values above 1 are allowed so a quiet incoming track can be loudness-matched, and the product is
     * clamped to the engine's valid amplitude range.
     */
    public void setFadeGain(float gain) {
        this.fadeGain = Float.isNaN(gain) || Float.isInfinite(gain) ? 0.0f : Math.max(0.0f, gain);
        applyAmp();
    }

    public float getFadeGain() {
        return this.fadeGain;
    }

    /** Audio-thread variant used by automix automation; no command is queued back onto JSyn. */
    void applyAutomixFrame(float fade, float bassDb, float vocalDepth, float midDb, float highDb) {
        this.fadeGain = Float.isFinite(fade) ? Math.max(0f, fade) : 0f;
        this.bassGainDb = Float.isFinite(bassDb) ? bassDb : 0f;
        this.vocalDuckDepth = Float.isFinite(vocalDepth) ? Math.max(0f, Math.min(1f, vocalDepth)) : 0f;
        this.midGainDb = Float.isFinite(midDb) ? Math.max(-3f, Math.min(3f, midDb)) : 0f;
        this.highGainDb = Float.isFinite(highDb) ? Math.max(-3f, Math.min(3f, highDb)) : 0f;
        if (!isUsable()) return;
        this.player.setAmplitudeNow(this.volume * this.fadeGain);
        AutomixDeckChain chain = this.automixChain;
        if (chain != null) chain.setTargetsNow(this.bassGainDb, this.vocalDuckDepth,
                this.midGainDb, this.highGainDb);
    }

    /** Audio-thread variant for the outgoing deck, including the separated tempo/pitch controls. */
    void applyAutomixFrame(float fade, float bassDb, float tempo, float pitch) {
        applyAutomixFrame(fade, bassDb, 0f, 0f, 0f);
        applySpeedAndPitchNow(tempo, pitch);
    }

    private void applySpeedAndPitchNow(float tempo, float pitch) {
        if (!isUsable()) return;
        float safeTempo = Float.isFinite(tempo) ? Math.max(.5f, Math.min(2f, tempo)) : 1f;
        float safePitch = Float.isFinite(pitch) ? Math.max(.5f, Math.min(2f, pitch)) : 1f;
        this.playbackRate = safeTempo;
        this.pitchRatio = safePitch;
        if (this.player.canTimeStretch()) {
            this.pitchPreserved = true;
            this.player.setRateAndStretchNow(safePitch, safeTempo / safePitch);
        } else {
            this.pitchPreserved = false;
            float combined = Math.max(AutomixSettings.MIN_PLAYBACK_RATE,
                    Math.min(AutomixSettings.MAX_PLAYBACK_RATE, safeTempo * safePitch));
            this.player.setRateNow(combined);
        }
    }

    /**
     * Attaches the automix processing chain if it is not already in the graph.
     *
     * <p>Called just before the deck starts sounding, so the graph is never rewired while audio is
     * running through it. The chain is only created when automix is actually allowed to use it: with
     * automix off - or with both the bass swap and the vocal duck off - the deck keeps the original,
     * effect-free graph.</p>
     */
    private void attachAutomixChain() {
        if (!AutomixSettings.isEnabled()) return;
        if (!AutomixSettings.isFoliaEngine() && !AutomixSettings.isBassSwapEnabled()
                && !AutomixSettings.isVocalDuckEnabled()) return;
        if (!isUsable()) return;
        try {
            if (this.automixChain == null) {
                this.automixChain = new AutomixDeckChain();
                this.automixChain.frequency(AutomixSettings.BASS_SHELF_HZ);
            }
            // The centre canceller needs to know whether there is a real stereo image: a mono file is
            // panned into two identical channels, where subtracting the centre would take the whole
            // cancelled band with it.
            this.automixChain.stereo(channelCount() >= 2);
            this.automixChain.resetTo(this.bassGainDb, this.vocalDuckDepth);
            this.automixChain.midGainDb(this.midGainDb);
            this.automixChain.highGainDb(this.highGainDb);
            // Not isProcessing(): pause() rips the effect out of the circuit without telling the
            // effect, so a resumed deck has to be re-wired or it would play unfiltered.
            if (!this.automixChain.isAttachedTo(this.player)) {
                this.automixChain.forget(this.player);
                this.automixChain.process(this.player);
            }
        } catch (Throwable ignored) {
            // An engine that refuses the extra units must not stop the track from playing.
            this.automixChain = null;
        }
    }

    private int channelCount() {
        try {
            return Math.max(1, this.player.channels());
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    /**
     * Sets the low shelf gain in dB for this deck. Negative values pull the bass out, which is how the
     * handover keeps two overlapping tracks from stacking their kick drums into one boom.
     *
     * <p>A no-op when no shelf is attached (automix or the bass swap disabled), so callers do not have
     * to know which decks are filtered.</p>
     */
    public void setBassGainDb(float db) {
        float safe = Float.isNaN(db) || Float.isInfinite(db) ? 0.0f : db;
        this.bassGainDb = safe;
        AutomixDeckChain chain = this.automixChain;
        if (chain == null) return;
        try {
            chain.gainDb(safe);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Sets how hard this deck's vocal band is cancelled, 0 leaving it alone and 1 being full
     * cancellation of the band-limited centre.
     *
     * <p>Used on the deck that is fading <em>in</em>: it enters as an instrumental bed so the outgoing
     * track's vocal never has a second vocal on top of it, and the cancellation is released before the
     * blend ends. A no-op when no chain is attached, so callers do not have to know which decks are
     * processed.</p>
     */
    public void setMidGainDb(float db) {
        float safe = Float.isNaN(db) || Float.isInfinite(db) ? 0f : Math.max(-3f, Math.min(3f, db));
        this.midGainDb = safe;
        AutomixDeckChain chain = this.automixChain;
        if (chain != null) try { chain.midGainDb(safe); } catch (RuntimeException ignored) {}
    }

    public void setHighGainDb(float db) {
        float safe = Float.isNaN(db) || Float.isInfinite(db) ? 0f : Math.max(-3f, Math.min(3f, db));
        this.highGainDb = safe;
        AutomixDeckChain chain = this.automixChain;
        if (chain != null) try { chain.highGainDb(safe); } catch (RuntimeException ignored) {}
    }

    public void setVocalDuckDepth(float depth) {
        float safe = Float.isNaN(depth) || Float.isInfinite(depth)
                ? 0.0f : Math.max(0.0f, Math.min(1.0f, depth));
        this.vocalDuckDepth = safe;
        AutomixDeckChain chain = this.automixChain;
        if (chain == null) return;
        try {
            chain.duckDepth(safe);
        } catch (RuntimeException ignored) {
        }
    }

    public float getVocalDuckDepth() {
        return this.vocalDuckDepth;
    }

    /** The decoded file behind this deck, or {@code null} when it is not known. */
    /** The display name of the track loaded on this deck, when known. */
    public String getTrackName() {
        return this.trackName;
    }

    public void setTrackName(String trackName) {
        this.trackName = trackName;
    }

    public File getSourceFile() {
        return this.sourceFile;
    }

    public float getBassGainDb() {
        return this.bassGainDb;
    }

    /**
     * Relative playback rate, 1.0 being the file's own tempo.
     *
     * <p>Used only on the deck that is fading <em>out</em> of a blend: nudging it onto the incoming
     * track's tempo keeps the two beat grids locked for the whole overlap instead of letting them drift
     * apart by a fraction of a beat.</p>
     *
     * <p><b>优先走时间伸缩，不走重采样。</b>{@code SoundFile.rate()} 拧的是 JSyn 读头的步进，速度和音高
     * 一起变 —— 1.03 倍就是升高约半个音，而且是那种"整首歌走调"的听感，用户反馈过。
     * {@link repackage.processing.sound.SoundFile#timeStretch} 走的是 WSOLA：只改速度，音高不动。
     * 只有这首歌没有伸缩层时（{@code wrap()} 判定包不了）才退回原来的重采样，
     * 那时的行为和这次改动之前完全一致。</p>
     */
    public void setPlaybackRate(float ratio) {
        setSpeedAndPitch(ratio, 1.0f);
    }

    /**
     * 分别设置<b>速度</b>与<b>音高</b>倍率，两者互不牵连。
     *
     * <p>这是 TarsosDSP 的 {@code PitchShiftingExample} 那条链在本项目里的等价物：它把
     * {@code WaveformSimilarityBasedOverlapAdd}（时间伸缩）和 {@code RateTransposer}（重采样）串起来，
     * 靠两者的比值把「速度」和「音高」解耦。本项目里重采样那一环<b>本来就有</b> ——
     * JSyn 读头的 {@code rate} 端口就是 {@code RateTransposer}，所以只要补上 WSOLA 就凑齐了。</p>
     *
     * <p>推导：WSOLA 用因子 {@code t} 时，吃掉 {@code t} 倍输入产出一份输出，音高不变；读头用
     * {@code rate = r} 时，速度与音高同时 × {@code r}。串起来<b>速度 = t·r、音高 = r</b>。于是：</p>
     * <ul>
     *   <li>只改速度（节奏锁定）：{@code r = 1}、{@code t = tempo}；</li>
     *   <li>只改音高（整半音吸附）：{@code r = pitch}、{@code t = tempo / pitch}，
     *       {@code tempo = 1} 时 {@code t = 1/pitch} 正好把重采样带来的时长变化还原回去。</li>
     * </ul>
     *
     * <p>没有伸缩层的曲子（{@code wrap()} 判定包不了：单声道以外的多声道、短到装不满一个 WSOLA 序列）
     * 只剩一个重采样端口，速度与音高重新耦合，只能把两者的乘积拧上去 —— 那正是接 WSOLA 之前的行为，
     * 所以此时也把乘积收回 automix 的一个半音预算之内。</p>
     *
     * @param tempo 速度倍率，1 表示按素材原速
     * @param pitch 音高倍率，1 表示不移调
     */
    public void setSpeedAndPitch(float tempo, float pitch) {
        if (Float.isNaN(tempo) || Float.isInfinite(tempo)) return;
        if (Float.isNaN(pitch) || Float.isInfinite(pitch)) return;
        float safeTempo = Math.max(.5f, Math.min(2.0f, tempo));
        float safePitch = Math.max(.5f, Math.min(2.0f, pitch));
        // The fade driver ticks every 10 ms and holds the rate flat for most of the blend; re-setting
        // the port with an unchanged value every tick would be pure overhead.
        if (Math.abs(safeTempo - this.playbackRate) < .0005f
                && Math.abs(safePitch - this.pitchRatio) < .0005f) {
            return;
        }
        this.playbackRate = safeTempo;
        this.pitchRatio = safePitch;
        if (!isUsable()) return;
        try {
            if (this.player.canTimeStretch()) {
                this.pitchPreserved = true;
                // 顺序无关紧要（两级串联），但读头必须先落到 pitch 上，否则某一帧里会出现
                // 「伸缩已经按解耦后的比值跑、读头还停在上一次的 pitch」这种半拍不一致。
                this.player.rate(safePitch);
                this.player.timeStretch(safeTempo / safePitch);
                return;
            }
            this.pitchPreserved = false;
            float combined = safeTempo * safePitch;
            if (combined < AutomixSettings.MIN_PLAYBACK_RATE) combined = AutomixSettings.MIN_PLAYBACK_RATE;
            if (combined > AutomixSettings.MAX_PLAYBACK_RATE) combined = AutomixSettings.MAX_PLAYBACK_RATE;
            this.player.rate(combined);
        } catch (RuntimeException ignored) {
        }
    }

    /** 当前的音高倍率，1 表示没移调。 */
    public float getPitchRatio() {
        return this.pitchRatio;
    }

    /** 这一次变速是不是由 WSOLA 完成的（也就是音高没被动过）。 */
    public boolean isPitchPreserved() {
        return this.pitchPreserved;
    }

    /** 时间伸缩层当前是否在跑。诊断用：交接结束后应当仍为 true（接管后不退回直通）。 */
    public boolean isTimeStretching() {
        return isUsable() && this.player.isTimeStretching();
    }

    public float getPlaybackRate() {
        return this.playbackRate;
    }

    /** Returns the deck to flat response, full vocal and natural tempo. */
    public void resetAutomixProcessing() {
        setBassGainDb(0.0f);
        setMidGainDb(0.0f);
        setHighGainDb(0.0f);
        setVocalDuckDepth(0.0f);
        if (Math.abs(this.playbackRate - 1.0f) > .0005f
                || Math.abs(this.pitchRatio - 1.0f) > .0005f) {
            setSpeedAndPitch(1.0f, 1.0f);
        }
    }

    private void releaseAutomixChain() {
        AutomixDeckChain chain = this.automixChain;
        this.automixChain = null;
        if (chain == null) return;
        try {
            chain.release();
        } catch (Throwable ignored) {
        }
    }

    /** Effective amplitude handed to the engine: persisted volume scaled by the crossfade gain. */
    private float effectiveAmp() {
        float amp = this.volume * this.fadeGain;
        if (Float.isNaN(amp) || Float.isInfinite(amp)) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, amp));
    }

    private void applyAmp() {
        if (!isUsable()) return;
        try {
            this.player.amp(effectiveAmp());
        } catch (RuntimeException ignored) {
            // Re-applied on the next successful playback operation.
        }
    }

    /**
     * Idempotent on purpose. A deck can now be released from several places (the play loop, a cancelled
     * switch, the end of a crossfade), and disposing a already cleaned-up sample would hand JSyn a null
     * buffer to queue.
     */
    @SneakyThrows
    public synchronized void close() {
        if (closed) return;
        closed = true;
        this.playRequested = false;
        releaseAutomixChain();
        dispose(this.player);
    }

    @Getter
    private boolean finished;

    public void setAfterPlayed(Runnable runnable) {
        this.afterPlayed = runnable;
        if (this.player != null)
            this.player.setOnFinished(() -> {
                finished = true;
                if (runnable != null)
                    runnable.run();
            });
    }

    public float getTotalTimeSeconds() {
        return (int) (getTotalTimeMillis() / 1000.0f);
    }

    public float getCurrentTimeSeconds() {
        return (int) (getCurrentTimeMillis() / 1000);
    }

    public float getTotalTimeMillis() {
        // 直接由原始浮点秒换算毫秒（round 到最近毫秒），保留小数时长。
        if (!isUsable())
            return 0.0f;

        try {
            float duration = this.player.duration();
            if (Float.isNaN(duration) || Float.isInfinite(duration) || duration <= 0.0f)
                return 0.0f;
            return Math.max(0.0f, Math.round(duration * 1000.0f));
        } catch (RuntimeException ignored) {
            return 0.0f;
        }
    }

    public float getCurrentTimeMillis() {
        if (!isUsable())
            return 0.0f;

        try {
            float position = this.player.position() * 1000.0f;
            if (Float.isNaN(position) || Float.isInfinite(position))
                return 0.0f;

            float total = getTotalTimeMillis();
            if (total <= 0.0f)
                return 0.0f;
            return Math.max(0.0f, Math.min(total, position));
        } catch (RuntimeException ignored) {
            // AudioSample.position() uses `% frames()` and can throw when a
            // decoder produced an empty sample. Report a safe position.
            return 0.0f;
        }
    }

    /**
     * Whether this deck counts as "not playing" from the user's point of view.
     *
     * <p>The test is the <b>play intent</b> ({@link #playRequested}), not the engine thread's
     * {@code isPlaying()}. The latter stays false for a whole engine buffer after {@code play()} is
     * queued, and on first playback it also sits behind decoding, a lyric network round trip and a
     * fixed wait - so on screen a track that is already audible keeps showing as paused with the
     * progress bar stuck at zero. That mismatch is exactly what this method removes.</p>
     *
     * <p>Where code needs to know whether the engine is sounding right now, ask {@link #isPlaying()}
     * rather than inferring it from this method.</p>
     */
    public boolean isPausing() {
        return !isUsable() || !this.playRequested;
    }

    /**
     * Whether the engine thread is sounding right now. <b>Different from {@link #isPausing()}</b>:
     * this is the engine's real state and stays false for a buffer after {@code play()} is queued.
     * The UI should ask {@link #isPausing()} to decide whether to draw the pause icon.
     */
    public boolean isPlaying() {
        return isUsable() && this.player.isPlaying();
    }

    public void setVolume(float volume) {
        this.volume = volume;
        applyAmp();
    }

    public void pause() {
        // Clear intent together with the engine. Intent first: otherwise, before the engine really
        // stops, the UI has a window of the opposite mismatch (pause pressed, play icon still shown),
        // which is the same quantity this change is about.
        this.playRequested = false;
        if (isUsable()) {
            try {
                this.player.pause();
            } catch (RuntimeException ignored) {
                // Ignore malformed decoder state.
            }
        }
    }

    public void unpause() {
        this.play();
    }

    /**
     * Returns whether the decoder exposed a non-empty, measurable sample.
     */
    public boolean isUsable() {
        if (closed || player == null)
            return false;
        return isUsable(player);
    }

    private static SoundFile loadUsableSoundFile(File file) {
        if (file == null)
            throw new IllegalArgumentException("Audio file is null");

        // Decoded PCM caches from an older build (or an unusually long stream) can exceed what the
        // player may hold in RAM. Reject them up front and treat a failed allocation as a track
        // failure rather than letting an OutOfMemoryError escape into the game loop.
        long pcmLimit = PlaybackMemoryLimits.maxDecodedPcmBytes();
        if (file.isFile() && file.length() > pcmLimit) {
            throw new IllegalStateException(PlaybackMemoryLimits.describeOverLimit(file.length()));
        }

        SoundFile candidate;
        try {
            candidate = new SoundFile(file.getAbsolutePath());
        } catch (OutOfMemoryError error) {
            throw new IllegalStateException("音频过长，内存不足以载入：" + file.getName());
        } catch (LinkageError error) {
            // 解码器（jflac / jsyn）随 mod 一起打包，且按需加载：FLAC 只有在带标签时才会第一次用到
            // VorbisComment/VorbisString。若运行期间重新编译或替换了 mod 文件，这些还没加载过的类
            // 就再也读不到字节码，抛 NoClassDefFoundError。第三方源的无损文件普遍带标签，所以往往
            // 是它先撞上，看起来像"只有第三方源不能播放"。这与歌曲本身无关，提示重启比抛类名有用。
            throw new IllegalStateException("音频解码器加载失败，请重启游戏后重试："
                    + error.getClass().getSimpleName());
        }
        if (isUsable(candidate))
            return candidate;

        dispose(candidate);
        throw new IllegalStateException("Audio file contains no usable frames: " + file.getName());
    }

    private static boolean isUsable(SoundFile soundFile) {
        if (soundFile == null)
            return false;

        try {
            int frames = soundFile.frames();
            int sampleRate = soundFile.sampleRate();
            float duration = soundFile.duration();
            return frames > 0 && sampleRate > 0
                    && !Float.isNaN(duration)
                    && !Float.isInfinite(duration)
                    && duration > 0.0f;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void dispose(SoundFile soundFile) {
        if (soundFile == null)
            return;

        // Deliberately no jump(0) here. AudioSample.jump() clears the data queue and then *starts*
        // playback: it adds the reader back into the running engine and queues a fresh data command,
        // only for the stop() below to tear it down again. Harmless when a track ends on its own,
        // but the outgoing deck of a crossfade is released while the incoming track is audible, so
        // that pointless voice lands exactly on the seam.
        try {
            soundFile.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            soundFile.cleanUp();
        } catch (RuntimeException ignored) {
        }
    }
}
