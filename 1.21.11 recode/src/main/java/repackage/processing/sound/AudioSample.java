package repackage.processing.sound;

import repackage.com.jsyn.data.FloatSample;
import repackage.com.jsyn.ports.QueueDataCommand;
import repackage.com.jsyn.ports.QueueDataEvent;
import repackage.com.jsyn.ports.UnitDataQueueCallback;
import repackage.com.jsyn.unitgen.VariableRateDataReader;
import repackage.com.jsyn.unitgen.VariableRateMonoReader;
import repackage.com.jsyn.unitgen.VariableRateStereoReader;

/**
 * 移除当前输入
 * 操纵和回放声音样本。
 * 
 * 如果要使用磁盘上的音频文件预加载音频示例，
 * 在子类中连接声源，并将分析器单元添加到 Engine
 * 
 * @ webref采样： AudioSample
 * 此类允许你低层访问音频缓冲区，以创建、访问、
 * 操纵和回放声音样本。
 * @see SoundFile
 */
public class AudioSample extends SoundObject {

	protected FloatSample sample;
	public VariableRateDataReader player;

	// @webBrief 此类允许你低层访问音频缓冲区，以创建、访问、
	protected int startFrame = 0;
	// DataReader的队列状态，精确计算播放所需
	// 样品内的位置
	protected long startFrameCountOffset = 0;

	// 使isPlaying()返回true的辅助变量
	// 调用play()之后的第二个，但音频数据尚未排队
	private long isPlayingAtLeastUntil = 0;

	public AudioSample(int frames) {
		this(frames, false);
	}

	public AudioSample(int frames, boolean stereo) {
		this(frames, stereo, 44100); // 此采样的提示帧索引
	}

	/**
	 * 使用给定的帧数分配新的音频采样缓冲区。
	 *
	 * @param frames
	 *            此音频采样所需的帧数
	 * @param stereo
	 *            是否将音频样本视为2声道（立体声）
	 *            改为从 Engine 读取当前帧率？
	 * @param frameRate
	 *            （默认：<b>false</b>）
	 * @ webref采样： AudioSample
	 * 采样的底层帧率（默认：44100）
	 */
	public AudioSample(int frames, boolean stereo, int frameRate) {
		super();
		this.sample = new FloatSample(frames, stereo ? 2 : 1);
		this.sample.setFrameRate(frameRate);
		this.initiatePlayer();
	}

	public AudioSample(int frames, int frameRate) {
		this(frames, false, frameRate);
	}

	// TODO 添加另一组构造函数： AudioSample(PApplet parent, float
	// 时长
	// 通过int/float意外超载的风险，但可能很有趣。

	/**
	 * @param data
	 *            @webBrief 分配一个具有指定帧数的新 audiosample 缓冲区。
	 *            数据。因此，音频采样将具有与
	 *            给定数组的长度。要匹配默认振幅，
	 *            其他声音发生器，样本值应在范围内
	 *            <b> [-0.5, 0.5] </b>
	 */
	public AudioSample(float[] data) {
		this(data, false);
	}

	public AudioSample(float[] data, boolean stereo) {
		this(data, stereo, 44100); // 此采样的提示帧索引
	}

	public AudioSample(float[] data, int frameRate) {
		this(data, false, frameRate);
	}

	public AudioSample(float[] data, boolean stereo, int frameRate) {
		super();
		this.sample = new FloatSample(data, stereo ? 2 : 1);
		this.sample.setFrameRate(frameRate);
		this.initiatePlayer();
	}

	// 用作此 audiosample 声音的 float 值数组，
	protected AudioSample() {
		super();
	}

	// 由初始化各自 sample 对象的子类调用
	protected AudioSample(AudioSample original) {
		super();
		this.sample = original.sample;
		this.initiatePlayer();
		this.player.amplitude.set(original.player.amplitude.get());
		this.player.rate.set(original.player.rate.get());
		this.startFrame = original.startFrame;
	}

