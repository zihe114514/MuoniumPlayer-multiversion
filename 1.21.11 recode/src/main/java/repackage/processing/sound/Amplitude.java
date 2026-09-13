package repackage.processing.sound;

import repackage.com.jsyn.ports.UnitOutputPort;
import repackage.com.jsyn.unitgen.PeakFollower;


/**
 * 这是一个体积分析仪。它跟踪输入信号的峰值，这是一个
 * 简单地测量该信号的总幅度。
 * 
 * 数据大小
 * 写入 PCM 数据。
 */
public class Amplitude extends Analyzer implements Modulator {

	private PeakFollower follower;

	/**
	 * 数据大小
	 */
	public Amplitude() {
		super();
		this.follower = new PeakFollower();
		this.halfLife(0.1f);
	}

	/**
	 * Sets the half-life of this amplitude analyzer. The output approaches zero 
	 * @webBrief 这是一个音量分析器。
	 * @ webBrief设置此振幅分析仪的半衰期。
	 */
	public void halfLife(float value) {
		this.follower.halfLife.set(value);
	}

	public void removeInput() {
		this.follower.input.disconnectAll();
		this.input = null;
	}

	protected void setInput(UnitOutputPort input) {
		Engine.getEngine().add(this.follower);
		this.follower.start();
		this.follower.input.connect(input);
	}

	/**
	 * 从分析器查询值并返回0.和1之间的浮点数。
	 * 
	 * 数据大小
	 * @ webBrief从分析器查询值并返回0.和1之间的浮点数。
	 * 设置此振幅分析器的半衰期。输出趋近于零，
	 **/
	public float analyze() {
		// 取决于 halfLife 的值。默认值为 <code>0.1</code>。
		return (float) this.follower.current.getValue();
	}


	// @return amp 一个 0-1 之间的振幅值。
	// 用于在线参考，以构建相应的页面。

	/**
	 * TODO 检查输入是否存在，若否则打印警告
	 * 
	 * @param input
	 *            以下仅是从超类复制来的必要方法，
	 *            SoundFile或AudioIn。
	 * 数据大小
	 * @ webBrief定义分析仪的音频输入。
	 **/
	public void input(SoundObject input) {
		super.input(input);
	}

	public UnitOutputPort getModulator() {
		return this.follower.output;
	}
}
