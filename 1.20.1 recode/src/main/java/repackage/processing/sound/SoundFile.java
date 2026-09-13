package repackage.processing.sound;

import lombok.Getter;
import repackage.com.jsyn.util.SampleLoader;

import java.io.File;


// calls to amp(), pan() etc affect both the LAST initiated and still running sample, AND all subsequently started ones
/**
 * This is a Soundfile player which allows to play back and manipulate sound
 * files. Supported formats are: WAV, AIF/AIFF, and MP3.
 * 
 * MP3 decoding can be very slow on ARM processors (Android/Raspberry Pi), we generally recommend you use lossless WAV or AIF files.
 * @webref Sampling:SoundFile
 * @webBrief This is a Soundfile Player which allows to play back and manipulate soundfiles.
 **/
public class SoundFile extends AudioSample {

	public SoundFile(String path) {
		super();

		// load WAV or AIF using JSyn
		// 再包一层「变速不变调」。tempo=1 时是真直通（readDouble 直接转发内层），
		// 所以正常听歌那条路和没有这层时逐采样一致；只有 automix 交接真的要变速时才会接管。
		// 包不了（声道数不对、曲子太短）时 wrap() 原样还回来，链路完全不变。
		this.sample = WsolaStretchSample.wrap(SampleLoader.loadStreamedFloatSample(new File(path)));

		this.initiatePlayer();
	}

	/**
	 * 变速但不变音高。
	 *
	 * <p>{@code tempo} 是 SoundTouch 语义：{@code > 1} 更快更短。和 {@link #rate(float)} 的区别是
	 * 后者靠重采样，速度和音高一起变。</p>
	 *
	 * @return {@code true} 表示时间伸缩层接住了这次请求，调用方<b>不要</b>再动 {@link #rate(float)}；
	 *         {@code false} 表示这首歌没有伸缩层（见 {@link WsolaStretchSample#wrap}），
	 *         调用方需要退回重采样
	 */
	public boolean timeStretch(float tempo) {
		return this.sample instanceof WsolaStretchSample
				&& ((WsolaStretchSample) this.sample).setTempo(tempo);
	}

	/** 有没有时间伸缩层可用。 */
	public boolean canTimeStretch() {
		return this.sample instanceof WsolaStretchSample;
	}

	/** 时间伸缩层当前是否真的在跑（而不是直通）。 */
	public boolean isTimeStretching() {
		return this.sample instanceof WsolaStretchSample
				&& ((WsolaStretchSample) this.sample).isEngaged();
	}

	/**
	 * 定位之后把伸缩层的输出时间轴重新对齐到源。
	 *
	 * <p>不调的话，跳转后 WSOLA 会从上一次停下的源位置继续长，而读头的下标已经跳到别处，
	 * 于是听到的是跳转前那一段。</p>
	 */
	public void resyncTimeStretch() {
		if (this.sample instanceof WsolaStretchSample) {
			((WsolaStretchSample) this.sample).resync();
		}
	}

	/**
	 * 音频线程版 {@link #amp(float)}：直接写端口，不往 JSyn 的命令队列里排。
	 *
	 * <p>普通的 {@code UnitInputPort.set()} 除了写值还会 {@code queueCommand}，而命令要到
	 * <b>下一个</b>块由音频线程 {@code processScheduledCommands} 抽干才真正生效。
	 * automix 的曲线执行本身就跑在音频线程上（{@code SynthesisEngine.runAudioTasks()}，
	 * 每输出缓冲一次）：在那里入队既要把这一块的值推后一块，又是在音频回调里做一次没必要的
	 * 队列写入。{@code setValueInternal} 正是那条队列命令最后执行的动作，直接调它就是「立刻生效」。</p>
	 *
	 * <p>{@code amp} 与 {@code volume} 两个字段一并跟上：{@code SoundObject.play()} 会用
	 * {@code amp} 字段重新覆盖端口，只写端口的值会在下次起播时被丢掉（交叉淡化里进场 deck
	 * 是<b>故意</b>从 0 起播的，丢了就是一声突入）。</p>
	 */
	public void setAmplitudeNow(float amp) {
		if (Float.isNaN(amp) || Float.isInfinite(amp)) return;
		this.amp = amp;
		this.volume = amp;
		this.player.amplitude.setValueInternal(amp);
	}

	/**
	 * 音频线程版 {@link #rate(float)}。参数约束与它一致：{@code rate} 必须为正。
	 *
	 * <p>只写读头的步进端口，不碰时间伸缩层 —— 那是 {@link #setRateAndStretchNow} 的事。</p>
	 */
	public void setRateNow(float rate) {
		if (!(rate > 0f) || Float.isInfinite(rate)) return;
		this.player.rate.setValueInternal(this.sampleRate() * rate);
	}

