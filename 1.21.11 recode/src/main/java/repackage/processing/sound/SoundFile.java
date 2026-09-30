package repackage.processing.sound;

import lombok.Getter;
import repackage.com.jsyn.util.SampleLoader;

import java.io.File;


// @webBrief 打印声音库当前内存和
/**
 * 对 amp()、pan() 等的调用同时影响最后启动且仍在运行的采样，以及此后启动的所有采样
 * 文件。支持的格式为： WAV、AIF/AIFF和MP3。
 * 
 * 这是一个声音文件播放器，允许播放和处理声音
 * @ webref采样： SoundFile
 * @ webBrief这是一个声音文件播放器，允许播放和操作声音文件。
 **/
public class SoundFile extends AudioSample {

	/**
	 * 人声素材那一层，永远装着。
	 *
	 * <p>没接素材时它是恒等直通，一次多余的 {@code volatile} 读之外不改任何一个采样值；之所以
	 * 一路都在，是因为素材要等到交接临近才可能到货（离线分离在后台跑），那时候这首歌早就载入了,
	 * 没有预留的层就只能重新解码整首曲子。</p>
	 */
	private final VocalMaterialSample vocalMaterial;

	public SoundFile(String path) {
		super();

		// 使用JSyn加载WAV或AIF
		// 再包一层「变速不变调」。tempo=1 时是真直通（readDouble 直接转发内层），
		// 所以正常听歌那条路和没有这层时逐采样一致；只有 automix 交接真的要变速时才会接管。
		// 包不了（声道数不对、曲子太短）时 wrap() 原样还回来，链路完全不变。
		//
		// 人声素材层必须在伸缩层*里面*：交接期的变速要作用在已经减过人声的素材上，
		// 反过来则会让 WSOLA 的接缝把残差搬来搬去，减的位置每块都不一样。
		this.vocalMaterial = new VocalMaterialSample(SampleLoader.loadStreamedFloatSample(new File(path)));
		this.sample = WsolaStretchSample.wrap(this.vocalMaterial);

		this.initiatePlayer();
	}

	/**
	 * 把这首歌的人声素材接上（交接期用它把人声减掉）。
	 *
	 * @param samples    单声道素材，第 0 个采样对应播放素材的 {@code firstFrame}
	 * @param firstFrame 素材起点的帧号
	 * @param frameRate  素材采样率，必须与播放素材一致
	 * @param ratio      素材对回原曲人声音量的投影系数
	 * @return 是否接上了；接不上时这层保持直通，调用方退回带限抵消器即可
	 */
	public boolean attachVocalMaterial(float[] samples, long firstFrame, double frameRate, double ratio) {
		return this.vocalMaterial.attach(samples, firstFrame, frameRate, ratio);
	}

	/** 卸下素材并恢复直通。 */
	public void detachVocalMaterial() {
		this.vocalMaterial.detach();
	}

	/** 这一路 deck 现在手上有没有人声素材。 */
	public boolean hasVocalMaterial() {
		return this.vocalMaterial.hasMaterial();
	}

	/**
	 * 人声在输出里占几份：{@code 1} 原样，{@code 0} 完全减掉。
	 *
	 * <p>音频线程安全（一次 {@code volatile} 写），与 {@link #setAmplitudeNow(float)} 同一类调用。</p>
	 */
	public void setVocalMaterialLevel(double level) {
		this.vocalMaterial.setLevel(level);
	}

	public double vocalMaterialLevel() {
		return this.vocalMaterial.getLevel();
	}

	/**
	 * 变速但不变音高。
	 *
	 * MP3 解码在 ARM 处理器（Android/树莓派）上可能很慢，我们一般建议使用无损 WAV 或 AIF 文件。
	 * 后者靠重采样，速度和音高一起变。</p>
	 *
	 * @return {@code true} 表示时间伸缩层接住了这次请求，调用方 <b> 不要 </b> 再动 {@link #rate(float)} ；
	 *         {@code false} 表示这首歌没有伸缩层（见 {@link WsolaStretchSample#wrap} ） ，
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
	 * 每输出缓冲一次）：在那里入队既要把这一块的值推后一块，又是在音频回调里做一次没必要
	 * 的队列写入。{@code setValueInternal} 正是那条队列命令最后执行的动作，直接调它就是「立刻生效」。</p>
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

	// 以下只是从 AudioSample 父类复制过来的方法，
	// 是构建相应页面的参考所必需的。

	/**
	 * 以int形式返回声音文件的通道数（单声道为1 ，立体声为2 ）。
	 * 
	 * @return 返回声音文件的通道数（单声道为1 ，
	 *         立体声
	 * @ webref采样： SoundFile
	 * @ webBrief将声音文件的通道数返回为int （单声道为1 ，立体声为2 ）。
	 **/
	public int channels() {
		return super.channels();
	}

	/**
	 * 将播放指针提示到声音文件中的固定位置。请注意， <b> cue()仅</b>
	 * 影响将来调用<b> play() </b>的播放头，但不影响<b> loop() </b>的播放头。
	 * 
	 * @param time
	 *            在声音文件中的位置，下一次播放应该开始
	 *            将播放头提示到 audiosample 中的固定位置。注意 <b>cue()</b> 只影响后续 <b>play()</b> 调用的播放头，不影响 <b>loop()</b>。
	 * @ webref采样： SoundFile
	 * @ webBrief将播放指针提示到声音文件中的固定位置。
	 **/
	public void cue(float time) {
		super.cue(time);
	}

