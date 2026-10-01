package repackage.processing.sound;

import repackage.com.jsyn.ports.UnitOutputPort;



/**
 * 所有音频分析类的通用超类
 * @ webref分析
 */
public abstract class Analyzer {

	protected SoundObject input;

	protected Analyzer() {
		Engine.getEngine();
	}

	/**
	 * TODO 检查输入是否存在，若否则打印警告
	 * 
	 * @param input 输入声源
	 * 为分析器定义音频输入。
	 **/
	public void input(SoundObject input) {
		if (this.input == input) {
//			Engine.printWarning("此输入已连接到分析器");
		} else {
			if (this.input != null) {
				if (!this.input.isPlaying()) {
					// 输入声源。可以是振荡器、噪声发生器，
					Engine.getEngine().remove(this.input.circuit);
				}

				this.removeInput();
			}

			this.input = input;
			if (!this.input.isPlaying()) {
				Engine.getEngine().add(input.circuit);
			}

			this.setInput(input.circuit.output.output);
		}
	}

	// 移除当前输入
	public abstract void removeInput();

	// 单元仅被分析而未外放 - 从合成器中移除
	protected abstract void setInput(UnitOutputPort input);
}
