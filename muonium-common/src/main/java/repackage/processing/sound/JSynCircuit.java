package repackage.processing.sound;

import repackage.com.jsyn.ports.UnitOutputPort;
import repackage.com.jsyn.unitgen.*;

/**
 * 辅助类包装源单元生成器，添加/平移处理器和效果
 * 整合成一个电路。
 */
public class JSynCircuit extends Circuit implements UnitSource {

	private UnitGenerator source;
	protected JSynProcessor processor;
	protected UnitOutputPort preEffect;
	protected Effect<? extends UnitFilter> effect;
	protected TwoInDualOut output;

	public JSynCircuit(UnitOutputPort input) {
		this.output = new TwoInDualOut();
		this.add(this.output);

		this.source = input.getUnitGenerator();
		this.add(this.source);

		if (input.getNumParts() == 2) {
			// 立体声源-无需平移，因此旁路处理器
			this.preEffect = input;
		} else {
			this.processor = new JSynProcessor();
			this.add(this.processor);
			this.processor.input.connect(input);
			this.preEffect = this.processor.output;
		}
		this.wireBypass();
	}

	protected void wireBypass() {
		this.preEffect.connect(0, this.output.inputA, 0);
		this.preEffect.connect(1, this.output.inputB, 0);
	}

	protected void removeEffect() {
		if (this.effect != null) {
			this.wireBypass();
			this.effect.left.output.disconnect(this.output.inputA);
			this.effect.right.output.disconnect(this.output.inputB);
			this.effect.left.input.disconnect(0, this.preEffect, 0);
			this.effect.right.input.disconnect(0, this.preEffect, 1);
			this.effect = null;
		}
	}

	protected void setEffect(Effect<? extends UnitFilter> effect) {
		this.effect = effect;
		this.preEffect.connect(0, this.effect.left.input, 0);
		this.preEffect.connect(1, this.effect.right.input, 0);

		this.effect.left.output.connect(this.output.inputA);
		this.preEffect.disconnect(0, this.output.inputA, 0);

		this.effect.right.output.connect(this.output.inputB);
		this.preEffect.disconnect(1, this.output.inputB, 0);
	}

	@Override
	public UnitOutputPort getOutput() {
		return this.output.output;
	}
}