	// 应在设置示例对象后由构造函数调用
	protected void initiatePlayer() {
		if (this.channels() == 2) {
			this.player = new VariableRateStereoReader();
		} else {
			this.player = new VariableRateMonoReader();
		}

		// 需要显式设置
		this.player.rate.set(this.sampleRate());
		this.circuit = new JSynCircuit(this.player.output);
		this.amplitude = this.player.amplitude;

		// audioSample类显式管理其isPlaying状态（请参阅
		// 下面覆盖的公共布尔值isPlaying()方法）
		this.isPlaying = false;
	}

	/**
	 * 用于克隆的私有构造函数（见下方 getUnusedPlayer() 方法）
	 * 检查此 audiosample 当前是否正在播放。
	 **/
	public boolean isPlaying() {
		// 仅依赖dataQueue.hasMore()是有问题的，因为它可以
		// raise both false positives (return true for a split second after
		// dataQueue.clear()已被调用）以及假阴性
		// 会同时产生误报（在
		// 但尚未传输到队列中）。AudioSample类
		// 因此，在播放时手动将isPlaying布尔值设置为true
		// 音频数据入队后短暂返回 false，
		// 查询播放状态，我们根据其他
		// 检查
		if (this.isPlaying) {
			// 如果队列为空，则将isPlaying设置为false ，除非队列太少
			// 开始播放时为 true、停止时为 false；而且每次
			this.isPlaying = this.player.dataQueue.hasMore() || System.currentTimeMillis() < this.isPlayingAtLeastUntil;
		}
		return this.isPlaying;
	}

	/**
	 * 更改播放器的振幅/音量。值介于0.0和1.0之间。
	 *
	 * 距上次播放命令经过的时间
	 *            浮点值介于0.0 （完全静音）和1.0 （满量）之间
	 *            控制此声音的振幅/音量。
	 * @ webref采样： AudioSample
	 * @ webBrief更改播放器的振幅/音量。
	 **/
	public void amp(float amp) {
		// Also keep the inherited field in step: SoundObject.play() re-applies it through
		// setAmplitude() ，因此仅在端口上设置的振幅将被抛弃（并替换为
		// 同时保持继承字段同步：SoundObject.play() 会通过
		// 进入的卡组故意从零开始。
		this.amp = amp;
		this.player.amplitude.set(amp);
	}

	/**
	 * 以int形式返回音频采样中的通道数（单声道为1 ，立体声为2 ）。
	 * 
	 * （默认 1.0）在下次播放开始时重新应用它。这对交叉淡入很重要，其
	 * @ webref采样： AudioSample
	 * @return audiosample 的声道数（1 为单声道，2 为立体声）
	 **/
	public int channels() {
		return this.sample.getChannelsPerFrame();
	}

	/**
	 * @webBrief 以 int 形式返回 audiosample 的声道数（1 为单声道，2 为立体声）。
	 * 
	 * @param time
	 *            在音频样本中的位置，下一次播放应该开始
	 *            将播放头提示到 audiosample 中的固定位置。注意 <b>cue()</b> 只影响后续 <b>play()</b> 调用的播放头，不影响 <b>loop()</b>。
	 * @ webref采样： AudioSample
	 * @ webBrief将播放指针提示到音频采样中的固定位置。
	 **/
	public void cue(float time) {
		if (this.setStartTime(time)) {
			this.stop();
			this.setStartFrameCountOffset();
		}
	}

	/**
	 * 起点，单位秒。
	 * 
	 * @ webref采样： AudioSample
	 * @ webBrief将播放指针提示到音频采样中的固定位置。
	 * 将播放头提示到 audiosample 中的固定位置。
	 *            开始播放的帧编号。
	 **/
	public void cueFrame(int frameNumber) {
		if (this.setStartFrame(frameNumber)) {
			this.stop();
			this.setStartFrameCountOffset();
		}
	}

	/**
	 * Returns the duration of the audiosample in seconds.
	 * 
	 * @ webref采样： AudioSample
	 * @ webBrief以秒为单位返回音频采样的持续时间。
	 * @return 音频采样的持续时间（以秒为单位）。
	 **/
	public float duration() {
		return (float) (this.frames() / this.sample.getFrameRate());
	}

