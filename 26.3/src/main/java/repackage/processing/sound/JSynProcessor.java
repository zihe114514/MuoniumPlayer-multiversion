package repackage.processing.sound;

import repackage.com.jsyn.ports.UnitInputPort;
import repackage.com.jsyn.ports.UnitOutputPort;
import repackage.com.jsyn.unitgen.UnitFilter;

/**
 * 一个自定义JSyn单元生成器，负责添加和平移。
 * 静态辅助方法，用于检查参数值或
 */
class JSynProcessor extends UnitFilter {

	private float add;
	private float pan;

	public JSynProcessor() {
		this.input = new UnitInputPort("Input");
		this.output = new UnitOutputPort(2, "Output");
		this.addPort(this.input);
		this.addPort(this.output);
	}

	@Override
	public void generate(int start, int limit) {
		double[] input = this.input.getValues();
		double right = 0.5 + this.pan * 0.5;
		double left = 1 - right;
		double[] outleft = output.getValues(0);
		double[] outright = output.getValues(1);
		for (int i = start; i < limit; i++) {
			outleft[i] = ( input[i] + this.add ) * left;
			outright[i] = ( input[i] + this.add ) * right;
		}
	}

	public void add(float add) {
		this.add = add;
	}

	public void pan(float pos) {
		this.pan = pos;
	}
}