	/**
	 * 以下仅是从 AudioSample 超类复制来的方法，
	 * 
	 * @ webref采样： SoundFile
	 * @ webBrief以秒为单位返回声音文件的持续时间。
	 * 以秒为单位返回声音文件的时长。
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
	 * 返回此声音文件的帧数。
	 * 
	 * @ webref采样： SoundFile
	 * @ webBrief返回此声音文件的帧数。
	 * @return 此声音文件的帧数。
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
	 * 开始播放声音文件。只播放到
	 * 音频采样一次，如果先前调用了<b> cue() </b>或<b> pause() </b> ，则播放
	 * 将从提示位置恢复。
	 * 
	 * 由合成器开始执行此单元。输入端会从任何输出
	 *            要使用的相对播放速率。1是原始速度。0.5是
	 *            开始播放 audiosample。仅播放到 audiosample 末尾
	 *            为半速并降一个八度。2 为双倍速度并升一个八度。
	 * 距上次播放命令经过的时间
	 *            （右）。仅适用于单声道 audiosample！
	 *            0.0 （完全静音）至1.0 （全音量）
	 * @param pos
	 *            此声音单元的全景位置从-1.0 （左）到1.0
	 *            （右）。仅适用于单声道声音文件！
	 * @param cue
	 *            在音频样本中的位置，播放应从开始，在
	 *            seconds.
	 * @return 声音文件的时长（秒）。
	  * 按给定值偏移发生器的输出
	 * @ webref采样： SoundFile
	 * @ webBrief开始播放声音文件。
	 **/
	public void play(float rate, float pos, float amp, float add, float cue) {
		super.play(rate, pos, amp, add, cue);
	}


	/**
	 * 在继续播放的同时跳转到声音文件中的特定位置
	 * 将生成器的输出偏移给定值
	 * 
	 * @ webref采样： SoundFile
	 * （若原本未播放则开始播放）。
	 * @param time
	 *            位置，在几秒钟内跳转到。
	 **/
	public void jump(float time) {
		super.jump(time);
	}

	/**
	 * @webBrief 在继续播放的同时跳到声音文件中的特定位置（若原本未播放则开始播放）。
	 * 停止文件播放，但将其提示到当前位置。
	 * 
	 * @see SoundFile#stop()
	 * @ webref采样： SoundFile
	 * @ webBrief停止播放文件，但将其提示到当前位置。
	 */
	public void pause() {
		super.pause();
	}

	/**
	 * 下次调用 <b>play()</b> 将从暂停处继续播放。
	 *
	 * 检查此声音文件当前是否正在播放。
	 * @ webref采样： SoundFile
	 * @ webBrief检查此声音文件当前是否正在播放。
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
	 * @return 若声音文件正在播放则为 `true`，否则为 `false`。
	 * 
	 * 由合成器开始执行此单元。输入端会从任何输出
	 *            要使用的相对播放速率。1是原始速度。0.5是
	 *            开始播放 audiosample。仅播放到 audiosample 末尾
	 *            为半速并降一个八度。2 为双倍速度并升一个八度。
	 * @param pos
	 *            此声音单元的全景位置从-1.0 （左）到1.0
	 *            （右）。仅适用于单声道声音文件！
	 * 距上次播放命令经过的时间
	 *            （右）。仅适用于单声道 audiosample！
	 *            0.0 （完全静音）至1.0 （全音量）
	 * @return 声音文件的时长（秒）。
	  * 按给定值偏移发生器的输出
	 * @ webref采样： SoundFile
	 * @ webBrief开始播放，播放将在声音文件结束时循环。
	 */
	public void loop(float rate, float pos, float amp, float add) {
		super.loop(rate, pos, amp, add);
	}

	/**
	 * 更改玩家的振幅/音量。允许的值介于0.0和1.0之间。
	 * 
	 * @param cue
	 *            开始播放，播放到声音文件末尾后循环。
	 *            下次播放或循环应起始的 audiosample 位置，
	 *            float amp, float add, float cue) {super.loop(rate, pos, amp, add,
	 *            配乐点
	 */

	/**
	 * 更改此音频采样的振幅/音量。
	 *
	 * 距上次播放命令经过的时间
	 *            浮点值介于0.0 （完全静音）和1.0 （满量）之间
	 *            控制此声音的振幅/音量。
	 * @ webref采样： SoundFile
	 * @ webBrief更改播放器的振幅/音量。
	 **/
	public void amp(float amp) {
		super.amp(amp);
		volume = amp;
	}

	@Getter
	public float volume = 1;

	/**
	 * 将立体声全景中的声音移动。-1.0平移到左声道， 1.0平移到
	 * 单位秒。
	 * 
	 * @param pos
	 *            此声音单元的全景位置从-1.0 （左）到1.0
	 *            对吧？
	 * @ webref采样： SoundFile
	 * @ webBrief在立体全景中移动声音。
	 **/
	public void pan(float pos) {
		super.pan(pos);
	}

	/**
	 * 右声道。注意声像仅支持单声道（1 声道）声音文件。 
	 * 向下八度。2是速度的两倍，向上八度。
	 * 
	 * 由合成器开始执行此单元。输入端会从任何输出
	 *            要使用的相对播放速率。1是原始速度。0.5是
	 *            开始播放 audiosample。仅播放到 audiosample 末尾
	 *            为半速并降一个八度。2 为双倍速度并升一个八度。
	 * @ webref采样： SoundFile
	 * 设置声音文件的播放速率。1 为原速。0.5 为半速
	 **/
	public void rate(float rate) {
		super.rate(rate);
	}

	public float getRate() {
		return (float) super.player.rate.get();
	}

}