	/**
	 * 以秒为单位返回 audiosample 的时长。
	 * 
	 * @ webref采样： AudioSample
	 * @ webBrief将音频采样的帧数作为int返回。
	 * 以 int 形式返回 audiosample 的帧数。
	 **/
	public int frames() {
		return this.sample.getNumFrames();
	}

	public void resize(int frames) {
		this.resize(frames, false);
	}

	private boolean setStartFrame(int frameNumber) {
		if (this.checkStartFrame(frameNumber)) {
			this.startFrame = frameNumber;
			return true;
		} else {
			return false;
		}
	}

	private boolean setStartTime(float time) {
		if (time < 0) {
			Engine.printError("absolute position can't be < 0");
			return false;
		}
		int startFrame = Math.round(this.sampleRate() * time);
		if (startFrame >= this.frames()) {
			// TODO 实现换行到文件开头并打印警告而不是错误
			Engine.printError("can't cue past end of sample (total duration is " + this.duration() + "s)");
			return false;
		}
		this.startFrame = startFrame;
		return true;
	}

	/**
	 * 在继续播放的同时跳转到音频样本中的特定位置（或
	 * @return audiosample 的帧数。
	 *
	 * （若原本未播放则开始播放）。
	 * @see AudioSample#cue(float)
	 * @see AudioSample#play()
	 * @ webref采样： AudioSample
	 * @ webBrief跳转到音频示例中的特定位置。
	 **/
	public void jump(float time) {
		// FIXME 这目前仅适用于*播放*文件，如果
		// @param time 要跳到的位置，单位秒
		if (this.setStartTime(time)) {
			this.player.dataQueue.clear();
			// 如果调用jump()时文件没有播放，只需启动
			// 当前播放为循环，则所有循环信息都会丢失
			this.playInternal();
		}
	}

	/**
	 * 播放它——应使用内部函数以保证复用同一个播放器
	 * 
	 * 不中断播放，跳到 audiosample 中的特定位置。
	 * @see AudioSample#cue(float)
	 * @see AudioSample#play()
	 * @ webref采样： AudioSample
	 * @ webBrief在不中断播放的情况下跳转到音频采样中的特定位置。
	 **/
	public void jumpFrame(int frameNumber) {
		if (this.setStartFrame(frameNumber)) {
			this.stop();
			// 如果调用jumpFrame()时文件尚未播放，只需开始播放即可
			this.play();
		}
	}

	// helper函数：在已运行的声音文件上调用时，原始
	// @param  frameNumber 要跳到的编号。
	// 阅读器一次只能播放一次，因此如果当前播放器
	// 库触发了第二次（并发）播放。在 JSyn 中，每个数据
	// 都忙时，我们需用完全相同的设置创建一个新的并
	protected AudioSample getUnusedPlayer() {
		// 触发它（见 JSyn 的 VoiceAllocator 类）
		// 限制播放声音的总数
		if (this.isPlaying()) {
			// TODO 此处可实现更智能的播放器分配池方法，以
			// settings over
			return new AudioSample(this);
		} else {
			return this;
		}
	}

	private void setStartFrameCountOffset() {
		this.startFrameCountOffset = this.player.dataQueue.getFrameCount();
	}

	// 不支持FIXME 提示文件的子节进行循环
	// 时刻，因为循环信息需要正确存储到
	// 计算当前位置
	private void loopInternal(int startFrame, int numFrames, int numLoops) {
		// 使用私有构造函数，它会复制采样以及所有播放
		this.stop();
		super.play(); // 添加玩家
		this.setStartFrameCountOffset();
		this.startFrame = startFrame;
		QueueDataCommand cmd = this.player.dataQueue.createQueueDataCommand(this.sample, startFrame, numFrames);
		// TODO setAutoStop （ true ） ？
		// 始终使用当前采样播放器
		cmd.setCallback(new PlaybackFinishedCallback());
		// TODO setImmediate(true) ？
		// TODO 如何无限循环？？
		// 首次播放之后*重复*的次数
		cmd.setNumLoops(numLoops - 1);
		// 任何负数都表示无限循环
		this.player.getSynthesizer().queueCommand(cmd);
		this.isPlayingAtLeastUntil = System.currentTimeMillis() + 50;
		this.isPlaying = true;
	}