	/**
	 * 音频线程版的「读头速率 + 时间伸缩」一次设定，等价于 {@link #setRateNow(float)} 接着
	 * {@link #timeStretch(float)}。
	 *
	 * <p>两条在音频线程上都是安全的：前者直写端口，后者只写一个 volatile 因子、下一个块生效
	 * （见 {@link WsolaTimeStretch#setTempo(double)}）。合成一个方法是因为<b>顺序有意义</b>：
	 * 读头必须先落到音高倍率上，否则某一帧里会出现「伸缩已经按解耦后的比值跑、读头还停在上一次
	 * 的 pitch」这种半拍不一致（与 {@code AudioPlayer.setSpeedAndPitch} 同一理由）。</p>
	 *
	 * @return 与 {@link #timeStretch(float)} 同义：时间伸缩层是否接住了这次请求
	 */
	public boolean setRateAndStretchNow(float rate, float stretch) {
		setRateNow(rate);
		return timeStretch(stretch);
	}

	// Below are just duplicated methods from the AudioSample superclass which
	// are required for the reference to build the corresponding pages.

	/**
	 * Returns the number of channels of the soundfile as an int (1 for mono, 2 for stereo).
	 * 
	 * @return Returns the number of channels of the soundfile (1 for mono, 2 for
	 *         stereo)
	 * @webref Sampling:SoundFile
	 * @webBrief Returns the number of channels of the soundfile as an int (1 for mono, 2 for stereo).
	 **/
	public int channels() {
		return super.channels();
	}

	/**
	 * Cues the playhead to a fixed position in the soundfile. Note that <b>cue()</b> only 
	 * affects the playhead for future calls to <b>play()</b>, but not to <b>loop()</b>.
	 * 
	 * @param time
	 *            position in the soundfile that the next playback should start
	 *            from, in seconds.
	 * @webref Sampling:SoundFile
	 * @webBrief Cues the playhead to a fixed position in the soundfile.
	 **/
	public void cue(float time) {
		super.cue(time);
	}

	/**
	 * Returns the duration of the soundfile in seconds.
	 * 
	 * @webref Sampling:SoundFile
	 * @webBrief Returns the duration of the soundfile in seconds.
	 * @return The duration of the soundfile in seconds.
	 **/
	public float duration() {
		return super.duration();
	}

	public int getTotalTimeSeconds() {
		return (int) this.duration();
	}

	public int getCurrentTimeSeconds() {
		return (int) (getCurrentTimeMillis() / 1000);
	}

	public int getTotalTimeMillis() {
		return getTotalTimeSeconds() * 1000;
	}

	public float getCurrentTimeMillis() {
		return this.position() * 1000;
	}

	/**
	 * Returns the number of frames of this soundfile.
	 * 
	 * @webref Sampling:SoundFile
	 * @webBrief Returns the number of frames of this soundfile.
	 * @return The number of frames of this soundfile.
	 **/
	public int frames() {
		return super.frames();
	}

	public void play() {
		super.play();
	}

	public void play(float rate) {
		super.play(rate);
	}

	public void play(float rate, float amp) {
		super.play(rate, amp);
	}

	public void play(float rate, float pos, float amp) {
		super.play(rate, pos, amp);
	}

	public void play(float rate, float pos, float amp, float add) {
		super.play(rate, pos, amp, add);
	}

	private boolean muted = false;

	public boolean isMuted() {
		return muted;
	}

	public void setMuted(boolean muted) {
		this.muted = muted;

		if (muted) {
			float volume1 = this.volume;
			this.amp(0.001f);
			this.volume = volume1;
		} else {
			this.amp(volume);
		}
	}

	/**
	 * Starts the playback of the soundfile. Only plays to the end of the
	 * audiosample once. If <b>cue()</b> or <b>pause()</b> were called previously, playback 
	 * will resume from the cued position.
	 * 
	 * @param rate
	 *            relative playback rate to use. 1 is the original speed. 0.5 is
	 *            half speed and one octave down. 2 is double the speed and one
	 *            octave up.
	 * @param amp
	 *            the desired playback amplitude of the audiosample as a value from
	 *            0.0 (complete silence) to 1.0 (full volume)
	 * @param pos
	 *            the panoramic position of this sound unit from -1.0 (left) to 1.0
	 *            (right). Only works for mono soundfiles!
	 * @param cue
	 *            position in the audiosample that playback should start from, in
	 *            seconds.
	 * @param add
	 *            offset the output of the generator by the given value
	 * @webref Sampling:SoundFile
	 * @webBrief Starts the playback of the soundfile.
	 **/
	public void play(float rate, float pos, float amp, float add, float cue) {
		super.play(rate, pos, amp, add, cue);
	}


