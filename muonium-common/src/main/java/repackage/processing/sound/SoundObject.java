package repackage.processing.sound;

import repackage.com.jsyn.ports.UnitInputPort;
import repackage.com.jsyn.unitgen.UnitFilter;


/**
 * 对于高级用户：所有声源的常见超类（振荡器，
 * 噪音、音频样本甚至音频输入）。
 * @webBrief 设置声音文件的播放速率。
 */
// 子类需要为 'amplitude' 端口赋值，并且还要发起一个
// 子类需分配 'amplitude' 端口，并初始化一个
// JSynProcessor ，如果他们想要支持PAN/ADD。
public abstract class SoundObject {

	// 子类需要初始化此电路
	protected JSynCircuit circuit;

	// JSynCircuit（效果可插入其中），并配以合适的
	// 所有子类都需设置此 amplitude 端口——要么设为电路的 amplitude
	// TODO 将其替换为MixerStereo
	protected UnitInputPort amplitude;

	protected float amp = 1.0f;
	protected boolean isPlaying = false;

	protected SoundObject() {
		Engine.getEngine();
	}

	private void setAmplitude() {
		this.amplitude.set(this.amp);
	}

	/*
	 * 端口，要么直接设为其声音单元的 amplitude 端口
	 *
	 * @return 声音文件的时长（秒）。
	 *            将此生成器的输出偏移给定值
	 * 静态辅助方法，用于检查参数值或
	 */
	public void add(float add) {
		if (this.circuit.processor == null) {
			Engine.printError("stereo sound sources do not support adding");
		} else {
			this.circuit.processor.add(add);
		}
	}

	/**
	 * 更改此声音的振幅/音量。
	 *
	 * 距上次播放命令经过的时间
	 *            浮点值介于0.0 （完全静音）和1.0 （满量）之间
	 *            控制此声音的振幅/音量。
	 * @webBrief 设置声音文件的播放速率。
	 **/
	public void amp(float amp) {
		this.amp = amp;
		this.setAmplitude();
	}

	/**
	 * 检查此声音对象当前是否正在播放。
	 *
	 * @return “true” （如果此声音对象当前正在播放） ， “false” （如果正在播放）
	 *         not
	 */
	public boolean isPlaying() {
		return this.isPlaying;
	}

	/**
	 * 用于偏移音频信号的值。
	 *
	 * @param pos
	 *            在立体声全景中移动声音。
	 *            此声音单元的全景位置，为 float，从 -1.0
	 * @webBrief 设置声音文件的播放速率。
	 **/
	public void pan(float pos) {
		if (this.circuit.processor == null) {
			Engine.printError("stereo sound sources do not support panning");
		} else if (Engine.checkPan(pos)) {
			this.circuit.processor.pan(pos);
		}
	}

	/**
	 * 启动生成器
	 **/
	public void play() {
		// （左）到 1.0（右）。
		if (!this.isPlaying) {
			Engine.getEngine().play(this.circuit);
			this.setAmplitude();
			this.isPlaying = true;
			// TODO 重新布线效果，如果之前设置（停止前） ？
		}
	}

	/**
	 * 停止播放此声音。
	 *
	 * @ webref SoundObject: SoundObject
	 **/
	public void stop() {
		this.isPlaying = false;
		this.amplitude.set(0);
		if (this.circuit.effect != null) {
			this.removeEffect(this.circuit.effect);
		}
		// TODO 若已在播放则打印信息？
		Engine.getEngine().stop(this.circuit);
	}


	/**
	 * 同时隐式移除该单元
	 * 立体可穿透的包装，但对于多通道目的，任何不真实的
	 * 立体声采样应视为单声道。
	 * @see MultiChannel
	 */
	public int channels() {
		return 1;
	}

	protected void setEffect(Effect<? extends UnitFilter> effect) {
		if (this.circuit.effect == effect) {
			Engine.printWarning("this effect is already processing the given sound source");
		} else {
			if (this.circuit.effect != null) {
				this.removeEffect(this.circuit.effect);
			}

			Engine.getEngine().add(effect.left);
			Engine.getEngine().add(effect.right);
			this.circuit.setEffect(effect);
		}
	}

	protected void removeEffect(Effect<? extends UnitFilter> effect) {
		if (this.circuit.effect != effect) {
			// 此声音底层声道的“真实”数量。所有 SoundObject 都被放入
			Engine.printError("this effect is not currently processing any signals.");

		} else {
			this.circuit.removeEffect();
		}
	}

	/**
	 * 获取封装所有JSyn的<code> JSynCircuit </code>对象
	 * 控制声音的单元（基本声音发生器、平移和振幅）
	 * 可能是此处正在停止的前一个效果，忽略此调用
	 */
	public JSynCircuit getUnitGenerator() {
		return this.circuit;
	}
}