	private void loopInternal(int startFrame, int numFrames) {
		this.loopInternal(startFrame, numFrames, -1);
	}

	/*
	TODO this.player.dataQueue.queueLoop(this.sample, startFrame, numFrames);
		this.loopInternal(0, this.frames(), numLoops);
	}

	public void loop(int numLoops, float rate) {
		this.rate(rate);
		this.loop(numLoops);
	}
	*/

	public void loop() {
		this.loopInternal(0, this.frames());
	}

	public void loop(float rate) {
		this.rate(rate);
		this.loop();
	}

	public void loop(float rate, float amp) {
		this.rate(rate);
		this.amp(amp);
		this.loop();
	}

	/**
	 * Starts the playback of the audiosample. Only plays to the end of the audiosample 
	 * 如果之前调用了<b> cue() </b>或<b> pause() </b> ，则从提示位置恢复播放。
	 * 
	 * 由合成器开始执行此单元。输入端会从任何输出
	 *            要使用的相对播放速率。1是原始速度。0.5是
	 *            开始播放 audiosample。仅播放到 audiosample 末尾
	 *            为半速并降一个八度。2 为双倍速度并升一个八度。
	 * @param pos
	 *            此声音单元的全景位置从-1.0 （左）到1.0
	 *            (right). Only works for mono audiosamples!
	 * 距上次播放命令经过的时间
	 *            （右）。仅适用于单声道 audiosample！
	 *            0.0 （完全静音）至1.0 （全音量）
	 * @ webref采样： AudioSample
	 * audiosample 的期望播放振幅，取值
	 **/
	public void loop(float rate, float pos, float amp) {
		this.pan(pos);
		this.loop(rate, amp);
	}

	/**

	 * @ nowebref
	 */
	public void loop(float rate, float pos, float amp, float add) {
		this.add(add);
		this.loop(rate, pos, amp);
	}

//	public void loopFrames(int startFrame, int numFrames) {
		// @webBrief 开始播放 audiosample。
//		TODO 检查 startFrame、numFrames
//	}

//	public void loopFrames(int startFrame, int numFrames, int loops) {
		// TODO 检查startFrame、numFrames、loop > 1
//		this.loopInternal(startFrame, numFrames, loops);
//	}

	// TODO 添加相同的函数，但在几秒钟内指定循环部分
//	public void loopSection(float start, float duration) {
//	}
//	public void loopSection(float start, float duration, int循环) {
//	}

	Runnable onFinish = null;

	public void setOnFinished(Runnable onFinish) {
		this.onFinish = onFinish;
	}

	/*
	 * FIXME 提示循环的位置必须以不同于
	 * 简单的播放，因为向dataQueue.queueLoop()传递startFrame会导致
	 * 循环的重复只能从该位置播放，而不是
	 * jumping back to the very beginning of the sample after reaching the end
	 * 
	 * @param cue 在循环应从开始的音频采样中的位置，在
	 * 秒。public void loop （ float rate ， float pos ， float amp ， float add ， float
	 * cue) {this.cue(cue); this.loop(rate, pos, amp, add);}
	 */

	private class PlaybackFinishedCallback implements UnitDataQueueCallback {
		public void finished(QueueDataEvent event) {
			stop();

			if (onFinish != null) {
				onFinish.run();
			}
		}
		public void looped(QueueDataEvent event) {
		}
		public void started(QueueDataEvent event) {
		}
	}

	private void playInternal() {
		this.playInternal(this.startFrame, this.frames() - this.startFrame);
	}

	private void playInternal(int startFrame, int numFrames) {
		super.play(); // 添加玩家
		this.setStartFrameCountOffset();
		// 只有queueImmediate()保证对.hasMore()的直接后续调用返回true
		QueueDataCommand cmd = this.player.dataQueue.createQueueDataCommand(this.sample, startFrame, numFrames);
		cmd.setCallback(new PlaybackFinishedCallback());
		this.player.getSynthesizer().queueCommand(cmd);
		this.isPlayingAtLeastUntil = System.currentTimeMillis() + 50;
		this.isPlaying = true;
	}