	/**
	 * Jump to a specific position in the soundfile while continuing to play 
	 * (or starting to play if it wasn't playing already).
	 * 
	 * @webref Sampling:SoundFile
	 * @webBrief Jump to a specific position in the soundfile while continuing to play (or starting to play if it wasn't playing already).
	 * @param time
	 *            position to jump to, in seconds.
	 **/
	public void jump(float time) {
		super.jump(time);
	}

	/**
	 * Stop the playback of the file, but cue it to the current position. The
	 * next call to <b>play()</b> will continue playing where it left off.
	 * 
	 * @see SoundFile#stop()
	 * @webref Sampling:SoundFile
	 * @webBrief Stop the playback of the file, but cue it to the current position.
	 */
	public void pause() {
		super.pause();
	}

	/**
	 * Check whether this soundfile is currently playing.
	 *
	 * @return `true` if the soundfile is currently playing, `false` if it is not.
	 * @webref Sampling:SoundFile
	 * @webBrief Check whether this soundfile is currently playing.
	 */
	public boolean isPlaying() {
		return super.isPlaying();
	}

	public void loop() {
		super.loop();
	}

	public void loop(float rate) {
		super.loop(rate);
	}

	public void loop(float rate, float amp) {
		super.loop(rate, amp);
	}

	public void loop(float rate, float pos, float amp) {
		super.loop(rate, pos, amp);
	}

	/**
	 * Starts playback which will loop at the end of the soundfile.
	 * 
	 * @param rate
	 *            relative playback rate to use. 1 is the original speed. 0.5 is
	 *            half speed and one octave down. 2 is double the speed and one
	 *            octave up.
	 * @param pos
	 *            the panoramic position of this sound unit from -1.0 (left) to 1.0
	 *            (right). Only works for mono soundfiles!
	 * @param amp
	 *            the desired playback amplitude of the audiosample as a value from
	 *            0.0 (complete silence) to 1.0 (full volume)
	 * @param add
	 *            offset the output of the generator by the given value
	 * @webref Sampling:SoundFile
	 * @webBrief Starts playback which will loop at the end of the soundfile.
	 */
	public void loop(float rate, float pos, float amp, float add) {
		super.loop(rate, pos, amp, add);
	}

	/**
	 * Changes the amplitude/volume of the player. Allowed values are between 0.0 and 1.0.
	 * 
	 * @param cue
	 *            position in the audiosample that the next playback or loop should
	 *            start from, in seconds. public void loop(float rate, float pos,
	 *            float amp, float add, float cue) { super.loop(rate, pos, amp, add,
	 *            cue); }
	 */

	/**
	 * Change the amplitude/volume of this audiosample.
	 *
	 * @param amp
	 *            A float value between 0.0 (complete silence) and 1.0 (full volume)
	 *            controlling the amplitude/volume of this sound.
	 * @webref Sampling:SoundFile
	 * @webBrief Changes the amplitude/volume of the player.
	 **/
	public void amp(float amp) {
		super.amp(amp);
		volume = amp;
	}

	@Getter
	public float volume = 1;

	/**
	 * Move the sound in a stereo panorama.-1.0 pans to the left channel and 1.0 to the 
	 * right channel. Note that panning is only supported for mono (1 channel) soundfiles.
	 * 
	 * @param pos
	 *            the panoramic position of this sound unit from -1.0 (left) to 1.0
	 *            (right).
	 * @webref Sampling:SoundFile
	 * @webBrief Move the sound in a stereo panorama.
	 **/
	public void pan(float pos) {
		super.pan(pos);
	}

	/**
	 * Set the playback rate of the soundfile. 1 is the original speed. 0.5 is half speed 
	 * and one octave down. 2 is double the speed and one octave up.
	 * 
	 * @param rate
	 *            Relative playback rate to use. 1 is the original speed. 0.5 is
	 *            half speed and one octave down. 2 is double the speed and one
	 *            octave up.
	 * @webref Sampling:SoundFile
	 * @webBrief Set the playback rate of the soundfile.
	 **/
	public void rate(float rate) {
		super.rate(rate);
	}

	public float getRate() {
		return (float) super.player.rate.get();
	}

}