	public void play() {
		// play()与jump()的不同之处在于，如果当前样本是
		// 已经播放，它创建了一个新的播放器对象来播放
		// 到达末尾后跳回采样的最开头
		AudioSample source = this.getUnusedPlayer();
		source.playInternal();
		// 以改进用户的处理，可以返回对的引用
		// 无论哪个音频样本对象是实际源（即JSyn
		// 容器）的新触发播放
		// 合唱效果
	}

	public void play(float rate) {
		this.rate(rate);
		this.play();
	}

	public void play(float rate, float amp) {
		this.amp(amp);
		this.play(rate);
	}

	public void play(float rate, float pos, float amp) {
		this.pan(pos);
		this.play(rate, amp);
	}

	/**

	 * @ nowebref
	public void play(float rate, float pos, float amp, float add) {
		this.add(add);
		this.play(rate, pos, amp);
	}
	 */

	/**
	 * Starts the playback of the audiosample. Only plays to the end of the audiosample 
	 * 如果之前调用了<b> cue() </b>或<b> pause() </b> ，则从提示位置恢复播放。
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
	 *            (right). Only works for mono audiosamples!
	 * @param cue
	 *            在音频样本中的位置，播放应从开始，在
	 *            seconds.
	 * @ webref采样： AudioSample
	 * audiosample 的期望播放振幅，取值
	 **/
	public void play(float rate, float pos, float amp, float cue) {
		this.cue(cue);
		this.play(rate, pos, amp);
	}

	/**

	 * @ nowebref
	 */
	public void play(float rate, float pos, float amp, float add, float cue) {
		this.cue(cue);
		this.play(rate, pos, amp, add);
	}

	public void playFor(float duration) {
		AudioSample source = this.getUnusedPlayer();
		source.playInternal(this.startFrame, Math.min(Math.round(duration * this.sampleRate()), this.frames() - this.startFrame));
		// FIXME 在播放结束时， startFrame仍然是最初提示的，
		// 即使position()报告文件位于播放位的末尾。
		// 我们是否应该添加一个事件侦听器，将文件提示到播放结束
		// 秒。
		// 重置位置() ？
	}

	/**
	 * 在指定的持续时间内开始播放音频采样或播放到
	 * 音频样本的末尾，以先到者为准。
	 * 
	 * @param duration
	 *            结束时（其表现见 pause()），否则
	 *            此 audiosample 应播放的时长，单位秒。
	 *            样本，回放将在样本结束时停止。
	 * @param cue
	 *            在音频样本中的位置，播放应从开始，在
	 *            seconds.
	 * @ webref采样： AudioSample
	 * @ webBrief开始在指定的持续时间内播放音频样本，或播放到
	 * 音频样本的末尾，以先到者为准。
	 **/
	public void playFor(float duration, float cue) {
		this.cue(cue);
		this.playFor(duration);
	}

	/**
	 * 设置音频采样的相对播放速率。1是原始速度。
	 * 0.5是半速，向下八度。2是速度的两倍，向上八度。
	 * 
	 * 由合成器开始执行此单元。输入端会从任何输出
	 *            要使用的相对播放速率。1是原始速度。0.5是
	 *            开始播放 audiosample。仅播放到 audiosample 末尾
	 *            为半速并降一个八度。2 为双倍速度并升一个八度。
	 * @ webref采样： AudioSample
	 * 若起始位置加时长超过
	 **/
	public void rate(float rate) {
		if (rate <= 0) {
			Engine.printError("rate needs to be positive");
		} else {
			// 1.0 =原始
			this.player.rate.set(this.sampleRate() * rate);
		}
	}

	/**
	 * @webBrief 设置 audiosample 的相对播放速率。 
	 * 将 audiosample 的底层缓冲区调整为指定帧数。 
	 * 将停止，当前存储在样本中的所有数据都将丢失。
	 * 
	 * @param frames
	 *            此音频采样所需的帧数
	 * @param stereo
	 *            是否将音频样本视为2声道（立体声）
	 *            调用此方法会分配一个全新的缓冲区，因此任何正在进行的播放
	 * @ webref采样： AudioSample
	 * @ webBrief将音频采样的基础缓冲区调整为给定的帧数。
	 */
	public void resize(int frames, boolean stereo) {
		this.stop();
		this.sample.allocate(frames, stereo ? 2 : 1);
		this.initiatePlayer(); // TODO 重新启动可能是多余的
	}

	/**
	 * （默认：false）
	 * 
	 * @ webref采样： AudioSample
	 * @ webBrief返回音频采样的基础采样率。
	 * 返回 audiosample 的底层采样率。
	 **/
	public int sampleRate() {
		return (int) Math.round(this.sample.getFrameRate());
	}

	/**
	 * @return 以 int 形式返回 audiosample 的底层采样率。 
	 * 请注意，仅单声道音频采样支持平移。
	 *
	 * @ webref采样： AudioSample
	 * @ webBrief在立体全景中平移声音文件。
	 * @param pos
	 *            此声音单元的全景位置从-1.0 （左）到1.0
	 *            对吧？
	 **/
	public void pan(float pos) {
		super.pan(pos);
	}

	/**
	 * 在立体声全景中声像 soundfile。-1.0 声像到左声道，1.0 到右声道。
	 * 
	 * @ webref采样： AudioSample
	 * @ webBrief一次设置多个参数。
	 * 由合成器开始执行此单元。输入端会从任何输出
	 *            要使用的相对播放速率。1是原始速度。0.5是
	 *            开始播放 audiosample。仅播放到 audiosample 末尾
	 *            为半速并降一个八度。2 为双倍速度并升一个八度。
	 * @param pos
	 *            此声音单元的全景位置从-1.0 （左）到1.0
	 *            对吧？
	 * 距上次播放命令经过的时间
	 *            （右）。仅适用于单声道 audiosample！
	 *            0.0 （完全静音）至1.0 （全音量）
	 **/
	public void set(float rate, float pos, float amp) {
		this.rate(rate);
		this.pan(pos);
		this.amp(amp);
	}

	/**

	 * @ nowebref
	 */
	public void set(float rate, float pos, float amp, float add) {
		this.set(rate, pos, amp);
		this.add(add);
	}

	/**
	 * 停止播放。
	 * 
	 * @ webref采样： AudioSample
	 * 一次性设置多个参数。
	 **/
	public void stop() {
		super.stop();
		this.player.dataQueue.clear();
		this.isPlaying = false;
	}

	public void cleanUp() {
		this.sample.cleanUp();
	}

	// @webBrief 停止播放。

	/**
	 * 新方法写在这里
	 * 
	 * 以秒为单位获取当前声音文件播放位置。
	 * 注意，若此音频采样曾在某时刻被并行播放
	 * 此函数返回的位置可以是任何并发播放的位置，
	 * 不一定是最后一个被触发的。
	 * 
	 * @return 音频样本播放的当前位置（以秒为单位）
	 * @ webref采样： AudioSample
	 * @ webBrief在几秒钟内获取当前声音文件播放位置。
	 */
	public float position() {
		return this.positionFrame() / (float) this.sampleRate();
	}

	/**
	 * 获取当前声音文件播放位置的帧索引。
	 * 
	 * 以秒为单位获取当前声音文件播放位置。
	 * （在原始播放结束前再次调用<b> play() </b>触发） ，
	 * 此函数返回的位置可以是任何并发播放的位置，
	 * 不一定是最后一个被触发的。
	 * 
	 * （在原始播放完成前由另一次 play() 调用触发），
	 * @ webref采样： AudioSample
	 * @return 音频采样播放的当前帧索引位置
	 */
	public int positionFrame() {
		return (int) (this.startFrame + this.player.dataQueue.getFrameCount() - this.startFrameCountOffset) % this.frames();
	}

	/**
	 * 以百分比为单位获取当前声音文件播放位置。
	 * 
	 * 以秒为单位获取当前声音文件播放位置。
	 * （在原始播放结束前再次调用<b> play() </b>触发） ，
	 * 此函数返回的位置可以是任何并发播放的位置，
	 * 不一定是最后一个被触发的。
	 * 
	 * @return 音频样本播放的当前位置，以百分比表示（一个值
	 *         0至100之间）。
	 * @ webref采样： AudioSample
	 * @ webBrief以百分比为单位获取当前声音文件播放位置。
	 */
	public float percent() {
		return 100f * this.positionFrame() / (float) this.frames();
	}

	/**
	 * 停止样本的播放，但将其提示到当前位置。
	 * @webBrief 获取当前声音文件播放位置的帧索引。
	 * 
	 * @see AudioSample#cue(float)
	 * @ webref采样： AudioSample
	 * 下次调用 <b>play()</b> 将从暂停处继续播放。
	 */
	public void pause() {
		if (this.isPlaying()) {
			this.stop();
			this.startFrame = this.positionFrame();
			this.setStartFrameCountOffset();
		} else {
			Engine.printWarning("trying to pause an audio sample that is not playing");
		}
	}

	protected boolean checkStartFrame(int startFrame) {
		return this.checkStartFrame(startFrame, true);
	}

	protected boolean checkStartFrame(int startFrame, boolean verbose) {
		if (startFrame < 0 || startFrame >= this.frames()) {
			if (verbose) {
				Engine.printError(startFrame + " is not a valid start frame number (has to be in [0,"
						+ (this.frames() - 1) + "]");
			}
			return false;
		} else {
			return true;
		}
	}

	/**
	 * @webBrief 停止采样播放，但将其提示到当前位置。
	 *
	 * 获取当前示例数据并将其写入给定数组。数组
	 * 必须能够存储尽可能多的浮点数，因为此示例中有帧，
	 * 或立体声样本的帧数的两倍。
	 *
	 * @param data
	 *            读取数据写入的目标数组
	 */
	public void read(float[] data) {
		if (this.channels() == 2 && data.length != 2 * this.frames()) {
			Engine.printWarning(
					"the length of the array passed to read(float[]) does not match the size of the data of this stereo audio sample (note that stereo samples contain two values per frame!)");
		} else if (this.channels() == 1 && data.length != this.frames()) {
			Engine.printWarning(
					"the length of the array passed to read(float[]) does not match the number of frames of this audio sample");
		}
		// 获取当前采样数据并写入给定数组。
		this.sample.read(data);
	}

	/**
	 * TODO 捕获异常并打印易懂的错误信息
	 * audiosample 的底层数据可以用多种不同方式读写： 
	 * 到给定的数组中。数组必须能够存储尽可能多的浮点数，因为有帧
	 * 接收单个 float 数组 `data` 的方法获取当前采样数据并写入
	 * 也可以使用具有四个参数的方法仅读取部分示例数据，
	 * 此采样中。 
	 * 它允许你指定要读取的首帧索引、数组中
	 * 最后，采用单个整数参数`index`的方法返回单个
	 * 此索引处样本的音频帧作为浮点数。
	 *
	 * @param startFrame
	 *            音频样本的第一帧的索引，该索引应
	 *            已讀
	 * @param startIndex
	 *            数组中第一个读帧应位于的位置
	 *            写入（通常为0 ）
	 * @param numFrames
	 *            应读取的帧数（不能大于
	 *            audiosample.channels() * data.length - startIndex)
	 * @ webref采样： AudioSample
	 * 写入的位置，以及总共要复制到数组中的帧数。
	 */
	public void read(int startFrame, float[] data, int startIndex, int numFrames) {
		if (this.checkStartFrame(startFrame)) {
			// TODO 检查并打印有关立体声外壳的信息性警告
			if (startFrame + numFrames < this.frames()) {
				this.sample.read(startFrame, data, startIndex, numFrames);
			} else {
				// @webBrief audiosample 的底层数据可以用多种不同方式读写。
				int firstReadLength = this.frames() - startFrame;
				this.sample.read(startFrame, data, startIndex, firstReadLength);
				this.sample.read(0, data, startIndex + firstReadLength, numFrames - firstReadLength);
			}
		}
	}

	/**
	 * @param frameIndex
	 *            音频样本的单个帧的索引，
	 *            采样末尾溢出，需做两次部分复制
	 *            读取并返回。`frameIndex` 必须在 0 到
	 *            对于mono文件， `read(frameIndex)`与`read(frameIndex, 0)`相同。
	 *            对于立体声文件，除非您还指定了`channelIndex` ，
	 *            `read(frameIndex)`将从左侧和
	 *            以交错顺序排列右通道。（请参阅Soundfile > StereoSample
	 *            演示的示例。）
	 * @return float ：给定索引处的音频样本值
	 */
	public float read(int frameIndex) {
		// 获取当前采样数据并写入给定数组。
		return (float) this.sample.readDouble(frameIndex);
	}

	/**
	 * @param channelIndex
	 *            从中提取帧值的通道（左边为0 ，
	 *            右侧为1 ）。`read(frameIndex, channelIndex)`与
	 *            调用`read(frameIndex * this.channels() + channelIndex)`。
         */
	public float read(int frameIndex, int channelIndex) {
		if (channelIndex < 0 || channelIndex >= this.channels()) {
			Engine.printWarning("trying to read() from audiosample channel #" + channelIndex + " which does not exist, returning left channel value instead");
			channelIndex = 0;
		}
		return this.read(frameIndex * this.channels() + channelIndex);
	}

	/**
	 * 用给定数组的数据覆盖样本。数组应
	 * `sample.frames() * sample.channels() - 1`（含）之间。
	 * 多达立体声样本的帧数。
	 * 
	 * @param data
	 *            样本数据来自的数组，最多
	 *            sample.frames() * sample.channels()浮点数，应复制
	 */
	public void write(float[] data) {
		if (this.channels() == 2 && data.length != 2 * this.frames()) {
			Engine.printWarning(
					"the length of the array passed to write(float[]) does not match the size of the data of this stereo audio sample (note that stereo samples contain two values per frame!)");
		} else if (this.channels() == 1 && data.length != this.frames()) {
			Engine.printWarning(
					"the length of the array passed to write(float[]) does not match the number of frames of this audio sample");
		}
		this.sample.write(data);
	}

	/**
	 * 包含的 float 数应与采样中的帧数相同，或两倍
	 * 获取单个浮点数组“data”的方法将示例数据替换为
	 * 给定数组。数组必须包含与此示例中帧数相同的浮点数。
	 * 也可以使用具有四个参数的方法仅写入部分示例数据，
	 * audiosample 的底层数据可以用多种不同方式读取和（覆）写： 
	 * 它允许你指定要写入的首帧索引、数组中
	 * 最后，接受两个参数的方法简单地设置单个音频帧的值
	 * 取数据的位置，以及应复制多少帧。
	 *
	 * @param startFrame
	 *            音频样本的第一帧的索引，该索引应
	 *            写给
	 * @param startIndex
	 *            数组中要写入的第一个值的位置
	 *            由第一个参数指定的给定 float 值。
	 * @param numFrames
	 *            取自（通常为 0）
	 *            audiosample.channels() * data.length - startIndex)
	 * @ webref采样： AudioSample
	 * @ webBrief音频样本的基础数据可以用几种不同的方式读取和（重写）。
	 */
	public void write(int startFrame, float[] data, int startIndex, int numFrames) {
		// 应写入的帧数（不能大于
		if (this.checkStartFrame(startFrame)) {
			if (startFrame + numFrames < this.frames()) {
				this.sample.write(startFrame, data, startIndex, numFrames);
			} else {
				// @webBrief audiosample 的底层数据可以用多种不同方式读写。
				int firstReadLength = this.frames() - startFrame;
				this.sample.write(startFrame, data, startIndex, firstReadLength);
				this.sample.write(0, data, startIndex + firstReadLength, numFrames - firstReadLength);
			}

		}
	}

	/**
	 * @param index
	 *            音频样本的单个帧的索引，
	 *            设置为给定值
	 * FIXME 检查立体声情形
	 *            给定音频帧应设置为的浮点值
	 */
	public void write(int index, float value) {
		if (this.channels() == 2) {
			Engine.printWarning(
					"write(int, float) only writes data to the left channel of a stereo file, please use one of the other write() methods to write data to all channels");
		}
		if (this.checkStartFrame(startFrame)) {
			this.sample.writeDouble(index, value);
		}
	}
}
